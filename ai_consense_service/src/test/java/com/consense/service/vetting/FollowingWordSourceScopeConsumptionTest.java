package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.document.DocumentParser;
import com.consense.domain.CanonicalSourceBinding;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.CanonicalEvidenceService;
import com.consense.service.sources.NormalObservedContract;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FollowingWordSourceScopeConsumptionTest {
    @Test void actualCanonicalAndOrdinaryConsumersCarryTheForwardConditionAndPayloadTogether()throws Exception{
        try(XWPFDocument doc=new XWPFDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            for(String value:Arrays.asList("1.\tSources:","(a)\tFirst source","(b)\tSecond source","(c)\tFor outside works, shall include the following:","","PAYLOAD with all modifications.","","2.\tNEXT_EXCLUDED","3.\tLATER_EXCLUDED")){
                XWPFRun run=doc.createParagraph().createRun();String[] parts=value.split("\t",-1);for(int i=0;i<parts.length;i++){if(i>0)run.addTab();run.setText(parts[i]);}
            }
            doc.write(out);verifyParsed(new DocumentParser(new ConsenseProperties(),null).parse("authored-following.docx",out.toByteArray()));
        }
    }

    public static ObjectNode verifyParsed(DocumentParser.ParsedDocument parsed)throws Exception{
        JsonNode scopes=parsed.getCoverage().getNativeDocxOutline().path("sourceClauseScopes");List<JsonNode> members=new ArrayList<>();JsonNode expected=null;
        for(JsonNode range:scopes.path("ranges"))if("explicit_forward_introduction_unique_payload_source_interval".equals(range.path("basis").asText())){assertNull(expected);expected=range;}
        assertNotNull(expected);Set<String> ids=new HashSet<>();expected.path("orderedMemberIds").forEach(id->ids.add(id.asText()));
        for(JsonNode member:scopes.path("members"))if(ids.contains(member.path("id").asText())&&!member.path("textObservation").path("text").asText().isBlank())members.add(member);
        assertEquals(2,members.size());String introduction=members.get(0).path("textObservation").path("text").asText(),payload=members.get(1).path("textObservation").path("text").asText();
        SourceDocument source=new SourceDocument();source.setId(983L);source.setProjectId("offline-following-source-replay");source.setFileName("authored-following.docx");source.setFileKey("PRE");source.setCategory(SourceDocument.CATEGORY_VETTING_PACKAGE);source.setReviewRole("tender");source.setDocumentScope("fullset");source.setParseStatus(parsed.getParseStatus());source.setSourceSha256(parsed.getSourceSha256());source.setTextContent(parsed.getText());source.setStructuredContentJson(JsonUtils.write(parsed.getBlocks()));source.setParseCoverageJson(JsonUtils.write(parsed.getCoverage()));NormalSourceCandidateGraph.capture(source);
        JsonNode graph=NormalSourceCandidateGraph.requireBound(source);String graphBefore=JsonUtils.write(graph),coverageBefore=source.getParseCoverageJson();ObjectNode receipt=JsonUtils.mapper().createObjectNode();receipt.put("phase","actual_canonical_and_ordinary_consumption");receipt.put("sourceSha256",source.getSourceSha256());receipt.put("rangeId",expected.path("id").asText());
        for(int n=0;n<members.size();n++){
            JsonNode selectedMember=members.get(n),unit=null;for(JsonNode candidate:graph.path("units"))for(JsonNode part:candidate.path("parts"))if(part.path("sourcePosition").equals(selectedMember.path("sourcePosition")))unit=candidate;assertNotNull(unit);
            JsonNode canonical=CanonicalWordSourceContextTest.canonical(graph,unit);CanonicalSourceBinding binding=CanonicalWordSourceContextTest.binding(source);EvidencePointer pointer=CanonicalWordSourceContextTest.pointer(binding,graph,canonical);
            ObjectNode snapshot=JsonUtils.mapper().createObjectNode();snapshot.put("schema",NormalObservedContract.SCHEMA);snapshot.putObject("manifest").set("candidateGraph",graph);
            Method context=CanonicalEvidenceService.class.getDeclaredMethod("wordSourceScopeContext",CanonicalSourceBinding.class,JsonNode.class,JsonNode.class);context.setAccessible(true);pointer.setNativeWordSourceScopesContext((JsonNode)context.invoke(null,binding,snapshot,canonical));
            JsonNode model=new OerModelMaterial.Request().project(pointer,canonical,"tender"),observed=model.path("observedWordSourceScopes");assertEquals(expected.path("orderedMemberIds"),observed.path("ranges").get(0).path("orderedMemberIds"));assertTrue(observed.toString().contains(introduction));assertTrue(observed.toString().contains(payload));assertFalse(pointer.isHumanConfirmed());assertTrue(model.path("adoption").isNull());SourceJsonWireAssertions.assertRoundTrip(model);
            VettingCorpus.Chunk hit=null;for(VettingCorpus.Chunk chunk:VettingCorpus.chunks(Collections.singletonList(source)))for(VettingCorpus.Part part:chunk.getParts())if(part.getSourcePosition().equals(selectedMember.path("sourcePosition")))hit=chunk;assertNotNull(hit);String hitBefore=JsonUtils.write(hit);
            JsonNode ordinary=new OerModelMaterial.Request(Collections.singletonList(source)).projectOrdinary(hit),found=null;for(JsonNode candidate:ordinary.path("units"))if(candidate.path("range").path("id").equals(expected.path("id")))found=candidate;assertNotNull(found);assertTrue(found.path("text").asText().contains(introduction));assertTrue(found.path("text").asText().contains(payload));assertFalse(found.path("semanticScopeVerified").asBoolean(true));assertEquals("unknown",found.path("legalClosure").asText());assertFalse(found.path("range").path("automaticEditEligible").asBoolean(true));assertFalse(found.path("range").path("semanticApplicabilityVerified").asBoolean(true));SourceJsonWireAssertions.assertRoundTrip(ordinary);assertEquals(hitBefore,JsonUtils.write(hit));receipt.put(n==0?"introductionSelectionConsumerPassed":"payloadSelectionConsumerPassed",true);
        }
        assertEquals(graphBefore,JsonUtils.write(graph));assertEquals(coverageBefore,source.getParseCoverageJson());receipt.put("quotationRankAliasesAndCoverageUnchanged",true);receipt.put("legalApplicabilityEditingAndAdoptionGranted",false);return receipt;
    }
}
