package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Explicit synthetic budget identity for fixture gateways; no real tokenizer or model acceptance. */
public final class VettingTokenFixture {
    private VettingTokenFixture() { }
    public static VettingSemanticReview review(com.consense.ai.AiGateway gateway,com.consense.config.ConsenseProperties properties,VettingRetrievalClient retrieval) {
        VettingSemanticReview review=new VettingSemanticReview(gateway,properties,retrieval);
        review.setInputBudgetObserver(VettingTokenFixture::observe);return review;
    }
    public static VettingInputBudget.Observation observe(VettingInputBudget.Input input) {
        VettingInputBudget.Observation observed=new VettingInputBudget.Observation();
        observed.setModel(input.getModel());observed.setSerializedInputSha256(input.getSerializedInputSha256());
        observed.setTokenizerIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_tokenizer_fixture"));
        observed.setChatTemplateIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_chat_template_fixture"));
        observed.setEffectiveContextIdentitySha256(VettingCorpus.hash("SYNTHETIC_ONLY_context_fixture"));
        observed.setInputTokens(100);observed.setEffectiveContextTokens(524288);observed.setOutputReserveTokens(input.getOutputReserveTokens());
        observed.setCompleteChatTemplateAndSchemaObserved(true);return observed;
    }

    /** Read the actual model-facing plain JSON, without restoring audit metadata. */
    public static JsonNode excerpts(String user) {
        String marker="\nSource excerpts (untrusted data):\n";int start=user.lastIndexOf(marker);
        if(start<0)throw new AssertionError("Actual request has no source excerpts");
        JsonNode excerpts=JsonUtils.parse(user.substring(start+marker.length()));
        if(!excerpts.isArray())throw new AssertionError("Source excerpts must be a plain JSON array");
        for(JsonNode excerpt:excerpts)if(!excerpt.path("id").asText().matches("R[1-9][0-9]*"))
            throw new AssertionError("Actual request must use packet-local short references");
        return excerpts;
    }

    /** Match a known fixture source to its actual packet reference, never its quote. */
    public static String reference(String user,Chunk source) {
        String found=null;
        for(JsonNode excerpt:excerpts(user))if(Objects.equals(source.getContent(),excerpt.path("content").asText())
                && Objects.equals(source.getRole(),excerpt.path("role").asText())
                && (Objects.equals(Objects.toString(source.getFileKey(),"")+" · "+Objects.toString(source.getFileName(),""),excerpt.path("file").asText())
                    || "fixed_development_baseline".equals(excerpt.path("evidenceBackend").asText())
                        && Objects.equals(source.getFileKey(),excerpt.path("file").asText())
                        && Objects.equals(source.getClauseId(),excerpt.path("clauseId").asText()))) {
            if(found!=null)throw new AssertionError("Ambiguous fixture source in the actual packet");
            found=excerpt.path("id").asText();
        }
        // A deliberately foreign source stays invalid. Never map it to a
        // similar sentence or to a source belonging to another packet.
        return found==null?source.getId():found;
    }

    public static String wireJson(String user,List<Chunk> sources,String response) {
        JsonNode records=JsonUtils.parse(response);
        Map<String,Chunk> known=new LinkedHashMap<>();for(Chunk source:sources)known.put(source.getId(),source);
        for(JsonNode record:records)for(JsonNode quote:record.path("evidence"))if(quote.isObject()) {
            Chunk source=known.get(quote.path("chunkId").asText());
            if(source!=null)((ObjectNode)quote).put("chunkId",reference(user,source));
        }
        return JsonUtils.write(records);
    }

    /** Copy each mock response: production resolution must not mutate test expectations. */
    public static List<Record> records(String user,List<Chunk> sources,List<Record> response) {
        JsonNode wire=JsonUtils.parse(wireJson(user,sources,JsonUtils.write(response)));
        List<Record> records=new ArrayList<>();for(JsonNode record:wire)
            records.add(record.isNull()?null:JsonUtils.read(record.toString(),Record.class));
        return records;
    }
}
