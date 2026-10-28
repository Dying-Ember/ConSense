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

    @Test void streamedCompactReadingKeepsTheSameBindingAndRejectsChangedGlyphs() {
        DocumentBlock block=pdfLine("page-one",1,"Special Conditions of Contract",0,.2);
        ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(1);
        DocumentParser.ParsedDocument parsed=new DocumentParser.ParsedDocument("text",Collections.emptyList(),1,false,"fixture",Collections.singletonList(block),coverage,SHA,Collections.emptyList());
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed),full=tree(parsed.getBlocks());ObjectNode saved=tree(coverage).deepCopy();saved.set("templateEvidence",evidence);
        assertEquals(evidence,OerTemplateEvidence.requireBound("SCC",SHA,full,saved,evidence));
        JsonNode compact=OerTemplateEvidence.compactBoundBlocks("SCC",SHA,new java.io.StringReader(JsonUtils.write(full)),saved,evidence);
        assertEquals(full.get(0).path("text"),compact.get(0).path("text"));assertFalse(compact.get(0).has("pdfGlyphs"));
        JsonNode changed=full.deepCopy();((ObjectNode)changed.get(0).path("pdfGlyphs").get(0)).put("unicode","X");
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.compactBoundBlocks("SCC",SHA,new java.io.StringReader(JsonUtils.write(changed)),saved,evidence));
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.compactBoundBlocks("SCC",OTHER,new java.io.StringReader(JsonUtils.write(full)),saved,evidence));
        ObjectNode tampered=evidence.deepCopy();tampered.put("testTamper",true);
        assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.compactBoundBlocks("SCC",SHA,new java.io.StringReader(JsonUtils.write(full)),saved,tampered));
    }

    @Test void oldOrMissingAnalysisRevisionIsStaleEvenWhenTheSourceAndStoredInputsHaveNotChanged() {
        DocumentParser.ParsedDocument parsed=word(p("a","Special Conditions of Contract"));JsonNode evidence=OerTemplateEvidence.analyse("SCC",parsed);ObjectNode old=evidence.deepCopy();((ObjectNode)old.path("sourceBinding")).put("analysisRevision","old-rules");
        assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
        ((ObjectNode)old.path("sourceBinding")).put("analysisRevision","native-template-rules-20261016-r15");assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
        ((ObjectNode)old.path("sourceBinding")).put("analysisRevision","native-template-rules-20261018-r16");assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
        ((ObjectNode)old.path("sourceBinding")).put("analysisRevision","native-template-rules-20261019-r18");assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
        ((ObjectNode)old.path("sourceBinding")).put("analysisRevision","native-template-rules-20261019-r19");assertEquals("TEMPLATE_EVIDENCE_REVISION_STALE",assertThrows(IllegalArgumentException.class,()->OerTemplateEvidence.requireBound("SCC",SHA,tree(parsed.getBlocks()),tree(parsed.getCoverage()),old)).getMessage());
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

    @Test void aNativeSccLibraryTitleRoutesWithoutAnExplicitFamilyOrAnotherCoverTitle() throws Exception {
        ObjectNode title=p("title","HKHA Standard Library of SCC for Building Works 2026 Edition"),owner=p("owner","SCC22.1 Source obligations"),one=p("one","(1) The stated source requirement."),next=p("next","SCC22.2 Another source clause");DocumentParser.ParsedDocument parsed=word(title,owner,one,next);assertEquals("SCC",OerTemplateEvidence.detectFamily(parsed));assertEquals("SCC",OerTemplateEvidence.analyse(null,parsed).path("sourceBinding").path("fileKey").asText());JsonNode scopes=clauseProjection(title,owner,one,next);assertEquals("SCC",scopes.path("familyCandidate").asText());assertEquals("next",clauseRange(scopes,"SCC22.1").path("endBoundary").path("memberId").asText());
        ObjectNode ordinary=p("ordinary","HKHA Standard Library of drainage materials for Building Works");assertNull(OerTemplateEvidence.detectFamily(word(ordinary)));JsonNode unknown=clauseProjection(ordinary);assertTrue(unknown.path("familyCandidate").isNull());assertTrue(unknown.path("ranges").isEmpty());
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

    @Test void aLeadingExplicitSelectionBelongsToBothCompleteBranchesIncludingContinuationMarkers() {
        List<ObjectNode> paragraphs=commonSelectionMembers();JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(paragraphs.toArray(new ObjectNode[0])));String commonId=evidence.path("guidanceNotes").get(0).path("id").asText(),conditionId=evidence.path("conditions").get(0).path("id").asText();
        List<JsonNode> branches=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if("complete_alternative_scope".equals(group.path("kind").asText()))branches.add(group);
        assertEquals(2,branches.size());JsonNode relation=branches.get(0).path("sourceInstructionRelationships").get(0);assertNotNull(relation);assertEquals("explicit_alternative_selection",relation.path("kind").asText());assertEquals("SCT91",relation.path("ownerReference").asText());assertEquals(commonId,relation.path("guidanceNoteId").asText());assertEquals(conditionId,relation.path("conditionId").asText());assertEquals(Arrays.asList(branches.get(0).path("id").asText(),branches.get(1).path("id").asText()),strings(relation.path("groupIds")));assertEquals(evidence.path("guidanceNotes").get(0).path("memberRanges"),relation.path("instructionMemberRanges"));
        for(JsonNode branch:branches){assertTrue(branch.path("scopeComplete").asBoolean());assertFalse(branch.path("automaticEditEligible").asBoolean());assertTrue(strings(branch.path("guidanceNoteIds")).contains(commonId));assertEquals(relation,branch.path("sourceInstructionRelationships").get(0));}
        for(String member:Arrays.asList("ha","hac","hb","hbc")){JsonNode marker=marker(evidence,member,"*");assertEquals("conditional_source_target",marker.path("disposition").asText());assertTrue(strings(marker.path("guidanceNoteIds")).contains(commonId));assertTrue(strings(marker.path("conditionIds")).contains(conditionId));}
        assertEquals(evidence,OerTemplateEvidence.analyse("SCT",word(paragraphs.toArray(new ObjectNode[0]))));
    }

    @Test void commonSelectionRejectsDuplicateIncompleteForeignOrUnrelatedOwners() {
        for(int variant=0;variant<10;variant++){
            List<ObjectNode> paragraphs=commonSelectionMembers();
            if(variant==0)paragraphs.add(1,p("duplicate","(Guidance Note: Select Alternative (a) or (b) for condition SCT91)"));
            if(variant==1)paragraphs.remove(paragraphs.size()-1);
            if(variant==2){paragraphs.add(paragraphs.size()-1,cell("hc","t5","r1","c1","*SCT91 A third branch"));paragraphs.add(paragraphs.size()-1,cell("c","t5","r1","c2","(Alternative (c) - for another arrangement)"));}
            if(variant==3)((ObjectNode)paragraphs.get(0).path("sourcePosition")).put("sourceSha256",OTHER);
            if(variant==4)((ObjectNode)paragraphs.get(7).path("sourcePosition")).put("partSha256",SHA);
            if(variant==5)paragraphs.set(0,foreign("common","(Guidance Note: Select Alternative (a) or (b) for SCT91)"));
            if(variant==6)((ObjectNode)paragraphs.get(0).path("sourcePosition")).put("partSha256",SHA);
            if(variant==7)paragraphs.set(0,p("common","(Guidance Note: Select Alternative (a) or (b) for SCT94)"));
            if(variant==8)paragraphs.set(0,p("common","(Select either SCC20.2 for fire services installation, or SCC20.3 for fire services and water pump installation)"));
            if(variant==9)paragraphs.add(1,p("interposed","SCT90 An unrelated intervening clause"));
            JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(paragraphs.toArray(new ObjectNode[0])));assertTrue(sourceRelations(evidence).isEmpty(),"variant "+variant);
        }
    }

    @Test void explicitSelectionsOfDifferentOwnersNeverShareTheirSourceCondition() {
        List<ObjectNode> paragraphs=commonSelectionMembers(),other=commonSelectionMembers();
        for(ObjectNode member:other){String old=member.path("id").asText(),text=member.path("textObservation").path("text").asText().replace("SCT91","SCT93").replace("SCT92","SCT94");ObjectNode copy=member.deepCopy();copy.put("id","other-"+old);for(String key:Arrays.asList("text","originalText"))((ObjectNode)copy.path("textObservation")).put(key,text);((ObjectNode)copy.path("sourcePosition")).put("nodePath",copy.path("sourcePosition").path("nodePath").asText().replace("tbl[t","tbl[u").replace("p["+old+"]","p[other-"+old+"]"));for(String key:Arrays.asList("tableNodePath","rowNodePath","cellNodePath"))if(copy.has(key))copy.put(key,copy.path(key).asText().replace("tbl[t","tbl[u"));paragraphs.add(copy);}
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(paragraphs.toArray(new ObjectNode[0])));Set<String> ids=new HashSet<>();int count=0;for(JsonNode group:evidence.path("groups"))if("complete_alternative_scope".equals(group.path("kind").asText())){count++;JsonNode relation=group.path("sourceInstructionRelationships").get(0);assertNotNull(relation);assertEquals(group.path("parentClauseLabel").asText(),relation.path("ownerReference").asText());ids.add(relation.path("id").asText());}assertEquals(4,count);assertEquals(2,ids.size());
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

    @Test void zeroCharacterOverrideClearsInheritedCharactersAndRetainsNativeTwipIndent() throws Exception {
        for(String inheritedAxis:Arrays.asList("leftChars","startChars")){
            ObjectNode a=layeredIndent(p("a","(a) First selected object;"),2400,600,"0"),b=layeredIndent(p("b","(b) Second selected object"),2400,600,"0"),tail=layeredIndent(p("tail","shall remain valid."),1800,0,"0");
            if(inheritedAxis.equals("startChars"))for(ObjectNode member:Arrays.asList(a,b,tail)){ObjectNode attributes=(ObjectNode)member.path("paragraphProperties").path("layoutEvidence").path("layers").get(0).path("declarations").get(0).path("attributes");attributes.set("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}startChars",attributes.remove("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}leftChars"));}
            ObjectNode original=a.deepCopy();JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7.304 Warranty"),p("one","(1) Parent"),a,b,tail,p("two","(2) Next parent"),p("next","SCC7.305 Other warranty"));
            for(String ref:Arrays.asList("SCC7.304(1)(a)","SCC7.304(1)(b)")){JsonNode range=clauseRange(scopes,ref);assertTrue(range.path("scopeComplete").asBoolean(),inheritedAxis);assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));}assertEquals(original,a);
        }
    }

    @Test void nonzeroCharacterOverrideCannotBorrowAnInheritedTwipHangingIndent() throws Exception {
        ObjectNode a=layeredIndent(p("a","(a) First selected object;"),2400,600,"0"),b=layeredIndent(p("b","(b) Second selected object"),2400,600,"100"),tail=layeredIndent(p("tail","shall remain valid."),1800,0,"0");
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7.304 Warranty"),p("one","(1) Parent"),a,b,tail,p("two","(2) Next parent"),p("next","SCC7.305 Other warranty"));
        for(String ref:Arrays.asList("SCC7.304(1)(a)","SCC7.304(1)(b)"))assertFalse(clauseRange(scopes,ref).path("scopeComplete").asBoolean());
    }

    @Test void inheritedCharacterHangingOrFirstLineCannotCertifyATwipSharedTail() throws Exception {
        for(String inheritedAttribute:Arrays.asList("hangingChars","firstLineChars")){
            ObjectNode a=layeredIndent(p("a","(a) First selected object;"),2400,600,"0"),b=layeredIndent(p("b","(b) Second selected object"),2400,600,"0"),tail=layeredIndent(p("tail","shall remain valid."),1800,0,"0");
            ((ObjectNode)b.path("paragraphProperties").path("layoutEvidence").path("layers").get(0).path("declarations").get(0).path("attributes")).put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"+inheritedAttribute,"100");
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7.304 Warranty"),p("one","(1) Parent"),a,b,tail,p("two","(2) Next parent"),p("next","SCC7.305 Other warranty"));
            for(String ref:Arrays.asList("SCC7.304(1)(a)","SCC7.304(1)(b)"))assertFalse(clauseRange(scopes,ref).path("scopeComplete").asBoolean(),inheritedAttribute);
        }
    }

    @Test void conflictingAliasesInTheSameNativeLayerCannotBeHiddenByAZeroValue() throws Exception {
        ObjectNode a=layeredIndent(p("a","(a) First selected object;"),2400,600,"0"),b=layeredIndent(p("b","(b) Second selected object"),2400,600,"0"),tail=layeredIndent(p("tail","shall remain valid."),1800,0,"0");
        ObjectNode direct=(ObjectNode)b.path("directParagraphLayout").path("ind").get(0).path("attributes");direct.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}startChars","200");((ObjectNode)b.path("paragraphProperties").path("layoutEvidence").path("layers").get(2).path("declarations").get(0)).set("attributes",direct.deepCopy());
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7.304 Warranty"),p("one","(1) Parent"),a,b,tail,p("two","(2) Next parent"),p("next","SCC7.305 Other warranty"));for(String ref:Arrays.asList("SCC7.304(1)(a)","SCC7.304(1)(b)"))assertFalse(clauseRange(scopes,ref).path("scopeComplete").asBoolean());
    }

    @Test void unresolvedStyleOrDuplicateNativeIndentCannotCertifyASharedTail() throws Exception {
        for(int variant=0;variant<3;variant++){
            ObjectNode a=layeredIndent(p("a","(a) First selected object;"),2400,600,"0"),b=layeredIndent(p("b","(b) Second selected object"),2400,600,"0"),tail=layeredIndent(p("tail","shall remain valid."),1800,0,"0");ObjectNode properties=(ObjectNode)b.path("paragraphProperties"),layout=(ObjectNode)properties.path("layoutEvidence");
            if(variant==0)properties.put("styleChainStatus","style_reference_missing");if(variant==1){ArrayNode declarations=(ArrayNode)layout.path("layers").get(0).path("declarations");declarations.add(declarations.get(0).deepCopy());}if(variant==2)layout.putObject("effective").putArray("ind").addObject().put("reason","numbering_and_paragraph_style_precedence_not_verified");
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7.304 Warranty"),p("one","(1) Parent"),a,b,tail,p("two","(2) Next parent"),p("next","SCC7.305 Other warranty"));for(String ref:Arrays.asList("SCC7.304(1)(a)","SCC7.304(1)(b)"))assertFalse(clauseRange(scopes,ref).path("scopeComplete").asBoolean(),"variant "+variant);
        }
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
        JsonNode relationship=whole.path("sourceInstructionRelationships").get(0);assertNotNull(relationship);assertEquals(relationship,inline.path("sourceInstructionRelationships").get(0));assertEquals("nested_source_condition",relationship.path("kind").asText());assertEquals(whole.path("id").asText(),relationship.path("parentGroupId").asText());assertEquals(inline.path("id").asText(),relationship.path("childGroupId").asText());assertEquals(whole.path("guidanceNoteId").asText(),relationship.path("parentGuidanceNoteId").asText());assertEquals(inline.path("guidanceNoteId").asText(),relationship.path("childGuidanceNoteId").asText());assertEquals(strings(star.path("conditionIds")),Collections.singletonList(relationship.path("parentConditionId").asText()));assertEquals(strings(hash.path("conditionIds")),Collections.singletonList(relationship.path("childConditionId").asText()));assertEquals("physical_source_containment_local_conditions_independent",relationship.path("status").asText());
    }

    @Test void nestedConditionsRequireOneCompleteSameSourceContainingParent() {
        for(int variant=0;variant<7;variant++){
            List<ObjectNode> paragraphs=nestedConditionMembers();
            if(variant==0)paragraphs.add(3,cell("duplicate","t1","r3","c1",paragraphs.get(2).path("textObservation").path("text").asText()));
            if(variant==1)((ObjectNode)paragraphs.get(2).path("textObservation")).put("text",paragraphs.get(2).path("textObservation").path("text").asText().replace(")", ""));
            if(variant==2)((ObjectNode)paragraphs.get(3).path("sourcePosition")).put("partSha256",SHA);
            if(variant==3)paragraphs.set(4,foreign("words","(# - Delete the words if precast facade is not applicable)"));
            if(variant==4)((ObjectNode)paragraphs.get(4).path("sourcePosition")).put("partSha256",SHA);
            if(variant==5)paragraphs.add(5,cell("duplicate-body","t1","r4b","c1","*(c) Duplicate source subitem."));
            if(variant==6){paragraphs.set(3,cell("body","t1","r4","c1","*(c) Domestic submission remains."));paragraphs.set(4,cell("words","t1","r5","c2","(# - Delete the words if precast facade is not applicable)"));paragraphs.set(5,cell("d","t1","r5","c1","(d) A different submission (#independent local words)"));}
            JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(paragraphs.toArray(new ObjectNode[0])));assertTrue(sourceRelations(evidence).isEmpty(),"variant "+variant);
        }
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

    @Test void aMarkedSpecifyInstructionBindsOnlyTheUniqueAdjacentNativePlaceholder() {
        for(String field:Arrays.asList("Ordinance","registration number")){
            String body="For the selected organisation, the source value is ________________________# and the remaining declaration still applies.";JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(p("h","SCT91 Declaration"),p("body",body),p("blank",""),p("note","(# Please specify the "+field+")"),p("other","A different organisation is established by ______# and remains separate."),p("next","SCT92 Next clause")));assertEquals(1,evidence.path("guidanceNotes").size());JsonNode group=firstGroup(evidence,"explicit_placeholder_scope");assertTrue(group.path("scopeComplete").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());assertEquals(Collections.singletonList("body"),strings(group.path("orderedMemberIds")));JsonNode range=group.path("memberRanges").get(0);assertEquals("________________________",body.substring(range.path("start").asInt(),range.path("end").asInt()));assertEquals("conditional_source_target",marker(evidence,"body","#").path("disposition").asText());assertEquals("negative_instruction_symbol",marker(evidence,"note","#").path("disposition").asText());assertEquals("unknown",marker(evidence,"other","#").path("disposition").asText());}
    }

    @Test void aSpecifyNoteCannotChooseAmongDuplicatesOrCrossAnInterveningParagraphStoryOrCell() {
        for(int variant=0;variant<5;variant++){
            ObjectNode body=p("body",variant==0?"First ______# and second ______#.":"The declared source value is ______#.");List<ObjectNode> source=new ArrayList<>(Arrays.asList(p("h","SCT91 Declaration"),body));if(variant==1)source.add(p("intervening","An independent source paragraph."));ObjectNode note=p("note",variant==2?"(* Please specify the value)":"(# Please specify the value)");if(variant==3){note.put("storyKind","footnote");note.put("storyRootNodePath","footnoteRoot");}if(variant==4)note=cell("note","t","r","1","(# Please specify the value)");source.add(note);source.add(p("next","SCT92 Next clause"));JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(source.toArray(new ObjectNode[0])));assertEquals("unknown",marker(evidence,"body","#").path("disposition").asText(),"Specify target "+variant);for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("explicit_placeholder_scope"))assertFalse(group.path("scopeComplete").asBoolean());
        }
        JsonNode ordinary=OerTemplateEvidence.analyse("SCT",word(p("h","SCT91 Declaration"),p("body","(The bidder must specify the organisation in the declaration.)"),p("next","SCT92 Next clause")));assertTrue(ordinary.path("guidanceNotes").isEmpty());
    }

    @Test void anExplicitEnterInstructionRetainsTheSourceTickWithoutSelectingAnyCheckbox() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(p("h","SCT91 Declaration"),p("note","(please enter “✓” in the appropriate box)"),p("first","\uF0A8 First source organisation option."),p("second","\uF0A8 Second source organisation option."),p("next","SCT92 Next clause")));assertEquals(1,evidence.path("guidanceNotes").size());JsonNode note=evidence.path("guidanceNotes").get(0);assertEquals("(please enter “✓” in the appropriate box)",note.path("text").asText());assertTrue(note.path("captureComplete").asBoolean());assertTrue(note.path("sourceSyntaxComplete").asBoolean());assertFalse(note.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("note"),strings(note.path("orderedMemberIds")));assertEquals("\uF0A8 First source organisation option.",evidence.path("sourceMembers").get(2).path("textObservation").path("text").asText());
        JsonNode ordinary=OerTemplateEvidence.analyse("SCT",word(p("h","SCT91 Declaration"),p("body","(The bidder will enter the appropriate organisation in the declaration.)"),p("next","SCT92 Next clause")));assertTrue(ordinary.path("guidanceNotes").isEmpty());
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

    @Test void pdfGuidanceDateOnlyOverlapRetainsTheEntireVerifiedMarginCluster() {
        DocumentBlock seed=pdfLine("revision",1,"Rev. 1",6,.10),approval=pdfLine("approval",1,"approved on",11,.115),date=pdfLine("date-and-note",1,"01.12.2022 [Guidance Note:",11,.13),body=pdfLine("body",1,"Keep every source obligation.]",0,.145);
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",pdf(seed,approval,date,body)),note=evidence.path("guidanceNotes").get(0);assertTrue(note.path("captureComplete").asBoolean());assertEquals("[Guidance Note:\nKeep every source obligation.]",note.path("text").asText());assertEquals("exact_glyph_projection_with_revision_margin_context",note.path("pdfLaneProjection").path("status").asText());assertEquals(3,note.path("revisionMarginContextRanges").size());List<String> contextMembers=new ArrayList<>();for(JsonNode range:note.path("revisionMarginContextRanges"))contextMembers.add(range.path("memberId").asText());assertEquals(Arrays.asList("revision","approval","date-and-note"),contextMembers);assertEquals("Rev. 1",evidence.path("sourceMembers").get(0).path("textObservation").path("text").asText());assertFalse(note.path("scopeComplete").asBoolean());assertEquals(0,sourceFields(evidence).size());
        for(JsonNode range:note.path("revisionMarginContextRanges"))assertFalse(range.path("pdfGlyphProvenance").path("glyphIds").isEmpty());
    }

    @Test void dateOnlyOverlapNeverExcludesAnIncompleteDistantForeignPageOrLargeFontCluster() {
        for(int variant=0;variant<5;variant++){
            DocumentBlock seed=pdfLine("revision",1,"Rev. 1",6,.10),approval=pdfLine("approval",1,"approved on",11,.115),date=pdfLine("date-and-note",1,"01.12.2022 [Guidance Note:",11,.13),body=pdfLine("body",1,"Keep every source obligation.]",0,.145);List<DocumentBlock> blocks=new ArrayList<>(Arrays.asList(seed,approval,date,body));
            if(variant==0)blocks.remove(seed);if(variant==1)blocks.remove(approval);if(variant==2)for(PdfPageEvidence.PdfGlyph glyph:seed.getPdfGlyphs()){double[] box=glyph.getBbox().clone();box[1]=.01;glyph.setBbox(box);}if(variant==3)seed.setPageNo(2);if(variant==4)for(PdfPageEvidence.PdfGlyph glyph:seed.getPdfGlyphs())glyph.setFontSizeInPt(9);
            JsonNode note=OerTemplateEvidence.analyse("SCC",pdf(blocks.toArray(new DocumentBlock[0]))).path("guidanceNotes").get(0);assertFalse(note.path("captureComplete").asBoolean(),"variant "+variant);assertEquals("lane_unknown_raw_source_retained",note.path("pdfLaneProjection").path("status").asText());assertTrue(note.path("revisionMarginContextRanges").isEmpty());assertEquals(note.path("rawText").asText(),note.path("text").asText());
        }
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

    @Test void ordinaryParallelAlternativesStopAtTheExactParagraphEndBeforeTrailingSpacers() throws Exception {
        ObjectNode electronic=cell("electronic","t1","r1","c2","*(c) The Bills generated by DesignTool42 and printed out in hard copy shall be submitted."),paper=cell("paper","t1","r1","c2","*(c) The original hard copy of the Bills shall be priced and submitted.");
        JsonNode scopes=clauseProjection(p("cover","Notes to Tenderers"),cell("n","t1","r1","c1","3."),electronic,cell("gap-a","t1","r1","c2","").put("nativeContentEmpty",true),paper,cell("gap-b","t1","r1","c2","").put("nativeContentEmpty",true),cell("a","t1","r1","c3","(Alternative (a) - for tenders with Bills issued in DesignTool42 system)"),cell("b","t1","r1","c3","(Alternative (b) - for tenders with Bills issued in hard copy)"),cell("next","t1","r2","c1","4."));
        int found=0;for(JsonNode range:scopes.path("ranges"))if("3(c)".equals(range.path("reference").asText())){found++;String id=range.path("startBoundary").path("memberId").asText();assertTrue(Arrays.asList("electronic","paper").contains(id));assertTrue(range.path("scopeComplete").asBoolean(),range.toString());assertEquals(Collections.singletonList(id),strings(range.path("orderedMemberIds")));assertEquals(id,range.path("endBoundary").path("memberId").asText());assertEquals((id.equals("electronic")?electronic:paper).path("textObservation").path("text").asText().length(),range.path("endBoundary").path("offset").asInt());}assertEquals(2,found);
        assertEquals(9,scopes.path("members").size());
    }

    @Test void repeatedListsRemainInsideTheirExplicitNativeBranchDespiteSpacerCounts() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC40.8 Service requirements"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("option-a","Option A"),p("intro-a","(1) The service requirements are revised as follows:-"),p("a-a","(a) First requirement;"),p("gap-a","").put("nativeContentEmpty",true),p("b-a","(b) Second requirement."),p("option-b","Option B"),p("intro-b","(1) The alternative requirements are revised as follows:-"),p("a-b","(a) Another first requirement;"),p("b-b","(b) Another second requirement."),p("next","SCC40.9 Next clause"));
        JsonNode list=null;for(JsonNode frame:scopes.path("referenceFrames"))if("explicit_native_list_introduction".equals(frame.path("kind").asText())&&strings(frame.path("anchorMemberIds")).contains("intro-a"))list=frame;
        assertNotNull(list);assertTrue(list.path("scopeComplete").asBoolean(),list.toString());assertEquals(Arrays.asList("a-a","gap-a","b-a"),strings(list.path("orderedMemberIds")));assertEquals("option-b",list.path("endBoundary").path("memberId").asText());
        boolean found=false;for(JsonNode range:scopes.path("ranges"))if("b-a".equals(range.path("startBoundary").path("memberId").asText())){found=true;assertTrue(range.path("scopeComplete").asBoolean(),range.toString());assertEquals(list.path("id"),range.path("sourceFrameId"));assertFalse(strings(range.path("orderedMemberIds")).contains("option-b"),range.toString());assertFalse(strings(range.path("contextMemberIds")).contains("option-b"),range.toString());assertEquals("option-b",range.path("endBoundary").path("memberId").asText());}assertTrue(found);
    }

    @Test void ordinaryOptionFramesAddOnlyProvenNativeSpacersAndKeepCommonTailsSeparate() throws Exception {
        for(int defect=-1;defect<8;defect++){
            ObjectNode spacer=p("spacer","").put("nativeContentEmpty",true);if(defect==0)spacer.remove("nativeContentEmpty");if(defect==1)spacer.put("nativeContentEmpty","true");if(defect==2)spacer.put("nativeContentEmpty",1);if(defect==3)((ObjectNode)spacer.path("textObservation")).remove("deletedText");if(defect==4)((ObjectNode)spacer.path("textObservation")).put("strikeText",1);if(defect==5)((ObjectNode)spacer.path("textObservation")).put("originalText","hidden");if(defect==6)((ObjectNode)spacer.path("sourcePosition")).put("sourceSha256",OTHER);if(defect==7)spacer.put("nativeContentEmpty",false);
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC11.302 Schedule of rates"),p("note","[Guidance Note: Select either Option A or Option B as appropriate.]"),p("option-a","Option A"),p("one","(1) The selected objects:"),indent(p("a","(a) First selected object;"),2400,600),spacer,indent(p("b","(b) Second selected object"),2400,600),indent(p("tail","shall remain valid."),1800,0),p("option-b","Option B"),p("other","(1) Other selected obligation."),p("next","SCC11.303 Next clause"));
            JsonNode frame=null;for(JsonNode candidate:scopes.path("referenceFrames"))if("option:a".equals(candidate.path("label").asText()))frame=candidate;if(defect!=6)assertNotNull(frame,"defect "+defect);if(frame!=null){assertEquals(defect<0,strings(frame.path("orderedMemberIds")).contains("spacer"),"defect "+defect);assertFalse(strings(frame.path("orderedMemberIds")).contains("option-b"));}
            if(defect<0){for(String ref:Arrays.asList("SCC11.302(1)(a)","SCC11.302(1)(b)")){JsonNode range=clauseRange(scopes,ref);assertTrue(range.path("scopeComplete").asBoolean(),range.toString());assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("option-b"));assertFalse(strings(range.path("contextMemberIds")).contains("option-b"),ref+" context crosses Option B: "+range);}boolean clipped=false;for(JsonNode range:scopes.path("ranges"))if("one".equals(range.path("startBoundary").path("memberId").asText())){clipped=true;assertEquals("option-b",range.path("endBoundary").path("memberId").asText());assertFalse(strings(range.path("orderedMemberIds")).contains("option-b"));}assertTrue(clipped);}
        }
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

    @Test void unmatchedQuotedInstructionsStayLinkedWithoutFuzzyTargetAuthority() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("h","t","r0","c1","SCT71 Tender documents"),cell("body","t","r1","c1","*and the Bill of Quantities and General Summary"),cell("note","t","r1","c2","(Guidance Note: *For specified tenders, delete “*and the Bills of Quantities and General Summary”.)"),cell("next","t","r2","c1","SCT72 Other documents"),cell("other","t","r3","c1","*and the Bill of Quantities and General Summary")));
        JsonNode observed=marker(evidence,"body","*"),note=evidence.path("guidanceNotes").get(0);
        assertEquals(Collections.singletonList(note.path("id").asText()),strings(observed.path("guidanceNoteIds")));
        assertEquals(Collections.singletonList(evidence.path("conditions").get(0).path("id").asText()),strings(observed.path("conditionIds")));
        assertEquals("EXPLICIT_QUOTED_INSTRUCTION_WITHOUT_EXACT_MARKED_TARGET",observed.path("unknownReasonCode").asText());
        assertEquals("unknown",observed.path("disposition").asText());assertFalse(observed.path("scopeComplete").asBoolean());assertTrue(observed.path("targetGroupIds").isEmpty());
        assertTrue(marker(evidence,"other","*").path("guidanceNoteIds").isEmpty());
    }

    @Test void pqSInstructionContextDoesNotTurnSuffixMarkersIntoWholeClauseEdits() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC71.302 Warranty (piling and footings*)"),p("note","[Guidance Notes: Only adopt this SCC Clause if applicable. *Delete/amend by the PQS as appropriate.]"),p("body","The piles and foundations including footings (raft/pad)* are warranted."),p("next","SCC71.303 Another warranty"),p("other","Other foundations* are described separately.")));
        String note=evidence.path("guidanceNotes").get(0).path("id").asText();
        for(String id:Arrays.asList("h","body")){JsonNode observed=marker(evidence,id,"*");assertEquals(Collections.singletonList(note),strings(observed.path("guidanceNoteIds")));assertEquals(1,observed.path("conditionIds").size());assertEquals("SOURCE_INSTRUCTION_CONTEXT_WITHOUT_EXACT_TARGET_RANGE",observed.path("unknownReasonCode").asText());assertEquals("unknown",observed.path("disposition").asText());assertFalse(observed.path("scopeComplete").asBoolean());assertTrue(observed.path("targetGroupIds").isEmpty());}
        assertTrue(marker(evidence,"other","*").path("guidanceNoteIds").isEmpty());
    }

    @Test void observedTitleChoiceLinksItsExistingFieldWithoutPromotingSelectionAuthority() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCT",word(cell("body","t","r","c1","Officer is A/ (*Mr/Ms ), telephone ."),cell("note","t","r","c2","(Project Architect)")));
        JsonNode observed=marker(evidence,"body","*"),choice=null;for(JsonNode field:sourceFields(evidence))if("person_title_choice".equals(field.path("fieldLabel").asText()))choice=field;
        assertNotNull(choice);assertTrue(strings(observed.path("contextGroupIds")).contains(choice.path("id").asText()));assertEquals(choice.path("guidanceNoteIds"),observed.path("guidanceNoteIds"));assertEquals(1,observed.path("guidanceNoteIds").size());
        assertEquals("SOURCE_CHOICE_OBSERVED_PROJECT_SELECTION_REQUIRED",observed.path("unknownReasonCode").asText());assertEquals("unknown",observed.path("disposition").asText());assertFalse(observed.path("scopeComplete").asBoolean());assertTrue(observed.path("targetGroupIds").isEmpty());assertFalse(choice.path("automaticEditEligible").asBoolean());
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

    @Test void normalClauseProjectionClosesAnNttSubitemAcrossCompleteCellParagraphs() throws Exception {
        JsonNode projection=clauseProjection(p("title","Notes to Tenderers"),cell("one","t1","r1","1","1."),cell("name","t1","r1","2","Type of Tender"),cell("a","t1","r1","2","(a) First item."),cell("f","t1","r1","2","(f) Schedules are listed below:"),cell("blank","t1","r1","2",""),cell("bill9","t1","r1","2","Bill No. 9 - Electrical Works;"),cell("bill10","t1","r1","2","Bill No. 10 - Fire Services;"),cell("bill11","t1","r1","2","Bill No. 11 - Lift Works."),cell("g","t1","r1","2","(g) A separate next item."),cell("two","t2","r1","1","2."),cell("name2","t2","r1","2","Documents"));
        JsonNode selected=clauseRange(projection,"1(f)");assertTrue(selected.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("f","blank","bill9","bill10","bill11"),strings(selected.path("orderedMemberIds")));assertEquals("g",selected.path("endBoundary").path("memberId").asText());assertEquals(clauseRange(projection,"1").path("id"),selected.path("parentId"));
        assertFalse(clauseRange(projection,"2").path("scopeComplete").asBoolean());assertEquals(12,projection.path("members").size());
    }

    @Test void normalSctProjectionReusesNestedUnitsAcrossTablesAndSeparatesEditorialAndPhysicalContext() throws Exception {
        JsonNode projection=clauseProjection(p("title","Special Conditions of Tender"),cell("h","t1","r1","1","SCT7 Inspection of documents"),cell("one","t1","r2","1","(1) Main requirement."),cell("role","t1","r2","2","(Project Architect)"),cell("four","t2","r1","1","(4) The following documents may be inspected:"),cell("a","t2","r1","1","(a) First document."),cell("blank","t2","r1","1",""),cell("b","t2","r1","1","(b) Second document."),cell("c","t2","r1","1","(c) Third document."),cell("five","t2","r1","1","(5) A different purpose."),cell("six","t2","r2","1","(6) Native numbering text remains observed."),p("footer","March 2020 - SCT/21 -"),p("code","HD(QS) 3C/121/1/2"),cell("running","t3","r1","1","SPECIAL CONDITIONS OF TENDER (Cont’d)"),cell("next","t4","r1","1","SCT8 Inspection of Site"));
        JsonNode root=clauseRange(projection,"SCT7"),fourth=clauseRange(projection,"SCT7(4)");assertTrue(root.path("scopeComplete").asBoolean());assertTrue(fourth.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("four","a","blank","b","c"),strings(fourth.path("orderedMemberIds")));assertEquals("five",fourth.path("endBoundary").path("memberId").asText());
        assertEquals(fourth.path("id"),clauseRange(projection,"SCT7(4)(c)").path("parentId"));assertTrue(strings(root.path("contextMemberIds")).containsAll(Arrays.asList("role","footer","code","running")));assertFalse(strings(root.path("orderedMemberIds")).contains("role"));assertTrue(clauseRange(projection,"SCT7(6)").path("scopeComplete").asBoolean());
    }

    @Test void explicitDecimalRootContainmentUsesTokenBoundariesAndDoesNotStopAParentAtItsFirstChild() throws Exception {
        JsonNode projection=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC1 Definitions"),p("intro","Parent introduction."),p("child","SCC1.301 Defined terms"),p("item","(1) A definition."),p("sibling","SCC1.302 Another definition"),p("body","Remaining source body."),p("ten","SCC10 Payments"));
        JsonNode root=clauseRange(projection,"SCC1"),child=clauseRange(projection,"SCC1.301");assertEquals("ten",root.path("endBoundary").path("memberId").asText());assertTrue(strings(root.path("orderedMemberIds")).containsAll(Arrays.asList("child","item","sibling","body")));assertTrue(root.path("scopeComplete").asBoolean());assertEquals(root.path("id"),child.path("parentId"));assertEquals("sibling",child.path("endBoundary").path("memberId").asText());assertTrue(clauseRange(projection,"SCC10").path("parentId").isNull());
    }

    @Test void sourceClauseProjectionKeepsForeignStoriesDuplicatesAndUnclassifiedParallelCellsUnknown() throws Exception {
        ObjectNode foreign=foreign("foreign","SCT8 A footnote cannot close the body clause");
        JsonNode projection=clauseProjection(p("title","Special Conditions of Tender"),cell("h","t1","r1","1","SCT7 Inspection"),cell("a","t1","r2","1","(1) Requirement."),cell("parallel","t1","r2","2","A separate unknown side column."),foreign,cell("repeat","t2","r1","1","SCT7 Other heading with a repeated reference"),cell("next","t3","r1","1","SCT8 Site"));
        int repeated=0;for(JsonNode range:projection.path("ranges"))if("SCT7".equals(range.path("reference").asText())){repeated++;assertFalse(range.path("scopeComplete").asBoolean());assertFalse(strings(range.path("orderedMemberIds")).contains("foreign"));}assertEquals(2,repeated);assertTrue(hasIssue(projection,"SOURCE_CLAUSE_REFERENCE_AMBIGUOUS"));
        JsonNode unknown=clauseProjection(p("title","A letter mentioning SCT7 without a formal title"),p("h","SCT7 Inspection"),p("next","SCT8 Site"));assertTrue(unknown.path("ranges").isEmpty());assertTrue(hasIssue(unknown,"SOURCE_CLAUSE_FAMILY_UNRESOLVED"));
    }

    @Test void aSourceAlignedRomanSequenceKeepsVAndViAsPeers() throws Exception {
        for(boolean upper:Arrays.asList(false,true)){
            List<ObjectNode> paragraphs=new ArrayList<>(Arrays.asList(p("title","Special Conditions of Contract"),p("h","SCC91 Source list"),p("one","(1) The following requirements apply:"),p("b","(b) The stated alternatives are:")));
            for(String label:Arrays.asList("i","ii","iii","iv","v","vi"))paragraphs.add(indent(p(label,"("+(upper?label.toUpperCase(Locale.ROOT):label)+") An alternative"+(label.equals("v")?"; or":".")),3010,600));
            paragraphs.add(p("c","(c) Another requirement."));paragraphs.add(p("next","SCC92 Next clause"));JsonNode scopes=clauseProjection(paragraphs.toArray(new ObjectNode[0]));
            for(String label:Arrays.asList("i","ii","iii","iv","v","vi")){JsonNode range=clauseRange(scopes,"SCC91(1)(b)("+(upper?label.toUpperCase(Locale.ROOT):label)+")");assertTrue(range.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList(label),strings(range.path("orderedMemberIds")));assertEquals(clauseRange(scopes,"SCC91(1)(b)").path("id"),range.path("parentId"));}
            assertEquals("vi",clauseRange(scopes,"SCC91(1)(b)("+(upper?"V":"v")+")").path("endBoundary").path("memberId").asText());
        }
    }

    @Test void romanSuffixReplacementLabelsKeepTheirLiteralCaseAndOuterAmendmentParent() throws Exception {
        for(boolean upperPrefix:Arrays.asList(false,true)){
            String first=upperPrefix?"Ia":"iA",second=upperPrefix?"Ib":"iB";JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC93 Amendments"),p("two","(2) Clause 8.4(2)(b)(i) is amended by substituting with the following :-"),indent(p("first","("+first+") The first replacement obligation."),3570,602),indent(p("second","("+second+") The second replacement obligation."),3570,602),p("three","(3) Clause 8.4(2)(b)(xii) is deleted and replaced by ‘Not used’."),p("next","SCC94 Next clause"));
            JsonNode a=clauseRange(scopes,"SCC93(2)("+first+")"),b=clauseRange(scopes,"SCC93(2)("+second+")");assertTrue(a.path("scopeComplete").asBoolean());assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("first"),strings(a.path("orderedMemberIds")));assertEquals(Collections.singletonList("second"),strings(b.path("orderedMemberIds")));assertEquals("second",a.path("endBoundary").path("memberId").asText());assertEquals("three",b.path("endBoundary").path("memberId").asText());assertEquals(clauseRange(scopes,"SCC93(2)").path("id"),a.path("parentId"));assertEquals(a.path("parentId"),b.path("parentId"));
        }
    }

    @Test void parenthesizedFormWordsAndInvalidRomanPrefixesDoNotBecomeSuffixClauseLabels() throws Exception {
        for(String label:Arrays.asList("Date","Civil","iAB","ICa")){JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT93 Declaration"),p("one","(1) The declaration must include:"),p("form","("+label+")"),p("two","(2) The next requirement."),p("next","SCT94 Next clause"));assertEquals(Arrays.asList("one","form"),strings(clauseRange(scopes,"SCT93(1)").path("orderedMemberIds")));for(JsonNode range:scopes.path("ranges"))assertNotEquals("SCT93(1)("+label+")",range.path("reference").asText());}
    }

    @Test void normalClauseProjectionKeepsMixedOrIncompleteGuidanceUnknown() throws Exception {
        JsonNode mixed=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC1 Definitions"),p("mixed","Source obligation. [Guidance Note: Insert a value.]"),p("next","SCC2 Payment"));
        JsonNode first=clauseRange(mixed,"SCC1");assertFalse(first.path("scopeComplete").asBoolean());assertTrue(strings(first.path("contextMemberIds")).contains("mixed"));assertFalse(strings(first.path("orderedMemberIds")).contains("mixed"));
        JsonNode incomplete=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC1 Definitions"),p("open","[Guidance Note: Keep the actual unclosed source"),p("next","SCC2 Payment"));assertFalse(clauseRange(incomplete,"SCC1").path("scopeComplete").asBoolean());assertTrue(hasIssue(incomplete,"GUIDANCE_SOURCE_SYNTAX_INCOMPLETE"));
    }

    @Test void parallelEnumerationCannotAdoptItsOwnLaneAsACompleteBodyUnit() throws Exception {
        JsonNode projection=clauseProjection(p("title","Special Conditions of Tender"),cell("h","t1","r1","1","SCT7 Inspection"),cell("one","t1","r2","1","(1) Main source requirement."),cell("side","t1","r2","2","(x) Unclassified parallel information."),cell("next","t2","r1","1","SCT8 Site"));
        assertFalse(clauseRange(projection,"SCT7").path("scopeComplete").asBoolean());assertFalse(clauseRange(projection,"SCT7(1)(x)").path("scopeComplete").asBoolean());assertTrue(strings(clauseRange(projection,"SCT7(1)(x)").path("contextMemberIds")).contains("side"));
    }

    @Test void duplicateMemberIdentityKeepsEveryCopyUnknownAndUnresolvedFamiliesRetainInventory() throws Exception {
        DocumentParser.ParsedDocument parsed=word(p("title","Special Conditions of Contract"),p("h","SCC1 Definitions"),p("same","First copy."),p("same","Second copy."),p("next","SCC2 Payment"));JsonNode inventory=parsed.getCoverage().getNativeDocxParagraphs(),before=inventory.deepCopy();
        JsonNode projection=(JsonNode)OerTemplateEvidence.class.getDeclaredMethod("nativeClauseRanges",JsonNode.class).invoke(null,inventory);assertFalse(clauseRange(projection,"SCC1").path("scopeComplete").asBoolean());assertEquals(before,inventory);assertEquals(5,projection.path("members").size());
        for(JsonNode member:projection.path("members"))if("same".equals(member.path("id").asText()))assertEquals("source_identity_unknown",member.path("sourceClauseClassification").asText());
        JsonNode unknown=clauseProjection(p("a","Unclassified specification"),p("b","Source member retained."));assertTrue(unknown.path("ranges").isEmpty());assertEquals(2,unknown.path("members").size());
    }

    @Test void anIsolatedContinuationHeadingCannotStandForTheMissingClauseStart() throws Exception {
        JsonNode orphan=clauseProjection(p("title","Special Conditions of Tender"),p("continued","SCT7 Inspection (Cont'd)"),p("body","Remaining source only."),p("next","SCT8 Site"));assertFalse(clauseRange(orphan,"SCT7").path("scopeComplete").asBoolean());assertTrue(hasIssue(orphan,"SOURCE_CLAUSE_CONTINUATION_WITHOUT_START"));
        JsonNode complete=clauseProjection(p("title","Special Conditions of Tender"),p("start","SCT7 Inspection"),p("body","First source portion."),p("continued","SCT7 Inspection (Cont'd)"),p("tail","Remaining source portion."),p("next","SCT8 Site"));assertTrue(clauseRange(complete,"SCT7").path("scopeComplete").asBoolean());assertEquals(Arrays.asList("start","body","tail"),strings(clauseRange(complete,"SCT7").path("orderedMemberIds")));assertTrue(strings(clauseRange(complete,"SCT7").path("contextMemberIds")).contains("continued"));
    }

    @Test void conflictingPartRevisionsCannotBeCombinedUnderOneOriginalSourceHash() throws Exception {
        ObjectNode changed=p("changed","A conflicting native part revision.");((ObjectNode)changed.path("sourcePosition")).put("partSha256",SHA);
        JsonNode projection=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC1 Definitions"),changed,p("next","SCC2 Payment"));assertTrue(projection.path("ranges").isEmpty());assertEquals(4,projection.path("members").size());assertTrue(hasIssue(projection,"SOURCE_CLAUSE_MEMBER_UNBOUND"));
        for(JsonNode member:projection.path("members"))assertEquals("source_identity_unknown",member.path("sourceClauseClassification").asText());
    }

    @Test void normalInventoryUsesExistingSourceBoundTocRolesWithoutRawXmlOrTitleRules() throws Exception {
        ObjectNode copy=tocRole(p("copy","SCC1 Definitions 1"),"toc_result");
        JsonNode projection=clauseProjection(p("title","Special Conditions of Contract"),copy,p("h","SCC1 Definitions"),p("body","Actual source clause body."),p("next","SCC2 Payment"));
        JsonNode first=clauseRange(projection,"SCC1");assertTrue(first.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("h","body"),strings(first.path("orderedMemberIds")));assertFalse(copy.has("nativeXml"));assertEquals(5,projection.path("members").size());
        ObjectNode literal=tocRole(p("copy","SCC1 Definitions 1"),"field_result");literal.putObject("paragraphProperties").put("paragraphStyleId","toc 1");
        JsonNode repeated=clauseProjection(p("title","Special Conditions of Contract"),literal,p("h","SCC1 Definitions"),p("body","Actual source clause body."),p("next","SCC2 Payment"));int found=0;for(JsonNode range:repeated.path("ranges"))if("SCC1".equals(range.path("reference").asText())){found++;assertFalse(range.path("scopeComplete").asBoolean());}assertEquals(2,found);
    }

    @Test void foreignPartOrSiblingTocRoleCannotSuppressAnActualSourceClause() throws Exception {
        for(String invalid:Arrays.asList("source","part","sibling")){
            ObjectNode copy=tocRole(p("copy","SCC1 Definitions"),"toc_result");ObjectNode position=(ObjectNode)copy.path("nativeFieldRoles").get(0).path("sourcePosition");
            if("source".equals(invalid))position.put("sourceSha256",OTHER);else if("part".equals(invalid))position.put("partSha256",SHA);else position.put("nodePath",BODY+"/{w}p[another]/{w}r[1]/{w}t[1]");
            JsonNode projection=clauseProjection(p("title","Special Conditions of Contract"),copy,p("body","Actual source body."),p("next","SCC2 Payment"));assertTrue(clauseRange(projection,"SCC1").path("scopeComplete").asBoolean());assertEquals("copy",clauseRange(projection,"SCC1").path("startBoundary").path("memberId").asText());
        }
    }

    @Test void tocRolesMustCoverTheWholeCachedParagraphWithoutHidingMixedBodyText() throws Exception {
        ObjectNode mixed=tocRole(p("mixed","SCC1 Definitions with an actual obligation. Cached TOC text"),"toc_result");((ObjectNode)mixed.path("nativeFieldRoles").get(0)).put("sourceText","Cached TOC text");mixed.put("nativeXml","<w:hyperlink w:anchor=\"_Toc42\"><w:instrText>PAGEREF _Toc42 </w:instrText></w:hyperlink>");
        JsonNode projection=clauseProjection(p("title","Special Conditions of Contract"),mixed,p("body","Actual source body."),p("next","SCC2 Payment"));assertTrue(clauseRange(projection,"SCC1").path("scopeComplete").asBoolean());assertEquals("mixed",clauseRange(projection,"SCC1").path("startBoundary").path("memberId").asText());
        ObjectNode complete=tocRole(p("copy","SCC1 Definitions 1"),"toc_result");ObjectNode first=(ObjectNode)complete.path("nativeFieldRoles").get(0);first.put("sourceText","SCC1\u00a0Definitions");ObjectNode second=first.deepCopy();second.put("sourceText","1");((ArrayNode)complete.path("nativeFieldRoles")).add(second);ObjectNode deleted=first.deepCopy();deleted.put("sourceText","Deleted source text");deleted.put("deleted",true);((ArrayNode)complete.path("nativeFieldRoles")).add(deleted);
        JsonNode copied=clauseProjection(p("title","Special Conditions of Contract"),complete,p("h","SCC1 Definitions"),p("body","Actual source body."),p("next","SCC2 Payment"));assertTrue(clauseRange(copied,"SCC1").path("scopeComplete").asBoolean());assertEquals("h",clauseRange(copied,"SCC1").path("startBoundary").path("memberId").asText());
    }

    @Test void loadedNativeStoryRootClosesFinalClauseAndChildrenWithoutBorrowingAnAppendedForeignStory() throws Exception {
        ObjectNode owner=p("last","SCC22 Final conditions"),root=loadedStoryRoot(owner);JsonNode projection=clauseProjectionWithRoot(root,p("title","Special Conditions of Contract"),owner,p("child","SCC22.309 Final special provision"),p("item","(1) Last source item."),p("blank",""),foreign("footnote","An independent footnote is not part of the final clause."));
        for(String reference:Arrays.asList("SCC22","SCC22.309","SCC22.309(1)")){JsonNode range=clauseRange(projection,reference);assertTrue(range.path("scopeComplete").asBoolean(),reference);assertEquals("native_container_end",range.path("endBoundary").path("kind").asText());assertEquals(root.path("sourcePosition"),range.path("endBoundary").path("sourcePosition"));assertFalse(range.path("endBoundary").hasNonNull("memberId"));assertFalse(strings(range.path("orderedMemberIds")).contains("footnote"));}
        assertEquals(clauseRange(projection,"SCC22").path("id"),clauseRange(projection,"SCC22.309").path("parentId"));assertFalse(clauseRange(projection,"SCC22").path("automaticEditEligible").asBoolean());
        JsonNode old=clauseProjection(p("title","Special Conditions of Contract"),owner,p("body","Existing saved inventory lacks a loaded root declaration."));assertFalse(clauseRange(old,"SCC22").path("scopeComplete").asBoolean());assertEquals("source_story_end_unverified",clauseRange(old,"SCC22").path("endBoundary").path("kind").asText());
    }

    @Test void foreignUnknownOrDuplicateRootDeclarationsCannotCloseTheFinalSourceClause() throws Exception {
        for(String invalid:Arrays.asList("source","partHash","partName","storyKind","rootPath","positionPath","status","duplicate")){
            ObjectNode owner=p("last","SCC22 Final conditions"),root=loadedStoryRoot(owner),position=(ObjectNode)root.path("sourcePosition");
            if("source".equals(invalid))position.put("sourceSha256",OTHER);else if("partHash".equals(invalid))position.put("partSha256",SHA);else if("partName".equals(invalid))position.put("partName","word/footnotes.xml");else if("storyKind".equals(invalid))root.put("storyKind","footnote");else if("rootPath".equals(invalid))root.put("storyRootNodePath",BODY+"/{w}p[last]");else if("positionPath".equals(invalid))position.put("nodePath",BODY+"/{w}p[last]");else if("status".equals(invalid))root.put("status","unknown");
            ObjectNode inventory=(ObjectNode)word(p("title","Special Conditions of Contract"),owner,p("body","Last source paragraph.")).getCoverage().getNativeDocxParagraphs();ArrayNode roots=inventory.putArray("storyRoots");roots.add(root);if("duplicate".equals(invalid))roots.add(root.deepCopy());
            JsonNode projection=(JsonNode)OerTemplateEvidence.class.getDeclaredMethod("nativeClauseRanges",JsonNode.class).invoke(null,inventory);assertFalse(clauseRange(projection,"SCC22").path("scopeComplete").asBoolean(),invalid);assertEquals("source_story_end_unverified",clauseRange(projection,"SCC22").path("endBoundary").path("kind").asText());
        }
    }

    @Test void loadedRootDoesNotUpgradeUnknownParagraphsRepeatedClauseStartsOrAmbiguousSharedTails() throws Exception {
        ObjectNode owner=p("last","SCC22 Final conditions"),unknown=p("unknown","Unknown text observation.");((ObjectNode)unknown.path("textObservation")).put("status","unknown");
        JsonNode incomplete=clauseProjectionWithRoot(loadedStoryRoot(owner),p("title","Special Conditions of Contract"),owner,unknown);assertFalse(clauseRange(incomplete,"SCC22").path("scopeComplete").asBoolean());
        JsonNode duplicate=clauseProjectionWithRoot(loadedStoryRoot(owner),p("title","Special Conditions of Contract"),owner,p("repeat","SCC22 Repeated independent start"),p("body","Remaining source."));for(JsonNode range:duplicate.path("ranges"))if("SCC22".equals(range.path("reference").asText()))assertFalse(range.path("scopeComplete").asBoolean());
        ObjectNode numbered=p("h","SCC22.309 Final provision");JsonNode tail=clauseProjectionWithRoot(loadedStoryRoot(numbered),p("title","Special Conditions of Contract"),numbered,p("one","(1) Requirements:"),p("a","(a) first unfinished item"),p("b","(b) second unfinished item"),p("tail","Unclassified continuation without a verified parent predicate."));assertFalse(clauseRange(tail,"SCC22.309(1)(a)").path("scopeComplete").asBoolean());assertFalse(clauseRange(tail,"SCC22.309(1)(b)").path("scopeComplete").asBoolean());
    }

    private static ObjectNode loadedStoryRoot(ObjectNode member){ObjectNode root=object();root.put("status","observed_loaded_native_root");root.set("storyKind",member.path("storyKind"));root.set("storyRootNodePath",member.path("storyRootNodePath"));ObjectNode position=member.path("sourcePosition").deepCopy();position.put("nodePath",member.path("storyRootNodePath").asText());root.set("sourcePosition",position);return root;}

    @Test void spacedCompositeLabelsKeepTheirParentAndDoNotRestartItOnTheNextPage() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT1 Source requirements"),p("a","(4) (a) First medium:"),p("i","(i) First listed document."),p("x","(x) Tenth listed document."),p("xi","(xi) Eleventh listed document."),p("continued","SCT1 Source requirements (Cont’d)"),p("b","(4) (b) Second medium:"),p("bi","(i) Second listed document."),p("five","(5) Next parent."),p("next","SCT2 Next source clause"));
        assertEquals(Arrays.asList("a","i","x","xi","b","bi"),strings(clauseRange(scopes,"SCT1(4)").path("orderedMemberIds")));assertEquals("five",clauseRange(scopes,"SCT1(4)").path("endBoundary").path("memberId").asText());
        assertEquals("SCT1(4)(a)(xi)",clauseRange(scopes,"SCT1(4)(a)(xi)").path("reference").asText());assertEquals(clauseRange(scopes,"SCT1(4)").path("id"),clauseRange(scopes,"SCT1(4)(b)").path("parentId"));
    }

    @Test void mixedAlphaRomanAndUppercaseLabelsKeepFourNativeSourceLevels() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Index adjustment"),p("three","(3) Index requirements:"),p("c","(c) A specialist section:"),p("i","(i) First calculation:"),p("ia","(a) First input."),p("ib","(b) Second input."),p("A","(A) First upper-case exception."),p("B","(B) Second upper-case exception."),p("ii","(ii) Second calculation:"),p("iia","(a) Another input."),p("four","(4) Next parent."),p("next","SCC21 Next source clause"));
        assertEquals("ia",clauseRange(scopes,"SCC20(3)(c)(i)(a)").path("orderedMemberIds").path(0).asText());assertEquals("A",clauseRange(scopes,"SCC20(3)(c)(i)(A)").path("orderedMemberIds").path(0).asText());assertEquals("iia",clauseRange(scopes,"SCC20(3)(c)(ii)(a)").path("orderedMemberIds").path(0).asText());
        assertEquals(clauseRange(scopes,"SCC20(3)(c)(ii)").path("id"),clauseRange(scopes,"SCC20(3)(c)(ii)(a)").path("parentId"));
    }

    @Test void aContinuationTitleInTheNttBodyCellClosesTheWholeNumberedClause() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Notes to Tenderers"),cell("one","t1","r1","1","1."),cell("name","t1","r1","2","Submission requirements"),cell("a","t1","r1","2","(a) First requirement."),cell("one-again","t2","r1","1","1."),cell("name-again","t2","r1","2","Submission requirements (Cont’d)"),cell("b","t2","r1","2","(b) Remaining requirement."),cell("two","t3","r1","1","2."),cell("next-name","t3","r1","2","Next requirement"));
        JsonNode root=clauseRange(scopes,"1");assertTrue(root.path("scopeComplete").asBoolean());assertTrue(strings(root.path("orderedMemberIds")).contains("b"));assertFalse(strings(root.path("orderedMemberIds")).contains("one-again"));assertEquals(root.path("id"),clauseRange(scopes,"1(b)").path("parentId"));
    }

    @Test void aParentLevelProvisoAfterCompleteSentencesIsSharedAndSameLevelContinuationStaysWithTheChild() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC4 Confidentiality"),indent(p("one","(1) The following requirements apply:"),1200,400),indent(p("a","(a) First source requirement."),1800,600),indent(p("b","(b) Second source requirement."),1800,600),indent(p("continuation","A continuation of the second source requirement."),1800,0),indent(p("tail","Provided that the source identity is retained."),1200,0),indent(p("two","(2) Next source requirement."),1200,400),p("next","SCC5 Next clause"));
        JsonNode a=clauseRange(scopes,"SCC4(1)(a)"),b=clauseRange(scopes,"SCC4(1)(b)");assertTrue(a.path("scopeComplete").asBoolean());assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("b","continuation"),strings(b.path("orderedMemberIds")));assertTrue(strings(a.path("contextMemberIds")).contains("tail"));assertTrue(strings(b.path("contextMemberIds")).contains("tail"));
    }

    @Test void aShallowerProvisoWithSmallerNativeHangingIsSharedWithoutRequiringZero() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC8.303 Conditions"),indent(p("one","(1B) The following requirements apply:"),1800,400),indent(p("a","(a) First source requirement."),2400,500),indent(p("b","(b) Second source requirement."),2400,500),indent(p("c","(c) Third source requirement."),2400,500),indent(p("tail","Provided that the source identity remains unchanged."),1800,20),p("two","(2) Next requirement."),p("next","SCC8.304 Next clause"));
        for(String label:Arrays.asList("a","b","c")){JsonNode range=clauseRange(scopes,"SCC8.303(1B)("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));}
    }

    @Test void anExplicitNativeReferenceColumnIsContextAndCannotCreateClauseNumberingOrTails() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("body-header","t","r0","1","Clause"),cell("reference-header","t","r0","2","Reference"),cell("h","t","r1","1","SCT2 Submission"),cell("one","t","r2","1","(1) The submission includes:"),cell("reference-one","t","r2","2","(i) A side reference"),cell("a","t","r3","1","(a) First requirement."),cell("b","t","r4","1","(b) Second requirement."),cell("reference-two","t","r4","2","(ii) Another side reference"),cell("next","t","r5","1","SCT3 Next clause"));
        JsonNode b=clauseRange(scopes,"SCT2(1)(b)");assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(b.path("orderedMemberIds")));assertTrue(strings(b.path("contextMemberIds")).contains("reference-two"));for(JsonNode range:scopes.path("ranges"))for(JsonNode observation:range.path("contextObservations"))if(Arrays.asList("reference-one","reference-two").contains(observation.path("memberId").asText()))assertEquals("source_native_reference_column_context",observation.path("reason").asText());for(JsonNode range:scopes.path("ranges"))assertFalse(strings(range.path("orderedMemberIds")).contains("reference-one")||strings(range.path("orderedMemberIds")).contains("reference-two"));
    }

    @Test void ordinaryReferenceWordingOrAHeaderInAnotherTableDoesNotExcludeSourceText() throws Exception {
        for(boolean otherTable:Arrays.asList(false,true)){
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("header-body","header","r0","1","Clause"),cell("header-ref","header","r0","2",otherTable?"Reference":"Reference details are part of the document"),cell("h",otherTable?"body":"header","r1","1","SCT2 Submission"),cell("one",otherTable?"body":"header","r2","1","(1) The submission includes:"),cell("a",otherTable?"body":"header","r3","1","(a) First requirement."),cell("b",otherTable?"body":"header","r4","1","(b) Second requirement."),cell("reference",otherTable?"body":"header","r4","2","(ii) Ordinary text must remain observable"),cell("next",otherTable?"body":"header","r5","1","SCT3 Next clause"));
            boolean retained=false;for(JsonNode range:scopes.path("ranges")){retained|=strings(range.path("orderedMemberIds")).contains("reference")||strings(range.path("contextMemberIds")).contains("reference");for(JsonNode observation:range.path("contextObservations"))if("reference".equals(observation.path("memberId").asText()))assertNotEquals("source_native_reference_column_context",observation.path("reason").asText());}assertTrue(retained);
        }
    }

    @Test void aReferenceHeaderMayFollowTheCompleteAlternativeAnnotationInItsOwnNativeColumn() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("h","t","r1","1","SCT2 Documents"),cell("alternative","t","r1","2","(Alternative (a) - for complete electronic tender documents)"),cell("one","t","r2","1","(1) The source includes:"),cell("reference-header","t","r2","2","Reference"),cell("a","t","r3","1","(a) First source requirement."),cell("reference-a","t","r3","2","(i) Source citation"),cell("b","t","r4","1","(b) Last source requirement."),cell("reference-b","t","r4","2","SCC8.1"),cell("next","t","r5","1","SCT3 Next clause"));JsonNode b=clauseRange(scopes,"SCT2(1)(b)");assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(b.path("orderedMemberIds")));assertTrue(hasSourceContextReason(scopes,"reference-b","source_native_reference_column_context"));assertTrue(hasSourceContextReason(scopes,"reference-a","source_native_reference_column_context"));
    }

    @Test void anOrdinaryReferenceSubheadingCannotReclassifyTheAlreadyObservedNttBodyColumn() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Notes to Tenderers"),cell("one","t","r1","1","1."),cell("h","t","r1","2","Submission requirements"),cell("blank-reference","t","r2","1",""),cell("reference-word","t","r2","2","Reference"),cell("blank-a","t","r3","1",""),cell("a","t","r3","2","(a) First requirement."),cell("blank-b","t","r4","1",""),cell("b","t","r4","2","(b) Second requirement."),cell("two","next-table","r1","1","2."));for(String label:Arrays.asList("a","b")){JsonNode range=clauseRange(scopes,"1("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertTrue(strings(range.path("orderedMemberIds")).contains(label));assertFalse(hasSourceContextReason(scopes,label,"source_native_reference_column_context"));}assertTrue(strings(clauseRange(scopes,"1").path("orderedMemberIds")).contains("reference-word"));
    }

    @Test void aReferenceColumnContinuesOnlyWithTheSameCompleteAlternativeAndContinuedSourceOwner() throws Exception {
        String annotation="(Alternative (a) - for complete electronic tender documents)";for(int variant=0;variant<7;variant++){
            ObjectNode reference=cell("continued-reference",variant==5?"nested":"second","r2","2",variant==6?"(iii) Continued citation":"SCT9(2)(a) and SCT9(3)"),body=cell("b","second","r2","1","(b) Last source requirement.");if(variant==5){for(String key:Arrays.asList("tableNodePath","rowNodePath","cellNodePath"))reference.put(key,reference.path(key).asText().replace(BODY,body.path("cellNodePath").asText()));((ObjectNode)reference.path("sourcePosition")).put("nodePath",reference.path("sourcePosition").path("nodePath").asText().replace(BODY,body.path("cellNodePath").asText()));}
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("h","first","r1","1","SCT2 Documents"),cell("first-alternative","first","r1","2",annotation),cell("one","first","r2","1","(1) The source includes:"),cell("reference-header","first","r2","2",variant==3?"Citation information":"Reference"),cell("a","first","r3","1","(a) First source requirement."),cell("continued","second","r1","1",variant==2?"SCT3 Documents (Cont’d)":variant==4?"SCT2 Documents":"SCT2 Documents (Cont’d)"),cell("second-alternative","second","r1","2",variant==1?"(Alternative (a) - for a different electronic tender package)":annotation),body,reference,cell("next","third","r1","1","SCT4 Next clause"));assertEquals(variant==0,hasSourceContextReason(scopes,"continued-reference","source_native_reference_column_context"),"Reference continuation "+variant);if(variant==0){JsonNode b=clauseRange(scopes,"SCT2(1)(b)");assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(b.path("orderedMemberIds")));}
        }
    }

    @Test void aSplitNativeAlternativeCellDoesNotHideTheFollowingReferenceHeaderOrItsContinuation() throws Exception {
        for(int variant=0;variant<5;variant++){
            List<ObjectNode> paragraphs=new ArrayList<>(Arrays.asList(p("title","Special Conditions of Tender"),cell("h","first","r1","1","SCT2 Documents"),cell("alternative-a","first","r1","2","(Alternative (a) - for tenders"),cell("alternative-b","first","r1","2",variant==1?"without a closing delimiter":"with complete source documents)")));
            if(variant==2)paragraphs.add(cell("ordinary-body","first","r1","2","Actual source obligation after the annotation."));
            paragraphs.addAll(Arrays.asList(cell("one","first","r2","1","(1) The source includes:"),cell("reference-header","first","r2","2","Reference"),cell("a","first","r3","1","(a) The first requirement."),cell("reference-a","first","r3","2","SCT9(1)"),cell("continued","second","r1","1","SCT2 Documents (Cont’d)"),cell("continued-alternative-a","second","r1","2","(Alternative (a) - for tenders"),cell("continued-alternative-b","second","r1",variant==4?"3":"2",variant==3?"with a different package)":"with complete source documents)"),cell("b","second","r2","1","(b) The second requirement."),cell("reference-b","second","r2","2","SCT9(2) and SCT9(3)"),cell("next","third","r1","1","SCT3 Next clause")));
            JsonNode scopes=clauseProjection(paragraphs.toArray(new ObjectNode[0]));assertEquals(variant!=1&&variant!=2,hasSourceContextReason(scopes,"reference-a","source_native_reference_column_context"),"current header "+variant);assertEquals(variant==0,hasSourceContextReason(scopes,"reference-b","source_native_reference_column_context"),"continued header "+variant);if(variant==0){JsonNode b=clauseRange(scopes,"SCT2(1)(b)");assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(b.path("orderedMemberIds")));}
        }
    }

    @Test void anExplicitParentOwnsItsNestedTableAndMatchingSubjectTailDespiteOneBadChildIndent() throws Exception {
        ObjectNode a=indent(cell("a","outer","r1","1","(a) A tender assessment shall use Table 1 below if:-"),1800,400),table=cell("table-title","nested","r1","1","Table 1 - Tender assessment"),value=cell("table-value","nested","r2","2","The source assessment criteria."),i=indent(cell("i","outer","r1","1","(i) first qualification,"),2400,600),ii=indent(cell("ii","outer","r1","1","(ii) second qualification,"),2400,600);((ObjectNode)ii.path("directParagraphLayout").path("ind").get(0).path("attributes")).put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}leftChars","100");
        for(ObjectNode nested:Arrays.asList(table,value)){for(String key:Arrays.asList("tableNodePath","rowNodePath","cellNodePath"))nested.put(key,nested.path(key).asText().replace(BODY,a.path("cellNodePath").asText()));((ObjectNode)nested.path("sourcePosition")).put("nodePath",nested.path("sourcePosition").path("nodePath").asText().replace(BODY,a.path("cellNodePath").asText()));}
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("h","outer","r0","1","SCT5 Tender evidence"),cell("four","outer","r1","1","(4) The submission includes:"),a,table,value,i,ii,cell("continued","outer","r2","1","SCT5 Tender evidence (Cont’d)"),indent(cell("tail","outer","r1","1","The tender assessment shall retain the original qualifications."),1800,0),cell("b","outer","r3","1","(b) Another assessment."),cell("next","outer","r4","1","SCT6 Next clause"));
        JsonNode parent=clauseRange(scopes,"SCT5(4)(a)");assertTrue(parent.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("a","table-title","table-value","i","ii","tail"),strings(parent.path("orderedMemberIds")));assertTrue(strings(parent.path("contextMemberIds")).contains("continued"));for(String label:Arrays.asList("i","ii")){JsonNode child=clauseRange(scopes,"SCT5(4)(a)("+label+")");assertTrue(child.path("scopeComplete").asBoolean());assertTrue(strings(child.path("contextMemberIds")).contains("tail"));assertFalse(strings(child.path("orderedMemberIds")).contains("tail"));}
    }

    @Test void sharedParentTailsRetainFurnitureAndContinuationHeadingsWithTheirOriginalRoles() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT91 Assessment"),p("one","(1) A tender assessment shall use these qualifications:"),indent(p("i","(i) The first qualification,"),2400,600),indent(p("ii","(ii) The second qualification,"),2400,600),indent(p("tail","The tender assessment shall retain the original qualifications."),1800,0),p("blank",""),p("furniture","SPECIAL CONDITIONS OF TENDER (Cont’d)"),p("continued","SCT91 Assessment (Cont’d)"),p("two","(2) Another requirement."),p("next","SCT92 Next clause"));
        for(String label:Arrays.asList("i","ii")){JsonNode child=clauseRange(scopes,"SCT91(1)("+label+")");assertTrue(child.path("scopeComplete").asBoolean());assertTrue(strings(child.path("contextMemberIds")).containsAll(Arrays.asList("tail","blank","furniture","continued")));for(JsonNode observation:child.path("contextObservations")){String member=observation.path("memberId").asText();if(member.equals("tail"))assertEquals("shared_parent_predicate_context",observation.path("reason").asText());else if(member.equals("blank")||member.equals("furniture"))assertEquals("source_physical_furniture_context",observation.path("reason").asText());else if(member.equals("continued"))assertEquals("source_repeated_clause_heading_context",observation.path("reason").asText());}}
    }

    @Test void eachExplicitDateListOwnsItsWhicheverAggregateWithoutCrossUnitIndentConversion() throws Exception {
        ObjectNode first=indent(p("first-tail","whichever is later."),0,0),second=indent(p("second-tail","whichever is earlier."),0,0);for(ObjectNode member:Arrays.asList(first,second)){ObjectNode attributes=(ObjectNode)member.path("directParagraphLayout").path("ind").get(0).path("attributes");attributes.removeAll();attributes.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}leftChars","200");}
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Index adjustment"),p("three","(3) The calculations include:"),p("c","(c) The first calculation includes:"),indent(p("i","(i) The date shall be selected from:-"),1800,600),indent(p("a","(a) first observed date,"),2400,600),indent(p("b","(b) second observed date,"),2400,600),first,indent(p("proviso","Provided that the alternative date shall be selected from:-"),1800,0),indent(p("A","(A) first revised date,"),2400,600),indent(p("B","(B) second revised date,"),2400,600),second,p("ii","(ii) Another calculation."),p("four","(4) Next requirement."),p("next","SCC21 Next clause"));
        JsonNode parent=clauseRange(scopes,"SCC20(3)(c)(i)");for(String label:Arrays.asList("a","b")){JsonNode range=clauseRange(scopes,"SCC20(3)(c)(i)("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertEquals(parent.path("id"),range.path("parentId"));assertTrue(strings(range.path("contextMemberIds")).contains("first-tail"));assertFalse(strings(range.path("contextMemberIds")).contains("second-tail"));}for(String label:Arrays.asList("A","B")){JsonNode range=clauseRange(scopes,"SCC20(3)(c)(i)("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertEquals(parent.path("id"),range.path("parentId"));assertTrue(strings(range.path("contextMemberIds")).contains("second-tail"));assertFalse(strings(range.path("contextMemberIds")).contains("first-tail"));}assertTrue(strings(parent.path("orderedMemberIds")).containsAll(Arrays.asList("first-tail","second-tail")));
    }

    @Test void aProvisoNamingItsActualParentIsSharedEvenWhenItsChildrenAreCompleteSentences() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Adjustment"),p("one","(1) The Effective Value includes:-"),p("a","(a) The first component."),p("b","(b) The second component."),p("tail","Provided that for the purposes of SCC20(1) only component (a) applies to the first certificate."),p("two","(2) Next requirement."),p("next","SCC21 Next clause"));
        for(String label:Arrays.asList("a","b")){JsonNode range=clauseRange(scopes,"SCC20(1)("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));}assertTrue(strings(clauseRange(scopes,"SCC20(1)").path("orderedMemberIds")).contains("tail"));
    }

    @Test void anExactParentReferenceTakesPriorityOverAnIdenticalPhysicalIndent() throws Exception {
        for(String reference:Arrays.asList("SCC20(1)","SCC21(1)","SCC20(10)"))for(boolean calculationInput:Arrays.asList(false,true)){
            boolean namesParent=reference.equals("SCC20(1)")&&!calculationInput;String text=calculationInput?"Provided that the amount for this item, calculated using "+reference+", shall be reduced.":"Provided that for the purposes of "+reference+" only item (a) applies to the first certificate.";JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Adjustment"),p("one","(1) The index values include:-"),indent(p("a","(a) The first index shall apply."),2400,600),indent(p("b","(b) The second index shall apply."),2400,600),indent(p("tail",text),2400,600),p("two","(2) Next requirement."),p("next","SCC21 Next clause"));
            JsonNode a=clauseRange(scopes,"SCC20(1)(a)"),b=clauseRange(scopes,"SCC20(1)(b)");assertTrue(a.path("scopeComplete").asBoolean());assertTrue(b.path("scopeComplete").asBoolean());assertEquals(namesParent,strings(a.path("contextMemberIds")).contains("tail"));assertEquals(namesParent,strings(b.path("contextMemberIds")).contains("tail"));assertEquals(!namesParent,strings(b.path("orderedMemberIds")).contains("tail"));assertTrue(strings(clauseRange(scopes,"SCC20(1)").path("orderedMemberIds")).contains("tail"));assertFalse(b.path("semanticApplicabilityVerified").asBoolean());
        }
    }

    @Test void aNamedDefinitionQualifierIsAParentPredicateButUsingThatNameAsAnInputIsNot() throws Exception {
        String[] tails={"Provided that the Effective Value shall include component (i) only.","Provided that in the case of the first interim certificate, the Effective Value shall include component (i) only.","Provided that the amount for this item, calculated using the Effective Value, shall be reduced.","Provided that in the case of the first interim certificate, the Adjusted Value shall include component (i) only.","Provided that the amount for this item assumes that the Effective Value shall remain unchanged."};for(String qualifier:Arrays.asList(""," in respect of the electrical Specialist Sub-contract Works or other work, as the case may be"))for(int variant=0;variant<tails.length;variant++){
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Adjustment"),p("three","(3) The definitions include:"),p("d","(d) The value of completed work (Effective Value"+qualifier+") shall include:-"),p("i","(i) The first component."),p("ii","(ii) The second component."),p("tail",tails[variant]),p("e","(e) The next definition."),p("next","SCC21 Next clause"));for(String label:Arrays.asList("i","ii")){JsonNode range=clauseRange(scopes,"SCC20(3)(d)("+label+")");assertEquals(variant<2,range.path("scopeComplete").asBoolean(),"Definition predicate "+variant+qualifier);assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));assertTrue(strings(range.path("contextMemberIds")).contains("tail"));}
        }
    }

    @Test void anUnfinishedCertificateListCanCompleteItsExplicitWeUndertakingWithoutInventedIndent() throws Exception {
        String[] introductions={"We confirm that the declaration includes:-","Our bid includes:-","The declaration includes:-","Their declaration quotes our undertaking as follows:-","The Employer states that we shall provide:-","‘Our bid’ is quoted in the form as follows:-"};for(boolean closed:Arrays.asList(false,true))for(int variant=0;variant<introductions.length;variant++){
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),cell("h","certificate","r1","1","SCT24 Certificate"),cell("two","certificate","r2","1","2. "+introductions[variant]),cell("i","certificate","r3","1","(i) the first undertaking"+(closed?".":",")),cell("ii","certificate","r4","1","(ii) the second undertaking"+(closed?".":",")),cell("tail","certificate","r5","1","and we undertake to provide the original certificates."),cell("three","certificate","r6","1","3. A separate declaration."),cell("next","certificate","r7","1","SCT25 Next clause"));for(String label:Arrays.asList("i","ii")){JsonNode range=clauseRange(scopes,"SCT24(2)("+label+")");assertEquals(variant<2&&!closed,range.path("scopeComplete").asBoolean());assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));}assertTrue(strings(clauseRange(scopes,"SCT24(2)").path("orderedMemberIds")).contains("tail"));
        }
    }

    @Test void anUnclassifiedChildTailDoesNotInvalidateIndependentPrecedingOrFollowingPeers() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT91 Declaration"),p("one","(1) An independent first declaration."),p("two","(2) This declaration includes:"),p("a","(a) The first component."),p("b","(b) The second component."),p("unknown","An unclassified source heading"),p("three","(3) A separate declaration."),p("four","(4) Another separate declaration."),p("next","SCT92 Next clause"));
        for(String ref:Arrays.asList("SCT91(2)","SCT91(2)(a)","SCT91(2)(b)"))assertFalse(clauseRange(scopes,ref).path("scopeComplete").asBoolean());for(String ref:Arrays.asList("SCT91(1)","SCT91(3)","SCT91(4)"))assertTrue(clauseRange(scopes,ref).path("scopeComplete").asBoolean());assertTrue(strings(clauseRange(scopes,"SCT91(2)").path("orderedMemberIds")).contains("unknown"));
    }

    @Test void aQualifiedDefinedTermCanKeepItsFullNameOrItsExplicitUnqualifiedHead() throws Exception {
        String name="Assessed Amount in respect of the measured work";for(boolean fullName:Arrays.asList(false,true)){JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC91 Assessment"),p("one","(1) AA ("+name+") shall include:-"),p("a","(a) The first amount."),p("b","(b) The second amount."),p("tail","Provided that the "+(fullName?name:"Assessed Amount")+" shall include the first amount only."),p("two","(2) Another assessment."),p("next","SCC92 Next clause"));for(String label:Arrays.asList("a","b")){JsonNode range=clauseRange(scopes,"SCC91(1)("+label+")");assertTrue(range.path("scopeComplete").asBoolean());assertTrue(strings(range.path("contextMemberIds")).contains("tail"));}}
    }

    @Test void referencesUsedAsCalculationInputsCannotOverrideTheProvisosOwnNativeOwner() throws Exception {
        for(int quotedLeft:Arrays.asList(1800,2400)){
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC14.302 Payment"),indent(p("outer","(1) Clause 14.2(1) is amended by substituting with the following:-"),1200,600),indent(p("quoted","(1) The Surveyor shall value:"),quotedLeft,600),indent(p("a","(a) First input amount."),2400,600),indent(p("b","(b) Second input amount."),2400,600),indent(p("e","(e) The amount for this sub-clause is assessed separately."),2400,600),indent(p("provided","Provided that the amount for this sub-clause, calculated using Clause 14.2(1)(a) and Clause 14.2(1)(b), shall be adjusted as follows:-"),2400,0),indent(p("i","(i) First adjustment."),3000,600),indent(p("ii","(ii) Second adjustment."),3000,600),indent(p("two","(2) Clause 14.3 is amended by deleting the specified words."),1200,600),p("next","SCC15 Next clause"));
        JsonNode owner=clauseRange(scopes,"SCC14.302(1)(1)(e)");for(String label:Arrays.asList("i","ii")){JsonNode range=clauseRange(scopes,"SCC14.302(1)(1)(e)("+label+")");assertEquals(owner.path("id"),range.path("parentId"));if(quotedLeft==2400)assertFalse(range.path("scopeComplete").asBoolean());}if(quotedLeft==2400){assertFalse(owner.path("scopeComplete").asBoolean());assertTrue(clauseRange(scopes,"SCC14.302(1)(1)").path("scopeComplete").asBoolean());}for(JsonNode range:scopes.path("ranges"))assertNotEquals("SCC14.302(1)(1)(i)",range.path("reference").asText());
        }
    }

    @Test void anIdenticallyDeclaredProvisoStaysInThePhysicalLastItemWithoutGrantingApplicability() throws Exception {
        for(boolean identical:Arrays.asList(true,false)){
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20 Adjustment"),p("one","(1) The index values include:-"),indent(p("a","(a) The base index shall apply."),2400,600),indent(p("b","(b) The current index shall apply."),2400,600),indent(p("tail","Provided that if the indices are unavailable the published index shall apply."),2400,identical?600:500),p("two","(2) Next requirement."),p("next","SCC21 Next clause"));JsonNode last=clauseRange(scopes,"SCC20(1)(b)");assertEquals(identical,last.path("scopeComplete").asBoolean());assertEquals(identical,strings(last.path("orderedMemberIds")).contains("tail"));assertFalse(last.path("semanticApplicabilityVerified").asBoolean());assertFalse(last.path("automaticEditEligible").asBoolean());assertTrue(strings(clauseRange(scopes,"SCC20(1)").path("orderedMemberIds")).contains("tail"));
        }
    }

    @Test void successiveProvisosAndTheirClosingTextStayWithTheChildThatOwnsTheRomanList() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC22.303 Conditions"),indent(p("four","(4) The requirements include:-"),1800,400),indent(p("a","(a) First source condition."),2400,600),indent(p("g","(g) A condition with its own exceptions."),2400,600),indent(p("first-proviso","Provided that the stated circumstances apply."),2400,0),indent(p("second-proviso","Provided further that the adjustment takes into account:-"),2400,0),indent(p("i","(i) First adjustment, or"),3000,600),indent(p("ii","(ii) Second adjustment, or"),3000,600),indent(p("iii","(iii) Third adjustment."),3000,600),indent(p("closing","(with the foregoing adjustments applied together)."),2400,0),indent(p("avoidance","For the avoidance of doubt, the adjustment in SCC22.303(4)(g)(iii) remains part of this exception."),2400,0),indent(p("five","(5) A separate requirement."),1800,400),p("next","SCC22.304 Next clause"));
        JsonNode owner=clauseRange(scopes,"SCC22.303(4)(g)");assertEquals(Arrays.asList("g","first-proviso","second-proviso","i","ii","iii","closing","avoidance"),strings(owner.path("orderedMemberIds")));assertEquals("five",owner.path("endBoundary").path("memberId").asText());for(String label:Arrays.asList("i","ii","iii"))assertEquals(owner.path("id"),clauseRange(scopes,"SCC22.303(4)(g)("+label+")").path("parentId"));for(JsonNode range:scopes.path("ranges"))assertNotEquals("SCC22.303(4)(i)",range.path("reference").asText());
    }

    @Test void explicitListsRetainCoordinatedConditionalAndParentheticalParentCompletions() throws Exception {
        String[][] texts={{"(1) The Contractor declares the following obligations:-","(a) first declaration,","(b) second declaration,","and, in relation thereto, gives the stated undertaking."},{"(1) Should there be a balance after deducting:-","(a) the first offset,","(b) another offset,","such balance will be released to the Contractor."},{"(1) The Contractor shall, in respect of the following design:-","(a) the first system,","(b) the second system,","(referred to as the Contractor's Design), have the like liability."},{"(1) In the event the Contractor requires access because:-","(a) First condition.","(b) Second condition.","the Contractor shall write to the Surveyor."}};
        for(String[] text:texts){JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC5.302 Conditions"),indent(p("one",text[0]),1800,400),indent(p("a",text[1]),2400,600),indent(p("b",text[2]),2400,600),indent(p("tail",text[3]),1800,20),p("two","(2) Next condition."),p("next","SCC5.303 Another clause"));for(String label:Arrays.asList("a","b")){JsonNode range=clauseRange(scopes,"SCC5.302(1)("+label+")");assertTrue(range.path("scopeComplete").asBoolean(),text[3]);assertTrue(strings(range.path("contextMemberIds")).contains("tail"));assertFalse(strings(range.path("orderedMemberIds")).contains("tail"));}}
    }

    @Test void anExplicitFractionOwnerKeepsItsFormulaAndVariableDefinitionsToTheNextPeer() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC20.302 Price adjustment"),indent(p("four","(4) The conversion factor is determined as follows:-"),1800,400),indent(p("a","(a) Use the officially published conversion factor."),2400,600),indent(p("b","(b) In other cases, calculate the conversion factor using the following fraction."),2400,600),indent(p("formula","i.e. X = Sum(Ln x Wn) / Sum(Wn)"),2340,0),indent(p("where","where"),2340,0),indent(p("x","X means the conversion factor."),2340,0),indent(p("l","Ln means the labour index for the month."),2340,0),p("five","(5) Next price adjustment."),p("next","SCC20.303 Another clause"));
        JsonNode a=clauseRange(scopes,"SCC20.302(4)(a)"),b=clauseRange(scopes,"SCC20.302(4)(b)");assertTrue(a.path("scopeComplete").asBoolean());assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("a"),strings(a.path("orderedMemberIds")));assertEquals(Arrays.asList("b","formula","where","x","l"),strings(b.path("orderedMemberIds")));assertEquals("five",b.path("endBoundary").path("memberId").asText());
    }

    @Test void aFormulaWithoutItsOwnerOrAParentCompletionWithoutIntroductionAndLayoutRemainsUnknown() throws Exception {
        for(int variant=0;variant<3;variant++){
            ObjectNode one=p("one",variant==1?"(1) Parent provision.":"(1) The following requirements apply:-"),a=p("a","(a) First item,"),b=p("b",variant==0?"(b) An ordinary final requirement.":"(b) Second item,"),tail=p("tail",variant==0?"i.e. X = A / B":"and, in relation thereto, gives the undertaking.");if(variant!=2){indent(one,1800,400);indent(a,2400,600);indent(b,2400,600);indent(tail,1800,0);}
            JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC5.302 Conditions"),one,a,b,tail,p("two","(2) Next condition."),p("next","SCC5.303 Another clause"));assertFalse(clauseRange(scopes,"SCC5.302(1)(b)").path("scopeComplete").asBoolean(),"variant "+variant);assertFalse(strings(clauseRange(scopes,"SCC5.302(1)(b)").path("orderedMemberIds")).contains("tail"));
        }
    }

    @Test void repeatedLettersInTwoExplicitListsHaveIndependentBoundSourceFrames() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC7 Warranty"),indent(p("one","(2) The Contractor warrants the work without :-"),1800,600),indent(p("first-a","(a) first defect,"),1800,600),indent(p("first-b","(b) second defect,"),1800,600),indent(p("period","for a period of three years from completion."),1800,600),indent(p("proviso","Provided that no responsibility arises for defects due to :-"),1800,600),indent(p("second-a","(a) misuse by others, or"),2400,600),indent(p("second-b","(b) improper maintenance by others."),2400,600),p("three","(3) Next warranty term."),p("next","SCC8 Next source clause"));
        List<JsonNode> a=new ArrayList<>();for(JsonNode range:scopes.path("ranges"))if(range.path("reference").asText().equals("SCC7(2)(a)"))a.add(range);assertEquals(2,a.size());assertTrue(a.get(0).path("scopeComplete").asBoolean());assertTrue(a.get(1).path("scopeComplete").asBoolean());assertNotEquals(a.get(0).path("sourceFrameId"),a.get(1).path("sourceFrameId"));assertTrue(strings(a.get(0).path("contextMemberIds")).contains("period"));assertFalse(strings(a.get(0).path("contextMemberIds")).contains("proviso"));assertEquals(2,scopes.path("referenceFrames").size());
    }

    @Test void amendmentNumbersAndSuffixedNumbersRemainUnderTheirActualSourceParents() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC8 Completion"),p("oneA","(1A) The first inserted condition:"),p("a","(a) First reason."),p("oneB","(1B) Another inserted condition:"),p("ba","(a) Another reason."),p("seven","(7) Clause 8.3(1) is amended by substituting with the following:-"),p("quoted-one","(1) The Works shall be completed within the stated time."),p("eight","(8) Clause 8.4(2) is amended by substituting with the following:-"),p("quoted-two","(2) Another replacement condition."),p("next","SCC9 Next clause"));
        assertEquals("a",clauseRange(scopes,"SCC8(1A)(a)").path("orderedMemberIds").path(0).asText());assertEquals("ba",clauseRange(scopes,"SCC8(1B)(a)").path("orderedMemberIds").path(0).asText());assertEquals(clauseRange(scopes,"SCC8(7)").path("id"),clauseRange(scopes,"SCC8(7)(1)").path("parentId"));assertEquals("eight",clauseRange(scopes,"SCC8(7)").path("endBoundary").path("memberId").asText());assertEquals("quoted-two",clauseRange(scopes,"SCC8(8)(2)").path("orderedMemberIds").path(0).asText());
    }

    @Test void embeddedCertificateDecimalIntroductionsOwnTheirSeparateLetterLists() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT24 Non-collusion"),p("two","2. We warrant that in relation to the tender:"),p("a","(a) First certificate undertaking."),p("b","(b) Another certificate undertaking."),p("three","3. This certificate exception applies to dealings with:"),p("other-a","(a) The Employer."),p("other-b","(b) An independent adviser."),p("next","SCT25 Next condition"));
        assertEquals("a",clauseRange(scopes,"SCT24(2)(a)").path("orderedMemberIds").path(0).asText());assertEquals("other-a",clauseRange(scopes,"SCT24(3)(a)").path("orderedMemberIds").path(0).asText());assertEquals("three",clauseRange(scopes,"SCT24(2)").path("endBoundary").path("memberId").asText());
    }

    @Test void replacementCompositeLabelsStayInTheQuoteAndTheNextOuterAmendmentExitsIt() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC11 Source amendment"),indent(p("outer","(1) Clause 11.1 is amended by substituting with the following:-"),1200,600),indent(p("q1","(1) First quoted provision."),1800,600),indent(p("q2","(2) Second quoted provision."),1800,600),indent(p("q3a","(3) (a) First quoted child."),1800,600),indent(p("q3b","(b) Second quoted child."),2400,600),indent(p("q4","(4) Last quoted provision."),1800,600),indent(p("next-outer","(2) Clause 11.2 is amended by deleting the specified words."),1200,600),p("next","SCC12 Another clause"));
        assertEquals("q3a",clauseRange(scopes,"SCC11(1)(3)(a)").path("orderedMemberIds").path(0).asText());assertEquals("q4",clauseRange(scopes,"SCC11(1)(4)").path("orderedMemberIds").path(0).asText());assertEquals("next-outer",clauseRange(scopes,"SCC11(1)").path("endBoundary").path("memberId").asText());assertEquals("next-outer",clauseRange(scopes,"SCC11(2)").path("orderedMemberIds").path(0).asText());
    }

    @Test void insertedSuffixedNumbersBelongToTheExplicitAddingInstructionAndIThenJAreLetterPeers() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC15 Source amendments"),indent(p("two","(2) Clause 15.1 is amended by adding the following new Clause 15.1(1A):-"),1200,600),indent(p("added","(1A) The added source requirement."),1800,600),indent(p("three","(3) Clause 15.1 is amended by adding the following after Clause 15.1(1)(h):-"),1200,600),indent(p("i","(i) First added letter item."),1800,600),indent(p("j","(j) Second added letter item."),1800,600),p("next","SCC16 Next clause"));
        assertEquals(clauseRange(scopes,"SCC15(2)").path("id"),clauseRange(scopes,"SCC15(2)(1A)").path("parentId"));assertEquals("j",clauseRange(scopes,"SCC15(3)(i)").path("endBoundary").path("memberId").asText());assertEquals(clauseRange(scopes,"SCC15(3)").path("id"),clauseRange(scopes,"SCC15(3)(j)").path("parentId"));
    }

    @Test void aProvisoIntroducesItsOwnParentLevelRomanListAndFormWordsAreNotClauseLabels() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC14 Payment"),indent(p("outer","(1) Clause 14.2 is amended by substituting with the following:-"),1200,600),indent(p("quoted","(1) The Surveyor shall value:"),1800,600),indent(p("a","(a) First estimated sum."),2400,600),indent(p("b","(b) Second estimated sum."),2400,600),indent(p("provided","Provided further that the total estimated sum shall be adjusted to take into account:-"),1800,0),indent(p("i","(i) Retention."),3000,600),indent(p("ii","(ii) Fluctuation adjustment."),3000,600),indent(p("two","(2) Clause 14.3 is amended by deleting the specified words."),1200,600),p("date","(Date)"),cell("form-a","t1","r1","1","(A)"),cell("form-b","t1","r1","2","(B)"),p("next","SCC15 Next clause"));
        assertEquals(clauseRange(scopes,"SCC14(1)(1)").path("id"),clauseRange(scopes,"SCC14(1)(1)(i)").path("parentId"));for(JsonNode range:scopes.path("ranges"))assertFalse(strings(range.path("orderedMemberIds")).size()==1&&Arrays.asList("date","form-a","form-b").contains(range.path("orderedMemberIds").path(0).asText()));
    }

    @Test void aProvisoExplicitlyReferencingTheObservedReplacementSiblingsUsesTheirCommonParent() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC14.302 Payment"),indent(p("outer","(1) Clause 14.2(1) is amended by substituting with the following:-"),1200,600),indent(p("quoted","(1) The Surveyor shall value:"),2412,615),indent(p("a","(a) First estimated sum."),2995,585),indent(p("b","(b) Second estimated sum."),2995,585),indent(p("c","(c) Third estimated sum."),2995,585),indent(p("d","(d) Fourth estimated sum."),2995,585),indent(p("e","(e) Fifth estimated sum."),3024,616),indent(p("provided","Provided further that the total estimated sum under Clause 14.2(1)(a) to 14.2(1)(e) shall be adjusted to take into account:-"),2410,0),indent(p("i","(i) Retention."),3600,600),indent(p("ii","(ii) Fluctuation adjustment."),3600,600),indent(p("two","(2) Clause 14.3 is amended by deleting the specified words."),1200,600),p("next","SCC15 Next clause"));
        JsonNode parent=clauseRange(scopes,"SCC14.302(1)(1)");for(String label:Arrays.asList("i","ii")){JsonNode range=clauseRange(scopes,"SCC14.302(1)(1)("+label+")");assertEquals(parent.path("id"),range.path("parentId"));assertTrue(strings(parent.path("orderedMemberIds")).containsAll(strings(range.path("orderedMemberIds"))));}assertEquals("two",parent.path("endBoundary").path("memberId").asText());
    }

    @Test void aProvisoNamingOnlyItsOwnDescendantCannotLiftTheListToTheReplacementParent() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC22.303 Conditions"),indent(p("outer","(4) Clause 22.3 is amended by substituting with the following:-"),1200,600),indent(p("quoted","(1) The Contractor shall observe:"),1800,600),indent(p("a","(a) First condition."),2400,600),indent(p("g","(g) A condition with its own exceptions."),2400,600),indent(p("provided","Provided that the amount referred to in Clause 22.3(1)(g)(iii) shall be calculated taking into account:-"),2400,0),indent(p("i","(i) First exception."),3000,600),indent(p("ii","(ii) Second exception."),3000,600),indent(p("iii","(iii) Third exception."),3000,600),indent(p("five","(5) Clause 22.4 is amended by deleting the specified words."),1200,600),p("next","SCC22.304 Next clause"));
        JsonNode parent=clauseRange(scopes,"SCC22.303(4)(1)(g)");for(String label:Arrays.asList("i","ii","iii"))assertEquals(parent.path("id"),clauseRange(scopes,"SCC22.303(4)(1)(g)("+label+")").path("parentId"));
    }

    @Test void provisoOwnershipCannotCompareRawTwipsWhenThePriorIndentHasCharacterPrecedence() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC14.302 Payment"),indent(p("outer","(1) Clause 14.2 is amended by substituting with the following:-"),1200,600),layeredIndent(p("quoted","(1) The Surveyor shall value:"),1800,600,"100"),indent(p("a","(a) First estimated sum."),2400,600),indent(p("b","(b) Another estimated sum."),2400,600),indent(p("provided","Provided that the sum shall take into account:-"),1800,0),indent(p("i","(i) Retention."),3000,600),indent(p("ii","(ii) Adjustment."),3000,600),indent(p("two","(2) Clause 14.3 is amended by deleting the specified words."),1200,600),p("next","SCC15 Next clause"));
        assertEquals("i",clauseRange(scopes,"SCC14.302(1)(1)(b)(i)").path("orderedMemberIds").path(0).asText());for(JsonNode range:scopes.path("ranges"))assertNotEquals("SCC14.302(1)(1)(i)",range.path("reference").asText());
    }

    @Test void anInlineDefinitionReplacementCannotTurnTheNextDeclaredLetterPeerIntoItsChild() throws Exception {
        ObjectNode a=indent(p("a","(a) Clause 11.1 is amended by substituting the definitions with the following:-"),2400,600),b=indent(p("b","(b) Clause 11.2 is amended by deleting the specified words."),2400,600),c=indent(p("c","(c) Clause 11.3 is amended by deleting the specified words."),2400,600);
        for(ObjectNode member:Arrays.asList(a,b,c)){ObjectNode numbering=member.putObject("paragraphProperties").putObject("layoutEvidence").putObject("numbering");numbering.put("numbered",true);numbering.put("numId","7");numbering.put("level",0);numbering.put("format","lowerLetter");}
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Contract"),p("h","SCC11.302 Conditions"),p("two","(2) The following amendments apply:"),a,p("definition","\"Completion\" means completion of the Works."),b,c,p("three","(3) A separate condition."),p("next","SCC12 Next clause"));
        JsonNode parent=clauseRange(scopes,"SCC11.302(2)"),first=clauseRange(scopes,"SCC11.302(2)(a)");assertEquals(Arrays.asList("a","definition"),strings(first.path("orderedMemberIds")));assertEquals("b",first.path("endBoundary").path("memberId").asText());for(String label:Arrays.asList("a","b","c"))assertEquals(parent.path("id"),clauseRange(scopes,"SCC11.302(2)("+label+")").path("parentId"));
    }

    @Test void anExplicitArticleReplacementUsesOnlyItsMatchingPrefixWithinTheSourceFrame() throws Exception {
        String parent="SCC17.301(3)(17)(d)";for(String article:Arrays.asList("20.1","73.6")){
            ObjectNode[] members=articleReplacementMembers(article,article,true);JsonNode before=tree(members),scopes=clauseProjection(members),owner=clauseRange(scopes,parent),a=clauseRange(scopes,parent+"(a)"),b=clauseRange(scopes,parent+"(b)");assertEquals(before,tree(members));
            assertTrue(owner.path("scopeComplete").asBoolean());assertTrue(a.path("scopeComplete").asBoolean());assertTrue(b.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("intro","article-a","i","ii","iii","iv","article-b"),strings(owner.path("orderedMemberIds")));assertEquals(Arrays.asList("article-a","i","ii","iii","iv"),strings(a.path("orderedMemberIds")));assertEquals(Collections.singletonList("article-b"),strings(b.path("orderedMemberIds")));
            assertEquals("article-b",a.path("endBoundary").path("memberId").asText());assertEquals("next",b.path("endBoundary").path("memberId").asText());assertEquals("next",owner.path("endBoundary").path("memberId").asText());assertEquals(owner.path("id"),a.path("parentId"));assertEquals(owner.path("id"),b.path("parentId"));for(String roman:Arrays.asList("i","ii","iii","iv")){JsonNode child=clauseRange(scopes,parent+"(a)("+roman+")");assertTrue(child.path("scopeComplete").asBoolean());assertEquals(a.path("id"),child.path("parentId"));}for(JsonNode range:scopes.path("ranges"))assertFalse(range.path("reference").asText().startsWith("Article"));
        }
    }

    @Test void unintroducedOrDifferentlyNumberedArticlePrefixesCannotCreateReplacementTargets() throws Exception {
        String parent="SCC17.301(3)(17)(d)";for(boolean introduced:Arrays.asList(false,true)){
            ObjectNode[] members=articleReplacementMembers("81.3","20.1",introduced);JsonNode before=tree(members),scopes=clauseProjection(members);assertEquals(before,tree(members));for(JsonNode range:scopes.path("ranges")){assertNotEquals(parent+"(a)",range.path("reference").asText());assertNotEquals(parent+"(b)",range.path("reference").asText());}assertFalse(clauseRange(scopes,parent).path("scopeComplete").asBoolean());assertEquals("“20.1(a) The replacement provision permits disclosures where:-",scopes.path("members").get(8).path("textObservation").path("text").asText());
        }
    }

    private static ObjectNode[] articleReplacementMembers(String introducedArticle,String bodyArticle,boolean introduced){return new ObjectNode[]{p("title","Special Conditions of Contract"),p("owner","SCC17.301 Avoidance and resolution of disputes"),p("outer","(3) Clause 17.1(17) is amended by substituting with the following:-"),p("inner","(17) The arbitration shall be subject to the following:-"),p("a","(a) First provision."),p("b","(b) Second provision."),p("c","(c) Third provision."),p("intro",introduced?"(d) Article "+introducedArticle+" of the Arbitration Rules shall be deleted and replaced by the following:-":"(d) A fixed business provision refers to Article "+introducedArticle+"."),p("article-a","“"+bodyArticle+"(a) The replacement provision permits disclosures where:-"),p("i","(i) First permitted circumstance, or"),p("ii","(ii) Second permitted circumstance, or"),p("iii","(iii) Third permitted circumstance, or"),p("iv","(iv) Fourth permitted circumstance."),p("article-b",bodyArticle+"(b) A separately stated replacement requirement.”"),p("next","SCC18 Notices")};}

    @Test void anOpenStageHeadingOwnsItsNextNativeRowAndAnExplicitFormulaOwnsItsNativeCells() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Notes to Tenderers"),cell("number","t1","r1","1","6."),cell("h","t1","r1","2","Tender evaluation"),cell("a","t1","r2","2","(a) Evaluation has two stages:"),indent(cell("i","t1","r3","2","(i) Stage I – Evaluation of non-price submissions"),2400,600),indent(cell("stage-body","t1","r4","2","The following marking scheme will be used for evaluation."),1800,0),cell("ii","t1","r5","2","(ii) Stage II – Evaluation of price submissions"),cell("price","t1","r6","2","Price evaluation shall use the submitted tender sum."),cell("c","t2","r1","2","(c) The combined score is determined by the formula below:"),cell("left","t2","r1","3","Corporate score"),cell("plus","t2","r1","4","+"),cell("right","t2","r1","5","Technical score"),cell("next","t3","r1","1","7."));
        assertEquals(Arrays.asList("i","stage-body"),strings(clauseRange(scopes,"6(a)(i)").path("orderedMemberIds")));JsonNode formula=clauseRange(scopes,"6(c)");assertTrue(formula.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("c","left","plus","right"),strings(formula.path("orderedMemberIds")));
    }

    @Test void anUnnumberedColonInTheNextNativeRowDoesNotRestartTheStageList() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Notes to Tenderers"),cell("number","t1","r1","1","6."),cell("h","t1","r1","2","Tender evaluation"),cell("a","t1","r2","2","(a) Evaluation has two stages:"),cell("i","t1","r3","2","(i) Stage I – Evaluation of non-price submissions"),cell("blank","t1","r3","2",""),cell("stage-body","t1","r4","2","The Corporate Score and PASS Score will be combined with the Technical Score as follows:"),cell("ii","t1","r5","2","(ii) Stage II – Evaluation of price submissions"),cell("price","t1","r6","2","Price evaluation shall use the submitted tender sum."),cell("b","t2","r1","2","(b) A separate requirement."),cell("next","t3","r1","1","7."));
        JsonNode first=clauseRange(scopes,"6(a)(i)");assertTrue(first.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("i","blank","stage-body"),strings(first.path("orderedMemberIds")));assertEquals("ii",first.path("endBoundary").path("memberId").asText());assertEquals(Arrays.asList("ii","price"),strings(clauseRange(scopes,"6(a)(ii)").path("orderedMemberIds")));assertEquals(clauseRange(scopes,"6(a)").path("id"),clauseRange(scopes,"6(a)(ii)").path("parentId"));
    }

    @Test void explicitAppendicesHaveSeparateNumberSpacesAndDoNotBecomeTheLastClauseChildren() throws Exception {
        JsonNode scopes=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT24 Main condition"),p("main","(1) Main requirement."),p("appendix-c","APPENDIX C"),p("to-c","TO SPECIAL CONDITIONS OF TENDER"),p("c-one","1. First form declaration."),p("c-two","2. Another form declaration."),p("appendix-e","APPENDIX E"),p("to-e","TO SPECIAL CONDITIONS OF TENDER"),p("e-one","1. First certificate paragraph."),p("e-two","2. Another certificate paragraph."),p("next","SCT25 Next main condition"));
        assertEquals("appendix-c",clauseRange(scopes,"SCT24").path("endBoundary").path("memberId").asText());assertEquals("c-two",clauseRange(scopes,"SCT/Appendix C(2)").path("orderedMemberIds").path(0).asText());assertEquals("e-two",clauseRange(scopes,"SCT/Appendix E(2)").path("orderedMemberIds").path(0).asText());assertTrue(clauseRange(scopes,"SCT/Appendix C(2)").path("scopeComplete").asBoolean());
        JsonNode mention=clauseProjection(p("title","Special Conditions of Tender"),p("h","SCT24 Main condition"),p("word","APPENDIX C"),p("ordinary","This is an ordinary body paragraph, not an attachment title pair."),p("next","SCT25 Next main condition"));assertEquals("next",clauseRange(mention,"SCT24").path("endBoundary").path("memberId").asText());
    }
    private static JsonNode clauseProjectionWithRoot(ObjectNode root,ObjectNode... paragraphs) throws Exception {ObjectNode inventory=(ObjectNode)word(paragraphs).getCoverage().getNativeDocxParagraphs();inventory.putArray("storyRoots").add(root);return (JsonNode)OerTemplateEvidence.class.getDeclaredMethod("nativeClauseRanges",JsonNode.class).invoke(null,inventory);}

    private static ObjectNode tocRole(ObjectNode member,String role){ObjectNode observed=member.putArray("nativeFieldRoles").addObject();observed.put("fieldRole",role);observed.put("sourceText",member.path("textObservation").path("text").asText());observed.put("deleted",false);ObjectNode source=member.path("sourcePosition").deepCopy();source.put("nodePath",source.path("nodePath").asText()+"/{w}r[1]/{w}t[1]");observed.set("sourcePosition",source);ObjectNode field=member.path("sourcePosition").deepCopy();field.put("nodePath",field.path("nodePath").asText()+"/{w}r[1]/{w}fldChar[1]");observed.set("fieldSourcePosition",field);return member;}

    private static JsonNode clauseProjection(ObjectNode... paragraphs) throws Exception {DocumentParser.ParsedDocument parsed=word(paragraphs);return (JsonNode)OerTemplateEvidence.class.getDeclaredMethod("nativeClauseRanges",JsonNode.class).invoke(null,parsed.getCoverage().getNativeDocxParagraphs());}
    private static JsonNode clauseRange(JsonNode projection,String reference){JsonNode result=null;for(JsonNode range:projection.path("ranges"))if(reference.equals(range.path("reference").asText())){assertNull(result,"ambiguous test range");result=range;}assertNotNull(result,"missing "+reference);return result;}
    private static boolean hasSourceContextReason(JsonNode scopes,String member,String reason){for(JsonNode range:scopes.path("ranges"))for(JsonNode observed:range.path("contextObservations"))if(member.equals(observed.path("memberId").asText())&&reason.equals(observed.path("reason").asText()))return true;return false;}
    private static List<JsonNode> sourceFields(JsonNode evidence){List<JsonNode> result=new ArrayList<>();for(JsonNode group:evidence.path("groups"))if(group.path("kind").asText().equals("native_source_field_candidate"))result.add(group);return result;}
    private static ObjectNode raw(ObjectNode p,String text){ObjectNode raw=p.putObject("preNormalizedTextObservation");raw.put("status","observed_parser_channels_before_whitespace_normalization");raw.put("text",text);raw.put("originalText",text);raw.put("deletedText","");raw.put("strikeText","");return p;}
    private static ObjectNode pageGroup(){ObjectNode group=object();group.put("physicalPageNo",1);group.put("scope","whole_page");group.putObject("source").put("sourceSha256",SHA).put("sourceRevisionSha256",SHA);group.put("selectionStatus","candidate_unverified");group.putNull("selectedParser");group.put("sourceQualityAccepted",false);ObjectNode candidate=group.putArray("candidates").addObject();candidate.put("parser","first");ObjectNode page=candidate.putObject("page");page.put("physicalPageNo",1);page.putObject("mapping").putObject("originalPageTransform").putObject("targetFrame").put("sourceSha256",SHA).put("physicalPageNo",1);candidate.putArray("blocks").addObject().put("text","all candidate contents retained");return group;}
    private static DocumentBlock groupBlock(JsonNode pageGroup){DocumentBlock group=new DocumentBlock();group.setId("group");group.setSource("parser_page_group_candidate");group.setPageNo(1);group.setText("");group.setParserPageGroup(pageGroup);return group;}
    private static ObjectNode foreign(String id,String text){ObjectNode p=p(id,text);p.put("storyKind","footnote");p.put("storyRootNodePath","footnoteRoot");((ObjectNode)p.path("sourcePosition")).put("partName","word/footnotes.xml");return p;}
    @Test void nativePdfReferenceScopesKeepChildContinuationAndSeparateObservedSharedTails() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(scopePdfReferenceBlocks())),group=firstGroup(evidence,"explicit_reference_scope");
        assertTrue(group.path("scopeComplete").asBoolean());assertEquals(9,group.path("targets").size());Map<String,JsonNode> targets=scopePdfTargets(group);
        assertEquals(Arrays.asList("pdf-page:18:line:17","pdf-page:18:line:18","pdf-page:18:line:19","pdf-page:18:line:20","pdf-page:18:line:21"),strings(targets.get("SCC7.3(1)(c)").path("orderedMemberIds")));
        List<String> firstTail=Arrays.asList("pdf-page:18:line:22","pdf-page:18:line:23","pdf-page:18:line:24","pdf-page:18:line:25","pdf-page:18:line:26","pdf-page:18:line:27","pdf-page:18:line:28","pdf-page:18:line:29");
        for(String label:Arrays.asList("a","b","c")){assertEquals(firstTail,strings(targets.get("SCC7.3(1)("+label+")").path("sharedParentTailContextMemberIds")));assertEquals(Arrays.asList("pdf-page:18:line:34","pdf-page:18:line:35"),strings(targets.get("SCC7.3(2)("+label+")").path("sharedParentTailContextMemberIds")));}
        assertFalse(group.path("automaticEditEligible").asBoolean());assertFalse(group.path("semanticApplicabilityVerified").asBoolean());
    }

    @Test void nativePdfExplicitUnitsKeepRepeatedPageFurnitureAsPhysicalContext() {
        JsonNode group=firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(scopePdfReferenceBlocks())),"explicit_reference_scope");Map<String,JsonNode> targets=scopePdfTargets(group);
        assertAll(()->{JsonNode target=targets.get("SCC7.3(3)");assertEquals(Arrays.asList("pdf-page:18:line:36","pdf-page:18:line:47"),strings(target.path("orderedMemberIds")));assertEquals(Arrays.asList("pdf-page:18:line:48","pdf-page:19:line:0","pdf-page:19:line:1"),strings(target.path("physicalContextMemberIds")));},
            ()->{JsonNode target=targets.get("SCC7.3(7)");assertEquals(Arrays.asList("pdf-page:19:line:41","pdf-page:19:line:52","pdf-page:20:line:2","pdf-page:20:line:3"),strings(target.path("orderedMemberIds")));assertEquals(Arrays.asList("pdf-page:19:line:53","pdf-page:20:line:0","pdf-page:20:line:1"),strings(target.path("physicalContextMemberIds")));});
    }

    @Test void nativePdfScopeProofRejectsForeignIncompleteOrMisalignedObservations() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<7;mode++){final int current=mode;cases.add(()->{
            List<DocumentBlock> blocks=scopePdfReferenceBlocks();DocumentBlock child=blocks.stream().filter(b->"pdf-page:18:line:17".equals(b.getId())).findFirst().orElseThrow();
            if(current==0)((ObjectNode)child.getReadingContext()).put("sourceSha256",OTHER);if(current==1)child.getPdfGlyphs().get(0).setId("pdf-page/19/glyph/foreign");if(current==2)child.getPdfGlyphs().get(0).setCoordinateSpace("appearance_stream_local_matrix_no_page_box");if(current==3)child.getPdfGlyphs().get(0).setUnicode("?");if(current==4)child.getPdfGlyphs().clear();if(current==5)child.setPageNo(19);
            if(current==6){int at=-1;for(int i=0;i<blocks.size();i++)if("pdf-page:18:line:22".equals(blocks.get(i).getId()))at=i;DocumentBlock old=blocks.get(at);blocks.set(at,scopePdfLine(old.getId(),18,"An unresolved continuation with no explicit shared predicate",old.getBbox(),old.getBbox()[0]));}
            assertFalse(firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"explicit_reference_scope").path("scopeComplete").asBoolean(),"PDF layout proof case "+current);
        });}assertAll(cases);
    }

    @Test void nativePdfLastSentenceUsesExactMarkedSuffixAcrossTwoObservedLines() {
        List<DocumentBlock> blocks=scopePdfLastSentenceBlocks();String first=blocks.get(0).getText();JsonNode group=firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"inline_last_sentence_scope");
        assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("pdf-page:62:line:26","pdf-page:62:line:27"),strings(group.path("orderedMemberIds")));assertEquals(2,group.path("memberRanges").size());
        JsonNode start=group.path("memberRanges").get(0),end=group.path("memberRanges").get(1);assertEquals(first.indexOf('*'),start.path("start").asInt());assertEquals(first.length(),start.path("end").asInt());assertEquals(0,end.path("start").asInt());assertEquals(blocks.get(1).getText().length(),end.path("end").asInt());
        assertEquals("*The railway protection plans can be inspected at\nMTRCL or viewed from the official website of MTRCL.",group.path("text").asText());assertFalse(group.path("wholeParagraphTargetAssumed").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());
    }

    @Test void nativePdfLastSentenceCannotBorrowAnUnboundOrUnmarkedEarlierLine() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<6;mode++){final int current=mode;cases.add(()->{List<DocumentBlock> blocks=scopePdfLastSentenceBlocks();DocumentBlock first=blocks.get(0);
            if(current==0)((ObjectNode)first.getReadingContext()).put("sourceSha256",OTHER);if(current==1)first.getPdfGlyphs().get(0).setCoordinateSpace("unknown_frame");if(current==2)first.getPdfGlyphs().get(0).setUnicode("?");if(current==3)blocks.get(1).setPageNo(63);if(current==4)first.getPdfGlyphs().clear();if(current==5)blocks.set(0,scopePdfLine(first.getId(),62,first.getText().replace("*",""),first.getBbox(),first.getBbox()[0]));
            JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks));for(JsonNode group:evidence.path("groups"))if("inline_last_sentence_scope".equals(group.path("kind").asText()))assertFalse(group.path("scopeComplete").asBoolean(),"PDF last sentence proof case "+current);
        });}assertAll(cases);
    }

    @Test void repeatedNativePdfTopBodyDoesNotBecomePublicationContext() {
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();String text="The Contractor shall retain the complete source obligations.";
        blocks.add(2,scopePdfLine("legal-top-18",18,text,new double[]{.269884,.08,.5,.005378},.269884));int at=-1;for(int i=0;i<blocks.size();i++)if("pdf-page:19:line:2".equals(blocks.get(i).getId()))at=i;blocks.add(at,scopePdfLine("legal-top-19",19,text,new double[]{.269884,.08,.5,.005378},.269884));
        JsonNode group=firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"explicit_reference_scope"),target=scopePdfTargets(group).get("SCC7.3(3)");assertTrue(strings(target.path("orderedMemberIds")).contains("legal-top-19"));assertFalse(strings(target.path("physicalContextMemberIds")).contains("legal-top-19"));
    }

    @Test void nativePdfFooterSequenceNeedsMatchingPositionAndFont() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<3;mode++){final int current=mode;cases.add(()->{List<DocumentBlock> blocks=scopePdfReferenceBlocks();int at=-1;for(int i=0;i<blocks.size();i++)if("pdf-page:19:line:53".equals(blocks.get(i).getId()))at=i;DocumentBlock old=blocks.get(at);double[] box=old.getBbox().clone();if(current==0)box[0]=.6;if(current==1)box[1]=.915;DocumentBlock changed=scopePdfLine(old.getId(),19,old.getText(),box,box[0]);if(current==2)for(PdfPageEvidence.PdfGlyph glyph:changed.getPdfGlyphs())glyph.setFontSizeInPt(12);blocks.set(at,changed);
            JsonNode target=scopePdfTargets(firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"explicit_reference_scope")).get("SCC7.3(3)");assertTrue(strings(target.path("orderedMemberIds")).contains("pdf-page:18:line:48"),"Unmatched terminal numeric source stays in observed body, case "+current);assertFalse(strings(target.path("physicalContextMemberIds")).contains("pdf-page:18:line:48"));
        });}assertAll(cases);
    }

    @Test void nativePdfScopeProofRequiresCompleteConsistentOriginalGlyphFrame() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<6;mode++){final int current=mode;cases.add(()->{List<DocumentBlock> blocks=scopePdfReferenceBlocks();DocumentBlock child=blocks.stream().filter(b->"pdf-page:18:line:17".equals(b.getId())).findFirst().orElseThrow();PdfPageEvidence.PdfGlyph glyph=child.getPdfGlyphs().get(child.getPdfGlyphs().size()-1);
            if(current==0)glyph.setPdfboxPosition(null);if(current==1){double[] frame=glyph.getPdfboxPosition().clone();frame[8]=0;glyph.setPdfboxPosition(frame);}if(current==2)glyph.setTextMatrix(null);if(current==3){double[] matrix=glyph.getTextMatrix().clone();matrix[4]+=10;glyph.setTextMatrix(matrix);}if(current==4){double[] box=glyph.getBbox().clone();box[0]+=.05;glyph.setBbox(box);}if(current==5)for(PdfPageEvidence.PdfGlyph observed:child.getPdfGlyphs()){double[] box=observed.getBbox();double w=700,h=1000,baseline=(box[1]+box[3])*h;observed.setPdfboxPosition(new double[]{box[0]*w,baseline,box[2]*w,box[3]*h,box[0]*w,baseline,box[2]*w,box[3]*h,w,h});observed.setTextMatrix(new double[]{9.96,0,0,9.96,box[0]*w,h-baseline});}
            assertFalse(firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"explicit_reference_scope").path("scopeComplete").asBoolean(),"Original native frame proof case "+current);
        });}assertAll(cases);
    }

    @Test void repeatedNativeParentOccurrencesCannotShareAnotherParentsTail() {
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();int at=-1;for(int i=0;i<blocks.size();i++)if("pdf-page:18:line:17".equals(blocks.get(i).getId()))at=i;blocks.add(at,scopePdfLine("second-parent-one",18,"(1) A second observed parent promises that:-",new double[]{.2116418,.34,.6,.0053783},.2698844));
        JsonNode group=firstGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"explicit_reference_scope");assertFalse(group.path("scopeComplete").asBoolean());for(JsonNode target:group.path("targets"))if(target.path("reference").asText().startsWith("SCC7.3(1)("))assertFalse(target.path("scopeComplete").asBoolean());
    }

    @Test void nativePdfDashPrefixedNamedInstructionRetainsFirstObject() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(scopePdfReferenceBlocks())),roofing=namedWordGroup(evidence,"Roofing Installation");
        assertTrue(roofing.path("scopeComplete").asBoolean());assertFalse(roofing.path("automaticEditEligible").asBoolean());assertFalse(roofing.path("semanticApplicabilityVerified").asBoolean());
        assertEquals(Arrays.asList("pdf-page:18:line:27","pdf-page:19:line:52"),strings(roofing.path("orderedMemberIds")));
        assertEquals(2,roofing.path("memberRanges").size());int[][] expected={{60,80},{63,83}};
        for(int n=0;n<expected.length;n++){JsonNode range=roofing.path("memberRanges").get(n),symbol=roofing.path("markerRanges").get(n);assertEquals(expected[n][0],range.path("start").asInt());assertEquals(expected[n][1],range.path("end").asInt());assertEquals(expected[n][1],symbol.path("start").asInt());assertEquals(expected[n][1]+1,symbol.path("end").asInt());assertEquals("text",range.path("channel").asText());assertEquals("utf16",range.path("offsetUnit").asText());}
        assertTrue(strings(marker(evidence,"pdf-page:18:line:27","#").path("targetGroupIds")).contains(roofing.path("id").asText()));
    }

    @Test void nativePdfNamedInstructionWithoutDashKeepsExistingLiteralGroups() {
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();replaceScopePdfText(blocks,"pdf-page:18:line:5",blocks.stream().filter(b->"pdf-page:18:line:5".equals(b.getId())).findFirst().orElseThrow().getText().replace("# - ","# "));
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks));
        for(String term:Arrays.asList("Roofing Installation","Refuge Floor Installation","Waterproofing System Installation")){JsonNode group=namedWordGroup(evidence,term);assertEquals(2,group.path("memberRanges").size());assertTrue(group.path("scopeComplete").asBoolean());assertFalse(group.path("automaticEditEligible").asBoolean());}
    }

    @Test void nativePdfNamedInstructionDoesNotNormalizeAnInternalBodyHyphen() {
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();replaceScopePdfText(blocks,"pdf-page:18:line:5",blocks.stream().filter(b->"pdf-page:18:line:5".equals(b.getId())).findFirst().orElseThrow().getText().replace("# - ","# "));
        replaceScopePdfText(blocks,"pdf-page:18:line:27","of completion issued in respect of the Section in which the Roofing-Installation#, the");
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),roofing=namedWordGroup(evidence,"Roofing Installation");
        assertEquals(Arrays.asList("pdf-page:19:line:52"),strings(roofing.path("orderedMemberIds")));
        JsonNode original=null;for(JsonNode member:evidence.path("sourceMembers"))if("pdf-page:18:line:27".equals(member.path("id").asText()))original=member;
        assertNotNull(original);assertTrue(original.path("textObservation").path("text").asText().contains("Roofing-Installation#"));
    }

    @Test void nativePdfNamedInstructionCannotBorrowAnUnrequestedUnit() {
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();replaceScopePdfText(blocks,"pdf-page:18:line:5",blocks.stream().filter(b->"pdf-page:18:line:5".equals(b.getId())).findFirst().orElseThrow().getText().replace("# - ","# "));
        replaceScopePdfText(blocks,"pdf-page:18:line:6","in the Clause SCC7.3(1)(a) is to be deleted by the PQS as appropriate.]");blocks.removeIf(b->"pdf-page:18:line:8".equals(b.getId()));
        replaceScopePdfText(blocks,"pdf-page:20:line:4","(8) Roofing Installation# remains outside the explicitly requested clause.");
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),roofing=namedWordGroup(evidence,"Roofing Installation");
        assertEquals(Arrays.asList("pdf-page:18:line:27"),strings(roofing.path("orderedMemberIds")));assertEquals(1,roofing.path("memberRanges").size());assertFalse(roofing.path("semanticApplicabilityVerified").asBoolean());
    }

    @Test void nativePdfNamedObjectsRetainExactSameTargetLineContinuations() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(scopePdfWrappedNamedInstructionBlocks())),roofing=namedWordGroup(evidence,"Roofing Installation");
        assertTrue(roofing.path("scopeComplete").asBoolean());assertFalse(roofing.path("automaticEditEligible").asBoolean());assertFalse(roofing.path("semanticApplicabilityVerified").asBoolean());
        assertEquals(Arrays.asList("pdf-page:18:line:27","pdf-page:19:line:20","pdf-page:19:line:21","pdf-page:19:line:25","pdf-page:19:line:26","pdf-page:19:line:52"),strings(roofing.path("orderedMemberIds")));
        int[][] expected={{60,80},{73,80},{0,12},{71,78},{0,12},{63,83}};assertEquals(expected.length,roofing.path("memberRanges").size());
        for(int n=0;n<expected.length;n++){JsonNode range=roofing.path("memberRanges").get(n);assertEquals(expected[n][0],range.path("start").asInt());assertEquals(expected[n][1],range.path("end").asInt());assertEquals("text",range.path("channel").asText());assertEquals("utf16",range.path("offsetUnit").asText());}
        assertEquals(4,roofing.path("markerRanges").size());for(String id:Arrays.asList("pdf-page:19:line:21","pdf-page:19:line:26")){assertTrue(strings(marker(evidence,id,"#").path("targetGroupIds")).contains(roofing.path("id").asText()));boolean found=false;for(JsonNode range:roofing.path("markerRanges"))if(id.equals(range.path("memberId").asText())){found=true;assertEquals(12,range.path("start").asInt());assertEquals(13,range.path("end").asInt());}assertTrue(found);}
    }

    @Test void nativePdfNamedLineContinuationsRejectForeignOrUnverifiedFrames() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<10;mode++){final int current=mode;cases.add(()->{
            List<DocumentBlock> blocks=scopePdfWrappedNamedInstructionBlocks();blocks.removeIf(b->Arrays.asList("pdf-page:19:line:25","pdf-page:19:line:26").contains(b.getId()));DocumentBlock first=blocks.stream().filter(b->"pdf-page:19:line:20".equals(b.getId())).findFirst().orElseThrow(),second=blocks.stream().filter(b->"pdf-page:19:line:21".equals(b.getId())).findFirst().orElseThrow();
            if(current==0)((ObjectNode)first.getReadingContext()).put("sourceSha256",OTHER);if(current==1)second.getPdfGlyphs().clear();if(current==2)second.setPageNo(20);if(current==3)second.getPdfGlyphs().get(0).setCoordinateSpace("unknown_frame");if(current==4){double[] matrix=first.getPdfGlyphs().get(0).getTextMatrix().clone();matrix[4]+=10;first.getPdfGlyphs().get(0).setTextMatrix(matrix);}if(current==5){double[] box=first.getPdfGlyphs().get(0).getBbox().clone();box[0]+=.1;first.getPdfGlyphs().get(0).setBbox(box);}
            if(current==6)blocks.add(blocks.indexOf(second),scopePdfLine("intervening-native-line",19,"Do not merge across this observed source line.",new double[]{.269884,.388,.5,.005378},.269884));if(current==7){double[] frame=second.getPdfGlyphs().get(0).getPdfboxPosition().clone();frame[8]+=1;second.getPdfGlyphs().get(0).setPdfboxPosition(frame);}if(current>=8){double[] box=second.getBbox().clone();box[current==8?0:1]+=.1;blocks.set(blocks.indexOf(second),scopePdfLine(second.getId(),19,second.getText(),box,box[0]));}
            JsonNode roofing=namedWordGroup(OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),"Roofing Installation");assertEquals(Arrays.asList("pdf-page:18:line:27","pdf-page:19:line:52"),strings(roofing.path("orderedMemberIds")),"Unverified native continuation case "+current);
        });}assertAll(cases);
    }

    @Test void nativePdfNamedLineContinuationsRequireOneExplicitCompleteTarget() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();for(int mode=0;mode<3;mode++){final int current=mode;cases.add(()->{
            List<DocumentBlock> blocks=scopePdfWrappedNamedInstructionBlocks();
            if(current==0){replaceScopePdfText(blocks,"pdf-page:18:line:6","in the Clauses SCC7.3(1)(a) and SCC7.3(7) are to be deleted by the PQS as appropriate.]");blocks.removeIf(b->"pdf-page:18:line:8".equals(b.getId()));}
            if(current==1){int at=-1;for(int n=0;n<blocks.size();n++)if("pdf-page:19:line:20".equals(blocks.get(n).getId()))at=n;blocks.add(at,scopePdfLine("duplicate-six",19,"(6) A second independently observed parent.",new double[]{.2116418,.37,.6,.0053783},.2698844));}
            if(current==2){replaceScopePdfText(blocks,"pdf-page:18:line:6","in the Clauses SCC7.3(6), SCC7.3(6)(a) and SCC7.3(7) are to be deleted by the PQS as appropriate.]");blocks.removeIf(b->"pdf-page:18:line:8".equals(b.getId()));int at=-1;for(int n=0;n<blocks.size();n++)if("pdf-page:19:line:20".equals(blocks.get(n).getId()))at=n;blocks.add(at,scopePdfLine("overlapping-six-a",19,"(a) A separately named child.",new double[]{.2698844,.37,.6,.0053783},.3114503));}
            JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),roofing=namedWordGroup(evidence,"Roofing Installation");for(JsonNode range:roofing.path("memberRanges"))assertFalse(Arrays.asList("pdf-page:19:line:20","pdf-page:19:line:21","pdf-page:19:line:25","pdf-page:19:line:26").contains(range.path("memberId").asText()),"Missing, ambiguous or overlapping explicit target case "+current);
        });}assertAll(cases);
    }

    private static List<DocumentBlock> scopePdfWrappedNamedInstructionBlocks(){
        List<DocumentBlock> blocks=scopePdfReferenceBlocks();replaceScopePdfText(blocks,"pdf-page:18:line:5",blocks.stream().filter(b->"pdf-page:18:line:5".equals(b.getId())).findFirst().orElseThrow().getText().replace("# - ","# "));int at=-1;for(int n=0;n<blocks.size();n++)if("pdf-page:19:line:40".equals(blocks.get(n).getId()))at=n;
        blocks.add(at++,scopePdfLine("pdf-page:19:line:20",19,"completion, then from the date of completion of the Section in which the Roofing",new double[]{.2698844489610114,.38176403553712684,.6134419875418332,.005378314496429837},.2698844489610114));
        blocks.add(at++,scopePdfLine("pdf-page:19:line:21",19,"Installation#, the Refuge Floor Installation# or the Waterproofing System Installation# (as",new double[]{.2698844489610114,.3954509707518119,.6135008250057992,.005378314496429837},.2698844489610114));
        blocks.add(at++,scopePdfLine("pdf-page:19:line:25",19,"to as “the Relevant Warrantor”) for the supply and installation of the Roofing",new double[]{.2698844489610114,.45005603663791777,.6134823742331269,.005378314496429837},.2698844489610114));
        blocks.add(at,scopePdfLine("pdf-page:19:line:26",19,"Installation#, the Refuge Floor Installation# or the Waterproofing System Installation# (as",new double[]{.2698844489610114,.46374297185260277,.6135008250057992,.005378314496429837},.2698844489610114));return blocks;
    }

    @Test void deletionOfTheQuotedWordsUsesTheOwnerText() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC80 Test owner"),p("n","[Guidance Note: Delete the words ‘obsolete reference’.]"),p("b","Retain the context *obsolete reference here."),p("next","SCC81 Next clause")));
        JsonNode group=namedWordGroup(evidence,"obsolete reference");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(group.path("orderedMemberIds")));assertFalse(group.path("automaticEditEligible").asBoolean());
    }

    @Test void aQuotedClauseReferenceTargetsItsLiteralInTheOwnerRatherThanTheReferencedClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("other","SCC70 External referenced provision"),p("other-body","(2) Keep this external clause intact."),p("h","SCC80 Test owner"),p("n","[Guidance Note: Where Clause SCC70 Option A is used, delete ‘Clauses SCC70(2) and SCC70(3)’.]"),p("b","The exception uses *Clauses SCC70(2) and SCC70(3) in this sentence."),p("next","SCC81 Next clause")));
        JsonNode group=namedWordGroup(evidence,"Clauses SCC70(2) and SCC70(3)");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("b"),strings(group.path("orderedMemberIds")));
    }

    @Test void prefixMarkedQuotedReferenceCanContinueOnTheNextNativePdfLine() {
        List<DocumentBlock> blocks=new ArrayList<>();String[] text={"SCC80 Test owner","[Guidance Note: Where Clause SCC70 Option A is used, delete the words ‘Clauses SCC70(3) and SCC70(4)’.]","The exception uses #Clauses SCC70(3) and","SCC70(4) in the current sentence.","SCC81 Next clause"};
        for(int i=0;i<text.length;i++)blocks.add(scopePdfLine("quoted-"+i,8,text[i],new double[]{.2,.12+i*.02,.65,.006},.2));
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks)),group=namedWordGroup(evidence,"Clauses SCC70(3) and SCC70(4)");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("quoted-2","quoted-3"),strings(group.path("orderedMemberIds")));JsonNode marker=group.path("markerRanges").get(0);assertEquals("quoted-2",marker.path("memberId").asText());assertEquals("#",text[2].substring(marker.path("start").asInt(),marker.path("end").asInt()));assertFalse(group.path("automaticEditEligible").asBoolean());
    }

    @Test void anExplicitDeletionTargetStillSelectsTheNamedExternalClause() {
        JsonNode evidence=OerTemplateEvidence.analyse("SCC",word(p("h","SCC70 Named provision"),p("two","(2) Keep *obsolete reference under this target."),p("owner","SCC80 Instruction owner"),p("n","[Guidance Note: Delete the words ‘obsolete reference’ from Clause SCC70(2).]"),p("local","Do not delete *obsolete reference here."),p("next","SCC81 Next clause")));
        JsonNode group=namedWordGroup(evidence,"obsolete reference");assertTrue(group.path("scopeComplete").asBoolean());assertEquals(Collections.singletonList("two"),strings(group.path("orderedMemberIds")));
    }

    @Test void nativeQuotedDeletionRefusesForeignMissingAmbiguousOrDiscontinuousObservations() {
        List<org.junit.jupiter.api.function.Executable> cases=new ArrayList<>();
        for(int mode=0;mode<11;mode++){final int current=mode;cases.add(()->{
            String[] text={"SCC80 Test owner","[Guidance Note: Delete the words ‘Clauses SCC70(3) and SCC70(4)’.]","The exception uses #Clauses SCC70(3) and","SCC70(4) in the current sentence.","SCC81 Next clause"};List<DocumentBlock> blocks=new ArrayList<>();
            for(int i=0;i<text.length;i++)blocks.add(scopePdfLine("q-"+i,8,text[i],new double[]{.2,.12+i*.02,.65,.006},.2));DocumentBlock second=blocks.get(3);
            if(current==0)((ObjectNode)second.getReadingContext()).put("sourceSha256",OTHER);
            if(current==1)second.getPdfGlyphs().clear();
            if(current==2)second.setPageNo(9);
            if(current==3)second.getPdfGlyphs().get(0).getPdfboxPosition()[8]+=10;
            if(current==4)blocks.set(3,scopePdfLine("q-3",8,text[3],new double[]{.5,.18,.35,.006},.5));
            if(current==5)blocks.set(3,scopePdfLine("q-3",8,text[3],new double[]{.2,.7,.65,.006},.2));
            if(current==6)blocks.add(3,scopePdfLine("intervening",8,"Unrelated source text.",new double[]{.2,.17,.65,.006},.2));
            if(current==7)blocks.set(2,scopePdfLine("q-2",8,text[2].replace("#",""),new double[]{.2,.16,.65,.006},.2));
            if(current==8)blocks.set(2,scopePdfLine("q-2",8,text[2].replace("#","##"),new double[]{.2,.16,.65,.006},.2));
            if(current==9)blocks.add(3,scopePdfLine("next-clause",8,"SCC81 A new owner boundary",new double[]{.2,.17,.65,.006},.2));
            if(current==10)blocks.add(4,scopePdfLine("repeated",8,"#Clauses SCC70(3) and SCC70(4)",new double[]{.2,.20,.65,.006},.2));
            JsonNode evidence=OerTemplateEvidence.analyse("SCC",scopePdf(blocks));for(JsonNode group:evidence.path("groups"))if("explicit_quoted_word_scope".equals(group.path("kind").asText()))assertFalse(group.path("scopeComplete").asBoolean(),"Unsafe complete scope mode "+current);
        });}assertAll(cases);
    }

    private static JsonNode namedWordGroup(JsonNode evidence,String term){for(JsonNode group:evidence.path("groups"))if("explicit_quoted_word_scope".equals(group.path("kind").asText())&&term.equals(group.path("quotedSourceLiteral").asText()))return group;throw new AssertionError("Missing named word group "+term);}
    private static void replaceScopePdfText(List<DocumentBlock> blocks,String id,String text){for(int n=0;n<blocks.size();n++){DocumentBlock old=blocks.get(n);if(id.equals(old.getId())){blocks.set(n,scopePdfLine(id,old.getPageNo(),text,old.getBbox(),old.getBbox()[0]));return;}}throw new AssertionError("Missing source block "+id);}

    private static Map<String,JsonNode> scopePdfTargets(JsonNode group){Map<String,JsonNode> out=new LinkedHashMap<>();for(JsonNode target:group.path("targets"))out.put(target.path("reference").asText(),target);return out;}
    private static DocumentParser.ParsedDocument scopePdf(List<DocumentBlock> blocks){ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(66);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),66,false,"fixture",blocks,coverage,SHA,Collections.emptyList());}
    private static DocumentBlock scopePdfLine(String id,int page,String text,double[] bbox,double bodyLeft){
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind("pdf_line");block.setSource("native");block.setPageNo(page);block.setLocation("pdf-page/"+page+"/"+id);block.setText(text);block.setOriginalText(text);block.setBbox(bbox.clone());ObjectNode context=JsonUtils.mapper().createObjectNode();context.put("sourceSha256",SHA);block.setReadingContext(context);
        java.util.regex.Matcher marker=java.util.regex.Pattern.compile("^\\s*\\([A-Za-z0-9]+\\)\\s*[#*]?\\s*").matcher(text);int markerEnd=marker.find()?marker.end():0;double step=(bbox[0]+bbox[2]-bodyLeft)/Math.max(1,text.length()-markerEnd);
        for(int n=0;n<text.length();n++){if(Character.isWhitespace(text.charAt(n)))continue;double x=n<markerEnd?bbox[0]+n*.002:bodyLeft+(n-markerEnd)*step,w=595.4400024414062,h=841.6799926757812,width=Math.max(step*.8,.000001),baseline=(bbox[1]+bbox[3])*h;PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId("pdf-page/"+page+"/glyph/"+id+"/"+n);glyph.setUnicode(text.substring(n,n+1));glyph.setCoordinateSpace("normalized_rendered_crop_top_left");glyph.setEligibleForNativeText(true);glyph.setFontSizeInPt(9);glyph.setFontSize(9);glyph.setBbox(new double[]{x,bbox[1],width,bbox[3]});glyph.setTextMatrix(new double[]{9.960000038146973,0,0,9.960000038146973,x*w,h-baseline});glyph.setPdfboxPosition(new double[]{x*w,baseline,width*w,bbox[3]*h,x*w,baseline,width*w,bbox[3]*h,w,h});block.getPdfGlyphs().add(glyph);}return block;
    }
    private static List<DocumentBlock> scopePdfReferenceBlocks(){
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(scopePdfLine("pdf-page:18:line:0",18,"HKHA Standard Library of SCC for Building Works 2013 Edition",new double[]{0.43297392936572704,0.037393286100121936,0.4524720908999866,0.005378314496429865},0.43297392936572704));
        blocks.add(scopePdfLine("pdf-page:18:line:1",18,"(Consolidated up to 1 January 2023)",new double[]{0.6151921202080141,0.051080219048686065,0.27025390005769956,0.005378314496429865},0.6151921202080141));
        blocks.add(scopePdfLine("pdf-page:18:line:2",18,"SCC7.3 Warranty and deed of warranty (roofing installation, refuge floor installation and/or other",new double[]{0.12095929359781764,0.10865509530941977,0.7620673178491032,0.005283647295321242},0.12095929359781764));
        blocks.add(scopePdfLine("pdf-page:18:line:3",18,"waterproofing system installation) #",new double[]{0.20438666607760866,0.1226271629217659,0.2621926293468355,0.005283647295321242},0.20438666607760866));
        blocks.add(scopePdfLine("pdf-page:18:line:4",18,"[Guidance Note:",new double[]{0.12095929359781764,0.15057129814645812,0.13053539837114525,0.005283647295321242},0.12095929359781764));
        blocks.add(scopePdfLine("pdf-page:18:line:5",18,"# - The Roofing Installation, the Refuge Floor Installation or the Waterproofing System Installation",new double[]{0.13607416657091057,0.16425823336114317,0.7463385466674881,0.005283647295321242},0.13607416657091057));
        blocks.add(scopePdfLine("pdf-page:18:line:6",18,"in the Clauses SCC7.3(1)(a), SCC7.3(1)(b), SCC7.3(1)(c), SCC7.3(2)(a), SCC7.3(2)(b),",new double[]{0.18141205864598595,0.1778025298611285,0.7010006545924127,0.005283647295321242},0.18141205864598595));
        blocks.add(scopePdfLine("pdf-page:18:line:8",18," SCC7.3(2)(c), SCC7.3(3), SCC7.3(6) and SCC7.3(7) are to be deleted by the PQS as appropriate.]",new double[]{0.18141205864598595,0.191394795608584,0.698592623750101,0.006661495265438883},0.18141205864598595));
        blocks.add(scopePdfLine("pdf-page:18:line:10",18,"(1) The Contractor shall unconditionally warrant to the Employer that:-",new double[]{0.2116418045921718,0.21848346112442382,0.5178187183908793,0.005378314496429865},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:18:line:11",18,"(a)# the roofing installation including that of the waterproofing membrane, insulation,",new double[]{0.2698844489610114,0.2458573315537939,0.6135462344074314,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:12",18,"screeding, roofing tiles, and expansion joints and fittings and accessories",new double[]{0.3114503496370169,0.2594016280537792,0.5712822794986597,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:13",18,"(hereinafter referred to as “Roofing Installation”),",new double[]{0.3114503496370169,0.27308856326846426,0.3389594266483894,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:14",18,"(b)# the floor finishing installation to the refuge floor including any waterproofing",new double[]{0.2698844489610114,0.30049803898960736,0.6127808348544116,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:15",18,"membrane, insulation, screeding and floor tiles (hereinafter referred to as “Refuge",new double[]{0.3114503496370169,0.3141849016884232,0.5715330049984169,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:16",18,"Floor Installation”), and",new double[]{0.3114503496370169,0.3278718369031083,0.16601167277882684,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:17",18,"(c)# other waterproofing system installation to the areas as specified in the Contract",new double[]{0.2698844489610114,0.3551030686177786,0.6128646833657776,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:18",18,"comprising, as the case may be, the waterproofing membrane (whether liquid, sheet",new double[]{0.3114503496370169,0.3687900038324637,0.5711982259787084,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:19",18,"or other forms of application), screeding (with or without waterproofing additives),",new double[]{0.3114503496370169,0.38247693904714875,0.5712583959984784,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:20",18,"insulation, tiles, joints, fittings and accessories (hereinafter referred to as",new double[]{0.3114503496370169,0.3961638380038992,0.5713487022802797,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:21",18,"“Waterproofing System Installation”)",new double[]{0.3114503496370169,0.4098507369606496,0.2569360554646627,0.005378314496429837},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:22",18,"shall remain watertight and free from leakage or defect for a period of ten years from the",new double[]{0.27008599802638233,0.4370820049332546,0.6131569999822667,0.005378314496429837},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:23",18,"date of completion certified by the Contract Manager in the certificate of completion",new double[]{0.27008599802638233,0.450768903890005,0.6126478611608057,0.005378314496429837},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:24",18,"issued under Clause 8.7 of the General Conditions of Contract. Where the Works are",new double[]{0.27008599802638233,0.46445580284675547,0.6126648768733812,0.005378314496429837},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:25",18,"phased for completion in Sections, the expression “certificate of completion” shall, for the",new double[]{0.27008599802638233,0.47814273806144053,0.6130508055351088,0.005378314496429837},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:26",18,"purposes of this Clause SCC7.3 of the Special Conditions of Contract, mean the certificate",new double[]{0.27008599802638233,0.49171081976652065,0.6128665028169717,0.005378314496429837},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:27",18,"of completion issued in respect of the Section in which the Roofing Installation#, the",new double[]{0.27008599802638233,0.5053977549812056,0.6126313579696934,0.005378314496429892},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:28",18,"Refuge Floor Installation# or the Waterproofing System Installation#, as the case may be,",new double[]{0.27008599802638233,0.5190846539379561,0.6130379924985309,0.005378314496429892},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:29",18,"are certified completed.",new double[]{0.27008599802638233,0.5327715528947066,0.1632405973581159,0.005378314496429892},0.27008599802638233));
        blocks.add(scopePdfLine("pdf-page:18:line:30",18,"(2) Notwithstanding the foregoing, the Contractor shall not be held responsible for:-",new double[]{0.2116418045921718,0.5601453870661421,0.6030666183598103,0.005378314496429892},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:18:line:31",18,"(a)# any leakage or defect in the Roofing Installation; and",new double[]{0.2698844489610114,0.587376655038747,0.4030968750643854,0.005378314496429892},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:32",18,"(b)# any leakage or defect in the Refuge Floor Installation; and",new double[]{0.2698844489610114,0.614750452952248,0.43720610097349344,0.005378314496429892},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:33",18,"(c)# any leakage or defect in the Waterproofing System Installation",new double[]{0.2698844489610114,0.6421243233816181,0.4674358469196793,0.005378314496429892},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:18:line:34",18,"which is due to structural movement if such structural movement is not attributable to any",new double[]{0.26827221019448244,0.6693556276121576,0.6144889151345435,0.005378314496429892},0.26827221019448244));
        blocks.add(scopePdfLine("pdf-page:18:line:35",18,"default of the Contractor.",new double[]{0.26827221019448244,0.6830780956027465,0.17392177528414443,0.005378314496429892},0.26827221019448244));
        blocks.add(scopePdfLine("pdf-page:18:line:36",18,"(3) If the Contractor is in breach of the warranty as provided in this Clause SCC7.3 of the",new double[]{0.2116418045921718,0.7104519660321166,0.671601808442233,0.005378314496429892},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:18:line:47",18,"which the Employer may have against the Contractor for breach of warranty or otherwise.",new double[]{0.2698844489610114,0.8607228309325142,0.6087094668556982,0.005378314496429892},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:18:line:48",18,"15",new double[]{0.8625218577590983,0.9491412852060013,0.020908825609344484,0.005378314496429892},0.8625218577590983));
        blocks.add(scopePdfLine("pdf-page:19:line:0",19,"HKHA Standard Library of SCC for Building Works 2013 Edition",new double[]{0.43297392936572704,0.037393286100121936,0.4524720908999866,0.005378314496429865},0.43297392936572704));
        blocks.add(scopePdfLine("pdf-page:19:line:1",19,"(Consolidated up to 1 January 2023)",new double[]{0.6151921202080141,0.051080219048686065,0.27025390005769956,0.005378314496429865},0.6151921202080141));
        blocks.add(scopePdfLine("pdf-page:19:line:2",19,"(4) In carrying out any work of repair or rectification, or making good any leakage or defect",new double[]{0.21144028115287397,0.10813273631018228,0.6709904728410264,0.005378314496429865},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:9",19,"(5) If the Contractor fails or refuses to carry out the work of repair or rectification, or make",new double[]{0.2116418045921718,0.2173429315337795,0.6707735737578351,0.005378314496429865},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:16",19,"(6) In addition to the obligations under Clauses SCC7.3(1) to SCC7.3(5) of the Special",new double[]{0.2116418045921718,0.3271590059089556,0.6716492679297177,0.005378314496429837},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:17",19,"Conditions of Contract, the Contractor shall (at no cost to the Employer and within 28",new double[]{0.2698844489610114,0.3407033024089409,0.613387045240987,0.005378314496429837},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:40",19,"SCC7.3 of the Special Conditions of Contract or the Contract or otherwise.",new double[]{0.3114503496370169,0.7097391350379638,0.508497887745294,0.005378314496429892},0.3114503496370169));
        blocks.add(scopePdfLine("pdf-page:19:line:41",19,"(7) The Contractor shall further unconditionally warrant that the Relevant Warrantor shall",new double[]{0.2116418045921718,0.7369703667526343,0.6710916445778459,0.005378314496429892},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:52",19,"other document(s) relate to the supply and installation of the Roofing Installation#, the",new double[]{0.2698844489610114,0.8872650168581266,0.6135308587635379,0.005378314496429892},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:19:line:53",19,"16",new double[]{0.8625218577590983,0.9491412852060013,0.020908825609344484,0.005378314496429892},0.8625218577590983));
        blocks.add(scopePdfLine("pdf-page:20:line:0",20,"HKHA Standard Library of SCC for Building Works 2013 Edition",new double[]{0.43297392936572704,0.037393286100121936,0.4524720908999866,0.005378314496429865},0.43297392936572704));
        blocks.add(scopePdfLine("pdf-page:20:line:1",20,"(Consolidated up to 1 January 2023)",new double[]{0.6151921202080141,0.051080219048686065,0.27025390005769956,0.005378314496429865},0.6151921202080141));
        blocks.add(scopePdfLine("pdf-page:20:line:2",20,"Refuge Floor Installation# or the Waterproofing System Installation# (as the case may",new double[]{0.2698844489610114,0.10813273631018228,0.6134394249345176,0.005378314496429865},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:20:line:3",20,"be).",new double[]{0.2698844489610114,0.12181967152486732,0.029776318207765717,0.005378314496429851},0.2698844489610114));
        blocks.add(scopePdfLine("pdf-page:20:line:4",20,"(8) The warranty in or under this Clause SCC7.3 of the Special Conditions of Contract and",new double[]{0.2116418045921718,0.14905090323953768,0.6708740279646062,0.005378314496429865},0.2698844489610114));
        return blocks;
    }
    private static List<DocumentBlock> scopePdfLastSentenceBlocks(){
        List<DocumentBlock> blocks=new ArrayList<>();
        blocks.add(scopePdfLine("pdf-page:62:line:26",62,"the boundary is more extensive. *The railway protection plans can be inspected at",new double[]{0.2638385151474181,0.479711002506511,0.6193850070456325,0.005378314496429837},0.2638385151474181));
        blocks.add(scopePdfLine("pdf-page:62:line:27",62,"MTRCL or viewed from the official website of MTRCL.",new double[]{0.2638385151474181,0.4934216866683563,0.38717583145588175,0.005378314496429837},0.2638385151474181));
        blocks.add(scopePdfLine("pdf-page:62:line:28",62,"[Guidance Note: * If the relevant railway protection plans are not yet available for",new double[]{0.2636369660820471,0.5142372218880449,0.6191252867941976,0.00537831676255085},0.2636369660820471));
        blocks.add(scopePdfLine("pdf-page:62:line:29",62,"inspection at MTRCL or viewing from the official website of",new double[]{0.4067748315880098,0.528303922709686,0.47607106479101574,0.00528364729532127},0.4067748315880098));
        blocks.add(scopePdfLine("pdf-page:62:line:30",62,"MTRCL, the project team should delete the last sentence and",new double[]{0.4067748315880098,0.5419908579243711,0.4764247046005669,0.00528364729532127},0.4067748315880098));
        blocks.add(scopePdfLine("pdf-page:62:line:31",62,"prepare railway protection plan(s) for incorporation into the",new double[]{0.4067748315880098,0.555535190682291,0.4760376483916205,0.00528364729532127},0.4067748315880098));
        blocks.add(scopePdfLine("pdf-page:62:line:32",62,"tender documents.]",new double[]{0.4067748315880098,0.5691274201718118,0.14226450330673718,0.00537831676255085},0.4067748315880098));
        return blocks;
    }

    private static DocumentParser.ParsedDocument pdf(DocumentBlock... blocks){ParseCoverage coverage=new ParseCoverage();coverage.setTotalPages(2);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),2,false,"fixture",Arrays.asList(blocks),coverage,SHA,Collections.emptyList());}
    private static List<ObjectNode> commonSelectionMembers(){return new ArrayList<>(Arrays.asList(p("common","(Guidance Note: Select Alternative (a) or (b) for condition SCT91)"),cell("ha","t1","r1","c1","*SCT91 Tender arrangements"),cell("a","t1","r1","c2","(Alternative (a) - for electronic tenders)"),cell("body-a","t1","r2","c1","(1) First electronic item."),cell("hac","t2","r1","c1","*SCT91 Tender arrangements (Cont’d)"),cell("ac","t2","r1","c2","(Alternative (a) - for electronic tenders)"),cell("hb","t3","r1","c1","*SCT91 Tender arrangements"),cell("b","t3","r1","c2","(Alternative (b) - for hardcopy tenders)"),cell("body-b","t3","r2","c1","(1) First hardcopy item."),cell("hbc","t4","r1","c1","*SCT91 Tender arrangements (Cont’d)"),cell("bc","t4","r1","c2","(Alternative (b) - for hardcopy tenders)"),cell("next","t5","r1","c1","SCT92 Other conditions")));}
    private static List<ObjectNode> nestedConditionMembers(){return new ArrayList<>(Arrays.asList(cell("h","t1","r1","c1","SCT6 Other submissions"),cell("three","t1","r2","c1","(3) Tenderers should note"),cell("condition","t1","r3","c1","(Guidance Note: Condition SCT6(3)(c) below applies where domestic blocks are in the Contract. Mark it as Not used if inapplicable.)"),cell("body","t1","r4","c1","*(c) The submission for domestic blocks (#and the submission for special terms of payment) shall not relieve the Contractor."),cell("words","t1","r4","c2","(# - Delete the words if precast facade is not applicable)"),cell("d","t1","r5","c1","(d) A different submission"),cell("next","t1","r6","c1","SCT7 Next condition")));}
    private static List<JsonNode> sourceRelations(JsonNode evidence){List<JsonNode> result=new ArrayList<>();for(JsonNode group:evidence.path("groups"))for(JsonNode relation:group.path("sourceInstructionRelationships"))result.add(relation);return result;}
    private static DocumentBlock pdfLine(String id,int page,String text,int smallPrefixLength,double y){DocumentBlock block=new DocumentBlock();block.setId(id);block.setSource("native");block.setKind("pdf_line");block.setPageNo(page);block.setText(text);List<PdfPageEvidence.PdfGlyph> glyphs=new ArrayList<>();double smallX=.10,bodyX=.20;for(int n=0;n<text.length();n++){if(Character.isWhitespace(text.charAt(n)))continue;boolean small=n<smallPrefixLength;PdfPageEvidence.PdfGlyph glyph=new PdfPageEvidence.PdfGlyph();glyph.setId(id+":"+n);glyph.setUnicode(text.substring(n,n+1));glyph.setBbox(new double[]{small?smallX:bodyX,y,small?.003:.005,.004});glyph.setFontSizeInPt(small?6:9);glyph.setEligibleForNativeText(true);glyphs.add(glyph);if(small)smallX+=.003;else bodyX+=.005;}block.setPdfGlyphs(glyphs);return block;}

    private static DocumentParser.ParsedDocument word(ObjectNode... paragraphs){ParseCoverage coverage=new ParseCoverage();ObjectNode inventory=object();inventory.put("version","docx-native-paragraphs-v1");inventory.put("sourceSha256",SHA);ArrayNode list=inventory.putArray("paragraphs");for(ObjectNode p:paragraphs)list.add(p);coverage.setNativeDocxParagraphs(inventory);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),0,false,"fixture",Collections.emptyList(),coverage,SHA,Collections.emptyList());}
    private static ObjectNode p(String id,String text){ObjectNode p=object();p.put("id",id);p.put("storyKind","body");p.put("storyRootNodePath",BODY);ObjectNode pos=p.putObject("sourcePosition");pos.put("kind","docx_native");pos.put("sourceSha256",SHA);pos.put("partName","word/document.xml");pos.put("partSha256",OTHER);pos.put("nodePath",BODY+"/{w}p["+id+"]");ObjectNode channels=p.putObject("textObservation");channels.put("status","observed_parser_paragraph_channels");channels.put("text",text);channels.put("originalText",text);channels.put("deletedText","");channels.put("strikeText","");return p;}
    private static ObjectNode cell(String id,String table,String row,String cell,String text){ObjectNode p=p(id,text);String t=BODY+"/{w}tbl["+table+"]",r=t+"/{w}tr["+row+"]",c=r+"/{w}tc["+cell+"]";p.put("tableNodePath",t);p.put("rowNodePath",r);p.put("cellNodePath",c);((ObjectNode)p.path("sourcePosition")).put("nodePath",c+"/{w}p["+id+"]");return p;}
    private static JsonNode firstGroup(JsonNode evidence,String kind){for(JsonNode group:evidence.path("groups"))if(kind.equals(group.path("kind").asText()))return group;throw new AssertionError("Missing group "+kind);}
    private static JsonNode marker(JsonNode evidence,String member,String symbol){for(JsonNode marker:evidence.path("markerCandidates"))if(member.equals(marker.path("memberId").asText())&&symbol.equals(marker.path("symbol").asText()))return marker;throw new AssertionError("Missing marker");}
    private static ObjectNode indent(ObjectNode p,int left,int hanging){ObjectNode attributes=p.putObject("directParagraphLayout").putArray("ind").addObject().putObject("attributes");attributes.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}left",String.valueOf(left));if(hanging!=0)attributes.put("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}hanging",String.valueOf(hanging));return p;}
    private static ObjectNode layeredIndent(ObjectNode member,int left,int hanging,String directCharacters){
        String ns="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";ObjectNode direct=member.putObject("directParagraphLayout").putArray("ind").addObject().putObject("attributes");direct.put(ns+"leftChars",directCharacters);ArrayNode layers=member.putObject("paragraphProperties").putObject("layoutEvidence").putArray("layers");
        ObjectNode style=layers.addObject().put("kind","paragraph_style").putArray("declarations").addObject();style.put("name","ind");style.putObject("attributes").put(ns+"leftChars","200").put(ns+"left","480");ObjectNode numbering=layers.addObject().put("kind","numbering_abstract_level").putArray("declarations").addObject();numbering.put("name","ind");numbering.putObject("attributes").put(ns+"left",String.valueOf(left)).put(ns+"hanging",String.valueOf(hanging));ObjectNode override=layers.addObject().put("kind","direct_paragraph").putArray("declarations").addObject();override.put("name","ind");override.set("attributes",direct.deepCopy());return member;
    }
    private static boolean hasIssue(JsonNode evidence,String code){for(JsonNode issue:evidence.path("issues"))if(code.equals(issue.path("code").asText()))return true;return false;}
    private static List<String> strings(JsonNode values){List<String> result=new ArrayList<>();for(JsonNode value:values)result.add(value.asText());return result;}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
}
