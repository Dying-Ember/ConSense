package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Synthetic originals and saved source observations; no parser, model, API, database or browser. */
class DraftDiscoveryNativeProjectionTest {
    private static final String NOTE="Delete this complete alternative when it does not apply.";

    @Test void aFullNormalizedParagraphProjectsToOriginalTabsAndSpacesAndBindsAtSave() throws Exception {
        String raw="\t*(e)\tThe tender uses precast  concrete facades. \t";
        Fixture fixture=fixture(raw);String before=JsonUtils.write(fixture.reading);JsonNode target=target(fixture,effect());
        assertEquals(raw,target.path("text").asText());assertEquals("delete",target.path("action").asText());
        assertTrue(target.path("scopeComplete").asBoolean());assertEquals("missing",target.path("nativeBinding").path("status").asText());
        JsonNode span=target.path("native").path("spans").path(0);assertEquals(0,span.path("start").asInt());assertEquals(raw.length(),span.path("end").asInt());assertEquals(raw,span.path("expectedText").asText());
        assertEquals(fixture.reading.path("groups").path(0),target.path("sourceScopeObservation"));assertEquals(before,JsonUtils.write(fixture.reading));
        assertEquals("verified_parser_text_to_editable_text",target.path("nativeScopeProjection").path("kind").asText());assertBound(fixture,target);
    }

    @Test void multiParagraphScopeRetainsVerifiedEmptyLayoutWithoutCreatingInvalidZeroLengthEdits() throws Exception {
        Fixture fixture=fixture("\tFirst  paragraph.","","Second\tparagraph. \t");ObjectNode scope=(ObjectNode)fixture.reading.path("groups").path(0);scope.remove("memberRanges");scope.putArray("orderedMemberIds").add("member-1").add("member-2").add("member-3");JsonNode target=target(fixture,effect());
        assertEquals("\tFirst  paragraph.\nSecond\tparagraph. \t",target.path("text").asText());assertEquals(2,target.path("native").path("spans").size());
        assertEquals(3,target.path("sourceScopeObservation").path("orderedMemberIds").size());
        assertEquals("member-2",target.path("nativeScopeProjection").path("emptyLayoutMemberIds").path(0).asText());assertFalse(target.path("nativeScopeProjection").path("layoutCleanupIncluded").asBoolean());
        assertBound(fixture,target);
        ObjectNode forged=((ObjectNode)target).deepCopy();ObjectNode emptySpan=((ObjectNode)forged.path("native").path("spans").path(0)).deepCopy();
        DocxTemplateEditor.Paragraph empty=fixture.index.mainParagraph(3);emptySpan.put("paragraphId",empty.getId()).put("paragraphSha256",empty.getSha256()).put("start",0).put("end",0).put("expectedText","");
        ((ObjectNode)forged.path("native")).putArray("spans").add(emptySpan);forged.put("text","");
        assertEquals("NATIVE_TEXT_EXPECTATION_MISMATCH",bind(fixture,forged).path("reason").asText());
    }

    @Test void explicitObservedOffsetsSelectTheSecondOccurrenceWithoutSearchingOrExpandingIt() throws Exception {
        String raw="\tPrefix😀\tTarget  value then Target\tvalue suffix. \t";
        Fixture fixture=fixture(raw);String observed=normalized(raw),selected="Target value";int start=observed.lastIndexOf(selected);
        range(fixture,0).put("start",start).put("end",start+selected.length());JsonNode target=target(fixture,effect());
        JsonNode span=target.path("native").path("spans").path(0);int nativeStart=raw.lastIndexOf("Target\tvalue");
        assertEquals(nativeStart,span.path("start").asInt());assertEquals(nativeStart+"Target\tvalue".length(),span.path("end").asInt());assertEquals("Target\tvalue",target.path("text").asText());assertBound(fixture,target);
    }

