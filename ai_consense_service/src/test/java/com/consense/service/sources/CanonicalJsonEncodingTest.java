package com.consense.service.sources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalJsonEncodingTest {
    @Test void exactCapacityAssemblyKeepsEscapesKeyOrderAndNumberTokens()throws Exception {
        String value="\"\\\b\f\n\r\t\u0000\u001f\u4e2d\ud83d\ude00\ud800";
        String quoted="\"\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f\u4e2d\ud83d\ude00\ud800\"";
        assertEquals(quoted,CanonicalJson.scalar(value));
        assertEquals("["+quoted+",null,1e-05,-0.0]",CanonicalJson.rawArray(Arrays.asList(quoted,null,"1e-05","-0.0")));
        Map<String,String> fields=new LinkedHashMap<>();fields.put("\ud83d\ude00","1e-05");fields.put("\ue000","-0.0");fields.put("a",quoted);
        String expected="{\"a\":"+quoted+",\"\ue000\":-0.0,\"\ud83d\ude00\":1e-05}";
        assertEquals(expected,CanonicalJson.rawObject(fields));
        assertEquals(sha(expected),CanonicalJson.hash(expected,null));
    }

    @Test void streamingCanonicalHashStillOmitsOnlyTheRootField()throws Exception {
        String raw="{\"seal\":0,\"nested\":{\"seal\":1e-05},\"text\":\"\u4e2d\ud83d\ude00\"}";
        assertEquals(sha("{\"nested\":{\"seal\":1e-05},\"text\":\"\u4e2d\ud83d\ude00\"}"),CanonicalJson.hash(raw,"seal"));
        assertEquals(sha("{\"seal\":1e-05}"),CanonicalJson.fieldHash(raw,"nested"));
    }

    private static String sha(String value)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(String.format("%02x",b&255));return hex.toString();
    }
}
