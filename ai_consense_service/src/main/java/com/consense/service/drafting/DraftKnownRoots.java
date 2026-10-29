package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Retain registered current-source business definitions before asking the model for local rules. */
final class DraftKnownRoots {
    private DraftKnownRoots() { }
    private static final List<String> CORE=Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","optional","options","unit","fixedValue","definitionScope","catalogAlignment","definitionReview","status","manual","applicability","definitionKind");

    static Result build(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,
                        Map<String,DraftCurrentTemplateAlignment.Index> indexes,Map<String,Object> baseGraph) {
        Map<String,Map<String,Object>> definitions=DraftCatalogAlignment.definitions();Map<String,Proof> proofs=new TreeMap<>();Map<String,String> taskSources=new HashMap<>();Map<String,Set<String>> relatedProofs=new HashMap<>();Set<String> allowedSources=allowedSources(readings,baseGraph);
        for(Map<String,Object> task:tasks){String source=text(task.get("sourceId"));JsonNode reading=readings.get(source);if(allowedSources.contains(source)&&reading!=null&&"bound".equals(reading.path("evidenceStatus").asText())&&reading.path("sourceSha256").asText().equals(text(task.get("sourceSha256")))&&("source-"+DraftAdoption.hash(text(task.get("sourceKey"))+"|"+reading.path("analysisRevision").asText()+"|"+reading.path("analysisInputSha256").asText()+"|"+reading.path("analysisSha256").asText()).substring(0,24)).equals(task.get("taskId")))taskSources.put(text(task.get("taskId")),identity(source,task.get("sourceSha256")));}
        for(Map<String,Object> task:tasks) {
            if(!"guidance".equals(task.get("itemKind"))||!taskSources.containsKey(text(task.get("taskId"))))continue;String source=text(task.get("sourceId"));JsonNode reading=readings.get(source);if(reading==null||!allowedSources.contains(source))continue;
            DraftCurrentTemplateAlignment.Index index=indexes.get(source);if(index==null)index=DraftCurrentTemplateAlignment.index(reading);
            Map<String,Object> payload;try{payload=DraftReviewDiscovery.sourceTaskPayload(reading,task);}catch(BizException changed){continue;}Map<String,Object> alignment=index.align(task,payload);
            boolean definitionOnly="definition_reference_only".equals(alignment.get("definitionStatus"));
            String quote=text(map(payload.get("instruction")).get("text"));if(quote.trim().isEmpty())continue;
            boolean registeredIncomplete=definitionOnly&&list(alignment.get("reasonCodes")).contains(DraftCurrentTemplateAlignment.REGISTERED_INCOMPLETE_NTT17_REFERENCE);
            Map<String,Object> anchor;try{anchor=registeredIncomplete?DraftReviewDiscovery.registeredIncompleteInstructionAnchor(reading,task):DraftReviewDiscovery.sourceTaskAnchor(reading,task);}catch(BizException changed){continue;}if(anchor==null||anchor.isEmpty())continue;
            Set<String> supplied=new HashSet<>();for(Object raw:list(payload.get("relatedGuidanceNotes")))if(raw instanceof JsonNode){String observation=((JsonNode)raw).path("id").asText();if(!observation.isEmpty())supplied.add(identity(source,task.get("sourceSha256"))+"\n"+observation);}relatedProofs.put(text(task.get("taskId")),supplied);
            if((!definitionOnly&&!"exact_instruction_reference".equals(alignment.get("definitionStatus")))||!"current_instruction_reference".equals(alignment.get("referenceKind")))continue;
            Set<String> actionKeys=new HashSet<>();for(Object raw:list(alignment.get("fixedActionReferences")))for(Object key:list(map(raw).get("inputKeys")))actionKeys.add(text(key));
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
        Map<String,Map<String,Object>> roots=new LinkedHashMap<>();Map<String,Set<String>> owners=new HashMap<>();Map<String,Map<String,String>> quotes=new HashMap<>();Map<String,Set<String>> dependencies=new HashMap<>(),rootSources=new HashMap<>(),proofRoots=new HashMap<>();
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
            boolean reviewedExisting=reuse.containsKey(key)&&"confirmed".equals(node.get("status"))&&DraftCatalogAlignment.reviewed(node)&&Boolean.TRUE.equals(map(node.get("definitionReview")).get("confirmed"))&&key.equals(map(node.get("currentDefinitionReference")).get("baselineKey"));
            // A new template may use an already reviewed fact without replacing its original proof or answer.
            if(!reviewedExisting)node.put("currentDefinitionReference",obj("kind","registered_current_instruction","definitionEvidence",proof==null?"catalog_prerequisite":"exact_current_instruction","baselineKey",key,"ruleVersion",DraftBusinessRules.RULE_VERSION,"taskIds",new ArrayList<>(taskIds),"taskQuotes",taskQuotes,"sourceReferences",references,"prerequisiteForTaskIds",new ArrayList<>(prerequisiteTasks),"conditionStatus",conditionStatus,"existing",reuse.containsKey(key),"confirmed",false,"editableAuthority",false));
            String id=text(node.get("id"));roots.put(id,node);owners.put(id,taskIds);quotes.put(id,taskQuotes);Set<String> identities=new HashSet<>();for(Object raw:references){Map<String,Object> ref=map(raw);String sourceIdentity=identity(ref.get("sourceId"),ref.get("sourceSha256"));identities.add(sourceIdentity);proofRoots.computeIfAbsent(sourceIdentity+"\n"+text(ref.get("sourceObservationId")),ignored->new HashSet<>()).add(id);}rootSources.put(id,identities);Set<String> required=new TreeSet<>();closure(key,definitions,required);required.retainAll(active);Set<String> requiredIds=new TreeSet<>();for(String dependency:required)requiredIds.add(text(ids.get(dependency)));dependencies.put(id,requiredIds);
        }
        Map<String,Set<String>> readers=new HashMap<>();for(Map.Entry<String,Set<String>> entry:relatedProofs.entrySet())for(String proof:entry.getValue())for(String root:proofRoots.getOrDefault(proof,Collections.emptySet()))if(!owners.get(root).contains(entry.getKey()))readers.computeIfAbsent(root,ignored->new HashSet<>()).add(entry.getKey());
        Set<String> reviewedDefinitions=new HashSet<>(),currentDefinitionIds=new HashSet<>();Deque<Map<String,Object>> fields=new ArrayDeque<>(DraftReviewGraph.fields(baseGraph));
        while(!fields.isEmpty()){Map<String,Object> field=fields.removeFirst();String id=text(field.get("id"));if(DraftTemplateGraphService.definitionReviewCurrent(field))currentDefinitionIds.add(id);Map<String,Object> root=roots.get(id),alignment=map(field.get("catalogAlignment")),reference=root==null?Collections.emptyMap():map(root.get("currentDefinitionReference"));if(root!=null&&"matched".equals(alignment.get("status"))&&Boolean.TRUE.equals(alignment.get("confirmed"))&&DraftBusinessRules.RULE_VERSION.equals(alignment.get("baselineVersion"))&&Objects.equals(reference.get("baselineKey"),alignment.get("baselineKey"))&&currentDefinitionIds.contains(id)&&DraftBusinessRules.RULE_VERSION.equals(reference.get("ruleVersion")))reviewedDefinitions.add(id);for(Object child:list(field.get("children")))fields.add(map(child));}
        return new Result(roots,owners,quotes,dependencies,taskSources,rootSources,definitions.keySet(),readers,reviewedDefinitions,reviewedProjectDefinitions(baseGraph,readings,tasks,definitions,currentDefinitionIds));
    }

