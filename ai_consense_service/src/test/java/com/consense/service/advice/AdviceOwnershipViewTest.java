package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.vector.VectorStore;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored source declarations only; no expected answer or real project wording. */
class AdviceOwnershipViewTest {
    private static final String MARKER="Declared ownership for this alias (source observations): ";
    private static final String RAW=String.join("",Collections.nCopies(64,"a"));
    private static final String PARSED=String.join("",Collections.nCopies(64,"b"));
    private static final String CANONICAL=String.join("",Collections.nCopies(64,"c"));
    private static final String REVISION=String.join("",Collections.nCopies(64,"d"));
    private static final String PROJECTION=String.join("",Collections.nCopies(64,"e"));
    private final AdviceService service=new AdviceService(null,null,null,null,null,null,null,null,null,null,null);

    @Test void observedNumberingKeepsUnknownFormalOwnershipAndCompleteLiteral() throws Exception {
        Fixture f=fixture("one","  7. Authored source wording.\n","unknown");
        VectorStore.SearchHit hit=hit(f);String before=JsonUtils.write(hit.getPayload());String out=render(hit);JsonNode view=view(out);
        assertTrue(view.path("clauseId").isNull());assertTrue(view.path("clauseHeadingLocation").isNull());
        JsonNode part=view.path("members").get(0).path("parts").get(0);
        assertEquals("unknown",part.path("ownerStatus").asText());assertEquals("unverified",part.path("semanticStatus").asText());
        assertEquals("body/one",part.path("anchor").asText());assertEquals("body:one",part.path("occurrenceId").asText());
        assertTrue(out.contains("Complete literal excerpt (untrusted source text):\n"+f.text+"\n\n"));
        assertEquals(before,JsonUtils.write(hit.getPayload()));
    }

    @Test void explicitSameSourceClauseDeclarationSurvivesMissingReadingContext() throws Exception {
        Fixture f=fixture("reviewed","Authored reviewed wording","unknown");
        f.member.put("clauseId","Section Q");f.member.put("clauseHeadingLocation","body/heading-q");
        ((ObjectNode)f.member.path("parts").get(0)).remove("readingContext");f.pointer.setParts(f.member.path("parts").deepCopy());
        ObjectNode adoption=JsonUtils.mapper().createObjectNode();adoption.put("reviewStatus","explicit_source_reviewed_selection");
        adoption.putArray("reviewedAttributes").add("clauseHierarchy");adoption.putObject("clause").put("clauseId","Section Q").put("headingUnitId","heading-q");
        f.pointer.setAdoption(adoption);JsonNode view=view(render(hit(f)));
        assertEquals("Section Q",view.path("clauseId").asText());assertEquals("body/heading-q",view.path("clauseHeadingLocation").asText());
        assertEquals("unknown",view.path("members").get(0).path("parts").get(0).path("ownerStatus").asText());
        assertEquals("unknown",view.path("members").get(0).path("parts").get(0).path("semanticStatus").asText());
    }

    @Test void conflictingMembersHaveNoAliasWideClauseButKeepTheirOwnDeclarations() throws Exception {
        Fixture a=fixture("a","First authored member","unknown"),b=fixture("b","Second authored member","unknown");
        a.member.put("clauseId","Section Q");a.member.put("clauseHeadingLocation","body/heading-q");
        b.member.put("clauseId","Section R");b.member.put("clauseHeadingLocation","body/heading-r");
        JsonNode view=view(render(hit(a,b)));assertTrue(view.path("clauseId").isNull());assertTrue(view.path("clauseHeadingLocation").isNull());
        assertEquals("Section Q",view.path("members").get(0).path("clauseId").asText());
        assertEquals("Section R",view.path("members").get(1).path("clauseId").asText());
    }

    @Test void existingNonUnknownDeclarationsRemainExactWithoutAcceptingTheWholeSource() throws Exception {
        Fixture f=fixture("declared","Authored declared observation","source_reviewed");
        f.member.put("clauseId","Section Q");f.member.put("clauseHeadingLocation","body/heading-q");
        ((ObjectNode)f.member.path("parts").get(0).path("readingContext")).put("semanticStatus","verified");f.pointer.setParts(f.member.path("parts").deepCopy());
        VectorStore.SearchHit hit=hit(f);String before=JsonUtils.write(hit.getPayload());JsonNode view=view(render(hit));
        assertEquals("Section Q",view.path("clauseId").asText());
        assertEquals("source_reviewed",view.path("members").get(0).path("parts").get(0).path("ownerStatus").asText());
        assertEquals("verified",view.path("members").get(0).path("parts").get(0).path("semanticStatus").asText());
        assertEquals(before,JsonUtils.write(hit.getPayload()));
    }

