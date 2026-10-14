package com.consense.web;

import com.consense.domain.SourceDocument;
import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.ProjectDocumentScope;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Additional source-boundary cases, using the original real MVC/JPA fixture. */
class IndependentFileCatalogBoundaryTest extends VettingFileCatalogJpaHttpTest {
    @Test void newerPricingSupplementSuppressesEarlierEligibleSameNamedContract() throws Exception {
        SourceDocument old=source("10 - SCC.docx", "SCC", "Old eligible contractual source must not return.");
        SourceDocument newer=source(" 10 - scc.DOCX ", "SCC", "New pricing upload of same named source.");
        newer.setCategory(SourceDocument.CATEGORY_VETTING_SUPPLEMENT);
        newer.setDocumentScope("pricing");
        newer.setReviewRole("tender");
        documents.saveAndFlush(newer);
        SourceDocument gcc=source("GCC.pdf", "GCC", "Another complete current source.");
        clear();
        JsonNode rows=data();
        assertEquals(2, rows.size());
        assertEquals(newer.getFileName(),rows.get(0).path("fileName").asText());
        assertFalse(rows.get(0).path("parsed").asBoolean());
        assertEquals(newer.getTextContent().length(),rows.get(0).path("textChars").asInt());
        assertEquals("GCC",rows.get(1).path("key").asText());assertTrue(rows.get(1).path("parsed").asBoolean());
        assertNoArchiveHydration();
        List<SourceDocument> selected=ProjectDocumentScope.selected(project,documents.findBusinessSourcesByProjectId(project),"fullset");
        assertEquals(1,selected.size());assertEquals(gcc.getId(),selected.get(0).getId());
        assertNotEquals(old.getId(),selected.get(0).getId());assertNotEquals(newer.getId(),selected.get(0).getId());
    }

    @Test void excludedIdentityPurposesRemainExcludedDespiteExplicitTenderAndEligibleList() throws Exception {
        SourceDocument template=source("10 - SCC.docx", "SCC", "Template body cannot become a review source.");
        template.setReviewRole("tender");
        template.setDocumentScope("package");
        OerDocumentIdentity templateIdentity=OerSourceMetadata.identity(template);templateIdentity.setSourcePurpose("standard_template");
        templateIdentity.setEligibleModules(Arrays.asList("vetting","advisory"));template.setOerIdentityJson(JsonUtils.write(templateIdentity));
        documents.saveAndFlush(template);
        SourceDocument answer=source("03 - NTT.docx", "NTT", "Answer body cannot become a review source.");
        answer.setReviewRole("tender");
        answer.setDocumentScope("package");
        OerDocumentIdentity answerIdentity=OerSourceMetadata.identity(answer);answerIdentity.setSourcePurpose("evaluation_answer");
        answerIdentity.setEligibleModules(Arrays.asList("vetting","advisory"));answer.setOerIdentityJson(JsonUtils.write(answerIdentity));
        documents.saveAndFlush(answer);
        SourceDocument gcc=source("GCC.pdf", "GCC", "Remaining eligible source.");
        clear();
        JsonNode rows=data();
        assertEquals(3, rows.size());
        Map<String,JsonNode> byKey=new HashMap<>();for(JsonNode row:rows)byKey.put(row.path("key").asText(),row);
        assertFalse(byKey.get("SCC").path("parsed").asBoolean());assertFalse(byKey.get("NTT").path("parsed").asBoolean());
        assertTrue(byKey.get("GCC").path("parsed").asBoolean());
        assertNoArchiveHydration();
        List<SourceDocument> selected=ProjectDocumentScope.selected(project,documents.findBusinessSourcesByProjectId(project),"fullset");
        assertEquals(1,selected.size());assertEquals(gcc.getId(),selected.get(0).getId());
        assertEquals("drafting_standard_template",ProjectDocumentScope.exclusionReason(template,"fullset"));
        assertEquals("evaluation_answer_isolated",ProjectDocumentScope.exclusionReason(answer,"fullset"));
    }
}
