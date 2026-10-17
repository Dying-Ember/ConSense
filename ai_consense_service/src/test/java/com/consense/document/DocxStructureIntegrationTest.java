package com.consense.document;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.ocr.OcrClient;
import com.consense.domain.SourceDocument;
import com.consense.service.vetting.NormalSourceCandidateGraph;
import com.consense.service.vetting.VettingCorpus;
import com.consense.service.vetting.VettingSourceMaterial;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocxStructureIntegrationTest {
    private static final String TICK = "<w:r><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r>";

    @Test void nativeHeaderFooterReferencesKeepTypedInheritanceAndRawConfigurationThroughSlices() throws Exception {
        byte[] bytes=sectionStoryFixture();DocumentParser.ParsedDocument parsed=parse(bytes);
        JsonNode inventory=parsed.getCoverage().getNativeDocxSections(),sections=inventory.path("sections");
        assertEquals(2,sections.size());assertEquals(0,parsed.getPageCount());
        List<DocumentBlock> body=new ArrayList<>();for(DocumentBlock block:parsed.getBlocks())if(block.getLocation().startsWith("body/"))body.add(block);
        assertEquals(2,body.size());assertEquals("First body "+String.join("",Collections.nCopies(1800,"x")),body.get(0).getText());
        assertEquals("Second body unchanged",body.get(1).getText());assertNotEquals(sections.get(0).path("sectionId"),sections.get(1).path("sectionId"));
        assertEquals(sections,parse(bytes).getCoverage().getNativeDocxSections().path("sections"));
        boolean emptyFooterLoaded=false;
        for(JsonNode part:parsed.getCoverage().getNativeDocxTables().path("parts"))if("word/footer-default.xml".equals(part.path("partName").asText())){
            emptyFooterLoaded=true;assertEquals(1,part.path("loadedRoots").size());assertTrue(part.path("members").isEmpty());
        }
        assertTrue(emptyFooterLoaded,"A textless footer remains an observed loaded part, without asserting visible blank");
        assertEquals(6,sections.get(0).path("headerFooterBindings").size(),"Native section output must retain six typed header/footer slots; unchanged source section baseline="+inventory);
        JsonNode setting=inventory.path("headerFooterSettings").path("evenAndOddHeaders");
        assertTrue(setting.path("present").asBoolean());assertEquals("true",setting.path("rawValue").asText());assertTrue(setting.path("valueCandidate").asBoolean());
        assertNativeFixturePosition(setting.path("sourcePosition"),bytes,"word/settings.xml","/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}settings[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}evenAndOddHeaders[1]");
        String origin=sections.get(0).path("sectionId").asText();
        for(int sectionIndex=0;sectionIndex<2;sectionIndex++){
            JsonNode section=sections.get(sectionIndex),bindings=section.path("headerFooterBindings"),title=section.path("titlePg");
            assertEquals(6,bindings.size());assertTrue(title.path("present").asBoolean());assertEquals(sectionIndex==0?"1":"0",title.path("rawValue").asText());
            assertEquals(sectionIndex==0,title.path("valueCandidate").asBoolean());assertEquals("unknown",section.path("effectiveSectionState").asText());
            assertNativeFixturePosition(title.path("sourcePosition"),bytes,"word/document.xml",section.path("endBoundary").path("sourcePosition").path("nodePath").asText()+"/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}titlePg[1]");
            assertEquals("unknown",section.path("ownerStatus").asText("unknown"));assertTrue(section.path("headerFooterReferences").isArray());
            assertEquals(sectionIndex==0?4:0,section.path("headerFooterReferences").size());Set<String> slots=new HashSet<>();
            if(sectionIndex==0)for(int referenceIndex=0;referenceIndex<4;referenceIndex++)assertEquals(Arrays.asList("rHE","rFD","rHF","rHD").get(referenceIndex),section.path("headerFooterReferences").get(referenceIndex).path("rawRelationshipId").asText());
            for(String kind:Arrays.asList("header","footer"))for(String variant:Arrays.asList("default","first","even")){
                JsonNode binding=storyBinding(bindings,kind,variant);assertTrue(slots.add(kind+"/"+variant));
                assertEquals("unknown",binding.path("effectiveState").asText());boolean target="header".equals(kind)||"default".equals(variant);
                if(!target){
                    assertEquals("no_target",binding.path("status").asText());assertTrue(binding.path("targetPart").isNull());
                    assertTrue(binding.path("originSectionId").isNull());assertTrue(binding.path("inheritedFromSectionId").isNull());continue;
                }
                assertEquals(sectionIndex==0?"explicit_target_loaded":"inherited_target_candidate",binding.path("status").asText());
                assertEquals(origin,binding.path("originSectionId").asText());
                if(sectionIndex==0)assertTrue(binding.path("inheritedFromSectionId").isNull());else assertEquals(origin,binding.path("inheritedFromSectionId").asText());
                String targetName="word/"+kind+"-"+variant+".xml";JsonNode part=binding.path("targetPart"),reference=binding.path("originReference"),relationship=binding.path("relationship");
                assertEquals(targetName,part.path("partName").asText());assertEquals(DocumentParseProbe.sha256(fixturePart(bytes,targetName)),part.path("partSha256").asText());
                assertEquals("{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"+("header".equals(kind)?"hdr":"ftr"),part.path("rootQName").asText());
                assertEquals(variant,reference.path("rawType").asText());String rid="footer".equals(kind)?"rFD":"default".equals(variant)?"rHD":"first".equals(variant)?"rHF":"rHE";
                assertEquals(rid,reference.path("rawRelationshipId").asText());assertEquals(rid,relationship.path("id").asText());
                assertEquals("http://schemas.openxmlformats.org/officeDocument/2006/relationships/"+kind,relationship.path("type").asText());
                assertEquals(kind+"-"+variant+".xml",relationship.path("rawTarget").asText());
                int refIndex="footer".equals(kind)?1:"default".equals(variant)?3:"first".equals(variant)?2:1;
                String endpoint=sections.get(0).path("endBoundary").path("sourcePosition").path("nodePath").asText();
                assertNativeFixturePosition(reference.path("sourcePosition"),bytes,"word/document.xml",endpoint+"/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"+kind+"Reference["+refIndex+"]");
                int relIndex="footer".equals(kind)?3:"default".equals(variant)?2:"first".equals(variant)?1:4;
                assertNativeFixturePosition(relationship.path("sourcePosition"),bytes,"word/_rels/document.xml.rels","/{http://schemas.openxmlformats.org/package/2006/relationships}Relationships[1]/{http://schemas.openxmlformats.org/package/2006/relationships}Relationship["+relIndex+"]");
            }
            assertEquals(6,slots.size());assertEquals(bindings,body.get(sectionIndex).getReadingContext().path("nativeDocxSection").path("headerFooterBindings"));
        }
        SourceDocument source=new SourceDocument();source.setId(921L);source.setProjectId("authored-section-stories");source.setFileName("authored.docx");
        source.setFileKey("SCT");source.setReviewRole("tender");source.setDocumentScope("package");source.setSourceSha256(parsed.getSourceSha256());
        source.setParseStatus(parsed.getParseStatus());source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));
        source=JsonUtils.read(JsonUtils.write(source),SourceDocument.class);NormalSourceCandidateGraph.capture(source);JsonNode graph=NormalSourceCandidateGraph.requireBound(source);
        List<VettingCorpus.Chunk> chunks=VettingCorpus.chunks(Collections.singletonList(source));assertTrue(chunks.size()>1);boolean sliced=false,complete=false;
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))assertBodyStoryBindings(part,sections);
        JsonNode materials=JsonUtils.mapper().valueToTree(VettingSourceMaterial.project(chunks,Collections.singletonList(source)));
        for(JsonNode material:materials){
            for(JsonNode unit:material.path("candidateStructure").path("units"))for(JsonNode part:unit.path("parts")){
                assertBodyStoryBindings(part,sections);if(part.path("readingContext").has("nativeDocxSection")){
                    if("selected_source_range_only".equals(unit.path("closureStatus").asText()))sliced=true;
                    if("complete_actual_source_block".equals(unit.path("closureStatus").asText()))complete=true;
                }
            }
        }
        assertTrue(sliced,"Actual long paragraph slices keep the same small section bindings");assertTrue(complete,"Existing ordinary material retains the complete second body block and its source bindings");
        assertEquals(inventory,JsonUtils.read(JsonUtils.write(parsed.getCoverage()),ParseCoverage.class).getNativeDocxSections());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.get(0).getReadingContext().path("nativeDocxSection").path("headerFooterBindings").get(0)).put("status","mutated_fixture_copy");
        assertNotEquals("mutated_fixture_copy",sections.get(0).path("headerFooterBindings").get(0).path("status").asText());
        assertNotEquals("mutated_fixture_copy",graph.path("units").get(0).path("parts").get(0).path("readingContext").path("nativeDocxSection").path("headerFooterBindings").get(0).path("status").asText());
    }

    @Test void nativeStoryConfigurationKeepsAbsentDefaultFalseAndInvalidValuesDistinct() throws Exception {
        String[] values={null,"","true","1","on","false","0","off","unexpected"};
        for(String value:values){
            String title=value==null?"":value.isEmpty()?"<w:titlePg/>":"<w:titlePg w:val=\""+value+"\"/>";
            String setting=title.replace("titlePg","evenAndOddHeaders");
            JsonNode inventory=parse(storySectionsFixture(setting,title)).getCoverage().getNativeDocxSections();
            JsonNode section=inventory.path("sections").get(0);
            for(JsonNode observation:Arrays.asList(section.path("titlePg"),inventory.path("headerFooterSettings").path("evenAndOddHeaders"))){
                assertEquals(value!=null,observation.path("present").asBoolean());
                assertEquals(value!=null&&value.isEmpty(),observation.path("schemaDefaultCandidate").asBoolean());
                assertEquals(value==null?"absent":"unexpected".equals(value)?"invalid_value":"observed",observation.path("status").asText());
                if(value==null||"unexpected".equals(value))assertTrue(observation.path("valueCandidate").isNull());
                else assertEquals(value.isEmpty()||Arrays.asList("true","1","on").contains(value),observation.path("valueCandidate").asBoolean());
                if(value==null||value.isEmpty())assertTrue(observation.path("rawValue").isNull());else assertEquals(value,observation.path("rawValue").asText());
                assertEquals(value==null?0:1,observation.path("observations").size());
            }
            assertEquals("unknown",section.path("effectiveSectionState").asText());
            for(JsonNode binding:section.path("headerFooterBindings")){assertEquals("no_target",binding.path("status").asText());assertEquals("unknown",binding.path("effectiveState").asText());}
        }
        JsonNode duplicate=parse(storySectionsFixture("<w:evenAndOddHeaders w:val=\"true\"/><w:evenAndOddHeaders w:val=\"false\"/>","<w:titlePg/><w:titlePg w:val=\"0\"/>"))
                .getCoverage().getNativeDocxSections();
        for(JsonNode observation:Arrays.asList(duplicate.path("sections").get(0).path("titlePg"),duplicate.path("headerFooterSettings").path("evenAndOddHeaders"))){
            assertEquals("duplicate_property",observation.path("status").asText());assertEquals(2,observation.path("observations").size());
            assertTrue(observation.path("rawValue").isNull());assertTrue(observation.path("valueCandidate").isNull());assertTrue(observation.path("sourcePosition").isNull());
            assertNotEquals(observation.path("observations").get(0).path("sourcePosition"),observation.path("observations").get(1).path("sourcePosition"));
        }
    }

    @Test void invalidExplicitStoryReferencesBlockFallbackAndPropagateThroughTheImmediatePredecessor() throws Exception {
        String valid="<w:headerReference w:type=\"default\" r:id=\"rHD\"/>";
        String[][] invalid={
                {"<w:headerReference w:type=\"default\"/>","missing_relationship_id"},
                {"<w:headerReference w:type=\"default\" r:id=\"notPresent\"/>","missing_relationship"},
                {"<w:headerReference w:type=\"default\" r:id=\"rFD\"/>","wrong_relationship_type"},
                {valid+valid,"duplicate_slot"},
                {"<w:headerReference w:type=\"other\" r:id=\"rHD\"/>","invalid_reference_type"},
                {"<w:headerReference r:id=\"rHD\"/>","invalid_reference_type"},
                {"<w:headerReference w:type=\"default\" xmlns:x=\"urn:foreign\" x:id=\"rHD\"/>","missing_relationship_id"}
        };
        for(String[] authored:invalid){
            JsonNode sections=parse(storySectionsFixture("",valid,authored[0],"")).getCoverage().getNativeDocxSections().path("sections");assertEquals(3,sections.size());
            assertEquals("explicit_target_loaded",storyBinding(sections.get(0).path("headerFooterBindings"),"header","default").path("status").asText());
            JsonNode bad=storyBinding(sections.get(1).path("headerFooterBindings"),"header","default"),following=storyBinding(sections.get(2).path("headerFooterBindings"),"header","default");
            assertEquals("unknown",bad.path("status").asText());assertEquals(authored[1],bad.path("reason").asText());assertTrue(bad.path("targetPart").isNull());
            assertEquals("unknown",following.path("status").asText());assertEquals("unresolved_previous_candidate",following.path("reason").asText());
            assertEquals(bad.path("unresolvedOrigin"),following.path("unresolvedOrigin"));assertTrue(following.path("targetPart").isNull());
            assertEquals(sections.get(1).path("sectionId"),following.path("previousSectionId"));assertTrue(following.path("inheritedFromSectionId").isNull());
            for(JsonNode section:sections)assertEquals("no_target",storyBinding(section.path("headerFooterBindings"),"footer","default").path("status").asText());
            assertEquals("unknown",following.path("effectiveState").asText());
        }
    }

    @Test void duplicateAndExternalRelationshipsKeepRawCandidatesWithoutSelectingATarget() throws Exception {
        String relationNamespace="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String[][] invalid={
                {"rHD","<Relationship Id=\"rHD\" Type=\""+relationNamespace+"/footer\" Target=\"footer-default.xml\"/>","duplicate_relationship_id"},
                {"externalHeader","<Relationship Id=\"externalHeader\" Type=\""+relationNamespace+"/header\" Target=\"https://example.invalid/header.xml\" TargetMode=\"External\"/>","external_relationship"}
        };
        for(String[] authored:invalid){
            byte[] bytes=storySectionsFixture("","<w:headerReference w:type=\"default\" r:id=\""+authored[0]+"\"/>","");
            bytes=editFixturePart(bytes,"word/_rels/document.xml.rels","</Relationships>",authored[1]+"</Relationships>");
            JsonNode inventory=parse(bytes).getCoverage().getNativeDocxSections(),sections=inventory.path("sections"),reference=sections.get(0).path("headerFooterReferences").get(0);
            JsonNode binding=storyBinding(sections.get(0).path("headerFooterBindings"),"header","default");
            assertEquals("unknown",binding.path("status").asText());assertEquals(authored[2],binding.path("reason").asText());assertTrue(binding.path("targetPart").isNull());
            JsonNode candidates=reference.path("relationshipCandidates");assertEquals("rHD".equals(authored[0])?2:1,candidates.size());
            for(JsonNode candidate:candidates){assertEquals(authored[0],candidate.path("id").asText());assertEquals(authored[0],candidate.path("rawAttributes").path("{}Id").asText());}
            assertEquals("word/_rels/document.xml.rels",candidates.get(0).path("sourcePosition").path("partName").asText());
            assertNativeFixturePosition(candidates.get(candidates.size()-1).path("sourcePosition"),bytes,"word/_rels/document.xml.rels","/{http://schemas.openxmlformats.org/package/2006/relationships}Relationships[1]/{http://schemas.openxmlformats.org/package/2006/relationships}Relationship[6]");
            if(candidates.size()==2){assertEquals("header-default.xml",candidates.get(0).path("rawTarget").asText());assertEquals("footer-default.xml",candidates.get(1).path("rawTarget").asText());}
            else{assertEquals("External",candidates.get(0).path("rawTargetMode").asText());assertEquals("https://example.invalid/header.xml",candidates.get(0).path("rawTarget").asText());}
            assertEquals("unresolved_previous_candidate",storyBinding(sections.get(1).path("headerFooterBindings"),"header","default").path("reason").asText());
        }
    }

    @Test void differentTypedSlotsMayReuseOneLoadedPartAndKeepImmediateInheritanceLinks() throws Exception {
        byte[] bytes=storySectionsFixture("","<w:headerReference w:type=\"default\" r:id=\"rHD\"/><w:headerReference w:type=\"first\" r:id=\"rHD\"/>","","");
        DocumentParser.ParsedDocument parsed=parse(bytes);JsonNode sections=parsed.getCoverage().getNativeDocxSections().path("sections");assertEquals(3,sections.size());
        for(String variant:Arrays.asList("default","first"))for(int index=0;index<3;index++){
            JsonNode binding=storyBinding(sections.get(index).path("headerFooterBindings"),"header",variant);
            assertEquals(index==0?"explicit_target_loaded":"inherited_target_candidate",binding.path("status").asText());
            assertEquals("word/header-default.xml",binding.path("targetPart").path("partName").asText());assertEquals(sections.get(0).path("sectionId"),binding.path("originSectionId"));
            if(index>0){assertEquals(sections.get(index-1).path("sectionId"),binding.path("previousSectionId"));assertEquals(sections.get(index-1).path("sectionId"),binding.path("inheritedFromSectionId"));}
            assertFalse(binding.path("originReference").has("relationshipCandidates"));assertEquals("unknown",binding.path("effectiveState").asText());
        }
        for(DocumentBlock block:parsed.getBlocks())if(block.getLocation().startsWith("body/")){
            JsonNode context=block.getReadingContext().path("nativeDocxSection");assertEquals("source_parse_coverage_inventory",context.path("headerFooterRefsScope").asText());
            assertEquals("/nativeDocxSections/headerFooterSettings",context.path("headerFooterSettingsRef").asText());
        }
    }

    @Test void historicalAndNonDirectStoryDeclarationsNeverReplaceCurrentSectionCandidates() throws Exception {
        String historical="<w:headerReference w:type=\"default\" r:id=\"notPresent\"/><w:titlePg w:val=\"1\"/>";
        String valid="<w:headerReference w:type=\"default\" r:id=\"rHD\"/>";
        byte[] bytes=storySectionsFixture("",valid+"<w:sectPrChange w:id=\"8\"><w:sectPr>"+historical+"</w:sectPr></w:sectPrChange>","");
        String extra="<w:p><w:pPr><w:pPrChange w:id=\"9\"><w:pPr><w:sectPr>"+historical+"</w:sectPr></w:pPr></w:pPrChange></w:pPr><w:r><w:t>historical paragraph</w:t></w:r></w:p>"
                +"<w:tbl><w:tr><w:tc><w:p><w:pPr><w:sectPr>"+historical+"</w:sectPr></w:pPr><w:r><w:t>table stays</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";
        bytes=editFixturePart(bytes,"word/document.xml","<w:sectPr></w:sectPr>",extra+"<w:sectPr></w:sectPr>");
        JsonNode inventory=parse(bytes).getCoverage().getNativeDocxSections(),sections=inventory.path("sections"),excluded=inventory.path("nonEffectiveSectionProperties");
        assertEquals(2,sections.size());assertEquals(3,excluded.size());
        assertEquals(1,sections.get(0).path("headerFooterReferences").size());assertTrue(sections.get(1).path("headerFooterReferences").isEmpty());
        assertEquals("explicit_target_loaded",storyBinding(sections.get(0).path("headerFooterBindings"),"header","default").path("status").asText());
        assertEquals("inherited_target_candidate",storyBinding(sections.get(1).path("headerFooterBindings"),"header","default").path("status").asText());
        Set<String> paths=new HashSet<>();for(JsonNode item:excluded){
            assertEquals("not_current_body_section_candidate",item.path("status").asText());assertEquals("notPresent",item.path("headerFooterReferences").get(0).path("rawRelationshipId").asText());
            assertTrue(item.path("titlePg").path("valueCandidate").asBoolean());assertTrue(paths.add(item.path("sourcePosition").path("nodePath").asText()));
        }
    }

    @Test void missingSettingsRelationshipKeepsConfigurationUnobservedRatherThanDisabled() throws Exception {
        byte[] bytes=storySectionsFixture("<w:evenAndOddHeaders w:val=\"true\"/>","");
        bytes=editFixturePart(bytes,"word/_rels/document.xml.rels","<Relationship Id=\"rSettings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>","");
        JsonNode setting=parse(bytes).getCoverage().getNativeDocxSections().path("headerFooterSettings");
        assertEquals("observed",setting.path("relationshipInventoryStatus").asText());assertEquals("settings_not_observed",setting.path("status").asText());
        assertEquals("unobserved",setting.path("evenAndOddHeaders").path("status").asText());assertFalse(setting.path("evenAndOddHeaders").path("present").asBoolean());
        assertTrue(setting.path("evenAndOddHeaders").path("valueCandidate").isNull());
    }

    @Test void ambiguousSettingsRelationshipsKeepEveryRawDeclarationWithoutSelectingASetting() throws Exception {
        String relationNamespace="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        byte[] bytes=storySectionsFixture("<w:evenAndOddHeaders/>","");
        bytes=editFixturePart(bytes,"word/_rels/document.xml.rels","</Relationships>","<Relationship Id=\"rSettingsOther\" Type=\""+relationNamespace+"/settings\" Target=\"settings.xml\"/></Relationships>");
        JsonNode setting=parse(bytes).getCoverage().getNativeDocxSections().path("headerFooterSettings"),candidates=setting.path("relationshipCandidates");
        assertEquals("ambiguous_settings_relationships",setting.path("status").asText());assertEquals(2,candidates.size());
        assertEquals("rSettings",candidates.get(0).path("id").asText());assertEquals("rSettingsOther",candidates.get(1).path("id").asText());
        for(int index=0;index<2;index++)assertNativeFixturePosition(candidates.get(index).path("sourcePosition"),bytes,"word/_rels/document.xml.rels","/{http://schemas.openxmlformats.org/package/2006/relationships}Relationships[1]/{http://schemas.openxmlformats.org/package/2006/relationships}Relationship["+(index+5)+"]");
        assertEquals("unobserved",setting.path("evenAndOddHeaders").path("status").asText());assertTrue(setting.path("evenAndOddHeaders").path("valueCandidate").isNull());
    }

    @Test void fieldAndObjectOnlyStoryPartsKeepLoadedIdentityWithoutRenderedUsageAuthority() throws Exception {
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        for(String content:Arrays.asList("<w:p/>","<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText>PAGE</w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>","<w:p><w:r><w:object/></w:r></w:p>")){
            byte[] bytes=storySectionsFixture("","<w:headerReference w:type=\"default\" r:id=\"rHD\"/>","");
            bytes=editFixturePart(bytes,"word/header-default.xml",new String(fixturePart(bytes,"word/header-default.xml"),StandardCharsets.UTF_8),"<w:hdr xmlns:w=\""+w+"\">"+content+"</w:hdr>");
            JsonNode sections=parse(bytes).getCoverage().getNativeDocxSections().path("sections");
            JsonNode binding=storyBinding(sections.get(0).path("headerFooterBindings"),"header","default");
            assertEquals("explicit_target_loaded",binding.path("status").asText());assertEquals("unknown",binding.path("effectiveState").asText());
            assertEquals(DocumentParseProbe.sha256(fixturePart(bytes,"word/header-default.xml")),binding.path("targetPart").path("partSha256").asText());
            assertEquals("word/header-default.xml",binding.path("targetPart").path("partName").asText());
            assertEquals("inherited_target_candidate",storyBinding(sections.get(1).path("headerFooterBindings"),"header","default").path("status").asText());
        }
    }

    @Test void nativeSectionsRetainEmptyAndNonemptyEndpointsWithoutChangingBodyTablesOrHeaders() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createParagraph().createRun().setText("Notes");
            doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            doc.createParagraph().getCTP().addNewPPr().addNewSectPr();
            XWPFParagraph note=doc.createParagraph();note.createRun().setText("Note 1");note.getCTP().addNewPPr().addNewSectPr();
            XWPFTable table=doc.createTable(1,2);table.getRow(0).getCell(0).setText("actual table");
            doc.createParagraph().createRun().setText("tail");
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Notes header");
            bytes=save(doc);
        }
        bytes=edit(bytes,"<w:body>","<w:body><w:sdt><w:sdtContent><w:p><w:r><w:t>UNOBSERVED_NATIVE_CONTENT</w:t></w:r></w:p></w:sdtContent></w:sdt>");
        DocumentParser.ParsedDocument parsed=parse(bytes);JsonNode inventory=parsed.getCoverage().getNativeDocxSections(),sections=inventory.path("sections");
        assertEquals(4,sections.size());assertFalse(inventory.path("semanticScopeVerified").asBoolean(true));
        assertEquals("unknown",inventory.path("applicabilityStatus").asText());
        assertEquals(2,sections.get(0).path("unrepresentedNativeMemberPaths").size());
        assertTrue(sections.get(0).path("unrepresentedNativeMemberPaths").get(0).asText().contains("}sdt[1]/"));
        assertEquals(0,sections.get(1).path("retainedBlockIds").size());assertEquals(1,sections.get(1).path("nativeMemberPaths").size());
        assertEquals("retained_blocks_only_unrepresented_members_unknown",inventory.path("membershipStatus").asText());
        for(int i=0;i<3;i++)assertEquals("paragraph_sectPr",sections.get(i).path("endBoundary").path("kind").asText());
        assertEquals("final_body_sectPr",sections.get(3).path("endBoundary").path("kind").asText());
        List<DocumentBlock> body=new ArrayList<>();Set<String> retainedIds=new HashSet<>();
        for(DocumentBlock block:parsed.getBlocks()){
            if(!block.getLocation().startsWith("body/")){assertFalse(block.getReadingContext().has("nativeDocxSection"));continue;}
            body.add(block);JsonNode ref=block.getReadingContext().path("nativeDocxSection"),section=sections.get(ref.path("ordinalZeroBased").asInt());
            assertEquals(section.path("sectionId"),ref.path("sectionId"));assertEquals(section.path("endBoundary"),ref.path("endBoundary"));
            assertFalse(ref.has("retainedBlockIds"));assertFalse(ref.has("nativeMemberPaths"));
            assertEquals("unknown",ref.path("ownerStatus").asText());assertEquals("unknown",ref.path("applicabilityStatus").asText());
            assertTrue(section.path("retainedBlockIds").toString().contains(block.getId()));
            for(String edge:Arrays.asList("startBoundary","endBoundary")){
                JsonNode pos=ref.path(edge).path("sourcePosition");assertEquals(DocumentParseProbe.sha256(bytes),pos.path("sourceSha256").asText());
                assertEquals("word/document.xml",pos.path("partName").asText());assertTrue(pos.path("partSha256").asText().matches("[a-f0-9]{64}"));
                assertTrue(pos.path("nodePath").asText().startsWith("/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}document[1]/"));
            }
        }
        assertEquals(Arrays.asList("Notes","Note 1","actual table | ","tail"),Arrays.asList(body.get(0).getText(),body.get(1).getText(),body.get(2).getText(),body.get(3).getText()));
        assertEquals(4,body.size());assertEquals(Arrays.asList("actual table",""),body.get(2).getCells());
        assertEquals(2,body.get(2).getWordCells().size());assertFalse(parsed.getText().contains("UNOBSERVED_NATIVE_CONTENT"));
        for(JsonNode section:sections)for(JsonNode id:section.path("retainedBlockIds"))assertTrue(retainedIds.add(id.asText()));
        assertEquals(4,retainedIds.size());assertEquals(inventory,JsonUtils.read(JsonUtils.write(parsed.getCoverage()),ParseCoverage.class).getNativeDocxSections());
        assertEquals(parsed.getBlocks(),JsonUtils.readList(JsonUtils.write(parsed.getBlocks()),DocumentBlock.class));
        assertEquals(inventory,parse(bytes).getCoverage().getNativeDocxSections());
    }

    @Test void zeroRetainedSectionsStillKeepNativeEndpointsAndMissingFinalPropertyIsExplicit() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createParagraph().getCTP().addNewPPr().addNewSectPr();bytes=save(doc);
        }
        DocumentParser.ParsedDocument parsed=parse(bytes);JsonNode sections=parsed.getCoverage().getNativeDocxSections().path("sections");
        assertTrue(parsed.getBlocks().isEmpty());assertEquals(2,sections.size());
        assertEquals(1,sections.get(0).path("nativeMemberPaths").size());assertEquals(0,sections.get(1).path("nativeMemberPaths").size());
        for(JsonNode section:sections)assertEquals(0,section.path("retainedBlockIds").size());
        assertEquals("body_end_without_sectPr",sections.get(1).path("endBoundary").path("kind").asText());
        assertEquals(sections.get(0).path("endBoundary").path("sourcePosition"),sections.get(1).path("startBoundary").path("sourcePosition"));
        assertNotEquals(sections.get(0).path("sectionId"),sections.get(1).path("sectionId"));
    }

    @Test void wrappedParagraphBoundariesSeparateLaterBodyWithoutPromotingTableOrHistoricalSectionProperties() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createParagraph().createRun().setText("retained after wrapper");doc.getDocument().getBody().addNewSectPr();bytes=save(doc);
        }
        String wrapped="<w:sdt><w:sdtContent><w:customXml>"
                +"<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>unrepresented Notes</w:t></w:r></w:p>"
                +"<w:p><w:pPr><w:sectPr/></w:pPr></w:p></w:customXml>"
                +"<w:tbl><w:tr><w:tc><w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>table paragraph</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                +"<w:p><w:pPr><w:pPrChange w:id=\"1\"><w:pPr><w:sectPr/></w:pPr></w:pPrChange></w:pPr><w:r><w:t>historical property</w:t></w:r></w:p>"
                +"</w:sdtContent></w:sdt>";
        DocumentParser.ParsedDocument parsed=parse(edit(bytes,"<w:body>","<w:body>"+wrapped));
        JsonNode inventory=parsed.getCoverage().getNativeDocxSections(),sections=inventory.path("sections");
        assertEquals(3,sections.size(),"Only actual body-flow paragraph properties end observed source sections");
        assertEquals(1,parsed.getBlocks().size());assertEquals("retained after wrapper",parsed.getText());
        assertEquals(0,sections.get(0).path("retainedBlockIds").size());assertEquals(0,sections.get(1).path("retainedBlockIds").size());
        assertTrue(sections.get(0).path("endBoundary").path("sourcePosition").path("nodePath").asText().contains("}sdtContent[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}customXml[1]/"));
        assertEquals(2,sections.get(2).path("unrepresentedNativeMemberPaths").size());
        assertEquals(2,parsed.getBlocks().get(0).getReadingContext().path("nativeDocxSection").path("ordinalZeroBased").asInt());
        assertEquals("final_body_sectPr",sections.get(2).path("endBoundary").path("kind").asText());
        assertEquals("unknown",inventory.path("ownerStatus").asText());assertEquals("unknown",inventory.path("applicabilityStatus").asText());
    }

    @Test void foreignLocalNamesCannotHideActualWordWrapperContentOrSectionProperties() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()){
            doc.createParagraph().createRun().setText("retained Word body");doc.getDocument().getBody().addNewSectPr();bytes=save(doc);
        }
        String wrapped="<w:sdt xmlns:x=\"urn:authored:foreign\"><x:sdtContent><x:p/></x:sdtContent><w:sdtContent>"
                +"<w:p><x:pPr><x:sectPr/></x:pPr><w:pPr><x:sectPr/><w:sectPr/></w:pPr><w:r><w:t>unrepresented Word note</w:t></w:r></w:p>"
                +"</w:sdtContent></w:sdt>";
        DocumentParser.ParsedDocument parsed=parse(edit(bytes,"<w:body>","<w:body>"+wrapped));JsonNode sections=parsed.getCoverage().getNativeDocxSections().path("sections");
        assertEquals(2,sections.size());assertEquals(1,parsed.getBlocks().size());assertEquals("retained Word body",parsed.getText());
        String endpoint=sections.get(0).path("endBoundary").path("sourcePosition").path("nodePath").asText();
        assertTrue(endpoint.endsWith("/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}sectPr[1]"));assertFalse(endpoint.contains("urn:authored:foreign"));
        assertEquals(1,parsed.getBlocks().get(0).getReadingContext().path("nativeDocxSection").path("ordinalZeroBased").asInt());
        assertEquals("unknown",parsed.getBlocks().get(0).getReadingContext().path("nativeDocxSection").path("effectiveSectionState").asText());
    }

    @Test void tableCellsAdvanceNumberingExactlyOnceAndPreserveCellAnchors() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument()) {
            BigInteger num = numbering(doc);
            XWPFTable table = doc.createTable(1, 2);
            for (int i=0; i<2; i++) {
                XWPFParagraph p=table.getRow(0).getCell(i).getParagraphs().get(0);
                p.setNumID(num); p.createRun().setText("item " + i);
            }
            bytes=save(doc);
        }
        DocumentBlock row=parse(bytes).getBlocks().get(0);
        assertEquals(Arrays.asList("(e) item 0", "(f) item 1"), row.getCells());
        assertEquals("body/0/table-row/0", row.getLocation());
        assertEquals(2, row.getWordNumbering().size());
        assertTrue(row.getWordNumbering().get(1).getLocation().contains("/cell/1/"));
        assertNull(row.getPageNo());
    }

    @Test void fullyDeletedOrStruckNumberedItemsDoNotBecomeEffectiveClauses() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);
            for (String text : Arrays.asList("delete me", "strike me", "active")) {
                XWPFParagraph p=doc.createParagraph(); p.setNumID(num);
                XWPFRun run=p.createRun(); run.setText(text);
                if (text.equals("strike me")) run.setStrikeThrough(true);
            }
            bytes=save(doc);
        }
        bytes=edit(bytes, "<w:r><w:t>delete me</w:t></w:r>",
                "<w:del w:id=\"1\" w:author=\"Fixture\"><w:r><w:delText>delete me</w:delText></w:r></w:del>");
        DocumentParser.ParsedDocument parsed=parse(bytes);
        assertEquals(3, parsed.getBlocks().size());
        assertEquals("", parsed.getBlocks().get(0).getText());
        assertEquals("(e) delete me", parsed.getBlocks().get(0).getDeletedText());
        assertEquals("", parsed.getBlocks().get(1).getText());
        assertEquals("(f) strike me", parsed.getBlocks().get(1).getStrikeText());
        assertEquals("(g) active", parsed.getText());
        assertFalse(parsed.getBlocks().get(0).getWordNumbering().get(0).isAppliedToEffectiveText());
    }

    @Test void symbolsKeepIndependentOriginalDeletedAndStrikeChannels() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc=new XWPFDocument()) { doc.createParagraph(); bytes=save(doc); }
        String paragraph="<w:p>"+TICK+"<w:del w:id=\"2\" w:author=\"Fixture\">"+TICK
                +"</w:del><w:r><w:rPr><w:strike/></w:rPr><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></w:r></w:p>";
        DocumentBlock b=parse(edit(bytes,"</w:body>",paragraph+"</w:body>")).getBlocks().get(0);
        assertEquals("✓",b.getText()); assertEquals("✓✓✓",b.getOriginalText());
        assertEquals("✓",b.getDeletedText()); assertEquals("✓",b.getStrikeText());
        assertEquals(3,b.getWordSymbols().size());
        assertTrue(b.getWordSymbols().get(1).isDeleted());
        assertTrue(b.getWordSymbols().get(2).isStruck());
    }

    @Test void unsupportedSymbolAndNumberingAreVisiblePartialCoverage() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(BigInteger.valueOf(999)); p.createRun().setText("body");
            bytes=save(doc);
        }
        bytes=edit(bytes,"</w:body>","<w:p><w:r><w:sym w:font=\"UnknownFont\" w:char=\"F0FC\"/></w:r></w:p></w:body>");
        DocumentParser.ParsedDocument parsed=parse(bytes);
        assertEquals("PARTIAL",parsed.getParseStatus());
        assertTrue(parsed.getText().contains("[unresolved Word numbering] body"));
        assertTrue(parsed.getText().contains("[unresolved Word symbol]"));
        assertFalse(parsed.getText().contains("✓"));
        assertEquals("999",parsed.getBlocks().get(0).getWordNumbering().get(0).getNumId());
        assertEquals("UnknownFont",parsed.getBlocks().get(1).getWordSymbols().get(0).getFont());
    }

    @Test void savedProvenanceRoundTripsAndLegacyBlocksRemainCompatible() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(numbering(doc)); p.createRun().setText("body"); bytes=save(doc);
        }
        bytes=edit(bytes,"</w:body>","<w:p>"+TICK+"</w:p></w:body>");
        List<DocumentBlock> blocks=parse(bytes).getBlocks();
        List<DocumentBlock> saved=JsonUtils.readList(JsonUtils.write(blocks),DocumentBlock.class);
        assertEquals(blocks,saved);
        DocumentBlock legacy=JsonUtils.read("{\"text\":\"old\",\"location\":\"body/1/paragraph\"}",DocumentBlock.class);
        assertTrue(legacy.getWordSymbols().isEmpty()); assertTrue(legacy.getWordNumbering().isEmpty());
        assertNull(legacy.getWordStructureVersion());
        assertFalse(JsonUtils.write(legacy).contains("wordSymbols"));
    }

    @Test void textboxNumberingDoesNotAdvanceBodyList() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            XWPFParagraph p=doc.createParagraph(); p.setNumID(numbering(doc)); p.createRun().setText("first"); bytes=save(doc);
        }
        String numbered="<w:p><w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>";
        String textbox="<w:p><w:r><w:pict><v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\"><v:textbox><w:txbxContent>"
                + numbered + "textbox</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>";
        bytes=edit(bytes,"</w:body>",textbox+numbered+"last</w:t></w:r></w:p></w:body>");
        List<DocumentBlock> blocks=parse(bytes).getBlocks();
        assertEquals("(e) first",blocks.get(0).getText());
        assertEquals("(e) textbox",blocks.get(1).getText());
        assertEquals("(f) last",blocks.get(2).getText());
    }

    @Test void alternateContentFallbackIsSelectedOnceWithoutAdvancingBodyList() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);
            XWPFParagraph p=doc.createParagraph();p.setNumID(num);p.createRun().setText("first");
            doc.createParagraph().createRun().setText("holder");
            p=doc.createParagraph();p.setNumID(num);p.createRun().setText("last");bytes=save(doc);
        }
        String alternative="<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" xmlns:v=\"urn:schemas-microsoft-com:vml\">"
                +"<mc:Choice Requires=\"v\"><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></mc:Choice>"
                +"<mc:Fallback><w:sym w:font=\"Wingdings\" w:char=\"F0FC\"/></mc:Fallback></mc:AlternateContent>";
        DocumentParser.ParsedDocument parsed=parse(edit(bytes,"<w:t>holder</w:t>",alternative));
        assertEquals("PARSED",parsed.getParseStatus());
        assertEquals("(f) last",parsed.getBlocks().get(2).getText());
        assertTrue(parsed.getBlocks().get(1).getWordWarnings().isEmpty());
        assertEquals("\u2713",parsed.getBlocks().get(1).getText());
    }

    @Test void noteNumberingRetainsDefinitionsButDoesNotAssertUnverifiedOrder() throws Exception {
        byte[] bytes;
        try(XWPFDocument doc=new XWPFDocument()) {
            BigInteger num=numbering(doc);doc.createParagraph().createRun().setText("body");
            XWPFFootnote note=doc.createFootnote();XWPFParagraph p=note.createParagraph();p.setNumID(num);p.createRun().setText("note");bytes=save(doc);
        }
        DocumentParser.ParsedDocument parsed=parse(bytes);
        DocumentBlock note=parsed.getBlocks().stream().filter(b->"footnote".equals(b.getKind())).findFirst().get();
        assertEquals("PARTIAL",parsed.getParseStatus());
        assertTrue(note.getText().contains("[unresolved Word numbering] note"));
        assertEquals("note_reference_order_not_verified",note.getWordNumbering().get(0).getReason());
        assertEquals("(e)",note.getWordNumbering().get(0).getLabel());
    }

    private JsonNode storyBinding(JsonNode bindings,String kind,String variant) {
        for(JsonNode binding:bindings)if(kind.equals(binding.path("kind").asText())&&variant.equals(binding.path("variant").asText()))return binding;
        throw new AssertionError("Missing typed story slot "+kind+"/"+variant);
    }
    private void assertBodyStoryBindings(JsonNode part,JsonNode sections) {
        JsonNode reference=part.path("readingContext").path("nativeDocxSection");
        if(part.path("anchor").asText().startsWith("body/"))assertTrue(reference.isObject(),"Every selected body block/slice keeps its source section binding");
        if(reference.isMissingNode())return;
        JsonNode section=sections.get(reference.path("ordinalZeroBased").asInt());assertEquals(section.path("sectionId"),reference.path("sectionId"));
        assertEquals(section.path("headerFooterBindings"),reference.path("headerFooterBindings"));assertFalse(reference.has("nativeMemberPaths"));
        assertEquals("unknown",reference.path("effectiveSectionState").asText());assertEquals("unknown",reference.path("applicabilityStatus").asText());
    }
    private void assertNativeFixturePosition(JsonNode position,byte[] bytes,String part,String nodePath) throws Exception {
        assertEquals(DocumentParseProbe.sha256(bytes),position.path("sourceSha256").asText());assertEquals(part,position.path("partName").asText());
        assertEquals(DocumentParseProbe.sha256(fixturePart(bytes,part)),position.path("partSha256").asText());assertEquals(nodePath,position.path("nodePath").asText());
    }
    private byte[] fixturePart(byte[] bytes,String name) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;
            while((entry=zip.getNextEntry())!=null)if(name.equals(entry.getName())){
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int size;
                while((size=zip.read(buffer))!=-1)out.write(buffer,0,size);return out.toByteArray();
            }
        }
        throw new AssertionError("Missing authored OPC part "+name);
    }
    private byte[] sectionStoryFixture() throws Exception {
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main",r="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        Map<String,String> parts=new LinkedHashMap<>();StringBuilder types=new StringBuilder("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        types.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>");
        for(String variant:Arrays.asList("default","first","even")){
            types.append("<Override PartName=\"/word/header-").append(variant).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>");
            parts.put("word/header-"+variant+".xml","<w:hdr xmlns:w=\""+w+"\"><w:p><w:r><w:t>HEADER_"+variant+"</w:t></w:r></w:p></w:hdr>");
        }
        types.append("<Override PartName=\"/word/footer-default.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/></Types>");
        parts.put("[Content_Types].xml",types.toString());parts.put("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rDocument\" Type=\""+r+"/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
        parts.put("word/_rels/document.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                +"<Relationship Id=\"rHF\" Type=\""+r+"/header\" Target=\"header-first.xml\"/>"
                +"<Relationship Id=\"rHD\" Type=\""+r+"/header\" Target=\"header-default.xml\"/>"
                +"<Relationship Id=\"rFD\" Type=\""+r+"/footer\" Target=\"footer-default.xml\"/>"
                +"<Relationship Id=\"rHE\" Type=\""+r+"/header\" Target=\"header-even.xml\"/>"
                +"<Relationship Id=\"rSettings\" Type=\""+r+"/settings\" Target=\"settings.xml\"/></Relationships>");
        parts.put("word/document.xml","<w:document xmlns:w=\""+w+"\" xmlns:r=\""+r+"\"><w:body><w:p><w:pPr><w:sectPr>"
                +"<w:headerReference w:type=\"even\" r:id=\"rHE\"/><w:footerReference w:type=\"default\" r:id=\"rFD\"/><w:headerReference w:type=\"first\" r:id=\"rHF\"/><w:headerReference w:type=\"default\" r:id=\"rHD\"/><w:titlePg w:val=\"1\"/>"
                +"</w:sectPr></w:pPr><w:r><w:t>First body "+String.join("",Collections.nCopies(1800,"x"))+"</w:t></w:r></w:p>"
                +"<w:p><w:r><w:t>Second body unchanged</w:t></w:r></w:p><w:sectPr><w:titlePg w:val=\"0\"/></w:sectPr></w:body></w:document>");
        parts.put("word/settings.xml","<w:settings xmlns:w=\""+w+"\"><w:evenAndOddHeaders w:val=\"true\"/></w:settings>");
        parts.put("word/footer-default.xml","<w:ftr xmlns:w=\""+w+"\"><w:p/></w:ftr>");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){
            for(Map.Entry<String,String> part:parts.entrySet()){ZipEntry entry=new ZipEntry(part.getKey());entry.setTime(0L);zip.putNextEntry(entry);zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }return out.toByteArray();
    }

    private byte[] storySectionsFixture(String settings,String... sectionProperties) throws Exception {
        String w="http://schemas.openxmlformats.org/wordprocessingml/2006/main",r="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        StringBuilder document=new StringBuilder("<w:document xmlns:w=\"").append(w).append("\" xmlns:r=\"").append(r).append("\"><w:body>");
        for(int index=0;index<sectionProperties.length;index++){
            if(index<sectionProperties.length-1)document.append("<w:p><w:pPr><w:sectPr>").append(sectionProperties[index]).append("</w:sectPr></w:pPr><w:r><w:t>authored body ").append(index).append("</w:t></w:r></w:p>");
            else document.append("<w:p><w:r><w:t>authored body ").append(index).append("</w:t></w:r></w:p><w:sectPr>").append(sectionProperties[index]).append("</w:sectPr>");
        }
        document.append("</w:body></w:document>");byte[] bytes=sectionStoryFixture();
        bytes=editFixturePart(bytes,"word/document.xml",new String(fixturePart(bytes,"word/document.xml"),StandardCharsets.UTF_8),document.toString());
        return editFixturePart(bytes,"word/settings.xml",new String(fixturePart(bytes,"word/settings.xml"),StandardCharsets.UTF_8),"<w:settings xmlns:w=\""+w+"\">"+settings+"</w:settings>");
    }
    private byte[] editFixturePart(byte[] bytes,String name,String from,String to) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean changed=false;
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes));ZipOutputStream zip=new ZipOutputStream(out)){
            ZipEntry entry;while((entry=in.getNextEntry())!=null){
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)data.write(buffer,0,count);
                byte[] content=data.toByteArray();if(name.equals(entry.getName())){String xml=new String(content,StandardCharsets.UTF_8);assertTrue(xml.contains(from));content=xml.replace(from,to).getBytes(StandardCharsets.UTF_8);changed=true;}
                ZipEntry replacement=new ZipEntry(entry.getName());replacement.setTime(0L);zip.putNextEntry(replacement);zip.write(content);zip.closeEntry();
            }
        }
        assertTrue(changed,"Authored OPC edit targets a real part");return out.toByteArray();
    }

    private BigInteger numbering(XWPFDocument doc) {
        XWPFNumbering n=doc.createNumbering();
        CTAbstractNum a=CTAbstractNum.Factory.newInstance(); a.setAbstractNumId(BigInteger.ZERO);
        CTLvl l=a.addNewLvl(); l.setIlvl(BigInteger.ZERO); l.addNewStart().setVal(BigInteger.valueOf(5));
        l.addNewNumFmt().setVal(STNumberFormat.LOWER_LETTER); l.addNewLvlText().setVal("(%1)");
        BigInteger abstractId=n.addAbstractNum(new XWPFAbstractNum(a));
        n.addNum(abstractId, BigInteger.ONE);
        return BigInteger.ONE;
    }
    private DocumentParser.ParsedDocument parse(byte[] bytes) {
        OcrClient ocr=mock(OcrClient.class);
        DocumentParser.ParsedDocument result=new DocumentParser(new ConsenseProperties(),ocr).parse("fixture.docx",bytes);
        verifyNoInteractions(ocr); return result;
    }
    private byte[] save(XWPFDocument doc) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); doc.write(out); return out.toByteArray(); }
    private byte[] edit(byte[] bytes,String from,String to) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(bytes)); ZipOutputStream zip=new ZipOutputStream(out)) {
            ZipEntry entry;
            while((entry=in.getNextEntry())!=null) {
                ByteArrayOutputStream data=new ByteArrayOutputStream(); byte[] buffer=new byte[8192]; int len;
                while((len=in.read(buffer))!=-1) data.write(buffer,0,len);
                byte[] content=data.toByteArray();
                if(entry.getName().equals("word/document.xml")) {
                    String xml=new String(content,StandardCharsets.UTF_8); assertTrue(xml.contains(from));
                    content=xml.replace(from,to).getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName())); zip.write(content); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
