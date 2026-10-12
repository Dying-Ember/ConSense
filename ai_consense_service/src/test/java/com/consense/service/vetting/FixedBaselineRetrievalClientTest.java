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
        Fixture(boolean table) throws Exception {
            if(table) {
                ObjectNode artifact=JsonUtils.mapper().createObjectNode();artifact.put("id","parent-b");artifact.put("originalSourceSha256",ORIGINAL);artifact.put("sourceQualityAccepted",false);
                ArrayNode matrix=artifact.putObject("table").putArray("extractedMatrix");matrix.addArray().add("Heading").addNull().add("");matrix.addArray().add("Authored value").add("Exception").add("Cell");
                Path artifactPath=temp.resolve("table.json");write(artifactPath,artifact);second.set("tableArtifact",descriptor(artifactPath));
            }
            ObjectNode rawFirst=first.deepCopy();rawFirst.putNull("observedNodeId");
            Files.write(corpus,(JsonUtils.write(rawFirst)+"\n"+JsonUtils.write(second)+"\n").getBytes(StandardCharsets.UTF_8));
            ObjectNode runtime=runtime("embedding",16),rr=runtime("reranker",4);
            health.put("status","ok");health.put("mode","local");health.put("offline",true);health.put("device","cuda");health.put("indexSignatureVersion",3);
            ObjectNode models=health.putObject("models");models.set("embedding",model("embedding"));models.set("reranker",model("reranker"));
            ObjectNode runs=health.putObject("runtime");runs.set("embedding",runtime);runs.set("reranker",rr);
            ObjectNode retrieval=health.putObject("retrieval");retrieval.put("roleFilter",true);ObjectNode windows=retrieval.putObject("sourceWindows");
            windows.put("contract","source-bound-window-points-full-parent-results-v1");windows.put("denseAggregation","max");windows.put("rerankAggregation","max");
            ObjectNode recipe=JsonUtils.mapper().createObjectNode();recipe.put("max_tokens",512);recipe.put("overlap_tokens",64);recipe.put("max_windows_per_parent",10000);recipe.put("aggregation","max");recipe.put("version","source-bound-token-windows-v1");
            windows.set("embeddingWindows",recipe);windows.set("rerankWindows",recipe);windows.set("rerankRuntime",rr);windows.putObject("algorithms");
            windows.putObject("structuralSelection").put("policy","unique-source-structural-unit-seeds-v1").put("closureMembersAreRanked",false);
            ObjectNode plan=JsonUtils.mapper().createObjectNode();plan.put("projectId","authored-fixed");plan.put("indexSignatureSha256",SIGNATURE);
            ObjectNode sig=plan.putObject("signature");sig.set("embedding",model("embedding"));sig.set("runtime",runtime);sig.set("windowRecipe",recipe);sig.putObject("algorithms");
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
    }
    @Test void disabledDoesNotReadOrCall() {
        HttpSupport http=mock(HttpSupport.class);FixedBaselineRetrievalClient c=new FixedBaselineRetrievalClient(http,new FixedBaselineProperties());
        assertFalse(c.enabled());assertThrows(IllegalStateException.class,()->c.search("vetting","q",1));verifyNoInteractions(http);
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
