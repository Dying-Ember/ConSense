package com.consense.service.sources;

import com.consense.service.vetting.VettingCorpus;
import com.consense.common.Utf8;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only in the explicitly budgeted large-input replay JVM. */
@EnabledIfSystemProperty(named="consense.test.largeUtf8",matches="true")
class LargeUtf8EncodingRegressionTest {
    private static final int UTF16_LENGTH=715827883;
    private static String mixedUtf16,expectedSha256;

    @BeforeAll static void constructOneMixedUtf16InputAndIndependentDigest()throws Exception {
        assertTrue(3L*UTF16_LENGTH>Integer.MAX_VALUE);
        assertTrue((long)UTF16_LENGTH+2<NormalObservedContract.MAX_BYTES);
        char[] chars=new char[UTF16_LENGTH];Arrays.fill(chars,'a');chars[UTF16_LENGTH-1]='\u4e2d';
        mixedUtf16=new String(chars);
        assertEquals(UTF16_LENGTH,mixedUtf16.length());
        assertEquals('\u4e2d',mixedUtf16.charAt(UTF16_LENGTH-1));
        expectedSha256=independentSha("","");
    }

    private static String independentSha(String prefix,String suffix)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(prefix.getBytes(StandardCharsets.US_ASCII));
        byte[] ascii=new byte[8192];Arrays.fill(ascii,(byte)'a');
        for(int remaining=UTF16_LENGTH-1;remaining>0;){int size=Math.min(ascii.length,remaining);digest.update(ascii,0,size);remaining-=size;}
        digest.update(new byte[]{(byte)0xe4,(byte)0xb8,(byte)0xad});
        digest.update(suffix.getBytes(StandardCharsets.US_ASCII));
        StringBuilder hex=new StringBuilder(64);
        for(byte b:digest.digest())hex.append(String.format("%02x",b&255));
        return hex.toString();
    }

    @Test void sourceHashPreservesEveryUtf8ByteAboveJdkUtf16PreallocationOverflow() {
        assertEquals(expectedSha256,VettingCorpus.hash(mixedUtf16));
    }

    @Test void oversizeNormalSnapshotFailsWithContractReasonBeforeJsonParsing() {
        CanonicalEvidenceException error=assertThrows(CanonicalEvidenceException.class,()->
                NormalObservedContract.validate(mixedUtf16,"bad",1024));
        assertEquals("NORMAL_OBSERVED_TOO_LARGE",error.getMessage());
    }

    @Test void exactWireBytesPreserveIndependentDigestWithoutWorstCaseAllocation()throws Exception {
        assertEquals((long)UTF16_LENGTH+2,Utf8.byteCount(mixedUtf16));byte[] bytes=Utf8.bytes(mixedUtf16);
        assertEquals(UTF16_LENGTH+2,bytes.length);assertEquals((byte)'a',bytes[0]);
        assertEquals((byte)0xe4,bytes[bytes.length-3]);assertEquals((byte)0xb8,bytes[bytes.length-2]);assertEquals((byte)0xad,bytes[bytes.length-1]);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(String.format("%02x",b&255));assertEquals(expectedSha256,hex.toString());
    }

    @Test void canonicalQuoteArrayAndObjectKeepExactLargeContentAndIndependentSeals()throws Exception {
        String quoted=CanonicalJson.scalar(mixedUtf16);assertEquals(UTF16_LENGTH+2,quoted.length());
        assertEquals(independentSha("\"","\""),Utf8.sha256(quoted));
        String array=CanonicalJson.rawArray(Collections.singletonList(quoted));assertEquals(UTF16_LENGTH+4,array.length());
        assertEquals(independentSha("[\"","\"]"),CanonicalJson.hash(array,null));
        String object=CanonicalJson.rawObject(Collections.singletonMap("text",quoted));assertEquals(UTF16_LENGTH+11,object.length());
        assertEquals(independentSha("{\"text\":\"","\"}"),CanonicalJson.hash(object,null));
    }

    @AfterAll static void releaseLargeInput(){mixedUtf16=null;}
}
