package org.eustia.wordcount;
/*
 * @package: org.eustia.wordcount
 * @program: test
 * @description
 *
 * @author:  rinne
 * @e-mail:  minami.rinne.me@gmail.com
 * @date: 2020/02/04 午前 12:17
 */


import com.mongodb.MongoException;
import org.ansj.domain.Result;
import org.ansj.domain.Term;
import org.ansj.splitWord.analysis.NlpAnalysis;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
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
import org.eustia.common.time.TimeCheckpoint;
import org.eustia.wordcount.dao.BasicDataMongodbConnect;
import org.eustia.wordcount.dao.EmotionalAnalysisConnect;
import org.eustia.wordcount.dao.WordCountConnect;
import org.eustia.common.model.MongodbSqlInfo;
import org.eustia.common.model.SqlInfo;
import org.eustia.wordcount.model.BasicDataInfo;
import org.eustia.wordcount.model.EmotionalAnalysisInfo;
import org.eustia.wordcount.model.WordCountInfo;

import java.io.*;
import java.util.*;

/**
 * @classname: WordCountStream
 * @description: %{description}
 * @author: rinne
 * @date: 2020/02/04 午前 12:17
 * @Version 1.0
 */

public class WordCountStream {
    static TreeSet<String> negativeWord = new TreeSet<>();
    static TreeSet<String> positiveWord = new TreeSet<>();

