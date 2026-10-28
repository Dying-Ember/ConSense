package com.consense.service.sources;

import com.consense.common.Utf8;
import com.consense.config.CanonicalEvidenceProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP transport fixtures; no running ConSense component is contacted. */
class NormalPointerTransportTest {
    private static final String PROJECTION="a".repeat(64),ORIGINAL="b".repeat(64);
    private static final byte[] RAW=Utf8.bytes("{\"text\":\"AUTHORED exact Unicode 😀 source\"}");

    @Test void matchingConditionalProofDoesNotReadOrRetainAResponseBody()throws Exception {
        try(Wire wire=new Wire(304,RAW,"valid",1024)){
            assertTrue(wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,hash(RAW),reader->fail("A 304 has no source body")));
            assertEquals(1,wire.calls.get());assertEquals("\""+hash(RAW)+"\"",wire.conditional);
        }
    }
    @Test void changedRawSpellingCanBeVerifiedThroughACompleteStreamingCallback()throws Exception {
        try(Wire wire=new Wire(200,RAW,"valid",1024)){
            StringBuilder selected=new StringBuilder();
            assertTrue(wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,"c".repeat(64),reader->{
                char[] block=new char[7];int count;while((count=reader.read(block))!=-1)selected.append(block,0,count);
            }));
            assertEquals(new String(RAW,StandardCharsets.UTF_8),selected.toString());assertEquals(1,wire.calls.get());
        }
    }
    @Test void anOptionalOriginalHeaderIsStillAcceptedOnlyWhenItMatches()throws Exception {
        try(Wire wire=new Wire(200,RAW,"source_header",1024)){
            assertTrue(wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,hash(RAW),reader->{char[] block=new char[19];while(reader.read(block)!=-1){}}));
            assertEquals(1,wire.calls.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"projection","original","etag","utf8","oversized_fixed","oversized_chunked","truncated","partial_verifier","wrong_status"})
    void badNativeReplyRejectsWithoutRetryOrWholeBodyAccumulation(String defect)throws Exception {
        byte[] bytes="utf8".equals(defect)?new byte[]{(byte)0xc3,(byte)0x28}:defect.startsWith("oversized")?Utf8.bytes("x".repeat(1025)):RAW;
        try(Wire wire=new Wire("wrong_status".equals(defect)?202:200,bytes,defect,1024)){
            assertThrows(CanonicalEvidenceException.class,()->wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,hash(RAW),reader->{
                if("partial_verifier".equals(defect)){reader.read();return;}
                char[] block=new char[19];while(reader.read(block)!=-1){}
            }),defect);
            assertEquals(1,wire.calls.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"projection","original","etag"})
    void a304StillRequiresExactSourceProjectionAndRawWireIdentity(String defect)throws Exception {
        try(Wire wire=new Wire(304,RAW,defect,1024)){
            assertThrows(CanonicalEvidenceException.class,()->wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,hash(RAW),reader->fail("No 304 body")));
            assertEquals(1,wire.calls.get());
        }
    }
    @Test void aSemanticGuardFailurePreservesItsReasonAndClosesWithoutReplay()throws Exception {
        try(Wire wire=new Wire(200,RAW,"valid",1024)){
            IllegalStateException changed=new IllegalStateException("AUTHORED candidate generation changed");
            assertSame(changed,assertThrows(IllegalStateException.class,()->wire.backend.verifyNormalSnapshot(PROJECTION,"vetting",ORIGINAL,hash(RAW),reader->{throw changed;})));
            assertEquals(1,wire.calls.get());
        }
    }
    private static String hash(byte[] bytes){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static final class Wire implements AutoCloseable {
        final HttpServer server;final CanonicalEvidenceBackend backend;final AtomicInteger calls=new AtomicInteger();
        volatile String conditional;
        Wire(int status,byte[] bytes,String defect,int maximum)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/canonical/"+PROJECTION,exchange->{
                calls.incrementAndGet();conditional=exchange.getRequestHeaders().getFirst("If-None-Match");
                assertEquals("GET",exchange.getRequestMethod());assertEquals("module=vetting",exchange.getRequestURI().getRawQuery());
                exchange.getResponseHeaders().set("X-Projection-Sha256","projection".equals(defect)?"d".repeat(64):PROJECTION);
                // Pinned normal canonical 200/304 replies omit this optional header.
                if("original".equals(defect)||"source_header".equals(defect))exchange.getResponseHeaders().set("X-Source-Sha256","original".equals(defect)?"d".repeat(64):ORIGINAL);
                exchange.getResponseHeaders().set("ETag","\""+("etag".equals(defect)?"d".repeat(64):hash(bytes))+"\"");
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(status,status==304?-1:"oversized_chunked".equals(defect)?0:"truncated".equals(defect)?bytes.length+10:bytes.length);
                try(OutputStream output=exchange.getResponseBody()){if(status!=304)output.write(bytes);}catch(IOException ignored){/* Expected when a rejecting client closes early. */}
            });server.start();
            CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            config.setTimeoutMs(1000);config.setNormalPublicationTimeoutMs(1000);config.setMaxSnapshotBytes(maximum);backend=new CanonicalEvidenceBackend(config);
        }
        @Override public void close(){server.stop(0);}
    }
}
