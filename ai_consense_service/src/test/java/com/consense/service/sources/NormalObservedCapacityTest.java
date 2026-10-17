package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalObservedCapacityTest {
    @Test void configuredNormalWholeRequestAndResponseCanExceedLegacySixteenMiB()throws Exception{
        String payload="{\"text\":\""+"\u754c".repeat(6*1024*1024)+"\"}";
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length>16*1024*1024);
        CanonicalEvidenceProperties props=new CanonicalEvidenceProperties();
        assertEquals(NormalObservedContract.MAX_BYTES,props.getMaxSnapshotBytes());
        props.setBaseUrl("http://127.0.0.1:1");props.setMaxSnapshotBytes(64*1024*1024);
        AtomicInteger posts=new AtomicInteger();
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(props){
            @Override protected Reply execute(Request request,int maximum){
                try{
                    if(request.method().equals("POST")){
                        Buffer actual=new Buffer();request.body().writeTo(actual);
                        assertEquals(CanonicalJson.sha(bytes),CanonicalJson.sha(actual.readByteArray()));posts.incrementAndGet();
                    }
                    // Exercise the production reader with both declared and unknown-length response bodies.
                    ResponseBody body=request.method().equals("POST")?ResponseBody.create(MediaType.parse("application/json"),bytes):
                        ResponseBody.create(MediaType.parse("application/json"),-1,new Buffer().write(bytes));
                    return new Reply(readBody(body,maximum),"application/json",null,null,null);
                }catch(java.io.IOException invalid){throw new AssertionError(invalid);}
            }
        };
        assertEquals(payload,backend.normalObserved(payload,"vetting"));
        assertEquals(payload,backend.normalSnapshot("1".repeat(64),"vetting"));assertEquals(1,posts.get());
        props.setMaxSnapshotBytes(bytes.length);
        assertEquals(payload,backend.normalObserved(payload,"vetting"));
        props.setMaxSnapshotBytes(bytes.length-1);
        assertThrows(CanonicalEvidenceException.class,()->backend.normalObserved(payload,"vetting"));
        assertThrows(CanonicalEvidenceException.class,()->backend.normalSnapshot("1".repeat(64),"vetting"));assertEquals(2,posts.get());
    }
    @Test void responseReaderCountsActualBytesDespiteUnknownOrForgedLengths()throws Exception{
        byte[] bytes="\u754c".repeat(1024).getBytes(StandardCharsets.UTF_8);
        for(long length:new long[]{bytes.length,-1,1}){
            assertArrayEquals(bytes,CanonicalEvidenceBackend.readBody(ResponseBody.create(null,length,new Buffer().write(bytes)),bytes.length));
            CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->
                CanonicalEvidenceBackend.readBody(ResponseBody.create(null,length,new Buffer().write(bytes)),bytes.length-1));
            assertEquals("OER_EVIDENCE_TOO_LARGE",error.getMessage());
        }
        CanonicalEvidenceProperties props=new CanonicalEvidenceProperties();props.setBaseUrl("http://127.0.0.1:1");
        for(int maximum:new int[]{0,-1,NormalObservedContract.MAX_BYTES+1}){
            props.setMaxSnapshotBytes(maximum);CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(props);
            assertEquals("OER_EVIDENCE_CONFIG_INVALID",assertThrows(CanonicalEvidenceException.class,()->backend.normalHealth("vetting")).getMessage());
        }
    }
}
