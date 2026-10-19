package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authored complete-page observations test the public analyse seam; no OCR/model calls. */
class OerReviewedVisualSidebarScopeTest {
    static final String SOURCE=repeat('a'),IMAGE=repeat('b');
    static String repeat(char c){char[] value=new char[64];Arrays.fill(value,c);return new String(value);}
    static ObjectNode obj(){return JsonUtils.mapper().createObjectNode();}
    static ArrayNode arr(){return JsonUtils.mapper().createArrayNode();}
    static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode out=obj();Set<String> keys=new TreeSet<>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)out.set(key,canonical(value.get(key)));return out;}if(value.isArray()){ArrayNode out=arr();for(JsonNode item:value)out.add(canonical(item));return out;}return value;}
    static String digest(JsonNode value)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static final class Fixture {
        final List<DocumentBlock> blocks=new ArrayList<>();final String parser;final ObjectNode transform,frame,context;DocumentBlock body,aside,next,group;
        Fixture(String parser)throws Exception{
            this.parser=parser;frame=obj().put("imageSha256",IMAGE).put("width",1000).put("height",2000).put("coordinateUnit","pixel").put("bboxFormat","xywh");
            ObjectNode input=obj().put("artifactName","page.png").put("coordinateFrame","raster").put("coordinateUnit","pixel").put("width",1000).put("height",2000).put("sha256",IMAGE);
            ObjectNode target=obj().put("coordinateFrame","original_page").put("coordinateUnit","pt").put("width",600).put("height",1200).put("physicalPageNo",6).put("sourceSha256",SOURCE);
            transform=obj().put("kind","affine");transform.set("inputFrame",input);transform.set("matrix",JsonUtils.parse("[0.6,0,0,0,0.6,0]"));transform.set("targetFrame",target);
            context=obj().put("physicalPageNo",6).put("imageSha256",IMAGE).put("width",1000).put("height",2000);context.set("originalPageTransform",transform);context.putObject("source").put("sourceSha256",SOURCE).put("sourceFilename","authored-unseen-source.pdf").put("mediaType","application/pdf").put("pageCount",9);
            body=block("body","text","*(q) Install the optional foundation system for this complete source paragraph.",new double[]{.2,.3,.7,.4});
            aside=block("aside","aside_text","(Delete if the optional foundation system is not applicable)",new double[]{.8,.31,.95,.41});
            next=block("next","paragraph_title","12. Following independent heading",new double[]{.15,.5,.72,.53});
            group=new DocumentBlock();group.setId("authored-whole-page-observation");group.setKind("visual_parser_group");group.setSource("parser_page_group_candidate");group.setText("");group.setPageNo(6);blocks.add(group);refresh();
        }
        String run(String parser){return parser+":"+repeat(parser.equals("paddleocr-vl")?'c':'d');}
        DocumentBlock block(String suffix,String kind,String text,double[] box)throws Exception{
            String id=run(parser)+"/page/6/block/"+suffix;ObjectNode provider=obj().put("blockId",id).put("label",kind).put("text",text);provider.set("readingOrder",obj().put("outputSequence",blocks.size()));ObjectNode bounds=provider.putObject("bbox").put("coordinateFrame","normalized").put("coordinateUnit","ratio").put("origin","top-left").put("pageWidth",1000).put("pageHeight",2000);bounds.set("xyxy",JsonUtils.mapper().valueToTree(box));
            ObjectNode region=obj().put("id",id).put("kind",kind).put("text",text).put("originalText",text).put("channel",parser).put("selectionStatus","source_review_selected_candidate").put("geometryStatus","observed_input_frame");region.set("providerBlock",provider);region.set("readingOrder",provider.path("readingOrder").deepCopy());region.set("bbox",JsonUtils.mapper().valueToTree(new double[]{box[0]*1000,box[1]*2000,(box[2]-box[0])*1000,(box[3]-box[1])*2000}));ObjectNode geometry=region.putObject("originalGeometry").put("status","verified").put("transformSha256",digest(transform));geometry.set("targetFrame",transform.path("targetFrame").deepCopy());geometry.set("xyxyNormalized",JsonUtils.mapper().valueToTree(box));
            DocumentBlock block=new DocumentBlock();block.setId("observed:"+id);block.setKind(kind);block.setPageNo(6);block.setText(text);block.setOriginalText(text);block.setSource("ocr");block.setLocation("page/6/"+suffix);block.setBbox(new double[]{box[0],box[1],box[2]-box[0],box[3]-box[1]});block.setVisualRegion(region);block.setVisualFrame(frame.deepCopy());ObjectNode provenance=obj();provenance.set("sourceContext",context.deepCopy());block.setVisualProvenance(provenance);block.setReadingContext(obj().put("sourceSha256",SOURCE));blocks.add(block);return block;
        }
        void refresh(){
            ObjectNode full=obj().put("physicalPageNo",6).put("scope","whole_page").put("selectionStatus","source_review_selected_candidate").put("selectedParser",parser).put("reviewManifestSha256",repeat('e')).put("sourceQualityAccepted",false).put("adoptionStatus","candidate_unverified");full.putObject("source").put("sourceSha256",SOURCE).put("sourceRevisionSha256",SOURCE).put("pageCount",9);
            ArrayNode ids=full.putArray("reviewedBlockIds"),candidates=full.putArray("candidates");for(String label:Arrays.asList("paddleocr-vl","mineru")){
                ObjectNode candidate=candidates.addObject().put("parser",label).put("runId",run(label)).put("pageId",run(label)+"/page/6");candidate.putObject("features").put("completeRawCapture",true).put("allGeometryBound",true);ArrayNode observed=candidate.putArray("blocks");for(DocumentBlock block:blocks)if(block!=group&&block.getVisualRegion()!=null){ObjectNode provider=block.getVisualRegion().path("providerBlock").deepCopy();if(label.equals(parser)){ids.add(provider.path("blockId"));observed.add(provider);}else{provider.put("blockId",run(label)+"/page/6/block/"+observed.size());observed.add(provider);}}
                ObjectNode page=candidate.putObject("page").put("pageId",run(label)+"/page/6").put("physicalPageNo",6);page.putObject("mapping").set("originalPageTransform",transform.deepCopy());page.set("blocks",observed.deepCopy());
            }group.setParserPageGroup(full);
        }
        JsonNode analyse(){ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(9);DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument("",Collections.emptyList(),9,true,null,blocks,coverage,SOURCE,Collections.emptyList());return OerTemplateEvidence.analyse("NTT",parsed);}
        ObjectNode region(DocumentBlock b){return (ObjectNode)b.getVisualRegion();}
        ObjectNode provider(DocumentBlock b){return (ObjectNode)b.getVisualRegion().path("providerBlock");}
        ObjectNode review(){return (ObjectNode)group.getParserPageGroup();}
        ObjectNode sourceContext(DocumentBlock b){return (ObjectNode)b.getVisualProvenance().path("sourceContext");}
    }
    static JsonNode note(JsonNode evidence){return evidence.path("guidanceNotes").get(0);}
    static List<JsonNode> scopes(JsonNode evidence){List<JsonNode> found=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if("source_unit_instruction_scope".equals(group.path("kind").asText())&&group.path("scopeComplete").asBoolean())found.add(group);return found;}
    static void unresolved(Fixture f){JsonNode evidence=f.analyse();assertTrue(scopes(evidence).isEmpty());assertTrue(evidence.path("markerCandidates").get(0).path("targetGroupIds").isEmpty());}

    @Test void completeReviewedPageBindsOneWholeMarkedParagraphAndKeepsProjectUnknown()throws Exception{
        Fixture f=new Fixture("paddleocr-vl");JsonNode evidence=f.analyse();assertTrue(note(evidence).path("sourceSyntaxKnown").asBoolean());assertTrue(note(evidence).path("sourceSyntaxComplete").asBoolean());assertEquals("source_reviewed_complete_visual_page_member",note(evidence).path("sourceSyntaxAuthority").asText());assertEquals(1,note(evidence).path("sourceReviewBindings").size());
        assertEquals(1,scopes(evidence).size());JsonNode scope=scopes(evidence).get(0),range=scope.path("memberRanges").get(0);assertEquals(f.body.getId(),range.path("memberId").asText());assertEquals(0,range.path("start").asInt());assertEquals(f.body.getText().length(),range.path("end").asInt());assertEquals(1,scope.path("memberRanges").size());assertEquals(f.next.getId(),scope.path("endBoundary").path("memberId").asText());assertFalse(scope.path("automaticEditEligible").asBoolean());assertFalse(scope.path("semanticApplicabilityVerified").asBoolean());assertEquals(3,scope.path("sourceReviewBindings").size());assertEquals("conditional_source_target",evidence.path("markerCandidates").get(0).path("disposition").asText());assertEquals(1,evidence.path("markerCandidates").get(0).path("guidanceNoteIds").size());assertEquals(1,evidence.path("markerCandidates").get(0).path("conditionIds").size());
    }
    @Test void reviewedMineruCandidateUsesTheSameSourceRelationshipRule()throws Exception{Fixture f=new Fixture("mineru");assertEquals(1,scopes(f.analyse()).size());}
    @Test void aStatusStringWithoutReviewedWholePageInventoryCannotClaimSourceSyntax()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.review().put("selectionStatus","candidate_unverified").putNull("selectedParser");f.review().remove("reviewedBlockIds");JsonNode e=f.analyse();assertFalse(note(e).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
    @Test void partialReviewedIdsDoNotAuthorizeTheCompleteCandidate()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ArrayNode)f.review().path("reviewedBlockIds")).remove(0);assertFalse(note(f.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
    @Test void foreignSourceCannotBorrowTheNoteReview()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ObjectNode)f.sourceContext(f.body).path("source")).put("sourceSha256",repeat('f'));unresolved(f);}
    @Test void foreignPhysicalPageCannotBorrowTheNoteReview()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.sourceContext(f.body).put("physicalPageNo",7);unresolved(f);}
    @Test void staleRunIdentityCannotClaimACompletePageReview()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ObjectNode)f.review().path("candidates").get(0)).put("runId","paddleocr-vl:"+repeat('f'));assertFalse(note(f.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
    @Test void changedRawTextCannotReuseAnExistingReviewedBlock()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.provider(f.body).put("text","*(q) A changed meaning.");unresolved(f);}
    @Test void changedRawGeometryCannotReuseAnExistingReviewedBlock()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ArrayNode)f.provider(f.body).path("bbox").path("xyxy")).set(0,JsonUtils.mapper().getNodeFactory().numberNode(.25));unresolved(f);}
    @Test void foreignImageFrameCannotBorrowTheNoteReview()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ObjectNode)f.body.getVisualFrame()).put("imageSha256",repeat('f'));unresolved(f);}
    @Test void changedTransformCannotReuseItsOldGeometryHash()throws Exception{Fixture f=new Fixture("paddleocr-vl");((ArrayNode)f.sourceContext(f.body).path("originalPageTransform").path("matrix")).set(0,JsonUtils.mapper().getNodeFactory().numberNode(.7));unresolved(f);}
    @Test void twoVerticallyAlignedMarkedTargetsRemainAmbiguous()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.block("second","text","*(r) A second complete marked requirement.",new double[]{.21,.32,.69,.39});f.refresh();assertTrue(note(f.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
    @Test void twoReviewedPageObservationsRemainAmbiguous()throws Exception{Fixture f=new Fixture("paddleocr-vl");DocumentBlock duplicate=new DocumentBlock();duplicate.setId("another-page-group");duplicate.setSource("parser_page_group_candidate");duplicate.setKind("visual_parser_group");duplicate.setText("");duplicate.setPageNo(6);duplicate.setParserPageGroup(f.group.getParserPageGroup().deepCopy());f.blocks.add(duplicate);unresolved(f);}
    @Test void unnumberedBodyContinuationDoesNotBecomeTheCompleteParagraphEnd()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.next.setText("The same requirement continues in an unnumbered source block.");f.next.setOriginalText(f.next.getText());f.region(f.next).put("text",f.next.getText()).put("originalText",f.next.getText());f.provider(f.next).put("text",f.next.getText());f.refresh();unresolved(f);}
    @Test void repeatedSameSubitemLabelDoesNotBecomeAnExclusiveNextBoundary()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.next.setText("(q) Continued source item");f.next.setOriginalText(f.next.getText());f.region(f.next).put("text",f.next.getText()).put("originalText",f.next.getText());f.provider(f.next).put("text",f.next.getText());f.refresh();unresolved(f);}
    @Test void derivedBlockGeometryMustStillMatchItsReviewedRawObservation()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.body.setBbox(new double[]{.1,.2,.5,.4});unresolved(f);}
    @Test void interveningTableCannotBeSkippedToFindANumberedEnd()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.block("continued-table","table","Additional requirements in the same source item",new double[]{.2,.43,.7,.47});f.refresh();unresolved(f);}
    @Test void aMergedRegionContainingASecondExplicitSubitemCannotBecomeOneCompleteTarget()throws Exception{for(String anchor:Arrays.asList("(f) Different source subitem.","2. Different source clause.","SCC 7.2 Different source clause.")){Fixture f=new Fixture("paddleocr-vl");String text="*(q) This first source item ends.\n"+anchor;f.body.setText(text);f.body.setOriginalText(text);f.region(f.body).put("text",text).put("originalText",text);f.provider(f.body).put("text",text);f.refresh();unresolved(f);}}
    @Test void overlappingBodyRegionAcrossTheTargetBottomKeepsTheEndAmbiguous()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.block("overlapping-body","text","An overlapping same-lane source continuation",new double[]{.2,.35,.7,.45});f.refresh();unresolved(f);}
    @Test void malformedTruthFlagsAndArrayShapesCannotAuthorizeAReviewedMember()throws Exception{
        for(String flag:Arrays.asList("completeRawCapture","allGeometryBound")){Fixture f=new Fixture("paddleocr-vl");((ObjectNode)f.review().path("candidates").get(0).path("features")).put(flag,1);assertFalse(note(f.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
        Fixture ids=new Fixture("paddleocr-vl");ObjectNode keyedIds=obj();int n=0;for(JsonNode id:ids.review().path("reviewedBlockIds"))keyedIds.set(Integer.toString(n++),id);ids.review().set("reviewedBlockIds",keyedIds);assertFalse(note(ids.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(ids);
        Fixture blocks=new Fixture("paddleocr-vl");ObjectNode selected=(ObjectNode)blocks.review().path("candidates").get(0),keyedBlocks=obj();n=0;for(JsonNode block:selected.path("blocks"))keyedBlocks.set(Integer.toString(n++),block);selected.set("blocks",keyedBlocks);((ObjectNode)selected.path("page")).set("blocks",keyedBlocks.deepCopy());assertFalse(note(blocks.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(blocks);
    }
    @Test void originalUnclosedGuidanceRemainsIncompleteEvenOnAReviewedPage()throws Exception{Fixture f=new Fixture("paddleocr-vl");String text="(Delete if the optional foundation system is not applicable";f.aside.setText(text);f.aside.setOriginalText(text);f.region(f.aside).put("text",text).put("originalText",text);f.provider(f.aside).put("text",text);f.refresh();JsonNode e=f.analyse();assertTrue(note(e).path("sourceSyntaxKnown").asBoolean());assertFalse(note(e).path("sourceSyntaxComplete").asBoolean());unresolved(f);}
    @Test void selectedRegionTraceCannotPretendToBeAReviewedWholePage()throws Exception{Fixture f=new Fixture("paddleocr-vl");f.region(f.body).put("selectionStatus","source_review_selected_region");f.region(f.aside).put("selectionStatus","source_review_selected_region");f.review().put("selectionStatus","candidate_unverified").putNull("selectedParser");assertFalse(note(f.analyse()).path("sourceSyntaxKnown").asBoolean());unresolved(f);}
}
