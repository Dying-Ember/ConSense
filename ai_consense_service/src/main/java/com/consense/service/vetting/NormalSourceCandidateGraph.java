package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.domain.SourceDocument;
import com.consense.service.sources.OerSourceMetadata;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingCorpus.Part;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.*;

/** Ordinary source observations never authorize accepted canonical adoption. */
public final class NormalSourceCandidateGraph {
    public static final String SCHEMA="consense.canonical-candidate.v1";
    public static final String VERSION="actual-normal-source-unit-whole-page-observations-v2";
    private static final Pattern ROW=Pattern.compile("^(.+)/table-row/(\\d+)$");
    private static final int MAX_SELECTED_CHARACTERS=120000;
    private NormalSourceCandidateGraph() { }
    public static void capture(SourceDocument source) { source.setNormalCandidateJson(JsonUtils.write(build(source))); }

    /** Reconstruct from the actual persisted source each time; JSON declarations alone are never a seal. */
    static JsonNode bound(SourceDocument source) {
        if(JsonUtils.isBlankText(source.getNormalCandidateJson()))return null;
        JsonNode stored=JsonUtils.parse(source.getNormalCandidateJson());
        JsonNode current=build(source);
        if(stored.equals(current))return current;
        ObjectNode unknown=declarations();unknown.put("status","generation_binding_unknown");unknown.putArray("units");
        unknown.putArray("unknowns").add(unknown("source_generation","unknown","saved_candidate_differs_from_current_original_identity_structure_or_coverage"));
        return unknown;
    }

    /** Selected native-table/visual/form units expand whole structure; paragraph slices retain only selected text. */
    static JsonNode selected(JsonNode graph,Chunk chunk) {
        if(graph==null)return null;
        if("generation_binding_unknown".equals(graph.path("status").asText()))return graph.deepCopy();
        ObjectNode selected=declarations();selected.put("status","observed_selected_candidate_units");
        for(String key:Arrays.asList("generationSha256","source","coverageSha256","readingPlan"))if(graph.has(key))selected.set(key,graph.path(key).deepCopy());
        selected.set("unknowns",graph.path("unknowns").deepCopy());selected.put("selectionMeaning","Actual selected source parts select whole native table/visual/form structure; no legal closure or adoption is inferred");
        Map<String,List<Part>> parts=new LinkedHashMap<>();if(chunk.getParts()!=null)for(Part part:chunk.getParts())
            parts.computeIfAbsent(part.getBlockId(),key->new ArrayList<>()).add(part);
        ArrayNode units=selected.putArray("units");Set<String> used=new HashSet<>();
        for(JsonNode unit:graph.path("units")) {
            boolean touches=false;for(JsonNode id:unit.path("requiredMemberBlockIds"))if(parts.containsKey(id.asText()))touches=true;
            if(!touches)continue;
            boolean ambiguous=false;for(JsonNode unknown:unit.path("unknowns"))if("missing_or_duplicate_actual_block_id".equals(unknown.path("reason").asText()))ambiguous=true;
            if(ambiguous){((ArrayNode)selected.get("unknowns")).add(unknown("selected_members","unknown","ambiguous_actual_member_identity_prevents_unit_expansion"));continue;}
            ObjectNode material=(ObjectNode)unit.deepCopy();
            if("source_block".equals(unit.path("kind").asText())){
                JsonNode parent=unit.path("parts").get(0);List<Part> ranges=parts.get(parent.path("blockId").asText());
                boolean whole=ranges.size()==1&&ranges.get(0).getStartOffset()==0&&ranges.get(0).getEndOffset()==parent.path("text").asText().length();
                if(!whole){
                    ArrayNode slices=material.putArray("parts");for(Part range:ranges){
                        String effective=parent.path("text").asText();int start=range.getStartOffset(),end=range.getEndOffset();
                        if(start<0||end<start||end>effective.length()||!Objects.equals(effective.substring(start,end),range.getText()))
                            throw new IllegalArgumentException("Candidate source slice differs from actual generation");
                        ObjectNode slice=object();slice.put("blockId",range.getBlockId());slice.put("anchor",range.getAnchor());slice.put("text",range.getText());
                        slice.put("startOffset",start);slice.put("endOffset",end);slice.put("offsetUnit","utf16");slice.put("parentEffectiveTextSha256",VettingCorpus.hash(effective));slices.add(slice);
                        if(parent.has("sourcePosition"))slice.set("sourcePosition",parent.path("sourcePosition").deepCopy());
                    }
                    material.put("closureStatus","selected_source_range_only");((ArrayNode)material.get("unknowns")).add(unknown("whole_parent","unknown","unselected_parent_remains_in_source_storage"));
                    material.put("text",String.join("\n",ranges.stream().map(Part::getText).collect(java.util.stream.Collectors.toList())));
                    ArrayNode spans=material.putArray("sourceSpans");for(Part range:ranges){ObjectNode span=object();span.put("blockId",range.getBlockId());span.put("startOffset",range.getStartOffset());span.put("endOffset",range.getEndOffset());span.put("offsetUnit","utf16");spans.add(span);}
                }else material.put("closureStatus","complete_actual_source_block");
            }else material.put("closureStatus","complete_actual_structural_unit");
            units.add(material);used.add(unit.path("unitId").asText());
        }
        ArrayNode relations=selected.putArray("relations");for(JsonNode relation:graph.path("relations"))
            if(used.contains(relation.path("fromUnitId").asText())||used.contains(relation.path("toUnitId").asText())){
                ObjectNode r=(ObjectNode)relation.deepCopy();
                if(!used.contains(r.path("fromUnitId").asText())||!used.contains(r.path("toUnitId").asText()))r.put("selectedClosureStatus","related_unit_not_selected_unknown");
                relations.add(r);
            }
        if(JsonUtils.write(selected).length()>MAX_SELECTED_CHARACTERS)
            throw new IllegalArgumentException("Complete candidate structural unit exceeds model material budget; no truncated unit may be submitted");
        return selected;
    }

