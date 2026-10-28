package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftBusinessRules.*;
import static com.consense.service.drafting.DraftReviewGraph.map;
import static com.consense.service.drafting.DraftReviewGraph.obj;
import static org.junit.jupiter.api.Assertions.*;

class DraftRequirementQuoteRepairTest {
    @Test void onlyAUniqueRejectedQuoteWithValidKindOwnerAndNodeReferencesEntersRepair(){
        ObjectNode raw=response("GCC Clause 1.1...");
        assertEquals(Collections.singleton("req_marker"),DraftAutomaticDiscovery.requirementQuoteRepairIds(evaluated(),raw,payload()));
        for(String field:Arrays.asList("kind","sourceTaskId")){
            ObjectNode wrong=raw.deepCopy();((ObjectNode)wrong.path("requirements").get(0)).put(field,"foreign");
            assertTrue(DraftAutomaticDiscovery.requirementQuoteRepairIds(evaluated(),wrong,payload()).isEmpty());
        }
        ObjectNode wrongNode=raw.deepCopy();((ObjectNode)wrongNode.path("requirements").get(0)).putArray("nodeIds").add("unknown_input");
        assertTrue(DraftAutomaticDiscovery.requirementQuoteRepairIds(evaluated(),wrongNode,payload()).isEmpty());
        ObjectNode duplicate=raw.deepCopy();duplicate.withArray("requirements").add(raw.path("requirements").get(0).deepCopy());
        assertTrue(DraftAutomaticDiscovery.requirementQuoteRepairIds(evaluated(),duplicate,payload()).isEmpty());
    }

    @Test void aNamedQuoteRepairPreservesTheOriginalAndRecordsBothHashes(){
        ObjectNode previous=response("GCC Clause 1.1..."),next=response("GCC Clause 1.1 and works contractor selected by the Contractor");
        String original=previous.toString();List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();
        JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,issues,normalizations,repairs(previous));
        assertEquals(next.path("requirements"),merged.path("requirements"));assertTrue(issues.isEmpty());
        assertEquals(original,previous.toString());assertEquals(1,normalizations.size());
        Map<String,Object> receipt=map(normalizations.get(0));assertEquals("requirement_quote_targeted_repair",receipt.get("kind"));
        assertNotEquals(receipt.get("previousRequirementSha256"),receipt.get("replacementRequirementSha256"));
    }

    @Test void evidenceOnlyRoundsCannotOverwriteAnExistingQuote(){
        ObjectNode previous=response("GCC Clause 1.1..."),next=response("A continuous original passage");
        List<Object> issues=new ArrayList<>(),normalizations=new ArrayList<>();
        JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,issues,normalizations,Collections.emptyList());
        assertEquals(previous.path("requirements"),merged.path("requirements"));assertFalse(issues.isEmpty());assertTrue(normalizations.isEmpty());
    }

    @Test void quoteRepairCannotChangeItsBusinessMapping(){
        ObjectNode previous=response("GCC Clause 1.1...");
        for(String field:Arrays.asList("sourceTaskId","kind","nodeIds")){
            ObjectNode next=response("A continuous original passage"),requirement=(ObjectNode)next.path("requirements").get(0);
            if("nodeIds".equals(field))requirement.putArray(field).add("other_input");else requirement.put(field,"foreign");
            List<Object> normalizations=new ArrayList<>();
            JsonNode merged=DraftAutomaticDiscovery.mergeGroupDraft(previous,next,new ArrayList<>(),normalizations,repairs(previous));
            assertEquals(previous.path("requirements").get(0),merged.path("requirements").get(0),field);
            assertTrue(normalizations.stream().map(DraftReviewGraph::map).noneMatch(row->"requirement_quote_targeted_repair".equals(row.get("kind"))),field);
        }
    }

    @Test void anEvidenceRoundEchoDoesNotSpendTheQuoteRepairAndAttemptsArePerId(){
        ObjectNode previous=response("GCC Clause 1.1...");ObjectNode second=(ObjectNode)previous.path("requirements").get(0).deepCopy();second.put("id","req_second");previous.withArray("requirements").add(second);
        List<Object> repairs=Collections.singletonList(obj("sourceTaskId","task_marker","requirementIds",Arrays.asList("req_marker","req_second")));
        assertTrue(DraftAutomaticDiscovery.attemptedRequirementQuoteRepairIds(previous,previous.deepCopy(),repairs).isEmpty());
        ObjectNode next=previous.deepCopy();((ObjectNode)next.path("requirements").get(0)).put("sourceQuote","The exact current passage");
        assertEquals(Collections.singleton("req_marker"),DraftAutomaticDiscovery.attemptedRequirementQuoteRepairIds(previous,next,repairs));
    }

    @Test void onlyTheActiveRepairOwnersRetainTheirAlreadyFetchedEvidence(){
        Map<String,Object> first=obj("kind","member","id","m1","text","first source passage"),second=obj("kind","member","id","m2","text","second source passage");
        Map<String,Object> originalContext=obj("items",Collections.singletonList(second),"editableAuthority",false),supplement=obj("currentSourceContext",Collections.singletonList(obj("sourceTaskId","task_marker","context",originalContext)));
        Map<String,List<Object>> retained=new LinkedHashMap<>();retained.put("task_marker",Arrays.asList(first,second));retained.put("other_owner",Collections.singletonList(obj("id","foreign")));
        DraftAutomaticDiscovery.retainRepairEvidence(supplement,Collections.singletonList(obj("sourceTaskId","task_marker")),retained);
        List<?> contexts=list(supplement.get("currentSourceContext"));assertEquals(1,contexts.size());Map<String,Object> context=map(map(contexts.get(0)).get("context"));
        assertEquals(Arrays.asList(first,second),context.get("items"));assertEquals(false,context.get("editableAuthority"));assertEquals(Collections.singletonList(second),originalContext.get("items"));
    }

    private static ObjectNode response(String quote){
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("nodes");result.putArray("scopeRules");result.putArray("taskOutcomes");result.putArray("notes");
        ObjectNode requirement=result.putArray("requirements").addObject().put("id","req_marker").put("sourceTaskId","task_marker").put("sourceQuote",quote).put("kind","verification").put("reason","Verify the original obligation");
        requirement.putArray("nodeIds").add("subcontractArrangement");return result;
    }
    private static Map<String,Object> payload(){return obj("knownRoots",Collections.singletonList(obj("id","subcontractArrangement")));}
    private static Map<String,Object> evaluated(){return obj("taskReceipts",Collections.singletonList(obj("taskId","task_marker","issues",Collections.singletonList(obj("requirementId","req_marker","reason","unsupported_requirement_quote_kind_or_node_reference","quoteDiagnostic",obj("reason","requirement_quote_not_in_authorized_evidence"))))));}
    private static List<Object> repairs(JsonNode response){return DraftAutomaticDiscovery.requirementQuoteRepairRequests(evaluated(),response,payload());}
}
