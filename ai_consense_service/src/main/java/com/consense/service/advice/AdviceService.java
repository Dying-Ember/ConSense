package com.consense.service.advice;

import com.consense.ai.AiGateway;
import com.consense.ai.ModelIdentity;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.config.ConsenseProperties;
import com.consense.domain.ChatMessage;
import com.consense.domain.EvidenceChunk;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.ocr.OcrClient;
import com.consense.repository.ChatMessageRepository;
import com.consense.repository.EvidenceChunkRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.vector.VectorStore;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 咨询模块：证据索引 + 检索增强问答。
 *
 * 对应设计稿的 guardrail：回答必须带条款或摘录依据，
 * 检索不到依据时返回 "I don't know"，不做任何补全与推断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdviceService {

    private final ProjectService projectService;
    private final AiGateway ai;
    private final VectorStore vectorStore;
    private final OcrClient ocrClient;
    private final ConsenseProperties props;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final EvidenceChunkRepository chunkRepository;
    private final ChatMessageRepository chatMessageRepository;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;

    // ------------------------------------------------------------ 索引

    @Transactional
    public IndexStatusVO rebuildIndex(String projectId) {
        projectService.require(projectId);
        List<SourceDocument> documents = selectedDocuments(projectId,"fullset").stream()
                .filter(ProjectDocumentScope::parseUsable)
                .collect(Collectors.toList());
        if (documents.isEmpty()) {
            throw new BizException(4200, "No parsed documents in the current review package. Upload the tender/contract package in Vetting first.");
        }
        if (!vectorStore.available()) {
            throw new BizException(4201, "向量库不可用（" + props.getVector().getBaseUrl()
                    + "）。请启动 Qdrant，或把 consense.vector.provider 改为 memory 先跑通流程。");
        }

        if(!ai.embeddingAvailable())throw new BizException(4201,"Deployment embedding model "+ai.embedModel()+" is unavailable. Restore that model before refreshing the advice evidence index.");
        vectorStore.deleteByProject(projectId);
        chunkRepository.deleteByProjectId(projectId);

        int total = 0;
        int index = 0;

        for (SourceDocument document : documents) {
            List<VettingCorpus.Chunk> nativeChunks=VettingCorpus.chunks(Collections.singletonList(document));
            List<String> pieces = nativeChunks.stream().map(VettingCorpus.Chunk::getContent).collect(Collectors.toList());
            if (pieces.isEmpty()) {
                continue;
            }
            List<float[]> vectors = ai.embed(pieces);
            if(vectors.size()!=pieces.size()||vectors.stream().anyMatch(v->v==null||v.length==0))throw new BizException(4201,"Embedding adapter did not index every permitted source passage.");
            String documentHash=VettingCorpus.sourceHash(document),documentRole=VettingCorpus.sourceRole(document);
            List<EvidenceChunk> chunks = new ArrayList<>();
            List<VectorStore.VectorPoint> points = new ArrayList<>();
            for (int i = 0; i < pieces.size(); i++) {
                EvidenceChunk chunk = new EvidenceChunk();
                chunk.setProjectId(projectId);
                chunk.setDocumentId(document.getId());
                chunk.setSourceHash(documentHash);
                chunk.setSourceRole(documentRole);
                chunk.setChunkIndex(index++);
                chunk.setFileLabel(nvl(document.getFileKey()) + " · " + nvl(document.getFileName()));
                chunk.setPageNo(nativeChunks.get(i).getPageNo());
                String anchor=nativeChunks.get(i).getAnchor();
                chunk.setAnchor(anchor==null?null:anchor.substring(0,Math.min(anchor.length(),255)));
                chunk.setContent(pieces.get(i));
                chunk.setCreatedAt(Instant.now());
                chunkRepository.save(chunk);

                String pointId = document.getId() + "-" + i;
                chunk.setPointId(pointId);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("projectId", projectId);
                payload.put("documentId", String.valueOf(document.getId()));
                payload.put("sourceHash",chunk.getSourceHash());
                payload.put("sourceRole",chunk.getSourceRole());
                payload.put("fileLabel", chunk.getFileLabel());
                payload.put("pageNo", chunk.getPageNo());
                payload.put("anchor", nvl(chunk.getAnchor()));
                payload.put("content", pieces.get(i));
                points.add(new VectorStore.VectorPoint(pointId,
                        vectors.get(i), payload));
                chunks.add(chunk);
            }
            chunkRepository.saveAll(chunks);
            if (!points.isEmpty()) {
                vectorStore.upsert(points);
            }
            total += pieces.size();
        }
        log.info("项目 {} 证据索引重建完成，共 {} 个切片", projectId, total);
        return status(projectId);
    }

    public IndexStatusVO status(String projectId) {
        projectService.require(projectId);
        List<EvidenceChunk> records=chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        int chunks = records.size();
        List<SourceDocument> documents=selectedDocuments(projectId,"fullset").stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        boolean stale=chunks>0&&!freshIndex(records,documents);
        boolean vectorReady = vectorStore.available();
        int indexed = vectorReady ? vectorStore.count(projectId) : 0;
        boolean llmReady = ai.available();
        boolean embeddingReady=ai.embeddingAvailable();
        boolean ocrReady = props.getOcr().isEnabled() && ocrClient.available();

        LocalizedText message;
        if(!embeddingReady) {
            message=LocalizedText.of("部署的向量模型 "+ai.embedModel()+" 不可用。请恢复该模型后再刷新咨询依据索引。","部署的向量模型 "+ai.embedModel()+" 不可用。請恢復該模型後再刷新諮詢依據索引。","Deployment embedding model "+ai.embedModel()+" is unavailable. Restore that model before refreshing the advice evidence index.");
        } else if (chunks == 0) {
            message = LocalizedText.of("尚未建立证据索引，请先点击「刷新证据索引」。",
                    "尚未建立證據索引，請先點擊「刷新證據索引」。",
                    "No evidence index yet — click 'Refresh evidence index' to build one.");
        } else if(stale) {
            message=LocalizedText.of("审查文件包或资料版本已变化；请刷新咨询依据索引，旧索引不可用于新的回答。","審查文件包或資料版本已變化；請刷新諮詢依據索引，舊索引不可用於新的回答。","The review package or source revision changed. Refresh the advice evidence index before asking.");
        } else if (!vectorReady) {
            message = LocalizedText.of("向量库不可达，检索将无法工作。",
                    "向量庫不可達，檢索將無法工作。",
                    "Vector store unreachable; retrieval is unavailable.");
        } else {
            message = LocalizedText.of("索引就绪，共 " + chunks + " 个切片。",
                    "索引就緒，共 " + chunks + " 個切片。",
                    chunks + " chunks indexed and ready.");
        }
        return new IndexStatusVO(chunks, indexed,
                props.getVector().getProvider(), props.getVector().getCollection(),
                ai.embedModel(), ai.chatModel(), llmReady, vectorReady, ocrReady, message,stale,documents.size(),(int)records.stream().map(EvidenceChunk::getDocumentId).distinct().count(),embeddingReady);
    }

    // ------------------------------------------------------------ 问答

    @Transactional
    public AskResponseVO ask(String projectId, AskRequest request) {
        Project project = projectService.require(projectId);
        String question = request == null ? "" : nvl(request.getQuestion()).trim();
        if (question.isEmpty()) {
            throw new BizException(4202, "请先输入问题");
        }
        String scope = ProjectDocumentScope.normalizeScope(request.getScope());
        QuestionContext questionContext=clarificationContext(projectId,scope,request.getReplyToMessageId(),question);

        // Configuration errors are model-action errors even when no index has been built.
        if (ai.explicitProfileSelected() && ai.capture().getUnavailableReason() != null) {
            ai.requireAvailable();
        }

        ChatMessage user=saveMessage(projectId, ChatMessage.ROLE_USER, null, question, null, null, null, null);
        user.setReplyToMessageId(request.getReplyToMessageId());user.setSourceScope(scope);user.setOriginalQuestion(questionContext.originalQuestion);user.setUserBackgroundJson(JsonUtils.write(questionContext.background));chatMessageRepository.save(user);

        String retrievalQuestion=questionContext.originalQuestion+(questionContext.background.isEmpty()?"":"\nUser supplied clarification: "+String.join("\n",questionContext.background));
        List<VectorStore.SearchHit> hits = withIncorporationContext(projectId,scope,retrieve(projectId, scope, retrievalQuestion));
        List<CitationVO> sources = new ArrayList<>();
        for(int i=0;i<hits.size();i++){CitationVO source=toCitation(hits.get(i));source.setEvidenceId("["+(i+1)+"]");sources.add(source);}

        if (hits.isEmpty()) {
            AskResponseVO response = unknown(projectId, scope, sources, Collections.<String>emptyList(), false);
            persistAssistant(projectId, response,user.getId());
            return response;
        }

        if (ai.explicitProfileSelected()) {
            ai.requireAvailable();
        }

        String excerpts = renderExcerpts(hits);
        String userPrompt = PromptService.format(promptService.userTemplate(PromptCatalog.KEY_ADVICE),
                scopeLabel(scope), excerpts, questionContext.originalQuestion);
        if(!questionContext.background.isEmpty())userPrompt+="\n\nuser-supplied-background (clarification replies, not contractual evidence):\n"+String.join("\n",questionContext.background)
                +"\nAnswer the original question in light of these user-stated assumptions. Do not cite this background as a contract document or established contractual fact.";

        AnswerRecord answer;
        try {
            answer = ai.completeJson(promptService.system(PromptCatalog.KEY_ADVICE), userPrompt, AnswerRecord.class);
        } catch (Exception e) {
            // External exception text may contain credentials; retain only the safe failure type/profile.
            log.warn("Advice model call failed ({}).", e.getClass().getSimpleName());
            ModelIdentity selected = ai.modelIdentity();
            throw new BizException(4203, "Selected LLM profile "
                    + (selected == null ? "legacy deployment" : selected.getProfileId())
                    + " failed; the operation was not rerouted.");
        }

        List<String> clarifications=answer==null?Collections.emptyList():cleanClarifications(answer.getClarifications());
        List<String> citations=validatedCitations(answer==null?null:answer.getCitations(),sources);
        String basisScope=answer!=null&&"reference_only".equals(nvl(answer.getBasisScope()).trim().toLowerCase(java.util.Locale.ROOT))?"reference_only":"project_contract";
        boolean basisSupported=incorporationSupported(basisScope,citations,sources);
        if(!basisSupported){clarifications=new ArrayList<>(clarifications);clarifications.add("Please provide or identify the adopted contractual provisions, including standard incorporation, applicable editions and any project amendments where relevant. Project correspondence and a document list alone do not establish a contractual requirement.");}
        if (answer == null || !answer.isGrounded() || !clarifications.isEmpty() || JsonUtils.isBlankText(answer.getAnswer())||citations.isEmpty()||!basisSupported) {
            List<String> extra = answer != null && answer.getMissingReason() != null
                    ? Arrays.asList(answer.getMissingReason())
                    : Collections.<String>emptyList();
            AskResponseVO response = unknown(projectId, scope, sources, extra, true);
            response.setClarifications(clarifications);
            response.setBasisScope("insufficient");
            persistAssistant(projectId, response,user.getId());
            return response;
        }

        AskResponseVO response = new AskResponseVO(true,
                LocalizedText.of("带依据的回答", "帶依據的回答", "Answer with basis"),
                LocalizedText.same(answer.getAnswer()),
                null,
                citations,
                evidenceIdOf(sources),
                sources,
                ai.chatModel(), ai.modelIdentity());
        response.setClarifications(clarifications);response.setSourceScope(scope);
        response.setBasisScope(basisScope);
        persistAssistant(projectId, response,user.getId());
        log.info("项目 {} 问答完成，命中 {} 条证据", projectId, hits.size());
        return response;
    }

    /**
     * 混合检索：
     *  1) 证据量小（总字符 ≤ stuffingLimit）→ 全量喂入（stuffing），跳过向量检索，
     *     检索环节零误差，所有切片连同引用信息直接进 prompt；
     *  2) 证据量大 → 向量检索，topK 提到 retrieveTopKFloor（默认 20）扩大召回，
     *     弱化"检索没捞到导致模型看不到"的问题。
     */
    private List<VectorStore.SearchHit> retrieve(String projectId, String scope, String question) {
        List<EvidenceChunk> chunks = chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        if (chunks.isEmpty()) {
            throw new BizException(4204, "该项目尚未建立证据索引，请先在咨询页点击「刷新证据索引」");
        }
        List<SourceDocument> current=selectedDocuments(projectId,"fullset").stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        if(!freshIndex(chunks,current))
            throw new BizException(4204,"The review package or source revision changed. Refresh the advice evidence index before asking.");
        Set<String> allowedIds=selectedDocuments(projectId,scope).stream().filter(ProjectDocumentScope::parseUsable).map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        chunks=chunks.stream().filter(c->allowedIds.contains(String.valueOf(c.getDocumentId()))).collect(Collectors.toList());
        if(chunks.isEmpty())return Collections.emptyList();

        int totalChars = 0;
        for (EvidenceChunk chunk : chunks) {
            totalChars += nvl(chunk.getContent()).length();
        }
        if (totalChars <= props.getVector().getStuffingLimit()) {
            log.info("项目 {} 证据总量 {} 字符（≤ {}），全量喂入（stuffing），共 {} 个切片",
                    projectId, totalChars, props.getVector().getStuffingLimit(), chunks.size());
            return chunks.stream().map(this::chunkToHit).collect(Collectors.toList());
        }

        // 大证据量：走向量检索，扩召回
        if (!ai.embeddingAvailable()) {
            if (ai.explicitProfileSelected()) {
                throw new BizException(4203,
                        "The deployment embedding adapter is unavailable; the selected chat profile was not rerouted.");
            }
            return Collections.emptyList();
        }
        if (!vectorStore.available()) {
            return Collections.emptyList();
        }
        List<float[]> vectors = ai.embed(Collections.singletonList(question));
        if (vectors.isEmpty() || vectors.get(0).length == 0) {
            return Collections.emptyList();
        }
        int topK = Math.max(props.getVector().getTopK(), props.getVector().getRetrieveTopKFloor());
        List<VectorStore.SearchHit> hits=vectorStore.search(projectId,allowedIds,vectors.get(0),topK,props.getVector().getScoreThreshold());
        // Validate external payloads against the current persisted chunk; never trust provider text or scope labels.
        Map<String,EvidenceChunk> byPoint=new LinkedHashMap<>();for(EvidenceChunk c:chunks){byPoint.put(nvl(c.getPointId()),c);byPoint.put(java.util.UUID.nameUUIDFromBytes(nvl(c.getPointId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),c);}
        List<VectorStore.SearchHit> verified=new ArrayList<>();
        for(VectorStore.SearchHit hit:hits){Map<String,Object> payload=hit.getPayload();if(payload==null)continue;
            if(!projectId.equals(String.valueOf(payload.get("projectId"))))continue;
            String documentId=String.valueOf(payload.get("documentId"));if(!allowedIds.contains(documentId))continue;
            EvidenceChunk stored=byPoint.get(hit.getId());
            if(stored==null||!documentId.equals(String.valueOf(stored.getDocumentId()))||!Objects.equals(stored.getSourceHash(),String.valueOf(payload.get("sourceHash"))))continue;
            VectorStore.SearchHit canonical=chunkToHit(stored);verified.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),canonical.getPayload()));
        }return verified;
    }

    /** EvidenceChunk → SearchHit（stuffing 模式构造全量命中，score 置 1.0） */
    private VectorStore.SearchHit chunkToHit(EvidenceChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", chunk.getProjectId());
        payload.put("documentId", String.valueOf(chunk.getDocumentId()));
        payload.put("sourceHash",chunk.getSourceHash());payload.put("sourceRole",chunk.getSourceRole());
        payload.put("fileLabel", nvl(chunk.getFileLabel()));
        payload.put("pageNo", chunk.getPageNo());
        payload.put("anchor", nvl(chunk.getAnchor()));
        payload.put("content", nvl(chunk.getContent()));
        return new VectorStore.SearchHit(nvl(chunk.getPointId()), 1.0, payload);
    }

    private AskResponseVO unknown(String projectId, String scope, List<CitationVO> sources,
                                  List<String> extraReasons, boolean chatDispatched) {
        List<String> citations = new ArrayList<>();
        if (sources.isEmpty()) {
            citations.add("Searched selected scope · no basis");
        } else {
            sources.stream().map(CitationVO::getFileLabel).distinct().limit(3).forEach(citations::add);
        }
        citations.addAll(extraReasons);
        AskResponseVO response=new AskResponseVO(false,
                LocalizedText.of("I don't know", "I don't know", "I don't know"),
                LocalizedText.empty(),
                scope,
                citations,
                evidenceIdOf(sources),
                sources,
                chatDispatched ? ai.chatModel() : null, chatDispatched ? ai.modelIdentity() : null);
        response.setSourceScope(scope);response.setClarifications(Collections.emptyList());response.setBasisScope("insufficient");return response;
    }

    /**
     * 与设计稿 unknownAnswer(scope) 完全一致的兜底文案，由前端按当前语言取值。
     */
    public static LocalizedText unknownAnswerText(String scopeKey, String scopeEn) {
        return LocalizedText.of(
                "在选定范围（" + scopeKey + "）内未找到充分条款依据。系统不会超出合约包进行推断。",
                "在選定範圍（" + scopeKey + "）內未找到充分條款依據。系統不會超出合約包進行推斷。",
                "No sufficient provision was found in the selected scope (" + scopeEn
                        + "). The system will not infer beyond the contract package.");
    }

    private String scopeLabel(String scope) {
        if ("tender".equals(nvl(scope))) {
            return "Tender documents only / 仅招标文件";
        }
        if ("contract".equals(nvl(scope))) {
            return "Conditions of Contract only / 仅合约条件";
        }
        return "Selected package conditions / 选定合约条件";
    }

    private String renderExcerpts(List<VectorStore.SearchHit> hits) {
        StringBuilder builder = new StringBuilder();
        builder.append("Selected source excerpts for this question. These excerpts do not establish that the entire standard/library or project package is complete. ")
                .append("A passage omitted from these excerpts does not prove that its document or clause is absent from the supplied files. ")
                .append("Project wording on adopting or amending standards, if included below, states only what those passages say about incorporation; ")
                .append("it does not guarantee that the underlying standard text or every amendment has been supplied or reviewed.\n\n");
        int index = 1;
        for (VectorStore.SearchHit hit : hits) {
            builder.append("[").append(index++).append("] ")
                    .append(hit.fileLabel())
                    .append(" | documentId=").append(hit.getPayload().get("documentId"))
                    .append(" | role=").append(hit.getPayload().get("sourceRole"))
                    .append(" | sourceRevision=").append(hit.getPayload().get("sourceHash"))
                    .append(" | physicalPage=").append(hit.pageNo().isEmpty()?"not available":hit.pageNo())
                    .append(" | location=").append(hit.anchor())
                    .append("\n").append(hit.text()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private CitationVO toCitation(VectorStore.SearchHit hit) {
        Map<String,Object> payload=hit.getPayload();
        Object page=payload.get("pageNo");
        return new CitationVO(hit.fileLabel(), page==null?null:String.valueOf(page), hit.anchor(), hit.text(), hit.getScore(),null,
                String.valueOf(payload.get("documentId")),String.valueOf(payload.get("sourceHash")),String.valueOf(payload.get("sourceRole")),
                payload.get("applicabilityContextComplete") instanceof Boolean?(Boolean)payload.get("applicabilityContextComplete"):null);
    }

    private String evidenceIdOf(List<CitationVO> sources) {
        return sources.isEmpty() ? null : "document:" + sources.get(0).getDocumentId();
    }

    // ------------------------------------------------------------ 历史

    public List<ChatMessageVO> history(String projectId) {
        projectService.require(projectId);
        List<ChatMessageVO> result = new ArrayList<>();
        for (ChatMessage message : chatMessageRepository.findByProjectIdOrderByIdAsc(projectId)) {
            List<String> citations = message.getCitationsJson() == null
                    ? Collections.<String>emptyList()
                    : safeList(message.getCitationsJson());
            result.add(new ChatMessageVO(message.getId(),
                    message.getRole(),
                    message.getTitle() == null ? null : LocalizedText.same(message.getTitle()),
                    message.getContent() == null ? null : LocalizedText.same(message.getContent()),
                    message.getUnknownScope(),
                    citations,
                    message.getEvidenceId(),
                    safeSources(message.getSourcesJson()),
                    message.getModelIdentityJson() == null ? null
                            : JsonUtils.read(message.getModelIdentityJson(), ModelIdentity.class),
                    message.getClarificationsJson()==null?Collections.emptyList():safeList(message.getClarificationsJson()),message.getSourceScope(),message.getBasisScope()));
        }
        return result;
    }

    @Transactional
    public void clearHistory(String projectId) {
        projectService.require(projectId);
        chatMessageRepository.deleteByProjectId(projectId);
    }

    public List<QuickQuestionVO> quickQuestions(String projectId) {
        return Arrays.asList(
                new QuickQuestionVO(LocalizedText.of(
                        "SCC4.1 下的文件优先次序是什么？",
                        "SCC4.1 下的文件優先次序是什麼？",
                        "What is the order of precedence under SCC4.1?"), Boolean.FALSE, "SCC4.1"),
                new QuickQuestionVO(LocalizedText.of(
                        "信封一与信封二在投标提交中如何使用？",
                        "信封一與信封二在投標提交中如何使用？",
                        "How are Envelope 1 and Envelope 2 used in the tender submission?"), Boolean.FALSE, "SCT5"),
                new QuickQuestionVO(LocalizedText.of(
                        "本文件包中 L10Pro 的用途是什么？",
                        "本文件包中 L10Pro 的用途是什麼？",
                        "What is L10Pro used for in this package?"), Boolean.FALSE, "L10Pro"),
                new QuickQuestionVO(LocalizedText.of(
                        "平台转换层需要什么混凝土等级？",
                        "平台轉換層需要什麼混凝土等級？",
                        "What concrete grade is required for the podium transfer slab?"), Boolean.TRUE, null),
                new QuickQuestionVO(LocalizedText.of(
                        "这份组装好的合约文件包属于哪个项目？",
                        "這份組裝好的合約文件包屬於哪個項目？",
                        "Which project does this assembled contract package relate to?"), Boolean.FALSE, null));
    }

    // ------------------------------------------------------------ 内部

    private ChatMessage saveMessage(String projectId, String role, String title, String content,
                             String unknownScope, List<String> citations, String evidenceId, ModelIdentity modelIdentity) {
        ChatMessage message = new ChatMessage();
        message.setProjectId(projectId);
        message.setRole(role);
        message.setTitle(title);
        message.setContent(content);
        message.setUnknownScope(unknownScope);
        message.setCitationsJson(citations == null ? null : JsonUtils.write(citations));
        message.setEvidenceId(evidenceId);
        message.setModelIdentityJson(modelIdentity == null ? null : JsonUtils.write(modelIdentity));
        message.setCreatedAt(Instant.now());
        return chatMessageRepository.save(message);
    }

    private void persistAssistant(String projectId, AskResponseVO response,Long questionMessageId) {
        String content = response.isGrounded()
                ? (response.getContent() == null ? "" : response.getContent().getZhHans())
                : null;
        ChatMessage message=saveMessage(projectId, ChatMessage.ROLE_ASSISTANT,
                response.getTitle() == null ? null : response.getTitle().getZhHans(),
                content,
                response.getUnknownScope(),
                response.getCitations(),
                response.getEvidenceId(), response.getModelIdentity());
        message.setSourcesJson(JsonUtils.write(response.getSources()));message.setClarificationsJson(JsonUtils.write(response.getClarifications()));message.setSourceScope(response.getSourceScope());message.setBasisScope(response.getBasisScope());message.setQuestionMessageId(questionMessageId);chatMessageRepository.save(message);response.setMessageId(message.getId());
    }

    private List<String> safeList(String json) {
        try {
            return JsonUtils.readList(json, String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 模型返回的回答记录 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnswerRecord {
        private boolean grounded;
        private String answer;
        private List<String> citations;
        private String missingReason;
        private List<String> clarifications;
        private String basisScope;
        public AnswerRecord(boolean grounded,String answer,List<String> citations,String missingReason){this(grounded,answer,citations,missingReason,null,null);}
        public AnswerRecord(boolean grounded,String answer,List<String> citations,String missingReason,List<String> clarifications){this(grounded,answer,citations,missingReason,clarifications,null);}
    }

    public List<AdviceSourceVO> sources(String projectId,String scope) {
        projectService.require(projectId);String normalized=ProjectDocumentScope.normalizeScope(scope);
        return ProjectDocumentScope.currentCatalog(projectId,sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId)).stream().map(d->{String exclusion=ProjectDocumentScope.exclusionReason(d,normalized);
            return new AdviceSourceVO(String.valueOf(d.getId()),d.getFileKey(),d.getFileName(),VettingCorpus.sourceRole(d),d.getDocumentScope(),d.getParseStatus(),VettingCorpus.sourceHash(d),exclusion==null,exclusion);}).collect(Collectors.toList());
    }
    private List<SourceDocument> selectedDocuments(String projectId,String scope){return ProjectDocumentScope.selected(projectId,sourceDocumentRepository.findByProjectIdOrderByIdAsc(projectId),scope);}
    private boolean freshIndex(List<EvidenceChunk> chunks,List<SourceDocument> documents) {
        Map<String,String> hashes=new LinkedHashMap<>(),roles=new LinkedHashMap<>();
        for(SourceDocument d:documents){String id=String.valueOf(d.getId());hashes.put(id,VettingCorpus.sourceHash(d));roles.put(id,VettingCorpus.sourceRole(d));}
        if(!chunks.stream().map(c->String.valueOf(c.getDocumentId())).collect(Collectors.toSet()).equals(hashes.keySet()))return false;
        return chunks.stream().allMatch(c->Objects.equals(c.getSourceHash(),hashes.get(String.valueOf(c.getDocumentId())))&&Objects.equals(c.getSourceRole(),roles.get(String.valueOf(c.getDocumentId()))));
    }
    private List<CitationVO> safeSources(String json){if(JsonUtils.isBlankText(json))return Collections.emptyList();try{return JsonUtils.readList(json,CitationVO.class);}catch(Exception e){return Collections.emptyList();}}
    private List<String> cleanClarifications(List<String> questions){if(questions==null)return Collections.emptyList();return questions.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isEmpty()).distinct().limit(12).collect(Collectors.toList());}
    private List<String> validatedCitations(List<String> requested,List<CitationVO> submitted) {
        if(requested==null||requested.isEmpty())return Collections.emptyList();List<String> accepted=new ArrayList<>();
        for(String citation:requested){String value=nvl(citation).trim();List<CitationVO> matches=submitted.stream().filter(s->value.equals(s.getEvidenceId())||value.equals(s.getFileLabel())).collect(Collectors.toList());
            if(matches.size()!=1)return Collections.emptyList();String id=matches.get(0).getEvidenceId();if(!accepted.contains(id))accepted.add(id);
        }return accepted;
    }
    /** Library retrieval must also show current incorporation/edition/override provisions, if available. */
    private List<VectorStore.SearchHit> withIncorporationContext(String projectId,String scope,List<VectorStore.SearchHit> hits) {
        List<VectorStore.SearchHit> libraries=hits.stream().filter(h->"standard".equals(String.valueOf(h.getPayload().get("sourceRole")))).collect(Collectors.toList());
        if(libraries.isEmpty())return hits;
        List<SourceDocument> selected=selectedDocuments(projectId,scope).stream().filter(ProjectDocumentScope::parseUsable).collect(Collectors.toList());
        Set<String> allowedIds=selected.stream().map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        Set<String> compositionIds=selected.stream().filter(d->{String name=nvl(d.getFileName()).toLowerCase(java.util.Locale.ROOT).replace('_',' ').replace('-',' ');return "tender".equals(VettingCorpus.sourceRole(d))&&(name.contains("the specification")||name.contains("specification composition"));}).map(d->String.valueOf(d.getId())).collect(Collectors.toSet());
        List<VectorStore.SearchHit> result=new ArrayList<>(hits);Set<String> present=result.stream().map(this::evidenceIdentity).collect(Collectors.toSet());int chars=0;
        List<EvidenceChunk> ordered=chunkRepository.findByProjectIdOrderByChunkIndexAsc(projectId);
        ordered.sort(java.util.Comparator.comparingInt(c->compositionIds.contains(String.valueOf(c.getDocumentId()))?0:1));
        for(EvidenceChunk chunk:ordered){
            if(!allowedIds.contains(String.valueOf(chunk.getDocumentId()))||!"tender".equals(chunk.getSourceRole()))continue;
            boolean relevant=compositionIds.contains(String.valueOf(chunk.getDocumentId()))||libraries.stream().anyMatch(h->referenceMention(chunk.getContent(),h.fileLabel()));
            if(!relevant||chars+nvl(chunk.getContent()).length()>12000)continue;
            VectorStore.SearchHit context=chunkToHit(chunk);if(present.add(evidenceIdentity(context))){result.add(context);chars+=nvl(chunk.getContent()).length();}
        }
        boolean complete=ordered.stream().filter(c->compositionIds.contains(String.valueOf(c.getDocumentId()))).map(this::chunkToHit).map(this::evidenceIdentity).allMatch(present::contains);
        List<VectorStore.SearchHit> annotated=new ArrayList<>();for(VectorStore.SearchHit hit:result){Map<String,Object> payload=new LinkedHashMap<>(hit.getPayload());if("standard".equals(payload.get("sourceRole")))payload.put("applicabilityContextComplete",complete);annotated.add(new VectorStore.SearchHit(hit.getId(),hit.getScore(),payload));}return annotated;
    }
    private boolean incorporationSupported(String basisScope,List<String> citations,List<CitationVO> sources) {
        if("reference_only".equals(basisScope))return true;
        List<CitationVO> cited=sources.stream().filter(s->citations.contains(s.getEvidenceId())).collect(Collectors.toList());
        if(cited.stream().noneMatch(s->"tender".equals(s.getSourceRole())))return false;
        for(CitationVO reference:cited)if("standard".equals(reference.getSourceRole())
                &&(Boolean.FALSE.equals(reference.getApplicabilityContextComplete())||cited.stream().noneMatch(s->"tender".equals(s.getSourceRole())&&referenceMention(s.getContent(),reference.getFileLabel()))))return false;
        return true;
    }
    private boolean referenceMention(String passage,String referenceLabel) {
        String text=nvl(passage).toLowerCase(java.util.Locale.ROOT),label=nvl(referenceLabel).toLowerCase(java.util.Locale.ROOT);
        if(label.startsWith("sl ")||label.contains("specification library")||label.contains("general specification"))
            return text.contains("specification library")||text.contains("general specification")||text.matches("(?s).*\\b(?:sl[ -]?20\\d\\d|gscew\\d*|gs[ -]?20\\d\\d)\\b.*");
        if(label.startsWith("gcc ")||label.contains("general conditions of contract"))return text.contains("general conditions of contract")||text.matches("(?s).*\\bgcc\\b.*");
        String key=label.split("[ ·]",2)[0];return !key.isEmpty()&&text.matches("(?s).*\\b"+java.util.regex.Pattern.quote(key)+"\\b.*");
    }
    private String evidenceIdentity(VectorStore.SearchHit hit){return hit.getPayload().get("documentId")+"|"+hit.anchor()+"|"+VettingCorpus.hash(hit.text());}
    private QuestionContext clarificationContext(String projectId,String scope,Long replyTo,String text) {
        if(replyTo==null)return new QuestionContext(text,Collections.emptyList());
        ChatMessage parent=chatMessageRepository.findById(replyTo).filter(m->projectId.equals(m.getProjectId())&&ChatMessage.ROLE_ASSISTANT.equals(m.getRole())
                &&scope.equals(m.getSourceScope())&&"insufficient".equals(m.getBasisScope())&&m.getQuestionMessageId()!=null&&!safeList(nvl(m.getClarificationsJson())).isEmpty())
                .orElseThrow(()->new BizException(4202,"Reply target must be an unanswered clarification in the same project and evidence scope."));
        ChatMessage original=chatMessageRepository.findById(parent.getQuestionMessageId()).filter(m->projectId.equals(m.getProjectId())&&ChatMessage.ROLE_USER.equals(m.getRole())&&scope.equals(m.getSourceScope()))
                .orElseThrow(()->new BizException(4202,"The clarification's original question is no longer available. Start a new question."));
        List<String> background=new ArrayList<>(safeList(nvl(original.getUserBackgroundJson())));background.add(text);
        if(background.size()>5||background.stream().mapToInt(String::length).sum()>6000)throw new BizException(4202,"This clarification thread exceeds the bounded context. Start a new question with the relevant facts.");
        return new QuestionContext(JsonUtils.isBlankText(original.getOriginalQuestion())?original.getContent():original.getOriginalQuestion(),background);
    }
    private static final class QuestionContext {final String originalQuestion;final List<String> background;QuestionContext(String question,List<String> background){this.originalQuestion=question;this.background=background;}}
}
