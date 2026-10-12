package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored source observations; verifies model input preservation and page assignment. */
class CanonicalPageExcerptTest {
    @Test void actualAdvicePromptAssociatesEveryCompleteLiteralWithItsPage() throws Exception {
        EvidencePointer a=pointer("a",7,"HEAD-OF-ENTRY"),b=pointer("b",9,"END-OF-ENTRY");
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileLabel","Authored PDF");payload.put("documentId","123");
        payload.put("content","HEAD-OF-ENTRY\nEND-OF-ENTRY");payload.put("canonicalEvidence",Arrays.asList(a,b));
        payload.put("canonicalSourceUnit",JsonUtils.parse("{\"status\":\"complete_observed_unit\",\"qualifiersComplete\":\"unknown\"}"));
        AdviceService advice=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        Method method=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class);method.setAccessible(true);
        String out=(String)method.invoke(advice,Collections.singletonList(new com.consense.vector.VectorStore.SearchHit("authored",0.5,payload)));
        assertTrue(out.contains("physicalPage=7 | blockId=a-block\nHEAD-OF-ENTRY"));
        assertTrue(out.contains("physicalPage=9 | blockId=b-block\nEND-OF-ENTRY"));
    }
    private String render(String literal,List<EvidencePointer> pointers) throws Exception {
        Class<?> type=Class.forName("com.consense.service.advice.CanonicalPageExcerpt");
        Method method=type.getDeclaredMethod("render",String.class,List.class);method.setAccessible(true);
        return (String)method.invoke(null,literal,pointers);
    }
    private EvidencePointer pointer(String id,int page,String text) {
        EvidencePointer p=new EvidencePointer();p.setUnitId(id);p.setPhysicalPages(Collections.singletonList(page));
        ObjectNode part=JsonUtils.mapper().createObjectNode();part.put("blockId",id+"-block");part.put("physicalPageNo",page);part.put("text",text);
        ObjectNode cell=part.putArray("cells").addObject();cell.put("cellId",id+"-cell");cell.put("row",2);cell.put("column",1);cell.put("text",text);
        p.setUnknowns(JsonUtils.mapper().createArrayNode());p.setSourceSpans(JsonUtils.mapper().createArrayNode());p.setParts(JsonUtils.mapper().createArrayNode().add(part));p.setRelations(JsonUtils.mapper().createArrayNode());return p;
    }
    @Test void preservesLiteralPartsWithTheirActualPagesAndWhitespace() throws Exception {
        EvidencePointer a=pointer("first",42,"  Alpha\n<table><tr><td>😀</td></tr></table>  ");
        EvidencePointer b=pointer("second",1055,"\tBeta continuation\n");
        String original=a.getParts().get(0).path("text").asText()+"\n"+b.getParts().get(0).path("text").asText();
        String out=render(original,Arrays.asList(a,b));assertNotNull(out);
        assertTrue(out.contains("physicalPage=42 | blockId=first-block\n"+a.getParts().get(0).path("text").asText()));
        assertTrue(out.contains("physicalPage=1055 | blockId=second-block\n"+b.getParts().get(0).path("text").asText()));
        assertTrue(out.indexOf("first-block")<out.indexOf("second-block"));
        assertEquals(1,out.split("Alpha",-1).length-1);assertFalse(out.contains("whole-source accepted"));
    }
    @Test void preservesDeclaredContinuationWithCellEndpointsAndStatus() throws Exception {
        EvidencePointer a=pointer("a",7,"Beginning"),b=pointer("b",9,"Continued");
        ObjectNode relation=JsonUtils.mapper().createObjectNode();relation.put("type","continuation");relation.put("status","explicit_source_reviewed");
        relation.put("fromBlockId","a-block");relation.put("fromCellId","a-cell");relation.put("toBlockId","b-block");relation.put("toCellId","b-cell");
        a.setRelations(JsonUtils.mapper().createArrayNode().add(relation));
        String out=render("Beginning\nContinued",Arrays.asList(a,b));assertTrue(out.contains("type=continuation | status=explicit_source_reviewed"));
        assertTrue(out.contains("from physicalPage=7 row=2 column=1"));assertTrue(out.contains("to physicalPage=9 row=2 column=1"));
        assertTrue(out.contains("applicability remain unknown"));
    }
    @Test void cannotDropAdditionalLiteralContextOrNormalizeWhitespace() throws Exception {
        EvidencePointer a=pointer("a",1,"  complete text  ");
        assertNull(render("preface\n  complete text  ",Collections.singletonList(a)));
        assertNull(render("complete text",Collections.singletonList(a)));
    }
    @Test void invalidOrMissingObservedPagesCannotAcquireAGuessedPosition() throws Exception {
        EvidencePointer a=pointer("a",5,"text");((ObjectNode)a.getParts().get(0)).put("physicalPageNo",0);
        assertNull(render("text",Collections.singletonList(a)));
        ((ObjectNode)a.getParts().get(0)).put("physicalPageNo",6);assertNull(render("text",Collections.singletonList(a)));
        ((ObjectNode)a.getParts().get(0)).remove("physicalPageNo");assertNull(render("text",Collections.singletonList(a)));
    }
    @Test void nativeAndLegacyViewsKeepTheirExistingTextChannels() throws Exception {
        EvidencePointer p=pointer("a",1,"text");p.setProducerKind("native_docx");assertNull(render("text",Collections.singletonList(p)));
        p.setProducerKind("native_pdf");assertNull(render("text",Collections.singletonList(p)));
        assertNull(render("legacy literal",Collections.emptyList()));
    }
    @Test void unresolvedRelationDoesNotInventPageOrCellEndpoints() throws Exception {
        EvidencePointer p=pointer("a",1,"text");ObjectNode r=JsonUtils.mapper().createObjectNode();r.put("type","continuation");r.put("status","unknown");
        r.put("fromBlockId","a-block");r.put("fromCellId","a-cell");r.put("toBlockId","missing");r.put("toCellId","missing-cell");p.setRelations(JsonUtils.mapper().createArrayNode().add(r));
        String out=render("text",Collections.singletonList(p));assertTrue(out.contains("status=unknown"));assertTrue(out.contains("to unresolved endpoint"));
    }
    @Test void mismatchedUnitOrAmbiguousCellCannotAcquireAPageLocation() throws Exception {
        EvidencePointer p=pointer("a",1,"text");ObjectNode r=JsonUtils.mapper().createObjectNode();r.put("type","continuation");r.put("status","explicit_source_reviewed");
        r.put("fromUnitId","wrong-owner");r.put("fromBlockId","a-block");r.put("fromCellId","a-cell");r.put("toUnitId","a");r.put("toBlockId","a-block");r.put("toCellId","a-cell");
        p.setRelations(JsonUtils.mapper().createArrayNode().add(r));assertTrue(render("text",Collections.singletonList(p)).contains("from unresolved endpoint"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)p.getParts().get(0).path("cells")).add(p.getParts().get(0).path("cells").get(0).deepCopy());
        assertTrue(render("text",Collections.singletonList(p)).contains("to unresolved endpoint"));
    }
}
