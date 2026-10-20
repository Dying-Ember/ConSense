package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class VettingModelPresentationTest {
    private static Map<String,Object> excerpt(String id,JsonNode structure) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("content","Exact source "+id);
        row.put("anchor","native/"+id);row.put("clauseId",null);row.put("canonicalStructure",structure);return row;
    }
    private static ObjectNode structure(String source,JsonNode order) {
        ObjectNode node=JsonUtils.mapper().createObjectNode();node.put("sourceDeclarationRef","model-source:"+VettingCorpus.hash(source));
        if(order!=null)node.set("sourceOrder",order);return node;
    }
    private static Map<String,Object> row(String id,String source,String order) {
        return excerpt(id,structure(source,order==null?null:JsonUtils.parse(order)));
    }
    @Test void scrambledRankUsesExactSourceOrderWithoutChangingRecordsOrSelection() {
        Map<String,Object> seven=row("seven","one","7"),three=row("three","one","3"),five=row("five","one","5");
        List<Map<String,Object>> selected=Arrays.asList(seven,three,five);String original=JsonUtils.write(selected);
        List<Map<String,Object>> shown=VettingSemanticReview.modelPresentation(selected);
        assertSame(three,shown.get(0));assertSame(five,shown.get(1));assertSame(seven,shown.get(2));
        assertEquals(original,JsonUtils.write(selected));assertEquals(3,shown.size());
        for(Map<String,Object> record:shown){assertTrue(selected.stream().anyMatch(s->s==record));assertNull(record.get("clauseId"));}
        assertEquals(JsonUtils.mapper().valueToTree(shown),VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(shown)));
    }
    @Test void missingNullNegativeTextDecimalAndDuplicateOrderPreserveWholeSourceGroup() {
        for(String unknown:Arrays.asList(null,"null","-1","\"1\"","1.0","9")) {
            List<Map<String,Object>> selected=Arrays.asList(row("nine","one","9"),row("unknown","one",unknown),row("one","one","1"));
            List<Map<String,Object>> shown=VettingSemanticReview.modelPresentation(selected);
            for(int i=0;i<selected.size();i++)assertSame(selected.get(i),shown.get(i),"Unknown or duplicate order must not guess source order: "+unknown);
        }
    }
    @Test void crossSourceInterleavingAndOrdinaryOrFixedRecordsKeepTheirOwnSlots() {
        Map<String,Object> a9=row("a9","a","9"),b8=row("b8","b","8"),a1=row("a1","a","1"),b2=row("b2","b","2");
        Map<String,Object> ordinary=new LinkedHashMap<>();ordinary.put("id","ordinary");ordinary.put("sourceOrder",0);
        Map<String,Object> fixed=Collections.singletonMap("id","fixed");
        List<Map<String,Object>> shown=VettingSemanticReview.modelPresentation(Arrays.asList(a9,b8,ordinary,a1,fixed,b2));
        assertEquals(Arrays.asList(a1,b2,ordinary,a9,fixed,b8),shown);
        assertSame(ordinary,shown.get(2));assertSame(fixed,shown.get(4));
    }
    @Test void sourceIdentityChangesFromActualProducerNeverShareOrderSlots() throws Exception {
        EvidencePointer base=new OerBusinessEvidenceTest.Wire().pointers().get(0);
        List<Consumer<EvidencePointer>> changes=Arrays.asList(
                p->p.setProjectId("other-project"),p->p.setDocumentId(999L),p->p.setModule("advisory"),
                p->p.setOriginalSourceSha256(VettingCorpus.hash("other raw")),p->p.setSourceRevisionSha256(VettingCorpus.hash("other revision")),
                p->p.setCanonicalSourceHash(VettingCorpus.hash("other canonical")),p->p.setProjectionSha256(VettingCorpus.hash("other projection")),
                p->p.setProjectionRevisionSha256(VettingCorpus.hash("other projection revision")),p->p.setProjectionSchema("other-schema"),
                p->p.setProducerKind("other-producer"));
        for(Consumer<EvidencePointer> change:changes) {
            EvidencePointer other=JsonUtils.read(JsonUtils.write(base),EvidencePointer.class);change.accept(other);
            assertSeparate(base,"tender",other,"tender");
        }
        assertSeparate(base,"tender",base,"reference");
    }
    private static void assertSeparate(EvidencePointer first,String firstRole,EvidencePointer other,String otherRole) {
        ObjectNode a=new OerModelMaterial.Request().project(first,null,firstRole),b=new OerModelMaterial.Request().project(other,null,otherRole);
        assertNotEquals(a.path("sourceDeclarationRef"),b.path("sourceDeclarationRef"));a.put("sourceOrder",9);b.put("sourceOrder",1);
        Map<String,Object> aRow=excerpt("a",a),bRow=excerpt("b",b);
        List<Map<String,Object>> shown=VettingSemanticReview.modelPresentation(Arrays.asList(aRow,bRow));
        assertSame(aRow,shown.get(0));assertSame(bRow,shown.get(1));
    }
}
