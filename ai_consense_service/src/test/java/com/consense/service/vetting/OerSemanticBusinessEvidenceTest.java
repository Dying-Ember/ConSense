package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Authored compact protocol doubles; no database, source OCR, retrieval model or LLM calls. */
class OerSemanticBusinessEvidenceTest {
    @Test void sharedDictionaryGroupHasOneSessionTemplateAndOneModelSubmission()throws Exception {
        Fixture f=new Fixture(2);f.dictionary=true;List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertSame(chunks.get(0).getCanonicalPayload().path("semanticUnit"),chunks.get(1).getCanonicalPayload().path("semanticUnit"));
        assertFalse(chunks.get(0).getCanonicalPayload().path("semanticUnit").has("members"));
        List<Map<String,Object>> model=VettingSourceMaterial.project(chunks);
        assertNotNull(model.get(0).get("semanticUnit"));assertNull(model.get(1).get("semanticUnit"));
        assertEquals("complete",model.get(0).get("unitReadStatus"));assertEquals("complete",model.get(1).get("unitReadStatus"));
        assertEquals("AUTHORED obligation 0",((JsonNode)model.get(0).get("semanticMember")).path("text").asText());
        assertEquals("AUTHORED obligation 1",((JsonNode)model.get(1).get("semanticMember")).path("text").asText());
    }
    @Test void oversizedDictionaryMemberRetainsReadableScopeAndExactPendingDenominator()throws Exception {
        Fixture f=new Fixture(2);f.dictionary=true;f.blockedFirst=true;List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(1,chunks.size());assertEquals("unit-1",chunks.get(0).getId());JsonNode coverage=f.session.semanticReadCoverage();
        assertEquals("partial",coverage.path("status").asText());assertEquals(2,coverage.path("totalOrigins").asInt());assertEquals(1,coverage.path("readableOrigins").asInt());
        assertEquals("unit-0",coverage.path("blockedOrigins").get(0).path("id").asText());
        assertEquals("unknown",VettingRetrievalSourceUnits.attached(chunks).get(0).status);
        Map<String,Object> model=VettingSourceMaterial.project(chunks).get(0);assertEquals("incomplete",model.get("unitReadStatus"));assertEquals(Collections.singleton("unit-0"),model.get("unavailableMemberIds"));
    }
    @Test void aThousandOrderedUnitsAreScannedOnceAndWarmReadsNeverLoadTheOldGraph()throws Exception {
        Fixture f=new Fixture(1000);long started=System.nanoTime();List<VettingCorpus.Chunk> chunks=f.service.scan(f.session);
        assertEquals(1000,chunks.size());assertTrue(OerBusinessEvidence.isSemanticCorpus(chunks));assertEquals("unit-0",chunks.get(0).getId());assertEquals("unit-999",chunks.get(999).getId());
        assertEquals(16,f.scans);assertTrue(chunks.get(5).getCanonicalPayload().path("semanticUnit").path("members").get(0).path("tableGroup").path("unit").asText().equals("days"));
        assertEquals("source anchor 5",chunks.get(5).getParts().get(0).getAnchor());assertNull(OerBusinessEvidence.canonicalViewStatus(chunks.get(5)));
        Map<String,JsonNode> first=f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(5)));((ObjectNode)first.get("unit-5")).put("changedByCaller",true);
        assertFalse(f.service.wordSourceScopeContexts(f.session,Collections.singletonList(chunks.get(5))).get("unit-5").has("changedByCaller"));
        assertSame(chunks,f.service.scan(f.session));assertEquals(16,f.scans);f.service.assertCurrent(f.session);
        verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());
        verify(f.evidence,never()).wordSourceScopeContext(anyString(),anyLong(),anyString(),anyString(),anyString());verify(f.retrieval,never()).index(anyString(),anyString(),anyList());
        assertTrue(f.catalogs>=5);System.out.println("AUTHORED_COMPACT_WARM units=1000 scanBatches="+f.scans+" catalogChecks="+f.catalogs+" elapsedMs="+(System.nanoTime()-started)/1000000);
        List<VettingRetrievalSourceUnits.Unit> closures=VettingRetrievalSourceUnits.attached(chunks);assertEquals(1000,closures.size());assertEquals(Arrays.asList("unit-0","unit-1"),closures.get(0).ids);
        VettingContextBuilder.Selection selection=new VettingContextBuilder(chunks).build("AUTHORED obligations",Collections.singletonList(chunks.get(0)),Collections.emptyMap(),0);
        VettingSourceRequestPacks.Result plan=new VettingSourceRequestPacks(chunks).planForCompleteInputBudget("AUTHORED obligations",Collections.singletonList(chunks.get(0)),selection,Collections.singletonList(closures.get(0)));
        assertTrue(plan.getRequests().stream().anyMatch(r->"retrieved_structural_unit".equals(r.getKind())&&r.getRequiredChunkIds().equals(Arrays.asList("unit-0","unit-1"))));
        chunks.get(0).setContent("modified after validation");assertThrows(IllegalStateException.class,()->f.service.assertCurrent(f.session));
    }
    @Test void anIncompletePublicUnitCannotPublishAPartialWholeScope()throws Exception {
        Fixture f=new Fixture(2);f.blocked=true;assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());
    }
    @Test void aRejectedClosureCannotLeavePartOfAScanPublished()throws Exception {
        Fixture f=new Fixture(2);f.invalidClosure=true;assertThrows(IllegalStateException.class,()->f.service.scan(f.session));assertEquals(0,f.session.chunkCount());
        assertTrue(VettingRetrievalSourceUnits.attached(f.session.chunks()).isEmpty());
    }
    @Test void warmRuleReadsRetainTheColdNativeContextWithoutRebindingStrippedSemanticParts()throws Exception {
        Fixture f=new Fixture(2);f.service.scan(f.session);assertFalse(f.session.chunks().get(0).getCanonicalPayload().path("parts").get(0).has("sourcePosition"));
        List<VettingRuleEngine.Source> sources=f.service.ruleSources(f.session,Collections.singletonList(new SourceDocument()));com.consense.document.DocumentBlock block=sources.get(0).getBlocks().get(0);
        assertEquals("source_bound",block.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("status").asText());assertEquals("footer",block.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("storyKind").asText());
        assertEquals("AUTHORED native section",block.getReadingContext().path("nativeDocxSection").path("sectionId").asText());assertEquals("/AUTHORED/native/footer",block.getSourcePosition().path("nodePath").asText());
        block.setText("caller changed text");((ObjectNode)block.getReadingContext()).removeAll();
        com.consense.document.DocumentBlock again=f.service.ruleSources(f.session).get(0).getBlocks().get(0);assertEquals("AUTHORED rule source",again.getText());assertEquals("source_bound",again.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("status").asText());
        verify(f.evidence,never()).verifiedBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).currentBindings(anyString(),anyString(),anyList());
    }
    @Test void anExactSourceQuoteRemainsLocatedWhenPhysicalCoordinatesWereNotRequested()throws Exception {
        Fixture f=new Fixture(2);VettingCorpus.Chunk chunk=f.service.scan(f.session).get(0);com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(chunk,"tender",chunk.getContent());
        assertTrue(quote.isLocated());assertNull(quote.getBbox());com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer original=JsonUtils.read(JsonUtils.write(chunk.getCanonicalEvidence()),com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class);original.setAddressKind("normal_observed_unit");original.setProjectionSchema("consense.normal-observed.v1");original.setOriginalGeometryStatus("unknown");
        com.consense.web.dto.VettingDtos.FindingEvidence fixed=new com.consense.web.dto.VettingDtos.FindingEvidence();fixed.setLocated(true);fixed.setFixedBaselineEvidence(JsonUtils.parse("{\"authority\":\"authored-fixed-library\"}"));
        when(f.evidence.pointer(eq("authored"),eq(42L),anyString(),eq("source-unit-0"),eq("vetting"))).thenReturn(original);f.service.bindReviewEvidence(f.session,Arrays.asList(quote,fixed,quote));
        assertTrue(fixed.isLocated());assertNull(fixed.getCanonicalEvidence());assertEquals("authored-fixed-library",fixed.getFixedBaselineEvidence().path("authority").asText());
        assertTrue(quote.isLocated());assertEquals("unknown",quote.getCanonicalEvidence().getOriginalGeometryStatus());assertNull(quote.getBbox());verify(f.evidence).pointer(eq("authored"),eq(42L),anyString(),eq("source-unit-0"),eq("vetting"));
    }
    @Test void coldVerifiedClauseOwnershipSurvivesSemanticScanWithoutSendingLocationsToTheModel()throws Exception {
        for(boolean verifiedHeading:Arrays.asList(true,false)){
            OerBusinessEvidenceTest.Wire w=new OerBusinessEvidenceTest.Wire();w.document.setFileKey("SCT");w.document.setFileName("AUTHORED SCT.docx");
            String heading=w.snapshot.path("chunks").get(0).path("clauseHeadingLocation").asText();
            for(JsonNode raw:w.snapshot.path("chunks")){ObjectNode chunk=(ObjectNode)raw;chunk.put("clauseId","SCT1");if(!verifiedHeading)chunk.remove("clauseHeadingLocation");}w.store();
            Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);view.setAccessible(true);
            Constructor<?> frameConstructor=CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);frameConstructor.setAccessible(true);
            CanonicalEvidenceService.VerifiedBindingFrame frame=(CanonicalEvidenceService.VerifiedBindingFrame)frameConstructor.newInstance(w.registry,w.registry,"project","vetting",Collections.singletonList(view.newInstance(w.binding,w.snapshot,false)));
            Constructor<?> scopeConstructor=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class);scopeConstructor.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeConstructor.newInstance(w.registry,"project","vetting",Collections.singletonList(w.binding),Collections.emptyMap(),Collections.emptyMap());
            OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(w.binding,w.snapshot.path("chunks").size());
            when(w.registry.verifiedBindings("project","vetting",Collections.singletonList(42L))).thenReturn(frame);
            when(w.registry.compactBindings(eq(frame),anyList())).thenReturn(scope);when(w.client.prepareSemanticSources(eq("vetting"),anyList())).thenReturn(selected);
            when(w.client.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("origins");result.putArray("units");result.putNull("nextCursor");
                if("scan".equals(demand.path("operation").asText())){
                    for(JsonNode raw:w.snapshot.path("chunks")){ObjectNode origin=semanticOrigin(raw,w.binding);result.withArray("origins").add(origin);ObjectNode unit=result.withArray("units").addObject();unit.put("status","complete_observed_unit").put("unitId",sha(raw.path("id").asText()));unit.putArray("originIds").add(origin.path("id").asText());unit.putArray("requiredMemberIds").add(origin.path("id").asText());unit.putArray("members").add(origin.deepCopy());}
                    result.putObject("coverage").put("status","complete").put("processedOrigins",w.snapshot.path("chunks").size());
                }return result;});
            OerBusinessEvidence.Session session=w.service.loadSemantic("project","vetting",Collections.singletonList(w.document));VettingCorpus.Chunk chunk=w.service.scan(session).get(0);
            assertEquals("SCT",chunk.getFileKey());assertEquals(w.document.getFileName(),chunk.getFileName());assertEquals("SCT1",chunk.getClauseId());assertEquals(verifiedHeading?heading:null,chunk.getClauseHeadingLocation());
            assertEquals(w.snapshot.path("chunks").get(0).path("parts").get(0).path("anchor").asText(),chunk.getAnchor());assertEquals(chunk.getAnchor(),chunk.getParts().get(0).getAnchor());
            assertEquals(w.binding.getOriginalSourceSha256(),chunk.getCanonicalEvidence().getOriginalSourceSha256());assertEquals(w.binding.getProjectionSha256(),chunk.getCanonicalEvidence().getProjectionSha256());assertEquals(w.binding.getJavaParsedSourceHash(),chunk.getCanonicalEvidence().getJavaParsedSourceHash());assertNull(OerBusinessEvidence.canonicalViewStatus(chunk));
            assertFalse(chunk.getCanonicalPayload().has("clauseHeadingLocation"));assertFalse(chunk.getCanonicalPayload().path("parts").get(0).has("anchor"));
            JsonNode model=VettingSourceMaterial.semantic(VettingSourceMaterial.project(Collections.singletonList(chunk)),Collections.emptyMap());assertTrue(model.findValues("clauseHeadingLocation").isEmpty());assertTrue(model.findValues("anchor").isEmpty());assertFalse(model.toString().contains(heading));
            JsonNode context=w.service.wordSourceScopeContexts(session,Collections.singletonList(chunk)).get(chunk.getId());assertTrue(context.findValues("clauseHeadingLocation").isEmpty());assertFalse(context.toString().contains(heading));
            VettingSemanticReview.Quote quote=new VettingSemanticReview.Quote();quote.setChunkId(chunk.getId());quote.setSide("tender");quote.setQuote(chunk.getContent());assertTrue(VettingCorpus.evidence(chunk,quote.getSide(),quote.getQuote()).isLocated());
            VettingSemanticReview.Record record=new VettingSemanticReview.Record();record.setComment("Clause 1 requires the recorded contract obligations.");record.setEvidence(Collections.singletonList(quote));
            List<Map<String,Object>> reasons=VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(chunk.getId(),chunk));
            if(verifiedHeading)assertTrue(reasons.isEmpty(),reasons.toString());else {assertEquals(1,reasons.size());assertEquals("unsupported_source_clause_attribution",reasons.get(0).get("reason"));}
            record.setComment("SCT Clause 99 requires the recorded contract obligations.");assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(record),Collections.singletonMap(chunk.getId(),chunk)).size());
            verify(w.registry,never()).currentBindings(anyString(),anyString(),anyList());verify(w.registry,times(1)).verifiedBindings(anyString(),anyString(),anyList());verify(w.client,never()).index(anyString(),anyString(),anyList());
        }
    }
    private static ObjectNode semanticOrigin(JsonNode raw,CanonicalSourceBinding binding){
        ObjectNode origin=JsonUtils.mapper().createObjectNode();for(String field:Arrays.asList("id","unitId","documentId","role","clauseId","sourceHash","eligibleModules","sourceOrder","sourceQuality"))origin.set(field,raw.path(field).deepCopy());
        origin.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);origin.put("text",raw.path("content").asText());origin.putArray("unknowns");origin.putObject("evidenceRef").put("projectionSha256",binding.getProjectionSha256()).put("canonicalChunkId",origin.path("id").asText()).put("originalSourceSha256",binding.getOriginalSourceSha256());
        for(JsonNode part:raw.path("parts")){ObjectNode text=origin.withArray("parts").addObject();for(String field:Arrays.asList("blockId","text","startOffset","endOffset"))text.set(field,part.path(field).deepCopy());}return origin;
    }
    private static OerCanonicalRetrievalClient.SemanticSources semanticSources(CanonicalSourceBinding binding,int count)throws Exception {
        Class<?> capability=Class.forName(OerCanonicalRetrievalClient.class.getName()+"$Capability");Constructor<?> health=capability.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);health.setAccessible(true);
        Object identity=health.newInstance(sha("health"),"AUTHORED source unit policy","unused",true,true,sha("software"));ObjectNode prepared=JsonUtils.mapper().createObjectNode();prepared.putArray("projectionSha256").add(binding.getProjectionSha256());prepared.putArray("readManifestSha256").add(sha("manifest"));prepared.put("readManifestToken",sha("token"));prepared.putArray("sources");
        Constructor<?> sources=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(String.class,String.class,capability,String.class,JsonNode.class,List.class,int.class);sources.setAccessible(true);
        return (OerCanonicalRetrievalClient.SemanticSources)sources.newInstance("vetting",binding.getScopePolicySha256(),identity,sha("runtime"),prepared,Collections.singletonList(binding),count);
    }
    private static final class Fixture {
        final CanonicalEvidenceService evidence=mock(CanonicalEvidenceService.class);
        final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final OerBusinessEvidence service=new OerBusinessEvidence(evidence,retrieval,new com.consense.config.CanonicalEvidenceProperties());
        final OerBusinessEvidence.Session session;int scans,catalogs;boolean blocked,invalidClosure,dictionary,blockedFirst;
        Fixture(int count)throws Exception {
            CanonicalSourceBinding binding=new CanonicalSourceBinding();binding.setId(3L);binding.setProjectId("authored");binding.setDocumentId(42L);binding.setProjectionSha256(sha("projection"));binding.setOriginalSourceSha256(sha("original"));binding.setJavaParsedSourceHash(sha("parsed"));binding.setCanonicalSourceHash(sha("canonical"));binding.setSourceRevisionSha256(sha("source revision"));binding.setProjectionRevisionSha256(sha("projection revision"));binding.setScopePolicySha256(sha("policy"));binding.setSnapshotBytesSha256(sha("snapshot"));binding.setSnapshotContentSha256(binding.getSnapshotBytesSha256());binding.setDeclaredModule("vetting");binding.setEligibleModulesJson("[\"vetting\"]");
            Constructor<?> compact=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class);compact.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)compact.newInstance(evidence,"authored","vetting",Collections.singletonList(binding),Collections.emptyMap(),Collections.emptyMap());
            OerCanonicalRetrievalClient.SemanticSources selected=semanticSources(binding,count);
            SourceDocument document=new SourceDocument();document.setId(42L);document.setProjectId("authored");document.setFileKey("PRE");document.setFileName("AUTHORED PRE.docx");
            Map<String,JsonNode> contexts=new LinkedHashMap<>();Map<String,List<String>> anchors=new LinkedHashMap<>();for(int i=0;i<count;i++){contexts.put("unit-"+i,JsonUtils.parse("{\"observedSourceUnits\":{\"status\":\"unknown\",\"reason\":\"AUTHORED unverified table\"}}"));anchors.put("unit-"+i,Collections.singletonList("source anchor "+i));}
            com.consense.document.DocumentBlock nativeRule=new com.consense.document.DocumentBlock();nativeRule.setText("AUTHORED rule source");nativeRule.setOriginalText(nativeRule.getText());nativeRule.setSourcePosition(JsonUtils.parse("{\"kind\":\"docx_native\",\"nodePath\":\"/AUTHORED/native/footer\"}"));ObjectNode nativeContext=JsonUtils.mapper().createObjectNode();nativeContext.putObject(VettingPackageIdentity.CONTEXT).put("status","source_bound").put("storyKind","footer");nativeContext.putObject("nativeDocxSection").put("sectionId","AUTHORED native section");nativeRule.setReadingContext(nativeContext);
            List<VettingRuleEngine.Source> rules=Collections.singletonList(new VettingRuleEngine.Source("42","PRE","AUTHORED PRE.docx",binding.getJavaParsedSourceHash(),Collections.singletonList(nativeRule),nativeRule.getText()));
            Constructor<?> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,CanonicalEvidenceService.CompactBindingScope.class,OerCanonicalRetrievalClient.SemanticSources.class,Map.class,Map.class,Map.class,List.class,Map.class);constructor.setAccessible(true);
            session=(OerBusinessEvidence.Session)constructor.newInstance("authored","vetting",scope,selected,Collections.singletonMap(42L,document),contexts,anchors,rules,Collections.emptyMap());
            when(retrieval.readSemanticSources(eq(selected),any())).thenAnswer(call->{JsonNode demand=call.getArgument(1);ObjectNode response=JsonUtils.mapper().createObjectNode();response.putArray("origins");response.putArray("units");ObjectNode coverage=response.putObject("coverage");response.putNull("nextCursor");
                if("catalog".equals(demand.path("operation").asText())){catalogs++;return response;}
                if(dictionary){scans++;response.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);response.putObject("originUnits");response.putArray("blockedOrigins");
                    String group=sha("shared closure");ObjectNode unit=response.withArray("units").addObject();unit.put("groupId",group);ObjectNode template=unit.putObject("unit");template.put("unitId",sha("shared closure unit"));template.put("status","complete_observed_unit");template.putArray("requiredMemberIds").add("unit-0").add("unit-1");
                    for(int i=0;i<count;i++)if(blockedFirst&&i==0)response.withArray("blockedOrigins").addObject().put("id","unit-0").put("documentId","42").put("reason","semantic_member_or_group_too_large");else {response.withArray("origins").add(origin(binding,i));((ObjectNode)response.path("originUnits")).put("unit-"+i,group);}
                    coverage.put("processedOrigins",count);coverage.put("status",blockedFirst?"complete_with_blocked":"complete");return response;}
                scans++;int offset=demand.path("cursor").asInt(0),end=Math.min(count,offset+64);coverage.put("status",blocked?"unit_too_large":end<count?"partial":"complete");coverage.put("processedOrigins",end);
                if(!blocked)for(int i=offset;i<end;i++){ObjectNode origin=origin(binding,i);response.withArray("origins").add(origin);ObjectNode unit=response.withArray("units").addObject();unit.put("status","complete_observed_unit");unit.put("unitId",sha("closure"+i));unit.putArray("originIds").add("unit-"+i);unit.putArray("requiredMemberIds").add("unit-"+i);unit.putArray("members").add(origin.deepCopy());if(i==0&&count>1){unit.withArray("requiredMemberIds").add("unit-1");unit.withArray("members").add(origin(binding,1));}}
                if(invalidClosure&&!response.path("units").isEmpty())((ObjectNode)response.path("units").get(0).path("members").get(0)).put("text","AUTHORED changed closure text");
                if(end<count)response.put("nextCursor",end);return response;});
        }
    }
    private static ObjectNode origin(CanonicalSourceBinding binding,int index){
        ObjectNode origin=JsonUtils.mapper().createObjectNode();origin.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);origin.put("id","unit-"+index);origin.put("unitId","source-unit-"+index);origin.put("documentId","42");origin.put("role","tender");origin.put("text","AUTHORED obligation "+index);origin.put("sourceHash",binding.getCanonicalSourceHash());origin.put("producerKind","normal_observed");origin.putArray("eligibleModules").add("vetting");origin.putArray("unknowns");origin.putObject("sourceQuality").put("semanticQuality","unknown");
        origin.putObject("evidenceRef").put("projectionSha256",binding.getProjectionSha256()).put("canonicalChunkId",origin.path("id").asText()).put("originalSourceSha256",binding.getOriginalSourceSha256());
        origin.putArray("parts").addObject().put("blockId","block-"+index).put("text",origin.path("text").asText()).put("startOffset",0).put("endOffset",origin.path("text").asText().length());origin.putObject("tableGroup").put("unit","days").put("qualifiersComplete","unknown");return origin;
    }
    private static String sha(String text){return VettingCorpus.hash(text);}
}
