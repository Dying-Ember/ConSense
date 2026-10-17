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
    private NormalSourceCandidateGraph() { }
    public static void capture(SourceDocument source) { source.setNormalCandidateJson(JsonUtils.write(build(source))); }

    public static JsonNode requireBound(SourceDocument source) {
        JsonNode graph=bound(source);
        if(graph==null||!"actual_source_candidate_generation".equals(graph.path("status").asText()))
            throw new IllegalArgumentException("NORMAL_OBSERVED_GENERATION_STALE_OR_MISSING");
        return graph;
    }

    /** Reconstruct from the actual persisted source each time; JSON declarations alone are never a seal. */
    static JsonNode bound(SourceDocument source) {
        if(JsonUtils.isBlankText(source.getNormalCandidateJson()))return null;
        JsonNode stored=JsonUtils.parse(source.getNormalCandidateJson());
        // Compare the persisted JSON representation; small Word Long values reload as IntNode.
        JsonNode current=JsonUtils.parse(JsonUtils.write(build(source)));
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
                        ObjectNode context=null;for(String key:Arrays.asList("nativeDocxSection","nativeDocxParagraph","nativeDocxOutline"))if(parent.path("readingContext").has(key)){
                            if(context==null)context=slice.putObject("readingContext");context.set(key,parent.path("readingContext").path(key).deepCopy());
                        }
                    }
                    material.put("closureStatus","selected_source_range_only");((ArrayNode)material.get("unknowns")).add(unknown("whole_parent","unknown","unselected_parent_remains_in_source_storage"));
                    material.put("text",String.join("\n",ranges.stream().map(Part::getText).collect(java.util.stream.Collectors.toList())));
                    ArrayNode spans=material.putArray("sourceSpans");for(Part range:ranges){ObjectNode span=object();span.put("blockId",range.getBlockId());span.put("startOffset",range.getStartOffset());span.put("endOffset",range.getEndOffset());span.put("offsetUnit","utf16");spans.add(span);}
                }else material.put("closureStatus","complete_actual_source_block");
            }else material.put("closureStatus","complete_actual_structural_unit");
            units.add(material);used.add(unit.path("unitId").asText());
        }
        if(graph.has("nativeDocxOutline")){
            Set<Integer> ordinals=new TreeSet<>();
            for(JsonNode unit:units)for(JsonNode part:unit.path("parts"))for(String key:Arrays.asList("ancestorOrdinals","containedHeadingOrdinals"))
                for(JsonNode ordinal:part.path("readingContext").path("nativeDocxOutline").path(key))ordinals.add(ordinal.asInt());
            ObjectNode outline=selected.putObject("nativeDocxOutline");outline.put("version",graph.path("nativeDocxOutline").path("version").asText());
            outline.put("membershipMeaning","Observed source outline; unselected text remains in source storage");ArrayNode records=outline.putArray("headings");
            for(int ordinal:ordinals){JsonNode heading=graph.path("nativeDocxOutline").path("headings").get(ordinal);if(heading==null)throw new IllegalArgumentException("Native outline reference differs from bound source");records.add(heading.deepCopy());}
        }
        ArrayNode relations=selected.putArray("relations");for(JsonNode relation:graph.path("relations"))
            if(used.contains(relation.path("fromUnitId").asText())||used.contains(relation.path("toUnitId").asText())){
                ObjectNode r=(ObjectNode)relation.deepCopy();
                if(!used.contains(r.path("fromUnitId").asText())||!used.contains(r.path("toUnitId").asText()))r.put("selectedClosureStatus","related_unit_not_selected_unknown");
                relations.add(r);
            }
        return selected;
    }

    /** Model-only physical Word rows. Persisted graphs, chunks and their selections are never changed. */
    static JsonNode selectedForModel(JsonNode graph,Chunk chunk) {
        JsonNode source=graph.path("source");
        if(!"actual_source_candidate_generation".equals(graph.path("status").asText())
                ||!Objects.equals(source.path("documentId").asText(),chunk.getDocumentId())
                ||!Objects.equals(source.path("parsedSourceHash").asText(),chunk.getSourceHash())
                ||!Objects.equals(source.path("role").asText(),chunk.getRole())
                ||chunk.getOriginalSourceSha256()!=null&&!Objects.equals(source.path("originalSourceSha256").asText(),chunk.getOriginalSourceSha256())
                ||chunk.getSourceSha256()!=null&&!Objects.equals(source.path("originalSourceSha256").asText(),chunk.getSourceSha256())
                ||chunk.getCandidateStructure()==null||!graph.path("generationSha256").equals(chunk.getCandidateStructure().path("generationSha256")))
            throw new IllegalArgumentException("Ordinary model source generation differs");
        Map<String,JsonNode> byId=new LinkedHashMap<>();Set<String> duplicates=new HashSet<>();
        for(JsonNode unit:graph.path("units"))for(JsonNode part:unit.path("parts"))
            if(byId.putIfAbsent(part.path("blockId").asText(),part)!=null)duplicates.add(part.path("blockId").asText());
        List<String> body=new ArrayList<>();Set<List<String>> touched=new LinkedHashSet<>();
        ObjectNode out=declarations();out.put("status","observed_model_only_word_table_context");
        out.put("generationSha256",graph.path("generationSha256").asText());out.set("source",source.deepCopy());
        out.put("coverageSha256",graph.path("coverageSha256").asText());ArrayNode unknowns=out.putArray("unknowns");
        if(chunk.getParts()==null)throw new IllegalArgumentException("Ordinary selected parts required");
        for(Part selected:chunk.getParts()){
            JsonNode part=byId.get(selected.getBlockId());String text=part==null?"":part.path("text").asText();
            int start=selected.getStartOffset(),end=selected.getEndOffset();
            String anchor=nvl(chunk.getClauseId()).isEmpty()?part==null?"":part.path("anchor").asText():chunk.getClauseId()+" · "+(part==null?"":part.path("anchor").asText());
            if(start!=0||end!=text.length())anchor+=" @"+start;
            if(part==null||duplicates.contains(selected.getBlockId())||start<0||end<start||end>text.length()
                    ||!Objects.equals(text.substring(start,end),selected.getText())||!Objects.equals(anchor,selected.getAnchor())
                    ||!Objects.equals(part.path("extractionSource").asText(),selected.getExtractionSource())
                    ||selected.getSourcePosition()==null||!part.path("sourcePosition").equals(selected.getSourcePosition()))
                throw new IllegalArgumentException("Ordinary selected source part differs from bound generation");
            if(selected.getWordCells()!=null){
                JsonNode actual=JsonUtils.mapper().valueToTree(selected.getWordCells()),expected=part.path("wordCells");
                if(actual.size()!=expected.size())throw new IllegalArgumentException("Selected Word cells differ from bound generation");
                for(int i=0;i<expected.size();i++){
                    Iterator<String> fields=expected.get(i).fieldNames();while(fields.hasNext()){
                        String field=fields.next();if(!expected.get(i).path(field).equals(actual.get(i).path(field)))
                            throw new IllegalArgumentException("Selected Word cells differ from bound generation");
                    }
                }
            }
            body.add(selected.getText());List<String> table=wordTableKey(part,source.path("originalSourceSha256").asText());
            if(table!=null)touched.add(table);
            else if("docx".equals(part.path("extractionSource").asText())&&!part.path("wordCells").isEmpty())
                unknowns.add(unknown("member."+selected.getBlockId(),"unknown","physical_word_table_identity_or_nested_members_unresolved"));
        }
        if(!Objects.equals(String.join("\n",body),chunk.getContent()))throw new IllegalArgumentException("Ordinary selected body differs from parts");
        Map<List<String>,List<JsonNode>> tables=new LinkedHashMap<>();Map<List<String>,Set<List<String>>> identities=new HashMap<>();
        Set<List<String>> blocked=new HashSet<>();Set<String> blockedLocations=new HashSet<>();
        for(JsonNode part:byId.values())if("docx".equals(part.path("extractionSource").asText())&&!part.path("wordCells").isEmpty()){
            List<String> key=wordTableKey(part,source.path("originalSourceSha256").asText());
            if(key==null||duplicates.contains(part.path("blockId").asText())){
                for(JsonNode cell:part.path("wordCells")){
                    String location=cell.path("tableLocation").asText();
                    if(part.path("sourcePosition").path("partName").asText().isEmpty())blockedLocations.add(location);
                    else blocked.add(Arrays.asList(part.path("sourcePosition").path("partName").asText(),location));
                }continue;
            }
            tables.computeIfAbsent(key,k->new ArrayList<>()).add(part);
            identities.computeIfAbsent(Arrays.asList(key.get(0),key.get(2)),k->new HashSet<>()).add(key);
        }
        ArrayNode units=out.putArray("units");
        for(List<String> key:touched){
            List<String> physical=Arrays.asList(key.get(0),key.get(2));
            if(blocked.contains(physical)||blockedLocations.contains(key.get(2))||identities.get(physical).size()!=1){
                unknowns.add(unknown("table."+key.get(2),"unknown","ambiguous_or_incomplete_observed_table_identity"));continue;
            }
            ObjectNode unit=declarations();unit.put("kind","ordinary_docx_table");
            unit.put("unitId","ordinary-table:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(graph.path("generationSha256"),source,key))));
            unit.put("closureStatus","all_observed_members_in_bound_table_generation");unit.put("qualifiersComplete","unknown");
            unit.put("contextMeaning","Unranked observed table context; only original selected chunk content can support its existing quotation or citation ID. Blank meaning, nested topology, applicability and whole-original coverage remain unknown.");
            ArrayNode members=unit.putArray("requiredMemberBlockIds"),parts=unit.putArray("parts"),spans=unit.putArray("sourceSpans");
            ObjectNode table=object();table.put("partName",key.get(0));table.put("partSha256",key.get(1));table.put("tableLocation",key.get(2));table.put("nativeTablePath",key.get(3));table.put("headerMeaning","unknown");table.put("semanticAdoption","unknown");
            ArrayNode rows=table.putArray("rows");
            Set<String> rowPaths=new HashSet<>();boolean repeated=false;
            for(JsonNode part:tables.get(key)){
                if(!rowPaths.add(part.path("sourcePosition").path("nodePath").asText()))repeated=true;
                members.add(part.path("blockId").asText());parts.add(part.deepCopy());
                ObjectNode span=object();for(String field:Arrays.asList("blockId","startOffset","endOffset","offsetUnit","sourceSha256","sourcePosition"))span.set(field,part.path(field).deepCopy());spans.add(span);
                ObjectNode row=object();row.put("blockId",part.path("blockId").asText());row.put("location",part.path("anchor").asText());
                row.set("cells",part.path("cells").deepCopy());row.set("wordCells",part.path("wordCells").deepCopy());row.set("sourcePosition",part.path("sourcePosition").deepCopy());rows.add(row);
            }
            if(repeated){unknowns.add(unknown("table."+key.get(2),"unknown","duplicate_actual_row_position"));continue;}
            unit.set("tableGroup",table);unit.putArray("unknowns").add(unknown("table_semantics","unknown","blank_grid_merge_gaps_and_applicability_not_source_accepted"));units.add(unit);
        }
        return out;
    }

    /** The legacy selector remains the gate, including for mixed/story carrier context. */
    static JsonNode selectedForModel(JsonNode graph,Chunk chunk,NativeDocxTableIndex index) {
        JsonNode legacy=selectedForModel(graph,chunk);
        index.requireCurrent(graph);
        if(!index.present)return legacy;
        ObjectNode out=(ObjectNode)legacy.deepCopy();
        out.put("status","observed_model_only_native_docx_table_context");out.putArray("unknowns");
        ArrayNode units=out.putArray("units");Set<NativeDocxTableIndex.Member> roots=new LinkedHashSet<>();
        for(Part part:chunk.getParts())roots.addAll(index.carriers.getOrDefault(part.getBlockId(),Collections.emptySet()));
        for(NativeDocxTableIndex.Member root:roots)units.add(index.unit(root));
        return out;
    }

    /** Request-local inventory join. It observes saved producer facts; it never reads original XML. */
    static final class NativeDocxTableIndex {
        static final String PROJECTION="ordinary-native-docx-table-tree-model-v1";
        private static final String WORD="http://schemas.openxmlformats.org/wordprocessingml/2006/main",STRICT="http://purl.oclc.org/ooxml/wordprocessingml/main";
        private static final Pattern SEGMENT=Pattern.compile("/\\{([^}]*)\\}([^/\\[\\]]+)\\[([1-9][0-9]*)\\]");
        private final SourceDocument source;
        private final List<Object> binding;
        private JsonNode graph;
        private final JsonNode inventory;
        private final boolean present;
        private boolean initialized;
        private final Map<String,Set<Member>> carriers=new HashMap<>();
        private final Map<Member,ObjectNode> units=new IdentityHashMap<>();
        private final Map<Member,List<Member>> trees=new IdentityHashMap<>();

        NativeDocxTableIndex(SourceDocument source){
            this.source=source;binding=state(source);
            JsonNode coverage=JsonUtils.isBlankText(source.getParseCoverageJson())?object():JsonUtils.parse(source.getParseCoverageJson());
            present=coverage.has("nativeDocxTables");inventory=coverage.get("nativeDocxTables");
        }
        NativeDocxTableIndex(SourceDocument source,JsonNode graph){this(source);this.graph=graph;}
        boolean present(){return present;}
        private static List<Object> state(SourceDocument s){
            // String references and scalar values only, rather than a second complete source JSON tree.
            return Arrays.asList(s.getId(),s.getProjectId(),s.getCategory(),s.getFileKey(),s.getReviewRole(),s.getDocumentScope(),s.getFileName(),s.getSourceSha256(),
                    s.getOerIdentityJson(),s.getTextContent(),s.getStructuredContentJson(),s.getParseCoverageJson(),s.getNormalCandidateJson(),s.getParseStatus(),s.getNativePdfEvidenceJson());
        }
        private static void valid(boolean value){if(!value)throw new IllegalArgumentException("Ordinary native table inventory invalid or stale");}
        private void requireCurrent(JsonNode current){
            valid((graph==null||current==graph)&&binding.equals(state(source)));
            if(graph==null)graph=current;
            if(initialized)return;
            if(present)load(inventory);
            initialized=true;
        }
        private static final class Path {
            final List<String> prefixes=new ArrayList<>(),names=new ArrayList<>(),namespaces=new ArrayList<>();
            final List<String> siblingKeys=new ArrayList<>();final List<java.math.BigInteger> ordinals=new ArrayList<>();
            Path(String raw){Matcher matcher=SEGMENT.matcher(raw);int cursor=0;
                while(matcher.find()){valid(matcher.start()==cursor);siblingKeys.add(raw.substring(0,cursor)+"/{"+matcher.group(1)+"}"+matcher.group(2));ordinals.add(new java.math.BigInteger(matcher.group(3)));cursor=matcher.end();prefixes.add(raw.substring(0,cursor));names.add(matcher.group(2));namespaces.add(matcher.group(1));}
                valid(cursor==raw.length()&&!prefixes.isEmpty());
            }
            String name(){return names.get(names.size()-1);}
            String namespace(){return namespaces.get(namespaces.size()-1);}
            String owner(String name){for(int i=names.size()-2;i>=0;i--)if(namespace().equals(namespaces.get(i))&&name.equals(names.get(i)))return prefixes.get(i);return null;}
        }
        private static final class Member {
            final JsonNode raw;final String part,sha,path,kind;final Path nativePath;
            JsonNode loadedRoot;
            Member table,row,parentCell,root;final List<Member> rows=new ArrayList<>(),cells=new ArrayList<>();
            final ArrayNode retained=JsonUtils.mapper().createArrayNode(),observations=JsonUtils.mapper().createArrayNode();
            Member(JsonNode raw,String part,String sha){this.raw=raw;this.part=part;this.sha=sha;path=raw.path("nodePath").asText();kind=raw.path("kind").asText();nativePath=new Path(path);}
        }
        private void load(JsonNode inventory){
            valid(inventory!=null&&inventory.isObject()&&"docx-native-tables-v1".equals(inventory.path("version").asText())
                    &&"docx_native".equals(inventory.path("kind").asText())&&source.getSourceSha256()!=null&&source.getSourceSha256().matches("[a-f0-9]{64}")
                    &&source.getSourceSha256().equals(inventory.path("sourceSha256").asText())&&"observed_loaded_native_roots".equals(inventory.path("status").asText())
                    &&"normal_loaded_body_header_footer_footnote_endnote_roots_only".equals(inventory.path("scope").asText())
                    &&"package_stories_or_roots_not_loaded_by_normal_parser_including_unsupported_stories".equals(inventory.path("unobservedScope").asText())
                    &&inventory.path("semanticScopeVerified").isBoolean()&&!inventory.path("semanticScopeVerified").asBoolean()
                    &&"unknown".equals(inventory.path("effectiveContentStatus").asText())&&inventory.path("parts").isArray());
            List<DocumentBlock> blocks=VettingCorpus.blocks(source);Map<String,DocumentBlock> ids=new HashMap<>();Set<List<Integer>> cellJoins=new HashSet<>();Set<Integer> rowJoins=new HashSet<>();Set<String> partNames=new HashSet<>();
            for(DocumentBlock block:blocks)valid(block.getId()!=null&&ids.putIfAbsent(block.getId(),block)==null);
            for(JsonNode part:inventory.path("parts")){
                String name=part.path("partName").asText(),sha=part.path("partSha256").asText();
                valid(part.isObject()&&!name.isEmpty()&&!name.startsWith("/")&&partNames.add(name)&&sha.matches("[a-f0-9]{64}")&&part.path("loadedRoots").isArray()&&part.path("members").isArray());
                Map<String,JsonNode> loaded=new HashMap<>();
                for(JsonNode root:part.path("loadedRoots")){
                    String path=root.path("nodePath").asText();Path p=new Path(path);String kind=root.path("kind").asText();
                    String local="header".equals(kind)?"hdr":"footer".equals(kind)?"ftr":kind;
                    valid(root.isObject()&&Arrays.asList("body","header","footer","footnote","endnote").contains(kind)&&local.equals(p.name())
                            &&(WORD.equals(p.namespace())||STRICT.equals(p.namespace()))&&loaded.putIfAbsent(path,root)==null);
                    boolean note="footnote".equals(kind)||"endnote".equals(kind);
                    valid(note?root.path("noteId").isTextual()&&root.path("noteId").asText().matches("-?(0|[1-9][0-9]*)"):!root.has("noteId"));
                }
                Map<String,Member> members=new LinkedHashMap<>();Set<String> departed=new HashSet<>(),encountered=new HashSet<>();List<String> previous=Collections.emptyList();Map<String,java.math.BigInteger> ordinals=new HashMap<>();
                for(JsonNode raw:part.path("members")){
                    Member m=new Member(raw,name,sha);String local="table".equals(m.kind)?"tbl":"row".equals(m.kind)?"tr":"cell".equals(m.kind)?"tc":"";
                    valid(raw.isObject()&&local.equals(m.nativePath.name())&&(WORD.equals(m.nativePath.namespace())||STRICT.equals(m.nativePath.namespace()))
                            &&raw.path("retained").isArray()&&members.putIfAbsent(m.path,m)==null);
                    for(int i=0;i<m.nativePath.prefixes.size()-1;i++)if(loaded.containsKey(m.nativePath.prefixes.get(i))){valid(m.loadedRoot==null);m.loadedRoot=loaded.get(m.nativePath.prefixes.get(i));}
                    valid(m.loadedRoot!=null);
                    // A path may not re-enter an already departed subtree. QName URIs contain '/', so use parsed segments.
                    int common=0;while(common<previous.size()&&common<m.nativePath.prefixes.size()&&previous.get(common).equals(m.nativePath.prefixes.get(common)))common++;
                    for(int i=common;i<previous.size();i++)departed.add(previous.get(i));
                    for(String prefix:m.nativePath.prefixes)valid(!departed.contains(prefix));previous=m.nativePath.prefixes;
                    for(int i=0;i<m.nativePath.prefixes.size();i++)if(encountered.add(m.nativePath.prefixes.get(i))){
                        java.math.BigInteger prior=ordinals.put(m.nativePath.siblingKeys.get(i),m.nativePath.ordinals.get(i));valid(prior==null||prior.compareTo(m.nativePath.ordinals.get(i))<0);
                    }
                    relation(raw,"table".equals(m.kind)?"parentTableNodePath":"tableNodePath",m.nativePath.owner("tbl"));
                    if(!"row".equals(m.kind))relation(raw,"parentCellNodePath",m.nativePath.owner("tc"));
                    if("cell".equals(m.kind))relation(raw,"rowNodePath",m.nativePath.owner("tr"));
                    m.table=owner(members,m.nativePath.owner("tbl"),"table");
                    m.parentCell=owner(members,m.nativePath.owner("tc"),"cell");
                    valid((m.table==null||m.table.loadedRoot==m.loadedRoot)&&(m.parentCell==null||m.parentCell.loadedRoot==m.loadedRoot));
                    if("table".equals(m.kind)){
                        m.root=m.table==null?m:m.table.root;trees.computeIfAbsent(m.root,key->new ArrayList<>()).add(m);
                    }else if("row".equals(m.kind)){
                        if(m.table!=null){m.root=m.table.root;m.table.rows.add(m);}
                    }else{
                        m.row=owner(members,m.nativePath.owner("tr"),"row");
                        if(m.row!=null)m.row.cells.add(m);if(m.table!=null)m.root=m.table.root;
                        valid((m.table==null||m.row!=null)&&(m.row==null||m.row.table==m.table));
                    }
                    for(JsonNode retained:raw.path("retained")){
                        valid(retained.isObject()&&retained.path("blockOrdinalZeroBased").isIntegralNumber()&&retained.path("blockOrdinalZeroBased").canConvertToInt());
                        int b=retained.path("blockOrdinalZeroBased").asInt();valid(b>=0&&b<blocks.size()&&!"table".equals(m.kind)&&m.root!=null);
                        DocumentBlock block=blocks.get(b);carrier(block,m);ObjectNode ref=object();ref.put("blockOrdinalZeroBased",b);ref.put("blockId",block.getId());
                        if("row".equals(m.kind)){
                            valid(retained.size()==1&&rowJoins.add(b));position(block.getSourcePosition(),m,false);m.retained.add(ref);
                        }else{
                            valid(retained.size()==2&&retained.path("wordCellOrdinalZeroBased").isIntegralNumber()&&retained.path("wordCellOrdinalZeroBased").canConvertToInt());
                            int c=retained.path("wordCellOrdinalZeroBased").asInt();valid(block.getWordCells()!=null&&c>=0&&c<block.getWordCells().size()&&cellJoins.add(Arrays.asList(b,c))
                                    &&m.path.startsWith(block.getSourcePosition().path("nodePath").asText()+"/"));
                            DocumentBlock.WordCell cell=block.getWordCells().get(c);position(cell.getSourcePosition(),m,true);ref.put("wordCellOrdinalZeroBased",c);
                            ObjectNode observation=ref.deepCopy();observation.set("wordCell",JsonUtils.mapper().valueToTree(cell));m.observations.add(observation);
                        }
                        if(m.root!=null)carriers.computeIfAbsent(block.getId(),key->new LinkedHashSet<>()).add(m.root);
                    }
                }
            }
            // Every actual native row block and cell must have exactly one retained ordinal join.
            for(int b=0;b<blocks.size();b++)if("docx".equals(blocks.get(b).getSource())){
                JsonNode p=blocks.get(b).getSourcePosition();
                if(p!=null&&"docx_native".equals(p.path("kind").asText())){
                    Path path=new Path(p.path("nodePath").asText());
                    if("tr".equals(path.name())&&(WORD.equals(path.namespace())||STRICT.equals(path.namespace())))valid(rowJoins.contains(b));
                }
                List<DocumentBlock.WordCell> cells=blocks.get(b).getWordCells();if(cells==null)continue;
                for(int c=0;c<cells.size();c++)valid(cellJoins.contains(Arrays.asList(b,c)));
            }
        }
        private static void relation(JsonNode raw,String field,String expected){
            valid(expected==null?!raw.has(field):raw.path(field).isTextual()&&expected.equals(raw.path(field).asText()));
        }
        private static Member owner(Map<String,Member> members,String path,String kind){
            if(path==null)return null;Member owner=members.get(path);valid(owner!=null&&kind.equals(owner.kind));return owner;
        }
        private void position(JsonNode p,Member member,boolean cell){
            valid(p!=null&&"docx_native".equals(p.path("kind").asText())&&source.getSourceSha256().equals(p.path("sourceSha256").asText())
                    &&member.part.equals(p.path("partName").asText())&&member.sha.equals(p.path("partSha256").asText())&&member.path.equals(p.path("nodePath").asText()));
            if(cell){relation(p,"tableNodePath",member.nativePath.owner("tbl"));relation(p,"rowNodePath",member.nativePath.owner("tr"));relation(p,"parentCellNodePath",member.nativePath.owner("tc"));}
        }
        private void carrier(DocumentBlock block,Member member){
            JsonNode p=block.getSourcePosition();String root=member.loadedRoot.path("nodePath").asText();
            valid("docx".equals(block.getSource())&&p!=null&&"docx_native".equals(p.path("kind").asText())&&source.getSourceSha256().equals(p.path("sourceSha256").asText())
                    &&member.part.equals(p.path("partName").asText())&&member.sha.equals(p.path("partSha256").asText())
                    &&(root.equals(p.path("nodePath").asText())||p.path("nodePath").asText().startsWith(root+"/")));
            String kind=member.loadedRoot.path("kind").asText();
            if("footnote".equals(kind)||"endnote".equals(kind))valid(("word-"+kind+"/"+member.loadedRoot.path("noteId").asText()).equals(block.getLocation()));
        }
        private ObjectNode unit(Member root){
            return units.computeIfAbsent(root,key->{
                ObjectNode unit=declarations();unit.put("kind","ordinary_docx_native_table_tree");
                unit.put("unitId","ordinary-native-table-tree:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(PROJECTION,graph.path("generationSha256"),graph.path("source"),root.part,root.sha,root.path))));
                unit.put("closureStatus","complete_loaded_native_physical_table_tree");unit.put("qualifiersComplete","unknown");
                unit.put("selectionMeaning","Physical context of selected retained carrier; no inferred cell UTF16 hit or new quotation rights");
                ArrayNode required=unit.putArray("requiredMemberBlockIds");Set<String> seen=new LinkedHashSet<>();unit.putArray("parts");unit.putArray("sourceSpans");
                ObjectNode group=unit.putObject("tableGroup"),position=group.putObject("sourcePosition");
                position.put("kind","docx_native");position.put("sourceSha256",source.getSourceSha256());position.put("partName",root.part);position.put("partSha256",root.sha);position.put("nodePath",root.path);
                group.set("loadedRoot",root.loadedRoot.deepCopy());group.put("scope","normal_loaded_body_header_footer_footnote_endnote_roots_only");
                group.put("unobservedScope","package_stories_or_roots_not_loaded_by_normal_parser_including_unsupported_stories");group.put("semanticScopeVerified",false);group.put("effectiveContentStatus","unknown");
                group.put("cellTextMeaning","Exact retained WordCell channels; outer cell text may intrinsically include nested text. No concatenation or string deduplication.");
                ArrayNode tables=group.putArray("tables");for(Member table:trees.get(root)){
                    ObjectNode t=tables.addObject();copyIdentity(table,t,"parentTableNodePath","parentCellNodePath");t.set("retained",table.retained);ArrayNode rows=t.putArray("rows");
                    for(Member row:table.rows){ObjectNode r=rows.addObject();copyIdentity(row,r);r.set("retained",row.retained);for(JsonNode ref:row.retained)seen.add(ref.path("blockId").asText());ArrayNode cells=r.putArray("cells");
                        for(Member cell:row.cells){ObjectNode c=cells.addObject();copyIdentity(cell,c,"parentCellNodePath");c.set("observations",cell.observations);for(JsonNode ref:cell.observations)seen.add(ref.path("blockId").asText());}
                    }
                }
                seen.forEach(required::add);unit.putArray("unknowns").add(unknown("table_semantics","unknown","effective_revision_branch_rendered_grid_blank_use_owner_Notes_applicability_legal_and_semantic_closure_unverified"));return unit;
            });
        }
        private static void copyIdentity(Member member,ObjectNode out,String... fields){out.put("nodePath",member.path);for(String field:fields)if(member.raw.has(field))out.set(field,member.raw.get(field));}
    }

    private static List<String> wordTableKey(JsonNode part,String originalSha) {
        if(!originalSha.matches("[a-f0-9]{64}")||!"docx".equals(part.path("extractionSource").asText()))return null;
        JsonNode position=part.path("sourcePosition");String path=position.path("nodePath").asText();
        String ns="\\{http://schemas.openxmlformats.org/wordprocessingml/2006/main\\}";
        Matcher row=Pattern.compile("^(.*)/"+ns+"tr\\[([1-9][0-9]*)\\]$").matcher(path);
        if(!"docx_native".equals(position.path("kind").asText())||!Objects.equals(originalSha,position.path("sourceSha256").asText())
                ||!position.path("partSha256").asText().matches("[a-f0-9]{64}")||position.path("partName").asText().isEmpty()
                ||!row.matches()||!row.group(1).matches(".*/"+ns+"tbl\\[[1-9][0-9]*\\]"))return null;
        Matcher segments=Pattern.compile("/\\{[^}]*\\}([^/\\[\\]]+)\\[([1-9][0-9]*)\\]").matcher(row.group(1));
        StringBuilder location=new StringBuilder("word");int cursor=0;
        while(segments.find()){
            if(segments.start()!=cursor)return null;
            location.append('/').append(segments.group(1)).append('[').append(Long.parseLong(segments.group(2))-1).append(']');cursor=segments.end();
        }
        if(cursor!=row.group(1).length()||!part.path("cells").isArray()||part.path("wordCells").isEmpty()||part.path("cells").size()!=part.path("wordCells").size())return null;
        Set<Integer> cellIds=new HashSet<>();int rowIndex=Integer.parseInt(row.group(2))-1;
        int ordinal=0;
        for(JsonNode cell:part.path("wordCells")){
            if(!location.toString().equals(cell.path("tableLocation").asText())||cell.path("rowIndex").asInt(-1)!=rowIndex
                    ||!cell.path("cellIndex").isIntegralNumber()||cell.path("cellIndex").asInt()<0||!cellIds.add(cell.path("cellIndex").asInt())
                    ||cell.path("cellIndex").asInt()!=ordinal||!cell.path("text").isTextual()||!cell.path("text").equals(part.path("cells").get(ordinal))
                    ||!(part.path("anchor").asText()+"/cell/"+ordinal).equals(cell.path("location").asText()))return null;
            ordinal++;
        }
        return Arrays.asList(position.path("partName").asText(),position.path("partSha256").asText(),location.toString(),row.group(1));
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
        if(coverage.has("nativeDocxOutline"))graph.set("nativeDocxOutline",coverage.get("nativeDocxOutline").deepCopy());
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
        if(!block.getWordTextSpans().isEmpty())part.set("wordTextSpans",JsonUtils.mapper().valueToTree(block.getWordTextSpans()));
        if(!block.getWordFields().isEmpty())part.set("wordFields",JsonUtils.mapper().valueToTree(block.getWordFields()));
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
        JsonNode raw=JsonUtils.mapper().valueToTree(cell);ObjectNode compact=object();for(String key:Arrays.asList("location","tableLocation","verticalMerge","horizontalMerge","topologyStatus","gridSpanRaw","gridBeforeRaw","rowIndex","cellIndex","gridColumn","columnSpan","rowSpan","text","limitations","sourcePosition"))if(raw.has(key))compact.set(key,raw.get(key));out.add(compact);
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
