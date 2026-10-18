package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Advice-only output contract. Model checks are observations, never independent legal acceptance. */
final class AdvisoryHarness {
    static final String POLICY = AdvisoryPromptPolicy.text();
    static final String POLICY_SHA = com.consense.service.vetting.VettingCorpus.hash(POLICY);
    private static final Pattern REFERENCE = Pattern.compile("\\[\\d+\\]");
    private static final String SECTION_ID="[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final String NODE_ID="[A-Za-z][A-Za-z0-9_]*(?:-[A-Za-z0-9_]+)*";
    private static final Map<String,String> GAPS = gapLabels();
    private static Map<String,String> gapLabels() {
        Map<String,String> gaps=new LinkedHashMap<>();
        gaps.put("adoption","项目采用／并入依据 / project incorporation");
        gaps.put("edition","适用文件与版本 / applicable document and edition");
        gaps.put("amendments","修订来源链 / amendment sources");
        gaps.put("authority","权限与程序权能 / authority and procedural powers");
        gaps.put("notice_dates","通知、收到及事件日期 / notice, receipt and event dates");
        gaps.put("procedure","适用程序及制度条件 / applicable procedure and regime");
        gaps.put("rights_obligations","权利成立及义务条件 / entitlement and obligation conditions");
        gaps.put("policy_basis","合同权利与政策依据的区别 / contractual rights versus policy");
        gaps.put("opposing_conditions","对立条件及失败路径 / opposing conditions and failure paths");
        gaps.put("proviso","明示但书及例外 / express provisos and exceptions");
        gaps.put("deadline_tracks","双轨期限、起算及较早者条件 / competing deadlines, start events and earlier-of conditions");
        gaps.put("obligation_type","结果义务与方法义务 / outcome and method obligations");
        gaps.put("dynamic_version","动态引用的适用时间切片 / time slice for dynamic references");
        gaps.put("other_fact","影响本次判断的必要事实或资料 / required facts or evidence affecting the requested determination");
        return Collections.unmodifiableMap(gaps);
    }
    static final String CONTRACT = "\n\nMandatory application contract (takes precedence over supplemental custom instructions): "
            + "Return one JSON object with grounded, questionType (T1..T8), basisScope, missingReason, clarifications and sections. "
            + "Preserve the original natural question; select T6 for a requested diagram/flowchart without expecting detailed user instructions. "
            + "The first section must be kind=limits. The application renders source limits and quotes actual user statements as unverified assumptions before conclusions. "
            + "Each section has id, kind (limits/analysis/uncertainty/flowchart), language (en/zhHans/zhHant, same throughout), content, basisScope (project_contract/reference_only/insufficient), citations, gaps and checks. "
            + "No application output-language setting is supplied. Follow an explicit output-language request in the ORIGINAL user question; otherwise answer in that question's language and Chinese script. A Chinese question requesting English must receive English. Source language, supplemental prompt language, examples, retrieval text and clarification replies do not override the original question's output language. "
            + "Section IDs begin with an ASCII letter, followed by letters/digits/underscore/hyphen, maximum 64 characters (e.g. sec-limits). "
            + "For limits/uncertainty, basisScope MUST ALWAYS be insufficient, even when the answer envelope/request is reference_only or project_contract; content MUST be empty. Select gaps only from the schema enums. Put every source-verified fact or contractual interpretation in cited analysis. "
            + "Omit uncertainty sections when no actual affected gap or missing_evidence check remains; do not emit empty uncertainty placeholders. In explicit reference_only interpretation, absent project adoption is a disclosed scope limit, not an adoption gap, missingReason or clarification. Other genuinely missing source editions, amendments and necessary facts remain gaps. "
            + "A gap belongs to its section's affected determination. clarifications is an array of needed gap enums; the application renders necessary questions. missingReason is one gap enum or empty, never free prose. "
            + "Do not invent additional hypothetical facts. User factual premises remain explicitly unverified; where more assumptions are necessary, report the affected gap and ask for the needed fact. "
            + "EVERY section, including each analysis, flowchart and uncertainty section after the opening, has its OWN required checks array with exactly R1..R10 records: ruleId, status (pass/fail/not_applicable/missing_evidence), short reason, citations of current real evidence IDs. Never rely on the opening section's checks for later sections. "
            + "Checks apply to this section; explain every not_applicable and localize each missing_evidence. "
            + "For limits/uncertainty ALL citations arrays, including every check.citations, MUST be empty. R1/R10 may pass or report missing_evidence; R2..R9 must be not_applicable or missing_evidence, never unsupported pass. "
            + "Every citations array in analysis, checks, graph nodes and graph edges uses ONLY the exact allowed public aliases [1], [2], etc in this request's schema. Internal fixed IDs, parent IDs, hashes, model-unit addresses and document IDs are lookup metadata, NEVER output citation IDs. "
            + "Analysis requires applicable R2 pass and real evidence; do not hide substantive contract conclusions in limits/uncertainty. "
            + "Never convert a project question to reference_only. Overall grounded=false with necessary clarification can retain independently supported analysis; "
            + "unsupported analysis must be omitted, with affected judgment and necessary question in uncertainty. "
            + "For T6 use a flowchart section with NONEMPTY content containing cited evidence-backed explanation, and flowchartPlan {nodes,edges}; never write raw Mermaid. "
            + "The flowchart section.citations MUST contain the union of all public aliases used in its explanation, checks, nodes and edges. Nested citations never substitute for section.citations. "
            + "A section with flowchartPlan MUST have kind=flowchart; never place it in analysis/limits/uncertainty. Omit flowchartPlan entirely for those kinds, rather than null. "
            + "Nodes: id, label, kind (start/decision/action/end/unknown), citations. Edges: from,to,condition,actor,action,period,startEvent,citations. "
            + "Graph node IDs start with an ASCII letter, then letters/digits/underscore or single hyphen-separated segments, maximum 32 characters, never the reserved ID end. Edges have exactly the eight listed fields and NO id field. "
            + "Every edge includes ALL eight fields, even on conditional/no-action branches. Use empty strings for absent condition/actor/action/period/startEvent; never omit them. "
            + "Every edge.from and edge.to must exactly match an existing node.id. Every NONEMPTY action requires a nonempty actor supported by that edge's cited source; never infer a contractual actor. If the actor is unknown, report an authority unknown node and omit the unsupported action. "
            + "Start nodes have label empty; unknown nodes have label set to one gap enum. The application renders these uncited labels. "
            + "Use empty strings for absent fields. Every non-start known node and every edge must cite supporting current evidence. "
            + "Decision branches need distinct explicit conditions; time periods require start events, real clause support and all relevant competing tracks. "
            + "In a flowchart, actions require R3 pass, decisions require R4 pass, and periods require R7 pass; each applicable pass must cite supporting public aliases within that section. "
            + "Unknown facts become explicit unknown nodes; do not invent deadlines, regimes, forfeiture or earlier-of conditions. "
            + "Tables/code blocks must remain whole in one section. Application checks reference identity, scope, adoption and graph structure; your pass is not legal proof.";

