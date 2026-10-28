package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Sealed, version-specific historical analysis; it never assigns current edits or values. */
@Service
public class DraftPdfSccReference {
    static final String SOURCE_SHA="1bebab1afbc0faaf8aa9c0b9e6c51b023c24e53a094d4987d746fdeb65b30741";
    private static final String PDF_SHA="a6c25582607e0f567d103c4e6b3d8fb0ca035ceaa44a6dbb7cc90eef081f1a97";
    private static final String ATOMIC_SHA="549f0ec064f61621c0da6c44e4f872f2a15d6483d4b77d448c0e398e74aa5e7b";
    private static final List<String> LIMITATIONS=Collections.unmodifiableList(Arrays.asList(
        "历史恢复的分析参考：74个处理点不是74个业务变量。",
        "完整父条证据包及全部标记处置尚未恢复；逐项核对当前原文。",
        "历史条件、位置与动作不授权当前模板范围、项目取值或自动编辑。"));
    private final String directory,pdfSha,atomicSha;
    private volatile Snapshot snapshot;

    @Autowired public DraftPdfSccReference(@Value("${consense.drafting.pdf-reference-dir:}") String directory) {
        this(directory,PDF_SHA,ATOMIC_SHA);
    }
    DraftPdfSccReference(String directory,String pdfSha,String atomicSha) {
        this.directory=directory;this.pdfSha=pdfSha;this.atomicSha=atomicSha;
    }

    public Map<String,Object> search(Map<String,Object> revision,String sourceId,String query,int offset) {
        String denied=denial(revision,sourceId);if(denied!=null)return unavailable(denied);
        Snapshot data=load();if(data==null)return unavailable("reference_missing_or_integrity_failed");
        if(query==null)query="";query=query.trim().toLowerCase(Locale.ROOT);
        if(query.length()>120||offset<0)return unavailable("invalid_search");
        List<Map<String,Object>> matching=new ArrayList<>();
        for(Map<String,Object> point:data.points.values()) {
            String contents=(text(point.get("id"))+" "+text(point.get("label"))+" "+text(point.get("clause"))+" "+text(point.get("meaning"))).toLowerCase(Locale.ROOT);
            if(query.isEmpty()||contents.contains(query))matching.add(point);
        }
        int start=Math.min(offset,matching.size()),end=Math.min(start+12,matching.size());List<Object> rows=new ArrayList<>();
        for(Map<String,Object> point:matching.subList(start,end))rows.add(summary(point,data));
        Map<String,Object> result=meta();result.put("candidates",rows);result.put("total",matching.size());result.put("nextOffset",end<matching.size()?end:null);return result;
    }

    public Map<String,Object> detail(Map<String,Object> revision,String sourceId,String pointId) {
        String denied=denial(revision,sourceId);if(denied!=null)return unavailable(denied);
        Snapshot data=load();if(data==null)return unavailable("reference_missing_or_integrity_failed");
        Map<String,Object> point=data.points.get(pointId);if(point==null)return unavailable("point_not_found");
        Map<String,Object> safe=summary(point,data);for(String key:Arrays.asList("meaning","applicability","valueSource","impacts","evidence"))safe.put(key,point.get(key));
        List<Object> rules=new ArrayList<>();for(Map<String,Object> source:data.rules.getOrDefault(pointId,Collections.emptyList())){Map<String,Object> rule=new LinkedHashMap<>(source);rule.remove("evidence");rules.add(rule);}
        Map<String,Object> result=meta();result.put("point",safe);result.put("rules",rules);return result;
    }

    public Map<String,Object> facts(Map<String,Object> revision,String sourceId) {
        String denied=denial(revision,sourceId);if(denied!=null)return unavailable(denied);Snapshot data=load();if(data==null)return unavailable("reference_missing_or_integrity_failed");
        List<Object> facts=new ArrayList<>();for(Object raw:data.fields){Map<String,Object> field=map(raw);facts.add(obj("inputRef",field.get("key"),"valueType",field.get("valueType"),"referencePoints",field.get("referencePoints")));}
        Map<String,Object> result=meta();result.put("facts",facts);return result;
    }

