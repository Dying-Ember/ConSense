package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.CanonicalEvidenceProperties;
import com.consense.config.ConsenseProperties;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.sources.NativeCanonicalFixture;
import com.consense.service.sources.NativeCanonicalContract;
import com.consense.service.sources.CanonicalEvidenceBackend;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.NativeDocumentCollectionFixture;
import com.consense.service.sources.CanonicalEvidenceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Python-authored wire and mocked transport; this is not a model or original-PDF acceptance test. */
class OerCanonicalRetrievalClientTest {
    private static final String INPUT="canonical-source-chunks-upstream-scope-and-parser-projection-v1";
    private static final String KEY="test-canonical";
    @Test void frameBackedCorpusBorrowsItsVerifiedChunkTree() throws Exception {
        Wire wire=new Wire();
        CanonicalEvidenceService owner=mock(CanonicalEvidenceService.class);
        Class<?> viewType=Class.forName(CanonicalEvidenceService.class.getName()+"$BindingView");
        java.lang.reflect.Constructor<?> view=viewType.getDeclaredConstructor(CanonicalSourceBinding.class,JsonNode.class,boolean.class);
        view.setAccessible(true);
        java.lang.reflect.Constructor<CanonicalEvidenceService.VerifiedBindingFrame> frameConstructor=
                CanonicalEvidenceService.VerifiedBindingFrame.class.getDeclaredConstructor(
                        CanonicalEvidenceService.class,CanonicalEvidenceService.class,String.class,String.class,List.class);
        frameConstructor.setAccessible(true);
        try(CanonicalEvidenceService.VerifiedBindingFrame frame=frameConstructor.newInstance(owner,owner,KEY,"vetting",
                Collections.singletonList(view.newInstance(wire.binding,wire.snapshot,false)))){
            java.lang.reflect.Method build=OerCanonicalRetrievalClient.class.getDeclaredMethod("corpus",List.class,String.class,
                    CanonicalEvidenceService.VerifiedBindingFrame.class);
            build.setAccessible(true);
            Object corpus=build.invoke(wire.client,frame.bindings(),"vetting",frame);
            java.lang.reflect.Field chunks=corpus.getClass().getDeclaredField("chunks");chunks.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,JsonNode> parents=(Map<String,JsonNode>)chunks.get(corpus);
            JsonNode original=wire.snapshot.path("chunks").get(0);
            assertSame(original,parents.get(original.path("id").asText()),
                    "An owned frame must not copy or reparse the complete verified source tree");
        }
    }
    @Test void portableStructuralHealthUsesExplicitV6WithoutRelaxingOldVersionPins() throws Exception {
        Wire wire=new Wire();wire.health.put("indexSignatureVersion",6);
        ((ObjectNode)wire.health.path("retrieval").path("sourceWindows").path("algorithms")).put("portable_index_signature.py",VettingCorpus.hash("authored portable adapter"));
        assertThrows(IllegalStateException.class,()->wire.client.index(KEY,"vetting",Collections.singletonList(wire.binding)));
        wire.properties.getVetting().setExpectedRetrievalSignatureVersion(6);
        wire.client.index(KEY,"vetting",Collections.singletonList(wire.binding));
        wire.health.put("indexSignatureVersion",5);wire.properties.getVetting().setExpectedRetrievalSignatureVersion(5);
        assertThrows(IllegalStateException.class,()->wire.client.index(KEY,"vetting",Collections.singletonList(wire.binding)));
    }
    @Test void explicitRebuildIsDistinctFromAutomaticIndexing() throws Exception {
        Wire wire=new Wire();List<CanonicalSourceBinding> bindings=Collections.singletonList(wire.binding);
        wire.client.index(KEY,"vetting",bindings);wire.client.rebuild(KEY,"vetting",bindings);
        ArgumentCaptor<String> requests=ArgumentCaptor.forClass(String.class);
        verify(wire.http,times(2)).postJson(endsWith("/index-canonical"),requests.capture(),anyLong());
        JsonNode first=JsonUtils.parse(requests.getAllValues().get(0)),second=JsonUtils.parse(requests.getAllValues().get(1));
        assertFalse(first.has("rebuild"));assertTrue(second.path("rebuild").asBoolean());
        assertEquals(first.path("projectionSha256"),second.path("projectionSha256"));
        assertEquals(first.path("projectId"),second.path("projectId"));
    }
    @Test void advisoryAvailabilityUsesCompactPinsWithoutSnapshotReadsAndChecksGeneration()throws Exception {
        Wire wire=new Wire();wire.advisory();
        wire.binding.setScopePolicySha256(wire.snapshot.path("manifest").path("scope").path("scopePolicySha256").asText());
        ((ObjectNode)wire.health.path("oer")).put("indexAvailabilityContract","live-canonical-index-availability-v1");
        wire.client.index(KEY,"advisory",Collections.singletonList(wire.binding));
        CanonicalSourceBinding compact=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(wire.binding,compact);compact.setSnapshotJson(null);
        ObjectNode available=wire.index.deepCopy();available.put("ready",true);available.put("readOnly",true);available.put("fullVectorIntegrityChecked",false);
        available.put("indexAvailabilityContract","live-canonical-index-availability-v1");available.put("vectorPoints",3);available.put("vectorDimension",2);
        available.put("collection","authored-availability");available.put("indexGeneration","a".repeat(32));
        when(wire.http.postJson(endsWith("/index-canonical/availability"),anyString(),anyLong())).thenAnswer(call->JsonUtils.write(available));
        clearInvocations(wire.backend,wire.http);
        OerCanonicalRetrievalClient.IndexState current=wire.client.indexAvailable(KEY,"advisory",Collections.singletonList(compact));
        assertTrue(current.ready);assertEquals(wire.snapshot.path("chunks").size(),current.chunkCount);
        ArgumentCaptor<String> body=ArgumentCaptor.forClass(String.class);
        verify(wire.http).postJson(endsWith("/index-canonical/availability"),body.capture(),anyLong());
        assertTrue(body.getValue().length()<2048);assertFalse(body.getValue().contains("chunks"));assertFalse(body.getValue().contains("snapshot"));
        verify(wire.http,never()).postJson(endsWith("/index-canonical/status"),anyString(),anyLong());verifyNoInteractions(wire.backend);
        available.put("indexGeneration","b".repeat(32));
        assertFalse(wire.client.indexAvailable(KEY,"advisory",Collections.singletonList(compact),wire.snapshot.path("chunks").size()).ready);
    }
    @Test void advisoryAvailabilityRejectsBindingMutationDuringTheLiveWait()throws Exception {
        Wire wire=new Wire();wire.advisory();
        wire.binding.setScopePolicySha256(wire.snapshot.path("manifest").path("scope").path("scopePolicySha256").asText());
        ((ObjectNode)wire.health.path("oer")).put("indexAvailabilityContract","live-canonical-index-availability-v1");
        wire.client.index(KEY,"advisory",Collections.singletonList(wire.binding));
        CanonicalSourceBinding compact=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(wire.binding,compact);compact.setSnapshotJson(null);
        ObjectNode available=wire.index.deepCopy();available.put("ready",true);available.put("readOnly",true);available.put("fullVectorIntegrityChecked",false);
        available.put("indexAvailabilityContract","live-canonical-index-availability-v1");available.put("vectorPoints",3);available.put("vectorDimension",2);available.put("collection","authored-availability");available.putNull("indexGeneration");
        when(wire.http.postJson(endsWith("/index-canonical/availability"),anyString(),anyLong())).thenAnswer(call->{compact.setJavaParsedSourceHash("f".repeat(64));return JsonUtils.write(available);});
        assertFalse(wire.client.indexAvailable(KEY,"advisory",Collections.singletonList(compact),wire.snapshot.path("chunks").size()).ready);
    }
    private static final class Wire {
        final HttpSupport http=mock(HttpSupport.class);
        final CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
        final ConsenseProperties properties=VettingDualStructureHealthTest.expected();
        final CanonicalEvidenceProperties config=new CanonicalEvidenceProperties();
        final ObjectNode snapshot,index,response,health;
        final CanonicalSourceBinding binding=new CanonicalSourceBinding();
        final OerCanonicalRetrievalClient client;
        Wire() throws Exception {this(false);}
        Wire(boolean nativeFixture) throws Exception {
            JsonNode wire=VettingDualStructureHealthTest.resource("authored_canonical_wire.json");
            snapshot=nativeFixture?NativeCanonicalFixture.snapshot():wire.path("projection").deepCopy();index=wire.path("index").deepCopy();response=wire.path("retrieve").deepCopy();
            health=VettingDualStructureHealthTest.health();
            OerCudaHealthFixtures.apply(health);
            String policy=snapshot.path("manifest").path("scope").path("scopePolicySha256").asText();
            ObjectNode scope=JsonUtils.mapper().createObjectNode();scope.put("manifestSha256",policy);
            ObjectNode oer=health.putObject("oer");
            oer.put("inputContract",INPUT);oer.put("assetReturnContract","source-and-projection-bound-content-addressed-assets-v1");
            oer.put("recipe","scope-bound-dual2048-cuda-fp32-no-fixed-cache-v1");oer.put("targetModule","vetting");
            oer.put("sourceQualityVerifiedByHealth",false);oer.put("parserCandidatesAcceptedByRetrieval",false);
            oer.put("canonicalProjectionEndpoint","/project");oer.put("indexStatusContract","read-only-existing-canonical-index-v1");oer.set("scopePolicy",scope.deepCopy());
            ObjectNode windows=(ObjectNode)health.path("retrieval").path("sourceWindows");
            windows.set("competitionScope",scope.deepCopy());windows.put("oerInputContract",INPUT);windows.put("oerTargetModule","vetting");
            ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
            for(String name:Arrays.asList("oer_server.py","competition_scope.py","artifact_store.py","structured_parser_evidence.py",
                    "canonical_projection.py","oer_source_registry.py","oer_request_evidence.py"))algorithms.put(name,VettingCorpus.hash("authored "+name));
            if(nativeFixture){
                oer.set("nativeCanonical",NativeCanonicalFixture.capability());for(String name:NativeCanonicalContract.ALGORITHMS)algorithms.put(name,VettingCorpus.hash("authored "+name));
                index.put("indexed",snapshot.path("chunks").size());index.putArray("canonicalProjections").add(snapshot.path("projectionSha256").asText());
                ((ObjectNode)index.path("competitionScope").path("policyIdentity")).put("manifestSha256",policy);
                ((ObjectNode)response.path("competitionScope").path("policyIdentity")).put("manifestSha256",policy);
                JsonNode seed=snapshot.path("chunks").get(1);ObjectNode hit=response.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
                Map<String,JsonNode> parents=new LinkedHashMap<>();snapshot.path("chunks").forEach(chunk->parents.put(chunk.path("id").asText(),chunk));
                ObjectNode nativeUnit=OerCanonicalSourceUnits.resolve(parents,seed);nativeUnit.put("originalRerankOrdinal",1);nativeUnit.put("seedScore",0.9);
                ObjectNode episode=(ObjectNode)response.path("sourceUnits");episode.putArray("originalHitIds").add(seed.path("id").asText());episode.putArray("units").add(nativeUnit);
            }
            config.setBaseUrl("http://127.0.0.1:8868");properties.getVetting().setTopK(1);properties.getVetting().setCandidateLimit(10);
            binding.setDocumentId(42L);binding.setProjectId(KEY);binding.setProjectionSha256(snapshot.path("projectionSha256").asText());
            binding.setOriginalSourceSha256(snapshot.path("source").path("sourceSha256").asText());
            binding.setCanonicalSourceHash(snapshot.path("sourceHash").asText());
            binding.setJavaParsedSourceHash(VettingCorpus.hash("authored Java parsed revision"));
            binding.setRoutingSha256(VettingCorpus.hash("authored routing revision"));
            binding.setAdoptionManifestSha256(snapshot.path("projectionIdentity").path("manifestSha256").asText());
            binding.setReviewReferenceSha256(snapshot.path("manifest").path("reference").path("evidenceSha256").asText());store();
            when(http.get(endsWith("/health"),anyLong())).thenAnswer(call->JsonUtils.write(health));
            when(http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->JsonUtils.write(index));
            when(http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{ObjectNode status=index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",status.path("indexed").asInt());status.put("indexStatusContract","read-only-existing-canonical-index-v1");return JsonUtils.write(status);});
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->JsonUtils.write(response));
            client=new OerCanonicalRetrievalClient(http,properties,config,backend);
        }
        void store(){binding.setSnapshotJson(JsonUtils.write(snapshot));binding.setSnapshotBytesSha256(VettingCorpus.hash(binding.getSnapshotJson()));}
        void index(){client.index(KEY,"vetting",Collections.singletonList(binding));}
        JsonNode query(){return client.retrieve(KEY,"vetting","authored complete table query","tender",Collections.singletonList(binding));}
        ObjectNode episode(){return (ObjectNode)response.path("sourceUnits");}
        ObjectNode unit(){return (ObjectNode)episode().path("units").get(0);}
        String pin(){
            String reviewed=VettingCorpus.hash("authored reviewed composite");
            config.getSourcePageRenderEvidenceIdentities().put(binding.getProjectionSha256(),reviewed);
            ((ObjectNode)health.path("oer")).put("sourcePageIndexContract","explicit-composite-index-pins-v1");
            index.putObject("sourcePageEvidence").put(binding.getProjectionSha256(),reviewed);return reviewed;
        }
        void advisory(){
            ((ObjectNode)health.path("oer")).put("targetModule","advisory");
            ((ObjectNode)health.path("retrieval").path("sourceWindows")).put("oerTargetModule","advisory");
            index.put("targetModule","advisory");((ObjectNode)index.path("competitionScope")).put("targetModule","advisory");
            ((ObjectNode)response.path("competitionScope")).put("targetModule","advisory");
        }
    }

