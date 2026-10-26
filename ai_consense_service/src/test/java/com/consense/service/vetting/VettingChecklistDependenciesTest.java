package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.VettingDtos.SemanticPacketVO;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored closed scope inputs at the trusted producer seam; no parser/model/source-grade acceptance. */
class VettingChecklistDependenciesTest {
    @Test void actualChecklistBasisUsesClosedItemMembersWhileChangedOtherResultsAndSourceSnapshotRemainSeparate() throws Exception {
        VettingChecklistCatalog.Item a=item("APP-authored-A","PRE.B3.040"),b=item("APP-authored-B","PRE.B4.030");
        List<VettingChecklistDependencies.Scope> scopes=Arrays.asList(scope("PRE.B3.040","alpha","old-a","71"),scope("PRE.B4.030","beta","old-b","71"));
        List<VettingChecklistDependencies.Scope> updated=Arrays.asList(scope("PRE.B3.040","CHANGED alpha","new-a","71"),scope("PRE.B4.030","beta","new-b","71"));
        com.consense.domain.VettingRun first=run(a,derive(a,scopes,JsonUtils.parse("{}")),b,derive(b,scopes,JsonUtils.parse("{}")));
        com.consense.domain.VettingRun next=run(a,derive(a,updated,JsonUtils.parse("{}")),b,derive(b,updated,JsonUtils.parse("{}")));
        next.setSourceSnapshotSha256(VettingCorpus.hash("actual changed overall source generation"));next.setResultJson("{\"total\":4}");
        java.lang.reflect.Method basis=VettingChecklistService.class.getDeclaredMethod("basis",com.consense.domain.VettingRun.class,VettingChecklistCatalog.Item.class,JsonNode.class,boolean.class);basis.setAccessible(true);
        assertNotEquals(basis.invoke(null,first,a,null,false),basis.invoke(null,next,a,null,false));assertEquals(basis.invoke(null,first,b,null,false),basis.invoke(null,next,b,null,false));
        next.setModelIdentityJson(next.getModelIdentityJson().replace("authored-model","changed-authored-model"));assertNotEquals(basis.invoke(null,first,b,null,false),basis.invoke(null,next,b,null,false));
        JsonNode automatic=JsonUtils.parse("{\"status\":\"fulfilled\",\"evidence\":[\"authored original\"]}");
        assertNotEquals(basis.invoke(null,first,b,automatic,true),basis.invoke(null,first,b,JsonUtils.parse("{\"status\":\"not_fulfilled\"}"),true));
        assertNotEquals(basis.invoke(null,first,b,automatic,true),basis.invoke(null,first,b,null,true),"Unknown automatic scope cannot use a closed member-only basis");
        assertNull(VettingChecklistDependencies.itemLedger(first.getCoverageJson(),"different-project",b));
    }
    @Test void currentCanonicalSessionActuallyProducesScopedLedgerAndChangedUnknownProofFallsBack() throws Exception {
        OerBusinessEvidenceTest.Wire wire=new OerBusinessEvidenceTest.Wire(true);
        wire.document.setCategory(com.consense.domain.SourceDocument.CATEGORY_VETTING_PACKAGE);wire.document.setFileName("authored-current-slot.docx");
        wire.document.setReviewRole("tender");wire.document.setDocumentScope("package");wire.document.setParseStatus("PARSED");wire.document.setSourceSha256(wire.binding.getOriginalSourceSha256());
        wire.document.setOerIdentityJson("{\"sourceSha256\":\""+wire.binding.getOriginalSourceSha256()+"\",\"kind\":\"PRE\",\"version\":\"AUTHORED-edition\",\"identityStatus\":\"sha_verified\",\"sourcePurpose\":\"project_document\",\"role\":\"tender\",\"eligibleModules\":[\"vetting\",\"advisory\"]}");
        ((com.fasterxml.jackson.databind.node.ArrayNode)wire.snapshot.get("unknowns")).removeAll();
        for(JsonNode chunk:wire.snapshot.path("chunks"))if("A1".equals(chunk.path("clauseId").asText())){
            ((com.fasterxml.jackson.databind.node.ObjectNode)chunk).put("clauseId","PRE.B3.040");
            ((com.fasterxml.jackson.databind.node.ObjectNode)chunk.path("adoption").path("clause")).put("clauseId","PRE.B3.040");
        }
        wire.store();OerBusinessEvidence.Session unresolved=wire.load();
        JsonNode unresolvedLedger=VettingChecklistDependencies.capture("project",unresolved,Collections.singletonList(wire.document),Collections.singletonList(wire.document),Collections.emptyList(),Collections.emptyList());
        assertEquals("required_member_dependencies_unknown",unresolvedLedger.path("items").path("APP-I-011").path("status").asText(),"The original authored wire deliberately retains unknown relation qualification");
        // This positive fixture authors an independent complete clause. It never upgrades the unknown original wire or claims source-grade acceptance.
        for(JsonNode chunk:wire.snapshot.path("chunks"))if("PRE.B3.040".equals(chunk.path("clauseId").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)chunk).putArray("relations");
        wire.store();OerBusinessEvidence.Session session=wire.load();
        JsonNode ledger=VettingChecklistDependencies.capture("project",session,Collections.singletonList(wire.document),Collections.singletonList(wire.document),Collections.emptyList(),Collections.emptyList());
        assertEquals("required_member_scope_closed",ledger.path("items").path("APP-I-011").path("status").asText());
        assertFalse(ledger.toString().contains("legalComplianceVerified"));
        wire.document.setOerIdentityJson(wire.document.getOerIdentityJson().replace("sha_verified","unknown"));
        JsonNode unknown=VettingChecklistDependencies.capture("project",session,Collections.singletonList(wire.document),Collections.singletonList(wire.document),Collections.emptyList(),Collections.emptyList());
        assertEquals("required_member_dependencies_unknown",unknown.path("items").path("APP-I-011").path("status").asText());
    }
    @Test void oneParsedChecklistContextPreservesEveryHistoricalWholeGenerationBasis()throws Exception{
        VettingChecklistCatalog.Item a=item("APP-authored-A","PRE.B3.040"),b=item("APP-authored-B","PRE.B4.030");
        com.consense.domain.VettingRun run=run(a,JsonUtils.parse("{}"),b,JsonUtils.parse("{}"));
        run.setCoverageJson("{\"authoredText\":\"Escaped \\\"quote\\\" and line\\nnext\",\"unrelated\":[]}");
        Class<?> contextType=Class.forName(VettingChecklistService.class.getName()+"$ChecklistContext");
        java.lang.reflect.Constructor<?> constructor=contextType.getDeclaredConstructor(com.consense.domain.VettingRun.class);constructor.setAccessible(true);Object context=constructor.newInstance(run);
        java.lang.reflect.Method basis=contextType.getDeclaredMethod("basis",VettingChecklistCatalog.Item.class,JsonNode.class);basis.setAccessible(true);
        java.lang.reflect.Field parsed=contextType.getDeclaredField("coverage");parsed.setAccessible(true);Object before=parsed.get(context);
        for(VettingChecklistCatalog.Item requirement:VettingChecklistCatalog.items()){
            String expected=VettingCorpus.hash(JsonUtils.write(Arrays.asList("manual-check-basis-v1",run.getProjectId(),run.getSourceSnapshotSha256(),run.getCoverageJson(),run.getResultJson(),run.getModelIdentityJson(),run.getRecoveryContractSha256(),
                    requirement.getId(),requirement.getSection(),requirement.getClause(),requirement.getDescription(),requirement.getReviewScope(),requirement.getScopeNote())));
            assertEquals(expected,basis.invoke(context,requirement,null),requirement.getId());assertSame(before,parsed.get(context));
        }
    }