    public Map<String,Object> relatedRules(Map<String,Object> revision,String sourceId,int offset) {
        String denied=denial(revision,sourceId);if(denied!=null)return unavailable(denied);Snapshot data=load();if(data==null)return unavailable("reference_missing_or_integrity_failed");if(offset<0)return unavailable("invalid_search");
        Map<String,Object> graph=map(revision.get("graph"));Map<String,List<String>> confirmed=new LinkedHashMap<>();for(Map<String,Object> node:nodes(graph)){Map<String,Object> alignment=map(node.get("pdfReferenceAlignment"));if(!sourceId.equals(text(alignment.get("sourceId")))||!DraftPdfSccAlignment.current(graph,node,this))continue;confirmed.computeIfAbsent(text(alignment.get("inputRef")),ignored->new ArrayList<>()).add(text(node.get("id")));}
        Set<String> reachable=new LinkedHashSet<>(confirmed.keySet());Map<String,Map<String,Object>> related=new LinkedHashMap<>();boolean changed;
        do{changed=false;for(Map<String,Object> rule:data.allRules){String id=text(rule.get("id"));if(related.containsKey(id))continue;boolean hit=false;for(Object raw:list(rule.get("inputs"))){String ref=text(map(raw).get("ref"));for(String known:reachable)if(ref.equals(known)||ref.startsWith(known+".")||ref.startsWith(known+"[")){hit=true;break;}if(hit)break;}if(!hit)continue;related.put(id,rule);changed=true;for(Object raw:list(rule.get("cases")))for(Object output:list(map(raw).get("outputs"))){String ref=text(map(output).get("ref"));if(ref.startsWith("pdf:output:"))reachable.add(ref);}}}while(changed);
        List<Map<String,Object>> all=new ArrayList<>(related.values());int start=Math.min(offset,all.size()),end=Math.min(start+24,all.size());List<Object> rows=new ArrayList<>();
        for(Map<String,Object> rule:all.subList(start,end)){List<Object> inputs=new ArrayList<>(),branches=new ArrayList<>();for(Object raw:list(rule.get("inputs"))){Map<String,Object> input=map(raw);String ref=text(input.get("ref"));inputs.add(obj("inputRef",ref,"role",input.get("role"),"stage",input.get("stage"),"confirmedNodeIds",confirmed.getOrDefault(ref,Collections.emptyList())));}for(Object raw:list(rule.get("cases"))){Map<String,Object> branch=map(raw);branches.add(obj("id",branch.get("id"),"when",branch.get("when")));}
            rows.add(obj("ruleId",rule.get("id"),"label",rule.get("label"),"relationType",rule.get("relationType"),"impactType",rule.get("impactType"),"pointIds",rule.get("chainIds"),"inputs",inputs,"branches",branches,"status","pending","targetBinding","unbound","executable",false));}
        Map<String,Object> result=meta();result.put("rules",rows);result.put("confirmedInputCount",confirmed.size());result.put("total",all.size());result.put("nextOffset",end<all.size()?end:null);result.put("coverage","reachable_historical_analysis_only");return result;
    }

    /** Match only current-source atoms against business facts, not adopted decisions or evidence receipts. */
    Map<String,Object> modelWindow(Map<String,JsonNode> readings,List<?> focus,Map<String,Map<String,Object>> nodes,String selectedId) {
        List<Object> eligible=new ArrayList<>();JsonNode reading=readings.get(selectedId);
        if(reading==null||!"SCC".equalsIgnoreCase(reading.path("fileKey").asText())||!"pdf".equalsIgnoreCase(reading.path("format").asText())||!SOURCE_SHA.equalsIgnoreCase(reading.path("sourceSha256").asText()))return obj("status","not_applicable","nodeIds",eligible,"fields",new ArrayList<>());
        for(Object raw:focus){Map<String,Object> node=nodes.get(text(raw));if(node==null)continue;List<?> docs=list(node.get("docIds"));String role=text(map(node.get("role")).get("kind"));if(docs.size()==1&&selectedId.equals(text(docs.get(0)))&&Arrays.asList("input","decision","content").contains(role)&&!text(node.get("semanticSourceQuote")).trim().isEmpty())eligible.add(raw);}
        if(eligible.isEmpty())return obj("status","not_applicable","nodeIds",eligible,"fields",new ArrayList<>());
        Snapshot data=load();if(data==null)return obj("status","unavailable","sourceId",selectedId,"sourceSha256",SOURCE_SHA,"nodeIds",eligible,"fields",new ArrayList<>());
        return obj("status","available","sourceId",selectedId,"sourceSha256",SOURCE_SHA,"nodeIds",eligible,"fields",data.fields,"complete",false);
    }

