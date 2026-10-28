package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Restore only explicitly shared definitions; imported targets remain pending and must rebind. */
final class DraftBaselineRestore {
    private DraftBaselineRestore() { }
    /** Materialize only the user-authorized template definitions, without project history or answers. */
    static Map<String,Object> snapshot(Map<String,Object> graph,List<String> roots,boolean complete){return snapshot(graph,roots,complete,null);}
    static Map<String,Object> snapshot(Map<String,Object> graph,List<String> roots,boolean complete,Map<String,Object> trustedOrigin){
        List<Object> sources=new ArrayList<>();Map<String,Object> selected=map(graph.get("selectedDocuments"));
        for(Object value:selected.values()){
            Map<String,Object> original=source(graph,text(value)),identity=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","docId","editionId","fileKey","format","sourceSha256","analysisRevision","analysisInputSha256","analysisSha256","evidenceStatus"))if(original.containsKey(key))identity.put(key,original.get(key));
            sources.add(identity);
        }
        Map<String,Object> empty=obj("kind","consense-template-graph-v1","sources",sources,"selectedDocuments",selected,"graph",obj("variables",new ArrayList<>(),"sourceReviews",new LinkedHashMap<>(),"events",new ArrayList<>(),"nextId",1,"blank",true,"guidanceMode","recognized"));
        Map<String,Object> shared=restore(graph,empty,roots,false,complete);if(shared==null||list(map(state(shared).get("baselineRestoration")).get("fieldIds")).size()!=roots.size())return null;
        Map<String,Map<String,Object>> originals=new HashMap<>();for(Map<String,Object> node:nodes(graph))originals.put(text(node.get("id")),node);
        List<Object> cleaned=new ArrayList<>();
        for(Map<String,Object> node:nodes(shared)){
            Map<String,Object> original=originals.get(text(node.get("id"))),safe=map(sharedValue(node,"node"));
            if(!shareableLiteral(original,true))return null;if(!literalRole(original))safe.remove("fixedValue");
            List<Object> anchors=new ArrayList<>();for(Object raw:list(original.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);if(bound(anchor)){Map<String,Object> copy=sharedPassage(anchor,true);copy.remove("reason");anchors.add(copy);}}
            safe.put("sourceAnchors",anchors);
            String quote=text(safe.get("semanticSourceQuote"));if(!quote.isEmpty()&&anchors.stream().noneMatch(raw->text(map(raw).get("text")).contains(quote)))safe.remove("semanticSourceQuote");
            List<Object> targets=new ArrayList<>();for(Object raw:list(original.get("editTargets"))){Map<String,Object> target=map(raw),copy=sharedPassage(target,false);
                if(!bound(target)){copy.remove("native");copy.put("text","");copy.put("action","check");}
                boolean unsupported=target.containsKey("value")||target.containsKey("itemValue")||target.containsKey("itemId");
                for(String key:Arrays.asList("replacementEn","replacementZh","prefix","suffix"))if(target.containsKey(key)){
                    Object literal=target.get(key);boolean sourced=literal instanceof String&&(!((String)literal).isEmpty()?anchors.stream().anyMatch(anchor->Objects.equals(map(anchor).get("docId"),target.get("docId"))&&text(map(anchor).get("text")).contains((String)literal)):true);
                    if(sourced)copy.put(key,literal);else unsupported=true;
                }
                if(unsupported){if(complete)return null;copy.put("action","check");}
                copy.put("needsRebind",true);targets.add(copy);
            }safe.put("editTargets",targets);cleaned.add(safe);
        }
        state(shared).put("variables",cleaned);List<String> included=new ArrayList<>();for(Map<String,Object> node:nodes(shared))included.add(text(node.get("id")));state(shared).put("baselineSharedNodeIds",included);
        List<Object> relations=new ArrayList<>();for(Object raw:list(state(shared).get("relationProposals"))){Map<String,Object> row=map(raw);relations.add(obj("fromId",row.get("fromId"),"toId",row.get("toId"),"kind",row.get("kind"),"status","pending","reason","Shared template relation; review against this original."));}state(shared).put("relationProposals",relations);
        if(complete&&trustedOrigin==null)return null;
        Map<String,Object> requirementGraph=graph,review=trustedOrigin==null?Collections.emptyMap():map(state(trustedOrigin).get("baselineCompletenessReview"));
        if(complete&&"manual_complete_edition".equals(review.get("basis"))){
            // Only the service supplies this verified projection; the project revision keeps omitted historical records.
            requirementGraph=obj("selectedDocuments",graph.get("selectedDocuments"),"graph",obj("requirements",state(trustedOrigin).get("requirements")));
            state(shared).put("baselineCompletenessReview",JsonUtils.mapper().convertValue(review,Object.class));
        }
        if(trustedOrigin!=null&&!retainRequirements(requirementGraph,shared,trustedOrigin,complete))return null;
        // Retained output is evidence, not proof that OER conditions and editing scopes were fully represented.
        try{validate(shared);return shared;}catch(RuntimeException unsupported){return null;}
    }
    private static boolean retainRequirements(Map<String,Object> graph,Map<String,Object> shared,Map<String,Object> origin,boolean complete){
        if(origin==null)return false;
        Map<String,Map<String,Object>> original=new LinkedHashMap<>();for(Object raw:list(state(origin).get("requirements"))){Map<String,Object> item=map(raw);String id=text(item.get("id"));if(id.isEmpty()||original.put(id,item)!=null)return !complete;}
        Set<String> included=new HashSet<>(),seen=new HashSet<>(),selected=new HashSet<>();for(Map<String,Object> node:nodes(shared))included.add(text(node.get("id")));for(Object id:map(shared.get("selectedDocuments")).values())selected.add(text(id));
        Map<String,Object> oldScopes=map(state(origin).get("sourceScopeObservations")),scopes=new LinkedHashMap<>();Map<String,String> integrity=DraftScopeObservationIntegrity.inspect(origin);List<Object> retained=new ArrayList<>();
        for(Object raw:list(state(graph).get("requirements"))){Map<String,Object> item=map(raw),source=original.get(text(item.get("id")));boolean safe=source!=null&&seen.add(text(item.get("id")))&&selected.contains(text(item.get("sourceId")));
            if(source!=null)for(String key:Arrays.asList("sourceId","sourceSha256","sourceQuote","kind","sourceQuoteEvidence","analysisRevision","analysisInputSha256","analysisSha256"))if(!Objects.equals(item.get(key),source.get(key)))safe=false;
            if(!complete&&list(item.get("nodeIds")).isEmpty())safe=false;
            for(Object id:list(item.get("nodeIds")))if(!included.contains(text(id)))safe=false;
            if(!safe){if(complete)return false;continue;}
            Map<String,Object> copy=new LinkedHashMap<>();for(String key:Arrays.asList("id","sourceId","sourceSha256","sourceQuote","kind","reason","status","sourceQuoteEvidence","analysisRevision","analysisInputSha256","analysisSha256"))if(source.containsKey(key))copy.put(key,JsonUtils.mapper().convertValue(source.get(key),Object.class));copy.put("nodeIds",new ArrayList<>(list(item.get("nodeIds"))));
            String ref=text(map(copy.get("sourceQuoteEvidence")).get("sourceScopeObservationRef"));if(!ref.isEmpty()){if(!"valid".equals(integrity.get(ref))||!oldScopes.containsKey(ref)){if(complete)return false;continue;}scopes.put(ref,JsonUtils.mapper().convertValue(oldScopes.get(ref),Object.class));}
            retained.add(copy);
        }
        if(complete&&seen.size()!=original.size())return false;state(shared).put("requirements",retained);if(!scopes.isEmpty())state(shared).put("sourceScopeObservations",scopes);if(complete)state(shared).put("baselineRequirementsValidated",true);return true;
    }
    private static boolean bound(Map<String,Object> anchor){return "bound".equals(map(anchor.get("nativeBinding")).get("status"))||"bound".equals(map(anchor.get("sourceObservationBinding")).get("status"));}
    private static Map<String,Object> sharedPassage(Map<String,Object> original,boolean sourceAnchor){
        Map<String,Object> copy=map(sharedValue(original,"passage")),binding=map(original.get("nativeBinding"));if(!copy.containsKey("native")&&"bound".equals(binding.get("status"))&&!list(binding.get("spans")).isEmpty())copy.put("native",sharedValue(obj("sourceSha256",binding.get("sourceSha256"),"spans",binding.get("spans")),"native"));
        // The observation must remain exact so the current-source binder can validate it again; it grants no editing authority.
        if(sourceAnchor&&!text(original.get("sourceObservationId")).isEmpty()&&"bound".equals(map(original.get("sourceObservationBinding")).get("status")))for(String key:Arrays.asList("sourceObservationId","sourceObservation","observedRanges","analysisRevision","analysisInputSha256","analysisSha256"))if(original.containsKey(key))copy.put(key,JsonUtils.mapper().convertValue(original.get(key),Object.class));
        return copy;
    }
    private static Object sharedValue(Object value,String scope){
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();Set<String> keys=sharedKeys(scope);for(Map.Entry<String,Object> entry:map(value).entrySet())if(keys.contains(entry.getKey()))result.put(entry.getKey(),"reason".equals(entry.getKey())?"Shared template definition; review against this original.":sharedValue(entry.getValue(),entry.getKey()));return result;}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object item:list(value))result.add(sharedValue(item,scope));return result;}
        return value;
    }
    private static Set<String> sharedKeys(String scope){
        switch(scope){
            case "node":return set("id","parentId","type","role","nameZh","nameEn","questionZh","questionEn","options","optional","unit","definitionScope","catalogAlignment","correction","relatedInputIds","docIds","guidance","semanticSourceQuote","applicability","factProjectionReview","definitionKind","definitionOrigin","definitionReview","splitReview","editorialReview","status","manual","sourceUnitIds","evidence","relations","targets","fixedValue");
            case "type":return set("kind","zh","en","unit");
            case "role":return set("kind","zh","en");
            case "options":return set("value","label","labelZh","labelEn");
            case "label":return set("zh","zhHans","zhHant","en");
            case "guidance":return set("zh","en");
            case "catalogAlignment":return set("status","baselineKey","baselineVersion","scope","confirmed","reason","proposedMatches");
            case "proposedMatches":return set("baselineKey","semanticStatus","factProjection");
            case "factProjection":return set("branchValue","branchPolarity");
            case "correction":case "convertedCorrection":return set("kind","branches","clauses","mode","scopeId","scopeMode","otherwise","needsRebind","inputIds");
            case "branches":return set("id","mode","scopeId","scopeMode","clauses","resultValue","effectZh","effectEn");
            case "clauses":return set("inputId","baselineKey","operator","value");
            case "applicability":return set("kind","mode","clauses","confirmed","reason","targetBaselineKey","ruleVersion");
            case "factProjectionReview":return set("rootId","baselineKey","branchValue","branchPolarity","exhaustiveConfirmed","original","convertedCorrection","previousRuleResolution","origin","reason");
            case "original":return set("role","correction","catalogAlignment","definitionOrigin","definitionReview","targetBindings");
            case "targetBindings":return set("id","branchId","needsRebind");
            case "definitionOrigin":return set("kind","reason");
            case "definitionReview":return set("confirmed","basis");
            case "previousRuleResolution":return set("status","decision","reason");
            case "splitReview":return set("mode","inputIds","originalZh","originalEn");
            case "editorialReview":return set("kind","confirmed");
            case "passage":return set("id","docId","text","sourceSha256","native","page","action","branchId","valueSourceId","valueMode","listId","itemKey","separator","replacementPlacement","needsRebind");
            case "native":return set("sourceSha256","spans","paragraphId","paragraphSha256","start","end","expectedText","channel","operation");
            case "spans":return set("sourceSha256","paragraphId","paragraphSha256","start","end","expectedText","channel","operation");
            case "replacementPlacement":return set("spanIndex");
            default:return Collections.emptySet();
        }
    }
    private static Set<String> set(String... keys){return new HashSet<>(Arrays.asList(keys));}
    static Map<String,Object> restore(Map<String,Object> oldGraph,Map<String,Object> current,List<String> roots,boolean complete){return restore(oldGraph,current,roots,true,complete);}
    private static Map<String,Object> restore(Map<String,Object> oldGraph,Map<String,Object> current,List<String> roots,boolean useSharedNodes,boolean complete){
        Map<String,String> documents=new HashMap<>();Map<String,Object> oldSelection=map(oldGraph.get("selectedDocuments")),selection=map(current.get("selectedDocuments"));
        for(String file:oldSelection.keySet()){
            if(!selection.containsKey(file))continue;
            Map<String,Object> before=source(oldGraph,text(oldSelection.get(file))),after=source(current,text(selection.get(file)));
            if(before.isEmpty()||after.isEmpty()||!file.equals(before.get("fileKey"))||!file.equals(after.get("fileKey"))||!"bound".equals(after.get("evidenceStatus"))||!Objects.equals(before.get("sourceSha256"),after.get("sourceSha256"))||!Objects.equals(before.get("format"),after.get("format")))continue;
            for(String key:Arrays.asList("id","docId","editionId"))if(!text(before.get(key)).isEmpty())documents.put(text(before.get(key)),text(after.get("id")));
        }
        if(documents.isEmpty())return null;
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();for(Map<String,Object> node:nodes(oldGraph))nodes.put(text(node.get("id")),node);
        Set<String> included=new LinkedHashSet<>(),rootIds=new LinkedHashSet<>();for(String root:roots){Set<String> required=closure(root,nodes,documents);if(required!=null){included.addAll(required);rootIds.add(root);}}
        if(rootIds.isEmpty())return null;Set<String> sharedIds=new HashSet<>();for(Object id:list(state(oldGraph).get("baselineSharedNodeIds")))sharedIds.add(text(id));
        if(complete)for(Map<String,Object> node:nodes.values()){String id=text(node.get("id"));if("excluded".equals(node.get("status"))||useSharedNodes&&!sharedIds.contains(id))continue;Set<String> required=closure(id,nodes,documents);if(required==null)return null;included.addAll(required);}
        boolean added;do{added=false;for(Map<String,Object> node:nodes.values()){
            String id=text(node.get("id")),role=text(map(node.get("role")).get("kind"));if(included.contains(id)||!(useSharedNodes?sharedIds.contains(id):"confirmed".equals(node.get("status")))||!Arrays.asList("derived","verification").contains(role)||!text(node.get("parentId")).isEmpty())continue;
            Deque<String> refs=new ArrayDeque<>();for(Object ref:list(node.get("relatedInputIds")))if(!text(ref).isEmpty())refs.add(text(ref));references(node.get("correction"),refs);references(node.get("applicability"),refs);references(node.get("factProjectionReview"),refs);targetReferences(node.get("editTargets"),refs);refs.removeIf(id::equals);
            if(!refs.isEmpty()&&included.containsAll(refs)){Set<String> required=closure(id,nodes,documents);if(required!=null&&included.addAll(required))added=true;}
        }}while(added);
        List<Object> restored=new ArrayList<>();
        for(String id:included){Map<String,Object> original=nodes.get(id),node=new LinkedHashMap<>();
            for(String key:Arrays.asList("id","parentId","type","role","nameZh","nameEn","questionZh","questionEn","options","optional","unit","definitionScope","catalogAlignment","correction","relatedInputIds","editTargets","sourceAnchors","docIds","guidance","semanticSourceQuote","applicability","factProjectionReview","definitionKind","definitionOrigin","splitReview","editorialReview","fixedValue"))if(original.containsKey(key))node.put(key,JsonUtils.mapper().convertValue(original.get(key),Object.class));
            if(!literalRole(original))node.remove("fixedValue");
            node.put("status","pending");node.put("manual",false);node.put("sourceUnitIds",new ArrayList<>());node.put("evidence",new ArrayList<>());node.put("relations",new ArrayList<>());node.put("targets",new ArrayList<>());
            if(rootIds.contains(id)||hasSelectedAncestor(id,nodes,rootIds))node.put("definitionReview",obj("confirmed",true,"basis",null));
            if(node.containsKey("definitionOrigin"))map(node.get("definitionOrigin")).put("reason","Template-version definition explicitly shared for review.");
            Map<String,Object> alignment=map(node.get("catalogAlignment"));if(!alignment.isEmpty()){alignment.remove("proposedMatches");if(Boolean.TRUE.equals(alignment.get("confirmed")))alignment.put("reason","Definition previously reviewed for this exact template version.");}
            Map<String,Object> projection=map(node.get("factProjectionReview"));if(!projection.isEmpty())projection.remove("historicalAnswerResolution");
            List<Object> docIds=new ArrayList<>();for(Object raw:list(node.get("docIds"))){String mapped=documents.get(text(raw));if(mapped==null)return null;docIds.add(mapped);}node.put("docIds",docIds);
            for(String group:Arrays.asList("sourceAnchors","editTargets"))for(Object raw:list(node.get(group))){Map<String,Object> anchor=map(raw);String mapped=documents.get(text(anchor.get("docId")));if(mapped==null)return null;anchor.put("docId",mapped);anchor.remove("nativeBinding");anchor.remove("sourceObservationBinding");anchor.remove("sourceDocumentId");anchor.remove("editionId");Object currentSourceId=source(current,mapped).get("sourceDocumentId");if(currentSourceId!=null)anchor.put("sourceDocumentId",currentSourceId);
                if("editTargets".equals(group)&&!useSharedNodes){boolean literal=anchor.containsKey("replacementEn")||anchor.containsKey("replacementZh")||anchor.containsKey("prefix")||anchor.containsKey("suffix");for(String key:Arrays.asList("replacementEn","replacementZh","prefix","suffix","value"))anchor.remove(key);if(literal){anchor.put("action","check");anchor.put("needsRebind",true);}}}
            if(!shareableLiteral(node,false))return null;
            restored.add(node);
        }
        Map<String,Object> graph=JsonUtils.readMap(JsonUtils.write(current)),state=state(graph);state.put("variables",restored);state.put("blank",restored.isEmpty());state.put("baselineRestoration",obj("status","partial_requires_review","fieldIds",new ArrayList<>(rootIds),"sourceIdentity","exact_bytes","projectAnswersImported",false));
        if(useSharedNodes&&!restoreRequirements(oldGraph,graph,documents,included,complete))return null;
        List<Object> relations=new ArrayList<>();for(Object raw:list(state(oldGraph).get("relationProposals"))){Map<String,Object> relation=map(raw);if(included.contains(text(relation.get("fromId")))&&included.contains(text(relation.get("toId")))){Map<String,Object> copy=JsonUtils.readMap(JsonUtils.write(relation));copy.put("status","pending");copy.put("reason","Relation from a shared template baseline; review against this original.");relations.add(copy);}}state.put("relationProposals",relations);
        try{validate(graph);return graph;}catch(RuntimeException unsupported){return null;}
    }
    private static boolean restoreRequirements(Map<String,Object> oldGraph,Map<String,Object> current,Map<String,String> documents,Set<String> included,boolean complete){
        Map<String,Object> oldState=state(oldGraph);if(complete&&!Boolean.TRUE.equals(oldState.get("baselineRequirementsValidated")))return false;
        List<Object> requirements=new ArrayList<>();Map<String,Object> oldScopes=map(oldState.get("sourceScopeObservations")),scopes=new LinkedHashMap<>();Map<String,String> integrity=DraftScopeObservationIntegrity.inspect(oldGraph);
        for(Object raw:list(oldState.get("requirements"))){Map<String,Object> original=map(raw);String oldId=text(original.get("sourceId")),mapped=documents.get(oldId);Map<String,Object> target=mapped==null?Collections.emptyMap():source(current,mapped);
            boolean safe=mapped!=null&&!target.isEmpty()&&Objects.equals(original.get("sourceSha256"),target.get("sourceSha256"))&&"bound".equals(target.get("evidenceStatus"));
            for(String key:Arrays.asList("analysisRevision","analysisInputSha256","analysisSha256"))if(!Objects.equals(original.get(key),target.get(key)))safe=false;
            for(Object id:list(original.get("nodeIds")))if(!included.contains(text(id)))safe=false;
            if(!safe){if(complete)return false;continue;}
            Map<String,Object> copy=JsonUtils.readMap(JsonUtils.write(original));copy.put("sourceId",mapped);Map<String,Object> evidence=map(copy.get("sourceQuoteEvidence"));String ref=text(evidence.get("sourceScopeObservationRef"));
            if(!ref.isEmpty()){Map<String,Object> oldScope=map(oldScopes.get(ref));if(!"valid".equals(integrity.get(ref))||!oldId.equals(text(oldScope.get("sourceId")))){if(complete)return false;continue;}
                Map<String,Object> scope=JsonUtils.readMap(JsonUtils.write(oldScope));scope.put("sourceId",mapped);String replacement=DraftScopeObservationIntegrity.reference(scope);if(replacement==null){if(complete)return false;continue;}scopes.put(replacement,scope);evidence.put("sourceScopeObservationRef",replacement);}
            Map<String,Object> anchor=map(evidence.get("anchor"));if(!anchor.isEmpty()){anchor.put("docId",mapped);if(target.get("sourceDocumentId")!=null)anchor.put("sourceDocumentId",target.get("sourceDocumentId"));anchor.remove("nativeBinding");anchor.remove("sourceObservationBinding");}
            requirements.add(copy);
        }
        if(!requirements.isEmpty())state(current).put("requirements",requirements);if(!scopes.isEmpty())state(current).put("sourceScopeObservations",scopes);return true;
    }
    private static Set<String> closure(String root,Map<String,Map<String,Object>> nodes,Map<String,String> documents){
        Set<String> included=new LinkedHashSet<>();Deque<String> queue=new ArrayDeque<>();queue.add(root);
        while(!queue.isEmpty()){
            String id=queue.removeFirst();if(!included.add(id))continue;Map<String,Object> node=nodes.get(id);if(node==null||"excluded".equals(node.get("status"))||!shareableLiteral(node,false))return null;
            for(Object doc:list(node.get("docIds")))if(!documents.containsKey(text(doc)))return null;
            for(String group:Arrays.asList("sourceAnchors","editTargets"))for(Object raw:list(node.get(group)))if(!documents.containsKey(text(map(raw).get("docId"))))return null;
            String parent=text(node.get("parentId"));if(!parent.isEmpty())queue.add(parent);for(Map<String,Object> child:nodes.values())if(id.equals(text(child.get("parentId"))))queue.add(text(child.get("id")));
            for(Object ref:list(node.get("relatedInputIds")))if(!text(ref).isEmpty())queue.add(text(ref));references(node.get("correction"),queue);references(node.get("applicability"),queue);references(node.get("factProjectionReview"),queue);targetReferences(node.get("editTargets"),queue);
        }return included;
    }
    private static boolean literalRole(Map<String,Object> node){return Arrays.asList("fixed","system").contains(text(map(node.get("role")).get("kind")));}
    private static boolean shareableLiteral(Map<String,Object> node,boolean requireBinding){
        String role=text(map(node.get("role")).get("kind"));if(!literalRole(node)||"system".equals(role)&&!node.containsKey("fixedValue"))return true;
        // Text constants need an exact source passage; word matches cannot establish Boolean or structured facts.
        if(!"text".equals(text(map(node.get("type")).get("kind")))||!(node.get("fixedValue") instanceof String)||text(node.get("fixedValue")).trim().isEmpty())return false;
        for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);if(Objects.equals(node.get("fixedValue"),anchor.get("text"))&&(!requireBinding||bound(anchor)))return true;}return false;
    }
    private static boolean hasSelectedAncestor(String id,Map<String,Map<String,Object>> nodes,Set<String> roots){Set<String> seen=new HashSet<>();while(nodes.containsKey(id)&&seen.add(id)){id=text(nodes.get(id).get("parentId"));if(roots.contains(id))return true;}return false;}
    private static Map<String,Object> source(Map<String,Object> graph,String id){Map<String,Object> match=null;for(Object raw:list(graph.get("sources"))){Map<String,Object> item=map(raw);if(id.equals(text(item.get("id")))){if(match!=null)return Collections.emptyMap();match=item;}}return match==null?Collections.emptyMap():match;}
    private static void references(Object value,Deque<String> queue){if(value instanceof Map)for(Map.Entry<String,Object> entry:map(value).entrySet()){if(Arrays.asList("inputId","scopeId","rootId","valueSourceId","listId","itemKey").contains(entry.getKey())&&entry.getValue() instanceof String&&!((String)entry.getValue()).isEmpty())queue.add((String)entry.getValue());else references(entry.getValue(),queue);}else if(value instanceof List)for(Object child:list(value))references(child,queue);}
    private static void targetReferences(Object value,Deque<String> queue){for(Object raw:list(value)){Map<String,Object> target=map(raw);for(String key:Arrays.asList("valueSourceId","listId","itemKey"))if(!text(target.get(key)).isEmpty())queue.add(text(target.get(key)));}}
}
