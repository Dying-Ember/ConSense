package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.common.LocalizedText;
import com.consense.common.PublicErrorMessages;
import com.consense.document.DocumentParser;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.OerDocumentIdentity;
import com.consense.document.OerSourceRouting;
import com.consense.service.sources.OerSourceMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.consense.domain.DraftDocument;
import com.consense.domain.DraftArtifact;
import com.consense.document.DocxTemplateEditor;
import com.consense.repository.DraftArtifactRepository;
import com.consense.domain.DraftPdfArtifact;
import com.consense.repository.DraftPdfArtifactRepository;
import com.consense.config.ConsenseProperties;
import com.consense.domain.DraftVariable;
import com.consense.domain.Project;
import com.consense.domain.SourceDocument;
import com.consense.domain.DraftExtractionRun;
import com.consense.repository.DraftExtractionRunRepository;
import com.consense.repository.DraftDocumentRepository;
import com.consense.repository.DraftVariableRepository;
import com.consense.repository.SourceDocumentRepository;
import com.consense.service.ProjectService;
import com.consense.service.StorageService;
import com.consense.service.prompt.PromptCatalog;
import com.consense.service.prompt.PromptService;
import com.consense.web.dto.DraftingDtos.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Source-grounded candidates and one adopted input snapshot for three drafts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftingService {

    /**
     * 模板/证据送入模型的最大预算。
     * CPU 推理下 qwen2.5:7b + 8192 上下文：prompt 控制在 1.1 万字符内（约 4-5K token），
     * 留约 3K token 给输出；长模板靠决策片段聚焦提取保证质量，不靠堆全文。
     */
    private static final int MAX_DOC_CHARS = 8000;
    private static final int MAX_TEMPLATE_CHARS = 10000;
    private static final int MAX_EVIDENCE_CHARS = 3000;
    private static final int MAX_EVIDENCE_SNIPPET_CHARS = 2500;
    /**
     * 模板中的决策线索标记：命中的位置附近（条款正文 + 右侧/下方 Guidance Note 小字）
     * 优先送入模型，避免长模板从头截断把决策点全部砍掉。
     */
    private static final List<String> DECISION_MARKERS = Arrays.asList(
            "Guidance", "guidance", "NOTE:", "Option A", "Option B",
            "Not used", "not used", "Delete", "delete", "amend", "Amend",
            "alternative", "Alternat", "For use in", "For use where",
            "two-envelope", "envelope tendering", "e-Tender", "e-tender",
            "precast", "Precast", "nominated", "Nominated");
    private static final List<String> TEMPLATE_KEYS = Arrays.asList("NTT", "SCT", "SCC");
    private static final List<String> ACTIONS = Arrays.asList("fill", "choice", "rewrite", "delete", "notused");
    private static final double MIN_CONFIDENCE = 0.70;

    private final ProjectService projectService;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final AiGateway ai;
    private final SourceDocumentRepository sourceDocumentRepository;
    private final DraftVariableRepository variableRepository;
    private final DraftDocumentRepository documentRepository;
    private final DraftArtifactRepository artifactRepository;
    private final DraftPdfArtifactRepository pdfArtifactRepository;
    private final ConsenseProperties properties;
    /** 提示词从数据库取（支持前端在线编辑），DB 无值时回退出厂默认 */
    private final PromptService promptService;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final DraftExtractionHarness extractionHarness;
    private final DraftJointEvidenceReview jointEvidenceReview;
    private final DraftExtractionRunRepository extractionRunRepository;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DraftTemplateGraphService templateGraphService;

    private boolean reviewedRules(String projectId) { return templateGraphService!=null&&(templateGraphService.hasConfirmed(projectId)||templateGraphService.hasDraft(projectId)); }

    /** 模型识别结果（Jackson 反序列化用 Lombok POJO） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscoveredVariable {
        private String key;
        private String scope;
        private String fileKey;
        private String labelZh;
        private String labelZhHant;
        private String labelEn;
        private String action;
        private List<String> options;
        private String affects;
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=DraftCandidateValueDeserializer.class)
        private String value;
        private String reason;
        private String sourceQuote;
        private Double confidence;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedDoc {
        private String title;
        private String content;
    }

    // ------------------------------------------------------------ 第 1 步：模板

    public List<TemplateVO> listTemplates(String projectId) {
        projectService.require(projectId);
        List<SourceDocumentRepository.UploadDisplayMetadata> documents =
                sourceDocumentRepository.findUploadDisplayMetadataByProjectIdAndCategory(projectId,
                        SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        Map<String, SourceDocumentRepository.UploadDisplayMetadata> byKey = new HashMap<>();
        documents.forEach(d -> byKey.put(d.getFileKey(), d));

        List<TemplateVO> result = new ArrayList<>();
        for (String key : TEMPLATE_KEYS) {
            SourceDocumentRepository.UploadDisplayMetadata document = byKey.get(key);
            boolean uploaded = document != null && "PARSED".equals(document.getParseStatus());
            boolean partial = document != null && "PARTIAL".equals(document.getParseStatus());
            boolean identityPending = document != null && "IDENTITY_PENDING".equals(document.getParseStatus());
            result.add(new TemplateVO(
                    key,
                    "STD-" + key,
                    LocalizedText.of("标准 " + key + " 模板", "標準 " + key + " 模板", "Standard " + key + " template"),
                    document == null ? defaultTemplateFileName(key) : document.getFileName(),
                    LocalizedText.of("仅用于生成文稿结构及条款规则，不作为项目变量确定值的来源。",
                            "僅用於生成文稿結構及條款規則，不作為項目變量確定值的來源。",
                            "Generation structure and clause rules only; not a source of project input values."),
                    uploaded
                            ? LocalizedText.of("已上传", "已上傳", "Uploaded")
                            : partial ? LocalizedText.of("已解析，待复核", "已解析，待覆核", "Partially parsed; review required")
                            : identityPending ? LocalizedText.of("身份待确认", "身份待確認", "Source identity pending")
                            : document == null ? LocalizedText.of("未上传", "未上傳", "Not uploaded") : LocalizedText.of("解析失败", "解析失敗", "Parse failed"),
                    uploaded ? "ok" : partial ? "partial" : identityPending ? "identity_pending" : document == null ? "missing" : "failed"));
        }
        return result;
    }

    public UploadResultVO uploadTemplates(String projectId, List<MultipartFile> files) {
        projectService.require(projectId);List<String> messages=new ArrayList<>();
        Map<String,List<DraftTemplateRouting.Upload>> groups=new LinkedHashMap<>();
        List<DraftTemplateRouting.Upload> pdfs=new ArrayList<>(),auxiliaries=new ArrayList<>();
        Map<DraftTemplateRouting.Upload,String> preferredWords=new IdentityHashMap<>();
        int failed=0,parsed=0;
        for(MultipartFile file:files) {
            if(file==null||file.isEmpty())continue;
            try {
                DraftTemplateRouting.Upload upload=new DraftTemplateRouting.Upload(file,file.getBytes());
                preflightTemplateIdentity(upload,null);
                if("pdf".equals(upload.format)){pdfs.add(upload);continue;}
                parseTemplateUpload(upload,null);groups.computeIfAbsent(upload.fileKey,key->new ArrayList<>()).add(upload);
            }catch(Exception failure){failed++;messages.add(file.getOriginalFilename()+": "+uploadFailure(failure));}
        }
        for(DraftTemplateRouting.Upload pdf:pdfs) {
            String hint=DraftTemplateRouting.filenameHint(pdf.file.getOriginalFilename());
            String wordHash=hint==null?null:existingPreferredWordHash(projectId,hint);
            if(wordHash==null&&hint!=null&&groups.containsKey(hint))try{DraftTemplateRouting.Upload candidate=DraftTemplateRouting.primary(groups.get(hint));if("docx".equals(candidate.format))wordHash=candidate.hash;}catch(BizException ambiguous){/* The family conflict is reported below. */}
            if(wordHash!=null){auxiliaries.add(pdf);preferredWords.put(pdf,wordHash);continue;}
            try{
                parseTemplateUpload(pdf,null);wordHash=existingPreferredWordHash(projectId,pdf.fileKey);
                if(wordHash!=null){auxiliaries.add(pdf);preferredWords.put(pdf,wordHash);}
                else groups.computeIfAbsent(pdf.fileKey,key->new ArrayList<>()).add(pdf);
            }
            catch(Exception failure){retainInitialFailedTemplate(projectId,null,pdf,failure);failed++;messages.add(pdf.file.getOriginalFilename()+": "+uploadFailure(failure));}
        }
        for(Map.Entry<String,List<DraftTemplateRouting.Upload>> group:groups.entrySet()) {
            try {
                DraftTemplateRouting.Upload selected=DraftTemplateRouting.primary(group.getValue());
                SourceDocument previous=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,selected.fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
                String previousSourceHash=previous==null?null:currentTemplateSourceHash(previous);
                if(previous!=null&&!selected.hash.equals(previousSourceHash))throw new BizException(4012,"TEMPLATE_REPLACEMENT_REQUIRED: "+selected.fileKey+" already has a different source. Use its explicit replace action; the existing source is retained.");
                saveTemplateUpload(projectId,selected,false);parsed++;
                messages.add(selected.fileKey+" ← "+selected.file.getOriginalFilename()+" parsed; source "+selected.hash+". "+selected.parsed.getMessage());
                for(DraftTemplateRouting.Upload candidate:group.getValue())if(candidate!=selected) {
                    if(candidate.hash.equals(selected.hash))messages.add(candidate.file.getOriginalFilename()+": duplicate source bytes; no additional primary was selected.");
                    else {auxiliaries.add(candidate);preferredWords.put(candidate,selected.hash);}
                }
            }catch(Exception failure){failed+=group.getValue().size();messages.add(group.getKey()+": "+uploadFailure(failure));}
        }
        for(DraftTemplateRouting.Upload auxiliary:auxiliaries)try {
            SourceDocument saved=saveAuxiliaryTemplate(projectId,auxiliary,preferredWords.get(auxiliary));
            messages.add(auxiliary.file.getOriginalFilename()+": unselected_auxiliary_candidate; Word candidate preferred. "+(auxiliary.parsed==null?"PDF content was not parsed; family/edition equivalence is unverified":"Complete independent PDF parse retained; edition equivalence is unverified")+"; raw source "+auxiliary.hash+", retained source record "+saved.getId()+".");
        }catch(Exception failure){failed++;messages.add(auxiliary.file.getOriginalFilename()+": "+uploadFailure(failure));}
        invalidateDocuments(projectId);return new UploadResultVO(files.size(),parsed,failed,messages);
    }

    private String uploadFailure(Exception failure) {
        log.warn("drafting upload failed",failure);
        return PublicErrorMessages.from(failure,"文件处理失败，请稍后重试；若仍失败，请联系维护人员。");
    }

    private void parseTemplateUpload(DraftTemplateRouting.Upload upload,String expectedKey) {
        SourceDocument source=preflightTemplateIdentity(upload,expectedKey);OerDocumentIdentity identity=OerSourceMetadata.identity(source);
        DocumentParser.ParsedDocument observation=null,result;
        if("pdf".equals(upload.format)&&!"sha_verified".equals(identity.getIdentityStatus())) {
            observation=documentParser.observe("template.pdf",upload.bytes);
            boolean readable=OerSourceMetadata.captureObservation(source,observation,upload.bytes);
            requireTemplateIdentity(source,expectedKey);
            if(!readable)throw new BizException(4012,"TEMPLATE_IDENTITY_PENDING: Identity observation did not establish a non-drawing source; full parsing was not started.");
            identity=OerSourceMetadata.identity(source);
        }
        if(observation!=null||"sha_verified".equals(identity.getIdentityStatus()))result=documentParser.parseTemplate(expectedKey,upload.file.getOriginalFilename(),upload.bytes,observation,identity);
        else result=documentParser.parseTemplate(expectedKey,upload.file.getOriginalFilename(),upload.bytes);
        DraftTemplateRouting.bind(upload,result,expectedKey);
        OerSourceMetadata.captureParsed(source,result);requireTemplateIdentity(source,expectedKey);
    }
    private SourceDocument preflightTemplateIdentity(DraftTemplateRouting.Upload upload,String expectedKey) {
        if(upload.identitySource==null)upload.identitySource=knownTemplateSource(upload);
        requireTemplateIdentity(upload.identitySource,expectedKey);return upload.identitySource;
    }
    SourceDocument knownTemplateSource(DraftTemplateRouting.Upload upload) {
        SourceDocument source=new SourceDocument();source.setFileName(upload.file.getOriginalFilename());source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        if(OerSourceRouting.isDrawing(null,source.getFileName(),upload.bytes))OerSourceMetadata.captureDrawing(source,upload.bytes);
        else OerSourceMetadata.captureKnownIdentity(source,upload.bytes);return source;
    }
    private void requireTemplateIdentity(SourceDocument source,String expectedKey) {
        OerDocumentIdentity identity=OerSourceMetadata.identity(source);
        String exclusion=OerSourceMetadata.moduleExclusion(source,"drafting");
        if("DRAWING".equals(identity.getKind())||exclusion!=null)throw new BizException(4012,"TEMPLATE_SOURCE_EXCLUDED: "+(exclusion==null?"drawing_outside_parsing":exclusion));
        if(!"unknown".equals(identity.getSourcePurpose())&&!"standard_template".equals(identity.getSourcePurpose()))throw new BizException(4012,"TEMPLATE_SOURCE_PURPOSE_MISMATCH: A completed project or reference source cannot become a standard template.");
        if(expectedKey!=null&&TEMPLATE_KEYS.contains(identity.getKind())&&!expectedKey.equals(identity.getKind()))throw new BizException(4012,"TEMPLATE_FAMILY_MISMATCH: Expected "+expectedKey+" but the source identity is "+identity.getKind()+".");
    }
    private String existingPreferredWordHash(String projectId,String fileKey) {
        SourceDocument source=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
        if(source==null||"FAILED".equals(source.getParseStatus())||source.getStoragePath()==null)return null;
        try {
            byte[] bytes=storageService.read(source.getStoragePath());String hash=DraftTemplateRouting.sha(bytes);
            if(!"docx".equals(DraftTemplateRouting.format(bytes))||!hash.equals(source.getSourceSha256()))return null;
            JsonNode coverage=JsonUtils.parse(source.getParseCoverageJson());
            JsonNode evidence=OerTemplateEvidence.requireBound(fileKey,hash,JsonUtils.parse(source.getStructuredContentJson()),coverage,coverage.path("templateEvidence"));
            JsonNode route=evidence.path("route");
            // A title-supported Word candidate is usable without declaring legal identity or PDF edition equivalence.
            return fileKey.equals(route.path("profile").asText())&&fileKey.equals(route.path("titleFamilyCandidate").asText())
                    &&"docx".equals(route.path("format").asText())&&hash.equals(coverage.path("nativeDocxParagraphs").path("sourceSha256").asText())?hash:null;
        }catch(RuntimeException unavailable){return null;}
    }
    private SourceDocument saveTemplateUpload(String projectId,DraftTemplateRouting.Upload upload,boolean replacement) {
        StorageService.StoredFile stored=storeTemplateAsset(projectId,upload);
        return new TransactionTemplate(transactionManager).execute(status->{
            lockTemplateProject(projectId);
            SourceDocument previous=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,upload.fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
            if(previous!=null)entityManager.refresh(previous);
            if(!replacement&&previous!=null&&!upload.hash.equals(currentTemplateSourceHash(previous)))throw new BizException(4012,"TEMPLATE_REPLACEMENT_REQUIRED: The current source changed; use its explicit replace action.");
            String previousHash=previous==null?null:DraftAdoption.sourceHash(previous);
            SourceDocument document=storedTemplateSource(projectId,upload.fileKey,upload,stored);
            if(previous!=null){document.setId(previous.getId());document.setReviewRole(previous.getReviewRole());document.setDocumentScope(previous.getDocumentScope());}
            DocumentParser.ParsedDocument result=upload.parsed;
            document.setParseStatus(result.getParseStatus());document.setParseMessage(result.getMessage());document.setPageCount(result.getPageCount());document.setOcrUsed(result.isOcrUsed());
            document.setTextContent(result.getText());document.setStructuredContentJson(JsonUtils.write(result.getBlocks()));document.setParseCoverageJson(JsonUtils.write(result.getCoverage()));
            com.consense.service.sources.OerSourceMetadata.captureParsed(document,result);
            SourceDocument saved=sourceDocumentRepository.save(document);
            if(previousHash==null||!previousHash.equals(DraftAdoption.sourceHash(document)))markTemplateReview(projectId,upload.fileKey);return saved;
        });
    }
    private void lockTemplateProject(String projectId) {
        if(entityManager.find(Project.class,projectId,LockModeType.PESSIMISTIC_WRITE)==null)throw new BizException(4006,"Project no longer exists.");
    }
    private String currentTemplateSourceHash(SourceDocument source) {
        String declared=source.getSourceSha256();
        if(source.getStoragePath()==null)return declared;
        String actual=DraftTemplateRouting.sha(storageService.read(source.getStoragePath()));
        if(declared!=null&&!declared.equals(actual))throw new BizException(4012,"TEMPLATE_CURRENT_SOURCE_HASH_MISMATCH: The existing original changed; use an explicit replacement.");
        return actual;
    }
    private StorageService.StoredFile storeTemplateAsset(String projectId,DraftTemplateRouting.Upload upload) {
        StorageService.StoredFile stored=storageService.store(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE,upload.file);
        if(!upload.hash.equals(DraftTemplateRouting.sha(storageService.read(stored.getPath()))))throw new BizException(4012,"TEMPLATE_STORED_BYTES_CHANGED");return stored;
    }
    private SourceDocument storedTemplateSource(String projectId,String fileKey,DraftTemplateRouting.Upload upload,StorageService.StoredFile stored) {
        SourceDocument source=new SourceDocument();source.setProjectId(projectId);source.setCategory(SourceDocument.CATEGORY_STANDARD_TEMPLATE);source.setFileKey(fileKey);
        source.setFileName(stored.getOriginalName());source.setContentType(stored.getContentType());source.setSizeBytes(stored.getSize());source.setStoragePath(stored.getPath());source.setSourceSha256(upload.hash);source.setCreatedAt(Instant.now());return source;
    }
    private void retainInitialFailedTemplate(String projectId,String fileKey,DraftTemplateRouting.Upload upload,Exception failure) {
        // Preserve initial unsupported/corrupt raw sources for the existing source/reading API; no analysis is fabricated.
        boolean pending=String.valueOf(failure.getMessage()).startsWith("TEMPLATE_IDENTITY_PENDING:");
        if(upload==null||fileKey==null&&!pending||failure instanceof BizException&&((BizException)failure).getCode()==4012&&!pending||!(pending||"unknown".equals(upload.format)||failure instanceof BizException&&((BizException)failure).getCode()==4003||String.valueOf(failure.getMessage()).startsWith("TEMPLATE_PARSE_FAILED:")))return;
        new TransactionTemplate(transactionManager).execute(status->{
            lockTemplateProject(projectId);
            if(fileKey!=null&&sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE).isPresent())return null;
            if(fileKey==null)for(SourceDocument existing:sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE))if(existing.getFileKey()==null&&"IDENTITY_PENDING".equals(existing.getParseStatus())&&upload.hash.equals(existing.getSourceSha256()))return null;
            SourceDocument source=storedTemplateSource(projectId,fileKey,upload,storeTemplateAsset(projectId,upload));
            source.setParseStatus(pending?"IDENTITY_PENDING":"FAILED");source.setParseMessage(failure.getMessage());source.setPageCount(0);source.setOcrUsed(false);
            if(pending&&upload.identitySource!=null)source.setOerIdentityJson(upload.identitySource.getOerIdentityJson());
            source.setParseCoverageJson(JsonUtils.write(DraftBusinessRules.map("templateSelection",DraftBusinessRules.map("status",pending?"identity_pending_unanalysed_source":"failed_unanalysed_source","explicitFileKey",fileKey,"familyConfirmed",false,"sourceSha256",upload.hash))));
            sourceDocumentRepository.save(source);return null;
        });
    }
    private SourceDocument saveAuxiliaryTemplate(String projectId,DraftTemplateRouting.Upload upload,String wordHash) {
        SourceDocument document=null;
        for(SourceDocument existing:sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE))
            if(DraftTemplateRouting.isAuxiliary(existing)&&upload.hash.equals(existing.getSourceSha256())&&existing.getStoragePath()!=null&&upload.hash.equals(DraftTemplateRouting.sha(storageService.read(existing.getStoragePath())))) {
                if(upload.parsed==null)return existing;document=existing;break;
            }
        if(document==null)document=storedTemplateSource(projectId,null,upload,storeTemplateAsset(projectId,upload));
        document.setParseStatus(DraftTemplateRouting.AUXILIARY_STATUS);
        if(upload.parsed==null) {
            document.setPageCount(0);document.setOcrUsed(false);document.setParseMessage("Word preferred; this PDF is retained without parsing or a verified family/edition relationship.");
            document.setParseCoverageJson(JsonUtils.write(DraftBusinessRules.map("templateSelection",DraftBusinessRules.map("status","unselected_auxiliary_candidate","filenameFamilyHint",DraftTemplateRouting.filenameHint(upload.file.getOriginalFilename()),"familyConfirmed",false,"sameEditionVerified",false,"contentParsed",false,"sourceSha256",upload.hash,"preferredWordCandidateSha256",wordHash))));
        } else {
            DocumentParser.ParsedDocument result=upload.parsed;
            document.setPageCount(result.getPageCount());document.setOcrUsed(result.isOcrUsed());document.setTextContent(result.getText());
            document.setStructuredContentJson(JsonUtils.write(result.getBlocks()));
            // Selection metadata must not change the coverage bound by the independent PDF analysis.
            document.setParseCoverageJson(JsonUtils.write(result.getCoverage()));OerSourceMetadata.captureParsed(document,result);
            document.setParseMessage("Word preferred; complete independent PDF observations retained without adopting this source or verifying edition equivalence.");
        }
        return sourceDocumentRepository.save(document);
    }

    // ------------------------------------------------------------ 第 1 步：证据

    public List<EvidenceVO> listInputs(String projectId) {
        projectService.require(projectId);
        return sourceDocumentRepository
                .findUploadDisplayMetadataByProjectIdAndCategory(projectId, SourceDocument.CATEGORY_PROJECT_INPUT)
                .stream().map(this::toEvidenceVO).collect(Collectors.toList());
    }

    /** An explicit replacement may change edition; failed parsing never overwrites the current source. */
    public UploadResultVO replaceTemplate(String projectId,String fileKey,MultipartFile file) {
        projectService.require(projectId);
        if(!TEMPLATE_KEYS.contains(fileKey))throw new BizException(4009,"Unknown template key: "+fileKey);
        if(file==null||file.isEmpty())throw new BizException(4010,"Replacement file is empty");
        DraftTemplateRouting.Upload upload=null;
        try {
            upload=new DraftTemplateRouting.Upload(file,file.getBytes());parseTemplateUpload(upload,fileKey);
            saveTemplateUpload(projectId,upload,true);invalidateDocuments(projectId);
            return new UploadResultVO(1,1,0,Collections.singletonList(fileKey+" ← "+file.getOriginalFilename()+" replaced and parsed; source "+upload.hash+". "+upload.parsed.getMessage()));
        }catch(Exception failure){retainInitialFailedTemplate(projectId,fileKey,upload,failure);return new UploadResultVO(1,0,1,Collections.singletonList(file.getOriginalFilename()+": "+uploadFailure(failure)+" 如已有模板，将继续保留。"));}
    }

    @Transactional
    public UploadResultVO uploadInputs(String projectId, List<MultipartFile> files) {
        projectService.require(projectId);
        List<String> messages = new ArrayList<>();
        List<SourceDocument> parsedDocuments = new ArrayList<>();
        int parsed = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            StorageService.StoredFile stored =
                    storageService.store(projectId, SourceDocument.CATEGORY_PROJECT_INPUT, file);
            SourceDocument document = new SourceDocument();
            document.setProjectId(projectId);
            document.setCategory(SourceDocument.CATEGORY_PROJECT_INPUT);
            document.setFileName(stored.getOriginalName());
            document.setContentType(stored.getContentType());
            document.setSizeBytes(stored.getSize());
            document.setStoragePath(stored.getPath());
            document.setCreatedAt(Instant.now());
            try {
                DocumentParser.ParsedDocument result =
                        documentParser.parse(stored.getOriginalName(), storageService.read(stored.getPath()));
                document.setParseStatus(result.getParseStatus());
                document.setParseMessage(result.getMessage());
                document.setPageCount(result.getPageCount());
                document.setOcrUsed(result.isOcrUsed());
                document.setTextContent(result.getText());
                document.setStructuredContentJson(JsonUtils.write(result.getBlocks()));
                document.setParseCoverageJson(JsonUtils.write(result.getCoverage()));
                com.consense.service.sources.OerSourceMetadata.captureParsed(document,result);
                if ("FAILED".equals(result.getParseStatus())) throw new BizException(4003, result.getMessage());
                parsed++;
                messages.add(stored.getOriginalName() + "：" + result.getMessage());
            } catch (Exception e) {
                document.setParseStatus("FAILED");
                document.setParseMessage(e.getMessage());
                failed++;
                messages.add(stored.getOriginalName() + " 解析失败：" + e.getMessage());
            }
            parsedDocuments.add(document);
        }
        // Parse and store the complete batch before the brief shared correspondence-write lock.
        entityManager.lock(projectService.require(projectId),LockModeType.PESSIMISTIC_WRITE);
        for(SourceDocument document:parsedDocuments) {
            sourceDocumentRepository.save(document);markSourceReview(projectId,document,false);
        }
        invalidateDocuments(projectId);
        return new UploadResultVO(files.size(), parsed, failed, messages);
    }

    @Transactional
    public void deleteInput(String projectId,Long id) {
        entityManager.lock(projectService.require(projectId),LockModeType.PESSIMISTIC_WRITE);
        SourceDocument source=sourceDocumentRepository.findById(id).filter(d->projectId.equals(d.getProjectId())&&SourceDocument.CATEGORY_PROJECT_INPUT.equals(d.getCategory()))
                .orElseThrow(()->new BizException(4011,"Project correspondence not found."));
        markSourceReview(projectId,source,true);
        // Keep the original stored file as evidence; deletion removes this project's active input link.
        sourceDocumentRepository.delete(source);
    }
    private void markSourceReview(String projectId,SourceDocument source,boolean deleted) {
        for(DraftVariable v:inputVariables(projectId)) {
            entityManager.refresh(v,LockModeType.PESSIMISTIC_WRITE);
            if(JsonUtils.isBlankText(v.getValueText()))continue;
            boolean related=false;
            List<CandidateVO> links=new ArrayList<>(DraftAdoption.candidates(v));
            links.addAll(DraftAdoption.adoptedSources(v));
            for(CandidateVO c:links)if(source.getId().equals(c.getSourceDocumentId())||source.getFileName().equals(c.getFileName())) {
                if(deleted||!DraftAdoption.sourceHash(source).equals(c.getSourceHash()))related=true;
            }
            if(related){v.setReviewRequired(true);variableRepository.save(v);}
        }
    }
    private void markTemplateReview(String projectId,String fileKey) {
        for(DraftVariable v:inputVariables(projectId))if(!JsonUtils.isBlankText(v.getValueText())&&v.getAffects()!=null&&Arrays.asList(v.getAffects().split(",")).contains(fileKey)) {
            v.setReviewRequired(true);variableRepository.save(v);
        }
    }

    // ------------------------------------------------------------ 第 2/3 步：变量

    @Transactional
    public List<VariableVO> listVariables(String projectId) {
        ensureInputs(projectId);
        if(reviewedRules(projectId)) {
            List<VariableVO> result=new ArrayList<>(templateGraphService.variables(projectId));
            result.add(identityVariableVO(projectId));return result;
        }
        return inputVariables(projectId).stream().map(variable->"contractTitle".equals(variable.getVarKey())?identityVariableVO(projectId):toVariableVO(variable)).collect(Collectors.toList());
    }

    public ExtractTraceVO lastExtractTrace(String projectId) {
        projectService.require(projectId);
        return extractionRunRepository.findFirstByProjectIdOrderByFinishedAtDescIdDesc(projectId).map(run->readExtractionReport(projectId,run)).orElse(null);
    }

    public List<ExtractRunSummaryVO> extractTraceHistory(String projectId,int limit) {
        projectService.require(projectId);
        return extractionRunRepository.findByProjectIdOrderByFinishedAtDescIdDesc(projectId,org.springframework.data.domain.PageRequest.of(0,Math.max(1,Math.min(20,limit))))
                .stream().map(run->{ExtractTraceVO trace=readExtractionReport(projectId,run);
                    ExtractRunSummaryVO summary=new ExtractRunSummaryVO(trace.getRunId(),trace.getHarnessVersion(),trace.getModel(),trace.getStatus(),trace.getStartedAt(),trace.getFinishedAt(),trace.getEvidenceRevision(),trace.getFailureCode(),trace.getFailureMessage(),trace.isStale());summary.setModelIdentity(trace.getModelIdentity());return summary;
                }).collect(Collectors.toList());
    }

    public ExtractTraceVO extractTraceRun(String projectId,String runId) {
        projectService.require(projectId);
        return readExtractionReport(projectId,extractionRunRepository.findByIdAndProjectId(runId,projectId).orElseThrow(()->new BizException(4006,"Extraction report not found for this project.")));
    }

    private ExtractTraceVO readExtractionReport(String projectId,DraftExtractionRun run) {
        ExtractTraceVO trace=JsonUtils.read(run.getTraceJson(),ExtractTraceVO.class);
        Map<String,Object> payload=JsonUtils.readMap(run.getTraceJson());
        trace.setStale(payload.containsKey("graphRevisionId")?templateGraphService==null||templateGraphService.traceStale(projectId,payload):!java.util.Objects.equals(trace.getEvidenceRevision(),evidenceRevision(projectId)));return trace;
    }

    private void appendExtractionReport(String projectId,ExtractTraceVO trace) {
        entityManager.persist(new DraftExtractionRun(trace.getRunId(),projectId,Instant.parse(trace.getFinishedAt()),JsonUtils.write(trace)));
    }

    public ProgressVO progress(String projectId) {
        projectService.require(projectId);ensureInputs(projectId);
        List<DraftVariable> inputs=inputVariables(projectId);
        Map<String,Object> currentPlan=plan(projectId,null);
        boolean ready=unresolved(currentPlan).isEmpty();
        int groups=((List<?>)catalog(projectId).get("groups")).size();
        int adopted=reviewedRules(projectId)?(ready?groups:0):completedGroups(inputs,currentPlan);
        List<String> generated=documentRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(d -> Boolean.TRUE.equals(d.getGenerated())).map(DraftDocument::getFileKey).collect(Collectors.toList());
        int evidence=sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT).size();
        int templates=(int)sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .stream().filter(d -> "PARSED".equals(d.getParseStatus())).count();
        return new ProgressVO(evidence,templates,groups,adopted,0,0,ready,ready,generated);
    }
    @SuppressWarnings("unchecked")
    private int completedGroups(List<DraftVariable> variables,Map<String,Object> plan) {
        Map<String,DraftVariable> byKey=new HashMap<>();for(DraftVariable v:variables)byKey.put(v.getVarKey(),v);
        Map<String,Object> effective=(Map<String,Object>)plan.get("effectiveValues");int completed=0;
        for(Object object:(List<?>)DraftBusinessRules.catalog().get("groups")) {
            boolean complete=true;Map<String,Object> group=(Map<String,Object>)object;
            for(Object item:(List<?>)group.get("fields")) {
                Map<String,Object> field=(Map<String,Object>)item;if(Boolean.TRUE.equals(field.get("optional")))continue;
                Boolean applicable=DraftBusinessRules.condition(field.get("condition"),effective);if(Boolean.FALSE.equals(applicable))continue;
                String key=String.valueOf(field.get("key"));DraftVariable variable=byKey.get(key);
                if(applicable==null||variable==null||!effective.containsKey(key)||!DraftInputRules.valid(variable)||Boolean.TRUE.equals(variable.getReviewRequired())||!Boolean.TRUE.equals(variable.getConfirmed()))complete=false;
            }if(complete)completed++;
        }return completed;
    }

    private List<DraftVariable> inputVariables(String projectId) {
        if(reviewedRules(projectId)) {
            Set<String> keys=new HashSet<>();for(Map<String,Object> field:DraftReviewGraph.fields(templateGraphService.graph(projectId)))keys.add(String.valueOf(field.get("id")));
            keys.add("contractTitle");
            return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream().filter(v->"INPUT".equals(v.getScope())&&keys.contains(v.getVarKey())).collect(Collectors.toList());
        }
        return variableRepository.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .filter(v -> DraftBlueprint.isInput(v.getVarKey()) && "INPUT".equals(v.getScope())).collect(Collectors.toList());
    }
    private void ensureInputs(String projectId) {
        projectService.require(projectId);
        Map<String,DraftVariable> existing=new HashMap<>();
        for(DraftVariable row:variableRepository.findByProjectIdOrderBySortOrderAsc(projectId))existing.put(row.getVarKey(),row);
        List<DraftBlueprint.InputSpec> specs=reviewedRules(projectId)?Collections.singletonList(DraftBlueprint.find("contractTitle")):DraftBlueprint.INPUTS;
        for (int i=0;i<specs.size();i++) {
            DraftBlueprint.InputSpec spec=specs.get(i);
            DraftVariable v=existing.get(spec.key);
            boolean fresh=v==null;
            if (fresh) { v=new DraftVariable();v.setProjectId(projectId);v.setVarKey(spec.key);v.setValueText(""); }
            boolean migration=!"INPUT".equals(v.getScope());
            if (migration && Boolean.TRUE.equals(v.getConfirmed())) v.setManuallyEdited(true);
            v.setScope("INPUT");v.setFileKey(null);v.setSortOrder(i);
            v.setLabelZhHans(spec.labelZhHans);v.setLabelZhHant(spec.labelZhHant);v.setLabelEn(spec.labelEn);
            v.setAction(spec.action);v.setKind(spec.kind);v.setAffects(spec.affects);
            v.setOptionsJson(spec.options.isEmpty()?null:serializeOptions(spec.options));
            v.setLinkedBase(null);v.setDerivedFrom(null);v.setColsJson(null);
            if (fresh) {v.setConfirmed(false);v.setManuallyEdited(false);v.setReviewRequired(false);}
            if("billNos".equals(spec.key)&&!JsonUtils.isBlankText(v.getValueText())) {
                try{v.setValueText(DraftInputRules.normalize(spec,v.getValueText()));}catch(RuntimeException invalidLegacy){/* Keep the original cache available for repair. */}
            }
            if (fresh||migration) v.setUpdatedAt(Instant.now());variableRepository.save(v);
        }
    }
    public Map<String,Object> baselineCatalog(String projectId) {projectService.require(projectId);return DraftCatalogAlignment.catalog();}

    public Map<String,Object> catalog(String projectId) {
        projectService.require(projectId);if(!reviewedRules(projectId))return DraftBusinessRules.catalog();
        Map<String,Object> result=new LinkedHashMap<>(templateGraphService.catalog(projectId));List<Object> groups=new ArrayList<>();
        for(Object raw:DraftBusinessRules.list(DraftBusinessRules.catalog().get("groups")))if("identity".equals(DraftBusinessRules.asMap(raw).get("id")))groups.add(raw);
        groups.addAll(DraftBusinessRules.list(result.get("groups")));result.put("groups",groups);return result;
    }
    private VariableVO identityVariableVO(String projectId) {
        DraftVariable variable=variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElseThrow(()->new BizException(4006,"Project identity input not found."));
        VariableVO result=toVariableVO(variable);
        if(resolvePackageIdentity(projectId,variable).requiresReview()){result.setReviewRequired(true);result.setAdoptionState("needs_review");}
        return result;
    }
    /** A live read of the fixed adopted input; no extraction, generation or Project synchronization. */
    @Transactional(readOnly=true)
    public PackageIdentitySnapshot packageIdentitySnapshot(String projectId) {
        projectService.require(projectId);
        return resolvePackageIdentity(projectId,variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElse(null));
    }
    /** Serialized at construction so neither callers nor a later mirror update can mutate this snapshot. */
    public static final class PackageIdentitySnapshot {
        public static final String POLICY="adopted-package-identity-v1";
        private final String coreJson,auditJson,authoritySha256;
        private PackageIdentitySnapshot(Map<String,Object> core,Map<String,Object> audit) {
            coreJson=JsonUtils.write(core);auditJson=JsonUtils.write(audit);authoritySha256=DraftAdoption.hash(coreJson);
        }
        public JsonNode toJson(){Map<String,Object> result=new LinkedHashMap<>();result.put("policy",POLICY);result.put("authoritySha256",authoritySha256);result.put("core",JsonUtils.parse(coreJson));result.put("audit",JsonUtils.parse(auditJson));return JsonUtils.mapper().valueToTree(result);}
        public String authoritySha256(){return authoritySha256;}
        private boolean requiresReview(){return JsonUtils.parse(coreJson).path("requiresReview").asBoolean();}
        private Object effectiveValue(){JsonNode core=JsonUtils.parse(coreJson);return core.path("eligible").asBoolean()?DraftAdoption.decode(core.path("normalizedValue").asText()):null;}
    }
    private PackageIdentitySnapshot resolvePackageIdentity(String projectId,DraftVariable variable) {
        Map<String,Object> core=new LinkedHashMap<>(),audit=new LinkedHashMap<>();List<String> blockers=new ArrayList<>();List<Map<String,Object>> bindings=new ArrayList<>();
        core.put("policy",PackageIdentitySnapshot.POLICY);core.put("projectId",projectId);core.put("key","contractTitle");
        core.put("present",variable!=null);core.put("valueText",variable==null?null:variable.getValueText());
        boolean confirmed=variable!=null&&Boolean.TRUE.equals(variable.getConfirmed()),manual=variable!=null&&Boolean.TRUE.equals(variable.getManuallyEdited()),review=variable!=null&&Boolean.TRUE.equals(variable.getReviewRequired());
        core.put("confirmed",confirmed);core.put("manuallyEdited",manual);core.put("reviewRequired",review);core.put("adoptedSourcesJson",variable==null?null:variable.getAdoptedSourcesJson());
        if(!confirmed)blockers.add(variable==null?"identity_missing":"identity_unconfirmed");if(review)blockers.add("identity_review_required");
        String normalized="",provenance="unknown";Map<String,Object> value=Collections.emptyMap();
        if(variable!=null) {
            try{normalized=DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),variable.getValueText());value=DraftBusinessRules.asMap(DraftAdoption.decode(normalized));}
            catch(RuntimeException invalid){blockers.add("identity_value_invalid");}
            try{requireIdentitySources(projectId,variable,bindings);provenance=bindings.isEmpty()?"explicit_manual":"source_backed";}
            catch(RuntimeException invalid){provenance="invalid";blockers.add("identity_provenance_invalid_or_changed");}
            audit.put("confirmedFrom",variable.getConfirmedFrom());audit.put("updatedAt",variable.getUpdatedAt());
        }
        core.put("normalizedValue",normalized);core.put("provenanceKind",provenance);core.put("sourceBindings",bindings);core.put("blockers",blockers);
        Map<String,Object> fields=new LinkedHashMap<>();boolean any=false;
        for(String key:Arrays.asList("number","title")) {
            Object raw=value.get(key);boolean available=raw instanceof String&&!((String)raw).codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));boolean eligible=blockers.isEmpty()&&available;
            Map<String,Object> field=new LinkedHashMap<>();field.put("value",raw);field.put("available",available);field.put("eligible",eligible);field.put("status",!blockers.isEmpty()?"blocked":available?"eligible":"missing");
            field.put("reasons",!blockers.isEmpty()?new ArrayList<>(blockers):available?Collections.emptyList():Collections.singletonList("identity_"+key+"_missing"));fields.put(key,field);any|=eligible;
        }
        core.put("fields",fields);core.put("eligible",any);core.put("requiresReview",review||variable!=null&&(confirmed||manual)&&(blockers.contains("identity_provenance_invalid_or_changed")||blockers.contains("identity_value_invalid")||!confirmed));
        return new PackageIdentitySnapshot(core,audit);
    }
    private Object identitySnapshot(String projectId) {
        DraftVariable variable=variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElse(null);
        return variable==null?null:Arrays.asList(variable.getValueText(),variable.getConfirmed(),variable.getManuallyEdited(),variable.getReviewRequired(),variable.getAdoptedSourcesJson(),variable.getConfirmedFrom());
    }
    private Map<String,Object> reviewedPlan(String projectId,Map<String,String> patches) {
        Map<String,String> graphPatches=patches==null?null:new LinkedHashMap<>(patches);if(graphPatches!=null)graphPatches.remove("contractTitle");
        Map<String,Object> result=new LinkedHashMap<>(templateGraphService.plan(projectId,graphPatches));
        DraftVariable variable=variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElse(null);
        PackageIdentitySnapshot identity=resolvePackageIdentity(projectId,variable);
        Object visible=variable==null?null:DraftAdoption.decode(variable.getValueText()),effective=identity.effectiveValue();boolean review=identity.requiresReview();
        if(patches!=null&&patches.containsKey("contractTitle")){visible=DraftAdoption.decode(DraftInputRules.normalize(DraftBlueprint.find("contractTitle"),patches.get("contractTitle")));effective=visible;review=false;}
        DraftBusinessRules.asMap(result.get("inputValues")).put("contractTitle",visible);
        Map<String,Object> values=DraftBusinessRules.asMap(result.get("effectiveValues"));if(effective==null)values.remove("contractTitle");else values.put("contractTitle",effective);
        for(String part:Arrays.asList("number","title"))if(!DraftBusinessRules.answered(DraftBusinessRules.asMap(effective).get(part)))unresolved(result).add(DraftBusinessRules.map("id","input-contractTitle-"+part,"kind","InputMissing","inputKeys",Collections.singletonList("contractTitle"),"message",LocalizedText.same("Complete the adopted project "+part+"; temporary project metadata is not a source fact.")));
        if(review)unresolved(result).add(DraftBusinessRules.map("id","review-contractTitle","kind","input_review","inputKeys",Collections.singletonList("contractTitle"),"message",LocalizedText.same("Project identity or its source changed. Review the adopted value.")));
        result.put("snapshotId",snapshotId(projectId,patches));result.put("savedSnapshotId",snapshotId(projectId,null));result.put("preview",patches!=null);return result;
    }
    private void requireIdentitySources(String projectId,DraftVariable variable,List<Map<String,Object>> bindings) {
        List<CandidateVO> adopted=new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode raw=JsonUtils.mapper().reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(variable.getAdoptedSourcesJson());
            if(raw==null||!raw.isArray())throw new BizException(4007,"Project identity provenance is missing or malformed. Adopt it again.");
            if(raw.isEmpty()&&!Boolean.TRUE.equals(variable.getManuallyEdited()))throw new BizException(4007,"Project identity source adoption is empty. Re-adopt it or explicitly enter a manual identity.");
            for(com.fasterxml.jackson.databind.JsonNode entry:raw) {
                com.fasterxml.jackson.databind.JsonNode id=entry.get("sourceDocumentId");
                if(!entry.isObject()||id==null||!id.isIntegralNumber()||!id.canConvertToLong()||id.asLong()<=0
                        ||!entry.path("sourceHash").isTextual()||!entry.path("sourceQuote").isTextual())
                    throw new BizException(4007,"Project identity source binding is malformed. Adopt it again.");
                adopted.add(JsonUtils.mapper().treeToValue(entry,CandidateVO.class));
            }
        }catch(BizException invalid){throw invalid;}
        catch(Exception invalid){throw new BizException(4007,"Project identity provenance is missing or malformed. Adopt it again.");}
        for(CandidateVO candidate:adopted) {
            Map<String,Object> binding=new LinkedHashMap<>();bindings.add(binding);binding.put("sourceDocumentId",candidate.getSourceDocumentId());binding.put("sourceHash",candidate.getSourceHash());binding.put("sourceQuote",candidate.getSourceQuote());binding.put("originalSha256",candidate.getOriginalSha256());binding.put("status","unverified");
            SourceDocument source=sourceDocumentRepository.findById(candidate.getSourceDocumentId()).orElseThrow(()->new BizException(4007,"Project identity source no longer exists."));
            binding.put("projectId",source.getProjectId());binding.put("category",source.getCategory());binding.put("parseStatus",source.getParseStatus());binding.put("currentSourceHash",DraftAdoption.sourceHash(source));binding.put("currentOriginalSha256",source.getSourceSha256());binding.put("originalBinding",candidate.getOriginalSha256()==null?"unknown_legacy_unbound":"unverified");
            if(!projectId.equals(source.getProjectId())||!SourceDocument.CATEGORY_PROJECT_INPUT.equals(source.getCategory())||!"PARSED".equals(source.getParseStatus())||!Objects.equals(candidate.getSourceHash(),DraftAdoption.sourceHash(source))||JsonUtils.isBlankText(candidate.getSourceQuote())||source.getTextContent()==null||!source.getTextContent().contains(candidate.getSourceQuote()))throw new BizException(4007,"Project identity source changed. Identify it again.");
            if(candidate.getOriginalSha256()!=null) {
                String actual=source.getStoragePath()==null?null:DraftPdfConverter.sha256(storageService.read(source.getStoragePath()));binding.put("checkedOriginalSha256",actual);
                if(!candidate.getOriginalSha256().equals(actual))throw new BizException(4007,"Project identity original changed. Identify it again.");binding.put("originalBinding","verified_current_bytes");
            }
            binding.put("status","verified_current_source");
        }
    }
    @Transactional
    public com.consense.web.dto.ProjectDtos.ProjectVO confirmProjectIdentity(String projectId,ProjectIdentityConfirmation request) {
        Project project=projectService.require(projectId);entityManager.refresh(project,LockModeType.PESSIMISTIC_WRITE);ensureInputs(projectId);
        Map<String,String> expected=request==null?null:request.getExpectedProjectIdentity();LocalizedText name=LocalizedText.of(project.getNameZhHans(),project.getNameZhHant(),project.getNameEn());
        Map<String,String> current=new LinkedHashMap<>();current.put("nameZhHans",name.getZhHans());current.put("nameZhHant",name.getZhHant());current.put("nameEn",name.getEn());current.put("contractNo",project.getContractNo());
        if(expected==null||!expected.equals(current))throw new BizException(4090,"PROJECT_IDENTITY_CONFLICT: Project metadata changed. Refresh before confirming.");
        DraftVariable variable=variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElseThrow(()->new BizException(4007,"Adopt the project identity before confirming."));entityManager.refresh(variable,LockModeType.PESSIMISTIC_WRITE);
        if(request.getExpectedAdoptedIdentity()==null||!Collections.singletonMap("value",variable.getValueText()).equals(request.getExpectedAdoptedIdentity()))throw new BizException(4090,"PROJECT_IDENTITY_CONFLICT: The adopted identity changed. Refresh before confirming.");
        if(!Boolean.TRUE.equals(variable.getConfirmed())||Boolean.TRUE.equals(variable.getReviewRequired())||!DraftInputRules.valid(variable))throw new BizException(4007,"Adopt and review both the project title and contract number before confirming.");
        PackageIdentitySnapshot adopted=resolvePackageIdentity(projectId,variable);if(adopted.requiresReview()||adopted.effectiveValue()==null)throw new BizException(4007,"Project identity provenance is missing or changed. Adopt it again.");Map<String,Object> identity=DraftBusinessRules.asMap(adopted.effectiveValue());String title=(String)identity.get("title"),number=(String)identity.get("number");
        if(number.length()>128||title.length()>512||number.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))||title.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))throw new BizException(4007,"Project title and contract number must be nonblank text within the supported metadata length.");
        if(!JsonUtils.isBlankText(project.getTitleLinesJson())) {
            List<LocalizedText> lines=JsonUtils.readList(project.getTitleLinesJson(),LocalizedText.class);List<LocalizedText> updated=new ArrayList<>();
            for(LocalizedText line:lines)updated.add(LocalizedText.of(identityTitleLine(line.getZhHans(),project.getContractNo(),project.getNameZhHans(),number,title),identityTitleLine(line.getZhHant(),project.getContractNo(),project.getNameZhHant(),number,title),identityTitleLine(line.getEn(),project.getContractNo(),project.getNameEn(),number,title)));
            project.setTitleLinesJson(JsonUtils.write(updated));
        }
        project.setNameZhHans(title);project.setNameZhHant(title);project.setNameEn(title);project.setContractNo(number);project.setUpdatedAt(Instant.now());
        variable.setConfirmedFrom("PROJECT_IDENTITY");variable.setUpdatedAt(Instant.now());variableRepository.save(variable);return projectService.toVO(project);
    }
    private String identityTitleLine(String text,String oldNumber,String oldTitle,String number,String title) {
        return !JsonUtils.isBlankText(oldNumber)&&Objects.equals(text,oldNumber)?number:!JsonUtils.isBlankText(oldTitle)&&Objects.equals(text,oldTitle)?title:text;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> plan(String projectId,Map<String,String> patches) {
        Map<String,Object> current=preparePlan(projectId,patches);
        return patches==null?savedGenerationPlan(projectId,current):current;
    }
    private Map<String,Object> preparePlan(String projectId,Map<String,String> patches) {
        if(reviewedRules(projectId))return reviewedPlan(projectId,patches);
        projectService.require(projectId);Map<String,Object> values=decodedValues(projectId);
        Map<String,Object> visibleValues=new LinkedHashMap<>(values);
        for(DraftVariable variable:inputVariables(projectId))
            if(!Boolean.TRUE.equals(variable.getConfirmed())&&!Boolean.TRUE.equals(variable.getManuallyEdited()))values.remove(variable.getVarKey());
        values.values().removeIf(value->value==null);
        if (patches!=null) for (Map.Entry<String,String> patch:patches.entrySet()) {
            if (!DraftBlueprint.isInput(patch.getKey()) && !"targetOverrides".equals(patch.getKey())) throw new BizException(4007,"Unknown drafting input: "+patch.getKey());
            if("targetOverrides".equals(patch.getKey())||"targetEdits".equals(patch.getKey()))validateTargetOverrides(projectId,patch.getValue());
            String proposed=patch.getValue();
            if("targetOverrides".equals(patch.getKey())||"targetEdits".equals(patch.getKey()))proposed=bindTargetSources(projectId,JsonUtils.write(visibleValues.get("targetOverrides")),proposed);
            String key="targetEdits".equals(patch.getKey())?"targetOverrides":patch.getKey();
            values.put(key,DraftAdoption.decode(DraftBlueprint.find(key),proposed));
            visibleValues.put(key,DraftAdoption.decode(DraftBlueprint.find(key),proposed));
        }
        values.values().removeIf(value->value==null);
        boolean identitySourceChanged=false;DraftVariable identity=variableRepository.findByProjectIdAndVarKey(projectId,"contractTitle").orElse(null);
        if(patches==null||!patches.containsKey("contractTitle")){PackageIdentitySnapshot adopted=resolvePackageIdentity(projectId,identity);Object effective=adopted.effectiveValue();if(effective==null)values.remove("contractTitle");else values.put("contractTitle",effective);identitySourceChanged=adopted.requiresReview();}
        Map<String,Object> result=DraftBusinessRules.plan(values);
        if(identitySourceChanged)unresolved(result).add(DraftBusinessRules.map("id","review-contractTitle-source","kind","input_review","inputKeys",Collections.singletonList("contractTitle"),"message",LocalizedText.same("Project identity source changed. Review the adopted value.")));
        addTargetSourceReviews(projectId,values.get("targetOverrides"),result);
        result.put("inputValues",visibleValues);
        String evidenceRevision=evidenceRevision(projectId);
        DraftVariable extractionState=variableRepository.findByProjectIdAndVarKey(projectId,"_evidenceSnapshot").orElse(null);
        boolean evidenceChanged=!sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT).isEmpty()
                &&(extractionState==null||!evidenceRevision.equals(extractionState.getValueText()));
        result.put("evidenceRevision",evidenceRevision);result.put("evidenceChangedSinceExtraction",evidenceChanged);
        if(evidenceChanged) {
            Map<String,Object> warning=new LinkedHashMap<>();warning.put("id","evidence-awaiting-extraction");warning.put("kind","evidence_changed");warning.put("inputKeys",Collections.emptyList());
            warning.put("message",LocalizedText.of("沟通资料已变化，重新识别后才能判断受影响的输入；当前采用值已保留。","溝通資料已變化，重新識別後才能判斷受影響的輸入；目前採用值已保留。","Correspondence changed. Extract it again to identify affected inputs. Existing adopted values are retained."));unresolved(result).add(warning);
        }
        for(String key:DraftBlueprint.draftFileKeys()) {
            SourceDocument source=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,key,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
            if(source!=null&&"PARSED".equals(source.getParseStatus())&&!JsonUtils.isBlankText(source.getTextContent()))DraftClauseRules.apply(source.getTextContent(),key,result);
        }
        for (DraftVariable v:inputVariables(projectId)) if (Boolean.TRUE.equals(v.getReviewRequired())) {
            Map<String,Object> item=new LinkedHashMap<>();item.put("id","review-"+v.getVarKey());item.put("kind","input_review");
            item.put("inputKeys",Collections.singletonList(v.getVarKey()));
            item.put("message",LocalizedText.of("相关资料已变化，请复核本次采用值。","相關資料已變化，請覆核本次採用值。","Related evidence has changed. Review the value adopted for this draft."));
            unresolved(result).add(item);
        }
        List<Object> sourceIdentities=new ArrayList<>();
        for(String category:Arrays.asList(SourceDocument.CATEGORY_STANDARD_TEMPLATE,SourceDocument.CATEGORY_PROJECT_INPUT))
            for(SourceDocument source:sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,category))if(!DraftTemplateRouting.isUnadopted(source))sourceIdentities.add(DraftAdoption.sourceIdentity(source));
        result.put("sourceIdentities",sourceIdentities);result.put("documentLanguage","en");
        result.put("snapshotId",snapshotId(projectId,patches));result.put("savedSnapshotId",snapshotId(projectId,null));result.put("preview",patches!=null);return result;
    }
    /** The lossy flat projection is a preview; completed source edits belong to the exact saved revisions. */
    private Map<String,Object> savedGenerationPlan(String projectId,Map<String,Object> current) {
        if(documentRepository==null||artifactRepository==null)return current;
        String snapshot=String.valueOf(current.get("snapshotId"));Map<String,DraftDocument> documents=new LinkedHashMap<>();
        for(DraftDocument document:documentRepository.findByProjectIdOrderByIdAsc(projectId))if(DraftBlueprint.draftFileKeys().contains(document.getFileKey())) {
            if(documents.put(document.getFileKey(),document)!=null)return current;
        }
        Map<String,Object> frozen=null;Map<String,String> revisions=new LinkedHashMap<>();
        for(String key:DraftBlueprint.draftFileKeys()) {
            DraftDocument document=documents.get(key);
            if(document==null||!Boolean.TRUE.equals(document.getGenerated())||Boolean.TRUE.equals(document.getContentEdited())||document.getRevisionId()==null||!snapshot.equals(document.getSnapshotId())||!Objects.equals(current.get("ruleVersion"),document.getRuleVersion()))return current;
            DraftArtifact artifact=artifactRepository.findById(document.getRevisionId()).orElse(null);
            if(artifact==null||!projectId.equals(artifact.getProjectId())||!key.equals(artifact.getFileKey())||!snapshot.equals(artifact.getSnapshotId())||artifact.getParentRevisionId()!=null||artifact.getDocxBytes()==null||!Objects.equals(artifact.getDocxSha256(),DraftPdfConverter.sha256(artifact.getDocxBytes())))return current;
            SourceDocument source=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,key,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
            if(source==null||!"PARSED".equals(source.getParseStatus())||source.getStoragePath()==null||!Objects.equals(artifact.getSourceSha256(),DraftPdfConverter.sha256(storageService.read(source.getStoragePath()))))return current;
            Map<String,Object> saved;
            try{saved=JsonUtils.readMap(document.getInputSnapshotJson());}catch(RuntimeException legacy){return current;}
            // Database source IDs are Longs; persisted JSON may decode small IDs as Integers.
            // Compare their exact JSON values rather than Java numeric wrapper identity.
            for(String identity:Arrays.asList("snapshotId","ruleVersion","evidenceRevision","effectiveValues","sourceIdentities"))if(!JsonUtils.write(current.get(identity)).equals(JsonUtils.write(saved.get(identity))))return current;
            if(!(saved.get("actions") instanceof List)||!(saved.get("unresolved") instanceof List))return current;
            if(frozen!=null&&!frozen.equals(saved))return current;
            frozen=saved;revisions.put(key,artifact.getId());
        }
        Map<String,Object> result=new LinkedHashMap<>(frozen);
        // The visible adopted-input view remains live; only the matching compiled generation supplies outcomes.
        for(String live:Arrays.asList("inputValues","snapshotId","savedSnapshotId","preview","evidenceRevision","evidenceChangedSinceExtraction","sourceIdentities"))result.put(live,current.get(live));
        result.put("applicationStateSource",DraftBusinessRules.map("kind","saved_generation","snapshotId",snapshot,"revisionIds",revisions));
        return result;
    }
    private Map<String,Object> decodedValues(String projectId) {
        if(reviewedRules(projectId))return DraftReviewGraph.map(templateGraphService.plan(projectId,null).get("inputValues"));
        Map<String,Object> values=new LinkedHashMap<>();
        for (DraftVariable v:variableRepository.findByProjectIdOrderBySortOrderAsc(projectId)) if (DraftBlueprint.isInput(v.getVarKey())||"targetOverrides".equals(v.getVarKey())) values.put(v.getVarKey(),DraftAdoption.decode(DraftBlueprint.find(v.getVarKey()),v.getValueText()));
        return values;
    }
    private String snapshotId(String projectId,Map<String,String> patches) {
        if(reviewedRules(projectId))return DraftAdoption.hash(JsonUtils.write(Arrays.asList(templateGraphService.snapshotId(projectId,patches),identitySnapshot(projectId),patches,DraftNativeIdentity.INTEGRATION_VERSION)));
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("ruleVersion",DraftBusinessRules.catalog().get("ruleVersion"));
        snapshot.put("projectIdentity",identitySnapshot(projectId));snapshot.put("nativeStoryVersion",DraftNativeIdentity.INTEGRATION_VERSION);
        Map<String,Object> values=decodedValues(projectId);if(patches!=null)patches.forEach((k,v)->values.put(k,DraftAdoption.decode(DraftBlueprint.find(k),v)));snapshot.put("values",values);
        List<Object> status=new ArrayList<>();for(DraftVariable v:inputVariables(projectId))status.add(Arrays.asList(v.getVarKey(),v.getManuallyEdited(),v.getReviewRequired(),v.getConfirmed(),v.getAdoptedSourcesJson()));snapshot.put("adoption",status);
        List<Object> sources=new ArrayList<>();for(String category:Arrays.asList(SourceDocument.CATEGORY_STANDARD_TEMPLATE,SourceDocument.CATEGORY_PROJECT_INPUT))for(SourceDocument source:sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,category)) {
            if(DraftTemplateRouting.isUnadopted(source))continue;
            sources.add(DraftAdoption.sourceIdentity(source));
            if(SourceDocument.CATEGORY_STANDARD_TEMPLATE.equals(category))sources.add(source.getStoragePath()==null?"EDITABLE_DOCX_MISSING":DraftPdfConverter.sha256(storageService.read(source.getStoragePath())));
        }snapshot.put("sources",sources);
        return DraftAdoption.hash(JsonUtils.write(snapshot));
    }
    private String evidenceRevision(String projectId) {
        return evidenceRevision(sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT));
    }
    private String evidenceRevision(List<SourceDocument> evidence) {
        List<Object> sources=new ArrayList<>();for(SourceDocument source:evidence)sources.add(DraftAdoption.sourceIdentity(source));
        return DraftAdoption.hash(JsonUtils.write(sources));
    }
    private void recordEvidenceRevision(String projectId) {
        DraftVariable state=variableRepository.findByProjectIdAndVarKey(projectId,"_evidenceSnapshot").orElseGet(DraftVariable::new);
        state.setProjectId(projectId);state.setVarKey("_evidenceSnapshot");state.setScope("STATE");state.setValueText(evidenceRevision(projectId));state.setSortOrder(Integer.MAX_VALUE);state.setUpdatedAt(Instant.now());variableRepository.save(state);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> unresolved(Map<String,Object> plan) {
        Object value=plan.get("unresolved");if(!(value instanceof List)){value=new ArrayList<Map<String,Object>>();plan.put("unresolved",value);}return (List<Map<String,Object>>)value;
    }

    public List<VariableVO> extractVariables(String projectId) {
        if(reviewedRules(projectId)){new TransactionTemplate(transactionManager).execute(status->{ensureInputs(projectId);return null;});templateGraphService.extract(projectId);return listVariables(projectId);}
        // Model calls run outside a write transaction. A user can adopt an input while extraction is in flight.
        TransactionTemplate transaction=new TransactionTemplate(transactionManager);
        transaction.execute(status->{ensureInputs(projectId);return null;});
        List<SourceDocument> evidence=sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,SourceDocument.CATEGORY_PROJECT_INPUT);
        String extractedRevision=evidenceRevision(evidence);
        String system=extractionHarness.systemPrompt(promptService.system(PromptCatalog.KEY_DISCOVER));
        List<String> raw=new ArrayList<>(),prompts=new ArrayList<>();
        ExtractTraceVO trace=new ExtractTraceVO(ai.chatModel(),null,system,null,raw);
        trace.setModelIdentity(ai.modelIdentity());
        trace.setRunId(java.util.UUID.randomUUID().toString());trace.setHarnessVersion(DraftExtractionHarness.VERSION+"+"+DraftJointEvidenceReview.VERSION);
        trace.setEvidenceRevision(extractedRevision);trace.setStartedAt(Instant.now().toString());
        Map<String,List<CandidateVO>> candidates=new LinkedHashMap<>();
        Map<Long,String> sourceTexts=new LinkedHashMap<>();
        try {
        for(SourceDocument source:evidence) {
            if(JsonUtils.isBlankText(source.getTextContent())||!"PARSED".equals(source.getParseStatus()))continue;
            sourceTexts.put(source.getId(),source.getTextContent());
            int partIndex=0,sourceStart=0;
            for(String part:DraftClauseRules.chunks(source.getTextContent(),MAX_EVIDENCE_CHARS)) {
                ExtractionPartVO reportPart=new ExtractionPartVO(source.getId()+":"+partIndex,source.getId(),source.getFileName(),DraftAdoption.sourceHash(source),partIndex++,part,new ArrayList<>());
                reportPart.setContext(DraftSourceContext.primary(source.getTextContent(),sourceStart,sourceStart+part.length()));sourceStart+=part.length();
                trace.getParts().add(reportPart);
            }
        }
        if(trace.getParts().isEmpty())throw new BizException(4005,"Upload successfully parsed project correspondence before extraction.");
        String configuredUser=promptService.userTemplate(PromptCatalog.KEY_DISCOVER);
        for(ExtractionPartVO part:trace.getParts())prompts.add(extractionHarness.userPrompt(configuredUser,part));
        for(int i=0;i<trace.getParts().size();i++) {
            ExtractionPartVO part=trace.getParts().get(i);
            try {extractionHarness.extractPart(trace,part,system,prompts.get(i),sourceTexts.get(part.getSourceDocumentId()));}
            catch(RuntimeException invalid) {
                ExtractionAttemptVO last=part.getAttempts().isEmpty()?null:part.getAttempts().get(part.getAttempts().size()-1);
                if(!(invalid instanceof BizException&&((BizException)invalid).getCode()==5002)&&(last==null||!"failed".equals(last.getStatus())||!"model_call_failed".equals(last.getErrorCode())))throw invalid;
                trace.setFailureCode("partial_source_failed");trace.setFailureMessage("Some correspondence could not be completed; source-checked candidates remain available for review.");
            }
        }
        Map<String,Object> conditions=new LinkedHashMap<>();
        // Only committed human/adopted values may override this run's applicability; stale unadopted suggestions cannot.
        for(DraftVariable variable:variableRepository.findByProjectIdOrderBySortOrderAsc(projectId))
            if(Boolean.TRUE.equals(variable.getManuallyEdited())||Boolean.TRUE.equals(variable.getConfirmed()))
                conditions.put(variable.getVarKey(),DraftAdoption.decode(DraftBlueprint.find(variable.getVarKey()),variable.getValueText()));
        extractionHarness.recall(trace,system,configuredUser,sourceTexts,conditions);
        extractionHarness.rejectInactiveSuggestions(trace,conditions);
        for(ExtractionPartVO part:trace.getParts()) {
            for(ExtractionDecisionVO item:trace.getDecisions()) {
                if(!part.getPartId().equals(item.getPartId()))continue;
                if(!"accepted".equals(item.getStatus()))continue;
                List<CandidateVO> fieldCandidates=candidates.computeIfAbsent(item.getKey(),key->new ArrayList<>());
                // Re-dispatching an overlapping source is not an independent vote. Decisions remain intact in the trace.
                boolean text="text".equals(DraftBlueprint.find(item.getKey()).kind);
                String valueIdentity=text?DraftEvidenceQuotes.textIdentity(item.getNormalizedValue()):item.getNormalizedValue();
                boolean repeated=fieldCandidates.stream().anyMatch(candidate->java.util.Objects.equals(candidate.getSourceDocumentId(),part.getSourceDocumentId())&&
                        java.util.Objects.equals(candidate.getSourceHash(),part.getSourceHash())&&java.util.Objects.equals(
                                text?DraftEvidenceQuotes.textIdentity(candidate.getValue()):candidate.getValue(),valueIdentity));
                if(!repeated)fieldCandidates.add(new CandidateVO(item.getNormalizedValue(),part.getSourceDocumentId(),part.getFileName(),part.getSourceHash(),item.getSourceQuote(),item.getReason(),item.getConfidence()));
            }
        }
        jointEvidenceReview.review(trace,candidates,sourceTexts);
        trace.setUserPrompt(String.join("\n\n--- Evidence part ---\n\n",prompts));
        return transaction.execute(status->{
        entityManager.clear();
        entityManager.lock(projectService.require(projectId),LockModeType.PESSIMISTIC_WRITE);
        if(!extractedRevision.equals(evidenceRevision(projectId)))throw new BizException(4007,"Correspondence changed during extraction. Extract it again; adopted values have been retained.");
        Set<String> conflicts=new HashSet<>();
        for(DraftVariable v:inputVariables(projectId)) {
            if(DraftBlueprint.find(v.getVarKey()).hidden)continue;
            // Lock and refresh only for the brief persistence step, after any concurrently committed human edit.
            entityManager.refresh(v,LockModeType.PESSIMISTIC_WRITE);
            List<CandidateVO> latest=candidates.getOrDefault(v.getVarKey(),Collections.emptyList());
            List<CandidateVO> previous=DraftAdoption.candidates(v);
            boolean partial=DraftExtractionHarness.incomplete(trace);
            if(partial&&latest.isEmpty()&&!previous.isEmpty()){v.setReviewRequired(true);variableRepository.save(v);continue;}
            boolean relatedChanged=!DraftAdoption.candidateIdentity(previous).equals(DraftAdoption.candidateIdentity(latest));
            boolean adopted=Boolean.TRUE.equals(v.getManuallyEdited())||Boolean.TRUE.equals(v.getConfirmed());
            v.setCandidatesJson(JsonUtils.write(latest));
            String merged="";boolean conflict=false;
            if("subcontractors".equals(v.getVarKey())) {
                merged=DraftTradeCandidates.merge(DraftBlueprint.find(v.getVarKey()),latest);
                conflict=merged==null;
            } else if("billNos".equals(v.getVarKey())) {
                merged=DraftBillCandidates.merge(DraftBlueprint.find(v.getVarKey()),latest);
                conflict=merged==null;
            } else if("designResponsibilities".equals(v.getVarKey())) {
                merged=DraftDesignCandidates.merge(DraftBlueprint.find(v.getVarKey()),latest);
                conflict=merged==null;
            } else for(CandidateVO candidate:latest) {
                if(merged.isEmpty()){merged=candidate.getValue();continue;}
                if(merged.equals(candidate.getValue()))continue;
                String combined=mergeListInput(DraftBlueprint.find(v.getVarKey()),merged,candidate.getValue());
                if(combined==null){conflict=true;break;}merged=combined;
            }
            if(conflict)conflicts.add(v.getVarKey());
            if(adopted) {
                if(relatedChanged)v.setReviewRequired(true);
            } else {
                v.setValueText(conflict?"":merged);v.setChoice(null);v.setConfirmed(false);v.setReviewRequired(false);
                ExtractionDecisionVO unanswered=latest.isEmpty()?unansweredDecision(trace,v.getVarKey()):null;
                CandidateVO displaySource=latest.isEmpty()?null:latest.get(0);
                if("designResponsibilities".equals(v.getVarKey())&&!conflict)
                    for(CandidateVO candidate:latest)if(Objects.equals(candidate.getValue(),merged)){displaySource=candidate;break;}
                v.setSourceRef(latest.isEmpty()?unanswered==null?null:unansweredSourceQuote(trace,unanswered):truncate(displaySource.getSourceQuote(),480));
                v.setNoteText(conflict?"Candidate values disagree. Select the value to adopt for this draft.":latest.isEmpty()?unanswered==null?
                        "No usable suggestion returned. The input remains editable.":unansweredNote(trace,unanswered):displaySource.getReason());
            }
            v.setUpdatedAt(Instant.now());variableRepository.save(v);
        }
        recordEvidenceRevision(projectId);
        boolean partial=DraftExtractionHarness.incomplete(trace);
        trace.setStatus(partial?"partial":"completed");trace.setFinishedAt(Instant.now().toString());
        if(partial&&trace.getFailureCode()==null){trace.setFailureCode("incomplete_json");trace.setFailureMessage("Only fully decoded, source-checked candidates were retained. Review the unfinished items manually.");}
        extractionHarness.describeFields(trace,conflicts,!partial);appendExtractionReport(projectId,trace);
        return inputVariables(projectId).stream().map(this::toVariableVO).collect(Collectors.toList());
        });
        } catch(RuntimeException failure) {
            trace.setStatus("failed");trace.setFinishedAt(Instant.now().toString());
            trace.setUserPrompt(String.join("\n\n--- Evidence part ---\n\n",prompts));
            trace.setFailureCode(failure instanceof BizException&&((BizException)failure).getCode()==4007?"source_changed":
                    failure instanceof BizException&&((BizException)failure).getCode()==5002?"primary_format_failed":
                    failure instanceof BizException&&((BizException)failure).getCode()==4005?"no_parsed_correspondence":"extraction_failed");
            trace.setFailureMessage(failure.getMessage());extractionHarness.describeFields(trace,Collections.emptySet(),false);
            TransactionTemplate failureTransaction=new TransactionTemplate(transactionManager);
            failureTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try{failureTransaction.execute(status->{entityManager.clear();appendExtractionReport(projectId,trace);return null;});}
            catch(RuntimeException reportFailure){failure.addSuppressed(reportFailure);log.error("Unable to persist failed extraction report {}",trace.getRunId(),reportFailure);}
            throw failure;
        }
    }

    private ExtractionDecisionVO unansweredDecision(ExtractTraceVO trace,String key) {
        for(ExtractionDecisionVO decision:trace.getDecisions())if(key.equals(decision.getKey())&&"unanswered".equals(decision.getStatus())&&
                decision.getCodes().contains("source_unresolved"))return decision;
        for(ExtractionDecisionVO decision:trace.getDecisions())if(key.equals(decision.getKey())&&"unanswered".equals(decision.getStatus())&&
                !JsonUtils.isBlankText(decision.getReason()))return decision;
        return null;
    }

    private String unansweredNote(ExtractTraceVO trace,ExtractionDecisionVO decision) {
        String file="";
        for(ExtractionPartVO part:trace.getParts())if(part.getPartId().equals(decision.getPartId())) {
            file=part.getFileName();
            break;
        }
        return (decision.getCodes().contains("source_unresolved")?"Source explicitly leaves this input unresolved. ":"Model reported this input as unresolved. ")+file+": "+decision.getReason();
    }

    private String unansweredSourceQuote(ExtractTraceVO trace,ExtractionDecisionVO decision) {
        for(ExtractionPartVO part:trace.getParts())if(part.getPartId().equals(decision.getPartId())&&
                DraftEvidenceQuotes.present(part.getSourceText(),decision.getSourceQuote()))return truncate(decision.getSourceQuote(),480);
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractionSchemas() {
        List<Map<String,Object>> fields=new ArrayList<>();
        for(DraftBlueprint.InputSpec spec:DraftBlueprint.INPUTS)if(!spec.hidden) {
            Map<String,Object> field=new LinkedHashMap<>();field.put("key",spec.key);field.put("meaning",spec.labelEn);field.put("kind",spec.kind);
            if(!spec.options.isEmpty())field.put("options",spec.options);
            if(spec.schema.containsKey("columnFields")) {
                List<Object> columns=new ArrayList<>();for(Object item:(List<?>)spec.schema.get("columnFields")) {
                    Map<String,Object> source=(Map<String,Object>)item;Map<String,Object> column=new LinkedHashMap<>();
                    column.put("key",source.get("key"));column.put("kind",source.get("kind"));
                    if(source.containsKey("options")) {List<Object> options=new ArrayList<>();for(Object option:(List<?>)source.get("options"))options.add(((Map<?,?>)option).get("value"));column.put("options",options);}
                    columns.add(column);
                }field.put("columnFields",columns);
            }fields.add(field);
        }return JsonUtils.write(fields);
    }

    private boolean quotePresent(String source, String quote) {
        return DraftEvidenceQuotes.present(source, quote);
    }

    private String mergeListInput(DraftBlueprint.InputSpec spec, String left, String right) {
        if ("contract".equals(spec.kind)) {
            Map<String,String> result = new LinkedHashMap<>();
            for (String key : Arrays.asList("number", "title")) {
                String a = JsonUtils.parse(left).path(key).asText(), b = JsonUtils.parse(right).path(key).asText();
                if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) return null;
                result.put(key, a.isEmpty() ? b : a);
            }
            return DraftInputRules.normalizeSuggestion(spec, JsonUtils.write(result));
        }
        if ("multiselect".equals(spec.kind)) {
            Set<String> values = new java.util.LinkedHashSet<>();
            for (com.fasterxml.jackson.databind.JsonNode node : JsonUtils.parse(left)) values.add(node.asText());
            for (com.fasterxml.jackson.databind.JsonNode node : JsonUtils.parse(right)) values.add(node.asText());
            return DraftInputRules.normalize(spec, JsonUtils.write(values));
        }
        if ("billNos".equals(spec.key)||"bills".equals(spec.kind)) {
            return DraftBillCandidates.merge(spec,left,right);
        }
        return null;
    }

    @Transactional
    public VariableVO updateVariable(String projectId,String key,VariablePatch patch) {
        if(reviewedRules(projectId)&&!"contractTitle".equals(key))return templateGraphService.updateVariable(projectId,key,patch);
        projectService.require(projectId);ensureInputs(projectId);
        if("targetEdits".equals(key))key="targetOverrides";
        DraftBlueprint.InputSpec spec=DraftBlueprint.find(key);
        if(spec==null)throw new BizException(4007,"Unknown drafting input: "+key);
        DraftVariable v=variableRepository.findByProjectIdAndVarKey(projectId,spec.key).orElseThrow(()->new BizException(4006,"Input not found."));
        entityManager.refresh(v,LockModeType.PESSIMISTIC_WRITE);
        String raw=patch.getValue()!=null?patch.getValue():patch.getChoice();
        if(("targetOverrides".equals(spec.key)||"targetEdits".equals(spec.key))&&raw!=null)validateTargetOverrides(projectId,raw);
        if(patch.getCandidateIndex()!=null) {
            List<CandidateVO> candidates=DraftAdoption.candidates(v);int index=patch.getCandidateIndex();
            if(index<0||index>=candidates.size())throw new BizException(4007,"Candidate no longer exists. Refresh the input.");
            CandidateVO candidate=candidates.get(index);
            if(patch.getCandidateSnapshot()==null||!DraftAdoption.candidateIdentity(Collections.singletonList(candidate)).equals(
                    DraftAdoption.candidateIdentity(Collections.singletonList(patch.getCandidateSnapshot()))))
                throw new BizException(4007,"Candidate changed since it was displayed. Refresh the input before adopting it.");
            SourceDocument source=sourceDocumentRepository.findById(candidate.getSourceDocumentId()).orElseThrow(()->new BizException(4007,"Candidate source no longer exists."));
            if(!projectId.equals(source.getProjectId())||!candidate.getSourceHash().equals(DraftAdoption.sourceHash(source)))throw new BizException(4007,"Candidate source has changed. Extract it again.");
            raw=candidate.getValue();v.setSourceRef(truncate(candidate.getSourceQuote(),480));v.setAdoptedSourcesJson(JsonUtils.write(Collections.singletonList(candidate)));v.setManuallyEdited(false);
        } else if(raw!=null) {v.setManuallyEdited(true);v.setSourceRef(null);v.setAdoptedSourcesJson("contractTitle".equals(key)?"[]":JsonUtils.write(DraftAdoption.candidates(v)));}
        else if(Boolean.TRUE.equals(patch.getReviewed())||Boolean.TRUE.equals(patch.getConfirmed())) {
            SuggestionSnapshot snapshot=patch.getSuggestionSnapshot();
            if(snapshot==null||snapshot.getCandidates()==null||snapshot.getCandidates().stream().anyMatch(Objects::isNull)||
                    snapshot.getReviewRequired()==null||!Objects.equals(Boolean.TRUE.equals(v.getReviewRequired()),snapshot.getReviewRequired())||!Objects.equals(nvl(v.getValueText()),snapshot.getValue())||
                    !Objects.equals(v.getSourceRef(),snapshot.getSource())||!DraftAdoption.candidateIdentity(DraftAdoption.candidates(v)).equals(DraftAdoption.candidateIdentity(snapshot.getCandidates())))
                throw new BizException(4007,"Suggestion or evidence changed since it was displayed. Refresh the input before adopting it.");
        }
        if(raw!=null) {
            if("targetOverrides".equals(spec.key))raw=bindTargetSources(projectId,v.getValueText(),raw);
            v.setValueText(DraftInputRules.normalize(spec,raw));v.setChoice(null);v.setReviewRequired(false);
            v.setConfirmed(!JsonUtils.isBlankText(v.getValueText()));v.setConfirmedFrom("INPUT");
            v.setNoteText(patch.getCandidateIndex()!=null?"Candidate adopted for this draft.":"Manually adopted for this draft.");
        } else if(Boolean.TRUE.equals(patch.getReviewed())) {
            v.setReviewRequired(false);v.setAdoptedSourcesJson(JsonUtils.write(DraftAdoption.candidates(v)));
            if(patch.getConfirmed()!=null)v.setConfirmed(patch.getConfirmed()&&!JsonUtils.isBlankText(v.getValueText()));
        } else if(patch.getConfirmed()!=null) {
            v.setConfirmed(patch.getConfirmed()&&!JsonUtils.isBlankText(v.getValueText()));
            if(Boolean.TRUE.equals(v.getConfirmed())){v.setReviewRequired(false);v.setAdoptedSourcesJson(JsonUtils.write(DraftAdoption.candidates(v)));}
        }
        if(patch.getNote()!=null)v.setNoteText(patch.getNote());
        v.setUpdatedAt(Instant.now());variableRepository.save(v);return toVariableVO(v);
    }

    @SuppressWarnings("unchecked")
    private void validateTargetOverrides(String projectId,String raw) {
        if(JsonUtils.isBlankText(raw))return;
        Object decoded=DraftAdoption.decode(raw);
        if(!(decoded instanceof List))throw new BizException(4007,"Target decisions must be a JSON list.");
        Map<String,Object> values=decodedValues(projectId);values.remove("targetOverrides");values.remove("targetEdits");
        Map<String,Object> base=DraftBusinessRules.plan(values);Map<String,Map<String,Object>> actions=new HashMap<>();
        for(Object action:(List<?>)base.get("actions")){Map<String,Object> item=(Map<String,Object>)action;actions.put(String.valueOf(item.get("id")),item);}
        Set<String> ids=new HashSet<>();
        for(Object entry:(List<?>)decoded) {
            if(!(entry instanceof Map))throw new BizException(4007,"Each target decision must be an object.");
            Map<String,Object> edit=(Map<String,Object>)entry;String id=String.valueOf(edit.get("actionId"));Map<String,Object> target=actions.get(id);
            if(target==null||!ids.add(id))throw new BizException(4007,"Unknown or duplicate target: "+id);
            String problem=DraftTargetDecisions.problem(target,edit);
            if(problem!=null)throw new BizException(4007,problem);
        }
    }

    /** Unmodified records keep their adopted source. Re-adopting a newly built record binds only that target. */
    private String bindTargetSources(String projectId,String previous,String proposed) {
        if(JsonUtils.isBlankText(proposed))return proposed;
        Map<String,Map<String,Object>> old=new HashMap<>();
        for(Object item:DraftBusinessRules.list(DraftAdoption.decode(previous))) {
            Map<String,Object> entry=DraftBusinessRules.asMap(item);old.put(String.valueOf(entry.get("actionId")),entry);
        }
        Map<String,String> documents=targetDocuments();List<Object> result=new ArrayList<>();
        for(Object item:DraftBusinessRules.list(DraftAdoption.decode(proposed))) {
            Map<String,Object> entry=new LinkedHashMap<>(DraftBusinessRules.asMap(item));String id=String.valueOf(entry.get("actionId"));
            Map<String,Object> existing=old.get(id);Object binding=entry.get("sourceRevision");
            if(binding!=null&&existing!=null&&targetDecision(existing).equals(targetDecision(entry))) {
                if(!java.util.Objects.equals(binding,existing.get("sourceRevision")))throw new BizException(4007,"The adopted source binding is server-managed. Re-adopt this target to bind its current source.");
                entry.put("sourceRevision",existing.get("sourceRevision"));
            } else entry.put("sourceRevision",targetSourceRevision(projectId,documents.get(id)));
            result.add(entry);
        }
        return JsonUtils.write(result);
    }
    private Map<String,Object> targetDecision(Map<String,Object> entry) {
        Map<String,Object> decision=new LinkedHashMap<>(entry);decision.remove("sourceRevision");return decision;
    }
    private Map<String,String> targetDocuments() {
        Map<String,String> result=new HashMap<>();
        for(Object item:DraftBusinessRules.list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions"))) {
            Map<String,Object> action=DraftBusinessRules.asMap(item);result.put(String.valueOf(action.get("id")),String.valueOf(action.get("document")));
        }return result;
    }
    private Map<String,Object> targetSourceRevision(String projectId,String document) {
        SourceDocument source=sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,document,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElse(null);
        Map<String,Object> revision=new LinkedHashMap<>();revision.put("document",document);revision.put("sourceDocumentId",source==null?null:source.getId());revision.put("sourceHash",source==null?null:DraftAdoption.sourceHash(source));return revision;
    }
    private void addTargetSourceReviews(String projectId,Object adopted,Map<String,Object> plan) {
        Map<String,Map<String,Object>> actions=new HashMap<>();for(Object item:DraftBusinessRules.list(plan.get("actions"))) {
            Map<String,Object> action=DraftBusinessRules.asMap(item);actions.put(String.valueOf(action.get("id")),action);
        }
        for(Object item:DraftBusinessRules.list(adopted)) {
            Map<String,Object> entry=DraftBusinessRules.asMap(item);String id=String.valueOf(entry.get("actionId"));Map<String,Object> target=actions.get(id);if(target==null)continue;
            String document=String.valueOf(target.get("document"));Object stored=entry.get("sourceRevision");
            Map<String,Object> current=targetSourceRevision(projectId,document);
            if(stored instanceof Map&&JsonUtils.write(stored).equals(JsonUtils.write(current)))continue;
            // Numeric IDs may deserialize as Integer; compare their canonical text independently of map ordering.
            Map<String,Object> revision=DraftBusinessRules.asMap(stored);
            if(stored instanceof Map&&java.util.Objects.equals(revision.get("document"),current.get("document"))
                    &&java.util.Objects.equals(String.valueOf(revision.get("sourceDocumentId")),String.valueOf(current.get("sourceDocumentId")))
                    &&java.util.Objects.equals(revision.get("sourceHash"),current.get("sourceHash")))continue;
            Map<String,Object> warning=new LinkedHashMap<>();warning.put("id","target-review-"+id);warning.put("kind","target_review");warning.put("actionId",id);warning.put("document",document);warning.put("clause",target.get("clause"));warning.put("inputKeys",Collections.singletonList("targetOverrides"));
            warning.put("message",LocalizedText.of("该目标采用后模板来源已变化，请核对并重新采用本条；其他目标的复核状态单独保留。","該目標採用後模板來源已變化，請核對並重新採用本條；其他目標的覆核狀態獨立保留。","The source for this adopted target changed or was never bound. Review and re-adopt this target against the current template; other target reviews remain independent."));
            unresolved(plan).add(warning);target.put("sourceReviewRequired",true);
        }
    }

    public VariableVO createVariable(String projectId,VariableCreate body) {
        projectService.require(projectId);throw new BizException(4007,"Edit the necessary inputs in the drafting catalogue.");
    }

    @Transactional
    public List<VariableVO> confirmAll(String projectId,String scope,String fileKey) {
        if(reviewedRules(projectId))return templateGraphService.confirmAll(projectId);
        ensureInputs(projectId);List<DraftVariable> inputs=inputVariables(projectId);
        for(DraftVariable v:inputs)if(DraftInputRules.valid(v)) {
            v.setConfirmed(true);v.setConfirmedFrom("INPUT");v.setReviewRequired(false);v.setAdoptedSourcesJson(JsonUtils.write(DraftAdoption.candidates(v)));v.setUpdatedAt(Instant.now());
        }
        variableRepository.saveAll(inputs);return inputs.stream().map(this::toVariableVO).collect(Collectors.toList());
    }

    /** Old drafts retain their frozen snapshot and remain viewable; stale is derived at read time. */
    private void invalidateDocuments(String projectId) { }

    // ------------------------------------------------------------ 文稿生成

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public List<DraftDocumentVO> generate(String projectId,String lang) {
        if(templateGraphService!=null)templateGraphService.lockForGeneration(projectId);
        ensureInputs(projectId);
        Map<String,Object> prepared=preparePlan(projectId,null);
        String snapshot=(String)prepared.get("snapshotId");
        Map<String,DraftArtifact> revisions=new LinkedHashMap<>();Map<String,String> contents=new LinkedHashMap<>();Map<String,DraftTocFinalizer.Result> finalized=new LinkedHashMap<>();
        Map<String,DocxTemplateEditor.DocxEditResult> compiled=new LinkedHashMap<>();Map<String,List<Map<String,Object>>> compiledBindings=new LinkedHashMap<>();
        // Compile/validate every package before any current document pointer changes.
        for(String key:DraftBlueprint.draftFileKeys()) {
            SourceDocument template=requireTemplate(projectId,key);
            if(template.getStoragePath()==null)throw new BizException(4012,"EDITABLE_DOCX_REQUIRED: Re-upload the original editable "+key+" DOCX.");
            byte[] original=storageService.read(template.getStoragePath());
            if(reviewedRules(projectId)&&isPdfSource(original))throw new BizException(4012,"EDITABLE_DOCX_REQUIRED: The selected "+key+" edition is PDF; it cannot borrow another edition's Word original.");
            DocumentParser.ParsedDocument fresh=documentParser.parseTemplate(key,template.getFileName(),original);
            if(!DraftTemplateSourceText.matches(template.getTextContent(),fresh,documentParser,original))throw new BizException(4012,"SOURCE_TEXT_DIVERGENCE: "+key+" saved template text differs from the original DOCX; verify and re-upload the original editable DOCX.");
            DocxTemplateEditor.TemplateIndex sourceIndex=new DocxTemplateEditor().inspect(original);
            List<Map<String,Object>> sourceBindings=DraftDocumentBindings.source(key,sourceIndex);
            DraftDocumentBindings.associateTargets(key,sourceIndex,prepared,sourceBindings);
            DocxTemplateEditor.NativeLayoutPlan nativeLayout=DraftNativeLayout.plan(sourceIndex,key,properties.getDrafting().getRendererKind());
            try {nativeLayout=DraftNativeIdentity.attach(sourceIndex,fresh.getCoverage().getNativeDocxSections(),key,prepared,properties.getDrafting().getRendererKind(),nativeLayout);}
            catch(IllegalArgumentException|IllegalStateException invalid){throw new BizException(4012,"NATIVE_STORY_PLAN_REVIEW_REQUIRED: "+invalid.getMessage());}
            DocxTemplateEditor.DocxEditResult generated;
            try {generated=reviewedRules(projectId)?templateGraphService.compile(original,key,prepared,DraftDocumentBindings.bookmarkNames(sourceBindings),nativeLayout):DraftFormattedRules.compile(original,key,prepared,DraftDocumentBindings.bookmarkNames(sourceBindings),nativeLayout);}
            catch(DocxTemplateEditor.EditException unsafe){throw new BizException(4012,unsafe.getCode()+": "+unsafe.getOperationId());}
            List<Map<String,Object>> bindings=DraftDocumentBindings.generated(sourceBindings,generated);
            compiled.put(key,generated);compiledBindings.put(key,bindings);
        }
        Map<String,Object> deliveryGraph=reviewedRules(projectId)?templateGraphService.graph(projectId):null;
        for(String key:DraftBlueprint.draftFileKeys()) {
            List<String> outstanding=deliveryGraph==null?Collections.emptyList():DraftIncompleteNotice.outstanding(deliveryGraph,prepared,key);
            DocxTemplateEditor.DocxEditResult generated=compiled.get(key);List<Map<String,Object>> bindings=compiledBindings.get(key);
            List<Object> ledger=DraftNativeLayout.ledger(generated,DraftNativeLayout.info(key,properties.getDrafting().getRendererKind(),generated));
            DraftIncompleteNotice.Delivery notice=DraftIncompleteNotice.applyWithReceipt(generated.getDocxBytes(),outstanding);
            byte[] delivery=notice.bytes;
            if(!outstanding.isEmpty()) {
                DocxTemplateEditor.DocxEditResult marked=new DocxTemplateEditor().apply(delivery,new DocxTemplateEditor.SourceEditBatch(DraftPdfConverter.sha256(delivery),Collections.emptyList()));
                bindings=DraftDocumentBindings.reconcile(bindings,marked.getIndex(),false);ledger=DraftNativeLayout.bodyLedger(JsonUtils.write(ledger),marked);
                DraftIncompleteNotice.bindDelivery(bindings,marked.getIndex(),notice);
            }
            DraftTocFinalizer.Result complete=finalizeDraft(key,generated.getOriginalSourceSha256(),delivery,bindings,ledger);DocxTemplateEditor.TemplateIndex finalIndex=complete.index();finalized.put(key,complete);
            DraftArtifact revision=new DraftArtifact();revision.setId(java.util.UUID.randomUUID().toString());revision.setProjectId(projectId);revision.setFileKey(key);
            revision.setSnapshotId(snapshot);revision.setSourceSha256(generated.getOriginalSourceSha256());revision.setDocxSha256(finalIndex.getSourceSha256());revision.setDocxBytes(complete.docx);
            if(!generated.getDocxSha256().equals(finalIndex.getSourceSha256()))bindings=DraftDocumentBindings.reconcile(bindings,finalIndex,false);
            if("SCC".equals(key))ledger=complete.ledger(ledger);
            revision.setBindingsJson(JsonUtils.write(bindings));revision.setLedgerJson(JsonUtils.write(ledger));revision.setBlocksJson(JsonUtils.write(DraftDocumentBindings.blocks(finalIndex,bindings)));revision.setFieldImpactsJson(JsonUtils.write(generated.getAffectedFeatures()));
            revisions.put(key,revision);contents.put(key,DraftFormattedRules.content(finalIndex));
        }
        String planJson=JsonUtils.write(prepared);
        List<DraftDocumentVO> result=new ArrayList<>();
        for(String fileKey:DraftBlueprint.draftFileKeys()) {
            DraftArtifact revision=artifactRepository.save(revisions.get(fileKey));
            saveFinalizedPdf(finalized.get(fileKey));
            DraftDocument doc=documentRepository.findByProjectIdAndFileKey(projectId,fileKey).orElseGet(DraftDocument::new);
            doc.setProjectId(projectId);doc.setFileKey(fileKey);doc.setTitle(DraftBlueprint.fileTitle(fileKey));doc.setContent(contents.get(fileKey));doc.setRevisionId(revision.getId());
            doc.setGenerated(true);doc.setContentEdited(false);doc.setSnapshotId(snapshot);doc.setRuleVersion(String.valueOf(prepared.get("ruleVersion")));
            doc.setInputSnapshotJson(planJson);doc.setUnresolvedJson(JsonUtils.write(unresolved(prepared)));doc.setUpdatedAt(Instant.now());
            if(doc.getCreatedAt()==null)doc.setCreatedAt(Instant.now());documentRepository.save(doc);result.add(documentVO(doc,snapshot));
        }
        return result;
    }

    public List<DraftDocumentVO> listDocuments(String projectId) {
        projectService.require(projectId);String current=snapshotId(projectId,null);
        Map<String,DraftDocument> byKey=new HashMap<>();documentRepository.findByProjectIdOrderByIdAsc(projectId).forEach(d->byKey.put(d.getFileKey(),d));
        ListingPdfMetadata pdfMetadata=new ListingPdfMetadata();
        List<DraftDocumentVO> result=new ArrayList<>();for(String key:DraftBlueprint.draftFileKeys())result.add(byKey.containsKey(key)?documentVO(byKey.get(key),current,pdfMetadata::find):new DraftDocumentVO(key,DraftBlueprint.fileTitle(key),"",false));return result;
    }
    private DraftDocumentVO documentVO(DraftDocument document,String current) {
        return documentVO(document,current,this::currentPdfMetadata);
    }
    private DraftDocumentVO documentVO(DraftDocument document,String current,java.util.function.Function<String,DraftPdfArtifactRepository.Metadata> pdfMetadata) {
        DraftDocumentVO vo=new DraftDocumentVO(document.getFileKey(),document.getTitle(),nvl(document.getContent()),Boolean.TRUE.equals(document.getGenerated()));
        vo.setSnapshotId(document.getSnapshotId());vo.setRuleVersion(document.getRuleVersion());
        vo.setContentEdited(Boolean.TRUE.equals(document.getContentEdited()));
        vo.setStale(document.getSnapshotId()==null||!document.getSnapshotId().equals(current)||document.getRevisionId()==null);
        if(document.getRevisionId()!=null) {
            DraftArtifact artifact=artifactRepository.findById(document.getRevisionId()).orElseThrow(()->new BizException(4012,"GENERATED_ARTIFACT_MISSING: Regenerate this draft."));
            vo.setRevisionId(artifact.getId());vo.setDocxSha256(artifact.getDocxSha256());vo.setSourceSha256(artifact.getSourceSha256());
            vo.setBlocks(currentBlocks(artifact));vo.setFieldImpacts((List)JsonUtils.readList(artifact.getFieldImpactsJson(),Map.class));vo.setFieldStatus("UNVERIFIED");
            vo.setNativeLayout(DraftNativeLayout.savedInfo(artifact.getLedgerJson(),document.getFileKey(),properties.getDrafting().getRendererKind(),artifact.getSourceSha256(),artifact.getDocxSha256()));
            DraftPdfArtifactRepository.Metadata pdf=pdfMetadata.apply(artifact.getDocxSha256());if(pdf!=null){vo.setPdfSha256(pdf.getPdfSha256());vo.setRenderProfileHash(pdf.getRenderProfileHash());}
        }
        try{vo.setUnresolved((List)JsonUtils.readList(document.getUnresolvedJson(),Map.class));}catch(RuntimeException invalidLegacy){vo.setUnresolved(Collections.emptyList());}return vo;
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> currentBlocks(DraftArtifact artifact) {
        List<Map<String,Object>> blocks=(List)JsonUtils.readList(artifact.getBlocksJson(),Map.class);
        // Old immutable artifacts may have advertised native tabs/breaks as editable text.
        // Reclassify that response from its frozen DOCX; never rewrite the artifact or its identity.
        for(Map<String,Object> block:blocks)if(Boolean.TRUE.equals(block.get("editable"))&&String.valueOf(block.get("text")).matches("[\\t\\r\\n]+"))return DraftFormattedRules.blocks(new DocxTemplateEditor().inspect(artifact.getDocxBytes()));
        return blocks;
    }

    /**
     * QS 在审阅界面直接修改生成稿正文（点击变量定位到文稿位置后编辑保存）。
     * 只改文稿本身，不动变量；改完仍视为已生成。
     */
    @Transactional
    public DraftDocumentVO updateDocument(String projectId, String fileKey, DocumentPatch patch) {
        projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        entityManager.refresh(document,LockModeType.PESSIMISTIC_WRITE);
        requireCurrent(document);
        if(patch.getContent()!=null)throw new BizException(4012,"FLAT_BODY_EDIT_UNSUPPORTED: Edit source-bound paragraph blocks instead.");
        DraftArtifact previous=requireArtifact(document);
        if(!previous.getId().equals(patch.getRevisionId())||!previous.getDocxSha256().equals(patch.getDocxSha256()))throw new BizException(4090,"ARTIFACT_REVISION_CONFLICT: Reload the current draft before saving.");
        if(patch.getBlocks()==null||patch.getBlocks().isEmpty())throw new BizException(4012,"SOURCE_BLOCK_PATCH_REQUIRED");
        DocxTemplateEditor editor=new DocxTemplateEditor();DocxTemplateEditor.TemplateIndex index=editor.inspect(previous.getDocxBytes());
        Map<String,DocxTemplateEditor.Paragraph> paragraphs=new HashMap<>();for(DocxTemplateEditor.Paragraph p:index.getMainParagraphs())paragraphs.put(p.getId(),p);
        Map<String,List<DocxTemplateEditor.Paragraph>> markers=DraftDocumentBindings.markers(index);Set<String> registeredBindings=new HashSet<>();for(Map<String,Object> binding:DraftDocumentBindings.read(previous.getBindingsJson()))registeredBindings.add(String.valueOf(binding.get("bindingId")));
        List<DocxTemplateEditor.Edit> edits=new ArrayList<>();Set<String> selected=new HashSet<>();
        try {
            for(BlockPatch block:patch.getBlocks()) {
                DocxTemplateEditor.Paragraph p=paragraphs.get(block.getId());
                if(block.getBindingId()!=null) {
                    List<DocxTemplateEditor.Paragraph> targets=markers.getOrDefault(block.getBindingId(),Collections.emptyList());
                    if(!registeredBindings.contains(block.getBindingId())||targets.size()!=1)throw new BizException(4090,"BINDING_LOCATION_CONFLICT: Reload the exact saved target.");p=targets.get(0);
                }
                if(p==null||!selected.add(p.getId()))throw new BizException(4012,"SOURCE_BLOCK_INVALID: "+block.getId());
                if(!DraftAdoption.hash(p.getText()).equals(block.getExpectedTextHash()))throw new BizException(4090,"SOURCE_BLOCK_TEXT_CONFLICT: "+block.getId());
                String unsupported=DraftFormattedRules.blockUnsupportedReason(index,p);
                if(unsupported!=null)throw new BizException(4012,unsupported+": "+block.getId());
                if(block.getText()==null)throw new BizException(4012,"NULL_TEXT_EDIT_UNSUPPORTED: "+block.getId());
                if(block.getText().replaceAll("(?U)\\s+","").isEmpty()&&DraftFormattedRules.hasNativeMath(index,p))throw new BizException(4012,"NATIVE_MATH_CLEAR_UNSUPPORTED: "+block.getId());
                if(block.getText().isEmpty())edits.add(DocxTemplateEditor.Edit.clearParagraph("body-"+p.getOrdinal(),p));
                else edits.addAll(DraftSourceEditCompiler.editableSpans("body-"+p.getOrdinal(),p,block.getText()));
                if(block.getInsertAfter()!=null&&!block.getInsertAfter().isEmpty())edits.add(DocxTemplateEditor.Edit.insertParagraphs("body-insert-"+p.getOrdinal(),p,true,block.getInsertAfter(),p));
            }
            if(edits.isEmpty())return documentVO(document,snapshotId(projectId,null));
            DocxTemplateEditor.DocxEditResult result=editor.applyWithParagraphBookmarks(previous.getDocxBytes(),new DocxTemplateEditor.SourceEditBatch(previous.getDocxSha256(),edits),Collections.emptyMap(),true);
            List<Map<String,Object>> bindings=DraftDocumentBindings.bodyEdited(DraftDocumentBindings.read(previous.getBindingsJson()),result);
            List<Object> ledger=DraftNativeLayout.bodyLedger(previous.getLedgerJson(),result);
            DraftTocFinalizer.Result complete=finalizeDraft(fileKey,previous.getSourceSha256(),result.getDocxBytes(),bindings,ledger);DocxTemplateEditor.TemplateIndex finalIndex=complete.index();
            DraftArtifact next=new DraftArtifact();next.setId(java.util.UUID.randomUUID().toString());next.setProjectId(projectId);next.setFileKey(fileKey);next.setSnapshotId(previous.getSnapshotId());next.setSourceSha256(previous.getSourceSha256());next.setParentRevisionId(previous.getId());
            if(!result.getDocxSha256().equals(finalIndex.getSourceSha256()))bindings=DraftDocumentBindings.reconcile(bindings,finalIndex,false);
            if("SCC".equals(fileKey))ledger=complete.ledger(ledger);
            next.setBindingsJson(JsonUtils.write(bindings));next.setDocxBytes(complete.docx);next.setDocxSha256(finalIndex.getSourceSha256());next.setLedgerJson(JsonUtils.write(ledger));next.setBlocksJson(JsonUtils.write(DraftDocumentBindings.blocks(finalIndex,bindings)));next.setFieldImpactsJson(JsonUtils.write(result.getAffectedFeatures()));
            artifactRepository.save(next);saveFinalizedPdf(complete);document.setRevisionId(next.getId());document.setContent(DraftFormattedRules.content(finalIndex));
        }catch(DocxTemplateEditor.EditException unsafe){throw new BizException(4012,unsafe.getCode()+": "+unsafe.getOperationId());}
        document.setContentEdited(true);
        document.setUpdatedAt(Instant.now());
        documentRepository.save(document);
        return documentVO(document,snapshotId(projectId,null));
    }

    public byte[] download(String projectId, String fileKey) {
        Project project = projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        requireCurrent(document);
        // 带 BOM，方便 Windows 下用记事本 / Word 直接打开不乱码
        return ("\uFEFF" + nvl(document.getContent())).getBytes(StandardCharsets.UTF_8);
    }

    public String downloadFileName(String projectId, String fileKey) {
        Project project = projectService.require(projectId);
        return "ConSense_" + fileKey + "_" + nvl(project.getContractNo()) + ".md";
    }

    /**
     * 第 3 步审阅模式 / 最终预览：把生成稿渲染成真正的 PDF，前端用浏览器原生 PDF 视图展示。
     */
    @Transactional
    public byte[] previewPdf(String projectId, String fileKey) {
        return previewPdf(projectId,fileKey,null);
    }

    /** Target metadata is frozen to the saved revision, independent of evidence source anchors. */
    @Transactional(readOnly=true)
    public DocumentBindingsVO documentBindings(String projectId,String fileKey,String expectedRevision,String expectedHash) {
        projectService.require(projectId);
        DraftDocument document=documentRepository.findByProjectIdAndFileKey(projectId,fileKey).orElseThrow(()->new BizException(4008,"No generated draft is available."));
        requireCurrent(document);requireRevision(document,expectedRevision);DraftArtifact artifact=requireArtifact(document);
        if(expectedHash!=null&&!expectedHash.equals(artifact.getDocxSha256()))throw new BizException(4090,"ARTIFACT_REVISION_CONFLICT: Reload the current draft.");
        DocumentBindingsVO result=DraftDocumentBindings.locate(DraftDocumentBindings.bundle(fileKey,"result",artifact.getSourceSha256(),artifact.getId(),artifact.getDocxSha256(),DraftDocumentBindings.read(artifact.getBindingsJson())),currentPdfArtifact(artifact.getDocxSha256()));result.setNativeLayout(DraftNativeLayout.savedInfo(artifact.getLedgerJson(),fileKey,properties.getDrafting().getRendererKind(),artifact.getSourceSha256(),artifact.getDocxSha256()));return result;
    }

    @Transactional(readOnly=true)
    public DocumentBindingsVO templateBindings(String projectId,String fileKey,String expectedSourceHash) {
        TemplateSourceFile source=templateSource(projectId,fileKey);byte[] original=source.getBytes();String sourceHash=DraftPdfConverter.sha256(original);
        if(expectedSourceHash!=null&&!expectedSourceHash.equals(sourceHash))throw new BizException(4090,"SOURCE_REVISION_CONFLICT: Reload the current template.");
        if(!"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(source.getContentType()))throw new BizException(4012,"EDITABLE_DOCX_REQUIRED: Native template bindings require DOCX.");
        DocxTemplateEditor.TemplateIndex sourceIndex=new DocxTemplateEditor().inspect(original);
        List<Map<String,Object>> rows=DraftDocumentBindings.source(fileKey,sourceIndex);
        DraftDocumentBindings.associateTargets(fileKey,sourceIndex,reviewedRules(projectId)?templateGraphService.plan(projectId,null):DraftBusinessRules.plan(Collections.emptyMap()),rows);
        DocxTemplateEditor.DocxEditResult workingResult=DraftDocumentBindings.sourceWorkingCopyResult(original,rows,DraftNativeLayout.plan(original,fileKey,properties.getDrafting().getRendererKind()));byte[] working=workingResult.getDocxBytes();
        String workingHash=DraftPdfConverter.sha256(working);
        DocumentBindingsVO result=DraftDocumentBindings.locate(DraftDocumentBindings.bundle(fileKey,"source",sourceHash,null,workingHash,DraftDocumentBindings.reconcile(rows,new DocxTemplateEditor().inspect(working),false)),currentPdfArtifact(workingHash));result.setNativeLayout(DraftNativeLayout.info(fileKey,properties.getDrafting().getRendererKind(),workingResult));return result;
    }
    private DraftPdfArtifactRepository.Metadata currentPdfMetadata(String docxHash) {
        String profileHash=currentPdfProfileHash(docxHash);if(profileHash==null)return null;
        return currentPdfMetadata(docxHash,profileHash);
    }
    private DraftPdfArtifactRepository.Metadata currentPdfMetadata(String docxHash,String profileHash) {
        DraftPdfArtifactRepository.Metadata metadata=pdfArtifactRepository.findMetadataById(DraftAdoption.hash(docxHash+"|"+profileHash)).orElse(null);
        if(metadata!=null&&(!docxHash.equals(metadata.getDocxSha256())||!profileHash.equals(metadata.getRenderProfileHash())))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");
        return metadata;
    }
    /** A single listing assesses the live renderer once; each document retains its exact PDF identity check. */
    private final class ListingPdfMetadata {
        private boolean assessed;
        private String profileHash;
        private DraftPdfArtifactRepository.Metadata find(String docxHash) {
            if(!pdfArtifactRepository.existsByDocxSha256(docxHash))return null;
            if(!assessed){assessed=true;profileHash=currentConfiguredPdfProfileHash();}
            return profileHash==null?null:currentPdfMetadata(docxHash,profileHash);
        }
    }
    private DraftPdfArtifact currentPdfArtifact(String docxHash) {
        String profileHash=currentPdfProfileHash(docxHash);if(profileHash==null)return null;
        DraftPdfArtifact artifact=pdfArtifactRepository.findById(DraftAdoption.hash(docxHash+"|"+profileHash)).orElse(null);
        if(artifact!=null&&(!docxHash.equals(artifact.getDocxSha256())||!profileHash.equals(artifact.getRenderProfileHash())))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");
        return artifact;
    }
    String currentPdfProfileHash(String docxHash) {
        if(!pdfArtifactRepository.existsByDocxSha256(docxHash))return null;
        return currentConfiguredPdfProfileHash();
    }
    String currentConfiguredPdfProfileHash() {
        ConsenseProperties.Drafting settings=properties.getDrafting();if(JsonUtils.isBlankText(settings.getRendererExecutable())||JsonUtils.isBlankText(settings.getRendererWorkRoot()))return null;
        try {
            return configuredPdfConverter(settings).currentProfileHash();
        }catch(DraftPdfConversionException|IllegalArgumentException unavailable){return null;} // Current renderer unavailable: old coordinates remain pending.
    }
    @Transactional
    public byte[] previewPdf(String projectId,String fileKey,String expectedRevision) {
        projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey)
                .orElseThrow(() -> new BizException(4008, "尚未生成 " + fileKey + " 文稿"));
        entityManager.refresh(document,LockModeType.PESSIMISTIC_WRITE);
        requireCurrent(document);
        requireRevision(document,expectedRevision);
        DraftArtifact artifact=requireArtifact(document);
        return renderPdf(artifact.getDocxBytes());
    }

    private DraftTocFinalizer.Result finalizeDraft(String key,String originalSourceSha256,byte[] compiled,List<Map<String,Object>> bindings,List<Object> ledger) {
        ConsenseProperties.Drafting settings=properties.getDrafting();
        if(!"SCC".equals(key)||!"libreoffice".equals(settings.getRendererKind())||JsonUtils.isBlankText(settings.getRendererExecutable())||JsonUtils.isBlankText(settings.getRendererWorkRoot()))return DraftTocFinalizer.unchanged(compiled,"TOC_FINALIZER_NOT_CONFIGURED_FOR_THIS_DOCUMENT");
        DraftTocFinalizer.Result result;
        try {result=finalizeCompiledToc(compiled,originalSourceSha256,bindings,ledger);}
        catch(RuntimeException unavailable){return DraftTocFinalizer.unchanged(compiled,unavailable.getMessage());}
        if(result==null||!java.util.Arrays.equals(compiled,result.compiled)||!DraftPdfConverter.sha256(compiled).equals(result.proof.get("compiledDocxSha256")))return DraftTocFinalizer.unchanged(compiled,"TOC_COMPILED_INPUT_IDENTITY_MISMATCH");
        // A failed candidate can fall back to the first PDF, whose cache has a different exact-DOCX key.
        for(int lookup=0;lookup<2&&result.pdf!=null;lookup++) {
            String id=DraftAdoption.hash(result.pdf.getDocxSha256()+"|"+result.pdf.getRenderProfileHash());DraftPdfArtifact existing=pdfArtifactRepository.findById(id).orElse(null);
            if(existing==null)return result;
            if(!result.pdf.getDocxSha256().equals(existing.getDocxSha256())||!result.pdf.getRenderProfileHash().equals(existing.getRenderProfileHash()))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");
            if(!existing.getPdfSha256().equals(DraftPdfConverter.sha256(existing.getPdfBytes())))throw new BizException(4013,"PDF_ARTIFACT_HASH_MISMATCH");
            DraftTocFinalizer.Result selected=result.useExisting(existing);
            if(selected.pdf!=null&&existing.getPdfSha256().equals(selected.pdf.getPdfSha256()))return selected;result=selected;
        }
        return result;
    }
    DraftTocFinalizer.Result finalizeCompiledToc(byte[] compiled,String originalSourceSha256,List<Map<String,Object>> bindings,List<Object> ledger) {return configuredPdfConverter(properties.getDrafting()).finalizeToc(compiled,originalSourceSha256,bindings,ledger);}
    private void saveFinalizedPdf(DraftTocFinalizer.Result result) {
        if(result==null||result.pdf==null)return;DraftPdfConversionResult pdf=result.pdf;
        if(!DraftPdfConverter.sha256(result.docx).equals(pdf.getDocxSha256())||!DraftPdfConverter.sha256(pdf.getPdfBytes()).equals(pdf.getPdfSha256()))throw new BizException(4013,"FINALIZED_DOCX_PDF_IDENTITY_MISMATCH");
        String id=DraftAdoption.hash(pdf.getDocxSha256()+"|"+pdf.getRenderProfileHash());DraftPdfArtifact existing=pdfArtifactRepository.findById(id).orElse(null);
        if(existing!=null){if(!pdf.getDocxSha256().equals(existing.getDocxSha256())||!pdf.getRenderProfileHash().equals(existing.getRenderProfileHash()))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");if(!existing.getPdfSha256().equals(DraftPdfConverter.sha256(existing.getPdfBytes())))throw new BizException(4013,"PDF_ARTIFACT_HASH_MISMATCH");if(!existing.getPdfSha256().equals(pdf.getPdfSha256()))throw new BizException(4013,"IMMUTABLE_FINALIZED_PDF_CONFLICT");return;}
        DraftPdfArtifact saved=new DraftPdfArtifact();saved.setId(id);saved.setDocxSha256(pdf.getDocxSha256());saved.setPdfSha256(pdf.getPdfSha256());saved.setRenderProfileHash(pdf.getRenderProfileHash());saved.setPdfBytes(pdf.getPdfBytes());saved.setManifestJson(JsonUtils.write(pdf.getManifest()));pdfArtifactRepository.save(saved);
    }
    private DraftPdfConverter configuredPdfConverter(ConsenseProperties.Drafting settings) {
        java.nio.file.Path executable=java.nio.file.Paths.get(settings.getRendererExecutable()),work=java.nio.file.Paths.get(settings.getRendererWorkRoot());
        String kind=settings.getRendererKind();
        if("desktop-x2t".equals(kind)) {
            if(JsonUtils.isBlankText(settings.getRendererAssetRoot())||JsonUtils.isBlankText(settings.getRendererFontCache()))throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Desktop x2t needs explicit renderer-asset-root and renderer-font-cache.");
            return new DraftPdfConverter(DraftPdfRenderProfile.desktopX2t(executable,work,settings.getRendererTimeoutMs(),java.nio.file.Paths.get(settings.getRendererAssetRoot()),java.nio.file.Paths.get(settings.getRendererFontCache())));
        }
        if(!"libreoffice".equals(kind))throw new DraftPdfConversionException(DraftPdfConversionException.Code.NOT_CONFIGURED,"Unknown explicitly configured document renderer kind.");
        return new DraftPdfConverter(DraftPdfRenderProfile.libreOffice(executable,work,settings.getRendererTimeoutMs()));
    }

    /** Shared exact-DOCX conversion/cache for generated drafts, templates and frozen Vetting reports. */
    public byte[] renderPdf(byte[] docx) {
        String docxHash=DraftPdfConverter.sha256(docx);ConsenseProperties.Drafting settings=properties.getDrafting();
        if(JsonUtils.isBlankText(settings.getRendererExecutable())||JsonUtils.isBlankText(settings.getRendererWorkRoot()))throw new BizException(4013,"NOT_CONFIGURED: Set consense.drafting.renderer-executable and renderer-work-root for formatted PDF conversion.");
        try {
            DraftPdfConverter converter=configuredPdfConverter(settings);
            String profileHash=converter.currentProfileHash(),cacheId=DraftAdoption.hash(docxHash+"|"+profileHash);
            DraftPdfArtifact cached=pdfArtifactRepository.findById(cacheId).orElse(null);
            if(cached==null) {
                DraftPdfConversionResult converted=converter.convert(docx);
                if(!docxHash.equals(converted.getDocxSha256())||!profileHash.equals(converted.getRenderProfileHash()))throw new BizException(4013,"RENDER_PROFILE_CHANGED: Retry using the current renderer profile.");
                cached=new DraftPdfArtifact();cached.setId(cacheId);cached.setDocxSha256(converted.getDocxSha256());cached.setPdfSha256(converted.getPdfSha256());cached.setRenderProfileHash(converted.getRenderProfileHash());cached.setPdfBytes(converted.getPdfBytes());cached.setManifestJson(JsonUtils.write(converted.getManifest()));pdfArtifactRepository.save(cached);
            }
            if(!docxHash.equals(cached.getDocxSha256())||!profileHash.equals(cached.getRenderProfileHash()))throw new BizException(4013,"BINDING_PDF_IDENTITY_MISMATCH");
            byte[] bytes=cached.getPdfBytes();if(!cached.getPdfSha256().equals(DraftPdfConverter.sha256(bytes)))throw new BizException(4013,"PDF_ARTIFACT_HASH_MISMATCH");return bytes;
        }catch(DraftPdfConversionException failure){throw new BizException(4013,failure.getCode()+": "+failure.getMessage());}
        catch(IllegalArgumentException invalid){throw new BizException(4013,"NOT_CONFIGURED: Invalid formatted renderer settings.");}
    }

    /**
     * 「读取资料」步骤上传的 NTT / SCT / SCC 原始模板的 PDF 表示（步骤 1 的源文件）。
     * 用于审阅模式下与文稿 PDF 并列展示，让用户在确认变量时能直接对照原始模板。
     */
    @Transactional
    public byte[] previewTemplate(String projectId, String fileKey) {
        return previewTemplate(projectId,fileKey,null);
    }
    @Transactional
    public byte[] previewTemplate(String projectId,String fileKey,String expectedSourceHash) {
        projectService.require(projectId);
        SourceDocument template = reviewedRules(projectId)?templateGraphService.selectedTemplate(projectId,fileKey):sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        byte[] source=storageService.read(template.getStoragePath());
        if(expectedSourceHash!=null&&!expectedSourceHash.equals(DraftPdfConverter.sha256(source)))throw new BizException(4090,"SOURCE_REVISION_CONFLICT: Reload the current template.");
        if(source.length>=5&&source[0]=='%'&&source[1]=='P'&&source[2]=='D'&&source[3]=='F'&&source[4]=='-') {
            try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(source)) {
                if(pdf.isEncrypted()||pdf.getNumberOfPages()==0)throw new java.io.IOException("No accessible PDF pages.");
                return source;
            }catch(java.io.IOException|RuntimeException invalid){throw new BizException(4013,"SOURCE_PDF_INVALID: The uploaded template is not a readable non-empty PDF.");}
        }
        List<Map<String,Object>> rows=DraftDocumentBindings.source(fileKey,new DocxTemplateEditor().inspect(source));
        return renderPdf(DraftDocumentBindings.sourceWorkingCopy(source,rows,DraftNativeLayout.plan(source,fileKey,properties.getDrafting().getRendererKind())));
    }

    public String previewTemplateFileName(String projectId, String fileKey) {
        if(reviewedRules(projectId))return nvl(templateGraphService.selectedTemplate(projectId,fileKey).getFileName()).replaceFirst("\\.[^.]+$","")+".pdf";
        return sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .map(source->nvl(source.getFileName()).replaceFirst("\\.[^.]+$","")+".pdf")
                .orElse(fileKey + ".pdf");
    }

    /** Original uploaded evidence; reading it never reparses or updates persisted drafting state. */
    @Transactional(readOnly=true)
    public TemplateSourceFile templateSource(String projectId,String fileKey) {
        projectService.require(projectId);
        SourceDocument template=reviewedRules(projectId)?templateGraphService.selectedTemplate(projectId,fileKey):sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId,fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(()->new BizException(4011,"SOURCE_NOT_UPLOADED: Upload the "+fileKey+" standard template first."));
        if(JsonUtils.isBlankText(template.getStoragePath()))throw new BizException(4011,"SOURCE_UNAVAILABLE: Upload the "+fileKey+" standard template again.");
        byte[] bytes;
        try {bytes=storageService.read(template.getStoragePath());}
        catch(BizException unavailable) {throw new BizException(4011,"SOURCE_UNAVAILABLE: Upload the "+fileKey+" standard template again.");}
        String mime="application/octet-stream";
        if(isPdfSource(bytes))mime="application/pdf";
        else {
            try {new DocxTemplateEditor().inspect(bytes);mime="application/vnd.openxmlformats-officedocument.wordprocessingml.document";}
            catch(DocxTemplateEditor.EditException unsupported) { /* Preserve raw evidence with a conservative MIME. */ }
        }
        return new TemplateSourceFile(bytes,template.getFileName(),mime);
    }

    /** Source-ID and byte-bound assets also cover non-primary editions and project correspondence. */
    @Transactional(readOnly=true)
    public TemplateSourceFile reviewSource(String projectId,Long sourceId,String expectedSha256,boolean preview) {
        projectService.require(projectId);
        SourceDocumentRepository.OriginalAssetMetadata source=sourceDocumentRepository.findOriginalAssetMetadataById(sourceId).filter(item->projectId.equals(item.getProjectId())&&Arrays.asList(SourceDocument.CATEGORY_STANDARD_TEMPLATE,SourceDocument.CATEGORY_PROJECT_INPUT).contains(item.getCategory()))
                .orElseThrow(()->new BizException(4006,"REVIEW_SOURCE_NOT_FOUND"));
        if(expectedSha256==null||!expectedSha256.matches("[a-f0-9]{64}"))throw new BizException(4090,"REVIEW_SOURCE_IDENTITY_REQUIRED");
        if(source.getStoragePath()==null||!storageService.matchesStoredBytes(source.getStoragePath(),expectedSha256))throw new BizException(4090,"REVIEW_SOURCE_CHANGED: Reload the current source.");
        byte[] original=storageService.read(source.getStoragePath());
        if(!expectedSha256.equals(DraftPdfConverter.sha256(original)))throw new BizException(4090,"REVIEW_SOURCE_CHANGED");
        if(isPdfSource(original))return new TemplateSourceFile(original,source.getFileName(),"application/pdf");
        if(preview) {
            DraftDocumentBindings.SourcePreview prepared=reviewPreview(sourceId,original);
            return new TemplateSourceFile(renderPdf(prepared.docx),source.getFileName()+".pdf","application/pdf");
        }
        return new TemplateSourceFile(original,source.getFileName(),"application/octet-stream");
    }

    @Transactional
    public DocumentBindingsVO reviewSourceBindings(String projectId,Long sourceId,String expectedSha256) {
        TemplateSourceFile original=reviewSource(projectId,sourceId,expectedSha256,false);
        if(isPdfSource(original.getBytes()))throw new BizException(4012,"EDITABLE_DOCX_REQUIRED: Original PDF locations use native page evidence.");
        DraftDocumentBindings.SourcePreview prepared=reviewPreview(sourceId,original.getBytes());
        renderPdf(prepared.docx);
        return DraftDocumentBindings.locate(prepared.bindings,currentPdfArtifact(prepared.bindings.getDocxSha256()));
    }
    private DraftDocumentBindings.SourcePreview reviewPreview(Long sourceId,byte[] original) {
        try{return DraftDocumentBindings.originalPreview(String.valueOf(sourceId),original,properties.getDrafting().getRendererKind());}
        catch(DocxTemplateEditor.EditException unsupported){throw new BizException(4012,"REVIEW_VISUAL_PREVIEW_UNAVAILABLE: The source can still be reviewed as text.");}
    }

    @Data @AllArgsConstructor
    public static class TemplateSourceFile {
        private final byte[] bytes;
        private final String fileName;
        private final String contentType;
    }

    @Transactional(readOnly=true)
    public TemplateReadingVO templateReading(String projectId,String fileKey) {
        TemplateSourceFile source=templateSource(projectId,fileKey);
        if("application/pdf".equals(source.getContentType())) {
            try(org.apache.pdfbox.pdmodel.PDDocument pdf=org.apache.pdfbox.pdmodel.PDDocument.load(source.getBytes())) {
                if(pdf.isEncrypted()||pdf.getNumberOfPages()==0)throw new java.io.IOException("No accessible PDF pages.");
                return new TemplateReadingVO(fileKey,source.getFileName(),DraftPdfConverter.sha256(source.getBytes()),"pdf",false,Collections.emptyList());
            }catch(java.io.IOException|RuntimeException invalid) {
                throw new BizException(4013,"SOURCE_PDF_INVALID: Upload a readable non-empty PDF template.");
            }
        }
        try {
            DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(source.getBytes());
            List<TemplateParagraphVO> paragraphs=index.getMainParagraphs().stream()
                    .map(p->new TemplateParagraphVO(p.getId(),p.getOrdinal(),p.getText())).collect(Collectors.toList());
            return new TemplateReadingVO(fileKey,source.getFileName(),index.getSourceSha256(),"docx",
                    DraftTemplateReadingSources.catalogueSourceVerified(fileKey,index.getSourceSha256()),paragraphs);
        }catch(DocxTemplateEditor.EditException invalid) {
            String name=nvl(source.getFileName()).toLowerCase(Locale.ROOT);
            if(name.endsWith(".docx"))throw new BizException(4013,"SOURCE_DOCX_INVALID: Upload a readable DOCX template. "+invalid.getCode());
            if(name.endsWith(".pdf"))throw new BizException(4013,"SOURCE_PDF_INVALID: Upload a readable non-empty PDF template.");
            return new TemplateReadingVO(fileKey,source.getFileName(),DraftPdfConverter.sha256(source.getBytes()),"unsupported",false,Collections.emptyList());
        }
    }

    /** Stored analysis is read-only and remains bound to current original bytes and parser inputs. */
    @Transactional(readOnly=true)
    public JsonNode templateEvidence(String projectId,String fileKey) {
        projectService.require(projectId);
        if(!TEMPLATE_KEYS.contains(fileKey))throw new BizException(4009,"Unknown template key: "+fileKey);
        SourceDocument source=reviewedRules(projectId)?templateGraphService.selectedTemplate(projectId,fileKey):sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId,fileKey,SourceDocument.CATEGORY_STANDARD_TEMPLATE).orElseThrow(()->new BizException(4011,"SOURCE_NOT_UPLOADED: "+fileKey));
        JsonNode coverage=JsonUtils.parse(source.getParseCoverageJson()),evidence=coverage.path("templateEvidence");
        if(evidence.isMissingNode()||evidence.isNull())throw new BizException(4012,"TEMPLATE_EVIDENCE_MISSING: This saved template has no analysis; re-upload its original source explicitly.");
        if(source.getStoragePath()==null)throw new BizException(4012,"TEMPLATE_SOURCE_MISSING: The original asset is unavailable.");
        String actualHash=DraftTemplateRouting.sha(storageService.read(source.getStoragePath()));
        if(!actualHash.equals(source.getSourceSha256()))throw new BizException(4012,"TEMPLATE_SOURCE_HASH_MISMATCH: The stored original changed.");
        try{return OerTemplateEvidence.requireBound(fileKey,actualHash,JsonUtils.parse(source.getStructuredContentJson()),coverage,evidence);}
        catch(IllegalArgumentException invalid){throw new BizException(4012,"TEMPLATE_EVIDENCE_INVALID: "+invalid.getMessage());}
    }

    private static boolean isPdfSource(byte[] bytes) {
        return bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-';
    }

    /**
     * 第 3 步「标准模板在线编辑」：读取模板正文（解析后的文本 / Markdown），供前端 textarea 编辑。
     * 若 textContent 为空（例如历史数据 / 解析失败），惰性重解析一次并回填。
     */
    @Transactional
    public TemplateTextVO getTemplateText(String projectId, String fileKey) {
        projectService.require(projectId);
        if(reviewedRules(projectId))return new TemplateTextVO(nvl(templateGraphService.selectedTemplate(projectId,fileKey).getTextContent()));
        SourceDocument template = sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        if (JsonUtils.isBlankText(template.getTextContent()) && template.getStoragePath() != null) {
            try {
                DocumentParser.ParsedDocument result = documentParser.parseTemplate(fileKey,
                        template.getFileName(), storageService.read(template.getStoragePath()));
                template.setTextContent(result.getText());
                template.setParseStatus(result.getParseStatus());
                template.setParseMessage(result.getMessage());
                template.setPageCount(result.getPageCount());
                template.setOcrUsed(result.isOcrUsed());
                template.setStructuredContentJson(JsonUtils.write(result.getBlocks()));
                template.setParseCoverageJson(JsonUtils.write(result.getCoverage()));
                com.consense.service.sources.OerSourceMetadata.captureParsed(template,result);
                sourceDocumentRepository.save(template);
            } catch (Exception failure) {
                template.setParseStatus("FAILED");
                template.setParseMessage(failure.getMessage());
                sourceDocumentRepository.save(template);
            }
        }
        return new TemplateTextVO(template.getTextContent() == null ? "" : template.getTextContent());
    }

    /**
     * 第 3 步「标准模板在线编辑」：保存模板正文到 textContent。
     * 仅持久化文本，不重新抽取变量（变量列表由用户在第 2 步或本面板手动维护）。
     */
    @Transactional
    public TemplateTextVO updateTemplateText(String projectId, String fileKey, String text) {
        projectService.require(projectId);
        SourceDocument template = reviewedRules(projectId)?templateGraphService.selectedTemplate(projectId,fileKey):sourceDocumentRepository
                .findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "尚未上传 " + fileKey + " 标准模板，请到第 1 步上传"));
        if(!nvl(template.getTextContent()).equals(nvl(text)))throw new BizException(4012,"SOURCE_TEXT_EDIT_REQUIRES_DOCX: Upload the edited source DOCX so formatting and text stay together.");
        return new TemplateTextVO(template.getTextContent());
    }

    // ------------------------------------------------------------ 辅助

    private boolean affectsFile(DraftVariable variable, String fileKey) {
        if (fileKey.equals(variable.getFileKey())) {
            return true;
        }
        return variable.getAffects() != null
                && Arrays.stream(variable.getAffects().split(",")).map(String::trim).anyMatch(fileKey::equals);
    }

    /**
     * 收集标准模板文本：短模板全文送入；超长模板（如 SCC 全文 20+ 万字符）改为
     * 决策片段聚焦提取——优先保留 Guidance Note / Option / Delete 等标记附近的原文，
     * 再用文档开头（标题与目录）补足背景，确保决策点不因截断而丢失。
     */
    private String collectTemplateText(String projectId) {
        List<SourceDocument> documents = sourceDocumentRepository
                .findByProjectIdAndCategoryOrderByIdAsc(projectId, SourceDocument.CATEGORY_STANDARD_TEMPLATE);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_TEMPLATE_CHARS;
        for (SourceDocument document : documents) {
            if (JsonUtils.isBlankText(document.getTextContent()) || remaining <= 0) {
                continue;
            }
            String header = "\n\n=== " + document.getFileName() + " ===\n";
            String raw = document.getTextContent();
            String body;
            if (raw.length() <= MAX_DOC_CHARS) {
                body = raw;
            } else {
                body = focusDecisionSegments(raw, Math.min(MAX_DOC_CHARS, remaining - header.length()));
                int room = Math.min(MAX_DOC_CHARS, remaining - header.length()) - body.length();
                if (room > 800) {
                    // 文档开头（封面/目录）提供全局背景
                    body = truncate(raw, Math.min(1800, room)) + "\n……（中间内容省略）……\n" + body;
                }
            }
            builder.append(header).append(body);
            remaining -= body.length() + header.length();
        }
        return builder.toString().trim();
    }

    /**
     * 决策片段聚焦提取：定位所有决策标记（Guidance Note、Option、Delete 等）出现的位置，
     * 各取前后一段上下文，合并重叠区间后按原文顺序拼接，总量不超过 budget。
     */
    private String focusDecisionSegments(String text, int budget) {
        // 1. 收集每个标记命中位置的扩展区间 [from, to]
        List<int[]> spans = new ArrayList<>();
        for (String marker : DECISION_MARKERS) {
            int idx = 0;
            while (spans.size() < 400) {
                idx = text.indexOf(marker, idx);
                if (idx < 0) {
                    break;
                }
                spans.add(new int[]{
                        Math.max(0, idx - 300),
                        Math.min(text.length(), idx + 500)});
                idx += marker.length();
            }
        }
        if (spans.isEmpty()) {
            return truncate(text, budget);
        }
        // 2. 按起点排序并合并重叠区间
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] span : spans) {
            if (!merged.isEmpty() && span[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new int[]{span[0], span[1]});
            }
        }
        // 3. 顺序拼接，总量控制在 budget 内（每个片段之间用省略号衔接）
        StringBuilder builder = new StringBuilder();
        for (int[] span : merged) {
            if (builder.length() >= budget) {
                break;
            }
            if (builder.length() > 0) {
                builder.append("\n……（未命中决策线索的正文省略）……\n");
            }
            String segment = text.substring(span[0], Math.min(span[1], text.length()));
            int room = budget - builder.length();
            builder.append(segment.length() <= room ? segment : segment.substring(0, room));
        }
        return builder.toString();
    }

    private String collectEvidenceText(String projectId, String category) {
        List<SourceDocument> documents =
                sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId, category);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_EVIDENCE_CHARS;
        for (SourceDocument document : documents) {
            if (JsonUtils.isBlankText(document.getTextContent()) || remaining <= 0) {
                continue;
            }
            String header = "\n\n=== " + document.getFileName() + " ===\n";
            String body = truncate(document.getTextContent(), Math.min(MAX_DOC_CHARS, remaining));
            builder.append(header).append(body);
            remaining -= body.length() + header.length();
        }
        return builder.toString().trim();
    }

    /**
     * 从全部证据中为 BASE 变量抽取关键句（不占用 MAX_EVIDENCE_CHARS 预算），
     * 避免 BQ Bill、分包清单等写在靠后文件里的值被整体截断漏掉。
     */
    private String collectBaseEvidenceSnippets(String projectId) {
        List<String> keywords = Arrays.asList(
                "contract no", "contract title", "tender a", "tender b", "funding arrangement",
                "bill ", "bq", "bill schedule", "preliminar", "preamble",
                "sub-contractor", "subcontractor", "specialist", "nominated", "trade");
        List<SourceDocument> documents =
                sourceDocumentRepository.findByProjectIdAndCategoryOrderByIdAsc(projectId,
                        SourceDocument.CATEGORY_PROJECT_INPUT);
        StringBuilder builder = new StringBuilder();
        int remaining = MAX_EVIDENCE_SNIPPET_CHARS;
        for (SourceDocument document : documents) {
            String text = nvl(document.getTextContent());
            if (text.isEmpty()) {
                continue;
            }
            String[] lines = text.split("\\r?\\n");
            boolean headerAdded = false;
            for (String line : lines) {
                if (remaining <= 0) {
                    break;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                boolean hit = false;
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.length() < 6) {
                    continue;
                }
                if (!headerAdded) {
                    String header = "\n[" + document.getFileName() + "]\n";
                    builder.append(header);
                    remaining -= header.length();
                    headerAdded = true;
                }
                String entry = "  " + trimmed + "\n";
                if (entry.length() > remaining) {
                    break;
                }
                builder.append(entry);
                remaining -= entry.length();
            }
        }
        String result = builder.toString().trim();
        return result.isEmpty() ? "（无聚焦摘录）" : result;
    }

    private EvidenceVO toEvidenceVO(SourceDocumentRepository.UploadDisplayMetadata document) {
        String contentType = nvl(document.getContentType()).toLowerCase(Locale.ROOT);
        String fileName = nvl(document.getFileName()).toLowerCase(Locale.ROOT);
        String typeLabel;
        if (contentType.contains("message") || contentType.contains("rfc822")
                || fileName.endsWith(".eml") || fileName.endsWith(".msg")) {
            typeLabel = "邮件";
        } else if (contentType.contains("pdf") || fileName.endsWith(".pdf")) {
            typeLabel = "PDF";
        } else if (contentType.contains("word") || fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            typeLabel = "Word";
        } else {
            typeLabel = "文本";
        }
        String status = nvl(document.getParseStatus());
        String tag;
        if ("PARSED".equals(status)) {
            tag = "ok";
        } else if ("FAILED".equals(status)) {
            tag = "danger";
        } else {
            tag = "info";
        }
        return new EvidenceVO(
                document.getId(),
                "E-" + (document.getId() == null ? "0" : document.getId()),
                LocalizedText.same(typeLabel),
                status,
                tag,
                LocalizedText.same(nvl(document.getFileName())),
                LocalizedText.same(truncate(nvl(document.getTextContent()), 400)),
                nvl(document.getParseMessage()),
                document.getFileName(),
                document.getFileKey(),
                document.getPageCount(),
                document.getOcrUsed(),
                LocalizedText.same(nvl(document.getParseMessage())));
    }

    private VariableVO toVariableVO(DraftVariable variable) {
        List<LocalizedText> options = new ArrayList<>();
        if (!JsonUtils.isBlankText(variable.getOptionsJson())) {
            try {
                options = JsonUtils.readList(variable.getOptionsJson(), LocalizedText.class);
            } catch (Exception e) {
                log.debug("解析 options 失败: {}", e.getMessage());
            }
        }
        List<String> cols = new ArrayList<>();
        if (!JsonUtils.isBlankText(variable.getColsJson())) {
            try {
                cols = JsonUtils.readList(variable.getColsJson(), String.class);
            } catch (Exception e) {
                log.debug("解析 cols 失败: {}", e.getMessage());
            }
        }
        List<String> affects = JsonUtils.isBlankText(variable.getAffects())
                ? Collections.<String>emptyList()
                : Arrays.stream(variable.getAffects().split(",")).map(String::trim).collect(Collectors.toList());
        List<String> derivedFrom = JsonUtils.isBlankText(variable.getDerivedFrom())
                ? Collections.<String>emptyList()
                : Arrays.stream(variable.getDerivedFrom().split(",")).map(String::trim).collect(Collectors.toList());

        VariableVO vo = new VariableVO(variable.getVarKey(),
                variable.getScope(),
                variable.getFileKey(),
                LocalizedText.of(variable.getLabelZhHans(), variable.getLabelZhHant(), variable.getLabelEn()),
                variable.getAction(),
                nvl(variable.getValueText()),
                options,
                Boolean.TRUE.equals(variable.getConfirmed()),
                variable.getConfirmedFrom(),
                variable.getSourceRef(),
                variable.getResultText(),
                variable.getKind(),
                cols,
                variable.getLinkedBase(),
                derivedFrom,
                affects,
                variable.getNoteText());
        List<CandidateVO> candidates=DraftAdoption.candidates(variable);
        vo.setCandidates(candidates);vo.setManuallyEdited(Boolean.TRUE.equals(variable.getManuallyEdited()));vo.setReviewRequired(Boolean.TRUE.equals(variable.getReviewRequired()));
        boolean answered=!JsonUtils.isBlankText(variable.getValueText());boolean valid=DraftInputRules.valid(variable);
        String invalid=DraftBusinessRules.invalid(variable.getVarKey(),DraftAdoption.decode(variable.getValueText()));
        if(Arrays.asList("siteInspectionStartDate","siteInspectionEndDate").contains(variable.getVarKey())) {
            String dates=DraftBusinessRules.datesIssue(decodedValues(variable.getProjectId()));if(dates!=null)invalid=dates;
        }
        if(invalid!=null){valid=false;vo.setValidationIssue(invalid);}
        else if(answered&&!valid)vo.setValidationIssue("The entered value requires review before it can be used.");
        boolean conflict=candidates.stream().map(CandidateVO::getValue).distinct().count()>1 && !answered;
        vo.setAdoptionState(vo.isReviewRequired()||answered&&!valid?"needs_review":!answered?(conflict&&!vo.isManuallyEdited()?"conflict":"missing"):Boolean.TRUE.equals(variable.getConfirmed())||vo.isManuallyEdited()?"adopted":"suggested");
        return vo;
    }

    private SourceDocument requireTemplate(String projectId, String fileKey) {
        if(reviewedRules(projectId))return templateGraphService.selectedTemplate(projectId,fileKey);
        SourceDocument source = sourceDocumentRepository.findFirstByProjectIdAndFileKeyAndCategory(projectId, fileKey, SourceDocument.CATEGORY_STANDARD_TEMPLATE)
                .orElseThrow(() -> new BizException(4011, "请先上传 " + fileKey + " 标准模板"));
        if (!"PARSED".equals(source.getParseStatus()) || JsonUtils.isBlankText(source.getTextContent()))
            throw new BizException(4011, fileKey + " 模板尚未成功解析");
        return source;
    }

    private void requireCurrent(DraftDocument document) {
        if (!Boolean.TRUE.equals(document.getGenerated())) throw new BizException(4009, "No generated draft is available.");
        if (document.getRevisionId()==null || document.getSnapshotId()==null || !document.getSnapshotId().equals(snapshotId(document.getProjectId(),null)))
            throw new BizException(4009,"This draft is outdated. Generate the drafts again before editing, previewing or exporting.");
    }

    private void applyContractIdentity(Project project,DraftDocument document) {
        // Render the adopted identity frozen for this generation, rather than mutable project metadata.
        if(document.getSnapshotId()!=null)project.setContractNo("[To be completed]");
        Map<String,Object> frozen=JsonUtils.readMap(document.getInputSnapshotJson());
        Object values=frozen.get("effectiveValues");
        if(values instanceof Map) {
            Object identity=((Map<?,?>)values).get("contractTitle");
            if(identity instanceof Map) {
                Object number=((Map<?,?>)identity).get("number");
                if(number!=null&&!JsonUtils.isBlankText(String.valueOf(number)))project.setContractNo(String.valueOf(number));
            }
        }
    }

    public byte[] exportWord(String projectId, String fileKey) {
        return exportWord(projectId,fileKey,null);
    }
    public byte[] exportWord(String projectId,String fileKey,String expectedRevision) {
        projectService.require(projectId);
        DraftDocument document = documentRepository.findByProjectIdAndFileKey(projectId, fileKey).orElseThrow(() -> new BizException(4008, "尚未生成文稿"));
        requireCurrent(document);
        requireRevision(document,expectedRevision);
        DraftArtifact revision=requireArtifact(document);
        byte[] bytes=revision.getDocxBytes();if(!revision.getDocxSha256().equals(DraftPdfConverter.sha256(bytes)))throw new BizException(4012,"GENERATED_ARTIFACT_HASH_MISMATCH");
        return bytes;
    }
    private DraftArtifact requireArtifact(DraftDocument document) {
        DraftArtifact artifact=artifactRepository.findById(document.getRevisionId()).orElseThrow(()->new BizException(4012,"GENERATED_ARTIFACT_MISSING"));
        if(!document.getProjectId().equals(artifact.getProjectId())||!document.getFileKey().equals(artifact.getFileKey())||!artifact.getDocxSha256().equals(DraftPdfConverter.sha256(artifact.getDocxBytes())))throw new BizException(4012,"GENERATED_ARTIFACT_HASH_MISMATCH");
        return artifact;
    }
    private void requireRevision(DraftDocument document,String expectedRevision) {
        if(expectedRevision!=null&&!expectedRevision.equals(document.getRevisionId()))throw new BizException(4090,"ARTIFACT_REVISION_CONFLICT: Reload the current draft.");
    }

    private String defaultTemplateFileName(String key) {
        if ("NTT".equals(key)) {
            return "01_Notes to Tenderers (NTT).docx";
        }
        if ("SCT".equals(key)) {
            return "03_Special Condition of Tender (SCT).docx";
        }
        return "07_Special Conditions of Contract (SCC).docx";
    }

    private String languageName(String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            return "English";
        }
        if ("zh-Hant".equalsIgnoreCase(lang)) {
            return "繁體中文";
        }
        return "简体中文";
    }

    private String pick(DraftVariable variable, String lang) {
        LocalizedText text = LocalizedText.of(variable.getLabelZhHans(),
                variable.getLabelZhHant(), variable.getLabelEn());
        return text.pick(lang);
    }

    /** key 只保留字母数字与下划线，长度限制内 */
    private String sanitizeKey(String key) {
        if (key == null) {
            return null;
        }
        String cleaned = key.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private String sanitizeFileKey(String fileKey) {
        if (fileKey == null) {
            return null;
        }
        String cleaned = fileKey.trim().toUpperCase(Locale.ROOT);
        return TEMPLATE_KEYS.contains(cleaned) ? cleaned : null;
    }

    private String sanitizeAffects(String affects) {
        if (JsonUtils.isBlankText(affects)) {
            return null;
        }
        List<String> kept = Arrays.stream(affects.split(","))
                .map(String::trim)
                .filter(s -> TEMPLATE_KEYS.contains(s.toUpperCase(Locale.ROOT)))
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    private String serializeOptions(List<String> options) {
        List<LocalizedText> opts = new ArrayList<>();
        for (String o : options) {
            opts.add(LocalizedText.same(o));
        }
        return JsonUtils.write(opts);
    }

    private String firstNonBlank(String primary, String fallback) {
        return JsonUtils.isBlankText(primary) ? fallback : primary;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "\n...[已截断]";
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    /** 项目显示名：中文名优先，缺失时回退英文名；都没有返回空串 */
    private String projectDisplayName(Project project) {
        if (project == null) {
            return "";
        }
        if (!JsonUtils.isBlankText(project.getNameZhHans())) {
            return project.getNameZhHans();
        }
        return nvl(project.getNameEn());
    }
}
