package com.consense.document;

import com.consense.config.ConsenseProperties;
import com.consense.common.JsonUtils;
import com.consense.ocr.OcrClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OerDocxSourceScopesTest {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void explicitManualSequenceKeepsAllPayloadAndResolvedListContinuationsInsideItsNativeChapter()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Authored chapter","heading");paragraph(d,"6.Provide the following system:","clause");
            numbered(d,"First item");paragraph(d,"Continuation of first item",null);d.createParagraph();
            numbered(d,"Second item");XWPFTableCell cell=d.createTable(1,1).getRow(0).getCell(0);cell.setText("Intermediate table payload");cell.getParagraphs().get(0).setStyle("heading");
            XWPFParagraph next=paragraph(d,"7.Provide next mock-up","clause");next.getCTP().getPPr().addNewInd().setLeftChars(BigInteger.valueOf(993));
            paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),six=range(scopes,"manual-sequence","6"),seven=range(scopes,"manual-sequence","7");
        assertTrue(six.path("scopeComplete").asBoolean());assertTrue(seven.path("scopeComplete").asBoolean());
        assertEquals("source_interval_containment_only",six.path("hierarchyStatus").asText());
        assertEquals(seven.path("startBoundary").path("sourcePosition"),six.path("endBoundary").path("sourcePosition"));
        List<JsonNode> body=members(scopes,six);assertTrue(body.stream().anyMatch(m->text(m).isEmpty()));
        assertTrue(body.stream().anyMatch(m->text(m).equals("Intermediate table payload")));assertFalse(body.stream().anyMatch(m->text(m).startsWith("7.")));
        JsonNode a=range(scopes,"native-list","6(a)"),b=range(scopes,"native-list","6(b)");
        assertEquals(six.path("id"),a.path("parentId"));assertEquals(b.path("startBoundary").path("sourcePosition"),a.path("endBoundary").path("sourcePosition"));
        assertEquals(Arrays.asList("(a) First item","Continuation of first item",""),members(scopes,a).stream().map(this::text).toList());
        assertNull(parsed.getCoverage().getNativeDocxParagraphs(),"Ordinary route does not expose the opt-in XML inventory");
        for(JsonNode member:scopes.path("members"))assertFalse(member.has("nativeXml"));
        verifyNoInteractions(ocr);
    }

    @Test void isolatedNumbersDatesAndDifferentStylesDoNotInventASourceSequence()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"2026.10.17 date","clause");paragraph(d,"1.0 version","clause");
            paragraph(d,"6.Standalone number","clause");paragraph(d,"7.Different source style","other");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        for(JsonNode range:parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("ranges"))assertEquals("native-outline",range.path("kind").asText());
        verifyNoInteractions(ocr);
    }

    @Test void repeatedManualReferencesAndAnUnprovedParentEndNeverBecomeComplete()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"5.Previous interval","clause");paragraph(d,"6.First source interval","clause");numbered(d,"Item ending at an ambiguous peer");
            paragraph(d,"8.Nonconsecutive peer","clause");paragraph(d,"9.First duplicate","clause");paragraph(d,"9.Second duplicate","clause");paragraph(d,"10.Next peer","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");int repeated=0;
        for(JsonNode range:scopes.path("ranges"))if("manual-sequence".equals(range.path("kind").asText())&&"9".equals(range.path("reference").asText())){repeated++;assertFalse(range.path("scopeComplete").asBoolean());}
        assertEquals(2,repeated);assertFalse(range(scopes,"native-list","6(a)").path("scopeComplete").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void distinctStylesWithIdenticalResolvedSourceIndentationKeepConsecutiveIntervals()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");XWPFParagraph first=paragraph(d,"6.First source interval","clause"),second=paragraph(d,"7.Next source interval","other");
            for(XWPFParagraph p:Arrays.asList(first,second)){CTInd ind=p.getCTP().getPPr().addNewInd();ind.setLeft(BigInteger.valueOf(1440));ind.setHanging(BigInteger.valueOf(360));}
            paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),six=range(scopes,"manual-sequence","6"),seven=range(scopes,"manual-sequence","7");
        assertTrue(six.path("scopeComplete").asBoolean());assertTrue(seven.path("scopeComplete").asBoolean());
        assertEquals(seven.path("startBoundary").path("sourcePosition"),six.path("endBoundary").path("sourcePosition"));
        assertFalse(six.path("semanticApplicabilityVerified").asBoolean());assertFalse(six.path("automaticEditEligible").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void differentIndentationOrCharacterUnitsCannotProveCrossStylePeers()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(boolean mixedUnits:Arrays.asList(false,true))try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");XWPFParagraph first=paragraph(d,"6.Outer source interval","clause"),second=paragraph(d,"7.Possibly nested number","other");
            CTInd a=first.getCTP().getPPr().addNewInd(),b=second.getCTP().getPPr().addNewInd();a.setLeft(BigInteger.valueOf(1440));a.setHanging(BigInteger.valueOf(360));b.setLeft(BigInteger.valueOf(mixedUnits?1440:2160));b.setHanging(BigInteger.valueOf(360));
            if(mixedUnits)b.setLeftChars(BigInteger.valueOf(600));paragraph(d,"Next chapter","heading");
            for(JsonNode range:parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("ranges"))assertEquals("native-outline",range.path("kind").asText());
        }
        verifyNoInteractions(ocr);
    }

    @Test void explicitBalancedGuidanceRegionSeparatesRestartedNumbersWithoutChangingTheirIds()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Policy","heading");paragraph(d,"1.Body first interval","clause");paragraph(d,"2.Body second interval","clause");d.createParagraph();
            paragraph(d,"(Guidance Notes to the Contractor:",null);d.createParagraph();paragraph(d,"1. Guidance first (nested clarification)","clause");paragraph(d,"2. Guidance second)","clause");
            paragraph(d,"Following unnumbered payload",null);paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),region=range(scopes,"source-region","(Guidance Notes to the Contractor:");
        assertTrue(region.path("scopeComplete").asBoolean());assertEquals("source_interval_containment_only",region.path("hierarchyStatus").asText());
        assertEquals(Arrays.asList("(Guidance Notes to the Contractor:","","1. Guidance first (nested clarification)","2. Guidance second)"),members(scopes,region).stream().map(this::text).toList());
        List<JsonNode> manual=new ArrayList<>();for(JsonNode candidate:scopes.path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText()))manual.add(candidate);
        assertEquals(4,manual.size());int guidance=0,body=0;
        for(JsonNode candidate:manual){
            assertTrue(candidate.path("scopeComplete").asBoolean(),candidate.toString());assertFalse(candidate.path("semanticApplicabilityVerified").asBoolean());assertFalse(candidate.path("automaticEditEligible").asBoolean());
            String firstId=candidate.path("orderedMemberIds").get(0).asText();assertEquals("docx-source-range:"+DocumentParseProbe.sha256(("manual-sequence|"+firstId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),candidate.path("id").asText());
            if(region.path("parentId").equals(candidate.path("parentId"))){body++;assertTrue(members(scopes,candidate).stream().noneMatch(m->text(m).contains("Guidance")));}
            else{guidance++;assertEquals(region.path("id"),candidate.path("parentId"));assertTrue(members(scopes,candidate).stream().noneMatch(m->text(m).equals("Following unnumbered payload")));}
        }
        assertEquals(2,body);assertEquals(2,guidance);verifyNoInteractions(ocr);
    }

    @Test void OrdinaryMentionsUnclosedBlocksAndHeadingChangesDoNotResetDuplicateNumbers()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String opening:Arrays.asList("Consult the Guidance Notes to the Contractor:","(Guidance Notes to the Contractor:","(Other commentary:")){
            try(XWPFDocument d=document()){
                paragraph(d,"Policy","heading");paragraph(d,"1.Body first","clause");paragraph(d,"2.Body second","clause");paragraph(d,opening,null);
                paragraph(d,"1.Later first","clause");paragraph(d,"2.Later second"+(opening.startsWith("(Guidance")?"":")"),"clause");paragraph(d,"Next chapter","heading");
                JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");int manual=0;
                for(JsonNode candidate:scopes.path("ranges")){assertNotEquals("source-region",candidate.path("kind").asText());if("manual-sequence".equals(candidate.path("kind").asText())){manual++;assertFalse(candidate.path("scopeComplete").asBoolean());}}
                assertEquals(4,manual);
            }
        }
        try(XWPFDocument d=document()){
            paragraph(d,"Policy","heading");paragraph(d,"1.Body first","clause");paragraph(d,"2.Body second","clause");paragraph(d,"(Guidance Notes to the Contractor:",null);
            paragraph(d,"New source chapter","heading");paragraph(d,"1.Later first","clause");paragraph(d,"2.Later second)","clause");
            for(JsonNode candidate:parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("ranges"))assertNotEquals("source-region",candidate.path("kind").asText());
        }
        verifyNoInteractions(ocr);
    }

    @Test void literalAlphaRestartProvesOnlyPhysicalDecimalIntervalsAcrossDifferentIndentation()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        List<String> first=new ArrayList<>();for(char label='a';label<='j';label++)first.add(label+(label=='i'?"\t":".\t")+"Observed source child "+label);
        try(XWPFDocument d=physicalDocument(first,Arrays.asList("a.\tNext source child","b.\tNext second child","i.\tNested first","ii.\tNested second","c.\tNext third child"),"21. Closing source interval")){parsed=parse(d,ocr);}
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),firstRange=range(scopes,"manual-sequence","19"),nextRange=range(scopes,"manual-sequence","20");
        for(JsonNode candidate:Arrays.asList(firstRange,nextRange)){
            assertTrue(candidate.path("scopeComplete").asBoolean(),candidate.toString());assertEquals("physical_decimal_source_interval_with_literal_alpha_restart",candidate.path("basis").asText());
            assertEquals("source_interval_containment_only",candidate.path("hierarchyStatus").asText());assertFalse(candidate.path("semanticApplicabilityVerified").asBoolean());assertFalse(candidate.path("automaticEditEligible").asBoolean());
        }
        assertEquals(nextRange.path("startBoundary").path("sourcePosition"),firstRange.path("endBoundary").path("sourcePosition"));
        assertTrue(members(scopes,firstRange).stream().anyMatch(m->text(m).startsWith("i ")));assertTrue(members(scopes,nextRange).stream().anyMatch(m->text(m).startsWith("ii.")));verifyNoInteractions(ocr);
    }

    @Test void nestedGuidanceOpeningsCannotPromoteAnInnerFragmentWhileEarlierProvenRegionsRemain()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(boolean closedOuter:Arrays.asList(false,true))try(XWPFDocument d=document()){
            paragraph(d,"Policy","heading");paragraph(d,"(Guidance Notes to the Employer:",null);paragraph(d,"1.Earlier complete first","clause");paragraph(d,"2.Earlier complete second)","clause");
            paragraph(d,"1.Body first","clause");paragraph(d,"2.Body second","clause");paragraph(d,"(Guidance Notes to the Contractor:",null);paragraph(d,"(Guidance Notes to the Supplier:",null);paragraph(d,"1.Inner first","clause");paragraph(d,"2.Inner second)","clause");
            if(closedOuter)paragraph(d,")",null);paragraph(d,"Next chapter","heading");JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");int regions=0;
            for(JsonNode candidate:scopes.path("ranges"))if("source-region".equals(candidate.path("kind").asText())){regions++;assertEquals("(Guidance Notes to the Employer:",candidate.path("reference").asText());assertTrue(candidate.path("scopeComplete").asBoolean());}
            assertEquals(1,regions,"Nested or unclosed outer guidance does not establish a separate inner source region");
        }
        verifyNoInteractions(ocr);
    }

    @Test void repeatedReferencesRemainUnknownAcrossStylesWithMatchingSourceIndentation()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Policy","heading");for(String style:Arrays.asList("clause","other"))for(String label:Arrays.asList("1. First source item","2. Second source item")){XWPFParagraph p=paragraph(d,label+" "+style,style);CTInd ind=p.getCTP().getPPr().addNewInd();ind.setLeft(BigInteger.valueOf(1440));ind.setHanging(BigInteger.valueOf(360));}
            paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        int manual=0;for(JsonNode candidate:parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText())){manual++;assertFalse(candidate.path("scopeComplete").asBoolean(),candidate.toString());assertTrue(candidate.path("unknownReasons").toString().contains("duplicate_explicit_number_in_same_source_sequence"));}
        assertEquals(4,manual);verifyNoInteractions(ocr);
    }

    @Test void emptyNativeOutlineBoundaryCannotClaimCompleteManualClause()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"6. Previous source interval","clause");paragraph(d,"7. The following obligations apply:","clause");paragraph(d,"(a) First obligation; and","clause");
            XWPFParagraph empty=d.createParagraph();empty.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(2));
            paragraph(d,"(b) Continued obligation beyond the empty outline paragraph","clause");paragraph(d,"8. Next explicit source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),six=range(scopes,"manual-sequence","6"),seven=range(scopes,"manual-sequence","7");
        assertTrue(six.path("scopeComplete").asBoolean());assertFalse(seven.path("scopeComplete").asBoolean());assertEquals("partial_unknown_scope",seven.path("status").asText());
        assertTrue(seven.path("unknownReasons").toString().contains("empty_native_outline_heading_does_not_close_manual_source_scope"));
        assertEquals("next_native_outline_heading",seven.path("endBoundary").path("kind").asText());assertTrue(scopes.path("members").toString().contains("Continued obligation beyond the empty outline paragraph"));verifyNoInteractions(ocr);
    }

    @Test void nonemptyNativeOutlineHeadingStillClosesObservedManualIntervals()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");paragraph(d,"6. Previous source interval","clause");paragraph(d,"7. Final source interval","clause");paragraph(d,"Complete observed source payload",null);
            paragraph(d,"Next authored chapter","heading");paragraph(d,"A separately authored source paragraph",null);parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");assertTrue(range(scopes,"manual-sequence","6").path("scopeComplete").asBoolean());JsonNode seven=range(scopes,"manual-sequence","7");
        assertTrue(seven.path("scopeComplete").asBoolean());assertEquals("next_native_outline_heading",seven.path("endBoundary").path("kind").asText());assertFalse(seven.has("unknownReasons"));verifyNoInteractions(ocr);
    }

    @Test void observedSectionBreakFollowedByRealPeerOrHigherHeadingPreservesCompleteIntervals()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String between:Arrays.asList("none","blank"))try(XWPFDocument d=sectionTransitionDocument(true,between,1)){
            DocumentParser.ParsedDocument parsed=parse(d,ocr);JsonNode sections=parsed.getCoverage().getNativeDocxSections();assertEquals("paragraph_sectPr",sections.path("sections").get(0).path("endBoundary").path("kind").asText());
            JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),seven=range(scopes,"manual-sequence","7");assertTrue(range(scopes,"manual-sequence","6").path("scopeComplete").asBoolean());
            assertTrue(seven.path("scopeComplete").asBoolean(),"A bound native section ends before a real same-or-higher heading with only empty source paragraphs between");assertFalse(seven.has("unknownReasons"));assertEquals("next_native_outline_heading",seven.path("endBoundary").path("kind").asText());
        }
        verifyNoInteractions(ocr);
    }

    @Test void sectionBreakCannotHideContinuationTableMissingSectionOrDeeperHeading()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String defect:Arrays.asList("body","table","missing","deeper"))try(XWPFDocument d=sectionTransitionDocument(!defect.equals("missing"),defect,defect.equals("deeper")?3:1)){
            JsonNode seven=range(parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),"manual-sequence","7");assertFalse(seven.path("scopeComplete").asBoolean(),defect);assertTrue(seven.path("unknownReasons").toString().contains("empty_native_outline_heading_does_not_close_manual_source_scope"));
        }
        verifyNoInteractions(ocr);
    }

    @Test void sectionTransitionEvidenceMustMatchTheSameOriginalPartAndBothNativeBoundaries()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;try(XWPFDocument d=sectionTransitionDocument(true,"none",1)){parsed=parse(d,ocr);}
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes");
        for(String defect:Arrays.asList("part","endSource","startBoundary","foreignMember","fractionalBlankOrdinal","fractionalNextOrdinal")){
            ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.set("sourceSha256",scopes.path("sourceSha256"));inventory.set("paragraphs",scopes.path("members").deepCopy());ObjectNode sections=parsed.getCoverage().getNativeDocxSections().deepCopy();
            if(defect.equals("part"))((ObjectNode)sections.path("sourcePosition")).put("partName","word/foreign.xml");
            else if(defect.equals("endSource"))((ObjectNode)sections.path("sections").get(0).path("endBoundary").path("sourcePosition")).put("sourceSha256","f".repeat(64));
            else if(defect.equals("startBoundary"))((ObjectNode)sections.path("sections").get(1).path("startBoundary").path("sourcePosition")).put("nodePath","/foreign-boundary");
            else if(defect.equals("foreignMember"))((com.fasterxml.jackson.databind.node.ArrayNode)sections.path("sections").get(1).path("nativeMemberPaths")).set(0,JsonUtils.mapper().getNodeFactory().textNode("/foreign-member"));
            else{boolean changed=false;for(JsonNode candidate:inventory.path("paragraphs"))if(defect.equals("fractionalBlankOrdinal")?text(candidate).isBlank()&&candidate.path("paragraphProperties").path("outlineLevel").asInt(9)==2:text(candidate).equals("Next authored native section")){((ObjectNode)candidate).put("ordinalZeroBased",candidate.path("ordinalZeroBased").asDouble()+0.25d);changed=true;break;}assertTrue(changed,"The intended source ordinal must actually be made fractional");}
            JsonNode seven=range(OerDocxSourceScopes.observe(inventory,outline,sections),"manual-sequence","7");assertFalse(seven.path("scopeComplete").asBoolean(),defect);
        }
        verifyNoInteractions(ocr);
    }

    private XWPFDocument sectionTransitionDocument(boolean section,String between,int nextLevel)throws Exception{
        XWPFDocument d=document();paragraph(d,"Chapter","heading");paragraph(d,"6. Previous source interval","clause");paragraph(d,"7. Complete final source sentence.","clause");XWPFParagraph empty=d.createParagraph();CTPPr properties=empty.getCTP().addNewPPr();properties.addNewOutlineLvl().setVal(BigInteger.valueOf(2));if(section)properties.addNewSectPr();
        if(between.equals("blank"))d.createParagraph();else if(between.equals("body"))paragraph(d,"(b) Source obligation continues before the next real heading","clause");else if(between.equals("table"))d.createTable(1,1).getRow(0).getCell(0).setText("Source warranty table before next heading");
        XWPFParagraph next=paragraph(d,"Next authored native section","heading");next.getCTP().getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(nextLevel));paragraph(d,"Next section source payload",null);return d;
    }

    @Test void incompleteOrAmbiguousLiteralRunsDoNotProvePhysicalDecimalBoundaries()throws Exception {
        OcrClient ocr=mock(OcrClient.class);List<String> valid=Arrays.asList("a.\tFirst source child","b.\tSecond source child");
        List<List<String>> invalid=Arrays.asList(Arrays.asList("a.\tFirst","c.\tMissing child"),Arrays.asList("a.\tFirst","b.\tSecond","b.\tRepeated"),Arrays.asList("b.\tFirst","a.\tReversed"),Arrays.asList("c.\tNo restart","d.\tNo restart"),Arrays.asList("a.\tFirst","b.\tSecond","i.\tUnclosed nested sequence"),Arrays.asList("a.\tFirst","b.\tSecond","i Ordinary word without source tab","j.\tUnproved alpha continuation"),Arrays.asList("a.\tFirst","b.\tSecond","1.Nested decimal first","2.Nested decimal second"));
        for(List<String> later:invalid)try(XWPFDocument d=physicalDocument(valid,later,"21. Closing source interval")){assertPhysicalUnknown(parse(d,ocr));}
        for(String end:Arrays.asList("22. Decimal gap","20. Duplicate decimal"))try(XWPFDocument d=physicalDocument(valid,valid,end)){assertPhysicalUnknown(parse(d,ocr));}
        for(String barrier:Arrays.asList("heading","table"))try(XWPFDocument d=physicalDocument(valid,valid,"21. Closing source interval")){
            if(barrier.equals("heading"))d.getParagraphs().stream().filter(p->p.getText().startsWith("b.")).findFirst().orElseThrow().setStyle("heading");
            else{XWPFParagraph point=d.getParagraphs().stream().filter(p->p.getText().startsWith("20.")).findFirst().orElseThrow();try(org.apache.xmlbeans.XmlCursor cursor=point.getCTP().newCursor()){d.insertNewTbl(cursor).createRow().createCell().setText("Table interrupts the source run");}}
            assertPhysicalUnknown(parse(d,ocr));
        }
        verifyNoInteractions(ocr);
    }

    @Test void foreignOrUnusableObservedMembersCannotDisappearFromPhysicalBoundaryProof()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;List<String> valid=Arrays.asList("a.\tFirst source child","b.\tSecond source child");
        try(XWPFDocument d=physicalDocument(valid,valid,"21. Closing source interval")){parsed=parse(d,ocr);}
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes");
        for(String defect:Arrays.asList("story","part","source","unusable")){
            ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.set("sourceSha256",scopes.path("sourceSha256"));inventory.set("paragraphs",scopes.path("members").deepCopy());
            ObjectNode member=null;for(JsonNode candidate:inventory.path("paragraphs"))if(text(candidate).equals("Physical continuation")){member=(ObjectNode)candidate;break;}assertNotNull(member);
            if(defect.equals("story"))member.put("storyRootNodePath","/foreign-story");else if(defect.equals("part"))((ObjectNode)member.path("sourcePosition")).put("partName","word/foreign.xml");else if(defect.equals("source"))((ObjectNode)member.path("sourcePosition")).put("sourceSha256","f".repeat(64));else((ObjectNode)member.path("textObservation")).put("status","unknown");
            JsonNode observed=OerDocxSourceScopes.observe(inventory,outline,parsed.getCoverage().getNativeDocxSections());for(JsonNode candidate:observed.path("ranges"))assertNotEquals("physical_decimal_source_interval_with_literal_alpha_restart",candidate.path("basis").asText(),defect);
        }
        verifyNoInteractions(ocr);
    }

    private XWPFDocument physicalDocument(List<String> first,List<String> next,String closing)throws Exception{
        XWPFDocument d=document();paragraph(d,"Chapter","heading");paragraph(d,"18. Previous source interval","clause");XWPFParagraph owner=paragraph(d,"19. First source interval","clause");CTInd a=owner.getCTP().getPPr().addNewInd();a.setLeft(BigInteger.valueOf(1440));a.setHanging(BigInteger.valueOf(360));
        for(String value:first)literalLine(d,value);paragraph(d,"Physical continuation",null);XWPFParagraph later=paragraph(d,"20. Next source interval","other");CTInd b=later.getCTP().getPPr().addNewInd();b.setLeft(BigInteger.valueOf(2160));b.setHanging(BigInteger.valueOf(360));b.setLeftChars(BigInteger.valueOf(993));b.setHangingChars(BigInteger.valueOf(194));
        for(String value:next)literalLine(d,value);paragraph(d,closing,"clause");paragraph(d,"Next chapter","heading");return d;
    }
    private void literalLine(XWPFDocument d,String text){XWPFParagraph p=d.createParagraph();p.setStyle("clause");String[] parts=text.split("\t",-1);XWPFRun run=p.createRun();for(int i=0;i<parts.length;i++){if(i>0)run.addTab();run.setText(parts[i]);}}
    private void assertPhysicalUnknown(DocumentParser.ParsedDocument parsed){JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");for(JsonNode candidate:scopes.path("ranges"))assertNotEquals("physical_decimal_source_interval_with_literal_alpha_restart",candidate.path("basis").asText());}

    @Test void ordinaryScopesReuseNativeTocFieldRolesWithoutXmlOrConfusingTheDirectoryWithTheBody()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument normal,template;byte[] bytes;
        try(XWPFDocument d=document()){
            paragraph(d,"SPECIAL CONDITIONS OF CONTRACT",null);
            d.createParagraph().getCTP().set(CTP.Factory.parse("<xml-fragment xmlns:w='"+W+"'><w:fldSimple w:instr='TOC'><w:hyperlink w:anchor='_Toc1'><w:r><w:t>SCC1 Authored clause</w:t></w:r><w:fldSimple w:instr='PAGEREF _Toc1'><w:r><w:t> 1</w:t></w:r></w:fldSimple></w:hyperlink></w:fldSimple></xml-fragment>"));
            paragraph(d,"SCC1 Authored clause",null);paragraph(d,"Body source text",null);paragraph(d,"SCC2 Next authored clause",null);
            ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);bytes=out.toByteArray();
        }
        DocumentParser parser=new DocumentParser(new ConsenseProperties(),ocr);normal=parser.parse("source.docx",bytes);template=parser.parseTemplate("SCC","source.docx",bytes);
        JsonNode scopes=normal.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");assertEquals(2,scopes.path("ranges").size());
        JsonNode first=scopes.path("ranges").get(0);assertEquals("SCC1",first.path("reference").asText());assertTrue(first.path("scopeComplete").asBoolean());
        assertEquals(Arrays.asList("SCC1 Authored clause","Body source text"),members(scopes,first).stream().map(this::text).toList());
        assertEquals(scopes,template.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"));
        JsonNode copied=scopes.path("members").get(1);assertFalse(copied.has("nativeXml"));assertEquals("toc_result",copied.path("nativeFieldRoles").get(0).path("fieldRole").asText());verifyNoInteractions(ocr);
    }

    @Test void paragraphToCarrierRangesAreExactAndOrderedWhileRepeatedTextStaysUnmapped()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            XWPFTableCell cell=d.createTable(1,1).getRow(0).getCell(0);cell.setText("Unique first source paragraph");
            cell.addParagraph().createRun().setText("Unique continuation "+"long source text ".repeat(120));
            XWPFTableCell repeated=d.createTable(1,1).getRow(0).getCell(0);repeated.setText("Repeated paragraph");repeated.addParagraph().createRun().setText("Repeated paragraph");parsed=parse(d,ocr);
        }
        Map<String,DocumentBlock> blocks=new HashMap<>();parsed.getBlocks().forEach(b->blocks.put(b.getId(),b));int unique=0,repeated=0;
        for(JsonNode member:parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes").path("members")){
            if(text(member).startsWith("Unique")){unique++;assertEquals(1,member.path("retainedBlockRanges").size());JsonNode range=member.path("retainedBlockRanges").get(0);DocumentBlock block=blocks.get(range.path("blockId").asText());assertEquals(text(member),block.getText().substring(range.path("startOffset").asInt(),range.path("endOffset").asInt()));assertEquals(DocumentParseProbe.sha256(block.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)),range.path("parentEffectiveTextSha256").asText());}
            if(text(member).equals("Repeated paragraph")){repeated++;assertTrue(member.path("retainedBlockRanges").isEmpty());}
        }
        assertEquals(2,unique);assertEquals(2,repeated);verifyNoInteractions(ocr);
    }

    @Test void tabDelimitedClauseKeepsContinuationGuidanceWholeTableAndTailAcrossNativeHeadings()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=warrantyDocument("valid")){parsed=parse(d,ocr);}
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes"),three=range(scopes,"manual-sequence","3");
        assertTrue(three.path("scopeComplete").asBoolean(),"The physical source clause includes (d), all eight table members and its common tail before literal4(a)");
        assertExactObservedInterval(scopes,three,"3.","4.");List<JsonNode> payload=members(scopes,three);
        assertEquals(21,payload.size());assertEquals(8,payload.stream().filter(m->m.has("cellNodePath")).count());
        assertTrue(payload.stream().anyMatch(m->text(m).startsWith("(d)")));assertTrue(payload.stream().anyMatch(m->text(m).contains("periods of warranty stated above")));
        assertTrue(payload.stream().anyMatch(m->text(m).contains("Guidance Note:")));assertTrue(payload.stream().anyMatch(m->text(m).endsWith("Contract.)")));
        assertEquals(outline.path("headings").size(),scopes.path("ranges").findValuesAsText("kind").stream().filter("native-outline"::equals).count());
        assertEquals("explicit_tab_delimited_literal_source_interval",three.path("basis").asText());assertTrue(three.path("parentId").isNull());
        Set<String> payloadIds=new HashSet<>();payload.forEach(m->payloadIds.add(m.path("id").asText()));List<String> linked=new ArrayList<>(),actual=new ArrayList<>();
        for(JsonNode candidate:scopes.path("ranges"))if("native-outline".equals(candidate.path("kind").asText()))for(JsonNode id:candidate.path("orderedMemberIds"))if(payloadIds.contains(id.asText())){linked.add(candidate.path("id").asText());break;}
        three.path("nativeOutlineRangeIds").forEach(id->actual.add(id.asText()));assertEquals(linked,actual);assertEquals(5,linked.size());
        assertPhysicalAuthorityGuards(three);verifyNoInteractions(ocr);
    }

    @Test void guidanceClosingNativeHeadingCannotTruncateAnApparentlyCompleteLiteralClause()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Authored source warranty context","heading");sourceLine(d,"1.\tFirst source clause","clause");sourceLine(d,"2.\tSecond source clause","clause");sourceLine(d,"3.\tThe supplier further warrants that:","clause");
            sourceLine(d,"(a)\tFirst source warranty;","clause");sourceLine(d,"(b)\tSecond source warranty;","clause");sourceLine(d,"(c)\tThird source warranty; and","clause");sourceLine(d,"(Guidance Note:","clause");
            sourceLine(d,"Insert the required period under the Contract.)","heading");sourceLine(d,"(d)\tThe product remains covered for Ten years.","clause");sourceLine(d,"and each period stated above begins on completion.",null);d.createParagraph();
            sourceLine(d,"4.\t(a)\tNext separate source clause","clause");paragraph(d,"Next authored source chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes"),three=range(scopes,"manual-sequence","3");
        assertTrue(three.path("scopeComplete").asBoolean());assertExactObservedInterval(scopes,three,"3.","4.");
        List<JsonNode> payload=members(scopes,three);assertEquals(9,payload.size());assertTrue(payload.stream().anyMatch(m->text(m).contains("Ten years")));assertTrue(payload.stream().anyMatch(m->text(m).contains("each period stated above")));
        assertTrue(outline.path("headings").findValuesAsText("text").contains("Insert the required period under the Contract.)"));
        assertEquals("explicit_tab_delimited_literal_source_interval",three.path("basis").asText());assertTrue(three.path("parentId").isNull());assertTrue(three.path("nativeOutlineRangeIds").size()>1);assertPhysicalAuthorityGuards(three);verifyNoInteractions(ocr);
    }

    @Test void unprovedGuidanceAlphaAndNonconsecutiveEndDoNotCloseCrossOutlineClause()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String defect:Arrays.asList("alpha-gap","alpha-duplicate","unclosed-guidance","nested-guidance","unrelated-heading","wrong-end"))try(XWPFDocument d=warrantyDocument(defect)){
            JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
            assertNoCompleteManual(scopes,"3",defect);assertTrue(scopes.path("members").toString().contains("periods of warranty stated above"));
        }
        verifyNoInteractions(ocr);
    }

    @Test void everyCrossOutlineTableMemberMustRemainBoundUsableAndOrdinalContinuous()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;try(XWPFDocument d=warrantyDocument("valid")){parsed=parse(d,ocr);}
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes");
        for(String defect:Arrays.asList("missing-table-member","foreign-source","foreign-part","foreign-story","unusable","fractional-ordinal","negative-first-ordinal","overflowing-ordinal-sequence","missing-raw")){
            ObjectNode inventory=sourceInventory(scopes);com.fasterxml.jackson.databind.node.ArrayNode rows=(com.fasterxml.jackson.databind.node.ArrayNode)inventory.path("paragraphs");
            ObjectNode cell=null;int index=-1;for(int n=0;n<rows.size();n++)if(text(rows.get(n)).equals("Ten years")){cell=(ObjectNode)rows.get(n);index=n;break;}assertNotNull(cell);
            if(defect.equals("negative-first-ordinal")){
                boolean changed=false;for(JsonNode member:rows)if(text(member).startsWith("3.")){((ObjectNode)member).put("ordinalZeroBased",-1);changed=true;break;}assertTrue(changed,"The interval first ordinal must actually become negative");
            }else if(defect.equals("overflowing-ordinal-sequence")){
                boolean started=false,wrapped=false;long ordinal=Integer.MAX_VALUE;for(JsonNode member:rows){if(text(member).startsWith("3."))started=true;if(started){int value=(int)ordinal++;((ObjectNode)member).put("ordinalZeroBased",value);wrapped|=value==Integer.MIN_VALUE;if(text(member).startsWith("4."))break;}}assertTrue(started&&wrapped,"The bound interval must actually cross signed int overflow");
            }else if(defect.equals("missing-table-member"))rows.remove(index);
            else if(defect.equals("foreign-source"))((ObjectNode)cell.path("sourcePosition")).put("sourceSha256","f".repeat(64));
            else if(defect.equals("foreign-part"))((ObjectNode)cell.path("sourcePosition")).put("partName","word/foreign.xml");
            else if(defect.equals("foreign-story"))cell.put("storyRootNodePath","/foreign-story");
            else if(defect.equals("unusable"))((ObjectNode)cell.path("textObservation")).put("status","unknown");
            else if(defect.equals("fractional-ordinal"))cell.put("ordinalZeroBased",cell.path("ordinalZeroBased").asDouble()+0.25d);
            else cell.remove("preNormalizedTextObservation");
            assertNoCompleteManual(OerDocxSourceScopes.observe(inventory,outline,parsed.getCoverage().getNativeDocxSections()),"3",defect);
        }
        verifyNoInteractions(ocr);
    }

    @Test void literalDecimalAndInlineAlphaOpenersGiveExactPhysicalIntervalsWithoutIndentationTolerance()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=inlineDecimalDocument("valid")){scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");}
        JsonNode two=range(scopes,"manual-sequence","2"),three=range(scopes,"manual-sequence","3"),four=range(scopes,"manual-sequence","4");
        assertTrue(two.path("scopeComplete").asBoolean(),"Different source indentation stays different; explicit tab-delimited literal sequence proves only the physical interval");
        assertTrue(three.path("scopeComplete").asBoolean());assertTrue(four.path("scopeComplete").asBoolean());
        assertExactObservedInterval(scopes,two,"2.","3.");assertExactObservedInterval(scopes,three,"3.","4.");assertExactObservedInterval(scopes,four,"4.","5.");
        JsonNode second=members(scopes,two).get(0),third=members(scopes,three).get(0);
        assertNotEquals(second.path("paragraphProperties").path("layoutEvidence").path("effective").path("ind"),third.path("paragraphProperties").path("layoutEvidence").path("effective").path("ind"));
        for(JsonNode target:Arrays.asList(two,three,four))assertPhysicalAuthorityGuards(target);verifyNoInteractions(ocr);
    }

    @Test void repeatedMainNumberWithAdvancingInlineAlphaIsAContinuationNotANewRoot()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=inlineDecimalDocument("valid")){scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");}
        JsonNode five=range(scopes,"manual-sequence","5"),sixteen=range(scopes,"manual-sequence","16");
        assertTrue(five.path("scopeComplete").asBoolean());assertTrue(sixteen.path("scopeComplete").asBoolean());
        assertExactObservedInterval(scopes,five,"5.","6.");assertExactObservedInterval(scopes,sixteen,"16.","17.");
        assertEquals(4,members(scopes,five).size());assertEquals(4,members(scopes,sixteen).size());
        assertEquals(1,members(scopes,five).stream().filter(m->text(m).startsWith("5.")&&text(m).contains("(d)")).count());
        assertEquals(1,members(scopes,sixteen).stream().filter(m->text(m).startsWith("16.")&&text(m).contains("(c)")).count());
        assertPhysicalAuthorityGuards(five);assertPhysicalAuthorityGuards(sixteen);verifyNoInteractions(ocr);
    }

    @Test void existingCompleteSourceIntervalKeepsDecimalSubitemsAndSpaceDelimitedChildren()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=document()){
            paragraph(d,"Authored original source chapter","heading");sourceLine(d,"1.\tExisting complete source clause","clause");sourceLine(d,"1.1 Original decimal subitem",null);sourceLine(d,"1.1.1 Original deeper decimal subitem",null);
            sourceLine(d,"(a)\tFirst original child",null);sourceLine(d,"(b)  Second original child",null);sourceLine(d,"(c)\tThird original child",null);
            sourceLine(d,"2.\tNext original source clause","clause");sourceLine(d,"3.\tFollowing original source clause","clause");paragraph(d,"Next original source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode one=range(scopes,"manual-sequence","1");assertTrue(one.path("scopeComplete").asBoolean(),"Decimal subitems and a space-delimited child inside the original interval do not prove a conflicting main continuation");
        assertExactObservedInterval(scopes,one,"1.","2.");assertEquals(6,members(scopes,one).size());assertTrue(members(scopes,one).stream().anyMatch(m->m.path("preNormalizedTextObservation").path("text").asText().contains("(b)  Second original child")));verifyNoInteractions(ocr);
    }

    @Test void unrelatedNumbersBeyondTheOriginalNativeBoundaryCannotDowngradeACompleteInterval()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=document()){
            paragraph(d,"First authored source chapter","heading");sourceLine(d,"5.\tPrevious original source clause","clause");sourceLine(d,"6.\tExisting complete source clause","clause");sourceLine(d,"Original source payload",null);
            paragraph(d,"Second authored source chapter","heading");sourceLine(d,"99. Unrelated ordinary source number in another chapter",null);sourceLine(d,"7.\tA source item in the later chapter","clause");sourceLine(d,"8.\tAnother later source item","clause");paragraph(d,"Third authored source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode six=range(scopes,"manual-sequence","6");assertTrue(six.path("scopeComplete").asBoolean(),"Only evidence inside the existing source interval can invalidate it");
        assertExactObservedInterval(scopes,six,"6.","Second authored source chapter");assertEquals(2,members(scopes,six).size());assertEquals("next_native_outline_heading",six.path("endBoundary").path("kind").asText());verifyNoInteractions(ocr);
    }

    @Test void explicitSpaceSeparatedChildBeforeSameMainContinuationPreservesItsCompleteSourceInterval()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String separator:Arrays.asList("  ","\u00a0","\u202f"))try(XWPFDocument d=document()){
            paragraph(d,"Authored source chapter","heading");sourceLine(d,"4.\tPrevious source clause","clause");sourceLine(d,"5.\tExisting complete source clause","clause");sourceLine(d,"(a)\tFirst source child",null);
            String second="(b)"+separator+"Second source child";sourceLine(d,second,null);sourceLine(d,"5.\t(c)\tExplicit same-main continuation",null);sourceLine(d,"6.\tNext source clause","clause");sourceLine(d,"7.\tFollowing source clause","clause");paragraph(d,"Next source chapter","heading");
            JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),five=range(scopes,"manual-sequence","5");
            assertTrue(five.path("scopeComplete").asBoolean(),"An explicit parenthesized child keeps its original source whitespace rather than becoming a false missing-letter gap");assertExactObservedInterval(scopes,five,"5.","6.");assertEquals(4,members(scopes,five).size());
            assertTrue(members(scopes,five).stream().anyMatch(m->m.path("preNormalizedTextObservation").path("text").asText().equals(second+"\n")));assertPhysicalAuthorityGuards(five);
        }
        verifyNoInteractions(ocr);
    }

    @Test void repeatedMainNumbersRequireUniqueConsecutiveLiteralChildren()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String defect:Arrays.asList("five-alpha-gap","five-alpha-restart","five-main-conflict","inline-without-tab","sixteen-alpha-gap"))try(XWPFDocument d=inlineDecimalDocument(defect)){
            JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");assertNoCompleteManual(scopes,defect.equals("sixteen-alpha-gap")?"16":"5",defect);
        }
        try(XWPFDocument d=inlineDecimalDocument("main-without-tab")){assertNoCompleteManual(parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),"2","Space-only mixed-layout numbers do not establish the new physical proof");}
        verifyNoInteractions(ocr);
    }

    @Test void nativeTabsAndUnicodeSeparatorsKeepNestedRomanAndExplicitSameMainContinuation()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=document()){
            paragraph(d,"Authored source rules","heading");sourceIndent(sourceLine(d,"1.\u00a0\tFirst source rule","clause"),2279,1678);sourceIndent(sourceLine(d,"2.\tSecond source rule","other"),2279,1678);sourceIndent(sourceLine(d,"3.\t(a)\u00a0\tFirst literal child",null),2280,1680);
            sourceLine(d,"(i)\tFirst nested alternative",null);sourceLine(d,"(ii)\tSecond nested alternative",null);sourceLine(d,"Unnumbered common continuation",null);sourceLine(d,"(b)\tSecond literal child",null);sourceLine(d,"3.\t(c)\tSame-main continuation",null);sourceLine(d,"\t(d)\tLater literal child",null);sourceLine(d,"4.\t(a)\tNext source clause",null);sourceLine(d,"5.\tFinal source clause",null);paragraph(d,"Next source chapter","heading");
            scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode two=range(scopes,"manual-sequence","2"),three=range(scopes,"manual-sequence","3");assertTrue(two.path("scopeComplete").asBoolean());assertTrue(three.path("scopeComplete").asBoolean());assertExactObservedInterval(scopes,three,"3.","4.");assertEquals(7,members(scopes,three).size());assertPhysicalAuthorityGuards(three);verifyNoInteractions(ocr);
    }

    private XWPFDocument warrantyDocument(String defect)throws Exception {
        XWPFDocument d=document();paragraph(d,"Authored source warranty context","heading");
        sourceLine(d,"1.\tFirst source clause","clause");sourceLine(d,"2.\tSecond source clause","clause");sourceLine(d,"3.\tWithout prejudice the supplier warrants that:#","clause");
        sourceLine(d,"(a)\tFirst source warranty;","clause");sourceLine(d,"(b)\tSecond source warranty;","clause");sourceLine(d,"(c)\tThird source warranty; and","clause");
        if(defect.equals("alpha-duplicate"))sourceLine(d,"(c)\tRepeated source warranty; and","clause");emptyOutline(d);
        sourceLine(d,(defect.equals("alpha-gap")?"(e)":"(d)")+"\tThe equipment shall be free of defects throughout the periods specified below:","clause");
        sourceLine(d,defect.equals("unrelated-heading")?"Unrelated authored chapter":"(Guidance Note:","heading");
        sourceLine(d,(defect.equals("nested-guidance")?"(Guidance Note: ":"")+"Insert below the certified completion date under the Contract."+(defect.equals("unclosed-guidance")?"":")"),"heading");emptyOutline(d);
        XWPFTable table=d.createTable(4,2);String[][] cells={{"Item","Period of warranty"},{"a. Structural integrity","Ten years"},{"b. Timber parts","Five years"},{"c. Polyethylene components","Five years"}};
        for(int row=0;row<cells.length;row++)for(int col=0;col<2;col++)table.getRow(row).getCell(col).setText(cells[row][col]);
        sourceLine(d,"\t\t",null);sourceLine(d,"\t\tand each of the periods of warranty stated above shall commence from [ ] under the Contract.",null);d.createParagraph();d.createParagraph();
        sourceLine(d,(defect.equals("wrong-end")?"5.":"4.")+"\t(a)\tNext source clause and a separate obligation","clause");sourceLine(d,"(b)\tPayload of the next source clause","clause");paragraph(d,"Next authored source chapter","heading");return d;
    }

    private XWPFDocument inlineDecimalDocument(String defect)throws Exception {
        XWPFDocument d=document();paragraph(d,"Authored source rules","heading");
        XWPFParagraph one=sourceLine(d,decimalLine("1.\tFirst source rule",defect),"clause");sourceIndent(one,2279,1678);
        XWPFParagraph two=sourceLine(d,decimalLine("2.\tSecond source rule",defect),"other");sourceIndent(two,2279,1678);
        XWPFParagraph three=sourceLine(d,decimalLine("3.\tThird source rule",defect),null);sourceIndent(three,2280,1680);
        sourceIndent(sourceLine(d,decimalLine("4.\t(a)\tFirst source child under four",defect),null),2880,2280);sourceLine(d,"(b)\tSecond source child under four",null);
        sourceIndent(sourceLine(d,decimalLine("5.\t(a)\tFirst source child under five",defect),null),2880,2280);sourceLine(d,"(b)\tSecond source child under five",null);
        if(!defect.equals("five-alpha-gap"))sourceLine(d,"(c)\tThird source child under five",null);
        String five=defect.equals("five-alpha-restart")?"5.\t(a)\tAmbiguous restarted child":defect.equals("five-main-conflict")?"4.\t(d)\tConflicting main reference":"5.\t(d)\tExplicit continuation of five";
        sourceLine(d,defect.equals("inline-without-tab")?five.replace('\t',' '):decimalLine(five,defect),null);
        for(int n=6;n<=15;n++)sourceIndent(sourceLine(d,decimalLine(n+".\tSource rule "+n,defect),null),2280,1680);
        sourceIndent(sourceLine(d,decimalLine("16.\tSource rule with explicit children",defect),null),2280,1680);sourceLine(d,"(a)\tFirst source child under sixteen",null);
        if(!defect.equals("sixteen-alpha-gap"))sourceLine(d,"(b)\tSecond source child under sixteen",null);
        sourceLine(d,decimalLine("16.\t(c)\tExplicit continuation of sixteen",defect),null);sourceIndent(sourceLine(d,decimalLine("17.\tNext source rule",defect),null),2280,1680);paragraph(d,"Next authored source chapter","heading");return d;
    }

    private String decimalLine(String value,String defect){return defect.equals("main-without-tab")?value.replace('\t',' '):value;}
    private void sourceIndent(XWPFParagraph p,int left,int hanging){CTInd ind=p.getCTP().isSetPPr()?p.getCTP().getPPr().addNewInd():p.getCTP().addNewPPr().addNewInd();ind.setLeft(BigInteger.valueOf(left));ind.setHanging(BigInteger.valueOf(hanging));}
    private void emptyOutline(XWPFDocument d){d.createParagraph().getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(2));}
    private XWPFParagraph sourceLine(XWPFDocument d,String value,String style){XWPFParagraph p=d.createParagraph();if(style!=null)p.setStyle(style);String[] parts=value.split("\t",-1);XWPFRun run=p.createRun();for(int i=0;i<parts.length;i++){if(i>0)run.addTab();run.setText(parts[i]);}return p;}
    private ObjectNode sourceInventory(JsonNode scopes){ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.set("sourceSha256",scopes.path("sourceSha256"));inventory.set("paragraphs",scopes.path("members").deepCopy());return inventory;}
    private void assertNoCompleteManual(JsonNode scopes,String reference,String reason){for(JsonNode candidate:scopes.path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText())&&reference.equals(candidate.path("reference").asText()))assertFalse(candidate.path("scopeComplete").asBoolean(),reason);}
    private void assertPhysicalAuthorityGuards(JsonNode candidate){assertEquals("source_interval_containment_only",candidate.path("hierarchyStatus").asText());assertFalse(candidate.path("semanticApplicabilityVerified").asBoolean(true));assertFalse(candidate.path("automaticEditEligible").asBoolean(true));}
    private void assertExactObservedInterval(JsonNode scopes,JsonNode candidate,String from,String end){
        JsonNode first=null,last=null;for(JsonNode member:scopes.path("members"))if(!member.has("cellNodePath")){if(first==null&&text(member).startsWith(from))first=member;if(last==null&&text(member).startsWith(end))last=member;}assertNotNull(first);assertNotNull(last);
        List<String> expected=new ArrayList<>(),actual=new ArrayList<>();int ordinal=first.path("ordinalZeroBased").asInt();for(JsonNode member:scopes.path("members"))if(member.path("ordinalZeroBased").asInt()>=first.path("ordinalZeroBased").asInt()&&member.path("ordinalZeroBased").asInt()<last.path("ordinalZeroBased").asInt()){assertTrue(member.path("ordinalZeroBased").isIntegralNumber());assertEquals(ordinal++,member.path("ordinalZeroBased").asInt());expected.add(member.path("id").asText());}
        candidate.path("orderedMemberIds").forEach(id->actual.add(id.asText()));assertEquals(expected,actual);assertEquals(last.path("sourcePosition"),candidate.path("endBoundary").path("sourcePosition"));assertFalse(actual.contains(last.path("id").asText()));
        assertEquals("docx-source-range:"+DocumentParseProbe.sha256(("manual-sequence|"+first.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),candidate.path("id").asText());
    }

    @Test void uniqueLiteralIntervalsUseTheirNativeOwnerEvenWhenOtherChaptersRepeatTheNumbers()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(boolean children:Arrays.asList(false,true))try(XWPFDocument d=ownerLocalPhysicalDocument(children)){
            JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),two=null;
            for(JsonNode candidate:scopes.path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText())&&"2".equals(candidate.path("reference").asText())&&text(members(scopes,candidate).get(0)).startsWith("2. First owner")){assertNull(two);two=candidate;}
            assertNotNull(two);assertTrue(two.path("scopeComplete").asBoolean(),"The literal 2 to 3 source boundary is explicit even though its native paragraph style and indentation change");
            assertEquals(children?4:1,members(scopes,two).size());assertExactObservedInterval(scopes,two,"2. First owner","3. First owner");assertPhysicalAuthorityGuards(two);
            assertEquals("native-outline",findRange(scopes,two.path("parentId").asText()).path("kind").asText());
        }
        verifyNoInteractions(ocr);
    }

    @Test void aSpaceDelimitedNotUsedMarkerClosesTheSameObservedConsecutiveSequence()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"12.\tEarlier source item","clause");sourceLine(d,"13.\tFurther source obligations:","clause");
            sourceLine(d,"a.\tFirst obligation","other");sourceLine(d,"b.\tSecond obligation","other");d.createParagraph();
            sourceIndent(paragraph(d,"14. Not used.","other"),2977,992);sourceLine(d,"15.\tLater source item","other");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),thirteen=range(scopes,"manual-sequence","13");
        assertTrue(thirteen.path("scopeComplete").asBoolean(),"The next explicit literal marker closes the physical interval without requiring a shared paragraph style or child list in the next item");
        assertEquals(4,members(scopes,thirteen).size());assertExactObservedInterval(scopes,thirteen,"13.","14.");assertPhysicalAuthorityGuards(thirteen);verifyNoInteractions(ocr);
    }

    @Test void ownerLocalPhysicalIntervalsRequireAllOriginalMembersAndTheExclusiveEndToRemainBound()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;try(XWPFDocument d=ownerLocalPhysicalDocument(true)){parsed=parse(d,ocr);}
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes");
        for(String defect:Arrays.asList("raw","ordinal","negativeOrdinal","endSource","memberOverlap")){
            ObjectNode inventory=sourceInventory(scopes);boolean changed=false;
            for(JsonNode member:inventory.path("paragraphs"))if(text(member).startsWith(defect.equals("endSource")?"3. First owner":"a. First source child")){
                ObjectNode target=(ObjectNode)member;
                if(defect.equals("raw"))target.remove("preNormalizedTextObservation");
                else if(defect.equals("ordinal"))target.put("ordinalZeroBased",member.path("ordinalZeroBased").asInt()+1);
                else if(defect.equals("negativeOrdinal"))target.put("ordinalZeroBased",-1);
                else if(defect.equals("endSource"))((ObjectNode)target.path("sourcePosition")).put("sourceSha256","f".repeat(64));
                else target.put("textOverlapStatus","unresolved");changed=true;break;
            }
            assertTrue(changed,defect);JsonNode observed=OerDocxSourceScopes.observe(inventory,outline,parsed.getCoverage().getNativeDocxSections());
            for(JsonNode candidate:observed.path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText())&&"2".equals(candidate.path("reference").asText())&&text(members(observed,candidate).get(0)).startsWith("2. First owner"))assertFalse(candidate.path("scopeComplete").asBoolean(),defect);
        }
        verifyNoInteractions(ocr);
    }

    @Test void aQuotedOpeningAfterAnExplicitNumberMustNotBeSwallowedByThePreviousItem()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"10.\tEarlier source item","clause");sourceLine(d,"11.\tEarlier obligations end here.","clause");
            sourceLine(d,"12.\t\"Self-owned\" source item starts here.","clause");sourceLine(d,"13.\tLater source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),eleven=range(scopes,"manual-sequence","11");
        assertTrue(eleven.path("scopeComplete").asBoolean());assertEquals(1,members(scopes,eleven).size());assertExactObservedInterval(scopes,eleven,"11.","12.");assertPhysicalAuthorityGuards(eleven);
        JsonNode twelve=range(scopes,"manual-sequence","12");assertTrue(twelve.path("scopeComplete").asBoolean());assertExactObservedInterval(scopes,twelve,"12.","13.");assertPhysicalAuthorityGuards(twelve);verifyNoInteractions(ocr);
    }

    @Test void bareLiteralMainNumbersKeepTheirChildrenAndCloseThePreviousSourceInterval()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"3.\tEarlier source item","clause");sourceLine(d,"4.\tEarlier obligations end here.","clause");
            paragraph(d,"5.","clause");sourceLine(d,"a.\tFirst obligation of the fifth item","other");sourceLine(d,"b.\tSecond obligation of the fifth item","other");
            paragraph(d,"6.","clause");sourceLine(d,"a.\tFirst obligation of the sixth item","other");sourceLine(d,"b.\tSecond obligation of the sixth item","other");
            sourceLine(d,"7.\tLater source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        for(String[] ends:Arrays.asList(new String[]{"4","5"},new String[]{"5","6"},new String[]{"6","7"})){
            JsonNode item=range(scopes,"manual-sequence",ends[0]);assertTrue(item.path("scopeComplete").asBoolean());assertExactObservedInterval(scopes,item,ends[0]+".",ends[1]+".");assertPhysicalAuthorityGuards(item);
            assertEquals(ends[0].equals("4")?1:3,members(scopes,item).size());
        }
        verifyNoInteractions(ocr);
    }

    @Test void decimalTextWithinAnExplicitMainItemDoesNotBecomeAnotherLiteralMainMarker()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"10.\tEarlier source item","clause");sourceLine(d,"11.\tCurrent source item","clause");
            paragraph(d,"12.5 is a measured quantity inside the current item.","clause");paragraph(d,"2026.10.18 is an example date.","clause");
            paragraph(d,"12.","clause");sourceLine(d,"a.\tChild of the next source item","other");sourceLine(d,"13.\tLater source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),eleven=range(scopes,"manual-sequence","11"),twelve=range(scopes,"manual-sequence","12");
        assertTrue(eleven.path("scopeComplete").asBoolean());assertEquals(Arrays.asList("11. Current source item","12.5 is a measured quantity inside the current item.","2026.10.18 is an example date."),members(scopes,eleven).stream().map(this::text).toList());
        assertEquals("12.",text(members(scopes,twelve).get(0)));assertEquals(twelve.path("startBoundary").path("sourcePosition"),eleven.path("endBoundary").path("sourcePosition"));assertPhysicalAuthorityGuards(eleven);verifyNoInteractions(ocr);
    }

    @Test void repeatedNativeBulletGlyphsAreDistinctNonordinalSourceItems()throws Exception {
        OcrClient ocr=mock(OcrClient.class);
        for(String glyph:Arrays.asList("\u2022","-"))try(XWPFDocument d=sourceListDocument(glyph)){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tSource requirements","clause");sourceList(d,"First nonordinal source item",30);sourceList(d,"Second nonordinal source item",30);
            sourceLine(d,"7.\tNext source requirement","clause");paragraph(d,"Next chapter","heading");JsonNode scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
            JsonNode first=rangeStartingWith(scopes,"native-list",glyph+" First nonordinal"),second=rangeStartingWith(scopes,"native-list",glyph+" Second nonordinal");
            for(JsonNode item:Arrays.asList(first,second)){assertTrue(item.path("scopeComplete").asBoolean(),"A repeated bullet glyph does not claim a repeated ordinal identity");assertFalse(item.path("unknownReasons").toString().contains("duplicate_native_list_label"));assertPhysicalAuthorityGuards(item);assertEquals("bullet",item.path("numbering").path("format").asText());assertEquals(glyph,item.path("numbering").path("label").asText());}
            assertFalse(first.path("id").equals(second.path("id")));assertSourceInterval(scopes,first,glyph+" First nonordinal",glyph+" Second nonordinal");assertSourceInterval(scopes,second,glyph+" Second nonordinal","7.");
        }
        verifyNoInteractions(ocr);
    }

    @Test void nativeLetterFramesContainWholeInterveningRomanListsEvenWhenAllDeclaredLevelsAreZero()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tProvide the following programmes:","clause");
            sourceList(d,"Work plans",10);paragraph(d,"Submit the following work plans:",null);sourceList(d,"Formwork movements",20);paragraph(d,"Continuation of the first work plan",null);sourceList(d,"Concrete works",20);
            sourceList(d,"Progress reports",10);sourceList(d,"Information requests",10);sourceList(d,"First information obligation",21);sourceList(d,"Second information obligation",21);
            sourceList(d,"Resource programmes",10);sourceList(d,"Labour forecast",22);sourceList(d,"Material forecast",22);sourceList(d,"Submission schedules",10);sourceList(d,"First submission requirement",23);sourceList(d,"Second submission requirement",23);
            sourceLine(d,"i.\tFabrication and transport plan","other");sourceList(d,"First fabrication obligation",24);sourceList(d,"Second fabrication obligation",24);
            sourceLine(d,"7.\tNext source requirement","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),d=rangeStartingWith(scopes,"native-list","d. Work plans"),f=rangeStartingWith(scopes,"native-list","f. Information requests"),g=rangeStartingWith(scopes,"native-list","g. Resource programmes"),h=rangeStartingWith(scopes,"native-list","h. Submission schedules");
        for(JsonNode item:Arrays.asList(d,f,g,h)){assertTrue(item.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(item);assertEquals(0,item.path("numbering").path("level").asInt(-1));assertEquals("10",item.path("numbering").path("numId").asText());}
        assertSourceInterval(scopes,d,"d. Work plans","e. Progress reports");assertSourceInterval(scopes,f,"f. Information requests","g. Resource programmes");assertSourceInterval(scopes,g,"g. Resource programmes","h. Submission schedules");assertSourceInterval(scopes,h,"h. Submission schedules","i. Fabrication and transport");
        for(String[] pair:Arrays.asList(new String[]{"i. Formwork movements","d. Work plans"},new String[]{"i. First information obligation","f. Information requests"},new String[]{"i. Labour forecast","g. Resource programmes"},new String[]{"i. First submission requirement","h. Submission schedules"})){
            JsonNode child=rangeStartingWith(scopes,"native-list",pair[0]),parent=rangeStartingWith(scopes,"native-list",pair[1]);assertEquals(parent.path("id"),child.path("parentId"));assertEquals(0,child.path("numbering").path("level").asInt(-1));assertPhysicalAuthorityGuards(child);
        }
        JsonNode literal=rangeStartingWith(scopes,"source-region","i. Fabrication and transport"),lastSubmission=rangeStartingWith(scopes,"native-list","ii. Second submission requirement"),fabrication=rangeStartingWith(scopes,"native-list","i. First fabrication obligation");
        assertSourceInterval(scopes,lastSubmission,"ii. Second submission requirement","i. Fabrication and transport");assertSourceInterval(scopes,literal,"i. Fabrication and transport","7.");assertEquals(literal.path("id"),fabrication.path("parentId"));assertPhysicalAuthorityGuards(literal);verifyNoInteractions(ocr);
    }

    @Test void existingTabRangesKeepTheirOriginalBasisAndRelativeOrderWhenAnOwnerProofAlsoSucceeds()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier source item","clause");sourceLine(d,"2.\tCross-style source item","clause");sourceIndent(sourceLine(d,"3.\tLater source item","other"),2400,420);
            sourceLine(d,"4.\t(a)\tFirst inline child",null);sourceLine(d,"(b)\tSecond inline child",null);sourceLine(d,"5.\tLater original peer","clause");sourceLine(d,"6.\tLast original peer","clause");paragraph(d,"Next chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        assertEquals("explicit_tab_delimited_literal_source_interval",range(scopes,"manual-sequence","2").path("basis").asText());List<String> references=new ArrayList<>();for(JsonNode candidate:scopes.path("ranges"))if("manual-sequence".equals(candidate.path("kind").asText()))references.add(candidate.path("reference").asText());assertEquals(Arrays.asList("1","2","5","6","3","4"),references);verifyNoInteractions(ocr);
    }

    @Test void namedRemarksFrameRestartsItsOwnNumbersAndRemainsInsideTheLastMainSourceItem()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tBody first item","clause");sourceLine(d,"2.\tBody second item","clause");sourceLine(d,"3.\tBody deliverables schedule","clause");
            XWPFTable table=d.createTable(2,2);table.getRow(0).getCell(0).setText("Deliverable");table.getRow(0).getCell(1).setText("Submission time");table.getRow(1).getCell(0).setText("1.");table.getRow(1).getCell(1).setText("Thirty days");
            paragraph(d,"Remarks:","clause");sourceLine(d,"1.\tRemarks first item","clause");sourceLine(d,"2.\tRemarks second item","clause");paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),three=rangeStartingWith(scopes,"manual-sequence","3. Body"),remarks=rangeStartingWith(scopes,"source-region","Remarks:");
        assertTrue(three.path("scopeComplete").asBoolean());assertSourceInterval(scopes,three,"3. Body","Next authored chapter");assertEquals(three.path("id"),remarks.path("parentId"));assertSourceInterval(scopes,remarks,"Remarks:","Next authored chapter");
        for(String opening:Arrays.asList("1. Body","2. Body","1. Remarks","2. Remarks")){JsonNode item=rangeStartingWith(scopes,"manual-sequence",opening);assertTrue(item.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(item);if(opening.contains("Remarks"))assertEquals(remarks.path("id"),item.path("parentId"));}
        assertPhysicalAuthorityGuards(remarks);verifyNoInteractions(ocr);
    }

    @Test void compoundSourceFramesKeepAChildNumberRestartInsideItsWholeMainItem()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier organization item","clause");sourceLine(d,"2.\tEarlier responsibilities item","clause");sourceLine(d,"6.\tEarlier treatment item","clause");sourceLine(d,"7.\tFlow chart and forms","clause");
            sourceLine(d,"7.1.\tFirst workflow frame","clause");sourceLine(d,"7.2.\tSpecialist material checking forms","clause");sourceLine(d,"1.\tMaterial checking forms","clause");sourceLine(d,"1.1.\tFirst checking instruction","clause");sourceLine(d,"1.2.\tSecond checking instruction","clause");sourceLine(d,"a.\tComplete checking record","other");sourceLine(d,"b.\tSubmit checking record","other");
            sourceLine(d,"7.3.\tLater delivery frame","clause");sourceLine(d,"7.4.\tLast flow chart frame","clause");paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),seven=rangeStartingWith(scopes,"manual-sequence","7. Flow"),compound=rangeStartingWith(scopes,"source-region","7.2. Specialist"),fresh=rangeStartingWith(scopes,"source-region","1. Material"),second=rangeStartingWith(scopes,"source-region","1.2. Second");
        assertTrue(seven.path("scopeComplete").asBoolean());assertSourceInterval(scopes,seven,"7. Flow","Next authored chapter");assertSourceInterval(scopes,compound,"7.2. Specialist","7.3. Later");assertEquals(seven.path("id"),compound.path("parentId"));assertEquals(compound.path("id"),fresh.path("parentId"));assertEquals(fresh.path("id"),second.path("parentId"));assertSourceInterval(scopes,second,"1.2. Second","7.3. Later");
        for(JsonNode item:Arrays.asList(compound,fresh,second)){assertTrue(item.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(item);}assertTrue(rangeStartingWith(scopes,"manual-sequence","1. Earlier").path("scopeComplete").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void resolvedNativeDecimalMarkersCanBePhysicalPeersOfSurroundingLiteralMainNumbers()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"15.\tEarlier main item","clause");sourceLine(d,"16.\tPrevious main item","clause");sourceLine(d,"17.\tSite coordination","clause");sourceList(d,"Paint exposed metal parts",40);sourceList(d,"Provide builder works",40);sourceList(d,"Coat pipe supports",40);sourceLine(d,"21.\tCombined works drawings","clause");sourceLine(d,"22.\tFollowing main item","clause");paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),seventeen=rangeStartingWith(scopes,"manual-sequence","17. Site"),eighteen=rangeStartingWith(scopes,"native-list","18. Paint"),nineteen=rangeStartingWith(scopes,"native-list","19. Provide"),twenty=rangeStartingWith(scopes,"native-list","20. Coat");
        assertTrue(seventeen.path("scopeComplete").asBoolean());assertEquals(1,members(scopes,seventeen).size());assertSourceInterval(scopes,seventeen,"17. Site","18. Paint");
        for(JsonNode item:Arrays.asList(eighteen,nineteen,twenty)){assertTrue(item.path("scopeComplete").asBoolean());assertEquals(seventeen.path("parentId"),item.path("parentId"));assertEquals(item.path("numbering").path("label"),item.path("reference"));assertEquals("40",item.path("numbering").path("numId").asText());assertEquals(0,item.path("numbering").path("level").asInt(-1));assertPhysicalAuthorityGuards(item);}
        assertSourceInterval(scopes,eighteen,"18. Paint","19. Provide");assertSourceInterval(scopes,nineteen,"19. Provide","20. Coat");assertSourceInterval(scopes,twenty,"20. Coat","21. Combined");verifyNoInteractions(ocr);
    }

    @Test void anAuthoredRomanNumberConflictKeepsItsLabelWhileThePhysicalItemIncludesAllBulletChildren()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("-")){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tMain source requirements","clause");sourceLine(d,"a.\tHealthy living requirements","other");sourceList(d,"Provide the following features",45);sourceList(d,"First feature",30);sourceList(d,"Second feature",30);sourceLine(d,"-\tThird source feature","other");sourceLine(d,"ii.\tAuthored next roman instruction","other");sourceLine(d,"b.\tNext source category","other");sourceLine(d,"2.\tNext main requirement","clause");paragraph(d,"Next authored chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),four=rangeStartingWith(scopes,"native-list","iv. Provide"),third=rangeStartingWith(scopes,"source-region","- Third source"),first=rangeStartingWith(scopes,"native-list","- First feature"),second=rangeStartingWith(scopes,"native-list","- Second feature");
        assertTrue(four.path("scopeComplete").asBoolean());assertSourceInterval(scopes,four,"iv. Provide","ii. Authored");assertEquals("iv.",four.path("numbering").path("label").asText());assertEquals("45",four.path("numbering").path("numId").asText());assertFalse(four.path("sourceConflicts").isEmpty(),"Source iv to ii is preserved as a reference conflict independently of the complete physical boundary");
        for(JsonNode child:Arrays.asList(first,second,third)){assertEquals(four.path("id"),child.path("parentId"));assertTrue(child.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(child);}assertSourceInterval(scopes,second,"- Second feature","- Third source");assertSourceInterval(scopes,third,"- Third source","ii. Authored");assertPhysicalAuthorityGuards(four);verifyNoInteractions(ocr);
    }

    @Test void aLiteralRomanSuccessorStopsTheLastNativeRomanEvenWhenTheManualParentIsComplete()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"8.\tSource coordination requirements","clause");sourceLine(d,"a.\tFirst source category","other");sourceLine(d,"b.\tServices coordination category","other");sourceLine(d,"i.\tFirst services requirement","other");sourceLine(d,"ii.\tSecond services requirement","other");sourceList(d,"Third services requirement",50);
            for(String roman:Arrays.asList("iv","v","vi","vii","viii","ix","x"))sourceLine(d,roman+".\tLater services requirement "+roman,"other");sourceLine(d,"c.\tLast source category","other");sourceLine(d,"9.\tNext source main item","clause");sourceLine(d,"10.\tFollowing source main item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),parent=rangeStartingWith(scopes,"manual-sequence","8. Source"),three=rangeStartingWith(scopes,"native-list","iii. Third services"),category=rangeStartingWith(scopes,"source-region","b. Services");
        assertTrue(parent.path("scopeComplete").asBoolean());assertTrue(three.path("scopeComplete").asBoolean());assertEquals(1,members(scopes,three).size());assertSourceInterval(scopes,three,"iii. Third services","iv. Later services");assertEquals(category.path("id"),three.path("parentId"));assertSourceInterval(scopes,category,"b. Services","c. Last source");assertEquals("iii.",three.path("numbering").path("label").asText());assertPhysicalAuthorityGuards(three);verifyNoInteractions(ocr);
    }

    @Test void anExplicitTabMainMarkerWithoutAPeriodKeepsItsSourceTextAndClosesThePreviousItem()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"3.\tEarlier source item","clause");sourceLine(d,"4.\tCurrent source item","clause");sourceLine(d,"5\tTransfer the authored source information","clause");sourceLine(d,"6.\tNext source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),four=rangeStartingWith(scopes,"manual-sequence","4. Current"),five=rangeStartingWith(scopes,"manual-sequence","5 Transfer");
        assertTrue(four.path("scopeComplete").asBoolean());assertTrue(five.path("scopeComplete").asBoolean());assertSourceInterval(scopes,four,"4. Current","5 Transfer");assertSourceInterval(scopes,five,"5 Transfer","6. Next");assertEquals(1,members(scopes,five).size());assertEquals("5\tTransfer the authored source information\n",members(scopes,five).get(0).path("preNormalizedTextObservation").path("text").asText());assertPhysicalAuthorityGuards(five);verifyNoInteractions(ocr);
    }

    @Test void repeatedWordLetterLabelsBeyondZKeepTheirNativeSourceFramesAndChildren()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tSource categories","clause");sourceList(d,"Last single letter category",60);sourceList(d,"First child of z",20);sourceList(d,"Second child of z",20);sourceList(d,"First repeated letter category",60);sourceList(d,"First child of aa",21);sourceList(d,"Second child of aa",21);sourceList(d,"Next repeated letter category",60);sourceLine(d,"7.\tNext main item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),z=rangeStartingWith(scopes,"native-list","z. Last single"),aa=rangeStartingWith(scopes,"native-list","aa. First repeated"),bb=rangeStartingWith(scopes,"native-list","bb. Next repeated"),child=rangeStartingWith(scopes,"native-list","i. First child of aa");
        assertTrue(z.path("scopeComplete").asBoolean());assertTrue(aa.path("scopeComplete").asBoolean());assertSourceInterval(scopes,z,"z. Last single","aa. First repeated");assertSourceInterval(scopes,aa,"aa. First repeated","bb. Next repeated");assertEquals(aa.path("id"),child.path("parentId"));assertEquals("aa.",aa.path("numbering").path("label").asText());assertEquals("bb.",bb.path("numbering").path("label").asText());assertFalse(aa.has("sourceConflicts"),"ECMA repeated-letter counters z/aa/bb are consecutive and must not be interpreted as Excel columns");verifyNoInteractions(ocr);
    }

    @Test void inheritedSourceReferenceConflictsDoNotOverwriteAChildsOwnConflict()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tSource categories","clause");sourceList(d,"Earlier source category",10);sourceList(d,"Category with a source label gap",10);sourceList(d,"First child in a conflicting sequence",70);sourceList(d,"Second child in a conflicting sequence",71);sourceLine(d,"g.\tLater authored category","other");sourceLine(d,"7.\tNext main item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),child=rangeStartingWith(scopes,"native-list","i. First child in");Set<String> pairs=new HashSet<>();for(JsonNode conflict:child.path("sourceConflicts"))pairs.add(conflict.path("previousLabel").asText()+"|"+conflict.path("label").asText());
        assertTrue(pairs.contains("i.|iii."),"The child's original i to iii conflict remains");assertTrue(pairs.contains("e.|g."),"The enclosing e to g conflict remains separately");assertEquals(2,pairs.size());assertEquals(2,child.path("sourceConflicts").size());assertEquals("i.",child.path("numbering").path("label").asText());verifyNoInteractions(ocr);
    }

    @Test void sourceLayoutContainmentRequiresComparableBoundTwipDeclarationsAndPreservesUnknownValues()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tSource categories","clause");sourceIndent(sourceLine(d,"a.\tAuthored source category","other"),1440,360);sourceIndent(sourceList(d,"First source child",20),2160,360);sourceIndent(sourceList(d,"Second source child",20),2160,360);sourceLine(d,"7.\tNext main item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode outline=parsed.getCoverage().getNativeDocxOutline(),scopes=outline.path("sourceClauseScopes"),positive=rangeStartingWith(scopes,"source-region","a. Authored"),first=rangeStartingWith(scopes,"native-list","i. First source child");assertEquals(positive.path("id"),first.path("parentId"));assertTrue(positive.path("scopeComplete").asBoolean());
        List<String> incorrectlyAdopted=new ArrayList<>();
        for(String defect:Arrays.asList("overlap","leftChars","startChars","nonNumeric","noLayers","bidi","textDirection","mirrorIndents")){
            ObjectNode inventory=sourceInventory(scopes),target=null;for(JsonNode member:inventory.path("paragraphs"))if(text(member).startsWith("i. First source child")){target=(ObjectNode)member;break;}assertNotNull(target);
            ObjectNode layout=(ObjectNode)target.path("paragraphProperties").path("layoutEvidence"),ind=(ObjectNode)layout.path("effective").path("ind"),left=(ObjectNode)ind.path("{"+W+"}left");
            if(defect.equals("leftChars")||defect.equals("startChars")){ObjectNode chars=left.deepCopy();chars.put("value","100");ind.set("{"+W+"}"+defect,chars);}
            else if(defect.equals("nonNumeric"))left.put("value","unresolved-left");
            else if(defect.equals("noLayers"))layout.putArray("layers");
            else if(defect.equals("overlap")){
                left.put("status","unknown");left.remove("value");left.put("reason","numbering_and_paragraph_style_precedence_not_verified");com.fasterxml.jackson.databind.node.ArrayNode layers=layout.putArray("layers");
                for(int value:new int[]{1000,2160}){ObjectNode layer=layers.addObject();layer.put("kind",value==1000?"paragraph_style":"numbering_abstract_level");layer.set("sourcePosition",target.path("sourcePosition").deepCopy());ObjectNode declaration=layer.putArray("declarations").addObject();declaration.put("name","ind");declaration.set("sourcePosition",target.path("sourcePosition").deepCopy());declaration.putObject("attributes").put("{"+W+"}left",Integer.toString(value));}
            }else{
                ObjectNode layer=((com.fasterxml.jackson.databind.node.ArrayNode)layout.path("layers")).addObject();layer.put("kind","direct_paragraph");layer.set("sourcePosition",target.path("sourcePosition").deepCopy());ObjectNode declaration=layer.putArray("declarations").addObject();declaration.put("name",defect);declaration.set("sourcePosition",target.path("sourcePosition").deepCopy());declaration.putObject("attributes").put("{"+W+"}val",defect.equals("textDirection")?"tbRl":"1");
            }
            JsonNode observed=OerDocxSourceScopes.observe(inventory,outline,parsed.getCoverage().getNativeDocxSections()),category=rangeStartingWith(observed,"source-region","a. Authored"),child=rangeStartingWith(observed,"native-list","i. First source child");
            if(category.path("scopeComplete").asBoolean()&&category.path("id").equals(child.path("parentId")))incorrectlyAdopted.add(defect);
            if(defect.equals("overlap"))assertEquals("unknown",members(observed,child).get(0).path("paragraphProperties").path("layoutEvidence").path("effective").path("ind").path("{"+W+"}left").path("status").asText());
        }
        assertTrue(incorrectlyAdopted.isEmpty(),"Unsupported source coordinate evidence must not grant a complete physical parent: "+incorrectlyAdopted);verifyNoInteractions(ocr);
    }

    @Test void aBoundCommonTailAfterTheLastUniformNativeLetterItemBelongsToTheMainSourceFrame()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"8.\tProvide the following source types:","clause");
            for(String title:Arrays.asList("First source type","Second source type","Third source type","Last source type"))sourceIndent(sourceList(d,title,10),2160,360);
            sourceIndent(paragraph(d,"Before dismantling, submit the common method statement for the listed source types.","clause"),1440,0);d.createParagraph();sourceLine(d,"9.\tNext main source item","clause");sourceLine(d,"10.\tFollowing main source item","clause");paragraph(d,"Next chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),main=rangeStartingWith(scopes,"manual-sequence","8. Provide"),last=rangeStartingWith(scopes,"native-list","g. Last source type");
        assertEquals(1,members(scopes,last).size(),"The last native g item must not swallow the main source frame's shared tail");assertSourceInterval(scopes,last,"g. Last source type","Before dismantling");JsonNode tail=rangeStartingWith(scopes,"source-region","Before dismantling");assertEquals(main.path("id"),tail.path("parentId"));assertEquals(2,members(scopes,tail).size());assertSourceInterval(scopes,tail,"Before dismantling","9. Next main");assertTrue(tail.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(tail);verifyNoInteractions(ocr);
    }

    @Test void overlappingNativeOwnersCannotExtendNamedRemarksIntoTheFollowingChapter()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=document()){
            paragraph(d,"Outer source part",null).getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);
            paragraph(d,"Source responsibility chapter","heading");sourceLine(d,"1.\tBody first source item","clause");sourceLine(d,"2.\tBody second source item","clause");sourceLine(d,"3.\tBody deliverables source item","clause");
            paragraph(d,"Remarks:","clause");sourceLine(d,"1.\tRemarks first source item","clause");sourceLine(d,"2.\tRemarks second source item","clause");
            paragraph(d,"Following authored source chapter","heading");sourceLine(d,"1.\tFollowing chapter first source item","clause");sourceLine(d,"2.\tFollowing chapter second source item","clause");sourceLine(d,"3.\tFollowing chapter third source item","clause");
            paragraph(d,"Next outer source part",null).getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ZERO);parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),remarks=rangeStartingWith(scopes,"source-region","Remarks:"),main=rangeStartingWith(scopes,"manual-sequence","3. Body deliverables");
        assertSourceInterval(scopes,remarks,"Remarks:","Following authored source chapter");assertEquals(3,members(scopes,remarks).size());assertEquals(main.path("id"),remarks.path("parentId"));
        for(String opening:Arrays.asList("1. Remarks first","2. Remarks second")){JsonNode item=rangeStartingWith(scopes,"manual-sequence",opening);assertTrue(item.path("scopeComplete").asBoolean(),item.toString());assertEquals(remarks.path("id"),item.path("parentId"));}
        verifyNoInteractions(ocr);
    }

    @Test void literalNeighborsAndTheirSharedTailsRequireEveryPhysicalOwnerToBeEmitted()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=sourceListDocument("\u2022")){
            paragraph(d,"Chapter","heading");sourceLine(d,"6.\tSource categories","clause");sourceIndent(sourceLine(d,"a.\tEarlier literal category","other"),1440,360);sourceIndent(sourceLine(d,"b.\tLiteral category with source continuation","other"),1440,360);
            sourceIndent(sourceLine(d,"i.\tLiteral child adjacent to a native source peer","other"),2160,360);sourceIndent(paragraph(d,"This common continuation belongs to the literal category.","clause"),1440,0);d.createParagraph();sourceIndent(sourceList(d,"Native next category",10),1440,360);
            sourceLine(d,"7.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        Set<String> ids=new HashSet<>();for(JsonNode candidate:scopes.path("ranges"))ids.add(candidate.path("id").asText());List<String> unbound=new ArrayList<>();for(JsonNode candidate:scopes.path("ranges"))if(!candidate.path("parentId").isNull()&&!ids.contains(candidate.path("parentId").asText()))unbound.add(candidate.path("id").asText());assertTrue(unbound.isEmpty(),"Every emitted literal neighbor and shared tail must retain its bound physical owner: "+unbound);
        JsonNode owner=rangeStartingWith(scopes,"source-region","b. Literal category"),child=rangeStartingWith(scopes,"source-region","i. Literal child"),tail=rangeStartingWith(scopes,"source-region","This common continuation");assertEquals(owner.path("id"),child.path("parentId"));assertEquals(owner.path("id"),tail.path("parentId"));verifyNoInteractions(ocr);
    }

    @Test void anAmbiguousLiteralMIsAnAlphaFrameWhenTheNextExplicitAlphaNEnclosesItsRomanChildren()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceDeclarationDocument()){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier main source item","clause");sourceLine(d,"2.\tCurrent main source requirements","clause");sourceLine(d,"k.\tEarlier authored source category","source3");paragraph(d,". Authored category with a missing source letter","source3");
            sourceLine(d,"m.\tWater saving source category","source3");XWPFParagraph first=paragraph(d,"First water saving source instruction","source4");first.setNumID(BigInteger.valueOf(45));first.setNumILvl(BigInteger.valueOf(5));
            sourceLine(d,"ii.\tSecond water saving source instruction","aff1");XWPFParagraph third=sourceLine(d,"iii.\tThird water saving source instruction","aff1");CTInd thirdInd=third.getCTP().getPPr().addNewInd();thirdInd.setLeftChars(BigInteger.ZERO);thirdInd.setLeft(BigInteger.valueOf(2345));
            XWPFParagraph last=paragraph(d,"Last water saving source instruction","source3");last.setNumID(BigInteger.valueOf(45));last.setNumILvl(BigInteger.valueOf(2));sourceIndent(last,2977,283);
            XWPFParagraph next=sourceLine(d,"n.\tNext explicit source category","source3");CTInd nextInd=next.getCTP().getPPr().addNewInd();nextInd.setLeft(BigInteger.valueOf(1440));nextInd.setFirstLineChars(BigInteger.valueOf(379));nextInd.setFirstLine(BigInteger.valueOf(826));
            sourceLine(d,"3.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),m=rangeStartingWith(scopes,"source-region","m. Water saving"),first=rangeStartingWith(scopes,"native-list","i. First water"),last=rangeStartingWith(scopes,"native-list","viii. Last water");
        assertSourceInterval(scopes,m,"m. Water saving","n. Next explicit");assertEquals(5,members(scopes,m).size());assertEquals(m.path("id"),first.path("parentId"));assertEquals(m.path("id"),last.path("parentId"));assertEquals(5,first.path("numbering").path("level").asInt());assertEquals(2,last.path("numbering").path("level").asInt());
        assertTrue(last.path("sourceConflicts").toString().contains("iii."));assertTrue(last.path("sourceConflicts").toString().contains("viii."));verifyNoInteractions(ocr);
    }

    @Test void aStrictSourceOutdentUsesTheFullUnresolvedNativeIndentBoundsForTheLastUniformLetter()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceDeclarationDocument()){
            paragraph(d,"Chapter","heading");sourceLine(d,"8.\tProvide the following source types:","clause");
            for(String title:Arrays.asList("Previous source type","Last source type")){XWPFParagraph item=paragraph(d,title,"source4");item.setNumID(BigInteger.valueOf(63));item.setNumILvl(BigInteger.ZERO);}
            XWPFParagraph tail=paragraph(d,"Before dismantling, submit the common source method statement.","source3");CTInd ind=tail.getCTP().getPPr().addNewInd();ind.setLeft(BigInteger.valueOf(2342));ind.setFirstLine(BigInteger.ZERO);d.createParagraph();sourceLine(d,"9.\tNext main source item","clause");sourceLine(d,"10.\tFollowing main source item","clause");paragraph(d,"Next source chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),main=rangeStartingWith(scopes,"manual-sequence","8. Provide"),last=rangeStartingWith(scopes,"native-list","g. Last source type");assertEquals("unknown",members(scopes,last).get(0).path("paragraphProperties").path("layoutEvidence").path("effective").path("ind").path("{"+W+"}left").path("status").asText());
        assertSourceInterval(scopes,last,"g. Last source type","Before dismantling");assertEquals(1,members(scopes,last).size());JsonNode tail=rangeStartingWith(scopes,"source-region","Before dismantling");assertEquals(main.path("id"),tail.path("parentId"));assertEquals(2,members(scopes,tail).size());assertTrue(tail.path("scopeComplete").asBoolean());verifyNoInteractions(ocr);
    }

    @Test void displayedRomanChildrenKeepTheOriginalNativeLetterFormatInsideSeparateTextHeadingFrames()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceDeclarationDocument()){
            BigInteger number=sourceNumbering(d,STNumberFormat.LOWER_LETTER,9,2880,480);
            paragraph(d,"Chapter","heading");sourceLine(d,"27.\tEarlier source item","clause");sourceLine(d,"28.\tSource coordination roles","clause");sourceLine(d,"a.\tEarlier source category","source3");sourceLine(d,"b.\tQualifications and experience","source3");paragraph(d,"The two source roles shall hold qualifications as follows:","source3");
            XWPFParagraph firstTitle=paragraph(d,"Coordinator source role","source3");firstTitle.getCTP().getPPr().addNewInd().setLeft(BigInteger.valueOf(3056));firstTitle.getCTP().getPPr().addNewRPr().addNewU().setVal(STUnderline.SINGLE);
            sourceIndent(sourceLine(d,"i.\tFirst coordinator qualification","source4"),3119,420);sourceIndent(sourceLine(d,"ii.\tSecond coordinator qualification","source4"),3119,420);d.createParagraph();
            XWPFParagraph secondTitle=paragraph(d,"Modeller source role","source3");sourceIndent(secondTitle,2704,5);secondTitle.getCTP().getPPr().addNewRPr().addNewU().setVal(STUnderline.SINGLE);
            XWPFParagraph nativeFirst=paragraph(d,"First modeller qualification","aff1");nativeFirst.setNumID(number);nativeFirst.setNumILvl(BigInteger.ZERO);CTInd nativeInd=nativeFirst.getCTP().getPPr().addNewInd();nativeInd.setLeftChars(BigInteger.ZERO);nativeInd.setLeft(BigInteger.valueOf(3119));nativeInd.setHanging(BigInteger.valueOf(425));
            XWPFParagraph second=sourceLine(d,"ii.\tSecond modeller qualification","source4");CTInd secondInd=second.getCTP().getPPr().addNewInd();secondInd.setLeft(BigInteger.valueOf(3119));secondInd.setHanging(BigInteger.valueOf(419));secondInd.setLeftChars(BigInteger.valueOf(1350));secondInd.setHangingChars(BigInteger.valueOf(192));
            sourceLine(d,"iii.\tThird modeller qualification","source4");paragraph(d,"","source3");sourceLine(d,"c.\tNext source category","source3");sourceLine(d,"d.\tFollowing source category","source3");sourceLine(d,"e.\tLast source category","source3");sourceLine(d,"29.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),first=rangeStartingWith(scopes,"native-list","i. First modeller"),b=rangeStartingWith(scopes,"source-region","b. Qualifications");
        assertSourceInterval(scopes,first,"i. First modeller","ii. Second modeller");assertEquals("lowerLetter",first.path("numbering").path("format").asText());assertEquals(0,first.path("numbering").path("level").asInt());assertSourceInterval(scopes,b,"b. Qualifications","c. Next source");
        JsonNode coordinator=rangeStartingWith(scopes,"source-region","Coordinator source role"),modeller=rangeStartingWith(scopes,"source-region","Modeller source role");assertSourceInterval(scopes,coordinator,"Coordinator source role","Modeller source role");assertSourceInterval(scopes,modeller,"Modeller source role","c. Next source");assertEquals(b.path("id"),coordinator.path("parentId"));assertEquals(b.path("id"),modeller.path("parentId"));assertEquals(modeller.path("id"),first.path("parentId"));
        for(JsonNode candidate:Arrays.asList(b,coordinator,modeller,first)){assertTrue(candidate.path("scopeComplete").asBoolean());assertPhysicalAuthorityGuards(candidate);}for(JsonNode candidate:scopes.path("ranges"))for(JsonNode conflict:candidate.path("sourceConflicts"))assertFalse(conflict.path("previousLabel").asText().equals("b.")&&conflict.path("label").asText().equals("i."));verifyNoInteractions(ocr);
    }

    @Test void aStruckSourceLetterKeepsItsActiveTextFrameWithoutRevivingTheOrdinalOrJoiningOtherAlphaPeers()throws Exception {
        OcrClient ocr=mock(OcrClient.class);DocumentParser.ParsedDocument parsed;
        try(XWPFDocument d=sourceDeclarationDocument()){
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier source item","clause");sourceLine(d,"2.\tCurrent source requirements","clause");sourceLine(d,"k.\tEarlier source category","source3");
            XWPFParagraph title=d.createParagraph();title.setStyle("source3");XWPFRun struck=title.createRun();struck.setText("l");struck.setStrikeThrough(true);XWPFRun active=title.createRun();active.setText(".");active.addTab();active.setText("Air conditioning source category");sourceLine(d,"i.\tAir conditioning source instruction","source4");
            sourceLine(d,"m.\tWater saving source category","source3");XWPFParagraph child=paragraph(d,"Water saving source instruction","source4");child.setNumID(BigInteger.valueOf(45));child.setNumILvl(BigInteger.valueOf(5));sourceLine(d,"n.\tNext source category","source3");sourceLine(d,"3.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");parsed=parse(d,ocr);
        }
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"),air=rangeStartingWith(scopes,"source-region",". Air conditioning"),m=rangeStartingWith(scopes,"source-region","m. Water saving");assertSourceInterval(scopes,air,". Air conditioning","m. Water saving");assertEquals(2,members(scopes,air).size());JsonNode title=members(scopes,air).get(0);assertTrue(title.path("preNormalizedTextObservation").path("originalText").asText().startsWith("l."));assertTrue(title.path("preNormalizedTextObservation").path("text").asText().startsWith("."));assertEquals("l",title.path("textObservation").path("strikeText").asText());assertFalse(air.path("reference").asText().startsWith("l."));assertSourceInterval(scopes,m,"m. Water saving","n. Next source");
        for(JsonNode candidate:scopes.path("ranges"))for(JsonNode conflict:candidate.path("sourceConflicts"))assertFalse(conflict.path("previousLabel").asText().equals("k.")&&conflict.path("label").asText().equals("m."));verifyNoInteractions(ocr);
    }

    @Test void sourceConflictsStayWithinOnePhysicalSequenceAndPreserveRealRomanGapsAndDuplicatePairs()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=sourceDeclarationDocument()){
            BigInteger alpha=sourceNumbering(d,STNumberFormat.LOWER_LETTER,34,2694,357),fifth=sourceNumbering(d,STNumberFormat.LOWER_ROMAN,5,3056,357),second=sourceNumbering(d,STNumberFormat.LOWER_ROMAN,2,3056,357);
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tCurrent source list and common qualifications","clause");
            for(String title:Arrays.asList("Earlier source personnel","Last source personnel")){XWPFParagraph item=paragraph(d,title,"source3");item.setNumID(alpha);item.setNumILvl(BigInteger.ZERO);sourceIndent(item,2694,357);}d.createParagraph();sourceIndent(paragraph(d,"Except for the following personnel, the common source requirement applies:","clause"),2342,357);sourceLine(d,"a.\tFirst exception category","source3");sourceLine(d,"b.\tSecond exception category","source3");
            sourceLine(d,"2.\tNext source main item","clause");sourceLine(d,"j.\tVentilation source category","source3");sourceLine(d,"i.\tFirst ventilation instruction","source4");sourceLine(d,"ii.\tSecond ventilation instruction","source4");sourceLine(d,"iii.\tThird ventilation instruction","source4");XWPFParagraph gap=paragraph(d,"Last ventilation instruction","source4");gap.setNumID(fifth);gap.setNumILvl(BigInteger.ZERO);sourceLine(d,"k.\tNext ventilation category","source3");
            sourceLine(d,"3.\tProjector source main item","clause");sourceLine(d,"c.\tProjector source category:","source3");sourceLine(d,"i.\tFirst projector instruction","source4");sourceLine(d,"ii.\tSecond projector instruction","source4");sourceLine(d,"ii.\tThird projector instruction","source4");XWPFParagraph duplicate=paragraph(d,"Last projector instruction","source4");duplicate.setNumID(second);duplicate.setNumILvl(BigInteger.ZERO);sourceLine(d,"d.\tNext projector category","source3");sourceLine(d,"4.\tLast source main item","clause");paragraph(d,"Next source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        Set<String> pairs=new HashSet<>();for(JsonNode candidate:scopes.path("ranges"))for(JsonNode conflict:candidate.path("sourceConflicts"))pairs.add(conflict.path("previousMemberId").asText()+"|"+conflict.path("memberId").asText());
        Map<String,String> byText=new HashMap<>();for(JsonNode member:scopes.path("members"))byText.put(text(member),member.path("id").asText());
        assertFalse(pairs.contains(byText.get("ii. Last source personnel")+"|"+byText.get("a. First exception category")),"A common source outdent followed by a fresh alpha list is not a source numbering gap");
        assertTrue(pairs.contains(byText.get("iii. Third ventilation instruction")+"|"+byText.get("v. Last ventilation instruction")),"The genuine displayed iii-to-v gap must remain source evidence");assertTrue(pairs.contains(byText.get("ii. Second projector instruction")+"|"+byText.get("ii. Third projector instruction")));assertTrue(pairs.contains(byText.get("ii. Third projector instruction")+"|"+byText.get("ii. Last projector instruction")));verifyNoInteractions(ocr);
    }

    @Test void anUndottedTabMainItemEndsBeforeAnEntirelyBlankNativeTransitionToTheNextRealHeading()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=document()){
            paragraph(d,"Chapter","heading");sourceLine(d,"3.\tEarlier source item","clause");sourceLine(d,"4.\tPrevious source item","clause");sourceLine(d,"5\tTransfer the authored source information","clause");paragraph(d,"","heading");d.createParagraph();
            paragraph(d,"Environmental source chapter",null).getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(3));sourceLine(d,"1.\tEnvironmental first source item","clause");sourceLine(d,"2.\tEnvironmental second source item","clause");paragraph(d,"Following source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode five=rangeStartingWith(scopes,"manual-sequence","5 Transfer the authored");assertEquals(1,members(scopes,five).size());assertTrue(five.path("scopeComplete").asBoolean(),five.toString());assertFalse(five.has("unknownReasons"));assertEquals("next_native_outline_heading",five.path("endBoundary").path("kind").asText());assertTrue(members(scopes,five).get(0).path("preNormalizedTextObservation").path("text").asText().startsWith("5\t"));verifyNoInteractions(ocr);
    }

    @Test void compactRomanChildrenRemainInTheirNativeAlphaFrameAndAbbreviationsDoNotBecomeSourceLabels()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=sourceDeclarationDocument()){
            BigInteger number=sourceNumbering(d,STNumberFormat.LOWER_LETTER,1,2702,360);paragraph(d,"Chapter","heading");sourceLine(d,"18.\tEarlier source item","clause");sourceLine(d,"19.\tProfessional source responsibilities","clause");XWPFParagraph a=paragraph(d,"Employ the source professional for the following works:","source3");a.setNumID(number);a.setNumILvl(BigInteger.ZERO);
            String[] children={"i.\tFirst source works","ii.\tSecond source works","iii.Any construction activities within the source site.","iv.Temporary works affecting the source site;","v.\tAny other source works."};for(int i=0;i<children.length;i++){XWPFParagraph child=sourceLine(d,children[i],"source4");CTInd ind=child.getCTP().getPPr().addNewInd();ind.setLeft(BigInteger.valueOf(i<3?2702:2694));ind.setFirstLine(BigInteger.ZERO);}
            sourceLine(d,"b.\tNext source responsibility","source3");sourceLine(d,"20.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode a=rangeStartingWith(scopes,"native-list","a. Employ the source");assertSourceInterval(scopes,a,"a. Employ the source","b. Next source");assertEquals(6,members(scopes,a).size());for(JsonNode candidate:scopes.path("ranges"))if(text(members(scopes,candidate).get(0)).startsWith("iv.Temporary"))assertNotEquals("resolved_source_outdent_within_explicit_label_frame",candidate.path("basis").asText());
        try(XWPFDocument d=sourceDeclarationDocument()){
            BigInteger number=sourceNumbering(d,STNumberFormat.LOWER_LETTER,1,2702,360);paragraph(d,"Chapter","heading");sourceLine(d,"1.\tSource requirements","clause");XWPFParagraph item=paragraph(d,"Source responsibility:","source3");item.setNumID(number);item.setNumILvl(BigInteger.ZERO);sourceLine(d,"i.\tFirst source child","source4");sourceIndent(paragraph(d,"i.e. this ordinary explanation is not a source item.","source4"),2694,0);sourceIndent(paragraph(d,"e.g. this ordinary example is not a source item.","source4"),2694,0);sourceLine(d,"b.\tNext source responsibility","source3");sourceLine(d,"2.\tNext main item","clause");paragraph(d,"Next chapter","heading");JsonNode observed=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
            for(JsonNode candidate:observed.path("ranges"))if(text(members(observed,candidate).get(0)).matches("(?:i\\.e\\.|e\\.g\\.).*"))assertNotEquals("explicit_literal_list_label_and_bound_source_frame",candidate.path("basis").asText());
            Map<String,JsonNode> ranges=new HashMap<>();for(JsonNode candidate:observed.path("ranges"))ranges.put(candidate.path("id").asText(),candidate);List<String> outside=new ArrayList<>();for(JsonNode candidate:observed.path("ranges"))if(candidate.path("scopeComplete").asBoolean()&&!candidate.path("parentId").isNull()){
                JsonNode parent=ranges.get(candidate.path("parentId").asText());assertNotNull(parent);Set<String> parentIds=new HashSet<>();for(JsonNode id:parent.path("orderedMemberIds"))parentIds.add(id.asText());for(JsonNode id:candidate.path("orderedMemberIds"))if(!parentIds.contains(id.asText())){outside.add(candidate.path("id").asText());break;}
            }
            assertTrue(outside.isEmpty(),"Repeated outdent observations at one source start must not replace a containing parent with a truncated parent: "+outside);JsonNode tail=rangeStartingWith(observed,"source-region","i.e. this ordinary"),main=rangeStartingWith(observed,"manual-sequence","1. Source requirements");assertEquals(main.path("id"),tail.path("parentId"));assertSourceInterval(observed,tail,"i.e. this ordinary","b. Next source responsibility");assertEquals(2,members(observed,tail).size());assertFalse(tail.has("sourceReferenceKind"));
        }
        verifyNoInteractions(ocr);
    }

    @Test void aRomanFrameEnclosesItsBulletAndSameNativeDecimalChildrenBeforeTheNextRomanPeer()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=sourceDeclarationDocument()){
            CTAbstractNum abstractNum=CTAbstractNum.Factory.newInstance();abstractNum.setAbstractNumId(BigInteger.valueOf(2000));for(int level:new int[]{1,2}){CTLvl declaration=abstractNum.addNewLvl();declaration.setIlvl(BigInteger.valueOf(level));declaration.addNewStart().setVal(BigInteger.ONE);declaration.addNewNumFmt().setVal(STNumberFormat.DECIMAL);declaration.addNewLvlText().setVal("%"+(level+1)+".");declaration.addNewPPr().addNewInd().setLeft(BigInteger.valueOf(4320));}BigInteger number=d.getNumbering().addNum(d.getNumbering().addAbstractNum(new XWPFAbstractNum(abstractNum)));
            paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier source item","clause");sourceLine(d,"2.\tSafety source requirements","clause");sourceLine(d,"g.\tThe safety source plan shall contain the following:","source3");sourceLine(d,"ix.\tAccident and incident source reporting:","source4");sourceLine(d,"-\tReport source accidents","source4");sourceLine(d,"-\tCopy source information","source4");sourceLine(d,"-\tNotify the source manager","source4");d.createParagraph();sourceLine(d,"-\tNotify the source staff, including:","source4");sourceLine(d,"\u2022\tSerious source accidents","source4");sourceLine(d,"\u2022\tReportable source accidents","source4");sourceLine(d,"\u2022\tDeath of a source person","source4");sourceLine(d,"\u2022\tTree source incidents resulting in any of the following:","source4");
            int ordinal=0;for(String title:Arrays.asList("Loss of source human life","Major source injury","Substantial source damage","Complete source access blockage")){XWPFParagraph item=paragraph(d,title,"source4");item.setNumID(number);item.setNumILvl(BigInteger.valueOf(ordinal++==0?2:1));}
            sourceLine(d,"\u2022\tAny source emergency situations","source4");sourceLine(d,"-\tInform the source manager","source4");sourceLine(d,"-\tProvide source statistics","source4");sourceLine(d,"-\tThe source investigation shall include:","source4");for(String title:Arrays.asList("Source causes","Source control deficiencies","Source lessons","Source recommendations","Source improvements"))sourceLine(d,"\u2022\t"+title,"source4");sourceLine(d,"-\tProvide late source report trends","source4");sourceLine(d,"-\tProvide specialist source information","source4");sourceLine(d,"-\tCommunicate the source statistics","source4");d.createParagraph();sourceLine(d,"x.\tEmergency source preparedness:","source4");sourceLine(d,"h.\tNext source category","source3");sourceLine(d,"3.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode ix=rangeStartingWith(scopes,"source-region","ix. Accident"),tree=rangeStartingWith(scopes,"source-region","\u2022 Tree source"),first=rangeStartingWith(scopes,"native-list","1. Loss of source"),second=rangeStartingWith(scopes,"native-list","1. Major source"),emergency=rangeStartingWith(scopes,"source-region","\u2022 Any source emergency");assertSourceInterval(scopes,ix,"ix. Accident","x. Emergency");assertEquals(27,members(scopes,ix).size());assertSourceInterval(scopes,tree,"\u2022 Tree source","\u2022 Any source emergency");assertEquals(5,members(scopes,tree).size());assertEquals(tree.path("id"),first.path("parentId"));assertEquals(tree.path("id"),second.path("parentId"));assertEquals(ix.path("id"),tree.path("parentId"));assertEquals(ix.path("id"),emergency.path("parentId"));assertEquals(2,first.path("numbering").path("level").asInt());assertEquals(1,second.path("numbering").path("level").asInt());assertEquals("1.",first.path("numbering").path("label").asText());assertEquals("1.",second.path("numbering").path("label").asText());assertTrue(second.path("sourceConflicts").toString().contains("native_level_label_disagreement"));verifyNoInteractions(ocr);
    }

    @Test void aNestedDecimalBulletCannotClaimCompleteRomanContainmentWithoutAVerifiedRomanClosure()throws Exception {
        OcrClient ocr=mock(OcrClient.class);JsonNode scopes;
        try(XWPFDocument d=sourceDeclarationDocument()){
            BigInteger number=sourceNumbering(d,STNumberFormat.DECIMAL,1,4320,360);paragraph(d,"Chapter","heading");sourceLine(d,"1.\tEarlier source item","clause");sourceLine(d,"2.\tSafety source requirements","clause");sourceLine(d,"g.\tSafety source contents:","source3");sourceLine(d,"ix.\tSource incident reporting:","source4");sourceLine(d,"\u2022\tTree source incidents resulting in the following:","source4");
            for(String title:Arrays.asList("First source incident outcome","Second source incident outcome")){XWPFParagraph item=paragraph(d,title,"source4");item.setNumID(number);item.setNumILvl(BigInteger.ZERO);}sourceLine(d,"h.\tNext source category without a following roman peer","source3");sourceLine(d,"3.\tNext main source item","clause");paragraph(d,"Next source chapter","heading");scopes=parse(d,ocr).getCoverage().getNativeDocxOutline().path("sourceClauseScopes");
        }
        JsonNode ix=rangeStartingWith(scopes,"source-region","ix. Source incident"),tree=rangeStartingWith(scopes,"source-region","\u2022 Tree source"),first=rangeStartingWith(scopes,"native-list","1. First source incident");assertFalse(ix.path("scopeComplete").asBoolean());assertEquals("partial_unknown_scope",ix.path("status").asText());assertTrue(ix.path("unknownReasons").toString().contains("nested_decimal_source_frame_has_no_verified_roman_peer_closure"));assertSourceInterval(scopes,ix,"ix. Source incident","h. Next source category");assertEquals(4,members(scopes,ix).size());assertEquals(ix.path("id"),tree.path("parentId"));assertEquals(tree.path("id"),first.path("parentId"));
        Map<String,JsonNode> byId=new HashMap<>();for(JsonNode candidate:scopes.path("ranges"))byId.put(candidate.path("id").asText(),candidate);List<String> outside=new ArrayList<>();for(JsonNode candidate:scopes.path("ranges"))if(candidate.path("scopeComplete").asBoolean()&&!candidate.path("parentId").isNull()){
            JsonNode parent=byId.get(candidate.path("parentId").asText());assertNotNull(parent);Set<String> included=new HashSet<>();for(JsonNode id:parent.path("orderedMemberIds"))included.add(id.asText());for(JsonNode id:candidate.path("orderedMemberIds"))if(!included.contains(id.asText())){outside.add(candidate.path("id").asText());break;}
        }
        assertTrue(outside.isEmpty(),"A nested decimal owner must not grant complete containment outside the observed roman frame: "+outside);verifyNoInteractions(ocr);
    }

    private BigInteger sourceNumbering(XWPFDocument document,STNumberFormat.Enum format,int start,int left,int hanging){
        CTAbstractNum raw=CTAbstractNum.Factory.newInstance();raw.setAbstractNumId(BigInteger.valueOf(1000+start));CTLvl level=raw.addNewLvl();level.setIlvl(BigInteger.ZERO);level.addNewStart().setVal(BigInteger.valueOf(start));level.addNewNumFmt().setVal(format);level.addNewLvlText().setVal("%1.");CTInd ind=level.addNewPPr().addNewInd();ind.setLeft(BigInteger.valueOf(left));ind.setHanging(BigInteger.valueOf(hanging));return document.getNumbering().addNum(document.getNumbering().addAbstractNum(new XWPFAbstractNum(raw)));
    }

    private XWPFDocument sourceDeclarationDocument()throws Exception {
        XWPFDocument d=document();d.getStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='paragraph' w:styleId='heading'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='clause'/><w:style w:type='paragraph' w:styleId='source1'><w:pPr><w:ind w:left='1985'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='source3'><w:basedOn w:val='source1'/><w:pPr><w:ind w:left='2699' w:hanging='357'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='source4'><w:basedOn w:val='source1'/><w:pPr><w:ind w:left='3056' w:hanging='357'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='aff1'><w:pPr><w:ind w:leftChars='200' w:left='480'/></w:pPr></w:style></w:styles>").getStyles());
        d.getNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='"+W+"'><w:abstractNum w:abstractNumId='82'><w:lvl w:ilvl='2'><w:start w:val='8'/><w:numFmt w:val='lowerRoman'/><w:lvlText w:val='%3.'/><w:pPr><w:ind w:left='1440' w:hanging='480'/></w:pPr></w:lvl><w:lvl w:ilvl='5'><w:start w:val='1'/><w:numFmt w:val='lowerRoman'/><w:lvlText w:val='%6.'/><w:pPr><w:ind w:left='2880' w:hanging='480'/></w:pPr></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId='39'><w:lvl w:ilvl='0'><w:start w:val='6'/><w:numFmt w:val='lowerLetter'/><w:lvlText w:val='%1.'/><w:pPr><w:ind w:left='2771' w:hanging='360'/></w:pPr></w:lvl></w:abstractNum><w:num w:numId='45'><w:abstractNumId w:val='82'/></w:num><w:num w:numId='63'><w:abstractNumId w:val='39'/></w:num></w:numbering>").getNumbering());return d;
    }

    private XWPFDocument sourceListDocument(String bullet)throws Exception {
        XWPFDocument d=document();StringBuilder xml=new StringBuilder("<w:numbering xmlns:w='"+W+"'>");
        for(int id:new int[]{10,20,21,22,23,24,30,40,45,50,60,70,71}){
            String format=id==10||id==60?"lowerLetter":id==30?"bullet":id==40?"decimal":"lowerRoman",label=id==30?bullet:"%1.";int start=id==10||id==45?4:id==40?18:id==60?26:id==50||id==71?3:1;
            xml.append("<w:abstractNum w:abstractNumId='").append(id).append("'><w:lvl w:ilvl='0'><w:start w:val='").append(start).append("'/><w:numFmt w:val='").append(format).append("'/><w:lvlText w:val='").append(label).append("'/></w:lvl></w:abstractNum>");
        }
        for(int id:new int[]{10,20,21,22,23,24,30,40,45,50,60,70,71})xml.append("<w:num w:numId='").append(id).append("'><w:abstractNumId w:val='").append(id).append("'/></w:num>");
        d.getNumbering().setNumbering(NumberingDocument.Factory.parse(xml.append("</w:numbering>").toString()).getNumbering());return d;
    }

    private XWPFParagraph sourceList(XWPFDocument d,String text,int id){XWPFParagraph p=paragraph(d,text,"clause");p.setNumID(BigInteger.valueOf(id));p.setNumILvl(BigInteger.ZERO);return p;}
    private JsonNode rangeStartingWith(JsonNode scopes,String kind,String text){JsonNode found=null;for(JsonNode candidate:scopes.path("ranges"))if(kind.equals(candidate.path("kind").asText())&&text(members(scopes,candidate).get(0)).startsWith(text)){assertNull(found,"Multiple ranges start with "+text);found=candidate;}assertNotNull(found,"Missing "+kind+" at "+text);return found;}
    private void assertSourceInterval(JsonNode scopes,JsonNode candidate,String from,String end){
        JsonNode first=null,last=null;for(JsonNode member:scopes.path("members")){if(first==null&&text(member).startsWith(from))first=member;if(last==null&&text(member).startsWith(end))last=member;}assertNotNull(first);assertNotNull(last);
        List<String> expected=new ArrayList<>(),actual=new ArrayList<>();for(JsonNode member:scopes.path("members"))if(member.path("ordinalZeroBased").asInt()>=first.path("ordinalZeroBased").asInt()&&member.path("ordinalZeroBased").asInt()<last.path("ordinalZeroBased").asInt())expected.add(member.path("id").asText());candidate.path("orderedMemberIds").forEach(id->actual.add(id.asText()));assertEquals(expected,actual);assertEquals(last.path("sourcePosition"),candidate.path("endBoundary").path("sourcePosition"));
        assertEquals("docx-source-range:"+DocumentParseProbe.sha256((candidate.path("kind").asText()+"|"+first.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),candidate.path("id").asText());
    }

    private XWPFDocument ownerLocalPhysicalDocument(boolean children)throws Exception {
        XWPFDocument d=document();paragraph(d,"First chapter","heading");sourceLine(d,"1.\tFirst owner earlier item","clause");sourceLine(d,"2.\tFirst owner source item:","clause");
        if(children){sourceLine(d,"a.\tFirst source child","other");sourceLine(d,"b.\tSecond source child","other");sourceLine(d,"c.\tThird source child","other");}
        sourceIndent(sourceLine(d,"3.\tFirst owner next item","other"),2410,425);sourceIndent(sourceLine(d,"4.\tFirst owner final item","other"),2410,425);
        paragraph(d,"Second chapter","heading");sourceLine(d,"1.\tSecond owner earlier item","clause");sourceLine(d,"2.\tSecond owner next item","clause");sourceLine(d,"3.\tSecond owner final item","clause");paragraph(d,"Following chapter","heading");return d;
    }

    private JsonNode findRange(JsonNode scopes,String id){for(JsonNode candidate:scopes.path("ranges"))if(id.equals(candidate.path("id").asText()))return candidate;fail("Missing source range "+id);return null;}

    private XWPFDocument document()throws Exception {
        XWPFDocument d=new XWPFDocument();d.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w='"+W+"'><w:style w:type='paragraph' w:styleId='heading'><w:pPr><w:outlineLvl w:val='2'/></w:pPr></w:style><w:style w:type='paragraph' w:styleId='clause'/><w:style w:type='paragraph' w:styleId='other'/></w:styles>").getStyles());
        d.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w='"+W+"'><w:abstractNum w:abstractNumId='1'><w:lvl w:ilvl='0'><w:start w:val='1'/><w:numFmt w:val='lowerLetter'/><w:lvlText w:val='(%1)'/></w:lvl></w:abstractNum><w:num w:numId='1'><w:abstractNumId w:val='1'/></w:num></w:numbering>").getNumbering());return d;
    }
    private XWPFParagraph paragraph(XWPFDocument d,String text,String style){XWPFParagraph p=d.createParagraph();if(style!=null)p.setStyle(style);p.createRun().setText(text);return p;}
    private void numbered(XWPFDocument d,String text){XWPFParagraph p=paragraph(d,text,"clause");p.setNumID(BigInteger.ONE);p.setNumILvl(BigInteger.ZERO);}
    private DocumentParser.ParsedDocument parse(XWPFDocument d,OcrClient ocr)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();d.write(out);DocumentParser.ParsedDocument parsed=new DocumentParser(new ConsenseProperties(),ocr).parse("source.docx",out.toByteArray());assertCompleteParentsContain(parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes"));return parsed;}
    private void assertCompleteParentsContain(JsonNode scopes){
        Map<String,JsonNode> ranges=new HashMap<>();for(JsonNode candidate:scopes.path("ranges"))ranges.put(candidate.path("id").asText(),candidate);List<String> failures=new ArrayList<>();
        for(JsonNode candidate:scopes.path("ranges"))if(candidate.path("scopeComplete").asBoolean()&&!candidate.path("parentId").isNull()){
            JsonNode parent=ranges.get(candidate.path("parentId").asText());if(parent==null){failures.add(candidate.path("reference").asText()+" has absent parent "+candidate.path("parentId").asText());continue;}Set<String> ids=new HashSet<>();for(JsonNode id:parent.path("orderedMemberIds"))ids.add(id.asText());for(JsonNode id:candidate.path("orderedMemberIds"))if(!ids.contains(id.asText())){failures.add(candidate.path("reference").asText()+" at "+candidate.path("startBoundary").path("sourcePosition").path("nodePath").asText()+" lies outside parent "+parent.path("reference").asText());break;}
        }
        assertTrue(failures.isEmpty(),"Every complete source range must have an existing physical parent containing all of its observed members: "+failures);
    }
    private JsonNode range(JsonNode scopes,String kind,String reference){List<JsonNode> matches=new ArrayList<>();for(JsonNode range:scopes.path("ranges"))if(kind.equals(range.path("kind").asText())&&reference.equals(range.path("reference").asText()))matches.add(range);assertEquals(1,matches.size(),kind+" "+reference);return matches.get(0);}
    private List<JsonNode> members(JsonNode scopes,JsonNode range){Map<String,JsonNode> byId=new HashMap<>();scopes.path("members").forEach(m->byId.put(m.path("id").asText(),m));List<JsonNode> out=new ArrayList<>();range.path("orderedMemberIds").forEach(id->out.add(Objects.requireNonNull(byId.get(id.asText()))));return out;}
    private String text(JsonNode member){return member.path("textObservation").path("text").asText();}
}
