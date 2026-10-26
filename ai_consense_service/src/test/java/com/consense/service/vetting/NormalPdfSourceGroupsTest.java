package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.PdfPageEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalPdfSourceGroupsTest {
    private static final String SHA="a".repeat(64),FRAME="normalized_rendered_crop_top_left";

    @Test void decimalWithoutFinalDotKeepsCrossPageChildrenAndStopsAtRealPeerRatherThanReference() {
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(line("head19",19,.10,.02,"Repeated native header"));
        blocks.add(line("prior",19,.16,.15,"Provided that the previous clause applies."));
        blocks.add(line("fourtwo",19,.10,.30,"4.2 Supply of documents"));
        blocks.add(line("one",19,.16,.34,"(1) Supply the copies."));
        blocks.add(line("ref",19,.18,.38,"See Clause 4.3 for the separate duty."));
        blocks.add(line("foot19",19,.80,.96,"7"));
        blocks.add(line("head20",20,.20,.02,"Repeated native header"));
        blocks.add(line("five",20,.26,.10,"(5) Keep one copy."));
        blocks.add(line("continuation",20,.28,.14,"Available for inspection."));
        blocks.add(line("six",20,.26,.18,"(6) Return the copies."));
        blocks.add(line("fourthree",20,.20,.26,"4.3 Contractor documents"));
        JsonNode result=build(blocks),group=group(result,"source_clause","4.2");
        assertTrue(group.path("scopeComplete").asBoolean());
        assertEquals(Arrays.asList("fourtwo","one","ref","five","continuation","six"),strings(group.path("orderedBlockIds")));
        assertEquals("fourthree",group.path("endBoundary").path("blockId").asText());
        assertEquals(group.path("id"),group(result,"source_clause","4.2(5)").path("parentId"));
        assertEquals(Arrays.asList(19,20),JsonUtils.mapper().convertValue(group.path("physicalPageNos"),List.class));
        assertFalse(strings(group.path("orderedBlockIds")).contains("prior"));
        assertEquals(SHA,group.path("sourcePosition").path("sourceSha256").asText());
        assertFalse(group.path("semanticScopeVerified").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());
    }

    @Test void outdentToActualAncestorContentColumnKeepsInspectionTailWithParentAndNamedFormOnlyTargetsB() {
        List<DocumentBlock> blocks=parentTail();blocks.add(form("margin",1,"Clause2b","See Special Conditions","See Special Conditions","See Special Conditions"));
        JsonNode result=build(blocks),parent=group(result,"source_clause","2"),b=group(result,"source_clause","2(b)");
        assertEquals(Arrays.asList("two","a","b","inspection","hours"),strings(parent.path("bodyBlockIds")));
        assertEquals(Collections.singletonList("b"),strings(b.path("bodyBlockIds")));
        JsonNode tail=group(result,"parent_tail","2:tail1");assertEquals(parent.path("id"),tail.path("parentId"));
        assertEquals(Arrays.asList("inspection","hours"),strings(tail.path("bodyBlockIds")));
        JsonNode note=group(result,"source_margin_note","2(b)");assertTrue(note.path("scopeComplete").asBoolean());
        assertEquals(Arrays.asList("b","margin"),strings(note.path("requiredMemberBlockIds")));
        JsonNode edge=relation(result,"source_explicit_note_target");assertEquals(b.path("id"),edge.path("targetGroupId"));
        assertEquals("margin",edge.path("fromBlockId").asText());assertEquals(Collections.singletonList("b"),strings(edge.path("targetBlockIds")));
    }

    @Test void logicalAppearanceAndActualAppearanceGlyphsMustAllAgreeWithoutBorrowingAnotherPage() {
        for(int mode=0;mode<4;mode++) {
            List<DocumentBlock> blocks=parentTail();DocumentBlock margin=form("margin",mode==3?2:1,"Clause2b","See Special Conditions",mode==0?"different":"See Special Conditions",mode==1?"different":"See Special Conditions");
            if(mode==2)margin.getPdfFormField().getAppearanceGlyphs().clear();blocks.add(margin);
            JsonNode result=build(blocks),note=group(result,"source_margin_note","2(b)");
            assertFalse(note.path("scopeComplete").asBoolean(),"mode "+mode);
            assertTrue(note.path("unknownReasons").size()>0);assertEquals(0,countRelations(result,"source_explicit_note_target"));
        }
    }

    @Test void rootNamedNoteTargetsOnlyTheObservedRootNodeNotItsDescendantsOrTail() {
        List<DocumentBlock> blocks=parentTail();blocks.add(form("root-note",1,"Condition2","See the stated condition","See the stated condition","See the stated condition"));
        JsonNode result=build(blocks),note=group(result,"source_margin_note","2");
        assertEquals(Collections.singletonList("two"),strings(note.path("bodyBlockIds")));
        assertEquals(Arrays.asList("two","root-note"),strings(note.path("requiredMemberBlockIds")));
        JsonNode relation=relation(result,"source_explicit_note_target");assertFalse(relation.path("targetIncludesDescendants").asBoolean());
        assertEquals("exact_observed_node_only",relation.path("targetScope").asText());
        assertEquals(Arrays.asList("two","a","b","inspection","hours"),strings(group(result,"source_clause","2").path("bodyBlockIds")));
    }

    @Test void pageCountAloneDoesNotCloseTheLastObservedClause() {
        List<DocumentBlock> blocks=Collections.singletonList(line("only",1,.10,.10,"1. Last observed native clause"));
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("totalPages",1);coverage.put("parsedPages",1);coverage.put("ocrPages",0);coverage.putArray("failedPages");coverage.put("complete",false);
        assertFalse(group(NormalPdfSourceGroups.build(blocks,SHA,coverage),"source_clause","1").path("scopeComplete").asBoolean());
        coverage.put("complete",true);assertTrue(group(NormalPdfSourceGroups.build(blocks,SHA,coverage),"source_clause","1").path("scopeComplete").asBoolean());
    }

    @Test void exactRootNoteWindowDoesNotInheritAmbiguousDescendantScope() {
        List<DocumentBlock> blocks=parentTail();blocks.add(3,line("duplicate",1,.15,.24,"(b) A real alternative body."));
        blocks.add(form("root-note",1,"Clause2","Read the source note","Read the source note","Read the source note"));
        JsonNode result=build(blocks);assertFalse(group(result,"source_clause","2").path("scopeComplete").asBoolean());
        JsonNode note=group(result,"source_margin_note","2");assertTrue(note.path("scopeComplete").asBoolean());
        assertEquals(Collections.singletonList("two"),strings(note.path("bodyBlockIds")));
    }

    @Test void splitNamedReferenceRetainsOrderedLiteralSpansAndFormsPluralIsNotALocalReference() {
        List<DocumentBlock> blocks=parentTail();blocks.add(3,line("reference-one",1,.17,.22,"Refer to condition"));blocks.add(4,line("reference-two",1,.17,.23,"2(a) above; the Form(s) remain originals."));
        JsonNode result=build(blocks),edge=relation(result,"source_literal_reference");assertEquals(1,countRelations(result,"source_literal_reference"));
        assertEquals("2(a)",edge.path("targetReference").asText());assertEquals("condition\n2(a)",edge.path("literal").asText());
        assertEquals("utf16_per_source_span",edge.path("offsetUnit").asText());assertFalse(edge.has("startOffset"));assertEquals(2,edge.path("sourceSpans").size());
        assertEquals("reference-one",edge.path("sourceSpans").get(0).path("blockId").asText());assertEquals("condition",edge.path("sourceSpans").get(0).path("literal").asText());
        assertEquals("reference-two",edge.path("sourceSpans").get(1).path("blockId").asText());assertEquals("2(a)",edge.path("sourceSpans").get(1).path("literal").asText());
    }

    @Test void nativeFormSourceOnlyAppliesToActualBoundWidgetsAndInvalidWidgetDoesNotInvalidateBody() {
        List<DocumentBlock> blocks=parentTail();DocumentBlock widget=form("bad-note",1,"Clause2b","Read source","Read source","Read source");((ObjectNode)widget.getReadingContext()).put("sourceSha256","b".repeat(64));blocks.add(widget);
        assertTrue(group(build(blocks),"source_clause","2").path("scopeComplete").asBoolean());
        DocumentBlock forged=line("forged-line",1,.10,.40,"4. Forged native form line");forged.setSource("native_form");blocks.add(forged);
        JsonNode result=build(blocks);assertTrue(groups(result,"source_clause","4").isEmpty());
        assertTrue(result.path("issues").toString().contains("bad-note"));
    }

    @Test void literalReferenceUsesExactUtf16OffsetsAndNeverCreatesAHeading() {
        List<DocumentBlock> blocks=parentTail();DocumentBlock reference=line("reference",1,.17,.22,"\uD83D\uDE00 Refer to condition 2(a) above.");blocks.add(3,reference);
        JsonNode result=build(blocks);JsonNode edge=relation(result,"source_literal_reference");String literal="condition 2(a)";
        assertEquals("reference",edge.path("fromBlockId").asText());assertEquals("utf16",edge.path("offsetUnit").asText());
        assertEquals(reference.getText().indexOf(literal),edge.path("startOffset").asInt());
        assertEquals(literal,reference.getText().substring(edge.path("startOffset").asInt(),edge.path("endOffset").asInt()));
        assertEquals(group(result,"source_clause","2(a)").path("id"),edge.path("targetGroupId"));
        assertEquals(4,countKind(result,"source_clause"));
    }

    @Test void repeatedClauseLabelsAndUnknownColumnsCannotBecomeCompleteOrNamedTargets() {
        List<DocumentBlock> blocks=parentTail();blocks.add(3,line("duplicate",1,.15,.24,"(b) A real alternative body."));
        blocks.add(form("margin",1,"Clause2b","Same note","Same note","Same note"));
        JsonNode result=build(blocks);List<JsonNode> bs=groups(result,"source_clause","2(b)");assertEquals(2,bs.size());
        for(JsonNode b:bs)assertFalse(b.path("scopeComplete").asBoolean());assertEquals(0,countRelations(result,"source_explicit_note_target"));
        List<DocumentBlock> columns=Arrays.asList(line("one",1,.10,.10,"1. First root"),line("left",1,.15,.20,"Left column"),line("right",1,.70,.20,"Right column"),line("two",1,.10,.30,"2. Next root"));
        JsonNode wide=build(columns);assertFalse(group(wide,"source_clause","1").path("scopeComplete").asBoolean());
        assertTrue(wide.path("issues").toString().contains("column"));assertTrue(wide.path("contextBlocks").toString().contains("left"));assertTrue(wide.path("contextBlocks").toString().contains("right"));
    }

    @Test void independentRepeatedMarkerIntervalsStayCompleteWhileLiteralReferenceRemainsAmbiguous() {
        List<DocumentBlock> blocks=Arrays.asList(line("first-root",1,.10,.10,"4. First observed parent"),line("first",1,.10,.20,"4.2 First local interval"),line("first-peer",1,.10,.30,"4.3 Actual next peer"),line("second-root",2,.10,.10,"4. Another observed parent"),line("second",2,.10,.20,"4.2 Another local interval"),line("second-peer",2,.10,.30,"4.3 See Clause 4.2 for the duty."));
        JsonNode result=build(blocks);List<JsonNode> intervals=groups(result,"source_clause","4.2");assertEquals(2,intervals.size());
        for(JsonNode interval:intervals){assertTrue(interval.path("scopeComplete").asBoolean());assertEquals("ambiguous_repeated_source_reference",interval.path("referenceIdentityStatus").asText());assertEquals(Collections.singletonList("duplicate_source_reference"),strings(interval.path("referenceIdentityUnknownReasons")));assertEquals(0,interval.path("unknownReasons").size());}
        assertEquals("first-peer",intervals.get(0).path("endBoundary").path("blockId").asText());assertEquals("second-peer",intervals.get(1).path("endBoundary").path("blockId").asText());
        assertNotEquals(intervals.get(0).path("parentId"),intervals.get(1).path("parentId"));JsonNode reference=relation(result,"source_literal_reference");assertEquals("unknown",reference.path("status").asText());assertTrue(reference.path("targetGroupId").isNull());assertEquals(0,reference.path("targetBlockIds").size());
        JsonNode roots=build(Arrays.asList(line("root-a",1,.10,.10,"1. First root"),line("root-b",1,.10,.20,"1. Repeated same-page root"),line("root-c",1,.10,.30,"2. Next root")));for(JsonNode root:groups(roots,"source_clause","1"))assertFalse(root.path("scopeComplete").asBoolean());
    }

    @Test void missingPageWrongSourceAndWrongGlyphFrameStayExplicitlyUnknown() {
        List<DocumentBlock> blocks=parentTail();blocks.get(0).getPdfGlyphs().get(0).setCoordinateSpace("appearance_stream_local_matrix_no_page_box");
        ((ObjectNode)blocks.get(1).getReadingContext()).put("sourceSha256","b".repeat(64));blocks.get(2).setPageNo(null);
        JsonNode result=build(blocks);assertTrue(result.path("issues").size()>=3);
        assertTrue(result.path("contextBlocks").toString().contains("two"));
        assertFalse(result.path("groups").toString().contains("\"scopeComplete\":true"));
    }

    @Test void partialFileStillExposesReliableLocalNativeIntervalsAndSeparatedMarkerBlocks() {
        List<DocumentBlock> blocks=Arrays.asList(line("marker",1,.10,.10,"9."),line("body",1,.13,.10,"Observed native body"),line("next",1,.10,.20,"10. Next observed root"));
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",false);coverage.putArray("failedPages").add(9);
        JsonNode result=NormalPdfSourceGroups.build(blocks,SHA,coverage),nine=group(result,"source_clause","9");
        assertTrue(nine.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("marker","body"),strings(nine.path("orderedBlockIds")));
    }

    @Test void activeParentTailKeepsEveryUnresolvedContinuationUnknown() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();
        for(int mode=0;mode<7;mode++){final int current=mode;cases.add(()->{
            List<DocumentBlock> blocks=parentTail();String reason;
            if(current==0){blocks.add(blocks.size()-1,line("unresolved-left",1,.115,.29,"Unexpected column"));blocks.add(blocks.size()-1,line("unresolved-right",1,.70,.29,"Other source text"));reason="same_baseline_column_reading_order_unknown";}
            else if(current==1){blocks.add(blocks.size()-1,line("unresolved-marker",1,.10,.29,"4."));reason="marker_body_unknown";}
            else if(current==2){blocks.add(blocks.size()-1,line("unresolved-paren",1,.05,.29,"(z) Outdented source marker."));reason="unknown_parenthesized_marker";}
            else if(current==3){blocks.add(blocks.size()-1,line("unresolved-continuation",1,.60,.29,"Unaligned source continuation."));reason="continuation_column_has_no_observed_ancestor";}
            else if(current==4){blocks.remove(blocks.size()-1);blocks.add(line("unresolved-next-page",2,.115,.10,"Unproven next-page continuation."));reason="cross_page_native_column_or_continuity_unknown";}
            else if(current==5){blocks.remove(blocks.size()-1);DocumentBlock invalid=line("invalid-next-page",2,.115,.10,"Unverified native continuation.");invalid.getPdfGlyphs().get(0).setCoordinateSpace("unknown_source_frame");blocks.add(invalid);blocks.add(line("next-page-peer",2,.10,.30,"3. Next observed root"));reason="unresolved_native_observations_on_boundary_page";}
            else{DocumentBlock unlocated=line("unlocated-native-line",1,.115,.29,"Native continuation with no physical page.");unlocated.setPageNo(null);blocks.add(blocks.size()-1,unlocated);reason="native_source_observation_physical_page_unknown";}
            ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",true);coverage.put("totalPages",current==4||current==5?2:1);coverage.put("parsedPages",current==4||current==5?2:1);coverage.put("ocrPages",0);coverage.putArray("failedPages");
            JsonNode tail=group(NormalPdfSourceGroups.build(blocks,SHA,coverage),"parent_tail","2:tail1");
            assertFalse(tail.path("scopeComplete").asBoolean(),"active tail case "+current);assertTrue(strings(tail.path("unknownReasons")).contains(reason),"active tail reason "+current);
            assertEquals(Arrays.asList("inspection","hours"),strings(tail.path("bodyBlockIds")),"Retain the known tail observations without adding unknown continuation text");
        });}
        assertAll("Every unresolved active parent-tail branch stays unknown",cases);
    }

    @Test void numberedGuidanceListInsideBracketedNoteDoesNotBecomeClauseRoots() {
        List<DocumentBlock> blocks=new ArrayList<>();
        DocumentBlock root=line("scc87",1,.06,.10,"8.7 Use of the Site by Police and other disciplined services");bold(root);blocks.add(root);
        DocumentBlock open=line("open",1,.10,.14,"[Guidance Note:");bold(open);blocks.add(open);
        for(int i=1;i<=5;i++){DocumentBlock item=line("item"+i,1,.11,.14+i*.02,i+". Guidance item "+i+" continues this note.");bold(item);blocks.add(item);}
        blocks.add(line("close",1,.11,.26,"be amended to (Clause 8.2(5))]"));
        blocks.add(line("body",1,.10,.30,"Clause 8.2 of the General Conditions of Contract is amended."));
        blocks.add(line("next",1,.06,.36,"8.8 Next clause"));
        JsonNode result=build(blocks);
        assertEquals(1,groups(result,"source_clause","8.7").size());
        for(int i=1;i<=5;i++)assertTrue(groups(result,"source_clause",String.valueOf(i)).isEmpty(),"guidance item "+i+" must not become a clause root");
        List<String> members=strings(group(result,"source_clause","8.7").path("orderedBlockIds"));
        assertTrue(members.contains("open"));assertTrue(members.contains("item1"));assertTrue(members.contains("item5"));
    }

    @Test void numberedCrossLineClauseReferenceDoesNotBecomeAHeading() {
        List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(
                line("a",1,.10,.10,"4.2 Supply of documents"),
                line("b",1,.16,.14,"For the avoidance of doubt, inspection is in accordance with Clause"),
                line("c",1,.16,.17,"7.4 of the General Conditions of Contract of any work covered up."),
                line("d",1,.10,.22,"4.3 Contractor documents")));
        JsonNode result=build(blocks);
        assertTrue(groups(result,"source_clause","7.4").isEmpty(),"a cross-line clause reference must not become a heading");
        assertEquals("d",group(result,"source_clause","4.2").path("endBoundary").path("blockId").asText());
    }

    @Test void slTechnicalHeadingsRetainLetteredClauseIdsAndObservedChapterParents() {
        List<DocumentBlock> blocks=new ArrayList<>();
        String[] labels={"FWP3.1.5", "FWP3.1.5.010.9", "FWP3.1.5.030.B", "COM11.D010.9", "TOL.CON3.060.9"};
        for(int i=0;i<labels.length;i++){DocumentBlock heading=line("sl"+i,1,.10,.10+i*.10,labels[i]+" SOURCE HEADING");bold(heading);blocks.add(heading);}
        JsonNode result=build(blocks);
        for(String label:labels){JsonNode observed=group(result,"source_clause",label);assertFalse(observed.path("semanticScopeVerified").asBoolean());}
        JsonNode chapter=group(result,"source_clause",labels[0]);
        assertEquals(chapter.path("id"),group(result,"source_clause",labels[1]).path("parentId"));
        assertEquals(chapter.path("id"),group(result,"source_clause",labels[2]).path("parentId"));
        assertEquals("sl3",chapter.path("endBoundary").path("blockId").asText());
        assertTrue(group(result,"source_clause",labels[1]).path("scopeComplete").asBoolean());
    }

    @Test void slClauseReferencesAndContentsDoNotCreateTechnicalHeadings() {
        DocumentBlock chapter=line("chapter",1,.10,.10,"FWP3.1.5 STRAINERS");bold(chapter);
        DocumentBlock reference=line("reference",1,.15,.15,"FWP3.1.5.030.B is the cited provision.");
        DocumentBlock contents=line("contents",1,.10,.20,"COM11.D010.9 RFID SYSTEM ........ 12");bold(contents);
        DocumentBlock peer=line("peer",1,.10,.30,"FWP3.1.6 NEXT SECTION");bold(peer);
        JsonNode result=build(Arrays.asList(chapter,reference,contents,peer));
        assertTrue(groups(result,"source_clause","FWP3.1.5.030.B").isEmpty());
        assertTrue(groups(result,"source_clause","COM11.D010.9").isEmpty());
        assertEquals("peer",group(result,"source_clause","FWP3.1.5").path("endBoundary").path("blockId").asText());
    }

    @Test void slCivilLabelsShareHeadingReferenceAndNamedFormGrammar() {
        String[] labels={"BRI.D010.9","BRI.D010.10","MAR.M010.9","MAR.M010.10","STW.M010.9","STW.M010.10","GIN.T070.9","GIN.T070.10","TOL.BRI.D010.10","COM7.110","COM11.D010.9","TOL.CON3.060.9"};
        for(String label:labels){
            assertTrue(NormalPdfSourceGroups.technicalLabel(label),label);
            assertEquals(label,NormalPdfSourceGroups.namedFormReference("Clause "+label));
            assertTrue(NormalPdfSourceGroups.referenceLiteralMatches("Clause "+label,label,"unrelated"));
        }
        for(String label:new String[]{"BRI.D010","MAR.M010","STW.M010.","GIN.T070.X","BRI..D010.10","BRI.D010.10junk"}){
            assertFalse(NormalPdfSourceGroups.technicalLabel(label),label);
            assertNull(NormalPdfSourceGroups.namedFormReference("Clause "+label));
        }
        assertTrue(NormalPdfSourceGroups.technicalLabel("COM7"),"Existing printed worksection roots remain supported");
        DocumentBlock first=line("civil",1,.10,.10,"BRI.D010.9 FIRST SOURCE HEADING");bold(first);
        DocumentBlock child=line("civil-child",1,.10,.20,"BRI.D010.9.1 CHILD SOURCE HEADING");bold(child);
        DocumentBlock next=line("civil-next",1,.10,.30,"MAR.M010.10 NEXT SOURCE HEADING");bold(next);
        JsonNode result=build(Arrays.asList(first,child,next)),parent=group(result,"source_clause","BRI.D010.9");
        assertEquals(parent.path("id"),group(result,"source_clause","BRI.D010.9.1").path("parentId"));
        assertEquals("civil-next",parent.path("endBoundary").path("blockId").asText());
        assertTrue(parent.path("scopeComplete").asBoolean());
    }

    @Test void slAppendixLabelsAndAttachedTitlesRemainDistinctFromBodyReferences() {
        for(String label:new String[]{"COM11.APPEND1.9","COM11.APPEND10.9","AP1.A010.B"}){
            assertTrue(NormalPdfSourceGroups.technicalLabel(label),label);
            assertEquals(label,NormalPdfSourceGroups.namedFormReference("Clause "+label));
        }
        DocumentBlock prior=line("prior",1,.10,.10,"COM11.T030.9 PROCEDURE");bold(prior);
        DocumentBlock appendix=line("appendix",1,.10,.20,"COM11.APPEND1.9CONCEPTUAL WORKFLOW");bold(appendix);
        DocumentBlock reference=line("reference",1,.15,.25,"COM5.M090 for CM's information.");
        DocumentBlock standards=line("standards",1,.10,.30,"AP1.A010.B LIST OF TECHNICAL STANDARDS");bold(standards);
        JsonNode result=build(Arrays.asList(prior,appendix,reference,standards));
        assertEquals(1,groups(result,"source_clause","COM11.APPEND1.9").size());
        assertEquals(1,groups(result,"source_clause","AP1.A010.B").size());
        assertTrue(groups(result,"source_clause","COM5.M090").isEmpty());
    }

    @Test void slCivilCitationsAndWrappedContentsDoNotBecomeClauses() {
        DocumentBlock first=line("first",1,.10,.10,"BRI.D010.10 FIRST SOURCE HEADING");bold(first);
        DocumentBlock reference=line("reference",1,.160,.15,"GIN.T070.10 is the cited provision.");
        DocumentBlock wrapped=line("wrapped",1,.10,.20,"MAR.M010.10 WRAPPED CONTENTS TITLE");bold(wrapped);
        DocumentBlock leader=line("leader",1,.10,.23,"CONTENTS CONTINUATION ........ 12");
        DocumentBlock peer=line("peer",1,.10,.30,"STW.M010.10 REAL SOURCE HEADING");bold(peer);
        DocumentBlock contents=line("contents",1,.10,.35,"GIN.T080.10 OTHER CONTENTS ........ 13");bold(contents);
        DocumentBlock last=line("last",1,.10,.40,"STW.M020.10 NEXT SOURCE HEADING");bold(last);
        JsonNode result=build(Arrays.asList(first,reference,wrapped,leader,peer,contents,last));
        for(String label:new String[]{"GIN.T070.10","MAR.M010.10","GIN.T080.10"})assertTrue(groups(result,"source_clause",label).isEmpty(),label);
        assertEquals(Arrays.asList("first","reference"),strings(group(result,"source_clause","BRI.D010.10").path("orderedBlockIds")));
        assertEquals("peer",group(result,"source_clause","BRI.D010.10").path("endBoundary").path("blockId").asText());
        assertEquals("last",group(result,"source_clause","STW.M010.10").path("endBoundary").path("blockId").asText());
    }

    @Test void slContentsPagePreservesContextAndInterruptsPreviousClauseScope() {
        for(String title:new String[]{"TABLE OF CONTENTS","Civil Engineering > Table of Contents"}){
            DocumentBlock first=line("first",1,.10,.10,"BRI.D010.9 FIRST SOURCE HEADING");bold(first);
            DocumentBlock contents=line("contents",2,.10,.20,"MAR.M010.10 CONTENTS TITLE WITHOUT LEADERS");bold(contents);
            DocumentBlock next=line("next",3,.10,.10,"STW.M010.10 NEXT SOURCE HEADING");bold(next);
            JsonNode result=build(Arrays.asList(first,line("contents-title",2,.10,.05,title),contents,line("contents-detail",2,.165,.25,"Unnumbered contents text"),next));
            assertTrue(groups(result,"source_clause","MAR.M010.10").isEmpty());
            JsonNode previous=group(result,"source_clause","BRI.D010.9");
            assertEquals(Collections.singletonList("first"),strings(previous.path("orderedBlockIds")));
            assertEquals("contents-title",previous.path("endBoundary").path("blockId").asText());
            assertFalse(previous.path("scopeComplete").asBoolean());
            assertEquals(3,result.path("contextBlocks").size());
            for(JsonNode context:result.path("contextBlocks"))assertEquals("native_table_of_contents_page_context",context.path("role").asText());
            group(result,"source_clause","STW.M010.10");
        }
    }

    @Test void slClauseContinuationKeepsAdjacentTextAndRejectsMissingPages() {
        for(int page:new int[]{2,3}){
            DocumentBlock first=line("first",1,.10,.10,"COM11.D010.9 RFID SYSTEM");bold(first);
            DocumentBlock next=line("next",page,.10,.30,"COM11.D020.9 MAIN COMPONENTS");bold(next);
            List<DocumentBlock> blocks=Arrays.asList(first,line("header1",1,.10,.03,"TECHNICAL SOURCE"),
                    line("body",1,.165,.16,"The specified condition continues"),line("header2",page,.10,.03,"TECHNICAL SOURCE"),
                    line("continued",page,.165,.10,"on the following physical page."),next);
            JsonNode clause=group(build(blocks),"source_clause","COM11.D010.9");
            if(page==2){assertTrue(clause.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("first","body","continued"),strings(clause.path("orderedBlockIds")));assertEquals("next",clause.path("endBoundary").path("blockId").asText());}
            else{assertFalse(clause.path("scopeComplete").asBoolean());assertFalse(strings(clause.path("orderedBlockIds")).contains("continued"));}
        }
    }

    @Test void slOrdinaryChunksUseOnlyBoundCompleteTechnicalIntervals() {
        DocumentBlock first=line("first",1,.10,.10,"COM11.D010.9 RFID SYSTEM");bold(first);first.setLocation("pdf-page/1/line/0");
        DocumentBlock next=line("next",2,.10,.30,"COM11.D020.9 MAIN COMPONENTS");bold(next);next.setLocation("pdf-page/2/line/1");
        List<DocumentBlock> blocks=Arrays.asList(first,line("body",1,.165,.16,"The specified condition continues"),
                line("continued",2,.165,.10,"on the following physical page."),next);
        com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();
        source.setId(42L);source.setProjectId("authored-sl");source.setFileKey("SL");source.setFileName("SL-authored.pdf");source.setReviewRole("standard");
        source.setSourceSha256(SHA);source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent("Authored technical intervals");source.setParseCoverageJson("{\"complete\":false}");
        NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        for(VettingCorpus.Chunk chunk:chunks){
            if(chunk.getContent().contains("MAIN COMPONENTS")){assertNull(chunk.getClauseId(),"The incomplete last interval cannot inherit the preceding technical clause");continue;}
            assertEquals("COM11.D010.9",chunk.getClauseId());assertEquals(first.getLocation(),chunk.getClauseHeadingLocation());
            assertEquals(VettingCorpus.METADATA_VERSION,chunk.getMetadataVersion());
            assertNull(chunk.getCanonicalEvidence());
        }
        source.setNormalCandidateJson(null);
        for(VettingCorpus.Chunk chunk:VettingCorpus.chunks(Collections.singletonList(source)))assertNull(chunk.getClauseId(),"A filename or free-standing label cannot replace source-bound native intervals");
    }

    @Test void slOrdinaryChunksDoNotExtendBeyondTheObservedInterval() {
        DocumentBlock first=line("first",1,.10,.10,"COM11.D010.9 RFID SYSTEM");bold(first);first.setLocation("first");
        DocumentBlock peer=line("peer",1,.10,.20,"1. Separate observed section");peer.setLocation("peer");
        DocumentBlock next=line("next",1,.10,.30,"COM11.D020.9 MAIN COMPONENTS");bold(next);next.setLocation("next");
        List<DocumentBlock> blocks=Arrays.asList(first,line("body",1,.165,.15,"Technical condition."),peer,line("outside",1,.115,.25,"Separate source content."),next);
        com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId(43L);source.setFileKey("SL");source.setFileName("SL-authored.pdf");
        source.setSourceSha256(SHA);source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent("Authored separate source intervals");source.setParseCoverageJson("{\"complete\":false}");
        NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        for(VettingCorpus.Chunk chunk:chunks)if(chunk.getContent().contains("Separate"))assertNull(chunk.getClauseId(),"Content beyond an explicit native interval end cannot inherit its clause identifier");
        ObjectNode stale=(ObjectNode)JsonUtils.parse(source.getNormalCandidateJson());stale.remove("nativePdfSourceGroups");source.setNormalCandidateJson(JsonUtils.write(stale));
        for(VettingCorpus.Chunk chunk:VettingCorpus.chunks(Collections.singletonList(source)))assertNull(chunk.getClauseId(),"A stale stored graph must be reconstructed before it can assign technical intervals");
    }

    @Test void slNumberedItemsRemainInsideTheIndentedTechnicalClause() {
        DocumentBlock first=line("first",1,.10,.10,"COM11.D010.9 RFID SYSTEM");bold(first);
        DocumentBlock next=line("next",1,.10,.35,"COM11.D020.9 MAIN COMPONENTS");bold(next);
        List<DocumentBlock> blocks=Arrays.asList(first,line("one",1,.165,.15,"1. First requirement"),line("body",1,.18,.20,"Observed continuation."),line("two",1,.165,.25,"2. Second requirement"),next);
        JsonNode result=build(blocks),clause=group(result,"source_clause","COM11.D010.9");
        assertEquals(Arrays.asList("first","one","body","two"),strings(clause.path("orderedBlockIds")));
        assertTrue(clause.path("scopeComplete").asBoolean());assertEquals("next",clause.path("endBoundary").path("blockId").asText());
        assertEquals(clause.path("id"),group(result,"source_clause","1").path("parentId"));
        assertEquals(clause.path("id"),group(result,"source_clause","2").path("parentId"));
    }

    @Test void slOrdinaryChunksReturnToObservedChapterTailAndPreserveOtherDocumentIds() {
        DocumentBlock chapter=line("chapter",1,.10,.10,"COM11 RFID SYSTEMS");bold(chapter);
        DocumentBlock child=line("child",1,.10,.18,"COM11.D010.9 COMPONENTS");bold(child);
        DocumentBlock peer=line("peer",1,.10,.35,"COM12 OTHER SYSTEMS");bold(peer);
        List<DocumentBlock> blocks=Arrays.asList(chapter,line("intro",1,.13,.14,"Source introduction."),child,
                line("detail",1,.165,.22,"Component requirement."),line("tail1",1,.13,.26,"Chapter content resumes"),line("tail2",1,.13,.28,"with another source line."),peer);
        for(DocumentBlock block:blocks)block.setLocation(block.getId());
        com.consense.domain.SourceDocument source=new com.consense.domain.SourceDocument();source.setId(44L);source.setFileKey("SL");source.setFileName("SL-authored.pdf");
        source.setSourceSha256(SHA);source.setStructuredContentJson(JsonUtils.write(blocks));source.setTextContent("Authored chapter and child intervals");source.setParseCoverageJson("{\"complete\":false}");NormalSourceCandidateGraph.capture(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));
        assertEquals(4,chunks.size());assertEquals("COM11",chunks.get(0).getClauseId());assertEquals(2,chunks.get(0).getParts().size());
        assertEquals("COM11.D010.9",chunks.get(1).getClauseId());assertEquals(2,chunks.get(1).getParts().size());
        assertEquals("COM11",chunks.get(2).getClauseId());assertEquals("chapter",chunks.get(2).getClauseHeadingLocation());assertEquals(2,chunks.get(2).getParts().size());assertNull(chunks.get(3).getClauseId());
        source.setFileKey("SCC");
        for(VettingCorpus.Chunk chunk:VettingCorpus.chunks(Collections.singletonList(source))){
            String ids=chunk.getParts().stream().map(VettingCorpus.Part::getBlockId).collect(java.util.stream.Collectors.joining(","));
            String oldSeed=VettingCorpus.METADATA_VERSION+"|"+VettingCorpus.SEGMENTATION_VERSION+"|"+VettingCorpus.NATIVE_TABLE_METADATA_VERSION+"|"+VettingCorpus.SOURCE_QUALITY_METADATA_VERSION+"|"+chunk.getSourceQualityHash()+"|"+chunk.getRole()+"|"+source.getId()+"|"+chunk.getSourceHash()+"|"+ids;
            assertEquals(UUID.nameUUIDFromBytes(oldSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),chunk.getId());
        }
    }

    @Test void resolvedSymbolTextWithLayoutWhitespaceRemainsAValidSourceLine() {
        DocumentBlock first=line("first",1,.10,.10,"COM11.D010.9 SYMBOL CONDITION");bold(first);
        DocumentBlock body=line("body",1,.165,.15,"\u03A6 \t\u2264");
        DocumentBlock next=line("next",1,.10,.30,"COM11.D020.9 NEXT CONDITION");bold(next);
        for(PdfPageEvidence.PdfGlyph glyph:body.getPdfGlyphs())if("\u03A6".equals(glyph.getUnicode())||"\u2264".equals(glyph.getUnicode())){
            String raw="\u03A6".equals(glyph.getUnicode())?"\uF046":"\uF0A3";
            glyph.setUnicode(raw);glyph.setCharacterCodes(new int[]{41});glyph.setFontName("Symbol");glyph.setFontSubtype("Type0");glyph.setFontEmbedded(true);
            PdfPageEvidence.TextResolution resolution=new PdfPageEvidence.TextResolution();resolution.setRawUnicode(raw);resolution.setSourceCharacterCodes(new int[]{41});resolution.setFontName("Symbol");
            resolution.setSelector(String.format(Locale.ROOT,"%04X",(int)raw.charAt(0)));resolution.setDecodedText(PdfPageEvidence.TextResolution.symbolText(raw));resolution.setResolutionStatus(PdfPageEvidence.TextResolution.RESOLVED);resolution.setMappingSource(PdfPageEvidence.TextResolution.MAPPING_SOURCE);
            resolution.setFontProgramSha256(SHA);resolution.setToUnicodeSha256(SHA);resolution.setSourceGlyphId(41);resolution.setSelectorGlyphId(41);resolution.setCmapPlatformId(3);resolution.setCmapEncodingId(0);glyph.setTextResolution(resolution);
        }
        JsonNode result=build(Arrays.asList(first,body,next));
        assertEquals(Arrays.asList("first","body"),strings(group(result,"source_clause","COM11.D010.9").path("orderedBlockIds")));
        assertTrue(group(result,"source_clause","COM11.D010.9").path("scopeComplete").asBoolean());
        assertEquals("\uF046",body.getPdfGlyphs().get(0).getUnicode());assertEquals("\u03A6 \t\u2264",body.getText());
    }

    private static void bold(DocumentBlock block){block.getPdfGlyphs().forEach(glyph->glyph.setFontName("Times New Roman,Bold"));}
    private List<DocumentBlock> parentTail(){return new ArrayList<>(Arrays.asList(line("two",1,.10,.10,"2. Documents not issued:"),line("a",1,.15,.15,"(a) Available documents."),line("b",1,.15,.20,"(b) Other drawings."),line("inspection",1,.115,.25,"These documents may be inspected"),line("hours",1,.115,.27,"during normal office hours."),line("three",1,.10,.32,"3. Next root")));}
    private JsonNode build(List<DocumentBlock> blocks){ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",false);return NormalPdfSourceGroups.build(blocks,SHA,coverage);}
    private DocumentBlock line(String id,int page,double x,double y,String text){DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind("pdf_line");block.setSource("native");block.setPageNo(page);block.setText(text);block.setOriginalText(text);block.setBbox(new double[]{x,y,text.codePointCount(0,text.length())*.005,.01});ObjectNode context=JsonUtils.mapper().createObjectNode();context.put("sourceSha256",SHA);block.setReadingContext(context);int offset=0,n=0;while(offset<text.length()){int point=text.codePointAt(offset);String value=new String(Character.toChars(point));PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId("pdf-page/"+page+"/glyph/"+id+"/"+n);glyph.setUnicode(value);glyph.setCoordinateSpace(FRAME);glyph.setEligibleForNativeText(true);glyph.setBbox(new double[]{x+n*.005,y,.005,.01});glyph.setTextMatrix(new double[]{10,0,0,10,(x+n*.005)*1000,(1-y)*1000});glyph.setFontSize(10);glyph.setFontSizeInPt(10);glyph.setPdfboxPosition(new double[]{0,0,0,0,0,0,0,0,1000,1000});block.getPdfGlyphs().add(glyph);offset+=value.length();n++;}return block;}
    private DocumentBlock form(String id,int page,String name,String value,String appearance,String display){DocumentBlock block=line(id,page,.02,.20,value);block.setKind("pdf_form_widget");block.setSource("native_form");block.getPdfGlyphs().clear();PdfPageEvidence.PdfFormField field=new PdfPageEvidence.PdfFormField();field.setId("pdf-form/"+id+"/widget/0");field.setFieldName(name);field.setFieldValue(value);field.setAppearanceValue(appearance);field.setPageNo(page);field.setAppearancePresent(true);field.setAppearanceGlyphObservationStatus("observed_existing_normal_AP_operators_unreviewed");PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId(field.getId()+"/glyph/0");glyph.setUnicode(display);glyph.setCoordinateSpace("appearance_stream_local_matrix_no_page_box");glyph.setTextMatrix(new double[]{8,0,0,8,2,20});glyph.setEligibleForNativeText(true);field.getAppearanceGlyphs().add(glyph);block.setPdfFormField(field);return block;}
    private List<String> strings(JsonNode values){List<String> out=new ArrayList<>();values.forEach(value->out.add(value.asText()));return out;}
    private List<JsonNode> groups(JsonNode value,String kind,String reference){List<JsonNode> out=new ArrayList<>();for(JsonNode group:value.path("groups"))if(kind.equals(group.path("kind").asText())&&reference.equals(group.path("reference").asText()))out.add(group);return out;}
    private JsonNode group(JsonNode value,String kind,String reference){List<JsonNode> out=groups(value,kind,reference);assertEquals(1,out.size(),kind+":"+reference);return out.get(0);}
    private int countKind(JsonNode value,String kind){int count=0;for(JsonNode group:value.path("groups"))if(kind.equals(group.path("kind").asText()))count++;return count;}
    private JsonNode relation(JsonNode value,String type){for(JsonNode relation:value.path("relations"))if(type.equals(relation.path("type").asText()))return relation;fail("Missing "+type);return null;}
    private int countRelations(JsonNode value,String type){int count=0;for(JsonNode relation:value.path("relations"))if(type.equals(relation.path("type").asText()))count++;return count;}
}