    @Test void fullSourceOrIdentityMismatchAndOtherNamedChannelsNeverAcquireNativeCoordinates() throws Exception {
        for(String defect:Arrays.asList("word-change","punctuation","source-sha","path","range-position","part-sha","duplicate-native","channel")) {
            Fixture fixture=fixture("\tTarget  value.");ObjectNode member=(ObjectNode)fixture.reading.path("sourceMembers").path(1),paragraph=(ObjectNode)fixture.reading.path("paragraphs").path(1);
            switch(defect) {
                case "word-change":((ObjectNode)member.path("textObservation")).put("text","Target other.");range(fixture,0).put("end",13);break;
                case "punctuation":((ObjectNode)member.path("textObservation")).put("text","Target value!");break;
                case "source-sha":((ObjectNode)member.path("sourcePosition")).put("sourceSha256","0".repeat(64));break;
                case "path":((ObjectNode)paragraph.path("sourcePosition")).put("nodePath","/foreign");break;
                case "range-position":((ObjectNode)range(fixture,0).path("sourcePosition")).put("partSha256","0".repeat(64));break;
                case "part-sha":((ObjectNode)paragraph.path("sourcePosition")).put("partSha256","0".repeat(64));break;
                case "duplicate-native":fixture.reading.withArray("paragraphs").add(paragraph.deepCopy());break;
                case "channel":((ObjectNode)member.path("textObservation")).set("originalText",member.path("textObservation").path("text"));range(fixture,0).put("channel","originalText");break;
            }
            JsonNode target=target(fixture,effect());assertEquals("check",target.path("action").asText(),defect);assertFalse(target.has("native"),defect);assertTrue(target.path("needsRebind").asBoolean(),defect);
        }
    }

    @Test void refinementUsesTheSameProjectionAndRemainsASeparateHumanReviewCandidate() throws Exception {
        Fixture fixture=fixture("\tKeep the Target  value and remaining prose. \t");Map<String,Object> effect=effect();effect.put("refinedQuote","Target value");JsonNode target=target(fixture,effect);
        assertEquals("check",target.path("action").asText());assertTrue(target.path("observedScopeComplete").asBoolean());
        JsonNode candidate=target.path("refinementCandidate");assertEquals("pending",candidate.path("status").asText());assertFalse(candidate.path("editableAuthority").asBoolean());assertEquals("Target  value",candidate.path("text").asText());
        assertEquals("\tKeep the Target  value and remaining prose. \t",target.path("text").asText());assertBound(fixture,target);assertBound(fixture,candidate);
        fixture=fixture("Target  value and Target\tvalue");target=target(fixture,effect);assertFalse(target.has("refinementCandidate"));assertEquals("UNIQUE_SOURCE_BOUND_SUBRANGE_REQUIRED",target.path("refinementIssue").asText());
    }

    @Test void aFullObservedScopeEchoDoesNotInventAnAdditionalCharacterRefinementReview() throws Exception {
        String raw="*(e)\tThe tender shall be based on the use of precast concrete facades for constructing the external facades to the domestic flat units unless otherwise specified in the Specification or shown on the Drawings.";
        Fixture fixture=fixture(raw);Map<String,Object> effect=effect();effect.put("refinedQuote",normalized(raw));String original=JsonUtils.write(effect);JsonNode target=target(fixture,effect);
        assertEquals("delete",target.path("action").asText());assertTrue(target.path("scopeComplete").asBoolean());assertFalse(target.path("needsRebind").asBoolean());assertEquals(raw,target.path("text").asText());
        assertEquals("complete_observed_scope_echo",target.path("refinementDisposition").asText());assertFalse(target.has("refinementCandidate"));assertFalse(target.has("refinementIssue"));assertEquals(original,JsonUtils.write(effect));assertBound(fixture,target);
        ((ObjectNode)fixture.reading.path("groups").path(0)).put("scopeComplete",false);target=target(fixture,effect);assertEquals("check",target.path("action").asText());assertEquals("complete_observed_scope_echo",target.path("refinementDisposition").asText());assertFalse(target.has("refinementIssue"));
    }

