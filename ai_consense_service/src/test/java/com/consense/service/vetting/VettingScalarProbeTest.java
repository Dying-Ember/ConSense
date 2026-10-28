package com.consense.service.vetting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.consense.config.ConsenseProperties;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class VettingScalarProbeTest {
    @Test void schemaSummaryKeepsOnlyBoundedTrustedPathsAndFixedCodes(){
        Logger logger=(Logger)LoggerFactory.getLogger(VettingReviewProbe.class);ListAppender<ILoggingEvent> trace=new ListAppender<>();trace.start();logger.addAppender(trace);
        try{
            VettingReviewProbe.Call call=VettingReviewProbe.start(new ConsenseProperties(),"run-fixture","project-fixture").call(22,null,null);
            call.summary("schema_failure",VettingReviewProbe.map("directionIndex",21,"attemptOrdinal",2,"purpose","evidence_patch","schemaPath","$[0].evidence[0].quote","constraint","maxLength","expectedType","string","actualType","STRING","actualLength",1201));
            String valid=trace.list.get(trace.list.size()-1).getFormattedMessage();assertTrue(valid.contains("directionIndex=21"));assertTrue(valid.contains("attemptOrdinal=2"));assertTrue(valid.contains("purpose=evidence_patch"));assertTrue(valid.contains("schemaPath=$[0].evidence[0].quote"));
            call.summary("schema_failure",VettingReviewProbe.map("schemaPath","$[0].PRIVATE_PATH_SENTINEL\nsecret","purpose","PRIVATE_VALUE_SENTINEL with spaces","raw","PRIVATE_BODY_SENTINEL"));
            String invalid=trace.list.get(trace.list.size()-1).getFormattedMessage();assertFalse(invalid.contains("PRIVATE_"));assertTrue(invalid.length()<1000);
        }finally{logger.detachAppender(trace);}
    }
    @Test void disabledFileProbesStillDescribeStagesWithoutReadingBodiesOrSuppliers(){
        Logger logger=(Logger)LoggerFactory.getLogger(VettingReviewProbe.class);ListAppender<ILoggingEvent> trace=new ListAppender<>();trace.start();logger.addAppender(trace);
        try {
            Object body=new Object(){@Override public String toString(){throw new AssertionError("Body must never be serialized");}};
            ConsenseProperties props=new ConsenseProperties();VettingReviewProbe review=VettingReviewProbe.start(props,"run-fixture","project-fixture");
            VettingReviewProbe.Call call=review.call(3,"SOURCE_SENTINEL","tender");
            try(VettingReviewProbe.Timer ignored=call.measure("schema_check","MODEL_SENTINEL")){call.event("raw",body);call.text("raw",body,"REASONING_SENTINEL");call.summary("attempt_complete",Map.of("attempt",2,"records",3,"status","accepted","rawBody",body));}
            review.finish("completed");
            AtomicInteger evaluated=new AtomicInteger();VettingServiceProbe service=VettingServiceProbe.start(props,"run-fixture","project-fixture","fixture");
            service.observe("ignored",()->{evaluated.incrementAndGet();return body;});service.elapsed("persist",System.nanoTime(),0,"SOURCE_SENTINEL",true);service.finish("completed",null);
            byte[] bytes={1,2,3};VettingExportProbe export=VettingExportProbe.start(props,"project-fixture","json","en");export.bindRun("run-fixture");
            try(VettingExportProbe.Timer ignored=export.measure("serialization","SOURCE_SENTINEL")){export.returned(bytes);}export.finish("returned_normally");
            assertArrayEquals(new byte[]{1,2,3},bytes);assertFalse(review.enabled());assertFalse(service.enabled());assertFalse(export.enabled());assertEquals(0,evaluated.get());
            String logs=messages(trace);assertTrue(logs.contains("direction=3"));assertTrue(logs.contains("call=run-fixture/3"));assertTrue(logs.contains("attempt=2"));assertTrue(logs.contains("status=started"));assertTrue(logs.contains("wallNanos="));assertTrue(logs.contains("stage=persist"));assertTrue(logs.contains("stage=export_bytes_ready"));
            for(String secret:List.of("SOURCE_SENTINEL","MODEL_SENTINEL","REASONING_SENTINEL"))assertFalse(logs.contains(secret));
        }finally{logger.detachAppender(trace);}
    }
    @Test void summaryDropsObjectsAndArbitraryStringsAndBoundsAllFields(){
        Logger logger=(Logger)LoggerFactory.getLogger(VettingReviewProbe.class);ListAppender<ILoggingEvent> trace=new ListAppender<>();trace.start();logger.addAppender(trace);
        try{
            Map<String,Object> values=new LinkedHashMap<>();values.put("queryFingerprint","0123456789abcdef");values.put("sessionFingerprint","invalid secret hash");values.put("reason","unsafe body\n".repeat(1000));values.put("apiKey","KEY_SENTINEL");values.put("body",List.of("SOURCE_SENTINEL"));values.put("notFinite",Double.NaN);for(int i=0;i<80;i++)values.put("count"+i,i);
            VettingReviewProbe.start(new ConsenseProperties(),"run-fixture","project-fixture").call(1,null,null).summary("bounded",values);
            String row=trace.list.stream().map(ILoggingEvent::getFormattedMessage).filter(message->message.contains("stage=bounded ")).findFirst().orElseThrow();assertTrue(row.contains("queryFingerprint=0123456789abcdef"));assertTrue(row.contains("reason=unknown_code"));assertFalse(row.contains("KEY_SENTINEL"));assertFalse(row.contains("SOURCE_SENTINEL"));assertFalse(row.contains("unsafe body"));assertFalse(row.contains("sessionFingerprint="));assertFalse(row.contains("count30="));assertTrue(row.length()<2000);
        }finally{logger.detachAppender(trace);}
    }
    @Test void faultySummaryInputAndLoggerCannotChangeTheCallerResult(){
        VettingReviewProbe.Call call=VettingReviewProbe.start(new ConsenseProperties(),"run-fixture","project-fixture").call(1,null,null);
        Map<String,Object> hostile=new AbstractMap<String,Object>(){@Override public Set<Entry<String,Object>> entrySet(){throw new AssertionError("Observer-only failure");}};
        assertDoesNotThrow(()->call.summary("ignored",hostile));
        Logger logger=(Logger)LoggerFactory.getLogger(VettingReviewProbe.class);ch.qos.logback.core.AppenderBase<ILoggingEvent> broken=new ch.qos.logback.core.AppenderBase<ILoggingEvent>(){@Override protected void append(ILoggingEvent event){throw new AssertionError("Observer sink failed");}};broken.start();logger.addAppender(broken);
        try{assertDoesNotThrow(()->{try(VettingReviewProbe.Timer ignored=call.measure("business_step","unchanged")){assertEquals(4,2+2);}});}finally{logger.detachAppender(broken);}
    }
    private static String messages(ListAppender<ILoggingEvent> trace){StringBuilder out=new StringBuilder();for(ILoggingEvent event:trace.list)out.append(event.getFormattedMessage()).append('\n');return out.toString();}
}
