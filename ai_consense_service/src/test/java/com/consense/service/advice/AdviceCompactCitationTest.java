package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Saved citations retain the attribution/member contract without copying source quality/context trees. */
class AdviceCompactCitationTest {
    @Test void compactCitationPreservesExactMemberPartsAndFormalClauseOwnership() {
        String sha=String.join("",Collections.nCopies(64,"a"));
        ObjectNode unit=(ObjectNode)JsonUtils.parse("{\"unitId\":\"unit\",\"status\":\"complete_observed_unit\",\"kind\":\"exact_clause_heading\",\"requiredMemberIds\":[\"member\"],\"qualifiersComplete\":\"unknown\",\"semanticScopeVerified\":false}");
        ObjectNode member=unit.putArray("members").addObject();member.put("id","member");member.put("unitId","unit");member.put("documentId","7");member.put("role","tender");member.put("sourceHash",sha);member.put("sourceRevisionSha256",sha);member.put("clauseId","4.1");member.put("clauseHeadingLocation","paragraph:9");
        member.putArray("parts").addObject().put("blockId","paragraph:10").put("text","Authored complete source wording");
        member.putObject("sourceQuality").put("unusedLargeAudit",String.join("",Collections.nCopies(1000,"AUDIT")));unit.putObject("hierarchyContext").set("members",unit.path("members").deepCopy());
        JsonNode before=unit.deepCopy(),compact=AdviceService.citationUnit(unit);assertEquals(before,unit);assertFalse(compact.has("hierarchyContext"));assertFalse(compact.path("members").get(0).has("sourceQuality"));
        assertEquals(member.path("parts"),compact.path("members").get(0).path("parts"));assertTrue(JsonUtils.write(compact).length()<JsonUtils.write(unit).length()/3);
        EvidencePointer pointer=new EvidencePointer();pointer.setDocumentId(7L);pointer.setJavaParsedSourceHash(sha);pointer.setOriginalSourceSha256(sha);pointer.setCanonicalSourceHash(sha);pointer.setSourceRevisionSha256(sha);pointer.setCanonicalChunkId("member");pointer.setUnitId("unit");pointer.setParts(member.path("parts").deepCopy());
        CitationVO source=new CitationVO("Authored document","P1","paragraph:10","Authored complete source wording",1d,"[1]","7",sha,"tender",null);source.setCanonicalEvidence(Collections.singletonList(pointer));source.setCanonicalSourceUnit(compact);
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(AdvicePrecedenceOwnership.class,"boundPointer",source,pointer));
        assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(AdviceClauseAttribution.class,"declared",source,"4.1"));
        ((ObjectNode)compact.path("members").get(0).path("parts").get(0)).put("text","Authored changed source");
        assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(AdvicePrecedenceOwnership.class,"boundPointer",source,pointer));
    }
}
