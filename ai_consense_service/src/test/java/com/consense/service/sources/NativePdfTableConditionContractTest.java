package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Saved actual producer outputs over authored retained PDF/native/dual inputs.
 * These Java declaration controls do not repeat Python cold CAS or certify source semantics. */
class NativePdfTableConditionContractTest {
    private static final String RESOURCE="/oer/native-pdf-table-condition/";

    @Test void headerOnlyCompleteTableKeepsZeroLogicalRows() throws Exception {
        ObjectNode value=wire("header-only");
        assertEquals(0,value.at("/chunks/0/nativeTableSummary/0/logicalRowCount").asInt(-1));
        assertEquals(0,value.at("/manifest/tableGroups/0/logicalRows").size());
        assertEquals(0,value.at("/manifest/tableGroups/0/fragments/0/headerRows/0").asInt(-1));
        assertEquals(2,value.at("/manifest/tableGroups/0/fragments/0/cells").size());
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void samePageConditionBeforeTableRemainsInTheWholeUnit() throws Exception {
        ObjectNode value=wire("same-page-condition-before");
        assertWholeCondition(value,1);
        assertEquals(value.at("/chunks/0/parts/0/referenceRegionId"),value.at("/chunks/0/relations/0/fromRegionId"));
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void samePageConditionAfterTableRemainsInTheWholeUnit() throws Exception {
        ObjectNode value=wire("same-page-condition-after");
        assertWholeCondition(value,0);
        assertEquals(value.at("/chunks/0/parts/1/referenceRegionId"),value.at("/chunks/0/relations/0/fromRegionId"));
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void crossPageConditionKeepsItsPageAndAssetsOutsideTheFragmentPageSummary() throws Exception {
        ObjectNode value=wire("cross-page-condition-before");
        assertWholeCondition(value,1);
        assertEquals(JsonUtils.parse("[3,4]"),value.at("/chunks/0/physicalPages"));
        assertEquals(JsonUtils.parse("[4]"),value.at("/chunks/0/nativeTableSummary/0/physicalPages"));
        assertEquals(3,value.at("/chunks/0/parts/0/physicalPageNo").asInt());
        assertEquals(4,value.at("/chunks/0/parts/1/physicalPageNo").asInt());
        assertEquals(2,value.at("/chunks/0/assetRefs").size());
        assertEquals(3,value.at("/chunks/0/assetRefs/0/physicalPageNo").asInt());
        assertEquals(4,value.at("/chunks/0/assetRefs/1/physicalPageNo").asInt());
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(value));
    }

    @Test void zeroLogicalRowsDoNotExcuseMissingGridSlotsOrBodyRows() throws Exception {
        rejected("header-only",v->array(v,"/manifest/tableGroups/0/fragments/0/cells").remove(1));
        rejected("header-only",v->object(v,"/chunks/0/nativeTableSummary/0").put("logicalRowCount",-1));
        rejected("header-only",v->object(v,"/chunks/0/nativeTableSummary/0").put("logicalRowCount",.5));
        rejected("header-only",v->object(v,"/chunks/0/nativeTableSummary/0").put("logicalRowCount",1));
        rejected("same-page-condition-after",v->{
            array(v,"/manifest/tableGroups/0/logicalRows").removeAll();
            object(v,"/chunks/0/nativeTableSummary/0").put("logicalRowCount",0);
        });
    }

    @Test void relatedConditionCannotBeDroppedFromAnyWholeGroupInventory() throws Exception {
        rejected("same-page-condition-after",v->array(v,"/chunks/0/parts").remove(1));
        rejected("same-page-condition-after",v->array(v,"/manifest/tableGroups/0/regionIds").remove(1));
        rejected("same-page-condition-after",v->array(v,"/chunks/0/adoption/regionIds").remove(1));
        rejected("same-page-condition-after",v->array(v,"/chunks/0/nativeTableSummary/0/completeness/present").remove(1));
        rejected("same-page-condition-after",v->array(v,"/chunks/0/sourceSpans").remove(1));
    }

    @Test void fragmentSubsetStillRejectsEmptyDuplicateAndUndeclaredRegions() throws Exception {
        rejected("same-page-condition-before",v->array(v,"/manifest/tableGroups/0/fragments").removeAll());
        rejected("same-page-condition-before",v->{
            ArrayNode fragments=array(v,"/manifest/tableGroups/0/fragments");
            fragments.add(fragments.get(0).deepCopy());
        });
        rejected("same-page-condition-before",v->object(v,"/manifest/tableGroups/0/fragments/0").put("regionId","AUTHORED-undeclared-region"));
    }

    @Test void fragmentPageSummaryCannotAbsorbConditionPagesOrUnrelatedPages() throws Exception {
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/nativeTableSummary/0").set("physicalPages",JsonUtils.parse("[3,4]")));
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/nativeTableSummary/0").set("physicalPages",JsonUtils.parse("[3]")));
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/nativeTableSummary/0").set("physicalPages",JsonUtils.parse("[2]")));
        rejected("cross-page-condition-before",v->array(v,"/chunks/0/nativeTableSummary/0/physicalPages").removeAll());
    }

    @Test void declaredConditionPageAssetCannotDisappearFromTheCoveringChunk() throws Exception {
        rejected("cross-page-condition-before",v->array(v,"/chunks/0/assetRefs").remove(0));
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/assetRefs/0").put("physicalPageNo",4));
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/assetRefs/0").put("quality","accepted"));
    }

    @Test void tableAndConditionSourceQualityRemainUnknownAfterAdmission() throws Exception {
        ObjectNode value=wire("cross-page-condition-before");
        assertFalse(value.path("humanConfirmed").asBoolean(true));
        assertFalse(value.path("semanticScopeVerified").asBoolean(true));
        assertEquals("unknown",value.path("wholeOriginalSourceCoverage").asText());
        assertFalse(value.at("/transportValidation/qualityCertified").asBoolean(true));
        assertEquals("unknown",value.at("/chunks/0/sourceQuality/semanticQuality").asText());
        for(JsonNode part:value.at("/chunks/0/parts"))
            assertEquals("unknown_not_raster_verified",part.path("nativeEffectiveVisibility").asText());
        rejected("cross-page-condition-before",v->object(v,"/chunks/0/sourceQuality").put("semanticQuality","accepted"));
        rejected("cross-page-condition-before",v->v.put("humanConfirmed",true));
    }

    private static void assertWholeCondition(ObjectNode value,int fragmentPart) {
        assertEquals(2,value.at("/chunks/0/parts").size());
        assertEquals(1,value.at("/manifest/tableGroups/0/fragments").size());
        assertEquals(2,value.at("/manifest/tableGroups/0/regionIds").size());
        assertEquals(value.at("/manifest/tableGroups/0/regionIds"),value.at("/chunks/0/adoption/regionIds"));
        assertEquals(value.at("/manifest/tableGroups/0/regionIds"),value.at("/chunks/0/nativeTableSummary/0/completeness/required"));
        assertEquals(value.at("/chunks/0/parts/"+fragmentPart+"/referenceRegionId"),value.at("/manifest/tableGroups/0/fragments/0/regionId"));
        assertEquals(1,value.at("/chunks/0/relations").size());
        assertEquals("qualifier",value.at("/chunks/0/relations/0/type").asText());
        assertEquals("within_whole_unit",value.at("/chunks/0/relations/0/scope").asText());
    }

    private static void rejected(String kind,Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode value=wire(kind);mutation.accept(value);
        assertThrows(CanonicalEvidenceException.class,()->NativePdfCanonicalContract.validateEnvelope(value));
    }
    private static ObjectNode object(JsonNode value,String pointer){return (ObjectNode)value.at(pointer);}
    private static ArrayNode array(JsonNode value,String pointer){return (ArrayNode)value.at(pointer);}
    private static ObjectNode wire(String kind) throws Exception {
        JsonNode provenance=JsonUtils.parse(new String(resource("fixture-provenance.json"),StandardCharsets.UTF_8));
        assertEquals("consense.sl-table-condition-public-fixtures.v1",provenance.path("schema").asText());
        assertTrue(provenance.path("actualProducerOutputsVerified").asBoolean(false));
        assertFalse(provenance.path("sourceQualityAccepted").asBoolean(true));
        JsonNode record=provenance.path("cases").path(kind);assertTrue(record.isObject(),kind);
        assertTrue(record.path("newActualProducerCalled").asBoolean(false));
        assertTrue(record.path("coldHydrateEqualsInternal").asBoolean(false));
        byte[] raw=resource(kind+"-public-wire.json");
        assertEquals(record.path("sha256").asText(),CanonicalJson.sha(raw));
        assertEquals(record.path("byteCount").asInt(-1),raw.length);
        return (ObjectNode)JsonUtils.parse(new String(raw,StandardCharsets.UTF_8));
    }
    private static byte[] resource(String name) throws Exception {
        try(InputStream stream=NativePdfTableConditionContractTest.class.getResourceAsStream(RESOURCE+name)){
            assertNotNull(stream,"Root must first run and verify the actual saved-source producer fixture: "+name);
            return stream.readAllBytes();
        }
    }
}
