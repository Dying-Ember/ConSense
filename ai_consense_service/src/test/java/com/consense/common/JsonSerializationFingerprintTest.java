package com.consense.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JsonSerializationFingerprintTest {
    @Test void preservesTheExistingSerializerAndUtf8BytesIncludingUnpairedSurrogates() {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("z",Arrays.asList(-0.0,1e-5,new BigInteger("9223372036854775808"),null,true));
        value.put("中😀","\u0000\b\f\n\r\t\"\\中😀\ud800x\udc00");
        value.put("instant",Instant.parse("2026-10-22T00:00:00Z"));
        for(Object item:Arrays.asList(value,JsonUtils.mapper().nullNode(),JsonUtils.parse("{\"b\":1.0,\"a\":1}"),"literal"))
            assertEquals(Utf8.sha256(JsonUtils.write(item)),JsonUtils.serializedSha256(item));
        Map<String,Object> reversed=new LinkedHashMap<>();reversed.put("a",1);reversed.put("b",2);
        Map<String,Object> forward=new LinkedHashMap<>();forward.put("b",2);forward.put("a",1);
        assertNotEquals(JsonUtils.serializedSha256(forward),JsonUtils.serializedSha256(reversed),"Do not silently canonicalize serialization order");
        assertNotEquals(JsonUtils.serializedSha256(JsonUtils.parse("1")),JsonUtils.serializedSha256(JsonUtils.parse("1.0")));
    }

    @Test void hashingLargeJsonDoesNotAllocateAnotherWholeSerializedDocument() {
        java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;
        assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        char[] text=new char[1024*1024];Arrays.fill(text,'x');text[8191]='\ud83d';text[8192]='\ude00';
        JsonNode value=JsonUtils.mapper().createObjectNode().put("body",new String(text));
        String expected=Utf8.sha256(JsonUtils.write(value));JsonUtils.serializedSha256(value);
        long before=allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        String actual=JsonUtils.serializedSha256(value);
        long bytes=allocation.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertEquals(expected,actual);assertTrue(bytes<256*1024,"Shared JSON hashing allocated "+bytes+" bytes for a 1MiB scalar");
    }
}
