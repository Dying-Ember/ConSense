package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Retain registered current-source business definitions before asking the model for local rules. */
final class DraftKnownRoots {
    private DraftKnownRoots() { }
    private static final List<String> CORE=Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","optional","options","unit","fixedValue","definitionScope","catalogAlignment","definitionReview","status","manual","applicability","definitionKind");

    static Result build(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,
                        Map<String,DraftCurrentTemplateAlignment.Index> indexes,Map<String,Object> baseGraph) {
        Map<String,Map<String,Object>> definitions=DraftCatalogAlignment.definitions();Map<String,Proof> proofs=new TreeMap<>();Map<String,String> taskSources=new HashMap<>();Set<String> allowedSources=allowedSources(readings,baseGraph);
        for(Map<String,Object> task:tasks){String source=text(task.get("sourceId"));JsonNode reading=readings.get(source);if(allowedSources.contains(source)&&reading!=null&&"bound".equals(reading.path("evidenceStatus").asText())&&reading.path("sourceSha256").asText().equals(text(task.get("sourceSha256"))))taskSources.put(text(task.get("taskId")),identity(source,task.get("sourceSha256")));}
        for(Map<String,Object> task:tasks) {
            if(!"guidance".equals(task.get("itemKind")))continue;String source=text(task.get("sourceId"));JsonNode reading=readings.get(source);if(reading==null||!allowedSources.contains(source))continue;
            DraftCurrentTemplateAlignment.Index index=indexes.get(source);if(index==null)index=DraftCurrentTemplateAlignment.index(reading);
            Map<String,Object> payload=DraftReviewDiscovery.sourceTaskPayload(reading,task),alignment=index.align(task,payload);
            boolean definitionOnly="definition_reference_only".equals(alignment.get("definitionStatus"));
            if((!definitionOnly&&!"exact_instruction_reference".equals(alignment.get("definitionStatus")))||!"current_instruction_reference".equals(alignment.get("referenceKind")))continue;
            Set<String> actionKeys=new HashSet<>();for(Object raw:list(alignment.get("fixedActionReferences")))for(Object key:list(map(raw).get("inputKeys")))actionKeys.add(text(key));
            String quote=text(map(payload.get("instruction")).get("text"));if(quote.trim().isEmpty())continue;
            boolean registeredIncomplete=definitionOnly&&list(alignment.get("reasonCodes")).contains(DraftCurrentTemplateAlignment.REGISTERED_INCOMPLETE_NTT17_REFERENCE);
            Map<String,Object> anchor=registeredIncomplete?DraftReviewDiscovery.registeredIncompleteInstructionAnchor(reading,task):DraftReviewDiscovery.sourceTaskAnchor(reading,task);if(anchor==null||anchor.isEmpty())continue;
            for(Object raw:list(alignment.get("fixedRootKeys"))) {
                String key=text(raw);Map<String,Object> field=definitions.get(key);
                // An exact current note can establish its shared definition without granting an action or range.
                if((!definitionOnly&&!actionKeys.contains(key))||key.contains(".")||field==null||Boolean.TRUE.equals(field.get("hidden")))continue;
                proofs.computeIfAbsent(key,ignored->new Proof()).add(task,quote,text(alignment.get("conditionStatus")),anchor);
            }
        }
        Map<String,Set<String>> closures=new TreeMap<>();Set<String> retained=new TreeSet<>();
        for(String key:proofs.keySet()){Set<String> closure=new TreeSet<>();closure(key,definitions,closure);closures.put(key,closure);retained.addAll(closure);}
        Map<String,List<Map<String,Object>>> existing=new HashMap<>();for(Map<String,Object> node:nodes(baseGraph))if(!"source_seed".equals(node.get("definitionKind"))&&Arrays.asList("input","decision","content").contains(text(map(node.get("role")).get("kind")))){
            String key=text(map(node.get("catalogAlignment")).get("baselineKey"));if(!key.isEmpty())existing.computeIfAbsent(key,ignored->new ArrayList<>()).add(node);
        }
        Map<String,Object> ids=new LinkedHashMap<>();Set<String> blocked=new HashSet<>(map(map(state(baseGraph).get("catalogReview")).get("exclusions")).keySet());Map<String,Map<String,Object>> reuse=new HashMap<>();Set<String> occupied=new HashSet<>();for(Map<String,Object> node:nodes(baseGraph))occupied.add(text(node.get("id")));
        for(String key:retained) {
            List<Map<String,Object>> candidates=existing.getOrDefault(key,Collections.emptyList());
            if(candidates.size()>1){blocked.add(key);continue;}
            if(candidates.size()==1){Map<String,Object> prior=candidates.get(0);if(!compatible(prior,definitions.get(key))){blocked.add(key);continue;}ids.put(key,prior.get("id"));reuse.put(key,prior);}
            else {String id="current-fixed-"+DraftAdoption.hash(DraftBusinessRules.RULE_VERSION+"\n"+key).substring(0,24);if(occupied.contains(id)){blocked.add(key);continue;}ids.put(key,id);}
        }
        for(Map.Entry<String,Map<String,Object>> entry:reuse.entrySet()){String key=entry.getKey();int dot=key.lastIndexOf('.');String parent=dot<0?"":text(ids.get(key.substring(0,dot)));if(!parent.equals(text(entry.getValue().get("parentId"))))blocked.add(key);}
        // An ambiguous existing prerequisite must not be replaced or silently omitted from an eligibility rule.
        Set<String> active=new TreeSet<>();for(Map.Entry<String,Set<String>> entry:closures.entrySet())if(Collections.disjoint(entry.getValue(),blocked))active.addAll(entry.getValue());
        Map<String,Map<String,Object>> roots=new LinkedHashMap<>();Map<String,Set<String>> owners=new HashMap<>();Map<String,Map<String,String>> quotes=new HashMap<>();Map<String,Set<String>> dependencies=new HashMap<>(),rootSources=new HashMap<>();
        for(String key:active) {
            Proof proof=proofs.get(key);Map<String,Object> node=reuse.containsKey(key)?copy(reuse.get(key)):DraftFixedBaseline.candidate(baseGraph,key,definitions.get(key),ids);
            Set<String> sourceIds=new TreeSet<>(),taskIds=new TreeSet<>(),conditions=new TreeSet<>();Map<String,String> taskQuotes=new TreeMap<>();List<Object> references=new ArrayList<>();Map<String,Map<String,Object>> anchors=new TreeMap<>();
            if(proof!=null){sourceIds.addAll(proof.sources);taskIds.addAll(proof.quotes.keySet());taskQuotes.putAll(proof.quotes);references.addAll(proof.references);conditions.addAll(proof.conditions);anchors.putAll(proof.anchors);}
            Set<String> prerequisiteTasks=new TreeSet<>();for(Map.Entry<String,Set<String>> entry:closures.entrySet())if(entry.getValue().contains(key)&&active.contains(entry.getKey())){Proof origin=proofs.get(entry.getKey());sourceIds.addAll(origin.sources);if(!entry.getKey().equals(key)){prerequisiteTasks.addAll(origin.quotes.keySet());if(proof==null){anchors.putAll(origin.anchors);for(Object raw:origin.references){Map<String,Object> reference=new LinkedHashMap<>(map(raw));reference.put("prerequisiteOfBaselineKey",entry.getKey());references.add(reference);}}}}
            if(!reuse.containsKey(key)) {
                node.put("definitionKind","current_fixed_reference");node.put("manual",false);node.put("docIds",new ArrayList<>(sourceIds));node.put("sourceUnitIds",proof==null?new ArrayList<>():new ArrayList<>(proof.observations));
                node.put("sourceAnchors",new ArrayList<>());node.put("editTargets",new ArrayList<>());node.put("relatedInputIds",new ArrayList<>());
                if(!anchors.isEmpty()){node.put("semanticSourceQuote",anchors.values().iterator().next().get("text"));List<Object> passages=new ArrayList<>(),evidence=new ArrayList<>();for(Map<String,Object> anchor:anchors.values()){passages.add(copy(anchor));evidence.add(obj("docId",anchor.get("docId"),"quote",anchor.get("text"),"locationEn",proof==null?"Current guidance requiring this catalog prerequisite":"Current registered guidance","locationZh",proof==null?"当前指导所需的固定前提定义":"当前已登记指导原文"));}node.put("sourceAnchors",passages);node.put("evidence",evidence);}
            }
            String conditionStatus=conditions.size()==1&&conditions.contains("current_evidence_complete")?"current_evidence_complete":"unknown";
            node.put("currentDefinitionReference",obj("kind","registered_current_instruction","definitionEvidence",proof==null?"catalog_prerequisite":"exact_current_instruction","baselineKey",key,"ruleVersion",DraftBusinessRules.RULE_VERSION,"taskIds",new ArrayList<>(taskIds),"taskQuotes",taskQuotes,"sourceReferences",references,"prerequisiteForTaskIds",new ArrayList<>(prerequisiteTasks),"conditionStatus",conditionStatus,"existing",reuse.containsKey(key),"confirmed",false,"editableAuthority",false));
            String id=text(node.get("id"));roots.put(id,node);owners.put(id,taskIds);quotes.put(id,taskQuotes);Set<String> identities=new HashSet<>();for(Object raw:references){Map<String,Object> ref=map(raw);identities.add(identity(ref.get("sourceId"),ref.get("sourceSha256")));}rootSources.put(id,identities);Set<String> required=new TreeSet<>();closure(key,definitions,required);required.retainAll(active);Set<String> requiredIds=new TreeSet<>();for(String dependency:required)requiredIds.add(text(ids.get(dependency)));dependencies.put(id,requiredIds);
        }
        return new Result(roots,owners,quotes,dependencies,taskSources,rootSources,definitions.keySet());
    }