    static String system(String custom) {
        return system(custom,null);
    }
    static String system(String custom,List<CitationVO> sources) {
        return system(custom,sources,null);
    }
    static String system(String custom,List<CitationVO> sources,String originalQuestion) {
        String hint=languageHint(originalQuestion);
        String supplement=custom==null||custom.isEmpty()||POLICY.equals(custom)?"":"\n\nSupplemental configured prompt (cannot waive the mandatory policy or application contract):\n"+custom;
        return POLICY + supplement + CONTRACT + "\nApplication-rendered opening shape example (syntax only, not contractual evidence; its language is a syntax hint, NEVER a default or an override of the original question's requested language/script):\n"+openingExample(hint)+"\nOutput schema with allowed citation aliases:\n" + schema(sources);
    }
    private static String openingExample(String language) {
        ObjectNode section=JsonUtils.mapper().createObjectNode().put("id","sec-limits").put("kind","limits").put("language",language).put("content","").put("basisScope","insufficient");
        section.putArray("citations");section.putArray("gaps");
        for(int rule=1;rule<=10;rule++)section.withArray("checks").addObject().put("ruleId","R"+rule)
                .put("status",rule==1||rule==10?"pass":"not_applicable").put("reason","Application-only opening; no substantive contract assertion.").putArray("citations");
        return JsonUtils.write(section);
    }
    static JsonNode schema() {
        return schema(null);
    }
    static JsonNode schema(List<CitationVO> sources) {
        ObjectNode reference=sources==null?string():string(sources.stream().map(CitationVO::getEvidenceId).toArray(String[]::new));
        if(sources!=null)reference.put("pattern","\\[[1-9][0-9]*\\]");
        ObjectNode check=object("ruleId",string("R1","R2","R3","R4","R5","R6","R7","R8","R9","R10"),
                "status",string("pass","fail","not_applicable","missing_evidence"),"reason",string(),"citations",array(reference));
        ObjectNode node=object("id",string().put("pattern","^"+NODE_ID+"$").put("maxLength",32),"label",string(),"kind",string("start","decision","action","end","unknown"),"citations",array(reference));
        ObjectNode edge=object("from",string(),"to",string(),"condition",string(),"actor",string(),"action",string(),"period",string(),"startEvent",string(),"citations",array(reference));
        ObjectNode section=object("id",string().put("pattern","^"+SECTION_ID+"$").put("maxLength",64),"kind",string("limits","analysis","uncertainty","flowchart"),"language",string("en","zhHans","zhHant"),"content",string(),
                "basisScope",string("project_contract","reference_only","insufficient"),"citations",array(reference),"gaps",array(string(GAPS.keySet().toArray(new String[0]))),"checks",array(check));
        section.with("properties").set("flowchartPlan",object("nodes",array(node),"edges",array(edge)));
        return object("grounded",JsonUtils.mapper().createObjectNode().put("type","boolean"),"questionType",string("T1","T2","T3","T4","T5","T6","T7","T8"),
                "basisScope",string("project_contract","reference_only","insufficient"),"missingReason",string(java.util.stream.Stream.concat(java.util.stream.Stream.of(""),GAPS.keySet().stream()).toArray(String[]::new)),"clarifications",array(string(GAPS.keySet().toArray(new String[0]))),"sections",array(section));
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
        List<String> problems=new ArrayList<>();shape(value,schema,"answer",problems);
        if(!problems.isEmpty())throw invalid(String.join("; ",problems));
    }
    private static void shape(JsonNode value,JsonNode schema,String path,List<String> problems) {
        if(problems.size()>=64)return;
        String type=schema.path("type").asText();
        if(value==null||value.isNull()){problems.add(path+": value is required and cannot be null");return;}
        if("object".equals(type)) {
            if(!value.isObject()){problems.add(path+": expected an object");return;}
            for(JsonNode required:schema.path("required"))if(!value.has(required.asText())&&problems.size()<64)problems.add(path+"."+required.asText()+": required field is missing");
            Iterator<Map.Entry<String,JsonNode>> fields=value.fields();
            while(fields.hasNext()&&problems.size()<64){Map.Entry<String,JsonNode> field=fields.next();JsonNode child=schema.path("properties").get(field.getKey());
                if(child==null){List<String> allowed=new ArrayList<>();schema.path("properties").fieldNames().forEachRemaining(allowed::add);problems.add(path+": fields must be only "+JsonUtils.write(allowed));}
                else shape(field.getValue(),child,path+"."+field.getKey(),problems);}
        } else if("array".equals(type)) {
            if(!value.isArray()||value.size()>256){problems.add(path+": expected an array of at most 256 items");return;}
            int index=0;for(JsonNode item:value)shape(item,schema.path("items"),path+"["+(index++)+"]",problems);
        } else if("boolean".equals(type)) {
            if(!value.isBoolean())problems.add(path+": expected a boolean");
        } else if("string".equals(type)) {
            if(!value.isTextual()||value.textValue().length()>schema.path("maxLength").asInt(1048576)){problems.add(path+": expected bounded text");return;}
            if(schema.has("pattern")&&!value.textValue().matches(schema.path("pattern").asText()))problems.add(path+": text must match "+schema.path("pattern").asText());
            if(schema.has("enum")){boolean allowed=false;for(JsonNode choice:schema.path("enum"))if(choice.equals(value))allowed=true;
                if(!allowed&&problems.size()<64)problems.add(path+": value must be one of "+schema.path("enum"));}
        } else throw new IllegalStateException("Unknown fixed Advisory schema type");
    }

