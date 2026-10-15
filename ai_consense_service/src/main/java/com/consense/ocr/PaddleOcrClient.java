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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

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
        JsonNode context=JsonUtils.mapper().valueToTree(nativeContext),source=context.path("source");
        if(originalPdf==null || originalPdf.length<5 || !new String(originalPdf,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")
                || !inputSha256(originalPdf).equals(source.path("sourceSha256").asText())
                || source.path("sourceFilename").asText().trim().isEmpty() || !"application/pdf".equals(source.path("mediaType").asText())
                || !source.path("pageCount").isIntegralNumber() || source.path("pageCount").asInt()<=0
                || !context.path("selectedPhysicalPages").isArray() || context.path("selectedPhysicalPages").isEmpty())
            throw new BizException("Native PDF request original identity or scope differs");
        Set<Integer> required=new HashSet<>();
        for(JsonNode page:context.path("selectedPhysicalPages"))if(!page.isIntegralNumber() || page.asInt()<1
                || page.asInt()>source.path("pageCount").asInt() || !required.add(page.asInt()))
            throw new BizException("Native PDF request physical-page scope differs");
        MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file",source.path("sourceFilename").asText(),RequestBody.create(originalPdf,MediaType.get("application/pdf")))
                .addFormDataPart("nativeContext",JsonUtils.write(context)).build();
        JsonNode response=JsonUtils.parse(http.postMultipart(trim(cfg.getBaseUrl())+"/native-pdf/table-candidates",body,cfg.getTimeoutMs()));
        if(!response.isObject() || !"consense.native-pdf-candidates.v1".equals(response.path("schema").asText())
                || !Arrays.asList("candidate_observed","partial","failed").contains(response.path("status").asText())
                || !response.path("source").path("sourceSha256").equals(source.path("sourceSha256"))
                || !response.path("pages").isArray() || !response.path("bundleRef").isObject()
                || response.path("sourceQualityAccepted").asBoolean() || response.path("canonicalAdopted").asBoolean())
            throw new BizException("Native PDF candidate response source, task or protocol differs");
        if("failed".equals(response.path("status").asText()) && response.path("pages").isEmpty() && response.path("failure").isObject())return response;
        if(!response.path("source").path("pageCount").isIntegralNumber()
                || response.path("source").path("pageCount").asInt(-1)!=source.path("pageCount").asInt(-2)
                || !response.path("source").path("sourceFilename").equals(source.path("sourceFilename"))
                || !response.path("selectedPhysicalPages").equals(context.path("selectedPhysicalPages")))
            throw new BizException("Native PDF candidate response original page-count or task differs");
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
        return response;
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
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "page.png",
                        RequestBody.create(imageBytes, MediaType.get("image/png")));
        if (sourceContext != null) builder.addFormDataPart("sourceContext", JsonUtils.write(sourceContext));
        MultipartBody body = builder.build();
        String raw = identityOnly ? http.postMultipart(trim(cfg.getBaseUrl()) + "/ocr", body, cfg.getTimeoutMs(),
                Collections.singletonMap("X-Consense-Ocr-Purpose", "identity"))
                : http.postMultipart(trim(cfg.getBaseUrl()) + "/ocr", body, cfg.getTimeoutMs());
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
                    || !Arrays.asList("feature_policy_selected_candidate","candidate_unverified").contains(status)
                    || "feature_policy_selected_candidate".equals(status) && (selected==null || !group.path("policySha256").asText().matches("[a-f0-9]{64}"))
                    || "candidate_unverified".equals(status) && selected!=null)
                throw new BizException("OCR whole page group selection or completeness differs");
            Set<String> parsers=new HashSet<>();
            for(JsonNode candidate:group.path("candidates"))if(!parsers.add(candidate.path("parser").asText())
                    || !candidate.path("blocks").isArray() || !candidate.path("page").isObject()
                    || candidate.path("runId").asText().isEmpty() || candidate.path("pageId").asText().isEmpty())
                throw new BizException("OCR whole page parser alternative is missing or duplicated");
            if(!parsers.equals(new HashSet<>(Arrays.asList("mineru","paddleocr-vl"))) || selected!=null && !parsers.contains(selected))
                throw new BizException("OCR whole page parser identities differ");
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
