package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerReviewedVisualSidebarIndependentGuardTest {
    @Test void anUnboundInterveningImageCannotBeSkippedToClaimACompleteSourceUnit()throws Exception{
        OerReviewedVisualSidebarScopeTest.Fixture f=new OerReviewedVisualSidebarScopeTest.Fixture("paddleocr-vl");
        DocumentBlock image=f.block("unbound-image","image","",new double[]{.2,.43,.7,.47});f.refresh();f.region(image).put("geometryStatus","unknown");
        assertTrue(OerReviewedVisualSidebarScopeTest.note(f.analyse()).path("sourceSyntaxKnown").asBoolean());OerReviewedVisualSidebarScopeTest.unresolved(f);
    }

    @Test void anUnreviewedInterveningBodyObservationCannotBeSkippedToReachALaterNumberedTitle()throws Exception{
        OerReviewedVisualSidebarScopeTest.Fixture f=new OerReviewedVisualSidebarScopeTest.Fixture("paddleocr-vl");
        DocumentBlock continuation=f.block("unreviewed-body","text","Continued source requirement",new double[]{.2,.43,.7,.47});f.refresh();f.region(continuation).put("selectionStatus","candidate_unverified");
        OerReviewedVisualSidebarScopeTest.unresolved(f);
    }

    @Test void internalExplicitSubitemAnchorsRemainBoundariesWhenTheirBodyStartsWithAMathOrNumericToken()throws Exception{
        for(String suffix:Arrays.asList("(f) $ f(x)=1 $. ","(f) 123 units are required.")){
            OerReviewedVisualSidebarScopeTest.Fixture f=new OerReviewedVisualSidebarScopeTest.Fixture("paddleocr-vl");String text="*(q) The first source item ends.\n"+suffix;f.body.setText(text);f.body.setOriginalText(text);f.region(f.body).put("text",text).put("originalText",text);f.provider(f.body).put("text",text);f.refresh();OerReviewedVisualSidebarScopeTest.unresolved(f);
        }
    }

    @Test void completeRawReviewCannotHideMissingOrDuplicateActiveProjectionMembers()throws Exception{
        OerReviewedVisualSidebarScopeTest.Fixture missing=new OerReviewedVisualSidebarScopeTest.Fixture("paddleocr-vl");DocumentBlock image=missing.block("missing-image","image","",new double[]{.2,.43,.7,.47});missing.refresh();missing.blocks.remove(image);OerReviewedVisualSidebarScopeTest.unresolved(missing);
        OerReviewedVisualSidebarScopeTest.Fixture duplicate=new OerReviewedVisualSidebarScopeTest.Fixture("paddleocr-vl");DocumentBlock copy=JsonUtils.mapper().treeToValue(JsonUtils.mapper().valueToTree(duplicate.aside),DocumentBlock.class);copy.setId("duplicated-active-projection");duplicate.blocks.add(copy);OerReviewedVisualSidebarScopeTest.unresolved(duplicate);
    }
}