    private static List<Object> effectiveFields(Snapshot data){Map<String,Map<String,Object>> fields=new LinkedHashMap<>();
        for(Object raw:data.inputs){Map<String,Object> input=map(raw);String ref=text(input.get("ref"));if(!ref.startsWith("pdf:input:")||ref.startsWith("pdf:input:verificationEvidence:"))continue;
            List<Object> references=new ArrayList<>();for(Map.Entry<String,List<Map<String,Object>>> entry:data.rules.entrySet())for(Map<String,Object> rule:entry.getValue())for(Object bound:list(rule.get("inputs"))){Map<String,Object> item=map(bound);if(!ref.equals(item.get("ref"))||!"effective".equals(item.get("stage"))||"evidence".equals(item.get("role")))continue;
                Map<String,Object> point=data.points.get(entry.getKey());Map<String,Object> description=obj("pointId",point.get("id"),"label",point.get("label"),"clause",point.get("clause"),"sourceStatus",point.get("relationStatus"));
                if(references.stream().noneMatch(row->Objects.equals(map(row).get("pointId"),point.get("id")))&&references.size()<3)references.add(description);
            }
            if(!references.isEmpty())fields.put(ref,obj("key",ref,"valueType",input.get("valueType"),"referencePoints",references));
        }
        return new ArrayList<>(fields.values());
    }

    Map<String,Object> modelContext(List<?> requested) {
        Snapshot data=load();List<Object> fields=new ArrayList<>();if(data==null)return obj("fields",fields);
        for(Object raw:requested){String ref=text(raw);List<Object> evidence=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(Map.Entry<String,List<Map<String,Object>>> entry:data.rules.entrySet())for(Map<String,Object> rule:entry.getValue())for(Object bound:list(rule.get("inputs"))){Map<String,Object> input=map(bound);if(!ref.equals(input.get("ref"))||!"effective".equals(input.get("stage"))||!seen.add(entry.getKey())||evidence.size()>=3)continue;
                Map<String,Object> point=data.points.get(entry.getKey());List<?> quotes=list(point.get("evidence"));if(quotes.isEmpty())continue;Map<String,Object> source=map(quotes.get(0));String quote=text(source.get("quote"));if(quote.length()>500)quote=quote.substring(0,500);
                evidence.add(obj("pointId",point.get("id"),"clause",point.get("clause"),"sourceStatus",point.get("relationStatus"),"page",source.get("page"),"excerpt",quote));
            }
            if(!evidence.isEmpty())fields.add(obj("key",ref,"evidence",evidence));
        }return obj("fields",fields);
    }

    static Map<String,Object> modelMatches(JsonNode response,Map<String,Object> window,Map<String,Map<String,Object>> nodes) {
        Map<String,Object> result=new LinkedHashMap<>(),allowed=new LinkedHashMap<>();for(Object raw:list(window.get("fields"))){Map<String,Object> field=map(raw);allowed.put(text(field.get("key")),field);}Set<String> seen=new HashSet<>();
        for(JsonNode row:response.path("pdfReferenceMatches")){String id=row.path("nodeId").asText(),ref=row.path("inputRef").asText(),quote=row.path("sourceQuote").asText(),reason=row.path("reason").asText();JsonNode confidence=row.path("confidence");
            if(!list(window.get("nodeIds")).contains(id)||!allowed.containsKey(ref)||!nodes.containsKey(id)||quote.trim().isEmpty()||!text(nodes.get(id).get("semanticSourceQuote")).contains(quote)||reason.trim().isEmpty()||!confidence.isNumber()||!Double.isFinite(confidence.asDouble())||confidence.asDouble()<0||confidence.asDouble()>1||!seen.add(id+"\n"+ref))continue;
            Map<String,Object> field=map(allowed.get(ref));@SuppressWarnings("unchecked") List<Object> proposals=(List<Object>)result.computeIfAbsent(id,ignored->new ArrayList<>());if(proposals.size()<4)proposals.add(obj("inputRef",ref,"valueType",field.get("valueType"),"referencePoints",field.get("referencePoints"),"sourceQuote",quote,"reason",reason,"confidence",confidence.asDouble(),"semanticStatus","unverified"));
        }return result;
    }

