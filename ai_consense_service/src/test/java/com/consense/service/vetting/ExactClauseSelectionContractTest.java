package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Authored payloads and scores only; no retrieval, models, HTTP or database. */
class ExactClauseSelectionContractTest {
    private static final class Fixture {
        final Map<String,Chunk> chunks=new LinkedHashMap<>();
        final Map<String,JsonNode> parents=new LinkedHashMap<>();
        final ObjectNode response=JsonUtils.mapper().createObjectNode(),ranking=JsonUtils.mapper().createObjectNode(),row=JsonUtils.mapper().createObjectNode();
        Fixture() {
            VettingRetrievalSourceUnitsTest authored=new VettingRetrievalSourceUnitsTest();
            Chunk exact=authored.c("exact","edition-a","tender","AAA.1","body:1","AAA.1 Required cover is 40 mm.");
            Chunk other=authored.c("other","edition-b","tender","BBB.1","body:1","BBB.1 Other source requirement.");
            for(Chunk chunk:Arrays.asList(exact,other)){chunks.put(chunk.getId(),chunk);parents.put(chunk.getId(),VettingRetrievalClient.normalizedPayload(chunk));}
            ObjectNode episode=response.putObject("sourceUnits");
            episode.put("policy",VettingRetrievalSourceUnits.POLICY);episode.put("scope","canonical_observed_structure_only");
            episode.put("memberOrder","canonical_parent_id_lexical");episode.put("qualifiersComplete","unknown");
            episode.put("derivedMembersCanBecomeOrigins",false);episode.put("semanticScopeVerified",false);
            episode.set("originalHitIds",JsonUtils.mapper().valueToTree(Arrays.asList("exact","other")));
            episode.set("rawParentTopKIds",episode.path("originalHitIds"));
            ObjectNode priority=episode.putObject("ranking");priority.put("policy",VettingRetrievalSourceUnits.EXACT_CLAUSE_POLICY);priority.put("identifier","aaa.1");
            priority.set("modelScoreOrderIds",JsonUtils.mapper().valueToTree(Arrays.asList("other","exact")));
            priority.set("selectionOrderIds",episode.path("originalHitIds"));priority.putArray("exactClauseCandidateIds").add("exact");
            ArrayNode hits=response.putArray("hits"),units=episode.putArray("units");
            int selected=0;for(String id:Arrays.asList("exact","other")) {
                selected++;double score="exact".equals(id)?.2:.9;
                ObjectNode hit=hits.addObject();hit.put("id",id);hit.put("score",score);hit.set("payload",parents.get(id));
                ObjectNode unit=OerCanonicalSourceUnits.resolveObserved(parents,parents.get(id));unit.put("seedScore",score);
                unit.put("originalRerankOrdinal","exact".equals(id)?2:1);unit.put("selectionOrdinal",selected);units.add(unit);
            }
            ranking.putArray("allRerankedInTrueScoreOrder").addObject().put("id","other").put("score",.9);
            ((ArrayNode)ranking.path("allRerankedInTrueScoreOrder")).addObject().put("id","exact").put("score",.2);
            ranking.set("ranking",priority);ranking.set("selectionOrderIds",priority.path("selectionOrderIds"));
            ranking.set("returnedIds",episode.path("originalHitIds"));ranking.set("rawParentTopKIds",episode.path("rawParentTopKIds"));
            ranking.put("selectionPolicy",VettingRetrievalSourceUnits.POLICY);
            row.putObject("request").put("query","  aAa.1  ").put("role","tender").put("limit",2);
        }
        void validateAll()throws IOException {
            List<Chunk> selected=new ArrayList<>(chunks.values());
            VettingRetrievalSourceUnits.read(response,selected,chunks,"tender",true);
            OerCanonicalSourceUnits.validate(response,parents,Arrays.asList("exact","other"));
            VettingInspectionRetrievalBinding.verifySource44Episode(response,row,ranking,new LinkedHashMap<>());
        }
    }
    @Test void exactPriorityPreservesModelRanksScoresAndExplicitSelectionAcrossAllReaders()throws Exception {
        Fixture fixture=new Fixture();fixture.validateAll();
        assertEquals(2,fixture.response.path("sourceUnits").path("units").get(0).path("originalRerankOrdinal").asInt());
        assertEquals(1,fixture.response.path("sourceUnits").path("units").get(0).path("selectionOrdinal").asInt());
    }
    @Test void forgedPriorityOrdersAndSourceClaimsFailEveryReader() {
        for(String mutation:Arrays.asList("missing-policy","unknown-policy","model-rank","selection-rank","unstable-order","unbound-clause","context-label","non-identifier")) {
            Fixture fixture=new Fixture();ObjectNode episode=(ObjectNode)fixture.response.path("sourceUnits"),priority=(ObjectNode)episode.path("ranking"),unit=(ObjectNode)episode.path("units").get(0);
            if("missing-policy".equals(mutation))episode.remove("ranking");
            if("unknown-policy".equals(mutation))priority.put("policy","unreviewed_priority");
            if("model-rank".equals(mutation))unit.put("originalRerankOrdinal",1);
            if("selection-rank".equals(mutation))unit.put("selectionOrdinal",2);
            if("unstable-order".equals(mutation))priority.set("selectionOrderIds",priority.path("modelScoreOrderIds"));
            if("unbound-clause".equals(mutation))priority.put("identifier","aaa.2");
            if("context-label".equals(mutation))((ObjectNode)fixture.response.path("hits").get(0).path("payload")).put("observedNodeKind","context");
            if("non-identifier".equals(mutation))priority.put("identifier","aaa.1 requirements");
            assertThrows(IllegalStateException.class,()->VettingRetrievalSourceUnits.read(fixture.response,new ArrayList<>(fixture.chunks.values()),fixture.chunks,"tender",true),mutation);
            assertThrows(IllegalStateException.class,()->OerCanonicalSourceUnits.validate(fixture.response,fixture.parents,Arrays.asList("exact","other")),mutation);
            assertThrows(IOException.class,()->VettingInspectionRetrievalBinding.verifySource44Episode(fixture.response,fixture.row,fixture.ranking,new LinkedHashMap<>()),mutation);
        }
    }
    @Test void inspectionCannotBindExactPriorityToAnotherQueryOrPureScoreOrder() {
        Fixture fixture=new Fixture();fixture.row.with("request").put("query","aaa.2");
        assertThrows(IOException.class,()->VettingInspectionRetrievalBinding.verifySource44Episode(fixture.response,fixture.row,fixture.ranking,new LinkedHashMap<>()));
        Fixture wrongOrder=new Fixture();wrongOrder.ranking.set("selectionOrderIds",wrongOrder.ranking.path("ranking").path("modelScoreOrderIds"));
        assertThrows(IOException.class,()->VettingInspectionRetrievalBinding.verifySource44Episode(wrongOrder.response,wrongOrder.row,wrongOrder.ranking,new LinkedHashMap<>()));
    }
}