    private static void assertReady(OerCanonicalRetrievalClient client,CanonicalSourceBinding binding){
        OerCanonicalRetrievalClient.IndexState state=client.indexStatus(KEY,"vetting",Collections.singletonList(binding));assertTrue(state.ready,state.reason);
    }

    private ObjectNode semanticFixture(Wire wire,String operation){
        ObjectNode result=sourceReadFixture(wire,"ids");result.put("view","semantic_units");result.put("operation",operation);result.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);
        wire.binding.setScopePolicySha256(wire.health.path("oer").path("scopePolicy").path("manifestSha256").asText());
        ObjectNode capability=(ObjectNode)wire.health.path("oer").path("sourceRead");capability.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);capability.putArray("views").add("canonical_units").add("semantic_units");capability.putArray("operations").add("prepare").add("catalog").add("ids").add("scan");
        ((ObjectNode)result.path("sources").get(0)).put("javaParsedSourceHash",wire.binding.getJavaParsedSourceHash());result.putArray("readManifestSha256").add(VettingCorpus.hash("AUTHORED semantic manifest"));result.put("readManifestToken",VettingCorpus.hash("AUTHORED HMAC TEST ONLY"));
        ArrayNode origins=(ArrayNode)result.path("origins");for(int i=0;i<origins.size();i++)origins.set(i,semanticMember(wire,origins.get(i)));
        for(JsonNode unit:result.path("units"))semanticMembers(wire,(ObjectNode)unit);
        ObjectNode scope=(ObjectNode)result.path("scopeIdentity");scope.put("view","semantic_units");scope.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);scope.set("sources",result.path("sources").deepCopy());scope.set("readManifestSha256",result.path("readManifestSha256").deepCopy());sealReadScope(result);
        ((ObjectNode)result.path("coverage")).put("totalOrigins",wire.snapshot.path("chunks").size());
        if("prepare".equals(operation)||"catalog".equals(operation)){origins.removeAll();((ArrayNode)result.path("units")).removeAll();((ObjectNode)result.path("coverage")).put("status","prepare".equals(operation)?"prepared":"catalog").put("returnedOrigins",0).putArray("returnedOriginIds");}
        return result;
    }
    private static ObjectNode semanticMember(Wire wire,JsonNode parent){
        ObjectNode result=parent.deepCopy();result.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);result.put("text",parent.path("content").asText());result.remove("content");
        result.putObject("evidenceRef").put("projectionSha256",wire.binding.getProjectionSha256()).put("canonicalChunkId",parent.path("id").asText()).put("originalSourceSha256",wire.binding.getOriginalSourceSha256());return result;
    }
    private static void semanticMembers(Wire wire,ObjectNode unit){
        ArrayNode members=(ArrayNode)unit.path("members");for(int i=0;i<members.size();i++)members.set(i,semanticMember(wire,members.get(i)));
        for(String field:Arrays.asList("hierarchyContext","attachmentContext"))if(unit.path(field).isObject())semanticMembers(wire,(ObjectNode)unit.path(field));
    }
    @Test void preparedSemanticReadsDoNotLoadSnapshotsOrRequireAnIndex()throws Exception{
        Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare"),returned=semanticFixture(wire,"ids");
        when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenAnswer(call->JsonUtils.write("prepare".equals(JsonUtils.parse(call.getArgument(0)).path("operation").asText())?prepared:returned));
        wire.binding.setSnapshotJson("AUTHORED invalid full snapshot proves this path never decodes one");
        OerCanonicalRetrievalClient.SemanticSources scope=wire.client.prepareSemanticSources("vetting",Collections.singletonList(wire.binding));
        ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","ids");demand.putArray("originIds").add(returned.path("origins").get(0).path("id").asText());
        assertEquals(returned,wire.client.readSemanticSources(scope,demand));verify(wire.backend,never()).snapshot(anyString(),anyString());verify(wire.backend,never()).normalSnapshot(anyString(),anyString());
        verify(wire.http,never()).postJson(anyString(),anyString(),anyLong());ArgumentCaptor<String> bodies=ArgumentCaptor.forClass(String.class);verify(wire.backend,times(2)).sourceRead(bodies.capture(),eq("vetting"),anyInt());
        assertEquals(prepared.path("readManifestToken"),JsonUtils.parse(bodies.getAllValues().get(1)).path("readManifestToken"));assertFalse(bodies.getAllValues().get(1).contains("snapshotJson"));
    }
    @Test void compactNormalPreparationIsExplicitlyNegotiatedAndRetainsOnlyAnIndependentProofCopy()throws Exception{
        Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare");List<CanonicalSourceBinding> bindings=Collections.singletonList(wire.binding);
        assertFalse(wire.client.compactNormalPreparationAvailable("vetting",bindings));
        ((ObjectNode)wire.health.path("oer").path("sourceRead")).putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
        assertTrue(wire.client.compactNormalPreparationAvailable("vetting",bindings));prepared.put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);prepared.putArray("normalProofs").addObject().put("projectionSha256",wire.binding.getProjectionSha256());
        when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenReturn(JsonUtils.write(prepared));wire.binding.setSnapshotJson("AUTHORED full projection must stay unread");
        OerCanonicalRetrievalClient.SemanticSources scope=wire.client.prepareCompactNormalSources("vetting",bindings);
        assertEquals(prepared.path("normalProofs"),scope.normalProofs());((ObjectNode)scope.normalProofs().get(0)).put("callerChanged",true);assertFalse(scope.normalProofs().get(0).has("callerChanged"));
        ArgumentCaptor<String> request=ArgumentCaptor.forClass(String.class);verify(wire.backend).sourceRead(request.capture(),eq("vetting"),eq(16777216));
        assertEquals(OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF,JsonUtils.parse(request.getValue()).path("normalProofVersion").asText());assertFalse(request.getValue().contains("snapshot"));verify(wire.backend,never()).normalSnapshot(anyString(),anyString());
    }
    @Test void compactNormalPreparationCannotAcceptAnAbsentUnadvertisedOrWrongSizedProof()throws Exception{
        for(String mutation:Arrays.asList("capability","version","missing","count")){
            Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare");List<CanonicalSourceBinding> bindings=Collections.singletonList(wire.binding);
            if(!"capability".equals(mutation))((ObjectNode)wire.health.path("oer").path("sourceRead")).putObject("semanticPreparation").put("normalProofVersion",OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
            prepared.put("normalProofVersion","version".equals(mutation)?"future-unknown":OerCanonicalRetrievalClient.COMPACT_NORMAL_PROOF);
            if(!"missing".equals(mutation)){ArrayNode proofs=prepared.putArray("normalProofs");proofs.addObject();if("count".equals(mutation))proofs.addObject();}
            when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenReturn(JsonUtils.write(prepared));assertThrows(IllegalStateException.class,()->wire.client.prepareCompactNormalSources("vetting",bindings),mutation);
            if("capability".equals(mutation))verify(wire.backend,never()).sourceRead(anyString(),anyString(),anyInt());
        }
    }
    @Test void dictionaryReadsValidateGroupBytesAndDoNotInventMissingCachedTemplates()throws Exception{
        for(String mutation:Arrays.asList("none","group_bytes","missing_group","source")){
            Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare"),returned=semanticFixture(wire,"ids");
            ((ObjectNode)wire.health.path("oer").path("sourceRead")).putArray("semanticReadVersions").add(OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            for(ObjectNode reply:Arrays.asList(prepared,returned)){reply.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);((ObjectNode)reply.path("scopeIdentity")).put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);sealReadScope(reply);}
            ObjectNode template=((ObjectNode)returned.path("units").get(0)).deepCopy();stripDictionaryMembers(template);template.put("documentId",returned.path("origins").get(0).path("documentId").asText());
            String group=VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(template)));returned.putArray("units").addObject().put("groupId",group).set("unit",template);
            returned.putObject("originUnits").put(returned.path("origins").get(0).path("id").asText(),group);returned.putArray("blockedOrigins");((ObjectNode)returned.path("coverage")).putArray("blockedOriginIds");
            when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenAnswer(call->JsonUtils.write("prepare".equals(JsonUtils.parse(call.getArgument(0)).path("operation").asText())?prepared:returned));
            OerCanonicalRetrievalClient.SemanticSources scope=wire.client.prepareSemanticSources("vetting",Collections.singletonList(wire.binding));assertTrue(scope.dictionary());
            if("group_bytes".equals(mutation))template.put("status","unknown");
            if("missing_group".equals(mutation))((ArrayNode)returned.path("units")).removeAll();
            if("source".equals(mutation))((ObjectNode)returned.path("origins").get(0).path("evidenceRef")).put("originalSourceSha256",VettingCorpus.hash("changed"));
            ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","ids");demand.putArray("originIds").add(returned.path("origins").get(0).path("id").asText());
            if("none".equals(mutation))assertEquals(returned,wire.client.readSemanticSources(scope,demand));else assertThrows(RuntimeException.class,()->wire.client.readSemanticSources(scope,demand),mutation);
        }
    }
    @Test void dictionaryScanCeilingIsNegotiatedAndOtherOperationsRemainAt256()throws Exception{
        for(boolean advertised:new boolean[]{false,true}){
            Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare");ObjectNode capability=(ObjectNode)wire.health.path("oer").path("sourceRead");
            capability.putArray("semanticReadVersions").add(OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            if(advertised)capability.put("dictionaryScanMaximumItems",1024);
            prepared.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            ((ObjectNode)prepared.path("scopeIdentity")).put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);sealReadScope(prepared);
            when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenAnswer(call->{
                if("prepare".equals(JsonUtils.parse(call.getArgument(0)).path("operation").asText()))return JsonUtils.write(prepared);
                throw new IllegalStateException("AUTHORED wire accepted");
            });
            OerCanonicalRetrievalClient.SemanticSources scope=wire.client.prepareSemanticSources("vetting",Collections.singletonList(wire.binding));
            assertEquals(advertised?1024:256,scope.scanMaximumItems());
            ObjectNode scan=JsonUtils.mapper().createObjectNode().put("operation","scan").put("maxItems",1024);
            assertTrue(assertThrows(IllegalStateException.class,()->wire.client.readSemanticSources(scope,scan)).getMessage()
                .endsWith(advertised?"AUTHORED wire accepted":"semantic read operation/budget invalid"));
            ObjectNode catalog=JsonUtils.mapper().createObjectNode().put("operation","catalog").put("maxItems",1024);
            assertTrue(assertThrows(IllegalStateException.class,()->wire.client.readSemanticSources(scope,catalog)).getMessage().endsWith("semantic read operation/budget invalid"));
            ObjectNode ids=JsonUtils.mapper().createObjectNode().put("operation","ids").put("maxItems",1024);
            ids.putArray("originIds").add("AUTHORED origin");
            assertTrue(assertThrows(IllegalStateException.class,()->wire.client.readSemanticSources(scope,ids)).getMessage().endsWith("semantic read operation/budget invalid"));
        }
    }
    @Test void semanticStreamRequiresOrderedPagesCompleteExitProofAndUnchangedSourceIdentity()throws Exception{
        for(String mutation:Arrays.asList("none","disconnect","missing_complete","complete_early","sequence","scope","token","counts","proof","late_page","source","software")){
            Wire wire=new Wire();List<ObjectNode> pages=new ArrayList<>();
            for(int i=0;i<wire.snapshot.path("chunks").size();i++)pages.add(semanticStreamPage(wire,i));
            ObjectNode prepared=semanticFixture(wire,"prepare");prepared.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            ObjectNode capability=(ObjectNode)wire.health.path("oer").path("sourceRead");capability.putArray("semanticReadVersions").add(OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);
            capability.putArray("semanticScanStreamVersions").add(OerCanonicalRetrievalClient.SEMANTIC_SCAN_STREAM);
            when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenReturn(JsonUtils.write(prepared));
            OerCanonicalRetrievalClient.SemanticSources selected=wire.client.prepareSemanticSources("vetting",Collections.singletonList(wire.binding));assertTrue(selected.stream());
            ObjectNode complete=JsonUtils.mapper().createObjectNode().put("type","complete").put("pageCount",pages.size()).put("totalOrigins",pages.size()).put("processedOrigins",pages.size())
                    .put("scopeSha256",pages.get(0).path("scopeIdentity").path("scopeSha256").asText()).put("readManifestToken",prepared.path("readManifestToken").asText()).put("proofComplete",true);
            if("scope".equals(mutation))complete.put("scopeSha256",VettingCorpus.hash("changed scope"));
            if("token".equals(mutation))complete.put("readManifestToken",VettingCorpus.hash("changed token"));
            if("counts".equals(mutation))complete.put("processedOrigins",pages.size()-1);
            if("proof".equals(mutation))complete.put("proofComplete",false);
            if("source".equals(mutation))((ObjectNode)pages.get(0).path("origins").get(0).path("evidenceRef")).put("originalSourceSha256",VettingCorpus.hash("changed source"));
            doAnswer(call->{java.util.function.Consumer<String> frame=call.getArgument(3);
                JsonNode request=JsonUtils.parse(call.getArgument(0));assertEquals("scan",request.path("operation").asText());assertFalse(request.has("candidateGraph"));assertFalse(request.has("cursor"));
                if("complete_early".equals(mutation))frame.accept(JsonUtils.write(complete));
                for(int i=0;i<pages.size();i++){
                    ObjectNode row=JsonUtils.mapper().createObjectNode().put("type","page").put("sequence","sequence".equals(mutation)?i+2:i+1);row.set("data",pages.get(i));frame.accept(JsonUtils.write(row));
                    if("disconnect".equals(mutation))throw new IllegalStateException("AUTHORED disconnected before exit proof");
                }
                if(!"missing_complete".equals(mutation))frame.accept(JsonUtils.write(complete));
                if("late_page".equals(mutation))frame.accept(JsonUtils.write(JsonUtils.mapper().createObjectNode().put("type","page").put("sequence",pages.size()+1).set("data",pages.get(0))));
                if("software".equals(mutation))((ObjectNode)wire.health.path("retrieval").path("sourceWindows").path("algorithms")).put("source_read_server.py",VettingCorpus.hash("changed runtime"));
                return null;
            }).when(wire.backend).sourceReadStream(anyString(),eq("vetting"),eq(16777216),any());
            List<JsonNode> consumed=new ArrayList<>();
            if("none".equals(mutation)){wire.client.streamSemanticSources(selected,consumed::add);assertEquals(pages,consumed);}
            else assertThrows(RuntimeException.class,()->wire.client.streamSemanticSources(selected,consumed::add),mutation);
            verify(wire.backend,times(1)).sourceReadStream(anyString(),eq("vetting"),eq(16777216),any());
            verify(wire.backend,times(1)).sourceRead(anyString(),eq("vetting"),eq(16777216));
        }
    }
    private ObjectNode semanticStreamPage(Wire wire,int index){
        ObjectNode page=semanticFixture(wire,"ids"),origin=semanticMember(wire,wire.snapshot.path("chunks").get(index));page.put("operation","scan");
        page.put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);((ObjectNode)page.path("scopeIdentity")).put("semanticReadVersion",OerCanonicalRetrievalClient.SEMANTIC_DICTIONARY);sealReadScope(page);
        page.putArray("origins").add(origin);ObjectNode template=JsonUtils.mapper().createObjectNode().put("unitId",VettingCorpus.hash("AUTHORED stream unit "+index)).put("documentId",origin.path("documentId").asText())
                .put("derivedMembersCanBecomeOrigins",false).put("semanticScopeVerified",false).put("qualifiersComplete","unknown").put("status","unknown").put("reason","AUTHORED unknown boundary");template.putArray("requiredMemberIds");
        String group=VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(template)));page.putArray("units").addObject().put("groupId",group).set("unit",template);
        page.putObject("originUnits").put(origin.path("id").asText(),group);page.putArray("blockedOrigins");
        int total=wire.snapshot.path("chunks").size(),processed=index+1;ObjectNode coverage=(ObjectNode)page.path("coverage");
        coverage.putArray("requestedOriginIds").add(origin.path("id").asText());coverage.putArray("returnedOriginIds").add(origin.path("id").asText());coverage.putArray("blockedOriginIds");
        coverage.put("returnedOrigins",1).put("processedOrigins",processed).put("remainingOrigins",total-processed).put("status",processed==total?"complete":"partial");
        if(processed<total)page.put("nextCursor",sourceCursor(page,processed));else page.putNull("nextCursor");return page;
    }
    private static void stripDictionaryMembers(ObjectNode unit){unit.remove(Arrays.asList("members","originIds"));for(String field:Arrays.asList("hierarchyContext","attachmentContext"))if(unit.path(field).isObject())stripDictionaryMembers((ObjectNode)unit.path(field));}
    @Test void semanticReadsRejectChangedScopeSourcePinsAndOmittedClosureMembers()throws Exception{
        for(String mutation:Arrays.asList("manifest","source","member","coverage","software")){
            Wire wire=new Wire();ObjectNode prepared=semanticFixture(wire,"prepare"),returned=semanticFixture(wire,"ids");
            when(wire.backend.sourceRead(anyString(),eq("vetting"),anyInt())).thenAnswer(call->JsonUtils.write("prepare".equals(JsonUtils.parse(call.getArgument(0)).path("operation").asText())?prepared:returned));
            OerCanonicalRetrievalClient.SemanticSources scope=wire.client.prepareSemanticSources("vetting",Collections.singletonList(wire.binding));
            if("manifest".equals(mutation))((ArrayNode)returned.path("readManifestSha256")).set(0,JsonUtils.mapper().getNodeFactory().textNode(VettingCorpus.hash("CHANGED manifest")));
            if("source".equals(mutation))((ObjectNode)returned.path("origins").get(0).path("evidenceRef")).put("originalSourceSha256",VettingCorpus.hash("CHANGED source"));
            if("member".equals(mutation))((ArrayNode)returned.path("units").get(0).path("members")).remove(0);
            if("coverage".equals(mutation))((ObjectNode)returned.path("coverage")).put("totalOrigins",999);
            if("software".equals(mutation)){((ObjectNode)returned.path("scopeIdentity")).put("softwareIdentitySha256",VettingCorpus.hash("CHANGED software"));sealReadScope(returned);}
            ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation","ids");demand.putArray("originIds").add(returned.path("origins").get(0).path("id").asText());assertThrows(RuntimeException.class,()->wire.client.readSemanticSources(scope,demand),mutation);
        }
    }

    @Test void independentConcurrentSourceInvalidationMustWithholdEarlierStatusAndRetrieval() throws Exception {
        independentConcurrentInvalidation(true);
    }
    @Test void independentConcurrentRuntimeInvalidationMustWithholdEarlierStatusAndRetrieval() throws Exception {
        independentConcurrentInvalidation(false);
    }
    private void independentConcurrentInvalidation(boolean source) throws Exception {
        Wire w=new Wire();w.index();
        String originalRouting=w.binding.getRoutingSha256();
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        ObjectNode status=w.index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",3);
        status.put("indexStatusContract","read-only-existing-canonical-index-v1");
        when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{
            entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return JsonUtils.write(status);
        });
        try {
            java.util.concurrent.Future<OerCanonicalRetrievalClient.IndexState> pending=executor.submit(()->w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            if(source)w.binding.setRoutingSha256(VettingCorpus.hash("independent changed routing while first status waits"));
            else ((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float16");
            OerCanonicalRetrievalClient.IndexState invalidated=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
            assertFalse(invalidated.ready);
            if(source)w.binding.setRoutingSha256(originalRouting);
            else ((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float32");
            release.countDown();
            OerCanonicalRetrievalClient.IndexState completed=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);
            boolean queryAccepted;
            try{queryAccepted=w.response.equals(w.query());}catch(IllegalStateException refused){queryAccepted=false;}
            OerCanonicalRetrievalClient.IndexState next=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
            System.out.println("INDEPENDENT_INVALIDATION kind="+(source?"source":"runtime")+" earlierStatusReady="+completed.ready+" queryAccepted="+queryAccepted+" nextStatusReady="+next.ready+" nextReason="+next.reason);
            assertFalse(completed.ready,"A pre-invalidation status must never publish a ready receipt after the same client has invalidated");
            assertFalse(queryAccepted,"Restoring old input cannot permit retrieval before an explicit successful normal index");
            verify(w.http,times(1)).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        } finally {release.countDown();executor.shutdownNow();}
    }

    private static final class Gate implements AutoCloseable {
        final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        void hold() throws InterruptedException {entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}
        void awaitEntered() throws InterruptedException {assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));}
        void release(){release.countDown();}
        @Override public void close(){release();executor.shutdownNow();}
    }
    private static ObjectNode readyStatus(Wire w){
        ObjectNode status=w.index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",status.path("indexed").asInt());
        status.put("indexStatusContract","read-only-existing-canonical-index-v1");return status;
    }
    private static void assertExpired(java.util.concurrent.Future<?> pending){
        java.util.concurrent.ExecutionException failure=assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(10,java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException,failure.toString());
    }

    @Test void explicitSuccessfulIndexClearsSharedInvalidationForAllReceiptReaders() throws Exception {
        for(boolean source:Arrays.asList(true,false)){
            Wire w=new Wire();w.index();String original=w.binding.getRoutingSha256();
            if(source)w.binding.setRoutingSha256(VettingCorpus.hash("changed source"));
            else ((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float16");
            assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
            w.binding.setRoutingSha256(original);((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float32");
            assertThrows(IllegalStateException.class,w::query);
            assertThrows(IllegalStateException.class,()->w.client.sourceUnitPolicy(KEY,"vetting",Collections.singletonList(w.binding)));
            assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
            w.index();assertReady(w.client,w.binding);assertEquals(w.response,w.query());
            assertEquals(w.response.path("sourceUnits").path("policy").asText(),w.client.sourceUnitPolicy(KEY,"vetting",Collections.singletonList(w.binding)));
        }
    }

    @Test void unchangedPollingDuringNormalIndexWithholdsReadyWithoutCancellingItsSuccess() throws Exception {
        Wire w=new Wire();w.index();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{gate.hold();return JsonUtils.write(w.index);});
            java.util.concurrent.Future<?> pending=gate.executor.submit(w::index);gate.awaitEntered();
            assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
            assertThrows(IllegalStateException.class,w::query);
            assertThrows(IllegalStateException.class,()->w.client.sourceUnitPolicy(KEY,"vetting",Collections.singletonList(w.binding)));
            gate.release();pending.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertReady(w.client,w.binding);assertEquals(w.response,w.query());
        }
    }

    @Test void inFlightNormalIndexCannotClearLaterSourceOrRuntimeInvalidation() throws Exception {
        for(boolean source:Arrays.asList(true,false)){
            Wire w=new Wire();w.index();String original=w.binding.getRoutingSha256();String reply=JsonUtils.write(w.index);
            try(Gate gate=new Gate()){
                when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{gate.hold();return reply;});
                java.util.concurrent.Future<?> pending=gate.executor.submit(w::index);gate.awaitEntered();
                if(source)w.binding.setRoutingSha256(VettingCorpus.hash("changed during pending index"));
                else ((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float16");
                assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
                w.binding.setRoutingSha256(original);((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float32");
                gate.release();assertExpired(pending);assertThrows(IllegalStateException.class,w::query);
                assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
                when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenReturn(reply);
                w.index();assertReady(w.client,w.binding);assertEquals(w.response,w.query());
            }
        }
    }

    @Test void olderIndexResponseCannotOverwriteOrInvalidateLaterSuccessfulIndex() throws Exception {
        Wire w=new Wire();w.index();String oldReply=JsonUtils.write(w.index);
        java.util.concurrent.atomic.AtomicInteger indexes=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{
                if(indexes.incrementAndGet()==1){gate.hold();return oldReply;}return JsonUtils.write(w.index);
            });
            java.util.concurrent.Future<?> pending=gate.executor.submit(w::index);gate.awaitEntered();
            String signature=VettingCorpus.hash("newer explicit normal index");w.index.put("signature",signature);w.response.put("indexSignature",signature);
            w.index();gate.release();assertExpired(pending);
            OerCanonicalRetrievalClient.IndexState ready=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
            assertTrue(ready.ready,ready.reason);assertEquals(signature,ready.signature);assertEquals(w.response,w.query());
        }
    }

    @Test void olderStatusResponseCannotEraseLaterSuccessfulExplicitIndex() throws Exception {
        Wire w=new Wire();w.index();String oldReply=JsonUtils.write(readyStatus(w));
        java.util.concurrent.atomic.AtomicInteger statuses=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{
                if(statuses.incrementAndGet()==1){gate.hold();return oldReply;}return JsonUtils.write(readyStatus(w));
            });
            java.util.concurrent.Future<OerCanonicalRetrievalClient.IndexState> pending=gate.executor.submit(()->w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)));
            gate.awaitEntered();String signature=VettingCorpus.hash("index supersedes waiting status");w.index.put("signature",signature);w.response.put("indexSignature",signature);
            w.index();gate.release();assertFalse(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).ready);
            assertReady(w.client,w.binding);assertEquals(w.response,w.query());
        }
    }

    @Test void waitingQueryHonorsConcurrentInvalidationButAllowsUnchangedWarmStatus() throws Exception {
        for(String kind:Arrays.asList("source","runtime","warmStatus")){
            Wire w=new Wire();w.index();String original=w.binding.getRoutingSha256(),reply=JsonUtils.write(w.response);
            try(Gate gate=new Gate()){
                when(w.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->{gate.hold();return reply;});
                java.util.concurrent.Future<JsonNode> pending=gate.executor.submit(w::query);gate.awaitEntered();
                if("source".equals(kind))w.binding.setRoutingSha256(VettingCorpus.hash("changed during query"));
                if("runtime".equals(kind))((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float16");
                OerCanonicalRetrievalClient.IndexState state=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
                assertEquals("warmStatus".equals(kind),state.ready);
                w.binding.setRoutingSha256(original);((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float32");
                gate.release();
                if("warmStatus".equals(kind))assertEquals(w.response,pending.get(10,java.util.concurrent.TimeUnit.SECONDS));
                else {assertExpired(pending);assertThrows(IllegalStateException.class,w::query);}
                when(w.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenReturn(reply);
                w.index();assertReady(w.client,w.binding);assertEquals(w.response,w.query());
            }
        }
    }

    @Test void waitingQueryCannotReturnAcrossExplicitIndexGenerationEvenWithEqualSignature() throws Exception {
        Wire w=new Wire();w.index();String reply=JsonUtils.write(w.response);
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->{gate.hold();return reply;});
            java.util.concurrent.Future<JsonNode> pending=gate.executor.submit(w::query);gate.awaitEntered();
            w.index();gate.release();assertExpired(pending);assertReady(w.client,w.binding);
            when(w.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenReturn(reply);
            assertEquals(w.response,w.query());
        }
    }

    @Test void coldStatusCannotRecoverThroughSameKeyModuleOrValidConfigurationSwitch() throws Exception {
        for(String kind:Arrays.asList("module","source","endpoint")){
            Wire w=new Wire();String original=w.binding.getRoutingSha256(),base=w.config.getBaseUrl(),reply=JsonUtils.write(readyStatus(w));
            ObjectNode oldHealth=w.health.deepCopy(),oldIndex=w.index.deepCopy(),oldResponse=w.response.deepCopy();
            try(Gate gate=new Gate()){
                when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{gate.hold();return reply;});
                java.util.concurrent.Future<OerCanonicalRetrievalClient.IndexState> pending=gate.executor.submit(()->w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)));
                gate.awaitEntered();
                if("module".equals(kind))w.advisory();
                if("source".equals(kind))w.binding.setRoutingSha256(VettingCorpus.hash("new valid source routing"));
                if("endpoint".equals(kind))w.config.setBaseUrl("http://127.0.0.1:8870");
                assertFalse(w.client.indexStatus(KEY,"module".equals(kind)?"advisory":"vetting",Collections.singletonList(w.binding)).ready);
                w.binding.setRoutingSha256(original);w.config.setBaseUrl(base);
                w.health.removeAll();w.health.setAll(oldHealth);w.index.removeAll();w.index.setAll(oldIndex);w.response.removeAll();w.response.setAll(oldResponse);
                gate.release();assertFalse(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).ready);
                assertThrows(IllegalStateException.class,w::query);assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
                when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenReturn(reply);
                w.index();assertReady(w.client,w.binding);assertEquals(w.response,w.query());
            }
        }
    }

    @Test void freshClientRecoversExistingIndexViaRealLocalHttpWithoutCallingIndexAgain() throws Exception {
        Wire w=new Wire();
        ((ObjectNode)w.health.path("oer")).put("indexStatusContract","read-only-existing-canonical-index-v1");
        ObjectNode status=w.index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",status.path("indexed").asInt());
        status.put("indexStatusContract","read-only-existing-canonical-index-v1");
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        java.util.concurrent.atomic.AtomicInteger indexes=new java.util.concurrent.atomic.AtomicInteger(),statuses=new java.util.concurrent.atomic.AtomicInteger();
        List<JsonNode> statusBodies=new ArrayList<>();
        server.createContext("/health",exchange->{byte[] body=JsonUtils.write(w.health).getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/index-canonical",exchange->{indexes.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body=JsonUtils.write(w.index).getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.createContext("/index-canonical/status",exchange->{statuses.incrementAndGet();statusBodies.add(JsonUtils.parse(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));byte[] body=JsonUtils.write(status).getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        server.start();
        try {
            w.config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            OerCanonicalRetrievalClient initial=new OerCanonicalRetrievalClient(new HttpSupport(),w.properties,w.config);
            initial.index(KEY,"vetting",Collections.singletonList(w.binding));
            OerCanonicalRetrievalClient restarted=new OerCanonicalRetrievalClient(new HttpSupport(),w.properties,w.config);
            OerCanonicalRetrievalClient.IndexState recovered=restarted.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
            assertTrue(recovered.ready,recovered.reason);
            assertEquals(w.index.path("signature").asText(),recovered.signature);
            assertEquals(1,indexes.get(),"Status recovery must never index again");assertEquals(1,statuses.get());
            assertEquals(w.index.path("canonicalProjections"),statusBodies.get(0).path("projectionSha256"));
            assertFalse(statusBodies.get(0).has("chunks"));
        } finally {server.stop(0);}
    }

    @Test void everyWarmStatusRevalidatesRemoteIndexAndMalformedReceiptsClearIt() throws Exception {
        for(String mutation:Arrays.asList("ready","readOnly","project","module","count","signature","projections","scope","pins","trailing","duplicate","array","missing")){
            Wire w=new Wire();w.index();assertReady(w.client,w.binding);
            ObjectNode status=w.index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",3);status.put("indexStatusContract","read-only-existing-canonical-index-v1");
            if("ready".equals(mutation))status.put("ready",false);
            if("readOnly".equals(mutation))status.put("readOnly","true");
            if("project".equals(mutation))status.put("projectId","wrong");
            if("module".equals(mutation))status.put("targetModule","advisory");
            if("count".equals(mutation))status.put("indexed",999);
            if("signature".equals(mutation))status.put("signature",VettingCorpus.hash("different indexed generation"));
            if("projections".equals(mutation))status.putArray("canonicalProjections").add(VettingCorpus.hash("wrong current source"));
            if("scope".equals(mutation))((ObjectNode)status.path("competitionScope").path("policyIdentity")).put("manifestSha256",VettingCorpus.hash("wrong scope"));
            if("pins".equals(mutation))status.putObject("sourcePageEvidence").put(w.binding.getProjectionSha256(),VettingCorpus.hash("unexpected pin"));
            String raw=JsonUtils.write(status);
            if("trailing".equals(mutation))raw+=" {}";
            if("duplicate".equals(mutation))raw="{\"ready\":false,"+raw.substring(1);
            if("array".equals(mutation))raw="[]";
            if("missing".equals(mutation))raw="{}";
            final String frozen=raw;when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenReturn(frozen);
            OerCanonicalRetrievalClient.IndexState state=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
            assertFalse(state.ready,mutation);assertThrows(IllegalStateException.class,w::query,mutation);
            verify(w.http,times(1)).postJson(endsWith("/index-canonical"),anyString(),anyLong());
            verify(w.http,times(2)).postJson(endsWith("/index-canonical/status"),anyString(),anyLong());
            verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        }
    }

    @Test void inFlightStatusCannotHydrateAfterCurrentSourceOrHealthChanges() throws Exception {
        for(boolean source:Arrays.asList(true,false)){
            Wire w=new Wire();
            when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{
                ObjectNode status=w.index.deepCopy();status.put("ready",true);status.put("readOnly",true);status.put("vectorPoints",3);status.put("indexStatusContract","read-only-existing-canonical-index-v1");
                if(source)w.binding.setRoutingSha256(VettingCorpus.hash("changed current routing"));
                else ((ObjectNode)w.health.path("runtime").path("embedding")).put("dtype","float16");
                return JsonUtils.write(status);
            });
            assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
            assertThrows(IllegalStateException.class,w::query);verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        }
    }

    @Test void reviewedCompositeIsSentAndEchoedWithoutChangingSealedChunksOrRetrieveBody() throws Exception {
        Wire w=new Wire();String reviewed=w.pin(),frozen=w.binding.getSnapshotJson();
        w.config.getSourcePageRenderEvidenceIdentities().put(VettingCorpus.hash("unselected projection"),VettingCorpus.hash("unselected composite"));
        w.index();assertReady(w.client,w.binding);assertEquals(w.response,w.query());
        ArgumentCaptor<String> indexBody=ArgumentCaptor.forClass(String.class),queryBody=ArgumentCaptor.forClass(String.class);
        verify(w.http).postJson(endsWith("/index-canonical"),indexBody.capture(),anyLong());
        verify(w.http).postJson(endsWith("/retrieve"),queryBody.capture(),anyLong());
        JsonNode sent=JsonUtils.parse(indexBody.getValue());assertEquals(1,sent.path("sourcePageEvidence").size());
        assertEquals(reviewed,sent.path("sourcePageEvidence").path(w.binding.getProjectionSha256()).asText());
        assertEquals(w.index.path("sourcePageEvidence"),sent.path("sourcePageEvidence"));
        assertFalse(sent.has("chunks"));assertFalse(JsonUtils.parse(queryBody.getValue()).has("sourcePageEvidence"));assertEquals(frozen,w.binding.getSnapshotJson());
    }

    @Test void unpinnedRequestsAndReceiptsKeepLegacyBehaviorAndAllowAnEmptyNewEcho() throws Exception {
        Wire w=new Wire();w.config.getSourcePageRenderEvidenceIdentities().put(VettingCorpus.hash("unselected projection"),"invalid unselected value");
        w.index();assertEquals(w.response,w.query());w.index.putObject("sourcePageEvidence");w.index();
        w.config.getSourcePageRenderEvidenceIdentities().clear();assertReady(w.client,w.binding);
        ArgumentCaptor<String> bodies=ArgumentCaptor.forClass(String.class);verify(w.http,times(2)).postJson(endsWith("/index-canonical"),bodies.capture(),anyLong());
        for(String body:bodies.getAllValues())assertFalse(JsonUtils.parse(body).has("sourcePageEvidence"));
        w.index.putObject("sourcePageEvidence").put(w.binding.getProjectionSha256(),VettingCorpus.hash("unexpected pin"));
        assertThrows(IllegalStateException.class,w::index);assertThrows(IllegalStateException.class,w::query);
    }

    @Test void reviewedCompositeRequiresItsExplicitBackendCapabilityBeforeIndex() throws Exception {
        for(String value:Arrays.asList("missing","incorrect")){
            Wire w=new Wire();w.pin();ObjectNode oer=(ObjectNode)w.health.path("oer");
            if("missing".equals(value))oer.remove("sourcePageIndexContract");else oer.put("sourcePageIndexContract","unknown-contract");
            assertThrows(IllegalStateException.class,w::index);verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        }
    }

    @Test void incompleteOrChangedCompositeEchoRejectsAndClearsEarlierReceipt() throws Exception {
        for(String mutation:Arrays.asList("missing","null","empty","wrong","extra","string")){
            Wire w=new Wire();w.pin();w.index();
            if("missing".equals(mutation))w.index.remove("sourcePageEvidence");
            if("null".equals(mutation))w.index.putNull("sourcePageEvidence");
            if("empty".equals(mutation))w.index.putObject("sourcePageEvidence");
            if("wrong".equals(mutation))w.index.putObject("sourcePageEvidence").put(w.binding.getProjectionSha256(),VettingCorpus.hash("different composite"));
            if("extra".equals(mutation))((ObjectNode)w.index.path("sourcePageEvidence")).put(VettingCorpus.hash("unselected projection"),VettingCorpus.hash("another composite"));
            if("string".equals(mutation))w.index.put("sourcePageEvidence","{}");
            assertThrows(IllegalStateException.class,w::index,mutation);assertThrows(IllegalStateException.class,w::query,mutation);
            verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        }
    }

    @Test void invalidSelectedCompositeRejectsBeforeAnyBackendCall() throws Exception {
        Wire w=new Wire();w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),"not-a-sha");
        assertThrows(IllegalStateException.class,w::index);verifyNoInteractions(w.http);
    }

    @Test void changedReviewedCompositeInvalidatesStatusAndCannotRestoreTheOldReceipt() throws Exception {
        for(boolean status:Arrays.asList(false,true)){
            Wire w=new Wire();String reviewed=w.pin();w.index();
            w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),VettingCorpus.hash("new reviewed composite"));
            if(status)assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);else assertThrows(IllegalStateException.class,w::query);
            w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),reviewed);assertThrows(IllegalStateException.class,w::query);
            verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        }
    }

    @Test void inFlightCompositeChangesCannotCreateOrReuseAReceipt() throws Exception {
        Wire indexing=new Wire();String pin=indexing.pin();
        when(indexing.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{
            indexing.config.getSourcePageRenderEvidenceIdentities().clear();return JsonUtils.write(indexing.index);
        });
        assertThrows(IllegalStateException.class,indexing::index);indexing.config.getSourcePageRenderEvidenceIdentities().put(indexing.binding.getProjectionSha256(),pin);
        assertThrows(IllegalStateException.class,indexing::query);
        for(String mutation:Arrays.asList("removed","invalid")){
            Wire querying=new Wire();String reviewed=querying.pin();querying.index();
            when(querying.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->{
                if("removed".equals(mutation))querying.config.getSourcePageRenderEvidenceIdentities().clear();
                else querying.config.getSourcePageRenderEvidenceIdentities().put(querying.binding.getProjectionSha256(),"invalid-sha");
                return JsonUtils.write(querying.response);
            });
            assertThrows(IllegalStateException.class,querying::query);querying.config.getSourcePageRenderEvidenceIdentities().put(querying.binding.getProjectionSha256(),reviewed);
            assertThrows(IllegalStateException.class,querying::query);
            verify(querying.http).postJson(endsWith("/retrieve"),anyString(),anyLong());
        }
    }

    private OerCanonicalRetrievalClient collectionClient(Wire w,CanonicalEvidenceBackend backend) throws Exception {
        return collectionClient(w,backend,"base");
    }
    private OerCanonicalRetrievalClient collectionClient(Wire w,CanonicalEvidenceBackend backend,String variant) throws Exception {
        String rootRaw=new String(NativeCanonicalFixture.bytes("authored-complete-"+variant+"-root.json"),java.nio.charset.StandardCharsets.UTF_8);
        w.snapshot.removeAll();w.snapshot.setAll((ObjectNode)JsonUtils.parse(rootRaw));
        w.binding.setProjectionSha256(w.snapshot.path("projectionSha256").asText());w.binding.setCanonicalSourceHash(w.snapshot.path("sourceHash").asText());w.store();
        w.index.putArray("canonicalProjections").add(w.binding.getProjectionSha256());
        ((ObjectNode)w.health.path("oer")).set("nativeDocumentCollection",NativeDocumentCollectionFixture.capability());
        for(String name:Arrays.asList("native_projection_collection.py","native_collection_registry.py","oer_native_collection_server.py","native_collection_envelope.py"))
            ((ObjectNode)w.health.path("retrieval").path("sourceWindows").path("algorithms")).put(name,VettingCorpus.hash("authored "+name));
        when(backend.snapshot(eq(w.binding.getProjectionSha256()),eq("vetting"))).thenReturn(rootRaw);
        for(int ordinal=0;ordinal<3;ordinal++)when(backend.batch(eq(w.binding.getProjectionSha256()),eq(ordinal),eq("vetting")))
                .thenReturn(NativeDocumentCollectionFixture.reply(NativeCanonicalFixture.bytes("authored-complete-"+variant+"-batch-"+ordinal+".json")));
        for(JsonNode descriptor:w.snapshot.path("envelopeBatches")){int ordinal=descriptor.path("ordinal").asInt();
            when(backend.envelope(eq(w.binding.getProjectionSha256()),eq(ordinal),eq("vetting")))
                .thenReturn(NativeDocumentCollectionFixture.reply(NativeCanonicalFixture.bytes("authored-complete-"+variant+"-envelope-"+ordinal+".json")));}
        JsonNode projection=JsonUtils.parse(new String(NativeCanonicalFixture.bytes("authored-complete-"+variant+"-public.json"),java.nio.charset.StandardCharsets.UTF_8));
        JsonNode seed=projection.path("chunks").get(1);ObjectNode hit=w.response.putArray("hits").addObject();hit.put("id",seed.path("id").asText());hit.put("score",0.9);hit.set("payload",seed.deepCopy());
        Map<String,JsonNode> parents=new LinkedHashMap<>();projection.path("chunks").forEach(chunk->parents.put(chunk.path("id").asText(),chunk));
        ObjectNode unit=OerCanonicalSourceUnits.resolve(parents,seed);unit.put("originalRerankOrdinal",1);unit.put("seedScore",0.9);
        w.episode().putArray("originalHitIds").add(seed.path("id").asText());w.episode().putArray("units").add(unit);
        return new OerCanonicalRetrievalClient(w.http,w.properties,w.config,backend);
    }
    @Test void completeScalarsIndexAndRetrieveUnmodifiedOpaqueNumbersAndUnicodeAcrossAllEnvelopes() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend,"scalars");
        client.index(KEY,"vetting",Collections.singletonList(w.binding));JsonNode actual=client.retrieve(KEY,"vetting","complete scalar table","tender",Collections.singletonList(w.binding));
        assertEquals(w.response,actual);JsonNode opaque=actual.path("hits").get(0).path("payload").path("authoredOpaqueUnknown");
        assertEquals(1e-05,opaque.path("scientific").asDouble());assertEquals(Long.MIN_VALUE,Double.doubleToRawLongBits(opaque.path("negativeZero").asDouble()));
        assertEquals(java.math.BigInteger.ONE.shiftLeft(80),opaque.path("hugeInteger").bigIntegerValue());assertEquals("表组😀",opaque.path("text").asText());
        for(int ordinal=0;ordinal<4;ordinal++)verify(backend,atLeast(2)).envelope(eq(w.binding.getProjectionSha256()),eq(ordinal),eq("vetting"));
    }
    @Test void completeScalarsStillRejectNextBinary64NegativeZeroHugeIntegerAndUnknownChanges() throws Exception {
        for(Consumer<ObjectNode> mutate:Arrays.<Consumer<ObjectNode>>asList(
                n->n.put("scientific",Math.nextUp(1e-05)),n->n.put("negativeZero",0.0),
                n->n.put("hugeInteger",java.math.BigInteger.ONE.shiftLeft(80).add(java.math.BigInteger.ONE)),
                n->n.put("text","表组 changed 😀"),n->n.remove("scientific"),n->n.put("invented",true))){
            Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend,"scalars");
            client.index(KEY,"vetting",Collections.singletonList(w.binding));mutate.accept((ObjectNode)w.response.path("hits").get(0).path("payload").path("authoredOpaqueUnknown"));
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->client.retrieve(KEY,"vetting","complete scalar table","tender",Collections.singletonList(w.binding)));
            assertTrue(failure.getMessage().endsWith("canonical ranked payload differs"),failure.getMessage());
        }
    }
    @Test void completeScalarsRejectChangedOpaqueClosureMemberEvenWhenRankedPayloadIsUnmodified() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend,"scalars");
        client.index(KEY,"vetting",Collections.singletonList(w.binding));ObjectNode opaque=null;
        for(JsonNode member:w.unit().path("members"))if(member.path("authoredOpaqueUnknown").isObject())opaque=(ObjectNode)member.path("authoredOpaqueUnknown");
        assertNotNull(opaque);opaque.put("hugeInteger",java.math.BigInteger.ONE.shiftLeft(80).add(java.math.BigInteger.ONE));
        assertThrows(IllegalStateException.class,()->client.retrieve(KEY,"vetting","complete scalar table","tender",Collections.singletonList(w.binding)));
    }
    @Test void collectionRootIndexesAsOneDocumentAndReturnsUnchangedFullNativeParents() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend);
        client.index(KEY,"vetting",Collections.singletonList(w.binding));assertEquals(w.response,client.retrieve(KEY,"vetting","whole table","tender",Collections.singletonList(w.binding)));
        assertReady(client,w.binding);
        ArgumentCaptor<String> request=ArgumentCaptor.forClass(String.class);verify(w.http).postJson(endsWith("/index-canonical"),request.capture(),anyLong());
        JsonNode sent=JsonUtils.parse(request.getValue());assertEquals(Collections.singletonList(w.binding.getProjectionSha256()),JsonUtils.readList(JsonUtils.write(sent.path("projectionSha256")),String.class));
        assertFalse(sent.has("chunks"));assertFalse(JsonUtils.parse(w.binding.getSnapshotJson()).has("chunks"));assertEquals(3,w.index.path("indexed").asInt());
        verify(backend,atLeast(3)).envelope(eq(w.binding.getProjectionSha256()),eq(0),eq("vetting"));
        for(int ordinal=0;ordinal<3;ordinal++)verify(backend,atLeast(3)).batch(eq(w.binding.getProjectionSha256()),eq(ordinal),eq("vetting"));
    }
    @Test void missingEnvelopeRejectsIndexBeforeCanonicalRequest() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend);
        when(backend.envelope(eq(w.binding.getProjectionSha256()),eq(0),eq("vetting"))).thenThrow(new CanonicalEvidenceException(org.springframework.http.HttpStatus.NOT_FOUND,"authored missing envelope"));
        assertThrows(RuntimeException.class,()->client.index(KEY,"vetting",Collections.singletonList(w.binding)));
        verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }
    @Test void missingEnvelopeAfterIndexRejectsRetrieveAndInvalidatesReceipt() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend);
        client.index(KEY,"vetting",Collections.singletonList(w.binding));
        when(backend.envelope(eq(w.binding.getProjectionSha256()),eq(0),eq("vetting"))).thenThrow(new CanonicalEvidenceException(org.springframework.http.HttpStatus.NOT_FOUND,"authored missing envelope"));
        assertThrows(RuntimeException.class,()->client.retrieve(KEY,"vetting","whole table","tender",Collections.singletonList(w.binding)));
        assertFalse(client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
        verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }
    @Test void missingCollectionProofAndChangedBatchInvalidateReceiptWithoutLegacyReplay() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend);
        client.index(KEY,"vetting",Collections.singletonList(w.binding));byte[] bytes=NativeDocumentCollectionFixture.batch(1);bytes[bytes.length-1]^=1;
        when(backend.batch(eq(w.binding.getProjectionSha256()),eq(1),eq("vetting"))).thenReturn(NativeDocumentCollectionFixture.reply(bytes));
        assertThrows(RuntimeException.class,()->client.retrieve(KEY,"vetting","whole table","tender",Collections.singletonList(w.binding)));
        assertFalse(client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }
    @Test void collectionNeedsNewCapabilityAndAllFourCollectionAlgorithmIdentities() throws Exception {
        Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);OerCanonicalRetrievalClient client=collectionClient(w,backend);
        ((ObjectNode)w.health.path("oer")).remove("nativeDocumentCollection");assertThrows(RuntimeException.class,()->client.index(KEY,"vetting",Collections.singletonList(w.binding)));
        ((ObjectNode)w.health.path("oer")).set("nativeDocumentCollection",NativeDocumentCollectionFixture.capability());
        ((ObjectNode)w.health.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_collection_registry.py");assertThrows(RuntimeException.class,()->client.index(KEY,"vetting",Collections.singletonList(w.binding)));
        verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
    }

    @Test void actualNativePublicWireIndexesAndRetrievesCompleteParentsWithoutPdfAliases() throws Exception {
        Wire w=new Wire(true);w.index();JsonNode returned=w.query();assertEquals(w.response,returned);
        JsonNode seed=returned.path("hits").get(0).path("payload");assertEquals(w.snapshot.path("chunks").get(1),seed);
        assertEquals(Collections.emptyList(),JsonUtils.readList(JsonUtils.write(seed.path("physicalPages")),Integer.class));
        assertEquals(2,seed.path("tableGroup").path("nativeTableTopology").size());assertEquals(5,seed.path("parts").size());
        for(JsonNode part:seed.path("parts")){assertFalse(part.has("physicalPageNo"));assertFalse(part.has("bbox"));assertEquals(part.path("text").asText().length(),part.path("endOffset").asInt());}
        assertEquals("unknown",returned.path("sourceUnits").path("qualifiersComplete").asText());
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }
    @Test void nativeIndexNeedsDedicatedCapabilityAndAllNativeAlgorithmIdentities() throws Exception {
        Wire missingCapability=new Wire(true);((ObjectNode)missingCapability.health.path("oer")).remove("nativeCanonical");
        assertThrows(RuntimeException.class,missingCapability::index);verify(missingCapability.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        Wire missingAlgorithm=new Wire(true);((ObjectNode)missingAlgorithm.health.path("retrieval").path("sourceWindows").path("algorithms")).remove("native_projection_transport.py");
        assertThrows(RuntimeException.class,missingAlgorithm::index);verify(missingAlgorithm.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        Wire changed=new Wire(true);changed.index();((ObjectNode)changed.health.path("retrieval").path("sourceWindows").path("algorithms")).put("native_source_registry.py",VettingCorpus.hash("new algorithm"));
        assertThrows(IllegalStateException.class,changed::query);assertFalse(changed.client.indexStatus(KEY,"vetting",Collections.singletonList(changed.binding)).ready);
    }

    @Test void completePythonWireRetainsUtf16TableRelationsAndUnknownsWithoutLegacyDto() throws Exception {
        Wire w=new Wire();w.index();JsonNode returned=w.query();assertEquals(w.response,returned);
        JsonNode table=null;for(JsonNode chunk:returned.path("sourceUnits").path("units").get(0).path("members"))
            if("table_group".equals(chunk.path("canonicalScope").asText()))table=chunk;
        assertNotNull(table);assertEquals(2,table.path("physicalPages").size());assertTrue(table.path("tableGroup").isObject());
        assertFalse(table.path("humanConfirmed").asBoolean(true));assertEquals("unknown",table.path("sourceQuality").path("semanticQuality").asText());
        JsonNode original=null;for(JsonNode chunk:w.snapshot.path("chunks"))if(chunk.path("id").equals(table.path("id")))original=chunk;
        assertNotNull(original);assertEquals(original.path("relations"),table.path("relations"));
        for(JsonNode member:returned.path("sourceUnits").path("units").get(0).path("members"))for(JsonNode part:member.path("parts"))
            assertEquals(part.path("text").asText().length(),part.path("endOffset").asInt()-part.path("startOffset").asInt());
        ((ObjectNode)returned).put("callerOnly",true);assertFalse(w.response.has("callerOnly"));
        ArgumentCaptor<String> request=ArgumentCaptor.forClass(String.class);
        verify(w.http).postJson(endsWith("/index-canonical"),request.capture(),anyLong());
        assertEquals(w.index.path("canonicalProjections"),JsonUtils.parse(request.getValue()).path("projectionSha256"));
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }

    @Test void rankedPayloadMustRemainExactlyEqualIncludingNewFields() throws Exception {
        for(Consumer<ObjectNode> mutation:Arrays.<Consumer<ObjectNode>>asList(
                n->n.put("content","silently changed"),n->n.remove("relations"),n->n.put("humanConfirmed",true),
                n->n.put("bboxConvention","xywh"),n->n.put("unboundNewField","injected"))) {
            Wire w=new Wire();w.index();mutation.accept((ObjectNode)w.response.path("hits").get(0).path("payload"));
            assertThrows(IllegalStateException.class,w::query);
        }
    }

    @Test void completeClauseCannotOmitReorderOrTransformClosureMembers() throws Exception {
        for(String mutation:Arrays.asList("omit","reverse","payload","requiredIds","blocks")) {
            Wire w=new Wire();w.index();ArrayNode members=(ArrayNode)w.unit().path("members");
            if("omit".equals(mutation))members.remove(0);
            if("reverse".equals(mutation)){JsonNode first=members.get(0);members.set(0,members.get(1));members.set(1,first);}
            if("payload".equals(mutation))((ObjectNode)members.get(0)).put("sourceHash",VettingCorpus.hash("another source"));
            if("requiredIds".equals(mutation))((ArrayNode)w.unit().path("requiredMemberIds")).remove(0);
            if("blocks".equals(mutation))((ArrayNode)w.unit().path("blockIds")).add("invented block");
            assertThrows(IllegalStateException.class,w::query,mutation);
        }
    }

    @Test void unknownAndObservedRangeStatesCannotBePromotedOrTruncated() throws Exception {
        for(String mutation:Arrays.asList("scope","qualifiers","memberScores","derived","end","blockComplete","ordinal","seedScore")) {
            Wire w=new Wire();w.index();ObjectNode unit=w.unit();
            if("scope".equals(mutation))unit.put("semanticScopeVerified",true);
            if("qualifiers".equals(mutation))unit.put("qualifiersComplete","yes");
            if("memberScores".equals(mutation))unit.putArray("memberScores").add(0.9);
            if("derived".equals(mutation))unit.put("derivedMembersCanBecomeOrigins",true);
            if("end".equals(mutation))((ObjectNode)unit.path("observedRanges").get(0)).put("endUtf16",1);
            if("blockComplete".equals(mutation))((ObjectNode)unit.path("observedRanges").get(0)).put("sourceBlockComplete",true);
            if("ordinal".equals(mutation))unit.put("originalRerankOrdinal",0);
            if("seedScore".equals(mutation))unit.put("seedScore",0.123);
            assertThrows(IllegalStateException.class,w::query,mutation);
        }
    }

    @Test void identityAndEligibilityAreCheckedBeforeSendingCanonicalIndex() throws Exception {
        for(String mutation:Arrays.asList("document","source","canonicalHash","eligibility","chunkSource","duplicateChunk","bytes")) {
            Wire w=new Wire();ObjectNode identity=(ObjectNode)w.snapshot.path("projectionIdentity");
            if("document".equals(mutation))identity.put("documentId","43");
            if("source".equals(mutation))((ObjectNode)w.snapshot.path("source")).put("sourceSha256",VettingCorpus.hash("different original"));
            if("canonicalHash".equals(mutation))w.snapshot.put("sourceHash",VettingCorpus.hash("different canonical source"));
            if("eligibility".equals(mutation))identity.putArray("eligibleModules").add("drafting");
            if("chunkSource".equals(mutation))((ObjectNode)w.snapshot.path("chunks").get(0)).put("originalSourceSha256",VettingCorpus.hash("wrong original"));
            if("duplicateChunk".equals(mutation))((ArrayNode)w.snapshot.path("chunks")).add(w.snapshot.path("chunks").get(0).deepCopy());
            w.store();if("bytes".equals(mutation))w.binding.setSnapshotBytesSha256(VettingCorpus.hash("wrong bytes"));
            assertThrows(IllegalStateException.class,w::index,mutation);
            verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        }
    }

    @Test void utf16PartOffsetsAndCanonicalTextCannotBeSilentlyNormalized() throws Exception {
        for(String mutation:Arrays.asList("length","offsetType","content")) {
            Wire w=new Wire();ObjectNode chunk=(ObjectNode)w.snapshot.path("chunks").get(0),part=(ObjectNode)chunk.path("parts").get(0);
            if("length".equals(mutation))part.put("endOffset",part.path("endOffset").asInt()+1);
            if("offsetType".equals(mutation))part.put("startOffset","0");
            if("content".equals(mutation))chunk.put("content",chunk.path("content").asText()+" ");
            w.store();assertThrows(IllegalStateException.class,w::index,mutation);
        }
    }

    @Test void failedIndexClearsEarlierReceiptAndCannotUseOldIndex() throws Exception {
        for(String mutation:Arrays.asList("count","signature","module","projections","policy","quality")) {
            Wire w=new Wire();w.index();
            if("count".equals(mutation))w.index.put("indexed",1);
            if("signature".equals(mutation))w.index.put("signature","not a SHA");
            if("module".equals(mutation))w.index.put("targetModule","advisory");
            if("projections".equals(mutation))w.index.putArray("canonicalProjections").add(VettingCorpus.hash("different projection"));
            if("policy".equals(mutation))((ObjectNode)w.index.path("competitionScope").path("policyIdentity")).put("manifestSha256",VettingCorpus.hash("another policy"));
            if("quality".equals(mutation))((ObjectNode)w.index.path("competitionScope")).put("sourceQualityChanged",true);
            assertThrows(IllegalStateException.class,w::index,mutation);assertThrows(IllegalStateException.class,w::query);
            verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
        }
    }

    @Test void changedCurrentSourceInvalidatesReceiptEvenIfOldBindingIsRestored() throws Exception {
        Wire w=new Wire();w.index();String old=w.binding.getRoutingSha256();w.binding.setRoutingSha256(VettingCorpus.hash("rerouted"));
        assertThrows(IllegalStateException.class,w::query);w.binding.setRoutingSha256(old);assertThrows(IllegalStateException.class,w::query);
        verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }

    @Test void changedHealthAlgorithmInvalidatesReceiptBeforeInference() throws Exception {
        Wire w=new Wire();w.index();ObjectNode algorithms=(ObjectNode)w.health.path("retrieval").path("sourceWindows").path("algorithms");
        JsonNode old=algorithms.get("oer_request_evidence.py");algorithms.put("oer_request_evidence.py",VettingCorpus.hash("changed journal"));
        assertThrows(IllegalStateException.class,w::query);algorithms.set("oer_request_evidence.py",old);assertThrows(IllegalStateException.class,w::query);
        verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }

    @Test void moduleSpecificBackendMustAdvertiseMatchingTargetAndPolicy() throws Exception {
        Wire w=new Wire();w.config.getRetrievalBaseUrls().put("advisory","http://127.0.0.1:8869");
        assertThrows(IllegalStateException.class,()->w.client.index(KEY,"advisory",Collections.singletonList(w.binding)));
        verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        w.advisory();w.client.index(KEY,"advisory",Collections.singletonList(w.binding));
        assertEquals(w.response,w.client.retrieve(KEY,"advisory","question","tender",Collections.singletonList(w.binding)));
        verify(w.http,atLeastOnce()).get(eq("http://127.0.0.1:8869/health"),anyLong());
        verify(w.http).postJson(eq("http://127.0.0.1:8869/index-canonical"),anyString(),anyLong());
        verify(w.http).postJson(eq("http://127.0.0.1:8869/retrieve"),anyString(),anyLong());
    }

    @Test void requestUsesConfiguredPoolAndOutputLimitAndChecksRole() throws Exception {
        Wire w=new Wire();w.index();w.query();ArgumentCaptor<String> sent=ArgumentCaptor.forClass(String.class);
        verify(w.http).postJson(endsWith("/retrieve"),sent.capture(),anyLong());JsonNode request=JsonUtils.parse(sent.getValue());
        assertEquals(10,request.path("candidates").asInt());assertEquals(1,request.path("limit").asInt());assertEquals("tender",request.path("role").asText());
        assertThrows(IllegalStateException.class,()->w.client.retrieve(KEY,"vetting","query","contract",Collections.singletonList(w.binding)));
        w.properties.getVetting().setTopK(11);assertThrows(IllegalStateException.class,w::query);
    }

    @Test void sourceScopeAndResponseSignatureCannotChangeAfterIndex() throws Exception {
        for(String mutation:Arrays.asList("signature","scope","quality","duplicateHit")) {
            Wire w=new Wire();w.index();
            if("signature".equals(mutation))w.response.put("indexSignature",VettingCorpus.hash("stale index"));
            if("scope".equals(mutation))((ObjectNode)w.response.path("competitionScope")).put("targetModule","drafting");
            if("quality".equals(mutation))((ObjectNode)w.response.path("competitionScope")).put("sourceQualityChanged",true);
            if("duplicateHit".equals(mutation))((ArrayNode)w.response.path("hits")).add(w.response.path("hits").get(0).deepCopy());
            assertThrows(IllegalStateException.class,w::query,mutation);
        }
    }

    @Test void explicitCudaFp32SerialHealthIndexesAndRetrievesExactCanonicalPayload() throws Exception {
        Wire w=new Wire();
        assertEquals("cuda",w.health.path("device").asText());
        for(String kind:Arrays.asList("embedding","reranker")) {
            assertEquals("cuda",w.health.path("runtime").path(kind).path("device").asText());
            assertEquals("float32",w.health.path("runtime").path(kind).path("dtype").asText());
            assertTrue(w.health.path("runtime").path(kind).path("serialOffload").asBoolean());
        }
        w.index();assertEquals(w.response,w.query());
        assertReady(w.client,w.binding);
        verify(w.http,never()).postJson(endsWith("/index"),anyString(),anyLong());
    }

    @Test void oldCpuRecipeIsRejectedEvenWhenEveryRuntimeFieldClaimsCudaFp32() throws Exception {
        Wire w=new Wire();((ObjectNode)w.health.path("oer")).put("recipe","scope-bound-dual2048-cpu-fp32-no-fixed-cache-v1");
        assertThrows(IllegalStateException.class,w::index);
        verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
    }

    @Test void forgedCudaRecipeCannotAdmitCpuAutoMixedPrecisionOrNonSerialRuntime() throws Exception {
        for(String mutation:Arrays.asList("rootCpu","rootAuto","rootMissing","rootDeviceIndex","embeddingCpu","embeddingAuto",
                "rerankerCpu","rerankerAuto","embeddingHalf","rerankerHalf","embeddingNonSerial","rerankerNonSerial","serialString")) {
            Wire w=new Wire();ObjectNode embedding=(ObjectNode)w.health.path("runtime").path("embedding");
            ObjectNode reranker=(ObjectNode)w.health.path("runtime").path("reranker");
            if("rootCpu".equals(mutation))w.health.put("device","cpu");
            if("rootAuto".equals(mutation))w.health.put("device","auto");
            if("rootMissing".equals(mutation))w.health.remove("device");
            if("rootDeviceIndex".equals(mutation))w.health.put("device","cuda:0");
            if("embeddingCpu".equals(mutation))embedding.put("device","cpu");
            if("embeddingAuto".equals(mutation))embedding.put("device","auto");
            if("rerankerCpu".equals(mutation))reranker.put("device","cpu");
            if("rerankerAuto".equals(mutation))reranker.put("device","auto");
            if("embeddingHalf".equals(mutation))embedding.put("dtype","float16");
            if("rerankerHalf".equals(mutation))reranker.put("dtype","float16");
            if("embeddingNonSerial".equals(mutation))embedding.put("serialOffload",false);
            if("rerankerNonSerial".equals(mutation))reranker.put("serialOffload",false);
            if("serialString".equals(mutation))embedding.put("serialOffload","true");
            ((ObjectNode)w.health.path("retrieval").path("sourceWindows")).set("rerankRuntime",reranker.deepCopy());
            assertThrows(IllegalStateException.class,w::index,mutation);
            verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
        }
    }

    @Test void runtimeDowngradeInvalidatesReceiptAndRestoringCudaDoesNotReuseIt() throws Exception {
        Wire w=new Wire();w.index();((ObjectNode)w.health.path("runtime").path("embedding")).put("serialOffload",false);
        assertThrows(IllegalStateException.class,w::query);
        OerCudaHealthFixtures.apply(w.health);assertThrows(IllegalStateException.class,w::query);
        assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
        verify(w.http,never()).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
    private static Object invokeListPath(Wire w,String kind,List<CanonicalSourceBinding> current){
        return invokeListPath(w.client,kind,current);
    }
    private static Object invokeListPath(OerCanonicalRetrievalClient client,String kind,List<CanonicalSourceBinding> current){
        if("index".equals(kind)){client.index(KEY,"vetting",current);return Boolean.TRUE;}
        if("status".equals(kind))return client.indexStatus(KEY,"vetting",current);
        if("query".equals(kind))return client.retrieve(KEY,"vetting","authored complete table query","tender",current);
        return client.sourceUnitPolicy(KEY,"vetting",current);
    }
    private static boolean accepted(java.util.concurrent.Future<?> pending) throws Exception {
        try {Object result=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);
            return !(result instanceof OerCanonicalRetrievalClient.IndexState)||((OerCanonicalRetrievalClient.IndexState)result).ready;
        }catch(java.util.concurrent.ExecutionException rejected){
            assertTrue(rejected.getCause() instanceof RuntimeException,rejected.toString());return false;
        }
    }

    @Test void terminalLocalAdmissionProtectsFullSnapshotBindingPinsSelectionAndRuntimeConfiguration() throws Exception {
        for(String kind:Arrays.asList("index","status","query","policy"))for(String mutation:Arrays.asList("snapshot","revision","eligibility","sourceHash","pins","selection","endpoint","runtime")){
            Wire w=new Wire();if("pins".equals(mutation))w.pin();if(!"index".equals(kind))w.index();
            List<CanonicalSourceBinding> current=new ArrayList<>(Collections.singletonList(w.binding));
            String frozenHealth=JsonUtils.write(w.health);java.util.concurrent.atomic.AtomicInteger healthCalls=new java.util.concurrent.atomic.AtomicInteger();
            try(Gate gate=new Gate()){
                when(w.http.get(endsWith("/health"),anyLong())).thenAnswer(call->{if(healthCalls.incrementAndGet()==2)gate.hold();return frozenHealth;});
                java.util.concurrent.Future<?> pending=gate.executor.submit(()->invokeListPath(w,kind,current));gate.awaitEntered();
                if("snapshot".equals(mutation)){w.snapshot.put("authoredUnknownChangedDuringFinalHealth",true);w.store();}
                if("revision".equals(mutation))w.binding.setProjectionRevisionSha256(VettingCorpus.hash("changed projection revision"));
                if("eligibility".equals(mutation))w.binding.setEligibleModulesJson("[\"advisory\"]");
                if("sourceHash".equals(mutation))w.binding.setOriginalSourceSha256(VettingCorpus.hash("changed source hash"));
                if("pins".equals(mutation))w.config.getSourcePageRenderEvidenceIdentities().put(w.binding.getProjectionSha256(),VettingCorpus.hash("changed reviewed composite"));
                if("selection".equals(mutation))current.add(null);
                if("endpoint".equals(mutation))w.config.setBaseUrl("http://127.0.0.1:8870");
                if("runtime".equals(mutation))w.properties.getVetting().setExpectedStructuralEmbeddingMaxTokens(4096);
                gate.release();assertFalse(accepted(pending),kind+":"+mutation);assertEquals(2,healthCalls.get(),"No third health wait is allowed");
            }
        }
    }

    @Test void finalHealthWaitUsesRealFrameFreshBindingGuardBeforeReturningAnyAcceptedResult() throws Exception {
        for(String kind:Arrays.asList("index","status","query","policy"))for(boolean changed:Arrays.asList(false,true)){
            Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
            w.binding.setDeclaredModule("vetting");
            when(backend.snapshot(eq(w.binding.getProjectionSha256()),eq("vetting"))).thenAnswer(call->w.binding.getSnapshotJson());
            com.consense.repository.CanonicalSourceBindingRepository repository=mock(com.consense.repository.CanonicalSourceBindingRepository.class);
            com.consense.service.sources.CanonicalCurrentSourceReader reader=mock(com.consense.service.sources.CanonicalCurrentSourceReader.class);
            com.consense.repository.CanonicalSourceBindingRepository.CompactMetadata compact=
                    mock(com.consense.repository.CanonicalSourceBindingRepository.CompactMetadata.class,call->
                            call.getMethod().getName().startsWith("get")
                                    ?CanonicalSourceBinding.class.getMethod(call.getMethod().getName()).invoke(w.binding):null);
            java.util.concurrent.atomic.AtomicBoolean finalHealthReturned=new java.util.concurrent.atomic.AtomicBoolean(),acceptedBeforeClose=new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger freshBindingReads=new java.util.concurrent.atomic.AtomicInteger(),healthCalls=new java.util.concurrent.atomic.AtomicInteger();
            when(repository.findByProjectIdAndDocumentIdOrderByIdDesc(eq(KEY),eq(42L),any(org.springframework.data.domain.Pageable.class))).thenAnswer(call->{
                if(finalHealthReturned.get())freshBindingReads.incrementAndGet();return Collections.singletonList(w.binding);
            });
            when(repository.findCompactByDocument(eq(KEY),eq(42L),any(org.springframework.data.domain.Pageable.class))).thenAnswer(call->{
                if(finalHealthReturned.get())freshBindingReads.incrementAndGet();return Collections.singletonList(compact);
            });
            when(repository.findByProjectIdAndDocumentIdAndProjectionSha256(eq(KEY),eq(42L),eq(w.binding.getProjectionSha256()))).thenAnswer(call->{
                CanonicalSourceBinding retained=new CanonicalSourceBinding();org.springframework.beans.BeanUtils.copyProperties(w.binding,retained);
                return Optional.of(retained);
            });
            com.consense.service.sources.CanonicalEvidenceService service=new com.consense.service.sources.CanonicalEvidenceService(repository,backend,reader);
            OerCanonicalRetrievalClient client=new OerCanonicalRetrievalClient(w.http,w.properties,w.config,backend);
            if(!"index".equals(kind))client.index(KEY,"vetting",Collections.singletonList(w.binding));
            String frozenHealth=JsonUtils.write(w.health);
            try(Gate gate=new Gate()){
                when(w.http.get(endsWith("/health"),anyLong())).thenAnswer(call->{if(healthCalls.incrementAndGet()==2){gate.hold();finalHealthReturned.set(true);}return frozenHealth;});
                java.util.concurrent.Future<?> pending=gate.executor.submit(()->{
                    try(com.consense.service.sources.CanonicalEvidenceService.VerifiedBindingFrame frame=service.verifiedBindings(KEY,"vetting",Collections.singletonList(42L))){
                        Object result;
                        if("index".equals(kind)){client.index(KEY,"vetting",frame);result=Boolean.TRUE;}
                        else if("status".equals(kind))result=client.indexStatus(KEY,"vetting",frame);
                        else if("query".equals(kind))result=client.retrieve(KEY,"vetting","authored complete table query","tender",frame);
                        else result=client.sourceUnitPolicy(KEY,"vetting",frame);
                        acceptedBeforeClose.set(!(result instanceof OerCanonicalRetrievalClient.IndexState)||((OerCanonicalRetrievalClient.IndexState)result).ready);
                        return result;
                    }
                });
                gate.awaitEntered();if(changed)w.binding.setRoutingSha256(VettingCorpus.hash("fresh repository source changed during last health"));
                gate.release();assertEquals(!changed,accepted(pending),kind+":"+changed);assertEquals(!changed,acceptedBeforeClose.get(),"Frame.close cannot hide an already accepted result");
                assertTrue(freshBindingReads.get()>0,"The actual frame must re-read committed bindings after the last health wait");assertEquals(2,healthCalls.get());
            }
        }
    }

    @Test void terminalListAdmissionDoesNotAppendHealthOrNativeCollectionTransportWaits() throws Exception {
        for(String kind:Arrays.asList("index","status","query","policy"))for(boolean collection:Arrays.asList(false,true)){
            Wire w=new Wire(true);CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);
            OerCanonicalRetrievalClient client;
            if(collection)client=collectionClient(w,backend);
            else {when(backend.snapshot(eq(w.binding.getProjectionSha256()),eq("vetting"))).thenReturn(w.binding.getSnapshotJson());client=new OerCanonicalRetrievalClient(w.http,w.properties,w.config,backend);}
            if(!"index".equals(kind))client.index(KEY,"vetting",Collections.singletonList(w.binding));
            java.util.concurrent.atomic.AtomicInteger healthCalls=new java.util.concurrent.atomic.AtomicInteger(),transportAtLastHealth=new java.util.concurrent.atomic.AtomicInteger(-1);
            when(w.http.get(endsWith("/health"),anyLong())).thenAnswer(call->{
                if(healthCalls.incrementAndGet()==2)transportAtLastHealth.set(mockingDetails(backend).getInvocations().size());return JsonUtils.write(w.health);
            });
            Object result=invokeListPath(client,kind,Collections.singletonList(w.binding));
            if(result instanceof OerCanonicalRetrievalClient.IndexState)assertTrue(((OerCanonicalRetrievalClient.IndexState)result).ready);
            assertEquals(2,healthCalls.get());assertEquals(transportAtLastHealth.get(),mockingDetails(backend).getInvocations().size(),"The List terminal guard is pure local, including collection roots/envelopes");
        }
    }

    @Test void independentFinalHealthWaitIndexMustRejectChangedCurrentSource() throws Exception { independentFinalHealthWait("index"); }
    @Test void independentFinalHealthWaitStatusMustRejectChangedCurrentSource() throws Exception { independentFinalHealthWait("status"); }
    @Test void independentFinalHealthWaitQueryMustRejectChangedCurrentSource() throws Exception { independentFinalHealthWait("query"); }
    @Test void independentFinalHealthWaitPolicyMustRejectChangedCurrentSource() throws Exception { independentFinalHealthWait("policy"); }
    private void independentFinalHealthWait(String kind) throws Exception {
        Wire w=new Wire();if(!"index".equals(kind))w.index();
        java.util.concurrent.atomic.AtomicInteger healthCalls=new java.util.concurrent.atomic.AtomicInteger();
        String frozenHealth=JsonUtils.write(w.health),originalRouting=w.binding.getRoutingSha256();
        try(Gate gate=new Gate()){
            when(w.http.get(endsWith("/health"),anyLong())).thenAnswer(call->{if(healthCalls.incrementAndGet()==2)gate.hold();return frozenHealth;});
            java.util.concurrent.Future<?> pending=gate.executor.submit(()->{
                if("index".equals(kind)){w.index();return Boolean.TRUE;}
                if("status".equals(kind))return w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));
                if("query".equals(kind))return w.query();
                return w.client.sourceUnitPolicy(KEY,"vetting",Collections.singletonList(w.binding));
            });
            gate.awaitEntered();assertEquals(2,healthCalls.get());
            w.binding.setRoutingSha256(VettingCorpus.hash("independent source changed during final health wait "+kind));gate.release();
            boolean accepted=false;Object completed=null;
            try {completed=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);accepted=!(completed instanceof OerCanonicalRetrievalClient.IndexState)||((OerCanonicalRetrievalClient.IndexState)completed).ready;}
            catch(java.util.concurrent.ExecutionException rejected){assertTrue(rejected.getCause() instanceof IllegalStateException,rejected.toString());}
            System.out.println("INDEPENDENT_FINAL_HEALTH_WAIT kind="+kind+" accepted="+accepted+" healthCalls="+healthCalls.get()+" routingChanged="+!originalRouting.equals(w.binding.getRoutingSha256()));
            assertFalse(accepted,"Current source changed during the final external health wait; no status/receipt/query/policy may be published");
        }
    }

    @Test void independentOlderFailedStatusMustNotInvalidateNewerSuccessfulIndex() throws Exception {
        Wire w=new Wire();w.index();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical/status"),anyString(),anyLong())).thenAnswer(call->{if(calls.incrementAndGet()==1){gate.hold();throw new IllegalStateException("independent older status transport failure");}return JsonUtils.write(readyStatus(w));});
            java.util.concurrent.Future<OerCanonicalRetrievalClient.IndexState> old=gate.executor.submit(()->w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)));
            gate.awaitEntered();String newSignature=VettingCorpus.hash("independent newer index after status failure");w.index.put("signature",newSignature);w.response.put("indexSignature",newSignature);w.index();
            gate.release();assertFalse(old.get(10,java.util.concurrent.TimeUnit.SECONDS).ready);
            OerCanonicalRetrievalClient.IndexState fresh=w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding));assertTrue(fresh.ready,fresh.reason);assertEquals(newSignature,fresh.signature);assertEquals(w.response,w.query());
        }
    }

    @Test void independentOlderFailedIndexMustNotInvalidateNewerSuccessfulIndex() throws Exception {
        Wire w=new Wire();w.index();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{if(calls.incrementAndGet()==1){gate.hold();throw new IllegalStateException("independent older index transport failure");}return JsonUtils.write(w.index);});
            java.util.concurrent.Future<?> old=gate.executor.submit(w::index);gate.awaitEntered();
            String newSignature=VettingCorpus.hash("independent newer index after old index failure");w.index.put("signature",newSignature);w.response.put("indexSignature",newSignature);w.index();
            gate.release();assertExpired(old);assertReady(w.client,w.binding);assertEquals(w.response,w.query());
        }
    }

    @Test void independentNewerFailedIndexMustNotLetOlderSuccessfulIndexResurrectReceipt() throws Exception {
        Wire w=new Wire();w.index();String oldReply=JsonUtils.write(w.index);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/index-canonical"),anyString(),anyLong())).thenAnswer(call->{if(calls.incrementAndGet()==1){gate.hold();return oldReply;}throw new IllegalStateException("independent newer index transport failure");});
            java.util.concurrent.Future<?> old=gate.executor.submit(w::index);gate.awaitEntered();assertThrows(IllegalStateException.class,w::index);
            gate.release();assertExpired(old);assertFalse(w.client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);assertThrows(IllegalStateException.class,w::query);
        }
    }

    @Test void independentOlderFailedQueryMustNotInvalidateNewerSuccessfulIndex() throws Exception {
        Wire w=new Wire();w.index();java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        try(Gate gate=new Gate()){
            when(w.http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(call->{if(calls.incrementAndGet()==1){gate.hold();throw new IllegalStateException("independent older query transport failure");}return JsonUtils.write(w.response);});
            java.util.concurrent.Future<?> old=gate.executor.submit(w::query);gate.awaitEntered();
            String newSignature=VettingCorpus.hash("independent newer index after query failure");w.index.put("signature",newSignature);w.response.put("indexSignature",newSignature);w.index();
            gate.release();assertExpired(old);assertReady(w.client,w.binding);assertEquals(w.response,w.query());
        }
    }

    private static OerCanonicalRetrievalClient familyClient(Wire w)throws Exception{
        String raw=com.consense.service.sources.NativePdfSourceFamilyFixture.raw("authored-family-v1.json");JsonNode root=JsonUtils.parse(raw);
        w.snapshot.removeAll();w.snapshot.setAll((ObjectNode)root);org.springframework.beans.BeanUtils.copyProperties(com.consense.service.sources.NativePdfSourceFamilyFixture.binding("authored-family-v1.json"),w.binding);w.binding.setProjectId(KEY);
        String policy=root.path("manifest").path("scope").path("scopePolicySha256").asText();ObjectNode scope=JsonUtils.mapper().createObjectNode().put("manifestSha256",policy);
        ObjectNode oer=(ObjectNode)w.health.path("oer"),windows=(ObjectNode)w.health.path("retrieval").path("sourceWindows");oer.set("scopePolicy",scope.deepCopy());windows.set("competitionScope",scope.deepCopy());
        oer.set("nativePdfSourceFamily",com.consense.service.sources.NativePdfSourceFamilyFixture.capability());ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
        root.path("manifest").path("software").fields().forEachRemaining(e->algorithms.set(e.getKey(),e.getValue().path("sha256")));
        for(String f:Arrays.asList("unified_source_registry.py","retrieval_source_units.py","oer_unified_server.py"))algorithms.put(f,VettingCorpus.hash("authored "+f));
        w.index.put("indexed",2);w.index.putArray("canonicalProjections").add(w.binding.getProjectionSha256());((ObjectNode)w.index.path("competitionScope").path("policyIdentity")).put("manifestSha256",policy);
        CanonicalEvidenceBackend backend=mock(CanonicalEvidenceBackend.class);when(backend.snapshot(anyString(),anyString())).thenReturn(raw);return new OerCanonicalRetrievalClient(w.http,w.properties,w.config,backend);
    }
    @Test void familyIndexUsesCompleteParentsAndDistinctCompositionPins()throws Exception{
        Wire w=new Wire();OerCanonicalRetrievalClient client=familyClient(w);client.index(KEY,"vetting",Collections.singletonList(w.binding));assertReady(client,w.binding);
        ArgumentCaptor<String> sent=ArgumentCaptor.forClass(String.class);verify(w.http).postJson(endsWith("/index-canonical"),sent.capture(),anyLong());JsonNode request=JsonUtils.parse(sent.getValue());
        assertEquals(JsonUtils.mapper().createArrayNode().add(w.binding.getProjectionSha256()),request.path("projectionSha256"));assertEquals(2,client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).chunkCount);
        assertNull(w.binding.getAdoptionManifestSha256());assertNotNull(w.binding.getFamilyCompositionSha256());
    }
    @Test void familyBothPinsInvalidateDuringFinalHealthWaitBeforeReturningStatus()throws Exception{
        for(boolean composition:new boolean[]{true,false}){Wire w=new Wire();OerCanonicalRetrievalClient client=familyClient(w);client.index(KEY,"vetting",Collections.singletonList(w.binding));
            when(w.http.get(endsWith("/health"),anyLong())).thenAnswer(call->{if(composition)w.binding.setFamilyCompositionSha256("b".repeat(64));else w.binding.setFamilyComponentSetSha256("b".repeat(64));return JsonUtils.write(w.health);});
            assertFalse(client.indexStatus(KEY,"vetting",Collections.singletonList(w.binding)).ready);
        }
    }
    @Test void familyCapabilityMissingCannotFallThroughNativePdfOrScannedAliases()throws Exception{
        Wire w=new Wire();OerCanonicalRetrievalClient client=familyClient(w);((ObjectNode)w.health.path("oer")).remove("nativePdfSourceFamily");assertThrows(RuntimeException.class,()->client.index(KEY,"vetting",Collections.singletonList(w.binding)));verify(w.http,never()).postJson(endsWith("/index-canonical"),anyString(),anyLong());
    }
    private ObjectNode demand(Wire wire,String operation){
        ObjectNode demand=JsonUtils.mapper().createObjectNode();demand.put("operation",operation);demand.put("maxItems",64);demand.put("maxBytes",4194304);
        if("ids".equals(operation))demand.putArray("originIds").add(wire.response.path("hits").get(0).path("id").asText());return demand;
    }
    private ObjectNode sourceReadFixture(Wire wire,String operation){
        ((ObjectNode)wire.health.path("oer")).putObject("sourceRead").put("contract",OerCanonicalRetrievalClient.SOURCE_READ).put("endpoint","/sources/read");
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.put("contract",OerCanonicalRetrievalClient.SOURCE_READ);result.put("module","vetting");result.put("view","canonical_units");result.put("operation",operation);
        result.putArray("projectionSha256").add(wire.binding.getProjectionSha256());result.set("sourceUnitPolicy",wire.response.path("sourceUnits").path("policy").deepCopy());result.put("sourceQualityVerified",false);
        ObjectNode source=result.putArray("sources").addObject();source.put("projectionSha256",wire.binding.getProjectionSha256());source.put("documentId",String.valueOf(wire.binding.getDocumentId()));
        source.put("originalSourceSha256",wire.binding.getOriginalSourceSha256());source.put("sourceHash",wire.binding.getCanonicalSourceHash());source.put("chunkCount",wire.snapshot.path("chunks").size());
        ObjectNode scope=result.putObject("scopeIdentity");scope.put("module","vetting");scope.put("scopeSha256",VettingCorpus.hash("authored scope"));scope.set("sources",result.path("sources").deepCopy());scope.set("policyIdentity",wire.health.path("oer").path("scopePolicy").deepCopy());
        scope.set("sourceUnitPolicy",result.path("sourceUnitPolicy").deepCopy());scope.put("softwareIdentitySha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(wire.health.path("retrieval").path("sourceWindows").path("algorithms")))));
        ArrayNode ordered=JsonUtils.mapper().createArrayNode();wire.snapshot.path("chunks").forEach(chunk->ordered.add(chunk.path("id").asText()));scope.put("originOrderSha256",VettingCorpus.hash(JsonUtils.write(ordered)));sealReadScope(result);
        ArrayNode origins=result.putArray("origins"),units=result.putArray("units");ObjectNode coverage=result.putObject("coverage");coverage.putArray("requestedOriginIds");
        if("ids".equals(operation)){
            JsonNode seed=wire.response.path("hits").get(0).path("payload");origins.add(seed.deepCopy());units.add(wire.response.path("sourceUnits").path("units").get(0).deepCopy());
            coverage.withArray("requestedOriginIds").add(seed.path("id").asText());
        }
        coverage.set("returnedOriginIds",coverage.path("requestedOriginIds").deepCopy());coverage.put("returnedOrigins",origins.size());coverage.put("status","catalog".equals(operation)?"catalog":"complete");result.putNull("nextCursor");
        when(wire.backend.sourceRead(anyString(),eq("vetting"),eq(4194304))).thenAnswer(call->JsonUtils.write(result));return result;
    }
    private static void sealReadScope(ObjectNode response){ObjectNode scope=(ObjectNode)response.path("scopeIdentity");scope.remove("scopeSha256");scope.put("scopeSha256",VettingCorpus.hash(JsonUtils.write(OerCanonicalSourceUnits.sorted(scope))));}
    private ObjectNode scanFixture(Wire wire,int index){
        ObjectNode result=sourceReadFixture(wire,"scan");JsonNode origin=wire.snapshot.path("chunks").get(index);result.withArray("origins").add(origin.deepCopy());
        ObjectNode unit=result.withArray("units").addObject();unit.put("unitId",VettingCorpus.hash("AUTHORED unknown unit "+index));unit.putArray("originIds").add(origin.path("id").asText());unit.set("sourceIdentity",OerCanonicalSourceUnits.identity(origin));
        unit.put("derivedMembersCanBecomeOrigins",false);unit.put("semanticScopeVerified",false);unit.put("qualifiersComplete","unknown");unit.put("status","unknown");unit.put("reason","AUTHORED unknown boundary");unit.putArray("members");unit.putArray("requiredMemberIds");
        ObjectNode coverage=(ObjectNode)result.path("coverage");coverage.putArray("requestedOriginIds").add(origin.path("id").asText());coverage.putArray("returnedOriginIds").add(origin.path("id").asText());coverage.put("returnedOrigins",1);
        int processed=index+1,total=wire.snapshot.path("chunks").size();coverage.put("totalOrigins",total);coverage.put("processedOrigins",processed);coverage.put("remainingOrigins",total-processed);coverage.put("status",processed==total?"complete":"partial");
        if(processed<total)result.put("nextCursor",sourceCursor(result,processed));return result;
    }
    private String sourceCursor(ObjectNode response,int offset){ObjectNode cursor=JsonUtils.mapper().createObjectNode();cursor.set("scopeSha256",response.path("scopeIdentity").path("scopeSha256"));cursor.put("offset",offset);return Base64.getUrlEncoder().encodeToString(JsonUtils.write(cursor).getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+VettingCorpus.hash("AUTHORED signature TEST ONLY");}
    @Test void sourceReadUsesTheCommonOerClosureWithoutIndexOrQueryInference()throws Exception{
        Wire wire=new Wire();ObjectNode expected=sourceReadFixture(wire,"ids");JsonNode actual=wire.client.readSources("vetting",demand(wire,"ids"),Collections.singletonList(wire.binding));
        assertEquals(expected,actual);ArgumentCaptor<String> request=ArgumentCaptor.forClass(String.class);verify(wire.backend).sourceRead(request.capture(),eq("vetting"),eq(4194304));
        JsonNode sent=JsonUtils.parse(request.getValue());assertEquals(wire.binding.getProjectionSha256(),sent.path("projectionSha256").get(0).asText());
        assertFalse(request.getValue().contains("candidateGraph"));assertFalse(request.getValue().contains("snapshotJson"));assertFalse(sent.has("chunks"));
        verify(wire.http,never()).postJson(anyString(),anyString(),anyLong());
        Wire catalog=new Wire();ObjectNode compact=sourceReadFixture(catalog,"catalog");assertEquals(compact,catalog.client.readSources("vetting",demand(catalog,"catalog"),Collections.singletonList(catalog.binding)));
    }
    @Test void sourceReadRejectsTamperedIdentityMemberTextOriginOrderAndQualityClaims()throws Exception{
        for(String mutation:Arrays.asList("module","revision","member","origin","quality","duplicate","scope")){
            Wire wire=new Wire();ObjectNode reply=sourceReadFixture(wire,"ids");
            if("module".equals(mutation))reply.put("module","advisory");
            if("revision".equals(mutation))((ObjectNode)reply.path("sources").get(0)).put("originalSourceSha256",VettingCorpus.hash("other original"));
            if("member".equals(mutation))((ObjectNode)reply.path("units").get(0).path("members").get(0)).put("content","forged source");
            if("origin".equals(mutation))((ObjectNode)reply.path("origins").get(0)).put("id","unselected-origin");
            if("quality".equals(mutation))reply.put("sourceQualityVerified",true);
            if("duplicate".equals(mutation))((ArrayNode)reply.path("units").get(0).path("requiredMemberIds")).add(reply.path("units").get(0).path("requiredMemberIds").get(0));
            if("scope".equals(mutation))((ObjectNode)reply.path("scopeIdentity").path("policyIdentity")).put("manifestSha256",VettingCorpus.hash("other policy"));
            assertThrows(IllegalStateException.class,()->wire.client.readSources("vetting",demand(wire,"ids"),Collections.singletonList(wire.binding)),mutation);
        }
    }
    @Test void sourceReadRejectsInvalidDemandsBeforeTransportAndKeepsUnknownOriginalsUsable()throws Exception{
        Wire invalid=new Wire();ObjectNode bad=demand(invalid,"ids");bad.put("maxBytes","4096");assertThrows(IllegalStateException.class,()->invalid.client.readSources("vetting",bad,Collections.singletonList(invalid.binding)));
        bad.remove("maxBytes");bad.putArray("originIds").add("outside-scope");assertThrows(IllegalStateException.class,()->invalid.client.readSources("vetting",bad,Collections.singletonList(invalid.binding)));verifyNoInteractions(invalid.backend);
        Wire wire=new Wire();ObjectNode reply=sourceReadFixture(wire,"ids"),unit=(ObjectNode)reply.path("units").get(0);unit.put("status","unknown");unit.put("reason","source boundary unresolved");unit.putArray("members");unit.putArray("requiredMemberIds");
        JsonNode result=wire.client.readSources("vetting",demand(wire,"ids"),Collections.singletonList(wire.binding));assertEquals("unknown",result.path("units").get(0).path("status").asText());assertEquals(1,result.path("origins").size());
        Wire drift=new Wire();ObjectNode unchanged=sourceReadFixture(drift,"ids");when(drift.backend.sourceRead(anyString(),anyString(),anyInt())).thenAnswer(call->{drift.binding.setRoutingSha256(VettingCorpus.hash("changed during read"));return JsonUtils.write(unchanged);});
        assertThrows(IllegalStateException.class,()->drift.client.readSources("vetting",demand(drift,"ids"),Collections.singletonList(drift.binding)));
    }
    @Test void sourceReadBindsScopeSoftwareOrderAndCoverageInsteadOfAcceptingAFakeLastPage()throws Exception{
        Wire first=new Wire();ObjectNode page=scanFixture(first,0);assertEquals(page,first.client.readSources("vetting",demand(first,"scan"),Collections.singletonList(first.binding)));
        Wire next=new Wire();ObjectNode second=scanFixture(next,1),nextDemand=demand(next,"scan");nextDemand.put("cursor",sourceCursor(second,1));assertEquals(second,next.client.readSources("vetting",nextDemand,Collections.singletonList(next.binding)));
        for(String mutation:Arrays.asList("skip","ledger","count","status","software","order","policy","scopeHash")){
            Wire wire=new Wire();ObjectNode reply=scanFixture(wire,"skip".equals(mutation)?wire.snapshot.path("chunks").size()-1:0);
            ObjectNode scope=(ObjectNode)reply.path("scopeIdentity"),coverage=(ObjectNode)reply.path("coverage");
            if("ledger".equals(mutation))coverage.putArray("returnedOriginIds");if("count".equals(mutation))coverage.put("returnedOrigins",0);if("status".equals(mutation))coverage.put("status","complete");
            if("software".equals(mutation))scope.put("softwareIdentitySha256",VettingCorpus.hash("another software"));if("order".equals(mutation))scope.put("originOrderSha256",VettingCorpus.hash("another order"));if("policy".equals(mutation))scope.put("sourceUnitPolicy","another policy");
            sealReadScope(reply);if("scopeHash".equals(mutation))scope.put("scopeSha256",VettingCorpus.hash("forged scope"));
            assertThrows(IllegalStateException.class,()->wire.client.readSources("vetting",demand(wire,"scan"),Collections.singletonList(wire.binding)),mutation);
        }
    }
    @Test void sourceReadContextCannotPromoteUnknownsOrLeaveItsOriginSource()throws Exception{
        for(String mutation:Arrays.asList("semantic","legal","origin","source")){
            Wire wire=new Wire();ObjectNode reply=sourceReadFixture(wire,"ids"),unit=(ObjectNode)reply.path("units").get(0),context=unit.putObject("hierarchyContext");
            context.set("seedObservedUnitId",unit.path("unitId"));context.set("sourceIdentity",unit.path("sourceIdentity").deepCopy());context.set("members",unit.path("members").deepCopy());context.set("requiredMemberIds",unit.path("requiredMemberIds").deepCopy());
            context.put("status","complete_observed_hierarchy_context");context.put("derivedMembersCanBecomeOrigins",false);context.put("semanticScopeVerified",false);context.put("legalClosureVerified",false);context.put("qualifiersComplete","unknown");
            if("semantic".equals(mutation))context.put("semanticScopeVerified",true);if("legal".equals(mutation))context.put("legalClosureVerified",true);if("origin".equals(mutation))context.put("seedObservedUnitId",VettingCorpus.hash("other origin"));if("source".equals(mutation))((ObjectNode)context.path("sourceIdentity")).put("documentId","other document");
            assertThrows(IllegalStateException.class,()->wire.client.readSources("vetting",demand(wire,"ids"),Collections.singletonList(wire.binding)),mutation);
        }
    }
}
