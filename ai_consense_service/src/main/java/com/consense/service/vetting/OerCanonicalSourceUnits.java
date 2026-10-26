package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Pure JSON counterpart of Python SourceUnits.resolve; derived members never become new origins. */
final class OerCanonicalSourceUnits {
    static final List<String> FIELDS=Arrays.asList("documentId","sourceHash","role","metadataVersion",
            "segmentationVersion","nativeTableMetadataVersion","sourceQualityMetadataVersion","sourceQualityHash");
    private OerCanonicalSourceUnits(){}

    static void validate(JsonNode result,Map<String,JsonNode> parents,List<String> ranked) {
        JsonNode episode=result.path("sourceUnits");
        String policy=episode.path("policy").asText();
        boolean typed=VettingRetrievalSourceUnits.TYPED_PARENT_POLICY.equals(policy);
        boolean attached=VettingRetrievalSourceUnits.attachmentPolicy(policy);
        boolean sharedNotes=VettingRetrievalSourceUnits.SHARED_NOTE_POLICY.equals(policy);
        require(VettingRetrievalSourceUnits.supportsPolicy(policy)
                && "canonical_observed_structure_only".equals(episode.path("scope").asText())
                && "canonical_parent_id_lexical".equals(episode.path("memberOrder").asText())
                && "unknown".equals(episode.path("qualifiersComplete").asText())
                && ids(episode.path("originalHitIds")).equals(ranked) && episode.path("units").isArray()
                && episode.path("units").size()==ranked.size() && falseBoolean(episode.path("derivedMembersCanBecomeOrigins"))
                && falseBoolean(episode.path("semanticScopeVerified")), "source-unit episode differs");
        Set<String> used=new HashSet<>();int ordinal=0;
        for(int i=0;i<ranked.size();i++) {
            JsonNode unit=episode.path("units").get(i);ObjectNode expected=attached?resolveAttached(parents,parents.get(ranked.get(i)),sharedNotes):typed?resolve(parents,parents.get(ranked.get(i))):resolveObserved(parents,parents.get(ranked.get(i)));
            for(Iterator<Map.Entry<String,JsonNode>> fields=expected.fields();fields.hasNext();) {
                Map.Entry<String,JsonNode> field=fields.next();
                require(field.getValue().equals(unit.path(field.getKey())),"source-unit field differs: "+field.getKey());
            }
            require(unit.has("hierarchyContext")==expected.has("hierarchyContext"),"source-unit hierarchy context missing or not advertised");
            require(unit.has("attachmentContext")==expected.has("attachmentContext"),"source-unit attachment context missing or not advertised");
            Set<String> allowed=new HashSet<>();expected.fieldNames().forEachRemaining(allowed::add);allowed.add("originalRerankOrdinal");allowed.add("seedScore");allowed.add("selectionOrdinal");
            unit.fieldNames().forEachRemaining(field->require(allowed.contains(field),"unknown source-unit field: "+field));
            int selectedOrdinal=VettingRetrievalSourceUnits.selectionOrdinal(episode,unit,result.path("hits").get(i).path("payload"));
            require(used.add(expected.path("unitId").asText()) && selectedOrdinal>ordinal
                    && unit.path("seedScore").equals(result.path("hits").get(i).path("score")),"source-unit ranked seed differs");
            ordinal=selectedOrdinal;
        }
    }

    static ObjectNode resolve(Map<String,JsonNode> parents,JsonNode seed) {
        ObjectNode observed=resolveObserved(parents,seed),context=new OerCanonicalHierarchyContext(parents).resolve(seed,observed);
        if(context!=null)observed.set("hierarchyContext",context);return observed;
    }

    static ObjectNode resolveAttached(Map<String,JsonNode> parents,JsonNode seed) {return resolveAttached(parents,seed,false);}

    static ObjectNode resolveAttached(Map<String,JsonNode> parents,JsonNode seed,boolean sharedNotes) {
        ObjectNode observed=resolve(parents,seed),attachment=new OerCanonicalAttachmentContext(parents,sharedNotes).resolve(seed,observed);
        if(attachment!=null)observed.set("attachmentContext",attachment);return observed;
    }