    static {
        File negativeWordFile = new File(WordCountStream.class.getResource("/classes/ntusd-negative.txt").getFile());
        File positiveWordFile = new File(WordCountStream.class.getResource("/classes/ntusd-positive.txt").getFile());

        try (FileReader fileReader = new FileReader(negativeWordFile)) {
            try (BufferedReader bufferedReader = new BufferedReader(fileReader)) {
                String word;
                while ((word = bufferedReader.readLine()) != null) {
                    negativeWord.add(word);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (FileReader fileReader = new FileReader(positiveWordFile)) {
            try (BufferedReader bufferedReader = new BufferedReader(fileReader)) {
                String word;
                while ((word = bufferedReader.readLine()) != null) {
                    positiveWord.add(word);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(final String[] args) {
        final int needCount = 10;
        StreamExecutionEnvironment streamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment();
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", "localhost:9092");
        properties.setProperty("zookeeper.connect", "localhost:2181");
        properties.setProperty("group.id", "WordCount.stream");
        FlinkKafkaConsumer<ObjectNode> kafkaConsumer = new FlinkKafkaConsumer<>("Word_Count",
                new JSONKeyValueDeserializationSchema(true), properties);
        kafkaConsumer.setStartFromGroupOffsets();
        DataStream<ObjectNode> wordStream = streamExecutionEnvironment.addSource(kafkaConsumer);

        // 原数据提交到MongoDB
        wordStream.addSink(new RichSinkFunction<ObjectNode>() {
            BasicDataMongodbConnect basicDataMongodbConnect;
            @Override
            public void open(Configuration parameters) throws Exception {
                basicDataMongodbConnect = new BasicDataMongodbConnect();
            }

            @Override
            public void invoke(ObjectNode value, Context context) throws Exception {
                BasicDataInfo basicDataInfo = new BasicDataInfo();
                MongodbSqlInfo<BasicDataInfo, Object> mongodbSqlInfo = new MongodbSqlInfo<>();
                basicDataInfo.setDate(value);
                mongodbSqlInfo.setModel(basicDataInfo);
                try {
                    basicDataMongodbConnect.insertData(mongodbSqlInfo);
                } catch (MongoException e) {
                    System.out.println(e);
                    basicDataMongodbConnect.updateData(mongodbSqlInfo);
                }
            }
        });

        // 热词统计
        wordStream.process(new ProcessFunction<ObjectNode, Tuple2<Long, String>>() {
            @Override
            public void processElement(ObjectNode jsonNodes, Context context, Collector<Tuple2<Long, String>> collector) {

                JsonNode value = jsonNodes.get("value");
                JsonNode bulletScreen = value.get("bullet_screen");

                for (JsonNode bulletInfo : bulletScreen) {
                    JsonNode bullet = bulletInfo.get("bullet");
                    Iterator<Map.Entry<String, JsonNode>> field = bullet.fields();

                    while (field.hasNext()) {
                        Map.Entry<String, JsonNode> message = field.next();
                        Iterator<Map.Entry<String, JsonNode>> messageField = message.getValue().fields();
                        while (messageField.hasNext()) {
                            Map.Entry<String, JsonNode> messageInfo = messageField.next();
                            long times = (Long.parseLong(messageInfo.getKey()) / (5 * 60)) * (5 * 60 * 1000);
                            String text = messageInfo.getValue().toString();
                            if (text.charAt(1) == '[' && text.charAt(text.length() - 2) == ']') {
                                continue;
                            }
                            Result parse = NlpAnalysis.parse(messageInfo.getValue().toString().replaceAll("[\\pP\\pS\\pZ]", ""));
                            for (Term words : parse) {
                                String word = words.toString().split("/")[0];
                                if (word.length() <= 1) {
                                    continue;
                                }
                                Tuple2<Long, String> wordTuple = new Tuple2<>(times, word);
                                collector.collect(wordTuple);
                            }
                        }
                    }
                }

                // 评论区
                JsonNode replies = value.get("replies");
                for (JsonNode repliesInfo : replies) {
                    JsonNode repliesMessage = repliesInfo.get("message");
                    long times = (Long.parseLong(repliesInfo.get("time").toString()) / (24 * 60 * 60)) * (24 * 60 * 60 * 1000);
                    Result parse = NlpAnalysis.parse(repliesMessage.toString().replaceAll("[\\pP\\pS\\pZ]", ""));

                    for (Term words : parse) {
                        String word = words.toString().split("/")[0];
                        if (word.length() <= 1) {
                            continue;
                        }
                        Tuple2<Long, String> text = new Tuple2<>(times, word);
                        collector.collect(text);
                    }
                }
            }
        })
                .flatMap(new FlatMapFunction<Tuple2<Long, String>, Tuple2<Tuple2<Long, String>, Integer>>() {
                    @Override
                    public void flatMap(Tuple2<Long, String> integerStringTuple2, Collector<Tuple2<Tuple2<Long, String>, Integer>> collector) {
                        Tuple2<Tuple2<Long, String>, Integer> tuple = new Tuple2<>(integerStringTuple2, 1);
                        collector.collect(tuple);
                    }
                })
                .keyBy(0)
                .timeWindow(Time.seconds(300))
                .sum(1)
                .process(new ProcessFunction<Tuple2<Tuple2<Long, String>, Integer>, Tuple2<Tuple2<Long, String>, Integer>>() {

                    @Override
                    public void processElement(Tuple2<Tuple2<Long, String>, Integer> value, Context ctx,
                                               Collector<Tuple2<Tuple2<Long, String>, Integer>> out) {
                        if ((int) value.getField(1) >= needCount) {
                            out.collect(value);
                        }
                    }
                })
                .addSink(new RichSinkFunction<Tuple2<Tuple2<Long, String>, Integer>>() {
                    private TimeCheckpoint timeCheckpoint;

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        super.open(parameters);
                        this.timeCheckpoint = new TimeCheckpoint();
                    }

                    @Override
                    public void invoke(Tuple2<Tuple2<Long, String>, Integer> value, Context context) throws Exception {
                        WordCountConnect wordCountConnect = new WordCountConnect();
                        WordCountInfo wordCountInfo = new WordCountInfo();
                        SqlInfo<WordCountInfo> sqlInfo = new SqlInfo<>();
                        Tuple2<Long, String> key = value.getField(0);

                        wordCountInfo.setTimeStamp(key.getField(0));
                        wordCountInfo.setWord(key.getField(1));
                        wordCountInfo.setCount(value.getField(1));

                        if (this.timeCheckpoint.isDay()) {
                            sqlInfo.setTable(this.timeCheckpoint.getTimeFormat());
                            try {
                                wordCountConnect.createTable(sqlInfo);
                            } catch (Exception e) {
                                System.out.println(e);
                            }
                        }

                        sqlInfo.setTable(this.timeCheckpoint.getTimeFormat());
                        sqlInfo.setModel(wordCountInfo);
                        wordCountConnect.insertDuplicateUpdateData(sqlInfo);
                    }
                });
        
        wordStream.process(new ProcessFunction<ObjectNode, String>() {

            @Override
            public void processElement(ObjectNode jsonNodes, Context context, Collector<String> out) throws Exception {
                JsonNode value = jsonNodes.get("value");
                JsonNode bulletScreen = value.get("bullet_screen");
                String avNumber = value.get("av").toString();

                for (JsonNode bulletInfo : bulletScreen) {
                    JsonNode bullet = bulletInfo.get("bullet");
                    Iterator<Map.Entry<String, JsonNode>> field = bullet.fields();

                    while (field.hasNext()) {
                        Map.Entry<String, JsonNode> message = field.next();
                        Iterator<Map.Entry<String, JsonNode>> messageField = message.getValue().fields();

                        while (messageField.hasNext()) {
                            Map.Entry<String, JsonNode> messageInfo = messageField.next();
                            String text = messageInfo.getValue().toString();
                            long times = (Long.parseLong(messageInfo.getKey()) / (5 * 60)) * (5 * 60 * 1000);

                            if (text.charAt(1) == '[' && text.charAt(text.length() - 2) == ']') {
                                continue;
                            }

                            Result parse = NlpAnalysis.parse(messageInfo.getValue().toString().replaceAll("[\\pP\\pS\\pZ]", ""));

                            for (Term words : parse) {
                                String word = words.toString().split("/")[0];
                                String info = times + "," + avNumber + "," + emotionalCheckpoint(word);

                                out.collect(info);
                            }
                        }
                    }
                }

                JsonNode replies = value.get("replies");
                for (JsonNode repliesInfo : replies) {
                    JsonNode repliesMessage = repliesInfo.get("message");
                    long times = (Long.parseLong(repliesInfo.get("time").toString()) / (24 * 60 * 60)) * (24 * 60 * 60 * 1000);
                    Result parse = NlpAnalysis.parse(repliesMessage.toString().replaceAll("[\\pP\\pS\\pZ]", ""));

                    for (Term words : parse) {
                        String word = words.toString().split("/")[0];
                        String info = times + "," + avNumber + "," + emotionalCheckpoint(word);

                        out.collect(info);
                    }
                }
            }

            private String emotionalCheckpoint(String word) {
                if (negativeWord.contains(word)) {
                    return "negative";
                } else if (positiveWord.contains(word)) {
                    return "positive";
                } else {
                    System.out.println(word);
                    return "unknown";
                }
            }
        })
                .flatMap(new FlatMapFunction<String, Tuple2<String, Integer>>() {

                    @Override
                    public void flatMap(String value, Collector<Tuple2<String, Integer>> out) throws Exception {
                        out.collect(new Tuple2<>(value, 1));
                    }
                })
                .keyBy(0)
                .timeWindow(Time.seconds(300))
                .sum(1)
                .addSink(new RichSinkFunction<Tuple2<String, Integer>>() {
                    private TimeCheckpoint timeCheckpoint;

                    @Override
                    public void open(Configuration parameters) throws Exception {
                        super.open(parameters);
                        this.timeCheckpoint = new TimeCheckpoint();
                    }

                    @Override
                    public void invoke(Tuple2<String, Integer> value, Context context) throws Exception {
                        EmotionalAnalysisConnect emotionalAnalysisConnect = new EmotionalAnalysisConnect();
                        EmotionalAnalysisInfo emotionalAnalysisInfo = new EmotionalAnalysisInfo();
                        SqlInfo<EmotionalAnalysisInfo> sqlInfo = new SqlInfo<>();
                        String key = value.getField(0);

                        emotionalAnalysisInfo.setTime(Long.parseLong(key.split(",")[0]));
                        emotionalAnalysisInfo.setAvNumber(key.split(",")[1].replaceAll("[\\pP\\pS\\pZ]", ""));
                        emotionalAnalysisInfo.setEmotional(key.split(",")[2]);
                        emotionalAnalysisInfo.setCount(value.getField(1));
                        if (this.timeCheckpoint.isDay()) {
                            sqlInfo.setTable(this.timeCheckpoint.getTimeFormat());
                            try {
                                emotionalAnalysisConnect.createTable(sqlInfo);
                            } catch (Exception e) {
                                System.out.println(e);
                            }
                        }

                        sqlInfo.setTable(this.timeCheckpoint.getTimeFormat());
                        sqlInfo.setModel(emotionalAnalysisInfo);
                        emotionalAnalysisConnect.insertDuplicateUpdateData(sqlInfo);
                    }
                });

        try {
            streamExecutionEnvironment.execute("WordCount");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}