package com.consense.service.drafting;

import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Historical references for review; no current-template target or edit is inferred. */
final class DraftFixedRuleSuggestions {
    private DraftFixedRuleSuggestions() { }

    static List<Object> forGraph(Map<String,Object> graph) {
        Map<String,List<String>> mapped=new LinkedHashMap<>();
        for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.confirmedDefinitions(graph).entrySet())
            mapped.computeIfAbsent(text(entry.getValue().get("baselineKey")),key->new ArrayList<>()).add(entry.getKey());
        if(mapped.isEmpty())return Collections.emptyList();
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();for(Map<String,Object> node:DraftReviewGraph.nodes(graph))nodes.put(text(node.get("id")),node);
        List<Object> result=new ArrayList<>();
        for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.definitions().entrySet()) {
            Map<String,Object> condition=map(entry.getValue().get("condition"));List<?> clauses=list(condition.get("all"));
            if(clauses.isEmpty())continue;
            Set<String> keys=new LinkedHashSet<>();for(Object raw:clauses){String key=text(map(raw).get("field"));if(!key.isEmpty())keys.add(key);}
            if(!mapped.containsKey(entry.getKey())&&Collections.disjoint(keys,mapped.keySet()))continue;
            List<Object> inputs=inputs(keys,mapped);
            Map<String,Object> suggestion=obj("kind","field_condition","ruleVersion",DraftBusinessRules.RULE_VERSION,"actionId","field:"+entry.getKey(),"document","","historicalClause","",
                "detail",obj("zhHans","固定目录中的字段适用条件；请核对当前模板。","en","A fixed catalog field condition; review against the current template."),
                "inputs",inputs,"targetBaselineKey",entry.getKey(),"targetNodeIds",mapped.getOrDefault(entry.getKey(),Collections.emptyList()),"conditions",clauses,
                "targetBinding","unbound","status","pending","executable",false,"coverage","catalog_condition");
            List<String> targets=mapped.getOrDefault(entry.getKey(),Collections.emptyList());List<Object> fingerprintTerms=new ArrayList<>();boolean unique=targets.size()==1&&text(map(nodes.get(targets.get(0))).get("parentId")).isEmpty();
            for(Object raw:inputs){List<?> candidates=list(map(raw).get("nodeIds"));if(candidates.size()!=1||!text(map(nodes.get(text(candidates.get(0)))).get("parentId")).isEmpty()){unique=false;break;}fingerprintTerms.add(obj("inputId",candidates.get(0)));}
            if(unique){String fingerprint=DraftReviewGraph.applicabilityFingerprint(graph,nodes.get(targets.get(0)),fingerprintTerms,nodes);if(fingerprint!=null&&!fingerprint.isEmpty())suggestion.put("bindingFingerprint",fingerprint);}
            suggestion.put("reviewCoverage",conditionCoverage(graph,suggestion,nodes));
            result.add(suggestion);
        }
        if(mapped.containsKey("electronicTendering")||mapped.containsKey("twoEnvelopeTendering")) {
            // shortcut: these reviewed examples cover only unconditional tender-format registrations; extend when fixed rules expose a complete graph.
            Set<String> examples=new LinkedHashSet<>(Arrays.asList("NTT-2-L10PRO","NTT-2-PAPER","NTT-3C-L10PRO","NTT-3C-PAPER","sct-issue-alternative","sct-pricing-return-alternative","sct-envelope-price-documents","sct-original-hardcopy-wording"));
            for(Object raw:list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions"))) {
                Map<String,Object> action=map(raw);if(!examples.contains(text(action.get("id"))))continue;
                Set<String> keys=new LinkedHashSet<>();for(Object key:list(action.get("inputKeys")))keys.add(text(key));
                if(Collections.disjoint(keys,mapped.keySet()))continue;
                result.add(obj("kind","action_reference","ruleVersion",DraftBusinessRules.RULE_VERSION,"actionId",action.get("id"),"document",action.get("document"),"historicalClause",action.get("clause"),"detail",action.get("detail"),
                    "inputs",inputs(keys,mapped),"targetBinding","unbound","status","pending","executable",false,"coverage","partial_examples"));
            }
        }
        return result;
    }
    private static List<Object> inputs(Collection<String> keys,Map<String,List<String>> mapped) {
        List<Object> result=new ArrayList<>();for(String key:keys)result.add(obj("baselineKey",key,"nodeIds",mapped.getOrDefault(key,Collections.emptyList())));return result;
    }
    /** Snapshot-only quality projection. It neither adopts this reference nor repairs an existing rule. */
    private static Map<String,Object> conditionCoverage(Map<String,Object> graph,Map<String,Object> suggestion,Map<String,Map<String,Object>> nodes) {
        List<?> targets=list(suggestion.get("targetNodeIds"));
        if(targets.size()!=1)return coverage("ambiguous",targets.isEmpty()?"target_mapping_missing":"target_mapping_ambiguous");
        Map<String,Object> target=nodes.get(text(targets.get(0)));if(target==null)return coverage("stale","target_definition_unavailable");
        if(!text(target.get("parentId")).isEmpty())return coverage("ambiguous","scoped_mapping_requires_review");
        Map<String,String> mapped=new LinkedHashMap<>();for(Object raw:list(suggestion.get("inputs"))){Map<String,Object> input=map(raw);List<?> ids=list(input.get("nodeIds"));if(ids.size()!=1)return coverage("ambiguous",ids.isEmpty()?"prerequisite_mapping_missing":"prerequisite_mapping_ambiguous");Map<String,Object> node=nodes.get(text(ids.get(0)));if(node==null)return coverage("stale","prerequisite_definition_unavailable");if(!text(node.get("parentId")).isEmpty())return coverage("ambiguous","scoped_mapping_requires_review");mapped.put(text(input.get("baselineKey")),text(ids.get(0)));}
        if(text(suggestion.get("bindingFingerprint")).isEmpty())return coverage("stale","definition_binding_unavailable");
        Map<String,Object> actual=map(target.get("applicability"));
        if(actual.isEmpty()){Map<String,Object> custom=map(target.get("correction"));return !list(custom.get("branches")).isEmpty()||!list(custom.get("clauses")).isEmpty()?coverage("ambiguous","custom_rule_requires_review"):coverage("missing","fixed_condition_not_restored");}
        if(!"fixed_catalog".equals(actual.get("kind")))return coverage("ambiguous","custom_applicability_requires_review");
        if(!Objects.equals(suggestion.get("ruleVersion"),actual.get("ruleVersion")))return coverage("stale","fixed_rule_version_changed");
        if(!Objects.equals(suggestion.get("targetBaselineKey"),actual.get("targetBaselineKey")))return coverage("stale","fixed_target_changed");
        if(!"all".equals(actual.get("mode")))return coverage("missing","logical_mode_differs");
        Set<String> known=new HashSet<>(Arrays.asList("kind","ruleVersion","targetBaselineKey","bindingFingerprint","mode","confirmed","reason","clauses"));for(String key:actual.keySet())if(!known.contains(key))return coverage("ambiguous","custom_applicability_structure");
        if(!(actual.get("clauses") instanceof List)||!sameClauses(list(suggestion.get("conditions")),list(actual.get("clauses")),mapped))return coverage("missing","fixed_clauses_differ");
        if(!Boolean.TRUE.equals(actual.get("confirmed"))||text(actual.get("reason")).trim().isEmpty())return coverage("ambiguous","condition_review_pending");
        if(!DraftReviewGraph.applicabilityBindingValid(graph,target,nodes))return coverage("stale","condition_binding_invalid");
        return coverage("covered","complete_current_fixed_condition");
    }
    private static boolean sameClauses(List<?> expected,List<?> actual,Map<String,String> mapped) {
        if(expected.size()!=actual.size())return false;boolean[] used=new boolean[actual.size()];Set<String> fields=new HashSet<>(Arrays.asList("inputId","baselineKey","operator","value"));
        for(Object raw:expected){Map<String,Object> spec=map(raw);boolean found=false;for(int i=0;i<actual.size();i++)if(!used[i]){if(!(actual.get(i) instanceof Map))continue;Map<String,Object> term=map(actual.get(i));if(!fields.containsAll(term.keySet()))continue;String key=text(spec.get("field"));if(Objects.equals(mapped.get(key),term.get("inputId"))&&key.equals(term.get("baselineKey"))&&Objects.equals(spec.get("operator"),term.get("operator"))&&Objects.equals(spec.get("value"),term.get("value"))){used[i]=true;found=true;break;}}if(!found)return false;}
        return true;
    }
    private static Map<String,Object> coverage(String status,String reason){return obj("status",status,"reason",reason);}
}
