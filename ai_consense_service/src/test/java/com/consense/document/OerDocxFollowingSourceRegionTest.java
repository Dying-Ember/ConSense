package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerDocxFollowingSourceRegionTest {
    static List<String> lines(){return new ArrayList<>(Arrays.asList("1.\tThe source includes the following:","(a)\tFIRST_BRANCH","","(b)\tSECOND_BRANCH","","(c)\tFor outside works, the specification shall include the following in addition to the earlier items.","","UNIQUE_PAYLOAD 42 days; deleted clauses; named-role substitution; precedence.","","2.\tEXCLUDED_NEXT_MAIN","3.\tEXCLUDED_THIRD_MAIN","4.\tEXCLUDED_FINAL_MAIN","EXCLUDED_LATER_COVER"));}
    static DocumentParser.ParsedDocument parse(List<String> lines,String defect)throws Exception{
        try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            int n=0;for(String line:lines){
                if(line.equals("TABLE")){doc.createTable(1,1).getRow(0).getCell(0).setText("AMBIGUOUS_TABLE");n++;continue;}
                XWPFParagraph p=doc.createParagraph();XWPFRun run=p.createRun();String[] pieces=line.split("\t",-1);for(int i=0;i<pieces.length;i++){if(i>0)run.addTab();run.setText(pieces[i]);}
                if(n==5&&defect.equals("char_units")){p.getCTP().addNewPPr().addNewInd().setLeftChars(BigInteger.valueOf(284));}
                if(n==6&&defect.equals("section"))p.getCTP().addNewPPr().addNewSectPr();
                if(n==6&&defect.equals("heading"))p.getCTP().addNewPPr().addNewOutlineLvl().setVal(BigInteger.ONE);
                if(n==5&&defect.equals("strike_intro")||n==7&&defect.equals("strike_payload"))run.setStrikeThrough(true);
                n++;
            }
            doc.write(out);return new DocumentParser(new ConsenseProperties(),null).parse("authored-following.docx",out.toByteArray());
        }
    }
    static JsonNode scopes(DocumentParser.ParsedDocument parsed){return parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");}
    static List<JsonNode> groups(JsonNode scopes){List<JsonNode> out=new ArrayList<>();for(JsonNode range:scopes.path("ranges"))if("explicit_forward_introduction_unique_payload_source_interval".equals(range.path("basis").asText()))out.add(range);return out;}
    @Test void literalForwardIntroductionKeepsOnlyItsPayloadAndNativeSpacersWithoutGeometricOrLegalClaims()throws Exception{
        for(String defect:Arrays.asList("","char_units")){
            DocumentParser.ParsedDocument parsed=parse(lines(),defect);JsonNode scopes=scopes(parsed);List<JsonNode> groups=groups(scopes);assertEquals(1,groups.size());JsonNode group=groups.get(0);
            assertEquals(4,group.path("orderedMemberIds").size());assertTrue(group.path("scopeComplete").asBoolean());assertTrue(group.path("parentId").isNull());assertFalse(group.path("automaticEditEligible").asBoolean(true));assertFalse(group.path("semanticApplicabilityVerified").asBoolean(true));
            assertEquals(scopes.path("members").get(9).path("sourcePosition"),group.path("endBoundary").path("sourcePosition"));assertEquals(0,parsed.getCoverage().getNativeDocxOutline().path("headings").size());
            List<String> expected=new ArrayList<>();for(int n=5;n<9;n++)expected.add(scopes.path("members").get(n).path("id").asText());assertEquals(JsonUtils.mapper().valueToTree(expected),group.path("orderedMemberIds"));assertEquals(1,scopes.path("ranges").size());
        }
    }
    @Test void ambiguousSyntaxBoundariesAndRevisionChannelsCannotCreateTheForwardGroup()throws Exception{
        for(String defect:Arrays.asList("no_cue","missing_b","duplicate_b","reversed","extra_payload","new_label","next_gap","missing_end","repeated_main","double_cue","quoted_cue","quoted_single_cue","quoted_curly_single_cue","quoted_curly_double_cue","table","section","heading","strike_intro","strike_payload")){
            List<String> values=lines();
            if(defect.equals("no_cue"))values.set(5,"(c)\tAn ordinary observation without a forward introduction.");
            if(defect.equals("missing_b"))values.set(3,"");
            if(defect.equals("duplicate_b"))values.set(5,"(b)\tShall include the following:");
            if(defect.equals("reversed"))Collections.swap(values,1,3);
            if(defect.equals("extra_payload"))values.add(8,"SECOND_UNNUMBERED_PAYLOAD");
            if(defect.equals("new_label"))values.add(8,"(d)\tNEW_LABEL");
            if(defect.equals("next_gap"))values.set(9,"7.\tUNPROVED_END");
            if(defect.equals("missing_end"))values=new ArrayList<>(values.subList(0,9));
            if(defect.equals("repeated_main"))values.add("1.\tDUPLICATED_MAIN");
            if(defect.equals("double_cue"))values.set(5,"(c)\tShall include the following; then include the following.");
            if(defect.equals("quoted_cue"))values.set(5,"(c)\tThe index quotes \"include the following\" for reference.");
            if(defect.equals("quoted_single_cue"))values.set(5,"(c)\tThe index quotes 'include the following' for reference.");
            if(defect.equals("quoted_curly_single_cue"))values.set(5,"(c)\tThe index quotes ‘include the following’ for reference.");
            if(defect.equals("quoted_curly_double_cue"))values.set(5,"(c)\tThe index quotes “include the following” for reference.");
            if(defect.equals("table"))values.add(8,"TABLE");
            assertTrue(groups(scopes(parse(values,defect))).isEmpty(),defect);
        }
    }
    @Test void duplicateMissingForeignOrUnobservedNativeMembersAndSectionsRemainUnknown()throws Exception{
        DocumentParser.ParsedDocument parsed=parse(lines(),"");JsonNode old=scopes(parsed);
        for(String defect:Arrays.asList("duplicate_id","missing_id","duplicate_position","ordinal_gap","foreign_source","foreign_part","foreign_story","unknown_channel","overlap","ineffective","deleted","raw_changed","missing_blank","missing_sections","split_section")){
            ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.set("sourceSha256",old.path("sourceSha256"));inventory.set("storyRoots",old.path("storyRoots"));ArrayNode members=old.path("members").deepCopy();inventory.set("paragraphs",members);ObjectNode payload=(ObjectNode)members.get(7);JsonNode sections=parsed.getCoverage().getNativeDocxSections().deepCopy();
            if(defect.equals("duplicate_id"))payload.set("id",members.get(5).path("id"));
            if(defect.equals("missing_id"))payload.remove("id");
            if(defect.equals("duplicate_position"))payload.set("sourcePosition",members.get(5).path("sourcePosition"));
            if(defect.equals("ordinal_gap"))payload.put("ordinalZeroBased",77);
            if(defect.equals("foreign_source"))((ObjectNode)payload.path("sourcePosition")).put("sourceSha256","f".repeat(64));
            if(defect.equals("foreign_part"))((ObjectNode)payload.path("sourcePosition")).put("partName","word/header1.xml");
            if(defect.equals("foreign_story"))payload.put("storyRootNodePath","foreign");
            if(defect.equals("unknown_channel"))((ObjectNode)payload.path("preNormalizedTextObservation")).put("status","unknown");
            if(defect.equals("overlap"))payload.put("textOverlapStatus","unknown");
            if(defect.equals("ineffective"))payload.put("effectiveCandidate",false);
            if(defect.equals("deleted"))((ObjectNode)payload.path("textObservation")).put("deletedText","deleted payload");
            if(defect.equals("raw_changed"))((ObjectNode)payload.path("preNormalizedTextObservation")).put("originalText","other channel");
            if(defect.equals("missing_blank"))members.remove(6);
            if(defect.equals("missing_sections"))sections=JsonUtils.mapper().createObjectNode();
            if(defect.equals("split_section")){ArrayNode values=(ArrayNode)sections.path("sections");ObjectNode first=(ObjectNode)values.get(0),second=first.deepCopy();ArrayNode paths=(ArrayNode)first.path("nativeMemberPaths"),tail=second.putArray("nativeMemberPaths");while(paths.size()>7)tail.add(paths.remove(7));values.add(second);}
            String before=JsonUtils.write(inventory);ObjectNode actual=OerDocxSourceScopes.observe(inventory,parsed.getCoverage().getNativeDocxOutline(),sections);assertTrue(groups(actual).isEmpty(),defect);assertEquals(before,JsonUtils.write(inventory),"Caller observations must stay immutable: "+defect);
        }
    }
}