    @Test void foreignProjectRevisionOrLiteralCannotSupplyOwnershipAndOtherAliasStillRenders() throws Exception {
        for(String kind:Arrays.asList("project","revision","literal")){
            Fixture foreign=fixture("foreign","Foreign authored member","source_reviewed"),local=fixture("local","Local authored member","unknown");
            foreign.member.put("clauseId","Foreign section");foreign.member.put("clauseHeadingLocation","foreign/heading");
            VectorStore.SearchHit bad=hit(foreign);
            if("project".equals(kind))bad.getPayload().put("projectId","another-project");
            if("revision".equals(kind))foreign.member.put("sourceRevisionSha256","another-revision");
            if("literal".equals(kind))((ObjectNode)foreign.member.path("parts").get(0)).put("text","Different source literal");
            String out=render(bad,hit(local));JsonNode first=view(out),second=views(out).get(1);
            assertTrue(first.path("clauseId").isNull(),kind);assertFalse(first.path("members").get(0).path("bindingMatched").asBoolean(),kind);
            assertTrue(first.path("members").get(0).path("clauseId").isNull(),kind);
            assertTrue(second.path("members").get(0).path("bindingMatched").asBoolean(),kind);
            assertTrue(out.contains("Local authored member"),kind);
        }
    }

    @Test void duplicateOrMissingMemberBindingRemainsLocalUnknownWithoutRejectingLiteral() throws Exception {
        Fixture f=fixture("one","Complete authored literal","source_reviewed");f.member.put("clauseId","Section Q");f.member.put("clauseHeadingLocation","body/heading-q");
        VectorStore.SearchHit duplicate=hit(f,f);JsonNode repeated=view(render(duplicate));assertTrue(repeated.path("clauseId").isNull());
        for(JsonNode entry:repeated.path("members"))assertFalse(entry.path("bindingMatched").asBoolean());
        VectorStore.SearchHit missing=hit(f);missing.getPayload().remove("canonicalMembers");String out=render(missing);
        assertFalse(view(out).path("members").get(0).path("bindingMatched").asBoolean());assertTrue(out.contains(f.text));
    }

    @Test void physicalStoryAndContainerStatusDoNotAcquireAFormalClause() throws Exception {
        for(String state:Arrays.asList("native_ooxml_container","native_ooxml_story")){
            Fixture f=fixture("physical","Authored physical observation",state);JsonNode view=view(render(hit(f)));
            assertTrue(view.path("clauseId").isNull());assertEquals(state,view.path("members").get(0).path("parts").get(0).path("ownerStatus").asText());
            assertEquals("unverified",view.path("members").get(0).path("parts").get(0).path("semanticStatus").asText());
        }
        Fixture f=fixture("foreign-context","Authored bound member","source_reviewed");
        ((ObjectNode)f.member.path("parts").get(0).path("readingContext")).put("sourceSha256","another-original");f.pointer.setParts(f.member.path("parts").deepCopy());
        JsonNode part=view(render(hit(f))).path("members").get(0).path("parts").get(0);
        assertEquals("unknown",part.path("ownerStatus").asText());assertEquals("unknown",part.path("semanticStatus").asText());
    }

