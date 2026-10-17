package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OerTemplateEvidenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a"));
    private static final String OTHER=String.join("",Collections.nCopies(64,"b"));
    private static final String BODY="/{w}document[1]/{w}body[1]";

    @Test void currentInputsBindTheEntireEvidenceAndChangedInputOrPayloadIsRejected() {
        DocumentParser.ParsedDocument parsed=word(p("p1","Special Conditions of Contract"),p("p2","[Guidance Note: Insert the amount.]"));
        JsonNode evidence=OerTemplateEvidence.analyse(null,parsed),blocks=tree(parsed.getBlocks()),coverage=tree(parsed.getCoverage());
        assertEquals("SCC",evidence.path("sourceBinding").path("fileKey").asText());
        assertEquals(evidence,OerTemplateEvidence.requireBound("SCC",SHA,blocks,coverage,evidence));
        ObjectNode persisted=coverage.deepCopy();persisted.set("templateEvidence",evidence);assertEquals(evidence,OerTemplateEvidence.requireBound("SCC",SHA,blocks,persisted,evidence));
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("NTT",SHA,blocks,coverage,evidence));
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",OTHER,blocks,coverage,evidence));
        ObjectNode changed=coverage.deepCopy();
        ((ObjectNode)changed.path("nativeDocxParagraphs").path("paragraphs").get(1).path("textObservation")).put("text","Changed text");
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,blocks,changed,evidence));
        ObjectNode tampered=evidence.deepCopy();((ObjectNode)tampered.path("guidanceNotes").get(0)).put("scopeComplete",true);
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,blocks,coverage,tampered));
        assertEquals(evidence,OerTemplateEvidence.analyse("SCC",parsed));
    }

    @Test void oldOrMissingAnalysisRevisionIsStaleEvenWhenTheSourceAndStoredInputsHaveNotChanged() {
        DocumentParser.ParsedDocument parsed=word(p("a","Special Conditions of Contract"));JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed);ObjectNode old=evidence.deepCopy();((ObjectNode)old.path("sourceBinding")).put("analysisRevision","old-rules");
        assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
        ((ObjectNode)old.path("sourceBinding")).remove("analysisRevision");assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
    }

    @Test void aShortObservedTitleSelectsTheFamilyButBodyReferencesNeverDo() {
        DocumentParser.ParsedDocument ntt=word(p("title","Notes to Tenderers"),p("body","Use the Special Conditions of Contract in accordance with SCC1.301."));
        assertEquals("NTT",OerTemplateEvidence.detectFamily(ntt));
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.analyse("SCC",ntt));
        DocumentParser.ParsedDocument reference=word(p("body","The Special Conditions of Contract shall apply to these Works."));
        assertNull(OerTemplateEvidence.detectFamily(reference));
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.analyse(null,reference));
        JsonNode explicit=OerTemplateEvidence.analyse("SCC",reference);
        assertEquals("explicit_unverified",explicit.path("route").path("familyIdentityStatus").asText());
        assertFalse(explicit.path("route").path("strictIdentityVerified").asBoolean());
    }

    @Test void titlesSplitAcrossNativeParagraphsRemainTitleCandidatesWithoutFilenameRules() {
        DocumentParser.ParsedDocument parsed=word(p("a","SPECIAL CONDITIONS"),p("b","OF TENDER"));
        assertEquals("SCT",OerTemplateEvidence.detectFamily(parsed));
        assertFalse(OerTemplateEvidence.analyse(null,parsed).path("route").path("editionFusionAllowed").asBoolean());
    }

    @Test void sccContentsAndConsistentNativeHeadingFamiliesWorkWithoutAnInventedCoverTitle() {
        assertEquals("SCC",OerTemplateEvidence.detectFamily(word(p("a","TABLE OF CONTENT"),p("b","SCC1 Definitions"),p("c","SCC1.301 Definitions of terms"),p("d","SCC3 Assignment and sub-contracting"))));
        assertNull(OerTemplateEvidence.detectFamily(word(p("a","TABLE OF CONTENT"),p("b","SCC1.301 Definitions"),p("c","SCC1.302 Other definitions"),p("d","SCC1.303 More definitions"))));
        ObjectNode foreign=p("title","SPECIAL CONDITIONS OF CONTRACT");((ObjectNode)foreign.path("sourcePosition")).put("sourceSha256",OTHER);assertNull(OerTemplateEvidence.detectFamily(word(foreign)));
        ObjectNode unknown=p("title","SPECIAL CONDITIONS OF CONTRACT");((ObjectNode)unknown.path("textObservation")).put("status","unknown");assertNull(OerTemplateEvidence.detectFamily(word(unknown)));
    }

    @Test void overlappingNestedParagraphChannelsCannotBeConcatenatedIntoCompleteGuidance() {
        ObjectNode parent=p("parent","[Guidance Note: repeated nested text]");parent.put("textOverlapStatus","contains_nested_paragraph_channels_do_not_concatenate");
        ObjectNode nested=p("nested","[Guidance Note: repeated nested text]");nested.put("textOverlapStatus","nested_paragraph_channels_may_overlap_do_not_concatenate");
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(parent,nested));assertEquals(2,evidence.path("sourceMembers").size());assertTrue(evidence.path("guidanceNotes").isEmpty());assertTrue(hasIssue(evidence,"NATIVE_PARAGRAPH_CHANNEL_OVERLAP"));
    }

    @Test void longGuidanceRetainsEveryNativeMemberAndItsLiteralConditions() {
        String longText=String.join(" ",Collections.nCopies(800,"condition"));
        DocumentParser.ParsedDocument parsed=word(p("a","[Guidance Notes:"),p("b","1. For use where both systems apply; unless excluded."),p("c",longText),p("d","2. Do not adopt where the Works are absent.]"));
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed),note=evidence.path("guidanceNotes").get(0);
        assertEquals(4,note.path("orderedMemberIds").size());assertTrue(note.path("text").asText().contains(longText));
        assertTrue(note.path("sourceSyntaxComplete").asBoolean());assertTrue(note.path("captureComplete").asBoolean());assertFalse(note.path("scopeComplete").asBoolean());
        assertEquals(note.path("text"),evidence.path("conditions").get(0).path("text"));
        assertEquals(4,evidence.path("sourceMembers").size());
    }

    @Test void multipleNotesInOneParagraphRemainSeparateAndUnclosedSourceIsNotRepaired() {
        JsonNode multiple=OerTemplateEvidence.analyse("SCC",word(p("a","[Guidance Note: Select Option A.] [Guidance Note: Insert the date.]")));
        assertEquals(2,multiple.path("guidanceNotes").size());
        assertEquals("[Guidance Note: Select Option A.]",multiple.path("guidanceNotes").get(0).path("text").asText());
        JsonNode incomplete=OerTemplateEvidence.analyse("SCC",word(p("a","[Guidance Notes:"),p("b","Keep this actual incomplete source")));
        assertFalse(incomplete.path("guidanceNotes").get(0).path("sourceSyntaxComplete").asBoolean());
        assertFalse(incomplete.path("guidanceNotes").get(0).path("captureComplete").asBoolean());
        assertTrue(incomplete.path("guidanceNotes").get(0).path("text").asText().endsWith("source"));
        assertTrue(hasIssue(incomplete,"GUIDANCE_SOURCE_SYNTAX_INCOMPLETE"));
    }

    @Test void guidanceNeverBorrowsAnAlreadyClosedDelimiterFromEarlierOrdinaryTextOrAnotherNote() {
        JsonNode ordinary=OerTemplateEvidence.analyse("SCC",word(p("a","See paragraph (a). Guidance Note: Insert the date.")));
        assertEquals("Guidance Note: Insert the date.",ordinary.path("guidanceNotes").get(0).path("text").asText());
        JsonNode two=OerTemplateEvidence.analyse("SCC",word(p("a","[Guidance Note: First.] Guidance Note: Second.")));assertEquals(2,two.path("guidanceNotes").size());assertEquals("Guidance Note: Second.",two.path("guidanceNotes").get(1).path("text").asText());
        for(JsonNode note:two.path("guidanceNotes"))assertTrue(note.path("text").asText().contains("Guidance Note:"));
    }

    @Test void useIncludeAndNonBodyColumnRolePlaceholdersRemainCompilationCandidates() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("use","t1","r1","c2","( Use Appendix Q with a reduced sum if the period exceeds the threshold)."),cell("include","t1","r2","c2","(Include only contract documents on Disc X)"),cell("main","t1","r3","c1","Contact details: ______"),cell("role","t1","r3","c2","(Project Architect)"),cell("main2","t1","r4","c1","Address: ______"),cell("address","t1","r4","c2","(Address advised by Project Architect)"),cell("ordinary","t1","r5","c2","(Normal concrete)")));
        assertEquals(4,evidence.path("guidanceNotes").size());for(JsonNode note:evidence.path("guidanceNotes"))assertFalse(note.path("scopeComplete").asBoolean());
    }

    @Test void templateProfilesKeepSccBodyGuidanceDistinctFromNttEditorialColumns() {
        ObjectNode a=cell("a","t1","r1","c3","(Alternative (a) - for tenders"),b=cell("b","t2","r1","c3","using electronic dissemination)"),business=cell("business","t2","r1","c2","Business reference must remain outside the note");
        JsonNode ntt=OerTemplateEvidence.analyse("NTT",word(a,b,business));
        assertEquals(1,ntt.path("guidanceNotes").size());assertEquals(2,ntt.path("guidanceNotes").get(0).path("orderedMemberIds").size());
        assertFalse(ntt.path("guidanceNotes").get(0).path("text").asText().contains("Business reference"));
        assertTrue(ntt.path("guidanceNotes").get(0).path("crossTableContinuationCandidate").asBoolean());assertFalse(ntt.path("guidanceNotes").get(0).path("scopeComplete").asBoolean());
        JsonNode scc=OerTemplateEvidence.analyse("SCC",word(p("a","(Delete if inapplicable)"),p("b","[Guidance Note: Keep both explicit conditions.]")));
        assertEquals(1,scc.path("guidanceNotes").size());assertEquals("b",scc.path("guidanceNotes").get(0).path("orderedMemberIds").get(0).asText());
    }

    @Test void anOptionAcrossTablesRetainsWholeRowsIncludingReferenceCellsUntilTheNextPeer() {
        ObjectNode a=cell("a","t1","r1","c3","(Alternative (a) - for use in this tender)"),body=cell("body","t1","r1","c2","Full first body"),continuation=cell("cont","t2","r2","c2","Continued complete option body"),ref=cell("ref","t2","r2","c3","Reference SCT6"),next=cell("next","t3","r1","c3","(Alternative (b) - for hard copy)"),nextBody=cell("nextBody","t3","r1","c2","Excluded next option");
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(body,a,continuation,ref,nextBody,next));
        JsonNode group=firstGroup(evidence,"literal_option_candidate");List<String> ids=strings(group.path("orderedMemberIds"));
        assertEquals(Arrays.asList("body","a","cont","ref"),ids);assertTrue(group.path("crossTable").asBoolean());assertFalse(group.path("scopeComplete").asBoolean());
    }

    @Test void nativeOutlineMembershipKeepsAllPhysicalTableMembersAndExcludesTheNextPeer() {
        DocumentParser.ParsedDocument parsed=word(p("h","Owner"),cell("a","t1","r1","c1","#(e) Body"),cell("ref","t1","r1","c2","Reference content"),p("next","Next heading"));
        ObjectNode outline=object(),heading=outline.putArray("headings").addObject();heading.set("sourcePosition",parsed.getCoverage().getNativeDocxParagraphs().path("paragraphs").get(0).path("sourcePosition"));
        heading.putArray("nativeMemberPaths").add(BODY+"/{w}p[h]").add(BODY+"/{w}tbl[t1]");heading.putObject("endBoundary").put("kind","next_native_outline_heading").set("sourcePosition",parsed.getCoverage().getNativeDocxParagraphs().path("paragraphs").get(3).path("sourcePosition"));parsed.getCoverage().setNativeDocxOutline(outline);
        JsonNode group=firstGroup(OerTemplateEvidence.analyse("SCT",parsed),"native_outline_physical_scope");
        assertEquals(Arrays.asList("h","a","ref"),strings(group.path("orderedMemberIds")));assertTrue(group.path("scopeComplete").asBoolean());assertFalse(group.path("semanticApplicabilityVerified").asBoolean());
    }

    @Test void sctHashRowsKeepTheirReferenceCellsWithoutAssumingTheWholeClauseIsATarget() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("e","t1","r1","c1","#(e) Submit the required particulars"),cell("ref","t1","r1","c2","Reference SCC20.301"),cell("next","t1","r2","c1","(f) Other requirement")));
        JsonNode row=firstGroup(evidence,"marker_native_row_candidate");assertEquals(Arrays.asList("e","ref"),strings(row.path("orderedMemberIds")));assertTrue(row.path("captureComplete").asBoolean());assertFalse(row.path("scopeComplete").asBoolean());
    }

    @Test void ordinaryStarsAndHashesKeepExactOffsetsWithoutWholeParagraphOrClauseTargets() {
        String text="The reference # is ordinary text. SCC22.307 remains effective. * Delete the final sentence only.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("a",text)));assertEquals(2,evidence.path("markerCandidates").size());
        JsonNode marker=evidence.path("markerCandidates").get(1);assertEquals(text.indexOf('*'),marker.path("start").asInt());assertFalse(marker.path("wholeParagraphTargetAssumed").asBoolean());assertFalse(marker.path("scopeComplete").asBoolean());
        assertEquals(" * Delete the final sentence only.",marker.path("localSentenceObservation").path("text").asText());
        assertEquals(text,evidence.path("sourceMembers").get(0).path("textObservation").path("text").asText());
    }

    @Test void noncontiguousReferencesArePreservedAsReferencesWithoutInventedContinuousScope() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("a","[Guidance Note: Amend SCC7.304(1), SCC7.304(3) and SCC7.304(11); retain SCC7.304(8) and SCC7.304(9).]")));
        JsonNode note=evidence.path("guidanceNotes").get(0);assertEquals(5,note.path("referenceCandidates").size());assertFalse(note.path("scopeComplete").asBoolean());
        for(JsonNode reference:note.path("referenceCandidates"))assertEquals("literal_reference_candidate_not_resolved",reference.path("status").asText());
    }

    @Test void unknownAndForeignParagraphsRemainInTheInventoryButCannotCreateTrustedGuidance() {
        ObjectNode unknown=p("unknown","never consume this");((ObjectNode)unknown.path("textObservation")).remove("text");((ObjectNode)unknown.path("textObservation")).put("status","unknown");
        ObjectNode foreign=p("foreign","[Guidance Note: Wrong source]");((ObjectNode)foreign.path("sourcePosition")).put("sourceSha256",OTHER);
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(unknown,foreign));assertEquals(2,evidence.path("sourceMembers").size());assertTrue(evidence.path("guidanceNotes").isEmpty());assertTrue(hasIssue(evidence,"MEMBER_SOURCE_MISMATCH"));
    }

    @Test void pdfCandidateTextDoesNotBecomeEffectiveAndItsPhysicalProvenanceRemainsVisible() {
        DocumentBlock effective=new DocumentBlock();effective.setId("native");effective.setSource("native_pdf");effective.setPageNo(1);effective.setText("Special Conditions of Contract");effective.setBbox(new double[]{.1,.2,.3,.4});
        DocumentBlock candidate=new DocumentBlock();candidate.setId("candidate");candidate.setSource("ocr_candidate");candidate.setPageNo(2);candidate.setText("[Guidance Note: Candidate only]");candidate.setVisualProvenance(JsonUtils.mapper().valueToTree(Collections.singletonMap("provider","observed-provider")));
        ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(2);DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument("native",Collections.emptyList(),2,false,"fixture",Arrays.asList(effective,candidate),coverage,SHA,Collections.emptyList());
        JsonNode evidence=OerTemplateEvidence.analyse(null,parsed);assertEquals("SCC",evidence.path("sourceBinding").path("fileKey").asText());assertTrue(evidence.path("guidanceNotes").isEmpty());
        assertEquals("",evidence.path("sourceMembers").get(1).path("textObservation").path("text").asText());assertEquals("[Guidance Note: Candidate only]",evidence.path("sourceMembers").get(1).path("blockObservation").path("text").asText());
        assertEquals(2,evidence.path("sourceMembers").get(1).path("blockObservation").path("pageNo").asInt());assertEquals("observed-provider",evidence.path("sourceMembers").get(1).path("blockObservation").path("visualProvenance").path("provider").asText());assertTrue(hasIssue(evidence,"PDF_SCOPE_REQUIRES_REVIEW"));
    }

    @Test void nttRepeatedAlternativeAnnotationsJoinWholeNativeRowsAcrossContinuationTables() {
        DocumentParser.ParsedDocument parsed=word(cell("numberA","t1","r1","c1","*2."),cell("bodyA","t1","r1","c2","Body of alternative A"),cell("noteA","t1","r1","c3","(Alternative (a) - for tenders with electronic documents)"),p("page","HD(QS) reference - NTT/2 -"),cell("header","t2","r1","c2","NOTES TO TENDERERS (Cont’d)"),cell("numberContinue","t2","r2","c1","*2."),cell("bodyContinue","t2","r2","c2","Body (Cont’d), including the complete final subitem"),cell("noteContinue","t2","r2","c3","(Alternative (a) - for tenders with electronic documents)"),cell("numberB","t3","r1","c1","*2."),cell("bodyB","t3","r1","c2","Alternative B body"),cell("noteB","t3","r1","c3","(Alternative (b) - for tenders with hard copy)"),cell("numberNext","t4","r1","c1","3."),cell("next","t4","r1","c2","Next clause"));
        JsonNode group=firstGroup(OerTemplateEvidence.analyse("NTT",parsed),"complete_alternative_scope");
        assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("numberA","bodyA","numberContinue","bodyContinue"),strings(group.path("orderedMemberIds")));assertEquals(Arrays.asList("noteA","noteContinue"),strings(group.path("guidanceContextMemberIds")));assertEquals(6,group.path("nativeRowMemberIds").size());
        assertEquals(Arrays.asList("page","header"),strings(group.path("interstitialContextMemberIds")));assertEquals(2,group.path("continuationAnnotationMemberIds").size());assertEquals("numberB",group.path("endBoundary").path("memberId").asText());
    }

    @Test void unclassifiedSourceBetweenAlternativeTablesKeepsTheScopeUnknown() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","*2."),cell("a","t1","r1","c3","(Alternative (a) - for use here)"),p("unknown","An unclassified source instruction between the tables"),cell("nb","t2","r1","c1","*2."),cell("b","t2","r1","c3","(Alternative (b) - for use there)"),cell("next","t3","r1","c1","3.")));
        assertFalse(firstGroup(evidence,"complete_alternative_scope").path("scopeComplete").asBoolean());assertTrue(hasIssue(evidence,"ALTERNATIVE_SCOPE_INCOMPLETE"));
    }

    @Test void continuationTopologyUsesTheAnnotatedRowsInsteadOfUnrelatedFurnitureCells() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("header","t1","r0","c2","NOTES TO TENDERERS (Cont’d)"),cell("extra","t1","r0","c4",""),cell("na","t1","r1","c1","*2."),cell("bodyA","t1","r1","c2","First body"),cell("a","t1","r1","c3","(Alternative (a) - for this system)"),cell("nc","t2","r1","c1","*2."),cell("bodyC","t2","r1","c2","Continuation body"),cell("ac","t2","r1","c3","(Alternative (a) - for this system)"),cell("nb","t3","r1","c1","*2."),cell("b","t3","r1","c3","(Alternative (b) - for another system)"),cell("next","t4","r1","c1","3.")));
        JsonNode group=firstGroup(evidence,"complete_alternative_scope");assertTrue(group.path("scopeComplete").asBoolean());assertTrue(strings(group.path("orderedMemberIds")).contains("bodyC"));assertTrue(group.path("continuationTableEvidence").get(0).path("compatiblePhysicalCellColumns").asBoolean());
        JsonNode mismatch=OerTemplateEvidence.analyse("NTT",word(cell("na","t1","r1","c1","*2."),cell("a","t1","r1","c3","(Alternative (a) - for this system)"),cell("nc","t2","r1","c1","*2."),cell("ac","t2","r1","c4","(Alternative (a) - for this system)"),cell("nb","t3","r1","c1","*2."),cell("b","t3","r1","c3","(Alternative (b) - for another system)"),cell("next","t4","r1","c1","3.")));
        assertFalse(firstGroup(mismatch,"complete_alternative_scope").path("scopeComplete").asBoolean());
    }

    @Test void enumeratedTargetsResolveOnlyRequestedNoncontiguousNativeSubitemsAndKeepParentContext() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("heading","SCC7.304 Warranty and deed of warranty"),p("note","[Guidance Note: The objects in the Clauses SCC7.304(1)(a), SCC7.304(1)(c) and SCC7.304(3) are to be deleted as appropriate.]"),p("one","(1) The Contractor shall warrant that:"),indent(p("a","(a) First selected object;"),2400,600),indent(p("b","(b) Unselected middle object;"),2400,600),indent(p("c","(c) Third selected object"),2400,600),indent(p("tail","shall remain watertight for the stated period."),1750,0),p("two","(2) Unselected second subsection."),p("three","(3) Selected third subsection."),p("four","(4) Unselected fourth subsection."),p("next","SCC7.305 Other warranty")));
        JsonNode group=firstGroup(evidence,"explicit_reference_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(3,group.path("targets").size());
        assertEquals(Arrays.asList("a","c","three"),strings(group.path("orderedMemberIds")));assertEquals(Arrays.asList("one"),strings(group.path("targets").get(0).path("ancestorContextMemberIds")));assertEquals(Arrays.asList("tail"),strings(group.path("targets").get(0).path("sharedParentTailContextMemberIds")));assertFalse(group.path("automaticEditEligible").asBoolean());
    }

    @Test void duplicateOrMissingEnumeratedUnitsNeverBecomeCompleteByChoosingTheFirstMatch() {
        JsonNode duplicate=OerTemplateEvidence.analyse("SCC",word(p("h","SCC7.304 Warranty"),p("n","[Guidance Note: Objects in the Clauses SCC7.304(1) are to be deleted as appropriate.]"),p("a","(1) First duplicate"),p("b","(1) Second duplicate"),p("next","SCC7.305 Next warranty")));
        assertFalse(firstGroup(duplicate,"explicit_reference_scope").path("scopeComplete").asBoolean());
        JsonNode missing=OerTemplateEvidence.analyse("SCC",word(p("h","SCC7.304 Warranty"),p("n","[Guidance Note: Objects in the Clauses SCC7.304(9) are to be deleted as appropriate.]"),p("a","(1) Only actual unit"),p("next","SCC7.305 Next warranty")));
        assertFalse(firstGroup(missing,"explicit_reference_scope").path("scopeComplete").asBoolean());assertTrue(hasIssue(missing,"EXPLICIT_REFERENCE_SCOPE_INCOMPLETE"));
    }

    @Test void anAdjacentLastSentenceInstructionBindsOnlyTheMarkedFinalSentence() {
        String text="The definition remains in force. Another sentence also remains. *The marked railway plans sentence is conditional.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("source",text),p("blank",""),p("note","[Guidance Note: If the plans are unavailable, delete the last sentence and prepare plans.]")));
        JsonNode group=firstGroup(evidence,"inline_last_sentence_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals("*The marked railway plans sentence is conditional.",group.path("text").asText());assertEquals(text.indexOf('*'),group.path("memberRanges").get(0).path("start").asInt());assertFalse(group.path("wholeParagraphTargetAssumed").asBoolean());
    }

    @Test void sctAlternativeEndsAtTheNextParentSubitemBeforeTheNextMainClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("heading","t1","r1","c1","SCT5 Submissions required with tender"),cell("fourA","t1","r2","c1","(4) Required submission"),cell("a","t1","r2","c2","(Alternative (a) - for electronic tenders)"),cell("fourB","t2","r1","c1","(4) Required hardcopy submission (Cont’d)"),cell("b","t2","r1","c2","(Alternative (b) - for hardcopy tenders)"),cell("five","t3","r1","c1","(5) Non-compliance may disqualify the tender"),cell("next","t3","r2","c1","SCT6 Other submissions")));
        List<JsonNode> alternatives=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope"))alternatives.add(group);
        assertEquals(2,alternatives.size());assertTrue(alternatives.get(1).path("scopeComplete").asBoolean());assertEquals(Arrays.asList("fourB"),strings(alternatives.get(1).path("orderedMemberIds")));assertEquals("five",alternatives.get(1).path("endBoundary").path("memberId").asText());assertEquals("next_distinct_parent_subitem_native_row",alternatives.get(1).path("endBoundary").path("kind").asText());
    }

    @Test void wholeClauseAlternativesDoNotInheritThePreviousBranchsNumericSubitemBoundary() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("ha","t1","r1","c1","SCT91 New tender clause"),cell("a","t1","r1","c2","(Alternative (a) - for electronic tenders)"),cell("a1","t1","r2","c1","(1) First electronic item"),cell("a2","t1","r3","c1","(2) Second electronic item"),cell("hb","t2","r1","c1","SCT91 New tender clause"),cell("b","t2","r1","c2","(Alternative (b) - for hardcopy tenders)"),cell("b1","t2","r2","c1","(1) First hardcopy item"),cell("b2","t2","r3","c1","(2) Second hardcopy item"),cell("next","t3","r1","c1","SCT92 Other terms")));
        List<JsonNode> options=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope"))options.add(group);assertTrue(options.get(1).path("scopeComplete").asBoolean());assertEquals(Arrays.asList("hb","b1","b2"),strings(options.get(1).path("orderedMemberIds")));assertEquals("next",options.get(1).path("endBoundary").path("memberId").asText());
    }

    @Test void subitemAlternativesKeepRepeatedParentHeadingsAsContextAndTheirOwnContinuationLabelsAsTargets() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT5 Submission documents"),cell("four","t1","r2","c1","*(4) Required submission"),cell("a","t1","r2","c2","(Alternative (a) - for this system)"),cell("parent","t2","r1","c1","SCT5 Submission documents (Cont’d)"),cell("blank","t2","r1","c2",""),cell("fourc","t2","r2","c1","*(4) (Cont’d)"),cell("ac","t2","r2","c2","(Alternative (a) - for this system)"),cell("body","t2","r3","c1","(c) Submission item"),cell("ref","t2","r3","c2","SCT3(2)"),cell("fourb","t3","r1","c1","*(4) Other submission"),cell("b","t3","r1","c2","(Alternative (b) - for another system)"),cell("five","t4","r1","c1","(5) Next subitem"),cell("next","t4","r2","c1","SCT6 Next clause")));
        JsonNode group=firstGroup(evidence,"complete_alternative_scope");assertTrue(group.path("scopeComplete").asBoolean());assertTrue(strings(group.path("parentContextMemberIds")).containsAll(Arrays.asList("parent","blank")));assertFalse(strings(group.path("targetMemberIds")).contains("parent"));assertTrue(strings(group.path("targetMemberIds")).containsAll(Arrays.asList("four","fourc","body","ref")));assertTrue(strings(group.path("nativeRowMemberIds")).contains("parent"));
    }

    @Test void sameRowAlternativesRemainUnknownInsteadOfChoosingAnEmptyFirstBranchAndTheWholeSecondRow() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("previous","t0","r1","c1","1."),cell("n","t1","r3","c1","2."),cell("bodyA","t1","r3","c2","*(c) First marked body"),cell("bodyB","t1","r3","c2","*(c) Other marked body"),cell("a","t1","r3","c3","(Alternative (a) - for the first arrangement)"),cell("b","t1","r3","c3","(Alternative (b) - for the second arrangement)"),cell("next","t1","r4","c1","3.")));
        int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope")){count++;assertFalse(group.path("scopeComplete").asBoolean());assertTrue(group.path("orderedMemberIds").isEmpty());assertTrue(strings(group.path("rowContextMemberIds")).containsAll(Arrays.asList("bodyA","bodyB","a","b")));}assertEquals(2,count);
    }

    @Test void aFollowingClausesLeadingGuidanceCannotBecomePartOfThePreviousOption() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT31 Existing clause"),cell("bodyA","t1","r2","c1","*(2) First body arrangement"),cell("a","t1","r2","c2","(Option 1 - first arrangement)"),cell("bodyB","t1","r3","c1","*(2) Second body arrangement"),cell("b","t1","r3","c2","(Option 2 - second arrangement)"),cell("prelude","t2","r1","c1","(Guidance Note: Condition SCT32 below applies to this tendering system.)"),cell("next","t2","r2","c1","SCT32 Following clause")));
        for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope")&&group.path("label").asText().equals("option:2")){assertTrue(group.path("scopeComplete").asBoolean());assertFalse(strings(group.path("orderedMemberIds")).contains("prelude"));assertEquals("prelude",group.path("endBoundary").path("memberId").asText());return;}fail("Missing second option");
    }

    @Test void anUnrelatedInterposedTableCannotBecomeContinuationOfAnAlternative() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","2."),cell("a","t1","r1","c3","(Alternative (a) - for electronic tenders)"),cell("schedule","unrelated","r1","c1","Schedule of Rates"),cell("rate","unrelated","r1","c2","Unrelated prices"),cell("nb","t2","r1","c1","2."),cell("b","t2","r1","c3","(Alternative (b) - for hardcopy tenders)"),cell("next","t3","r1","c1","3.")));
        JsonNode group=firstGroup(evidence,"complete_alternative_scope");assertFalse(group.path("scopeComplete").asBoolean());assertFalse(strings(group.path("orderedMemberIds")).contains("schedule"));assertTrue(strings(group.path("interstitialContextMemberIds")).contains("schedule"));
    }

    @Test void anUnprovenUnlabelledTailStaysContextAndCannotMakeEnumeratedTargetsComplete() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC7.304 Warranty"),p("n","[Guidance Note: Objects in the Clauses SCC7.304(1)(a) and SCC7.304(1)(b) are to be deleted as appropriate.]"),p("one","(1) Parent"),p("a","(a) First item."),p("b","(b) Second item."),p("unknown","This could be a continuation or a shared parent tail."),p("two","(2) Next parent"),p("next","SCC7.305 Other warranty")));
        JsonNode group=firstGroup(evidence,"explicit_reference_scope");assertFalse(group.path("scopeComplete").asBoolean());assertFalse(strings(group.path("orderedMemberIds")).contains("unknown"));
    }

    @Test void mixedCharacterAndTwipIndentEvidenceCannotCertifyASharedParentTail() {
        ObjectNode a=indent(p("a","(a) First selected object;"),2400,600),b=indent(p("b","(b) Second selected object"),2400,600);((ObjectNode)b.path("directParagraphLayout").path("ind").get(0).path("attributes")).put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}leftChars","100");
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC7.304 Warranty"),p("n","[Guidance Note: Objects in the Clauses SCC7.304(1)(a) and SCC7.304(1)(b) are to be deleted as appropriate.]"),p("one","(1) Parent"),a,b,indent(p("tail","shall remain valid."),1750,0),p("two","(2) Next parent"),p("next","SCC7.305 Other warranty")));
        JsonNode group=firstGroup(evidence,"explicit_reference_scope");assertFalse(group.path("scopeComplete").asBoolean());assertFalse(strings(group.path("orderedMemberIds")).contains("tail"));
    }

    @Test void explicitMarkedItemsAboveKeepWholeNativeRowsAndLinkEachMarkerToItsOwnSourceCondition() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT5 Submission requirements"),cell("three","t1","r2","c1","(3) Required submissions"),cell("e","t1","r3","c1","#(e) Assessment of ground conditions"),cell("eref","t1","r3","c2","SCT6(1)(b)"),cell("f","t1","r4","c1","#(f) Proposed sub-contractor"),cell("fref","t1","r4","c2","SCT6(1)(c)"),cell("note","t1","r5","c1","(Guidance Note: Delete items marked with # above if they are not required.)")));
        JsonNode group=firstGroup(evidence,"explicit_marked_item_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("e","eref","f","fref"),strings(group.path("orderedMemberIds")));
        JsonNode marker=marker(evidence,"e","#");assertEquals("conditional_source_target",marker.path("disposition").asText());assertTrue(strings(marker.path("targetGroupIds")).contains(group.path("id").asText()));assertEquals(1,marker.path("conditionIds").size());assertEquals("negative_instruction_symbol",marker(evidence,"note","#").path("disposition").asText());
    }

    @Test void latestRateInstructionsRequireExternalVerificationAndNeverDeleteThePhysicalRow() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("body","t1","r1","c1","The rate is #$8.7 for one page and #$45 for a larger page."),cell("note","t1","r1","c2","(#Check the latest rate as promulgated by the relevant authority)")));
        JsonNode group=firstGroup(evidence,"external_verification_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(2,group.path("memberRanges").size());assertEquals(Arrays.asList("body"),strings(group.path("orderedMemberIds")));assertFalse(group.path("automaticEditEligible").asBoolean());assertEquals("external_verification_required",marker(evidence,"body","#").path("disposition").asText());
    }

    @Test void markedMoneyKeepsTheWholeThousandsAndDecimalLiteralOrReportsUnknownTokenScope() {
        String body="The fee is #$1,234.56 per page.";JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("body","t1","r1","c1",body),cell("note","t1","r1","c2","(#Check the latest rate as promulgated by the authority)")));
        JsonNode group=firstGroup(evidence,"external_verification_scope"),range=group.path("memberRanges").get(0);assertTrue(group.path("scopeComplete").asBoolean());assertEquals("#$1,234.56",body.substring(range.path("start").asInt(),range.path("end").asInt()));
        JsonNode malformed=OerTemplateEvidence.analyse("SCT",word(cell("body","t1","r1","c1","The fee is #$1,23.45 per page."),cell("note","t1","r1","c2","(#Check the latest rate as promulgated by the authority)")));assertFalse(firstGroup(malformed,"external_verification_scope").path("scopeComplete").asBoolean());assertEquals("external_verification_required",marker(malformed,"body","#").path("disposition").asText());
    }

    @Test void sameRowWholeUnitAndInlineWordsKeepTheirDifferentSourceConditions() {
        String body="*(c) The submission for domestic blocks (#and the submission for special terms of payment) shall not relieve the Contractor.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("three","t1","r2","c1","(3) Tenderers should note"),cell("condition","t1","r3","c1","(Guidance Note: Condition SCT6(3)(c) below applies where domestic blocks are in the Contract. Mark it as Not used if inapplicable.)"),cell("body","t1","r4","c1",body),cell("words","t1","r4","c2","(# - Delete the words if precast facade is not applicable)"),cell("d","t1","r5","c1","(d) A different submission"),cell("next","t1","r6","c1","SCT7 Next condition")));
        JsonNode whole=firstGroup(evidence,"explicit_condition_unit_scope"),inline=firstGroup(evidence,"inline_parenthetical_scope");assertTrue(whole.path("scopeComplete").asBoolean());assertTrue(inline.path("scopeComplete").asBoolean());assertEquals("#and the submission for special terms of payment",inline.path("text").asText());assertEquals(2,inline.path("wrapperContextRanges").size());assertEquals("independent_decision_required",inline.path("wrapperCleanupStatus").asText());assertEquals("(",body.substring(inline.path("wrapperContextRanges").get(0).path("start").asInt(),inline.path("wrapperContextRanges").get(0).path("end").asInt()));
        JsonNode star=marker(evidence,"body","*"),hash=marker(evidence,"body","#");assertTrue(strings(star.path("targetGroupIds")).contains(whole.path("id").asText()));assertFalse(strings(hash.path("targetGroupIds")).contains(whole.path("id").asText()));assertTrue(strings(hash.path("targetGroupIds")).contains(inline.path("id").asText()));assertNotEquals(star.path("conditionIds"),hash.path("conditionIds"));
    }

    @Test void explicitQuotedRepaymentAndCalendarWordsRetainExactLocalRanges() {
        String body="(3) Repay over #six months and begin in the #seventh payment. Other six remains.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC14.305 Advance payment"),p("n","[Guidance Note: # Project team may amend the words “six” and “seventh” in Clause SCC14.305(3).]"),p("b",body),p("four","(4) Next payment provision"),p("next","SCC15.301 Other clause")));
        List<String> literals=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("explicit_quoted_word_scope")){assertTrue(group.path("scopeComplete").asBoolean());JsonNode range=group.path("memberRanges").get(0);literals.add(body.substring(range.path("start").asInt(),range.path("end").asInt()));assertFalse(group.path("automaticEditEligible").asBoolean());}assertEquals(Arrays.asList("six","seventh"),literals);assertEquals("conditional_source_target",marker(evidence,"b","#").path("disposition").asText());
    }

    @Test void doubleStarChoicesKeepBothOptionsAndSeparateCleanupRangesWithoutChoosingOne() {
        String body="(7) Items in Bill No(s). _____ / Schedule No(s). _____** of the Bills of Quantities / schedule of rates** remain conditional.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.301 Price fluctuation"),p("n","[Guidance Note: ** In sub-clause (7), delete inapplicable “Bill No(s). _____” or “Schedule No(s). _____”, delete inapplicable “Bills of Quantities” or “schedule of rates” and delete the two “ / ” and the two “**”.]"),p("b",body),p("eight","(8) Unrelated provision"),p("next","SCC20.302 Other clause")));
        int groups=0,stars=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("explicit_quoted_choice_scope")){groups++;assertTrue(group.path("scopeComplete").asBoolean());assertEquals(2,group.path("memberRanges").size());assertEquals(2,group.path("cleanupCandidateRanges").size());JsonNode range=group.path("markerRanges").get(0);assertEquals("**",body.substring(range.path("start").asInt(),range.path("end").asInt()));assertEquals("independent_decision_required",group.path("cleanupStatus").asText());}for(JsonNode marker:evidence.path("markerCandidates"))if(marker.path("memberId").asText().equals("b")){stars++;assertEquals("conditional_source_target",marker.path("disposition").asText());}assertEquals(2,groups);assertEquals(4,stars);
    }

    @Test void oneFillInInstructionBindsAllRepeatedMarkedPlaceholdersButNotAnotherClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC8.303 Extension of time"),p("n","[Guidance Note: * Fill in the blank with the designation of the Section, and delete ‘*’ and the underline ‘___’. If adopted, do not adopt SCC8.302.]"),p("a","(1A) Section ____* and Section ______* are subject to this provision."),p("b","(1B) Other than Section ___*."),p("next","SCC8.304 Another clause"),p("other","Section ______* remains unbound.")));
        JsonNode group=firstGroup(evidence,"explicit_placeholder_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(3,group.path("memberRanges").size());assertEquals(Arrays.asList("a","b"),strings(group.path("orderedMemberIds")));assertEquals("unknown",marker(evidence,"other","*").path("disposition").asText());
    }

    @Test void sameRowConditionalSidebarKeepsAWholeParentAndItsUnmarkedChildren() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("one","t1","r2","c1","(1) Tenderer requirements"),cell("c","t1","r3","c1","*(c) The following information is required:"),cell("n","t1","r3","c2","(For use where foundation works are involved.)"),cell("i","t1","r4","c1","(i) First child."),cell("ii","t1","r5","c1","(ii) Second child."),cell("d","t1","r6","c1","(d) Next peer."),cell("next","t2","r1","c1","SCT7 Other submissions")));
        JsonNode group=firstGroup(evidence,"source_unit_instruction_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("c","i","ii"),strings(group.path("orderedMemberIds")));assertEquals("SCT6(1)(c)",group.path("sourceReference").asText());assertEquals(3,group.path("memberRanges").size());assertEquals("d",group.path("endBoundary").path("memberId").asText());
    }

    @Test void nativeTocHyperlinkAndPageReferenceMakeTheCopiedMarkerAnExplicitNegative() {
        ObjectNode copy=p("copy","SCC7.302 Warranty (footings*) 17");copy.put("nativeXml","<w:p><w:hyperlink w:anchor=\"_TocExample\"><w:r><w:t>footings*</w:t><w:instrText> PAGEREF _TocExample \\h </w:instrText></w:r></w:hyperlink></w:p>");
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("toc","TABLE OF CONTENTS"),copy,p("h","SCC7.302 Warranty"),p("body","Footings* remain ambiguous."),p("next","SCC7.303 Another warranty")));
        assertEquals("negative_toc_source_copy",marker(evidence,"copy","*").path("disposition").asText());assertFalse(marker(evidence,"copy","*").path("scopeComplete").asBoolean());assertEquals("unknown",marker(evidence,"body","*").path("disposition").asText());
    }

    @Test void pdfMixedBlockRevisionPrefixesBecomeExactContextWhileOrdinaryBodyWordsStay() {
        DocumentBlock first=pdfLine("l1",1,"Rev. 2 [Guidance Note: Keep approved on wording.",7,.10),second=pdfLine("l2",1,"approved on The body ends.]",12,.115),date=pdfLine("l3",1,"01.12 .2022",11,.13);
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",pdf(first,second,date)),note=evidence.path("guidanceNotes").get(0);assertTrue(note.path("captureComplete").asBoolean());assertEquals("[Guidance Note: Keep approved on wording.\n The body ends.]",note.path("text").asText());assertEquals(3,note.path("revisionMarginContextRanges").size());assertTrue(note.path("rawText").asText().contains("approved on The body ends.]"));assertEquals("not_persisted_in_bound_inputs",note.path("pdfLaneProjection").path("frameStatus").asText());assertEquals("Rev. 2 [Guidance Note: Keep approved on wording.",evidence.path("sourceMembers").get(0).path("textObservation").path("text").asText());
        for(JsonNode range:note.path("memberRanges")){assertEquals("text",range.path("channel").asText());assertFalse(range.path("pdfGlyphProvenance").path("glyphIds").isEmpty());}
    }

    @Test void pdfGlyphMismatchOrMissingGeometryRetainsTheRawNoteAndReportsLaneUnknown() {
        DocumentBlock mismatch=pdfLine("bad",1,"[Guidance Note: The body ends.]",0,.10);mismatch.getPdfGlyphs().get(0).setUnicode("?");JsonNode note=OerTemplateEvidence.analyse("SCC",pdf(mismatch)).path("guidanceNotes").get(0);assertFalse(note.path("captureComplete").asBoolean());assertEquals("[Guidance Note: The body ends.]",note.path("text").asText());assertEquals("lane_unknown_raw_source_retained",note.path("pdfLaneProjection").path("status").asText());
        DocumentBlock missing=pdfLine("missing",1,"[Guidance Note: The body ends.]",0,.10);missing.getPdfGlyphs().get(0).setBbox(null);JsonNode other=OerTemplateEvidence.analyse("SCC",pdf(missing)).path("guidanceNotes").get(0);assertFalse(other.path("captureComplete").asBoolean());assertTrue(other.path("revisionMarginContextRanges").isEmpty());
    }

    @Test void pdfBodyApprovalWordsStayAndCrossPageOwnershipNeverBecomesVerified() {
        DocumentBlock body=pdfLine("body",1,"[Guidance Note: The application was approved on this date.]",0,.10);JsonNode ordinary=OerTemplateEvidence.analyse("SCC",pdf(body)).path("guidanceNotes").get(0);assertTrue(ordinary.path("captureComplete").asBoolean());assertEquals(body.getText(),ordinary.path("text").asText());assertTrue(ordinary.path("revisionMarginContextRanges").isEmpty());
        JsonNode crossing=OerTemplateEvidence.analyse("SCC",pdf(pdfLine("a",1,"[Guidance Note: This continues",0,.10),pdfLine("b",2,"on the next page.]",0,.10))).path("guidanceNotes").get(0);assertFalse(crossing.path("captureComplete").asBoolean());assertEquals("lane_unknown_raw_source_retained",crossing.path("pdfLaneProjection").path("status").asText());
    }

    @Test void sameRowAlternativesRequireDistinctSourceLexicalSignaturesRatherThanParagraphOrder() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","3."),cell("title","t1","r1","c2","Tender price documents"),cell("electronic","t1","r1","c2","*(c) The Bills generated by DesignTool42 and printed out in hard copy shall be submitted."),cell("paper","t1","r1","c2","*(c) The original hard copy of the Bills shall be priced and submitted."),cell("a","t1","r1","c3","(Alternative (a) - for tenders with Bills issued in DesignTool42 system)"),cell("b","t1","r1","c3","(Alternative (b) - for tenders with Bills issued in hard copy)"),cell("next","t1","r2","c1","4.")));
        int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope")){count++;assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList(group.path("label").asText().equals("alternative:a")?"electronic":"paper"),strings(group.path("targetMemberIds")));assertEquals(1,group.path("memberRanges").size());assertFalse(strings(group.path("targetMemberIds")).contains("title"));}assertEquals(2,count);
    }

    @Test void ocrRegionsAndNativeObservationsWithoutGlyphsRetainTheirRawSyntaxCapture() {
        DocumentBlock ocr=new DocumentBlock();ocr.setId("ocr");ocr.setSource("ocr");ocr.setKind("ocr_region");ocr.setPageNo(1);ocr.setText("[Guidance Note: Preserve this observed OCR instruction.]");JsonNode note=OerTemplateEvidence.analyse("SCC",pdf(ocr)).path("guidanceNotes").get(0);assertTrue(note.path("captureComplete").asBoolean());assertEquals(ocr.getText(),note.path("text").asText());assertEquals("not_applicable_ocr_or_region_observation_raw_syntax_capture_preserved",note.path("pdfLaneProjection").path("status").asText());assertEquals("not_assessed",note.path("pdfLaneProjection").path("marginLaneStatus").asText());
        ocr.setSource("native");JsonNode nativeNote=OerTemplateEvidence.analyse("SCC",pdf(ocr)).path("guidanceNotes").get(0);assertTrue(nativeNote.path("captureComplete").asBoolean());assertEquals("native_glyph_unavailable_raw_syntax_capture_preserved",nativeNote.path("pdfLaneProjection").path("status").asText());
    }

    @Test void unmarkedContinuationAfterTheLastParallelBranchCannotBeLostFromACompleteScope() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","3."),cell("electronic","t1","r1","c2","*(c) The Bills generated by DesignTool42 shall be submitted."),cell("paper","t1","r1","c2","*(c) The original hard copy of the Bills shall be submitted."),cell("tail","t1","r1","c2","The final hardcopy continuation must also be submitted."),cell("a","t1","r1","c3","(Alternative (a) - for tenders with Bills issued in DesignTool42 system)"),cell("b","t1","r1","c3","(Alternative (b) - for tenders with Bills issued in hard copy)"),cell("next","t1","r2","c1","4.")));
        int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_alternative_scope")){count++;assertFalse(group.path("scopeComplete").asBoolean());assertTrue(group.path("targetMemberIds").isEmpty());assertTrue(strings(group.path("rowContextMemberIds")).contains("tail"));}assertEquals(2,count);
    }

    @Test void amendmentListsExposeExactListRangesAndPreserveUnrelatedBusinessProse() {
        String body="(1) Payment remains due under Clause SCC20.302, Clause SCC20.303 and Clause SCC20.304 of the Special Conditions of Contract*. Other obligations remain.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC14.305 Advance payment"),p("n","[Guidance Note: * Amend in sub-clause (1) the reference of SCC Clauses adopted for price fluctuations and delete ‘*’.]"),p("b",body),p("two","(2) Next payment provision"),p("next","SCC15.301 Other clause")));
        JsonNode group=firstGroup(evidence,"explicit_adopted_reference_list_scope"),range=group.path("memberRanges").get(0);assertTrue(group.path("scopeComplete").asBoolean());assertEquals("SCC20.302, Clause SCC20.303 and Clause SCC20.304",body.substring(range.path("start").asInt(),range.path("end").asInt()));assertEquals(2,group.path("surroundingContextRanges").size());assertEquals("conditional_source_target",marker(evidence,"b","*").path("disposition").asText());
    }

    @Test void sameRowUseAndSelectInstructionsBindOnlyTheirLiteralSlashChoices() {
        String body="Use Appendix *G7/*G7a under the Contract.";JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","8."),cell("body","t1","r1","c2",body),cell("note","t1","r1","c3","(Use Appendix G7a where the combined contract requires it.)"),cell("next","t1","r2","c1","9.")));
        JsonNode group=firstGroup(evidence,"inline_source_choice_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(2,group.path("memberRanges").size());assertEquals(2,group.path("markerRanges").size());assertEquals("project_selection_required",group.path("requiredAction").asText());assertFalse(group.path("semanticApplicabilityVerified").asBoolean());
    }

    @Test void explicitPlaceholderSubclauseNeverAbsorbsAnotherSubclausesMarkedBlank() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC8.303 Extension of time"),p("n","[Guidance Note: Fill in the blank in sub-clause (1) with the designation of the Section.]"),p("one","(1) Section ____* is affected."),p("two","(2) Section ______* is separate."),p("next","SCC8.304 Another clause")));
        JsonNode group=firstGroup(evidence,"explicit_placeholder_scope");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("one"),strings(group.path("orderedMemberIds")));assertEquals(1,group.path("memberRanges").size());assertEquals("unknown",marker(evidence,"two","*").path("disposition").asText());
    }

    @Test void unsupportedSlashTokenContinuationsRemainUnknownInsteadOfBeingSilentlyTruncated() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("n","t1","r1","c1","8."),cell("body","t1","r1","c2","Use Appendix *G7/G7a-extra under the Contract."),cell("note","t1","r1","c3","(Use Appendix G7a where the combined contract requires it.)"),cell("next","t1","r2","c1","9.")));
        for(JsonNode group:evidence.path("groups"))assertFalse(group.path("kind").asText().equals("inline_source_choice_scope")&&group.path("scopeComplete").asBoolean());assertEquals("unknown",marker(evidence,"body","*").path("disposition").asText());
    }

    @Test void quotedAlternativesRequireWholeLiteralBoundariesOnBothSides() {
        for(String body:Arrays.asList("(1) often/twenty* days remain.","(1) pre-ten/twenty* days remain.","(1) foo.ten/twenty* days remain.","(1) ten/twenty-extra* days remain.")){JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC7.303 Warranty"),p("n","[Guidance Note: Delete inapplicable ‘ten’ or ‘twenty’.]"),p("body",body),p("next","SCC7.304 Another warranty")));JsonNode group=firstGroup(evidence,"explicit_quoted_choice_scope");assertFalse(group.path("scopeComplete").asBoolean());assertTrue(group.path("memberRanges").isEmpty());assertEquals("unknown",marker(evidence,"body","*").path("disposition").asText());}
    }

    @Test void sourceNamedReferenceNamespaceKeepsLaterFixedClauseReferencesAsContext() {
        String body="(b) Clauses *SCC1.301 to SCC1.307, SCC3.301, SCC5.304 and SCC6.302 of the Special Conditions of Contract.";
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC3.302 Sub-contract conditions"),p("one","(1) Provisions to include"),p("body",body),p("n","[Guidance Note: * Amend the reference of SCC Clauses adopted for “SCC1 DEFINITIONS AND INTERPRETATION” as appropriate.]"),p("next","SCC3.303 Another clause")));JsonNode group=firstGroup(evidence,"explicit_adopted_reference_list_scope"),range=group.path("memberRanges").get(0);assertTrue(group.path("scopeComplete").asBoolean());assertEquals("SCC1.301 to SCC1.307",body.substring(range.path("start").asInt(),range.path("end").asInt()));assertEquals("SCC1",group.path("instructionNamedNamespace").asText());assertTrue(group.path("sourceListObservationRanges").get(0).path("end").asInt()>range.path("end").asInt());
    }

    @Test void oneMetadataGlyphJumpingAcrossTheBodyLaneCannotDeleteTheWholeApprovalWord() {
        DocumentBlock first=pdfLine("l1",1,"Rev. 2 [Guidance Note: Keep the source.",7,.10),second=pdfLine("l2",1,"approved on The body ends.]",12,.115),date=pdfLine("l3",1,"01.12 .2022",11,.13);double[] wrong=second.getPdfGlyphs().get(8).getBbox().clone();wrong[0]=.60;second.getPdfGlyphs().get(8).setBbox(wrong);JsonNode note=OerTemplateEvidence.analyse("SCC",pdf(first,second,date)).path("guidanceNotes").get(0);assertFalse(note.path("captureComplete").asBoolean());assertTrue(note.path("revisionMarginContextRanges").isEmpty());assertEquals(note.path("rawText").asText(),note.path("text").asText());assertTrue(note.path("text").asText().contains("approved on The body ends.]"));
    }

    @Test void explicitWholeUnitsKeepBusinessReferencesAndSeparatePhysicalFurnitureAndChildParentHeadings() {
        JsonNode whole=OerTemplateEvidence.analyse("SCT",word(p("note","(Guidance Note: Condition SCT4 below applies where domestic blocks are involved.)"),cell("h","t1","r1","c1","*SCT4 Permitted modifications"),cell("body","t1","r2","c1","(1) The first business provision."),p("footer","HD(QS) 3C/121/1/2"),p("page","March 2020 - SCT/12 -"),cell("running","t2","r0","c1","SPECIAL CONDITIONS OF TENDER (Cont’d)"),cell("hc","t2","r1","c1","*SCT4 Permitted modifications (Cont’d)"),cell("more","t2","r2","c1","(2) The second business provision."),cell("ref","t2","r2","c2","SCT6(2)"),cell("next","t3","r1","c1","SCT5 Next condition")));
        JsonNode root=firstGroup(whole,"explicit_condition_unit_scope");assertTrue(root.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("h","body","hc","more","ref"),strings(root.path("orderedMemberIds")));assertTrue(strings(root.path("physicalContextMemberIds")).containsAll(Arrays.asList("footer","page","running")));assertTrue(root.path("parentContextMemberIds").isEmpty());
        JsonNode child=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("one","t1","r2","c1","(1) Earlier submissions."),p("note","(Guidance Note: Condition SCT6(2) below applies where domestic blocks are involved.)"),cell("two","t1","r3","c1","*(2) Design submissions."),cell("body","t1","r4","c1","(a) The business requirement."),cell("ref","t1","r4","c2","SCT4(2)"),p("footer","HD(QS) 3C/121/1/2"),p("page","March 2020 - SCT/20 -"),cell("running","t2","r0","c1","SPECIAL CONDITIONS OF TENDER (Cont’d)"),cell("parent","t2","r1","c1","SCT6 Other submissions (Cont’d)"),cell("more","t2","r2","c1","(b) The final business requirement."),cell("three","t2","r3","c1","(3) The next peer."),cell("next","t3","r1","c1","SCT7 Next condition")));
        JsonNode unit=firstGroup(child,"explicit_condition_unit_scope");assertTrue(unit.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("two","body","ref","more"),strings(unit.path("orderedMemberIds")));assertEquals(Collections.singletonList("parent"),strings(unit.path("parentContextMemberIds")));assertTrue(strings(unit.path("physicalContextMemberIds")).containsAll(Arrays.asList("footer","page","running")));
    }

    @Test void standaloneBodyOptionsKeepCompleteBranchesAndCommonParentProvisionsAsContext() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("common","(1) This common provision remains for both choices."),p("note","[Guidance Notes: Select either Option A or Option B as appropriate and delete the non-applicable one.]"),p("a","Option A"),p("blank",""),p("abody","(1) The first branch begins here."),p("atail","The unlabelled first-branch continuation remains included."),p("b","Option B"),p("bbody","(1) The other branch begins here."),p("bref","Reference #blue is ordinary business wording."),p("next","SCC11.303 Requested Variation")));
        List<JsonNode> options=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope"))options.add(group);assertEquals(2,options.size());for(JsonNode group:options){assertTrue(group.path("scopeComplete").asBoolean());assertFalse(group.path("markerRequired").asBoolean());assertFalse(group.path("semanticApplicabilityVerified").asBoolean());assertFalse(strings(group.path("targetMemberIds")).contains("common"));assertTrue(strings(group.path("parentContextMemberIds")).containsAll(Arrays.asList("h","common")));assertFalse(group.path("automaticEditEligible").asBoolean());}assertEquals(Arrays.asList("a","abody","atail"),strings(options.get(0).path("orderedMemberIds")));assertEquals(Arrays.asList("a","blank","abody","atail"),strings(options.get(0).path("nativeMemberIds")));assertEquals("b",options.get(0).path("endBoundary").path("memberId").asText());assertEquals(Arrays.asList("b","bbody","bref"),strings(options.get(1).path("orderedMemberIds")));assertEquals("next",options.get(1).path("endBoundary").path("memberId").asText());assertEquals("unknown",marker(evidence,"bref","#").path("disposition").asText());
    }

    @Test void bodyOptionsWithoutSameOwnerSelectionOrVerifiedFinalPeerStayUnknown() {
        JsonNode missing=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("a","Option A"),p("abody","First body."),p("b","Option B"),p("bbody","Second body."),p("next","SCC11.303 Requested Variation")));
        JsonNode noPeer=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("a","Option A"),p("abody","First body."),p("b","Option B"),p("bbody","Second body.")));
        for(JsonNode evidence:Arrays.asList(missing,noPeer)){int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope")){count++;assertFalse(group.path("scopeComplete").asBoolean());}assertEquals(2,count);assertTrue(hasIssue(evidence,"BODY_OPTION_SCOPE_INCOMPLETE"));}
    }

    @Test void duplicateStandaloneOptionLabelsCannotBeMadeCompleteByChoosingTheFirstPair() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("a","Option A"),p("abody","First body."),p("b","Option B"),p("bbody","Second body."),p("again","Option A"),p("tail","Unclassified repeated first branch."),p("next","SCC11.303 Requested Variation")));
        int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope")){count++;assertFalse(group.path("scopeComplete").asBoolean());}assertEquals(3,count);
    }

    @Test void inlineOptionMentionsAndAnotherClausesSelectionInstructionNeverBindBodyBranches() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h1","SCC11.301 Measurement"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("h2","SCC11.302 Schedule of rates"),p("mention","Option A is a phrase in this ordinary paragraph."),p("a","Option A"),p("abody","First body."),p("b","Option B"),p("bbody","Second body."),p("next","SCC11.303 Requested Variation")));
        int count=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope")){count++;assertFalse(group.path("scopeComplete").asBoolean());assertFalse(strings(group.path("orderedMemberIds")).contains("mention"));}assertEquals(2,count);
    }

    @Test void aBusinessProvisionEndingInAProfilePageReferenceIsNeverClassifiedAsFurniture() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(p("note","(Guidance Note: Condition SCT4 below applies where domestic blocks are involved.)"),cell("h","t1","r1","c1","*SCT4 Permitted modifications"),p("body","(1) The tenderer shall complete the form on SCT/12"),p("plainBusiness","The tenderer shall complete the other form on SCT/13"),p("footer","March 2020 - SCT/12 -"),cell("next","t2","r1","c1","SCT5 Next condition")));
        JsonNode group=firstGroup(evidence,"explicit_condition_unit_scope");assertTrue(group.path("scopeComplete").asBoolean());assertTrue(strings(group.path("orderedMemberIds")).containsAll(Arrays.asList("body","plainBusiness")));assertFalse(strings(group.path("physicalContextMemberIds")).contains("body"));assertFalse(strings(group.path("physicalContextMemberIds")).contains("plainBusiness"));assertTrue(strings(group.path("physicalContextMemberIds")).contains("footer"));
    }

    @Test void repeatedStandaloneBusinessMonthsRemainTargetsInWholeUnitsAndBodyOptions() {
        JsonNode whole=OerTemplateEvidence.analyse("SCT",word(p("note","(Guidance Note: Condition SCT4 below applies where domestic blocks are involved.)"),cell("h","t1","r1","c1","*SCT4 Permitted modifications"),p("dateOne","March 2020"),p("body","The preceding date is the first business value."),p("dateTwo","March 2020"),cell("next","t2","r1","c1","SCT5 Next condition")));
        JsonNode unit=firstGroup(whole,"explicit_condition_unit_scope");assertTrue(unit.path("scopeComplete").asBoolean());assertTrue(strings(unit.path("orderedMemberIds")).containsAll(Arrays.asList("dateOne","dateTwo")));assertFalse(strings(unit.path("physicalContextMemberIds")).contains("dateOne"));assertFalse(strings(unit.path("physicalContextMemberIds")).contains("dateTwo"));
        JsonNode choices=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("a","Option A"),p("aBody","The first business date follows."),p("dateA","March 2020"),p("b","Option B"),p("bBody","The second business date follows."),p("dateB","March 2020"),p("next","SCC11.303 Requested Variation")));
        int count=0;for(JsonNode group:choices.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope")){count++;assertTrue(group.path("scopeComplete").asBoolean());String expected=group.path("label").asText().equals("option:a")?"dateA":"dateB";assertTrue(strings(group.path("targetMemberIds")).contains(expected));assertFalse(strings(group.path("physicalContextMemberIds")).contains(expected));}assertEquals(2,count);
    }

    @Test void aStandaloneMonthNeedsItsAdjacentSameStoryHdAndProfilePageFooterEvidence() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(cell("numberA","t1","r1","c1","*2."),cell("bodyA","t1","r1","c2","First complete alternative."),cell("a","t1","r1","c3","(Alternative (a) - for electronic tenders)"),p("footer","HD(QS) 3C/121/2/10 - NTT/3 -"),p("blank",""),p("month","March 2020"),cell("numberB","t2","r1","c1","*2."),cell("bodyB","t2","r1","c2","Second complete alternative."),cell("b","t2","r1","c3","(Alternative (b) - for hardcopy tenders)"),cell("next","t3","r1","c1","3.")));
        JsonNode group=firstGroup(evidence,"complete_alternative_scope");assertTrue(group.path("scopeComplete").asBoolean());assertTrue(strings(group.path("interstitialContextMemberIds")).containsAll(Arrays.asList("footer","month")));assertFalse(strings(group.path("targetMemberIds")).contains("month"));
        ObjectNode foreign=p("footer","HD(QS) 3C/121/2/10 - NTT/3 -");foreign.put("storyKind","header");foreign.put("storyRootNodePath","headerRoot");JsonNode unrelated=OerTemplateEvidence.analyse("NTT",word(cell("numberA","t1","r1","c1","*2."),cell("bodyA","t1","r1","c2","First complete alternative."),cell("a","t1","r1","c3","(Alternative (a) - for electronic tenders)"),foreign,p("month","March 2020"),cell("numberB","t2","r1","c1","*2."),cell("b","t2","r1","c3","(Alternative (b) - for hardcopy tenders)"),cell("next","t3","r1","c1","3.")));
        assertFalse(firstGroup(unrelated,"complete_alternative_scope").path("scopeComplete").asBoolean());
    }

    @Test void labelledChildrenOfTheLastSiblingAreNotSharedTailsAndUnlabelledBusinessTailsStayUnknown() {
        for(boolean unassignedTail:Arrays.asList(false,true)){List<ObjectNode> source=new ArrayList<>(Arrays.asList(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("one","t1","r2","c1","(1) Required information:"),cell("a","t1","r3","c1","(a) Earlier business information."),cell("b","t1","r4","c1","*(b) Assessment of the ground conditions."),cell("bn","t1","r4","c2","(For use where foundation works are designed by the Contractor.)"),cell("c","t1","r5","c1","*(c) The following information is required:"),cell("i","t1","r5","c1","(i) A letter of intent."),cell("cn","t1","r5","c2","(For use where site formation works are involved.)"),cell("ii","t1","r6","c1","(ii) The names of the proposed directors."),cell("iii","t1","r6","c1","(iii) The proposed activity and scope.")));if(unassignedTail)source.add(cell("tail","t1","r7","c1","This unlabelled business paragraph has no proven parent or child owner."));source.add(cell("two","t1","r8","c1","(2) The next numeric parent."));source.add(cell("next","t2","r1","c1","SCT7 Next condition"));JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(source.toArray(new ObjectNode[0])));int count=0;
            for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("source_unit_instruction_scope")){count++;assertEquals(!unassignedTail,group.path("scopeComplete").asBoolean());if(!unassignedTail&&group.path("sourceReference").asText().equals("SCT6(1)(c)")){assertEquals(Arrays.asList("c","i","ii","iii"),strings(group.path("orderedMemberIds")));assertEquals("two",group.path("endBoundary").path("memberId").asText());}if(!unassignedTail&&group.path("sourceReference").asText().equals("SCT6(1)(b)"))assertEquals(Collections.singletonList("b"),strings(group.path("orderedMemberIds")));}assertEquals(2,count);
        }
    }

    @Test void guidanceFooterAndForeignStoryAfterTheLastSiblingNeverBecomeSharedBusinessTails() {
        ObjectNode footnote=p("footnote","shall remain a separate footnote observation.");footnote.put("storyKind","footnote");footnote.put("storyRootNodePath","footnoteRoot");
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("three","t1","r2","c1","(3) Tenderers should note the following:"),cell("a","t1","r3","c1","(a) The first independent condition."),cell("b","t1","r4","c1","(b) The second independent condition."),p("pre","(Guidance Note: Condition SCT6(3)(c) below applies where domestic blocks are in the Contract. Mark it as Not used if inapplicable.)"),cell("c","t1","r5","c1","*(c) The domestic-block condition (#and the special payment words) remains independent."),cell("words","t1","r5","c2","(# - Delete the words if precast facade is not applicable)"),cell("d","t2","r1","c1","*(d) Structural submissions shall be signed by the engineer."),cell("dn","t2","r1","c2","(Not used if there are no specific requirements on these submissions)"),p("extra","(Guidance Notes: Ensure any additional submissions are essential.)"),footnote,p("footer","HD(QS) 3C/121/1/2"),p("page","March 2020 - SCT/20 -"),cell("running","t3","r1","c1","SPECIAL CONDITIONS OF TENDER (Cont’d)"),cell("next","t3","r2","c1","SCT7 Next condition")));
        JsonNode c=firstGroup(evidence,"explicit_condition_unit_scope");assertTrue(c.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("c"),strings(c.path("orderedMemberIds")));assertTrue(marker(evidence,"c","*").path("scopeComplete").asBoolean());assertTrue(marker(evidence,"c","#").path("scopeComplete").asBoolean());
        for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("source_unit_instruction_scope")&&group.path("sourceReference").asText().equals("SCT6(3)(d)")){assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("d"),strings(group.path("orderedMemberIds")));assertTrue(strings(group.path("physicalContextMemberIds")).containsAll(Arrays.asList("footer","page","running")));assertFalse(strings(group.path("orderedMemberIds")).contains("footnote"));return;}fail("Missing complete final (d) scope");
    }

    @Test void anExplicitQuotedWordCanResolveInsideTheCertainPrefixWithoutAdoptingAnAmbiguousProviso() {
        for(boolean prefixHasWord:Arrays.asList(false,true)){String labelled=prefixHasWord?"(g) The current index applies #six calendar months after acceptance.":"(g) The current index is stated in the Contract.";JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.302 Electrical fluctuations"),p("note","[Guidance Note: # Amend the word “six” in Clause SCC20.302(2)(g).]"),p("two","(2) The indices are defined as follows:"),p("f","(f) The base index is stated in the Contract."),p("condition","[Guidance Note: Condition SCC20.302(2)(g) below applies where index adjustment is required.]"),p("g",labelled),p("proviso","Provided that #six is mentioned in this unassigned business proviso."),p("three","(3) The next numeric peer."),p("next","SCC20.303 Other fluctuations")));
            JsonNode whole=firstGroup(evidence,"explicit_condition_unit_scope");assertFalse(whole.path("scopeComplete").asBoolean());assertEquals("ambiguous_parent_tail",whole.path("targets").get(0).path("status").asText());assertEquals("unknown",marker(evidence,"proviso","#").path("disposition").asText());int exact=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("explicit_quoted_word_scope")){exact++;assertTrue(prefixHasWord);assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("g"),strings(group.path("orderedMemberIds")));JsonNode range=group.path("memberRanges").get(0);assertEquals("six",labelled.substring(range.path("start").asInt(),range.path("end").asInt()));}assertEquals(prefixHasWord?1:0,exact);
        }
    }

    @Test void anExplicitSpecialistListCanResolveInAnEarlierLabelledPrefixWhileTheUnitTailStaysUnknown() {
        for(boolean prefixHasList:Arrays.asList(false,true)){String labelled=prefixHasList?"(i) less all sums in respect of lift Specialist Sub-contract Works, electrical Specialist Sub-contract Works* and other items; and":"(i) The first labelled sum is defined in the Contract.";JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.301 Contract price fluctuations"),p("note","[Guidance Note: * Project team to amend types of Specialist Sub-contract Works in sub-clause (3)(d)(i).]"),p("three","(3) The effective value is calculated as follows:"),p("d","(d) The value is the difference between:"),p("condition","[Guidance Note: Condition SCC20.301(3)(d)(i) below applies where specialist works are included.]"),p("i",labelled),p("ii","(ii) The sum in the preceding payment certificate."),p("proviso","Provided that the sum for lift Specialist Sub-contract Works* is mentioned in this unassigned proviso."),p("four","(4) The next numeric peer."),p("next","SCC20.302 Electrical fluctuations")));
            JsonNode whole=firstGroup(evidence,"explicit_condition_unit_scope");assertFalse(whole.path("scopeComplete").asBoolean());assertEquals("ambiguous_parent_tail",whole.path("targets").get(0).path("status").asText());assertEquals("unknown",marker(evidence,"proviso","*").path("disposition").asText());int exact=0;for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("explicit_specialist_type_list_scope")){exact++;assertTrue(prefixHasList);assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("i"),strings(group.path("orderedMemberIds")));JsonNode range=group.path("memberRanges").get(0);assertEquals("lift Specialist Sub-contract Works, electrical Specialist Sub-contract Works",labelled.substring(range.path("start").asInt(),range.path("end").asInt()));}assertEquals(prefixHasList?1:0,exact);
        }
    }

    @Test void anExactQuotedWordNeverUsesAForeignStoryAfterTheLastBodyClause() {
        ObjectNode foreign=foreign("fn","#six remains a footnote observation.");JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.302 Electrical fluctuations"),p("note","[Guidance Note: # Amend the word “six” in Clause SCC20.302(2)(g).]"),p("two","(2) The indices are defined as follows:"),p("f","(f) The base index is stated in the Contract."),p("g","(g) The current index is stated in the Contract."),foreign));
        assertEquals("unknown",marker(evidence,"fn","#").path("disposition").asText());for(JsonNode group:evidence.path("groups"))assertNotEquals("explicit_quoted_word_scope",group.path("kind").asText());
    }

    @Test void anExplicitSpecialistListNeverUsesAForeignStoryAfterTheLastBodyClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.301 Contract price fluctuations"),p("note","[Guidance Note: * Project team to amend types of Specialist Sub-contract Works in sub-clause (3)(d)(i).]"),p("three","(3) The effective value is calculated as follows:"),p("d","(d) The value is the difference between:"),p("i","(i) The first labelled sum is defined in the Contract."),foreign("fn","The footnote is in respect of lift Specialist Sub-contract Works* and other items.")));
        assertEquals("unknown",marker(evidence,"fn","*").path("disposition").asText());for(JsonNode group:evidence.path("groups"))assertNotEquals("explicit_specialist_type_list_scope",group.path("kind").asText());
    }

    @Test void theClauseReferenceListOverrideNeverUsesAForeignStory() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC3.302 Sub-contract conditions"),p("one","(1) The adopted references are specified in the Contract."),p("note","[Guidance Note: * Amend the reference of SCC Clauses adopted for “SCC1 DEFINITIONS AND INTERPRETATION” as appropriate.]"),foreign("fn","SCC1.301 to SCC1.307* remain footnote context.")));
        assertEquals("unknown",marker(evidence,"fn","*").path("disposition").asText());for(JsonNode group:evidence.path("groups"))assertNotEquals("explicit_adopted_reference_list_scope",group.path("kind").asText());
    }

    @Test void aWholeClauseFillInInstructionNeverUsesAForeignPlaceholder() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC3.302 Sub-contract conditions"),p("note","[Guidance Note: * Fill in the blank from project facts.]"),p("one","(1) The value is specified in the Contract."),foreign("fn","____* is unrelated footnote context.")));JsonNode group=firstGroup(evidence,"explicit_placeholder_scope");assertFalse(group.path("scopeComplete").asBoolean());assertEquals(0,group.path("memberRanges").size());assertEquals("unknown",marker(evidence,"fn","*").path("disposition").asText());
    }

    @Test void aForeignInstructionCannotAdoptABodyTargetWithoutASourceRelationship() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC20.302 Electrical fluctuations"),p("two","(2) The indices are defined as follows:"),p("f","(f) The base index is stated in the Contract."),p("g","(g) The current index applies #six calendar months after acceptance."),foreign("fn","[Guidance Note: # Amend the word “six” in Clause SCC20.302(2)(g).]")));assertEquals("unknown",marker(evidence,"g","#").path("disposition").asText());for(JsonNode group:evidence.path("groups"))assertNotEquals("explicit_quoted_word_scope",group.path("kind").asText());
    }

    @Test void visibleUnmarkedFieldsKeepOnlyTheirExactLocalPlaceholderRuns() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(p("bills","Bills No. 3 to ___ generated by the program"),p("address","Inspect at the …........... floor, Block ......, Authority Headquarters."),p("definition","Technical proposals are in Envelope _____________ referred to in Clause SCT_____________."),p("tree1","Tree No. ____________."),p("tree2","If Tree No. _________ dies the payment applies."),p("tree3","Provided that Tree No. ________ was protected."),p("marked","The marked value is Envelope ___* and must use its own instruction.")));
        List<JsonNode> fields=sourceFields(evidence);assertEquals(8,fields.size());for(JsonNode field:fields){assertTrue(field.path("captureComplete").asBoolean());assertTrue(field.path("sourceFieldRangeComplete").asBoolean());assertFalse(field.path("scopeComplete").asBoolean());assertFalse(field.path("automaticEditEligible").asBoolean());JsonNode range=field.path("memberRanges").get(0);assertEquals("text",range.path("channel").asText());assertTrue(range.path("observedText").asText().matches("[_.…]+"));assertNotEquals("marked",range.path("memberId").asText());}assertEquals("unknown",marker(evidence,"marked","*").path("disposition").asText());
    }

    @Test void nativeWhitespaceSlotsUseTheNamedPreNormalizedChannelAndRoleNotesSupplyNoValues() {
        String raw="Officer is A/     (*Mr/Ms                     ), telephone          .\n";ObjectNode body=raw(cell("body","t","r","c1","Officer is A/ (*Mr/Ms ), telephone ."),raw);JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(body,cell("note","t","r","c2","(Project Architect)")));assertEquals(1,evidence.path("guidanceNotes").size());List<JsonNode> fields=sourceFields(evidence);assertEquals(4,fields.size());int spaces=0;for(JsonNode field:fields){assertEquals(1,field.path("guidanceNoteIds").size());assertFalse(field.path("automaticEditEligible").asBoolean());assertFalse(field.path("scopeComplete").asBoolean());assertEquals("unknown_requires_project_source",field.path("fieldValueStatus").asText());JsonNode range=field.path("memberRanges").get(0);if(range.path("channel").asText().equals("preNormalizedTextObservation.text")){spaces++;assertEquals(raw.substring(range.path("start").asInt(),range.path("end").asInt()),range.path("observedText").asText());assertTrue(range.path("observedText").asText().matches(" {5,21}"));}else{assertEquals("Mr/Ms",range.path("observedText").asText());assertEquals(2,field.path("optionRanges").size());assertTrue(field.path("sourceChoiceComplete").asBoolean());}}assertEquals(3,spaces);assertEquals("unknown",marker(evidence,"body","*").path("disposition").asText());
    }

    @Test void collapsedOrLexicallyDifferentRawObservationsCannotInventWhitespaceSlots() {
        for(boolean mismatch:Arrays.asList(false,true)){ObjectNode body=p("body","Officer is A/ (*Mr/Ms ), telephone .");if(mismatch)raw(body,"Officer is A/     (*Mr/Ms DIFFERENT), telephone          .\n");JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(body));List<JsonNode> fields=sourceFields(evidence);assertEquals(1,fields.size());assertEquals("person_title_choice",fields.get(0).path("fieldLabel").asText());assertEquals("unknown",marker(evidence,"body","*").path("disposition").asText());}
    }

    @Test void emptyBillDescriptionsExposeInsertionAnchorsWithoutInventingReplacementExtentsOrListLength() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("first","t","r","c1","Bill No. 3 –"),raw(cell("second","t","r","c1","Bill No. 4 –"),"\tBill No. 4 –\t     \n"),cell("filled","t","r","c1","Bill No. 5 – Existing description"),cell("note","t","r","c2","(Insert all Bill Nos. and descriptions. Additional rows may be required.)")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields){assertEquals("bill_description",field.path("fieldLabel").asText());assertTrue(field.path("sourceInsertionAnchorComplete").asBoolean());assertFalse(field.path("sourceFieldRangeComplete").asBoolean());assertFalse(field.path("scopeComplete").asBoolean());assertEquals(0,field.path("memberRanges").size());JsonNode anchor=field.path("insertionAnchors").get(0);assertEquals(anchor.path("start"),anchor.path("end"));assertEquals("",anchor.path("observedText").asText());assertEquals("unknown_from_existing_numbered_source_rows",field.path("listCardinalityStatus").asText());assertEquals(1,field.path("guidanceNoteIds").size());}
    }

    @Test void billDescriptionInstructionsCannotCrossNativeRowsOrStories() {
        ObjectNode note=cell("foreign","t","r1","c2","(Insert all Bill Nos. and descriptions.)");note.put("storyKind","footnote");note.put("storyRootNodePath","foreign");((ObjectNode)note.path("sourcePosition")).put("partName","word/footnotes.xml");JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("empty","t","r1","c1","Bill No. 3 –"),cell("other","t","r2","c2","(Insert all Bill Nos. and descriptions.)"),note));assertEquals(0,sourceFields(evidence).size());
    }

    @Test void aSingleTreeInstructionAssociatesThreeObservedSlotsIncludingTheProviso() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC22.301 Preservation of tree"),p("note","[Guidance Notes: This clause is for a single Old and Valuable Tree. Check the serial number of the Old and Valuable Tree and insert it in both Clauses SCC22.301(1) and SCC22.301(2).]"),p("one","(1) Preserve Old and Valuable Tree No. ____________."),p("two","(2) If Tree No. _________ dies the payment applies."),p("proviso","Provided that Tree No. ________ was protected the payment does not apply."),p("next","SCC22.302 Aftercare"),p("other","Tree No. ______ is in another clause.")));List<JsonNode> fields=sourceFields(evidence);assertEquals(4,fields.size());Set<String> sameValues=new HashSet<>();for(JsonNode field:fields){if(field.path("memberRanges").get(0).path("memberId").asText().equals("other")){assertEquals(0,field.path("guidanceNoteIds").size());assertFalse(field.has("sameValueSourceGroupId"));}else{assertEquals(1,field.path("guidanceNoteIds").size());sameValues.add(field.path("sameValueSourceGroupId").asText());}}assertEquals(1,sameValues.size());assertFalse(sameValues.contains(""));
    }

    @Test void envelopeAndClauseFieldsUseTheSameClauseInstructionWithoutSupplyingNumbers() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number and the relevant SCT clause number according to the source contract.]"),p("body","The Envelope _____________ is referred to in Clause SCT_____________."),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields){assertEquals(1,field.path("guidanceNoteIds").size());assertEquals("one_source_context_relationship",field.path("instructionAssociationStatus").asText());assertEquals("same_native_clause_and_explicit_field_instruction",field.path("sourceInstructionRelationships").get(0).path("basis").asText());assertFalse(field.has("fieldValue"));}
    }

    @Test void foreignOrUnboundNativeMembersCannotSupplyFieldCandidates() {
        ObjectNode unbound=p("unbound","Envelope _____________");((ObjectNode)unbound.path("sourcePosition")).put("sourceSha256",OTHER);JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(unbound,foreign("foreign","Envelope _____________")));assertEquals(0,sourceFields(evidence).size());assertTrue(hasIssue(evidence,"MEMBER_SOURCE_MISMATCH"));
    }

    @Test void ordinaryGuidanceReferencesNeverBecomeInstructionLabels() {
        JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(p("user","6. An asterisk * denotes optional alternative for selection/amendment/deletion. Delete all guidance notes and side notes in italics."),p("prose","Read the guidance notes: the author gives examples."),p("quote","The phrase “Guidance Note: optional” is quoted in ordinary prose.")));assertEquals(0,evidence.path("guidanceNotes").size());
    }

    @Test void explicitGuidanceLabelsSurviveMixedRevisionPrefixesAndNamedClauseSuffixes() {
        for(String prefix:Arrays.asList("","SCC ","approved on ","Rev. 1 ","01.12.2022 ")){JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("note",prefix+"[Guidance Notes to SCC22.8(5)(c): Select the applicable source option.]")));assertEquals(1,evidence.path("guidanceNotes").size());assertEquals("[Guidance Notes to SCC22.8(5)(c): Select the applicable source option.]",evidence.path("guidanceNotes").get(0).path("text").asText());assertTrue(evidence.path("guidanceNotes").get(0).path("sourceSyntaxComplete").asBoolean());}
    }

    @Test void unselectedOcrCandidatesRetainTheirWholeObservationsWithoutOriginalSyntaxAuthority() {
        DocumentBlock ocr=new DocumentBlock();ocr.setId("ocr");ocr.setSource("ocr");ocr.setKind("ocr_region");ocr.setPageNo(1);ocr.setText("[Guidance Note: Delete the inapplicable choice.]");ObjectNode pageGroup=pageGroup();DocumentBlock group=groupBlock(pageGroup);JsonNode evidence=OerTemplateEvidence.analyse("SCC",pdf(ocr,group)),note=evidence.path("guidanceNotes").get(0);assertTrue(note.path("captureComplete").asBoolean());assertTrue(note.path("observedSyntaxComplete").asBoolean());assertFalse(note.path("sourceSyntaxKnown").asBoolean());assertFalse(note.path("sourceSyntaxComplete").asBoolean());assertTrue(hasIssue(evidence,"DERIVED_GUIDANCE_SOURCE_SYNTAX_UNVERIFIED"));assertTrue(hasIssue(evidence,"PARSER_PAGE_CANDIDATES_UNSELECTED"));assertEquals(1,evidence.path("route").path("unselectedOrUnverifiedParserPageGroupCount").asInt());assertEquals("unverified_observation_only_no_source_quality_acceptance",evidence.path("route").path("legacyEffectiveTextAuthority").asText());assertEquals(pageGroup,evidence.path("sourceMembers").get(1).path("blockObservation").path("parserPageGroup"));assertFalse(hasIssue(evidence,"PARSER_PAGE_GROUP_SOURCE_BINDING_MISMATCH"));
    }

    @Test void foreignParserGroupSourcesPagesAndCandidateFramesAreRetainedWithBindingIssues() {
        for(int failure=0;failure<4;failure++){ObjectNode group=pageGroup();if(failure==0)((ObjectNode)group.path("source")).put("sourceSha256",OTHER);if(failure==1)group.put("physicalPageNo",2);if(failure==2)((ObjectNode)group.path("candidates").get(0).path("page")).put("physicalPageNo",2);if(failure==3)((ObjectNode)group.path("candidates").get(0).path("page").path("mapping").path("originalPageTransform").path("targetFrame")).put("sourceSha256",OTHER);JsonNode evidence=OerTemplateEvidence.analyse("SCC",pdf(groupBlock(group)));assertTrue(hasIssue(evidence,"PARSER_PAGE_GROUP_SOURCE_BINDING_MISMATCH"));assertEquals("source_or_page_mismatch_present",evidence.path("route").path("parserPageGroupBindingStatus").asText());assertEquals(group,evidence.path("sourceMembers").get(0).path("blockObservation").path("parserPageGroup"));}
    }

    @Test void treeInstructionsAssociateOnlyTheirExplicitlyNamedNativeSubclauses() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC22.301 Preservation of tree"),p("note","[Guidance Notes: The surrounding context mentions SCC22.301(1). This clause is for a single Old and Valuable Tree. Check the serial number of the Old and Valuable Tree and insert it in Clause SCC22.301(2).]"),p("one","(1) Another Tree No. ____________ is not named by the insertion directive."),p("two","(2) Preserve Tree No. _________."),p("proviso","Provided that Tree No. ________ was protected the payment does not apply."),p("three","(3) A different Tree No. _______ is outside the named source unit."),p("next","SCC22.302 Aftercare")));List<JsonNode> fields=sourceFields(evidence);assertEquals(4,fields.size());int related=0;Set<String> same=new HashSet<>();for(JsonNode field:fields){String member=field.path("memberRanges").get(0).path("memberId").asText();if(member.equals("two")||member.equals("proviso")){related++;assertEquals(1,field.path("guidanceNoteIds").size());same.add(field.path("sameValueSourceGroupId").asText());}else{assertEquals(0,field.path("guidanceNoteIds").size());assertFalse(field.has("sameValueSourceGroupId"));}}assertEquals(2,related);assertEquals(1,same.size());assertFalse(same.contains(""));
    }

    @Test void definitionFieldInstructionsCannotSpreadToOtherDefinitionsInTheSameClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number containing the technical proposals and the relevant SCT clause number.]"),p("technical","“Technical Proposals” means the proposals in Envelope _______ referred to in Clause SCT_______."),p("financial","“Financial Proposals” means the proposals in Envelope _______ referred to in Clause SCT_______."),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(4,fields.size());for(JsonNode field:fields){String member=field.path("memberRanges").get(0).path("memberId").asText();assertEquals(member.equals("technical")?1:0,field.path("guidanceNoteIds").size());}
    }

    @Test void indistinguishableSameClauseFieldsRemainCandidatesWithoutUniqueInstructionAssociation() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number.]"),p("one","The first Envelope _______ contains a submission."),p("two","The second Envelope _______ contains a different submission."),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields){assertEquals(0,field.path("guidanceNoteIds").size());assertTrue(field.path("sourceFieldRangeComplete").asBoolean());assertFalse(field.path("automaticEditEligible").asBoolean());}
    }

    @Test void aParentheticalReferenceToGuidanceIsNotADirectInstructionLabel() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("prose","This term includes (a reference to Guidance Note: Select either Option A or Option B) in its glossary."),p("a","Option A"),p("abody","First business choice."),p("b","Option B"),p("bbody","Second business choice."),p("next","SCC11.303 Other rates")));assertEquals(0,evidence.path("guidanceNotes").size());for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope"))assertFalse(group.path("scopeComplete").asBoolean());
    }

    @Test void quotedBracketedGuidanceExamplesSupplyNoInstructionAuthority() {
        for(String quoted:Arrays.asList("The glossary quotes “[Guidance Note: Select either Option A or Option B.]” as an example.","The glossary quotes \"[Guidance Note: Select either Option A or Option B.]\" as an example.","The glossary quotes '[Guidance Note: Select either Option A or Option B.]' as an example.")){JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC11.302 Schedule of rates"),p("quoted",quoted),p("a","Option A"),p("abody","First business choice."),p("b","Option B"),p("bbody","Second business choice."),p("next","SCC11.303 Other rates")));assertEquals(0,evidence.path("guidanceNotes").size());for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("complete_body_option_scope"))assertFalse(group.path("scopeComplete").asBoolean());}
    }

    @Test void numberedUnrelatedDefinitionsCannotBorrowAnInstructionWhenTheNamedDefinitionIsAlreadyFilled() {
        for(String prefix:Arrays.asList("(2) ","*(2) ","(2)(a) ")){JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number containing the technical proposals and the relevant SCT clause number.]"),p("technical","(1) “Technical Proposals” means proposals in Envelope 2 referred to in Clause SCT12."),p("financial",prefix+"“Financial Proposals” means proposals in Envelope _______ referred to in Clause SCT_______."),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields){assertEquals(0,field.path("guidanceNoteIds").size());assertTrue(field.path("sourceFieldRangeComplete").asBoolean());assertFalse(field.path("automaticEditEligible").asBoolean());}}
    }

    @Test void knownSubitemAndMarkerPrefixesStillPermitTheExplicitlyNamedDefinition() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number containing the technical proposals and the relevant SCT clause number.]"),p("technical","*(1)(a) “Technical Proposals” means proposals in Envelope _______ referred to in Clause SCT_______."),p("financial","(2) “Financial Proposals” means proposals in Envelope 2 referred to in Clause SCT12."),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields)assertEquals(1,field.path("guidanceNoteIds").size());
    }

    @Test void anUnrecognizedMeansDefinitionKeepsItsFieldsWithoutInferredInstructionAuthority() {
        for(String definition:Arrays.asList("Financial Proposals means proposals in Envelope _______ referred to in Clause SCT_______.","(2) <Financial Proposals> means proposals in Envelope _______ referred to in Clause SCT_______.")){JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC1.301 Definitions"),p("note","[Guidance Note: Insert the relevant Envelope number containing the technical proposals and the relevant SCT clause number.]"),p("financial",definition),p("next","SCC1.302 Other definitions")));List<JsonNode> fields=sourceFields(evidence);assertEquals(2,fields.size());for(JsonNode field:fields)assertEquals(0,field.path("guidanceNoteIds").size());}
    }

    private static List<JsonNode> sourceFields(JsonNode evidence){List<JsonNode> result=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("native_source_field_candidate"))result.add(group);return result;}
    private static ObjectNode raw(ObjectNode p,String text){ObjectNode raw=p.putObject("preNormalizedTextObservation");raw.put("status","observed_parser_channels_before_whitespace_normalization");raw.put("text",text);raw.put("originalText",text);raw.put("deletedText","");raw.put("strikeText","");return p;}
    private static ObjectNode pageGroup(){ObjectNode group=object();group.put("physicalPageNo",1);group.put("scope","whole_page");group.putObject("source").put("sourceSha256",SHA).put("sourceRevisionSha256",SHA);group.put("selectionStatus","candidate_unverified");group.putNull("selectedParser");group.put("sourceQualityAccepted",false);ObjectNode candidate=group.putArray("candidates").addObject();candidate.put("parser","first");ObjectNode page=candidate.putObject("page");page.put("physicalPageNo",1);page.putObject("mapping").putObject("originalPageTransform").putObject("targetFrame").put("sourceSha256",SHA).put("physicalPageNo",1);candidate.putArray("blocks").addObject().put("text","all candidate contents retained");return group;}
    private static DocumentBlock groupBlock(JsonNode pageGroup){DocumentBlock group=new DocumentBlock();group.setId("group");group.setSource("parser_page_group_candidate");group.setPageNo(1);group.setText("");group.setParserPageGroup(pageGroup);return group;}
    private static ObjectNode foreign(String id,String text){ObjectNode p=p(id,text);p.put("storyKind","footnote");p.put("storyRootNodePath","footnoteRoot");((ObjectNode)p.path("sourcePosition")).put("partName","word/footnotes.xml");return p;}
    private static DocumentParser.ParsedDocument pdf(DocumentBlock... blocks){ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(2);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),2,false,"fixture",Arrays.asList(blocks),coverage,SHA,Collections.emptyList());}
    private static DocumentBlock pdfLine(String id,int page,String text,int smallPrefixLength,double y){DocumentBlock block=new DocumentBlock();block.setId(id);block.setSource("native");block.setKind("pdf_line");block.setPageNo(page);block.setText(text);List<PdfPageEvidence.PdfGlyph> glyphs=new ArrayList<>();double smallX=.10,bodyX=.20;for(int n=0;n<text.length();n++){if(Character.isWhitespace(text.charAt(n)))continue;boolean small=n<smallPrefixLength;PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId(id+":"+n);glyph.setUnicode(text.substring(n,n+1));glyph.setBbox(new double[]{small?smallX:bodyX,y,small?.003:.005,.004});glyph.setFontSizeInPt(small?6:9);glyph.setEligibleForNativeText(true);glyphs.add(glyph);if(small)smallX+=.003;else bodyX+=.005;}block.setPdfGlyphs(glyphs);return block;}

    private static DocumentParser.ParsedDocument word(ObjectNode... paragraphs){ParseCoverage coverage=new ParseCoverage();ObjectNode inventory=object();inventory.put("version","docx-native-paragraphs-v1");inventory.put("sourceSha256",SHA);ArrayNode list=inventory.putArray("paragraphs");for(ObjectNode p:paragraphs)list.add(p);coverage.setNativeDocxParagraphs(inventory);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),0,false,"fixture",Collections.emptyList(),coverage,SHA,Collections.emptyList());}
    private static ObjectNode p(String id,String text){ObjectNode p=object();p.put("id",id);p.put("storyKind","body");p.put("storyRootNodePath",BODY);ObjectNode pos=p.putObject("sourcePosition");pos.put("kind","docx_native");pos.put("sourceSha256",SHA);pos.put("partName","word/document.xml");pos.put("partSha256",OTHER);pos.put("nodePath",BODY+"/{w}p["+id+"]");ObjectNode channels=p.putObject("textObservation");channels.put("status","observed_parser_paragraph_channels");channels.put("text",text);channels.put("originalText",text);channels.put("deletedText","");channels.put("strikeText","");return p;}
    private static ObjectNode cell(String id,String table,String row,String cell,String text){ObjectNode p=p(id,text);String t=BODY+"/{w}tbl["+table+"]",r=t+"/{w}tr["+row+"]",c=r+"/{w}tc["+cell+"]";p.put("tableNodePath",t);p.put("rowNodePath",r);p.put("cellNodePath",c);((ObjectNode)p.path("sourcePosition")).put("nodePath",c+"/{w}p["+id+"]");return p;}
    private static JsonNode firstGroup(JsonNode evidence,String kind){for(JsonNode group:evidence.path("groups"))if(kind.equals(group.path("kind").asText()))return group;throw new AssertionError("Missing group "+kind);}
    private static JsonNode marker(JsonNode evidence,String member,String symbol){for(JsonNode marker:evidence.path("markerCandidates"))if(member.equals(marker.path("memberId").asText())&&symbol.equals(marker.path("symbol").asText()))return marker;throw new AssertionError("Missing marker");}
    private static ObjectNode indent(ObjectNode p,int left,int hanging){ObjectNode attributes=p.putObject("directParagraphLayout").putArray("ind").addObject().putObject("attributes");attributes.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}left",String.valueOf(left));if(hanging!=0)attributes.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}hanging",String.valueOf(hanging));return p;}
    private static boolean hasIssue(JsonNode evidence,String code){for(JsonNode issue:evidence.path("issues"))if(code.equals(issue.path("code").asText()))return true;return false;}
    private static List<String> strings(JsonNode values){List<String> result=new ArrayList<>();for(JsonNode value:values)result.add(value.asText());return result;}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
}
