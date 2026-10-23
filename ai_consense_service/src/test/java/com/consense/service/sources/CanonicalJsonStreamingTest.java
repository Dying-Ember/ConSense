package com.consense.service.sources;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CanonicalJsonStreamingTest {
    @Test void digestKeepsWireNumbersUnicodeEscapesAndCodepointOrder()throws Exception {
        String raw="{\"\\ud83d\\ude00\":1e-05,\"z\":[-0.0,-0,1.00e+2,true,false,null],\"\\ue000\":\"\\u0000\\b\\f\\n\\r\\t\\\"\\\\/中😀\\ud800\"}";
        String canonical="{\"z\":[-0.0,-0,1.00e+2,true,false,null],\"\ue000\":\"\\u0000\\b\\f\\n\\r\\t\\\"\\\\/中😀\ud800\",\"😀\":1e-05}";
        assertEquals(sha(canonical),CanonicalJson.hash(raw,null));
        assertEquals("[-0.0,-0,1.00e+2,true,false,null]",CanonicalJson.field(raw,"z"));
        assertEquals(sha("[-0.0,-0,1.00e+2,true,false,null]"),CanonicalJson.fieldHash(raw,"z"));
        assertEquals("null",CanonicalJson.field("{\"value\":null}","value"));
    }

    @Test void utf8DigestPreservesSurrogatePairsAcrossWriterBufferBoundaries()throws Exception {
        char[] prefix=new char[8182];Arrays.fill(prefix,'a');
        String canonical="{\"text\":\""+new String(prefix)+"😀\ud800x\udc00\"}";
        assertEquals(sha(canonical),CanonicalJson.hash(canonical,null));
        assertEquals(sha("\""+new String(prefix)+"😀\ud800x\udc00\""),CanonicalJson.fieldHash(canonical,"text"));
    }

    @Test void skippedFieldsAndOmittedRootFieldsStillValidateEveryNestedToken() {
        for(String invalid:Arrays.asList(
                "{\"value\":1,\"other\":{\"a\":0,\"a\":1}}",
                "{\"value\":1,\"other\":[1,]}",
                "{\"value\":1,\"other\":\"\\u00xx\"}",
                "{\"value\":1,\"other\":true}{}",
                "{\"other\":0}","[1]")) {
            assertInvalid(()->CanonicalJson.field(invalid,"value"));
            assertInvalid(()->CanonicalJson.fieldHash(invalid,"value"));
        }
        assertInvalid(()->CanonicalJson.hash("{\"omit\":{\"a\":0,\"a\":1},\"kept\":2}","omit"));
        assertInvalid(()->CanonicalJson.hash("{\"omit\":\"\\u00xx\",\"kept\":2}","omit"));
    }

    @Test void nestedLargeScalarDoesNotAllocateAnotherCanonicalCopyForEveryAncestor()throws Exception {
        com.sun.management.ThreadMXBean allocation=allocationBean();
        String canonical=wrappedScalar(64,256*1024),expected=sha(canonical);
        CanonicalJson.hash("{\"warm\":[\"中😀\",-0.0,true]}",null);
        long before=allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        String actual=CanonicalJson.hash(canonical,null);
        long bytes=allocation.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertEquals(expected,actual);
        assertTrue(bytes<12L*canonical.length()+1024*1024,"Canonical hash allocated "+bytes+" bytes for "+canonical.length()+" characters");
    }

    @Test void extractingSmallFieldDoesNotConstructTheLargeUnselectedSibling() {
        com.sun.management.ThreadMXBean allocation=allocationBean();
        String raw="{\"value\":1e-05,\"other\":"+wrappedScalar(64,256*1024)+"}";
        CanonicalJson.field("{\"value\":0,\"other\":[\"warm\"]}","value");
        long before=allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        String actual=CanonicalJson.field(raw,"value");
        long bytes=allocation.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertEquals("1e-05",actual);
        assertTrue(bytes<1024*1024,"Unselected field extraction allocated "+bytes+" bytes");
    }

    @Test void largeCandidateGraphFieldHashPreservesOriginalNumberTokens()throws Exception {
        com.sun.management.ThreadMXBean allocation=allocationBean();
        String body=wrappedScalar(64,256*1024);
        String canonical="{\"a\":1e-05,\"n\":-0.0,\"z\":"+body+"}",expected=sha(canonical);
        String manifest="{\"observer\":{},\"candidateGraph\":{\"z\":"+body+",\"n\":-0.0,\"a\":1e-05}}";
        CanonicalJson.fieldHash("{\"candidateGraph\":{\"warm\":true}}","candidateGraph");
        long before=allocation.getThreadAllocatedBytes(Thread.currentThread().getId());
        String actual=CanonicalJson.fieldHash(manifest,"candidateGraph");
        long bytes=allocation.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
        assertEquals(expected,actual);
        assertTrue(bytes<12L*canonical.length()+1024*1024,"Candidate graph field hash allocated "+bytes+" bytes");
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        java.lang.management.ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean allocation=(com.sun.management.ThreadMXBean)bean;
        assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation;
    }

    private static String wrappedScalar(int depth,int length) {
        char[] data=new char[length];Arrays.fill(data,'x');
        StringBuilder out=new StringBuilder(length+depth*6+2);
        for(int i=0;i<depth;i++)out.append("{\"z\":");
        out.append('"').append(data).append('"');
        for(int i=0;i<depth;i++)out.append('}');
        return out.toString();
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,action);
        assertEquals("CANONICAL_JSON_INVALID",error.getMessage());
    }

    private static String sha(String value)throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);for(byte b:bytes)hex.append(String.format("%02x",b&255));return hex.toString();
    }
}