    @Test void anotherClauseRevisionKeepsOnlyTheUnchangedItemsRequiredMemberBasis() {
        VettingChecklistCatalog.Item a=item("APP-I-authored-A","PRE.B3.040"),b=item("APP-I-authored-B","PRE.B4.030");
        List<VettingChecklistDependencies.Scope> first=Arrays.asList(scope("PRE.B3.040","alpha","original-a","71"),scope("PRE.B4.030","beta","original-b","71"));
        List<VettingChecklistDependencies.Scope> next=Arrays.asList(scope("PRE.B3.040","CHANGED alpha","new-a","71"),scope("PRE.B4.030","beta","new-b","71"));
        JsonNode oldA=derive(a,first,JsonUtils.parse("{}")),oldB=derive(b,first,JsonUtils.parse("{}")),newA=derive(a,next,JsonUtils.parse("{}")),newB=derive(b,next,JsonUtils.parse("{}"));
        assertEquals("required_member_scope_closed",oldA.path("status").asText());assertEquals("required_member_scope_closed",newB.path("status").asText());
        assertNotEquals(oldA.path("basisSha256"),newA.path("basisSha256"));assertEquals(oldB.path("basisSha256"),newB.path("basisSha256"));
        assertNotEquals(oldB.path("requiredMemberRefs"),newB.path("requiredMemberRefs"),"Actual current original/revision references stay distinct beside unchanged material comparison");
    }

