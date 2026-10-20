package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.OerTemplateEvidence;
import com.consense.domain.SourceDocument;
import com.consense.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Source-bound review projection. It never invokes the document parser, OCR or a model. */
@Component
public class DraftReviewSources {
    private final StorageService storage;
    private final Path root;

    public DraftReviewSources(StorageService storage, ConsenseProperties properties) {
        this.storage=storage;
        root=Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    public ArrayNode sources(List<SourceDocument> documents) {
        ArrayNode result=array();
        for(SourceDocument source:documents) {
            if(!eligible(source))continue;
            try { result.add(metadata(source,load(source))); }
            catch(BizException unavailable) {
                ObjectNode item=base(source);item.put("format","unsupported");item.put("canEdit",false);
                item.put("evidenceStatus","unavailable");item.put("issue",unavailable.getMessage());result.add(item);
            }
        }
        return result;
    }

    public ObjectNode reading(SourceDocument source) {
        Loaded loaded=load(source);ObjectNode result=metadata(source,loaded);
        ArrayNode paragraphs=result.putArray("paragraphs");
        if(loaded.index!=null)for(DocxTemplateEditor.Paragraph paragraph:loaded.index.getMainParagraphs()) {
            ObjectNode value=paragraphs.addObject().put("id",paragraph.getId()).put("ordinal",paragraph.getOrdinal())
                .put("text",paragraph.getText()).put("paragraphSha256",paragraph.getSha256())
                .put("sourceSha256",loaded.sha).put("channel","editableText");
            value.putObject("sourcePosition").put("sourceSha256",loaded.sha).put("partName",paragraph.getPart()).put("nodePath",paragraph.getNativeNodePath());
        }
        JsonNode storedBlocks=JsonUtils.parse(source.getStructuredContentJson());
        if(storedBlocks.isArray())result.set("blocks",storedBlocks);
        else if(storedBlocks.path("blocks").isArray())result.set("blocks",storedBlocks.path("blocks"));
        else if(storedBlocks.path("data").isArray())result.set("blocks",storedBlocks.path("data"));
        else {result.set("blocks",array());result.set("structuredContentObservation",storedBlocks);}
        result.put("text",source.getTextContent()==null?"":source.getTextContent());
        result.put("physicalWordPagesStatus","docx".equals(loaded.format)?"unknown_requires_rendition":"not_word");
        JsonNode evidence=boundEvidence(source,loaded);
        for(String field:Arrays.asList("sourceMembers","guidanceNotes","markerCandidates","groups","conditions","issues"))
            result.set(field,evidence!=null?evidence.path(field).deepCopy():array());
        if(evidence!=null)result.set("structure",evidence.path("structure").deepCopy());
        return result;
    }

    /** The caller resolves the project source; the browser cannot choose a filesystem path. */
    public ObjectNode bind(SourceDocument source,JsonNode passage) {
        return bind(source,load(source),passage);
    }

    /** One graph save reuses its verified originals; nothing is cached across requests. */
    public BoundSource bindSource(SourceDocument source) {return new BoundSource(source,load(source));}

    public final class BoundSource {
        private final SourceDocument source;private final Loaded loaded;
        private BoundSource(SourceDocument source,Loaded loaded){this.source=source;this.loaded=loaded;}
        public ObjectNode bind(JsonNode passage){return DraftReviewSources.this.bind(source,loaded,passage);}
        public ObjectNode bindObservation(JsonNode passage){return DraftReviewSources.this.bindObservation(source,loaded,passage);}
    }

    private ObjectNode bind(SourceDocument source,Loaded loaded,JsonNode passage) {
        ObjectNode description=metadata(source,loaded);
        String docId=passage.path("docId").asText();
        if(!docId.isEmpty()&&!docId.equals(description.path("id").asText())
                &&!docId.equals(description.path("docId").asText())&&!docId.equals(description.path("editionId").asText()))
            return pending("unsafe",loaded.sha,"SOURCE_EDITION_MISMATCH");
        if(passage.has("sourceDocumentId")&&!String.valueOf(source.getId()).equals(passage.path("sourceDocumentId").asText()))
            return pending("unsafe",loaded.sha,"SOURCE_DOCUMENT_MISMATCH");
        return bindTarget(loaded.bytes,loaded.prepared,JsonUtils.mapper().convertValue(passage,Map.class));
    }

    /** A named, saved source observation can support review without acquiring editable coordinates. */
    public ObjectNode bindObservation(SourceDocument source,JsonNode passage) {
        return bindObservation(source,load(source),passage);
    }

    private ObjectNode bindObservation(SourceDocument source,Loaded loaded,JsonNode passage) {
        String sha=loaded.sha;JsonNode evidence=boundEvidence(source,loaded);
        if(evidence==null)return pending("missing",sha,"CURRENT_SOURCE_OBSERVATIONS_REQUIRED");
        if(!sha.equals(passage.path("sourceSha256").asText()))return pending("unsafe",sha,"SOURCE_HASH_MISMATCH");
        if(!String.valueOf(source.getId()).equals(passage.path("sourceDocumentId").asText()))return pending("unsafe",sha,"SOURCE_DOCUMENT_MISMATCH");
        JsonNode binding=evidence.path("sourceBinding");
        for(String[] key:new String[][]{{"analysisRevision","analysisRevision"},{"analysisInputSha256","inputSha256"},{"analysisSha256","generationSha256"}})if(!passage.path(key[0]).equals(binding.path(key[1])))return pending("unsafe",sha,"SOURCE_OBSERVATION_ANALYSIS_CHANGED");
        JsonNode original=null;String observationId=passage.path("sourceObservationId").asText();
        if(observationId.isEmpty())return pending("missing",sha,"SOURCE_OBSERVATION_ID_REQUIRED");
        for(String kind:Arrays.asList("guidanceNotes","markerCandidates","groups"))for(JsonNode candidate:evidence.path(kind))if(observationId.equals(candidate.path("id").asText())){if(original!=null)return pending("unsafe",sha,"AMBIGUOUS_SOURCE_OBSERVATION");original=candidate;}
        if(original==null||!original.equals(passage.path("sourceObservation")))return pending("unsafe",sha,"SOURCE_OBSERVATION_OBJECT_CHANGED");
        JsonNode ranges=passage.path("observedRanges");if(!ranges.isArray()||ranges.isEmpty())return pending("missing",sha,"SOURCE_OBSERVATION_RANGES_REQUIRED");
        if(!ranges.equals(original.path("memberRanges")))return pending("unsafe",sha,"SOURCE_OBSERVATION_SCOPE_CHANGED");
        if(!original.path("text").isTextual()||!original.path("text").equals(passage.path("text")))return pending("unsafe",sha,"SOURCE_OBSERVATION_QUOTE_MISMATCH");
        StringJoiner selected=new StringJoiner("\n");
        for(JsonNode range:ranges) {
            JsonNode member=null;for(JsonNode candidate:evidence.path("sourceMembers"))if(range.path("memberId").asText().equals(candidate.path("id").asText())){if(member!=null)return pending("unsafe",sha,"AMBIGUOUS_SOURCE_MEMBER");member=candidate;}
            if(member==null)return pending("unsafe",sha,"SOURCE_MEMBER_NOT_BOUND");
            if("docx".equals(loaded.format)) {
                if(!sha.equals(member.path("sourcePosition").path("sourceSha256").asText()))return pending("unsafe",sha,"SOURCE_MEMBER_NOT_BOUND");
            }else if("pdf".equals(loaded.format)) {
                JsonNode block=member.path("blockObservation"),context=block.path("readingContext"),provenance=range.path("pdfGlyphProvenance");
                if(!sha.equals(context.path("sourceSha256").asText())||!member.path("id").equals(block.path("id"))||block.path("source").asText().isEmpty()||!block.path("pageNo").isIntegralNumber()||block.path("pageNo").asInt()<1||block.path("pageNo").asInt()>evidence.path("structure").path("physicalPages").asInt())return pending("unsafe",sha,"PDF_SOURCE_MEMBER_NOT_BOUND");
                if(context.hasNonNull("occurrenceId")&&!context.path("occurrenceId").equals(block.path("id"))||provenance.hasNonNull("blockId")&&!provenance.path("blockId").equals(block.path("id"))||provenance.hasNonNull("pageNo")&&!provenance.path("pageNo").equals(block.path("pageNo")))return pending("unsafe",sha,"PDF_SOURCE_POSITION_MISMATCH");
            }else return pending("missing",sha,"SOURCE_OBSERVATION_FORMAT_UNSUPPORTED");
            if(range.hasNonNull("sourcePosition")&&!range.path("sourcePosition").equals(member.path("sourcePosition")))return pending("unsafe",sha,"SOURCE_POSITION_MISMATCH");
            String channel=range.path("channel").asText("text"),name=channel.startsWith("preNormalizedTextObservation.")?channel.substring("preNormalizedTextObservation.".length()):channel;
            if(!Arrays.asList("text","originalText","deletedText","strikeText").contains(name))return pending("unsafe",sha,"NAMED_TEXT_CHANNEL_MISMATCH");
            JsonNode value=member.path(channel.startsWith("preNormalizedTextObservation.")?"preNormalizedTextObservation":"textObservation").path(name);
            if(!value.isTextual()||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber()||!range.path("start").canConvertToInt()||!range.path("end").canConvertToInt())return pending("unsafe",sha,"INVALID_SOURCE_OBSERVATION_RANGE");
            String text=value.asText();int start=range.path("start").asInt(),end=range.path("end").asInt();
            if(start<0||end<=start||end>text.length())return pending("unsafe",sha,"SOURCE_OBSERVATION_RANGE_MISMATCH");
            for(int offset:new int[]{start,end})if(offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset)))return pending("unsafe",sha,"INVALID_UTF16_BOUNDARY");
            String quote=text.substring(start,end);if(range.has("observedText")&&!quote.equals(range.path("observedText").asText()))return pending("unsafe",sha,"SOURCE_OBSERVATION_TEXT_MISMATCH");selected.add(quote);
        }
        if(!passage.path("text").isTextual()||!passage.path("text").asText().equals(selected.toString()))return pending("unsafe",sha,"SOURCE_OBSERVATION_QUOTE_MISMATCH");
        ObjectNode result=object().put("status","bound").put("kind","named_saved_source_observation").put("sourceSha256",sha).put("sourceObservationId",observationId).put("editableAuthority",false);result.set("observedRanges",ranges.deepCopy());
        for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))result.set(key,passage.path(key).deepCopy());return result;
    }

    /** Exact editableText spans only. Unbound legacy page selections remain reviewable. */
    public static ObjectNode bindTarget(byte[] original,Map<String,Object> target) {
        return bindTarget(original,null,target);
    }

    private static ObjectNode bindTarget(byte[] original,DocxTemplateEditor.PreparedTemplate prepared,Map<String,Object> target) {
        String sha=DraftTemplateRouting.sha(original);JsonNode passage=JsonUtils.mapper().valueToTree(target);
        if(passage.has("sourceSha256")&&!sha.equals(passage.path("sourceSha256").asText()))return pending("unsafe",sha,"SOURCE_HASH_MISMATCH");
        if(!"docx".equals(DraftTemplateRouting.format(original)))return pending("missing",sha,"EDITABLE_DOCX_REQUIRED");
        DocxTemplateEditor.TemplateIndex index;
        try { if(prepared==null)prepared=new DocxTemplateEditor().prepare(original);index=prepared.getIndex(); }
        catch(DocxTemplateEditor.EditException invalid){return pending("unsafe",sha,invalid.getCode());}
        JsonNode requested=passage.path("native");
        if(requested.isMissingNode()||requested.isNull())requested=passage.path("nativeBinding");
        if(!requested.isMissingNode()&&!requested.isNull()&&!requested.isObject())return pending("unsafe",sha,"INVALID_NATIVE_BINDING");
        ObjectNode result=object();result.put("status","bound");result.put("sourceSha256",sha);ArrayNode spans=result.putArray("spans");
        if(requested.isObject()) {
            JsonNode entries=requested.has("spans")?requested.path("spans"):array().add(requested);
            if(!entries.isArray()||entries.size()==0)return pending("missing",sha,"NATIVE_SPANS_REQUIRED");
            boolean insertion="insert".equals(passage.path("action").asText());
            if(insertion&&entries.size()!=1)return pending("unsafe",sha,"ONE_INSERTION_POINT_REQUIRED");
            if(requested.has("sourceSha256")&&!sha.equals(requested.path("sourceSha256").asText()))return pending("unsafe",sha,"SOURCE_HASH_MISMATCH");
            for(JsonNode entry:entries) {
                ObjectNode checked=exactSpan(index,entry,sha,requested.path("sourceSha256").asText(),insertion);
                if(!"bound".equals(checked.path("status").asText()))return checked;
                spans.add(checked.path("span"));
            }
            if(passage.has("text")) {
                StringJoiner displayed=new StringJoiner("\n");for(JsonNode span:spans)displayed.add(span.path("expectedText").asText());
                if(!passage.path("text").asText().equals(displayed.toString()))return pending("unsafe",sha,"SELECTED_TEXT_AND_NATIVE_SPAN_DIFFER");
            }
        } else {
            if("insert".equals(passage.path("action").asText()))return pending("missing",sha,"EXPLICIT_INSERTION_POINT_REQUIRED");
            if(sparse(passage.path("wordIds")))return pending("missing",sha,"SPARSE_SELECTION_REQUIRES_NATIVE_SPANS");
            String quote=passage.path("text").asText();if(quote.isEmpty())return pending("missing",sha,"EXACT_SOURCE_TEXT_REQUIRED");
            for(DocxTemplateEditor.Paragraph paragraph:index.getParagraphs()) {
                String text=paragraph.getText();int at=text.indexOf(quote);
                while(at>=0) {
                    spans.add(span(paragraph,at,at+quote.length(),quote,sha));
                    if(spans.size()>1)return pending("ambiguous",sha,"SOURCE_TEXT_HAS_MULTIPLE_MATCHES");
                    at=text.indexOf(quote,at+1);
                }
            }
            if(spans.size()==0)return pending("missing",sha,"SOURCE_TEXT_NOT_FOUND");
        }
        for(int i=0;i<spans.size();i++)for(int j=i+1;j<spans.size();j++) {
            JsonNode a=spans.get(i),b=spans.get(j);
            if(a.path("paragraphId").equals(b.path("paragraphId"))&&DocxTemplateEditor.overlappingSpans(a.path("start").asInt(),a.path("end").asInt(),b.path("start").asInt(),b.path("end").asInt()))
                return pending("unsafe",sha,"OVERLAPPING_NATIVE_SPANS");
        }
        List<DocxTemplateEditor.Edit> checks=new ArrayList<>();
        for(int n=0;n<spans.size();n++) {
            JsonNode selected=spans.get(n);DocxTemplateEditor.Paragraph paragraph=index.paragraph(selected.path("paragraphId").asText());
            checks.add("insert".equals(selected.path("operation").asText())?DocxTemplateEditor.Edit.insertText("review-check-"+n,paragraph,selected.path("start").asInt(),""):DocxTemplateEditor.Edit.replaceSpan("review-check-"+n,paragraph,selected.path("start").asInt(),selected.path("end").asInt(),selected.path("expectedText").asText(),selected.path("expectedText").asText()));
        }
        try { prepared.validateTextEdits(new DocxTemplateEditor.SourceEditBatch(sha,checks)); }
        catch(DocxTemplateEditor.EditException unsafe){return pending("unsafe",sha,unsafe.getCode());}
        return result;
    }

    /** Content-addressed original snapshot; replace of a SourceDocument cannot retarget this asset. */
    public ObjectNode archive(SourceDocument source) {
        Loaded loaded=load(source);ObjectNode manifest=metadata(source,loaded);String key=loaded.sha+".bin";
        try {
            Path directory=root.resolve(".draft-review-originals");Files.createDirectories(directory);
            Path canonical=directory.toRealPath();if(!canonical.startsWith(root.toRealPath()))throw new IOException("Archive directory escapes storage");
            Path target=canonical.resolve(key);
            try { Files.write(target,loaded.bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
            catch(FileAlreadyExistsException existing) { /* The immutable existing object must verify below. */ }
            if(!target.toRealPath().startsWith(canonical)||!Files.isRegularFile(target))throw new IOException("Archive path is invalid");
            if(!loaded.sha.equals(DraftTemplateRouting.sha(Files.readAllBytes(target))))throw new IOException("Archived original does not match its identity");
            manifest.put("archiveKey",key);return manifest;
        }catch(IOException failure){throw new BizException(4012,"REVIEW_SOURCE_ARCHIVE_FAILED");}
    }

    public byte[] archivedBytes(JsonNode manifest) {
        String sha=manifest.path("sourceSha256").asText(),key=manifest.path("archiveKey").asText();
        if(!sha.matches("[a-f0-9]{64}")||!key.equals(sha+".bin"))throw new BizException(4012,"REVIEW_SOURCE_ARCHIVE_IDENTITY_INVALID");
        try {
            Path directory=root.resolve(".draft-review-originals").toRealPath(),target=directory.resolve(key).toRealPath();
            if(!directory.startsWith(root.toRealPath())||!target.startsWith(directory)||!Files.isRegularFile(target))throw new IOException("Archive path is invalid");
            byte[] bytes=Files.readAllBytes(target);if(!sha.equals(DraftTemplateRouting.sha(bytes)))throw new IOException("Archived bytes changed");return bytes;
        }catch(IOException failure){throw new BizException(4012,"REVIEW_SOURCE_ARCHIVE_UNAVAILABLE");}
    }

    private Loaded load(SourceDocument source) {
        if(!eligible(source)||source.getId()==null)throw new BizException(4012,"REVIEW_SOURCE_OUTSIDE_DRAFTING");
        try {
            Path path=Paths.get(source.getStoragePath()).toAbsolutePath().normalize().toRealPath();
            if(!path.startsWith(root.toRealPath())||!Files.isRegularFile(path))throw new IOException("Original is outside storage");
            byte[] bytes;
            try { bytes=storage.read(path.toString()); }
            catch(BizException unavailable){throw new BizException(4012,"REVIEW_SOURCE_UNAVAILABLE");}
            String sha=DraftTemplateRouting.sha(bytes);
            if(source.getSourceSha256()!=null&&!sha.equals(source.getSourceSha256()))throw new BizException(4012,"REVIEW_SOURCE_BYTES_CHANGED");
            String format=DraftTemplateRouting.format(bytes);DocxTemplateEditor.PreparedTemplate prepared=null;
            if("docx".equals(format))try{prepared=new DocxTemplateEditor().prepare(bytes);}catch(DocxTemplateEditor.EditException invalid){format="unsupported";}
            if("unknown".equals(format))format="unsupported";
            return new Loaded(bytes,sha,format,prepared);
        }catch(IOException|java.nio.file.InvalidPathException|NullPointerException failure){throw new BizException(4012,"REVIEW_SOURCE_UNAVAILABLE");}
    }

    private ObjectNode metadata(SourceDocument source,Loaded loaded) {
        ObjectNode result=base(source);result.put("sourceSha256",loaded.sha);result.put("sourceHash",DraftAdoption.sourceHash(source));
        result.put("format",loaded.format);result.put("canEdit",loaded.index!=null);
        String family=source.getFileKey();JsonNode coverage=coverage(source,loaded),saved=coverage.path("templateEvidence");
        if((family==null||family.isEmpty())&&saved.isObject())family=saved.path("sourceBinding").path("fileKey").asText();
        if(family==null||family.isEmpty())family="source";
        String docId=String.valueOf(source.getId());
        if(SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(source.getCategory())&&Arrays.asList("NTT","SCT","SCC").contains(source.getFileKey())) {
            docId=family.toLowerCase(java.util.Locale.ROOT);if("SCC".equals(family))docId="scc-"+("docx".equals(loaded.format)?"word":"pdf");
        }
        result.put("docId",docId);result.put("editionId",family.toLowerCase(java.util.Locale.ROOT)+"-"+loaded.format+"-"+loaded.sha);
        ObjectNode coverageSummary=result.putObject("coverage");
        for(String field:Arrays.asList("totalPages","parsedPages","ocrPages","blankPages","failedPages","complete","ocrQualityStatus","needsReviewPages","ocrQualityPageScopeUnknown","limitations"))
            if(coverage.has(field))coverageSummary.set(field,coverage.path(field).deepCopy());
        if(saved.isObject())result.set("analysisRevision",saved.path("sourceBinding").path("analysisRevision").deepCopy());else result.putNull("analysisRevision");
        if(saved.isObject()) {
            result.set("analysisInputSha256",saved.path("sourceBinding").path("inputSha256").deepCopy());
            result.set("analysisSha256",saved.path("sourceBinding").path("generationSha256").deepCopy());
        }
        result.put("evidenceStatus",saved.isObject()?boundEvidence(source,loaded)!=null?"bound":"invalid":"missing");
        result.put("editableCoordinates","editableText_utf16_start_inclusive_end_exclusive");return result;
    }

    private static ObjectNode base(SourceDocument source) {
        ObjectNode result=object();result.put("id",String.valueOf(source.getId()));result.put("sourceDocumentId",source.getId());
        result.put("category",source.getCategory());result.put("fileKey",source.getFileKey());result.put("fileName",source.getFileName());result.put("parseStatus",source.getParseStatus());return result;
    }

    private static JsonNode boundEvidence(SourceDocument source,Loaded loaded) {
        if(loaded.evidenceChecked)return loaded.evidence;
        loaded.evidenceChecked=true;JsonNode coverage=coverage(source,loaded),evidence=coverage.path("templateEvidence");
        if(!evidence.isObject())return null;
        String fileKey=source.getFileKey();if(fileKey==null||fileKey.isEmpty())fileKey=evidence.path("sourceBinding").path("fileKey").asText();
        try {loaded.evidence=OerTemplateEvidence.requireBound(fileKey,loaded.sha,JsonUtils.parse(source.getStructuredContentJson()),coverage,evidence);return loaded.evidence;}
        catch(IllegalArgumentException invalid){return null;}
    }

    private static JsonNode coverage(SourceDocument source,Loaded loaded) {
        if(loaded.coverage==null)loaded.coverage=JsonUtils.parse(source.getParseCoverageJson());return loaded.coverage;
    }

    private static ObjectNode exactSpan(DocxTemplateEditor.TemplateIndex index,JsonNode entry,String sha,String parentSha,boolean insertion) {
        if(!entry.isObject()||!sha.equals(entry.has("sourceSha256")?entry.path("sourceSha256").asText():parentSha))return pending("unsafe",sha,"SOURCE_HASH_MISMATCH");
        if(entry.has("channel")&&!"editableText".equals(entry.path("channel").asText()))return pending("unsafe",sha,"NAMED_TEXT_CHANNEL_MISMATCH");
        DocxTemplateEditor.Paragraph paragraph;
        try { paragraph=index.paragraph(entry.path("paragraphId").asText()); }
        catch(DocxTemplateEditor.EditException absent){return pending("missing",sha,"NATIVE_PARAGRAPH_NOT_FOUND");}
        if(!paragraph.getSha256().equals(entry.path("paragraphSha256").asText()))return pending("unsafe",sha,"NATIVE_PARAGRAPH_HASH_MISMATCH");
        if(!entry.path("start").canConvertToInt()||!entry.path("end").canConvertToInt()||!entry.path("start").isIntegralNumber()||!entry.path("end").isIntegralNumber()||!entry.path("expectedText").isTextual())return pending("unsafe",sha,"INVALID_NATIVE_SPAN");
        int start=entry.path("start").asInt(),end=entry.path("end").asInt();String text=paragraph.getText(),expected=entry.path("expectedText").asText();
        boolean explicitInsertion="insert".equals(entry.path("operation").asText());
        if(insertion!=explicitInsertion||start<0||end<start||end>text.length()||insertion&&start!=end||!insertion&&start==end||!text.substring(start,end).equals(expected))return pending("unsafe",sha,"NATIVE_TEXT_EXPECTATION_MISMATCH");
        for(int offset:new int[]{start,end})if(offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset)))return pending("unsafe",sha,"INVALID_UTF16_BOUNDARY");
        ObjectNode result=object(),selected=span(paragraph,start,end,expected,sha);if(insertion)selected.put("operation","insert");result.put("status","bound");result.set("span",selected);return result;
    }

    private static ObjectNode span(DocxTemplateEditor.Paragraph paragraph,int start,int end,String expected,String sha) {
        return object().put("paragraphId",paragraph.getId()).put("paragraphSha256",paragraph.getSha256()).put("start",start).put("end",end).put("expectedText",expected).put("sourceSha256",sha).put("channel","editableText");
    }
    private static ObjectNode pending(String status,String sha,String reason){return object().put("status",status).put("sourceSha256",sha).put("reason",reason).set("spans",array());}
    private static boolean sparse(JsonNode ids){if(!ids.isArray()||ids.size()<2)return false;List<Integer> sorted=new ArrayList<>();for(JsonNode id:ids){if(!id.isIntegralNumber())return true;sorted.add(id.asInt());}java.util.Collections.sort(sorted);for(int n=1;n<sorted.size();n++)if(sorted.get(n)!=sorted.get(n-1)+1)return true;return false;}
    private static boolean eligible(SourceDocument source){return source!=null&&Arrays.asList(SourceDocument.CATEGORY_STANDARD_TEMPLATE,SourceDocument.CATEGORY_PROJECT_INPUT).contains(source.getCategory());}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static ArrayNode array(){return JsonUtils.mapper().createArrayNode();}
    private static final class Loaded {
        final byte[] bytes;final String sha,format;final DocxTemplateEditor.TemplateIndex index;final DocxTemplateEditor.PreparedTemplate prepared;
        JsonNode coverage,evidence;boolean evidenceChecked;
        Loaded(byte[] bytes,String sha,String format,DocxTemplateEditor.PreparedTemplate prepared){this.bytes=bytes;this.sha=sha;this.format=format;this.prepared=prepared;index=prepared==null?null:prepared.getIndex();}
    }
}
