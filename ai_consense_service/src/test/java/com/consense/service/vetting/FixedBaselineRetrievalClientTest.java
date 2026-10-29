package com.consense.service.vetting;

import com.consense.ai.HttpSupport;
import com.consense.common.JsonUtils;
import com.consense.config.FixedBaselineProperties;
import com.consense.vector.VectorStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Faithful v1/signature-3 wire shapes with authored source text; no model/network/DB calls. */
class FixedBaselineRetrievalClientTest {
    @Test void explicitFrozenClauseMissingFromVectorHeadersReachesTheActualDirectionWire()throws Exception {
        Fixture f=headerOnly();snapshot(f,Arrays.asList(f.first,f.second));
        com.consense.domain.Project project=new com.consense.domain.Project();project.setId("authored");
        com.consense.repository.ProjectRepository projects=mock(com.consense.repository.ProjectRepository.class);when(projects.findById("authored")).thenReturn(Optional.of(project));
        org.springframework.test.util.ReflectionTestUtils.setField(f.client,"projectRepository",projects);
        Class<?> type=Class.forName("com.consense.service.vetting.VettingNamedReferenceAdmissionTest$Exercise");
        java.lang.reflect.Constructor<?> create=type.getDeclaredConstructor(String.class);create.setAccessible(true);Object run=create.newInstance("initial");
        VettingSemanticReview review=(VettingSemanticReview)org.springframework.test.util.ReflectionTestUtils.getField(run,"review");review.setFixedBaselineRetrievalClient(f.client);
        java.lang.reflect.Method execute=type.getDeclaredMethod("execute");execute.setAccessible(true);execute.invoke(run);
        @SuppressWarnings("unchecked") List<String> wires=(List<String>)org.springframework.test.util.ReflectionTestUtils.getField(run,"wires");
        assertEquals(2,wires.size(),"The empty checklist response receives one bounded coverage replay");
        for(String wire:wires){
            assertTrue(wire.contains(f.second.path("content").asText()),"A real frozen 13,387-character body must reach both prepared wires even when vector RAG returns only a 76-character header");
            assertFalse(wire.contains(f.first.path("content").asText()));
        }
        verify(f.http,times(2)).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
    @Test void exactRefinementIsCompleteUnrankedAndDoesNotAddAnotherVectorRequest()throws Exception {
        Fixture f=headerOnly();snapshot(f,Arrays.asList(f.first,f.second));List<VectorStore.SearchHit> hits=f.client.search("vetting","GCC 1.1",10);
        assertEquals("parent-b",hits.get(0).getId());assertEquals(0,hits.get(0).getScore());assertFalse((Boolean)hits.get(0).getPayload().get("fixedReferenceOrigin"));
        assertEquals("explicit_observed_clause_refinement",hits.get(0).getPayload().get("fixedReferenceRetrievalMethod"));
        FixedReferenceEvidence body=f.client.evidence(hits.get(0));assertEquals("complete_observed_unit",body.getSourceUnit().path("status").asText());
        assertEquals(Collections.singleton("parent-b"),new HashSet<>(Collections.singletonList(body.getSourceUnit().path("requiredMemberIds").get(0).asText())));
        assertTrue(f.client.toModelMaterial(hits,20000).contains("Authored definitions"));verify(f.http,times(1)).postJson(endsWith("/retrieve"),anyString(),anyLong());
    }
    @Test void rawQuestionsAndVersionDecimalsDoNotTriggerLiteralClauseRefinement()throws Exception {
        for(String query:Arrays.asList("Authored definitions","GCC version 1.1","GCC revision 1.1")){
            Fixture f=headerOnly();snapshot(f,Arrays.asList(f.first,f.second));List<VectorStore.SearchHit> hits=f.client.search("vetting",query,10);
            assertEquals(1,hits.size());assertEquals("parent-a",hits.get(0).getId());
        }
    }
    @Test void literalProvenanceCannotBeRelabelledAsAnIndependentVectorRank()throws Exception {
        Fixture f=headerOnly();snapshot(f,Arrays.asList(f.first,f.second));VectorStore.SearchHit hit=f.client.search("vetting","GCC 1.1",10).get(0);
        Map<String,Object> changed=new LinkedHashMap<>(hit.getPayload());changed.put("fixedReferenceOrigin",true);
        FixedReferenceEvidence checked=f.client.evidence(new VectorStore.SearchHit(hit.getId(),.99,changed));
        assertEquals(0,checked.getScore());assertFalse(checked.getPayload().path("fixedReferenceOrigin").asBoolean(true));
        changed.put("fixedReferenceRetrievalMethod","vector_ranked");assertThrows(IllegalStateException.class,()->f.client.evidence(new VectorStore.SearchHit(hit.getId(),.99,changed)));
    }
    @Test void exactRefinementHasFiniteParentAndByteLimitsBeforeMaterializingGroups()throws Exception {
        for(String bound:Arrays.asList("parents","bytes")){
            Fixture f=headerOnly();List<ObjectNode> rows=new ArrayList<>(Arrays.asList(f.first,f.second));
            if("parents".equals(bound))for(int i=0;i<64;i++){ObjectNode row=parent("extra-"+i,"AUTHORED required fragment "+i,i+3);observed(row,"1.1");row.put("clauseHeadingLocation",f.second.path("anchor").asText());rows.add(row);}
            if("bytes".equals(bound))f.p.setMaxResponseBytes(5000);snapshot(f,rows);
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.client.search("vetting","GCC 1.1",10));
            assertTrue(failure.getMessage().contains("limit"),bound);
        }
    }
    @Test void sameClauseInAnotherVerifiedFamilyCannotSatisfyTheRequestedGcc()throws Exception {
        Fixture f=headerOnly();Path gct=temp.resolve("gct.pdf");Files.write(gct,"AUTHORED GCT source".getBytes(StandardCharsets.UTF_8));
        f.second.put("fileKey","GCT").put("documentId","GCT:GCT").put("originalSourceSha256",sha(Files.readAllBytes(gct)));((ObjectNode)f.second.path("sourceQuality")).put("family","GCT");
        ObjectNode manifest=manifest(f);((ObjectNode)manifest.path("originalSources")).set("gctSource",descriptor(gct));writeManifest(f,manifest);snapshot(f,Arrays.asList(f.first,f.second));
        assertEquals(1,f.client.search("vetting","GCC 1.1",10).size());
    }
    @Test void selectedSlEditionIsExactAndAnUnselectedSameNumberDoesNotLeak()throws Exception {
        Fixture f=headerOnly();Path one=slOriginal("one"),two=slOriginal("two");
        ObjectNode selected=slBody("selected","SL:one",one),unselected=slBody("unselected","SL:two",two);
        configureSl(f,one,two);snapshot(f,Arrays.asList(f.first,selected,unselected));JsonNode selection=slSelection(f,one,"one");
        f.response.putArray("selectedSourceSha256").add(ORIGINAL).add(sha(Files.readAllBytes(one)));
        List<FixedReferenceEvidence> hits=f.client.searchEvidence("vetting","SL 1.1",10,selection);
        assertEquals("selected",hits.get(0).getId());assertTrue(hits.stream().noneMatch(hit->"unselected".equals(hit.getId())));
    }
    @Test void sameEditionSlNumberWithSeveralWorksectionOwnersIsRefused()throws Exception {
        Fixture f=headerOnly();Path source=slOriginal("one");configureSl(f,source,null);
        snapshot(f,Arrays.asList(f.first,slBody("section-a","SL:section-a",source),slBody("section-b","SL:section-b",source)));
        f.response.putArray("selectedSourceSha256").add(ORIGINAL).add(sha(Files.readAllBytes(source)));
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.client.searchEvidence("vetting","SL 1.1",10,slSelection(f,source,"one")));
        assertTrue(failure.getMessage().contains("ambiguous"));
    }
    @Test void selectedEditionRemainsIsolatedWhenDocumentAndParserIdentitiesAreReused()throws Exception {
        for(boolean sameParsedHash:new boolean[]{true,false}){
            Fixture f=headerOnly();Path one=slOriginal("one"),two=slOriginal("two");configureSl(f,one,two);
            ObjectNode selected=slBody("selected-shared-doc","SL:shared-document",one),unselected=slBody("unselected-shared-doc","SL:shared-document",two);
            if(!sameParsedHash)unselected.put("sourceHash",sha("AUTHORED other parsed snapshot"));
            snapshot(f,Arrays.asList(f.first,selected,unselected));f.response.putArray("selectedSourceSha256").add(ORIGINAL).add(sha(Files.readAllBytes(one)));
            List<FixedReferenceEvidence> hits=f.client.searchEvidence("vetting","SL 1.1",10,slSelection(f,one,"one"));
            assertEquals("selected-shared-doc",hits.get(0).getId());assertTrue(hits.stream().noneMatch(hit->"unselected-shared-doc".equals(hit.getId())));
        }
    }
    @Test void requestedClauseRagSourceContextCanDisambiguateTheSelectedSlWorksection()throws Exception {
        Fixture f=headerOnly();Path source=slOriginal("one");configureSl(f,source,null);
        ObjectNode a=slBody("section-a","SL:section-a",source),b=slBody("section-b","SL:section-b",source),context=slBody("section-context","SL:section-a",source);
        context.put("clauseHeadingLocation",a.path("anchor").asText());f.response.withArray("hits").removeAll();f.response.withArray("hits").addObject().put("id",context.path("id").asText()).put("score",.8).set("payload",context);
        headerUnit(f,context);snapshot(f,Arrays.asList(f.first,a,b,context));f.response.putArray("selectedSourceSha256").add(ORIGINAL).add(sha(Files.readAllBytes(source)));
        List<FixedReferenceEvidence> hits=f.client.searchEvidence("vetting","SL 1.1",10,slSelection(f,source,"one"));
        assertEquals("section-a",hits.get(0).getId());assertTrue(hits.stream().noneMatch(hit->"section-b".equals(hit.getId())));
    }
    @Test void unrelatedOperativeRagTextCannotChooseOneOfSeveralSameNumberSlOwners()throws Exception {
        Fixture f=headerOnly();Path source=slOriginal("one");configureSl(f,source,null);
        ObjectNode a=slBody("section-a","SL:section-a",source),b=slBody("section-b","SL:section-b",source),context=slBody("unrelated-context","SL:section-a",source);
        context.put("clauseId","9.9").put("observedLabelRaw","9.9");f.response.withArray("hits").removeAll();f.response.withArray("hits").addObject().put("id",context.path("id").asText()).put("score",.8).set("payload",context);
        headerUnit(f,context);snapshot(f,Arrays.asList(f.first,a,b,context));f.response.putArray("selectedSourceSha256").add(ORIGINAL).add(sha(Files.readAllBytes(source)));
        assertThrows(IllegalStateException.class,()->f.client.searchEvidence("vetting","SL 1.1",10,slSelection(f,source,"one")));
    }
    @Test void aSharedNumberAcrossRequestedFamiliesCannotBeChosenByOneFamilyRankedContext()throws Exception {
        Fixture f=headerOnly();Path original=temp.resolve("gct-shared-number.pdf");Files.write(original,"AUTHORED other family".getBytes(StandardCharsets.UTF_8));
        ObjectNode other=parent("gct-body","AUTHORED GCT same number",3);observed(other,"1.1");other.put("fileKey","GCT").put("documentId","GCT:GCT").put("originalSourceSha256",sha(Files.readAllBytes(original)));other.with("sourceQuality").put("family","GCT");
        ObjectNode value=manifest(f);value.with("originalSources").set("gctSource",descriptor(original));writeManifest(f,value);
        f.response.withArray("hits").removeAll();f.response.withArray("hits").addObject().put("id","parent-b").put("score",.8).set("payload",f.second);headerUnit(f,f.second);snapshot(f,Arrays.asList(f.first,f.second,other));
        assertThrows(IllegalStateException.class,()->f.client.search("vetting","GCC 1.1 and GCT 1.1",10));
    }
    @Test void rankedUnknownRequestedSeedStillAcquiresItsCompleteFrozenContinuation()throws Exception {
        Fixture f=headerOnly();ObjectNode tail=parent("continuation","AUTHORED required continuation",3);observed(tail,"1.1");tail.put("clauseHeadingLocation",f.second.path("anchor").asText());
        ObjectNode part=(ObjectNode)tail.path("parts").get(0);int offset=f.second.path("content").asText().length();part.put("blockId",f.second.path("parts").get(0).path("blockId").asText()).put("startOffset",offset).put("endOffset",offset+part.path("text").asText().length()).put("anchor",f.second.path("anchor").asText()+" @"+offset);
        f.response.withArray("hits").removeAll();f.response.withArray("hits").addObject().put("id","parent-b").put("score",.8).set("payload",f.second);headerUnit(f,f.second);snapshot(f,Arrays.asList(f.first,f.second,tail));
        List<VectorStore.SearchHit> hits=f.client.search("vetting","GCC 1.1",10);assertEquals(2,hits.size());
        for(VectorStore.SearchHit hit:hits){assertEquals("complete_observed_unit",f.client.evidence(hit).getSourceUnit().path("status").asText());assertEquals(2,f.client.evidence(hit).getSourceUnit().path("requiredMemberIds").size());}
        assertTrue(hits.stream().anyMatch(hit->"continuation".equals(hit.getId())));
    }
    @Test void incompleteClauseRangeCannotBeInventedAsACompleteRefinementGroup()throws Exception {
        Fixture f=headerOnly();ObjectNode continuation=parent("continuation","AUTHORED later fragment",3);observed(continuation,"1.1");continuation.put("clauseHeadingLocation",f.second.path("anchor").asText());
        ObjectNode part=(ObjectNode)continuation.path("parts").get(0);part.put("blockId",f.second.path("parts").get(0).path("blockId").asText());int offset=f.second.path("content").asText().length()+1;
        part.put("startOffset",offset).put("endOffset",offset+part.path("text").asText().length()).put("anchor",f.second.path("anchor").asText()+" @"+offset);
        snapshot(f,Arrays.asList(f.first,f.second,continuation));IllegalStateException failure=assertThrows(IllegalStateException.class,()->f.client.search("vetting","GCC 1.1",10));
        assertTrue(failure.getMessage().contains("incomplete"));
    }
    @Test void exactRefinementKeepsFrozenOriginalAndIndexSnapshotDriftGuards()throws Exception {
        for(String defect:Arrays.asList("original","index","corpus")){
            Fixture f=headerOnly();snapshot(f,Arrays.asList(f.first,f.second));f.client.frozenManifest();
            if("original".equals(defect))Files.write(f.original,"CHANGED source".getBytes(StandardCharsets.UTF_8));
            if("index".equals(defect))f.response.put("indexSignature",sha("changed index"));
            if("corpus".equals(defect))Files.write(f.corpus,"CHANGED corpus".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class,()->f.client.search("vetting","GCC 1.1",10),defect);
        }
    }
    private Fixture headerOnly()throws Exception {
        Fixture f=new Fixture(false);String header="General Conditions of Contract for Building Works 2013 Edition (version 1.1)";
        f.first.put("content",header);((ObjectNode)f.first.path("parts").get(0)).put("text",header);observed(f.first,null);f.first.put("observedNodeKind","document").put("observedSourceRole","content");
        String body="1.1 Authored definitions\n"+"x".repeat(13387-"1.1 Authored definitions\n".length());f.second.put("content",body);((ObjectNode)f.second.path("parts").get(0)).put("text",body);observed(f.second,"1.1");headerUnit(f,f.first);return f;
    }
    private static void observed(ObjectNode row,String clause){
        for(String field:Arrays.asList("metadataVersion","segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion"))row.put(field,"AUTHORED fixed version");
        row.put("observedNodeKind","clause").put("observedSourceRole","body");if(clause!=null)row.put("clauseId",clause).put("observedLabelRaw",clause).put("clauseHeadingLocation",row.path("anchor").asText());
        for(JsonNode value:row.path("parts")){ObjectNode part=(ObjectNode)value;part.put("anchor",row.path("anchor").asText()).put("startOffset",0).put("endOffset",part.path("text").asText().length());}
    }
    private static void headerUnit(Fixture f,ObjectNode seed){ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId",sha("AUTHORED unknown "+seed.path("id").asText())).put("kind","unknown").put("status","unknown").put("reason","AUTHORED no ranked closure");
        unit.putArray("originIds").add(seed.path("id").asText());unit.putArray("requiredMemberIds");unit.putArray("members");ObjectNode identity=unit.putObject("sourceIdentity");for(String field:OerCanonicalSourceUnits.FIELDS)identity.set(field,seed.path(field));
        unit.put("derivedMembersCanBecomeOrigins",false).put("qualifiersComplete","unknown").put("semanticScopeVerified",false);f.response.with("sourceUnits").withArray("units").removeAll().add(unit);}
    private static ObjectNode manifest(Fixture f)throws Exception{return (ObjectNode)JsonUtils.parse(Files.readString(Paths.get(f.p.getManifestPath())));}
    private static void writeManifest(Fixture f,ObjectNode value)throws Exception{Path path=Paths.get(f.p.getManifestPath());write(path,value);f.p.setManifestSha256(sha(Files.readAllBytes(path)));}
    private static void snapshot(Fixture f,List<ObjectNode> rows)throws Exception {StringBuilder body=new StringBuilder();for(ObjectNode row:rows)body.append(JsonUtils.write(row)).append('\n');Files.write(f.corpus,body.toString().getBytes(StandardCharsets.UTF_8));
        ObjectNode value=manifest(f);value.set("corpus",descriptor(f.corpus));value.with("coverage").put("totalParents",rows.size());writeManifest(f,value);}
    private Path slOriginal(String edition)throws Exception{Path path=temp.resolve("sl-"+edition+".pdf");Files.write(path,("AUTHORED SL edition "+edition).getBytes(StandardCharsets.UTF_8));return path;}
    private static ObjectNode slBody(String id,String document,Path original)throws Exception {ObjectNode row=parent(id,"AUTHORED SL body "+id,2);observed(row,"1.1");row.put("fileKey","SL").put("documentId",document).put("originalSourceSha256",sha(Files.readAllBytes(original)));row.with("sourceQuality").put("family","SL");return row;}
    private static void configureSl(Fixture f,Path one,Path two)throws Exception{ObjectNode value=manifest(f);ArrayNode sources=value.with("originalSources").putArray("slSources");ObjectNode a=descriptor(one);a.put("editionId","one");sources.add(a);if(two!=null){ObjectNode b=descriptor(two);b.put("editionId","two");sources.add(b);}writeManifest(f,value);}
    private static JsonNode slSelection(Fixture f,Path original,String edition)throws Exception {ObjectNode selection=JsonUtils.mapper().createObjectNode();selection.put("manifestSha256",f.p.getManifestSha256());selection.putArray("slSources").addObject().put("sha256",sha(Files.readAllBytes(original))).put("editionId",edition);return selection;}
    @Test void observedEditionIdentityComesFromVerifiedSameSourceBytesAndCannotBeReplacedByHitMetadata()throws Exception {
        Fixture f=new Fixture(false);String title="General Conditions of Contract for Building Works 2013 Edition (version 1.1)";
        f.second.put("content",title);((ObjectNode)f.second.path("parts").get(0)).put("text",title);
        Files.write(f.corpus,(JsonUtils.write(f.first)+"\n"+JsonUtils.write(f.second)+"\n").getBytes(StandardCharsets.UTF_8));
        Path path=Paths.get(f.p.getManifestPath());ObjectNode manifest=(ObjectNode)JsonUtils.parse(Files.readString(path));manifest.set("corpus",descriptor(f.corpus));write(path,manifest);f.p.setManifestSha256(sha(Files.readAllBytes(path)));
        VectorStore.SearchHit selected=f.client.search("advisory","authored payment",1).get(0);assertEquals(title,f.client.evidence(selected).getDocumentTitle());
        Map<String,Object> changed=new LinkedHashMap<>(selected.getPayload());changed.put("fixedReferenceDocumentTitle",title.replace("2013","2020"));
        assertEquals(title,f.client.evidence(new VectorStore.SearchHit(selected.getId(),selected.getScore(),changed)).getDocumentTitle(),"The mapper must regenerate identity from the pinned corpus rather than trust retrieval metadata");
    }
    @TempDir Path temp;
    private static final String ORIGINAL=sha("authored source"), SOURCE=sha("authored parser snapshot"), SIGNATURE=sha("authored index");
    private final class Fixture {
        final HttpSupport http=mock(HttpSupport.class);
        final FixedBaselineProperties p=new FixedBaselineProperties();
        final ObjectNode first=parent("parent-a","1.1 Authored complete condition",1), second=parent("parent-b","Exception: authored qualifier",2);
        final ObjectNode health=JsonUtils.mapper().createObjectNode(), response=JsonUtils.mapper().createObjectNode();
        final Path corpus=temp.resolve("corpus.jsonl");
        final Path original=temp.resolve("original.pdf");
        final FixedBaselineRetrievalClient client;
        Fixture(boolean table) throws Exception {this(table,3);}
        Fixture(boolean table,int signatureVersion) throws Exception {
            if(table) {
                ObjectNode artifact=JsonUtils.mapper().createObjectNode();artifact.put("id","parent-b");artifact.put("originalSourceSha256",ORIGINAL);artifact.put("sourceQualityAccepted",false);
                ArrayNode matrix=artifact.putObject("table").putArray("extractedMatrix");matrix.addArray().add("Heading").addNull().add("");matrix.addArray().add("Authored value").add("Exception").add("Cell");
                Path artifactPath=temp.resolve("table.json");write(artifactPath,artifact);second.set("tableArtifact",descriptor(artifactPath));
            }
            ObjectNode rawFirst=first.deepCopy();rawFirst.putNull("observedNodeId");
            Files.write(corpus,(JsonUtils.write(rawFirst)+"\n"+JsonUtils.write(second)+"\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode runtime=runtime("embedding",16),rr=runtime("reranker",4);
            health.put("status","ok");health.put("mode","local");health.put("offline",true);health.put("device","cuda");health.put("indexSignatureVersion",signatureVersion);
            ObjectNode models=health.putObject("models");models.set("embedding",model("embedding"));models.set("reranker",model("reranker"));
            ObjectNode runs=health.putObject("runtime");runs.set("embedding",runtime);runs.set("reranker",rr);
            ObjectNode retrieval=health.putObject("retrieval");retrieval.put("roleFilter",true);ObjectNode windows=retrieval.putObject("sourceWindows");
            windows.put("contract","source-bound-window-points-full-parent-results-v1");windows.put("denseAggregation","max");windows.put("rerankAggregation","max");
            ObjectNode recipe=JsonUtils.mapper().createObjectNode();recipe.put("max_tokens",512);recipe.put("overlap_tokens",64);recipe.put("max_windows_per_parent",10000);recipe.put("aggregation","max");recipe.put("version","source-bound-token-windows-v1");
            windows.set("embeddingWindows",recipe);windows.set("rerankWindows",recipe);windows.set("rerankRuntime",rr);windows.putObject("algorithms");
            windows.putObject("structuralSelection").put("policy","unique-source-structural-unit-seeds-v1").put("closureMembersAreRanked",false);
            ObjectNode plan=JsonUtils.mapper().createObjectNode();plan.put("projectId","authored-fixed");plan.put("indexSignatureSha256",SIGNATURE);
            ObjectNode sig=plan.putObject("signature");sig.put("signatureVersion",signatureVersion);sig.set("embedding",model("embedding"));sig.set("runtime",runtime);sig.set("windowRecipe",recipe);sig.putObject("algorithms");
            Path planPath=temp.resolve("plan.json");write(planPath,plan);
            ObjectNode manifest=JsonUtils.mapper().createObjectNode();manifest.put("schema","consense.frozen-fixed-development-baseline.v1");manifest.put("developmentBaseline",true);
            manifest.put("finalParsingAccepted",false);manifest.put("businessAccepted",false);manifest.put("version","authored-freeze-05");manifest.put("indexSignature",SIGNATURE);
            Files.write(original,"authored source".getBytes(StandardCharsets.UTF_8));
            manifest.putArray("scope").add("GCC").add("GCT").add("all-applicable-SL-technical");manifest.putObject("originalSources").set("gccSource",descriptor(original));
            manifest.set("runtimePlan",descriptor(planPath));manifest.set("corpus",descriptor(corpus));manifest.putObject("coverage").put("totalParents",2);
            Path manifestPath=temp.resolve("manifest.json");write(manifestPath,manifest);
            p.setEnabled(true);p.setManifestPath(manifestPath.toString());p.setManifestSha256(sha(Files.readAllBytes(manifestPath)));p.setProjectId("authored-fixed");p.setIndexSignature(SIGNATURE);
            response.put("projectId","authored-fixed");response.put("indexSignature",SIGNATURE);response.put("mode","source-bound-window-points-full-parent-results-v1");
            response.putArray("hits").addObject().put("id","parent-a").put("score",.8).set("payload",first);
            ObjectNode source=response.putObject("sourceUnits");source.put("policy","unique-source-structural-unit-seeds-v1");source.put("scope","canonical_observed_structure_only");
            source.put("derivedMembersCanBecomeOrigins",false);source.put("qualifiersComplete","unknown");source.put("semanticScopeVerified",false);
            ObjectNode unit=source.putArray("units").addObject();unit.put("unitId",sha("authored group"));unit.put("kind","exact_clause_heading");unit.put("status","complete_observed_unit");
            unit.putArray("originIds").add("parent-a");unit.putArray("requiredMemberIds").add("parent-a").add("parent-b");unit.putArray("members").add(first).add(second);
            unit.putObject("sourceIdentity").put("documentId","GCC:GCC").put("sourceHash",SOURCE).put("role","standard");
            unit.put("derivedMembersCanBecomeOrigins",false);unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);
            when(http.get(endsWith("/health"),anyLong())).thenAnswer(c->JsonUtils.write(health));
            when(http.postJson(endsWith("/retrieve"),anyString(),anyLong())).thenAnswer(c->JsonUtils.write(response));
            client=new FixedBaselineRetrievalClient(http,p);
        }
        ObjectNode unit(){return (ObjectNode)response.path("sourceUnits").path("units").get(0);}
        List<VectorStore.SearchHit> search(){return client.search("advisory","authored question",1);}
        void enableMigration() throws Exception {
            Path mp=Paths.get(p.getManifestPath());ObjectNode manifest=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(mp),StandardCharsets.UTF_8));
            Path pp=Paths.get(manifest.path("runtimePlan").path("path").asText());ObjectNode plan=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(pp),StandardCharsets.UTF_8));
            ObjectNode sig=(ObjectNode)plan.path("signature"),windows=(ObjectNode)health.path("retrieval").path("sourceWindows");
            sig.put("contract","source-bound-window-points-full-parent-results-v1");sig.put("corpus",sha("authored canonical parents"));
            sig.put("signatureVersion",3);sig.put("normalized",true);sig.put("structuralSelectionPolicy","unique-source-structural-unit-seeds-v1");sig.putObject("embeddingPreprocessing").put("strip",true);
            ObjectNode tokenizer=sig.putObject("tokenizerAndModelFiles");tokenizer.set("model",model("embedding"));tokenizer.putArray("files").addObject().put("relativePath","authored.bin").put("bytes",1).put("sha256",sha("weight"));
            tokenizer.put("backendSha256",sha("tokenizer"));tokenizer.put("tokenizerClass","AuthoredFastTokenizer");tokenizer.put("fast",true);tokenizer.put("modelMaxLength",512);
            write(pp,plan);manifest.set("runtimePlan",descriptor(pp));manifest.put("collection","authored_local");manifest.put("persistedVectorCount",2);
            Path metadata=temp.resolve("metadata.json");write(metadata,JsonUtils.mapper().createObjectNode().put("authored","immutable metadata"));manifest.set("persistedMetadata",descriptor(metadata));
            ObjectNode seal=manifest.putObject("vectorIntegrity");seal.put("schema","consense.persisted-vector-index-integrity.v1");seal.put("dimension",2);seal.put("dtype","float32-le");seal.put("pointCount",2);seal.put("embeddingSignatureSha256",SIGNATURE);seal.put("pointsSha256",sha("warm cosine values"));seal.put("persistedPointsSha256",sha("complete vectors"));
            ObjectNode persisted=seal.deepCopy();persisted.remove("persistedPointsSha256");persisted.put("pointsSha256",sha("complete vectors"));
            ObjectNode publications=manifest.putObject("modelPublications");
            for(String kind:Arrays.asList("embedding","reranker")) {ObjectNode publication=JsonUtils.mapper().createObjectNode();publication.set("model",model(kind));publication.set("files",tokenizer.path("files").deepCopy());publication.put("publicationSha256",sha(kind+" release"));Path pub=temp.resolve(kind+"-publication.json");write(pub,publication);publications.set(kind,descriptor(pub));}
            write(mp,manifest);p.setManifestSha256(sha(Files.readAllBytes(mp)));
            ObjectNode migration=JsonUtils.mapper().createObjectNode();migration.put("schema","consense.qdrant-server-migration.v1");migration.put("status","completed_full_storage_readback_candidate_not_adopted");
            migration.put("targetAdopted",false);migration.put("activeConfigurationChanged",false);migration.put("allOriginalPersistentStateBytesUnchanged",true);
            migration.set("frozenManifest",descriptor(mp));migration.set("originalMetadata",descriptor(metadata));migration.set("candidateMetadata",descriptor(metadata));migration.set("embeddingSignature",sig.deepCopy());
            migration.put("indexSignature",SIGNATURE);migration.put("parentCount",2);migration.put("vectorWindowCount",2);migration.set("targetVectorIntegrity",persisted.deepCopy());migration.set("sourceVectorIntegrity",persisted.deepCopy());
            migration.putObject("target").put("url","http://127.0.0.1:16333").put("collection","oer_server_authored");
            Path receipt=temp.resolve("migration.json");write(receipt,migration);p.setMigrationReceiptPath(receipt.toString());p.setMigrationReceiptSha256(sha(Files.readAllBytes(receipt)));p.setBaseUrl("http://127.0.0.1:18878");
            health.put("mode","server");health.put("device","cpu");
            for(String kind:Arrays.asList("embedding","reranker")) {ObjectNode run=(ObjectNode)health.path("runtime").path(kind);run.put("device","cpu");run.put("dtype","float32");run.put("batchSize",1);}
            ObjectNode algorithms=(ObjectNode)windows.path("algorithms");
            for(String name:Arrays.asList("server.py","window_runtime.py","migrated_qdrant_index.py","migrate_qdrant_server.py","fixed_development_baseline_runtime.py","verify_retrieval_offline.py","token_windows.py","index_integrity.py","retrieval_source_units.py","checkpoint_publication.py","fixed_vector_cache.py","qdrant_backend.py","workspace_root.py","embedding_compatibility.py","structural_retrieval.py","preparation_cache.py","checkpoint_identity.py"))algorithms.put(name,sha(name));
            ObjectNode identity=sig.deepCopy();identity.set("runtime",health.path("runtime").path("embedding").deepCopy());
            ((ObjectNode)identity.path("tokenizerAndModelFiles")).putObject("checkpointPublication").put("manifestSha256",publications.path("embedding").path("sha256").asText()).put("publicationSha256",sha("embedding release"));
            ObjectNode recipe=windows.deepCopy(),rrTokenizer=tokenizer.deepCopy();recipe.set("rerankTokenizerAndModelFiles",rrTokenizer);rrTokenizer.set("model",model("reranker"));rrTokenizer.putObject("checkpointPublication").put("manifestSha256",publications.path("reranker").path("sha256").asText()).put("publicationSha256",sha("reranker release"));
            recipe.set("rerankRuntime",health.path("runtime").path("reranker").deepCopy());
            ObjectNode query=JsonUtils.mapper().createObjectNode();query.put("schema","consense.migrated-server-query-runtime.v1");query.put("migrationReceiptSha256",p.getMigrationReceiptSha256());query.put("queryApiBaseUrl",p.getBaseUrl());
            query.put("indexSignature",SIGNATURE);query.put("readOnly",true);query.put("runtimeCompatibilityVerified",false);query.set("target",migration.path("target"));query.set("fullVectorIntegrity",persisted);
            query.set("queryIdentity",identity);query.set("queryRecipe",recipe);query.set("health",health.deepCopy());
            java.lang.reflect.Method digest=FixedBaselineRetrievalClient.class.getDeclaredMethod("queryDigest",JsonNode.class);digest.setAccessible(true);query.put("queryRuntimeSignature",(String)digest.invoke(null,identity));
            Path qp=temp.resolve("query.json");write(qp,query);p.setQueryRuntimePath(qp.toString());p.setQueryRuntimeSha256(sha(Files.readAllBytes(qp)));
            ObjectNode access=response.putObject("indexAccess");access.put("mode","pinned-migrated-server-read-only");access.put("readOnly",true);access.put("runtimeCompatibilityVerified",false);access.put("migrationReceiptSha256",p.getMigrationReceiptSha256());
            for(String field:Arrays.asList("target","queryIdentity","queryRecipe","queryRuntimeSignature","fullVectorIntegrity"))access.set(field,query.path(field).deepCopy());
        }
    }
    @Test void disabledDoesNotReadOrCall() {
        HttpSupport http=mock(HttpSupport.class);FixedBaselineRetrievalClient c=new FixedBaselineRetrievalClient(http,new FixedBaselineProperties());
        assertFalse(c.enabled());assertThrows(IllegalStateException.class,()->c.search("vetting","q",1));verifyNoInteractions(http);
    }

    @Test void multiEditionRequiresSelectionAndRejectsUnselectedGroupMember() throws Exception {
        Fixture f=new Fixture(false);Path mp=Paths.get(f.p.getManifestPath());
        ObjectNode manifest=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(mp),StandardCharsets.UTF_8));
        ((ObjectNode)manifest.path("originalSources")).remove("gccSource");
        ArrayNode sources=((ObjectNode)manifest.path("originalSources")).putArray("slSources");
        ObjectNode a=(ObjectNode)descriptor(f.original);a.put("editionId","edition-a");sources.add(a);
        Path other=temp.resolve("other.pdf");Files.write(other,"other source".getBytes(StandardCharsets.UTF_8));
        ObjectNode b=(ObjectNode)descriptor(other);b.put("editionId","edition-b");sources.add(b);
        for(ObjectNode parent:Arrays.asList(f.first,f.second)){((ObjectNode)parent.path("sourceQuality")).put("family","SL");parent.put("fileKey","SL");}
        f.second.put("originalSourceSha256",b.path("sha256").asText());
        f.response.withArray("hits").addObject().put("id","parent-b").put("score",.7).set("payload",f.second);
        ObjectNode secondUnit=f.unit().deepCopy();secondUnit.put("unitId",sha("second group"));
        secondUnit.putArray("originIds").add("parent-b");secondUnit.putArray("requiredMemberIds").add("parent-b");secondUnit.putArray("members").add(f.second);
        f.unit().putArray("requiredMemberIds").add("parent-a");f.unit().putArray("members").add(f.first);
        ((ObjectNode)f.response.path("sourceUnits")).withArray("units").add(secondUnit);
        Files.write(f.corpus,(JsonUtils.write(f.first)+"\n"+JsonUtils.write(f.second)+"\n").getBytes(StandardCharsets.UTF_8));manifest.set("corpus",descriptor(f.corpus));
        write(mp,manifest);f.p.setManifestSha256(sha(Files.readAllBytes(mp)));
        assertThrows(IllegalStateException.class,f::search);verifyNoInteractions(f.http);
        ObjectNode selection=JsonUtils.mapper().createObjectNode().put("manifestSha256",f.p.getManifestSha256());
        selection.putArray("slSources").addObject().put("editionId","edition-a").put("sha256",ORIGINAL);
        f.response.putArray("selectedSourceSha256").add(ORIGINAL);
        assertThrows(IllegalStateException.class,()->f.client.searchEvidence("advisory","q",1,selection));
        selection.withArray("slSources").addObject().put("editionId","edition-b").put("sha256",b.path("sha256").asText());
        f.response.withArray("selectedSourceSha256").add(b.path("sha256").asText());
        assertEquals(2,f.client.searchEvidence("advisory","q",2,selection).size());
        selection.withArray("slSources").remove(1);((ObjectNode)selection.path("slSources").get(0)).put("editionId","wrong-edition");
        assertThrows(IllegalStateException.class,()->f.client.searchEvidence("advisory","q",1,selection));
    }

    @Test void projectSelectionIsPersistedAndPinsTheExactManifest() throws Exception {
        Fixture f=new Fixture(false);
        com.consense.repository.ProjectRepository repository=mock(com.consense.repository.ProjectRepository.class);
        com.consense.domain.Project project=new com.consense.domain.Project();project.setId("project-a");
        when(repository.findById("project-a")).thenReturn(Optional.of(project));
        java.lang.reflect.Field field=FixedBaselineRetrievalClient.class.getDeclaredField("projectRepository");field.setAccessible(true);field.set(f.client,repository);
        JsonNode selected=f.client.projectSelection("project-a");assertEquals(f.p.getManifestSha256(),selected.path("manifestSha256").asText());
        f.client.saveProjectSelection("project-a",selected);verify(repository).save(project);
        assertEquals(selected,JsonUtils.parse(project.getFixedReferenceSelectionJson()));
        ((ObjectNode)selected).put("manifestSha256",sha("stale manifest"));
        assertThrows(IllegalStateException.class,()->f.client.saveProjectSelection("project-a",selected));
        assertThrows(IllegalStateException.class,()->f.client.projectSelection("missing-project"));
    }
    @Test void fullSourceGroupAndTopLevelNullWireArePreserved() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();assertEquals(2,hits.size());
        JsonNode material=JsonUtils.parse(f.client.toModelMaterial(hits,100000));assertEquals("reference_only",material.path("basisScope").asText());
        assertEquals(2,material.path("references").size());assertEquals(f.second.path("content"),material.path("references").get(1).path("content"));
        assertFalse(material.toString().contains("bbox"));assertFalse(hits.get(0).getPayload().containsKey("canonicalEvidence"));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(Collections.singletonList(hits.get(0)),100000));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(hits,20));
        assertEquals(f.second.path("content").asText(),f.client.readOnlyHitById("parent-b").text());
    }
    @Test void refusesChangedPayloadPartsScopeAndSignature() throws Exception {
        Fixture f=new Fixture(false);f.first.put("content","changed content");assertThrows(IllegalStateException.class,f::search);
        f.first.put("content","1.1 Authored complete condition");((ObjectNode)f.first.path("parts").get(0)).put("text","changed part");assertThrows(IllegalStateException.class,f::search);
        ((ObjectNode)f.first.path("parts").get(0)).put("text","1.1 Authored complete condition");f.first.put("role","tender");assertThrows(IllegalStateException.class,f::search);
        f.first.put("role","standard");f.response.put("indexSignature",sha("wrong signature"));assertThrows(IllegalStateException.class,f::search);
    }
    @Test void refusesIncompleteClosureAndUnknownSource() throws Exception {
        Fixture f=new Fixture(false);((ArrayNode)f.unit().path("members")).remove(1);assertThrows(IllegalStateException.class,f::search);
        ((ArrayNode)f.unit().path("members")).add(f.second);f.second.put("originalSourceSha256",sha("wrong original"));assertThrows(IllegalStateException.class,f::search);
    }
    @Test void migrationPinsAllowDeclaredCpuQueryAndKeepCompleteGroupGuard() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();assertEquals(2,f.search().size());
        ((ArrayNode)f.unit().path("members")).remove(1);assertThrows(IllegalStateException.class,f::search);
    }
    @Test void missingChangedOrRemovedMigrationPinsFailBeforeHttp() throws Exception {
        Fixture partial=new Fixture(false);partial.p.setMigrationReceiptPath("partial");assertThrows(IllegalStateException.class,partial::search);verifyNoInteractions(partial.http);
        Fixture wrong=new Fixture(false);wrong.enableMigration();wrong.p.setMigrationReceiptSha256(sha("wrong pin"));assertThrows(IllegalStateException.class,wrong::search);verifyNoInteractions(wrong.http);
        Fixture f=new Fixture(false);f.enableMigration();f.search();clearInvocations(f.http);
        Files.write(Paths.get(f.p.getQueryRuntimePath()),"{}".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,f::search);verifyNoInteractions(f.http);
    }
    @Test void migratedRuntimeUrlVectorAndHelperDriftAreRejected() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();ObjectNode access=(ObjectNode)f.response.path("indexAccess"),target=(ObjectNode)access.path("target");
        target.put("url","http://127.0.0.1:16334");assertThrows(IllegalStateException.class,f::search);target.put("url","http://127.0.0.1:16333");
        ObjectNode seal=(ObjectNode)access.path("fullVectorIntegrity");seal.put("pointsSha256",sha("same count corruption"));assertThrows(IllegalStateException.class,f::search);seal.put("pointsSha256",sha("complete vectors"));
        ((ObjectNode)access.path("queryRecipe").path("algorithms")).put("migrated_qdrant_index.py",sha("changed helper"));assertThrows(IllegalStateException.class,f::search);
        ((ObjectNode)f.health.path("runtime").path("embedding")).put("dtype","float16");assertThrows(IllegalStateException.class,f::search);
    }
    @Test void defaultLocalGuardStillRejectsServerHealth() throws Exception {
        Fixture f=new Fixture(false);f.health.put("mode","server");assertThrows(IllegalStateException.class,f::search);verify(f.http,never()).postJson(anyString(),anyString(),anyLong());
    }
    @Test void portableV5HealthMustMatchTheFrozenRuntimePlan() throws Exception {
        Fixture f=new Fixture(false,5);assertEquals(2,f.search().size());
        f.health.put("indexSignatureVersion",3);assertThrows(IllegalStateException.class,f::search);
    }
    @Test void oldPinnedMigratedQueryNeverAcceptsAnUnannouncedVersionUpgrade() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();f.health.put("indexSignatureVersion",5);
        assertThrows(IllegalStateException.class,f::search);
    }
    @Test void explicitlyPinnedV5QueryKeepsTheHistoricalV3IndexIdentity() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();Path qp=Paths.get(f.p.getQueryRuntimePath());
        ObjectNode query=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(qp),StandardCharsets.UTF_8));
        ((ObjectNode)query.path("queryIdentity")).put("signatureVersion",5);f.health.put("indexSignatureVersion",5);
        ((ObjectNode)f.health.path("retrieval").path("sourceWindows").path("algorithms")).put("portable_index_signature.py",sha("portable adapter"));
        ((ObjectNode)query.path("queryRecipe").path("algorithms")).put("portable_index_signature.py",sha("portable adapter"));query.set("health",f.health.deepCopy());
        java.lang.reflect.Method digest=FixedBaselineRetrievalClient.class.getDeclaredMethod("queryDigest",JsonNode.class);digest.setAccessible(true);
        query.put("queryRuntimeSignature",(String)digest.invoke(null,query.path("queryIdentity")));write(qp,query);f.p.setQueryRuntimeSha256(sha(Files.readAllBytes(qp)));
        for(String key:Arrays.asList("queryIdentity","queryRecipe","queryRuntimeSignature"))((ObjectNode)f.response.path("indexAccess")).set(key,query.path(key).deepCopy());
        assertEquals(2,f.search().size());assertEquals(SIGNATURE,f.response.path("indexSignature").asText());
    }
    @Test void querySignatureMatchesActualPythonIntegerJsonGolden() throws Exception {
        JsonNode value=JsonUtils.parse("{\"modelMaxLength\":1000000000000000019884624838656,\"files\":[{\"bytes\":10}]}");
        java.lang.reflect.Method digest=FixedBaselineRetrievalClient.class.getDeclaredMethod("queryDigest",JsonNode.class);digest.setAccessible(true);
        assertEquals("1f9b27100148c6e0e18d728e25673b67120895fb703d61ac59c390ed2b963b49",digest.invoke(null,value));
    }
    @Test void repinnedWrongOriginalColdOrTargetVectorSealFailsBeforeHttp() throws Exception {
        for(String kind:Arrays.asList("sourceVectorIntegrity","targetVectorIntegrity")) {
            Fixture f=new Fixture(false);f.enableMigration();Path path=Paths.get(f.p.getMigrationReceiptPath());
            ObjectNode receipt=(ObjectNode)JsonUtils.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8));
            ((ObjectNode)receipt.path(kind)).put("pointsSha256",sha("same count wrong vector"));write(path,receipt);
            f.p.setMigrationReceiptSha256(sha(Files.readAllBytes(path)));
            assertTrue(assertThrows(IllegalStateException.class,f::search).getMessage().contains("migration_original_persisted_vectors_changed"));verifyNoInteractions(f.http);
        }
    }
    @Test void tableNullAndEmptyRemainDistinctAndBodyIsNotRepeated() throws Exception {
        Fixture f=new Fixture(true);List<VectorStore.SearchHit> hits=f.search();JsonNode material=JsonUtils.parse(f.client.toModelMaterial(hits,100000));
        JsonNode table=material.path("references").get(1);assertFalse(table.has("content"));JsonNode matrix=table.path("completeObservedTable").path("matrix");
        assertTrue(matrix.get(0).get(1).isNull());assertEquals("",matrix.get(0).get(2).asText());assertEquals("unknown",table.path("completeObservedTable").path("trueBlankAccepted").asText());
        Files.write(temp.resolve("table.json"),"{}".getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
    }
    @Test void rejectsCorpusOrConfigurationChangeAfterCatalog() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();f.p.setIndexSignature(sha("new signature"));assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        f.p.setIndexSignature(SIGNATURE);Files.write(f.corpus,"extra".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);assertThrows(IllegalStateException.class,()->f.client.frozenManifest());
    }
    @Test void rejectsSameSizeOriginalReplacementEvenWithRestoredTimestamp() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.original);
        Files.write(f.original,"modified source".getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(f.original,before);
        assertEquals(15,Files.size(f.original));
        assertThrows(IllegalStateException.class,f::search);
        assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        assertThrows(IllegalStateException.class,()->f.client.toModelMaterial(hits,100000));
        assertThrows(IllegalStateException.class,()->f.client.readOnlyHitById("parent-a"));
    }
    @Test void rejectsSameSizeSelectedCorpusReplacementWithRestoredTimestamp() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.corpus);
        String content=new String(Files.readAllBytes(f.corpus),StandardCharsets.UTF_8);
        Files.write(f.corpus,content.replace("Authored complete","Altered complete").getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(f.corpus,before);
        assertThrows(IllegalStateException.class,f::search);
        assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits));
        assertThrows(IllegalStateException.class,()->f.client.readOnlyHitById("parent-a"));
    }
    @Test void requestReadGuardChecksFreshOriginalIdentityBeforeEveryWait() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();Runnable guard=f.client.currentReadGuard(hits);
        guard.run();Files.delete(f.original);assertThrows(IllegalStateException.class,guard::run);
    }
    @Test void intermediateGuardAvoidsWholeOriginalRehashButFinalGuardRejectsSameAttributeMutation() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();Runnable guard=f.client.currentReadGuard(hits);
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.original);
        Files.write(f.original,"tampered source".getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(f.original,before);
        assertDoesNotThrow(guard::run,"Intermediate identity guards do not claim a fresh full byte hash");
        assertThrows(IllegalStateException.class,()->f.client.assertCurrent(hits),"The final full original-byte guard remains mandatory");
    }
    @Test void requestReadGuardStillChecksExactSelectedCorpusBytesWithRestoredTimestamp() throws Exception {
        Fixture f=new Fixture(false);List<VectorStore.SearchHit> hits=f.search();Runnable guard=f.client.currentReadGuard(hits);
        java.nio.file.attribute.FileTime before=Files.getLastModifiedTime(f.corpus);
        String text=new String(Files.readAllBytes(f.corpus),StandardCharsets.UTF_8);
        Files.write(f.corpus,text.replace("Authored complete","Altered complete").getBytes(StandardCharsets.UTF_8));Files.setLastModifiedTime(f.corpus,before);
        assertThrows(IllegalStateException.class,guard::run);
    }
    @Test void requestReadGuardKeepsPinnedMigrationArtifactsAndConfiguration() throws Exception {
        Fixture f=new Fixture(false);f.enableMigration();List<VectorStore.SearchHit> hits=f.search();Runnable guard=f.client.currentReadGuard(hits);
        guard.run();f.p.setQueryRuntimeSha256(sha("changed query pin"));assertThrows(IllegalStateException.class,guard::run);
    }
    private static ObjectNode parent(String id,String text,int page) {
        ObjectNode p=JsonUtils.mapper().createObjectNode();p.put("id",id);p.put("content",text);p.put("role","standard");p.put("fileKey","GCC");p.put("documentId","GCC:GCC");p.put("anchor","GCC/P"+page);p.put("pageNo",page);
        p.put("sourceHash",SOURCE);p.put("originalSourceSha256",ORIGINAL);p.put("sourceQualityHash",sha("authored quality"));p.put("fullObservedParentPreserved",true);p.put("fixedDevelopmentBaseline",true);
        p.put("qualityAdopted",false);p.put("legalScopeAccepted",false);p.put("semanticScopeVerified",false);p.put("qualifiersComplete","unknown");p.putObject("sourceQuality").put("family","GCC").put("finalQualityAccepted",false);
        ObjectNode part=p.putArray("parts").addObject();part.put("blockId",id+"-block");part.put("pageNo",page);part.put("text",text);part.putArray("bbox").add(.1).add(.2).add(.3).add(.4);return p;
    }
    private static ObjectNode model(String kind) {ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("name","embedding".equals(kind)?"BAAI/bge-m3":"BAAI/bge-reranker-v2-m3");n.put("revision","embedding".equals(kind)?"5617a9f61b028005a4858fdac845db406aefb181":"953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e");return n;}
    private static ObjectNode runtime(String kind,int batch) {ObjectNode n=model(kind);n.put("maxTokens",512);n.put("batchSize",batch);n.put("device","cuda");n.put("dtype","float16");n.put("serialOffload",true);return n;}
    private static void write(Path path,JsonNode n) throws Exception {Files.write(path,JsonUtils.write(n).getBytes(StandardCharsets.UTF_8));}
    private static ObjectNode descriptor(Path path) throws Exception {ObjectNode n=JsonUtils.mapper().createObjectNode();n.put("path",path.toString());n.put("bytes",Files.size(path));n.put("sha256",sha(Files.readAllBytes(path)));return n;}
    private static String sha(String v){return sha(v.getBytes(StandardCharsets.UTF_8));}
    private static String sha(byte[] v){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(v))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