    static String denial(Map<String,Object> revision,String sourceId) {
        if(revision==null||!Boolean.FALSE.equals(revision.get("sourceStale")))return "revision_source_stale_or_unknown";
        Map<String,Object> graph=map(revision.get("graph"));String selected=text(map(graph.get("selectedDocuments")).get("SCC"));
        if(sourceId==null||sourceId.isEmpty()||!sourceId.equals(selected))return "source_not_selected";
        Map<String,Object> found=null;for(Object raw:list(graph.get("sources"))){Map<String,Object> row=map(raw);if(!sourceId.equals(text(row.get("id"))))continue;if(found!=null)return "source_identity_ambiguous";found=row;}
        if(found==null||!"SCC".equalsIgnoreCase(text(found.get("fileKey")))||!"pdf".equalsIgnoreCase(text(found.get("format")))||!SOURCE_SHA.equalsIgnoreCase(text(found.get("sourceSha256"))))return "source_edition_unsupported";
        return null;
    }
    private static Map<String,Object> meta(){return obj("available",true,"sourceSha256",SOURCE_SHA,"status","historical_analysis_requires_review","limitations",LIMITATIONS,"executable",false);}
    private static Map<String,Object> unavailable(String reason){return obj("available",false,"status",reason,"limitations",LIMITATIONS,"executable",false);}
    private static Map<String,Object> summary(Map<String,Object> point,Snapshot data){return obj("pointId",point.get("id"),"label",point.get("label"),"kind",point.get("kind"),"clause",point.get("clause"),"relationStatus",point.get("relationStatus"),"summary",point.get("meaning"),"ruleCount",data.rules.getOrDefault(text(point.get("id")),Collections.emptyList()).size());}

    String effectiveFactType(String ref){Snapshot data=load();if(data==null)return null;Map<String,Object> definition=null;for(Object raw:data.inputs){Map<String,Object> item=map(raw);if(ref.equals(item.get("ref"))){definition=item;break;}}if(definition==null||ref.startsWith("pdf:input:verificationEvidence:"))return null;
        for(List<Map<String,Object>> rules:data.rules.values())for(Map<String,Object> rule:rules)for(Object raw:list(rule.get("inputs"))){Map<String,Object> input=map(raw);if(ref.equals(input.get("ref"))&&"effective".equals(input.get("stage"))&&!"evidence".equals(input.get("role")))return text(definition.get("valueType"));}return null;
    }

    private Snapshot load(){
        Snapshot current=snapshot;if(current!=null)return current;
        if(directory==null||directory.trim().isEmpty())return null;
        synchronized(this){if(snapshot!=null)return snapshot;
            try{
                Path root=Paths.get(directory);byte[] a=read(root.resolve("pdf.json"),pdfSha),b=read(root.resolve("atomic-pdf.json"),atomicSha);
                Map<String,Object> pdf=JsonUtils.readMap(new String(a,StandardCharsets.UTF_8)),atomic=JsonUtils.readMap(new String(b,StandardCharsets.UTF_8));
                if(!SOURCE_SHA.equals(pdf.get("sourceSha256"))||!SOURCE_SHA.equals(atomic.get("sourceSha256"))||!"scc-pdf-1bebab1afbc0".equals(pdf.get("sourceId"))||!Objects.equals(pdf.get("sourceId"),atomic.get("sourceId")))return null;
                Map<String,Map<String,Object>> points=new LinkedHashMap<>();for(Object raw:list(pdf.get("points"))){Map<String,Object> point=map(raw);String id=text(point.get("id"));if(id.isEmpty()||points.put(id,point)!=null)return null;}
                Map<String,List<Map<String,Object>>> rules=new LinkedHashMap<>();for(Object raw:list(atomic.get("rules"))){Map<String,Object> rule=map(raw);for(Object id:list(rule.get("chainIds"))){String key=text(id);if(!points.containsKey(key))return null;rules.computeIfAbsent(key,ignored->new ArrayList<>()).add(rule);}}
                if(points.size()!=74||list(atomic.get("rules")).size()!=138)return null;
                List<Map<String,Object>> allRules=new ArrayList<>();for(Object raw:list(atomic.get("rules")))allRules.add(map(raw));snapshot=new Snapshot(points,rules,list(atomic.get("inputs")),allRules);return snapshot;
            }catch(Exception invalid){return null;}
        }
    }
    private static byte[] read(Path path,String expected)throws Exception{if(!Files.isRegularFile(path)||Files.size(path)>2_000_000)throw new IllegalStateException("Invalid reference file");byte[] data=Files.readAllBytes(path);if(!expected.equals(DraftPdfConverter.sha256(data)))throw new IllegalStateException("Reference hash mismatch");return data;}
    private static final class Snapshot {final Map<String,Map<String,Object>> points;final Map<String,List<Map<String,Object>>> rules;final List<?> inputs;final List<Object> fields;final List<Map<String,Object>> allRules;Snapshot(Map<String,Map<String,Object>> points,Map<String,List<Map<String,Object>>> rules,List<?> inputs,List<Map<String,Object>> allRules){this.points=points;this.rules=rules;this.inputs=inputs;this.allRules=allRules;this.fields=Collections.unmodifiableList(effectiveFields(this));}}
}
