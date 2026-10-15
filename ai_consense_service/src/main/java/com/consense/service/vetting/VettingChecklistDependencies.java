package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.OerDocumentIdentity;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.sources.ProjectDocumentScope;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Server-only dependency producer. Closed observed units authorize reuse, never legal compliance. */
final class VettingChecklistDependencies {
    static final String VERSION="checklist-current-required-material-members-v1";
    private static final Pattern CLAUSE=Pattern.compile("(?i)\\b(?:NTT|SCT|SCC|GCT|GCC|PRE|FT|AA|GS|SL)(?:\\.[A-Z0-9]+|[0-9]+)(?:\\.[A-Z0-9]+)*\\b");
    private static final Pattern FAMILY=Pattern.compile("(?i)^(NTT|SCT|SCC|GCT|GCC|PRE|FT|AA|GS|SL)(?:\\.|$)");
    private VettingChecklistDependencies() { }

    /** Internal trusted seam; only capture creates real scopes from the current verified canonical session. */
    static final class Scope {
        final String project,family,edition,role,logicalSource,clause;
        final List<String> members;
        final JsonNode material,sourceRef;
        Scope(String project,String family,String edition,String role,String logicalSource,String clause,List<String> members,JsonNode material,JsonNode sourceRef){
            this.project=project;this.family=family;this.edition=edition;this.role=role;this.logicalSource=logicalSource;this.clause=clause;
            this.members=members;this.material=material;this.sourceRef=sourceRef;
        }
    }