    static ObjectNode resolveObserved(Map<String,JsonNode> parents,JsonNode seed) {
        ObjectNode row=JsonUtils.mapper().createObjectNode();
        row.putArray("originIds").add(seed.path("id").asText());row.put("kind","unknown");row.put("status","unknown");row.putNull("reason");
        row.putArray("requiredMemberIds");row.putArray("members");row.putArray("observedRanges");row.set("sourceIdentity",identity(seed));
        row.set("clauseId",nullable(seed,"clauseId"));row.set("headingLocation",nullable(seed,"clauseHeadingLocation"));row.putArray("blockIds");
        row.put("derivedMembersCanBecomeOrigins",false);row.putNull("memberScores");row.put("qualifiersComplete","unknown");row.put("semanticScopeVerified",false);
        try {
            source(parents,seed);mapped(seed);JsonNode key=scopeIdentity(seed);
            SortedMap<String,JsonNode> members=new TreeMap<>();SortedSet<String> blocks=new TreeSet<>();
            boolean clause=seed.path("clauseId").isTextual()&&!seed.path("clauseId").asText().isEmpty();
            if(clause) {
                for(JsonNode parent:parents.values())if(scopeIdentity(parent).equals(key)&&parent.path("clauseId").equals(seed.path("clauseId")))members.put(parent.path("id").asText(),parent);
                Set<JsonNode> headings=new HashSet<>();for(JsonNode member:members.values())headings.add(nullable(member,"clauseHeadingLocation"));
                String heading=seed.path("clauseHeadingLocation").asText();
                observed(headings.size()==1&&!heading.isEmpty(),"clause_heading_not_unique");
                boolean located=false;for(JsonNode member:members.values())for(JsonNode part:member.path("parts")) {
                    String anchor=part.path("anchor").asText().split(" @",2)[0];
                    if(anchor.equals(heading)||anchor.endsWith(" · "+heading))located=true;
                }
                observed(located,"heading_not_located_in_observed_parts");row.put("kind","exact_clause_heading");
                for(JsonNode member:members.values())for(JsonNode part:member.path("parts"))blocks.add(part.path("blockId").asText());
                for(JsonNode parent:parents.values())if(scopeIdentity(parent).equals(key)&&touches(parent,blocks))
                    observed(members.containsKey(parent.path("id").asText()),"source_block_crosses_clause_unit_boundary");
            } else {
                row.put("kind","observed_source_blocks");for(JsonNode part:seed.path("parts"))blocks.add(part.path("blockId").asText());
                for(JsonNode parent:parents.values())if(scopeIdentity(parent).equals(key)&&touches(parent,blocks))members.put(parent.path("id").asText(),parent);
                // Additional blocks of an included member are retained as payload, not followed as origins.
            }
            for(JsonNode member:members.values()) {
                source(parents,member);observed(scopeIdentity(member).equals(key)&&member.path("sourceQuality").equals(seed.path("sourceQuality")),"source_quality_or_scope_differs");mapped(member);
            }
            ArrayNode ranges=JsonUtils.mapper().createArrayNode();for(String block:blocks)ranges.add(blockRange(parents,key,block));
            row.set("observedRanges",ranges);blocks.forEach(row.putArray("blockIds")::add);
            ArrayNode memberIds=row.putArray("requiredMemberIds"),payloads=row.putArray("members");
            for(Map.Entry<String,JsonNode> entry:members.entrySet()){memberIds.add(entry.getKey());payloads.add(entry.getValue().deepCopy());}
            row.put("status","complete_observed_unit");
        } catch(ObservationUnknown unknown) {
            row.put("reason",unknown.getMessage());row.putArray("requiredMemberIds");row.putArray("members");row.putArray("observedRanges");
        }
        ObjectNode address=JsonUtils.mapper().createObjectNode();
        for(String field:Arrays.asList("kind","sourceIdentity","clauseId","headingLocation","blockIds","requiredMemberIds"))address.set(field,row.path(field));
        if(!"complete_observed_unit".equals(row.path("status").asText()))address.put("fallbackSeedId",seed.path("id").asText());
        row.put("unitId",VettingCorpus.hash(JsonUtils.write(sorted(address))));return row;
    }

