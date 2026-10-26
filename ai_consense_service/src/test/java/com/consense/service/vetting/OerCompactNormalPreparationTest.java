package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalCurrentSourceReader;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.NormalObservedContract;
import com.consense.service.sources.SourceContentDigests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Complete authored scope; transport and authority boundaries are doubled independently. */
class OerCompactNormalPreparationTest {
    @Test void allThirtyThreeStampedNormalSourcesAvoidTheInitialStoredSnapshotFrame()throws Exception {
        Fixture f=new Fixture();OerBusinessEvidence.Session session=f.service.loadSemantic("authored","vetting",f.sources);
        assertEquals(33,session.documentIds.size());assertEquals(0,session.chunkCount());assertEquals(33,f.service.ruleSources(session).size());
        verify(f.evidence,never()).verifiedSemanticBindings(anyString(),anyString(),anyList());verify(f.evidence,never()).prepareVettingSource(any());
        verify(f.retrieval,times(1)).prepareCompactNormalSources(eq("vetting"),anyList());verify(f.evidence,times(1)).attestCompactNormalSources(eq(f.preparation),eq(f.proofs));
        @SuppressWarnings("unchecked") Map<String,List<String>> anchors=(Map<String,List<String>>)ReflectionTestUtils.getField(session,"semanticAnchors");assertFalse(anchors.isEmpty());
        int expectedCount=0;for(SourceDocument source:f.sources)for(JsonNode unit:f.graphs.get(source.getId()).path("units"))if(!NormalObservedContract.noObservedText(unit.path("text").asText())){
            CanonicalSourceBinding binding=f.bindings.get(f.sources.indexOf(source));String id=NormalObservedContract.canonicalChunkId(binding.getProjectionRevisionSha256(),unit.path("unitId").asText());
            List<String> expected=new ArrayList<>();for(JsonNode part:unit.path("parts"))expected.add(part.path("anchor").asText());assertEquals(expected,anchors.get(id));expectedCount++;
        }assertEquals(expectedCount,anchors.size());verify(f.retrieval,never()).prepareSemanticSources(anyString(),anyList());
    }
    @Test void rejectedNativeProofCannotPublishThePreparedScopeOrScan()throws Exception {
        Fixture f=new Fixture();doThrow(new IllegalStateException("AUTHORED native candidate graph changed")).when(f.evidence).attestCompactNormalSources(any(),any());
        assertEquals("AUTHORED native candidate graph changed",assertThrows(IllegalStateException.class,()->f.service.loadSemantic("authored","vetting",f.sources)).getMessage());
        verify(f.retrieval,never()).readSemanticSources(any(),any());verify(f.evidence,never()).verifiedSemanticBindings(anyString(),anyString(),anyList());
    }
    @Test void unsupportedCompactCapabilityRetainsTheExistingFramePath()throws Exception {
        Fixture f=new Fixture();when(f.retrieval.compactNormalPreparationAvailable(anyString(),anyList())).thenReturn(false);
        assertEquals("AUTHORED old frame path",assertThrows(IllegalStateException.class,()->f.service.loadSemantic("authored","vetting",f.sources)).getMessage());
        verify(f.evidence,times(1)).verifiedSemanticBindings(anyString(),anyString(),anyList());verify(f.retrieval,never()).prepareCompactNormalSources(anyString(),anyList());
    }
    @Test void admissionLogsOnePayloadFreeDecisionForEligibleForeignCapabilityAndStoredAuthorityPaths()throws Exception {
        for(String reason:Arrays.asList("eligible","foreign_latest_binding","native_capability_unavailable","stored_authority_not_normal")){
            Fixture f=new Fixture();ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OerBusinessEvidence.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> log=new ch.qos.logback.core.read.ListAppender<>();log.start();logger.addAppender(log);
            try{
                if("foreign_latest_binding".equals(reason))doAnswer(call->{java.util.function.BiConsumer<String,Integer> record=call.getArgument(3);record.accept(reason,2);return null;}).when(f.evidence).compactNormalCandidates(anyString(),anyString(),anyList(),any());
                if("native_capability_unavailable".equals(reason))when(f.retrieval.compactNormalPreparationAvailable(anyString(),anyList())).thenReturn(false);
                if("stored_authority_not_normal".equals(reason))doAnswer(call->{java.util.function.BiConsumer<String,Integer> record=call.getArgument(5);record.accept(reason,7);return null;}).when(f.evidence).prepareCompactNormalSources(anyString(),anyString(),anyList(),anyList(),any(),any());
                if("eligible".equals(reason))assertNotNull(f.service.loadSemantic("authored","vetting",f.sources));else assertEquals("AUTHORED old frame path",assertThrows(IllegalStateException.class,()->f.service.loadSemantic("authored","vetting",f.sources)).getMessage());
                List<String> decisions=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:log.list)if(event.getFormattedMessage().startsWith("Vetting compact normal admission "))decisions.add(event.getFormattedMessage());
                int prefix="foreign_latest_binding".equals(reason)?2:"stored_authority_not_normal".equals(reason)?7:33;
                assertEquals(Collections.singletonList("Vetting compact normal admission selectedSources=33 qualifiedPrefix="+prefix+" reason="+reason),decisions);assertFalse(decisions.get(0).contains("AUTHORED"));
                if(!"eligible".equals(reason))verify(f.retrieval,never()).prepareCompactNormalSources(anyString(),anyList());
            }finally{logger.detachAppender(log);log.stop();}
        }
    }
    @Test void currentGraphRuleInputsMatchTheExistingAcceptedChunkAdapter()throws Exception {
        Fixture f=new Fixture();SourceDocument source=f.sources.get(0);JsonNode graph=f.graphs.get(source.getId());CanonicalSourceBinding binding=f.bindings.get(0);
        Map<String,List<String>> anchors=new LinkedHashMap<>();List<VettingRuleEngine.Source> actual=new ArrayList<>();
        Method compact=OerBusinessEvidence.class.getDeclaredMethod("prepareCompactNormalRuleSource",String.class,SourceDocument.class,JsonNode.class,CanonicalSourceBinding.class,Map.class,List.class);compact.setAccessible(true);compact.invoke(null,"authored",source,graph,binding,anchors,actual);
        List<com.consense.document.DocumentBlock> expected=new ArrayList<>();
        for(JsonNode unit:graph.path("units"))if(!NormalObservedContract.noObservedText(unit.path("text").asText())){
            JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,unit);com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer pointer=CanonicalWordSourceContextTest.pointer(CanonicalWordSourceContextTest.binding(source),graph,canonical);
            Method view=OerBusinessEvidence.class.getDeclaredMethod("view",JsonNode.class,com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer.class,SourceDocument.class);view.setAccessible(true);
            VettingCorpus.Chunk chunk=(VettingCorpus.Chunk)view.invoke(f.service,canonical,pointer,source);
            for(VettingCorpus.Part part:chunk.getParts()){
                com.consense.document.DocumentBlock block=new com.consense.document.DocumentBlock();block.setId(part.getBlockId());block.setLocation(part.getAnchor());block.setText(part.getText());block.setOriginalText(part.getText());block.setSource("normal_observed_candidate");if(part.getPageNo()!=null)block.setPageNo(Integer.valueOf(part.getPageNo().substring(1)));
                OerBusinessEvidence.bindRuleContext("authored",source,chunk,part,block);expected.add(block);
            }
        }
        assertEquals(1,actual.size());assertEquals(JsonUtils.mapper().valueToTree(expected),JsonUtils.mapper().valueToTree(actual.get(0).getBlocks()));
        assertTrue(expected.stream().anyMatch(block->"source_bound".equals(block.getReadingContext().path(VettingPackageIdentity.CONTEXT).path("status").asText())));
    }
    private static final class Fixture {
        final CanonicalEvidenceService evidence=mock(CanonicalEvidenceService.class);final OerCanonicalRetrievalClient retrieval=mock(OerCanonicalRetrievalClient.class);
        final CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();final OerBusinessEvidence service;
        final List<SourceDocument> sources=new ArrayList<>();final List<CanonicalSourceBinding> bindings=new ArrayList<>();final Map<Long,JsonNode> graphs=new LinkedHashMap<>();final ArrayNode proofs=JsonUtils.mapper().createArrayNode();
        final CanonicalEvidenceService.CompactNormalPreparation preparation;
        Fixture()throws Exception {
            config.setBaseUrl("http://127.0.0.1:1");service=new OerBusinessEvidence(evidence,retrieval,config);SourceDocument authored=CanonicalWordSourceContextTest.source();Map<Long,SourceDocument> pins=new LinkedHashMap<>();
            for(int n=0;n<33;n++){
                SourceDocument source=new SourceDocument();org.springframework.beans.BeanUtils.copyProperties(authored,source);source.setId(1000L+n);source.setFileName("AUTHORED document "+(n+1)+".docx");NormalSourceCandidateGraph.capture(source);stamp(source);sources.add(source);graphs.put(source.getId(),NormalSourceCandidateGraph.requireBound(source));
                CanonicalSourceBinding binding=CanonicalWordSourceContextTest.binding(source);binding.setId(2000L+n);binding.setProjectionSha256(sha("projection "+n));binding.setProjectionRevisionSha256(sha("revision "+n));binding.setScopePolicySha256(sha("policy"));binding.setDeclaredModule("vetting");bindings.add(binding);proofs.addObject().put("projectionSha256",binding.getProjectionSha256());pins.put(source.getId(),CanonicalCurrentSourceReader.compactVerifiedSource(source));
            }
            Constructor<?> scopeType=CanonicalEvidenceService.CompactBindingScope.class.getDeclaredConstructor(CanonicalEvidenceService.class,String.class,String.class,List.class,Map.class,Map.class,Map.class);scopeType.setAccessible(true);
            CanonicalEvidenceService.CompactBindingScope scope=(CanonicalEvidenceService.CompactBindingScope)scopeType.newInstance(evidence,"authored","vetting",bindings,Collections.emptyMap(),pins,Collections.emptyMap());
            Constructor<?> prepType=CanonicalEvidenceService.CompactNormalPreparation.class.getDeclaredConstructor(CanonicalEvidenceService.CompactBindingScope.class,Map.class,Map.class);prepType.setAccessible(true);preparation=(CanonicalEvidenceService.CompactNormalPreparation)prepType.newInstance(scope,Collections.emptyMap(),Collections.emptyMap());
            when(evidence.compactNormalCandidates(anyString(),anyString(),anyList(),any())).thenAnswer(call->{java.util.function.BiConsumer<String,Integer> record=call.getArgument(3);record.accept("candidate_metadata_ready",bindings.size());return bindings;});when(retrieval.compactNormalPreparationAvailable(anyString(),anyList())).thenReturn(true);
            when(evidence.prepareCompactNormalSources(anyString(),anyString(),anyList(),anyList(),any(),any())).thenAnswer(call->{@SuppressWarnings("unchecked") BiConsumer<SourceDocument,JsonNode> callback=call.getArgument(4);for(SourceDocument source:sources)callback.accept(source,graphs.get(source.getId()));return preparation;});
            doAnswer(call->{ReflectionTestUtils.setField(preparation,"attested",true);return null;}).when(evidence).attestCompactNormalSources(any(),any());
            when(evidence.verifiedSemanticBindings(anyString(),anyString(),anyList())).thenThrow(new IllegalStateException("AUTHORED old frame path"));
            Class<?> capabilityType=Class.forName(OerCanonicalRetrievalClient.class.getName()+"$Capability");Constructor<?> capability=capabilityType.getDeclaredConstructor(String.class,String.class,String.class,boolean.class,boolean.class,String.class);capability.setAccessible(true);
            ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.putArray("projectionSha256");receipt.putArray("readManifestSha256");receipt.putArray("sources");receipt.set("normalProofs",proofs);receipt.put("readManifestToken",sha("token"));
            Constructor<?> semanticType=OerCanonicalRetrievalClient.SemanticSources.class.getDeclaredConstructor(String.class,String.class,capabilityType,String.class,JsonNode.class,List.class,int.class);semanticType.setAccessible(true);
            OerCanonicalRetrievalClient.SemanticSources semantic=(OerCanonicalRetrievalClient.SemanticSources)semanticType.newInstance("vetting",sha("policy"),capability.newInstance(sha("health"),"AUTHORED policy","unused",true,true,sha("software")),sha("runtime"),receipt,bindings,33);
            when(retrieval.prepareCompactNormalSources(anyString(),anyList())).thenReturn(semantic);
        }
    }
    private static String sha(String value){return VettingCorpus.hash(value);}
    private static void stamp(SourceDocument source){source.setTextContentSha256(SourceContentDigests.field(source.getTextContent()));source.setStructuredContentSha256(SourceContentDigests.field(source.getStructuredContentJson()));source.setParseCoverageSha256(SourceContentDigests.field(source.getParseCoverageJson()));source.setNormalCandidateSha256(SourceContentDigests.field(source.getNormalCandidateJson()));source.setBusinessContentSha256(SourceContentDigests.combined(source.getTextContentSha256(),source.getStructuredContentSha256(),source.getParseCoverageSha256(),source.getNormalCandidateSha256()));}
}
