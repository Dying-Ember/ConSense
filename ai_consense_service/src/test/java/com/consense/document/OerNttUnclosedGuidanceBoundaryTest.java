package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OerNttUnclosedGuidanceBoundaryTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),PART=String.join("",Collections.nCopies(64,"b"));
    private static final String BODY="/{w}document[1]/{w}body[1]";

    @Test void unclosedSidebarCannotPoisonTheNextDifferentClauseThroughProvenEmptyCells() {
        for(String[] labels:new String[][]{{"37","38","39"},{"92","104","117"}}){
            ObjectNode[] source=fixture(labels[0],labels[1],labels[2],spacer("after","r4"));
            DocumentParser.ParsedDocument parsed=word(source);
            JsonNode scopes=OerTemplateEvidence.nativeClauseRanges(parsed.getCoverage().getNativeDocxParagraphs());
            assertFalse(range(scopes,labels[0]).path("scopeComplete").asBoolean());
            assertTrue(range(scopes,labels[1]).path("scopeComplete").asBoolean(),"the unrelated next clause retains complete physical body scope");
            assertEquals(Arrays.asList("next-number","next-title","child-a","child-b"),ids(range(scopes,labels[1]).path("orderedMemberIds")));
            assertTrue(range(scopes,labels[1]+"(a)").path("scopeComplete").asBoolean());
            assertTrue(range(scopes,labels[1]+"(b)").path("scopeComplete").asBoolean());
            JsonNode evidence=OerTemplateEvidence.analyse("NTT",parsed),note=evidence.path("guidanceNotes").get(0);
            assertEquals(Arrays.asList("open","tail","before"),ids(note.path("orderedMemberIds")));
            assertFalse(note.path("captureComplete").asBoolean());
            assertFalse(note.path("observedSyntaxComplete").asBoolean());
            assertFalse(note.path("sourceSyntaxComplete").asBoolean());
            assertTrue(note.path("sourceSyntaxKnown").asBoolean());
            assertFalse(note.path("scopeComplete").asBoolean());
            assertEquals(source.length,evidence.path("sourceMembers").size());
            assertEquals("terminator",range(scopes,labels[1]).path("endBoundary").path("memberId").asText());
        }
    }

    @Test void balancedOrNonemptyGuidanceContinuationAcrossAnotherClauseIsNeverCut() {
        for(String continuation:Arrays.asList("the condition continues in the next native row)","the actual unclosed condition continues")){
            DocumentParser.ParsedDocument parsed=word(fixture("37","38","39",cell("after","r4","c3",continuation)));
            JsonNode note=OerTemplateEvidence.analyse("NTT",parsed).path("guidanceNotes").get(0);
            assertTrue(ids(note.path("orderedMemberIds")).contains("after"));
            assertTrue(note.path("text").asText().contains(continuation));
            assertEquals(continuation.endsWith(")"),note.path("sourceSyntaxComplete").asBoolean());
            if(!continuation.endsWith(")"))assertFalse(range(OerTemplateEvidence.nativeClauseRanges(parsed.getCoverage().getNativeDocxParagraphs()),"38").path("scopeComplete").asBoolean());
        }
    }

    @Test void blankLookingCellsRequireStrictSourceBoundNativeSpacerEvidence() {
        for(int defect=0;defect<9;defect++){
            ObjectNode after=spacer("after","r4");
            if(defect==0)after.remove("nativeContentEmpty");
            if(defect==1)after.put("nativeContentEmpty",false);
            if(defect==2)after.put("nativeContentEmpty","true");
            if(defect==3)after.put("nativeContentEmpty",1);
            if(defect==4)((ObjectNode)after.path("textObservation")).put("originalText","hidden source payload");
            if(defect==5)((ObjectNode)after.path("textObservation")).put("strikeText","deleted choice");
            if(defect==6)((ObjectNode)after.path("textObservation")).remove("deletedText");
            if(defect==7)((ObjectNode)after.path("textObservation")).put("text",1);
            if(defect==8)((ObjectNode)after.path("sourcePosition")).put("sourceSha256",PART);
            DocumentParser.ParsedDocument parsed=word(fixture("37","38","39",after));
            JsonNode note=OerTemplateEvidence.analyse("NTT",parsed).path("guidanceNotes").get(0);
            if(defect<7)assertTrue(ids(note.path("orderedMemberIds")).contains("after"),"retain uncertain suffix case "+defect);
            assertFalse(note.path("captureComplete").asBoolean());
            assertFalse(note.path("sourceSyntaxComplete").asBoolean());
            assertEquals(15,OerTemplateEvidence.analyse("NTT",parsed).path("sourceMembers").size());
            assertFalse(range(OerTemplateEvidence.nativeClauseRanges(parsed.getCoverage().getNativeDocxParagraphs()),"38").path("scopeComplete").asBoolean(),"refuse source proof case "+defect);
        }
    }

    @Test void aDifferentNativePartRevisionCannotProveAnEmptySuffixOrTheNextOwnerBoundary() {
        for(int changedMember:new int[]{7,9}){
            ObjectNode[] source=fixture("37","38","39",spacer("after","r4"));
            ((ObjectNode)source[changedMember].path("sourcePosition")).put("partSha256",SHA);
            JsonNode evidence=OerTemplateEvidence.analyse("NTT",word(source)),note=evidence.path("guidanceNotes").get(0);
            assertTrue(ids(note.path("orderedMemberIds")).contains("after"),"retain suffix when boundary or spacer has a foreign part revision");
            assertFalse(note.path("captureComplete").asBoolean());
            assertFalse(note.path("sourceSyntaxComplete").asBoolean());
            assertEquals(source.length,evidence.path("sourceMembers").size());
        }
    }

    @Test void sameNumberOrDifferentNativeTableCannotProvideTheRequiredNewOwnerBoundary() {
        ObjectNode[] repeated=fixture("37","37","39",spacer("after","r4"));
        JsonNode same=OerTemplateEvidence.analyse("NTT",word(repeated)).path("guidanceNotes").get(0);
        assertTrue(ids(same.path("orderedMemberIds")).contains("after"));
        ObjectNode[] foreign=fixture("37","38","39",spacer("after","r4"));
        for(int at=7;at<foreign.length;at++){
            ObjectNode member=foreign[at];
            for(String key:Arrays.asList("tableNodePath","rowNodePath","cellNodePath"))if(member.has(key))member.put(key,member.path(key).asText().replace("tbl[t1]","tbl[t2]"));
            ((ObjectNode)member.path("sourcePosition")).put("nodePath",member.path("sourcePosition").path("nodePath").asText().replace("tbl[t1]","tbl[t2]"));
        }
        JsonNode different=OerTemplateEvidence.analyse("NTT",word(foreign)).path("guidanceNotes").get(0);
        assertTrue(ids(different.path("orderedMemberIds")).contains("after"));
        assertFalse(different.path("sourceSyntaxComplete").asBoolean());
    }

    private static ObjectNode[] fixture(String owner,String next,String terminator,ObjectNode after){return new ObjectNode[]{
        paragraph("title","Notes to Tenderers"),cell("owner-number","r1","c1",owner+"."),cell("owner-title","r1","c2","First source condition"),cell("open","r1","c3","(Not used if"),
        cell("owner-body","r2","c2","The complete source requirement."),cell("tail","r2","c3","the source works are absent"),spacer("before","r3"),
        cell("next-number","r4","c1",next+"."),cell("next-title","r4","c2","Other matters"),after,
        cell("child-a","r5","c2","(a) First independent source requirement."),spacer("after-a","r5"),
        cell("child-b","r6","c2","(b) Second independent source requirement."),spacer("after-b","r6"),cell("terminator","r7","c1",terminator+".")};}
    private static ObjectNode spacer(String id,String row){return cell(id,row,"c3","").put("nativeContentEmpty",true);}
    private static ObjectNode paragraph(String id,String text){ObjectNode member=JsonUtils.mapper().createObjectNode();member.put("id",id);member.put("storyKind","body");member.put("storyRootNodePath",BODY);member.putObject("sourcePosition").put("kind","docx_native").put("sourceSha256",SHA).put("partName","word/document.xml").put("partSha256",PART).put("nodePath",BODY+"/{w}p["+id+"]");member.putObject("textObservation").put("status","observed_parser_paragraph_channels").put("text",text).put("originalText",text).put("deletedText","").put("strikeText","");return member;}
    private static ObjectNode cell(String id,String row,String column,String text){ObjectNode member=paragraph(id,text);String table=BODY+"/{w}tbl[t1]",nativeRow=table+"/{w}tr["+row+"]",cell=nativeRow+"/{w}tc["+column+"]";member.put("tableNodePath",table);member.put("rowNodePath",nativeRow);member.put("cellNodePath",cell);((ObjectNode)member.path("sourcePosition")).put("nodePath",cell+"/{w}p["+id+"]");return member;}
    private static DocumentParser.ParsedDocument word(ObjectNode... members){ParseCoverage coverage=new ParseCoverage();ObjectNode inventory=JsonUtils.mapper().createObjectNode();inventory.put("sourceSha256",SHA);ArrayNode paragraphs=inventory.putArray("paragraphs");for(ObjectNode member:members)paragraphs.add(member);coverage.setNativeDocxParagraphs(inventory);return new DocumentParser.ParsedDocument("fixture",Collections.emptyList(),0,false,"fixture",Collections.emptyList(),coverage,SHA,Collections.emptyList());}
    private static JsonNode range(JsonNode scopes,String label){JsonNode result=null;for(JsonNode range:scopes.path("ranges"))if(label.equals(range.path("reference").asText())){assertNull(result,"duplicate "+label);result=range;}assertNotNull(result,"missing "+label);return result;}
    private static List<String> ids(JsonNode values){List<String> result=new ArrayList<>();for(JsonNode value:values)result.add(value.asText());return result;}
}
