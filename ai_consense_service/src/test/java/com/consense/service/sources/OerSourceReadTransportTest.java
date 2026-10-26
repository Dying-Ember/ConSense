package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerSourceReadTransportTest {
    @Test void onlyExactSemanticPreparationUsesThePublicationDeadlineAndKeepsWireBytesAndCap() {
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://snapshot.invalid");
        properties.setTimeoutMs(180000);properties.setNormalPublicationTimeoutMs(900000);AtomicInteger calls=new AtomicInteger();
        for(String module:new String[]{"vetting","advisory"}){
            String input="{ \"module\":\""+module+"\", \"operation\":\"prepare\", \"view\":\"semantic_units\" }";
            CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(properties){
                @Override protected Reply execute(Request request,int maximum){
                    calls.incrementAndGet();assertEquals(Integer.valueOf(900000),request.tag(Integer.class));
                    assertEquals("/sources/read",request.url().encodedPath());assertEquals(16*1024*1024,maximum);
                    Buffer body=new Buffer();try{request.body().writeTo(body);}catch(java.io.IOException invalid){throw new AssertionError(invalid);}
                    assertEquals(input,body.readUtf8());return new Reply(new byte[]{123,125},"application/json",null,null,null);
                }
            };
            assertEquals("{}",backend.sourceRead(input,module,16*1024*1024));
        }
        assertEquals(2,calls.get());
    }

    @Test void ordinaryReadsAndMalformedOrMismatchedPreparationKeepTheirExistingDeadline() {
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://snapshot.invalid");
        properties.setTimeoutMs(180000);properties.setNormalPublicationTimeoutMs(0);AtomicInteger calls=new AtomicInteger();
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(properties){
            @Override protected Reply execute(Request request,int maximum){
                calls.incrementAndGet();assertNull(request.tag(Integer.class));assertEquals(4096,maximum);
                return new Reply(new byte[]{123,125},"application/json",null,null,null);
            }
        };
        for(String operation:new String[]{"scan","ids","catalog"})
            assertEquals("{}",backend.sourceRead("{\"module\":\"vetting\",\"operation\":\""+operation+"\",\"view\":\"semantic_units\"}","vetting",4096));
        for(String input:new String[]{"not-json","null","[]","{}",
                "{\"module\":\"vetting\",\"operation\":\"prepare\",\"view\":\"semantic_units\"} trailing-garbage",
                "{\"module\":\"advisory\",\"operation\":\"prepare\",\"view\":\"semantic_units\"}",
                "{\"module\":\"vetting\",\"operation\":\"prepare\",\"view\":\"canonical_units\"}"})
            assertEquals("{}",backend.sourceRead(input,"vetting",4096));
        assertEquals("{}",backend.sourceRead("{\"module\":\"drafting\",\"operation\":\"prepare\",\"view\":\"semantic_units\"}","drafting",4096));
        assertEquals(11,calls.get());
        CanonicalEvidenceException invalid=assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead(
                "{\"module\":\"vetting\",\"operation\":\"prepare\",\"view\":\"semantic_units\"}","vetting",4096));
        assertEquals("OER_EVIDENCE_CONFIG_INVALID",invalid.getMessage());assertEquals(11,calls.get());
        for(int budget:new int[]{1023,16*1024*1024+1})assertThrows(CanonicalEvidenceException.class,()->backend.sourceRead(
                "{\"module\":\"vetting\",\"operation\":\"prepare\",\"view\":\"semantic_units\"}","vetting",budget));
        assertEquals(11,calls.get());
    }

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
