package com.consense.service.sources;

import com.consense.common.JsonUtils;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.service.vetting.OerModelMaterial;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual two-wire positives plus authored declaration controls, never new CAS/source acceptance. */
class NativePdfCompleteStructureContractTest {
    private static final String OTHER="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test void allFiveInternalRecipeDeclarationShapesUseTheirMatchingPublicRecipe() throws Exception {
        NativePdfCanonicalContract.validateEnvelope(wire("prose"));
        NativePdfCanonicalContract.validateEnvelope(wire("table"));
        for(String version:Arrays.asList(NativePdfCanonicalContract.INTERNAL_VERSION,NativePdfCanonicalContract.INTERNAL_V2))
            assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(ordinary(wire("prose"),version)));
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(ordinary(wire("table"),NativePdfCanonicalContract.INTERNAL_V3)));
    }

    @Test void publicAndInternalRecipePairingsCannotBeMixed() throws Exception {
        rejected("prose",v->object(v,"/projectionIdentity").put("projectionVersion",NativePdfCanonicalContract.TABLE_VERSION));
        rejected("table",v->object(v,"/projectionIdentity").put("projectionVersion",NativePdfCanonicalContract.VERSION));
        rejected("table",v->object(v,"/chunks/0").put("metadataVersion",NativePdfCanonicalContract.VERSION));
        rejected("table",v->object(v,"/projectionIdentity/projectionDependencySha256").remove("native_pdf_table_projection.py"));
    }

    @Test void slScopeAliasesModulesAndWholeUnitStayBound() throws Exception {
        rejected("prose",v->object(v,"/manifest/scope/slTechnicalUnit").put("editionId","other"));
        rejected("prose",v->object(v,"/manifest/scope").put("scopePolicySha256",OTHER));
        rejected("prose",v->object(v,"/manifest/scope/slTechnicalUnit/scopeManifestRef").put("relativePath","../scope"));
        rejected("prose",v->object(v,"/chunks/0").put("sourceUnitId","other"));
        rejected("prose",v->object(v,"/chunks/0").put("fileKey","GCC"));
        rejected("prose",v->object(v,"/chunks/0").remove("sourceEditionId"));
        rejected("prose",v->{object(v,"/projectionIdentity").put("sourcePurpose","project_document");object(v,"/manifest").put("sourcePurpose","project_document");});
        rejected("prose",v->{object(v,"/projectionIdentity").put("role","tender");object(v,"/manifest").put("role","tender");});
    }

    @Test void pointToReferenceRasterBridgeCannotBeDroppedOrRebound() throws Exception {
        rejected("prose",v->layout(v).remove("referenceRasterGeometry"));
        rejected("prose",v->object(layout(v),"/rasterMappingReview").remove("referenceRasterBridge"));
        rejected("prose",v->object(layout(v),"/rasterMappingReview/referenceRasterBridge").put("nativeFrameSha256",OTHER));
        rejected("prose",v->object(layout(v),"/rasterMappingReview/referenceRasterBridge/referenceFrame").put("rasterSha256",OTHER));
        rejected("prose",v->((ArrayNode)layout(v).at("/rasterMappingReview/referenceRasterBridge/matrix")).set(4,JsonUtils.mapper().getNodeFactory().numberNode(3)));
        rejected("prose",v->object(layout(v),"/referenceRasterGeometry").put("pointGeometrySha256",OTHER));
        rejected("prose",v->((ArrayNode)layout(v).at("/referenceRasterGeometry/xyxyNormalized")).set(0,JsonUtils.mapper().getNodeFactory().numberNode(.5)));
    }

    @Test void wholeTableTypedReferenceCannotUseAnotherSourceOrPartialPointer() throws Exception {
        rejected("table",v->object(v,"/chunks/0/nativeTableGroupsRef").put("jsonPointer","/chunks/0/nativeTables/0/fragments/0"));
        rejected("table",v->object(v,"/chunks/0/nativeTableGroupsRef").put("sourceSha256",OTHER));
        rejected("table",v->object(v,"/chunks/0/nativeTableGroupsRef").put("internalProjectionSha256",OTHER));
        rejected("table",v->object(v,"/chunks/0/nativeTableGroupsRef").put("kind","native_pdf_whole_region"));
        rejected("table",v->object(v,"/chunks/0/nativeTableGroupsRef/artifactDescriptor").put("sha256",OTHER));
    }

    @Test void wholeTableCompletenessBlankCellsAndRowsCannotBeTrimmed() throws Exception {
        rejected("table",v->((ArrayNode)v.at("/chunks/0/nativeTableSummary/0/completeness/present")).removeAll());
        rejected("table",v->object(v,"/chunks/0/nativeTableSummary/0").put("logicalRowCount",2));
        rejected("table",v->((ArrayNode)v.at("/manifest/tableGroups/0/fragments/0/cells")).remove(1));
        rejected("table",v->object(v,"/manifest/tableGroups/0/fragments/0/cells/1").put("valueState","literal"));
        rejected("table",v->((ArrayNode)v.at("/manifest/tableGroups/0/logicalRows")).removeAll());
    }

    @Test void originalPageAssetsRetainExactDescriptorSourceAndUnknownQuality() throws Exception {
        rejected("table",v->object(v,"/assetRefs/0").put("quality","accepted"));
        rejected("table",v->object(v,"/assetRefs/0").put("sourceSha256",OTHER));
        rejected("table",v->object(v,"/assetRefs/0").put("byteCount",1));
        rejected("table",v->object(v,"/assetRefs/0/sourceRelation").put("originalPhysicalPageNo",3));
        rejected("table",v->((ArrayNode)v.at("/chunks/0/assetRefs")).removeAll());
        rejected("table",v->object(v,"/manifest/assets/0").put("assetId","other"));
    }

    @Test void ordinaryAssetOnlyRecipeDoesNotRequireInventedTableMetadata() throws Exception {
        ObjectNode v=ordinary(wire("table"),NativePdfCanonicalContract.INTERNAL_V3),c=object(v,"/chunks/0");
        ((ArrayNode)v.at("/manifest/tableGroups")).removeAll();c.put("canonicalScope","whole_text_unit").put("nativeTableMetadataVersion","native-pdf-no-table-adoption-v1");c.remove(Arrays.asList("nativeTableGroupsRef","nativeTableSummary"));
        object(c,"/adoption").put("scope","whole_text_unit").remove("tableGroupId");object(v,"/manifest/units/0").put("scope","whole_text_unit").remove("tableGroupId");object(v,"/canonical/units/0").put("scope","whole_text_unit");
        assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(v));
    }

    @Test void crossPageLogicalRowsRequireCompleteMembershipAndSourceContinuation() throws Exception {
        // Isolated public topology declaration seam, not an invented actual cross-page wire.
        ObjectNode v=crossPageTopology();assertDoesNotThrow(()->validateTopology(v));
        ObjectNode missing=v.deepCopy();((ArrayNode)missing.at("/chunks/0/relations")).removeAll();assertThrows(CanonicalEvidenceException.class,()->validateTopology(missing));
        ObjectNode partial=v.deepCopy();((ArrayNode)partial.at("/manifest/tableGroups/0/logicalRows/0/physicalRows")).remove(1);assertThrows(CanonicalEvidenceException.class,()->validateTopology(partial));
        ObjectNode repeated=v.deepCopy();ArrayNode rows=(ArrayNode)repeated.at("/manifest/tableGroups/0/logicalRows/0/physicalRows");rows.add(rows.get(0).deepCopy());assertThrows(CanonicalEvidenceException.class,()->validateTopology(repeated));
    }

    @Test void parsedDeclarationHashesKeepPythonBinary64NumberIdentity() {
        for(String raw:Arrays.asList("[0.0,-0.0,1e-07,1e+16,2,2.0]","{\"a\":0.094,\"b\":226.39999999999998}","[2,0,0,0,2,0]"))
            assertEquals(CanonicalJson.sha(raw.getBytes(StandardCharsets.UTF_8)),CanonicalJson.nodeHash(JsonUtils.parse(raw)));
        assertNotEquals(CanonicalJson.nodeHash(JsonUtils.parse("2")),CanonicalJson.nodeHash(JsonUtils.parse("2.0")));
    }

    @Test void pointerAndModelProjectionRetainSlAndTableContextWithoutRawGlyphProof() throws Exception {
        ObjectNode root=wire("table"),chunk=object(root,"/chunks/0");CanonicalSourceBinding binding=new CanonicalSourceBinding();
        // A synthetic binding exercises projection only; Long registration remains a separate actual fixture gate.
        binding.setProjectId("authored-pointer-projection");binding.setDocumentId(42L);binding.setOriginalSourceSha256(root.at("/source/sourceSha256").asText());binding.setCanonicalSourceHash(root.path("sourceHash").asText());binding.setProjectionSha256(root.path("projectionSha256").asText());binding.setSourceRevisionSha256(root.at("/source/sourceRevisionSha256").asText());binding.setProjectionRevisionSha256(root.path("projectionRevisionSha256").asText());
        Method method=CanonicalEvidenceService.class.getDeclaredMethod("pointer",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class,String.class);method.setAccessible(true);
        EvidencePointer pointer=(EvidencePointer)method.invoke(null,binding,root,chunk,"vetting");
        assertEquals(chunk.path("nativeTableGroupsRef"),pointer.getNativeTableGroupsRef());assertEquals(chunk.path("nativeTableSummary"),pointer.getNativeTableSummary());
        assertEquals(chunk.path("sourceEditionId").asText(),pointer.getSourceEditionId());assertEquals(chunk.path("sourceUnitId").asText(),pointer.getSourceUnitId());assertEquals(pointer,JsonUtils.read(JsonUtils.write(pointer),EvidencePointer.class));
        ObjectNode material=new OerModelMaterial.Request().project(pointer,chunk,"standard");
        assertEquals(chunk.path("nativeTableSummary"),material.path("nativeTableSummary"));assertEquals(chunk.path("sourceEditionId"),material.path("sourceEditionId"));assertEquals(chunk.at("/nativeTableGroupsRef/jsonPointer"),material.at("/nativeTableGroupsRef/jsonPointer"));assertEquals(chunk.at("/nativeTableGroupsRef/payloadSha256"),material.at("/nativeTableGroupsRef/payloadSha256"));
        assertFalse(JsonUtils.write(material).contains("nativeGlyphMembershipRef"));assertFalse(JsonUtils.write(material).contains("captureCompleteness"));assertFalse(material.at("/sourceDeclaration/limits/humanConfirmed").asBoolean(true));
    }

    @Test void ordinaryRecipeCannotDowngradeKnownSlClassByDroppingAliases() throws Exception {
        ObjectNode value=ordinary(wire("prose"),NativePdfCanonicalContract.INTERNAL_V2);
        for(JsonNode node:Arrays.asList(value.path("projectionIdentity"),value.path("manifest"),value.path("chunks").get(0)))((ObjectNode)node).put("sourceClass","specification_library");
        assertThrows(CanonicalEvidenceException.class,()->NativePdfCanonicalContract.validateEnvelope(value));
    }

    private static ObjectNode ordinary(ObjectNode value,String recipe) {
        // Declaration controls only: no rewritten CAS/public seal is claimed as actual producer output.
        ObjectNode identity=object(value,"/projectionIdentity"),manifest=object(value,"/manifest"),chunk=object(value,"/chunks/0");
        identity.put("internalProjectionVersion",recipe);chunk.put("internalProjectionVersion",recipe);
        for(ObjectNode node:Arrays.asList(identity,manifest,chunk))node.put("sourceClass","project_contract");
        for(ObjectNode node:Arrays.asList(identity,chunk)){node.remove(Arrays.asList("sourceEditionId","sourceUnitId","slUnitScopeManifestSha256","fileKey"));ObjectNode dependencies=object(node,"/projectionDependencySha256");if(NativePdfCanonicalContract.INTERNAL_VERSION.equals(recipe))dependencies.remove(Arrays.asList("pdf_selected_source.py","selected_source_raster.py"));if(!NativePdfCanonicalContract.INTERNAL_V3.equals(recipe))dependencies.remove("native_pdf_table_projection.py");}
        object(manifest,"/scope").remove("slTechnicalUnit");return value;
    }

    private static ObjectNode crossPageTopology() throws Exception {
        ObjectNode actual=wire("table"),v=JsonUtils.mapper().createObjectNode(),chunk=object(actual,"/chunks/0").deepCopy(),group=object(actual,"/manifest/tableGroups/0").deepCopy();
        v.putArray("assetRefs");v.putArray("chunks").add(chunk);v.putObject("manifest").putArray("tableGroups").add(group);((ArrayNode)chunk.path("assetRefs")).removeAll();
        String old=group.path("regionIds").get(0).asText(),first="AUTHORED-cross-page-first";ArrayNode ids=group.putArray("regionIds");ids.add(first).add(old);object(chunk,"/adoption").set("regionIds",ids.deepCopy());
        ObjectNode part=object(chunk,"/parts/0").deepCopy();part.put("referenceRegionId",first).put("physicalPageNo",3);((ArrayNode)chunk.path("parts")).insert(0,part);
        ObjectNode fragment=object(group,"/fragments/0").deepCopy();fragment.put("regionId",first);for(JsonNode cell:fragment.path("cells")){((ObjectNode)cell).put("cellId",cell.path("cellId").asText()+"-cross-page-first");ArrayNode glyphs=(ArrayNode)cell.path("glyphIds");for(int n=0;n<glyphs.size();n++)glyphs.set(n,JsonUtils.mapper().getNodeFactory().textNode(glyphs.get(n).asText().replace("pdf-page/4/","pdf-page/3/")));}((ArrayNode)group.path("fragments")).insert(0,fragment);
        ArrayNode physical=object(group,"/logicalRows/0").putArray("physicalRows");physical.addObject().put("regionId",first).put("row",0);physical.addObject().put("regionId",old).put("row",0);
        chunk.putArray("physicalPages").add(3).add(4);object(chunk,"/nativeTableSummary/0").putArray("physicalPages").add(3).add(4);
        chunk.putArray("relations").addObject().put("type","continuation").put("fromRegionId",first).put("toRegionId",old);return v;
    }

    private static void validateTopology(ObjectNode value) throws Exception {Method method=NativePdfCanonicalContract.class.getDeclaredMethod("tableManifest",JsonNode.class);method.setAccessible(true);try{method.invoke(null,value);}catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException)throw (RuntimeException)error.getCause();throw error;}}
    private static ObjectNode layout(ObjectNode value){return object(value,"/chunks/0/parts/0/visualLayoutParts/0");}
    private static ObjectNode object(JsonNode value,String pointer){return (ObjectNode)value.at(pointer);}
    private static void rejected(String kind,Consumer<ObjectNode> mutation)throws Exception {ObjectNode value=wire(kind);assertDoesNotThrow(()->NativePdfCanonicalContract.validateEnvelope(value));mutation.accept(value);assertThrows(CanonicalEvidenceException.class,()->NativePdfCanonicalContract.validateEnvelope(value));}
    private static ObjectNode wire(String kind)throws Exception {String name=kind+"-public-wire.json";try(InputStream input=NativePdfCompleteStructureContractTest.class.getResourceAsStream("/oer/native-pdf-actual-sl/"+name)){assertNotNull(input);byte[] bytes=input.readAllBytes();assertEquals("prose".equals(kind)?"d308ea81fc02b2325505e63af6b4b8d7c556915239985bdd13f6b5b957693e18":"60a522a02d3480077921e48ec3e553a25ebbe077c7f4ef7dfd426d868dbac6df",CanonicalJson.sha(bytes));return (ObjectNode)JsonUtils.parse(new String(bytes,StandardCharsets.UTF_8));}}
}
