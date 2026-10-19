package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerTemplateScanOptionCandidateTest {
    private static final String SOURCE=String.join("",Collections.nCopies(64,"a"));
    private static final String OTHER=String.join("",Collections.nCopies(64,"b"));

    @Test void sourceBoundLayoutLinksRepeatedAnnotationsAndTheCompleteBodyUntilTheNextBranch() {
        List<DocumentBlock> blocks=spread();JsonNode before=JsonUtils.mapper().valueToTree(blocks),evidence=analyse(blocks);List<JsonNode> groups=options(evidence);
        assertEquals(3,groups.size());List<String> expected=Arrays.asList("heading-a","a","support","d");
        for(int at=0;at<2;at++){JsonNode group=groups.get(at);assertEquals(expected,ids(group.path("orderedMemberIds")));assertEquals(expected,ids(group.path("targetMemberIds")));assertEquals(Arrays.asList("note-a","note-ac"),ids(group.path("continuationAnnotationMemberIds")));assertFalse(group.path("scopeComplete").asBoolean());assertTrue(group.path("captureComplete").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());assertEquals("heading-b",group.path("endBoundary").path("memberId").asText());assertTrue(ids(group.path("physicalContextMemberIds")).contains("heading-ac"));}
        assertEquals(Arrays.asList("heading-b","b"),ids(groups.get(2).path("orderedMemberIds")));assertEquals("next",groups.get(2).path("endBoundary").path("memberId").asText());assertEquals(blocks.size(),evidence.path("sourceMembers").size());assertEquals(before,JsonUtils.mapper().valueToTree(blocks));
    }

    @Test void equalNumbersWithoutAnExplicitContinuationNeverJoin() {
        List<DocumentBlock> blocks=spread();find(blocks,"heading-ac").setText("*27. Electronic submission");JsonNode evidence=analyse(blocks);
        assertEquals(Arrays.asList("heading-a","a","support"),ids(options(evidence).get(0).path("orderedMemberIds")));assertEquals(Arrays.asList("heading-ac","d"),ids(options(evidence).get(1).path("orderedMemberIds")));
    }

    @Test void aDifferentTitleAndAnUnobservedPhysicalPageBothPreventContinuation() {
        List<DocumentBlock> title=spread();find(title,"heading-ac").setText("*27. Different submission (Cont'd)");assertEquals(Arrays.asList("heading-a","a","support"),ids(options(analyse(title)).get(0).path("orderedMemberIds")));
        List<DocumentBlock> gap=spread();for(DocumentBlock block:gap)if(block.getPageNo()==32){block.setPageNo(34);((ObjectNode)block.getVisualRegion().path("originalGeometry").path("targetFrame")).put("physicalPageNo",34);}assertEquals(Arrays.asList("heading-a","a","support"),ids(options(analyse(gap)).get(0).path("orderedMemberIds")));
    }

    @Test void aTrailingMarginAnnotationCannotConsumeTheFollowingPages() {
        List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(title("heading",7,"8. Documents",.1),body("body",7,"(a) Actual body",.18),note("unmatched",7,"(Alternative (b) - source instruction)",.4),title("next",8,"9. Other clause",.1),body("later",8,"Must remain outside the earlier branch",.2)));
        JsonNode group=options(analyse(blocks)).get(0);assertEquals(Collections.singletonList("unmatched"),ids(group.path("orderedMemberIds")));assertFalse(group.path("captureComplete").asBoolean());assertEquals("unmatched",group.path("endBoundary").path("memberId").asText());assertEquals("observed_annotation_member_end",group.path("endBoundary").path("kind").asText());
    }

    @Test void ambiguousPositiveTitlesNeverChooseTheFirstOverlappingHeading() {
        List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(title("one",11,"13. First branch",.1),title("two",11,"14. Second branch",.11),note("note",11,"(Alternative (a) - source instruction)",.1),title("end",11,"15. End",.6)));
        assertEquals(Collections.singletonList("note"),ids(options(analyse(blocks)).get(0).path("orderedMemberIds")));
    }

    @Test void foreignFramesMissingTransformsAndNonpositiveLayoutRolesStayAnnotationOnly() {
        for(String problem:Arrays.asList("source","page","transform","status","role")){
            List<DocumentBlock> blocks=spread();ObjectNode region=(ObjectNode)find(blocks,"note-a").getVisualRegion(),geometry=(ObjectNode)region.path("originalGeometry");
            if("source".equals(problem))((ObjectNode)geometry.path("targetFrame")).put("sourceSha256",OTHER);
            if("page".equals(problem))((ObjectNode)geometry.path("targetFrame")).put("physicalPageNo",99);
            if("transform".equals(problem))geometry.put("transformSha256",OTHER);
            if("status".equals(problem))geometry.put("status","unknown");
            if("role".equals(problem)){region.put("kind","text");((ObjectNode)region.path("providerBlock")).put("label","text");}
            assertEquals(Collections.singletonList("note-a"),ids(options(analyse(blocks)).get(0).path("orderedMemberIds")),problem);
        }
    }

    @Test void aNumericTextBlockIsNotPromotedToATitleByItsTextAlone() {
        DocumentBlock title=body("numeric-text",9,"*63. Ordinary paragraph",.1);JsonNode evidence=analyse(Arrays.asList(title,note("note",9,"(Alternative (a) - source instruction)",.1),title("next",9,"64. Next",.7)));
        assertEquals(Collections.singletonList("note"),ids(options(evidence).get(0).path("orderedMemberIds")));
    }

    @Test void conflictingAnnotationsOnOneHeadingDoNotBecomeTwoBranches() {
        JsonNode evidence=analyse(Arrays.asList(title("heading",6,"21. Shared heading",.1),body("body",6,"(a) The actual source",.3),note("a",6,"(Alternative (a) - first instruction)",.1),note("b",6,"(Alternative (b) - second instruction)",.1),title("end",6,"22. Other",.7)));
        assertEquals(Collections.singletonList("a"),ids(options(evidence).get(0).path("orderedMemberIds")));assertEquals(Collections.singletonList("b"),ids(options(evidence).get(1).path("orderedMemberIds")));
    }

    @Test void sourceUnclosedGuidanceIsNotRepairedAndBodyMembershipGrantsNoSyntaxAuthority() {
        List<DocumentBlock> blocks=spread();DocumentBlock note=find(blocks,"note-a");note.setText("(Alternative (a) - source instruction with missing delimiter");JsonNode evidence=analyse(blocks);
        assertEquals(Arrays.asList("heading-a","a","support","d"),ids(options(evidence).get(0).path("orderedMemberIds")));JsonNode guidance=null;for(JsonNode candidate:evidence.path("guidanceNotes"))if(ids(candidate.path("orderedMemberIds")).contains("note-a"))guidance=candidate;
        assertNotNull(guidance);assertFalse(guidance.path("captureComplete").asBoolean());assertFalse(guidance.path("sourceSyntaxKnown").asBoolean());assertFalse(guidance.path("sourceSyntaxComplete").asBoolean());assertFalse(options(evidence).get(0).path("scopeComplete").asBoolean());
    }

    @Test void nativePdfOptionsKeepTheirExistingSerialCandidateBehavior() {
        DocumentBlock a=note("a",2,"(Alternative (a) - first native instruction)",.1),body=body("body",2,"Native body",.2),b=note("b",2,"(Alternative (b) - second native instruction)",.7);for(DocumentBlock block:Arrays.asList(a,body,b))block.setSource("native_pdf");
        assertEquals(Arrays.asList("a","body"),ids(options(analyse(Arrays.asList(a,body,b))).get(0).path("orderedMemberIds")));
    }

    @Test void aBodyRegionCrossingThePeerBoundaryRemainsContextWithoutAnInventedTextSplit() {
        DocumentBlock crossing=scan("crossing",17,"(a) Body merged across the next heading boundary","text",.18,.3,.76,.65);
        JsonNode group=options(analyse(Arrays.asList(title("heading",17,"19. First branch",.1),crossing,title("peer",17,"20. Next source clause",.5),note("annotation",17,"(Alternative (a) - first branch)",.1)))).get(0);
        assertEquals(Collections.singletonList("heading"),ids(group.path("orderedMemberIds")));assertTrue(ids(group.path("physicalContextMemberIds")).contains("crossing"));assertFalse(group.path("captureComplete").asBoolean());assertFalse(group.path("scopeComplete").asBoolean());
    }

    @Test void aPositiveNumericTitleWithObservedMathMarkupStillEndsTheEarlierBranch() {
        JsonNode group=options(analyse(Arrays.asList(title("heading",17,"19. First branch",.1),body("body",17,"(a) Complete source body",.2),title("peer",17,"20. $ \\underline{\\text{Other source clause}} $",.5),body("outside",17,"Not part of the first branch",.6),note("annotation",17,"(Alternative (a) - first branch)",.1)))).get(0);
        assertEquals(Arrays.asList("heading","body"),ids(group.path("orderedMemberIds")));assertEquals("peer",group.path("endBoundary").path("memberId").asText());
    }

    private static List<DocumentBlock> spread(){
        DocumentBlock carrier=body("carrier",31,"Complete candidate container",.97);carrier.setSource("parser_page_group_candidate");
        return new ArrayList<>(Arrays.asList(scan("header",31,"NOTES TO TENDERERS (Cont'd)","header",.3,.03,.7,.05),title("heading-a",31,"*27. Electronic submission",.2),body("a",31,"(a) Full branch body",.3),body("support",31,"Technical support remains part of this branch",.72),scan("footer",31,"HD(QS) source footer","footer",.1,.93,.5,.95),note("note-a",31,"(Alternative (a) - for electronic submission)",.2),carrier,scan("header2",32,"NOTES TO TENDERERS (Cont'd)","header",.3,.02,.7,.04),title("heading-ac",32,"*27. Electronic submission (Cont'd)",.08),body("d",32,"(d) Complete continuation body",.14),title("heading-b",32,"*27. Hard copy submission",.36),body("b",32,"(a) Complete other branch",.44),title("next",32,"28. Next source clause",.7),body("next-body",32,"(a) Not part of either Alternative",.8),note("note-ac",32,"(Alternative (a) - for electronic submission)",.08),note("note-b",32,"(Alternative (b) - for hard copy)",.36),scan("footer2",32,"Source footer","footer",.1,.94,.5,.96)));
    }
    private static DocumentBlock find(List<DocumentBlock> blocks,String id){return blocks.stream().filter(block->id.equals(block.getId())).findFirst().orElseThrow(AssertionError::new);}
    private static DocumentBlock title(String id,int page,String text,double y){return scan(id,page,text,"paragraph_title",.1,y,.65,y+.025);}
    private static DocumentBlock body(String id,int page,String text,double y){return scan(id,page,text,"text",.18,y,.76,y+.05);}
    private static DocumentBlock note(String id,int page,String text,double y){return scan(id,page,text,"aside_text",.8,y,.98,y+.14);}
    private static DocumentBlock scan(String id,int page,String text,String role,double x1,double y1,double x2,double y2){
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setPageNo(page);block.setSource("ocr");block.setText(text);block.setOriginalText(text);ObjectNode region=JsonUtils.mapper().createObjectNode();region.put("kind",role);region.put("text",text);region.putObject("providerBlock").put("label",role);ObjectNode geometry=region.putObject("originalGeometry");geometry.put("status","verified");geometry.put("transformSha256",SOURCE);geometry.putArray("xyxyNormalized").add(x1).add(y1).add(x2).add(y2);ObjectNode frame=geometry.putObject("targetFrame");frame.put("sourceSha256",SOURCE);frame.put("physicalPageNo",page);frame.put("coordinateFrame","original_page");frame.put("origin","top-left");frame.put("width",600);frame.put("height",800);block.setVisualRegion(region);return block;
    }
    private static JsonNode analyse(List<DocumentBlock> blocks){ParseCoverage coverage=new ParseCoverage();int pages=blocks.stream().mapToInt(DocumentBlock::getPageNo).max().orElse(1);coverage.setTotalPages(pages);return OerTemplateEvidence.analyse("NTT",new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),pages,false,"fixture",blocks,coverage,SOURCE,Collections.emptyList()));}
    private static List<JsonNode> options(JsonNode evidence){List<JsonNode> groups=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if("literal_option_candidate".equals(group.path("kind").asText()))groups.add(group);return groups;}
    private static List<String> ids(JsonNode values){List<String> ids=new ArrayList<>();for(JsonNode value:values)ids.add(value.asText());return ids;}
}
