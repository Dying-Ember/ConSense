package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor.TemplateIndex;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Read-only sidecar metadata contracts; mocked hashes do not establish original-file acceptance. */
class DraftDocumentBindingsMixedTest {
    @Test void mixedPlanRetainsNativeLinksAndProjectsSpecialFixedAssociationInputs() {
        String sha=DraftFormattedRules.verifiedSourceHash("SCT");TemplateIndex source=source(sha);
        Map<String,Object> fixed=obj("id","sct-domestic-submission-liability","executionKind","fixed_catalog","document","SCT","action","retain","paragraphs","P898","catalogInputKeys",Arrays.asList("domesticBlocks","precastFacadePermission"),"inputKeys",Arrays.asList("fixed-domesticBlocks","fixed-precastFacadePermission"));
        Map<String,Object> nativeAction=nativeAction(sha,"SCT","p895");List<Map<String,Object>> rows=Arrays.asList(row(895,"p895"),row(898,"p898"),row(899,"p899"));
        DraftDocumentBindings.associateTargets("SCT",source,plan(fixed,nativeAction),rows);
        assertEquals(new HashSet<>(Arrays.asList("fixed-domesticBlocks","custom-variable")),new HashSet<>(list(rows.get(0).get("fieldKeys"))));
        assertTrue(list(rows.get(0).get("actionIds")).containsAll(Arrays.asList("sct-domestic-submission-liability","native-action")));
        assertEquals(Collections.singletonList("fixed-precastFacadePermission"),rows.get(2).get("fieldKeys"));
        assertFalse(fixed.containsKey("nativeBinding"));assertFalse(rows.stream().anyMatch(row->row.containsKey("nativeBinding")));assertFalse(rows.stream().anyMatch(row->row.containsKey("editable")));
    }

    @Test void fixedNegativeAssociationIncludesItsAuditedBodyAndTocButDoesNotGrantAnEdit() {
        Map<String,Object> fixed=obj("id","scc-excision","executionKind","fixed_catalog","document","SCC","clause","SCC9.301","action","not_adopted","paragraphs","P951","catalogInputKeys",Collections.singletonList("worksSubjectToExcision"),"inputKeys",Collections.singletonList("fixed-worksSubjectToExcision"));
        List<Map<String,Object>> rows=Arrays.asList(row(54,"toc"),row(956,"last"),row(957,"outside"));
        DraftDocumentBindings.associateTargets("SCC",source(DraftFormattedRules.verifiedSourceHash("SCC")),plan(fixed),rows);
        assertTrue(list(rows.get(0).get("actionIds")).contains("scc-excision"));assertTrue(list(rows.get(1).get("fieldKeys")).contains("fixed-worksSubjectToExcision"));assertTrue(list(rows.get(2).get("actionIds")).isEmpty());assertFalse(fixed.containsKey("nativeBinding"));
    }

    @Test void anotherEditionCannotBorrowFixedParagraphsWhileItsNativeGraphLinksStillWork() {
        String other=String.join("",Collections.nCopies(64,"a"));Map<String,Object> fixed=obj("id","sct-domestic-submission-liability","executionKind","fixed_catalog","document","SCT","paragraphs","P895","catalogInputKeys",Collections.singletonList("domesticBlocks"),"inputKeys",Collections.singletonList("fixed-domesticBlocks"));
        List<Map<String,Object>> rows=Collections.singletonList(row(895,"p895"));DraftDocumentBindings.associateTargets("SCT",source(other),plan(fixed,nativeAction(other,"SCT","p895")),rows);
        assertEquals(Collections.singletonList("native-action"),rows.get(0).get("actionIds"));assertEquals(Collections.singletonList("custom-variable"),rows.get(0).get("fieldKeys"));
    }

    private static TemplateIndex source(String sha){TemplateIndex source=mock(TemplateIndex.class);when(source.getSourceSha256()).thenReturn(sha);return source;}
    private static Map<String,Object> row(int ordinal,String id){return obj("sourceParagraphOrdinal",ordinal,"sourceParagraphId",id,"fieldKeys",new ArrayList<>(),"actionIds",new ArrayList<>());}
    private static Map<String,Object> plan(Map<String,Object>...actions){return obj("graphRevisionId","current-review","fixedRuleCoverage",obj("registered",true),"actions",Arrays.asList(actions));}
    private static Map<String,Object> nativeAction(String sha,String file,String paragraph){return obj("id","native-action","document",file,"inputKeys",Collections.singletonList("custom-variable"),"nativeBinding",obj("status","bound","sourceSha256",sha,"spans",Collections.singletonList(obj("paragraphId",paragraph))));}
}
