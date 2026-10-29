package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.domain.*;
import com.consense.document.*;
import com.consense.service.advice.*;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Advice compact/registry/mapper/publication path, authored local producer and transport doubles. */
class AdviceCompactIncorporationReceivingTest extends CanonicalRecheckAdviceRealSeamTest {
    static final String TITLE="General Conditions of Contract for Building Works 2013 Edition (version 1.1)";
    String mode;
    @Test void selectedCoCWithDeferredContextCannotEstablishAdoption()throws Exception{compactDiscoveryRetainsDeferredSourcesWithoutPromotingAdoption("matching");}
    @Test void standardOnlyAnalysisCannotAttachDeferredProjectBasis()throws Exception{compactDiscoveryRetainsDeferredSourcesWithoutPromotingAdoption("scope_basis_only");}
    @Test void standardOnlyAnalysisStillRefusesAnUnselectedProjectBasis()throws Exception{compactDiscoveryRetainsDeferredSourcesWithoutPromotingAdoption("scope_basis_missing");}
    @Test void projectTextCanStillGroundAnAnswerWithoutClaimingStandardAdoption()throws Exception{compactDiscoveryRetainsDeferredSourcesWithoutPromotingAdoption("project_only");}
    @Override ObjectNode retrieveFixture(String key)throws Exception {
        if(mode==null)return super.retrieveFixture(key);
        if("empty_project_seed".equals(mode)&&retrieveCalls.get()==1){ObjectNode empty=super.retrieveFixture(key);empty.putArray("hits");((ObjectNode)empty.path("sourceUnits")).putArray("originalHitIds");((ObjectNode)empty.path("sourceUnits")).putArray("units");return empty;}
        ObjectNode result=super.retrieveFixture(key);long document="missing_scc".equals(mode)?(retrieveCalls.get()==1?44L:43L):!Arrays.asList("unselected_coc","scope_basis_missing").contains(mode)&&retrieveCalls.get()==2?44L:43L;
        JsonNode seed=snapshots.get(document).path("chunks").get(0);
        ObjectNode hit=result.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",.9);hit.set("payload",seed.deepCopy());
        Map<String,JsonNode> parents=new LinkedHashMap<>();for(JsonNode snapshot:snapshots.values())for(JsonNode row:snapshot.path("chunks"))parents.put(row.path("id").asText(),row);
        ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",1);unit.put("seedScore",.9);
        ((ObjectNode)result.path("sourceUnits")).putArray("originalHitIds").add(seed.path("id").asText());((ObjectNode)result.path("sourceUnits")).putArray("units").add(unit);
        if(retrieveCalls.get()>1)result.putArray("selectedSourceSha256").add(seed.path("originalSourceSha256").asText());return result;
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"matching","missing_scc","empty_project_seed","wrong_edition","inspection_only","unselected_coc","changed_coc"})
    void compactDiscoveryRetainsDeferredSourcesWithoutPromotingAdoption(String variation)throws Exception {
        mode=variation;
        ObjectNode read=(ObjectNode)health.path("oer").path("sourceRead");read.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);read.putArray("views").add("semantic_units");read.putArray("operations").add("prepare");read.putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();
        SourceDocument scc=documents.findById(43L).orElseThrow();normal(scc,"10 - SCC.docx","SCC14.301 Clause 14.1(1)(a) of the General Conditions of Contract is amended: provide the value of work, with daywork and Variations listed separately.");documents.saveAndFlush(scc);
        SourceDocument coc=new SourceDocument();coc.setProjectId(PROJECT);coc.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);coc.setReviewRole("tender");coc.setParseStatus("PARSED");coc.setFileKey("CoC");
        Path original=temporary.resolve("authored-coc.docx");byte[] old=Files.readAllBytes(originals.get(43L));byte[] changed=Arrays.copyOf(old,old.length+1);changed[changed.length-1]=1;Files.write(original,changed);coc.setStoragePath(original.toString());coc.setSourceSha256(shaBytes(changed));
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(coc.getSourceSha256());identity.setKind("CoC");identity.setSourcePurpose("project_document");identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));coc.setOerIdentityJson(JsonUtils.write(identity));
        String title="wrong_edition".equals(mode)?TITLE.replace("2013","2020"):TITLE;
        normal(coc,"09 - CoC.docx",("inspection_only".equals(mode)?"For inspection only: ":"The Contract shall comprise the ")+title+".");if(Arrays.asList("unselected_coc","scope_basis_missing").contains(mode))coc.setDocumentScope("example");assertEquals(44L,documents.saveAndFlush(coc).getId());originals.put(44L,original);
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");resetCounters();clearInvocations(reader);
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);when(fixed.frozenManifest()).thenReturn(JsonUtils.parse("{\"version\":\"AUTHORED\"}"));
        when(fixed.currentReadGuard(anyList(),any())).thenAnswer(call->{java.util.function.Consumer<JsonNode> capture=call.getArgument(1);capture.accept(fixed.frozenManifest());return (Runnable)()->{};});
        String sha=repeat('e');JsonNode payload=JsonUtils.parse("{\"fileKey\":\"GCC\",\"fileLabel\":\"GCC\",\"pageNo\":58,\"content\":\"14.1 Interim payments. The Contractor shall deliver an interim statement to the Surveyor.\",\"sourceHash\":\""+sha+"\",\"originalSourceSha256\":\""+sha+"\",\"fixedReferenceDocumentTitle\":\""+TITLE+"\",\"fixedDevelopmentBaseline\":true,\"sourceQuality\":{\"family\":\"GCC\"}}");
        java.lang.reflect.Constructor<FixedReferenceEvidence> ctor=FixedReferenceEvidence.class.getDeclaredConstructor(String.class,double.class,JsonNode.class,JsonNode.class,JsonNode.class);ctor.setAccessible(true);FixedReferenceEvidence standard=ctor.newInstance("fixed:payment",.9,payload,null,null);
        when(fixed.searchForProject(anyString(),eq("advisory"),anyString(),eq(10))).thenReturn(Collections.singletonList(standard.asHit()));when(fixed.evidence(any())).thenReturn(standard);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(advice);ReflectionTestUtils.setField(target,"fixedBaseline",fixed);
        List<String> inputs=new ArrayList<>();HttpSupport transport=mock(HttpSupport.class);
        when(transport.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{String input=call.getArgument(1);inputs.add(input);boolean probe=JsonUtils.parse(input).path("max_tokens").asInt()==1;
            if(probe&&"changed_coc".equals(mode))mutate(44L,"original_same_length_restored_mtime");
            String aliases="scope_basis_missing".equals(mode)||"project_only".equals(mode)?"[\"[2]\"]":"scope_basis_only".equals(mode)?"[\"[3]\"]":Arrays.asList("matching","missing_scc","empty_project_seed","changed_coc").contains(mode)?"[\"[1]\",\"[2]\",\"[3]\"]":"[\"[1]\",\"[2]\"]";
            String text="scope_basis_only".equals(mode)?"The Contractor shall deliver an interim statement to the Surveyor [3].":"project_only".equals(mode)?"The selected text requests the value of work [2].":"The adopted GCC requires an interim statement, with the SCC payment amendment retained.";
            String content=probe?"{":AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":\""+text+"\",\"citations\":"+aliases+"}");
            if(!probe&&"scope_basis_only".equals(mode)){ObjectNode envelope=(ObjectNode)JsonUtils.parse(content),second=envelope.path("sections").get(1).deepCopy();second.put("id","second-supported-rule");second.put("content","The interim statement is delivered to the Surveyor [3].");((com.fasterxml.jackson.databind.node.ArrayNode)envelope.path("sections")).add(second);content=JsonUtils.write(envelope);}
            ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("object","chat.completion");result.put("model","qwen3.8-27b");ObjectNode choice=result.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);result.putObject("usage").put("prompt_tokens",1000).put("completion_tokens",1).put("total_tokens",1001);return JsonUtils.write(result);});
        AdviceService.QwenOperation operation=ReflectionTestUtils.invokeMethod(this,"authoredQwen",generationLedger,transport);
        NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();NormalSourceSql.capture=true;
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(operation)){
            if("changed_coc".equals(mode)){assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest("What information does this selected tender require for interim payment, taking account of project amendments?","fullset")));assertEquals(1,inputs.size());assertEquals(0,history.count());return;}
            AskResponseVO response=advice.ask(PROJECT,new AskRequest("What information does this selected tender require for interim payment, taking account of project amendments?","fullset"));
            if("project_only".equals(mode)){
                assertTrue(response.isGrounded());assertEquals("project_contract",response.getBasisScope());assertEquals(Collections.singletonList("[2]"),response.getCitations());
                assertEquals(1,response.getSources().size());assertEquals("43",response.getSources().get(0).getDocumentId());
                assertEquals("unknown",response.getSources().get(0).getCanonicalSourceUnit().path("status").asText());
                assertEquals("additional_source_context_deferred",response.getSources().get(0).getCanonicalSourceUnit().path("reason").asText());
            }else {
                assertFalse(response.isGrounded());assertEquals("insufficient",response.getBasisScope());assertTrue(response.getCitations().isEmpty());
                assertFalse(response.getContent().getEn().contains("Selected tender document basis:"));
            }
            if(Arrays.asList("matching","missing_scc","empty_project_seed","scope_basis_only","project_only").contains(mode)){
                ArgumentCaptor<JsonNode> units=ArgumentCaptor.forClass(JsonNode.class);
                verify(business,atLeastOnce()).admitsAdviceText(any(),argThat(pointer->pointer!=null&&Long.valueOf(44L).equals(pointer.getDocumentId())),anyString(),units.capture());
                assertTrue(units.getAllValues().stream().anyMatch(unit->"unknown".equals(unit.path("status").asText())&&"additional_source_context_deferred".equals(unit.path("reason").asText())));
            }
        }finally{NormalSourceSql.capture=false;ReflectionTestUtils.setField(target,"fixedBaseline",null);}
        assertEquals(Arrays.asList("unselected_coc","scope_basis_missing").contains(mode)?1:"empty_project_seed".equals(mode)?3:2,retrieveCalls.get());assertEquals("project_only".equals(mode)?2:4,inputs.size());if(Arrays.asList("matching","missing_scc","empty_project_seed","scope_basis_only","project_only").contains(mode))assertTrue(inputs.stream().allMatch(input->input.contains(TITLE)&&input.contains("SCC14.301")));
        assertTrue(NormalSourceSql.bindingReads.isEmpty(),"Scope discovery must not materialize binding CLOBs");assertTrue(NormalSourceSql.reads.isEmpty(),"Scope discovery must not materialize whole candidate graphs");
        assertTrue(mockingDetails(reader).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("normalDocument")));assertEquals(2,history.count());
    }
    private void normal(SourceDocument source,String name,String text){source.setFileName(name);source.setTextContent(text);com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId("authored:scope:"+name);block.setSource("native");block.setLocation("body/0/paragraph");block.setText(text);source.setStructuredContentJson(JsonUtils.write(Collections.singletonList(block)));source.setNormalCandidateJson(null);source.setParseCoverageJson("{\"complete\":true}");}
}