    private static ObjectNode build(SourceDocument source) {
        ObjectNode graph=declarations();graph.put("producerVersion",VERSION);graph.put("status","actual_source_candidate_generation");
        ObjectNode identity=object();identity.put("projectId",source.getProjectId());identity.put("documentId",String.valueOf(source.getId()));
        identity.put("originalSourceSha256",source.getSourceSha256());identity.put("parsedSourceHash",VettingCorpus.sourceHash(source));
        identity.put("filename",source.getFileName());identity.put("role",VettingCorpus.sourceRole(source));identity.put("scope",source.getDocumentScope());
        identity.put("identitySha256",VettingCorpus.hash(nvl(source.getOerIdentityJson())));graph.set("source",identity);
        JsonNode coverage=JsonUtils.isBlankText(source.getParseCoverageJson())?object():JsonUtils.parse(source.getParseCoverageJson());
        graph.put("coverageSha256",VettingCorpus.hash(nvl(source.getParseCoverageJson())));
        if(coverage.has("readingPlan"))graph.set("readingPlan",coverage.get("readingPlan").deepCopy());
        ArrayNode unknowns=graph.putArray("unknowns");
        if(source.getSourceSha256()==null||!source.getSourceSha256().matches("[a-f0-9]{64}"))unknowns.add(unknown("original_source","unknown","original_bytes_identity_missing"));
        if(OerSourceMetadata.identity(source)==null)unknowns.add(unknown("source_identity","unknown","confirmed_source_identity_unavailable"));
        if(!coverage.path("complete").asBoolean())unknowns.add(unknown("source_coverage","unknown","whole_source_extraction_not_complete"));
        if(coverage.isEmpty())unknowns.add(unknown("source_coverage","unknown","physical_coverage_unavailable"));
        for(JsonNode page:coverage.path("failedPages"))unknowns.add(unknown("physical_page."+page.asText(),"unknown","failed_page"));
        for(JsonNode page:coverage.path("needsReviewPages"))unknowns.add(unknown("physical_page."+page.asText(),"unverified","needs_review_page"));
        if(coverage.path("ocrQualityPageScopeUnknown").asBoolean())unknowns.add(unknown("ocr_page_scope","unknown","unverified_ocr_page_scope_unknown"));
        for(JsonNode limitation:coverage.path("limitations"))unknowns.add(unknown("source_limitation","unknown",limitation.asText()));
        List<DocumentBlock> blocks=VettingCorpus.blocks(source);Map<String,Integer> counts=new HashMap<>();
        for(DocumentBlock block:blocks)counts.merge(nvl(block.getId()),1,Integer::sum);
        Map<String,List<DocumentBlock>> groups=new LinkedHashMap<>();Map<String,String> kinds=new HashMap<>();Map<String,Integer> sourceOrders=new HashMap<>();
        int ordinal=0;for(DocumentBlock block:blocks){
            String key="block:"+ordinal++,kind="source_block";String table=tableLocation(block);
            if(table!=null){key="native-table:"+table;kind="native_table";}
            else if(!block.getWordNativeObjects().isEmpty()){kind="word_native_object";}
            else if(block.getParserPageGroup()!=null){kind="whole_parser_page";}
            else if(block.getNativeTableCandidate()!=null){kind="native_table_method";}
            else if(block.getVisualRegion()!=null){kind="visual_region";}
            else if(block.getPdfFormField()!=null){kind="native_form";}
            groups.computeIfAbsent(key,k->new ArrayList<>()).add(block);kinds.put(key,kind);sourceOrders.putIfAbsent(key,ordinal-1);
        }
        ArrayNode units=graph.putArray("units"),relations=graph.putArray("relations");Map<String,String> visualOwners=new HashMap<>(),blockOwners=new HashMap<>();
        for(Map.Entry<String,List<DocumentBlock>> group:groups.entrySet()) {
            ObjectNode unit=object();String unitId="candidate-unit:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(identity,graph.path("coverageSha256"),VERSION,group.getKey())));
            unit.put("unitId",unitId);unit.put("kind",kinds.get(group.getKey()));unit.put("status","observed_structure_candidate");
            unit.put("adoptionStatus","candidate_unverified");unit.put("semanticScopeVerified",false);unit.put("qualifiersComplete","unknown");
            ArrayNode members=unit.putArray("requiredMemberBlockIds"),parts=unit.putArray("parts"),spans=unit.putArray("sourceSpans"),pages=unit.putArray("physicalPages"),unitUnknowns=unit.putArray("unknowns");
            Set<Integer> pageIds=new TreeSet<>();List<String> effective=new ArrayList<>();unit.put("sourceOrder",sourceOrders.get(group.getKey()));
            for(DocumentBlock block:group.getValue()) {
                members.add(block.getId());ObjectNode part=part(block,source.getSourceSha256());parts.add(part);effective.add(nvl(block.getEffectiveText()));
                if(blockOwners.containsKey(block.getId()))blockOwners.put(block.getId(),"");else blockOwners.put(block.getId(),unitId);
                ObjectNode span=object();span.put("blockId",block.getId());span.put("startOffset",0);span.put("endOffset",nvl(block.getEffectiveText()).length());span.put("offsetUnit","utf16");
                span.put("sourceSha256",source.getSourceSha256());span.set("sourcePosition",part.path("sourcePosition").deepCopy());spans.add(span);
                if(block.getPageNo()!=null)pageIds.add(block.getPageNo());
                if(counts.get(nvl(block.getId()))!=1||JsonUtils.isBlankText(block.getId()))unitUnknowns.add(unknown("member_identity","unknown","missing_or_duplicate_actual_block_id"));
                if("ocr_candidate".equals(block.getSource()))unitUnknowns.add(unknown("member."+block.getId(),"unknown","native_visual_conflict_candidate_not_effective_body"));
                if(block.getNativeTableCandidate()!=null)unitUnknowns.add(unknown("native_table_method","unverified","whole_independent_method_structure_not_source_accepted"));
                if(block.getParserPageGroup()!=null)unitUnknowns.add(unknown("whole_parser_page","unverified","whole_independent_parser_alternatives_not_source_accepted"));
                if(!block.getWordNativeObjects().isEmpty())unitUnknowns.add(unknown("word_native_object","unknown","native_ooxml_unrendered_semantics_unknown"));
                if(block.getVisualRegion()!=null){
                    String region=block.getVisualRegion().path("id").asText();if(!region.isEmpty()){
                        if(visualOwners.containsKey(region))visualOwners.put(region,"");else visualOwners.put(region,unitId);
                    }
                    unitUnknowns.add(unknown("visual_semantics","unverified","provider_table_fields_relations_geometry_are_candidate_observations"));
                }
            }
            pageIds.forEach(pages::add);unit.put("text",String.join("\n",effective));
            if("native_table".equals(unit.path("kind").asText())){
                ObjectNode table=object();table.put("tableLocation",group.getKey().substring("native-table:".length()));table.put("headerMeaning","unknown");
                table.put("semanticAdoption","unknown");ArrayNode rows=table.putArray("rows");
                for(DocumentBlock block:group.getValue()){ObjectNode row=object();row.put("blockId",block.getId());row.put("location",block.getLocation());
                    row.set("cells",JsonUtils.mapper().valueToTree(block.getCells()));row.set("wordCells",nativeCells(block));rows.add(row);
                    ObjectNode membership=object();membership.put("type","native_table_member");membership.put("status","observed_structure_only");membership.put("fromUnitId",unitId);membership.put("toUnitId",unitId);membership.put("toBlockId",block.getId());membership.put("humanConfirmed",false);relations.add(membership);
                }unit.set("tableGroup",table);
            }else unit.putNull("tableGroup");
            units.add(unit);
        }
        for(JsonNode unit:units)for(JsonNode part:unit.path("parts")){
            for(JsonNode candidate:part.path("readingContext").path("relationCandidates")){
                String target=candidate.path("toOccurrenceId").asText(),targetUnit=blockOwners.get(target);
                ObjectNode relation=object();relation.put("type",candidate.path("kind").asText());relation.put("status","unknown_not_source_assigned");
                relation.put("fromUnitId",unit.path("unitId").asText());relation.put("toUnitId",targetUnit);relation.put("toOccurrenceId",target);
                relation.put("basis",candidate.path("basis").asText());relation.put("humanConfirmed",false);relations.add(relation);
            }
            String parent=part.path("visualRegion").path("parentId").asText();String owner=visualOwners.get(parent);
            if(!parent.isEmpty()){
                ObjectNode relation=object();relation.put("type","provider_parent_candidate");relation.put("fromUnitId",unit.path("unitId").asText());relation.put("observedParentRegionId",parent);
                relation.put("status",owner==null||owner.isEmpty()?"unknown_parent_member":"provider_observed_unverified");relation.put("toUnitId",owner);relation.put("humanConfirmed",false);relations.add(relation);
            }
        }
        graph.put("generationSha256",VettingCorpus.hash(JsonUtils.write(graph)));return graph;
    }

