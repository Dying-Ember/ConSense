package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalObservedTransportTest {
    @Test void normalPublicationAndHealthUseTheConfiguredTargetModuleWithoutTouchingSnapshotBase()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicInteger health=new AtomicInteger(),posts=new AtomicInteger();
        server.createContext("/health",exchange->{health.incrementAndGet();byte[] bytes="{\"targetModule\":\"advisory\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/normal-observed",exchange->{assertEquals("POST",exchange.getRequestMethod());assertEquals("{\"candidateGraph\":{},\"sourceBinding\":{}}",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));posts.incrementAndGet();byte[] bytes="{\"immutable\":true}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(bytes);}});
        server.createContext("/canonical/",exchange->{assertEquals("module=advisory",exchange.getRequestURI().getQuery());byte[] bytes="{\"producerKind\":\"normal_observed\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(bytes);}});
        server.start();try{CanonicalEvidenceProperties props=new CanonicalEvidenceProperties();props.setBaseUrl("http://127.0.0.1:1");props.getRetrievalBaseUrls().put("advisory","http://127.0.0.1:"+server.getAddress().getPort());CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(props);
            assertTrue(backend.normalHealth("advisory").contains("advisory"));assertTrue(backend.normalObserved("{\"candidateGraph\":{},\"sourceBinding\":{}}","advisory").contains("immutable"));assertEquals(1,health.get());assertEquals(1,posts.get());
            assertTrue(backend.normalSnapshot("1".repeat(64),"advisory").contains("normal_observed"));
            assertThrows(CanonicalEvidenceException.class,()->backend.normalObserved("x".repeat(NormalObservedPreparationService.MAX_BYTES+1),"advisory"));assertEquals(1,posts.get());
            props.getRetrievalBaseUrls().put("advisory","http://user:secret@127.0.0.1:"+server.getAddress().getPort());assertThrows(CanonicalEvidenceException.class,()->backend.normalHealth("advisory"));assertEquals(1,health.get());
        }finally{server.stop(0);}
    }
}
