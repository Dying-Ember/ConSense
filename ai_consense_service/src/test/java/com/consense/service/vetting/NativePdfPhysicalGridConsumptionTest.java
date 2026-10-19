package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored complete original-grid fragments; no original-page or model quality claim. */
class NativePdfPhysicalGridConsumptionTest {
    @Test void uniqueWrappedCellLineKeepsTheWholeOriginalTableAsUnknownContext(){
        SourceDocument source=source();VettingCorpus.Chunk seed=PdfSourceGroupConsumptionTest.hit(source,"row");String before=JsonUtils.write(seed);
        JsonNode group=onlyMethod(seed);assertEquals(4,group.path("methodTable").path("origins").size());assertEquals("Item\nFIELD VALUE\nending",group.path("methodTable").path("origins").get(0).path("text").asText());
        JsonNode proof=group.path("alignments").get(0).path("nativeCellFragmentProof");assertEquals(1,proof.size());assertEquals("unique_contiguous_cell_literal",proof.get(0).path("basis").asText());assertEquals("FIELD VALUE",proof.get(0).path("nativeLiteral").asText());assertFalse(group.path("semanticScopeVerified").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());assertEquals("unknown",group.path("legalClosure").asText());
        JsonNode model=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(seed);assertTrue(JsonUtils.write(model).contains("FIELD VALUE"));assertEquals(before,JsonUtils.write(seed));
    }
    @Test void completeIndependentCellIsAValidSeedWithoutSplittingItsText(){
        SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);ObjectNode raw=raw(blocks);ObjectNode table=(ObjectNode)raw.path("candidate").path("logicalTables").get(0);((ObjectNode)table.path("origins").get(0)).put("text","FIELD VALUE");((ObjectNode)raw.path("candidate").path("tables").get(0).path("cells").get(0)).put("text","FIELD VALUE");refresh(source,blocks);
        JsonNode proof=onlyMethod(PdfSourceGroupConsumptionTest.hit(source,"row")).path("alignments").get(0).path("nativeCellFragmentProof");assertEquals("complete_cell_literal",proof.get(0).path("basis").asText());
    }
    @Test void spatialOverlapRepeatedPartialWordsForeignOrIncompleteCellsCannotExpand(){
        for(int kind=0;kind<8;kind++){
            SourceDocument source=source();List<DocumentBlock> blocks=VettingCorpus.blocks(source);ObjectNode raw=raw(blocks),table=(ObjectNode)raw.path("candidate").path("logicalTables").get(0),physical=(ObjectNode)raw.path("candidate").path("tables").get(0);DocumentBlock seed=blocks.stream().filter(b->"row".equals(b.getId())).findFirst().orElseThrow();
            if(kind==0){((ObjectNode)table.path("origins").get(0)).put("text","Item FIELD VALUES ending");((ObjectNode)physical.path("cells").get(0)).put("text","Item FIELD VALUES ending");}
            if(kind==1){((ObjectNode)table.path("origins").get(0)).put("text","FIELD VALUE then FIELD VALUE");((ObjectNode)physical.path("cells").get(0)).put("text","FIELD VALUE then FIELD VALUE");}
            if(kind==2)((ObjectNode)physical.path("cells").get(0)).put("text","Different original cell");
            if(kind==3){seed.setBbox(new double[]{.2,.2,.5,.03});seed.getPdfGlyphs().get(0).setBbox(seed.getBbox());}
            if(kind==4)((ArrayNode)physical.path("coverage").path("missing")).add(99);
            if(kind==5)table.remove("textTransform");
            if(kind==6){seed.setText("UNRELATED");seed.setOriginalText("UNRELATED");seed.getPdfGlyphs().get(0).setUnicode("UNRELATED");}
            if(kind==7)raw.put("sourceSha256",VettingCorpus.hash("foreign source"));
            refresh(source,blocks);VettingCorpus.Chunk hit=PdfSourceGroupConsumptionTest.hit(source,"row");assertTrue(methods(hit).isEmpty(),"Invalid or solely spatial cell match must stay unknown: "+kind);
        }
    }
    private static SourceDocument source(){
        SourceDocument source=PdfSourceGroupConsumptionTest.source(false);List<DocumentBlock> blocks=VettingCorpus.blocks(source);DocumentBlock row=blocks.stream().filter(b->"row".equals(b.getId())).findFirst().orElseThrow();row.setText("FIELD VALUE");row.setOriginalText("FIELD VALUE");row.setBbox(new double[]{.2,.2,.3,.03});row.getPdfGlyphs().get(0).setUnicode("FIELD VALUE");row.getPdfGlyphs().get(0).setBbox(row.getBbox());row.getPdfGlyphs().get(0).setCoordinateSpace("normalized_rendered_crop_top_left");
        ObjectNode raw=raw(blocks),table=(ObjectNode)raw.path("candidate").path("logicalTables").get(0),physical=(ObjectNode)raw.path("candidate").path("tables").get(0);table.put("textTransform","none_original_physical_cell_literals");((ObjectNode)table.path("origins").get(0)).put("text","Item\nFIELD VALUE\nending");ArrayNode cells=physical.putArray("cells");int n=0;
        for(JsonNode value:table.path("origins")){ObjectNode cell=(ObjectNode)value;String id="authored-physical-cell-"+n;cell.putArray("physicalCellIds").add(id);cell.putArray("glyphIds").add(n);ObjectNode original=cell.deepCopy();original.remove("physicalCellIds");original.put("physicalCellId",id);cells.add(original);n++;}
        physical.putArray("issues");ObjectNode coverage=physical.putObject("coverage");for(String name:Arrays.asList("required","present")){ArrayNode ids=coverage.putArray(name);for(int i=0;i<n;i++)ids.add(i);}coverage.putArray("missing");coverage.putArray("duplicate");refresh(source,blocks);return source;
    }
    private static void refresh(SourceDocument source,List<DocumentBlock> blocks){source.setTextContent(String.join("\n",blocks.stream().filter(b->b.getNativeTableCandidate()==null).map(DocumentBlock::getEffectiveText).toArray(String[]::new)));source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);}
    private static ObjectNode raw(List<DocumentBlock> blocks){return (ObjectNode)blocks.stream().filter(b->b.getNativeTableCandidate()!=null).findFirst().orElseThrow().getNativeTableCandidate();}
    private static List<JsonNode> methods(VettingCorpus.Chunk seed){List<JsonNode> out=new ArrayList<>();for(JsonNode group:seed.getCandidateStructure().path("nativePdfSourceGroups").path("groups"))if("native_table_method_group".equals(group.path("kind").asText()))out.add(group);return out;}
    private static JsonNode onlyMethod(VettingCorpus.Chunk seed){List<JsonNode> values=methods(seed);assertEquals(1,values.size());return values.get(0);}
}