    @Test void aCompleteScopeEchoWithMismatchedNativeIdentityStaysCheckWithoutARefinementError() throws Exception {
        String raw="*(e)\tThe tender shall be based on precast concrete facades.";Fixture fixture=fixture(raw);Map<String,Object> effect=effect();effect.put("refinedQuote",normalized(raw));
        ((ObjectNode)fixture.reading.path("paragraphs").path(1).path("sourcePosition")).put("nodePath","/foreign-native-paragraph");JsonNode target=target(fixture,effect);
        assertEquals("check",target.path("action").asText());assertFalse(target.path("scopeComplete").asBoolean());assertTrue(target.path("needsRebind").asBoolean());assertFalse(target.has("native"));
        assertEquals("complete_observed_scope_echo",target.path("refinementDisposition").asText());assertFalse(target.has("refinementCandidate"));assertFalse(target.has("refinementIssue"));assertEquals(normalized(raw),target.path("text").asText());
    }

    @Test void collapsedLineBreaksRequireAnUnambiguousBoundaryAndNeverBecomeSpaces() throws Exception {
        Fixture fixture=fixture("one\n\n\ntwo");JsonNode complete=target(fixture,effect());assertEquals("one\n\n\ntwo",complete.path("text").asText());
        assertEquals("UNSUPPORTED_INLINE_FEATURE",bind(fixture,complete).path("reason").asText(),"A coordinate projection does not bypass the editor's native break guard");
        range(fixture,0).put("start",4).put("end",8);JsonNode ambiguous=target(fixture,effect());assertEquals("check",ambiguous.path("action").asText());assertFalse(ambiguous.has("native"));
        fixture=fixture("one\ntwo");((ObjectNode)fixture.reading.path("sourceMembers").path(1).path("textObservation")).put("text","one two");
        JsonNode mismatched=target(fixture,effect());assertEquals("check",mismatched.path("action").asText());assertFalse(mismatched.has("native"));
    }

    @Test void explicitWrongQuoteAndNormalizedInsertionNeverGainAuthority() throws Exception {
        Fixture fixture=fixture("\tTarget  value. ");Map<String,Object> effect=effect();effect.put("quote","Target wrong.");JsonNode target=target(fixture,effect);
        assertEquals("check",target.path("action").asText());assertEquals("Target wrong.",target.path("text").asText());assertFalse(target.has("native"));assertEquals("EXPLICIT_SCOPE_QUOTE_MISMATCH",target.path("scopeQuoteIssue").asText());
        ObjectNode scope=(ObjectNode)fixture.reading.path("groups").path(0);ObjectNode anchor=range(fixture,0).deepCopy();scope.remove("memberRanges");scope.put("sourceInsertionAnchorComplete",true).put("scopeComplete",false);anchor.put("start",0).put("end",0).put("offsetUnit","utf16");scope.putArray("insertionAnchors").add(anchor);
        effect=effect();effect.put("action","insert");target=target(fixture,effect);assertEquals("check",target.path("action").asText());assertFalse(target.has("native"));
    }

    @Test void preNormalizedTextKeepsItsExistingExactOffsetsAndTerminalNewlineIsNotEditable() throws Exception {
        String raw="\tTarget  value. ";Fixture fixture=fixture(raw);ObjectNode member=(ObjectNode)fixture.reading.path("sourceMembers").path(1);
        member.putObject("preNormalizedTextObservation").put("status","observed_parser_channels_before_whitespace_normalization").put("text",raw+"\n");range(fixture,0).put("channel","preNormalizedTextObservation.text").put("end",raw.length());
        JsonNode exact=target(fixture,effect());assertEquals(raw,exact.path("text").asText());assertBound(fixture,exact);
        range(fixture,0).put("end",raw.length()+1);JsonNode unsafe=target(fixture,effect());assertEquals("check",unsafe.path("action").asText());assertFalse(unsafe.has("native"));
    }

