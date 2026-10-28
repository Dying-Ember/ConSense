package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Post-atomic suggestions only; neither model output nor a matching label adopts a definition or value. */
final class DraftBaselineMatcher {
    private static final String METHOD="post_atomic_semantic";
    private DraftBaselineMatcher() { }

    /** Small searchable index; it proposes identities, never grants a match. */
    static Map<String,Object> catalogIndex() {
        List<Object> fields=new ArrayList<>();
        for(Map.Entry<String,Map<String,Object>> entry:definitions().entrySet())
            fields.add(obj("key",entry.getKey(),"parentKey",parentKey(entry.getKey()),"label",entry.getValue().get("label")));
        return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"fields",fields);
    }

    static Map<String,Object> catalogWindow(List<?> focus,Map<String,Map<String,Object>> nodes) {
        StringBuilder query=new StringBuilder();for(Object id:focus){Map<String,Object> node=nodes.get(text(id));if(node!=null)for(String key:Arrays.asList("nameEn","nameZh","questionEn","questionZh","semanticSourceQuote"))query.append(' ').append(text(node.get(key)));}
        return searchIndex(Collections.singletonList(query.toString()));
    }
    static Map<String,Object> searchIndex(List<?> queries) {
        Set<String> terms=new LinkedHashSet<>();for(Object query:queries)for(String token:text(query).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(token.length()>=4&&!Arrays.asList("that","with","from","this","project","whether","does","what","which","question","included","required").contains(token))terms.add(token.endsWith("s")&&token.length()>5?token.substring(0,token.length()-1):token);
        List<Map<String,Object>> ranked=new ArrayList<>();for(Map.Entry<String,Map<String,Object>> entry:definitions().entrySet()) {
            Map<String,Object> definition=entry.getValue();String haystack=(entry.getKey()+" "+JsonUtils.write(definition.get("label"))+" "+JsonUtils.write(definition.get("options"))).toLowerCase(Locale.ROOT);int score=0;for(String term:terms)if(haystack.contains(term))score++;
            if(score>0)ranked.add(obj("key",entry.getKey(),"parentKey",parentKey(entry.getKey()),"label",definition.get("label"),"score",score));
        }
        ranked.sort((a,b)->Integer.compare((Integer)b.get("score"),(Integer)a.get("score")));List<Object> fields=new ArrayList<>();for(Map<String,Object> row:ranked){row.remove("score");fields.add(row);if(fields.size()==12)break;}
        return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"fields",fields,"complete",false,"notice","Search suggestions are incomplete; the full catalog remains available for human review.");
    }

    /** Return only requested identities and a bounded selection of existing fixed source context. */
    static Map<String,Object> requestedContext(List<?> requested) {
        Map<String,Map<String,Object>> definitions=definitions();List<Object> fields=new ArrayList<>();Set<String> seen=new LinkedHashSet<>();
        for(Object value:requested) {
            String key=text(value);if(!definitions.containsKey(key)||!seen.add(key)||fields.size()>=8)continue;
            Map<String,Object> original=definitions.get(key),field=obj("key",key,"parentKey",parentKey(key));
            for(String property:Arrays.asList("label","description","kind","unit","optional","options","condition"))if(original.containsKey(property))field.put(property,original.get(property));
            List<Object> excerpts=new ArrayList<>();Set<String> tokens=contextTokens(original);
            for(Object raw:list(original.get("affects"))) {
                Map<String,Object> affect=map(raw);String document=text(affect.get("document")),range=text(affect.get("paragraphs"));
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("P([0-9]+)(?:[–-]([0-9]+))?").matcher(range);
                while(matcher.find()&&excerpts.size()<3) {
                    int start=Integer.parseInt(matcher.group(1)),end=matcher.group(2)==null?start:Integer.parseInt(matcher.group(2));
                    // Nearby definitions may follow the affected guidance, but never scan arbitrary source documents.
                    for(int p=Math.max(1,start-12);p<=Math.min(end+12,start+400)&&excerpts.size()<3;p++) {
                        String quote=DraftBusinessRules.standardParagraph(document,p),lower=quote.toLowerCase(Locale.ROOT);
                        if(quote.isEmpty()||quote.length()>2000||tokens.stream().noneMatch(token->lower.contains(token)))continue;
                        Map<String,Object> excerpt=obj("sourceKind","fixed_catalog_embedded_reference","referenceFormat","docx","document",document,"paragraph",p,"text",quote);
                        if("SCC".equals(document))excerpt.put("referenceSourceSha256","9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208");
                        excerpts.add(excerpt);
                    }
                }
            }
            field.put("sourceContext",excerpts);fields.add(field);
        }
        return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"fields",fields);
    }
    private static Set<String> contextTokens(Map<String,Object> definition) {
        StringBuilder value=new StringBuilder(text(map(definition.get("label")).get("en")));
        for(Object option:list(definition.get("options")))value.append(' ').append(text(map(map(option).get("label")).get("en")));
        Set<String> tokens=new LinkedHashSet<>();for(String token:value.toString().toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))if(token.length()>=4&&!Arrays.asList("included","issue","format","required","yes","hardcopy","electronic").contains(token))tokens.add(token);
        return tokens;
    }

    static Map<String,Object> catalog() {
        List<Object> fields=new ArrayList<>();for(Map.Entry<String,Map<String,Object>> entry:definitions().entrySet()){
            Map<String,Object> definition=entry.getValue(),compact=obj("key",entry.getKey(),"parentKey",parentKey(entry.getKey()));
            for(String key:Arrays.asList("label","description","kind","unit","optional","options","condition"))if(definition.containsKey(key))compact.put(key,definition.get(key));fields.add(compact);
        }return obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"fields",fields);
    }
    private static Map<String,Map<String,Object>> definitions() {
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();for(Map.Entry<String,Map<String,Object>> entry:DraftCatalogAlignment.definitions().entrySet())if(!"contractTitle".equals(entry.getKey())&&!entry.getKey().startsWith("contractTitle.")&&!Boolean.TRUE.equals(entry.getValue().get("hidden"))&&(!"id".equals(entry.getValue().get("key"))||Boolean.FALSE.equals(entry.getValue().get("hidden"))))result.put(entry.getKey(),entry.getValue());return result;
    }
    private static boolean eligible(Map<String,Object> node){return DraftCatalogAlignment.businessCandidate(node);}

    static List<Object> focusDefinitions(List<?> focus,Map<String,Map<String,Object>> nodes){List<Object> definitions=new ArrayList<>();for(Object id:focus){Map<String,Object> node=nodes.get(text(id));if(!eligible(node))continue;Map<String,Object> definition=obj("id",id);for(String key:Arrays.asList("parentId","type","role","questionZh","questionEn","optional","options","unit","definitionScope","correction"))if(node.containsKey(key))definition.put(key,node.get(key));definitions.add(definition);}return definitions;}

    /** Deterministic conflicts only; an empty list does not establish semantic equivalence. */
    static List<Object> sameFactIssues(Map<String,Object> left,Map<String,Object> right) {
        List<Object> issues=new ArrayList<>();
        if(!eligible(left)||!eligible(right)){issues.add("same_fact_business_value_required");return issues;}
        String leftType=text(map(left.get("type")).get("kind")),rightType=text(map(right.get("type")).get("kind"));
        if(leftType.isEmpty()||!leftType.equals(rightType))issues.add("same_fact_type_mismatch");
        if(!Objects.equals(map(left.get("role")).get("kind"),map(right.get("role")).get("kind")))issues.add("same_fact_role_mismatch");
        Object leftUnit=map(left.get("type")).get("unit"),rightUnit=map(right.get("type")).get("unit");
        if(!text(leftUnit==null?left.get("unit"):leftUnit).trim().equals(text(rightUnit==null?right.get("unit"):rightUnit).trim()))issues.add("same_fact_unit_mismatch");
        if(Arrays.asList("choice","multiselect").contains(leftType)&&!optionValues(left).equals(optionValues(right)))issues.add("same_fact_option_domain_mismatch");
        if(Boolean.TRUE.equals(left.get("optional"))!=Boolean.TRUE.equals(right.get("optional")))issues.add("same_fact_optional_mismatch");
        if(!text(left.get("parentId")).equals(text(right.get("parentId"))))issues.add("same_fact_parent_review_required");
        Map<String,Object> a=map(left.get("catalogAlignment")),b=map(right.get("catalogAlignment"));
        String leftScope=text(left.get("definitionScope")).trim(),rightScope=text(right.get("definitionScope")).trim();
        if(leftScope.isEmpty())leftScope=text(a.get("scope")).trim();if(rightScope.isEmpty())rightScope=text(b.get("scope")).trim();
        if(!leftScope.equals(rightScope))issues.add("same_fact_scope_review_required");
        if(Boolean.TRUE.equals(a.get("confirmed"))&&Boolean.TRUE.equals(b.get("confirmed"))&&"matched".equals(a.get("status"))&&"matched".equals(b.get("status"))&&(!Objects.equals(a.get("baselineKey"),b.get("baselineKey"))||!Objects.equals(a.get("baselineVersion"),b.get("baselineVersion"))))issues.add("same_fact_confirmed_definition_conflict");
        return issues;
    }

    static Map<String,Object> classifications(JsonNode response,List<?> focus,Map<String,Map<String,Object>> nodes) {
        Map<String,Object> result=new LinkedHashMap<>();Set<String> duplicates=new HashSet<>();
        for(JsonNode row:response.path("nodeClassifications")) {
            String id=row.path("nodeId").asText(),kind=row.path("kind").asText(),quote=row.path("sourceQuote").asText(),reason=row.path("reason").asText();
            if(!focus.contains(id)||!eligible(nodes.get(id)))continue;
            if(result.containsKey(id)){duplicates.add(id);continue;}
            if(!Arrays.asList("business_value","editorial","unresolved").contains(kind)||quote.trim().isEmpty()||reason.trim().isEmpty()||!text(nodes.get(id).get("semanticSourceQuote")).contains(quote))continue;
            result.put(id,obj("kind",kind,"sourceQuote",quote,"reason",reason,"status","pending","confirmed",false));
        }
        for(String id:duplicates)result.remove(id);
        return result;
    }
    static void applyClassifications(List<Map<String,Object>> nodes,List<Map<String,Object>> receipts) {
        Map<String,Object> suggestions=new LinkedHashMap<>();for(Map<String,Object> receipt:receipts)suggestions.putAll(map(receipt.get("nodeClassifications")));
        for(Map<String,Object> node:nodes)if(suggestions.containsKey(text(node.get("id"))))node.put("editorialSuggestion",JsonUtils.readMap(JsonUtils.write(suggestions.get(text(node.get("id"))))));
    }
    static void assess(Map<String,Object> matching,JsonNode response,List<?> requested) {
        Map<String,Object> alignments=map(matching.get("alignments"));Set<String> seen=new HashSet<>();
        for(JsonNode row:response.path("catalogAssessments")) {
            String id=row.path("nodeId").asText(),key=row.path("baselineKey").asText(),status=row.path("semanticStatus").asText(),reason=row.path("reason").asText();
            String pair=id+"\n"+key;if(!requested.contains(key)||!seen.add(pair)||reason.trim().isEmpty()||!Arrays.asList("consistent","conflict","uncertain").contains(status))continue;
            for(Object raw:list(map(alignments.get(id)).get("proposedMatches"))) {Map<String,Object> candidate=map(raw);if(key.equals(candidate.get("baselineKey"))) {
                candidate.put("semanticStatus",status);candidate.put("semanticReason",reason);
                JsonNode branch=row.path("branchValue"),polarity=row.path("branchPolarity");Map<String,Object> definition=definitions().get(key);
                if(definition!=null&&branch.isTextual()&&polarity.isBoolean()&&optionValues(definition).contains(branch.asText()))candidate.put("factProjection",obj("branchValue",branch.asText(),"branchPolarity",polarity.asBoolean(),"status","pending","confirmed",false));
            }}
        }
    }
    static void addRequestedMatches(Map<String,Object> matching,JsonNode response,List<?> focus,Map<String,Map<String,Object>> nodes,List<?> allowed) {
        if(!response.path("catalogMatches").isArray())return;
        Map<String,Object> extra=read(response,focus,nodes),current=map(matching.get("alignments"));
        for(Map.Entry<String,Object> entry:map(extra.get("alignments")).entrySet()) {
            Map<String,Object> target=map(current.get(entry.getKey()));if(target.isEmpty())continue;
            List<Object> candidates=new ArrayList<>(list(target.get("proposedMatches")));Set<String> keys=new HashSet<>();for(Object item:candidates)keys.add(text(map(item).get("baselineKey")));
            for(Object item:list(map(entry.getValue()).get("proposedMatches"))){String key=text(map(item).get("baselineKey"));if(allowed.contains(key)&&keys.add(key))candidates.add(item);}
            if(!candidates.isEmpty()){target.put("proposedMatches",candidates);target.put("proposalStatus","matched");}
        }
    }

    static Map<String,Object> read(JsonNode response,List<?> focus,Map<String,Map<String,Object>> nodes) {return read(response,focus,nodes,null);}
    static Map<String,Object> read(JsonNode response,List<?> focus,Map<String,Map<String,Object>> nodes,Collection<?> allowed) {
        Map<String,Object> alignments=new LinkedHashMap<>();List<Object> issues=new ArrayList<>();for(Object id:focus)if(eligible(nodes.get(text(id))))alignments.put(text(id),unavailable());
        if(alignments.isEmpty())return obj("alignments",alignments,"issues",issues);
        JsonNode rows=response.path("catalogMatches");if(!rows.isArray()){issues.add(obj("reason","catalog_matches_array_required"));return obj("alignments",alignments,"issues",issues);}
        Map<String,Map<String,Object>> baseline=definitions();Set<String> seen=new HashSet<>();
        for(JsonNode row:rows){String id=row.path("nodeId").asText();if(!alignments.containsKey(id)){issues.add(obj("nodeId",id,"reason","catalog_match_node_outside_focus"));continue;}
            if(!seen.add(id)){alignments.put(id,unavailable());issues.add(obj("nodeId",id,"reason","catalog_match_node_duplicated"));continue;}
            List<Object> candidates=new ArrayList<>();Set<String> keys=new HashSet<>();boolean valid=row.path("candidates").isArray();String source=text(nodes.get(id).get("semanticSourceQuote"));
            for(JsonNode candidate:row.path("candidates")){String key=candidate.path("baselineKey").asText(),quote=candidate.path("sourceQuote").asText(),reason=candidate.path("reason").asText();JsonNode confidence=candidate.path("confidence");
                if(!baseline.containsKey(key)||allowed!=null&&!allowed.contains(key)||!keys.add(key)||quote.trim().isEmpty()||!source.contains(quote)||reason.trim().isEmpty()||!confidence.isNumber()||!Double.isFinite(confidence.asDouble())||confidence.asDouble()<0||confidence.asDouble()>1||candidate.hasNonNull("baselineVersion")&&!DraftBusinessRules.RULE_VERSION.equals(candidate.path("baselineVersion").asText())){valid=false;continue;}
                candidates.add(obj("baselineKey",key,"baselineVersion",DraftBusinessRules.RULE_VERSION,"reason",reason,"sourceQuote",quote,"confidence",confidence.asDouble(),"semanticStatus","unverified","compatible",false,"issues",new ArrayList<>()));
            }
            if(!valid){alignments.put(id,unavailable());issues.add(obj("nodeId",id,"reason","catalog_match_candidate_invalid"));continue;}
            Map<String,Object> alignment=unavailable();alignment.put("proposalStatus",candidates.isEmpty()?"unmatched":"matched");alignment.put("proposalReason",candidates.isEmpty()?"No equivalent fixed variable was proposed; review this as a separate variable.":"Review the proposed fixed-variable meaning against the atomic source evidence.");alignment.put("proposedMatches",candidates);alignments.put(id,alignment);
        }
        for(String id:alignments.keySet())if(!seen.contains(id))issues.add(obj("nodeId",id,"reason","catalog_match_node_unanswered"));
        return obj("alignments",alignments,"issues",issues);
    }

    static void apply(List<Map<String,Object>> nodes,List<Map<String,Object>> receipts) {
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(Map<String,Object> node:nodes)byId.put(text(node.get("id")),node);Map<String,Object> proposed=new LinkedHashMap<>();
        for(Map<String,Object> receipt:receipts)proposed.putAll(map(receipt.get("catalogAlignments")));
        Map<String,Map<String,Object>> baseline=definitions();for(Map<String,Object> node:nodes)if(eligible(node)&&!Boolean.TRUE.equals(map(node.get("catalogAlignment")).get("confirmed"))){
            String id=text(node.get("id"));Map<String,Object> alignment=proposed.containsKey(id)?JsonUtils.readMap(JsonUtils.write(proposed.get(id))):unavailable();List<Object> candidates=new ArrayList<>();
            for(Object raw:list(alignment.get("proposedMatches"))){Map<String,Object> candidate=JsonUtils.readMap(JsonUtils.write(raw));String key=text(candidate.get("baselineKey"));List<Object> problems=new ArrayList<>();
                if(!DraftBusinessRules.RULE_VERSION.equals(candidate.get("baselineVersion"))||!baseline.containsKey(key))problems.add("baseline_version_changed");else problems.addAll(compatibility(node,key,baseline,byId,proposed));candidate.put("compatible",problems.isEmpty());if("conflict".equals(candidate.get("semanticStatus")))problems.add("semantic_conflict_review_required");candidate.put("issues",problems);candidates.add(candidate);
            }
            alignment.put("proposedMatches",candidates);alignment.put("status","unreviewed");alignment.put("confirmed",false);alignment.remove("baselineKey");alignment.remove("baselineVersion");
            if(!"unavailable".equals(alignment.get("proposalStatus")))alignment.put("proposalStatus",candidates.isEmpty()?"unmatched":candidates.size()==1&&Boolean.TRUE.equals(map(candidates.get(0)).get("compatible"))&&!"conflict".equals(map(candidates.get(0)).get("semanticStatus"))?"matched":"ambiguous");node.put("catalogAlignment",alignment);
        }
    }
    static void refreshSuggestions(Map<String,Object> existing,Map<String,Object> proposed){
        Map<String,Object> classification=map(proposed.get("editorialSuggestion"));if(!classification.isEmpty())existing.put("editorialSuggestion",JsonUtils.readMap(JsonUtils.write(classification)));
        Map<String,Object> current=map(existing.get("catalogAlignment")),suggestion=map(proposed.get("catalogAlignment"));if(!METHOD.equals(suggestion.get("proposalMethod"))||Boolean.TRUE.equals(current.get("confirmed"))||!current.isEmpty()&&!"unreviewed".equals(current.get("status")))return;Map<String,Object> updated=new LinkedHashMap<>(current);updated.putIfAbsent("status","unreviewed");updated.putIfAbsent("confirmed",false);for(String key:Arrays.asList("proposalMethod","proposalStatus","proposalReason","proposedMatches"))if(suggestion.containsKey(key))updated.put(key,suggestion.get(key));existing.put("catalogAlignment",updated);
    }
    private static List<Object> compatibility(Map<String,Object> node,String key,Map<String,Map<String,Object>> baseline,Map<String,Map<String,Object>> nodes,Map<String,Object> proposals) {
        Map<String,Object> definition=baseline.get(key);List<Object> issues=new ArrayList<>();if(!kind(text(definition.get("kind"))).equals(text(map(node.get("type")).get("kind"))))issues.add("type_mismatch");
        Object unit=map(node.get("type")).get("unit");if(unit==null)unit=node.get("unit");if(!text(unit).trim().equals(text(definition.get("unit")).trim()))issues.add("unit_mismatch");
        if(Boolean.TRUE.equals(node.get("optional"))!=Boolean.TRUE.equals(definition.get("optional")))issues.add("optional_mismatch");
        if(Arrays.asList("choice","multiselect").contains(kind(text(definition.get("kind"))))&&!optionValues(node).equals(optionValues(definition)))issues.add("option_domain_mismatch");
        String expected=parentKey(key),parent=text(node.get("parentId"));if(!expected.isEmpty()){
            String mapped=proposedKey(nodes.get(parent),proposals);if(parent.isEmpty()||mapped.isEmpty())issues.add("parent_mapping_required");else if(!expected.equals(mapped))issues.add("parent_mismatch");
        }else if(!parent.isEmpty()&&text(node.get("definitionScope")).trim().isEmpty()&&text(map(node.get("catalogAlignment")).get("scope")).trim().isEmpty())issues.add("parent_mapping_required");
        for(Object raw:list(definition.get("columnFields"))){Map<String,Object> child=map(raw);if(Boolean.TRUE.equals(child.get("optional"))||Boolean.TRUE.equals(child.get("hidden")))continue;String childKey=key+"."+child.get("key");boolean found=false;
            for(Map<String,Object> actual:nodes.values())if(eligible(actual)&&Objects.equals(node.get("id"),actual.get("parentId"))&&childKey.equals(proposedKey(actual,proposals)))found=true;if(!found&&!issues.contains("child_mapping_required"))issues.add("child_mapping_required");
        }return issues;
    }
    private static String proposedKey(Map<String,Object> node,Map<String,Object> proposals){if(node==null)return "";Map<String,Object> current=map(node.get("catalogAlignment"));if(Boolean.TRUE.equals(current.get("confirmed"))&&"matched".equals(current.get("status")))return text(current.get("baselineKey"));Map<String,Object> proposal=map(proposals.get(text(node.get("id"))));List<?> candidates=list(proposal.get("proposedMatches"));return !"unavailable".equals(proposal.get("proposalStatus"))&&candidates.size()==1?text(map(candidates.get(0)).get("baselineKey")):"";}
    private static Set<String> optionValues(Map<String,Object> node){Set<String> values=new LinkedHashSet<>();for(Object raw:list(node.get("options")))values.add(text(map(raw).get("value")));return values;}
    private static String kind(String value){if(Arrays.asList("contract","object").contains(value))return "record";if(Arrays.asList("bills","table","records").contains(value))return "list";return Arrays.asList("textarea","longtext").contains(value)?"text":value;}
    private static String parentKey(String key){int dot=key.lastIndexOf('.');return dot<0?"":key.substring(0,dot);}
    private static Map<String,Object> unavailable(){return obj("status","unreviewed","confirmed",false,"proposalMethod",METHOD,"proposalStatus","unavailable","proposalReason","No validated semantic catalog suggestion is available; review the fixed catalog manually.","proposedMatches",new ArrayList<>());}
}
