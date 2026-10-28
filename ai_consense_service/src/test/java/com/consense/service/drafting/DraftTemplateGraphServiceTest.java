package com.consense.service.drafting;

import com.consense.ai.AiGateway;
import com.consense.common.*;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.OerTemplateEvidence;
import com.consense.document.ParseCoverage;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.StorageService;
import com.consense.web.dto.DraftingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated H2 persistence and exact synthetic assets; no external model or production state. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=false","consense.llm.enabled=false","consense.ocr.enabled=false","consense.vector.provider=memory"})
@ActiveProfiles("h2")
class DraftTemplateGraphServiceTest {
    static final String DB=UUID.randomUUID().toString();
    @DynamicPropertySource static void resources(DynamicPropertyRegistry registry){registry.add("spring.datasource.url",()->"jdbc:h2:mem:graph_"+DB+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");registry.add("consense.storage-root",()->Paths.get("target","graph-service-synthetic",DB).toAbsolutePath().toString());}
    @Autowired DraftTemplateGraphService service;
    @Autowired DocumentParser documentParser;
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @SpyBean DraftVariableRepository values;
    @Autowired DraftTemplateGraphRevisionRepository revisions;
    @Autowired DraftTemplateBaselineRepository baselines;
    @Autowired DraftAutomationRunRepository automationRuns;
    @Autowired DraftTemplateGraphHeadRepository graphHeads;
    @SpyBean DraftExtractionRunRepository runs;
    @Autowired StorageService storage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.consense.config.ConsenseProperties settings;
    @SpyBean DraftReviewSources reviewSources;
    @javax.persistence.PersistenceContext javax.persistence.EntityManager entityManager;
    @Autowired com.consense.service.ProjectService projectService;
    @MockBean AiGateway ai;
    @BeforeEach void gateway(){when(ai.chatModel()).thenReturn("mocked-dynamic-regression");}
    String project(){Project project=new Project();project.setId("graph-"+UUID.randomUUID());project.setNameZhHans("Synthetic");projects.save(project);return project.getId();}
    SourceDocument template(String project) throws Exception {try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Duration: ____ months.");word.write(bytes);return source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","synthetic.docx",bytes.toByteArray(),"Duration: ____ months.");}}
    SourceDocument correspondence(String project,String text){return source(project,SourceDocument.CATEGORY_PROJECT_INPUT,null,"synthetic-minutes.txt",text.getBytes(StandardCharsets.UTF_8),text);}
    SourceDocument source(String project,String category,String key,String name,byte[] bytes,String text){SourceDocument source=new SourceDocument();source.setProjectId(project);source.setCategory(category);source.setFileKey(key);source.setFileName(name);source.setParseStatus("PARSED");source.setTextContent(text);source.setSourceSha256(DraftTemplateRouting.sha(bytes));source.setStoragePath(storage.store(project,category,new MockMultipartFile("file",name,"application/octet-stream",bytes)).getPath());return sources.save(source);}
    Map<String,Object> graph(SourceDocument template,String status,String type){String id=template.getId().toString();Map<String,Object> anchor=map("docId",id,"text","Duration: ____ months.");Map<String,Object> node=map("id","duration","nameZh","工期","nameEn","Duration","type",map("kind",type),"role",map("kind","input","zh","项目事实","en","Project fact"),"status",status,"docIds",Collections.singletonList(id),"sourceAnchors",Collections.singletonList(anchor),"editTargets",new ArrayList<>(),"options",new ArrayList<>());return map("kind","consense-template-graph-v1","graph",map("variables",Collections.singletonList(node),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",2,"blank",false,"guidanceMode","recognized"),"selectedDocuments",map("NTT",id));}
    String confirm(String project,SourceDocument template,String type,String current){Map<String,Object> draft=service.save(project,graph(template,"confirmed",type),current);return String.valueOf(service.confirm(project,String.valueOf(draft.get("id")),current).get("id"));}
    VariableVO variable(String project){return service.variables(project).stream().filter(value->"duration".equals(value.getKey())).findFirst().get();}
    VariablePatch patch(VariableVO current,Object... extra){Map<String,Object> body=map("graphRevisionId",current.getGraphRevisionId(),"fieldDefinitionHash",current.getFieldDefinitionHash());for(int i=0;i<extra.length;i+=2)body.put(String.valueOf(extra[i]),extra[i+1]);return JsonUtils.mapper().convertValue(body,VariablePatch.class);}
    @SuppressWarnings("unchecked") Map<String,Object> effective(String project){return (Map<String,Object>)service.plan(project,null).get("effectiveValues");}
    String response(String value,String quote){return JsonUtils.write(Collections.singletonList(map("key","duration","value",Integer.valueOf(value),"sourceQuote",quote,"reason","Synthetic source statement","confidence",0.99)));}
    Map<String,Object> manyFields(SourceDocument template,int count){Map<String,Object> graph=graph(template,"confirmed","number"),state=(Map<String,Object>)graph.get("graph");List<Object> fields=new ArrayList<>();for(int n=0;n<count;n++){Map<String,Object> field=JsonUtils.readMap(JsonUtils.write(DraftReviewGraph.nodes(graph).get(0)));field.put("id",n==0?"duration":"duration-"+n);field.put("nameEn","Test-only duration "+n);field.put("nameZh","测试工期 "+n);fields.add(field);}state.put("variables",fields);return graph;}
    String confirmMany(String project,SourceDocument template,int count){Map<String,Object> draft=service.save(project,manyFields(template,count),null);return String.valueOf(service.confirm(project,String.valueOf(draft.get("id")),null).get("id"));}
    String approvedDraft(String project,String draft){return String.valueOf(service.confirmDefinitions(project,draft,null,"Test-only human graph review",true).get("id"));}
    Map<String,Object> withConfirmedDefinition(Map<String,Object> graph){DraftReviewGraph.nodes(graph).get(0).put("definitionReview",map("confirmed",true,"basis",null));return graph;}
    Map<String,Object> confirmedBillGraph(SourceDocument template){
        Map<String,Object> graph=graph(template,"confirmed","number"),root=DraftReviewGraph.nodes(graph).get(0);root.put("id","atomic-bills");root.put("type",map("kind","list"));root.put("catalogAlignment",map("status","matched","baselineKey","billNos","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human review"));root.put("definitionReview",map("confirmed",true,"basis",null));
        List<Object> nodes=new ArrayList<>();nodes.add(root);for(String key:Arrays.asList("number","description","type")){Map<String,Object> child=JsonUtils.readMap(JsonUtils.write(root));child.put("id","atomic-"+key);child.put("parentId","atomic-bills");child.put("nameEn",key);child.put("type",map("kind","type".equals(key)?"choice":"text"));child.put("optional","type".equals(key));child.put("options","type".equals(key)?Arrays.asList(map("value","BQ"),map("value","SOR")):Collections.emptyList());child.put("catalogAlignment",map("status","matched","baselineKey","billNos."+key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human review"));nodes.add(child);}DraftReviewGraph.state(graph).put("variables",nodes);return graph;
    }
    Map<String,Object> confirmedWaterproofGraph(SourceDocument template){Map<String,Object> graph=graph(template,"confirmed","text"),node=DraftReviewGraph.nodes(graph).get(0);node.put("id","waterproof-areas");node.put("nameEn","Other waterproofing areas");node.put("catalogAlignment",map("status","matched","baselineKey","otherWaterproofingSpecificationAreas","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human confirms this is the specified area wording"));node.put("definitionReview",map("confirmed",true,"basis",null));return graph;}

    @Test void explicitlySharedTemplateSnapshotSurvivesProjectDeletionWithoutProjectAnswers() throws Exception {
        String owner=project(),secret="PRIVATE_PROJECT_ANSWER";SourceDocument original=template(owner);saveSyntheticOerEvidence(original,"same-style");
        byte[] bytes=storage.read(original.getStoragePath());SourceDocument originalScc=source(owner,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","original-scc.docx",bytes,"Duration: ____ months.");saveSyntheticOerEvidence(originalScc,"same-style");Map<String,Object> proposed=withConfirmedDefinition(graph(original,"pending","number")),node=DraftReviewGraph.nodes(proposed).get(0);DraftReviewGraph.map(proposed.get("selectedDocuments")).put("SCC",originalScc.getId().toString());
        node.put("value",secret);DraftReviewGraph.map(node.get("type")).put("value",secret);DraftReviewGraph.map(DraftReviewGraph.list(node.get("sourceAnchors")).get(0)).put("value",secret);node.put("editTargets",Collections.singletonList(map("id","target","docId",original.getId().toString(),"text","____","action","replace","replacementEn",secret,"itemValue",secret,"itemId",secret,"reason",secret)));
        Map<String,Object> other=JsonUtils.readMap(JsonUtils.write(node));other.put("id","unshared");other.put("editTargets",new ArrayList<>());
        Map<String,Object> derived=JsonUtils.readMap(JsonUtils.write(node));derived.put("id","long-duration");derived.put("type",map("kind","boolean"));derived.put("role",map("kind","derived"));derived.put("status","confirmed");derived.remove("definitionReview");derived.put("correction",map("kind","rule","branches",Collections.singletonList(map("id","long","mode","all","clauses",Collections.singletonList(map("inputId","duration","operator","ge","value",39)),"resultValue",true))));derived.put("editTargets",Collections.singletonList(map("id","derived-target","docId",original.getId().toString(),"text","____","action","delete","branchId","long")));
        DraftReviewGraph.state(proposed).put("variables",Arrays.asList(node,other,derived));DraftReviewGraph.state(proposed).put("relationProposals",Collections.singletonList(map("fromId","duration","toId","long-duration","kind","dependency","status","confirmed","reason",secret)));
        String draft=String.valueOf(service.save(owner,proposed,null).get("id"));String reviewed=String.valueOf(service.confirmDefinitions(owner,draft,null,"Confirm duration only",true,Collections.singletonList("duration"),false).get("id"));
        assertFalse(baselines.findAll().stream().anyMatch(item->owner.equals(item.getOriginProjectId())));
        service.confirmDefinitions(owner,reviewed,null,"Share duration for this version",true,Collections.singletonList("duration"),true);
        List<DraftTemplateBaseline> shared=baselines.findAll();assertEquals(1,shared.stream().filter(item->owner.equals(item.getOriginProjectId())).count());DraftTemplateBaseline saved=shared.stream().filter(item->owner.equals(item.getOriginProjectId())).findFirst().orElseThrow();
        assertFalse(saved.getGraphJson().contains(secret));assertFalse(saved.getGraphJson().contains("unshared"));assertEquals(DraftAdoption.hash(saved.getGraphJson()),saved.getGraphSha256());
        projectService.delete(owner);
        String next=project();SourceDocument same=source(next,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","another-name.docx",bytes,"Duration: ____ months.");saveSyntheticOerEvidence(same,"same-style");
        SourceDocument changedScc;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Another SCC edition.");word.write(output);changedScc=source(next,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","changed-scc.docx",output.toByteArray(),"Another SCC edition.");}saveSyntheticOerEvidence(changedScc,"same-style");
        Map<String,Object> restored=(Map<String,Object>)service.bootstrap(next).get("graph");Map<String,Object> field=DraftReviewGraph.nodes(restored).stream().filter(item->"duration".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("pending",field.get("status"));assertEquals(Collections.singletonList(same.getId().toString()),field.get("docIds"));assertFalse(JsonUtils.write(restored).contains(secret));assertFalse(DraftReviewGraph.nodes(restored).stream().anyMatch(item->"unshared".equals(item.get("id"))));
        Map<String,Object> restoredDerived=DraftReviewGraph.nodes(restored).stream().filter(item->"long-duration".equals(item.get("id"))).findFirst().orElseThrow();assertEquals("pending",restoredDerived.get("status"));assertTrue(JsonUtils.write(restoredDerived.get("correction")).contains("duration"));assertEquals(1,DraftReviewGraph.list(DraftReviewGraph.state(restored).get("relationProposals")).size());
        Map<String,Object> target=DraftReviewGraph.map(DraftReviewGraph.list(field.get("editTargets")).get(0));assertTrue(Boolean.TRUE.equals(target.get("needsRebind")));assertFalse(target.containsKey("replacementEn"));assertFalse(target.containsKey("itemId"));
        String different=project();SourceDocument changed;try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("A different template.");word.write(output);changed=source(different,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","different.docx",output.toByteArray(),"A different template.");}saveSyntheticOerEvidence(changed,"same-style");
        Map<String,Object> noMatch=(Map<String,Object>)service.bootstrap(different).get("graph");assertFalse(DraftReviewGraph.nodes(noMatch).stream().anyMatch(item->"duration".equals(item.get("id"))));
    }

    @Test void sameFormatSccOriginalsCannotUseAnAmbiguousLegacyAlias() throws Exception {
        String project=project();SourceDocument original=template(project);byte[] bytes=storage.read(original.getStoragePath());
        SourceDocument first=source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","first.docx",bytes,"Duration: ____ months."),second=source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","second.docx",bytes,"Duration: ____ months.");
        Map<String,Object> proposed=graph(first,"pending","number");proposed.put("selectedDocuments",map("SCC",first.getId().toString()));Map<String,Object> node=DraftReviewGraph.nodes(proposed).get(0);
        node.put("docIds",Collections.singletonList("scc-word"));DraftReviewGraph.map(DraftReviewGraph.list(node.get("sourceAnchors")).get(0)).put("docId","scc-word");
        assertThrows(BizException.class,()->service.save(project,proposed,null));
        node.put("docIds",Collections.singletonList(first.getId().toString()));DraftReviewGraph.map(DraftReviewGraph.list(node.get("sourceAnchors")).get(0)).put("docId",first.getId().toString());
        Map<String,Object> saved=service.save(project,proposed,null);Map<String,Object> savedNode=DraftReviewGraph.nodes((Map<String,Object>)saved.get("graph")).get(0);assertEquals(Collections.singletonList(first.getId().toString()),savedNode.get("docIds"));assertNotEquals(first.getId(),second.getId());
    }

    @Test void completeEditionCanBeManuallyCompletedAfterPartialModelCoverage() throws Exception {
        String owner=project();SourceDocument original=template(owner);saveSyntheticOerEvidence(original,"same-style");byte[] bytes=storage.read(original.getStoragePath());
        Map<String,Object> proposed=withConfirmedDefinition(graph(original,"confirmed","number"));DraftReviewGraph.nodes(proposed).get(0).put("editTargets",Collections.singletonList(map("id","target","docId",original.getId().toString(),"text","____","action","replace","replacementEn","months")));DraftReviewGraph.state(proposed).put("automaticDiscovery",map("scanStatus","partial","failedRequirementTasks",1,"semanticCompleteness","requires_human_review"));DraftReviewGraph.state(proposed).put("automaticDiscoveryIssues",Collections.singletonList(map("reason","synthetic_model_failure")));
        JsonNode reading=reviewSources.readingCompact(original);Map<String,Object> scope=map("sourceId",original.getId().toString(),"sourceSha256",original.getSourceSha256(),"analysisRevision",reading.path("analysisRevision").asText(),"analysisInputSha256",reading.path("analysisInputSha256").asText(),"analysisSha256",reading.path("analysisSha256").asText(),"sourceObservationId","synthetic-scope","sourceScopeObservation",map("id","synthetic-scope"),"sourceScopeText","Duration: ____ months.");String ref=DraftScopeObservationIntegrity.reference(scope);
        Map<String,Object> requirement=map("id","req","sourceId",original.getId().toString(),"sourceSha256",original.getSourceSha256(),"sourceQuote","Duration","kind","input","nodeIds",Collections.singletonList("duration"),"reason","Template instruction","status","pending","sourceQuoteEvidence",map("kind","scope","sourceObservationId","synthetic-scope","sourceScopeObservationRef",ref),"analysisRevision",scope.get("analysisRevision"),"analysisInputSha256",scope.get("analysisInputSha256"),"analysisSha256",scope.get("analysisSha256"));DraftReviewGraph.state(proposed).put("requirements",Collections.singletonList(requirement));DraftReviewGraph.state(proposed).put("sourceScopeObservations",map(ref,scope));
        String draft=String.valueOf(service.save(owner,proposed,null).get("id"));
        Map<String,Object> unsafe=(Map<String,Object>)service.revision(owner,draft).get("graph");DraftReviewGraph.map(DraftReviewGraph.list(DraftReviewGraph.nodes(unsafe).get(0).get("editTargets")).get(0)).put("replacementEn","project-only literal");assertNull(DraftBaselineRestore.snapshot(unsafe,Collections.singletonList("duration"),true));
        Map<String,Object> trusted=(Map<String,Object>)service.revision(owner,draft).get("graph"),forged=JsonUtils.readMap(JsonUtils.write(trusted));DraftReviewGraph.map(DraftReviewGraph.list(DraftReviewGraph.state(forged).get("requirements")).get(0)).put("sourceQuote","Project-only text");assertNull(DraftBaselineRestore.snapshot(forged,Collections.singletonList("duration"),true,trusted));
        assertFalse(baselines.findAll().stream().anyMatch(item->owner.equals(item.getOriginProjectId())&&item.isCompleteEdition()));
        DraftAutomationRun proof=new DraftAutomationRun();proof.setId(UUID.randomUUID().toString());proof.setProjectId(owner);proof.setStatus("PARTIAL");proof.setPhase("READY_FOR_REVIEW");proof.setProfileId("synthetic");proof.setConfigurationSha256("a".repeat(64));proof.setResultRevisionId(draft);proof.setCreatedAt(java.time.Instant.now());proof.setUpdatedAt(proof.getCreatedAt());automationRuns.saveAndFlush(proof);
        service.confirmDefinitions(owner,draft,null,"All source text and relationships reviewed",true,Collections.singletonList("duration"),true,true);
        assertTrue(baselines.findAll().stream().anyMatch(item->owner.equals(item.getOriginProjectId())&&item.isCompleteEdition()));
        DraftTemplateBaseline complete=baselines.findAll().stream().filter(item->owner.equals(item.getOriginProjectId())&&item.isCompleteEdition()).findFirst().orElseThrow();JsonNode completeGraph=JsonUtils.parse(complete.getGraphJson()),review=completeGraph.path("graph").path("baselineCompletenessReview");assertEquals("manual_complete_edition",review.path("basis").asText());assertEquals(complete.getOriginRevisionId(),review.path("revisionId").asText());assertEquals(revisions.findById(complete.getOriginRevisionId()).orElseThrow().getGraphSha256(),review.path("graphSha256").asText());assertEquals("PARTIAL",automationRuns.findById(proof.getId()).orElseThrow().getStatus());assertEquals("partial",JsonUtils.parse(revisions.findById(draft).orElseThrow().getGraphJson()).path("graph").path("automaticDiscovery").path("scanStatus").asText());assertEquals("synthetic_model_failure",JsonUtils.parse(revisions.findById(draft).orElseThrow().getGraphJson()).path("graph").path("automaticDiscoveryIssues").path(0).path("reason").asText());
        String next=project();SourceDocument same=source(next,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","same.docx",bytes,"Duration: ____ months.");saveSyntheticOerEvidence(same,"same-style");
        Map<String,Object> restored=(Map<String,Object>)service.bootstrap(next).get("graph");assertEquals("complete_requires_review",DraftReviewGraph.map(DraftReviewGraph.state(restored).get("baselineRestoration")).get("status"));assertEquals("pending",DraftReviewGraph.nodes(restored).get(0).get("status"));Map<String,Object> target=DraftReviewGraph.map(DraftReviewGraph.list(DraftReviewGraph.nodes(restored).get(0).get("editTargets")).get(0));assertEquals("months",target.get("replacementEn"));assertEquals(true,target.get("needsRebind"));
        Map<String,Object> restoredRequirement=DraftReviewGraph.map(DraftReviewGraph.list(DraftReviewGraph.state(restored).get("requirements")).get(0));assertEquals(same.getId().toString(),restoredRequirement.get("sourceId"));assertEquals("Duration",restoredRequirement.get("sourceQuote"));Map<String,Object> restoredEvidence=DraftReviewGraph.map(restoredRequirement.get("sourceQuoteEvidence")),restoredAnchor=DraftReviewGraph.map(restoredEvidence.get("anchor"));assertEquals("manual_source",restoredEvidence.get("kind"));assertEquals(same.getId().toString(),restoredAnchor.get("docId"));assertEquals(same.getId().longValue(),((Number)restoredAnchor.get("sourceDocumentId")).longValue());assertFalse(DraftReviewGraph.list(DraftReviewGraph.map(restoredAnchor.get("native")).get("spans")).isEmpty());assertFalse(restoredEvidence.containsKey("sourceScopeObservationRef"));assertFalse(DraftReviewGraph.state(restored).containsKey("sourceScopeObservations"));assertEquals(ref,JsonUtils.parse(revisions.findById(draft).orElseThrow().getGraphJson()).path("graph").path("requirements").path(0).path("sourceQuoteEvidence").path("sourceScopeObservationRef").asText());assertTrue(JsonUtils.parse(revisions.findById(draft).orElseThrow().getGraphJson()).path("graph").path("sourceScopeObservations").has(ref));
        String restoredDraft=String.valueOf(service.save(next,restored,null).get("id")),baselineId=String.valueOf(DraftReviewGraph.map(DraftReviewGraph.state(restored).get("baselineRestoration")).get("baselineId"));
        DraftAutomationRun reuse=new DraftAutomationRun();reuse.setId(UUID.randomUUID().toString());reuse.setProjectId(next);reuse.setStatus("COMPLETED");reuse.setPhase("READY_FOR_REVIEW");reuse.setProfileId("synthetic");reuse.setConfigurationSha256("b".repeat(64));reuse.setResultRevisionId(restoredDraft);reuse.setManifestJson(JsonUtils.write(map("completeBaselineId","unrelated")));reuse.setCreatedAt(java.time.Instant.now());reuse.setUpdatedAt(reuse.getCreatedAt());automationRuns.saveAndFlush(reuse);
        Map<String,Object> corrected=(Map<String,Object>)service.revision(next,restoredDraft).get("graph"),correctedNode=DraftReviewGraph.nodes(corrected).get(0);correctedNode.put("nameEn","Reviewed duration");correctedNode.put("status","confirmed");correctedNode.put("definitionReview",map("confirmed",true,"basis",null));DraftReviewGraph.map(DraftReviewGraph.list(correctedNode.get("editTargets")).get(0)).put("needsRebind",false);
        String correctedDraft=String.valueOf(service.save(next,corrected,null).get("id"));service.confirmDefinitions(next,correctedDraft,null,"Reviewed correction against every original",true,Collections.singletonList("duration"),true,true);
        String third=project();SourceDocument sameAgain=source(third,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","third.docx",bytes,"Duration: ____ months.");saveSyntheticOerEvidence(sameAgain,"same-style");Map<String,Object> reused=(Map<String,Object>)service.bootstrap(third).get("graph");assertEquals("complete_requires_review",DraftReviewGraph.map(DraftReviewGraph.state(reused).get("baselineRestoration")).get("status"));assertEquals("Reviewed duration",DraftReviewGraph.nodes(reused).get(0).get("nameEn"));
        String changed=project();SourceDocument reanalysed=source(changed,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"NTT","same.docx",bytes,"Duration: ____ months.");saveSyntheticOerEvidence(reanalysed,"changed-style");
        Map<String,Object> fallback=(Map<String,Object>)service.bootstrap(changed).get("graph");assertEquals("partial_requires_review",DraftReviewGraph.map(DraftReviewGraph.state(fallback).get("baselineRestoration")).get("status"));
    }

    @Test void manualCompleteBaselineRejectsUnboundNodesAndOmitsUnprovenHistoricalRequirements()throws Exception{
        String project=project();SourceDocument source=template(project);saveSyntheticOerEvidence(source,"manual-complete");Map<String,Object> graph=withConfirmedDefinition(graph(source,"confirmed","number"));JsonNode reading=reviewSources.readingCompact(source);
        Map<String,Object> bad=map("id","unproven","sourceId",source.getId().toString(),"sourceSha256",source.getSourceSha256(),"sourceQuote","Invented instruction","kind","input","nodeIds",Collections.singletonList("duration"),"sourceQuoteEvidence",map("kind","scope","sourceScopeObservationRef","forged"));for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))bad.put(key,reading.path(key).asText());
        Map<String,Object> bound=JsonUtils.readMap(JsonUtils.write(bad));bound.put("id","manual-source");bound.put("sourceQuote","Duration");bound.put("sourceQuoteEvidence",map("anchor",map("docId",source.getId().toString(),"text","Duration: ____ months.")));Map<String,Object> excluded=JsonUtils.readMap(JsonUtils.write(bound));excluded.put("id","excluded-node");excluded.put("nodeIds",Collections.singletonList("missing-node"));DraftReviewGraph.state(graph).put("requirements",Arrays.asList(bad,bound,excluded));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));service.confirmDefinitions(project,draft,null,"I reviewed all original text and manually completed the graph",true,Collections.singletonList("duration"),true,true);
        DraftTemplateBaseline baseline=baselines.findAll().stream().filter(item->project.equals(item.getOriginProjectId())&&item.isCompleteEdition()).findFirst().orElseThrow();JsonNode shared=JsonUtils.parse(baseline.getGraphJson()).path("graph");assertEquals(1,shared.path("requirements").size());assertEquals("manual_source",shared.path("requirements").path(0).path("sourceQuoteEvidence").path("kind").asText());assertEquals(1,shared.path("baselineCompletenessReview").path("omittedHistoricalRequirements").path("source_evidence_unverified").asInt());assertEquals(1,shared.path("baselineCompletenessReview").path("omittedHistoricalRequirements").path("excluded_or_missing_node").asInt());assertEquals(3,JsonUtils.parse(revisions.findById(draft).orElseThrow().getGraphJson()).path("graph").path("requirements").size());
        Map<String,Object> changed=(Map<String,Object>)service.current(project).get("draft");Map<String,Object> current=JsonUtils.readMap(JsonUtils.write(changed.get("graph")));DraftReviewGraph.nodes(current).get(0).put("sourceAnchors",Collections.singletonList(map("docId",source.getId().toString(),"text","not present in the original")));String unbound=String.valueOf(service.save(project,current,null).get("id"));assertThrows(BizException.class,()->service.confirmDefinitions(project,unbound,null,"Claiming a complete review cannot bind invented text",true,Collections.singletonList("duration"),true,true));
        assertEquals(1,baselines.findAll().stream().filter(item->project.equals(item.getOriginProjectId())&&item.isCompleteEdition()).count());verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void aClientCompletenessClaimCannotReplaceCurrentRangeReview()throws Exception{
        String project=project();SourceDocument source=template(project);saveSyntheticOerEvidence(source,"range-review");Map<String,Object> graph=withConfirmedDefinition(graph(source,"confirmed","number"));DraftReviewGraph.state(graph).put("baselineCompletenessReview",map("basis","manual_complete_edition","revisionId","forged","graphSha256","forged"));DraftReviewGraph.state(graph).put("automaticDiscovery",map("scanStatus","completed"));DraftReviewGraph.nodes(graph).get(0).put("editTargets",Collections.singletonList(map("id","unreviewed-target","docId",source.getId().toString(),"text","____","action","check","needsRebind",true)));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));assertThrows(BizException.class,()->service.confirmDefinitions(project,draft,null,"A whole-edition assertion still requires reviewed ranges",true,Collections.singletonList("duration"),true,true));assertFalse(baselines.findAll().stream().anyMatch(item->project.equals(item.getOriginProjectId())&&item.isCompleteEdition()));verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void aServerRunCannotPromoteAnInheritedRequirementWithoutCurrentSourceEvidence()throws Exception{
        String project=project();SourceDocument source=template(project);saveSyntheticOerEvidence(source,"inherited-requirement");Map<String,Object> proposed=withConfirmedDefinition(graph(source,"confirmed","number"));JsonNode reading=reviewSources.readingCompact(source);
        Map<String,Object> invented=map("id","inherited-invented","sourceId",source.getId().toString(),"sourceSha256",source.getSourceSha256(),"sourceQuote","Forged project-only instruction","kind","input","nodeIds",Collections.singletonList("duration"),"status","pending","sourceQuoteEvidence",map("kind","scope","sourceScopeObservationRef","forged"));for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))invented.put(key,reading.path(key).asText());
        Map<String,Object> genuine=JsonUtils.readMap(JsonUtils.write(invented));genuine.put("id","inherited-genuine");genuine.put("sourceQuote","Duration");DraftReviewGraph.state(proposed).put("requirements",Arrays.asList(invented,genuine));DraftReviewGraph.state(proposed).put("automaticDiscovery",map("scanStatus","completed"));String draft=String.valueOf(service.save(project,proposed,null).get("id"));String originalGraph=revisions.findById(draft).orElseThrow().getGraphJson();
        DraftAutomationRun run=new DraftAutomationRun();run.setId(UUID.randomUUID().toString());run.setProjectId(project);run.setStatus("COMPLETED");run.setPhase("READY_FOR_REVIEW");run.setProfileId("synthetic");run.setConfigurationSha256("c".repeat(64));run.setResultRevisionId(draft);run.setCreatedAt(java.time.Instant.now());run.setUpdatedAt(run.getCreatedAt());automationRuns.saveAndFlush(run);
        service.confirmDefinitions(project,draft,null,"All current source text and graph reviewed",true,Collections.singletonList("duration"),true,true);
        DraftTemplateBaseline baseline=baselines.findAll().stream().filter(item->project.equals(item.getOriginProjectId())&&item.isCompleteEdition()).findFirst().orElseThrow();JsonNode shared=JsonUtils.parse(baseline.getGraphJson()).path("graph");assertEquals(1,shared.path("requirements").size());assertEquals("inherited-genuine",shared.path("requirements").path(0).path("id").asText());assertEquals("manual_source",shared.path("requirements").path(0).path("sourceQuoteEvidence").path("kind").asText());assertFalse(shared.path("requirements").path(0).path("sourceQuoteEvidence").path("anchor").path("native").path("spans").isEmpty());assertFalse(shared.path("requirements").path(0).path("sourceQuoteEvidence").has("sourceScopeObservationRef"));assertEquals(1,shared.path("baselineCompletenessReview").path("omittedHistoricalRequirements").path("source_evidence_unverified").asInt());assertFalse(baseline.getGraphJson().contains("Forged project-only instruction"));assertEquals(originalGraph,revisions.findById(draft).orElseThrow().getGraphJson());assertEquals("COMPLETED",automationRuns.findById(run.getId()).orElseThrow().getStatus());verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void graphConfirmationGatesNewExtractionAndTracksDefinitionsRelationsAndSources() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        Map<String,Object> graph=withConfirmedDefinition(graph(template,"pending","number")),node=DraftReviewGraph.nodes(graph).get(0);
        node.put("editTargets",Collections.singletonList(map("id","pending-target","docId",template.getId().toString(),"text","____","action","check","needsRebind",true)));
        DraftReviewGraph.state(graph).put("relationProposals",Collections.singletonList(map("fromId","duration","toId","duration","kind","undetermined","status","pending")));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));assertThrows(BizException.class,()->service.extract(project,draft));verify(ai,never()).complete(anyString(),anyString());
        assertThrows(BizException.class,()->service.confirmDefinitions(project,draft,null,"Reviewed",false));assertThrows(BizException.class,()->service.confirmDefinitions(project,draft,null," ",true));
        Map<String,Object> approved=service.confirmDefinitions(project,draft,null,"Graph reviewed; target still pending",true);String approvedId=String.valueOf(approved.get("id"));assertNotEquals(draft,approvedId);Map<?,?> review=(Map<?,?>)approved.get("definitionGraphReview");assertEquals(true,review.get("valid"));assertEquals(1,review.get("unresolvedRelationCount"));
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));assertEquals("42",service.extract(project,approvedId).get(0).getCandidates().get(0).getValue());
        VariableVO duration=variable(project);service.updateVariable(project,"duration",patch(duration,"value","45","note","Existing human value"));
        Map<String,Object> changed=(Map<String,Object>)service.revision(project,approvedId).get("graph"),changedNode=DraftReviewGraph.nodes(changed).get(0);DraftReviewGraph.map(DraftReviewGraph.list(changedNode.get("editTargets")).get(0)).put("action","delete");
        Map<String,Object> targetOnly=service.save(project,changed,null,approvedId,true);String targetId=String.valueOf(targetOnly.get("id"));assertEquals(true,((Map<?,?>)targetOnly.get("definitionGraphReview")).get("valid"));assertEquals("45",variable(project).getValue());
        assertEquals("42",JsonUtils.mapper().valueToTree(service.candidates(project,targetId)).path("variables").get(0).path("candidates").get(0).path("value").asText());
        Map<String,Object> changedRule=(Map<String,Object>)targetOnly.get("graph"),changedInput=DraftReviewGraph.nodes(changedRule).get(0);changedInput.put("correction",map("kind","rule","branches",Collections.singletonList(map("id","case","mode","all","clauses",Collections.singletonList(map("inputId","duration","operator","ge","value",30))))));
        Map<String,Object> changedRevision=service.save(project,changedRule,null,targetId,true);String changedId=String.valueOf(changedRevision.get("id"));assertEquals(false,((Map<?,?>)changedRevision.get("definitionGraphReview")).get("valid"));assertThrows(BizException.class,()->service.extract(project,changedId));assertEquals("45",variable(project).getValue());
        template.setTextContent("Changed source observation");sources.saveAndFlush(template);assertEquals(false,((Map<?,?>)service.revision(project,changedId).get("definitionGraphReview")).get("valid"));
    }

    @Test void confirmedSubsetExtractsIncrementallyAndPreservesOtherCandidates() throws Exception {
        String project=project();SourceDocument template=template(project);String quote="The duration is 42 months and the fee is 7.";correspondence(project,quote);
        Map<String,Object> graph=withConfirmedDefinition(graph(template,"pending","number")),other=JsonUtils.readMap(JsonUtils.write(DraftReviewGraph.nodes(graph).get(0)));
        other.put("id","fee");other.put("nameEn","Fee");other.remove("definitionReview");DraftReviewGraph.state(graph).put("variables",Arrays.asList(DraftReviewGraph.nodes(graph).get(0),other));
        String first=String.valueOf(service.save(project,graph,null).get("id"));assertThrows(BizException.class,()->service.confirmDefinitions(project,first,null,"All",true));
        Map<String,Object> groupA=service.confirmDefinitions(project,first,null,"Duration checked",true,Collections.singletonList("duration"));String a=String.valueOf(groupA.get("id"));
        assertEquals(Collections.singletonList("duration"),DraftReviewGraph.list(DraftReviewGraph.map(groupA.get("definitionGraphReview")).get("confirmedFieldIds")));
        when(ai.complete(anyString(),anyString())).thenReturn(response("42",quote));service.extract(project,a);
        Map<String,Object> next=(Map<String,Object>)service.revision(project,a).get("graph");DraftReviewGraph.nodes(next).get(1).put("definitionReview",map("confirmed",true,"basis",null));
        Map<String,Object> saved=service.save(project,next,null,a,true);String savedId=String.valueOf(saved.get("id"));assertEquals(Collections.singletonList("duration"),DraftReviewGraph.list(DraftReviewGraph.map(saved.get("definitionGraphReview")).get("confirmedFieldIds")));
        String b=String.valueOf(service.confirmDefinitions(project,savedId,null,"Fee checked",true,Collections.singletonList("fee")).get("id"));
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{String prompt=call.getArgument(0);assertTrue(prompt.contains("\"id\":\"fee\""));assertFalse(prompt.contains("\"id\":\"duration\""));return JsonUtils.write(Collections.singletonList(map("key","fee","value",7,"sourceQuote",quote,"reason","Fee statement","confidence",0.99)));});
        service.extract(project,b);Map<String,Object> candidates=service.candidates(project,b);JsonNode rows=JsonUtils.mapper().valueToTree(candidates).path("variables");assertEquals("42",rows.get(0).path("candidates").get(0).path("value").asText());assertEquals("7",rows.get(1).path("candidates").get(0).path("value").asText());
        service.correctCandidate(project,b,"duration",map("expectedRunId",candidates.get("runId"),"candidateIndex",0,"value","43","sourceQuote",quote));
        Map<String,Object> changed=(Map<String,Object>)service.revision(project,b).get("graph");DraftReviewGraph.nodes(changed).get(1).put("correction",map("kind","rule","branches",Collections.singletonList(map("id","case","mode","all","clauses",Collections.singletonList(map("inputId","duration","operator","ge","value",30))))));
        Map<String,Object> changedRevision=service.save(project,changed,null,b,true);String changedId=String.valueOf(changedRevision.get("id"));Map<String,Object> review=DraftReviewGraph.map(changedRevision.get("definitionGraphReview"));
        assertEquals(Collections.singletonList("duration"),DraftReviewGraph.list(review.get("confirmedFieldIds")));assertEquals(Collections.singletonList("fee"),DraftReviewGraph.list(review.get("pendingFieldIds")));
        JsonNode after=JsonUtils.mapper().valueToTree(service.candidates(project,changedId));assertEquals("43",after.path("variables").get(0).path("candidates").get(0).path("value").asText());assertEquals(0,after.path("variables").get(1).path("candidates").size());assertEquals(1,after.path("unassignedVariables").size());
    }

    @Test void explicitGroupConfirmationCanApproveAnAlreadyAlignedDefinitionButNotAnUnreviewedProposal() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"pending","boolean"),field=DraftReviewGraph.nodes(graph).get(0);
        field.put("catalogAlignment",map("status","matched","confirmed",true,"baselineKey","periodAtLeast39Months","baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Human reviewed the source and fixed definition"));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));Map<String,Object> approved=service.confirmDefinitions(project,draft,null,"Reviewed as a group",true,Collections.singletonList("duration"));
        Map<String,Object> stamped=DraftReviewGraph.nodes((Map<String,Object>)approved.get("graph")).get(0);assertEquals(true,DraftReviewGraph.map(stamped.get("definitionReview")).get("confirmed"));assertTrue(String.valueOf(DraftReviewGraph.map(stamped.get("definitionReview")).get("basis")).startsWith("v1:"));
        Map<String,Object> unreviewed=(Map<String,Object>)approved.get("graph");Map<String,Object> proposal=DraftReviewGraph.nodes(unreviewed).get(0);proposal.remove("definitionReview");DraftReviewGraph.map(proposal.get("catalogAlignment")).put("confirmed",false);String next=String.valueOf(service.save(project,unreviewed,null,String.valueOf(approved.get("id")),true).get("id"));
        assertThrows(BizException.class,()->service.confirmDefinitions(project,next,null,"Not yet matched",true,Collections.singletonList("duration")));
    }

    @Test void groupConfirmationRequiresReferencedDefinitionsToBeConfirmedTogetherOrEarlier() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=withConfirmedDefinition(graph(template,"pending","number")),dependent=JsonUtils.readMap(JsonUtils.write(DraftReviewGraph.nodes(graph).get(0)));
        dependent.put("id","fee");dependent.put("correction",map("kind","rule","branches",Collections.singletonList(map("id","case","mode","all","clauses",Collections.singletonList(map("inputId","duration","operator","ge","value",30))))));
        DraftReviewGraph.state(graph).put("variables",Arrays.asList(DraftReviewGraph.nodes(graph).get(0),dependent));String draft=String.valueOf(service.save(project,graph,null).get("id"));
        assertTrue(assertThrows(BizException.class,()->service.confirmDefinitions(project,draft,null,"Fee only",true,Collections.singletonList("fee"))).getMessage().contains("GRAPH_DEFINITION_DEPENDENCY_REVIEW_REQUIRED"));
        Map<String,Object> approved=service.confirmDefinitions(project,draft,null,"Reviewed together",true,Arrays.asList("duration","fee"));assertEquals(2,DraftReviewGraph.list(DraftReviewGraph.map(approved.get("definitionGraphReview")).get("confirmedFieldIds")).size());
    }

    @Test void onlyExplicitlyConfirmedSavedDefinitionsCanExtractWhileTargetsRemainPending() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        Map<String,Object> graph=graph(template,"pending","number"),node=DraftReviewGraph.nodes(graph).get(0);
        node.put("editTargets",Collections.singletonList(map("id","pending-target","docId",template.getId().toString(),"text","____","action","check","needsRebind",true)));
        String first=String.valueOf(service.save(project,graph,null).get("id"));
        assertThrows(BizException.class,()->service.extract(project,first));verify(ai,never()).complete(anyString(),anyString());
        node.put("definitionReview",map("confirmed",true,"basis",null));String second=approvedDraft(project,String.valueOf(service.save(project,graph,null,first,true).get("id")));
        Map<String,Object> saved=JsonUtils.readMap(revisions.findById(second).get().getGraphJson());assertTrue(String.valueOf(DraftReviewGraph.map(DraftReviewGraph.nodes(saved).get(0).get("definitionReview")).get("basis")).startsWith("v1:"));
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        List<VariableVO> result=service.extract(project,second);assertEquals("42",result.get(0).getCandidates().get(0).getValue());assertFalse(result.get(0).isConfirmed());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
        clearInvocations(ai);DraftReviewGraph.nodes(saved).get(0).put("nameEn","Different meaning");String changed=String.valueOf(service.save(project,saved,null,second,true).get("id"));
        assertThrows(BizException.class,()->service.extract(project,changed));verify(ai,never()).complete(anyString(),anyString());
        JsonNode old=JsonUtils.mapper().valueToTree(service.candidates(project,changed));assertEquals("42",old.path("unassignedVariables").path(0).path("candidates").path(0).path("value").asText());
    }

    @Test void renamedMeaningKeepsManualValueButRequiresReview() throws Exception {
        String project=project();SourceDocument template=template(project);String confirmed=confirm(project,template,"number",null);
        service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Human supplied duration"));assertEquals(42,((Number)effective(project).get("duration")).intValue());
        Map<String,Object> changed=service.graph(project);DraftReviewGraph.nodes(changed).get(0).put("nameEn","Different business meaning");service.save(project,changed,confirmed);
        assertFalse(effective(project).containsKey("duration"),"Plan must not depend on a preceding variables GET to notice a changed meaning");VariableVO preserved=variable(project);assertEquals("42",preserved.getValue());assertTrue(preserved.isReviewRequired());assertFalse(effective(project).containsKey("duration"));verify(ai,never()).complete(anyString(),anyString());
    }
    @Test void templateChangedDuringExtractionCannotPublishCandidate() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{template.setTextContent("Template analysis changed during model call");sources.saveAndFlush(template);return response("42","The duration is 42 months.");});
        assertThrows(BizException.class,()->service.extract(project,draft));verify(ai,times(1)).complete(anyString(),anyString());JsonNode review=JsonUtils.mapper().valueToTree(service.candidates(project,draft));assertTrue(review.path("variables").get(0).path("candidates").isEmpty());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
    }
    @Test void projectedHistoricalManualAnswerStaysSeparateAndBlocksConflictingDerivedAction() throws Exception {
        String project=project();SourceDocument template=template(project);String confirmed=confirm(project,template,"boolean",null);
        service.updateVariable(project,"duration",patch(variable(project),"value","false","note","Existing human decision"));
        DraftVariable stored=values.findByProjectIdAndVarKey(project,"duration").get();stored.setSourceRef("Historical source wording retained separately");values.saveAndFlush(stored);
        Map<String,Object> graph=service.graph(project),local=DraftReviewGraph.nodes(graph).get(0),original=JsonUtils.readMap(JsonUtils.write(local));
        Map<String,Object> projection=map("branchValue","L10Pro","branchPolarity",true);original.put("catalogAlignment",map("proposedMatches",Collections.singletonList(map("baselineKey","electronicTendering","semanticStatus","uncertain","factProjection",projection))));
        Map<String,Object> correction=map("kind","rule","otherwise","needs_review","branches",Arrays.asList(map("id","projection-selected","mode","all","clauses",Collections.singletonList(map("inputId","issue","operator","eq","value","L10Pro")),"resultValue",true),map("id","projection-other","mode","all","clauses",Collections.singletonList(map("inputId","issue","operator","eq","value","Hardcopy")),"resultValue",false)));
        local.put("role",map("kind","derived"));local.put("correction",correction);local.put("factProjectionReview",map("rootId","issue","baselineKey","electronicTendering","branchValue","L10Pro","branchPolarity",true,"exhaustiveConfirmed",true,"original",original,"convertedCorrection",correction));local.put("editTargets",Collections.singletonList(map("id","local-target","docId",template.getId().toString(),"text","____","action","delete","branchId","projection-selected")));
        Map<String,Object> root=JsonUtils.readMap(JsonUtils.write(original));root.put("id","issue");root.put("nameEn","Issue format");root.put("type",map("kind","choice"));root.put("options",DraftBusinessRules.fields().stream().filter(field->"electronicTendering".equals(field.get("key"))).findFirst().get().get("options"));root.put("catalogAlignment",map("status","matched","baselineKey","electronicTendering","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human confirms the shared fact"));
        DraftReviewGraph.state(graph).put("variables",Arrays.asList(root,local));String draft=String.valueOf(service.save(project,graph,confirmed).get("id"));service.confirm(project,draft,confirmed);
        VariableVO issue=service.variables(project).stream().filter(value->"issue".equals(value.getKey())).findFirst().get();service.updateVariable(project,"issue",patch(issue,"value","L10Pro","note","Human selected electronic issue"));
        Map<String,Object> plan=service.plan(project,null);Map<String,Object> history=DraftReviewGraph.map(DraftReviewGraph.map(plan.get("historicalProjectionValues")).get("duration"));assertEquals(false,history.get("value"));assertFalse(DraftReviewGraph.map(plan.get("effectiveValues")).containsKey("duration"));assertFalse(DraftReviewGraph.map(plan.get("derived")).containsKey("duration"));assertTrue(JsonUtils.write(plan.get("unresolved")).contains("HistoricalProjectionAnswerConflict"));assertFalse(JsonUtils.write(plan.get("historicalProjectionValues")).contains("sourceRef"));assertEquals("Historical source wording retained separately",values.findByProjectIdAndVarKey(project,"duration").get().getSourceRef());
        String working=String.valueOf(service.current(project).get("workingRevisionId"));Map<String,Object> details=service.historicalProjectionSources(project,working,"duration");assertEquals("manual_unbound",details.get("sourceStatus"));assertEquals(history.get("evidenceFingerprint"),details.get("evidenceFingerprint"));assertThrows(BizException.class,()->service.historicalProjectionSources(project,"obsolete","duration"));
        VariableVO override=service.variables(project).stream().filter(value->"targetOverrides".equals(value.getKey())).findFirst().get();service.updateVariable(project,"targetOverrides",patch(override,"value",JsonUtils.write(Collections.singletonList(map("actionId","local-target","action","delete"))),"note","Manual target override"));
        Map<String,Object> overridden=service.plan(project,null);Map<String,Object> action=DraftReviewGraph.map(DraftReviewGraph.list(overridden.get("actions")).get(0));assertEquals("pending",action.get("action"));assertTrue(Boolean.TRUE.equals(action.get("historicalProjectionBlocked")));assertTrue(JsonUtils.write(overridden.get("unresolved")).contains("HistoricalProjectionAnswerConflict"));
        Map<String,Object> reread=service.graph(project);Map<String,Object> review=DraftReviewGraph.map(DraftReviewGraph.nodes(reread).get(1).get("factProjectionReview"));review.put("historicalAnswerResolution",map("confirmed",true,"decision","use_derived","rootValue","L10Pro","oldValue",false,"evidenceFingerprint",details.get("evidenceFingerprint"),"reason","Human reviewed the retained answer"));String next=String.valueOf(service.save(project,reread,working).get("id"));service.confirm(project,next,working);
        assertFalse(JsonUtils.write(service.plan(project,null).get("unresolved")).contains("HistoricalProjectionAnswerConflict"));DraftVariable prior=values.findByProjectIdAndVarKey(project,"duration").get();prior.setSourceRef("Changed historical source detail");values.saveAndFlush(prior);Map<String,Object> changed=service.plan(project,null);assertTrue(JsonUtils.write(changed.get("unresolved")).contains("HistoricalProjectionAnswerConflict"));assertNotEquals(details.get("evidenceFingerprint"),DraftReviewGraph.map(DraftReviewGraph.map(changed.get("historicalProjectionValues")).get("duration")).get("evidenceFingerprint"));
        String quote="The previous human answer is false.";SourceDocument evidence=correspondence(project,quote);CandidateVO historical=new CandidateVO("false",evidence.getId(),evidence.getFileName(),DraftAdoption.sourceHash(evidence),quote,"Originally adopted source",0.99);historical.setOriginalSha256(evidence.getSourceSha256());prior.setAdoptedSourcesJson(JsonUtils.write(Collections.singletonList(historical)));prior.setManuallyEdited(false);values.saveAndFlush(prior);
        String latest=String.valueOf(service.current(project).get("workingRevisionId"));Map<String,Object> bound=service.historicalProjectionSources(project,latest,"duration");assertEquals("bound_sources",bound.get("sourceStatus"));assertEquals(quote,DraftReviewGraph.map(DraftReviewGraph.list(bound.get("sources")).get(0)).get("sourceQuote"));
        evidence.setTextContent("Changed correspondence.");sources.saveAndFlush(evidence);Map<String,Object> stale=service.historicalProjectionSources(project,latest,"duration");assertEquals("sources_need_review",stale.get("sourceStatus"));assertEquals(quote,DraftReviewGraph.map(DraftReviewGraph.list(stale.get("sources")).get(0)).get("sourceQuote"));assertNotEquals(bound.get("evidenceFingerprint"),stale.get("evidenceFingerprint"));
    }

    @Test void editorialSuggestionsRequireExplicitBusinessClassificationBeforeExtraction() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        Map<String,Object> graph=graph(template,"pending","number"),node=DraftReviewGraph.nodes(graph).get(0);
        node.put("editorialSuggestion",map("kind","editorial"));node.put("definitionReview",map("confirmed",true,"basis",null));
        String first=String.valueOf(service.save(project,graph,null).get("id"));assertThrows(BizException.class,()->service.extract(project,first));verify(ai,never()).complete(anyString(),anyString());
        node.put("editorialReview",map("kind","business_value","confirmed",true));node.put("definitionReview",map("confirmed",true,"basis",null));
        String next=approvedDraft(project,String.valueOf(service.save(project,graph,null,first,true).get("id")));when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        assertEquals("42",service.extract(project,next).get(0).getCandidates().get(0).getValue());
    }

    @Test void nestedDefinitionsRequireBothParentAndChildrenAndInvalidateOnChildChange() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        Map<String,Object> graph=graph(template,"pending","number"),original=DraftReviewGraph.nodes(graph).get(0);
        Map<String,Object> parent=JsonUtils.readMap(JsonUtils.write(original)),child=JsonUtils.readMap(JsonUtils.write(original));
        original.put("definitionReview",map("confirmed",true,"basis",null));parent.put("id","records");parent.put("nameEn","Schedule records");parent.put("type",map("kind","list"));parent.put("definitionReview",map("confirmed",true,"basis",null));
        child.put("id","quantity");child.put("nameEn","Quantity");child.put("parentId","records");
        DraftReviewGraph.state(graph).put("variables",Arrays.asList(original,parent,child));
        String first=String.valueOf(service.save(project,graph,null).get("id"));when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        assertThrows(BizException.class,()->service.extract(project,first));assertThrows(BizException.class,()->service.confirmDefinitions(project,first,null,"Test-only review",true));verify(ai,never()).complete(anyString(),anyString());
        Map<String,Object> saved=JsonUtils.readMap(revisions.findById(first).get().getGraphJson());DraftReviewGraph.nodes(saved).get(2).put("definitionReview",map("confirmed",true,"basis",null));
        String second=approvedDraft(project,String.valueOf(service.save(project,saved,null,first,true).get("id")));clearInvocations(ai);when(ai.complete(anyString(),anyString())).thenReturn("[]");service.extract(project,second);
        verify(ai).complete(argThat(prompt->prompt.contains("\"id\":\"records\"")&&prompt.contains("\"id\":\"quantity\"")),anyString());
        Map<String,Object> changed=JsonUtils.readMap(revisions.findById(second).get().getGraphJson());DraftReviewGraph.nodes(changed).get(2).put("nameEn","Changed quantity");String third=String.valueOf(service.save(project,changed,null,second,true).get("id"));
        clearInvocations(ai);assertThrows(BizException.class,()->service.extract(project,third));verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void confirmedBillMappingReusesFixedSourceValidationWithoutConfusingNamespaces() throws Exception {
        String project=project();SourceDocument template=template(project);String source="Use the following complete formal Bill schedule of seven rows. BQ and SOR have separate number series.\n\nPricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)\nSOR | 1 | Ventilation Plant and Ductwork\nBQ | 2 | Groundworks\nBQ | 3 | Structure\nBQ | 4 | Facades\nBQ | 5 | Services\nBQ | 6 | External Works";correspondence(project,source);
        Map<String,Object> graph=graph(template,"pending","number"),root=DraftReviewGraph.nodes(graph).get(0);root.put("id","atomic-bills");root.put("type",map("kind","list"));root.put("catalogAlignment",map("status","matched","baselineKey","billNos","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human review"));root.put("definitionReview",map("confirmed",true,"basis",null));
        List<Object> nodes=new ArrayList<>();nodes.add(root);for(String key:Arrays.asList("number","description","type")){
            Map<String,Object> child=JsonUtils.readMap(JsonUtils.write(root));child.put("id","atomic-"+key);child.put("parentId","atomic-bills");child.put("nameEn",key);child.put("type",map("kind","type".equals(key)?"choice":"text"));child.put("optional","type".equals(key));
            child.put("options","type".equals(key)?Arrays.asList(map("value","BQ"),map("value","SOR")):Collections.emptyList());child.put("catalogAlignment",map("status","matched","baselineKey","billNos."+key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human review"));nodes.add(child);
        }DraftReviewGraph.state(graph).put("variables",nodes);String revision=approvedDraft(project,String.valueOf(service.save(project,graph,null).get("id")));
        List<Object> rows=Arrays.asList(map("atomic-number","1","atomic-description","Building Works (All Provisional)","atomic-type","BQ"),map("atomic-number","1","atomic-description","Ventilation Plant and Ductwork","atomic-type","SOR"),map("atomic-number","2","atomic-description","Groundworks","atomic-type","BQ"),map("atomic-number","3","atomic-description","Structure","atomic-type","BQ"),map("atomic-number","4","atomic-description","Facades","atomic-type","BQ"),map("atomic-number","5","atomic-description","Services","atomic-type","BQ"),map("atomic-number","6","atomic-description","External Works","atomic-type","BQ"));
        when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(Collections.singletonList(map("key","atomic-bills","value",rows,"sourceQuote",source,"reason","Exact source table","confidence",0.99))));
        List<VariableVO> result=service.extract(project,revision);JsonNode items=JsonUtils.parse(result.get(0).getCandidates().get(0).getValue());assertEquals(7,items.size());assertEquals("BQ",items.get(0).path("atomic-type").asText());assertEquals("SOR",items.get(1).path("atomic-type").asText());assertNotEquals(items.get(0).path("_itemId").asText(),items.get(1).path("_itemId").asText());assertEquals("Building Works (All Provisional)",items.get(0).path("atomic-description").asText());assertFalse(result.get(0).isConfirmed());
        List<Object> wrong=new ArrayList<>(rows);wrong.set(0,map("atomic-number","1","atomic-description","Building Works (All Provisional)","atomic-type","SOR"));
        when(ai.complete(anyString(),anyString())).thenReturn(JsonUtils.write(Collections.singletonList(map("key","atomic-bills","value",wrong,"sourceQuote",source,"reason","Unsupported type","confidence",0.99))));
        assertTrue(service.extract(project,revision).get(0).getCandidates().isEmpty(),"A type copied from another numbered row is not accepted");
    }
    @Test void compositeBillRequiresExplicitCurrentSnapshotAndRetainsEachSource() throws Exception {
        String project=project();SourceDocument template=template(project);
        String firstText="Complete Bill schedule for this source.\n\nPricing type | Bill No. | Formal description\nBQ | 1 | Building Works (All Provisional)",secondText="Complete Bill schedule for this source.\n\nPricing type | Bill No. | Formal description\nSOR | 1 | Ventilation Plant and Ductwork";
        SourceDocument first=correspondence(project,firstText),second=correspondence(project,secondText);String revision=approvedDraft(project,String.valueOf(service.save(project,confirmedBillGraph(template),null).get("id")));
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{String prompt=call.getArgument(1);boolean bq=prompt.contains("Building Works");String quote=bq?firstText:secondText;return JsonUtils.write(Collections.singletonList(map("key","atomic-bills","value",Collections.singletonList(map("atomic-number","1","atomic-description",bq?"Building Works (All Provisional)":"Ventilation Plant and Ductwork","atomic-type",bq?"BQ":"SOR")),"sourceQuote",quote,"reason","Complete source table","confidence",0.99)));});
        service.extract(project,revision);VariableVO variable=service.variables(project).stream().filter(item->"atomic-bills".equals(item.getKey())).findFirst().get();assertFalse(variable.isConfirmed());
        Map<String,Object> review=service.candidates(project,revision);VariableVO candidate=((List<VariableVO>)review.get("variables")).stream().filter(item->"atomic-bills".equals(item.getKey())).findFirst().get();CompositeBillSuggestion combined=candidate.getCompositeBillSuggestion();assertNotNull(combined);assertFalse(combined.isPartial());JsonNode rows=JsonUtils.parse(combined.getValue());assertEquals(2,rows.size());assertEquals("BQ",rows.get(0).path("atomic-type").asText());assertEquals("SOR",rows.get(1).path("atomic-type").asText());assertNotEquals(rows.get(0).path("_itemId").asText(),rows.get(1).path("_itemId").asText());
        Map<String,Object> request=map("runId",combined.getRunId(),"memberIndexes",combined.getMemberIndexes(),"mergeHash",combined.getMergeHash(),"expectedValue",variable.getValue(),"expectedConfirmed",variable.isConfirmed(),"expectedManuallyEdited",variable.isManuallyEdited(),"expectedReviewRequired",variable.isReviewRequired());
        VariableVO displayed=variable;Map<String,Object> wrong=new LinkedHashMap<>(request);wrong.put("mergeHash","invalid");assertThrows(BizException.class,()->service.updateVariable(project,"atomic-bills",patch(displayed,"compositeBill",wrong)));assertFalse(values.findByProjectIdAndVarKey(project,"atomic-bills").get().getConfirmed());
        service.updateVariable(project,"atomic-bills",patch(displayed,"value","[]","note","Human changed this value while reviewing"));assertThrows(BizException.class,()->service.updateVariable(project,"atomic-bills",patch(displayed,"compositeBill",request)));
        variable=service.variables(project).stream().filter(item->"atomic-bills".equals(item.getKey())).findFirst().get();request.put("expectedValue",variable.getValue());request.put("expectedConfirmed",variable.isConfirmed());request.put("expectedManuallyEdited",variable.isManuallyEdited());request.put("expectedReviewRequired",variable.isReviewRequired());
        VariableVO adopted=service.updateVariable(project,"atomic-bills",patch(variable,"compositeBill",request));assertTrue(adopted.isConfirmed());assertEquals(combined.getValue(),adopted.getValue());DraftVariable stored=values.findByProjectIdAndVarKey(project,"atomic-bills").get();assertNull(stored.getSourceRef());List<CandidateVO> sources=DraftAdoption.adoptedSources(stored);assertEquals(2,sources.size());assertEquals(new HashSet<>(Arrays.asList(first.getId(),second.getId())),new HashSet<>(Arrays.asList(sources.get(0).getSourceDocumentId(),sources.get(1).getSourceDocumentId())));assertEquals(firstText,sources.get(0).getSourceQuote());assertEquals(secondText,sources.get(1).getSourceQuote());
        first.setTextContent("Changed source after adoption");this.sources.saveAndFlush(first);assertFalse(effective(project).containsKey("atomic-bills"));assertEquals(combined.getValue(),values.findByProjectIdAndVarKey(project,"atomic-bills").get().getValueText());
    }
    @Test void revisionReadReportsObservationIntegrityWithoutChangingStoredGraphOrBlockingSave() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> proposed=graph(template,"confirmed","text"),state=DraftReviewGraph.state(proposed);
        state.put("requirements",Arrays.asList(map("id","req-1","sourceQuoteEvidence",map("sourceScopeObservationRef","bad")),map("id","req-2","sourceQuoteEvidence",map("sourceScopeObservationRef","absent"))));state.put("sourceScopeObservations",map("bad",map("text","retained evidence")));
        Map<String,Object> saved=service.save(project,proposed,null),loaded=service.revision(project,String.valueOf(saved.get("id")));assertEquals(map("bad","invalid","absent","missing"),loaded.get("scopeObservationIntegrity"));
        Map<String,Object> loadedState=DraftReviewGraph.state((Map<String,Object>)loaded.get("graph"));assertEquals(JsonUtils.write(state.get("requirements")),JsonUtils.write(loadedState.get("requirements")));assertEquals(JsonUtils.write(state.get("sourceScopeObservations")),JsonUtils.write(loadedState.get("sourceScopeObservations")));
        Map<String,Object> next=service.save(project,(Map<String,Object>)loaded.get("graph"),null);assertEquals(map("bad","invalid","absent","missing"),next.get("scopeObservationIntegrity"));
    }
    @Test void wholeGraphConfirmationKeepsUnreviewedSourceChecksButStillRequiresInputAlignmentReview() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> proposed=graph(template,"confirmed","number"),input=DraftReviewGraph.nodes(proposed).get(0);
        Map<String,Object> verification=JsonUtils.readMap(JsonUtils.write(input));verification.put("id","verify-source");verification.put("role",map("kind","verification"));verification.put("catalogAlignment",map("status","matched","baselineKey","contractPeriodMonths","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",false));
        Map<String,Object> seed=JsonUtils.readMap(JsonUtils.write(input));seed.put("id","source-seed");seed.put("definitionKind","source_seed");seed.put("catalogAlignment",map("status","matched","baselineKey","contractPeriodMonths","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",false));DraftReviewGraph.state(proposed).put("variables",Arrays.asList(input,verification,seed));
        Map<String,Object> draft=service.save(project,proposed,null);VariableVO prior=variable(project);service.updateVariable(project,"duration",patch(prior,"value","42","note","Manual source review"));Map<String,Object> confirmed=service.confirm(project,String.valueOf(draft.get("id")),null);
        List<Map<String,Object>> nodes=DraftReviewGraph.nodes((Map<String,Object>)confirmed.get("graph"));assertEquals(false,((Map<?,?>)nodes.get(1).get("catalogAlignment")).get("confirmed"));assertEquals(false,((Map<?,?>)nodes.get(2).get("catalogAlignment")).get("confirmed"));assertEquals("42",variable(project).getValue());
        String second=project();SourceDocument other=template(second);Map<String,Object> unreviewed=graph(other,"confirmed","number");DraftReviewGraph.nodes(unreviewed).get(0).put("catalogAlignment",map("status","matched","baselineKey","contractPeriodMonths","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",false));Map<String,Object> pending=service.save(second,unreviewed,null);BizException error=assertThrows(BizException.class,()->service.confirm(second,String.valueOf(pending.get("id")),null));assertTrue(error.getMessage().contains("GRAPH_CATALOG_REVIEW_INCOMPLETE"));
    }
    @Test void waterproofingRecallPreservesPartialAndFullNineAreaCandidatesWithoutAdopting() throws Exception {
        String project=project();SourceDocument template=template(project);String list="1. Roof decks;\n2. Balconies;\n3. Kitchens;\n4. Bathrooms;\n5. Plant rooms and water tanks;\n   including the adjoining pump chambers;\n6. Lift pits;\n7. External walls;\n8. Podium slabs;\n   including all perimeter upstands;\n9. Basement slabs.";String source="Other waterproofing areas specified in Specification:\n"+list;correspondence(project,source);String revision=approvedDraft(project,String.valueOf(service.save(project,confirmedWaterproofGraph(template),null).get("id")));
        String partial="1. Roof decks;\n2. Balconies;",shortQuote="1. Roof decks;\n2. Balconies;";
        String partialResponse=JsonUtils.write(Collections.singletonList(map("key","waterproof-areas","value",partial,"sourceQuote",shortQuote,"reason","Partial model candidate","confidence",0.99)));
        String completeResponse=JsonUtils.write(Collections.singletonList(map("key","waterproof-areas","value",list,"sourceQuote",source,"reason","Complete source list","confidence",0.99)));
        when(ai.complete(anyString(),anyString())).thenReturn(partialResponse,completeResponse);List<VariableVO> result=service.extract(project,revision);verify(ai,times(2)).complete(anyString(),anyString());
        assertEquals(2,result.get(0).getCandidates().size());CandidateVO first=result.get(0).getCandidates().get(0),second=result.get(0).getCandidates().get(1);assertEquals("needs_review",first.getCompletenessStatus());assertEquals(partial,first.getValue());assertEquals("complete",second.getCompletenessStatus(),String.valueOf(second.getCompletenessIssues()));assertEquals(list,second.getValue());assertTrue(second.getValue().contains("including the adjoining pump chambers"));assertTrue(second.getValue().contains("including all perimeter upstands"));assertFalse(result.get(0).isConfirmed());assertFalse(values.findByProjectIdAndVarKey(project,"waterproof-areas").isPresent());
        VariableVO editable=service.variables(project).stream().filter(value->"waterproof-areas".equals(value.getKey())).findFirst().get();assertThrows(BizException.class,()->service.updateVariable(project,"waterproof-areas",patch(editable,"candidateIndex",0,"candidateSnapshot",editable.getCandidates().get(0))));
        VariableVO adopted=service.updateVariable(project,"waterproof-areas",patch(editable,"candidateIndex",1,"candidateSnapshot",editable.getCandidates().get(1)));assertTrue(adopted.isConfirmed());assertEquals(list,adopted.getValue());
    }
    @Test void waterproofingOmissionTriggersOneBoundedRecallOnlyWhenSourceHasIdentifiableScope() throws Exception {
        String project=project();SourceDocument template=template(project);String list="1. Roof decks;\n2. Balconies;\n3. Kitchens;\n4. Bathrooms;\n5. Plant rooms;\n   Continue around the pumps;\n6. Lift pits;\n7. External walls;\n8. Podium slabs;\n   Return around the upstands;\n9. Basement slabs.";String source="The complete specification list of waterproofing areas:\n"+list;correspondence(project,source);String revision=approvedDraft(project,String.valueOf(service.save(project,confirmedWaterproofGraph(template),null).get("id")));
        String complete=JsonUtils.write(Collections.singletonList(map("key","waterproof-areas","value",list,"sourceQuote",source,"reason","Full list","confidence",0.99)));when(ai.complete(anyString(),anyString())).thenReturn("[]",complete);
        List<VariableVO> result=service.extract(project,revision);verify(ai,times(2)).complete(anyString(),anyString());assertEquals(1,result.get(0).getCandidates().size());assertEquals("complete",result.get(0).getCandidates().get(0).getCompletenessStatus());assertFalse(result.get(0).isConfirmed());
    }
    @Test void waterproofingRepeatedOmissionReportsPartialWithoutInventingCandidate() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The complete specification list of waterproofing areas:\n1. Roof decks;\n2. Balconies.");String revision=approvedDraft(project,String.valueOf(service.save(project,confirmedWaterproofGraph(template),null).get("id")));when(ai.complete(anyString(),anyString())).thenReturn("[]");
        List<VariableVO> result=service.extract(project,revision);verify(ai,times(2)).complete(anyString(),anyString());assertTrue(result.get(0).getCandidates().isEmpty());assertEquals("",result.get(0).getValue());assertFalse(result.get(0).isConfirmed());JsonNode trace=JsonUtils.parse(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).orElseThrow().getTraceJson());assertEquals("partial",trace.path("status").asText());assertTrue(trace.path("decisions").toString().contains("waterproof_scope_unanswered"));
    }
    @Test void waterproofingWithoutUniqueIdentifiableScopeRemainsEditableButCannotBeAdoptedAsComplete() throws Exception {
        String project=project();SourceDocument template=template(project);String source="Areas:\n1. Roof decks;\n2. Balconies.";correspondence(project,source);String revision=approvedDraft(project,String.valueOf(service.save(project,confirmedWaterproofGraph(template),null).get("id")));String raw=JsonUtils.write(Collections.singletonList(map("key","waterproof-areas","value","1. Roof decks;\n2. Balconies.","sourceQuote",source,"reason","Needs context","confidence",0.99)));when(ai.complete(anyString(),anyString())).thenReturn(raw);
        List<VariableVO> result=service.extract(project,revision);assertEquals("needs_review",result.get(0).getCandidates().get(0).getCompletenessStatus());assertEquals("",result.get(0).getValue());verify(ai,times(2)).complete(anyString(),anyString());
        VariableVO field=service.variables(project).stream().filter(value->"waterproof-areas".equals(value.getKey())).findFirst().get();assertThrows(BizException.class,()->service.updateVariable(project,"waterproof-areas",patch(field,"candidateIndex",0,"candidateSnapshot",field.getCandidates().get(0))));VariableVO manual=service.updateVariable(project,"waterproof-areas",patch(field,"value","Human-completed area wording","note","Reviewed the source and completed the wording"));assertTrue(manual.isConfirmed());assertEquals("Human-completed area wording",manual.getValue());
    }

    @Test void selectedSccPdfAndWordRemainSeparateSourceIdentities() throws Exception {
        String project=project();byte[] wordBytes,pdfBytes;
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("Duration: ____ months.");word.write(out);wordBytes=out.toByteArray();}
        try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(out);pdfBytes=out.toByteArray();}
        SourceDocument word=source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","SCC-word.docx",wordBytes,"Duration: ____ months."),pdf=source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,"SCC","SCC-pdf.pdf",pdfBytes,"Duration: ____ months.");correspondence(project,"The duration is 42 months.");
        Map<String,Object> graph=graph(word,"pending","number"),node=DraftReviewGraph.nodes(graph).get(0);
        graph.put("selectedDocuments",map("SCC",word.getId().toString()));node.put("docIds",Arrays.asList(word.getId().toString(),pdf.getId().toString()));node.put("sourceAnchors",Collections.singletonList(map("docId",pdf.getId().toString(),"text","Duration: ____ months.")));node.put("definitionReview",map("confirmed",true,"basis",null));
        assertThrows(BizException.class,()->service.save(project,graph,null));node.put("docIds",Collections.singletonList(pdf.getId().toString()));
        String first=String.valueOf(service.save(project,graph,null).get("id"));assertThrows(BizException.class,()->service.extract(project,first));verify(ai,never()).complete(anyString(),anyString());
        graph.put("selectedDocuments",map("SCC",pdf.getId().toString()));node.put("docIds",Collections.singletonList(pdf.getId().toString()));node.put("definitionReview",map("confirmed",true,"basis",null));
        String second=approvedDraft(project,String.valueOf(service.save(project,graph,null,first,true).get("id")));when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));assertEquals("42",service.extract(project,second).get(0).getValue());
        JsonNode saved=JsonUtils.mapper().valueToTree(service.revision(project,second));assertEquals(pdf.getId().toString(),saved.path("graph").path("selectedDocuments").path("SCC").asText());
        assertNotEquals(word.getSourceSha256(),pdf.getSourceSha256());assertTrue(saved.toString().contains(word.getSourceSha256()));assertTrue(saved.toString().contains(pdf.getSourceSha256()));
    }

    @Test void manualDefinitionWithoutTemplateSourceCanPersistConfirmAdoptAndInvalidateOnScopeChange() {
        String project=project();Map<String,Object> node=map("id","duration","nameEn","Duration","type",map("kind","number","unit","months"),"role",map("kind","input"),"status","confirmed","optional",true,
                "definitionOrigin",map("kind","manual","reason","User supplied a missing project input"),"sourceAnchors",Collections.emptyList(),"editTargets",Collections.emptyList(),"docIds",Collections.emptyList(),
                "catalogAlignment",map("status","matched","baselineKey","contractPeriodMonths","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",true,"reason","Human confirmed the duration in months"));
        Map<String,Object> graph=map("graph",map("variables",Collections.singletonList(node)));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));assertNotNull(service.revision(project,draft).get("graph"));
        String confirmed=String.valueOf(service.confirm(project,draft,null).get("id"));VariableVO empty=variable(project);assertFalse(empty.isConfirmed());assertTrue(effective(project).isEmpty());
        service.updateVariable(project,"duration",patch(empty,"value","39","note","Human project decision"));assertEquals(new java.math.BigDecimal("39"),effective(project).get("duration"));
        Map<String,Object> changed=service.graph(project);DraftReviewGraph.nodes(changed).get(0).put("definitionScope","Separate building contract");
        String next=String.valueOf(service.save(project,changed,confirmed,draft,true).get("id"));VariableVO preserved=variable(project);assertEquals("39",preserved.getValue());assertTrue(preserved.isReviewRequired());assertTrue(effective(project).isEmpty());
        assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(empty,"value","40","note","Old screen")));
        assertEquals(confirmed,service.current(project).get("currentRevisionId"));assertEquals(next,service.current(project).get("draftRevisionId"));verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void manualDefinitionCannotBypassTemplateSourceRequirementForRulesOrEditTargets() {
        String project=project();Map<String,Object> node=map("id","duration","nameEn","Duration","type",map("kind","number"),"role",map("kind","input"),"status","confirmed",
                "definitionOrigin",map("kind","manual","reason","Human definition"),"sourceAnchors",Collections.emptyList(),"docIds",Collections.singletonList("missing-source"),
                "editTargets",Collections.singletonList(map("id","target","docId","missing-source","action","keep","text","Keep exact original")));
        String draft=String.valueOf(service.save(project,map("graph",map("variables",Collections.singletonList(node))),null).get("id"));
        assertTrue(assertThrows(BizException.class,()->service.confirm(project,draft,null)).getMessage().contains("GRAPH_SOURCE_PASSAGE_REQUIRED"));
        assertTrue(DraftReviewGraph.list(service.plan(project,null).get("unresolved")).stream().anyMatch(raw->String.valueOf(DraftReviewGraph.map(raw).get("id")).startsWith("source_review:")));verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void catalogAlignmentRetainsOldCandidateEvidenceUntilExplicitReview() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        String first=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));service.extract(project,first);clearInvocations(ai);
        Map<String,Object> changed=service.graph(project),node=DraftReviewGraph.nodes(changed).get(0);node.put("optional",true);((Map<String,Object>)node.get("type")).put("unit","months");
        node.put("catalogAlignment",map("status","matched","baselineKey","contractPeriodMonths","baselineVersion",DraftBusinessRules.RULE_VERSION,"confirmed",false,"reason","Awaiting human review"));
        String second=String.valueOf(service.save(project,changed,null,first,true).get("id"));JsonNode candidates=JsonUtils.mapper().valueToTree(service.candidates(project,second));
        assertTrue(candidates.path("variables").get(0).path("candidates").isEmpty());assertEquals("42",candidates.path("unassignedVariables").get(0).path("candidates").get(0).path("value").asText());
        node.put("status","confirmed");String pending=String.valueOf(service.save(project,changed,null,second,true).get("id"));
        assertTrue(assertThrows(BizException.class,()->service.confirm(project,pending,null)).getMessage().contains("GRAPH_CATALOG_REVIEW_INCOMPLETE"));verifyNoInteractions(ai);
    }

    @Test void currentDraftCanHoldAdoptedAnswersWithoutConfirmingItsRulesOrReplacingConfirmedHistory()throws Exception {
        String project=project();SourceDocument template=template(project);
        String draft=String.valueOf(service.save(project,graph(template,"pending","number"),null).get("id"));
        assertEquals(draft,service.catalog(project).get("graphRevisionId"));assertEquals("DRAFT",service.catalog(project).get("reviewStatus"));
        VariableVO field=service.variables(project).stream().filter(v->"duration".equals(v.getKey())).findFirst().get();
        service.updateVariable(project,"duration",patch(field,"value","42","note","Explicit synthetic test adoption"));
        Map<String,Object> pending=service.plan(project,null);assertFalse(DraftReviewGraph.map(pending.get("effectiveValues")).containsKey("duration"));
        assertTrue(DraftReviewGraph.list(pending.get("unresolved")).stream().anyMatch(x->"NodeReviewRequired".equals(DraftReviewGraph.map(x).get("kind"))));
        assertNull(graphHeads.findById(project).get().getCurrentRevisionId());assertEquals("pending",DraftReviewGraph.nodes(JsonUtils.readMap(revisions.findById(draft).get().getGraphJson())).get(0).get("status"));
        String reviewedDraft=String.valueOf(service.save(project,graph(template,"confirmed","number"),null,draft,true).get("id"));
        assertEquals(reviewedDraft,service.catalog(project).get("graphRevisionId"));assertTrue(service.plan(project,null).get("effectiveValues").toString().contains("42"));
        assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(field,"value","43","note","Stale revision")));
        String confirmed=String.valueOf(service.confirm(project,reviewedDraft,null).get("id"));assertEquals(confirmed,service.catalog(project).get("graphRevisionId"));assertEquals("CONFIRMED",service.catalog(project).get("reviewStatus"));
        String changed=String.valueOf(service.save(project,graph(template,"pending","number"),confirmed).get("id"));
        assertEquals(changed,service.catalog(project).get("graphRevisionId"));assertEquals(confirmed,graphHeads.findById(project).get().getCurrentRevisionId());
        assertTrue(service.variables(project).stream().filter(v->"duration".equals(v.getKey())).findFirst().get().isConfirmed());
        assertFalse(DraftReviewGraph.map(service.plan(project,null).get("effectiveValues")).containsKey("duration"));verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void automaticPreparationUsesSavedSourceAndPublishesOneSourceBoundDraft()throws Exception {
        String project=project();String instruction="(Delete if precast facade is not applicable)";SourceDocument template;
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("NTT1 Precast facade");word.createParagraph().createRun().setText(instruction);word.write(bytes);template=source(project,"STANDARD_TEMPLATE","NTT","NTT synthetic.docx",bytes.toByteArray(),"NTT1 Precast facade\n"+instruction);}
        DocumentParser.ParsedDocument parsed=documentParser.parseTemplate("NTT",template.getFileName(),storage.read(template.getStoragePath()));template.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));template.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));sources.saveAndFlush(template);List<JsonNode> readings=new ArrayList<>();
        Map<String,Object> base=service.automationInput(project,null,null,readings);assertEquals(1,readings.size());assertEquals("bound",readings.get(0).path("evidenceStatus").asText());
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);assertFalse(plan.tasks().isEmpty());when(ai.complete(anyString(),anyString())).thenReturn("{\"requirements\":[],\"nodes\":[]}");
        List<Map<String,Object>> receipts=new ArrayList<>();for(Map<String,Object> task:plan.tasks())receipts.add(DraftAutomaticDiscovery.process(ai,plan,task));
        Map<String,Object> saved=service.save(project,DraftAutomaticDiscovery.finish(base,plan,receipts),null,null,true);
        assertNotNull(saved.get("id"));assertEquals(1,revisions.findByProjectIdOrderByCreatedAtDesc(project).size());assertNull(graphHeads.findById(project).get().getCurrentRevisionId());
        assertEquals("completed",JsonUtils.mapper().valueToTree(saved).path("graph").path("graph").path("automaticDiscovery").path("scanStatus").asText());
    }

    @Test void newlyDiscoveredInputsRequireSeparateCatalogReviewBeforeConfirmationOrUse() throws Exception {
        String project=project(),instruction="(Delete if precast facade is not applicable)";SourceDocument template;
        try(XWPFDocument word=new XWPFDocument();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){word.createParagraph().createRun().setText("NTT1 Precast facade");word.createParagraph().createRun().setText(instruction);word.write(bytes);template=source(project,"STANDARD_TEMPLATE","NTT","NTT synthetic.docx",bytes.toByteArray(),"NTT1 Precast facade\n"+instruction);}
        DocumentParser.ParsedDocument parsed=documentParser.parseTemplate("NTT",template.getFileName(),storage.read(template.getStoragePath()));template.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));template.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));sources.saveAndFlush(template);
        List<JsonNode> readings=new ArrayList<>();Map<String,Object> graph=service.automationInput(project,null,null,readings);Map<String,Object> task=DraftReviewDiscovery.sourceTasks(readings).get(0);String quote=DraftReviewDiscovery.sourceTaskText(readings.get(0),task);
        JsonNode response=JsonUtils.mapper().valueToTree(map("nodes",Collections.singletonList(map("id","facade","nameEn","Facade applicable","type","boolean","role","input","sourceQuote",quote))));
        List<Map<String,Object>> discovered=DraftReviewDiscovery.sourceTaskNodes(readings.get(0),task,response,new ArrayList<>());assertEquals(1,discovered.size());Map<String,Object> node=discovered.get(0);String key=String.valueOf(node.get("id"));
        assertEquals("unreviewed",DraftReviewGraph.map(node.get("catalogAlignment")).get("status"));node.put("status","confirmed");DraftReviewGraph.state(graph).put("variables",discovered);
        String first=String.valueOf(service.save(project,graph,null).get("id"));VariableVO field=service.variables(project).stream().filter(value->key.equals(value.getKey())).findFirst().get();service.updateVariable(project,key,patch(field,"value","true","note","Explicit synthetic project value"));
        assertTrue(effective(project).isEmpty());assertTrue(assertThrows(BizException.class,()->service.confirm(project,first,null)).getMessage().contains("GRAPH_CATALOG_REVIEW_INCOMPLETE"));
        node.put("catalogAlignment",map("status","new","confirmed",true,"reason","Human compared the catalog and retained a distinct project definition"));
        String second=String.valueOf(service.save(project,graph,null,first,true).get("id"));service.confirm(project,second,null);assertTrue(effective(project).isEmpty());
        VariableVO retained=service.variables(project).stream().filter(value->key.equals(value.getKey())).findFirst().get();assertEquals("true",retained.getValue());assertTrue(retained.isReviewRequired());verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void stoppingCandidateExtractionRetainsEarlierSourceCheckedCandidatesWithoutAdoption()throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");correspondence(project,"The alternative duration is 43 months.");
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();service.extract(project,draft,new DraftTemplateGraphService.ExtractionMonitor(){public boolean beforeCall(String system,String user,String file){return calls.incrementAndGet()==1;}});
        JsonNode candidates=JsonUtils.mapper().valueToTree(service.candidates(project,draft));assertEquals("42",candidates.path("variables").get(0).path("candidates").get(0).path("value").asText());
        assertEquals("partial",JsonUtils.parse(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).get().getTraceJson()).path("status").asText());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());verify(ai,times(1)).complete(anyString(),anyString());
    }

    @Test void readingManyVariablesVerifiesTheirSharedSourceOncePerRequest()throws Exception {
        String project=project();SourceDocument template=template(project);confirmMany(project,template,24);VariableVO first=variable(project);service.updateVariable(project,"duration",patch(first,"value","42","note","Test-only adopted value remains unchanged by reads"));
        for(int request=0;request<2;request++){clearInvocations(reviewSources);List<VariableVO> result=service.variables(project);assertEquals(24,result.size());assertEquals("42",result.get(0).getValue());assertTrue(result.get(0).isConfirmed());assertFalse(result.get(0).isReviewRequired());verify(reviewSources,times(1)).archive(argThat(source->template.getId().equals(source.getId())));}
        assertEquals("42",values.findByProjectIdAndVarKey(project,"duration").orElseThrow().getValueText());verify(ai,never()).complete(anyString(),anyString());
    }

    @Test void sourceAndArchiveChangesAreRecheckedOnTheNextVariableReadAndBeforeWrites()throws Exception {
        for(boolean archive:new boolean[]{false,true}){
            String project=project();SourceDocument template=template(project);confirmMany(project,template,6);VariableVO first=variable(project);service.updateVariable(project,"duration",patch(first,"value","42","note","Test-only adoption before integrity checks"));VariableVO adopted=variable(project);assertFalse(adopted.isReviewRequired());
            String sourceHash=template.getSourceSha256();byte[] original=storage.read(template.getStoragePath());JsonNode manifest=reviewSources.archive(template);java.nio.file.Path changed=archive?java.nio.file.Paths.get(settings.getStorageRoot(),".draft-review-originals",manifest.path("archiveKey").asText()):java.nio.file.Paths.get(template.getStoragePath());byte[] before=java.nio.file.Files.readAllBytes(changed);
            try {java.nio.file.Files.write(changed,"TEST ONLY damaged original or archive".getBytes(StandardCharsets.UTF_8));List<VariableVO> stale=service.variables(project);assertTrue(stale.stream().allMatch(VariableVO::isReviewRequired));assertEquals("42",stale.get(0).getValue());assertTrue(stale.get(0).isConfirmed());assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(adopted,"value","43")));assertThrows(BizException.class,()->service.extract(project));verify(ai,never()).complete(anyString(),anyString());assertEquals("42",values.findByProjectIdAndVarKey(project,"duration").orElseThrow().getValueText());assertEquals(sourceHash,sources.findById(template.getId()).orElseThrow().getSourceSha256());}
            finally{java.nio.file.Files.write(changed,before);}
            assertFalse(variable(project).isReviewRequired());assertArrayEquals(original,storage.read(template.getStoragePath()));
        }
    }

    @Test void currentReadReusesOnlyCurrentSourceChecksAndStillChecksEveryRevisionManifest()throws Exception {
        String project=project();SourceDocument first=template(project);String confirmed=confirmMany(project,first,3);Map<String,Object> draft=service.save(project,manyFields(first,4),confirmed);
        clearInvocations(reviewSources);Map<String,Object> shared=service.current(project);assertEquals(false,((Map<?,?>)shared.get("current")).get("sourceStale"));assertEquals(false,((Map<?,?>)shared.get("draft")).get("sourceStale"));verify(reviewSources,times(1)).archive(any(SourceDocument.class));verify(reviewSources,times(2)).archivedBytes(any(JsonNode.class));
        SourceDocument second=template(project);draft=service.save(project,manyFields(second,4),confirmed);String draftId=String.valueOf(draft.get("id"));DraftTemplateGraphRevision row=revisions.findById(draftId).orElseThrow();String original=row.getSourcesJson();
        assertEquals(1,JsonUtils.parse(revisions.findById(confirmed).orElseThrow().getSourcesJson()).size());assertEquals(2,JsonUtils.parse(original).size());
        clearInvocations(reviewSources);service.current(project);verify(reviewSources,times(2)).archive(any(SourceDocument.class));verify(reviewSources,times(3)).archivedBytes(any(JsonNode.class));
        DraftTemplateGraphHead selected=graphHeads.findById(project).orElseThrow();
        try {for(String field:Arrays.asList("sourceSha256","sourceHash","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus","archiveKey")){JsonNode changed=JsonUtils.parse(original);((ObjectNode)changed.get(0)).put(field,"TEST ONLY changed "+field);String fixtureId=UUID.randomUUID().toString();revisions.saveAndFlush(new DraftTemplateGraphRevision(fixtureId,project,draftId,"DRAFT",row.getGraphSha256(),row.getGraphJson(),JsonUtils.write(changed),java.time.Instant.now()));selected.setDraftRevisionId(fixtureId);graphHeads.saveAndFlush(selected);Map<String,Object> current=service.current(project);assertEquals(false,((Map<?,?>)current.get("current")).get("sourceStale"),field);assertEquals(true,((Map<?,?>)current.get("draft")).get("sourceStale"),field);}}
        finally{selected.setDraftRevisionId(draftId);graphHeads.saveAndFlush(selected);}
        SourceDocument added=correspondence(project,"Test-only new source after the previous read.");List<?> listed=(List<?>)service.current(project).get("sources");assertTrue(listed.stream().map(value->(Map<?,?>)value).anyMatch(value->Objects.equals(String.valueOf(added.getId()),value.get("id"))));
    }

    @Test void aSourceChangeDuringTheModelCannotReuseAnEarlierReadOrOverwriteAdoptedValues()throws Exception {
        String project=project();SourceDocument template=template(project);confirmMany(project,template,4);VariableVO first=variable(project);service.updateVariable(project,"duration",patch(first,"value","42","note","Test-only adoption before the model changes source bytes"));correspondence(project,"The duration is 43 months.");service.variables(project);byte[] original=storage.read(template.getStoragePath());
        when(ai.complete(anyString(),anyString())).thenAnswer(call->{java.nio.file.Files.write(java.nio.file.Paths.get(template.getStoragePath()),"TEST ONLY source changed during the model".getBytes(StandardCharsets.UTF_8));return response("43","The duration is 43 months.");});
        try {assertThrows(BizException.class,()->service.extract(project));VariableVO duration=variable(project);assertEquals("42",duration.getValue());assertTrue(duration.isConfirmed());assertTrue(duration.isReviewRequired());assertTrue(((Map<?,?>)service.plan(project,null).get("effectiveValues")).isEmpty());assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(duration,"value","43")));assertEquals("42",values.findByProjectIdAndVarKey(project,"duration").orElseThrow().getValueText());}
        finally{java.nio.file.Files.write(java.nio.file.Paths.get(template.getStoragePath()),original);}
    }

    @Test void retainedQuestionCoreCannotConfirmUntilItsRejectedRulesAreManuallyRebound()throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> proposed=graph(template,"confirmed","boolean");
        Map<String,Object> node=DraftReviewGraph.nodes(proposed).get(0);node.put("discoveryCoreRetained",true);node.put("correction",map("kind","rule","branches",new ArrayList<>(),"inputIds",new ArrayList<>(),"needsRebind",true));
        Map<String,Object> draft=service.save(project,proposed,null);BizException rejected=assertThrows(BizException.class,()->service.confirm(project,String.valueOf(draft.get("id")),null));
        assertTrue(rejected.getMessage().contains("GRAPH_RULE_REBIND_REQUIRED"));assertNull(graphHeads.findById(project).orElseThrow().getCurrentRevisionId());assertTrue(values.findByProjectIdOrderBySortOrderAsc(project).isEmpty());
    }

    @Test void bootstrapExcludesBoundAuxiliaryPdfFromSourceManifestAndSeedsButKeepsItsReading()throws Exception {
        String project=project();SourceDocument word=guidanceTemplate(project,false),auxiliary=guidanceTemplate(project,true);saveGuidanceEvidence(auxiliary);
        JsonNode reading=service.reading(project,auxiliary.getId());assertEquals("bound",reading.path("evidenceStatus").asText());assertEquals("pdf",reading.path("format").asText());assertEquals(1,reading.path("guidanceNotes").size());assertEquals(auxiliary.getTextContent(),reading.path("text").asText());
        assertEquals(2,service.sources(project).size());
        JsonNode graph=JsonUtils.mapper().valueToTree(service.bootstrap(project).get("graph"));
        assertSelectedWordSourcesAndSeeds(graph,word);assertEquals(word.getId().toString(),graph.path("selectedDocuments").path("NTT").asText());verifyNoInteractions(ai);
    }

    @Test void auxiliaryEvidenceUpgradeCannotStaleSavedGraphOrEnterDiscovery()throws Exception {
        String project=project();SourceDocument word=guidanceTemplate(project,false),auxiliary=guidanceTemplate(project,true);
        Map<String,Object> draft=service.save(project,(Map<String,Object>)service.bootstrap(project).get("graph"),null);String draftId=String.valueOf(draft.get("id"));
        assertEquals("missing",service.reading(project,auxiliary.getId()).path("evidenceStatus").asText());saveGuidanceEvidence(auxiliary);
        assertEquals(Boolean.FALSE,service.revision(project,draftId).get("sourceStale"));assertEquals("bound",service.reading(project,auxiliary.getId()).path("evidenceStatus").asText());
        when(ai.complete(anyString(),anyString())).thenReturn("{\"nodes\":[],\"notes\":[\"Synthetic selected source reviewed\"]}");
        Map<String,Object> discovered=service.discover(project,draftId,null,10);assertSelectedWordSourcesAndSeeds(JsonUtils.mapper().valueToTree(discovered.get("graph")),word);
        verify(ai,times(1)).complete(anyString(),argThat(prompt->prompt.contains("Selected Word instruction")&&!prompt.contains("Unselected PDF instruction")));
        assertEquals(2,service.sources(project).size());assertEquals(auxiliary.getTextContent(),service.reading(project,auxiliary.getId()).path("text").asText());
    }

    void assertSelectedWordSourcesAndSeeds(JsonNode graph,SourceDocument word){
        assertEquals(1,graph.path("sources").size());assertEquals(word.getId().toString(),graph.path("sources").get(0).path("sourceDocumentId").asText());
        JsonNode nodes=graph.path("graph").path("variables");assertEquals(1,nodes.size());for(JsonNode node:nodes){assertEquals(1,node.path("sourceAnchors").size());assertEquals(word.getId().toString(),node.path("sourceAnchors").get(0).path("sourceDocumentId").asText());}
    }
    SourceDocument guidanceTemplate(String project,boolean auxiliary)throws Exception {
        String instruction=auxiliary?"[Guidance Note: Unselected PDF instruction remains a separate edition.]":"[Guidance Note: Selected Word instruction remains available for review.]";byte[] bytes;
        if(auxiliary)try(org.apache.pdfbox.pdmodel.PDDocument pdf=new org.apache.pdfbox.pdmodel.PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            org.apache.pdfbox.pdmodel.PDPage page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);try(org.apache.pdfbox.pdmodel.PDPageContentStream content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){content.beginText();content.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,8);content.newLineAtOffset(36,720);content.showText(instruction);content.endText();}pdf.save(out);bytes=out.toByteArray();
        }else try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){doc.createParagraph().createRun().setText(instruction);doc.write(out);bytes=out.toByteArray();}
        SourceDocument source=source(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,auxiliary?null:"NTT",auxiliary?"separate-edition.pdf":"selected.docx",bytes,instruction);
        if(auxiliary){source.setParseStatus(DraftTemplateRouting.AUXILIARY_STATUS);sources.save(source);}else saveGuidanceEvidence(source);return source;
    }
    void saveGuidanceEvidence(SourceDocument source){
        DocumentBlock block=new DocumentBlock();block.setId("synthetic-guidance-"+source.getId());block.setKind("paragraph");block.setSource("native");block.setText(source.getTextContent());block.setOriginalText(source.getTextContent());
        ParseCoverage coverage=new ParseCoverage();coverage.setComplete(true);DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(source.getTextContent(),Collections.emptyList(),0,false,"synthetic saved observations",Collections.singletonList(block),coverage,source.getSourceSha256(),Collections.emptyList());coverage.setTemplateEvidence(OerTemplateEvidence.analyse("NTT",parsed));
        source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));sources.save(source);
    }

    @Test void savedDraftIsImmutableAndConfirmationChecksTheDisplayedHead() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> proposed=graph(template,"confirmed","number");
        Map<String,Object> draft=service.save(project,proposed,null);String draftId=String.valueOf(draft.get("id")),original=revisions.findById(draftId).get().getGraphJson();
        assertFalse(service.hasConfirmed(project));String current=String.valueOf(service.confirm(project,draftId,null).get("id"));
        assertNotEquals(current,draftId);assertEquals("DRAFT",revisions.findById(draftId).get().getStatus());assertEquals(original,revisions.findById(draftId).get().getGraphJson());
        assertEquals(draftId,service.current(project).get("draftRevisionId"));assertEquals(current,service.current(project).get("workingRevisionId"),"A confirmed descendant of the retained draft remains the working revision");
        assertThrows(BizException.class,()->service.save(project,proposed,null));assertEquals(current,service.current(project).get("currentRevisionId"));
        assertThrows(BizException.class,()->service.revision(project(),current));verifyNoInteractions(ai);
    }
    @Test void consecutiveProviderFailuresStopExtractionWithoutDiscardingTheUsableCandidate() throws Exception {
        String project=project();SourceDocument template=template(project);for(int i=0;i<6;i++)correspondence(project,"The duration is 42 months. Source "+i);
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months.")).thenThrow(new IllegalStateException("synthetic timeout"));
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));List<VariableVO> proposed=service.extract(project,draft);
        verify(ai,times(4)).complete(anyString(),anyString());assertEquals("42",proposed.get(0).getValue());assertFalse(proposed.get(0).isConfirmed());
        JsonNode trace=JsonUtils.parse(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).orElseThrow().getTraceJson());assertEquals("partial",trace.path("status").asText());assertEquals(4,trace.path("parts").size());
    }
    @Test void draftExtractionDoesNotAdoptOrOverwriteCurrentValuesAndSurvivesReviewConfirmation() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        Map<String,Object> savedDraft=service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null);Map<String,Object> draft=service.confirmDefinitions(project,String.valueOf(savedDraft.get("id")),null,"Test-only human graph review",true);List<VariableVO> identified=service.extract(project,String.valueOf(draft.get("id")));
        assertEquals("42",identified.get(0).getValue());assertFalse(identified.get(0).isConfirmed());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());assertFalse(service.hasConfirmed(project));
        confirm(project,template,"number",null);VariableVO current=variable(project);assertEquals(1,current.getCandidates().size());assertFalse(effective(project).containsKey("duration"));
        service.updateVariable(project,"duration",patch(current,"candidateIndex",0,"candidateSnapshot",current.getCandidates().get(0)));assertEquals("42",String.valueOf(effective(project).get("duration")));
        assertTrue(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).isPresent());
    }
    @Test void graphCandidatePromptRequiresNumericConfidenceAndKeepsLabelResponsesRejected()throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        String invalid=JsonUtils.write(Collections.singletonList(map("key","duration","value",42,"sourceQuote","The duration is 42 months.","reason","Test-only complete source","confidence","high")));
        when(ai.complete(anyString(),anyString())).thenReturn(invalid,response("42","The duration is 42 months."));String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));
        List<VariableVO> rejected=service.extract(project,draft);assertTrue(rejected.get(0).getCandidates().isEmpty());assertFalse(rejected.get(0).isConfirmed());
        JsonNode trace=JsonUtils.parse(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).orElseThrow().getTraceJson());assertEquals(invalid,trace.path("parts").get(0).path("attempts").get(0).path("rawResponse").asText());assertTrue(trace.path("decisions").get(0).path("codes").toString().contains("confidence_invalid"));
        List<VariableVO> numeric=service.extract(project,draft);assertEquals(1,numeric.get(0).getCandidates().size());assertEquals("42",numeric.get(0).getValue());assertFalse(numeric.get(0).isConfirmed());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
        verify(ai,times(2)).complete(argThat(prompt->prompt.contains("confidence must be a JSON number from 0 to 1")&&prompt.contains("boolean uses JSON true/false")&&prompt.contains("choice uses an exact options[].value")&&prompt.contains("do not infer a clause keep/delete decision only from a template rule")),anyString());
        verify(ai,times(2)).complete(argThat(prompt->!prompt.contains("sourceAnchors")&&!prompt.contains("editTargets")&&prompt.length()<64000&&prompt.contains("Omit fields without a supported non-null answer")),anyString());
    }

    @Test void partialDraftExtractionRemainsEditableAndCanBeConfirmedThenExplicitlyAdopted() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");
        String raw="["+JsonUtils.write(map("key","duration","value",42,"sourceQuote","The duration is 42 months.","reason","Complete test-only evidence","confidence",0.99))+",{\"key\":";
        when(ai.complete(anyString(),anyString())).thenReturn(raw);
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));List<VariableVO> proposed=service.extract(project,draft);
        assertEquals("42",proposed.get(0).getValue());assertFalse(proposed.get(0).isConfirmed());List<?> candidateFields=(List<?>)service.candidates(project,draft).get("variables");assertEquals(2,candidateFields.size());assertEquals("contractTitle",((VariableVO)candidateFields.get(1)).getKey());assertEquals("missing",((VariableVO)candidateFields.get(1)).getAdoptionState());
        Map<String,Object> candidateState=service.candidates(project,draft);assertNotNull(candidateState.get("runId"));
        Map<String,Object> trace=JsonUtils.readMap(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).get().getTraceJson());assertEquals("partial",trace.get("status"));assertTrue(JsonUtils.write(trace).contains(raw.replace("\"","\\\"")));
        confirm(project,template,"number",null);VariableVO current=variable(project);assertEquals(1,current.getCandidates().size());assertFalse(current.isConfirmed());assertFalse(effective(project).containsKey("duration"));
        service.updateVariable(project,"duration",patch(current,"candidateIndex",0,"candidateSnapshot",current.getCandidates().get(0)));assertEquals(42,((Number)effective(project).get("duration")).intValue());
    }

    @Test void invalidGraphExtractionSourceKeepsEarlierCompleteSourceCandidatesPending() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");correspondence(project,"Further decisions are unavailable.");
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."),"[{\"key\":");
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));List<VariableVO> proposed=service.extract(project,draft);
        assertEquals("42",proposed.get(0).getValue());assertFalse(proposed.get(0).isConfirmed());
        Map<String,Object> trace=JsonUtils.readMap(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).get().getTraceJson());assertEquals("partial",trace.get("status"));
        assertNotNull(service.candidates(project,draft).get("runId"));assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
    }

    @Test void actualMiniMaxHttpLengthAndLaterProviderFailureKeepEarlierGroundedCandidatesAvailable() throws Exception {
        for(boolean length:Arrays.asList(false,true)) {
            String first="The duration is 42 months.",second="An alternative duration is 43 months.";
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
            com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            String partial="["+JsonUtils.write(map("key","duration","value",43,"sourceQuote",second,"reason","Complete test-only alternative","confidence",0.99))+",{\"key\":";
            String envelope=JsonUtils.write(map("base_resp",map("status_code",0),"choices",Collections.singletonList(map("finish_reason","length","message",map("content",partial))),"usage",map("prompt_tokens",100,"completion_tokens",20,"total_tokens",120)));
            server.createContext("/v1/models",exchange->{byte[] body="{\"data\":[]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(body);}});
            server.createContext("/v1/chat/completions",exchange->{int ordinal=calls.incrementAndGet();exchange.getRequestBody().close();String payload=ordinal==1?JsonUtils.write(map("choices",Collections.singletonList(map("finish_reason","stop","message",map("content",response("42",first)))))):length?envelope:"{\"error\":\"TEST ONLY provider failed\"}";byte[] body=payload.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(ordinal==1||length?200:400,body.length);try(java.io.OutputStream out=exchange.getResponseBody()){out.write(body);}});
            server.start();
            try {
                com.consense.config.ConsenseProperties props=new com.consense.config.ConsenseProperties();com.consense.config.ConsenseProperties.Llm cfg=props.getMinimaxCn();cfg.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());cfg.setApiKey("test-only-provider-key");cfg.setEnabled(true);
                com.consense.ai.MiniMaxChatClient client=new com.consense.ai.MiniMaxChatClient(cfg,new com.consense.ai.HttpSupport());com.consense.ai.LlmProfiles profiles=new com.consense.ai.LlmProfiles(props,client,client);com.consense.ai.AiGateway actual=new com.consense.ai.AiGateway(client,profiles);
                doAnswer(call->actual.complete(call.getArgument(0),call.getArgument(1))).when(ai).complete(anyString(),anyString());
                String project=project();SourceDocument template=template(project);correspondence(project,first);correspondence(project,second);String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));
                try(com.consense.ai.LlmProfiles.Scope selected=profiles.bind(profiles.resolve("minimax-cn"))){List<VariableVO> proposed=service.extract(project,draft);assertTrue(proposed.get(0).getCandidates().stream().anyMatch(candidate->"42".equals(candidate.getValue())));assertFalse(proposed.get(0).isConfirmed());assertEquals(length?2:1,proposed.get(0).getCandidates().size());}
                JsonNode trace=JsonUtils.parse(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).get().getTraceJson());assertEquals("partial",trace.path("status").asText());assertEquals(2,calls.get());assertNotNull(service.candidates(project,draft).get("runId"));assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
                JsonNode attempt=trace.path("parts").path(1).path("attempts").path(0);
                if(length){assertEquals("OUTPUT_BUDGET_EXHAUSTED",attempt.path("providerFailureKind").asText());assertEquals(envelope,attempt.path("providerResponse").asText());assertEquals(partial,attempt.path("rawResponse").asText());assertEquals("length",attempt.path("providerResponseMetadata").path("finishReason").asText());assertEquals(120,attempt.path("providerResponseMetadata").path("usage").path("total_tokens").asInt());}
                else {assertEquals("failed",attempt.path("status").asText());assertEquals("model_call_failed",attempt.path("errorCode").asText());}
            }finally{server.stop(0);}
        }
    }
    @Test void correctionAndCandidateRerunsKeepManualValuesAndOnlyChangedSuggestionsRequireReview() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months. An alternative is 43 months.");confirm(project,template,"number",null);
        when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));service.extract(project);
        VariableVO current=variable(project);service.updateVariable(project,"duration",patch(current,"value","50","note","Manual synthetic decision"));
        service.extract(project);assertEquals("50",variable(project).getValue());assertFalse(variable(project).isReviewRequired());assertEquals("50",String.valueOf(effective(project).get("duration")));
        when(ai.complete(anyString(),anyString())).thenReturn(response("43","An alternative is 43 months."));service.extract(project);assertEquals("50",variable(project).getValue());assertTrue(variable(project).isReviewRequired());assertFalse(effective(project).containsKey("duration"));
        VariableVO latest=variable(project);CandidateVO forged=new CandidateVO("99",latest.getCandidates().get(0).getSourceDocumentId(),"forged",latest.getCandidates().get(0).getSourceHash(),"invented",null,1.0);
        assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(latest,"candidateIndex",0,"candidateSnapshot",forged)));
    }
    @Test void changedDefinitionRetainsOldValueButCannotUseItOrAnObsoletePatch() throws Exception {
        String project=project();SourceDocument template=template(project);String first=confirm(project,template,"number",null);VariableVO current=variable(project);
        service.updateVariable(project,"duration",patch(current,"value","50","note","Manual duration"));VariableVO old=variable(project);
        confirm(project,template,"text",first);assertEquals("50",variable(project).getValue());assertTrue(variable(project).isReviewRequired());assertFalse(effective(project).containsKey("duration"));
        assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(old,"value","51","note","Obsolete view")));
    }
    @Test void originalReplacementStalesTheGraphAndCannotRetargetItsStoredRevision() throws Exception {
        String project=project();SourceDocument template=template(project);String current=confirm(project,template,"number",null);VariableVO value=variable(project);service.updateVariable(project,"duration",patch(value,"value","42","note","Manual duration"));String snapshot=service.snapshotId(project,null);
        byte[] original=storage.read(template.getStoragePath());
        String changed="Different original";template.setStoragePath(storage.store(project,SourceDocument.CATEGORY_STANDARD_TEMPLATE,new MockMultipartFile("file","changed.txt","text/plain",changed.getBytes(StandardCharsets.UTF_8))).getPath());template.setSourceSha256(DraftTemplateRouting.sha(changed.getBytes(StandardCharsets.UTF_8)));sources.save(template);
        assertEquals(Boolean.TRUE,service.revision(project,current).get("sourceStale"));assertNotEquals(snapshot,service.snapshotId(project,null));assertFalse(effective(project).containsKey("duration"));assertEquals("42",variable(project).getValue());
        assertTrue(variable(project).isReviewRequired());assertArrayEquals(original,(byte[])service.archivedSource(project,current,template.getId().toString()).get("bytes"));
    }

    @Test void newBoundOerInputsCannotReuseTheOldReviewedGraph(){assertOerDriftGuards("input");}
    @Test void newBoundOerPayloadCannotReuseTheOldReviewedGraph(){assertOerDriftGuards("payload");}
    @Test void invalidOerPayloadCannotKeepTheReviewedGraphCurrent(){assertOerDriftGuards("invalid");}
    @Test void missingOerEvidenceCannotKeepTheReviewedGraphCurrent(){assertOerDriftGuards("missing");}
    void assertOerDriftGuards(String mode){
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(String guard:Arrays.asList("state","save","confirm","adopt","generate"))checks.add(()->assertOerDriftGuard(mode,guard));
        assertAll(checks);
    }
    void assertOerDriftGuard(String mode,String guard)throws Exception{
        String project=project();SourceDocument template=template(project);saveSyntheticOerEvidence(template,"observed-style-a");JsonNode before=service.reading(project,template.getId());assertEquals("bound",before.path("evidenceStatus").asText());
        String current=confirm(project,template,"number",null);service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Source-bound synthetic adoption"));VariableVO oldVariable=variable(project);String snapshot=service.snapshotId(project,null);
        Map<String,Object> draft=service.save(project,service.graph(project),current),oldClient=(Map<String,Object>)draft.get("graph");String draftId=String.valueOf(draft.get("id"));byte[] original=storage.read(template.getStoragePath());String oldSourceHash=DraftAdoption.sourceHash(template);
        if("input".equals(mode))saveSyntheticOerEvidence(template,"observed-style-b");
        else{
            ObjectNode coverage=(ObjectNode)JsonUtils.parse(template.getParseCoverageJson());
            if("missing".equals(mode))coverage.remove("templateEvidence");
            else{
                ObjectNode evidence=(ObjectNode)coverage.path("templateEvidence");((com.fasterxml.jackson.databind.node.ArrayNode)evidence.path("issues")).addObject().put("code","SYNTHETIC_OBSERVATION_REVIEW").put("message","A saved observation was reviewed without changing the original or text.");
                if("payload".equals(mode)){ObjectNode payload=evidence.deepCopy();((ObjectNode)payload.path("sourceBinding")).remove("generationSha256");((ObjectNode)evidence.path("sourceBinding")).put("generationSha256",DraftAdoption.hash(canonicalEvidence(payload).toString()));}
            }
            template.setParseCoverageJson(JsonUtils.write(coverage));sources.save(template);
        }
        JsonNode after=service.reading(project,template.getId());assertEquals(before.path("sourceSha256"),after.path("sourceSha256"));assertEquals(oldSourceHash,DraftAdoption.sourceHash(template));assertArrayEquals(original,storage.read(template.getStoragePath()));
        if(!"missing".equals(mode))assertEquals(before.path("analysisRevision"),after.path("analysisRevision"));
        if(Arrays.asList("input","payload").contains(mode)){
            assertEquals("bound",after.path("evidenceStatus").asText());JsonNode coverage=JsonUtils.parse(template.getParseCoverageJson());assertDoesNotThrow(()->OerTemplateEvidence.requireBound("NTT",template.getSourceSha256(),JsonUtils.parse(template.getStructuredContentJson()),coverage,coverage.path("templateEvidence")));
            assertNotEquals(before.path("analysisSha256"),after.path("analysisSha256"));if("input".equals(mode))assertNotEquals(before.path("analysisInputSha256"),after.path("analysisInputSha256"));else assertEquals(before.path("analysisInputSha256"),after.path("analysisInputSha256"));
        }else assertEquals("invalid".equals(mode)?"invalid":"missing",after.path("evidenceStatus").asText());
        switch(guard){
            case "state":assertAll(()->assertEquals(true,service.revision(project,current).get("sourceStale")),()->assertEquals(true,service.revision(project,draftId).get("sourceStale")),()->assertFalse(effective(project).containsKey("duration")),()->assertNotEquals(snapshot,service.snapshotId(project,null)));break;
            case "save":assertEquals(4090,assertThrows(BizException.class,()->service.save(project,oldClient,current)).getCode());break;
            case "confirm":assertEquals(4090,assertThrows(BizException.class,()->service.confirm(project,draftId,current)).getCode());break;
            case "adopt":assertEquals(4090,assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(oldVariable,"value","43","note","Old view must fail"))).getCode());break;
            case "generate":assertEquals(4090,assertThrows(BizException.class,()->service.compile(original,"NTT",map("graphRevisionId",current),Collections.emptyMap(),null)).getCode());break;
            default:fail("Unknown guard");
        }
        verifyNoInteractions(ai);
    }
    void saveSyntheticOerEvidence(SourceDocument source,String style){
        DocumentBlock block=new DocumentBlock();block.setId("saved-synthetic-paragraph");block.setKind("paragraph");block.setSource("docx");block.setText(source.getTextContent());block.setOriginalText(source.getTextContent());block.setParagraphStyle(style);
        ParseCoverage coverage=new ParseCoverage();coverage.setComplete(true);DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument(source.getTextContent(),Collections.emptyList(),0,false,null,Collections.singletonList(block),coverage,source.getSourceSha256(),Collections.emptyList());coverage.setTemplateEvidence(OerTemplateEvidence.analyse(source.getFileKey(),parsed));
        source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(coverage));sources.save(source);
    }
    JsonNode canonicalEvidence(JsonNode value){
        if(value.isObject()){ObjectNode result=JsonUtils.mapper().createObjectNode();Set<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,canonicalEvidence(value.get(key)));return result;}
        if(value.isArray()){com.fasterxml.jackson.databind.node.ArrayNode result=JsonUtils.mapper().createArrayNode();for(JsonNode item:value)result.add(canonicalEvidence(item));return result;}
        return value;
    }
    @Test void unquotedCandidateIsRejectedAndUnknownCannotBeBulkAdopted() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");confirm(project,template,"number",null);when(ai.complete(anyString(),anyString())).thenReturn(response("42","not in this source"));
        service.extract(project);assertTrue(variable(project).getCandidates().isEmpty());service.confirmAll(project);assertFalse(variable(project).isConfirmed());assertFalse(effective(project).containsKey("duration"));
    }
    @Test void correctingADraftCandidateCreatesHistoryAndNeverAdoptsTheCorrectedValue() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");when(ai.complete(anyString(),anyString())).thenReturn(response("39","The duration is 42 months."));
        String draft=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));service.extract(project,draft);Map<String,Object> before=service.candidates(project,draft);
        Map<String,Object> corrected=service.correctCandidate(project,draft,"duration",map("expectedRunId",before.get("runId"),"candidateIndex",0,"value","42","sourceQuote","The duration is 42 months."));
        assertNotEquals(before.get("runId"),corrected.get("runId"));assertTrue(runs.findById(String.valueOf(before.get("runId"))).isPresent());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
        assertThrows(BizException.class,()->service.correctCandidate(project,draft,"duration",map("expectedRunId",before.get("runId"),"candidateIndex",0,"value","41","sourceQuote","The duration is 42 months.")));
        assertEquals("42",variable(project).getCandidates().get(0).getValue());
        clearInvocations(runs,values,ai);
        assertEquals("42",variable(project).getCandidates().get(0).getValue());
        verify(runs,times(1)).findByProjectIdOrderByFinishedAtDescIdDesc(eq(project),argThat(page->page.isPaged()&&page.getPageSize()==1&&page.getPageNumber()==0));
        verify(values,never()).save(any());verify(ai,never()).complete(anyString(),anyString());
        confirm(project,template,"number",null);VariableVO current=variable(project);assertEquals("42",current.getCandidates().get(0).getValue());assertFalse(current.isConfirmed());assertFalse(effective(project).containsKey("duration"));
    }
    @Test void changedActiveQuestionRetainsCandidateForExplicitReviewWithoutNewInference() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        String first=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));service.extract(project,first);clearInvocations(ai);
        String next=String.valueOf(service.save(project,graph(template,"pending","text"),null).get("id"));
        JsonNode review=JsonUtils.mapper().valueToTree(service.candidates(project,next));
        assertTrue(review.path("variables").get(0).path("candidates").isEmpty());
        assertEquals("42",review.path("unassignedVariables").path(0).path("candidates").path(0).path("value").asText());
        String hash=review.path("unassignedVariables").path(0).path("candidates").path(0).path("fieldDefinitionHash").asText();
        assertEquals("number",review.path("candidateDefinitions").path(hash).path("kind").asText());
        assertEquals("text",review.path("catalog").path("groups").path(0).path("fields").path(0).path("kind").asText());
        assertTrue(review.path("unassignedVariables").path(0).path("reviewRequired").asBoolean());
        assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());verifyNoInteractions(ai);
    }
    @Test void correctingOneCandidateDoesNotRebindOtherCandidatesToChangedDefinition() throws Exception {
      for(boolean confirmFirst:Arrays.asList(false,true)) {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");correspondence(project,"The duration is 43 months.");
        when(ai.complete(anyString(),anyString())).thenAnswer(call->((String)call.getArgument(1)).contains("43 months")?response("43","The duration is 43 months."):response("42","The duration is 42 months."));
        String first=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));service.extract(project,first);clearInvocations(ai);
        String next=String.valueOf(service.save(project,graph(template,confirmFirst?"confirmed":"pending","text"),null).get("id"));if(confirmFirst)next=String.valueOf(service.confirm(project,next,null).get("id"));Map<String,Object> before=service.candidates(project,next);JsonNode old=JsonUtils.mapper().valueToTree(before).path("unassignedVariables").path(0).path("candidates");assertEquals(2,old.size());
        Map<String,Object> updated=service.correctCandidate(project,next,"duration",map("expectedRunId",before.get("runId"),"candidateIndex",0,"value",old.get(0).path("value").asText(),"sourceQuote",old.get(0).path("sourceQuote").asText()));
        JsonNode after=JsonUtils.mapper().valueToTree(updated);assertTrue(after.path("variables").get(0).path("candidates").isEmpty());JsonNode retained=after.path("unassignedVariables").get(0).path("candidates");assertEquals(2,retained.size());
        assertEquals(old.get(1),retained.get(0));assertEquals("number",after.path("candidateDefinitions").path(retained.get(0).path("fieldDefinitionHash").asText()).path("kind").asText());assertEquals("text",after.path("candidateDefinitions").path(retained.get(1).path("fieldDefinitionHash").asText()).path("kind").asText());
        if(confirmFirst)assertTrue(variable(project).getCandidates().isEmpty());
        JsonNode completed=JsonUtils.mapper().valueToTree(service.correctCandidate(project,next,"duration",map("expectedRunId",updated.get("runId"),"candidateIndex",0,"value",retained.get(0).path("value").asText(),"sourceQuote",retained.get(0).path("sourceQuote").asText())));
        assertTrue(completed.path("unassignedVariables").isEmpty());assertEquals(2,completed.path("variables").get(0).path("candidates").size());
        if(confirmFirst)assertFalse(variable(project).isConfirmed());verifyNoInteractions(ai);
      }
    }
    @Test void retiredQuestionsRetainCandidatesForExplicitReassignmentWithoutNewInference() throws Exception {
      for(boolean confirmFirst:Arrays.asList(false,true)) {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        String first=approvedDraft(project,String.valueOf(service.save(project,withConfirmedDefinition(graph(template,"pending","number")),null).get("id")));service.extract(project,first);clearInvocations(ai);
        Map<String,Object> changed=withConfirmedDefinition(graph(template,"pending","number"));List<Map<String,Object>> nodes=DraftReviewGraph.nodes(changed);Map<String,Object> retained=JsonUtils.readMap(JsonUtils.write(nodes.get(0)));retained.put("id","duration-current");nodes.get(0).put("status","excluded");nodes.get(0).put("replacedBy",Collections.singletonList("duration-current"));List<Object> all=new ArrayList<>(nodes);if(confirmFirst)retained.put("status","confirmed");all.add(retained);DraftReviewGraph.state(changed).put("variables",all);
        String next=String.valueOf(service.save(project,changed,null).get("id"));if(confirmFirst)next=String.valueOf(service.confirm(project,next,null).get("id"));Map<String,Object> review=service.candidates(project,next);
        JsonNode displayField=JsonUtils.mapper().valueToTree(review).path("catalog").path("groups").get(0).path("fields").get(0);assertEquals("duration-current",displayField.path("key").asText());assertEquals("number",displayField.path("kind").asText());assertFalse(displayField.path("label").isMissingNode());
        JsonNode orphan=JsonUtils.mapper().valueToTree(review).path("unassignedVariables").get(0);assertEquals("duration",orphan.path("key").asText());assertEquals("42",orphan.path("candidates").get(0).path("value").asText());assertTrue(orphan.path("reviewRequired").asBoolean());
        Map<String,Object> corrected=service.correctCandidate(project,next,"duration",map("expectedRunId",review.get("runId"),"candidateIndex",0,"targetKey","duration-current","value","42","sourceQuote","The duration is 42 months."));
        JsonNode after=JsonUtils.mapper().valueToTree(corrected);assertTrue(after.path("unassignedVariables").isEmpty());assertEquals("42",after.path("variables").get(0).path("candidates").get(0).path("value").asText());if(confirmFirst){assertFalse(service.variables(project).stream().filter(value->"duration-current".equals(value.getKey())).findFirst().get().isConfirmed());}else assertFalse(values.findByProjectIdAndVarKey(project,"duration-current").isPresent());verifyNoInteractions(ai);
      }
    }
    @Test void explicitTargetAmendmentsUseOnlyCurrentGraphTargetsAndRequireReviewAfterAnotherRevision() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","duration-target","docId",template.getId().toString(),"text","____","action","fill","valueSourceId","duration")));
        String draft=String.valueOf(service.save(project,graph,null).get("id")),current=String.valueOf(service.confirm(project,draft,null).get("id"));
        service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Manual duration"));String before=service.snapshotId(project,null);
        VariableVO override=service.variables(project).stream().filter(value->"targetOverrides".equals(value.getKey())).findFirst().get();
        service.updateVariable(project,"targetOverrides",patch(override,"value",JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","50")))));
        Map<?,?> action=(Map<?,?>)((List<?>)service.plan(project,null).get("actions")).get(0);assertEquals("50",action.get("value"));assertNotEquals(before,service.snapshotId(project,null));
        VariableVO latest=service.variables(project).stream().filter(value->"targetOverrides".equals(value.getKey())).findFirst().get();assertThrows(BizException.class,()->service.updateVariable(project,"targetOverrides",patch(latest,"value","[{\"actionId\":\"old-static-target\",\"action\":\"delete\"}]")));
        String next=String.valueOf(service.save(project,graph,current).get("id"));service.confirm(project,next,current);
        assertTrue(service.variables(project).stream().filter(value->"targetOverrides".equals(value.getKey())).findFirst().get().isReviewRequired());
    }
    @Test void currentTraceUsesGraphAndCorrespondenceIdentityAndTurnsStaleWhenEvidenceChanges() throws Exception {
        String project=project();SourceDocument template=template(project);SourceDocument evidence=correspondence(project,"The duration is 42 months.");confirm(project,template,"number",null);when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));service.extract(project);
        Map<String,Object> trace=JsonUtils.readMap(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).get().getTraceJson());assertFalse(service.traceStale(project,trace));evidence.setTextContent("The duration is now 43 months.");sources.save(evidence);assertTrue(service.traceStale(project,trace));
    }
    @Test void emptyTextRequiresExplicitChoiceAndReasonAndIsDifferentFromUnknown() throws Exception {
        String project=project();SourceDocument template=template(project);confirm(project,template,"text",null);assertFalse(effective(project).containsKey("duration"));
        service.updateVariable(project,"duration",patch(variable(project),"value",""));assertFalse(variable(project).isConfirmed());assertFalse(effective(project).containsKey("duration"));
        assertThrows(BizException.class,()->service.updateVariable(project,"duration",patch(variable(project),"value","","explicitEmpty",true)));
        service.updateVariable(project,"duration",patch(variable(project),"value","","explicitEmpty",true,"note","Explicitly no text for this field"));assertTrue(variable(project).isConfirmed());assertEquals("adopted",variable(project).getAdoptionState());assertTrue(effective(project).containsKey("duration"));assertEquals("",effective(project).get("duration"));VariableVO adopted=variable(project);service.updateVariable(project,"duration",patch(adopted,"reviewed",true,"suggestionSnapshot",map("value",adopted.getValue(),"reviewRequired",adopted.isReviewRequired(),"candidates",adopted.getCandidates())));assertTrue(variable(project).isConfirmed());assertEquals("",effective(project).get("duration"));
    }

    @Test void graphPreviewReportsTheProposedValuesWithoutMutatingAdoptions() throws Exception {
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(String[] input:new String[][]{{"number","10","0"},{"boolean","true","false"},{"list","[{}]","[]"}}){
            String project=project();SourceDocument template=template(project);confirm(project,template,input[0],null);
            service.updateVariable(project,"duration",patch(variable(project),"value",input[1],"note","Saved synthetic answer"));String snapshot=service.snapshotId(project,null);
            Map<String,Object> preview=service.plan(project,Collections.singletonMap("duration",input[2]));
            checks.add(()->assertEquals(input[2],JsonUtils.write(((Map<?,?>)preview.get("inputValues")).get("duration")),input[0]+" displayed preview"));
            checks.add(()->assertEquals(input[2],JsonUtils.write(((Map<?,?>)preview.get("effectiveValues")).get("duration")),input[0]+" effective preview"));
            checks.add(()->assertEquals(snapshot,preview.get("savedSnapshotId")));
            checks.add(()->assertEquals(input[1],variable(project).getValue()));
            checks.add(()->assertEquals(input[1],JsonUtils.write(effective(project).get("duration"))));
        }
        assertAll(checks);verifyNoInteractions(ai);
    }

    @Test void blankGraphInputPreviewClearsVisibleAndEffectiveValuesWithoutAdopting() throws Exception {
        for(String[] input:new String[][]{{"number","10"},{"boolean","true"},{"list","[{}]"},{"text","Saved exact text"}}){
            String project=project();SourceDocument template=template(project);confirm(project,template,input[0],null);service.updateVariable(project,"duration",patch(variable(project),"value",input[1],"note","Saved synthetic answer"));String snapshot=service.snapshotId(project,null);
            Map<String,Object> preview=service.plan(project,Collections.singletonMap("duration",""));Map<?,?> visible=(Map<?,?>)preview.get("inputValues");
            assertTrue(visible.containsKey("duration"));assertEquals("text".equals(input[0])?"":null,visible.get("duration"));assertFalse(((Map<?,?>)preview.get("effectiveValues")).containsKey("duration"));
            assertEquals(snapshot,preview.get("savedSnapshotId"));assertEquals(input[1],variable(project).getValue());assertTrue(variable(project).isConfirmed());
        }
        verifyNoInteractions(ai);
    }

    @Test void targetPreviewReportsTheSameNormalizedOverridesAsItsActionsWithoutSaving() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","duration-target","docId",template.getId().toString(),"text","____","action","fill","valueSourceId","duration")));
        String draft=String.valueOf(service.save(project,graph,null).get("id")),current=String.valueOf(service.confirm(project,draft,null).get("id"));service.updateVariable(project,"duration",patch(variable(project),"value","10","note","Saved duration"));
        service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","50")))));String saved=overrides(project).getValue(),snapshot=service.snapshotId(project,null);
        Map<String,Object> preview=service.plan(project,Collections.singletonMap("targetOverrides",JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","60")))));
        Map<?,?> action=(Map<?,?>)((List<?>)preview.get("actions")).get(0),entry=(Map<?,?>)((List<?>)((Map<?,?>)preview.get("inputValues")).get("targetOverrides")).get(0);
        assertEquals("60",action.get("value"));assertEquals(action.get("value"),entry.get("value"));assertEquals(map("graphRevisionId",current,"sourceSha256",template.getSourceSha256()),entry.get("sourceRevision"));
        assertEquals(snapshot,preview.get("savedSnapshotId"));assertEquals(saved,overrides(project).getValue());
        Map<String,Object> cleared=service.plan(project,Collections.singletonMap("targetOverrides",""));assertEquals(Collections.emptyList(),((Map<?,?>)cleared.get("inputValues")).get("targetOverrides"));assertEquals("10",((Map<?,?>)((List<?>)cleared.get("actions")).get(0)).get("value"));
        assertEquals(snapshot,cleared.get("savedSnapshotId"));assertEquals(saved,overrides(project).getValue());assertEquals("50",((Map<?,?>)((List<?>)service.plan(project,null).get("actions")).get(0)).get("value"));verifyNoInteractions(ai);
    }

    @Test void invalidDeclaredRuleValuesCannotBecomeAConfirmedHead() throws Exception {
        String project=project();SourceDocument template=template(project);String current=confirm(project,template,"number",null);
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();
        for(String role:Arrays.asList("fixed","system","derived")){
            Map<String,Object> proposed=graph(template,"confirmed","number"),node=(Map<String,Object>)((List<?>)((Map<?,?>)proposed.get("graph")).get("variables")).get(0);node.put("role",map("kind",role));
            if("derived".equals(role)){
                Map<String,Object> trigger=new LinkedHashMap<>(node);trigger.put("id","trigger");trigger.put("type",map("kind","boolean"));trigger.put("role",map("kind","input"));
                ((Map<String,Object>)proposed.get("graph")).put("variables",Arrays.asList(node,trigger));
                node.put("correction",map("branches",Collections.singletonList(map("id","yes","mode","all","clauses",Collections.singletonList(map("inputId","trigger","operator","yes")),"resultValue","not-a-number"))));
            }else node.put("fixedValue","not-a-number");
            checks.add(()->assertThrows(BizException.class,()->service.save(project,proposed,current),role));
        }
        assertAll(checks);assertEquals(current,service.current(project).get("currentRevisionId"));verifyNoInteractions(ai);
    }
    @Test void graphReadsWaitForAnInFlightAdoptionAndCannotOverwriteItsAnswer() throws Exception {
        String project=project();SourceDocument template=template(project);confirm(project,template,"number",null);VariableVO initial=variable(project);java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch locked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),readStarted=new java.util.concurrent.CountDownLatch(1);
        try{java.util.concurrent.Future<?> writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{entityManager.find(Project.class,project,javax.persistence.LockModeType.PESSIMISTIC_WRITE);locked.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test release timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}service.updateVariable(project,"duration",patch(initial,"value","57","note","Concurrent decision"));return null;}));assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS));java.util.concurrent.Future<List<VariableVO>> reader=pool.submit(()->{readStarted.countDown();return service.variables(project);});assertTrue(readStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->reader.get(150,java.util.concurrent.TimeUnit.MILLISECONDS));release.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals("57",reader.get(5,java.util.concurrent.TimeUnit.SECONDS).get(0).getValue());assertEquals("57",variable(project).getValue());assertEquals("57",String.valueOf(effective(project).get("duration")));}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void targetAmendmentsCanBeReviewedOneAtATimeAndStaleEntriesCanBeDeleted() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);node.put("editTargets",Arrays.asList(map("id","a","docId",template.getId().toString(),"text","____","action","fill","valueSourceId","duration"),map("id","b","docId",template.getId().toString(),"text","____","action","fill","valueSourceId","duration")));
        String draft=String.valueOf(service.save(project,graph,null).get("id")),current=String.valueOf(service.confirm(project,draft,null).get("id"));VariableVO override=overrides(project);service.updateVariable(project,"targetOverrides",patch(override,"value",JsonUtils.write(Arrays.asList(map("actionId","a","action","amend","value","A"),map("actionId","b","action","amend","value","B")))));
        List<Map> old=JsonUtils.readList(overrides(project).getValue(),Map.class);draft=String.valueOf(service.save(project,graph,current).get("id"));service.confirm(project,draft,current);
        Map<String,Object> readoptA=new LinkedHashMap<>(old.get(0));readoptA.remove("sourceRevision");service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Arrays.asList(readoptA,old.get(1)))));
        List<Map> mixed=JsonUtils.readList(overrides(project).getValue(),Map.class);assertNotEquals(old.get(0).get("sourceRevision"),mixed.get(0).get("sourceRevision"));assertEquals(old.get(1).get("sourceRevision"),mixed.get(1).get("sourceRevision"));Map<String,Object> plan=service.plan(project,null);assertEquals("A",((Map<?,?>)((List<?>)plan.get("actions")).get(0)).get("value"));assertEquals("needs_review",((Map<?,?>)((List<?>)plan.get("actions")).get(1)).get("adoptionState"));assertTrue(overrides(project).isReviewRequired());
        service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Collections.singletonList(mixed.get(0)))));assertFalse(overrides(project).isReviewRequired());assertEquals(1,JsonUtils.readList(overrides(project).getValue(),Map.class).size());
        service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Arrays.asList(mixed.get(0),map("actionId","b","action","amend","value","B")))));assertFalse(overrides(project).isReviewRequired());assertEquals("B",((Map<?,?>)((List<?>)service.plan(project,null).get("actions")).get(1)).get("value"));
    }
    VariableVO overrides(String project){return service.variables(project).stream().filter(value->"targetOverrides".equals(value.getKey())).findFirst().get();}
    @Test void callerSuppliedObservationOrNativeBoundStatusesCannotConfirmAnUnsupportedPassage() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("sourceAnchors",Collections.singletonList(map("docId",template.getId().toString(),"text","Fabricated unsupported instruction","sourceObservationBinding",map("status","bound"),"nativeBinding",map("status","bound"))));
        Map<String,Object> saved=service.save(project,graph,null);Map<String,Object> savedNode=DraftReviewGraph.nodes((Map<String,Object>)saved.get("graph")).get(0);
        Map<String,Object> anchor=(Map<String,Object>)((List<?>)savedNode.get("sourceAnchors")).get(0);assertNotEquals("bound",((Map<?,?>)anchor.get("sourceObservationBinding")).get("status"));assertNotEquals("bound",((Map<?,?>)anchor.get("nativeBinding")).get("status"));
        assertThrows(BizException.class,()->service.confirm(project,String.valueOf(saved.get("id")),null));
    }
    @Test void reviewedInsertionManualOverrideKeepsItsExplicitPointAndInsertOperation() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");byte[] original=storage.read(template.getStoragePath());
        com.consense.document.DocxTemplateEditor.Paragraph paragraph=new com.consense.document.DocxTemplateEditor().inspect(original).mainParagraph(1);int end=paragraph.getText().length();
        Map<String,Object> point=map("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"sourceSha256",template.getSourceSha256(),"channel","editableText","start",end,"end",end,"expectedText","","operation","insert");
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","insert-target","docId",template.getId().toString(),"text","","action","insert","valueSourceId","duration","native",map("spans",Collections.singletonList(point)))));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));service.confirm(project,draft,null);
        service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Test-only required input, independently reviewed before the target override"));
        service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Collections.singletonList(map("actionId","insert-target","action","amend","value"," Test-only wording")))));
        Map<String,Object> plan=service.plan(project,null);assertTrue(((List<?>)plan.get("unresolved")).isEmpty());assertEquals("insert",((Map<?,?>)((List<?>)plan.get("actions")).get(0)).get("action"));
        com.consense.document.DocxTemplateEditor.DocxEditResult output=service.compile(original,"NTT",plan,Collections.emptyMap(),null);
        assertEquals(paragraph.getText()+" Test-only wording",output.getIndex().mainParagraph(1).getText());assertEquals(com.consense.document.DocxTemplateEditor.EditType.INSERT_TEXT,output.getLedger().get(0).getType());
    }
    @Test void reviewedMultiSpanPlacementAlsoAppliesToManualOverridesAndCompilation() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");
        byte[] original=storage.read(template.getStoragePath());com.consense.document.DocxTemplateEditor.Paragraph paragraph=new com.consense.document.DocxTemplateEditor().inspect(original).mainParagraph(1);
        List<Object> spans=new ArrayList<>();for(int start:new int[]{10,12})spans.add(map("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"sourceSha256",template.getSourceSha256(),"channel","editableText","start",start,"end",start+2,"expectedText","__"));
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","duration-target","docId",template.getId().toString(),"text","__\n__","action","fill","valueSourceId","duration","native",map("spans",spans),"replacementPlacement",map("spanIndex",1))));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));service.confirm(project,draft,null);
        service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Manual test duration"));
        service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","50")))));
        Map<String,Object> plan=service.plan(project,null);assertTrue(((List<?>)plan.get("unresolved")).isEmpty());assertEquals("amend",((Map<?,?>)((List<?>)plan.get("actions")).get(0)).get("action"));
        assertEquals("Duration: 50 months.",service.compile(original,"NTT",plan,Collections.emptyMap(),null).getIndex().mainParagraph(1).getText());
    }
    @Test void multiSpanAmendmentOverridesAreRejectedAndHistoricalOnesRemainPending() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");
        com.consense.document.DocxTemplateEditor.Paragraph paragraph=new com.consense.document.DocxTemplateEditor().inspect(storage.read(template.getStoragePath())).mainParagraph(1);
        List<Object> spans=new ArrayList<>();for(int start:new int[]{10,12})spans.add(map("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"sourceSha256",template.getSourceSha256(),"channel","editableText","start",start,"end",start+2,"expectedText","__"));
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","duration-target","docId",template.getId().toString(),"text","__\n__","action","fill","valueSourceId","duration","native",map("spans",spans))));
        String draft=String.valueOf(service.save(project,graph,null).get("id"));
        assertThrows(BizException.class,()->service.confirm(project,draft,null));
        Map<String,Object> target=(Map<String,Object>)((List<?>)node.get("editTargets")).get(0);target.put("replacementPlacement",map("spanIndex",1));
        String placed=String.valueOf(service.save(project,graph,null).get("id")),reviewed=String.valueOf(service.confirm(project,placed,null).get("id"));
        // A pre-placement confirmed revision is a historical fixture; new confirmations cannot create it.
        DraftTemplateGraphRevision currentRevision=revisions.findById(reviewed).get();Map<String,Object> historicalGraph=JsonUtils.readMap(currentRevision.getGraphJson());
        Map<String,Object> historicalNode=(Map<String,Object>)((List<?>)((Map<?,?>)historicalGraph.get("graph")).get("variables")).get(0);
        ((Map<String,Object>)((List<?>)historicalNode.get("editTargets")).get(0)).remove("replacementPlacement");
        String current=UUID.randomUUID().toString(),historicalJson=JsonUtils.write(historicalGraph);
        revisions.save(new DraftTemplateGraphRevision(current,project,reviewed,"CONFIRMED",DraftAdoption.hash(historicalJson),historicalJson,currentRevision.getSourcesJson(),java.time.Instant.now()));
        DraftTemplateGraphHead head=graphHeads.findById(project).get();head.setCurrentRevisionId(current);graphHeads.save(head);
        service.updateVariable(project,"duration",patch(variable(project),"value","42","note","Manual duration"));
        String amendment=JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","50")));
        assertAll(
            ()->assertThrows(BizException.class,()->service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",amendment))),
            ()->{
                DraftVariable historical=values.findByProjectIdAndVarKey(project,"targetOverrides").get();historical.setConfirmed(true);
                String preserved=JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","amend","value","50","sourceRevision",map("graphRevisionId",current,"sourceSha256",template.getSourceSha256()))));historical.setValueText(preserved);values.save(historical);
                assertDoesNotThrow(()->service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",preserved)));
                assertTrue(overrides(project).isReviewRequired());
                Map<String,Object> plan=service.plan(project,null);assertEquals("pending",((Map<?,?>)((List<?>)plan.get("actions")).get(0)).get("action"));
                assertTrue(((List<?>)plan.get("unresolved")).stream().map(item->(Map<?,?>)item).anyMatch(issue->"ReplacementPlacementRequired".equals(issue.get("kind"))));
                service.updateVariable(project,"targetOverrides",patch(overrides(project),"value",JsonUtils.write(Collections.singletonList(map("actionId","duration-target","action","retain")))));
                assertEquals("retain",((Map<?,?>)((List<?>)service.plan(project,null).get("actions")).get(0)).get("action"));
                assertFalse(overrides(project).isReviewRequired());
            });
    }
    @Test void deletingAProjectRemovesItsGraphHeadsAndRevisions() throws Exception {
        String project=project();SourceDocument template=template(project);confirm(project,template,"number",null);assertTrue(service.hasConfirmed(project));projectService.delete(project);
        assertFalse(service.hasConfirmed(project));assertTrue(revisions.findByProjectIdOrderByCreatedAtDesc(project).isEmpty());
    }
    static Map<String,Object> map(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(String.valueOf(pairs[i]),pairs[i+1]);return result;}
}
