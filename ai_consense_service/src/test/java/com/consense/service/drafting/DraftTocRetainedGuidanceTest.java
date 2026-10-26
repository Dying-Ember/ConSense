package com.consense.service.drafting;

import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftNativeHeadingBoundariesTest.*;

/** Source-gap verification is independent of heading pagination; no renderer or business acceptance. */
class DraftTocRetainedGuidanceTest {
    static final class Retained {
        final byte[] bytes;final List<NativeHeadingFlow> flows;
        Retained()throws Exception {
            String original=xml(fixture()),empty=paragraph(original,"CLdynamic0Gap0");
            String note="<w:p>"+text("Authored retained guidance, not approved for deletion.")+"</w:p>";
            byte[] bare=packageXml("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+note+"</w:body></w:document>");String sourceHash=EDITOR.inspect(bare).getMainParagraphs().get(0).getSha256();
            String name="CSauthoredRetainedGuidance",marked=note.replace("</w:p>",marker(990010,name)+end(990010)+"</w:p>");
            bytes=packageXml(original.replace(empty,marked));NativeHeadingFlow first=allFlows().get(0);
            List<NativeHeadingGap> gaps=new ArrayList<>();gaps.add(new NativeHeadingGap(name,sourceHash));
            for(int i=1;i<first.getEmptyParagraphs();i++)gaps.add(new NativeHeadingGap("CSauthoredEmpty"+i,DraftAdoption.hash("unused empty source"+i)));
            flows=new ArrayList<>(allFlows());flows.set(0,new NativeHeadingFlow(first.getHeadingBookmark(),first.getBodyBookmark(),first.getEmptyParagraphs(),gaps));
        }
        int verify(byte[] input){return EDITOR.verifyNativeHeadingRetainedSourceGaps(input,DraftPdfConverter.sha256(input),flows);}
    }
    @Test void exactRetainedSourceGapDoesNotRelaxTheDefaultHeadingPolicyOrMutateBytes()throws Exception {
        Retained s=new Retained();EditException strict=assertThrows(EditException.class,()->EDITOR.updateNativeHeadingBoundaries(s.bytes,DraftPdfConverter.sha256(s.bytes),s.flows,Collections.emptySet()));assertEquals("HEADING_FLOW_CHAIN_CHANGED",strict.getCode());
        byte[] frozen=s.bytes.clone();assertEquals(1,s.verify(s.bytes));assertArrayEquals(frozen,s.bytes);
    }
    @Test void changedTextUnknownNodesWrongGapSlotAndAmbiguousMarkersFailClosed()throws Exception {
        Retained s=new Retained();String original=xml(s.bytes),note=paragraph(original,"CSauthoredRetainedGuidance");List<String> invalid=new ArrayList<>();
        invalid.add(original.replace("Authored retained guidance","Changed retained guidance"));
        invalid.add(original.replace(note,note+"<w:p/>"));
        invalid.add(original.replace(note,note+"<w:p>"+text("Unregistered inserted text")+"</w:p>"));
        invalid.add(original.replace(note,note.replace("</w:p>","<w:r><w:drawing/></w:r></w:p>")));
        invalid.add(original.replace(note,note.replace("CSauthoredRetainedGuidance","CSunknownGuidance")));
        invalid.add(original.replace(note,note.replace("</w:p>",marker(990011,"CSauthoredRetainedGuidance")+end(990011)+"</w:p>")));
        String other=paragraph(original,"CLdynamic0Gap1");invalid.add(original.replace(note,"@@NOTE@@").replace(other,note).replace("@@NOTE@@",other));
        for(String value:invalid)assertThrows(EditException.class,()->s.verify(packageXml(value)));
        assertThrows(EditException.class,()->EDITOR.verifyNativeHeadingRetainedSourceGaps(s.bytes,"wrong-sha",s.flows));
    }
    @Test void registryGapHashesRemainOrderedAndThePlainEmptyFixtureCannotTriggerFallback()throws Exception {
        NativeHeadingFlow first=allFlows().get(0);assertEquals(first.getEmptyParagraphs(),first.getSourceGaps().size());assertEquals("CS1cad99463b9e7f5a51507ab7d63e",first.getSourceGaps().get(1).getBookmark());assertEquals("ebef78013c5190bab48bf1b51d5870ae926a1485c9afa2a5b71ad11718cb28a1",first.getSourceGaps().get(1).getSourceSha256());
        byte[] empty=fixture();assertThrows(EditException.class,()->EDITOR.verifyNativeHeadingRetainedSourceGaps(empty,DraftPdfConverter.sha256(empty),allFlows()));
    }
    @Test void retainedGapContractsRejectMissingIdentityAndCopyCallerLists()throws Exception {
        NativeHeadingGap gap=new NativeHeadingGap("CSauthoredGap",DraftAdoption.hash("source"));List<NativeHeadingGap> supplied=new ArrayList<>(List.of(gap));NativeHeadingFlow flow=new NativeHeadingFlow("CSauthoredHeading","CSauthoredBody",1,supplied);supplied.clear();assertEquals(1,flow.getSourceGaps().size());assertDoesNotThrow(()->new NativeHeadingFlow("CSauthoredHeading","CSauthoredBody",1,List.of(gap)));
        assertThrows(EditException.class,()->new NativeHeadingGap("foreign","bad"));assertThrows(EditException.class,()->new NativeHeadingFlow("CSauthoredHeading","CSauthoredBody",1,Collections.singletonList(null)));assertThrows(EditException.class,()->new NativeHeadingFlow("CSauthoredHeading","CSauthoredBody",2,List.of(gap)));
    }
}