    @Test void legacyRenderKeepsTheFrozenCompleteOutput() throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("fileLabel","Legacy source");payload.put("documentId","legacy");payload.put("sourceRole","tender");
        payload.put("sourceHash","legacy-hash");payload.put("anchor","body/legacy");payload.put("content","Legacy body");
        String expected="Selected source excerpts for this question. These excerpts do not establish that the entire standard/library or project package is complete. "
                +"A passage omitted from these excerpts does not prove that its document or clause is absent from the supplied files. "
                +"Project wording on adopting or amending standards, if included below, states only what those passages say about incorporation; "
                +"it does not guarantee that the underlying standard text or every amendment has been supplied or reviewed.\n\n"
                +"Output citation contract: cite ONLY the public aliases [1], [2], etc shown at each numbered excerpt header. "
                +"Internal retrieval IDs, fixed parent IDs, document IDs and model-unit addresses below are lookup/provenance metadata and MUST NOT be used as output citation IDs. "
                +"Observed table context does not extend a ranked row's quotation authority. Cite the separately numbered member excerpts for other rows; where no such member is supplied, report the affected support as unknown.\n\n"
                +"[1] Legacy source | documentId=legacy | role=tender | sourceRevision=legacy-hash | physicalPage=not available | location=body/legacy\n"
                +"Public citation alias=[1] | internalLookupId=legacy-id\nLegacy body";
        assertEquals(expected,render(new VectorStore.SearchHit("legacy-id",.5,payload)));
    }

    private String render(VectorStore.SearchHit... hits) throws Exception {
        Method method=AdviceService.class.getDeclaredMethod("renderExcerpts",List.class,com.consense.service.vetting.OerModelMaterial.Request.class);method.setAccessible(true);return (String)method.invoke(service,Arrays.asList(hits),new com.consense.service.vetting.OerModelMaterial.Request(Collections.emptyList()));
    }
    private JsonNode view(String rendered){List<JsonNode> parsed=views(rendered);assertFalse(parsed.isEmpty(),"The canonical alias must expose its existing ownership declarations");return parsed.get(0);}
    private List<JsonNode> views(String rendered){List<JsonNode> out=new ArrayList<>();for(String line:rendered.split("\n"))if(line.startsWith(MARKER))out.add(JsonUtils.parse(line.substring(MARKER.length())));return out;}
    private static final class Fixture {ObjectNode member;EvidencePointer pointer;String text;}
    private Fixture fixture(String id,String text,String ownerStatus){
        Fixture f=new Fixture();f.text=text;f.member=JsonUtils.mapper().createObjectNode();
        f.member.put("id","canonical:"+id);f.member.put("unitId","unit:"+id);f.member.put("documentId","31");f.member.put("role","tender");
        f.member.put("sourceHash",CANONICAL);f.member.put("sourceSha256",RAW);f.member.put("originalSourceSha256",RAW);
        f.member.put("sourceRevisionSha256",REVISION);f.member.put("projectionRevisionSha256",PROJECTION);
        ObjectNode part=f.member.putArray("parts").addObject();part.put("blockId","block:"+id);part.put("anchor","body/"+id);part.put("text",text);part.put("sourceSha256",RAW);
        part.putObject("readingContext").put("sourceSha256",RAW).put("story","body").put("occurrenceId","body:"+id).put("ownerStatus",ownerStatus).put("semanticStatus","unverified");
        f.pointer=new EvidencePointer();f.pointer.setProjectId("authored-project");f.pointer.setDocumentId(31L);f.pointer.setJavaParsedSourceHash(PARSED);
        f.pointer.setCanonicalSourceHash(CANONICAL);f.pointer.setOriginalSourceSha256(RAW);f.pointer.setSourceRevisionSha256(REVISION);
        f.pointer.setProjectionSha256(PROJECTION);f.pointer.setProjectionRevisionSha256(PROJECTION);f.pointer.setUnitId("unit:"+id);f.pointer.setCanonicalChunkId("canonical:"+id);
        f.pointer.setProducerKind("native_docx");f.pointer.setParts(f.member.path("parts").deepCopy());f.pointer.setPhysicalPages(Collections.emptyList());
        f.pointer.setUnknowns(JsonUtils.mapper().createArrayNode());f.pointer.setSourceSpans(JsonUtils.mapper().createArrayNode());f.pointer.setRelations(JsonUtils.mapper().createArrayNode());return f;
    }
    private VectorStore.SearchHit hit(Fixture... fixtures){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("projectId","authored-project");payload.put("documentId","31");payload.put("sourceHash",PARSED);payload.put("sourceRole","tender");
        payload.put("fileLabel","Authored source");payload.put("anchor","body/authored");payload.put("scoreKind","authored_test");
        List<ObjectNode> members=new ArrayList<>();List<EvidencePointer> pointers=new ArrayList<>();List<String> text=new ArrayList<>();
        for(Fixture f:fixtures){members.add(f.member);pointers.add(f.pointer);text.add(f.text);}
        payload.put("canonicalMembers",members);payload.put("canonicalEvidence",pointers);payload.put("content",String.join("\n",text));
        ObjectNode unit=JsonUtils.mapper().createObjectNode();unit.put("unitId","authored-source-unit");unit.put("status","complete_observed_unit");unit.put("qualifiersComplete","unknown");
        payload.put("canonicalSourceUnit",unit);return new VectorStore.SearchHit("authored-hit",.5,payload);
    }
}
