package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Receipt extraction keeps complete syntax checks without constructing the complete receipt tree. */
class NormalObservedReceiptStreamingTest {
    @Test void exactUtf8ObjectSpanPreservesOrderEscapesNumbersAndNestedBoundaries(){
        String projection="{ \"z\": [{\"end\":\"} ], \\\"projection\\\": {}\"}], \"a\":1.00e+2,\"n\":-0.0,\"text\":\"\\u754c/\\ud83d\\ude00\" }";
        String wire=" \n{\"ignored\":{\"projection\":false},\"chunks\":2,\"projection\" : "+projection+",\"projectionSha256\":\"seal\",\"immutable\":true,\"qualityVerifiedByRegistry\":false,\"sourceQualityChangedByRegistry\":false,\"assets\":[{\"x\":1}]}\t\r";
        CanonicalJson.NormalReceipt actual=receipt(wire);
        assertEquals(projection,actual.projection);
        assertEquals(CanonicalJson.hash(CanonicalJson.field(wire,"projection"),null),CanonicalJson.hash(actual.projection,null));
        assertEquals(JsonUtils.parse("{\"chunks\":2,\"projectionSha256\":\"seal\",\"immutable\":true,\"qualityVerifiedByRegistry\":false,\"sourceQualityChangedByRegistry\":false}"),actual.header);
        assertFalse(actual.header.has("projection"));assertFalse(actual.header.has("ignored"));assertFalse(actual.header.has("assets"));
    }

    @Test void unorderedProjectionRetainsTheActualFrozenSealManifestCountAndContract()throws Exception{
        String canonical=NormalObservedContractTest.resource("authored-projection.json");
        Map<String,String> fields=CanonicalJson.rawFields(canonical);List<String> names=new ArrayList<>(fields.keySet());Collections.reverse(names);
        StringBuilder wireOrder=new StringBuilder("{");
        for(String name:names){if(wireOrder.length()>1)wireOrder.append(',');wireOrder.append(CanonicalJson.scalar(name)).append(':').append(fields.get(name));}
        wireOrder.append('}');String unsorted=wireOrder.toString();assertNotEquals(canonical,unsorted);
        CanonicalJson.NormalReceipt actual=receipt("{\"projection\":"+unsorted+",\"chunks\":2}");
        assertEquals(unsorted,actual.projection);assertEquals(CanonicalJson.hash(canonical,"projectionSha256"),CanonicalJson.hash(actual.projection,"projectionSha256"));
        JsonNode expected=JsonUtils.parse(canonical);
        assertEquals(expected,NormalObservedContract.validate(actual.projection,expected.path("projectionSha256").asText()));
    }

    @Test void duplicateMalformedMissingTrailingAndForeignEncodingInputsRejectThroughout(){
        for(String invalid:Arrays.asList(
            "{}","[]","{\"projection\":null}","{\"projection\":[]}","{\"projection\":{}}{}",
            "{\"projection\":{},\"projection\":{}}","{\"projection\":{\"a\":0,\"a\":1}}",
            "{\"projection\":{},\"ignored\":{\"a\":0,\"a\":1}}","{\"projection\":{},\"ignored\":[1,]}",
            "{\"projection\":{},\"ignored\":\"\\u00xx\"}","{\"projection\":{\"x\":\"\\u00xx\"}}",
            "{\"projection\":{},\"chunks\":1,\"chunks\":2}","{\"projection\":{},\"immutable\":[{\"x\":0,\"x\":1}]}",
            "{\"projection\":{},\"ignored\":", "\ufeff{\"projection\":{}}"))assertInvalid(invalid.getBytes(StandardCharsets.UTF_8));
        for(java.nio.charset.Charset charset:Arrays.asList(StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE,StandardCharsets.UTF_16))
            assertInvalid("{\"projection\":{}}".getBytes(charset));
        byte[] prefix=("{\"projection\":{},\"ignored\":\""+"A".repeat(16384)).getBytes(StandardCharsets.UTF_8);
        byte[] invalid=Arrays.copyOf(prefix,prefix.length+3);invalid[prefix.length]=(byte)0xff;invalid[prefix.length+1]='"';invalid[prefix.length+2]='}';assertInvalid(invalid);
    }

    @Test void largeSkippedScalarAndProjectionAvoidWholeReceiptCopies(){
        java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        byte[] wire=("{\"projection\":{\"text\":\""+"A".repeat(8*1024*1024)+"\"},\"ignored\":\""+"B".repeat(8*1024*1024)+"\",\"chunks\":1}").getBytes(StandardCharsets.UTF_8);
        receipt("{\"projection\":{},\"chunks\":1}");long thread=Thread.currentThread().getId(),before=allocation.getThreadAllocatedBytes(thread);
        CanonicalJson.NormalReceipt actual=CanonicalJson.normalObservedReceipt(wire);long bytes=allocation.getThreadAllocatedBytes(thread)-before;
        assertEquals(8*1024*1024+11,actual.projection.length());assertEquals(1,actual.header.path("chunks").asInt());
        assertTrue(bytes<wire.length+2*1024*1024L,"Receipt extraction allocated "+bytes+" bytes for "+wire.length+" wire bytes");
    }
    private static CanonicalJson.NormalReceipt receipt(String wire){return CanonicalJson.normalObservedReceipt(wire.getBytes(StandardCharsets.UTF_8));}
    private static void assertInvalid(byte[] wire){assertEquals("CANONICAL_JSON_INVALID",assertThrows(CanonicalEvidenceException.class,()->CanonicalJson.normalObservedReceipt(wire)).getMessage());}
}
