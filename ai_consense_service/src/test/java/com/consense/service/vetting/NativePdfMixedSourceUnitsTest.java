package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.sources.CanonicalEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact saved numeric producer/family public bytes; no producer, CAS, HTTP or model call.
 * Mutations below are consumer guard declarations, never resealed source artifacts. */
class NativePdfMixedSourceUnitsTest {
    @Test void actualMixedFamilyProseResolvesAsCompleteWithoutChangingItsOriginalIdentity()throws Exception {
        completeFamily(0,"native-pdf-no-table-adoption-v1");
    }
    @Test void actualMixedFamilyTableResolvesAsCompleteWithItsWholeTablePointerAndAliases()throws Exception {
        completeFamily(1,"source-reviewed-native-pdf-whole-table-groups-v1");
    }
    @Test void actualStandaloneRootsKeepTheirAlreadyCorrectObservedUnitHashes()throws Exception {
        for(String kind:Arrays.asList("prose","table")){
            ObjectNode root=root(kind+"-numeric-public-wire.json");JsonNode seed=root.path("chunks").get(0);
            ObjectNode observed=OerCanonicalSourceUnits.resolveObserved(parents(root),seed);
            assertEquals("complete_observed_unit",observed.path("status").asText());
            assertEquals("prose".equals(kind)?"15728b5e85557b4f9cab7b5e81ab6355238147b46771d98763063836cadc319a":"98b53ef37d0ce9bc83b652018392854436fb20cfc726bb0e7b556c964b89fa6e",observed.path("unitId").asText());
            assertEquals(seed,observed.path("members").get(0));
        }
    }
    @Test void changedRootRevisionScopeModulesOrSlAliasesCannotJoinTheSameDocument()throws Exception {
        for(String field:Arrays.asList("sourceHash","role","metadataVersion","segmentationVersion","sourceQualityMetadataVersion","sourceQualityHash","originalSourceSha256","sourceSha256","projectionRevisionSha256","sourceRevisionSha256","scopeSha256","sourceEditionId","slUnitScopeManifestSha256","module","eligibleModules")){
            ObjectNode value=root("numeric-family-public-wire.json"),changed=(ObjectNode)value.path("chunks").get(1);
            if("eligibleModules".equals(field))changed.putArray(field).add("vetting");
            else changed.put(field,field.endsWith("Sha256")||"sourceHash".equals(field)||"sourceQualityHash".equals(field)?"a".repeat(64):"other-source-declaration");
            unknown(value,field);
        }
    }
    @Test void missingUnknownOrWrongUnitMetadataNeverGetsNativeRootComparison()throws Exception {
        for(String mutation:Arrays.asList("missing","unknown","scope","producer","storage")){
            ObjectNode value=root("numeric-family-public-wire.json"),changed=(ObjectNode)value.path("chunks").get(1);
            if("missing".equals(mutation))changed.remove("nativeTableMetadataVersion");
            else if("unknown".equals(mutation))changed.put("nativeTableMetadataVersion","table-supported");
            else if("scope".equals(mutation))changed.put("canonicalScope","whole_text_unit");
            else if("producer".equals(mutation))changed.put("producerKind","native_docx");
            else changed.put("storageShape","legacy");
            unknown(value,mutation);
        }
    }
    @Test void ordinaryAndUnknownProducerShapesKeepTheExistingStrictVersionTuple()throws Exception {
        for(String producer:Arrays.asList("native_docx","dual_parser","unknown")){
            ObjectNode value=root("numeric-family-public-wire.json");
            value.path("chunks").forEach(chunk->((ObjectNode)chunk).put("producerKind",producer));
            unknown(value,producer);
        }
    }
    private static void completeFamily(int ordinal,String metadata)throws Exception {
        ObjectNode root=root("numeric-family-public-wire.json"),before=root.deepCopy();Map<String,JsonNode> parents=parents(root);JsonNode seed=root.path("chunks").get(ordinal);
        ObjectNode result=OerCanonicalSourceUnits.resolveAttached(parents,seed,true);
        assertEquals("complete_observed_unit",result.path("status").asText(),result.path("reason").asText());assertTrue(result.path("reason").isNull());
        assertEquals(1,result.path("members").size());assertEquals(seed,result.path("members").get(0));assertEquals(metadata,result.path("sourceIdentity").path("nativeTableMetadataVersion").asText());
        assertEquals(seed.path("sourceUnitId"),result.path("members").get(0).path("sourceUnitId"));assertEquals(seed.path("sourceEditionId"),result.path("members").get(0).path("sourceEditionId"));
        assertEquals(seed.path("nativeTableGroupsRef"),result.path("members").get(0).path("nativeTableGroupsRef"));assertEquals(seed.path("nativeTableSummary"),result.path("members").get(0).path("nativeTableSummary"));
        assertTrue(result.path("memberScores").isNull());assertFalse(result.path("derivedMembersCanBecomeOrigins").asBoolean(true));assertFalse(result.path("semanticScopeVerified").asBoolean(true));assertEquals("unknown",result.path("qualifiersComplete").asText());
        assertFalse(result.has("hierarchyContext"));assertFalse(result.has("attachmentContext"));assertEquals(before,root);
    }
    private static void unknown(ObjectNode root,String label){Map<String,JsonNode> parents=parents(root);for(JsonNode seed:parents.values())assertEquals("unknown",OerCanonicalSourceUnits.resolveObserved(parents,seed).path("status").asText(),label);}
    private static Map<String,JsonNode> parents(ObjectNode root){Map<String,JsonNode> parents=new LinkedHashMap<>();root.path("chunks").forEach(chunk->assertNull(parents.put(chunk.path("id").asText(),chunk)));return parents;}
    private static ObjectNode root(String name)throws Exception {
        Map<String,String> pins=Map.of("numeric-family-public-wire.json","bf9eefb1692346ef48763c7202e9bb22a2ca4b52d9c03bf4338397e48a139c24","prose-numeric-public-wire.json","a0b6ac9445748d119f8adb93264ba43087e1900cc7bba8e76fb4687ba6cb35d0","table-numeric-public-wire.json","8884f6934c5c8ccd6ef2fd88ebab47e90876fed8c30e4819fb89441b971e1858");
        try(InputStream input=NativePdfMixedSourceUnitsTest.class.getResourceAsStream("/oer/native-pdf-mixed-family/"+name)){
            assertNotNull(input,name);String raw=new String(input.readAllBytes(),StandardCharsets.UTF_8);assertEquals(pins.get(name),VettingCorpus.hash(raw));ObjectNode root=(ObjectNode)JsonUtils.parse(raw);
            assertEquals(root,CanonicalEvidenceService.validateSnapshot(raw,root.path("projectionSha256").asText()));return root;
        }
    }
}
