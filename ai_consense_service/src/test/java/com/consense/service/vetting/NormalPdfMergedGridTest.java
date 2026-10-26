package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored geometry controls; these do not establish source or semantic quality. */
class NormalPdfMergedGridTest {
    static ObjectNode table(){
        ObjectNode table=JsonUtils.mapper().createObjectNode();table.put("rowCount",3).put("colCount",3).put("textTransform","none_original_physical_cell_literals");table.set("bbox",JsonUtils.parse("[0,0,30,30]"));ArrayNode cells=table.putArray("origins");
        int[][] positions={{0,0,1,3},{1,0,2,1},{1,1,1,1},{1,2,1,1},{2,1,1,2}};
        for(int i=0;i<positions.length;i++){int[] p=positions[i];ObjectNode cell=cells.addObject();cell.put("row",p[0]).put("col",p[1]).put("rowSpan",p[2]).put("colSpan",p[3]).put("spanKnown",true).put("text",i==0?"Merged acceptance header":"Original cell "+i).put("physicalCellId","cell"+i);cell.putArray("physicalCellIds").add("cell"+i);cell.putArray("glyphIds").add(i);cell.set("bbox",JsonUtils.mapper().valueToTree(new int[]{p[1]*10,p[0]*10,(p[1]+p[3])*10,(p[0]+p[2])*10}));}
        return table;
    }
    static ObjectNode physical(JsonNode table){
        ObjectNode physical=JsonUtils.mapper().createObjectNode();physical.set("cells",table.path("origins").deepCopy());physical.putArray("issues");ObjectNode coverage=physical.putObject("coverage");coverage.set("required",JsonUtils.parse("[0,1,2,3,4]"));coverage.set("present",JsonUtils.parse("[0,1,2,3,4]"));coverage.putArray("missing");coverage.putArray("duplicate");return physical;
    }
    @Test void mergedOriginsTileOnceWithoutExpandingOrRepeatingTheirLiterals(){
        ObjectNode table=table(),physical=physical(table);String before=table.toString();assertTrue(NormalPdfMethodGroups.wholePhysicalGrid(table,physical));
        ObjectNode inventory=JsonUtils.mapper().createObjectNode(),group=inventory.putArray("groups").addObject();group.put("kind","native_table_method_group");group.putObject("methodBinding").put("physicalPageNo",1).put("method","pdfplumber_lines_lines");group.set("methodTable",table);group.set("methodPhysicalTable",physical);
        List<Map<String,Object>> readings=NormalPdfReadingProjection.tableReadings(inventory,1);assertEquals(1,readings.size());assertEquals("unknown",readings.get(0).get("continuationStatus"));assertEquals(5,((List<?>)readings.get(0).get("cells")).size());assertEquals(before,table.toString());assertTrue(NormalPdfReadingProjection.tableReadings(inventory,2).isEmpty());
        Map<?,?> header=(Map<?,?>)((List<?>)readings.get(0).get("cells")).get(0);assertEquals(3,header.get("colSpan"));assertEquals("Merged acceptance header",header.get("text"));assertFalse(header.containsKey("glyphIds"));
    }
    @Test void overlapsHolesBadSpansGeometryAndGlyphCoverageCannotBecomeReadableGrids(){
        for(int mode=0;mode<9;mode++){
            ObjectNode table=table();ArrayNode cells=(ArrayNode)table.path("origins");ObjectNode target=(ObjectNode)cells.get(4);
            if(mode==0)target.put("colSpan",1);if(mode==1)target.put("col",0);if(mode==2)target.put("rowSpan",0);if(mode==3)target.put("rowSpan",2);if(mode==4)target.set("bbox",JsonUtils.parse("[10,20,29,30]"));if(mode==5)target.put("spanKnown",false);if(mode==6)target.put("rowSpan",1.5);if(mode==7)target.set("glyphIds",JsonUtils.parse("[1]"));
            ObjectNode physical=physical(table);if(mode==8)((ArrayNode)physical.path("coverage").path("missing")).add(4);
            assertFalse(NormalPdfMethodGroups.wholePhysicalGrid(table,physical),"mode "+mode);
        }
    }
}
