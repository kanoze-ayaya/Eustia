package org.eustia;
/*
 * @package: org.eustia
 * @program: test
 * @description
 *
 * @author:  rinne
 * @e-mail:  minami.rinne.me@gmail.com
 * @date: 2020/02/04 午前 12:17
 */

import org.ansj.domain.Result;
import org.ansj.domain.Term;
import org.ansj.splitWord.analysis.NlpAnalysis;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.util.serialization.JSONKeyValueDeserializationSchema;
import org.apache.flink.util.Collector;
import org.eustia.dao.WordCountConnect;
import org.eustia.model.SqlInfo;
import org.eustia.model.WordCountInfo;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @classname: WordCountStream
 * @description: %{description}
 * @author: rinne
 * @date: 2020/02/04 午前 12:17
 * @Version 1.0
 */

public class WordCountStream {
    public static void main(final String[] args) {
        final int hour = 1000 * 60 * 60;
        final int needCount = 10;
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        long day = timestamp.getTime() / (1000 * 60 * 60 * 24);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        Integer[] tableFlag = new Integer[]{0};
        String[] dayFormat = new String[]{simpleDateFormat.format(timestamp)};
        String[] tableName = new String[]{dayFormat[0] + "_" + tableFlag[0]};

        StreamExecutionEnvironment streamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment();
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", "localhost:9092");
        properties.setProperty("zookeeper.connect", "localhost:2181");
        properties.setProperty("group.id", "kafka.test");
        FlinkKafkaConsumer<ObjectNode> kafkaConsumer = new FlinkKafkaConsumer<>("kafka-test-topic",
                new JSONKeyValueDeserializationSchema(true), properties);
        kafkaConsumer.setStartFromGroupOffsets();
        DataStream<ObjectNode> wordStream = streamExecutionEnvironment.addSource(kafkaConsumer);
        wordStream.process(new ProcessFunction<ObjectNode, Tuple2<Integer, String>>() {
            @Override
            public void processElement(ObjectNode jsonNodes, Context context, Collector<Tuple2<Integer, String>> collector) {

                JsonNode value = jsonNodes.get("value");
                JsonNode bulletScreen = value.get("bullet_screen");

                for (JsonNode bulletInfo : bulletScreen) {
                    JsonNode bullet = bulletInfo.get("bullet");
                    Iterator<Map.Entry<String, JsonNode>> field = bullet.fields();

                    while (field.hasNext()) {
                        Map.Entry<String, JsonNode> message = field.next();
                        int times = Integer.parseInt(message.getKey()) / (24 * 60 * 60);
                        Result parse = NlpAnalysis.parse(message.getValue().toString().replaceAll("[\\pP\\pS\\pZ]", ""));

                        for (Term words : parse) {
                            String word = words.toString().split("/")[0];
                            if (word.length() <= 1) {
                                continue;
                            }
                            Tuple2<Integer, String> text = new Tuple2<>(times, word);
                            collector.collect(text);
                        }
                    }
                }

                JsonNode replies = value.get("replies");
                for (JsonNode repliesInfo : replies) {
                    JsonNode repliesMessage = repliesInfo.get("message");
                    int times = Integer.parseInt(repliesInfo.get("time").toString()) / (24 * 60 * 60);
                    Result parse = NlpAnalysis.parse(repliesMessage.toString().replaceAll("[\\pP\\pS\\pZ]", ""));

                    for (Term words : parse) {
                        String word = words.toString().split("/")[0];
                        if (word.length() <= 1) {
                            continue;
                        }
                        Tuple2<Integer, String> text = new Tuple2<>(times, word);
                        collector.collect(text);
                    }
                }
            }
        })
                .flatMap(new FlatMapFunction<Tuple2<Integer, String>, Tuple2<Tuple2<Integer, String>, Integer>>() {
                    @Override
                    public void flatMap(Tuple2<Integer, String> integerStringTuple2, Collector<Tuple2<Tuple2<Integer, String>, Integer>> collector) {
                        Tuple2<Tuple2<Integer, String>, Integer> tuple = new Tuple2<>(integerStringTuple2, 1);
                        collector.collect(tuple);
                    }
                })
                .keyBy(0)
                .timeWindow(Time.seconds(1))
                .sum(1)
                .process(new ProcessFunction<Tuple2<Tuple2<Integer, String>, Integer>, Tuple2<Tuple2<Integer, String>, Integer>>() {
                    @Override
                    public void processElement(Tuple2<Tuple2<Integer, String>, Integer> value, Context ctx,
                                               Collector<Tuple2<Tuple2<Integer, String>, Integer>> out) {
                        if ((int) value.getField(1) >= needCount) {
                            out.collect(value);
                        }
                    }
                })
                .addSink(new RichSinkFunction<Tuple2<Tuple2<Integer, String>, Integer>>() {
                    @Override
                    public void invoke(Tuple2<Tuple2<Integer, String>, Integer> value, Context context) throws Exception {
                        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis());
                        WordCountConnect wordCountConnect = new WordCountConnect();
                        WordCountInfo wordCountInfo = new WordCountInfo();
                        SqlInfo<WordCountInfo> sqlInfo = new SqlInfo<>();
                        Tuple2<Integer, String> key = value.getField(0);

                        if (nowTimestamp.getTime() - timestamp.getTime() > hour || tableFlag[0] == 0) {
                            long nowDay = nowTimestamp.getTime() / (1000 * 60 * 60 * 24);
                            if (nowDay > day) {
                                tableFlag[0] = 0;
                                dayFormat[0] = simpleDateFormat.format(nowTimestamp);
                            } else {
                                tableFlag[0] = tableFlag[0] + 1;
                            }
                            tableName[0] = dayFormat[0] + "_" +tableFlag[0];
                            sqlInfo.setTable(tableName[0]);
                            wordCountConnect.createTable(sqlInfo);
                        }

                        wordCountInfo.setTimeStamp(key.getField(0));
                        wordCountInfo.setWord(key.getField(1));
                        wordCountInfo.setCount(value.getField(1));

                        sqlInfo.setTable(tableName[0]);
                        sqlInfo.setModel(wordCountInfo);
                        wordCountConnect.insertDuplicateUpdateData(sqlInfo);
                    }
                });

        try {
            streamExecutionEnvironment.execute("WordCount");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
