package com.atguigu.bigdata.tune;

import com.alibaba.fastjson.JSONObject;
import com.atguigu.bigdata.source.MockSourceFunction;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.util.Random;
import java.util.concurrent.TimeUnit;


public class SkewApp2 {
    public static void main(String[] args) throws Exception {
        
        
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.disableOperatorChaining();
        
        env.setStateBackend(new HashMapStateBackend());
        env.enableCheckpointing(TimeUnit.SECONDS.toMillis(3), CheckpointingMode.EXACTLY_ONCE);
        
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setCheckpointStorage("hdfs://hadoop162:8020/flink-tuning/ck");
        checkpointConfig.setMinPauseBetweenCheckpoints(TimeUnit.SECONDS.toMillis(3));
        checkpointConfig.setTolerableCheckpointFailureNumber(5);
        checkpointConfig.setCheckpointTimeout(TimeUnit.MINUTES.toMillis(1));
        
        
        SingleOutputStreamOperator<JSONObject> jsonobjDS = env
            .addSource(new MockSourceFunction())
            .map(JSONObject::parseObject);
        
        // ????????? ????????????,????????? (mid,1L)
        SingleOutputStreamOperator<Tuple2<String, Long>> pageMidTuple = jsonobjDS
            .filter(data -> StringUtils.isEmpty(data.getString("start")))
            .map(r -> Tuple2.of(r.getJSONObject("common").getString("mid"), 1L))
            .returns(Types.TUPLE(Types.STRING, Types.LONG));
        
        
        // ??????mid??????????????????10s,???mid???????????????
        ParameterTool parameterTool = ParameterTool.fromArgs(args);
        boolean isTwoPhase = parameterTool.getBoolean("two-phase", true);
        int randomNum = parameterTool.getInt("random-num", 5);
        
        if (!isTwoPhase) {
            pageMidTuple
                .keyBy(r -> r.f0)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
                .reduce((value1, value2) -> Tuple2.of(value1.f0, value1.f1 + value2.f1))
                .print().setParallelism(1);
        } else {
            // ?????????????????????????????????????????????????????????
            SingleOutputStreamOperator<Tuple3<String, Long, Long>> firstAgg = pageMidTuple
                .map(new MapFunction<Tuple2<String, Long>, Tuple2<String, Long>>() {
                    Random random = new Random();
                    
                    @Override
                    public Tuple2<String, Long> map(Tuple2<String, Long> value) throws Exception {
                        return Tuple2.of(value.f0 + "-" + random.nextInt(randomNum), 1L);
                    }
                }) // mid???????????????
                .keyBy(r -> r.f0) // ??????????????? "mid|?????????" ??????
                .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
                .reduce(
                    (value1, value2) -> Tuple2.of(value1.f0, value1.f1 + value2.f1),
                    new ProcessWindowFunction<Tuple2<String, Long>, Tuple3<String, Long, Long>, String, TimeWindow>() {
                        @Override
                        public void process(String s,
                                            Context context,
                                            Iterable<Tuple2<String, Long>> elements,
                                            Collector<Tuple3<String, Long, Long>> out) throws Exception {
                            Tuple2<String, Long> midAndCount = elements.iterator().next();
                            long windowEndTs = context.window().getEnd();
                            out.collect(Tuple3.of(midAndCount.f0, midAndCount.f1, windowEndTs));
                        }
                    }
                );// ???????????????????????????????????????????????????????????????????????????????????????????????????
            
            // ??????????????? key???windowEnd????????????????????????
            firstAgg
                .map(new MapFunction<Tuple3<String, Long, Long>, Tuple3<String, Long, Long>>() {
                    @Override
                    public Tuple3<String, Long, Long> map(Tuple3<String, Long, Long> value) throws Exception {
                        String originKey = value.f0.split("-")[0];
                        return Tuple3.of(originKey, value.f1, value.f2);
                    }
                }) // ?????? ??????????????????
                .keyBy(new KeySelector<Tuple3<String, Long, Long>, Tuple2<String, Long>>() {
                    @Override
                    public Tuple2<String, Long> getKey(Tuple3<String, Long, Long> value) throws Exception {
                        return Tuple2.of(value.f0, value.f2);
                    }
                }) // ?????? ????????? key??? ?????????????????? ??????
                .reduce((value1, value2) -> Tuple3.of(value1.f0, value1.f1 + value2.f1, value1.f2)) // ?????????????????????
                .print().setParallelism(1);
        }
        
        env.execute();
    }
}