    private static Set<String> allowedSources(Map<String,JsonNode> readings,Map<String,Object> baseGraph) {
        Map<String,Object> selected=map(baseGraph.get("selectedDocuments"));Set<String> sccSources=new HashSet<>(),allowed=new HashSet<>();
        for(Map.Entry<String,JsonNode> entry:readings.entrySet())if("SCC".equals(entry.getValue().path("fileKey").asText()))sccSources.add(entry.getKey());
        for(Map.Entry<String,JsonNode> entry:readings.entrySet()) {
            String fileKey=entry.getValue().path("fileKey").asText(),source=text(selected.get(fileKey)).trim();
            if(!source.isEmpty()){if(source.equals(entry.getKey()))allowed.add(entry.getKey());}
            // Multiple SCC sources need an explicit source identity; neither format nor numbering chooses an edition.
            else if(!"SCC".equals(fileKey)||sccSources.size()<=1)allowed.add(entry.getKey());
        }
        return allowed;
    }

    static final class Result {
        private final Map<String,Map<String,Object>> roots;private final Map<String,Set<String>> owners,dependencies,rootSources;private final Map<String,Map<String,String>> quotes;private final Map<String,String> taskSources,wireIds=new LinkedHashMap<>();
        Result(Map<String,Map<String,Object>> roots,Map<String,Set<String>> owners,Map<String,Map<String,String>> quotes,Map<String,Set<String>> dependencies,Map<String,String> taskSources,Map<String,Set<String>> rootSources,Set<String> definitionKeys){
            this.roots=roots;this.owners=owners;this.quotes=quotes;this.dependencies=dependencies;this.taskSources=taskSources;this.rootSources=rootSources;
            Set<String> seen=new HashSet<>();
            for(Map.Entry<String,Map<String,Object>> entry:roots.entrySet()){
                String key=text(map(entry.getValue().get("currentDefinitionReference")).get("baselineKey"));
                if(key.isEmpty())throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_REQUIRED: "+entry.getKey());
                if(!definitionKeys.contains(key))throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_UNKNOWN: "+key);
                if(!seen.add(key))throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_DUPLICATED: "+key);
                wireIds.put(entry.getKey(),key);
            }
        }
        List<Map<String,Object>> roots(){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> node:roots.values())result.add(copy(node));return result;}
        boolean has(String id){return roots.containsKey(id);}
        boolean accepts(Map<String,Object> node){Map<String,Object> expected=roots.get(text(node.get("id")));return expected!=null&&core(expected).equals(core(node));}
        Map<String,Map<String,Object>> forTasks(Collection<?> taskIds) {
            Set<String> requested=new HashSet<>(),sources=new HashSet<>();for(Object task:taskIds){String id=text(task);requested.add(id);if(taskSources.containsKey(id))sources.add(taskSources.get(id));}Set<String> available=new HashSet<>();for(String id:roots.keySet())if(!Collections.disjoint(owners.get(id),requested)||!Collections.disjoint(rootSources.get(id),sources))available.addAll(dependencies.get(id));
            Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(String id:roots.keySet())if(available.contains(id)){Map<String,Object> node=copy(roots.get(id)),reference=map(node.get("currentDefinitionReference"));Set<String> allowed=new TreeSet<>(owners.get(id));allowed.retainAll(requested);Map<String,String> localQuotes=new TreeMap<>();for(String task:allowed)localQuotes.put(task,quotes.get(id).get(task));reference.put("taskIds",new ArrayList<>(allowed));reference.put("taskQuotes",localQuotes);result.put(wireIds.get(id),node);}return result;
        }
        List<Map<String,Object>> descriptor(){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> node:roots.values()){Map<String,Object> reference=map(node.get("currentDefinitionReference"));result.add(copy(obj("id",node.get("id"),"wireId",wireIds.get(text(node.get("id"))),"baselineKey",reference.get("baselineKey"),"definitionSha256",DraftAdoption.hash(JsonUtils.write(core(node))),"sourceReferences",reference.get("sourceReferences"),"taskIds",reference.get("taskIds"),"prerequisiteForTaskIds",reference.get("prerequisiteForTaskIds"),"existing",reference.get("existing"))));}return result;}
    }
    private static Map<String,Object> core(Map<String,Object> node){Map<String,Object> result=new LinkedHashMap<>();for(String field:CORE)if(node.containsKey(field))result.put(field,node.get(field));return result;}
    private static void closure(String key,Map<String,Map<String,Object>> definitions,Set<String> found) {
        if(!found.add(key))return;Map<String,Object> field=definitions.get(key);if(field==null)throw new IllegalArgumentException("KNOWN_ROOT_DEFINITION_MISSING: "+key);
        for(Object raw:list(map(field.get("condition")).get("all"))){String dependency=text(map(raw).get("field"));if(dependency.isEmpty())throw new IllegalArgumentException("KNOWN_ROOT_PREREQUISITE_MISSING: "+key);closure(dependency,definitions,found);}
        for(String child:definitions.keySet())if(child.startsWith(key+".")&&child.lastIndexOf('.')==key.length()&&!Boolean.TRUE.equals(definitions.get(child).get("hidden")))closure(child,definitions,found);
    }
    private static boolean compatible(Map<String,Object> node,Map<String,Object> field) {
        String expected=text(field.get("kind"));if(Arrays.asList("contract","object").contains(expected))expected="record";if(Arrays.asList("bills","table","records").contains(expected))expected="list";if(Arrays.asList("textarea","longtext").contains(expected))expected="text";
        Map<String,Object> type=map(node.get("type")),alignment=map(node.get("catalogAlignment"));if("excluded".equals(node.get("status"))||!"matched".equals(alignment.get("status"))||!DraftBusinessRules.RULE_VERSION.equals(alignment.get("baselineVersion"))||!expected.equals(type.get("kind"))||Boolean.TRUE.equals(node.get("optional"))!=Boolean.TRUE.equals(field.get("optional")))return false;
        Object unit=type.containsKey("unit")?type.get("unit"):node.get("unit");if(!text(unit).equals(text(field.get("unit"))))return false;
        if(Arrays.asList("choice","multiselect").contains(expected)){Set<Object> actual=new HashSet<>(),wanted=new HashSet<>();for(Object option:list(node.get("options")))actual.add(map(option).get("value"));for(Object option:list(field.get("options")))wanted.add(map(option).get("value"));if(!actual.equals(wanted))return false;}return true;
    }
    private static Map<String,Object> copy(Map<String,Object> node){return JsonUtils.readMap(JsonUtils.write(node));}
    private static String identity(Object source,Object sha){return text(source)+"\n"+text(sha);}
    private static final class Proof {
        final Set<String> sources=new TreeSet<>(),observations=new TreeSet<>(),conditions=new TreeSet<>();final Map<String,String> quotes=new TreeMap<>();final Map<String,Map<String,Object>> anchors=new TreeMap<>();final List<Object> references=new ArrayList<>();
        void add(Map<String,Object> task,String quote,String condition,Map<String,Object> anchor){String id=text(task.get("taskId"));if(quotes.putIfAbsent(id,quote)!=null)return;anchors.put(id,copy(anchor));sources.add(text(task.get("sourceId")));observations.add(text(task.get("sourceObservationId")));conditions.add(condition);references.add(obj("taskId",id,"sourceId",task.get("sourceId"),"sourceSha256",task.get("sourceSha256"),"sourceObservationId",task.get("sourceObservationId")));}
    }
}