    @Test void missingAmbiguousAndUnclosedPacketContextCannotAuthorizeLocalReuse() {
        VettingChecklistCatalog.Item item=item("APP-I-authored","PRE.B3.040");VettingChecklistDependencies.Scope scope=scope("PRE.B3.040","alpha","id-a","71");
        assertEquals("required_member_dependencies_unknown",derive(item,Collections.emptyList(),JsonUtils.parse("{}")).path("status").asText());
        assertEquals("required_member_dependencies_unknown",derive(item,Arrays.asList(scope,scope("PRE.B3.040","alpha","id-b","72")),JsonUtils.parse("{}")).path("status").asText());
        SemanticPacketVO packet=packet("id-a");packet.setSubmittedChunkIds(Arrays.asList("id-a","unclosed-extra-context"));
        assertEquals("required_member_dependencies_unknown",VettingChecklistDependencies.derive(item,"p",Collections.singletonList(scope),JsonUtils.parse("{}"),Collections.emptyList(),Collections.singletonList(packet)).path("status").asText());
    }

    @Test void factModelCheckSourceProofAndLogicalSourceChangesInvalidateTheRightBasis() {
        VettingChecklistCatalog.Item item=item("APP-I-authored","PRE.B3.040");VettingChecklistDependencies.Scope scope=scope("PRE.B3.040","alpha","id-a","71");
        JsonNode first=derive(item,Collections.singletonList(scope),JsonUtils.parse("{\"factSha256\":\"first\"}"));
        assertNotEquals(first.path("basisSha256"),derive(item,Collections.singletonList(scope),JsonUtils.parse("{\"factSha256\":\"changed\"}")).path("basisSha256"));
        assertNotEquals(first.path("basisSha256"),derive(item,Collections.singletonList(scope("PRE.B3.040","alpha","id-a","72")),JsonUtils.parse("{\"factSha256\":\"first\"}")).path("basisSha256"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)scope.material.get(0).path("adoption")).put("reviewStatus","unknown");
        assertEquals("required_member_dependencies_unknown",derive(item,Collections.singletonList(scope),JsonUtils.parse("{}")).path("status").asText());
        scope=scope("PRE.B3.040","alpha","id-a","71");SemanticPacketVO failed=packet("id-a");failed.setStatus("failed");
        assertEquals("required_member_dependencies_unknown",VettingChecklistDependencies.derive(item,"p",Collections.singletonList(scope),JsonUtils.parse("{}"),Collections.emptyList(),Collections.singletonList(failed)).path("status").asText());
        assertEquals("required_member_dependencies_unknown",VettingChecklistDependencies.derive(item,"other-project",Collections.singletonList(scope),JsonUtils.parse("{}"),Collections.emptyList(),Collections.emptyList()).path("status").asText());
    }

    @Test void broadOrCrossClauseRequirementsAndNormalCandidatesStayOnWholeGenerationFallback() {
        assertEquals("required_member_dependencies_unknown",derive(new VettingChecklistCatalog.Item("broad","GENERALLY","1. All clauses","All clauses",1),Collections.emptyList(),JsonUtils.parse("{}")).path("status").asText());
        assertEquals("required_member_dependencies_unknown",derive(new VettingChecklistCatalog.Item("cross","PRE.B3","PRE.B3.040","Compare PRE.B4.030",1),Collections.singletonList(scope("PRE.B3.040","alpha","id-a","71")),JsonUtils.parse("{}")).path("status").asText());
        JsonNode ledger=VettingChecklistDependencies.capture("p",null,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.emptyList());
        assertEquals("required_member_dependencies_unknown",ledger.path("items").path("APP-I-011").path("status").asText());
    }

