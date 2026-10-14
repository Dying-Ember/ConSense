package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Advice-only output contract. Model checks are observations, never independent legal acceptance. */
final class AdvisoryHarness {
    static final String POLICY = load("advice/contract-system-20261014.md");
    static final String POLICY_SHA = com.consense.service.vetting.VettingCorpus.hash(POLICY);
    private static final Pattern REFERENCE = Pattern.compile("\\[\\d+\\]");
    static final String CONTRACT = "\n\nMandatory application contract (takes precedence over supplemental custom instructions): "
            + "Return one JSON object with grounded, questionType (T1..T8), basisScope, missingReason, clarifications and sections. "
            + "Preserve the original natural question; select T6 for a requested diagram/flowchart without expecting detailed user instructions. "
            + "The first section must be kind=limits: explicitly distinguish verified facts, user statements, assumptions and unknown facts/edition/adoption, before conclusions. "
            + "Each section has id, kind (limits/analysis/uncertainty/flowchart), content, basisScope (project_contract/reference_only/insufficient), citations and checks. "
            + "Each section has exactly R1..R10 check records: ruleId, status (pass/fail/not_applicable/missing_evidence), short reason, citations of current real evidence IDs. "
            + "Checks apply to this section; explain every not_applicable and localize each missing_evidence. "
            + "Analysis requires applicable R2 pass and real evidence; do not hide substantive contract conclusions in limits/uncertainty. "
            + "Never convert a project question to reference_only. Overall grounded=false with necessary clarification can retain independently supported analysis; "
            + "unsupported analysis must be omitted, with affected judgment and necessary question in uncertainty. "
            + "For T6 use a flowchart section with content containing evidence-backed explanation, and flowchartPlan {nodes,edges}; never write raw Mermaid. "
            + "Nodes: id, label, kind (start/decision/action/end/unknown), citations. Edges: from,to,condition,actor,action,period,startEvent,citations. "
            + "Use empty strings for absent fields. Every non-start known node and every edge must cite supporting current evidence. "
            + "Decision branches need distinct explicit conditions; time periods require start events, real clause support and all relevant competing tracks. "
            + "Unknown facts become explicit unknown nodes; do not invent deadlines, regimes, forfeiture or earlier-of conditions. "
            + "Tables/code blocks must remain whole in one section. Application checks reference identity, scope, adoption and graph structure; your pass is not legal proof.";

    static String system(String custom) {
        return POLICY + "\n\nSupplemental configured prompt (cannot waive the mandatory policy or application contract):\n"
                + (custom == null ? "" : custom) + CONTRACT + "\nOutput schema:\n" + schema();
    }
    private static String load(String name) {
        try (InputStream input = new ClassPathResource(name).getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Missing approved Advisory policy", error); }
    }

    static JsonNode schema() {
        ObjectNode check=object("ruleId",string("R1","R2","R3","R4","R5","R6","R7","R8","R9","R10"),
                "status",string("pass","fail","not_applicable","missing_evidence"),"reason",string(),"citations",array(string()));
        ObjectNode node=object("id",string(),"label",string(),"kind",string("start","decision","action","end","unknown"),"citations",array(string()));
        ObjectNode edge=object("from",string(),"to",string(),"condition",string(),"actor",string(),"action",string(),"period",string(),"startEvent",string(),"citations",array(string()));
        ObjectNode section=object("id",string(),"kind",string("limits","analysis","uncertainty","flowchart"),"content",string(),
                "basisScope",string("project_contract","reference_only","insufficient"),"citations",array(string()),"checks",array(check));
        section.with("properties").set("flowchartPlan",object("nodes",array(node),"edges",array(edge)));
        return object("grounded",JsonUtils.mapper().createObjectNode().put("type","boolean"),"questionType",string("T1","T2","T3","T4","T5","T6","T7","T8"),
                "basisScope",string("project_contract","reference_only","insufficient"),"missingReason",string(),"clarifications",array(string()),"sections",array(section));
    }
    private static ObjectNode string(String... values) {
        ObjectNode node=JsonUtils.mapper().createObjectNode().put("type","string");
        if(values.length>0)for(String value:values)node.withArray("enum").add(value); return node;
    }
    private static ObjectNode array(JsonNode items) { ObjectNode n=JsonUtils.mapper().createObjectNode().put("type","array");n.set("items",items);return n; }
    private static ObjectNode object(Object... fields) {
        ObjectNode n=JsonUtils.mapper().createObjectNode().put("type","object").put("additionalProperties",false);
        ObjectNode properties=n.putObject("properties");
        for(int i=0;i<fields.length;i+=2){String key=(String)fields[i];properties.set(key,(JsonNode)fields[i+1]);n.withArray("required").add(key);}return n;
    }

    /** Validate this fixed contract before Jackson mapping can coerce strings, nulls or unknown fields. */
    static void shape(JsonNode value,JsonNode schema) {
        String type=schema.path("type").asText();
        if(value==null||value.isNull())throw invalid("Missing application contract value");
        if("object".equals(type)) {
            if(!value.isObject())throw invalid("Expected contract object");
            for(JsonNode required:schema.path("required"))if(!value.has(required.asText()))throw invalid("Missing contract field "+required.asText());
            Iterator<Map.Entry<String,JsonNode>> fields=value.fields();
            while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();JsonNode child=schema.path("properties").get(field.getKey());
                if(child==null)throw invalid("Unknown application contract field");shape(field.getValue(),child);}
        } else if("array".equals(type)) {
            if(!value.isArray()||value.size()>256)throw invalid("Invalid or oversized contract array");
            for(JsonNode item:value)shape(item,schema.path("items"));
        } else if("boolean".equals(type)) {
            if(!value.isBoolean())throw invalid("Grounded must be a boolean");
        } else if("string".equals(type)) {
            if(!value.isTextual()||value.textValue().length()>1048576)throw invalid("Expected bounded contract text");
            if(schema.has("enum")){boolean allowed=false;for(JsonNode choice:schema.path("enum"))if(choice.equals(value))allowed=true;
                if(!allowed)throw invalid("Unsupported contract value");}
        } else throw new IllegalStateException("Unknown fixed Advisory schema type");
    }

