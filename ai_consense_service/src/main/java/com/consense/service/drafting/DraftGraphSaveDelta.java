package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static com.consense.service.drafting.DraftBusinessRules.list;

/** Transport for existing-node edits; the saved graph and its full validation remain authoritative. */
final class DraftGraphSaveDelta {
    private DraftGraphSaveDelta() { }
    static Map<String,Object> apply(Map<String,Object> baseline,Map<String,Object> delta) {
        if(!delta.keySet().equals(new HashSet<>(Arrays.asList("baseRevisionId","baseGraphSha256","variables","appendedEvents")))||!(delta.get("variables") instanceof List)||!(delta.get("appendedEvents") instanceof List))throw invalid();
        Map<String,Object> result=copy(baseline),state=state(result);List<Object> variables=list(state.get("variables"));Map<String,Integer> positions=positions(variables);
        Set<String> seen=new HashSet<>();List<?> changes=list(delta.get("variables"));if(changes.isEmpty())throw invalid();
        for(Object raw:changes){if(!(raw instanceof Map))throw invalid();Map<String,Object> node=map(raw);Object id=node.get("id");if(!(id instanceof String)||!positions.containsKey(id)||!seen.add((String)id))throw invalid();variables.set(positions.get(id),copy(node));}
        if(!(state.get("events") instanceof List))throw invalid();List<Object> events=list(state.get("events"));for(Object event:list(delta.get("appendedEvents"))){if(!(event instanceof Map))throw invalid();events.add(copy(map(event)));}
        return result;
    }
    static Map<String,Object> receipt(String baseId,String baseSha,Map<String,Object> baseline,Map<String,Object> saved) {
        Map<String,Object> graph=map(saved.get("graph"));if(!without(baseline,"graph").equals(without(graph,"graph")))return saved;
        Map<String,Object> before=state(baseline),after=state(graph);List<Object> previous=list(before.get("variables")),next=list(after.get("variables"));
        if(previous.size()!=next.size()||!(before.get("events") instanceof List)||!(after.get("events") instanceof List))return saved;
        List<Object> replacements=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<previous.size();i++){Object id=map(previous.get(i)).get("id");if(!(id instanceof String)||!ids.add((String)id)||!Objects.equals(id,map(next.get(i)).get("id")))return saved;if(!Objects.equals(previous.get(i),next.get(i)))replacements.add(next.get(i));}
        List<Object> oldEvents=list(before.get("events")),events=list(after.get("events"));if(events.size()<oldEvents.size()||!oldEvents.equals(events.subList(0,oldEvents.size())))return saved;
        Map<String,Object> changes=new LinkedHashMap<>();List<String> removed=new ArrayList<>();
        for(String key:after.keySet())if(!Arrays.asList("variables","events").contains(key)&&(!before.containsKey(key)||!Objects.equals(before.get(key),after.get(key))))changes.put(key,after.get(key));
        for(String key:before.keySet())if(!Arrays.asList("variables","events").contains(key)&&!after.containsKey(key))removed.add(key);
        Map<String,Object> result=new LinkedHashMap<>(saved);result.remove("graph");result.put("graphDelta",obj("baseRevisionId",baseId,"baseGraphSha256",baseSha,"variables",replacements,"appendedEvents",new ArrayList<>(events.subList(oldEvents.size(),events.size())),"stateChanges",changes,"removedStateKeys",removed));return result;
    }
    private static Map<String,Integer> positions(List<Object> nodes){Map<String,Integer> result=new HashMap<>();for(int i=0;i<nodes.size();i++){Object id=map(nodes.get(i)).get("id");if(!(id instanceof String)||((String)id).isEmpty()||result.put((String)id,i)!=null)throw invalid();}return result;}
    private static Map<String,Object> without(Map<String,Object> value,String key){Map<String,Object> result=new LinkedHashMap<>(value);result.remove(key);return result;}
    private static Map<String,Object> copy(Map<String,Object> value){return JsonUtils.readMap(JsonUtils.write(value));}
    private static BizException invalid(){return new BizException(4007,"GRAPH_DELTA_INVALID: Use a full graph save for changes outside existing nodes and appended events.");}
}
