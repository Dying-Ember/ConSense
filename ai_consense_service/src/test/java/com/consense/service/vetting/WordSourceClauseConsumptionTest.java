package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordSourceClauseConsumptionTest {
    private static final String SHA=VettingCorpus.hash("authored source"),PART=VettingCorpus.hash("authored part"),NS="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}",BODY="/"+NS+"document[1]/"+NS+"body[1]";
    @Test void partialParagraphHitCarriesNarrowestClauseAndRealParentWithOrderedEmptyAndSeparateContext(){
        SourceDocument source=source();VettingCorpus.Chunk hit=hit(source);String before=JsonUtils.write(hit);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")));
        assertEquals(Arrays.asList("m2","m3","m4"),strings(selected.path("ranges").get(0).path("orderedMemberIds")));
        assertEquals(7,selected.path("members").size());assertEquals("",member(selected,"m3").path("textObservation").path("text").asText());
        assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));assertTrue(JsonUtils.write(selected).contains("PARENT_SIBLING"));assertTrue(JsonUtils.write(selected).contains("SEPARATE_CONTEXT"));assertFalse(JsonUtils.write(selected).contains("OUTSIDE_CHAPTER"));
        assertEquals(Collections.singletonList("m7"),strings(selected.path("ranges").get(0).path("contextMemberIds")));
        assertEquals(before,JsonUtils.write(hit));assertEquals(source.getSourceSha256(),selected.path("sourceSha256").asText());
    }
    @Test void plainWordScopeBodyAndContextReachOrdinaryModelAndLosslessCompactWireWithoutChangingQuotation(){
        SourceDocument source=source();VettingCorpus.Chunk hit=hit(source);String content=hit.getContent();OerModelMaterial.Request request=new OerModelMaterial.Request(Collections.singletonList(source));
        JsonNode model=request.projectOrdinary(hit);assertNotNull(model,"Plain Word clauses must not return early because no table inventory exists");
        assertEquals(2,model.path("units").size());JsonNode narrow=model.path("units").get(0);
        assertEquals("ordinary_docx_source_clause",narrow.path("kind").asText());assertEquals(3,narrow.path("members").size());assertEquals(1,narrow.path("contextMembers").size());
        assertTrue(narrow.path("text").asText().contains("CHILD_TAIL"));assertFalse(narrow.path("text").asText().contains("SEPARATE_CONTEXT"));assertEquals("SEPARATE_CONTEXT",narrow.path("contextMembers").get(0).path("textObservation").path("text").asText());
        assertTrue(narrow.path("parts").isEmpty());assertFalse(narrow.path("semanticScopeVerified").asBoolean());assertEquals("unknown",narrow.path("legalClosure").asText());
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source)));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertTrue(JsonUtils.write(ordinary).contains("PARENT_SIBLING"));assertTrue(JsonUtils.write(ordinary).contains("SEPARATE_CONTEXT"));
        assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));assertEquals(content,hit.getContent());
        assertTrue(request.projectOrdinary(hit).path("units").get(0).has("modelUnitRef"));
    }
    @Test void incompleteNarrowestScopeDoesNotExpandToItsCompleteParent(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());((ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(1)).put("scopeComplete",false).put("status","unknown_boundary");
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
        assertTrue(selected.path("ranges").isEmpty());assertFalse(selected.path("issues").isEmpty());assertFalse(JsonUtils.write(selected).contains("CHILD_TAIL"));
    }
    @Test void completeChildSurvivesPartialParentWhoseUnknownBodyDoesNotClaimChildMembership(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode chapter=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(0);
        chapter.put("scopeComplete",false).put("status","partial_unknown_scope");chapter.set("orderedMemberIds",JsonUtils.mapper().valueToTree(Collections.singletonList("m1")));
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);VettingCorpus.Chunk hit=hit(source);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Collections.singletonList("child-a"),ids(selected.path("ranges")),"An incomplete parent's unknown body cannot invalidate the complete source-bound child");
        assertFalse(selected.path("issues").isEmpty());assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));assertFalse(JsonUtils.write(selected).contains("PARENT_SIBLING"));
        JsonNode ordinary=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source)));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertFalse(JsonUtils.write(ordinary).contains("PARENT_SIBLING"));assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));
    }
    @Test void partialCarrierUsesOnlyTheExactBoundUniqueParagraphRange(){
        SourceDocument source=carrierSource();VettingCorpus.Chunk hit=hit(source);String before=JsonUtils.write(hit);JsonNode selected=hit.getCandidateStructure().path("sourceClauseScopes");
        assertEquals(Arrays.asList("child-a","chapter"),ids(selected.path("ranges")));assertEquals("",member(selected,"m3").path("textObservation").path("text").asText());assertTrue(JsonUtils.write(selected).contains("CHILD_TAIL"));
        JsonNode ordinary=JsonUtils.parse(JsonUtils.write(VettingSourceMaterial.project(Collections.singletonList(hit),Collections.singletonList(source))));
        assertTrue(JsonUtils.write(ordinary).contains("CHILD_TAIL"));assertEquals(ordinary,VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(ordinary)));assertEquals(before,JsonUtils.write(hit));
    }
    @Test void nativeContainerEndUsesTheActualAncestorWithoutInventingAMember(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode boundary=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("ranges").get(1).path("endBoundary");boundary.put("kind","native_container_end").remove("memberId");ObjectNode body=position(1);body.put("nodePath",BODY);boundary.set("sourcePosition",body);
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);assertEquals(Arrays.asList("child-a","chapter"),ids(hit(source).getCandidateStructure().path("sourceClauseScopes").path("ranges")));
    }
    @Test void duplicateForeignOrUnboundParagraphRangesNeverExpandPartialCarrier(){
        for(int kind=0;kind<9;kind++){
            SourceDocument source=carrierSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");ObjectNode first=(ObjectNode)member(scopes,"m2");ArrayNode ranges=(ArrayNode)first.path("retainedBlockRanges");ObjectNode range=(ObjectNode)ranges.get(0);
            if(kind==0)ranges.add(range.deepCopy());if(kind==1)range.put("parentEffectiveTextSha256",VettingCorpus.hash("other carrier"));if(kind==2)range.put("startOffset",1);if(kind==3)range.put("offsetUnit","bytes");if(kind==4)range.put("textChannel","originalText");if(kind==5)first.set("sourcePosition",position(2));
            if(kind==6){List<DocumentBlock> blocks=VettingCorpus.blocks(source);for(DocumentBlock block:blocks)if("b2".equals(block.getId())){String duplicate=block.getEffectiveText()+"\n"+first.path("textObservation").path("text").asText();block.setText(duplicate);for(JsonNode m:scopes.path("members"))for(JsonNode r:m.path("retainedBlockRanges"))((ObjectNode)r).put("parentEffectiveTextSha256",VettingCorpus.hash(duplicate));}source.setStructuredContentJson(JsonUtils.write(blocks));}
            if(kind==7){ObjectNode tail=(ObjectNode)member(scopes,"m4");((ObjectNode)tail.path("textObservation")).put("text",first.path("textObservation").path("text").asText());tail.set("retainedBlockRanges",first.path("retainedBlockRanges").deepCopy());}
            if(kind==8){ArrayNode members=(ArrayNode)scopes.path("members");JsonNode earlier=members.get(1),later=members.get(3);members.set(1,later);members.set(3,earlier);}
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"Unsafe paragraph-carrier range must not expand, case "+kind);assertFalse(selected.path("issues").isEmpty(),"Unsafe paragraph-carrier mapping must remain explicit unknown, case "+kind);
        }
    }
    @Test void wrongSourcePartStoryDuplicateReferenceAndDanglingParentsNeverExpand(){
        for(int kind=0;kind<9;kind++){
            SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");
            if(kind==0)scopes.put("sourceSha256",VettingCorpus.hash("wrong original"));
            if(kind==1)((ObjectNode)scopes.path("members").get(3).path("sourcePosition")).put("partSha256",VettingCorpus.hash("other part"));
            if(kind==2)((ObjectNode)scopes.path("members").get(3)).put("storyKind","header");
            if(kind==3){ObjectNode duplicate=scopes.path("ranges").get(1).deepCopy();duplicate.put("id","duplicate-clause");((ArrayNode)scopes.path("ranges")).add(duplicate);}
            if(kind==4)((ObjectNode)scopes.path("ranges").get(1)).put("parentId","missing-parent");
            if(kind==5)((ObjectNode)scopes.path("ranges").get(1)).remove("endBoundary");
            if(kind==6)((ObjectNode)scopes.path("ranges").get(1).path("endBoundary")).put("memberId","m7");
            if(kind==7)((ObjectNode)scopes.path("ranges").get(0)).set("orderedMemberIds",JsonUtils.mapper().valueToTree(Collections.singletonList("m1")));
            if(kind==8){ObjectNode boundary=(ObjectNode)scopes.path("ranges").get(1).path("endBoundary");boundary.put("kind","native_container_end").remove("memberId");ObjectNode foreign=position(8);foreign.put("nodePath",BODY+"/"+NS+"tbl[2]/"+NS+"tr[1]/"+NS+"tc[9]");boundary.set("sourcePosition",foreign);}
            source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode selected=hit(source).getCandidateStructure().path("sourceClauseScopes");
            assertTrue(selected.path("ranges").isEmpty(),"Unsafe source clause inventory must not expand, case "+kind);assertFalse(selected.path("issues").isEmpty(),"Unknown must be explicit, case "+kind);
        }
    }
    private static List<String> ids(JsonNode ranges){List<String> out=new ArrayList<>();for(JsonNode r:ranges)out.add(r.path("id").asText());return out;}
    private static List<String> strings(JsonNode values){List<String> out=new ArrayList<>();for(JsonNode value:values)out.add(value.asText());return out;}
    private static JsonNode member(JsonNode scopes,String id){for(JsonNode member:scopes.path("members"))if(id.equals(member.path("id").asText()))return member;throw new AssertionError(id);}
    private static VettingCorpus.Chunk hit(SourceDocument source){return VettingCorpus.chunks(Collections.singletonList(source)).stream().filter(c->c.getParts().stream().anyMatch(p->"b2".equals(p.getBlockId()))).findFirst().get();}
    private static SourceDocument source(){
        SourceDocument source=new SourceDocument();source.setId(77L);source.setProjectId("authored-word-scope");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setFileKey("PRE");source.setFileName("authored.docx");source.setSourceSha256(SHA);source.setParseStatus("PARSED");source.setPageCount(0);
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",true);ObjectNode outline=coverage.putObject("nativeDocxOutline");outline.put("version","docx-native-outline-v1");outline.putArray("headings");ObjectNode scopes=outline.putObject("sourceClauseScopes");scopes.put("version","docx-source-clause-scopes-v1");scopes.put("sourceSha256",SHA);scopes.put("familyCandidate","PRE");scopes.putArray("issues");ArrayNode members=scopes.putArray("members");
        List<DocumentBlock> blocks=new ArrayList<>();String longHit="HIT "+String.join(" ",Collections.nCopies(400,"source"));String[] text={"CHAPTER",longHit,"","CHILD_TAIL","PARENT_SIBLING","SIBLING_TAIL","SEPARATE_CONTEXT","OUTSIDE_CHAPTER"};
        for(int i=0;i<text.length;i++){
            ObjectNode m=members.addObject();m.put("id","m"+(i+1));m.put("storyKind","body");m.put("storyRootNodePath",BODY);m.set("sourcePosition",position(i+1));ObjectNode observation=m.putObject("textObservation");for(String channel:Arrays.asList("text","originalText","deletedText","strikeText"))observation.put(channel,channel.equals("text")||channel.equals("originalText")?text[i]:"");ArrayNode retained=m.putArray("retainedBlockIds");
            if(!text[i].isEmpty()){retained.add("b"+(i+1));DocumentBlock block=new DocumentBlock();block.setId("b"+(i+1));block.setLocation("body/"+i);block.setKind("paragraph");block.setSource("docx");block.setText(text[i]);block.setSourcePosition(position(i+1));blocks.add(block);}
        }
        ArrayNode ranges=scopes.putArray("ranges");range(ranges,"chapter","PRE1",null,Arrays.asList("m1","m2","m3","m4","m5","m6"));range(ranges,"child-a","PRE1(a)","chapter",Arrays.asList("m2","m3","m4"));range(ranges,"child-b","PRE1(b)","chapter",Arrays.asList("m5","m6"));
        source.setTextContent(String.join("\n",text));source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static SourceDocument carrierSource(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode scopes=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes");String row=BODY+"/"+NS+"tbl[1]/"+NS+"tr[1]",cell=row+"/"+NS+"tc[1]";JsonNode first=member(scopes,"m2");String paragraph=first.path("textObservation").path("text").asText(),carrier=paragraph+"\n\nCHILD_TAIL";
        for(int n=2;n<=4;n++){ObjectNode m=(ObjectNode)member(scopes,"m"+n);((ObjectNode)m.path("textObservation")).put("status","observed_parser_paragraph_channels");ObjectNode p=position(n);p.put("nodePath",cell+"/"+NS+"p["+(n-1)+"]");m.set("sourcePosition",p);m.putArray("retainedBlockIds").add("b2");ArrayNode mappings=m.putArray("retainedBlockRanges");if(n!=3){int start=n==2?0:paragraph.length()+2;ObjectNode mapping=mappings.addObject();mapping.put("blockId","b2");mapping.put("startOffset",start);mapping.put("endOffset",start+m.path("textObservation").path("text").asText().length());mapping.put("offsetUnit","utf16");mapping.put("textChannel","text");mapping.put("parentEffectiveTextSha256",VettingCorpus.hash(carrier));}}
        ((ObjectNode)scopes.path("ranges").get(1).path("startBoundary")).set("sourcePosition",member(scopes,"m2").path("sourcePosition"));List<DocumentBlock> blocks=VettingCorpus.blocks(source);blocks.removeIf(b->"b4".equals(b.getId()));for(DocumentBlock block:blocks)if("b2".equals(block.getId())){block.setText(carrier);block.setLocation("body/carrier/table-row/0");ObjectNode p=position(2);p.put("nodePath",row);block.setSourcePosition(p);}source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);return source;
    }
    private static void range(ArrayNode ranges,String id,String reference,String parent,List<String> ids){ObjectNode r=ranges.addObject();r.put("id",id);r.put("reference",reference);if(parent==null)r.putNull("parentId");else r.put("parentId",parent);r.put("status","source_syntax_complete");r.put("scopeComplete",true);r.set("orderedMemberIds",JsonUtils.mapper().valueToTree(ids));r.putArray("contextMemberIds").add("m7");r.putArray("contextObservations").addObject().put("memberId","m7").put("reason","source_note_context");r.putObject("startBoundary").put("kind","observed_source_member").put("memberId",ids.get(0)).set("sourcePosition",position(Integer.parseInt(ids.get(0).substring(1))));r.putObject("endBoundary").put("kind","next_peer").put("memberId","m8").set("sourcePosition",position(8));}
    private static ObjectNode position(int paragraph){ObjectNode p=JsonUtils.mapper().createObjectNode();p.put("kind","docx_native");p.put("sourceSha256",SHA);p.put("partName","word/document.xml");p.put("partSha256",PART);p.put("nodePath",BODY+"/"+NS+"p["+paragraph+"]");return p;}
}
