package com.consense.service.sources;

import com.consense.config.CanonicalEvidenceProperties;
import java.nio.charset.StandardCharsets;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalObservedByteReplyTest {
    @Test void byteSeamDoesNotDecodeAndTheExistingStringAdapterStillDecodesStrictly(){
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://normal.invalid");
        byte[] malformed={(byte)0xff};
        CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(properties){
            @Override protected Reply execute(Request request,int maximum){return new Reply(malformed,"application/json",null,null,null);}
        };
        assertSame(malformed,backend.normalObservedReply("{}","vetting").bytes);
        assertEquals("CANONICAL_JSON_INVALID",assertThrows(CanonicalEvidenceException.class,()->backend.normalObserved("{}","vetting")).getMessage());
    }
    @Test void streamedRequestHasExactReplacementBytesLengthAndDoesNotCloseTheCallerSink(){
        CanonicalEvidenceProperties properties=new CanonicalEvidenceProperties();properties.setBaseUrl("http://normal.invalid");
        for(String value:new String[]{"", "A".repeat(8191)+"\ud83d\ude00\u754c\u00e9", "\ud800x\udc00", "A".repeat(8191)+"\ud800"}){
            CanonicalEvidenceBackend backend=new CanonicalEvidenceBackend(properties){
                @Override protected Reply execute(Request request,int maximum){
                    byte[] expected=value.getBytes(StandardCharsets.UTF_8);Buffer sink=new Buffer();
                    try{assertEquals(expected.length,request.body().contentLength());request.body().writeTo(sink);sink.writeByte('!');
                        assertArrayEquals(expected,sink.readByteArray(expected.length));assertEquals("!",sink.readUtf8());
                    }catch(java.io.IOException invalid){throw new AssertionError(invalid);}
                    return new Reply("{}".getBytes(StandardCharsets.UTF_8),"application/json",null,null,null);
                }
            };
            assertEquals("{}",backend.normalObserved(value,"vetting"));
        }
    }
}
