package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalObservedTransportTest {
    @Test void normalPublicationAndHealthUseTheConfiguredTargetModuleWithoutTouchingSnapshotBase()throws Exception{
        AtomicInteger health=new AtomicInteger(),posts=new AtomicInteger();
        CanonicalEvidenceProperties props=new CanonicalEvidenceProperties();props.setBaseUrl("http://snapshot.invalid");
        props.getRetrievalBaseUrls().put("advisory","http://advisory.invalid");
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(props){
            @Override protected Reply execute(Request request,int maximum){
                assertEquals("advisory.invalid",request.url().host());String result;
                switch(request.url().encodedPath()){
                    case "/health":health.incrementAndGet();result="{\"targetModule\":\"advisory\"}";break;
                    case "/normal-observed":
                        assertEquals("POST",request.method());Buffer actual=new Buffer();
                        try{request.body().writeTo(actual);}catch(java.io.IOException error){throw new AssertionError(error);}
                        assertEquals("{\"candidateGraph\":{},\"sourceBinding\":{}}",actual.readUtf8());
                        posts.incrementAndGet();result="{\"immutable\":true}";break;
                    default:assertEquals("advisory",request.url().queryParameter("module"));result="{\"producerKind\":\"normal_observed\"}";
                }
                return new Reply(result.getBytes(StandardCharsets.UTF_8),"application/json",null,null,null);
            }
        };
        assertTrue(backend.normalHealth("advisory").contains("advisory"));
        assertTrue(backend.normalObserved("{\"candidateGraph\":{},\"sourceBinding\":{}}","advisory").contains("immutable"));
        assertEquals(1,health.get());assertEquals(1,posts.get());
        assertTrue(backend.normalSnapshot("1".repeat(64),"advisory").contains("normal_observed"));
        props.setMaxSnapshotBytes(1024);
        assertThrows(CanonicalEvidenceException.class,()->backend.normalObserved("x".repeat(1025),"advisory"));assertEquals(1,posts.get());
        props.getRetrievalBaseUrls().put("advisory","http://user:secret@advisory.invalid");
        assertThrows(CanonicalEvidenceException.class,()->backend.normalHealth("advisory"));assertEquals(1,health.get());
    }
}