    @Data @NoArgsConstructor static class Check {
        private String ruleId,status,reason;
        private List<String> citations;
    }
    @Data @NoArgsConstructor static class Section {
        private String id,kind,language,content,basisScope;
        private List<String> citations;
        private List<String> gaps;
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
        if(section==null||!text(section.id)||!section.id.matches(SECTION_ID)||section.content==null
                ||!Arrays.asList("limits","analysis","uncertainty","flowchart").contains(section.kind))throw invalid("Incomplete answer section");
        if(!Arrays.asList("en","zhHans","zhHant").contains(section.language))throw invalid("Unsupported answer language");
        if(section.gaps==null||section.gaps.stream().anyMatch(g->!GAPS.containsKey(g))||new HashSet<>(section.gaps).size()!=section.gaps.size())throw invalid("Invalid evidence gaps");
        List<String> cited=refs(section.citations,sources);
        java.util.regex.Matcher matcher=REFERENCE.matcher(section.content);
        while(matcher.find())if(!cited.contains(matcher.group()))throw invalid("Text reference is outside the section's evidence IDs");
        if(section.content.contains("```mermaid"))throw invalid("Raw Mermaid is not an application-validated graph plan");
        if(section.checks==null||section.checks.size()!=10)throw invalid("Every section requires R1 through R10 checks");
        Set<String> rules=new HashSet<>();List<String> problems=new ArrayList<>();
        for(int index=0;index<section.checks.size();index++){
            Check check=section.checks.get(index);String path="section.checks["+index+"]";
            if(check==null||!text(check.ruleId)||!check.ruleId.matches("R(?:[1-9]|10)")||!rules.add(check.ruleId)||!text(check.reason)
                    ||!Arrays.asList("pass","fail","not_applicable","missing_evidence").contains(check.status))throw invalid("Invalid self-check record");
            if(!cited.containsAll(refs(check.citations,sources)))problems.add(path+".citations: every alias must also be in section.citations");
            if("pass".equals(check.status)&&!Arrays.asList("R1","R10").contains(check.ruleId)&&check.citations.isEmpty())problems.add(path+".citations: applicable substantive pass requires evidence");
            if("fail".equals(check.status))problems.add(path+".status: a failed rule cannot be published");
        }
        boolean substantive="analysis".equals(section.kind)||"flowchart".equals(section.kind);
        if(substantive){
            if(!text(section.content))problems.add("section.content: substantive sections require a nonempty cited explanation");
            if(!section.gaps.isEmpty())problems.add("section.gaps: substantive sections require complete independent support and no unresolved gaps");
            if(cited.isEmpty())problems.add("section.citations: substantive sections require evidence, including all nested aliases");
            if(!"pass".equals(status(section,"R2")))problems.add("section.checks.R2.status: supported revision citations require pass");
            if(section.checks.stream().anyMatch(c->"missing_evidence".equals(c.status)))problems.add("section.checks.status: missing evidence affects this substantive section");
            if(!Arrays.asList("project_contract","reference_only").contains(section.basisScope)
                    ||referenceOnly!= "reference_only".equals(section.basisScope)||!adopted.test(section.basisScope,cited))
                problems.add("section.basisScope: adoption or requested basis is not supported");
        } else {
            if(!section.content.isEmpty())problems.add("section.content: limits/uncertainty must be empty application-rendered gaps");
            if(!cited.isEmpty())problems.add("section.citations: limits/uncertainty must be empty");
            if(!"insufficient".equals(section.basisScope))problems.add("section.basisScope: limits/uncertainty must be insufficient regardless of envelope/request basis");
            if("uncertainty".equals(section.kind)&&gaps(section).isEmpty())problems.add("section.gaps: omit uncertainty when there is no affected gap or missing_evidence check");
        }
        if(referenceOnly&&gaps(section).contains("adoption"))problems.add("section.gaps: project adoption is a disclosed scope limit, not a required gap for explicit reference_only interpretation");
        if("limits".equals(section.kind)&&!Arrays.asList("pass","missing_evidence").contains(status(section,"R1")))problems.add("section.checks.R1.status: opening facts and assumptions require pass or missing_evidence");
        if(!Arrays.asList("pass","missing_evidence").contains(status(section,"R10")))problems.add("section.checks.R10.status: uncertainty requires pass or missing_evidence");
        if("flowchart".equals(section.kind)) {
            try{graph(section.flowchartPlan,cited,sources);}catch(ContractViolation invalid){problems.add(invalid.getMessage());}
            if(section.flowchartPlan!=null&&section.flowchartPlan.edges!=null&&section.flowchartPlan.nodes!=null){
                if(section.flowchartPlan.edges.stream().anyMatch(e->e!=null&&text(e.action))&&!"pass".equals(status(section,"R3")))problems.add("section.checks.R3.status: graph actions require the authority/power check");
                if(section.flowchartPlan.nodes.stream().anyMatch(n->n!=null&&"decision".equals(n.kind))&&!"pass".equals(status(section,"R4")))problems.add("section.checks.R4.status: graph branches require the opposing-condition check");
                if(section.flowchartPlan.edges.stream().anyMatch(e->e!=null&&text(e.period))&&!"pass".equals(status(section,"R7")))problems.add("section.checks.R7.status: graph deadlines require the competing-track check");
            }
        }
        else if(section.flowchartPlan!=null)problems.add("section.flowchartPlan: graph plan belongs only to a flowchart section");
        if(!problems.isEmpty())throw invalid(String.join("; ",problems));
    }
    static String status(Section section,String rule) { return section.checks.stream().filter(c->rule.equals(c.ruleId)).map(c->c.status).findFirst().orElse(""); }
    static List<String> gaps(Section section) {
        Set<String> gaps=new LinkedHashSet<>(section.gaps);
        String[] rules={"other_fact","amendments","authority","opposing_conditions","policy_basis","proviso","deadline_tracks","obligation_type","dynamic_version","other_fact"};
        for(Check check:section.checks)if("missing_evidence".equals(check.status))gaps.add(rules[Integer.parseInt(check.ruleId.substring(1))-1]);
        if(section.flowchartPlan!=null&&section.flowchartPlan.nodes!=null)for(Node node:section.flowchartPlan.nodes)if(node!=null&&"unknown".equals(node.kind))gaps.add(node.label);
        return new ArrayList<>(gaps);
    }
    static boolean unresolved(Section section) {
        return !gaps(section).isEmpty();
    }
    static String missing(String gap,String language) {
        if(!GAPS.containsKey(gap))throw invalid("Unknown missing-evidence reason");
        String effect=gap.equals("notice_dates")||gap.equals("deadline_tracks")?copy(language,"The final deadline cannot be determined.","不能确定最后期限。","不能確定最後期限。")
                :gap.equals("rights_obligations")?copy(language,"Entitlement or the obligation cannot be determined.","不能确定权利或义务是否成立。","不能確定權利或義務是否成立。")
                :Arrays.asList("adoption","edition","amendments","dynamic_version").contains(gap)?copy(language,"The applicability of the provision or edition cannot be determined.","不能确定该条文或版本是否适用。","不能確定該條文或版本是否適用。")
                :gap.equals("authority")?copy(language,"The actor's authority or procedural power cannot be determined.","不能确定相关角色的权限或程序权能。","不能確定相關角色的權限或程序權能。")
                :gap.equals("procedure")?copy(language,"The required procedural route or next step cannot be determined.","不能确定必须采取的程序或下一步。","不能確定必須採取的程序或下一步。")
                :gap.equals("policy_basis")?copy(language,"A contractual entitlement cannot be inferred from a policy statement.","不能从政策表述推定合同权利。","不能從政策表述推定合同權利。")
                :gap.equals("opposing_conditions")||gap.equals("proviso")?copy(language,"The relevant exceptions, limiting conditions or failure path cannot be determined.","不能确定相关例外、限制条件或失败路径。","不能確定相關例外、限制條件或失敗路徑。")
                :gap.equals("obligation_type")?copy(language,"The required outcome or method cannot be determined.","不能确定必须达到的结果或采用的方法。","不能確定必須達到的結果或採用的方法。")
                :copy(language,"The affected requested determination remains unresolved; independently supported analysis below remains available.","受影响的所问判断尚不能定论，其他独立有据分析仍可保留。","受影響的所問判斷尚不能定論，其他獨立有據分析仍可保留。");
        return copy(language,"Evidence still required: ","仍待核实的依据：","仍待核實的依據：")+gapLabel(gap,language)+". "+effect;
    }
    private static String copy(String language,String en,String hans,String hant){return "en".equals(language)?en:"zhHant".equals(language)?hant:hans;}
    static String languageHint(String question){return question!=null&&question.matches("(?s).*\\p{IsHan}.*")?"zhHans":"en";}
    static String rejectionClarification(String language) {
        return copy(language,"Some requested conclusions or the flowchart could not be verified. Confirm the applicable provisions, amendments, dates and missing facts before relying on them.",
                "部分所问结论或流程图尚无法核实。请先确认适用条文、修订、日期和缺失事实，再据此作判断。",
                "部分所問結論或流程圖尚無法核實。請先確認適用條文、修訂、日期和缺失事實，再據此作判斷。");
    }
    private static String gapLabel(String gap,String language) {
        String label=GAPS.get(gap);if(label==null)throw invalid("Unknown evidence gap");
        if("en".equals(language))return label.split(" / ")[1];
        if(!"zhHant".equals(language))return label.split(" / ")[0];
        String[] traditional={"項目採用／併入依據","適用文件與版本","修訂來源鏈","權限與程序權能","通知、收到及事件日期","適用程序及制度條件","權利成立及義務條件","合同權利與政策依據的區別","對立條件及失敗路徑","明示但書及例外","雙軌期限、起算及較早者條件","結果義務與方法義務","動態引用的適用時間切片","影響本次判斷的必要事實或資料"};
        return traditional[new ArrayList<>(GAPS.keySet()).indexOf(gap)];
    }
    static String clarification(String gap,String language) {
        return copy(language,"Please provide the relevant facts or documents confirming ","请提供能够核实以下事项的事实或文件：","請提供能夠核實以下事項的事實或文件：")
                +gapLabel(gap,language)+"?";
    }
    static String content(Section section,List<CitationVO> sources,boolean referenceOnly,String question,List<String> background) {
        if("analysis".equals(section.kind)||"flowchart".equals(section.kind))
            return section.content.trim()+("flowchart".equals(section.kind)?"\n\n"+graph(section.flowchartPlan,section.citations,sources,section.language):"");
        StringBuilder out=new StringBuilder();
        String language=section.language;
        if("limits".equals(section.kind)) {
            out.append(copy(language,"Evidence limits and assumptions","资料界限与假设","資料界限與假設")).append("\n\n")
                    .append(referenceOnly?copy(language,"Standard reference explanation; project adoption is not established.","本次按固定标准参考解释，不确认项目采用。","本次按固定標準參考解釋，不確認項目採用。")
                            :copy(language,"Selected project sources; incorporation, edition and rights require the cited support below.","本次按所选项目资料范围核查；具体采用、版本和权利须由下文真实引用分别支持。","本次按所選項目資料範圍核查；具體採用、版本和權利須由下文真實引用分別支持。"))
                    .append("\n\n").append(copy(language,"Source identity was checked; verified facts and interpretations are stated with citations below. Availability alone does not establish applicability.","来源已按本次请求核对身份；有据事实与合同解释见下文逐项引用，来源存在不证明条款适用。","來源已按本次請求核對身份；有據事實與合同解釋見下文逐項引用，來源存在不證明條款適用。"))
                    .append("\n\n").append(copy(language,"User question and background (unverified statements):","用户问句及背景（未经证实的陈述）：","用戶問句及背景（未經證實的陳述）：")).append("\n> ")
                    .append(question.replace("\n","\n> "));
            for(String stated:background)out.append("\n> ").append(stated.replace("\n","\n> "));
            out.append("\n\n").append(copy(language,"No additional factual assumptions are introduced. Any factual premises supplied by you remain conditional and do not prove adoption, deadline start events or entitlement.","不增加额外事实假设。您提供的事实前提仍为待核条件，不证明采用、期限起算或权利成立。","不增加額外事實假設。您提供的事實前提仍為待核條件，不證明採用、期限起算或權利成立。"));
        }
        for(String gap:gaps(section))out.append(out.length()==0?"":"\n\n").append(missing(gap,language));
        return out.toString();
    }
    static String graph(GraphPlan graph,List<String> cited,List<CitationVO> sources) {
        return graph(graph,cited,sources,"en");
    }
    static String graph(GraphPlan graph,List<String> cited,List<CitationVO> sources,String language) {
        if(graph==null||graph.nodes==null||graph.edges==null||graph.nodes.size()<2||graph.nodes.size()>64||graph.edges.isEmpty()||graph.edges.size()>128)throw invalid("Incomplete graph plan");
        // Report independent field errors together so the one admitted repair can correct all of them.
        graphFields(graph,cited,sources);
        Map<String,Node> nodes=new LinkedHashMap<>();Map<String,String> renderedIds=new HashMap<>();StringBuilder out=new StringBuilder("```mermaid\nflowchart TD\n");
        for(Node node:graph.nodes){
            nodes.put(node.id,node);List<String> nr=node.citations;
            String label=("start".equals(node.kind)?copy(language,"Start","开始","開始"):"unknown".equals(node.kind)?copy(language,"Unconfirmed: ","待核：","待核：")+gapLabel(node.label,language):node.label)+(nr.isEmpty()?"":" "+String.join(" ",nr));
            renderedIds.put(node.id,"n"+renderedIds.size()); // Model IDs can collide with Mermaid keywords even when bounded.
            out.append("  ").append(renderedIds.get(node.id)).append("decision".equals(node.kind)?"{\"":"[\"").append(label(label)).append("decision".equals(node.kind)?"\"}\n":"\"]\n");
        }
        Set<String> seen=new HashSet<>();Map<String,List<Edge>> outgoing=new HashMap<>();
        for(Edge edge:graph.edges){
            if(!seen.add(edge.from+"|"+edge.to+"|"+edge.condition))throw invalid("flowchartPlan.edges: duplicate endpoint/condition tuple");
            List<String> er=edge.citations;
            outgoing.computeIfAbsent(edge.from,k->new ArrayList<>()).add(edge);
            List<String> parts=Arrays.asList(edge.condition,edge.actor,edge.action,edge.period,text(edge.startEvent)?copy(language,"from: ","起算：","起算：")+edge.startEvent:"",String.join(" ",er));
            out.append("  ").append(renderedIds.get(edge.from)).append(" -->|\"").append(label(parts.stream().filter(AdvisoryHarness::text).collect(Collectors.joining("; ")))).append("\"| ").append(renderedIds.get(edge.to)).append('\n');
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
    private static void graphFields(GraphPlan graph,List<String> cited,List<CitationVO> sources) {
        List<String> problems=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<graph.nodes.size();i++) {
            Node node=graph.nodes.get(i);String path="flowchartPlan.nodes["+i+"]";
            if(node==null){problems.add(path+": node is required");continue;}
            if(!text(node.id)||node.id.length()>32||!node.id.matches(NODE_ID)||"end".equals(node.id)||!ids.add(node.id))problems.add(path+".id: expected a unique bounded nonreserved node ID");
            if(!Arrays.asList("start","decision","action","end","unknown").contains(node.kind))problems.add(path+".kind: unsupported node kind");
            if(node.label==null||("start".equals(node.kind)?!node.label.isEmpty():"unknown".equals(node.kind)?!GAPS.containsKey(node.label):!text(node.label)))problems.add(path+".label: start must be empty, unknown must be a gap enum, known nodes require supported text");
            try {List<String> refs=refs(node.citations,sources);
                if(!cited.containsAll(refs))problems.add(path+".citations: every alias must also be in section.citations");
                if(!"start".equals(node.kind)&&!"unknown".equals(node.kind)&&refs.isEmpty())problems.add(path+".citations: known nodes require source evidence");
            }catch(ContractViolation invalid){problems.add(path+".citations: "+invalid.getMessage());}
        }
        for(int i=0;i<graph.edges.size();i++) {
            Edge edge=graph.edges.get(i);String path="flowchartPlan.edges["+i+"]";
            if(edge==null){problems.add(path+": edge is required");continue;}
            if(!ids.contains(edge.from))problems.add(path+".from: must exactly match an existing node.id");
            if(!ids.contains(edge.to))problems.add(path+".to: must exactly match an existing node.id");
            if(edge.from!=null&&edge.from.equals(edge.to))problems.add(path+".from/to: self edges cannot be published");
            try {List<String> refs=refs(edge.citations,sources);
                if(refs.isEmpty())problems.add(path+".citations: edges require source evidence");
                if(!cited.containsAll(refs))problems.add(path+".citations: every alias must also be in section.citations");
            }catch(ContractViolation invalid){problems.add(path+".citations: "+invalid.getMessage());}
            if(text(edge.period)&&!text(edge.startEvent))problems.add(path+".startEvent: every nonempty period requires a source-supported start event");
            if(text(edge.action)&&!text(edge.actor))problems.add(path+".actor: every nonempty action requires its source-supported actor; otherwise omit the unsupported action and use an authority unknown node");
        }
        if(!problems.isEmpty())throw invalid(String.join("; ",problems));
    }
    private static String label(String value) {
        if(value.length()>1200||value.matches("(?s).*(?:[<>`]|%%|#(?:quot|[0-9]+);|click\\s|https?://).*"))throw invalid("Unsafe or oversized graph label");
        return value.replace("\r"," ").replace("\n"," ").replace("\"","#quot;").replace("|","/");
    }
    static boolean text(String value){return value!=null&&!value.replaceAll("[\\s\\p{Z}]+","").isEmpty();}
    static boolean flowchartRequested(String question){return question!=null&&question.toLowerCase(Locale.ROOT).matches("(?s).*(?:flow[ -]?chart|diagram|流程[图圖]|以[图圖](?:示|表示)|(?:画|畫|绘制|繪製).*(?:图|圖)).*");}
    static final class ContractViolation extends IllegalArgumentException { ContractViolation(String reason){super(reason);} }
    static String diagnostic(Throwable error){return error instanceof ContractViolation?error.getMessage():"The structured response was not one complete valid JSON object.";}
    static ContractViolation invalid(String reason){return new ContractViolation(reason);}
}
