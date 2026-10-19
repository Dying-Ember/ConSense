package com.consense.common;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** String.getBytes(UTF_8) semantics without a whole-string worst-case allocation. */
public final class Utf8 {
    private Utf8(){}

    public static long byteCount(String value){return encode(null,value);}

    public static String sha256(String value){
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");update(digest,value);
            byte[] bytes=digest.digest();char[] hex=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();
            for(int i=0;i<bytes.length;i++){hex[2*i]=digits[(bytes[i]&255)>>>4];hex[2*i+1]=digits[bytes[i]&15];}
            return new String(hex);
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    public static void update(MessageDigest digest,String value){encode(Objects.requireNonNull(digest),value);}

    /** Materialize bytes only for callers that actually need a wire body. */
    public static byte[] bytes(String value){
        byte[] bytes=new byte[Math.toIntExact(byteCount(value))];ByteBuffer output=ByteBuffer.wrap(bytes);
        CharsetEncoder encoder=encoder();CoderResult result=encoder.encode(CharBuffer.wrap(value),output,true);
        if(!result.isUnderflow()||!encoder.flush(output).isUnderflow()||output.position()!=bytes.length)
            throw new IllegalStateException("UTF-8 byte count changed during encoding");
        return bytes;
    }

    private static CharsetEncoder encoder(){return StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(new byte[]{(byte)'?'});}

    private static long encode(MessageDigest digest,String value){
        CharBuffer input=CharBuffer.wrap(value);ByteBuffer output=ByteBuffer.allocate(8192);
        CharsetEncoder encoder=encoder();CoderResult result;long count=0;
        do {
            result=encoder.encode(input,output,true);count+=output.position();
            if(digest!=null)digest.update(output.array(),0,output.position());output.clear();
            if(result.isError())throw new IllegalStateException("UTF-8 replacement encoding failed");
        }while(result.isOverflow());
        do {
            result=encoder.flush(output);count+=output.position();
            if(digest!=null)digest.update(output.array(),0,output.position());output.clear();
            if(result.isError())throw new IllegalStateException("UTF-8 replacement encoding failed");
        }while(result.isOverflow());
        return count;
    }
}