    @Data @NoArgsConstructor static class Check {
        private String ruleId,status,reason;
        private List<String> citations;
    }
    @Data @NoArgsConstructor static class Section {
        private String id,kind,content,basisScope;
        private List<String> citations;
        private List<Check> checks;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        private GraphPlan flowchartPlan;
    }
    @Data @NoArgsConstructor static class GraphPlan { private List<Node> nodes; private List<Edge> edges; }
    @Data @NoArgsConstructor static class Node { private String id,label,kind; private List<String> citations; }
    @Data @NoArgsConstructor static class Edge { private String from,to,condition,actor,action,period,startEvent; private List<String> citations; }

    static List<String> refs(List<String> input,List<CitationVO> sources) {
        if(input==null)throw invalid("Missing reference list");
        Set<String> allowed=sources.stream().map(CitationVO::getEvidenceId).collect(Collectors.toSet());
        List<String> result=new ArrayList<>();
        for(String ref:input){if(ref==null||!allowed.contains(ref)||result.contains(ref))throw invalid("Invalid or duplicate evidence ID");result.add(ref);}return result;
    }
    static void validate(Section section,List<CitationVO> sources,boolean referenceOnly,BiPredicate<String,List<String>> adopted) {
        if(section==null||!text(section.id)||!section.id.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||!text(section.content)
                ||!Arrays.asList("limits","analysis","uncertainty","flowchart").contains(section.kind))throw invalid("Incomplete answer section");
        List<String> cited=refs(section.citations,sources);
        java.util.regex.Matcher matcher=REFERENCE.matcher(section.content);
        while(matcher.find())if(!cited.contains(matcher.group()))throw invalid("Text reference is outside the section's evidence IDs");
        if(section.content.contains("```mermaid"))throw invalid("Raw Mermaid is not an application-validated graph plan");
        if(section.checks==null||section.checks.size()!=10)throw invalid("Every section requires R1 through R10 checks");
        Set<String> rules=new HashSet<>();
        for(Check check:section.checks){
            if(check==null||!text(check.ruleId)||!check.ruleId.matches("R(?:[1-9]|10)")||!rules.add(check.ruleId)||!text(check.reason)
                    ||!Arrays.asList("pass","fail","not_applicable","missing_evidence").contains(check.status))throw invalid("Invalid self-check record");
            if(!cited.containsAll(refs(check.citations,sources)))throw invalid("Self-check evidence is outside the section");
            if("pass".equals(check.status)&&!Arrays.asList("R1","R10").contains(check.ruleId)&&check.citations.isEmpty())throw invalid("Applicable substantive self-check lacks evidence");
            if("fail".equals(check.status))throw invalid("A failed rule cannot be published");
        }
        boolean substantive="analysis".equals(section.kind)||"flowchart".equals(section.kind);
        if(substantive){
            if(cited.isEmpty()||!"pass".equals(status(section,"R2")))throw invalid("Substantive sections require supported revision citations");
            if(section.checks.stream().anyMatch(c->"missing_evidence".equals(c.status)))throw invalid("Missing evidence affects this substantive section");
            if(!Arrays.asList("project_contract","reference_only").contains(section.basisScope)
                    ||referenceOnly!= "reference_only".equals(section.basisScope)||!adopted.test(section.basisScope,cited))
                throw invalid("Section adoption or requested basis is not supported");
        } else if(!"insufficient".equals(section.basisScope))throw invalid("Limits and uncertainty cannot assert a contract basis");
        if("limits".equals(section.kind)&&!Arrays.asList("pass","missing_evidence").contains(status(section,"R1")))throw invalid("Opening facts and assumptions were not checked");
        if(!Arrays.asList("pass","missing_evidence").contains(status(section,"R10")))throw invalid("Uncertainty must be checked explicitly");
        if("flowchart".equals(section.kind))graph(section.flowchartPlan,cited,sources);
        else if(section.flowchartPlan!=null)throw invalid("Graph plan belongs only to a flowchart section");
    }
    static String status(Section section,String rule) { return section.checks.stream().filter(c->rule.equals(c.ruleId)).map(c->c.status).findFirst().orElse(""); }
    static String content(Section section,List<CitationVO> sources) {
        return section.content.trim()+("flowchart".equals(section.kind)?"\n\n"+graph(section.flowchartPlan,section.citations,sources):"");
    }
    static String graph(GraphPlan graph,List<String> cited,List<CitationVO> sources) {
        if(graph==null||graph.nodes==null||graph.edges==null||graph.nodes.size()<2||graph.nodes.size()>64||graph.edges.isEmpty()||graph.edges.size()>128)throw invalid("Incomplete graph plan");
        Map<String,Node> nodes=new LinkedHashMap<>();StringBuilder out=new StringBuilder("```mermaid\nflowchart TD\n");
        for(Node node:graph.nodes){
            if(node==null||!text(node.id)||!node.id.matches("[A-Za-z][A-Za-z0-9_]{0,31}")||"end".equals(node.id)||nodes.put(node.id,node)!=null
                    ||!text(node.label)||!Arrays.asList("start","decision","action","end","unknown").contains(node.kind))throw invalid("Invalid graph node");
            List<String> nr=refs(node.citations,sources);if(!cited.containsAll(nr)||(!"start".equals(node.kind)&&!"unknown".equals(node.kind)&&nr.isEmpty()))throw invalid("Graph node lacks section evidence");
            String label=node.label+("unknown".equals(node.kind)?" (unconfirmed / 待核)":"")+(nr.isEmpty()?"":" "+String.join(" ",nr));
            out.append("  ").append(node.id).append("decision".equals(node.kind)?"{\"":"[\"").append(label(label)).append("decision".equals(node.kind)?"\"}\n":"\"]\n");
        }
        Set<String> seen=new HashSet<>();Map<String,List<Edge>> outgoing=new HashMap<>();
        for(Edge edge:graph.edges){
            if(edge==null||!nodes.containsKey(edge.from)||!nodes.containsKey(edge.to)||edge.from.equals(edge.to)
                    ||!seen.add(edge.from+"|"+edge.to+"|"+edge.condition))throw invalid("Invalid graph endpoint or duplicate edge");
            List<String> er=refs(edge.citations,sources);if(er.isEmpty()||!cited.containsAll(er))throw invalid("Graph edge lacks section evidence");
            if(text(edge.period)&&!text(edge.startEvent))throw invalid("Graph deadline lacks a start event");
            if(text(edge.action)&&!text(edge.actor))throw invalid("Graph action lacks an actor");
            outgoing.computeIfAbsent(edge.from,k->new ArrayList<>()).add(edge);
            List<String> parts=Arrays.asList(edge.condition,edge.actor,edge.action,edge.period,text(edge.startEvent)?"from / 起算: "+edge.startEvent:"",String.join(" ",er));
            out.append("  ").append(edge.from).append(" -->|\"").append(label(parts.stream().filter(AdvisoryHarness::text).collect(Collectors.joining("; ")))).append("\"| ").append(edge.to).append('\n');
        }
        List<Node> starts=graph.nodes.stream().filter(n->"start".equals(n.kind)).collect(Collectors.toList());
        if(starts.size()!=1||graph.nodes.stream().noneMatch(n->"end".equals(n.kind)||"unknown".equals(n.kind)))throw invalid("Graph requires one start and an end or unknown outcome");
        Set<String> reachable=new HashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(starts.get(0).id);
        while(!queue.isEmpty()){String id=queue.remove();if(reachable.add(id))for(Edge edge:outgoing.getOrDefault(id,Collections.emptyList()))queue.add(edge.to);}
        if(reachable.size()!=nodes.size())throw invalid("Unreachable graph node");
        for(Node node:graph.nodes){List<Edge> edges=outgoing.getOrDefault(node.id,Collections.emptyList());
            if("decision".equals(node.kind)&&(edges.size()<2||edges.stream().anyMatch(e->!text(e.condition))||edges.stream().map(e->e.condition).distinct().count()!=edges.size()))throw invalid("Decision requires distinct stated branches");
            if(("end".equals(node.kind)||"unknown".equals(node.kind))&&!edges.isEmpty())throw invalid("Terminal graph node has outgoing actions");
            if(!"end".equals(node.kind)&&!"unknown".equals(node.kind)&&edges.isEmpty())throw invalid("Nonterminal graph node has no outcome");
        }
        return out.append("```").toString();
    }
    private static String label(String value) {
        if(value.length()>1200||value.matches("(?s).*(?:[<>`]|%%|#(?:quot|[0-9]+);|click\\s|https?://).*"))throw invalid("Unsafe or oversized graph label");
        return value.replace("\r"," ").replace("\n"," ").replace("\"","#quot;").replace("|","/");
    }
    static boolean text(String value){return value!=null&&!value.trim().isEmpty();}
    static boolean flowchartRequested(String question){return question!=null&&question.toLowerCase(Locale.ROOT).matches("(?s).*(?:flow[ -]?chart|diagram|流程[图圖]|以[图圖](?:示|表示)|(?:画|畫|绘制|繪製).*(?:图|圖)).*");}
    static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
