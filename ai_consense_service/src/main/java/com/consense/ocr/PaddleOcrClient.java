package com.consense.ocr;

import com.consense.ai.HttpSupport;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * PaddleOCR / PP-Structure 客户端。
 *
 * 兼容两种常见部署形态：
 *  classic    —— POST {base}{ocrPath}，body {"images":["<base64>"]}，返回 results 二维数组
 *  hubserving —— POST {base}{structurePath}，multipart 字段名 image
 *  local      —— POST {base}/ocr，multipart 字段名 file；GET /health
 *
 * 返回体结构在不同版本间差异较大，这里做宽松解析：只要能拿到 text 字段就收集。
 */
@Slf4j
@RequiredArgsConstructor
public class PaddleOcrClient implements OcrClient {

    private final ConsenseProperties.Ocr cfg;
    private final HttpSupport http;

    @Override
    public OcrResult recognize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new OcrResult("", 0.0, Collections.emptyList());
        }
        if ("local".equalsIgnoreCase(cfg.getMode())) {
            return recognizeLocal(imageBytes);
        }
        String raw = "hubserving".equalsIgnoreCase(cfg.getMode())
                ? callHubServing(imageBytes)
                : callClassic(imageBytes);

        List<OcrLine> lines = new ArrayList<>();
        collect(raw, lines);
        if (lines.isEmpty()) {
            log.warn("OCR 未解析出文本，原始响应片段: {}", abbreviate(raw));
            return new OcrResult("", 0.0, Collections.emptyList());
        }
        double sum = 0;
        StringBuilder builder = new StringBuilder();
        for (OcrLine line : lines) {
            builder.append(line.getText()).append('\n');
            sum += line.getConfidence();
        }
        return new OcrResult(builder.toString().trim(), sum / lines.size(), lines);
    }

    @Override public OcrResult observeIdentity(byte[] imageBytes) {
        return "local".equalsIgnoreCase(cfg.getMode()) ? recognizeLocal(imageBytes, true) : recognize(imageBytes);
    }
    @Override public OcrResult recognize(byte[] imageBytes, Map<String,Object> sourceContext) {
        return "local".equalsIgnoreCase(cfg.getMode()) && imageBytes != null && imageBytes.length > 0
                ? recognizeLocal(imageBytes, false, sourceContext) : recognize(imageBytes);
    }
    @Override public OcrResult observeIdentity(byte[] imageBytes, Map<String,Object> sourceContext) {
        return "local".equalsIgnoreCase(cfg.getMode()) && imageBytes != null && imageBytes.length > 0
                ? recognizeLocal(imageBytes, true, sourceContext) : observeIdentity(imageBytes);
    }
    @Override public boolean canReuseIdentityObservation(OcrResult observation) {
        return !"local".equalsIgnoreCase(cfg.getMode());
    }
    @Override public JsonNode observeNativePdf(byte[] originalPdf, Map<String,Object> nativeContext) {
        if(!"local".equalsIgnoreCase(cfg.getMode()))return null;
        if(nativeContext==null)throw new BizException("Native PDF request original identity or scope differs");
        JsonNode source=JsonUtils.mapper().valueToTree(nativeContext.get("source"));
        JsonNode selected=JsonUtils.mapper().valueToTree(nativeContext.get("selectedPhysicalPages"));
        if(originalPdf==null || originalPdf.length<5 || !new String(originalPdf,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")
                || !inputSha256(originalPdf).equals(source.path("sourceSha256").asText())
                || source.path("sourceFilename").asText().trim().isEmpty() || !"application/pdf".equals(source.path("mediaType").asText())
                || !source.path("pageCount").isIntegralNumber() || source.path("pageCount").asInt()<=0
                || !selected.isArray() || selected.isEmpty())
            throw new BizException("Native PDF request original identity or scope differs");
        Set<Integer> required=new LinkedHashSet<>();
        for(JsonNode page:selected)if(!page.isIntegralNumber() || page.asInt()<1
                || page.asInt()>source.path("pageCount").asInt() || !required.add(page.asInt()))
            throw new BizException("Native PDF request physical-page scope differs");
        Object evidence=nativeContext.get("javaNativeEvidence");
        if(evidence!=null && !(evidence instanceof List<?>))throw new BizException("Native PDF Java evidence must retain its page list");
        if(evidence!=null)for(Object page:(List<?>)evidence)if(!required.contains(nativeEvidencePage(page)))
            throw new BizException("Native PDF Java evidence physical-page scope differs");
        NativePdfBatches batches=new NativePdfBatches(originalPdf,nativeContext,source,new ArrayList<>(required));
        List<Integer> pages=new ArrayList<>(required);
        // shortcut: Compact contexts use four-page batches; subdivide retained bundles if actual replies exceed the byte cap.
        int pageCap="page-admission-frames-v1".equals(nativeContext.get("javaNativeEvidenceShape"))?4:NATIVE_PAGE_CAP;
        for(int start=0;start<pages.size();start+=pageCap)batches.observe(pages.subList(start,Math.min(start+pageCap,pages.size())));
        return batches.result();
    }

    private static final int NATIVE_PAGE_CAP=64, NATIVE_CONTEXT_CAP=32*1024*1024, NATIVE_RESPONSE_CAP=8*1024*1024;
    private static final String NATIVE_BATCH_SCHEMA="consense.native-pdf-batch-transport.v1";

    private static int nativeEvidencePage(Object evidence) {
        Object page=evidence instanceof com.consense.document.PdfPageEvidence?((com.consense.document.PdfPageEvidence)evidence).getPageNo()
                :evidence instanceof Map<?,?>?((Map<?,?>)evidence).get("pageNo")
                :evidence instanceof JsonNode?((JsonNode)evidence).path("pageNo"):null;
        JsonNode number=JsonUtils.mapper().valueToTree(page);
        if(!number.isIntegralNumber() || !number.canConvertToInt())throw new BizException("Native PDF Java evidence page number differs");
        return number.asInt();
    }

    private final class NativePdfBatches {
        private final byte[] original;
        private final Map<String,Object> context;
        private final JsonNode source;
        private final List<Integer> selected;
        private final ArrayNode records=JsonUtils.mapper().createArrayNode(),pages=JsonUtils.mapper().createArrayNode(),refs=JsonUtils.mapper().createArrayNode();
        private JsonNode singleResponse;
        private JsonNode predecessorBundle;
        private int predecessorLastPage;
        private BizException firstFailure;
        private boolean stopped,cleanupFailed;

        private NativePdfBatches(byte[] original,Map<String,Object> context,JsonNode source,List<Integer> selected) {
            this.original=original;this.context=context;this.source=source;this.selected=selected;
        }

        private void observe(List<Integer> batch) {
            if(stopped){recordFailure(batch,"not_executed",null,null);return;}
            com.consense.document.DocumentParser.progress("正在核对表格与版面，物理页："+batch);
            Map<String,Object> scoped=new LinkedHashMap<>(context);scoped.put("selectedPhysicalPages",batch);
            scoped.remove("predecessorBundleRef");
            if(predecessorBundle!=null && predecessorLastPage+1==batch.get(0))scoped.put("predecessorBundleRef",predecessorBundle);
            if(context.get("javaNativeEvidence") instanceof List<?>) {
                List<Object> evidence=new ArrayList<>();
                for(Object page:(List<?>)context.get("javaNativeEvidence"))if(batch.contains(nativeEvidencePage(page)))evidence.add(page);
                scoped.put("javaNativeEvidence",evidence);
            }
            Path file=null;JsonNode response=null;ObjectNode record=null;
            try {
                file=Files.createTempFile("consense-native-context-",".json");
                if(!writeNativeContext(file,scoped)) {
                    boolean cleaned=cleanupNativeContext(file,batch,null);file=null;if(!cleaned)return;
                    if(batch.size()==1)throw new BizException("Native PDF single-page context exceeds 33554432 bytes; complete evidence was not truncated");
                    int middle=batch.size()/2;observe(batch.subList(0,middle));observe(batch.subList(middle,batch.size()));return;
                }
                MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file",source.path("sourceFilename").asText(),RequestBody.create(original,MediaType.get("application/pdf")))
                        .addFormDataPart("nativeContext","native-context.json",nativeContextBody(file)).build();
                String raw=http.postMultipart(trim(cfg.getBaseUrl())+"/native-pdf/table-candidates",body,cfg.getTimeoutMs(),Collections.emptyMap(),NATIVE_RESPONSE_CAP);
                if(raw==null)throw new BizException("Native PDF response is absent; no candidate was accepted");
                response=JsonUtils.parse(raw);validateNativePdfResponse(response,source,batch);
                singleResponse=response;
                record=((ObjectNode)response).deepCopy();record.remove("pages");record.put("batchIndex",records.size());records.add(record);
                ArrayNode observed=record.putArray("responseObservedPhysicalPages");for(JsonNode page:response.path("pages"))observed.add(page.path("physicalPageNo"));
                refs.add(response.path("bundleRef"));
                if(!response.path("pages").isEmpty()) {
                    predecessorBundle=response.path("bundleRef").deepCopy();
                    predecessorLastPage=batch.get(batch.size()-1);
                }
                for(JsonNode page:response.path("pages")) {
                    ObjectNode bound=((ObjectNode)page).deepCopy();bound.set("bundleRef",response.path("bundleRef"));
                    if(!bound.has("coverage") && response.has("coverage"))bound.set("coverage",response.path("coverage"));
                    pages.add(bound);
                }
                if("failed".equals(response.path("status").asText()) && response.path("pages").isEmpty())stopped=true;
            } catch(Exception failure) {
                BizException actual=failure instanceof BizException?(BizException)failure:new BizException("Native PDF batch transport failed: "+failure.getMessage());
                if(firstFailure==null)firstFailure=actual;
                record=recordFailure(batch,"failed",actual,response);stopped=true;
            } finally {
                if(file!=null)cleanupNativeContext(file,batch,record);
            }
        }

        private ObjectNode recordFailure(List<Integer> batch,String status,BizException failure,JsonNode invalid) {
            ObjectNode record=records.addObject();record.put("batchIndex",records.size()-1);record.put("status",status);
            record.set("source",source);record.set("selectedPhysicalPages",JsonUtils.mapper().valueToTree(batch));
            record.putArray("responseObservedPhysicalPages");
            ObjectNode reason=record.putObject("failure");reason.put("type",failure==null?"NotExecutedAfterBatchFailure":"NativePdfBatchTransportFailure");
            reason.put("message",failure==null?"An earlier batch failed; this batch was not dispatched":failure.getMessage());
            if(invalid!=null)record.set("invalidResponse",invalid);
            return record;
        }

        private boolean cleanupNativeContext(Path file,List<Integer> batch,ObjectNode record) {
            try{Files.deleteIfExists(file);return true;}catch(IOException failure) {
                if(record==null) {
                    BizException actual=new BizException("Native PDF temporary context cleanup failed before dispatch: "+failure.getMessage());
                    record=recordFailure(batch,"failed",actual,null);if(firstFailure==null)firstFailure=actual;
                }
                ObjectNode cleanup=record.putObject("transportCleanupFailure");cleanup.put("path",file.toAbsolutePath().toString());
                cleanup.put("type",failure.getClass().getSimpleName());cleanup.put("message",failure.getMessage());cleanup.put("cleanupStatus","not_cleaned");
                stopped=true;cleanupFailed=true;return false;
            }
        }

        private JsonNode result() {
            if(records.size()==1 && !cleanupFailed) {
                if(firstFailure!=null)throw firstFailure;
                return singleResponse;
            }
            ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("schema","consense.native-pdf-candidates.v1");
            result.put("transportSchema",NATIVE_BATCH_SCHEMA);result.set("source",source);
            result.set("selectedPhysicalPages",JsonUtils.mapper().valueToTree(selected));result.set("pages",pages);result.set("batchResults",records);
            ObjectNode bundle=result.putObject("bundleRef");bundle.put("schema","consense.native-pdf-batch-bundle-refs.v1");bundle.set("batchBundleRefs",refs);
            ArrayNode cleanup=result.putArray("transportCleanupFailures");for(JsonNode record:records)if(record.has("transportCleanupFailure"))cleanup.add(record.path("transportCleanupFailure"));
            Set<Integer> observed=new HashSet<>();int successes=0,failures=0;
            for(JsonNode page:pages) {
                observed.add(page.path("physicalPageNo").asInt());
                for(JsonNode method:page.path("methods"))if("failed".equals(method.path("status").asText()))failures++;else successes++;
            }
            ArrayNode missing=result.putArray("unobservedPhysicalPages");for(Integer page:selected)if(!observed.contains(page))missing.add(page);
            result.put("status",successes==0?"failed":failures>0 || !missing.isEmpty() || !cleanup.isEmpty()?"partial":"candidate_observed");
            result.put("sourceQualityAccepted",false);result.put("canonicalAdopted",false);
            if(!missing.isEmpty())result.putObject("failure").put("type","NativePdfBatchIncomplete").put("message","Complete requested denominator retained; unobserved pages are unknown");
            else if(!cleanup.isEmpty())result.putObject("failure").put("type","NativePdfContextCleanupFailure").put("message","Actual candidates retained; temporary native context was not cleaned");
            validateNativePdfCandidateResponse(result,source,selected);
            return result;
        }
    }

    private static boolean writeNativeContext(Path path,Map<String,Object> context) throws IOException {
        try(CappedNativeContext output=new CappedNativeContext(Files.newOutputStream(path))) {
            try{JsonUtils.mapper().writeValue(output,context);return true;}catch(IOException failure){if(output.exceeded)return false;throw failure;}
        }
    }

    private static RequestBody nativeContextBody(Path path) {
        RequestBody file=RequestBody.create(path.toFile(),MediaType.get("application/json"));
        return new RequestBody() {
            @Override public MediaType contentType(){return file.contentType();}
            @Override public long contentLength() throws IOException{return file.contentLength();}
            @Override public void writeTo(okio.BufferedSink sink) throws IOException{file.writeTo(sink);}
            @Override public boolean isOneShot(){return true;}
        };
    }

    private static final class CappedNativeContext extends FilterOutputStream {
        private long count;private boolean exceeded;
        private CappedNativeContext(OutputStream output){super(output);}
        private void reserve(int bytes) throws IOException {if(count+bytes>NATIVE_CONTEXT_CAP){exceeded=true;throw new IOException("Native PDF context byte cap exceeded");}count+=bytes;}
        @Override public void write(int value) throws IOException {reserve(1);out.write(value);}
        @Override public void write(byte[] value,int offset,int length) throws IOException {reserve(length);out.write(value,offset,length);}
    }

    /** Both real and alternate OCR clients must preserve the same complete source/batch contract. */
    public static void validateNativePdfCandidateResponse(JsonNode response,JsonNode source,List<Integer> selected) {
        if(!response.has("transportSchema")){validateNativePdfResponse(response,source,selected);return;}
        if(!NATIVE_BATCH_SCHEMA.equals(response.path("transportSchema").asText())
                || !"consense.native-pdf-candidates.v1".equals(response.path("schema").asText())
                || !response.path("source").equals(source) || !response.path("selectedPhysicalPages").equals(JsonUtils.mapper().valueToTree(selected))
                || !response.path("pages").isArray() || !response.path("batchResults").isArray() || response.path("batchResults").isEmpty()
                || !"consense.native-pdf-batch-bundle-refs.v1".equals(response.path("bundleRef").path("schema").asText())
                || !response.path("bundleRef").path("batchBundleRefs").isArray()
                || !response.path("sourceQualityAccepted").isBoolean() || response.path("sourceQualityAccepted").asBoolean()
                || !response.path("canonicalAdopted").isBoolean() || response.path("canonicalAdopted").asBoolean())
            throw new BizException("Native PDF aggregate source or explicit transport contract differs");
        Map<Integer,JsonNode> observed=new LinkedHashMap<>();
        for(JsonNode page:response.path("pages"))if(!page.isObject() || !page.path("physicalPageNo").isIntegralNumber()
                || !selected.contains(page.path("physicalPageNo").asInt()) || observed.put(page.path("physicalPageNo").asInt(),page)!=null)
            throw new BizException("Native PDF aggregate physical-page binding differs");
        List<Integer> requested=new ArrayList<>();Set<Integer> accounted=new HashSet<>();ArrayNode refs=JsonUtils.mapper().createArrayNode(),cleanup=JsonUtils.mapper().createArrayNode();
        boolean priorFailure=false;int index=0,successes=0,failures=0;
        for(JsonNode batch:response.path("batchResults")) {
            if(!batch.isObject() || !batch.path("source").equals(source) || !batch.path("batchIndex").isIntegralNumber()
                    || batch.path("batchIndex").asInt()!=index++ || !batch.path("selectedPhysicalPages").isArray()
                    || batch.path("selectedPhysicalPages").isEmpty() || batch.path("selectedPhysicalPages").size()>NATIVE_PAGE_CAP
                    || !batch.path("responseObservedPhysicalPages").isArray())
                throw new BizException("Native PDF aggregate batch identity or scope differs");
            List<Integer> scope=new ArrayList<>(),actual=new ArrayList<>();
            for(JsonNode page:batch.path("selectedPhysicalPages")) {
                if(!page.isIntegralNumber() || !selected.contains(page.asInt()) || requested.contains(page.asInt()))
                    throw new BizException("Native PDF aggregate duplicated or borrowed a requested page");
                scope.add(page.asInt());requested.add(page.asInt());
            }
            ArrayNode batchPages=JsonUtils.mapper().createArrayNode();
            for(JsonNode page:batch.path("responseObservedPhysicalPages")) {
                if(!page.isIntegralNumber() || !scope.contains(page.asInt()) || !accounted.add(page.asInt()) || !observed.containsKey(page.asInt()))
                    throw new BizException("Native PDF aggregate observed page is unbound or duplicated");
                JsonNode bound=observed.get(page.asInt());
                if(!bound.path("bundleRef").equals(batch.path("bundleRef")))throw new BizException("Native PDF aggregate page borrowed another batch bundle");
                actual.add(page.asInt());batchPages.add(bound);
            }
            String status=batch.path("status").asText();
            if(batch.path("bundleRef").isObject()) {
                if(priorFailure)throw new BizException("Native PDF aggregate observed a batch after an earlier transport failure");
                ObjectNode wire=((ObjectNode)batch).deepCopy();wire.set("pages",batchPages);validateNativePdfResponse(wire,source,scope);refs.add(batch.path("bundleRef"));
                if(actual.isEmpty())priorFailure=true;
                for(JsonNode page:batchPages)for(JsonNode method:page.path("methods"))if("failed".equals(method.path("status").asText()))failures++;else successes++;
            } else {
                if(!actual.isEmpty() || !batch.path("failure").isObject()
                        || !("failed".equals(status) && "NativePdfBatchTransportFailure".equals(batch.path("failure").path("type").asText())
                        || "not_executed".equals(status) && priorFailure && "NotExecutedAfterBatchFailure".equals(batch.path("failure").path("type").asText())))
                    throw new BizException("Native PDF aggregate missing page has no actual failure or unexecuted basis");
                priorFailure=true;
            }
            if(batch.has("transportCleanupFailure")) {
                JsonNode failure=batch.path("transportCleanupFailure");
                if("not_executed".equals(status) || !failure.isObject() || !"not_cleaned".equals(failure.path("cleanupStatus").asText())
                        || failure.path("type").asText().isEmpty() || failure.path("path").asText().isEmpty() || !Path.of(failure.path("path").asText()).isAbsolute())
                    throw new BizException("Native PDF aggregate cleanup failure lost its exact residual path or state");
                cleanup.add(failure);priorFailure=true;
            }
        }
        ArrayNode missing=JsonUtils.mapper().createArrayNode();for(Integer page:selected)if(!observed.containsKey(page))missing.add(page);
        String terminal=successes==0?"failed":failures>0 || !missing.isEmpty() || !cleanup.isEmpty()?"partial":"candidate_observed";
        if(!requested.equals(selected) || !accounted.equals(observed.keySet()) || !missing.equals(response.path("unobservedPhysicalPages"))
                || !refs.equals(response.path("bundleRef").path("batchBundleRefs")) || !terminal.equals(response.path("status").asText())
                || response.has("transportCleanupFailures") && !cleanup.equals(response.path("transportCleanupFailures"))
                || !response.has("transportCleanupFailures") && !cleanup.isEmpty() || response.path("batchResults").size()==1 && cleanup.isEmpty()
                || !missing.isEmpty() && !"NativePdfBatchIncomplete".equals(response.path("failure").path("type").asText())
                || missing.isEmpty() && !cleanup.isEmpty() && !"NativePdfContextCleanupFailure".equals(response.path("failure").path("type").asText()))
            throw new BizException("Native PDF aggregate full denominator, bundle references or terminal differs");
    }

    private static void validateNativePdfResponse(JsonNode response,JsonNode source,List<Integer> selected) {
        Set<Integer> required=new HashSet<>(selected);
        if(!response.isObject() || !"consense.native-pdf-candidates.v1".equals(response.path("schema").asText())
                || !Arrays.asList("candidate_observed","partial","failed").contains(response.path("status").asText())
                || !response.path("source").path("sourceSha256").equals(source.path("sourceSha256"))
                || !response.path("pages").isArray() || !response.path("bundleRef").isObject()
                || response.has("transportSchema")
                || "consense.native-pdf-batch-bundle-refs.v1".equals(response.path("bundleRef").path("schema").asText())
                || response.path("sourceQualityAccepted").asBoolean() || response.path("canonicalAdopted").asBoolean())
            throw new BizException("Native PDF candidate response source, task or protocol differs");
        if(!response.path("source").path("pageCount").isIntegralNumber()
                || response.path("source").path("pageCount").asInt(-1)!=source.path("pageCount").asInt(-2)
                || !response.path("source").path("sourceFilename").equals(source.path("sourceFilename"))
                || !response.path("source").path("mediaType").equals(source.path("mediaType"))
                || !response.path("selectedPhysicalPages").equals(JsonUtils.mapper().valueToTree(selected)))
            throw new BizException("Native PDF candidate response original page-count or task differs");
        if("failed".equals(response.path("status").asText()) && response.path("pages").isEmpty() && response.path("failure").isObject())return;
        Set<Integer> actual=new HashSet<>();int successes=0,failures=0;
        for(JsonNode page:response.path("pages")) {
            if(!page.path("physicalPageNo").isIntegralNumber() || !required.contains(page.path("physicalPageNo").asInt())
                    || !actual.add(page.path("physicalPageNo").asInt()) || !page.path("methods").isArray())throw new BizException("Native PDF physical-page bindings differ");
            Set<String> methods=new HashSet<>();
            for(JsonNode method:page.path("methods")) {
                String status=method.path("status").asText();
                if(!methods.add(method.path("method").asText()) || method.path("provider").asText().isEmpty()
                        || !("candidate_observed".equals(status) && method.path("candidate").isObject()
                        && "consense.native-pdf-table-candidate.v1".equals(method.path("candidate").path("schema").asText())
                        && method.path("candidate").path("tables").isArray() && method.path("candidate").path("conditionCandidates").isArray()
                        || "failed".equals(status) && method.path("candidate").isNull() && method.path("failure").isObject()))
                    throw new BizException("Native PDF whole-method candidate or failure differs");
                if("failed".equals(status))failures++;else successes++;
            }
            if(!methods.equals(new HashSet<>(Arrays.asList("pdfplumber_lines_lines","camelot_network","camelot_stream"))))
                throw new BizException("Native PDF candidate response omitted or duplicated an entire method");
        }
        if(!required.equals(actual))throw new BizException("Native PDF candidate response omitted an entire physical page");
        String terminal=successes==0?"failed":failures>0?"partial":"candidate_observed";
        if(!terminal.equals(response.path("status").asText()))
            throw new BizException("Native PDF candidate terminal differs from actual method failures");
    }

    @Override
    public boolean available() {
        try {
            String ping = http.get(trim(cfg.getBaseUrl())
                    + ("local".equalsIgnoreCase(cfg.getMode()) ? "/health" : "/"), 3000);
            return ping != null;
        } catch (Exception e) {
            log.debug("OCR 服务不可达: {}", e.getMessage());
            return false;
        }
    }

    private String callClassic(byte[] imageBytes) {
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        ArrayNode images = body.putArray("images");
        images.add(Base64.getEncoder().encodeToString(imageBytes));
        return http.postJson(trim(cfg.getBaseUrl()) + cfg.getOcrPath(),
                JsonUtils.write(body), cfg.getTimeoutMs());
    }

    private String callHubServing(byte[] imageBytes) {
        RequestBody fileBody = RequestBody.create(imageBytes, MediaType.get("image/png"));
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "page.png", fileBody)
                .build();
        return http.postMultipart(trim(cfg.getBaseUrl()) + cfg.getStructurePath(),
                body, cfg.getTimeoutMs());
    }

    private OcrResult recognizeLocal(byte[] imageBytes) { return recognizeLocal(imageBytes, false); }
    private OcrResult recognizeLocal(byte[] imageBytes, boolean identityOnly) {
        return recognizeLocal(imageBytes, identityOnly, null);
    }
    private OcrResult recognizeLocal(byte[] imageBytes, boolean identityOnly, Map<String,Object> sourceContext) {
        long started = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString().replace("-", "");
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("X-Consense-Ocr-Task-Id", taskId);
        headers.put("X-Consense-Ocr-Deadline-Ms", Long.toString(Math.addExact(started, cfg.getTimeoutMs())));
        headers.put("X-Consense-Ocr-Purpose", identityOnly ? "identity" : "parse");
        RequestBody image = RequestBody.create(imageBytes, MediaType.get("image/png"));
        RequestBody oneShotImage = new RequestBody() {
            @Override public MediaType contentType(){return image.contentType();}
            @Override public long contentLength() throws IOException{return image.contentLength();}
            @Override public void writeTo(okio.BufferedSink sink) throws IOException{image.writeTo(sink);}
            @Override public boolean isOneShot(){return true;}
        };
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "page.png", oneShotImage);
        if (sourceContext != null) builder.addFormDataPart("sourceContext", JsonUtils.write(sourceContext));
        MultipartBody body = builder.build();
        String raw;
        try {
            long remaining = Math.max(1, cfg.getTimeoutMs() - (System.currentTimeMillis() - started));
            raw = http.postMultipart(trim(cfg.getBaseUrl()) + "/ocr", body, remaining, headers, 32*1024*1024);
        } catch (RuntimeException failure) {
            String status = "unknown", retained = "";
            String taskUrl = trim(cfg.getBaseUrl()) + "/ocr/tasks/" + taskId;
            try {
                JsonNode task = JsonUtils.parse(http.getBounded(taskUrl, 2000, 65536));
                if (!taskId.equals(task.path("taskId").asText())) throw new BizException("OCR task response identity differs");
                status = task.path("status").asText("unknown");
                retained = task.has("resultRef") ? "; resultRef=" + task.path("resultRef").path("sha256").asText() : "";
                if ("completed".equals(status) && task.path("resultRef").path("byteCount").asLong(Long.MAX_VALUE) <= 32L*1024*1024)
                    raw = http.getBounded(taskUrl + "/result", 2000, 32*1024*1024);
                else {
                    if ("queued".equals(status) || "running".equals(status)) http.deleteBounded(taskUrl, 2000, 65536);
                    throw new BizException("OCR task has no inline completed result");
                }
            } catch (RuntimeException queryFailure) {
                throw new BizException("OCR task " + taskId + " status=" + status + retained
                        + "; no OCR request was replayed: " + failure.getMessage());
            }
        }
        JsonNode response = JsonUtils.parse(raw);
        validateObservation(response, imageBytes);
        validatePageGroups(response.get("wholePageGroups"),sourceContext);
        JsonNode echoedContext = response.path("provenance").get("sourceContext");
        if (echoedContext != null && !echoedContext.isNull()
                && (sourceContext == null || !echoedContext.equals(JsonUtils.mapper().valueToTree(sourceContext))))
            throw new BizException("OCR original source context differs from actual request");
        List<OcrLine> lines = new ArrayList<>();
        double confidence = 0;
        StringBuilder text = new StringBuilder();
        for (JsonNode line : response.path("lines")) {
            if (JsonUtils.isBlankText(line.path("text").asText())) continue;
            JsonNode box = line.path("bbox");
            double[] bbox = box.isArray() && box.size() == 4
                    ? new double[]{box.get(0).asDouble(), box.get(1).asDouble(),
                    box.get(2).asDouble(), box.get(3).asDouble()} : null;
            double score = line.path("confidence").asDouble(0);
            lines.add(new OcrLine(line.path("text").asText(), score, bbox, line.get("polygon")));
            text.append(line.path("text").asText()).append('\n');
            confidence += score;
        }
        String fullText = response.path("text").asText(text.toString()).trim();
        ObjectNode parserEvidence = JsonUtils.mapper().createObjectNode();
        for (String key : new String[]{"parserRuns", "dualEvidenceRef", "adoptionStatus"})
            if (response.has(key)) parserEvidence.set(key, response.get(key).deepCopy());
        return new OcrResult(fullText, lines.isEmpty() ? 0 : confidence / lines.size(), lines,
                response.get("regions"), response.get("frame"), response.get("provenance"), response.get("coverage"),
                parserEvidence.size() == 0 ? null : parserEvidence,response.get("wholePageGroups"));
    }
    private static void validatePageGroups(JsonNode groups,Map<String,Object> sourceContext) {
        if(groups==null)return;
        if(!groups.isArray())throw new BizException("OCR whole page groups must be an array");
        JsonNode context=JsonUtils.mapper().valueToTree(sourceContext);
        for(JsonNode group:groups) {
            String selected=group.path("selectedParser").isNull()?null:group.path("selectedParser").asText(),status=group.path("selectionStatus").asText();
            if(!group.isObject() || !"whole_page".equals(group.path("scope").asText()) || !group.path("physicalPageNo").isIntegralNumber()
                    || group.path("physicalPageNo").asInt()<1 || !"candidate_unverified".equals(group.path("adoptionStatus").asText())
                    || !group.path("sourceQualityAccepted").isBoolean() || group.path("sourceQualityAccepted").asBoolean()
                    || !group.path("candidates").isArray() || group.path("candidates").size()!=2
                    || !Arrays.asList("feature_policy_selected_candidate","source_review_selected_candidate","candidate_unverified").contains(status)
                    || "feature_policy_selected_candidate".equals(status) && (selected==null || !group.path("policySha256").asText().matches("[a-f0-9]{64}"))
                    || "source_review_selected_candidate".equals(status) && (selected==null || !group.path("reviewManifestSha256").asText().matches("[a-f0-9]{64}"))
                    || "candidate_unverified".equals(status) && selected!=null)
                throw new BizException("OCR whole page group selection or completeness differs");
            Set<String> parsers=new HashSet<>();
            for(JsonNode candidate:group.path("candidates"))if(!parsers.add(candidate.path("parser").asText())
                    || !candidate.path("blocks").isArray() || !candidate.path("page").isObject()
                    || candidate.path("runId").asText().isEmpty() || candidate.path("pageId").asText().isEmpty())
                throw new BizException("OCR whole page parser alternative is missing or duplicated");
            if(!parsers.equals(new HashSet<>(Arrays.asList("mineru","paddleocr-vl"))) || selected!=null && !parsers.contains(selected))
                throw new BizException("OCR whole page parser identities differ");
            if("source_review_selected_candidate".equals(status)) {
                Set<String> reviewed=new HashSet<>(),observed=new HashSet<>();JsonNode ids=group.path("reviewedBlockIds");
                if(!ids.isArray()||ids.isEmpty())throw new BizException("OCR reviewed whole page region inventory missing");
                for(JsonNode id:ids)if(!id.isTextual()||id.asText().isEmpty()||!reviewed.add(id.asText()))throw new BizException("OCR reviewed whole page region identity differs");
                for(JsonNode candidate:group.path("candidates"))if(selected.equals(candidate.path("parser").asText())) {
                    if(!candidate.path("features").path("completeRawCapture").asBoolean()||!candidate.path("features").path("allGeometryBound").asBoolean())throw new BizException("OCR reviewed whole page capture or geometry incomplete");
                    for(JsonNode block:candidate.path("blocks"))if(block.path("blockId").asText().isEmpty()||!observed.add(block.path("blockId").asText()))throw new BizException("OCR reviewed whole page block identity differs");
                }
                if(!reviewed.equals(observed))throw new BizException("OCR reviewed whole page region inventory differs");
            }
            if(sourceContext!=null && (!group.path("source").path("sourceSha256").equals(context.path("source").path("sourceSha256"))
                    || !group.path("physicalPageNo").equals(context.path("physicalPageNo"))))
                throw new BizException("OCR whole page group original-source bindings differ");
        }
    }

    private static void validateObservation(JsonNode response, byte[] input) {
        if (!response.isObject()) throw new BizException("OCR response must be an object");
        JsonNode regions = response.get("regions"), frame = response.get("frame"), provenance = response.get("provenance");
        if (regions != null && !regions.isArray()) throw new BizException("OCR regions must be an array");
        if (provenance != null && !provenance.isNull() && !provenance.isObject()) throw new BizException("OCR provenance must be an object");
        String actualSha = inputSha256(input);
        if (provenance != null && provenance.hasNonNull("inputSha256")
                && !actualSha.equals(provenance.path("inputSha256").asText())) throw new BizException("OCR observation input identity differs");
        if (frame != null && !frame.isNull()) {
            if (!frame.isObject() || !"pixel".equals(frame.path("coordinateUnit").asText())
                    || !"xywh".equals(frame.path("bboxFormat").asText())
                    || !actualSha.equals(frame.path("imageSha256").asText())) throw new BizException("OCR image frame or identity differs");
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
                if (image == null || !frame.path("width").isIntegralNumber() || !frame.path("height").isIntegralNumber()
                        || image.getWidth() != frame.path("width").asInt() || image.getHeight() != frame.path("height").asInt())
                    throw new BizException("OCR frame dimensions differ from actual input image");
            } catch (java.io.IOException invalid) { throw new BizException("OCR input image frame cannot be verified"); }
        }
        if (regions != null) for (JsonNode region : regions) {
            if (!region.isObject() || region.path("id").asText().trim().isEmpty() || region.path("kind").asText().trim().isEmpty())
                throw new BizException("OCR region requires its actual id and kind");
        }
    }

    private static String inputSha256(byte[] input) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(input)) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * 递归收集 {text, confidence} 节点，兼容 results 的多种嵌套形态。
     */
    private void collect(String raw, List<OcrLine> out) {
        JsonNode root;
        try {
            root = JsonUtils.parse(raw);
        } catch (Exception e) {
            throw new BizException("OCR 响应不是合法 JSON: " + abbreviate(raw));
        }
        walk(root, out, 0);
    }

    private void walk(JsonNode node, List<OcrLine> out, int depth) {
        if (node == null || depth > 8) {
            return;
        }
        if (node.isArray()) {
            // Paddle's [polygon, [text, confidence]] line form.
            if (node.size() == 2 && node.get(0).isArray() && node.get(1).isArray()
                    && node.get(1).size() >= 2 && node.get(1).get(0).isTextual()) {
                ObjectNode region = JsonUtils.mapper().createObjectNode();
                region.set("points", node.get(0));
                out.add(new OcrLine(node.get(1).get(0).asText(), node.get(1).get(1).asDouble(), extractBbox(region), node.get(0)));
                return;
            }
            node.forEach(child -> walk(child, out, depth + 1));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode textNode = firstPresent(node, "text", "transcription", "rec_text");
        if (textNode != null && !JsonUtils.isBlankText(textNode.asText())) {
            JsonNode scoreNode = firstPresent(node, "confidence", "score", "rec_score");
            double[] bbox = extractBbox(node);
            out.add(new OcrLine(
                    textNode.asText(),
                    scoreNode == null ? 1.0 : scoreNode.asDouble(1.0),
                    bbox));
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isContainerNode() && !"text".equals(entry.getKey())) {
                walk(value, out, depth + 1);
            }
        });
    }

    /**
     * PP-OCR classic 返回结构里 text_region 是 [[x1,y1],[x2,y2],[x3,y3],[x4,y4]] 四点。
     * hubserving/structure 返回里也常见 text_box / bbox / points 等同义字段。
     * 收敛为 {x, y, w, h} 的最小外接矩形；无法识别则返回 null。
     */
    private double[] extractBbox(JsonNode node) {
        JsonNode region = firstPresent(node, "text_region", "text_box", "bbox", "points", "poly");
        if (region == null || !region.isArray()) return null;
        // PP-Structure flat rectangles use x1, y1, x2, y2; local mode is parsed separately as x,y,w,h.
        if (region.size() == 4 && region.get(0).isNumber() && region.get(1).isNumber()
                && region.get(2).isNumber() && region.get(3).isNumber()) {
            double x = region.get(0).asDouble(), y = region.get(1).asDouble();
            return new double[]{x, y, Math.max(0, region.get(2).asDouble() - x),
                    Math.max(0, region.get(3).asDouble() - y)};
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (JsonNode p : region) {
            if (p.isArray() && p.size() >= 2 && p.get(0).isNumber() && p.get(1).isNumber()) {
                double x = p.get(0).asDouble();
                double y = p.get(1).asDouble();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            } else if (p.isObject() && p.has("x") && p.has("y")) {
                double x = p.get("x").asDouble();
                double y = p.get("y").asDouble();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            } else {
                return null;
            }
        }
        if (minX == Double.POSITIVE_INFINITY) return null;
        return new double[]{minX, minY, maxX - minX, maxY - minY};
    }

    private JsonNode firstPresent(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
