package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import com.consense.domain.DraftPdfArtifact;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;
import static com.consense.service.drafting.DraftTocFinalizerDynamicHeadingTest.*;

/** The native empty endpoint is independently checked; heading-flow acceptance remains UNKNOWN. */
class DraftTocClearedEndpointTest {
    private static DraftNativeHeadingRemovalAuthorizationTest.Scenario cleared()throws Exception {
        DraftNativeHeadingRemovalAuthorizationTest.Scenario s=new DraftNativeHeadingRemovalAuthorizationTest.Scenario();Paragraph paragraph=DraftDocumentBindings.markers(EDITOR.inspect(s.retained)).get(s.name).get(0);
        byte[] empty=EDITOR.apply(s.retained,new SourceEditBatch(DraftPdfConverter.sha256(s.retained),List.of(Edit.clearParagraph("authored-clear",paragraph)))).getDocxBytes();s.removed=packageXml(xml(empty).replaceFirst("<w:t>2</w:t>","<w:t>1</w:t>"));
        Paragraph current=DraftDocumentBindings.markers(EDITOR.inspect(s.removed)).get(s.name).get(0);s.body().put("resultParagraphId",current.getId());s.body().put("text","");s.body().put("textHash",DraftAdoption.hash(""));s.layout.put("docxSha256",DraftPdfConverter.sha256(s.removed));s.ledger=new ArrayList<>(List.of(s.layout));return s;
    }
    @Test void exactEmptyRetainedEndpointAllowsOnlyTocAndPreservesUnknownHeadingDenominator()throws Exception {
        DraftNativeHeadingRemovalAuthorizationTest.Scenario s=cleared();IllegalStateException original=assertThrows(IllegalStateException.class,()->s.select(new LinkedHashMap<>()));assertEquals("HEADING_FLOW_REMOVAL_STILL_PRESENT: "+s.name,original.getMessage());
        AuthoredRenderer renderer=new AuthoredRenderer(Mode.NO_SPLIT);DraftTocFinalizer.Result result=DraftNativeHeadingRemovalAuthorizationTest.finish(s,renderer);
        assertEquals("PASS",result.proof.get("status"),String.valueOf(result.proof));assertEquals("UNKNOWN",result.proof.get("headingFlowStatus"));assertEquals(original.getMessage(),result.proof.get("headingFlowReason"));assertEquals(244,result.proof.get("headingFlowDenominator"));assertEquals(244,result.proof.get("headingFlowRegistered"));assertEquals(0,result.proof.get("headingFlowRepairCount"));assertEquals(0,result.proof.get("headingMutationCount"));assertEquals(70,result.proof.get("targetDenominator"));assertEquals(1,result.proof.get("changedCaches"));assertEquals(2,renderer.inputs.size());assertFalse(result.proof.containsKey("finalHeadingPairs"));assertEquals(xml(s.removed).replaceFirst("<w:t>1</w:t>","<w:t>2</w:t>"),xml(result.docx));unchangedPartsExceptDocument(s.removed,result.docx);
        DraftPdfArtifact wrong=new DraftPdfArtifact();wrong.setDocxSha256("wrong");wrong.setRenderProfileHash(result.pdf.getRenderProfileHash());wrong.setPdfBytes(result.pdf.getPdfBytes());wrong.setPdfSha256(result.pdf.getPdfSha256());DraftTocFinalizer.Result rejected=result.useExisting(wrong);assertEquals("UNKNOWN",rejected.proof.get("status"));assertEquals("UNKNOWN",rejected.proof.get("headingFlowStatus"));assertArrayEquals(s.removed,rejected.docx);assertArrayEquals(result.first.getPdfBytes(),rejected.pdf.getPdfBytes());assertEquals(DraftPdfConverter.sha256(rejected.docx),rejected.pdf.getDocxSha256());assertEquals(2,renderer.inputs.size());
    }
    @Test void bindingMismatchOrAnyNativeVisibleControlCannotUseTocOnlyFallback()throws Exception {
        for(int fault=0;fault<9;fault++){
            DraftNativeHeadingRemovalAuthorizationTest.Scenario s=cleared();switch(fault){
                case 0:s.body().put("textHash","wrong");break;case 1:s.body().put("resultParagraphId","foreign");break;case 2:s.body().put("sourceTextHash","wrong");break;case 3:s.bindings.add(new LinkedHashMap<>(s.body()));break;
                default:String p=paragraph(xml(s.removed),s.name),extra=fault==4?text("Visible body"):fault==5?"<w:r><w:drawing/></w:r>":fault==6?"<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>":fault==7?"<m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"/>":"<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr>";s.removed=packageXml(xml(s.removed).replace(p,p.replace("</w:p>",extra+"</w:p>")));s.layout.put("docxSha256",DraftPdfConverter.sha256(s.removed));
            }
            AuthoredRenderer renderer=new AuthoredRenderer(Mode.NO_SPLIT);DraftTocFinalizer.Result result=DraftNativeHeadingRemovalAuthorizationTest.finish(s,renderer);assertEquals("UNKNOWN",result.proof.get("status"),"fault "+fault);assertArrayEquals(s.removed,result.docx);assertEquals(1,renderer.inputs.size());assertFalse(result.proof.containsKey("tocOnlyClearedEndpoint"));
        }
    }
    @Test void independentTocStillRejectsChangedRenderIdentityOrProfileAndKeepsFirstPair()throws Exception {
        for(Mode mode:List.of(Mode.WRONG_PAIR,Mode.PROFILE_CHANGED)){DraftNativeHeadingRemovalAuthorizationTest.Scenario s=cleared();AuthoredRenderer renderer=new AuthoredRenderer(mode);DraftTocFinalizer.Result result=DraftNativeHeadingRemovalAuthorizationTest.finish(s,renderer);assertEquals("UNKNOWN",result.proof.get("status"));assertEquals("UNKNOWN",result.proof.get("headingFlowStatus"));assertTrue(String.valueOf(result.proof.get("headingFlowReason")).startsWith("HEADING_FLOW_REMOVAL_STILL_PRESENT:"));assertEquals(mode==Mode.WRONG_PAIR?"TOC_CONVERSION_IDENTITY_MISMATCH":"TOC_RENDER_PROFILE_CHANGED",result.proof.get("reason"));assertArrayEquals(s.removed,result.docx);assertArrayEquals(renderer.pdfs.get(0),result.pdf.getPdfBytes());assertEquals(DraftPdfConverter.sha256(s.removed),result.pdf.getDocxSha256());assertEquals(2,renderer.inputs.size());}
    }
}
