package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback transport fixtures; no ConSense runtime, parser, model or business requests. */
class OerSourceReadStreamTransportTest {
    @Test void oneResponseCanExceedThePerFrameLimitWithoutAccumulatingOrReplayingTheStream()throws Exception{
        String line="{\"text\":\"AUTHORED bounded frame\"}\n";byte[] raw=line.repeat(100).getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls=new AtomicInteger();try(Wire wire=new Wire(raw,"application/x-ndjson",calls)){
            List<String> frames=new ArrayList<>();wire.backend.sourceReadStream("{}","vetting",1024,frames::add);
            assertEquals(100,frames.size());assertTrue(raw.length>1024);assertEquals(1,calls.get());
        }
    }
    @Test void malformedUtf8OversizedTruncatedAndWrongMediaFramesAreRejectedWithoutReplay()throws Exception{
        List<byte[]> inputs=Arrays.asList(new byte[]{(byte)0xc3,(byte)0x28,10},("x".repeat(1025)+"\n").getBytes(StandardCharsets.UTF_8),"{\"type\":\"complete\"}".getBytes(StandardCharsets.UTF_8),"{}\n".getBytes(StandardCharsets.UTF_8));
        for(int index=0;index<inputs.size();index++){
            AtomicInteger calls=new AtomicInteger();try(Wire wire=new Wire(inputs.get(index),index==3?"application/json":"application/x-ndjson",calls)){
                assertThrows(CanonicalEvidenceException.class,()->wire.backend.sourceReadStream("{}","vetting",1024,line->{}));assertEquals(1,calls.get());
            }
        }
    }
    @Test void aSourceGuardFailureClosesTheOwnedTransportWithoutASecondRequest()throws Exception{
        AtomicInteger calls=new AtomicInteger();try(Wire wire=new Wire("{}\n{}\n".getBytes(StandardCharsets.UTF_8),"application/x-ndjson",calls)){
            IllegalStateException changed=new IllegalStateException("AUTHORED selected source generation changed");
            assertSame(changed,assertThrows(IllegalStateException.class,()->wire.backend.sourceReadStream("{}","vetting",1024,line->{throw changed;})));assertEquals(1,calls.get());
        }
    }
    private static final class Wire implements AutoCloseable {
        final HttpServer server;final CanonicalEvidenceBackend backend;
        Wire(byte[] bytes,String type,AtomicInteger calls)throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/sources/read",exchange->{
                calls.incrementAndGet();assertEquals("application/x-ndjson",exchange.getRequestHeaders().getFirst("Accept"));
                try(java.io.InputStream input=exchange.getRequestBody()){while(input.read()!=-1){}}
                exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);
                try(java.io.OutputStream output=exchange.getResponseBody()){output.write(bytes);}
            });server.start();CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());config.setTimeoutMs(5000);config.setNormalPublicationTimeoutMs(5000);backend=new CanonicalEvidenceBackend(config);
        }
        @Override public void close(){server.stop(0);}
    }
}