    @Test void unknownNoteBranchOrContinuationRelationsNeverCertifyAClosedDependencyScope() {
        VettingChecklistCatalog.Item item=item("APP-I-authored","PRE.B3.040");VettingChecklistDependencies.Scope scope=scope("PRE.B3.040","alpha","id-a","71");
        ((com.fasterxml.jackson.databind.node.ObjectNode)scope.material.get(0)).set("relations",JsonUtils.parse("[{\"type\":\"note_scope\",\"status\":\"unknown\",\"toUnitId\":\"unread-neighbour\"}]"));
        assertEquals("required_member_dependencies_unknown",derive(item,Collections.singletonList(scope),JsonUtils.parse("{}")).path("status").asText());
    }

    private static JsonNode derive(VettingChecklistCatalog.Item item,List<VettingChecklistDependencies.Scope> scopes,JsonNode context){
        List<SemanticPacketVO> packets=new ArrayList<>();for(VettingChecklistDependencies.Scope scope:scopes)packets.add(packet(scope.members.get(0)));
        return VettingChecklistDependencies.derive(item,"p",scopes,context,Collections.emptyList(),packets);
    }
    private static VettingChecklistCatalog.Item item(String id,String clause){return new VettingChecklistCatalog.Item(id,"PRE.B3",clause,"Check this exact clause",1);}
    private static com.consense.domain.VettingRun run(VettingChecklistCatalog.Item a,JsonNode rowA,VettingChecklistCatalog.Item b,JsonNode rowB){
        com.consense.domain.VettingRun run=new com.consense.domain.VettingRun();run.setProjectId("p");run.setStatus("COMPLETED");run.setResultJson("{\"total\":0}");run.setSourceSnapshotSha256(VettingCorpus.hash("source generation"));
        run.setModelIdentityJson("{\"profileId\":\"local\",\"provider\":\"authored-wire\",\"model\":\"authored-model\",\"identityScope\":\"configured-profile\",\"configurationSha256\":\""+VettingCorpus.hash("authored model config")+"\"}");run.setRecoveryContractSha256(VettingCorpus.hash("authored recovery recipe"));
        com.fasterxml.jackson.databind.node.ObjectNode coverage=JsonUtils.mapper().createObjectNode(),ledger=coverage.putObject("checklistDependencyLedger"),items=ledger.putObject("items");ledger.put("producerVersion",VettingChecklistDependencies.VERSION);ledger.put("projectId","p");items.set(a.getId(),rowA);items.set(b.getId(),rowB);run.setCoverageJson(JsonUtils.write(coverage));return run;
    }
    private static SemanticPacketVO packet(String id){SemanticPacketVO packet=new SemanticPacketVO();packet.setSubmittedChunkIds(Collections.singletonList(id));packet.setStatus("completed_empty");packet.setKind("authored-independent-clause-check");packet.setActualGatewayCallStarted(true);packet.setCheckState("not_completed");return packet;}
    private static VettingChecklistDependencies.Scope scope(String clause,String text,String revision,String logicalSource){
        String sha=VettingCorpus.hash(revision);JsonNode material=JsonUtils.parse("[{\"unitId\":\""+clause+"\",\"content\":\""+text+"\",\"parts\":[{\"blockId\":\"native-"+revision+"\",\"text\":\""+text+"\",\"sourceSha256\":\""+sha+"\",\"sourcePosition\":{\"nodePath\":\"/"+clause+"\",\"partSha256\":\""+sha+"\"}}],\"adoption\":{\"reviewStatus\":\"explicit_source_reviewed_selection\",\"reviewedAttributes\":[\"text\",\"clauseHierarchy\"]}}]");
        return new VettingChecklistDependencies.Scope("p","PRE","AUTHORED-edition-v1","tender","p|source_document_id:"+logicalSource,clause,Collections.singletonList(revision),material,JsonUtils.parse("[{\"projectId\":\"p\",\"originalSourceSha256\":\""+sha+"\",\"memberId\":\""+revision+"\"}]"));
    }
}
