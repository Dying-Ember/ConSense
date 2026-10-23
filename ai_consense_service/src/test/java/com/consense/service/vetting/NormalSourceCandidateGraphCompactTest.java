package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalSourceCandidateGraphCompactTest {
    private static final String REF="$nativeParagraph",SECTION_REF="$nativeSection",NS="{http://schemas.openxmlformats.org/wordprocessingml/2006/main}";
    private static final String BODY="/"+NS+"document[1]/"+NS+"body[1]";

    @Test void sharedParagraphPropertiesRemainOnceAndSelectedViewsRestoreTheExactContent()throws Exception{
        SourceDocument source=source();String text=source.getTextContent(),structure=source.getStructuredContentJson(),coverage=source.getParseCoverageJson();
        ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source),pool=graph.path("nativeDocxOutline").path("paragraphs");
        assertEquals(NormalSourceCandidateGraph.VERSION,graph.path("producerVersion").asText());
        assertTrue(source.getNormalCandidateJson().length()<JsonUtils.write(legacy).length()/2,"Repeated layout observations must not be stored in each source view");
        assertEquals(legacy.path("nativeDocxOutline").path("headings"),graph.path("nativeDocxOutline").path("headings"));
        for(int i=0;i<pool.size();i++){
            assertEquals(Collections.singleton(REF),keys(graph.path("units").get(i).path("parts").get(0).path("readingContext").path("nativeDocxParagraph")));
            assertEquals(i,graph.path("nativeDocxOutline").path("sourceClauseScopes").path("members").get(i).path("paragraphProperties").path(REF).asInt());
            assertEquals(JsonUtils.write(legacy.path("units").get(i).path("parts")),JsonUtils.write(NormalSourceCandidateGraph.expandedParagraphContexts(graph,graph.path("units").get(i).path("parts"))));
        }
        VettingCorpus.Chunk selected=wholeChunk(source,graph);JsonNode view=NormalSourceCandidateGraph.selected(graph,selected);
        assertEquals(pool.get(0),view.path("units").get(0).path("parts").get(0).path("readingContext").path("nativeDocxParagraph"));
        JsonNode scopes=NormalSourceCandidateGraph.wordSourceScopeContext(graph,JsonUtils.mapper().valueToTree(selected.getParts()));
        assertTrue(scopes.path("issues").isEmpty(),scopes.toString());assertEquals(4,scopes.path("members").size());
        for(JsonNode member:scopes.path("members"))assertEquals(pool.get(Integer.parseInt(member.path("id").asText().substring(1))),member.path("paragraphProperties"));
        JsonNode model=NormalSourceCandidateGraph.selectedForModel(graph,selected,new NormalSourceCandidateGraph.NativeDocxTableIndex(source,graph));
        assertEquals(1,model.path("units").size());assertEquals(pool.get(0),model.path("units").get(0).path("members").get(0).path("paragraphProperties"));
        assertEquals(text,source.getTextContent());assertEquals(structure,source.getStructuredContentJson());assertEquals(coverage,source.getParseCoverageJson());
        String saved=source.getNormalCandidateJson();NormalSourceCandidateGraph.capture(source);assertEquals(saved,source.getNormalCandidateJson());
    }

    @Test void partialSourceSlicesRestoreOnlyTheirSelectedParagraphContext(){
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        VettingCorpus.Chunk selected=wholeChunk(source,graph);VettingCorpus.Part first=selected.getParts().get(0);first.setStartOffset(1);first.setEndOffset(4);first.setText(first.getText().substring(1,4));selected.setParts(Collections.singletonList(first));
        JsonNode view=NormalSourceCandidateGraph.selected(graph,selected),part=view.path("units").get(0).path("parts").get(0);
        assertEquals(graph.path("nativeDocxOutline").path("paragraphs").get(0),part.path("readingContext").path("nativeDocxParagraph"));
        assertEquals("selected_source_range_only",view.path("units").get(0).path("closureStatus").asText());assertEquals(first.getText(),part.path("text").asText());
    }

    @Test void sharedSectionsRemainOnceAndSelectedSlicesRestoreExactLegacyParts()throws Exception{
        SourceDocument source=source();ObjectNode first=JsonUtils.mapper().createObjectNode();first.put("story","body");first.putObject("pageSetup").put("leftMargin",1440).put("retained",String.join("",Collections.nCopies(200,"source-section-layout-")));
        ObjectNode second=first.deepCopy();((ObjectNode)second.path("pageSetup")).put("leftMargin",1800);
        setSections(source,Arrays.asList(first,first.deepCopy(),first.deepCopy(),first.deepCopy(),first.deepCopy(),second));
        String structure=source.getStructuredContentJson(),coverage=source.getParseCoverageJson(),text=source.getTextContent();
        ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        assertFalse(legacy.has("nativeDocxSections"));assertEquals(2,graph.path("nativeDocxSections").size());assertEquals(first,graph.path("nativeDocxSections").get(0));assertEquals(second,graph.path("nativeDocxSections").get(1));
        assertFalse(graph.path("nativeDocxSections").get(0).has("sourcePosition"));
        for(int i=0;i<graph.path("units").size();i++){
            JsonNode part=graph.path("units").get(i).path("parts").get(0),ref=part.path("readingContext").path("nativeDocxSection");
            assertEquals(Collections.singleton(SECTION_REF),keys(ref));assertEquals(i==5?1:0,ref.path(SECTION_REF).asInt(-1));
            assertEquals(JsonUtils.write(legacy.path("units").get(i).path("parts")),JsonUtils.write(NormalSourceCandidateGraph.expandedParagraphContexts(graph,graph.path("units").get(i).path("parts"))));
        }
        JsonNode selected=NormalSourceCandidateGraph.selected(graph,wholeChunk(source,graph));
        for(int i=0;i<selected.path("units").size();i++)assertEquals(JsonUtils.write(legacy.path("units").get(i).path("parts")),JsonUtils.write(selected.path("units").get(i).path("parts")));
        VettingCorpus.Chunk slice=wholeChunk(source,graph);VettingCorpus.Part part=slice.getParts().get(0);part.setStartOffset(1);part.setEndOffset(4);part.setText(part.getText().substring(1,4));slice.setParts(Collections.singletonList(part));
        assertEquals(first,NormalSourceCandidateGraph.selected(graph,slice).path("units").get(0).path("parts").get(0).path("readingContext").path("nativeDocxSection"));
        assertEquals(structure,source.getStructuredContentJson());assertEquals(coverage,source.getParseCoverageJson());assertEquals(text,source.getTextContent());String saved=source.getNormalCandidateJson();NormalSourceCandidateGraph.capture(source);assertEquals(saved,source.getNormalCandidateJson());
    }

    @Test void invalidSectionReferencesAndMixedNestedTargetsAreRejected(){
        SourceDocument source=source();setSections(source,Collections.nCopies(6,JsonUtils.parse("{\"pageSetup\":{\"leftMargin\":1440}}")));NormalSourceCandidateGraph.capture(source);JsonNode original=NormalSourceCandidateGraph.requireBound(source);
        for(JsonNode ref:Arrays.asList(JsonUtils.parse("{\"$nativeSection\":-1}"),JsonUtils.parse("{\"$nativeSection\":99}"),JsonUtils.parse("{\"$nativeSection\":0.0}"),JsonUtils.parse("{\"$nativeSection\":\"0\"}"),JsonUtils.parse("{\"$nativeSection\":true}"),JsonUtils.parse("{\"$nativeSection\":null}"),JsonUtils.parse("{\"$nativeSection\":2147483648}"),JsonUtils.parse("{\"$nativeSection\":0,\"extra\":true}"),JsonUtils.parse("{\"$nativeSection\":0,\"$nativeParagraph\":0}"))){
            ObjectNode graph=original.deepCopy();context(graph).set("nativeDocxSection",ref);
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(graph),ref.toString());assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.expandedParagraphContexts(graph,context(graph)),ref.toString());
            source.setNormalCandidateJson(JsonUtils.write(graph));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
        }
        for(JsonNode target:Arrays.asList(JsonUtils.mapper().nullNode(),JsonUtils.parse("[]"),JsonUtils.parse("true"),JsonUtils.parse("\"section\""),JsonUtils.parse("{\"$nativeParagraph\":0}"),JsonUtils.parse("{\"nested\":{\"$nativeSection\":0}}"),JsonUtils.parse("{\"nested\":{\"$nativeParagraph\":0}}"))){
            ObjectNode graph=original.deepCopy();((ArrayNode)graph.path("nativeDocxSections")).set(0,target);
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(graph));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.expandedParagraphContexts(graph,context(graph)));
        }
        ObjectNode missing=original.deepCopy();missing.remove("nativeDocxSections");assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(missing));
        ObjectNode mixed=original.deepCopy();((ObjectNode)mixed.path("nativeDocxOutline").path("paragraphs").get(0)).putObject("nested").put(SECTION_REF,0);assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(mixed));
    }

    @Test void legacyAndNonobjectSectionObservationsRemainInline()throws Exception{
        SourceDocument source=source();List<JsonNode> sections=Arrays.asList(JsonUtils.parse("null"),JsonUtils.parse("\"unknown\""),JsonUtils.parse("[]"),JsonUtils.parse("false"),JsonUtils.parse("7"),JsonUtils.parse("[\"literal\"]"));setSections(source,sections);
        ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);String before=JsonUtils.write(legacy);source.setNormalCandidateJson(before);assertEquals(before,JsonUtils.write(NormalSourceCandidateGraph.requireBound(source)));assertEquals(before,source.getNormalCandidateJson());assertFalse(legacy.has("nativeDocxSections"));
        NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);assertFalse(graph.has("nativeDocxSections"));
        for(int i=0;i<sections.size();i++){
            assertEquals(sections.get(i),graph.path("units").get(i).path("parts").get(0).path("readingContext").path("nativeDocxSection"));
            assertEquals(JsonUtils.write(legacy.path("units").get(i).path("parts")),JsonUtils.write(NormalSourceCandidateGraph.expandedParagraphContexts(graph,graph.path("units").get(i).path("parts"))));
        }
    }

    @Test void physicalWordTableModelRestoresTheCompactPartContexts()throws Exception{
        SourceDocument source=NormalSourceCandidateGraphTest.ordinaryWordSource();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ObjectNode outline=coverage.putObject("nativeDocxOutline");outline.putArray("headings");ArrayNode pool=outline.putArray("paragraphs");List<DocumentBlock> blocks=VettingCorpus.blocks(source);
        for(DocumentBlock block:blocks){ObjectNode property=pool.addObject();property.set("sourcePosition",block.getSourcePosition().deepCopy());property.put("status","authored_table_row_property");ObjectNode context=JsonUtils.mapper().createObjectNode();context.set("nativeDocxParagraph",property.deepCopy());context.putArray("nativeDocxParagraphs").add(property.deepCopy());context.putObject("nativeDocxSection").put("sameObservedSection","table");block.setReadingContext(context);}
        source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        assertEquals(0,context(graph).path("nativeDocxParagraph").path(REF).asInt(-1));JsonNode model=NormalSourceCandidateGraph.selectedForModel(graph,wholeChunk(source,graph));
        assertEquals(1,model.path("units").size());assertEquals("ordinary_docx_table",model.path("units").get(0).path("kind").asText());ArrayNode expected=JsonUtils.mapper().createArrayNode();for(JsonNode unit:legacy.path("units"))for(JsonNode part:unit.path("parts"))expected.add(part);
        assertEquals(JsonUtils.write(expected),JsonUtils.write(model.path("units").get(0).path("parts")));assertFalse(JsonUtils.write(model).contains(REF));assertFalse(JsonUtils.write(model).contains(SECTION_REF));
    }

    @Test void compactNativeBulletPropertiesPreserveNonordinalPeerChecks()throws Exception{
        Method fixture=WordSourceClauseConsumptionTest.class.getDeclaredMethod("bulletSource",String.class);fixture.setAccessible(true);
        for(String glyph:Arrays.asList("•","-")){
            SourceDocument source=(SourceDocument)fixture.invoke(null,glyph);installParagraphPool(source);ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);VettingCorpus.Chunk hit=VettingCorpus.chunks(Collections.singletonList(source)).stream().filter(chunk->chunk.getParts().stream().anyMatch(part->"b2".equals(part.getBlockId()))).findFirst().orElseThrow(AssertionError::new);
            JsonNode selected=NormalSourceCandidateGraph.wordSourceScopeContext(graph,JsonUtils.mapper().valueToTree(hit.getParts()));assertTrue(selected.path("issues").isEmpty(),selected.toString());assertEquals(2,selected.path("ranges").size());
            JsonNode before=NormalSourceCandidateGraph.wordSourceScopeContext(legacy,JsonUtils.mapper().valueToTree(hit.getParts()));for(String field:Arrays.asList("ranges","members","issues"))assertEquals(before.path(field),selected.path(field));
            boolean found=false;for(JsonNode member:graph.path("nativeDocxOutline").path("sourceClauseScopes").path("members"))if("m2".equals(member.path("id").asText())){assertTrue(member.path("paragraphProperties").has(REF));found=true;}assertTrue(found);
            assertEquals(2,new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit).path("units").size());
            ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());for(JsonNode member:coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("members"))if("m2".equals(member.path("id").asText()))((ObjectNode)member.path("paragraphProperties").path("layoutEvidence").path("numbering")).put("label",glyph.equals("•")?"-":"•");source.setParseCoverageJson(JsonUtils.write(coverage));installParagraphPool(source);NormalSourceCandidateGraph.capture(source);graph=NormalSourceCandidateGraph.requireBound(source);
            JsonNode rejected=NormalSourceCandidateGraph.wordSourceScopeContext(graph,JsonUtils.mapper().valueToTree(hit.getParts()));assertTrue(rejected.path("ranges").isEmpty());assertFalse(rejected.path("issues").isEmpty());
        }
    }

    @Test void unmatchedAndAmbiguousPropertiesRemainCompleteInsteadOfPickingAnArbitraryPoolEntry(){
        SourceDocument source=source();ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson());ArrayNode pool=(ArrayNode)coverage.path("nativeDocxOutline").path("paragraphs");pool.add(pool.get(0).deepCopy());
        JsonNode expected=pool.get(0).deepCopy();ObjectNode unmatched=(ObjectNode)coverage.path("nativeDocxOutline").path("sourceClauseScopes").path("members").get(1).path("paragraphProperties");unmatched.put("unmatchedObservation","retain me");
        source.setParseCoverageJson(JsonUtils.write(coverage));NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        assertEquals(expected,graph.path("units").get(0).path("parts").get(0).path("readingContext").path("nativeDocxParagraph"));
        assertEquals(expected,graph.path("nativeDocxOutline").path("sourceClauseScopes").path("members").get(0).path("paragraphProperties"));
        assertEquals(unmatched,graph.path("nativeDocxOutline").path("sourceClauseScopes").path("members").get(1).path("paragraphProperties"));
    }

    @Test void invalidReferencesAndNestedOrCyclicPoolEntriesAreRejectedWithoutExpandingTheGraph(){
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);JsonNode original=NormalSourceCandidateGraph.requireBound(source);
        List<JsonNode> invalid=Arrays.asList(JsonUtils.parse("{\"$nativeParagraph\":-1}"),JsonUtils.parse("{\"$nativeParagraph\":99}"),
                JsonUtils.parse("{\"$nativeParagraph\":0.0}"),JsonUtils.parse("{\"$nativeParagraph\":\"0\"}"),JsonUtils.parse("{\"$nativeParagraph\":true}"),
                JsonUtils.parse("{\"$nativeParagraph\":null}"),JsonUtils.parse("{\"$nativeParagraph\":2147483648}"),JsonUtils.parse("{\"$nativeParagraph\":0,\"extra\":true}"));
        for(JsonNode ref:invalid){
            ObjectNode graph=original.deepCopy();context(graph).set("nativeDocxParagraph",ref);
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(graph),ref.toString());
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.expandedParagraphContexts(graph,context(graph)),ref.toString());
            source.setNormalCandidateJson(JsonUtils.write(graph));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
        }
        for(JsonNode target:Arrays.asList(JsonUtils.mapper().nullNode(),JsonUtils.mapper().createArrayNode(),JsonUtils.parse("{}"),JsonUtils.parse("{\"sourcePosition\":null}"),JsonUtils.parse("{\"sourcePosition\":{},\"$nativeParagraph\":0}"),JsonUtils.parse("{\"sourcePosition\":{},\"nested\":{\"$nativeParagraph\":1}}"))){
            ObjectNode graph=original.deepCopy();((ArrayNode)graph.path("nativeDocxOutline").path("paragraphs")).set(0,target);
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(graph));
            assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.expandedParagraphContexts(graph,context(graph)));
        }
        ObjectNode missing=original.deepCopy();((ObjectNode)missing.path("nativeDocxOutline")).remove("paragraphs");assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(missing));
        ObjectNode arrayRef=original.deepCopy();((ArrayNode)context(arrayRef).path("nativeDocxParagraphs")).set(0,invalid.get(0));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(arrayRef));
        ObjectNode memberRef=original.deepCopy();((ObjectNode)memberRef.path("nativeDocxOutline").path("sourceClauseScopes").path("members").get(0)).set("paragraphProperties",invalid.get(0));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.validateParagraphReferences(memberRef));
    }

    @Test void legacyBindingRetainsItsVersionAndStrictSourceValidationWhileSmallLongValuesRoundTrip()throws Exception{
        SourceDocument source=source();ObjectNode legacy=build(source,NormalSourceCandidateGraph.LEGACY_VERSION);String saved=JsonUtils.write(legacy);source.setNormalCandidateJson(saved);
        assertEquals(JsonUtils.parse(saved),NormalSourceCandidateGraph.requireBound(source));assertEquals(saved,source.getNormalCandidateJson());
        assertEquals(NormalSourceCandidateGraph.LEGACY_VERSION,NormalSourceCandidateGraph.requireBound(source).path("producerVersion").asText());
        assertTrue(NormalSourceCandidateGraph.supportedVersion(NormalSourceCandidateGraph.LEGACY_VERSION));assertTrue(NormalSourceCandidateGraph.supportedVersion(NormalSourceCandidateGraph.VERSION));assertFalse(NormalSourceCandidateGraph.supportedVersion("future"));
        for(JsonNode value:Arrays.asList(JsonUtils.mapper().getNodeFactory().numberNode(7.0),JsonUtils.mapper().getNodeFactory().textNode("7"),JsonUtils.mapper().getNodeFactory().booleanNode(true))){
            ObjectNode changed=(ObjectNode)JsonUtils.parse(saved);((ObjectNode)changed.path("units").get(0).path("parts").get(0).path("wordNumbering").get(0)).set("value",value);
            source.setNormalCandidateJson(JsonUtils.write(changed));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
        }
        source.setNormalCandidateJson(saved);source.setTextContent(source.getTextContent()+" changed");assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
    }

    @Test void compactPoolOrReferenceTamperingCannotBeSealedByChangingTheDeclaredGenerationHash(){
        SourceDocument source=source();NormalSourceCandidateGraph.capture(source);JsonNode original=NormalSourceCandidateGraph.requireBound(source);
        for(int defect=0;defect<3;defect++){
            ObjectNode graph=original.deepCopy();if(defect==0)context(graph).putObject("nativeDocxParagraph").put(REF,1);
            if(defect==1)((ObjectNode)graph.path("nativeDocxOutline").path("paragraphs").get(0)).put("status","tampered");
            if(defect==2)graph.put("producerVersion","unsupported");graph.remove("generationSha256");graph.put("generationSha256",VettingCorpus.hash(JsonUtils.write(graph)));
            source.setNormalCandidateJson(JsonUtils.write(graph));assertThrows(IllegalArgumentException.class,()->NormalSourceCandidateGraph.requireBound(source));
        }
    }

    private static ObjectNode build(SourceDocument source,String version)throws Exception{Method method=NormalSourceCandidateGraph.class.getDeclaredMethod("build",SourceDocument.class,String.class);method.setAccessible(true);return (ObjectNode)method.invoke(null,source,version);}
    private static void setSections(SourceDocument source,List<JsonNode> sections){List<DocumentBlock> blocks=VettingCorpus.blocks(source);assertEquals(blocks.size(),sections.size());for(int i=0;i<blocks.size();i++)((ObjectNode)blocks.get(i).getReadingContext()).set("nativeDocxSection",sections.get(i).deepCopy());source.setStructuredContentJson(JsonUtils.write(blocks));}
    private static void installParagraphPool(SourceDocument source){ObjectNode coverage=(ObjectNode)JsonUtils.parse(source.getParseCoverageJson()),outline=(ObjectNode)coverage.path("nativeDocxOutline");ArrayNode pool=outline.putArray("paragraphs");for(JsonNode member:outline.path("sourceClauseScopes").path("members"))if(member.path("paragraphProperties").isObject()){ObjectNode property=(ObjectNode)member.path("paragraphProperties");if(!property.has("sourcePosition"))property.set("sourcePosition",member.path("sourcePosition").deepCopy());pool.add(property.deepCopy());}source.setParseCoverageJson(JsonUtils.write(coverage));}
    private static ObjectNode context(JsonNode graph){return (ObjectNode)graph.path("units").get(0).path("parts").get(0).path("readingContext");}
    private static Set<String> keys(JsonNode value){Set<String> keys=new HashSet<>();value.fieldNames().forEachRemaining(keys::add);return keys;}
    private static VettingCorpus.Chunk wholeChunk(SourceDocument source,JsonNode graph){
        VettingCorpus.Chunk chunk=new VettingCorpus.Chunk();List<VettingCorpus.Part> parts=new ArrayList<>();List<String> text=new ArrayList<>();
        for(JsonNode unit:graph.path("units"))for(JsonNode actual:unit.path("parts")){
            VettingCorpus.Part part=new VettingCorpus.Part();part.setBlockId(actual.path("blockId").asText());part.setAnchor(actual.path("anchor").asText());part.setText(actual.path("text").asText());part.setStartOffset(0);part.setEndOffset(part.getText().length());part.setSourcePosition(actual.path("sourcePosition").deepCopy());part.setExtractionSource("docx");parts.add(part);text.add(part.getText());
        }
        chunk.setParts(parts);chunk.setContent(String.join("\n",text));chunk.setDocumentId(String.valueOf(source.getId()));chunk.setSourceHash(VettingCorpus.sourceHash(source));chunk.setRole("tender");chunk.setOriginalSourceSha256(source.getSourceSha256());chunk.setCandidateStructure(graph);return chunk;
    }
    private static SourceDocument source(){
        SourceDocument source=new SourceDocument();source.setId(770L);source.setProjectId("compact-source-fixture");source.setFileName("authored.docx");source.setReviewRole("tender");source.setSourceSha256(VettingCorpus.hash("authored original"));source.setParseStatus("PARSED");
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.put("complete",true);ObjectNode outline=coverage.putObject("nativeDocxOutline");outline.put("version","docx-native-outline-v1");ArrayNode pool=outline.putArray("paragraphs"),headings=outline.putArray("headings");
        ObjectNode scopes=outline.putObject("sourceClauseScopes");scopes.put("version","docx-source-clause-scopes-v1");scopes.put("sourceSha256",source.getSourceSha256());scopes.putNull("familyCandidate");ArrayNode members=scopes.putArray("members");scopes.putArray("issues");
        List<DocumentBlock> blocks=new ArrayList<>();String[] texts={"INTRODUCTION","OBLIGATION","","SEPARATE_CONTEXT","OUTSIDE","TAIL"};
        for(int i=0;i<texts.length;i++){
            ObjectNode position=JsonUtils.mapper().createObjectNode();position.put("kind","docx_native").put("sourceSha256",source.getSourceSha256()).put("partName","word/document.xml").put("partSha256",VettingCorpus.hash("authored part")).put("nodePath",BODY+"/"+NS+"p["+(i+1)+"]");
            ObjectNode property=pool.addObject();property.set("sourcePosition",position.deepCopy());property.put("status","outline_not_declared");property.putObject("layoutEvidence").put("retainedDeclaration",String.join("",Collections.nCopies(200,"source-layout-declaration-"))).putObject("numbering").put("numbered",false);
            DocumentBlock block=new DocumentBlock();block.setId("b"+i);block.setLocation("body/"+i);block.setKind("paragraph");block.setSource("docx");block.setText(texts[i]);block.setSourcePosition(position.deepCopy());ObjectNode context=JsonUtils.mapper().createObjectNode();context.set("nativeDocxParagraph",property.deepCopy());context.putArray("nativeDocxParagraphs").add(property.deepCopy());context.putObject("nativeDocxOutline").putArray("ancestorOrdinals").add(0);block.setReadingContext(context);
            if(i==0){DocumentBlock.WordNumbering number=new DocumentBlock.WordNumbering();number.setValue(7L);block.getWordNumbering().add(number);}blocks.add(block);
            ObjectNode member=members.addObject();member.put("id","m"+i).put("storyKind","body").put("storyRootNodePath",BODY);member.set("sourcePosition",position.deepCopy());member.set("paragraphProperties",property.deepCopy());member.putArray("retainedBlockIds").add(block.getId());member.putObject("textObservation").put("status","observed_parser_paragraph_channels").put("text",texts[i]);
        }
        ObjectNode heading=headings.addObject();heading.put("ordinalZeroBased",0);heading.set("properties",pool.get(0).deepCopy());
        ObjectNode range=scopes.putArray("ranges").addObject();range.put("id","root").put("reference","PRE1").putNull("parentId");range.put("status","source_syntax_complete").put("scopeComplete",true);range.putArray("orderedMemberIds").add("m0").add("m1").add("m2");range.putArray("contextMemberIds").add("m3");range.putObject("startBoundary").put("kind","observed_source_member").put("memberId","m0").set("sourcePosition",members.get(0).path("sourcePosition").deepCopy());range.putObject("endBoundary").put("kind","next_peer").put("memberId","m4").set("sourcePosition",members.get(4).path("sourcePosition").deepCopy());
        source.setTextContent(String.join("\n",texts));source.setStructuredContentJson(JsonUtils.write(blocks));source.setParseCoverageJson(JsonUtils.write(coverage));return source;
    }
}
