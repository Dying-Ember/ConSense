package com.consense.service.sources;

import com.consense.common.Utf8;
import com.consense.domain.SourceDocument;
import java.security.MessageDigest;
import java.util.Arrays;

/** Pure content fingerprints. Current database/original-byte validity is always checked separately. */
public final class SourceContentDigests {
    private SourceContentDigests() {}
    public static String field(String value){
        MessageDigest digest=sha();Utf8.update(digest,value==null?"source-null-v1":"source-string-v1\n");
        if(value!=null)Utf8.update(digest,value);return hex(digest.digest());
    }
    public static String combined(String text,String structure,String coverage,String candidate){
        MessageDigest digest=sha();Utf8.update(digest,"source-business-content-v1\n");
        for(String value:Arrays.asList(text,structure,coverage,candidate)) {if(!valid(value))throw new IllegalArgumentException("Missing source content digest");Utf8.update(digest,value);digest.update((byte)'\n');}
        return hex(digest.digest());
    }
    public static String actual(SourceDocument source){
        String known=source.getVerifiedBusinessContentSha256();if(known!=null)return known;
        String result=combined(field(source.getTextContent()),field(source.getStructuredContentJson()),field(source.getParseCoverageJson()),field(source.getNormalCandidateJson()));
        source.setVerifiedBusinessContentSha256(result);return result;
    }
    public static boolean stamped(SourceDocument source){
        return valid(source.getTextContentSha256())&&valid(source.getStructuredContentSha256())&&valid(source.getParseCoverageSha256())
                &&valid(source.getNormalCandidateSha256())&&valid(source.getBusinessContentSha256())
                &&source.getBusinessContentSha256().equals(combined(source.getTextContentSha256(),source.getStructuredContentSha256(),source.getParseCoverageSha256(),source.getNormalCandidateSha256()));
    }
    public static void verifyLoaded(SourceDocument source){
        if(source.getBusinessContentSha256()!=null&&(!stamped(source)||!actual(source).equals(source.getBusinessContentSha256())))
            throw new IllegalArgumentException("Source content does not match database-maintained revision");
    }
    public static void requireCurrent(SourceDocument captured,SourceDocument current){
        if(!ProjectDocumentScope.sameScopeRevision(captured,current)||!stamped(current)||!actual(captured).equals(current.getBusinessContentSha256()))
            throw new IllegalArgumentException("Source content or authorization changed");
    }
    /** Bind only a locally prepared source to the verified, database-owned content revision. */
    public static void rebindVerifiedCurrent(SourceDocument captured,SourceDocument current){
        requireCurrent(captured,current);
        captured.setTextContentSha256(current.getTextContentSha256());
        captured.setStructuredContentSha256(current.getStructuredContentSha256());
        captured.setParseCoverageSha256(current.getParseCoverageSha256());
        captured.setNormalCandidateSha256(current.getNormalCandidateSha256());
        captured.setBusinessContentSha256(current.getBusinessContentSha256());
        verifyLoaded(captured);
    }
    public static boolean valid(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){char[] result=new char[bytes.length*2],digits="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){result[i*2]=digits[(bytes[i]&255)>>>4];result[i*2+1]=digits[bytes[i]&15];}return new String(result);}
}