    /** automationInput has verified every saved project original/analysis; saved anchors were rebound by pinGraph.
     * A reference outside this job remains read-only and must still match that selected, bound source cohort. */
    private static ReadOnlyPool reviewedProjectDefinitions(Map<String,Object> graph,Map<String,JsonNode> readings,List<Map<String,Object>> tasks,Map<String,Map<String,Object>> definitions,Set<String> currentDefinitionIds) {
        ReadOnlyPool result=new ReadOnlyPool();Map<String,Map<String,Object>> sources=new HashMap<>();Set<String> ambiguousSources=new HashSet<>();
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);String id=text(source.get("id"));if(sources.putIfAbsent(id,source)!=null)ambiguousSources.add(id);}
        Map<String,List<Map<String,Object>>> qualified=new TreeMap<>();Map<String,Map<String,Object>> currentAnchors=new HashMap<>();Set<String> exclusions=map(map(state(graph).get("catalogReview")).get("exclusions")).keySet();
        for(Map<String,Object> node:nodes(graph)){
            Map<String,Object> alignment=map(node.get("catalogAlignment")),reference=map(node.get("currentDefinitionReference"));String key=text(alignment.get("baselineKey"));
            if(exclusions.contains(key)||!definitions.containsKey(key)||!DraftCatalogAlignment.businessCandidate(node)||!Boolean.TRUE.equals(alignment.get("confirmed"))||!compatible(node,definitions.get(key))||!currentDefinitionIds.contains(text(node.get("id")))
                    ||!"registered_current_instruction".equals(reference.get("kind"))||!key.equals(reference.get("baselineKey"))||!DraftBusinessRules.RULE_VERSION.equals(reference.get("ruleVersion")))continue;
            Set<String> declared=new HashSet<>(),anchored=new HashSet<>();boolean valid=!list(node.get("docIds")).isEmpty()&&!list(node.get("sourceAnchors")).isEmpty();
            for(Object raw:list(node.get("docIds"))){String id=text(raw);Map<String,Object> source=sources.get(id);declared.add(id);if(source==null||ambiguousSources.contains(id)||!id.equals(text(map(graph.get("selectedDocuments")).get(text(source.get("fileKey")))))||!"bound".equals(source.get("evidenceStatus")))valid=false;}
            for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);String sourceId=text(anchor.get("docId"));Map<String,Object> source=sources.get(sourceId);if(!declared.contains(sourceId)||source==null||!boundDefinitionAnchor(anchor,source)){valid=false;continue;}
                if(readings.containsKey(sourceId)){
                    String observation=text(anchor.get("sourceObservationId")),cacheKey=sourceId+"\n"+observation;Map<String,Object> current=currentAnchors.get(cacheKey);
                    if(current==null){current=Collections.emptyMap();for(Map<String,Object> task:tasks)if(sourceId.equals(text(task.get("sourceId")))&&observation.equals(text(task.get("sourceObservationId"))))try{Map<String,Object> candidate=DraftReviewDiscovery.sourceTaskAnchor(readings.get(sourceId),task);if(candidate==null)candidate=DraftReviewDiscovery.registeredIncompleteInstructionAnchor(readings.get(sourceId),task);if(candidate!=null){current=candidate;break;}}catch(BizException changed){/* A stale task cannot certify a saved definition. */}currentAnchors.put(cacheKey,current);}
                    if(current.isEmpty()||!sameAnchor(anchor,current))valid=false;
                }
                anchored.add(sourceId);
            }
            if(valid&&anchored.containsAll(declared))qualified.computeIfAbsent(key,ignored->new ArrayList<>()).add(node);
        }
        for(Map.Entry<String,List<Map<String,Object>>> entry:qualified.entrySet())if(entry.getValue().size()==1)result.nodes.put(entry.getKey(),readOnly(entry.getValue().get(0)));
        // Parent identity is part of the reviewed schema, not a replaceable display relationship.
        Set<String> invalid=new HashSet<>();for(Map.Entry<String,Map<String,Object>> entry:result.nodes.entrySet()){String key=entry.getKey();int dot=key.lastIndexOf('.');String parent=dot<0?"":text(map(result.nodes.get(key.substring(0,dot))).get("id"));if(dot>=0&&parent.isEmpty()||!parent.equals(text(entry.getValue().get("parentId"))))invalid.add(key);}
        for(String key:invalid)result.nodes.remove(key);
        for(String key:result.nodes.keySet()){Set<String> required=new LinkedHashSet<>();closure(key,definitions,required);result.dependencies.put(key,required);}return result;
    }
    private static boolean boundDefinitionAnchor(Map<String,Object> anchor,Map<String,Object> source){
        Map<String,Object> binding=map(anchor.get("sourceObservationBinding")),observation=map(anchor.get("sourceObservation"));String id=text(anchor.get("sourceObservationId"));
        if(!"bound".equals(binding.get("status"))||!"named_saved_source_observation".equals(binding.get("kind"))||id.isEmpty()||!id.equals(text(binding.get("sourceObservationId")))||!id.equals(text(observation.get("id")))||text(anchor.get("text")).trim().isEmpty()||!Objects.equals(anchor.get("text"),observation.get("text"))
                ||text(source.get("sourceDocumentId")).isEmpty()||!text(source.get("sourceDocumentId")).equals(text(anchor.get("sourceDocumentId")))||list(anchor.get("observedRanges")).isEmpty()||!same(anchor.get("observedRanges"),binding.get("observedRanges"))||!same(anchor.get("observedRanges"),JsonUtils.mapper().convertValue(DraftReviewSources.observationRanges(JsonUtils.mapper().valueToTree(observation)),Object.class)))return false;
        for(String key:Arrays.asList("sourceSha256","analysisRevision","analysisInputSha256","analysisSha256")){String actual=text(source.get(key));if(actual.isEmpty()||!"analysisRevision".equals(key)&&!actual.matches("[a-f0-9]{64}")||!actual.equals(text(anchor.get(key)))||!actual.equals(text(binding.get(key))))return false;}return true;
    }
    private static boolean sameAnchor(Map<String,Object> saved,Map<String,Object> current){if(!text(saved.get("sourceDocumentId")).equals(text(current.get("sourceDocumentId"))))return false;for(String key:Arrays.asList("docId","sourceSha256","sourceObservationId","sourceObservation","observedRanges","text","analysisRevision","analysisInputSha256","analysisSha256"))if(!same(saved.get(key),current.get(key)))return false;return true;}
    private static boolean same(Object first,Object second){return Objects.equals(JsonUtils.mapper().valueToTree(first),JsonUtils.mapper().valueToTree(second));}
    private static Map<String,Object> readOnly(Map<String,Object> node){Map<String,Object> result=new LinkedHashMap<>();for(String key:Arrays.asList("id","parentId","nameZh","nameEn","questionZh","questionEn","type","role","optional","options","unit","definitionScope","status","manual","definitionKind"))if(node.containsKey(key))result.put(key,node.get(key));Map<String,Object> alignment=map(node.get("catalogAlignment")),reference=map(node.get("currentDefinitionReference"));result.put("catalogAlignment",obj("status",alignment.get("status"),"baselineKey",alignment.get("baselineKey"),"baselineVersion",alignment.get("baselineVersion"),"confirmed",alignment.get("confirmed"),"reason",alignment.get("reason")));result.put("currentDefinitionReference",obj("kind","registered_current_instruction","baselineKey",reference.get("baselineKey"),"ruleVersion",reference.get("ruleVersion"),"conditionStatus",reference.get("conditionStatus"),"taskIds",Collections.emptyList(),"taskQuotes",Collections.emptyMap()));return copy(result);}
    private static final class ReadOnlyPool {final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();final Map<String,Set<String>> dependencies=new HashMap<>();}

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
        private final Map<String,Map<String,Object>> roots;private final Map<String,Set<String>> owners,dependencies,rootSources,readers;private final Map<String,Map<String,String>> quotes;private final Map<String,String> taskSources,wireIds=new LinkedHashMap<>();private final ReadOnlyPool readOnly;
        Result(Map<String,Map<String,Object>> roots,Map<String,Set<String>> owners,Map<String,Map<String,String>> quotes,Map<String,Set<String>> dependencies,Map<String,String> taskSources,Map<String,Set<String>> rootSources,Set<String> definitionKeys){
            this(roots,owners,quotes,dependencies,taskSources,rootSources,definitionKeys,Collections.emptyMap(),Collections.emptySet(),new ReadOnlyPool());
        }
        private Result(Map<String,Map<String,Object>> roots,Map<String,Set<String>> owners,Map<String,Map<String,String>> quotes,Map<String,Set<String>> dependencies,Map<String,String> taskSources,Map<String,Set<String>> rootSources,Set<String> definitionKeys,Map<String,Set<String>> readers,Set<String> reviewedDefinitions,ReadOnlyPool readOnly){
            this.roots=roots;this.owners=owners;this.quotes=quotes;this.dependencies=dependencies;this.taskSources=taskSources;this.rootSources=rootSources;this.readers=readers;
            this.readOnly=readOnly;
            Set<String> seen=new HashSet<>();
            for(Map.Entry<String,Map<String,Object>> entry:roots.entrySet()){
                String key=text(map(entry.getValue().get("currentDefinitionReference")).get("baselineKey"));
                if(key.isEmpty())throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_REQUIRED: "+entry.getKey());
                if(!definitionKeys.contains(key))throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_UNKNOWN: "+key);
                if(!seen.add(key))throw new IllegalArgumentException("KNOWN_ROOT_BASELINE_KEY_DUPLICATED: "+key);
                wireIds.put(entry.getKey(),key);
            }
            for(String id:reviewedDefinitions){String key=wireIds.get(id);readOnly.nodes.putIfAbsent(key,readOnly(roots.get(id)));Set<String> required=new LinkedHashSet<>();for(String dependency:dependencies.getOrDefault(id,Collections.emptySet()))required.add(wireIds.get(dependency));readOnly.dependencies.putIfAbsent(key,required);}
        }
        List<Map<String,Object>> roots(){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> node:roots.values())result.add(copy(node));return result;}
        boolean has(String id){return roots.containsKey(id);}
        boolean currentOwnerProof(String rootId,String taskId){
            return roots.containsKey(rootId)&&owners.getOrDefault(rootId,Collections.emptySet()).contains(taskId)
                    &&!quotes.getOrDefault(rootId,Collections.emptyMap()).getOrDefault(taskId,"").trim().isEmpty()&&taskSources.containsKey(taskId);
        }
        boolean currentPrerequisiteProof(String rootId,String taskId,String originBaselineKey){
            for(String origin:roots.keySet())if(!origin.equals(rootId)&&(originBaselineKey==null||originBaselineKey.equals(wireIds.get(origin)))
                    &&dependencies.getOrDefault(origin,Collections.emptySet()).contains(rootId)&&currentOwnerProof(origin,taskId))return true;
            return false;
        }
        boolean accepts(Map<String,Object> node){Map<String,Object> expected=roots.get(text(node.get("id")));return expected!=null&&core(expected).equals(core(node));}
        Map<String,Map<String,Object>> forTasks(Collection<?> taskIds) {
            Set<String> requested=new HashSet<>();for(Object task:taskIds)requested.add(text(task));Set<String> available=new HashSet<>();for(String id:roots.keySet())if(!Collections.disjoint(owners.get(id),requested)||!Collections.disjoint(readers.getOrDefault(id,Collections.emptySet()),requested))available.addAll(dependencies.get(id));
            Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(String id:roots.keySet())if(available.contains(id)){Map<String,Object> node=copy(roots.get(id)),reference=map(node.get("currentDefinitionReference"));Set<String> allowed=new TreeSet<>(owners.get(id));allowed.retainAll(requested);Map<String,String> localQuotes=new TreeMap<>();for(String task:allowed)localQuotes.put(task,quotes.get(id).get(task));reference.put("taskIds",new ArrayList<>(allowed));reference.put("taskQuotes",localQuotes);result.put(wireIds.get(id),node);}return result;
        }
        Map<String,Map<String,Object>> forTasks(Collection<?> taskIds,Collection<?> candidateKeys){
            Map<String,Map<String,Object>> result=forTasks(taskIds);Set<String> extra=new LinkedHashSet<>();
            for(Object raw:candidateKeys){String key=text(raw);if(!readOnly.nodes.containsKey(key))continue;String seed=key;Set<String> parents=new HashSet<>();while(seed.contains(".")&&parents.add(seed))seed=seed.substring(0,seed.lastIndexOf('.'));if(!readOnly.nodes.containsKey(seed)||!text(readOnly.nodes.get(seed).get("parentId")).isEmpty())continue;
                Set<String> needed=new LinkedHashSet<>(readOnly.dependencies.get(seed));needed.removeAll(result.keySet());if(!readOnly.nodes.keySet().containsAll(needed))continue;Set<String> expanded=new LinkedHashSet<>(extra);expanded.addAll(needed);if(expanded.size()<=8)extra=expanded;
            }
            for(String key:extra)result.put(key,copy(readOnly.nodes.get(key)));return result;
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
