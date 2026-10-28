package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Application drawing protocol. Structural validation does not establish contractual correctness. */
final class AdviceFlowchartTool {
    static final String INSTRUCTION = "Create a draft flowchart using the application draw_flowchart tool. "
            + "Return one JSON object with tool=draw_flowchart, nodes (id, label), edges (from, to, label) and citations. "
            + "Use concise plain-text labels. Put branch conditions, responsible actors and deadline start events in labels when supported. "
            + "Preserve separate tracks and exceptions. Use only current supplied evidence; prior answers are not evidence. "
            + "Citations are the supplied aliases of sources consulted, not proof that the diagram is legally correct. "
            + "Do not invent sources, project adoption or missing facts. Label unknown steps as unknown. "
            + "If more current source text is needed, request up to four targeted evidenceRequests (citation, kind=clause/adjacent/table, clause, reason). "
            + "The application will retrieve these on demand. Still draw the supported partial process, marking missing steps. "
            + "Do not return answer sections, self-checks, Mermaid source, Markdown or provider tool_calls. "
            + "The application renders and labels this as an unverified draft, with no complete legal conclusion.";

    static String system(List<CitationVO> sources,String question){return INSTRUCTION+"\nTool schema:\n"+schema(sources)+AdvisoryHarness.languageInstruction(question);}

    static JsonNode schema(List<CitationVO> sources) {
        ObjectNode schema=(ObjectNode)JsonUtils.parse("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"tool\",\"nodes\",\"edges\",\"citations\"],\"properties\":{"
                +"\"tool\":{\"type\":\"string\",\"enum\":[\"draw_flowchart\"]},"
                +"\"nodes\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":64,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"label\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_-]{0,63}$\"},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":240}}}},"
                +"\"edges\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":128,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"from\",\"to\",\"label\"],\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"label\":{\"type\":\"string\",\"maxLength\":240}}}},"
                +"\"citations\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"type\":\"string\"}}}}");
        JsonNode advice=AdvisoryHarness.schema(sources);
        schema.with("properties").set("evidenceRequests",advice.path("properties").path("evidenceRequests"));
        schema.with("properties").with("citations").set("items",advice.path("properties").path("sections").path("items").path("properties").path("citations").path("items"));
        return schema;
    }

    static final class Result {
        private final String mermaid;
        private final List<String> citations;
        private final List<AdviceService.EvidenceRequest> requests;
        Result(String mermaid,List<String> citations,List<AdviceService.EvidenceRequest> requests){this.mermaid=mermaid;this.citations=citations;this.requests=requests;}
        String getMermaid(){return mermaid;}
        List<String> getCitations(){return citations;}
        List<AdviceService.EvidenceRequest> getEvidenceRequests(){return requests;}
        String content(boolean referenceOnly) {
            return "Draft flowchart - not independently verified\n\n"
                    +(referenceOnly?"Standard reference interpretation; project adoption is not established.":"Draft based on the selected project scope; applicability and legal conclusions remain unverified.")
                    +"\n\n"+mermaid+(citations.isEmpty()?"\n\nNo source citations were supplied for this draft.":"\n\nSources consulted (not independent validation): "+String.join(" ",citations));
        }
    }

    static Result render(String raw,List<String> allowed) {
        if(raw==null||raw.length()>65536)throw AdvisoryHarness.invalid("Flowchart tool output is missing or too large");
        JsonNode call;
        try(com.fasterxml.jackson.core.JsonParser parser=JsonUtils.mapper().getFactory().createParser(raw)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            call=JsonUtils.mapper().readTree(parser);
            if(call==null||parser.nextToken()!=null)throw AdvisoryHarness.invalid("Flowchart tool requires one JSON object");
        } catch(java.io.IOException invalid){throw AdvisoryHarness.invalid("Flowchart tool requires valid JSON");}
        List<CitationVO> sources=new ArrayList<>();for(String alias:allowed){CitationVO source=new CitationVO();source.setEvidenceId(alias);sources.add(source);}
        AdvisoryHarness.shape(call,schema(sources));
        if(call.path("nodes").size()<2||call.path("edges").isEmpty())throw AdvisoryHarness.invalid("Flowchart needs at least two nodes and one edge");
        for(String alias:AdvisoryHarness.explicitBodyAliases(JsonUtils.write(call)))if(!allowed.contains(alias))throw AdvisoryHarness.invalid("Flowchart contains an unknown source alias");
        Map<String,String> ids=new LinkedHashMap<>();StringBuilder graph=new StringBuilder("```mermaid\nflowchart TD\n");
        for(JsonNode node:call.path("nodes")) {
            String id=node.path("id").asText();String safe="n"+ids.size();
            if(ids.putIfAbsent(id,safe)!=null)throw AdvisoryHarness.invalid("Flowchart node IDs must be unique");
            if(!AdvisoryHarness.text(node.path("label").asText()))throw AdvisoryHarness.invalid("Flowchart node labels cannot be blank");
            graph.append(safe).append("[\"").append(label(node.path("label").asText())).append("\"]\n");
        }
        for(JsonNode edge:call.path("edges")) {
            String from=ids.get(edge.path("from").asText()),to=ids.get(edge.path("to").asText());
            if(from==null||to==null)throw AdvisoryHarness.invalid("Flowchart edge refers to an unknown node");
            String label=label(edge.path("label").asText());
            graph.append(from).append(label.isEmpty()?" --> ":" -->|\""+label+"\"| ").append(to).append('\n');
        }
        List<String> citations=new ArrayList<>();for(JsonNode alias:call.path("citations"))citations.add(alias.asText());
        citations=AdvisoryHarness.refs(citations,sources);
        List<AdviceService.EvidenceRequest> requests=new ArrayList<>();
        for(JsonNode request:call.path("evidenceRequests"))requests.add(JsonUtils.mapper().convertValue(request,AdviceService.EvidenceRequest.class));
        String mermaid=graph.append("```").toString();
        if(mermaid.contains("%%{")||mermaid.matches("(?s).*@\\s*\\{.*")
                ||java.util.regex.Pattern.compile("(?im)(?:^|;)\\s*(?:click|classDef|style|linkStyle)\\b").matcher(mermaid).find())
            throw AdvisoryHarness.invalid("Flowchart labels contain a directive rejected by the diagram renderer");
        return new Result(mermaid,citations,requests);
    }
    private static String label(String text) {
        if(text.matches("(?s).*[<>`\\p{Cntrl}].*"))throw AdvisoryHarness.invalid("Flowchart labels must be safe single-line text");
        return text.replace("#","#35;").replace("&","#38;").replace("\"","#quot;").replace("|","/").replace("\\","/");
    }
}