    static JsonNode capture(String project,OerBusinessEvidence.Session session,List<SourceDocument> selected,List<SourceDocument> catalog,
                            Collection<VettingSemanticReview.Candidate> outcomes,List<SemanticTopicVO> topics) {
        ObjectNode ledger=object();ledger.put("producerVersion",VERSION);ledger.put("projectId",project);ObjectNode items=ledger.putObject("items");
        List<Scope> scopes=new ArrayList<>();ObjectNode context=object();String unknown=session==null?"current_verified_canonical_session_unavailable":null;
        Map<String,SourceDocument> sources=selected.stream().collect(Collectors.toMap(d->String.valueOf(d.getId()),d->d));
        for(SourceDocument source:ProjectDocumentScope.currentCatalog(project,catalog)){
            String excluded=ProjectDocumentScope.exclusionReason(source,"fullset");
            if("source_identity_unresolved".equals(excluded)){unknown="catalog_contains_unresolved_source_identity";break;}
            if(excluded!=null)continue;
            String role=VettingCorpus.sourceRole(source);OerDocumentIdentity identity=OerSourceMetadata.identity(source);
            if(source.getId()==null||identity==null||!Arrays.asList("sha_verified","content_supported").contains(identity.getIdentityStatus())
                    ||JsonUtils.isBlankText(identity.getVersion())||"UNKNOWN".equals(identity.getKind())){unknown="confirmed_source_family_or_edition_unavailable";break;}
            if(!"tender".equals(role)){
                if(!"PARSED".equals(source.getParseStatus())){unknown="required_standard_fact_or_manifest_coverage_unknown";break;}
                context.set(logicalSource(source),JsonUtils.mapper().valueToTree(Arrays.asList(role,identity,source.getSourceSha256(),VettingCorpus.sourceHash(source),source.getParseCoverageJson())));
            }
        }
        if(unknown==null){
            List<Chunk> chunks=session.chunks();Map<String,JsonNode> parents=new LinkedHashMap<>();Map<String,Chunk> byId=new LinkedHashMap<>();
            for(Chunk chunk:chunks){
                if(OerBusinessEvidence.canonicalViewStatus(chunk)!=null){unknown="current_canonical_view_changed";break;}
                parents.put(chunk.getId(),chunk.getCanonicalPayload());byId.put(chunk.getId(),chunk);
            }
            if(unknown==null){Set<String> observed=new HashSet<>();for(Chunk chunk:chunks){
                if(!"tender".equals(chunk.getRole())||JsonUtils.isBlankText(chunk.getClauseId()))continue;
                JsonNode unit=OerCanonicalSourceUnits.resolve(parents,chunk.getCanonicalPayload());
                if(!"complete_observed_unit".equals(unit.path("status").asText())||!"exact_clause_heading".equals(unit.path("kind").asText()))continue;
                String key=chunk.getDocumentId()+"|"+chunk.getClauseId();if(!observed.add(key))continue;
                SourceDocument source=sources.get(chunk.getDocumentId());if(source==null)continue;OerDocumentIdentity identity=OerSourceMetadata.identity(source);
                if(identity==null||JsonUtils.isBlankText(identity.getVersion()))continue;
                List<String> ids=new ArrayList<>();unit.path("requiredMemberIds").forEach(id->ids.add(id.asText()));
                ArrayNode material=JsonUtils.mapper().createArrayNode(),refs=JsonUtils.mapper().createArrayNode();boolean closed=true;
                for(String id:ids){Chunk member=byId.get(id);if(member==null){closed=false;break;}
                    JsonNode raw=member.getCanonicalPayload(),adoption=raw.path("adoption");
                    if(!"explicit_source_reviewed_selection".equals(adoption.path("reviewStatus").asText())
                            ||!adoption.path("clause").path("clauseId").asText().equals(chunk.getClauseId())
                            ||!member.getCanonicalEvidence().getUnknowns().isEmpty()){closed=false;break;}
                    ObjectNode body=object();for(String field:Arrays.asList("unitId","canonicalScope","content","parts","sourceSpans","tableGroup","relations","adoption","sourceQuality"))body.set(field,raw.path(field).deepCopy());
                    material.add(body);refs.add(JsonUtils.mapper().valueToTree(member.getCanonicalEvidence()));
                }
                if(closed&&!ids.isEmpty())scopes.add(new Scope(project,identity.getKind(),identity.getVersion(),chunk.getRole(),logicalSource(source),chunk.getClauseId(),ids,material,refs));
            }}
        }
        List<SemanticPacketVO> packets=topics.stream().flatMap(topic->topic.getPacketAudits().stream()).collect(Collectors.toList());
        for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items())items.set(item.getId(),unknown==null
                ?derive(item,project,scopes,context,outcomes,packets):unknown(item,unknown));
        return ledger;
    }

    /** Pure derivation over already verified actual units, exercised separately with authored scope fixtures. */
    static ObjectNode derive(VettingChecklistCatalog.Item item,String project,List<Scope> scopes,JsonNode context,
                             Collection<VettingSemanticReview.Candidate> outcomes,List<SemanticPacketVO> packets) {
        Set<String> clauses=clauses(item.getClause());Matcher family=FAMILY.matcher(item.getSection());
        if(!family.find()||clauses.size()!=1||item.getClause().toLowerCase(Locale.ROOT).contains("all "))return unknown(item,"generic_or_multi_clause_requirement_scope_unknown");
        String clause=clauses.iterator().next(),requiredFamily=family.group(1).toUpperCase(Locale.ROOT);
        Set<String> referenced=clauses(item.getDescription());referenced.remove(clause);
        if(!referenced.isEmpty()||Pattern.compile("(?i)\\b(?:GCC|NTT|SCT|SCC|PRE|GS|SL)\\s+(?:clauses?\\s+)?[0-9]").matcher(item.getDescription()).find())return unknown(item,"cross_source_requirement_dependencies_not_closed");
        List<Scope> matches=scopes.stream().filter(scope->scope.project.equals(project)&&scope.family.equals(requiredFamily)
                &&scope.role.equals("tender")&&scope.clause.equals(clause)).collect(Collectors.toList());
        if(matches.size()!=1)return unknown(item,"target_source_clause_missing_or_ambiguous");
        Map<String,Scope> byMember=new HashMap<>();for(Scope scope:scopes)for(String id:scope.members){if(byMember.putIfAbsent(id,scope)!=null)return unknown(item,"actual_required_member_identity_ambiguous");}
        Set<Scope> required=new LinkedHashSet<>();required.add(matches.get(0));Set<String> targetIds=new HashSet<>(matches.get(0).members);
        List<Object> checks=new ArrayList<>();
        for(SemanticPacketVO packet:packets)if(packet.getSubmittedChunkIds().stream().anyMatch(targetIds::contains)){
            if(!packet.isActualGatewayCallStarted()||!Arrays.asList("completed","completed_empty","completed_with_rejections").contains(packet.getStatus()))return unknown(item,"target_packet_execution_or_context_unknown");
            for(String id:packet.getSubmittedChunkIds()){Scope scope=byMember.get(id);if(scope==null||!scope.project.equals(project))return unknown(item,"target_packet_contains_unclosed_context_member");required.add(scope);}
            checks.add(Arrays.asList(packet.getKind(),packet.getStatus(),packet.getCheckState(),packet.getVerifiedAssessments()));
        }
        for(VettingSemanticReview.Candidate outcome:outcomes){
            boolean touches=outcome.getEvidence()!=null&&outcome.getEvidence().stream().anyMatch(e->e.getCanonicalEvidence()!=null&&targetIds.contains(e.getCanonicalEvidence().getCanonicalChunkId()));
            if(!touches)continue;
            for(FindingEvidence quote:outcome.getEvidence()){
                Scope scope=quote.getCanonicalEvidence()==null?null:byMember.get(quote.getCanonicalEvidence().getCanonicalChunkId());
                if(scope==null)return unknown(item,"target_generated_check_has_unbound_context");required.add(scope);
            }
            checks.add(JsonUtils.mapper().valueToTree(outcome));
        }
        Map<String,String> addresses=new HashMap<>();for(Scope scope:required){String address=scope.logicalSource+"|"+scope.family+"|"+scope.edition+"|"+scope.clause;
            for(String id:scope.members)addresses.put(id,address);nativeAddresses(scope.material,address,addresses);
        }
        ArrayNode refs=JsonUtils.mapper().createArrayNode();List<String> members=new ArrayList<>();
        Set<String> materialIds=new HashSet<>();for(Scope scope:required)materialIds(scope.material,materialIds);
        for(Scope scope:required){if(!scope.project.equals(project)||JsonUtils.isBlankText(scope.edition)||JsonUtils.isBlankText(scope.logicalSource)||scope.members.isEmpty())return unknown(item,"required_source_adoption_identity_incomplete");
            if(!scope.material.isArray()||scope.material.isEmpty())return unknown(item,"required_source_material_unavailable");
            for(JsonNode member:scope.material){Set<String> attributes=new HashSet<>();member.path("adoption").path("reviewedAttributes").forEach(attribute->attributes.add(attribute.asText()));
                if(!"explicit_source_reviewed_selection".equals(member.path("adoption").path("reviewStatus").asText())
                        ||!attributes.containsAll(Arrays.asList("text","clauseHierarchy")))return unknown(item,"required_source_proof_or_clause_membership_unknown");
                for(JsonNode relation:member.path("relations")){
                    if(!Arrays.asList("source_reviewed","observed_structure_only").contains(relation.path("status").asText())
                            ||relation.path("qualifiersComplete").asText().equals("unknown"))return unknown(item,"required_source_relation_scope_unknown");
                    for(String target:Arrays.asList("toUnitId","toBlockId","toRegionId","toCellId"))
                        if(relation.hasNonNull(target)&&!materialIds.contains(relation.path(target).asText()))return unknown(item,"required_source_relation_member_not_in_closed_context");
                }
            }
            members.add(JsonUtils.write(Arrays.asList(scope.project,scope.family,scope.edition,scope.role,scope.logicalSource,scope.clause,
                    comparable(scope.material,addresses))));refs.add(scope.sourceRef.deepCopy());
        }Collections.sort(members);
        ObjectNode row=object();row.put("status","required_member_scope_closed");row.put("reuseScope","exact_required_material_members");row.put("requirementSha256",requirementHash(item));
        row.put("basisSha256",VettingCorpus.hash(JsonUtils.write(Arrays.asList(VERSION,project,requirementHash(item),members,context,comparable(JsonUtils.mapper().valueToTree(checks),addresses)))));
        row.set("requiredMemberRefs",refs);row.put("meaning","Current source-reviewed observed unit and actual submitted/context members only; no legal compliance or independent judgment claim");return row;
    }

    static String requirementHash(VettingChecklistCatalog.Item item){return VettingCorpus.hash(JsonUtils.write(Arrays.asList(item.getId(),item.getSection(),item.getClause(),item.getDescription(),item.getReviewScope(),item.getScopeNote())));}
    static JsonNode itemLedger(String coverage,String project,VettingChecklistCatalog.Item item){
        if(JsonUtils.isBlankText(coverage))return null;JsonNode ledger=JsonUtils.parse(coverage).path("checklistDependencyLedger");
        JsonNode row=ledger.path("items").path(item.getId());return VERSION.equals(ledger.path("producerVersion").asText())&&project.equals(ledger.path("projectId").asText())
                &&requirementHash(item).equals(row.path("requirementSha256").asText())?row:null;
    }
    private static Set<String> clauses(String text){Set<String> out=new LinkedHashSet<>();Matcher matches=CLAUSE.matcher(text);while(matches.find())out.add(matches.group().toUpperCase(Locale.ROOT));return out;}
    private static ObjectNode unknown(VettingChecklistCatalog.Item item,String reason){ObjectNode row=object();row.put("status","required_member_dependencies_unknown");row.put("reuseScope","exact_whole_generation");row.put("requirementSha256",requirementHash(item));row.put("reason",reason);return row;}
    // A reupload receives a new server-owned source ID. A filename, matching family/edition or equal clause text never joins unrelated originals.
    private static String logicalSource(SourceDocument source){return source.getProjectId()+"|"+source.getCategory()+"|source_document_id:"+source.getId();}
    private static JsonNode comparable(JsonNode raw,Map<String,String> addresses){
        if(raw.isArray()){ArrayNode out=JsonUtils.mapper().createArrayNode();for(JsonNode value:raw)out.add(comparable(value,addresses));return out;}
        if(raw.isObject()){ObjectNode out=object();TreeMap<String,JsonNode> fields=new TreeMap<>();raw.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            for(Map.Entry<String,JsonNode> field:fields.entrySet()){
                if(field.getKey().endsWith("Ref")||Arrays.asList("sourceSha256","originalSourceSha256","sourceRevisionSha256","sourceHash","partSha256","projectionSha256","projectionRevisionSha256","canonicalSourceHash","javaParsedSourceHash","referenceSha256","runId","artifactDescriptor","rawNode","rawResponse","rawPayload","transportValidation","softwareProof","packetId","packetSourceSnapshotSha256","observerIdentitySha256","internalProjectionSha256").contains(field.getKey()))continue;
                out.set(field.getKey(),comparable(field.getValue(),addresses));
            }return out;
        }
        if(raw.isTextual()&&addresses.containsKey(raw.asText()))return JsonUtils.mapper().getNodeFactory().textNode(addresses.get(raw.asText()));return raw.deepCopy();
    }
    private static void nativeAddresses(JsonNode raw,String scope,Map<String,String> addresses){
        if(raw.isArray()){for(JsonNode value:raw)nativeAddresses(value,scope,addresses);return;}
        if(!raw.isObject())return;String path=raw.path("sourcePosition").path("nodePath").asText();if(path.isEmpty())path=raw.path("nodePath").asText();if(path.isEmpty())path=raw.path("nativePath").asText();
        if(!path.isEmpty())for(String key:Arrays.asList("blockId","nativeBlockId","nodeId","tableId","rowId","cellId"))if(raw.path(key).isTextual())addresses.put(raw.path(key).asText(),scope+"|native-path:"+path);
        raw.elements().forEachRemaining(value->nativeAddresses(value,scope,addresses));
    }
    private static void materialIds(JsonNode raw,Set<String> ids){
        if(raw.isArray()){for(JsonNode value:raw)materialIds(value,ids);return;}
        if(!raw.isObject())return;for(String key:Arrays.asList("unitId","blockId","referenceRegionId","nodeId","tableId","rowId","cellId"))if(raw.path(key).isTextual())ids.add(raw.path(key).asText());
        raw.fields().forEachRemaining(field->{if(!"relations".equals(field.getKey()))materialIds(field.getValue(),ids);});
    }
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
}
