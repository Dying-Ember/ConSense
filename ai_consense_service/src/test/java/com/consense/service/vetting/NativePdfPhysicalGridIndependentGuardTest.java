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

/** Independent negative cases use existing source fixtures and ordinary consumption. */
class NativePdfPhysicalGridIndependentGuardTest {
    @Test void deletingTheSameCellFromBothViewsCannotClaimACompleteGrid(){
        Fixture f=new Fixture();JsonNode coverage=f.physical.path("coverage").deepCopy();
        ((ArrayNode)f.table.path("origins")).remove(3);((ArrayNode)f.physical.path("cells")).remove(3);
        assertEquals(coverage,f.physical.path("coverage"));f.assertNoFragmentExpansion("One complete seed row cannot certify a 2x2 grid with only three cells");
    }
    @Test void duplicatedPhysicalGlyphIdsCannotBorrowUnchangedCompleteCoverage(){
        for(boolean withinCell:Arrays.asList(false,true)){
            Fixture f=new Fixture();ArrayNode logical=(ArrayNode)f.table.path("origins").get(2).path("glyphIds"),physical=(ArrayNode)f.physical.path("cells").get(2).path("glyphIds");
            if(withinCell){logical.add(2);physical.add(2);}else{logical.removeAll().add(0);physical.removeAll().add(0);}
            f.assertNoFragmentExpansion("Repeated glyph ownership is incomplete even when both table views agree: "+withinCell);
        }
    }
    @Test void anUnownedRequiredGlyphPreventsWholeGridFragmentExpansion(){
        Fixture f=new Fixture();((ArrayNode)f.table.path("origins").get(2).path("glyphIds")).removeAll();((ArrayNode)f.physical.path("cells").get(2).path("glyphIds")).removeAll();
        f.assertNoFragmentExpansion("Declared required glyph 2 has no original cell owner");
    }
    @Test void completeRowEqualityCannotBypassAnIncompletePhysicalGrid(){
        Fixture f=new Fixture();DocumentBlock row=f.blocks.stream().filter(b->"row".equals(b.getId())).findFirst().orElseThrow();String wholeRow="Item FIELD VALUE ending Note (1)";row.setText(wholeRow);row.setOriginalText(wholeRow);row.getPdfGlyphs().get(0).setUnicode(wholeRow);
        ((ArrayNode)f.table.path("origins")).remove(3);((ArrayNode)f.physical.path("cells")).remove(3);f.assertNoFragmentExpansion("Exact row text does not repair a missing original physical cell");
    }
    static final class Fixture {
        final SourceDocument source=PdfSourceGroupConsumptionTest.source(false);final List<DocumentBlock> blocks=VettingCorpus.blocks(source);final ObjectNode raw,table,physical;
        Fixture(){
            DocumentBlock row=blocks.stream().filter(b->"row".equals(b.getId())).findFirst().orElseThrow();row.setText("FIELD VALUE");row.setOriginalText("FIELD VALUE");row.setBbox(new double[]{.2,.2,.3,.03});row.getPdfGlyphs().get(0).setUnicode("FIELD VALUE");row.getPdfGlyphs().get(0).setBbox(row.getBbox());row.getPdfGlyphs().get(0).setCoordinateSpace("normalized_rendered_crop_top_left");
            raw=(ObjectNode)blocks.stream().filter(b->b.getNativeTableCandidate()!=null).findFirst().orElseThrow().getNativeTableCandidate();table=(ObjectNode)raw.path("candidate").path("logicalTables").get(0);physical=(ObjectNode)raw.path("candidate").path("tables").get(0);
            table.put("textTransform","none_original_physical_cell_literals");((ObjectNode)table.path("origins").get(0)).put("text","Item\nFIELD VALUE\nending");ArrayNode cells=physical.putArray("cells");int n=0;
            for(JsonNode value:table.path("origins")){ObjectNode cell=(ObjectNode)value;String id="authored-physical-cell-"+n;cell.putArray("physicalCellIds").add(id);cell.putArray("glyphIds").add(n);ObjectNode original=cell.deepCopy();original.remove("physicalCellIds");original.put("physicalCellId",id);cells.add(original);n++;}
            physical.putArray("issues");ObjectNode coverage=physical.putObject("coverage");for(String name:Arrays.asList("required","present")){ArrayNode ids=coverage.putArray(name);for(int i=0;i<n;i++)ids.add(i);}coverage.putArray("missing");coverage.putArray("duplicate");
        }
        void assertNoFragmentExpansion(String why){
            source.setTextContent(String.join("\n",blocks.stream().filter(b->b.getNativeTableCandidate()==null).map(DocumentBlock::getEffectiveText).toArray(String[]::new)));source.setStructuredContentJson(JsonUtils.write(blocks));NormalSourceCandidateGraph.capture(source);
            VettingCorpus.Chunk hit=PdfSourceGroupConsumptionTest.hit(source,"row");List<JsonNode> groups=new ArrayList<>();for(JsonNode group:hit.getCandidateStructure().path("nativePdfSourceGroups").path("groups"))if("native_table_method_group".equals(group.path("kind").asText()))groups.add(group);
            assertTrue(groups.isEmpty(),why);assertTrue(hit.getContent().contains("FIELD VALUE"),"The ordinary native quote remains readable");
        }
    }
}
