package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerSourceReadTransportTest {
    @Test void addressedReadsUseOnlyTheConfiguredModuleWithAFiniteResponseBudget() throws Exception {
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://snapshot.invalid");
        properties.getRetrievalBaseUrls().put("drafting","http://drafting.invalid");AtomicInteger calls=new AtomicInteger();
        String input="{\"operation\":\"ids\",\"module\":\"drafting\",\"ids\":[\"unit-1\"]}";
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(properties){
            @Override protected Reply execute(Request request,int maximum){
                calls.incrementAndGet();assertEquals("drafting.invalid",request.url().host());assertEquals("/sources/read",request.url().encodedPath());
                assertEquals("POST",request.method());assertEquals(4096,maximum);Buffer body=new Buffer();
                try{request.body().writeTo(body);}catch(java.io.IOException invalid){throw new AssertionError(invalid);}
                assertEquals(input,body.readUtf8());return new Reply("{\"units\":[]}".getBytes(StandardCharsets.UTF_8),"application/json",null,null,null);
            }
        };
        assertEquals("{\"units\":[]}",backend.sourceRead(input,"drafting",4096));
        for(int budget:new int[]{0,1023,16*1024*1024+1})assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead(input,"drafting",budget));
        assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead("x".repeat(65537),"drafting",4096));
        assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead(input,"unknown",4096));
        properties.getRetrievalBaseUrls().put("drafting","http://user:secret@drafting.invalid");
        assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead(input,"drafting",4096));assertEquals(1,calls.get());
    }
}
