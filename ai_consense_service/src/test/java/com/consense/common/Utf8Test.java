package com.consense.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Utf8Test {
    @Test void byteCountHashAndExactBytesMatchJdkForEveryUtf16CodeUnit()throws Exception {
        char[] units=new char[65536];for(int i=0;i<units.length;i++)units[i]=(char)i;
        equivalent(new String(units));
        for(String value:new String[]{"","ASCII\u0000\r\n","\u4e2d\u6587","\ud83d\ude00","\ud800","\udc00","\ud800\ud800","\udc00\ud800","a\ud800b\udc00c"})equivalent(value);
    }

    @Test void surrogatePairsAndMalformedUtf16SurviveEightKilobyteOutputBoundaries()throws Exception {
        for(int padding:new int[]{8188,8189,8190,8191,8192,8193,16383})
            equivalent("a".repeat(padding)+"\ud83d\ude00\u4e2d\ud800z\udc00");
    }

    @Test void separateUpdatesEndMalformedInputBeforeTheNextString()throws Exception {
        MessageDigest actual=MessageDigest.getInstance("SHA-256");
        Utf8.update(actual,"prefix\ud83d");actual.update((byte)'\n');Utf8.update(actual,"\ude00tail");
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("prefix?\n?tail".getBytes(StandardCharsets.UTF_8)),actual.digest());
    }

    @Test void nullInputsRemainErrors(){
        assertThrows(NullPointerException.class,()->Utf8.byteCount(null));
        assertThrows(NullPointerException.class,()->Utf8.sha256(null));
        assertThrows(NullPointerException.class,()->Utf8.bytes(null));
        assertThrows(NullPointerException.class,()->Utf8.update(null,"text"));
    }

    private static void equivalent(String value)throws Exception {
        byte[] expected=value.getBytes(StandardCharsets.UTF_8);
        assertEquals((long)expected.length,Utf8.byteCount(value));assertArrayEquals(expected,Utf8.bytes(value));
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(expected);StringBuilder hex=new StringBuilder(64);
        for(byte b:digest)hex.append(String.format("%02x",b&255));assertEquals(hex.toString(),Utf8.sha256(value));
        MessageDigest actual=MessageDigest.getInstance("SHA-256");Utf8.update(actual,value);assertArrayEquals(digest,actual.digest());
    }
}
