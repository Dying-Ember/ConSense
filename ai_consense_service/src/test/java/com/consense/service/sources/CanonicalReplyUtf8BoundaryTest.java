package com.consense.service.sources;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored strict wire decoding checks, including errors after several decoder buffers. */
class CanonicalReplyUtf8BoundaryTest {
    @Test void validMultibyteSequencesAcrossBufferBoundariesRemainExact(){
        for(int padding=8187;padding<8196;padding++){
            String value="A".repeat(padding)+"\u00e9\u754c\ud83d\ude00"+"B".repeat(8192)+"\ud834\udd1e";
            assertEquals(value,reply(value.getBytes(StandardCharsets.UTF_8)).json());
        }
        assertEquals("",reply(new byte[0]).json());assertEquals("\ufeff{}",reply(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,123,125}).json());
    }
    @Test void malformedTruncatedOverlongSurrogateAndOutOfRangeBytesRejectEvenLate(){
        byte[][] invalid={{(byte)0xff},{(byte)0x80},{(byte)0xc0,(byte)0xaf},{(byte)0xe2,(byte)0x82},
            {(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80}};
        for(byte[] suffix:invalid)for(int padding:new int[]{0,8191,16384}){
            byte[] value=new byte[padding+suffix.length];java.util.Arrays.fill(value,0,padding,(byte)'A');System.arraycopy(suffix,0,value,padding,suffix.length);
            assertEquals("CANONICAL_JSON_INVALID",assertThrows(CanonicalEvidenceException.class,()->reply(value).json()).getMessage());
        }
    }
    private static CanonicalEvidenceBackend.Reply reply(byte[] value){return new CanonicalEvidenceBackend.Reply(value,"application/json",null,null,null);}
}