    private static Fixture fixture(String... bodies) throws Exception {
        byte[] bytes;try(XWPFDocument document=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            List<String> texts=new ArrayList<>();texts.add(NOTE);texts.addAll(Arrays.asList(bodies));
            for(String text:texts){XWPFRun run=document.createParagraph().createRun();StringBuilder part=new StringBuilder();for(char c:text.toCharArray()){if(c=='\t'||c=='\n'){if(part.length()>0){run.setText(part.toString());part.setLength(0);}if(c=='\t')run.addTab();else run.addBreak();}else part.append(c);}if(part.length()>0)run.setText(part.toString());}
            document.write(out);bytes=out.toByteArray();
        }
        DocxTemplateEditor.TemplateIndex index=new DocxTemplateEditor().inspect(bytes);String sha=index.getSourceSha256();
        ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("format","docx").put("fileKey","NTT").put("sourceSha256",sha).put("evidenceStatus","bound");
        for(String field:Arrays.asList("sourceMembers","paragraphs","guidanceNotes","markerCandidates","groups","conditions"))source.putArray(field);
        ObjectNode scope=source.withArray("groups").addObject().put("id","scope").put("guidanceNoteId","note").put("scopeComplete",true);scope.putArray("memberRanges");
        for(int i=0;i<index.getMainParagraphs().size();i++) {
            DocxTemplateEditor.Paragraph paragraph=index.getMainParagraphs().get(i);String id="member-"+i,observed=normalized(paragraph.getText());
            ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id);ObjectNode position=member.putObject("sourcePosition").put("sourceSha256",sha).put("partName",paragraph.getPart()).put("partSha256",index.getEntryHashes().get(paragraph.getPart())).put("nodePath",paragraph.getNativeNodePath());member.putObject("textObservation").put("text",observed);
            ObjectNode current=source.withArray("paragraphs").addObject().put("id",paragraph.getId()).put("text",paragraph.getText()).put("sourceSha256",sha).put("paragraphSha256",paragraph.getSha256());current.set("sourcePosition",position.deepCopy());
            if(i>0){ObjectNode range=scope.withArray("memberRanges").addObject().put("memberId",id).put("channel","text").put("start",0).put("end",observed.length());range.set("sourcePosition",position.deepCopy());}
        }
        source.withArray("guidanceNotes").addObject().put("id","note").put("text",NOTE).put("captureComplete",true);return new Fixture(bytes,index,source);
    }
    private static String normalized(String raw){return raw.replace("\r\n","\n").replace('\r','\n').replace('\u00a0',' ').replaceAll("[ \\t]+"," ").replaceAll("\n{3,}","\n\n").trim();}
    private static ObjectNode range(Fixture fixture,int i){return (ObjectNode)fixture.reading.path("groups").path(0).path("memberRanges").path(i);}
    private static Map<String,Object> effect(){return obj("scopeId","scope","action","delete");}
    private static JsonNode target(Fixture fixture,Map<String,Object> effect){
        Map<String,Object> task=DraftReviewDiscovery.sourceTasks(Collections.singletonList(fixture.reading)).stream().filter(value->"note".equals(value.get("sourceObservationId"))).findFirst().orElseThrow();
        JsonNode response=JsonUtils.mapper().valueToTree(obj("nodes",Collections.singletonList(obj("id","fact","type","boolean","role","input","nameEn","Project alternative","sourceQuote",NOTE,"options",Collections.emptyList(),"branches",Collections.emptyList(),"effects",Collections.singletonList(effect)))));
        List<Object> rejected=new ArrayList<>();List<Map<String,Object>> nodes=DraftReviewDiscovery.sourceTaskNodes(fixture.reading,task,response,rejected);assertTrue(rejected.isEmpty(),JsonUtils.write(rejected));assertEquals(1,nodes.size());return JsonUtils.mapper().valueToTree(nodes.get(0)).path("editTargets").path(0);
    }
    private static JsonNode bind(Fixture fixture,JsonNode target){return DraftReviewSources.bindTarget(fixture.bytes,JsonUtils.mapper().convertValue(target,Map.class));}
    private static void assertBound(Fixture fixture,JsonNode target){JsonNode binding=bind(fixture,target);assertEquals("bound",binding.path("status").asText(),binding.toString());}
    private static final class Fixture {final byte[] bytes;final DocxTemplateEditor.TemplateIndex index;final ObjectNode reading;Fixture(byte[] bytes,DocxTemplateEditor.TemplateIndex index,ObjectNode reading){this.bytes=bytes;this.index=index;this.reading=reading;}}
}
