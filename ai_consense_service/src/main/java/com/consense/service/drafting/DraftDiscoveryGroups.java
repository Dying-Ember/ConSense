package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Bounded evidence routing. Shared context never proves that independent facts are identical. */
final class DraftDiscoveryGroups {
    static final int MAX_TASKS=8,MAX_SOURCES=3,MAX_PACKET_CHARS=96000;
    private static final int BASE_PACKET_CHARS=64000;
    private DraftDiscoveryGroups() { }

    static List<Map<String,Object>> plan(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,List<Map<String,Object>> restored) {
        return plan(readings,tasks,restored,currentTemplateIndexes(readings));
    }
    static List<Map<String,Object>> plan(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,List<Map<String,Object>> restored,
                                         Map<String,DraftCurrentTemplateAlignment.Index> indexes) {
        List<Bucket> buckets=new ArrayList<>();
        for(Map<String,Object> task:tasks) {
            String source=text(task.get("sourceId"));JsonNode reading=readings.get(source);Piece piece=piece(reading,task,restored,indexes.get(source));Bucket best=null;int score=0;
            for(Bucket candidate:buckets) {
                if(candidate.pieces.size()>=MAX_TASKS||candidate.sources.size()>=MAX_SOURCES&&!candidate.sources.contains(piece.source))continue;
                int overlap=score(candidate,piece);if(overlap<=score)continue;
                List<Piece> combined=new ArrayList<>(candidate.pieces);combined.add(piece);if(JsonUtils.write(packet(combined,Collections.emptyList())).length()>BASE_PACKET_CHARS)continue;
                best=candidate;score=overlap;
            }
            if(best==null){best=new Bucket();buckets.add(best);}best.add(piece);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(Bucket bucket:buckets) {
            List<Object> ids=new ArrayList<>(),sources=new ArrayList<>();Set<String> members=new HashSet<>();List<String> names=new ArrayList<>();
            for(Piece piece:bucket.pieces){ids.add(piece.task.get("taskId"));for(Object id:list(piece.task.get("memberIds")))members.add(piece.source+"|"+id);String name=piece.reading.path("fileKey").asText();if(!name.isEmpty()&&!names.contains(name))names.add(name);}
            for(String source:bucket.sources)sources.add(identity(readings.get(source)));
            String id="group-"+DraftAdoption.hash(JsonUtils.write(ids)).substring(0,24);int chars=JsonUtils.write(packet(bucket.pieces,Collections.emptyList())).length();
            Set<String> rootKeys=new LinkedHashSet<>(bucket.roots);rootKeys.addAll(bucket.routeRoots);result.add(obj("taskId",id,"phase","GROUP_DISCOVERY","taskIds",ids,"sourceIds",new ArrayList<>(bucket.sources),"sources",sources,"rootKeys",new ArrayList<>(rootKeys),"basisKinds",new ArrayList<>(bucket.bases),"memberCount",members.size(),"fileName",String.join(" / ",names),"status",chars>MAX_PACKET_CHARS?"blocked":"ready","contextChars",chars));
        }return result;
    }

    static Map<String,Object> payload(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,List<Map<String,Object>> restored) {
        return payload(readings,tasks,restored,currentTemplateIndexes(readings));
    }
    static Map<String,Object> payload(Map<String,JsonNode> readings,List<Map<String,Object>> tasks,List<Map<String,Object>> restored,
                                      Map<String,DraftCurrentTemplateAlignment.Index> indexes) {
        List<Piece> pieces=new ArrayList<>();for(Map<String,Object> task:tasks){String source=text(task.get("sourceId"));pieces.add(piece(readings.get(source),task,restored,indexes.get(source)));}
        Set<String> roots=new LinkedHashSet<>();List<Object> queries=new ArrayList<>();for(Piece piece:pieces){roots.addAll(piece.roots);roots.addAll(piece.routeRoots);if("bound".equals(piece.reading.path("evidenceStatus").asText()))queries.add(map(piece.context.get("instruction")).get("text"));}
        List<Object> requested=new ArrayList<>(roots);while(requested.size()>8)requested.remove(requested.size()-1);
        Map<String,Map<String,Object>> definitions=requested.isEmpty()?Collections.emptyMap():DraftCatalogAlignment.definitions();
        // Only explicit definition prerequisites extend a source-bound root; lexical neighbours remain navigation.
        for(int i=0;i<requested.size()&&requested.size()<8;i++)for(Object raw:list(map(definitions.getOrDefault(text(requested.get(i)),Collections.emptyMap()).get("condition")).get("all"))){String key=text(map(raw).get("field"));Map<String,Object> definition=definitions.get(key);if(definition!=null&&!Boolean.TRUE.equals(definition.get("hidden"))&&!requested.contains(key)&&requested.size()<8)requested.add(key);}
        Map<String,Object> search=roots.isEmpty()?DraftBaselineMatcher.searchIndex(queries):obj("fields",Collections.emptyList(),"complete",false);
        search.put("notice",roots.isEmpty()?"Lexical navigation only, not semantic matches. Request baselineContextRequests for a relevant key or baselineSearchRequests for another concept; no candidate full definitions are included automatically.":"Current-source roots and explicit definition prerequisites are supplied in fixedContext. Request additional definitions or search only if needed; lexical neighbours are not included automatically.");
        search.put("referenceKind","fixed_definition_reference");search.put("editableAuthority",false);
        Map<String,Object> result=packet(pieces,restored);result.put("fixedDefinitionCandidates",search);
        result.put("fixedContextComplete",false);
        while(true){Map<String,Object> context=DraftBaselineMatcher.requestedContext(requested);context.put("referenceKind","fixed_definition_reference");context.put("editableAuthority",false);for(Object raw:list(context.get("fields")))map(raw).remove("sourceContext");result.put("fixedContext",context);
            // Initial evidence comes from this group's native observations; heuristic nearby excerpts are not evidence.
            if(JsonUtils.write(result).length()<=MAX_PACKET_CHARS-12000||requested.isEmpty()||requested.size()<=roots.size())break;requested.remove(requested.size()-1);
        }
        return result;
    }

    static Map<String,DraftCurrentTemplateAlignment.Index> currentTemplateIndexes(Map<String,JsonNode> readings) {
        Map<String,DraftCurrentTemplateAlignment.Index> indexes=new LinkedHashMap<>();
        for(Map.Entry<String,JsonNode> reading:readings.entrySet())indexes.put(reading.getKey(),DraftCurrentTemplateAlignment.index(reading.getValue()));return indexes;
    }

    /** The scope-only prompt is safe only when every instruction's referenced business definitions are supplied. */
    static boolean knownDefinitionsComplete(Map<String,Object> payload) {
        Set<String> available=new HashSet<>();for(Object raw:list(payload.get("knownRoots"))){Map<String,Object> root=map(raw);available.add(text(root.get("baselineKey")));}
        List<?> instructions=list(payload.get("instructions"));if(instructions.isEmpty())return false;
        for(Object raw:instructions){Map<String,Object> alignment=map(map(raw).get("currentTemplateAlignment"));List<?> keys=list(alignment.get("fixedRootKeys"));if(keys.isEmpty()||!Arrays.asList("exact_instruction_reference","definition_reference_only").contains(alignment.get("definitionStatus")))return false;for(Object key:keys)if(!available.contains(text(key)))return false;}
        return true;
    }

    private static int score(Bucket bucket,Piece piece) {
        if(intersects(bucket.roots,piece.roots))return 100;
        if(intersects(bucket.routeRoots,piece.routeRoots))return 80;
        // Cross-source routing needs a reviewed root or an explicit current-source definition reference.
        if(!bucket.sources.contains(piece.source))return 0;
        if(intersects(bucket.scopes,piece.scopes))return 40;
        if(intersects(bucket.conditions,piece.conditions))return 20;
        if(intersects(bucket.notes,piece.notes))return 10;
        return 0;
    }
    private static boolean intersects(Set<String> left,Set<String> right){for(String value:right)if(left.contains(value))return true;return false;}

    private static Piece piece(JsonNode reading,Map<String,Object> task,List<Map<String,Object>> restored,DraftCurrentTemplateAlignment.Index index) {
        Piece result=new Piece(reading,task,DraftReviewDiscovery.sourceTaskPayload(reading,task));String prefix=result.source+"|";
        result.currentAlignment=Objects.requireNonNull(index,"Current reading alignment index required").align(task,result.context);
        result.notes.add(prefix+text(task.get("sourceObservationId")));
        for(Object raw:list(result.context.get("scopeCandidates")))result.scopes.add(prefix+text(map(raw).get("id")));
        for(Object raw:list(result.context.get("relatedGuidanceNotes"))){JsonNode row=JsonUtils.mapper().valueToTree(raw);result.notes.add(prefix+row.path("id").asText());}
        for(Object raw:list(result.context.get("sourceConditions"))){JsonNode row=JsonUtils.mapper().valueToTree(raw);result.conditions.add(prefix+row.path("id").asText());}
        for(Map<String,Object> node:restored) {
            Map<String,Object> alignment=map(node.get("catalogAlignment"));String key=text(alignment.get("baselineKey"));
            if(!Boolean.TRUE.equals(alignment.get("confirmed"))||!"matched".equals(alignment.get("status"))||key.isEmpty())continue;
            boolean linked=list(node.get("sourceUnitIds")).contains(task.get("sourceObservationId"))&&list(node.get("docIds")).contains(result.source);
            for(Object raw:list(node.get("sourceAnchors"))){Map<String,Object> anchor=map(raw);if(result.source.equals(text(anchor.get("docId")))&&text(task.get("sourceObservationId")).equals(text(anchor.get("sourceObservationId"))))linked=true;}
            for(Object raw:list(node.get("editTargets"))){Map<String,Object> target=map(raw);if(result.source.equals(text(target.get("docId")))&&result.scopes.contains(prefix+text(target.get("sourceScopeId"))))linked=true;}
            if(linked)result.roots.add(key.contains(".")?key.substring(0,key.indexOf('.')):key);
        }fixedRoutes(result,index);
        // Complete matching instructions supply references only; never move them into reviewed roots.
        Set<String> actionIds=new HashSet<>();for(Object raw:result.actionReferences)actionIds.add(text(map(raw).get("actionId")));
        for(Object raw:list(result.currentAlignment.get("fixedActionReferences")))if(actionIds.add(text(map(raw).get("actionId"))))result.actionReferences.add(raw);
        for(Object key:list(result.currentAlignment.get("fixedRootKeys")))result.routeRoots.add(text(key));
        return result;
    }

    private static boolean verified(JsonNode reading){return "bound".equals(reading.path("evidenceStatus").asText())&&DraftTemplateReadingSources.catalogueSourceVerified(reading.path("fileKey").asText(),reading.path("sourceSha256").asText());}
    private static void fixedRoutes(Piece piece,DraftCurrentTemplateAlignment.Index index) {
        JsonNode reading=piece.reading;if(!verified(reading))return;Set<String> members=new HashSet<>(),positions=new HashSet<>();
        // Related scope members are context, not proof that this instruction belongs to every action inside it.
        instructionMembers(JsonUtils.mapper().valueToTree(map(piece.context.get("instruction")).get("observation")),members);
        for(JsonNode member:reading.path("sourceMembers"))if(members.contains(member.path("id").asText())){JsonNode position=member.path("sourcePosition");if(reading.path("sourceSha256").equals(position.path("sourceSha256"))&&"word/document.xml".equals(position.path("partName").asText())&&!position.path("nodePath").asText().isEmpty())positions.add(position.path("partName").asText()+"|"+position.path("nodePath").asText());}
        String document=reading.path("fileKey").asText();Set<Integer> ordinals=new HashSet<>();for(JsonNode paragraph:reading.path("paragraphs")){JsonNode position=paragraph.path("sourcePosition");int ordinal=paragraph.path("ordinal").asInt(-1);if(ordinal>0&&!DraftBusinessRules.standardParagraph(document,ordinal).isEmpty()&&reading.path("sourceSha256").equals(paragraph.path("sourceSha256"))&&reading.path("sourceSha256").equals(position.path("sourceSha256"))&&positions.contains(position.path("partName").asText()+"|"+position.path("nodePath").asText()))ordinals.add(ordinal);}
        List<ActionRoute> matches=new ArrayList<>();for(ActionRoute route:FixedActions.BY_DOCUMENT.getOrDefault(document,Collections.emptyList()))if(route.contains(ordinals)&&index.actionScopeMatches(text(route.reference.get("actionId")),piece.context))matches.add(route);
        for(ActionRoute route:matches){piece.actionReferences.add(route.reference);boolean parent=false;for(ActionRoute child:matches)if(route!=child&&!route.keys.equals(child.keys)&&route.coversMoreSpecific(child,ordinals)){parent=true;break;}if(!parent)piece.routeRoots.addAll(route.keys);}
    }
    private static void instructionMembers(JsonNode value,Set<String> members){
        if(value==null)return;if(value.isArray()){for(JsonNode child:value)instructionMembers(child,members);return;}if(!value.isObject())return;
        if(value.path("memberId").isTextual())members.add(value.path("memberId").asText());for(JsonNode id:value.path("orderedMemberIds"))if(id.isTextual())members.add(id.asText());
        value.fields().forEachRemaining(entry->{if(!Arrays.asList("sourcePosition","observation","orderedMemberIds").contains(entry.getKey()))instructionMembers(entry.getValue(),members);});
    }
    private static final class FixedActions {
        // Business rules are static for this application version; never rebuild a project plan for every source task.
        static final Map<String,List<ActionRoute>> BY_DOCUMENT=index();
        private static Map<String,List<ActionRoute>> index(){Map<String,List<ActionRoute>> result=new LinkedHashMap<>();Map<String,Map<String,Object>> definitions=DraftCatalogAlignment.definitions();
            for(Object raw:list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions"))){Map<String,Object> action=map(raw);if("SourceCheck".equals(action.get("pendingKind"))||"sct-source-bill-identities".equals(action.get("id")))continue; // This action's location depends on an adopted project format.
                Set<String> keys=new LinkedHashSet<>();for(Object key:list(action.get("inputKeys"))){String root=text(key);if(root.contains("."))root=root.substring(0,root.indexOf('.'));Map<String,Object> field=definitions.get(root);if(field!=null&&!Boolean.TRUE.equals(field.get("hidden")))keys.add(root);}
                List<int[]> ranges=actionRanges(text(action.get("paragraphs")));if(keys.isEmpty()||ranges.isEmpty())continue;ActionRoute route=new ActionRoute(action,keys,ranges);result.computeIfAbsent(text(action.get("document")),key->new ArrayList<>()).add(route);
            }return result;
        }
    }
    private static List<int[]> actionRanges(String specification){
        List<int[]> result=new ArrayList<>();String range="P[0-9]+(?:\\s*[–-]\\s*P?[0-9]+)?";Pattern item=Pattern.compile("P([0-9]+)(?:\\s*[–-]\\s*P?([0-9]+))?");
        for(String segment:specification.split(";")){String current=segment.trim().replaceFirst("(?i)^(?:standard|guidance)\\s+","").replaceFirst("(?i)\\s*\\(including\\b.*\\)$","");
            // Case-edition cues and cross-document references are not positions in the verified current original.
            if(!current.matches(range+"(?:\\s*(?:/|,|and)\\s*"+range+")*"))continue;Matcher matcher=item.matcher(current);while(matcher.find()){int start=Integer.parseInt(matcher.group(1)),end=matcher.group(2)==null?start:Integer.parseInt(matcher.group(2));if(end>=start)result.add(new int[]{start,end});}
        }return result;
    }
    private static final class ActionRoute {
        final Set<String> keys;final List<int[]> ranges;final Map<String,Object> reference;
        ActionRoute(Map<String,Object> action,Set<String> keys,List<int[]> ranges){this.keys=keys;this.ranges=ranges;reference=obj("actionId",action.get("id"),"document",action.get("document"),"historicalClause",action.get("clause"),"inputKeys",new ArrayList<>(keys),"detailEn",map(action.get("detail")).get("en"));}
        boolean contains(Set<Integer> ordinals){for(int[] range:ranges)for(int ordinal:ordinals)if(range[0]<=ordinal&&ordinal<=range[1])return true;return false;}
        boolean coversMoreSpecific(ActionRoute child,Set<Integer> ordinals){for(int[] parent:ranges)for(int[] part:child.ranges)if(parent[0]<=part[0]&&part[1]<=parent[1]&&(parent[0]<part[0]||part[1]<parent[1]))for(int ordinal:ordinals)if(part[0]<=ordinal&&ordinal<=part[1])return true;return false;}
    }

    private static Map<String,Object> packet(List<Piece> pieces,List<Map<String,Object>> restored) {
        List<Object> instructions=new ArrayList<>();Map<String,Object> sources=new LinkedHashMap<>(),evidence=new LinkedHashMap<>();Set<String> roots=new LinkedHashSet<>(),routeRoots=new LinkedHashSet<>();
        for(Piece piece:pieces) {
            sources.put(piece.source,identity(piece.reading));roots.addAll(piece.roots);routeRoots.addAll(piece.routeRoots);Map<String,Object> instruction=map(piece.context.get("instruction"));List<Object> refs=new ArrayList<>();
            for(String collection:Arrays.asList("scopeCandidates","sourceConditions","relatedGuidanceNotes","sourceRelationships"))for(Object raw:list(piece.context.get(collection))) {
                JsonNode row=JsonUtils.mapper().valueToTree(raw);String kind="scopeCandidates".equals(collection)?"scope":"sourceConditions".equals(collection)?"condition":"relatedGuidanceNotes".equals(collection)?"guidance":"relationship",id=row.path("id").asText();if(id.isEmpty())id=DraftAdoption.hash(row.toString()).substring(0,16);
                String ref=piece.source+"|"+kind+"|"+id;Map<String,Object> projected=obj("ref",ref,"sourceId",piece.source,"kind",kind,"id",id);
                for(String field:Arrays.asList("text","status","scopeComplete","captureComplete","sourceInsertionAnchorComplete","sourceSyntaxKnown","sourceSyntaxComplete","semanticStatus","targetScopeStatus","guidanceNoteId","guidanceNoteIds","conditionId","conditionIds","parentConditionId","childConditionId","parentGuidanceNoteId","childGuidanceNoteId","parentGroupId","childGroupId","groupIds","ownerReference","basis","projectValueSupplied","referenceCandidates"))if(row.has(field)&&!DraftDiscoveryEvidence.internalConditionStatus(field,row.get(field)))projected.put(field,JsonUtils.mapper().convertValue(row.get(field),Object.class));
                if("scope".equals(kind)&&!evidence.containsKey(ref)){
                    if(row.path("observation").path("fieldLabel").isTextual())projected.put("fieldLabel",row.path("observation").path("fieldLabel").asText());
                    Map<String,Object> point=DraftReviewDiscovery.insertionPointContext(piece.reading,row.path("observation"));
                    for(String field:Arrays.asList("fieldLabel","insertionPoint","memberId"))if(point.containsKey(field))projected.put(field,point.get(field));
                }
                if(row.has("kind"))projected.put("observationKind",row.path("kind").asText());evidence.putIfAbsent(ref,projected);refs.add(ref);
            }
            Map<String,Object> supplied=obj("sourceTaskId",piece.task.get("taskId"),"sourceId",piece.source,"sourceObservationId",piece.task.get("sourceObservationId"),"kind",instruction.get("kind"),"text",instruction.get("text"),"evidenceRefs",refs,"scopeObligationIds",scopeObligations(piece.context),"unresolvedConditionIds",piece.context.get("unresolvedConditionIds"));if(!piece.actionReferences.isEmpty())supplied.put("fixedActionReferences",piece.actionReferences);
            Map<String,Object> alignment=new LinkedHashMap<>(piece.currentAlignment);alignment.remove("fixedActionReferences");supplied.put("currentTemplateAlignment",alignment);instructions.add(supplied);
        }
        List<Object> definitions=new ArrayList<>();for(Map<String,Object> node:restored){String key=text(map(node.get("catalogAlignment")).get("baselineKey")),root=key.contains(".")?key.substring(0,key.indexOf('.')):key;if(!roots.contains(root))continue;Map<String,Object> field=obj("baselineKey",key,"nameZh",node.get("nameZh"),"nameEn",node.get("nameEn"),"type",node.get("type"),"role",node.get("role"),"options",node.get("options"));for(String property:Arrays.asList("definitionScope","optional","unit"))if(node.containsKey(property))field.put(property,node.get(property));definitions.add(field);}
        return obj("sources",new ArrayList<>(sources.values()),"instructions",instructions,"sharedEvidence",new ArrayList<>(evidence.values()),"reviewedRootKeys",new ArrayList<>(roots),"fixedRootRouteCandidates",new ArrayList<>(routeRoots),"restoredDefinitionReferences",definitions);
    }
    private static Map<String,Object> identity(JsonNode reading){Map<String,Object> result=obj("sourceId",reading.path("id").asText());for(String key:Arrays.asList("sourceSha256","analysisRevision","analysisInputSha256","analysisSha256","fileKey","editionId","format"))if(reading.hasNonNull(key))result.put(key,JsonUtils.mapper().convertValue(reading.get(key),Object.class));return result;}
    private static List<Object> scopeObligations(Map<String,Object> context){List<Object> result=new ArrayList<>();for(Object raw:list(context.get("scopeCandidates"))){JsonNode scope=JsonUtils.mapper().valueToTree(raw);String id=scope.path("id").asText();if(!id.isEmpty()&&(scope.path("scopeComplete").asBoolean(false)||scope.path("sourceInsertionAnchorComplete").asBoolean(false)||scope.path("observation").path("sourceFieldRangeComplete").asBoolean(false)))result.add(id);}return result;}
    private static final class Piece {
        final JsonNode reading;final Map<String,Object> task,context;Map<String,Object> currentAlignment;final String source;final List<Object> actionReferences=new ArrayList<>();final Set<String> roots=new LinkedHashSet<>(),routeRoots=new LinkedHashSet<>(),scopes=new LinkedHashSet<>(),conditions=new LinkedHashSet<>(),notes=new LinkedHashSet<>();
        Piece(JsonNode reading,Map<String,Object> task,Map<String,Object> context){this.reading=reading;this.task=task;this.context=context;this.source=text(task.get("sourceId"));}
    }
    private static final class Bucket {
        final List<Piece> pieces=new ArrayList<>();final Set<String> sources=new LinkedHashSet<>(),roots=new LinkedHashSet<>(),routeRoots=new LinkedHashSet<>(),scopes=new LinkedHashSet<>(),conditions=new LinkedHashSet<>(),notes=new LinkedHashSet<>(),bases=new LinkedHashSet<>();
        void add(Piece piece){if(!pieces.isEmpty()){if(intersects(roots,piece.roots))bases.add("reviewed_fixed_root");if(intersects(routeRoots,piece.routeRoots))bases.add("fixed_source_reference");if(intersects(scopes,piece.scopes))bases.add("shared_current_scope");if(intersects(conditions,piece.conditions))bases.add("explicit_source_condition");if(intersects(notes,piece.notes))bases.add("related_guidance");}else bases.add("source_observation");pieces.add(piece);sources.add(piece.source);roots.addAll(piece.roots);routeRoots.addAll(piece.routeRoots);scopes.addAll(piece.scopes);conditions.addAll(piece.conditions);notes.addAll(piece.notes);}
    }
}
