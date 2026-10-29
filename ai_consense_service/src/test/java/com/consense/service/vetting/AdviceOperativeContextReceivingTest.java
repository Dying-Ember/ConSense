package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.document.*;
import com.consense.domain.SourceDocument;
import com.consense.service.advice.*;
import com.consense.web.dto.AdviceDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual compact source proof, numbered context, Advice packing and prepared wire; authored only. */
class AdviceOperativeContextReceivingTest extends CanonicalRecheckAdviceRealSeamTest {
    static final String TITLE="General Conditions of Contract for Building Works 2013 Edition (version 1.1)";
    static final String QUESTION="Summarise GCC17.1 for this selected tender, including project amendments, actors and time limits.";
    boolean operativeCase;
    boolean changePreAtPreflight;
    boolean missingSccSeed;
    final List<String> scopeQueries=new ArrayList<>();
    @Override ObjectNode retrieveFixture(String key)throws Exception {
        if(!operativeCase)return super.retrieveFixture(key);
        ObjectNode result=(ObjectNode)VettingDualStructureHealthTest.resource("authored_canonical_wire.json").path("retrieve").deepCopy();result.put("projectId",key);result.put("indexSignature",SIGNATURE);result.put("mode",VettingRetrievalHealth.STRUCTURAL_CONTRACT);result.set("competitionScope",scope());
        List<JsonNode> seeds=new ArrayList<>();
        if(retrieveCalls.get()==1){if(!missingSccSeed)seeds.add(snapshots.get(43L).path("chunks").get(0));seeds.add(snapshots.get(45L).path("chunks").get(0));}
        else seeds.add(snapshots.get(retrieveCalls.get()==2?44L:43L).path("chunks").get(retrieveCalls.get()==2?0:1));
        com.fasterxml.jackson.databind.node.ArrayNode hits=result.putArray("hits"),units=((ObjectNode)result.path("sourceUnits")).putArray("units"),ids=((ObjectNode)result.path("sourceUnits")).putArray("originalHitIds");
        Map<String,JsonNode> parents=new LinkedHashMap<>();for(JsonNode snapshot:snapshots.values())for(JsonNode row:snapshot.path("chunks"))parents.put(row.path("id").asText(),row);
        int ordinal=0;for(JsonNode seed:seeds){String id=seed.path("id").asText();hits.addObject().put("id",id).put("score",.9).set("payload",seed.deepCopy());ids.add(id);ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",++ordinal);unit.put("seedScore",.9);units.add(unit);}
        if(retrieveCalls.get()>1)result.putArray("selectedSourceSha256").add(seeds.get(0).path("originalSourceSha256").asText());return result;
    }
    @Test void headOnlyRankedSeedsMustDeliverOperativeSccAndPreTextWithoutIndexClausePollution()throws Exception {
        runOperativeCase(false,false);
    }
    @Test void aChangedOperativePreOriginalStopsBeforeGenerationWithoutReloadingItsGraph()throws Exception {
        runOperativeCase(true,false);
    }
    @Test void anInitiallyAbsentSccUsesOnlyTheRequestedClauseThenClosesItsReplacementBody()throws Exception {
        runOperativeCase(false,true);
    }
    private void runOperativeCase(boolean changed,boolean missing)throws Exception {
        changePreAtPreflight=changed;missingSccSeed=missing;
        operativeCase=true;props.getVetting().setTopK(10);props.getVetting().setCandidateLimit(50);
        ObjectNode read=(ObjectNode)health.path("oer").path("sourceRead");read.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);read.putArray("views").add("semantic_units");read.putArray("operations").add("prepare");read.putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
        SourceDocument unused=documents.findById(42L).orElseThrow();unused.setDocumentScope("example");documents.saveAndFlush(unused);snapshots.remove(42L);
        bindings.delete(bindings.findByProjectIdAndDocumentIdOrderByIdDesc(PROJECT,43L).get(0));bindings.flush();
        SourceDocument scc=documents.findById(43L).orElseThrow();normal(scc,"SCC","10 - SCC.docx",Arrays.asList("SCC17.301 Avoidance and resolution of disputes","(1) Clauses 17.1(1) and 17.1(2) of the General Conditions of Contract are amended by substituting with the following:","(1) The Employer and Contractor shall implement the dispute avoidance procedure.","(2) Not used.","(2) Clauses 17.1(4) and 17.1(5) are amended by substituting with the following:","(4) The Employer and Contractor shall share the DARA fees equally.","(5) The parties shall meet monthly and whenever requested by either party.","SCC18.301 OTHER MATTERS","UNRELATED_SCC_SIBLING_MUST_STAY_OUT"));documents.saveAndFlush(scc);
        SourceDocument coc=additional(44L,"CoC","09 - CoC.docx",Collections.singletonList("The Contract shall comprise the "+TITLE+"."));
        SourceDocument pre=additional(45L,"PRE","25 - PRE.docx",Arrays.asList("PRE.B6.350.P GCC 17.1 - AVOIDANCE AND RESOLUTION OF DISPUTES","(1) The following appointment procedure applies.","(2) The DARA shall be appointed within 63 days commencing from the date of contract award.","If no suitable DARA is agreed within 63 days and no extension is agreed, either party may apply in writing to HKIAC.","PRE.B6.360.P OTHER DUTIES","UNRELATED_PRE_SIBLING_MUST_STAY_OUT"));
        assertTrue(advice.prepareSources(PROJECT,"fullset").isComplete());advice.rebuildIndex(PROJECT,"fullset");resetCounters();clearInvocations(reader);
        doAnswer(call->{scopeQueries.add(call.getArgument(2));return call.callRealMethod();}).when(canonicalRetrieval).retrieveCompact(eq("advisory"),anyString(),anyString(),any(),anyList(),anyString(),any(),any(),any(),eq(true),anySet());
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);when(fixed.frozenManifest()).thenReturn(JsonUtils.parse("{\"version\":\"AUTHORED\"}"));when(fixed.currentReadGuard(anyList(),any())).thenAnswer(call->{java.util.function.Consumer<JsonNode> capture=call.getArgument(1);capture.accept(fixed.frozenManifest());return (Runnable)()->{};});
        FixedReferenceEvidence operative=standard("fixed:disputes","17.1 Avoidance and resolution of disputes. The Contractor and Employer shall use the dispute procedure.");
        FixedReferenceEvidence index=standard("fixed:contents","CONTENTS\n13.6 Valuation\n13.7 Costs\n14.1 Interim payments\n17.1 Avoidance and resolution of disputes");
        when(fixed.searchForProject(anyString(),eq("advisory"),anyString(),eq(10))).thenReturn(Arrays.asList(operative.asHit(),index.asHit()));when(fixed.evidence(any())).thenReturn(operative);
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(advice);ReflectionTestUtils.setField(target,"fixedBaseline",fixed);
        List<String> inputs=new ArrayList<>();HttpSupport transport=mock(HttpSupport.class);
        when(transport.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{String input=call.getArgument(1);inputs.add(input);JsonNode wire=JsonUtils.parse(input);boolean probe=wire.path("max_tokens").asInt()==1;if(probe&&changePreAtPreflight)mutate(45L,"original_same_length_restored_mtime");String content="{";if(!probe){String preAlias=aliasFor(wire,"The DARA shall be appointed within 63 days"),sccAlias=aliasFor(wire,"share the DARA fees equally");String answer="The DARA shall be appointed within 63 days commencing from contract award "+preAlias+". The Employer and Contractor share the DARA fees equally "+sccAlias+".";content=AdviceBudgetFixture.contract("{\"grounded\":true,\"answer\":"+JsonUtils.write(answer)+",\"citations\":"+JsonUtils.write(Arrays.asList(preAlias,sccAlias))+"}");}ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("object","chat.completion");result.put("model","qwen3.8-27b");ObjectNode choice=result.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason","stop");choice.putObject("message").put("role","assistant").put("content",content);result.putObject("usage").put("prompt_tokens",1000).put("completion_tokens",1).put("total_tokens",1001);return JsonUtils.write(result);});
        AdviceService.QwenOperation operation=ReflectionTestUtils.invokeMethod(this,"authoredQwen",generationLedger,transport);
        NormalSourceSql.reads.clear();NormalSourceSql.bindingReads.clear();NormalSourceSql.capture=true;
        AskResponseVO response;
        try(AdviceService.QwenScope selected=advice.bindQwenOperation(operation)){if(changed){assertThrows(RuntimeException.class,()->advice.ask(PROJECT,new AskRequest(QUESTION,"fullset")));assertEquals(1,inputs.size());assertEquals(0,history.count());return;}response=advice.ask(PROJECT,new AskRequest(QUESTION,"fullset"));}
        finally{NormalSourceSql.capture=false;ReflectionTestUtils.setField(target,"fixedBaseline",null);assertTrue(NormalSourceSql.bindingReads.isEmpty(),"No whole binding CLOB entity read");assertTrue(NormalSourceSql.reads.isEmpty(),"No complete candidate graph entity read");assertTrue(mockingDetails(reader).getInvocations().stream().noneMatch(call->call.getMethod().getName().equals("normalDocument")));}
        assertFalse(inputs.isEmpty());
        assertTrue(inputs.get(0).contains("within 63 days"),"The actual prepared input must include the operative PRE appointment period, not only its heading");
        assertTrue(inputs.get(0).contains("apply in writing to HKIAC"));assertTrue(inputs.get(0).contains("share the DARA fees equally"));assertTrue(inputs.get(0).contains("meet monthly"));
        assertFalse(inputs.get(0).contains("UNRELATED_SCC_SIBLING"));assertFalse(inputs.get(0).contains("UNRELATED_PRE_SIBLING"));
        assertEquals(missing?3:2,retrieveCalls.get(),"The already verified operative window must be reused without another SCC search/stream pass");if(missing)assertTrue(scopeQueries.stream().anyMatch(q->q.contains("Clause 17.1")));
        assertTrue(scopeQueries.stream().noneMatch(q->q.contains("Clause 13.6")||q.contains("Clause 13.7")||q.contains("Clause 14.1")),"Contents/index references must not broaden the requested17.1 amendment query");
        assertTrue(response.isGrounded());assertTrue(response.getClarifications().isEmpty());assertEquals(2,response.getCitations().size());
        List<CitationVO> saved=JsonUtils.readList(history.findAll().stream().filter(row->"assistant".equals(row.getRole())).findFirst().orElseThrow().getSourcesJson(),CitationVO.class);
        for(Object[] expected:Arrays.asList(new Object[]{45L,pre.getSourceSha256(),"within 63 days"},new Object[]{43L,scc.getSourceSha256(),"share the DARA fees equally"})){
            CitationVO actual=response.getSources().stream().filter(source->source.getContent().contains((String)expected[2])).findFirst().orElseThrow();assertTrue(response.getCitations().contains(actual.getEvidenceId()));
            assertEquals(1,actual.getCanonicalEvidence().size());com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=actual.getCanonicalEvidence().get(0);assertEquals(expected[0],pointer.getDocumentId());assertEquals(expected[1],pointer.getOriginalSourceSha256());
            JsonNode original=snapshots.get((Long)expected[0]).path("chunks").findValues("content").stream().filter(value->value.asText().equals(actual.getContent())).findFirst().orElseThrow();
            JsonNode chunk=null;for(JsonNode row:snapshots.get((Long)expected[0]).path("chunks"))if(row.path("content").equals(original))chunk=row;assertNotNull(chunk);assertEquals(chunk.path("id").asText(),pointer.getCanonicalChunkId());assertEquals(chunk.path("unitId").asText(),pointer.getUnitId());
            CitationVO persisted=saved.stream().filter(source->actual.getEvidenceId().equals(source.getEvidenceId())).findFirst().orElseThrow();assertEquals(JsonUtils.write(actual.getCanonicalEvidence()),JsonUtils.write(persisted.getCanonicalEvidence()));assertEquals(actual.getContent(),persisted.getContent());
        }
    }
    private String aliasFor(JsonNode wire,String marker){String text="";for(JsonNode message:wire.path("messages"))if("user".equals(message.path("role").asText()))text=message.path("content").asText();int at=text.indexOf(marker);assertTrue(at>=0,"The model cannot cite an operative passage absent from its actual prepared input");java.util.regex.Matcher header=java.util.regex.Pattern.compile("(?m)^\\[[0-9]+\\] ").matcher(text);String alias=null;while(header.find()&&header.start()<at)alias=header.group().trim();assertNotNull(alias);return alias;}
    private SourceDocument additional(long id,String key,String name,List<String> texts)throws Exception {
        SourceDocument source=new SourceDocument();source.setProjectId(PROJECT);source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setParseStatus("PARSED");
        Path original=temporary.resolve("authored-original-"+id+".docx");byte[] bytes=Arrays.copyOf(Files.readAllBytes(originals.get(43L)),(int)Files.size(originals.get(43L))+1);bytes[bytes.length-1]=(byte)id;Files.write(original,bytes);source.setStoragePath(original.toString());source.setSourceSha256(shaBytes(bytes));
        OerDocumentIdentity identity=new OerDocumentIdentity();identity.setSourceSha256(source.getSourceSha256());identity.setKind(key);identity.setSourcePurpose("project_document");identity.setRole("tender");identity.setIdentityStatus("content_supported");identity.setEligibleModules(Arrays.asList("vetting","advisory"));source.setOerIdentityJson(JsonUtils.write(identity));normal(source,key,name,texts);assertEquals(id,documents.saveAndFlush(source).getId());originals.put(id,original);return source;
    }
    private void normal(SourceDocument source,String key,String name,List<String> texts){source.setFileKey(key);source.setFileName(name);source.setTextContent(String.join("\n",texts));List<DocumentBlock> blocks=new ArrayList<>();for(int i=0;i<texts.size();i++){DocumentBlock b=new DocumentBlock();b.setId("authored:"+key+":"+i);b.setSource("native");b.setLocation("body/"+i+"/paragraph");b.setText(texts.get(i));blocks.add(b);}source.setStructuredContentJson(JsonUtils.write(blocks));source.setNormalCandidateJson(null);source.setParseCoverageJson("{\"complete\":true}");}
    private FixedReferenceEvidence standard(String id,String content)throws Exception {String sha=repeat('e');ObjectNode payload=JsonUtils.mapper().createObjectNode();payload.put("fileKey","GCC");payload.put("fileLabel","GCC");payload.put("pageNo",65);payload.put("content",content);payload.put("sourceHash",sha);payload.put("originalSourceSha256",sha);payload.put("fixedReferenceDocumentTitle",TITLE);payload.put("fixedDevelopmentBaseline",true);payload.put("role","standard");boolean index=id.endsWith("contents");payload.put("observedNodeKind",index?"document":"clause");payload.put("observedSourceRole",index?"content":"body");if(index)payload.putNull("clauseId");else payload.put("clauseId","17.1");payload.putObject("sourceQuality").put("family","GCC");java.lang.reflect.Constructor<FixedReferenceEvidence> ctor=FixedReferenceEvidence.class.getDeclaredConstructor(String.class,double.class,JsonNode.class,JsonNode.class,JsonNode.class);ctor.setAccessible(true);return ctor.newInstance(id,.9,payload,null,null);}
}
