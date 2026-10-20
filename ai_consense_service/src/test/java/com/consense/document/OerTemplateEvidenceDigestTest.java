package com.consense.document;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerTemplateEvidenceDigestTest {
    @Test void sourceEvidenceDigestsKeepJdkBytesForUnicodeAndEncodingBoundaries()throws Exception {
        char[] units=new char[65536];for(int i=0;i<units.length;i++)units[i]=(char)i;
        equivalent(new String(units));
        for(String value:new String[]{"","ASCII\u0000\r\n","\u4e2d\u6587","\ud83d\ude00","\ud800","\udc00","\ud800\ud800","\udc00\ud800","a\ud800b\udc00c"})equivalent(value);
        for(int padding:new int[]{8188,8189,8190,8191,8192,8193,16383})
            equivalent("a".repeat(padding)+"\ud83d\ude00\u4e2d\ud800z\udc00");
    }

    @Test void sourceEvidenceHashesLargeMixedUtf16WithOnlyABoundedEncodingBuffer()throws Exception {
        Path directory=Paths.get("target","oer-evidence-digest-heap");Files.createDirectories(directory);
        Path output=Files.createTempFile(directory,"probe-",".log");
        String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process process=new ProcessBuilder(java,"-Xmx384m","-Dfile.encoding=UTF-8","-classpath",classpath,
                OerTemplateEvidenceDigestTest.class.getName(),"--heap-probe")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished=process.waitFor(90,TimeUnit.SECONDS);
        if(!finished)process.destroyForcibly();
        assertTrue(finished,"Owned bounded-heap digest probe timed out: "+output);
        assertEquals(0,process.exitValue(),Files.readString(output));
        assertTrue(Files.readString(output).contains("OER_EVIDENCE_DIGEST_90M_PASS"));
    }

    /** Separate JVM proves the production call site without retaining a test-suite heap. */
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!"--heap-probe".equals(args[0]))throw new IllegalArgumentException("Explicit heap probe required");
        int length=90_000_000;String expected=largeExpected(length);String value=largeText(length);
        if(value.length()!=length)throw new AssertionError("Complete UTF-16 input required");
        String actual=evidenceDigest(value);
        if(!expected.equals(actual))throw new AssertionError("Complete UTF-8 source digest differs");
        System.out.println("OER_EVIDENCE_DIGEST_90M_PASS utf16="+length+" maxHeap="+Runtime.getRuntime().maxMemory()+" sha256="+actual);
    }

    private static String largeText(int length){char[] chars=new char[length];Arrays.fill(chars,'a');chars[length-1]='\u4e2d';return new String(chars);}
    private static String largeExpected(int length)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] ascii=new byte[8192];Arrays.fill(ascii,(byte)'a');
        for(int left=length-1;left>0;){int count=Math.min(ascii.length,left);digest.update(ascii,0,count);left-=count;}
        digest.update(new byte[]{(byte)0xe4,(byte)0xb8,(byte)0xad});return hex(digest.digest());
    }
    private static void equivalent(String value)throws Exception {
        assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))),evidenceDigest(value));
    }
    private static String evidenceDigest(String value)throws Exception {
        Method method=OerTemplateEvidence.class.getDeclaredMethod("digest",String.class);method.setAccessible(true);
        return (String)method.invoke(null,value);
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(64);for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
}
