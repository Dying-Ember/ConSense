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
    @Autowired ProjectRepository projects;
    @Autowired SourceDocumentRepository sources;
    @Autowired DraftVariableRepository values;
    @Autowired DraftTemplateGraphRevisionRepository revisions;
    @Autowired DraftExtractionRunRepository runs;
    @Autowired StorageService storage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
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

    @Test void savedDraftIsImmutableAndConfirmationChecksTheDisplayedHead() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> proposed=graph(template,"confirmed","number");
        Map<String,Object> draft=service.save(project,proposed,null);String draftId=String.valueOf(draft.get("id")),original=revisions.findById(draftId).get().getGraphJson();
        assertFalse(service.hasConfirmed(project));String current=String.valueOf(service.confirm(project,draftId,null).get("id"));
        assertNotEquals(current,draftId);assertEquals("DRAFT",revisions.findById(draftId).get().getStatus());assertEquals(original,revisions.findById(draftId).get().getGraphJson());
        assertThrows(BizException.class,()->service.save(project,proposed,null));assertEquals(current,service.current(project).get("currentRevisionId"));
        assertThrows(BizException.class,()->service.revision(project(),current));verifyNoInteractions(ai);
    }
    @Test void draftExtractionDoesNotAdoptOrOverwriteCurrentValuesAndSurvivesReviewConfirmation() throws Exception {
        String project=project();SourceDocument template=template(project);correspondence(project,"The duration is 42 months.");when(ai.complete(anyString(),anyString())).thenReturn(response("42","The duration is 42 months."));
        Map<String,Object> draft=service.save(project,graph(template,"pending","number"),null);List<VariableVO> identified=service.extract(project,String.valueOf(draft.get("id")));
        assertEquals("42",identified.get(0).getValue());assertFalse(identified.get(0).isConfirmed());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());assertFalse(service.hasConfirmed(project));
        confirm(project,template,"number",null);VariableVO current=variable(project);assertEquals(1,current.getCandidates().size());assertFalse(effective(project).containsKey("duration"));
        service.updateVariable(project,"duration",patch(current,"candidateIndex",0,"candidateSnapshot",current.getCandidates().get(0)));assertEquals("42",String.valueOf(effective(project).get("duration")));
        assertTrue(runs.findFirstByProjectIdOrderByFinishedAtDescIdDesc(project).isPresent());
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
        String draft=String.valueOf(service.save(project,graph(template,"pending","number"),null).get("id"));service.extract(project,draft);Map<String,Object> before=service.candidates(project,draft);
        Map<String,Object> corrected=service.correctCandidate(project,draft,"duration",map("expectedRunId",before.get("runId"),"candidateIndex",0,"value","42","sourceQuote","The duration is 42 months."));
        assertNotEquals(before.get("runId"),corrected.get("runId"));assertTrue(runs.findById(String.valueOf(before.get("runId"))).isPresent());assertFalse(values.findByProjectIdAndVarKey(project,"duration").isPresent());
        assertThrows(BizException.class,()->service.correctCandidate(project,draft,"duration",map("expectedRunId",before.get("runId"),"candidateIndex",0,"value","41","sourceQuote","The duration is 42 months.")));
        confirm(project,template,"number",null);VariableVO current=variable(project);assertEquals("42",current.getCandidates().get(0).getValue());assertFalse(current.isConfirmed());assertFalse(effective(project).containsKey("duration"));
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
    @Test void multiSpanAmendmentOverridesAreRejectedAndHistoricalOnesRemainPending() throws Exception {
        String project=project();SourceDocument template=template(project);Map<String,Object> graph=graph(template,"confirmed","number");
        com.consense.document.DocxTemplateEditor.Paragraph paragraph=new com.consense.document.DocxTemplateEditor().inspect(storage.read(template.getStoragePath())).mainParagraph(1);
        List<Object> spans=new ArrayList<>();for(int start:new int[]{10,12})spans.add(map("paragraphId",paragraph.getId(),"paragraphSha256",paragraph.getSha256(),"sourceSha256",template.getSourceSha256(),"channel","editableText","start",start,"end",start+2,"expectedText","__"));
        Map<String,Object> node=(Map<String,Object>)((List<?>)((Map<?,?>)graph.get("graph")).get("variables")).get(0);
        node.put("editTargets",Collections.singletonList(map("id","duration-target","docId",template.getId().toString(),"text","__\n__","action","fill","valueSourceId","duration","native",map("spans",spans))));
        String draft=String.valueOf(service.save(project,graph,null).get("id")),current=String.valueOf(service.confirm(project,draft,null).get("id"));
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
