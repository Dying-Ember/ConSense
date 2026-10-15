package com.consense.service.vetting;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** One immutable business policy for runtime review, defaults and recovery identity. */
public final class VettingPromptPolicy {
    public static final String VERSION="competition-vetting-20261015-v1";
    public static final String RESOURCE="/vetting/contract-system-competition-20261015.md";
    public static final String WIRE_VERSION="seven-class-packet-wire-v1";
    private static final byte[] BYTES=load();
    private static final String TEXT=new String(BYTES,StandardCharsets.UTF_8);
    private static final String SHA256=digest(BYTES);
    private VettingPromptPolicy() { }
    public static String text() { return TEXT; }
    public static String sha256() { return SHA256; }
    public static Map<String,String> metadata() {
        return metadata("");
    }
    public static String supplementSha256(String supplement) { return digest((supplement==null?"":supplement).getBytes(StandardCharsets.UTF_8)); }
    public static Map<String,String> metadata(String supplement) {
        Map<String,String> result=new LinkedHashMap<>();result.put("version",VERSION);result.put("sha256",SHA256);
        result.put("resource",RESOURCE);result.put("wireVersion",WIRE_VERSION);result.put("supplementSha256",supplementSha256(supplement));return result;
    }
    private static byte[] load() {
        try(InputStream stream=VettingPromptPolicy.class.getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IllegalStateException("Vetting competition policy resource is unavailable");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=stream.read(buffer))!=-1)out.write(buffer,0,count);
            byte[] bytes=out.toByteArray();
            if(new String(bytes,StandardCharsets.UTF_8).trim().isEmpty())throw new IllegalStateException("Vetting competition policy is empty");
            return bytes;
        } catch(Exception e) { throw new IllegalStateException("Cannot load Vetting competition policy",e); }
    }
    private static String digest(byte[] bytes) {
        try {
            StringBuilder hex=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",b&255));
            return hex.toString();
        } catch(Exception e) { throw new IllegalStateException("Cannot hash Vetting competition policy",e); }
    }
}