    static void source(Map<String,JsonNode> parents,JsonNode parent) {
        JsonNode key=scopeIdentity(parent);boolean valid=true;for(String field:FIELDS)valid&=parent.path(field).isTextual()&&!parent.path(field).asText().trim().isEmpty();
        observed(valid&&sha(parent.path("sourceHash").asText())&&sha(parent.path("sourceQualityHash").asText())
                &&Arrays.asList("tender","standard","project_fact","package_manifest").contains(parent.path("role").asText())
                &&parent.path("sourceQuality").isObject(),"unknown_source_identity_quality_or_version");
        for(JsonNode other:parents.values())if(other.path("documentId").equals(parent.path("documentId")))
            observed(scopeIdentity(other).equals(key),"ambiguous_document_source_identity");
    }
    static void mapped(JsonNode parent) {
        observed(parent.path("parts").isArray()&&parent.path("parts").size()>0,"MISSING_SOURCE_PARTS");
        List<String> text=new ArrayList<>();for(JsonNode part:parent.path("parts"))text.add(part.path("text").asText());
        String joined=String.join("\n",text);observed(parent.path("content").isTextual()
                &&(joined.equals(parent.path("content").asText())||joined.trim().equals(parent.path("content").asText())),"UNMAPPED_PARENT_CONTENT");
        for(JsonNode part:parent.path("parts"))observed(part.path("text").isTextual()&&integer(part.path("startOffset"))&&integer(part.path("endOffset"))
                &&part.path("endOffset").asInt()-part.path("startOffset").asInt()==part.path("text").asText().length(),"PART_UTF16_MISMATCH");
        for(JsonNode part:parent.path("parts"))observed(part.path("blockId").isTextual()&&!part.path("blockId").asText().isEmpty()
                &&part.path("anchor").isTextual()&&!part.path("anchor").asText().isEmpty()&&part.path("startOffset").asInt()>=0,"unknown_source_part_mapping");
    }
    static ObjectNode blockRange(Map<String,JsonNode> parents,JsonNode key,String block) {
        List<JsonNode> parts=new ArrayList<>();for(JsonNode parent:parents.values())if(scopeIdentity(parent).equals(key))for(JsonNode part:parent.path("parts"))
            if(block.equals(part.path("blockId").asText())){mapped(parent);parts.add(part);}
        observed(!parts.isEmpty(),"observed_block_mapping_missing");
        parts.sort(Comparator.comparingInt((JsonNode p)->p.path("startOffset").asInt()).thenComparingInt(p->p.path("endOffset").asInt()));
        StringBuilder text=new StringBuilder();for(JsonNode part:parts) {
            int start=part.path("startOffset").asInt(),end=part.path("endOffset").asInt();String raw=part.path("text").asText();
            observed(start<=text.length(),"observed_block_start_or_gap_unknown");int overlap=Math.max(0,Math.min(text.length(),end)-start);
            observed(text.substring(start,start+overlap).equals(raw.substring(0,overlap)),"overlapping_source_text_mismatch");
            if(end>text.length())text.append(raw.substring(text.length()-start));
        }
        ObjectNode range=JsonUtils.mapper().createObjectNode();range.put("blockId",block);range.put("startUtf16",0);range.put("endUtf16",text.length());
        range.put("status","observed_canonical_ranges_zero_gap");range.putNull("sourceBlockComplete");return range;
    }
    private static boolean touches(JsonNode parent,Set<String> blocks){for(JsonNode part:parent.path("parts"))if(blocks.contains(part.path("blockId").asText()))return true;return false;}
    /** Comparison only: raw identity() and all canonical payloads stay exact. */
    static ObjectNode scopeIdentity(JsonNode parent){
        ObjectNode out=identity(parent);String producer=parent.path("producerKind").asText(),version=parent.path("metadataVersion").asText();
        boolean nativePdf="native_pdf".equals(producer)&&"cas-native-pdf-joint-adopted-public-v1".equals(parent.path("storageShape").asText())
                &&Arrays.asList("source-reviewed-native-pdf-cas-public-projection-v1","source-reviewed-native-pdf-cas-public-projection-v2").contains(version);
        boolean family="native_pdf_source_family".equals(producer)&&"cas-same-original-native-pdf-complete-components-public-v1".equals(parent.path("storageShape").asText())
                &&"same-original-complete-native-pdf-components-v1".equals(version);
        String scope=parent.path("canonicalScope").asText(),metadata="whole_text_unit".equals(scope)?"native-pdf-no-table-adoption-v1":"whole_table_group".equals(scope)?"source-reviewed-native-pdf-whole-table-groups-v1":null;
        String module=parent.path("module").asText();JsonNode eligible=parent.path("eligibleModules");
        if(!(nativePdf||family)||metadata==null||!metadata.equals(parent.path("nativeTableMetadataVersion").asText())
                ||!Arrays.asList("vetting","advisory").contains(module)||!eligible.isArray()||eligible.isEmpty())return out;
        Set<String> modules=new HashSet<>();for(JsonNode value:eligible)if(!value.isTextual()||!Arrays.asList("vetting","advisory").contains(value.asText())||!modules.add(value.asText()))return out;
        if(!modules.contains(module))return out;
        for(String field:Arrays.asList("originalSourceSha256","sourceSha256","projectionRevisionSha256","sourceRevisionSha256","scopeSha256"))
            if(!parent.path(field).isTextual()||!sha(parent.path(field).asText()))return out;
        if(!parent.path("originalSourceSha256").equals(parent.path("sourceSha256")))return out;
        JsonNode edition=nullable(parent,"sourceEditionId"),policy=nullable(parent,"slUnitScopeManifestSha256");
        if(!(edition.isNull()&&policy.isNull())&&!(edition.isTextual()&&!edition.asText().trim().isEmpty()&&policy.isTextual()&&sha(policy.asText())))return out;
        ObjectNode root=JsonUtils.mapper().createObjectNode();root.put("kind","exact-native-pdf-root-unit-shapes-v1");
        for(String field:Arrays.asList("producerKind","storageShape","module","originalSourceSha256","sourceSha256","projectionRevisionSha256","sourceRevisionSha256","scopeSha256","sourceEditionId","slUnitScopeManifestSha256","eligibleModules"))root.set(field,nullable(parent,field));
        out.put("nativeTableMetadataVersion","exact-native-pdf-root-unit-shapes-v1:"+VettingCorpus.hash(JsonUtils.write(sorted(root))));return out;
    }
    static ObjectNode identity(JsonNode parent){ObjectNode out=JsonUtils.mapper().createObjectNode();for(String key:FIELDS)out.set(key,nullable(parent,key));return out;}
    static JsonNode nullable(JsonNode parent,String field){return parent.has(field)?parent.get(field):JsonUtils.mapper().nullNode();}
    static JsonNode sorted(JsonNode node){if(node.isObject()){ObjectNode out=JsonUtils.mapper().createObjectNode();TreeMap<String,JsonNode> fields=new TreeMap<>();node.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->out.set(k,sorted(v)));return out;}if(node.isArray()){ArrayNode out=JsonUtils.mapper().createArrayNode();node.forEach(v->out.add(sorted(v)));return out;}return node;}
    private static List<String> ids(JsonNode list){require(list.isArray(),"source-unit ID list missing");List<String> out=new ArrayList<>();for(JsonNode id:list){require(id.isTextual()&&!id.asText().isEmpty()&&!out.contains(id.asText()),"source-unit ID duplicate/missing");out.add(id.asText());}return out;}
    private static boolean integer(JsonNode n){return n.isIntegralNumber()&&n.canConvertToInt();}
    private static boolean falseBoolean(JsonNode n){return n.isBoolean()&&!n.asBoolean();}
    private static boolean sha(String s){return s.matches("[a-f0-9]{64}");}
    private static void observed(boolean ok,String why){if(!ok)throw new ObservationUnknown(why);}
    static class ObservationUnknown extends RuntimeException{ObservationUnknown(String reason){super(reason);}}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException("Canonical retrieval rejected: "+why);}
}
