package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.document.DocumentParser.ParsedDocument;
import com.consense.document.OerTemplateEvidence;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.multipart.MultipartFile;

/** Chooses primary assets; filename hints never establish an analysed source identity. */
final class DraftTemplateRouting {
    static final String AUXILIARY_STATUS="UNSELECTED_AUXILIARY_CANDIDATE";
    private DraftTemplateRouting() { }
    static boolean isAuxiliary(SourceDocument source){return source.getFileKey()==null&&SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(source.getCategory())&&AUXILIARY_STATUS.equals(source.getParseStatus());}
    static boolean isUnadopted(SourceDocument source){return isAuxiliary(source)||source.getFileKey()==null&&SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(source.getCategory())&&"IDENTITY_PENDING".equals(source.getParseStatus());}

    static final class Upload {
        final MultipartFile file;final byte[] bytes;final String hash,format;
        ParsedDocument parsed;String fileKey;SourceDocument identitySource;
        Upload(MultipartFile file,byte[] bytes){this.file=file;this.bytes=bytes;hash=sha(bytes);format=format(bytes);}
    }

    static String sha(byte[] bytes) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String format(byte[] bytes) {
        if(bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-')return "pdf";
        if(bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==3&&bytes[3]==4)return "docx";
        return "unknown";
    }
    static String filenameHint(String filename) {
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);Set<String> hints=new LinkedHashSet<>();
        for(String key:DraftBlueprint.TEMPLATE_KEYS)if(Pattern.compile("(?i)(?<![a-z])"+key+"(?![a-z])").matcher(name).find())hints.add(key);
        if(name.contains("notes to tenderers"))hints.add("NTT");
        if(name.contains("special conditions of tender")||name.contains("special condition of tender"))hints.add("SCT");
        if(name.contains("special conditions of contract")||name.contains("special condition of contract"))hints.add("SCC");
        return hints.size()==1?hints.iterator().next():null;
    }
    static void bind(Upload upload,ParsedDocument parsed,String expectedKey) {
        if(parsed==null||"FAILED".equals(parsed.getParseStatus()))throw new BizException(4003,"TEMPLATE_PARSE_FAILED: "+(parsed==null?"No parser result":parsed.getMessage()));
        JsonNode evidence=parsed.getCoverage()==null?null:parsed.getCoverage().getTemplateEvidence();
        if(evidence==null||!evidence.isObject())throw new BizException(4012,"TEMPLATE_EVIDENCE_MISSING: No source-bound template evidence was returned.");
        String actualKey=evidence.path("sourceBinding").path("fileKey").asText();
        if(!DraftBlueprint.TEMPLATE_KEYS.contains(actualKey))throw new BizException(4012,"TEMPLATE_FAMILY_UNKNOWN: The source content does not identify NTT, SCT or SCC.");
        if(expectedKey!=null&&!expectedKey.equals(actualKey))throw new BizException(4012,"TEMPLATE_FAMILY_MISMATCH: Expected "+expectedKey+" but the source identifies "+actualKey+".");
        if(!upload.hash.equals(parsed.getSourceSha256()))throw new BizException(4012,"TEMPLATE_SOURCE_HASH_MISMATCH");
        OerTemplateEvidence.requireBound(actualKey,upload.hash,JsonUtils.mapper().valueToTree(parsed.getBlocks()),JsonUtils.mapper().valueToTree(parsed.getCoverage()),evidence);
        upload.fileKey=actualKey;upload.parsed=parsed;
    }
    static Upload primary(List<Upload> uploads) {
        List<Upload> preferred=new ArrayList<>();for(Upload upload:uploads)if("docx".equals(upload.format))preferred.add(upload);
        if(preferred.isEmpty())preferred.addAll(uploads);
        Upload selected=null;
        for(Upload upload:preferred){if(selected==null)selected=upload;else if(!selected.hash.equals(upload.hash))throw new BizException(4012,"TEMPLATE_VERSION_CONFLICT: Multiple different "+upload.fileKey+" primary sources require an explicit selection or replacement.");}
        if(selected==null)throw new BizException(4012,"TEMPLATE_SOURCE_MISSING");return selected;
    }
}
