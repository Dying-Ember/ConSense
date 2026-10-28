package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored contract regressions, not a replay of an unobserved model response. */
class AdviceDescriptiveIntentTest {
    private static final String QUESTION="How are Envelope 1 and Envelope 2 used in the tender submission?";
    private final List<CitationVO> tender=Collections.singletonList(new CitationVO("SCT selected tender",null,"source item",
            "AUTHORED: Envelope 1 contains the technical submission; Envelope 2 contains the price submission.",1,"[1]","1","sha","tender",true));

    private AdvisoryHarness.Section section(int index){
        return JsonUtils.mapper().convertValue(JsonUtils.parse(AdviceBudgetFixture.contract(
                "{\"grounded\":true,\"answer\":\"The selected tender text assigns the technical submission to Envelope 1 and the price submission to Envelope 2 [1].\",\"citations\":[\"[1]\"]}"))
                .path("sections").get(index),AdvisoryHarness.Section.class);
    }

    @Test void rejectionDoesNotInventAnUnrequestedGraphOrAListOfMissingLegalFacts(){
        String warning=AdvisoryHarness.rejectionClarification("en");
        assertFalse(AdvisoryHarness.flowchartRequested(QUESTION));
        for(String unrelated:Arrays.asList("flowchart","amendments","dates","rights","incorporation"))
            assertFalse(warning.toLowerCase(Locale.ROOT).contains(unrelated),warning);
        assertTrue(warning.contains("could not be verified"),"A publication failure must remain visible");
    }

    @Test void descriptiveOpeningDoesNotInventApplicationPrerequisites(){
        String opening=AdvisoryHarness.content(section(0),tender,false,QUESTION,Collections.emptyList());
        assertTrue(opening.contains(QUESTION));
        assertFalse(opening.contains("incorporation, edition and rights require"),opening);
        assertFalse(opening.contains("do not prove adoption, deadline start events or entitlement"),opening);
        assertTrue(opening.contains("Only gaps affecting the requested answer"),opening);
    }

    @Test void promptSeparatesSelectedTextDescriptionFromBindingApplicationWithoutChangingBasis(){
        String prompt=AdvisoryHarness.system(null,tender,QUESTION);
        for(String instruction:Arrays.asList("A descriptive question about selected tender text", "does not by itself ask whether that text has been adopted",
                "A fully supported descriptive answer may be grounded=true", "Keep its requested project_contract basis",
                "Actual project applicability, entitlement, post-contract rights", "do not erase a declared gap"))
            assertTrue(prompt.contains(instruction),instruction);
        assertEquals("c0b67e64c8fac02badb7c2fbd36b4e61bbf5735a43936e062b7f72e96d3c1eb7",AdvisoryHarness.POLICY_SHA);
    }

    @Test void tenderDescriptionUsesExistingSourceGateWhileStandardApplicationStillNeedsSupport(){
        AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);
        AdvisoryHarness.Section description=section(1);
        assertDoesNotThrow(()->AdvisoryHarness.validate(description,tender,false,
                (basis,refs)->Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service,"incorporationSupported",basis,refs,tender))));
        List<CitationVO> standard=Collections.singletonList(new CitationVO("GCC standard",null,"source item","AUTHORED standard",1,"[1]","fixed","sha","standard",false));
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(description,standard,false,
                (basis,refs)->Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service,"incorporationSupported",basis,refs,standard))));
    }

    @Test void realApplicabilityAndRightsGapsRemainUnresolvedAndReaderVisible(){
        AdvisoryHarness.Section unresolved=section(0);unresolved.setKind("uncertainty");
        unresolved.setGaps(Arrays.asList("adoption","rights_obligations"));String before=JsonUtils.write(unresolved);
        assertDoesNotThrow(()->AdvisoryHarness.validate(unresolved,tender,false,(basis,refs)->false));
        String rendered=AdvisoryHarness.content(unresolved,tender,false,"Has the project adopted this rule, and are we entitled to payment?",Collections.emptyList());
        assertTrue(rendered.contains("project incorporation"));assertTrue(rendered.contains("entitlement and obligation conditions"));
        assertTrue(AdvisoryHarness.unresolved(unresolved));assertEquals(before,JsonUtils.write(unresolved));
        String clarification=AdvisoryHarness.clarification("adoption","en");assertTrue(clarification.contains("project incorporation"));
    }

    @Test void descriptiveQuestionDoesNotWaiveMissingTextOrCitationValidation(){
        AdvisoryHarness.Section missing=section(1);missing.setGaps(Collections.singletonList("other_fact"));
        String before=JsonUtils.write(missing);assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(missing,tender,false,(basis,refs)->true));
        assertEquals(before,JsonUtils.write(missing));
        AdvisoryHarness.Section unknownCitation=section(1);unknownCitation.setContent("Unsupported content [2].");
        assertThrows(IllegalArgumentException.class,()->AdvisoryHarness.validate(unknownCitation,tender,false,(basis,refs)->true));
    }
}