    private static ObjectNode part(DocumentBlock block,String sourceSha) {
        ObjectNode part=object();part.put("blockId",block.getId());part.put("anchor",block.getLocation());part.put("text",block.getEffectiveText());
        part.put("physicalPageNo",block.getPageNo());part.put("extractionSource",block.getSource());part.put("sourceSha256",sourceSha);
        part.put("startOffset",0);part.put("endOffset",nvl(block.getEffectiveText()).length());part.put("offsetUnit","utf16");
        ObjectNode position=object();position.put("kind",block.getPageNo()==null?"native_location":"physical_page");position.put("physicalPageNo",block.getPageNo());position.put("nodePath",block.getLocation());
        String path=nvl(block.getLocation());position.put("story",path.split("/",2)[0]);part.set("sourcePosition",position);
        if(block.getSourcePosition()!=null)part.set("sourcePosition",block.getSourcePosition().deepCopy());
        part.set("bbox",JsonUtils.mapper().valueToTree(block.getBbox()));part.set("cells",JsonUtils.mapper().valueToTree(block.getCells()));part.set("wordCells",nativeCells(block));
        part.set("wordNumbering",JsonUtils.mapper().valueToTree(block.getWordNumbering()));part.set("wordSymbols",JsonUtils.mapper().valueToTree(block.getWordSymbols()));
        part.set("wordWarnings",JsonUtils.mapper().valueToTree(block.getWordWarnings()));
        part.set("wordNativeObjects",OerModelMaterial.wordNativeObjects(JsonUtils.mapper().valueToTree(block.getWordNativeObjects())));
        if(block.getVisualRegion()!=null){part.set("visualRegion",OerModelMaterial.visualRegion(block.getVisualRegion()));part.set("visualFrame",OerModelMaterial.visualFrame(block.getVisualFrame()));part.set("visualProvenance",OerModelMaterial.visualProvenance(block.getVisualProvenance()));}
        if(block.getReadingContext()!=null)part.set("readingContext",block.getReadingContext().deepCopy());
        if(block.getNativeTableCandidate()!=null)part.set("nativeTableCandidate",OerModelMaterial.nativeTableCandidate(block.getNativeTableCandidate()));
        if(block.getParserPageGroup()!=null)part.set("parserPageGroup",OerModelMaterial.parserPageGroup(block.getParserPageGroup()));
        if(block.getPdfFormField()!=null){JsonNode field=JsonUtils.mapper().valueToTree(block.getPdfFormField());ObjectNode form=object();
            for(String key:Arrays.asList("id","fieldName","fieldType","fieldValue","appearanceValue","appearanceState","visibilityStatus","valueStatus","pageNo","needAppearances","appearancePresent","conflict","limitations"))if(field.has(key))form.set(key,field.get(key));
            part.set("pdfFormField",form);
        }return part;
    }
    private static ArrayNode nativeCells(DocumentBlock block){ArrayNode out=JsonUtils.mapper().createArrayNode();for(DocumentBlock.WordCell cell:block.getWordCells()){
        JsonNode raw=JsonUtils.mapper().valueToTree(cell);ObjectNode compact=object();for(String key:Arrays.asList("location","tableLocation","verticalMerge","horizontalMerge","topologyStatus","gridSpanRaw","gridBeforeRaw","rowIndex","cellIndex","gridColumn","columnSpan","rowSpan","text","limitations"))if(raw.has(key))compact.set(key,raw.get(key));out.add(compact);
    }return out;}
    private static String tableLocation(DocumentBlock block){
        if(!"native".equals(block.getSource()))return null;
        Set<String> locations=new HashSet<>();for(DocumentBlock.WordCell cell:block.getWordCells())if(!JsonUtils.isBlankText(cell.getTableLocation()))locations.add(cell.getTableLocation());
        if(locations.size()==1)return locations.iterator().next();
        Matcher row=ROW.matcher(nvl(block.getLocation()));return row.matches()&&!block.getCells().isEmpty()?row.group(1):null;
    }
    private static ObjectNode declarations(){ObjectNode out=object();out.put("schema",SCHEMA);out.put("adoptionStatus","candidate_unverified");out.put("humanConfirmed",false);out.put("sourceQualityAccepted",false);out.put("semanticScopeVerified",false);out.put("legalClosure","unknown");return out;}
    private static ObjectNode unknown(String path,String status,String reason){ObjectNode out=object();out.put("path",path);out.put("status",status);out.put("reason",reason);return out;}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static String nvl(String value){return value==null?"":value;}
}
