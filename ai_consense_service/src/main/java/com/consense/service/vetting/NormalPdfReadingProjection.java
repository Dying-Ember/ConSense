package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.DocumentParser;
import com.consense.document.PdfPageEvidence.PdfFormField;
import com.consense.document.PdfPageEvidence.PdfGlyph;
import com.consense.domain.SourceDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Parser-derived ordinary observations; they do not confer source-reviewed or canonical authority. */
public final class NormalPdfReadingProjection {
    public static final String FIELD="normalPdfReadingObservation";
    public static final String SCHEMA="consense.normal-pdf-reading-observation.v1";
    public static final String VERSION="ordinary-native-pdf-reading-compact-v1";
    private NormalPdfReadingProjection() {}

    public static boolean capture(SourceDocument source,DocumentParser.ParsedDocument parsed,String sha){
        boolean observedPdf=parsed!=null&&"IDENTITY_OBSERVED".equals(parsed.getParseStatus())&&parsed.getRoutingObservation()!=null&&"pdf".equals(parsed.getRoutingObservation().path("format").asText());
        JsonNode plan=parsed==null||parsed.getCoverage()==null?JsonUtils.mapper().nullNode():parsed.getCoverage().getReadingPlan();
        boolean ordinaryPdf=plan!=null&&"oer-reading-plan-v1".equals(plan.path("schemaVersion").asText())&&"ordinary_native_first".equals(plan.path("readingMode").asText())
                &&"deferred_native_first".equals(plan.path("optionalReadingStatus").asText())&&Objects.equals(sha,plan.path("sourceSha256").asText())&&contains(plan.path("tasks"),"native_text_and_page_frame");
        if(parsed==null||!observedPdf&&!ordinaryPdf&&(parsed.getNativePdfEvidence()==null||parsed.getNativePdfEvidence().isEmpty()))return false;
        need(sha!=null&&sha.equals(parsed.getSourceSha256()),"original_identity");
        List<DocumentBlock> rich=parsed.getBlocks(),compact=new ArrayList<>();
        ObjectNode coverage=JsonUtils.mapper().valueToTree(parsed.getCoverage());
        JsonNode groups=NormalPdfSourceGroups.buildReading(rich,sha,coverage);
        compactSyntax(groups,rich);
        for(DocumentBlock block:rich)compact.add(compact(block));
        ObjectNode observation=coverage.putObject(FIELD);
        observation.put("schema",SCHEMA).put("version",VERSION).put("sourceSha256",sha);
        observation.put("blocksSha256",JsonUtils.serializedSha256(compact));
        observation.put("semanticScopeVerified",false).put("sourceQualityAccepted",false).put("humanConfirmed",false);
        observation.put("meaning","Derived parser reading observations; raw glyph inference is not independently replayed; original PDF remains the recovery source");
        observation.set("sourceGroups",groups);
        observation.put("sourceGroupsSha256",JsonUtils.serializedSha256(groups));
        observation.set("memberWitnesses",witnesses(compact));
        observation.set("textDecodingWitnesses",decodingWitnesses(rich));
        validate(compact,sha,coverage);
        source.setStructuredContentJson(JsonUtils.write(compact));source.setParseCoverageJson(JsonUtils.write(coverage));
        return true;
    }

    private static DocumentBlock compact(DocumentBlock b){
        DocumentBlock c=new DocumentBlock();c.setId(b.getId());c.setKind(b.getKind());c.setLocation(b.getLocation());c.setPageNo(b.getPageNo());
        c.setText(b.getText());c.setOriginalText(b.getOriginalText());c.setDeletedText(b.getDeletedText());c.setStrikeText(b.getStrikeText());
        c.setCells(b.getCells());c.setBbox(b.getBbox());c.setConfidence(b.getConfidence());c.setSource(b.getSource());
        c.setSourcePosition(b.getSourcePosition());c.setNativeVisibilityStatus(b.getNativeVisibilityStatus());c.setReadingContext(b.getReadingContext());
        c.setNativeTableCandidate(b.getNativeTableCandidate());c.setParserPageGroup(b.getParserPageGroup());
        c.setVisualRegion(b.getVisualRegion());c.setVisualFrame(b.getVisualFrame());c.setVisualProvenance(b.getVisualProvenance());
        if(b.getPdfFormField()!=null){PdfFormField f=b.getPdfFormField(),v=new PdfFormField();
            v.setId(f.getId());v.setFieldName(f.getFieldName());v.setFieldType(f.getFieldType());v.setFieldValue(f.getFieldValue());
            v.setAppearanceValue(f.getAppearanceValue());v.setAppearanceState(f.getAppearanceState());v.setVisibilityStatus(f.getVisibilityStatus());
            v.setValueStatus(f.getValueStatus());v.setPageNo(f.getPageNo());v.setBbox(f.getBbox());v.setNeedAppearances(f.isNeedAppearances());
            v.setAppearancePresent(f.isAppearancePresent());v.setConflict(f.isConflict());v.setLimitations(f.getLimitations());c.setPdfFormField(v);
            v.setAnnotationFlags(f.getAnnotationFlags());v.setRectanglePdf(f.getRectanglePdf());v.setAppearanceBbox(f.getAppearanceBbox());v.setAppearanceMatrix(f.getAppearanceMatrix());
            v.setAppearanceStreamObservationStatus(f.getAppearanceStreamObservationStatus());v.setAppearanceGlyphObservationStatus(f.getAppearanceGlyphObservationStatus());
        }return c;
    }

    private static JsonNode witnesses(List<DocumentBlock> blocks){
        com.fasterxml.jackson.databind.node.ArrayNode out=JsonUtils.mapper().createArrayNode();
        for(DocumentBlock b:blocks){ObjectNode row=out.addObject();row.put("blockId",b.getId()).put("textSha256",VettingCorpus.hash(b.getText()==null?"":b.getText()));
            row.put("utf16Length",b.getText()==null?0:b.getText().length());row.put("physicalPageNo",b.getPageNo());row.set("bbox",JsonUtils.mapper().valueToTree(b.getBbox()));}
        return out;
    }

    /** Only derived literal spans survive; raw glyph/operator inventories remain transient. */
    private static void compactSyntax(JsonNode inventory,List<DocumentBlock> blocks){
        Map<String,ObjectNode> glyphs=new HashMap<>();Map<String,String> texts=new HashMap<>();Map<String,Integer> order=new HashMap<>();Set<String> selected=new HashSet<>(),duplicates=new HashSet<>();
        for(JsonNode group:inventory.path("groups"))for(JsonNode observation:group.path("nativeSyntaxObservations"))for(String field:Arrays.asList("glyphIds","excludedMarginGlyphIds"))for(JsonNode id:observation.path(field))selected.add(id.asText());
        int ordinal=0;for(DocumentBlock block:blocks){texts.put(block.getId(),nvl(block.getText()));order.put(block.getId(),ordinal++);}
        for(DocumentBlock block:blocks){String text=nvl(block.getText());int cursor=0;
            for(PdfGlyph glyph:block.getPdfGlyphs()){
                String literal=nvl(glyph.readerText());if(literal.codePoints().allMatch(NormalPdfReadingProjection::space)){if(selected.contains(glyph.getId()))glyphs.put(glyph.getId(),JsonUtils.mapper().createObjectNode().put("separatorOnly",true));continue;}
                while(cursor<text.length()&&space(text.codePointAt(cursor)))cursor+=Character.charCount(text.codePointAt(cursor));
                int start=cursor;boolean exact=true;
                for(int index=0;index<literal.length();){int cp=literal.codePointAt(index);index+=Character.charCount(cp);if(space(cp))continue;
                    while(cursor<text.length()&&space(text.codePointAt(cursor)))cursor+=Character.charCount(text.codePointAt(cursor));
                    if(cursor>=text.length()||text.codePointAt(cursor)!=cp){exact=false;break;}cursor+=Character.charCount(cp);
                }
                if(!exact)break;
                if(!selected.contains(glyph.getId())||duplicates.contains(glyph.getId()))continue;
                ObjectNode span=JsonUtils.mapper().createObjectNode();span.put("blockId",block.getId()).put("startOffset",start).put("endOffset",cursor).put("offsetUnit","utf16").put("literal",text.substring(start,cursor));
                if(glyphs.putIfAbsent(glyph.getId(),span)!=null){glyphs.remove(glyph.getId());duplicates.add(glyph.getId());}
            }
        }
        for(JsonNode group:inventory.path("groups"))for(JsonNode raw:group.path("nativeSyntaxObservations")){
            ObjectNode observation=(ObjectNode)raw;ArrayNode spans=literalSpans(observation.path("glyphIds"),glyphs,texts),excluded=literalSpans(observation.path("excludedMarginGlyphIds"),glyphs,texts);
            observation.remove(Arrays.asList("glyphIds","excludedMarginGlyphIds"));observation.put("offsetUnit","utf16_per_source_span");
            boolean exact=spans!=null&&excluded!=null&&normal(joinSpans(spans)).equals(normal(observation.path("observedText").asText()));
            observation.put("rangeStatus",exact?"exact_current_member_literal_segments":"unknown_discarded_glyph_ranges");
            observation.set("sourceSpans",spans==null?JsonUtils.mapper().createArrayNode():spans);observation.set("excludedMarginSpans",excluded==null?JsonUtils.mapper().createArrayNode():excluded);
            if(!exact){((ObjectNode)group).put("scopeComplete",false).put("status","partial_unknown_scope");ArrayNode unknown=(ArrayNode)group.path("unknownReasons");String reason="compact_source_segment_ranges_unknown";if(!contains(unknown,reason))unknown.add(reason);}
        }
        for(JsonNode group:inventory.path("groups"))if(Arrays.asList("source_clause","parent_tail").contains(group.path("kind").asText())){
            int previous=-1;boolean ordered=true;for(JsonNode id:group.path("bodyBlockIds")){Integer current=order.get(id.asText());if(current==null||current<=previous)ordered=false;else previous=current;}
            if(group.path("endBoundary").has("blockId")){Integer end=order.get(group.path("endBoundary").path("blockId").asText());ordered&=end!=null&&end>previous;}
            if(!ordered){((ObjectNode)group).put("scopeComplete",false).put("status","partial_unknown_scope");ArrayNode unknown=(ArrayNode)group.path("unknownReasons");if(!contains(unknown,"compact_source_block_order_or_boundary_unknown"))unknown.add("compact_source_block_order_or_boundary_unknown");}
        }
    }
    private static ArrayNode literalSpans(JsonNode ids,Map<String,ObjectNode> glyphs,Map<String,String> texts){
        ArrayNode out=JsonUtils.mapper().createArrayNode();ObjectNode current=null;
        for(JsonNode id:ids){ObjectNode span=glyphs.get(id.asText());if(span==null)return null;if(span.path("separatorOnly").asBoolean())continue;
            if(current!=null&&current.path("blockId").equals(span.path("blockId"))&&span.path("startOffset").asInt()>=current.path("endOffset").asInt()){
                String text=texts.get(current.path("blockId").asText()),between=text.substring(current.path("endOffset").asInt(),span.path("startOffset").asInt());
                if(between.codePoints().allMatch(NormalPdfReadingProjection::space)){current.put("endOffset",span.path("endOffset").asInt()).put("literal",text.substring(current.path("startOffset").asInt(),span.path("endOffset").asInt()));continue;}
            }current=span.deepCopy();out.add(current);
        }return out;
    }
    private static JsonNode decodingWitnesses(List<DocumentBlock> blocks){
        ArrayNode out=JsonUtils.mapper().createArrayNode();
        for(DocumentBlock block:blocks)for(PdfGlyph glyph:block.getPdfGlyphs())if(glyph.getTextResolution()!=null){
            ObjectNode row=out.addObject();row.put("blockId",block.getId()).put("glyphId",glyph.getId()).put("rawUnicode",glyph.getUnicode()).put("readerText",glyph.readerText());
            row.put("fontName",glyph.getFontName()).put("fontSubtype",glyph.getFontSubtype());row.set("fontEmbedded",JsonUtils.mapper().valueToTree(glyph.getFontEmbedded()));
            row.set("characterCodes",JsonUtils.mapper().valueToTree(glyph.getCharacterCodes()));row.set("textResolution",JsonUtils.mapper().valueToTree(glyph.getTextResolution()));
            row.put("meaning","Sparse original font-decoding observation; original and reader block channels remain literal; no source-quality acceptance");
        }return out;
    }

    /** Rebuild graph inputs from the current compact source, never from a saved candidate graph. */
    static JsonNode validate(List<DocumentBlock> blocks,String sha,JsonNode coverage){
        JsonNode value=coverage.path(FIELD);
        need(SCHEMA.equals(value.path("schema").asText())&&VERSION.equals(value.path("version").asText())&&sha!=null&&sha.equals(value.path("sourceSha256").asText()),"recipe_or_source");
        need(value.path("semanticScopeVerified").isBoolean()&&!value.path("semanticScopeVerified").asBoolean()
                &&value.path("sourceQualityAccepted").isBoolean()&&!value.path("sourceQualityAccepted").asBoolean()
                &&value.path("humanConfirmed").isBoolean()&&!value.path("humanConfirmed").asBoolean(),"authority");
        need(JsonUtils.serializedSha256(blocks).equals(value.path("blocksSha256").asText())&&witnesses(blocks).equals(value.path("memberWitnesses")),"current_blocks");
        Map<String,DocumentBlock> members=new LinkedHashMap<>();
        for(DocumentBlock block:blocks)need(block.getId()!=null&&members.putIfAbsent(block.getId(),block)==null&&block.getPdfGlyphs()!=null&&block.getPdfGlyphs().isEmpty(),"duplicate_or_raw_member");
        Map<String,Integer> order=new HashMap<>();int ordinal=0;for(String id:members.keySet())order.put(id,ordinal++);
        JsonNode inventory=value.path("sourceGroups");
        need(JsonUtils.serializedSha256(inventory).equals(value.path("sourceGroupsSha256").asText()),"group_fingerprint");
        need("native-pdf-source-groups-v1".equals(inventory.path("version").asText())&&sha.equals(inventory.path("sourceSha256").asText())
                &&inventory.path("groups").isArray()&&inventory.path("relations").isArray()&&inventory.path("issues").isArray()&&inventory.path("contextBlocks").isArray(),"inventory");
        Map<String,JsonNode> groups=new LinkedHashMap<>();
        for(JsonNode group:inventory.path("groups")){
            String id=group.path("id").asText(),kind=group.path("kind").asText();
            need(!id.isEmpty()&&groups.putIfAbsent(id,group)==null&&Arrays.asList("source_clause","parent_tail","source_margin_note","native_table_method_group").contains(kind),"group_identity");
            need(group.path("semanticScopeVerified").isBoolean()&&!group.path("semanticScopeVerified").asBoolean()
                    &&group.path("automaticEditEligible").isBoolean()&&!group.path("automaticEditEligible").asBoolean()&&"unknown".equals(group.path("legalClosure").asText()),"group_authority");
            Set<String> body=ids(group.path("orderedBlockIds"),members),context=ids(group.path("contextBlockIds"),members),required=ids(group.path("requiredMemberBlockIds"),members);
            need(group.path("orderedBlockIds").equals(group.path("bodyBlockIds")),"body_order");Set<String> all=new HashSet<>(body);all.addAll(context);need(all.equals(required)&&!all.isEmpty(),"complete_membership");
            Set<Integer> pages=new HashSet<>();for(JsonNode page:group.path("physicalPageNos"))need(page.isInt()&&page.asInt()>0&&pages.add(page.asInt()),"pages");
            for(String member:all)need(pages.contains(members.get(member).getPageNo()),"member_page");
            if(!"native_table_method_group".equals(kind))position(group.path("sourcePosition"),sha,members);
            need(group.path("unknownReasons").isArray()&&group.path("scopeComplete").isBoolean()&&group.path("relations").isArray(),"group_state");
            if(group.has("humanConfirmed"))need(group.path("humanConfirmed").isBoolean()&&!group.path("humanConfirmed").asBoolean(),"group_authority");
            if("source_clause".equals(kind)||"parent_tail".equals(kind)){
                Set<String> own=ids(group.path("observedOwnBlockIds"),members);need(!own.isEmpty()&&body.containsAll(own)&&own.iterator().next().equals(body.iterator().next()),"own_members");
                boundary(group.path("startBoundary"),sha,members,false);boundary(group.path("endBoundary"),sha,members,true);
                need(!body.isEmpty()&&body.iterator().next().equals(group.path("startBoundary").path("blockId").asText())&&group.path("sourcePosition").equals(group.path("startBoundary").path("sourcePosition")),"start_member");
                if(group.path("scopeComplete").asBoolean()){
                    need(group.path("unknownReasons").isArray()&&group.path("unknownReasons").isEmpty(),"complete_unknown");
                    if("observed_input_end_unverified".equals(group.path("endBoundary").path("kind").asText()))need(false,"unverified_end");
                    if("complete_observed_native_input_end".equals(group.path("endBoundary").path("kind").asText()))need(nativeEnd(coverage,members),"incomplete_input_end");
                    List<Integer> ordered=new ArrayList<>(pages);Collections.sort(ordered);for(int i=1;i<ordered.size();i++)need(ordered.get(i)==ordered.get(i-1)+1,"page_gap");
                    int previous=-1;for(String member:body){int current=order.get(member);need(current>previous,"body_source_order");previous=current;}
                    if(group.path("endBoundary").has("blockId"))need(order.get(group.path("endBoundary").path("blockId").asText())>previous,"exclusive_end_before_body");
                }
            }
            if("native_table_method_group".equals(kind)){
                method(group,sha,members,context);
            }
            for(JsonNode observation:group.path("nativeSyntaxObservations")){
                need(body.contains(observation.path("blockId").asText())&&members.get(observation.path("blockId").asText()).getPageNo()==observation.path("physicalPageNo").asInt(),"syntax_member");
                spans(observation.path("sourceSpans"),members,body);spans(observation.path("excludedMarginSpans"),members,members.keySet());
                if("exact_current_member_literal_segments".equals(observation.path("rangeStatus").asText()))need(normal(joinSpans(observation.path("sourceSpans"))).equals(normal(observation.path("observedText").asText())),"syntax_literal");
                else need(!group.path("scopeComplete").asBoolean()&&contains(group.path("unknownReasons"),"compact_source_segment_ranges_unknown"),"syntax_unknown");
            }
        }
        for(JsonNode group:groups.values()){
            Set<String> seen=new HashSet<>();JsonNode child=group;
            while(!child.path("parentId").asText("").isEmpty()){
                String parent=child.path("parentId").asText("");need(seen.add(parent)&&groups.containsKey(parent),"parent_cycle_or_missing");
                JsonNode owner=groups.get(parent);if(Arrays.asList("source_clause","parent_tail").contains(child.path("kind").asText()))need(ids(owner.path("bodyBlockIds"),members).containsAll(ids(child.path("bodyBlockIds"),members)),"parent_membership");child=owner;
            }
        }
        for(JsonNode row:inventory.path("contextBlocks")){DocumentBlock block=members.get(row.path("blockId").asText());need(block!=null&&!row.path("role").asText().isEmpty()&&(!row.has("physicalPageNo")||Objects.equals(block.getPageNo(),row.path("physicalPageNo").asInt())),"context_member");}
        for(JsonNode issue:inventory.path("issues"))need(!issue.path("reason").asText().isEmpty()&&(!issue.has("blockId")||members.containsKey(issue.path("blockId").asText())),"issue_member");
        for(JsonNode relation:inventory.path("relations"))relation(relation,groups,members,sha);
        for(JsonNode group:groups.values())if(!"native_table_method_group".equals(group.path("kind").asText())){
            ArrayNode expected=JsonUtils.mapper().createArrayNode();String id=group.path("id").asText();for(JsonNode edge:inventory.path("relations")){String type=edge.path("type").asText();if((Arrays.asList("source_parent","source_parent_tail").contains(type)&&id.equals(edge.path("to").asText()))||("source_literal_reference".equals(type)&&id.equals(edge.path("from").asText()))||("source_explicit_note_target".equals(type)&&(id.equals(edge.path("targetGroupId").asText())||"source_margin_note".equals(group.path("kind").asText())&&group.path("sourcePosition").path("blockId").equals(edge.path("fromBlockId")))))expected.add(edge);}
            need(expected.equals(group.path("relations")),"group_relations");
        }
        need(value.path("textDecodingWitnesses").isArray(),"decoding_inventory");Set<String> decoded=new HashSet<>();
        for(JsonNode row:value.path("textDecodingWitnesses")){
            DocumentBlock block=members.get(row.path("blockId").asText());need(block!=null&&decoded.add(row.path("glyphId").asText())&&row.path("glyphId").asText().startsWith("pdf-page/"+block.getPageNo()+"/glyph/"),"decoding_member");
            PdfGlyph glyph=new PdfGlyph();glyph.setUnicode(row.path("rawUnicode").asText());glyph.setFontName(row.path("fontName").asText(null));glyph.setFontSubtype(row.path("fontSubtype").asText(null));glyph.setFontEmbedded(row.path("fontEmbedded").isBoolean()?row.path("fontEmbedded").asBoolean():null);
            glyph.setCharacterCodes(JsonUtils.mapper().convertValue(row.path("characterCodes"),int[].class));glyph.setTextResolution(JsonUtils.mapper().convertValue(row.path("textResolution"),com.consense.document.PdfPageEvidence.TextResolution.class));
            need(Objects.equals(glyph.readerText(),row.path("readerText").asText())&&nvl(block.getOriginalText()).contains(nvl(glyph.getUnicode()))&&nvl(block.getText()).contains(nvl(glyph.readerText())),"decoding_literal");
        }
        return inventory.deepCopy();
    }
    /** Bounded display of existing original-cell observations, without inferred headers or cross-page ownership. */
    static List<Map<String,Object>> tableReadings(JsonNode inventory,int page){
        List<Map<String,Object>> result=new ArrayList<>();int characters=0;
        for(JsonNode group:inventory.path("groups"))if("native_table_method_group".equals(group.path("kind").asText())&&group.path("methodBinding").path("physicalPageNo").asInt()==page){
            JsonNode table=group.path("methodTable");
            if(!"none_original_physical_cell_literals".equals(table.path("textTransform").asText()))continue;
            need(NormalPdfMethodGroups.wholePhysicalGrid(table,group.path("methodPhysicalTable")),"reader_grid");
            need(result.size()<10&&table.path("rowCount").asInt()<=100&&table.path("colCount").asInt()<=30&&table.path("origins").size()<=2000,"reader_table_limit");
            List<JsonNode> ordered=new ArrayList<>();table.path("origins").forEach(ordered::add);ordered.sort(Comparator.comparingInt((JsonNode cell)->cell.path("row").asInt()).thenComparingInt(cell->cell.path("col").asInt()));
            List<Map<String,Object>> cells=new ArrayList<>();for(JsonNode cell:ordered){
                characters+=cell.path("text").asText().length();need(characters<=200_000,"reader_table_text_limit");Map<String,Object> value=new LinkedHashMap<>();
                for(String key:Arrays.asList("row","col","rowSpan","colSpan"))value.put(key,cell.path(key).asInt());value.put("text",cell.path("text").asText());cells.add(value);
            }
            Map<String,Object> value=new LinkedHashMap<>();value.put("method",group.path("methodBinding").path("method").asText());value.put("physicalPage",page);value.put("rowCount",table.path("rowCount").asInt());value.put("colCount",table.path("colCount").asInt());value.put("cells",cells);value.put("continuationStatus","unknown");result.add(value);
        }return result;
    }
    private static Set<String> ids(JsonNode values,Map<String,DocumentBlock> members){need(values.isArray(),"member_array");Set<String> out=new LinkedHashSet<>();for(JsonNode id:values)need(id.isTextual()&&members.containsKey(id.asText())&&out.add(id.asText()),"member_identity");return out;}
    private static void boundary(JsonNode b,String sha,Map<String,DocumentBlock> members,boolean end){
        need(end?Arrays.asList("observed_input_end_unverified","complete_observed_native_input_end","next_source_peer_or_ancestor_marker","next_source_marker","next_observed_parent_content_tail","next_observed_parent_paragraph").contains(b.path("kind").asText()):"observed_literal_source_marker".equals(b.path("kind").asText()),"boundary_kind");
        if(end&&Arrays.asList("observed_input_end_unverified","complete_observed_native_input_end").contains(b.path("kind").asText())){need(sha.equals(b.path("sourceSha256").asText()),"end_source");return;}
        need(members.containsKey(b.path("blockId").asText())&&b.path("blockId").equals(b.path("sourcePosition").path("blockId")),"boundary_member");position(b.path("sourcePosition"),sha,members);
    }
    private static void position(JsonNode p,String sha,Map<String,DocumentBlock> members){DocumentBlock block=members.get(p.path("blockId").asText());need(block!=null&&sha.equals(p.path("sourceSha256").asText())&&block.getPageNo()!=null&&block.getPageNo()==p.path("physicalPageNo").asInt()&&"pdf_observed_source".equals(p.path("kind").asText())&&"normalized_rendered_crop_top_left".equals(p.path("coordinateSpace").asText())&&("pdf-page/"+block.getPageNo()+"/glyph").equals(p.path("glyphNamespace").asText()),"position");}
    private static void method(JsonNode group,String sha,Map<String,DocumentBlock> members,Set<String> context){
        String id=group.path("methodGroupRef").path("methodBlockId").asText(),tableId=group.path("methodGroupRef").path("tableId").asText();DocumentBlock carrier=members.get(id);
        need(carrier!=null&&context.contains(id)&&carrier.getNativeTableCandidate()!=null&&"native_table_candidate".equals(carrier.getSource()),"method_carrier");
        JsonNode raw=carrier.getNativeTableCandidate(),binding=group.path("methodBinding"),candidate=raw.path("candidate"),position=group.path("sourcePosition");
        need(sha.equals(raw.path("sourceSha256").asText())&&sha.equals(candidate.path("sourceSha256").asText())&&sha.equals(binding.path("sourceSha256").asText())&&sha.equals(position.path("sourceSha256").asText())&&Objects.equals(carrier.getPageNo(),raw.path("physicalPageNo").asInt())&&Objects.equals(carrier.getPageNo(),candidate.path("physicalPageNo").asInt())&&raw.path("physicalPageNo").equals(binding.path("physicalPageNo"))&&raw.path("physicalPageNo").equals(position.path("physicalPageNo"))&&VettingSourceMaterial.formSourceSha256(raw).equals(binding.path("actualMethodCandidateSha256").asText())&&"candidate_observed".equals(raw.path("status").asText())&&("pdfplumber/page/"+carrier.getPageNo()).equals(raw.path("nativeGlyphNamespace").asText()),"method_binding");
        for(String field:Arrays.asList("method","provider","frame","nativeGlyphNamespace"))need(raw.path(field).equals(binding.path(field))&&raw.path(field).equals(candidate.path(field)),"method_recipe");
        for(String[] ref:new String[][]{{"rawRef","rawResultSha256"},{"bundleRef","bundleSha256"},{"sourceObservationRef","sourceObservationSha256"}})need(raw.path(ref[0]).path("sha256").equals(binding.path(ref[1])),"method_descriptor");
        JsonNode table=unique(candidate.path("logicalTables"),"tableId",tableId),observed=unique(candidate.path("observationGroups").path("groups"),"tableId",tableId);
        need(table!=null&&observed!=null&&table.equals(group.path("methodTable"))&&observed.equals(group.path("methodObservation")),"method_table");
        need(group.path("methodPhysicalTable").equals(unique(candidate.path("tables"),"tableId",table.path("providerTableId").asText())),"method_physical_table");
        if("none_original_physical_cell_literals".equals(table.path("textTransform").asText()))need(NormalPdfMethodGroups.wholePhysicalGrid(table,group.path("methodPhysicalTable")),"method_physical_grid");
        need(group.path("id").asText().equals("pdf-method-group:"+VettingCorpus.hash(JsonUtils.write(Arrays.asList(sha,id,table.path("tableId"),VettingSourceMaterial.formSourceSha256(raw))))),"method_identity");
        Set<String> aligned=new LinkedHashSet<>();for(JsonNode alignment:group.path("alignments")){DocumentBlock line=members.get(alignment.path("blockId").asText());need(line!=null&&aligned.add(line.getId())&&Objects.equals(line.getPageNo(),carrier.getPageNo())&&alignment.path("glyphIdNamespacesJoined").isBoolean()&&!alignment.path("glyphIdNamespacesJoined").asBoolean(),"method_alignment");double[] box=line.getBbox();need(box!=null&&box.length==4,"method_line_bbox");double width=raw.path("frame").path("pageWidth").asDouble(),height=raw.path("frame").path("pageHeight").asDouble();double[] points={box[0]*width,box[1]*height,(box[0]+box[2])*width,(box[1]+box[3])*height};JsonNode actual=alignment.path("nativeLineBboxPoints");need(actual.isArray()&&actual.size()==4,"method_line_bbox");for(int n=0;n<4;n++)need(Math.abs(points[n]-actual.get(n).asDouble())<1e-6,"method_line_bbox");alignment(alignment,line,table,points);}
        need(new ArrayList<>(aligned).equals(new ArrayList<>(ids(group.path("bodyBlockIds"),members))),"method_alignment_members");
        ArrayNode expectedRelations=group.path("methodObservation").path("relations").deepCopy();
        for(JsonNode suffix:group.path("methodContinuationObservations")){
            DocumentBlock next=members.get(suffix.path("continuationMethodBlockId").asText());need(next!=null&&next.getNativeTableCandidate()!=null&&context.contains(next.getId()),"continuation_carrier");JsonNode nextRaw=next.getNativeTableCandidate(),continuation=nextRaw.path("candidate").path("observationGroups").path("continuations");
            JsonNode nextCandidate=nextRaw.path("candidate");need(Objects.equals(next.getPageNo(),carrier.getPageNo()+1)&&sha.equals(nextRaw.path("sourceSha256").asText())&&sha.equals(nextCandidate.path("sourceSha256").asText())&&sha.equals(continuation.path("sourceSha256").asText())&&Objects.equals(next.getPageNo(),nextCandidate.path("physicalPageNo").asInt())&&Objects.equals(next.getPageNo(),nextRaw.path("physicalPageNo").asInt())&&next.getPageNo()==suffix.path("physicalPageNo").asInt()&&Objects.equals(carrier.getPageNo(),continuation.path("anchorPhysicalPageNo").asInt())&&nextRaw.path("frame").equals(nextCandidate.path("frame"))&&"candidate_observed".equals(nextRaw.path("status").asText())&&("pdfplumber/page/"+next.getPageNo()).equals(nextRaw.path("nativeGlyphNamespace").asText())&&nextRaw.path("nativeGlyphNamespace").equals(nextCandidate.path("nativeGlyphNamespace"))&&VettingSourceMaterial.formSourceSha256(nextRaw).equals(suffix.path("continuationCandidateSha256").asText()),"continuation_identity");
            for(String field:Arrays.asList("method","provider"))need(nextRaw.path(field).equals(raw.path(field))&&nextRaw.path(field).equals(suffix.path(field)),"continuation_recipe");
            need(nextRaw.path("rawRef").equals(suffix.path("rawRef"))&&nextRaw.path("sourceObservationRef").equals(suffix.path("sourceObservationRef"))&&continuation.path("anchorRawRef").path("sha256").equals(binding.path("rawResultSha256"))&&continuation.path("anchorSourceObservationRef").path("sha256").equals(binding.path("sourceObservationSha256")),"continuation_descriptor");
            need(continuation.path("groups").isArray()&&continuation.path("groups").size()==1,"continuation_group");ObjectNode expected=continuation.path("groups").get(0).deepCopy();
            expected.put("continuationMethodBlockId",next.getId()).put("continuationCandidateSha256",VettingSourceMaterial.formSourceSha256(nextRaw)).put("physicalPageNo",next.getPageNo());for(String field:Arrays.asList("method","provider","nativeGlyphNamespace","rawRef","sourceObservationRef"))expected.set(field,nextRaw.path(field));expected.put("nativeGlyphNamespacesJoined",false);expected.set("ordinaryNativeBlockIds",suffix.path("ordinaryNativeBlockIds"));need(expected.equals(suffix),"continuation_observation");
            List<String> literals=new ArrayList<>();for(String nativeId:ids(suffix.path("ordinaryNativeBlockIds"),members)){DocumentBlock line=members.get(nativeId);need(context.contains(nativeId)&&Objects.equals(line.getPageNo(),next.getPageNo()),"continuation_member");literals.add(normal(nvl(line.getEffectiveText())));}
            List<String> expectedLiterals=new ArrayList<>();for(JsonNode member:suffix.path("members"))for(JsonNode line:member.path("lines"))expectedLiterals.add(normal(line.path("text").asText()));need(literals.equals(expectedLiterals),"continuation_literals");
            int lineOrdinal=0;List<String> selectedIds=new ArrayList<>(ids(suffix.path("ordinaryNativeBlockIds"),members));for(JsonNode member:suffix.path("members"))for(JsonNode observedLine:member.path("lines")){
                List<DocumentBlock> matches=new ArrayList<>();for(DocumentBlock line:members.values())if(Objects.equals(line.getPageNo(),next.getPageNo())&&"native".equals(line.getSource())&&"pdf_line".equals(line.getKind())&&line.getReadingContext()!=null&&sha.equals(line.getReadingContext().path("sourceSha256").asText())&&normal(nvl(line.getEffectiveText())).equals(normal(observedLine.path("text").asText()))&&intersects(observedLine.path("bbox"),line.getBbox(),nextRaw.path("frame")))matches.add(line);
                need(matches.size()==1&&lineOrdinal<selectedIds.size()&&matches.get(0).getId().equals(selectedIds.get(lineOrdinal++)),"continuation_unique_geometry");
            }
            for(JsonNode edge:suffix.path("relations"))expectedRelations.add(edge);
        }
        need(expectedRelations.equals(group.path("relations")),"method_relations");
    }
    private static boolean intersects(JsonNode points,double[] normalized,JsonNode frame){if(!points.isArray()||points.size()!=4||normalized==null||normalized.length!=4)return false;double width=frame.path("pageWidth").asDouble(),height=frame.path("pageHeight").asDouble();return Math.min(points.get(2).asDouble(),(normalized[0]+normalized[2])*width)>Math.max(points.get(0).asDouble(),normalized[0]*width)&&Math.min(points.get(3).asDouble(),(normalized[1]+normalized[3])*height)>Math.max(points.get(1).asDouble(),normalized[1]*height);}
    private static void alignment(JsonNode alignment,DocumentBlock line,JsonNode table,double[] points){
        need(alignment.path("row").isIntegralNumber()&&alignment.path("row").asInt()>=0&&alignment.path("row").asInt()<table.path("rowCount").asInt()&&"unicode_whitespace_collapsed_case_and_punctuation_preserved".equals(alignment.path("textComparison").asText())&&alignment.path("geometryTolerancePoints").asDouble()==.02,"method_row_recipe");
        List<JsonNode> cells=new ArrayList<>();for(JsonNode cell:table.path("origins"))if(cell.path("row").equals(alignment.path("row")))cells.add(cell);cells.sort(Comparator.comparingInt(cell->cell.path("col").asInt()));need(!cells.isEmpty(),"method_row_missing");
        String literal=normal(nvl(line.getEffectiveText())),basis=alignment.path("basis").asText();double[] rowBounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};List<String> text=new ArrayList<>();
        for(JsonNode cell:cells){need(cell.path("bbox").isArray()&&cell.path("bbox").size()==4,"method_cell_bbox");for(int n=0;n<4;n++)rowBounds[n]=n<2?Math.min(rowBounds[n],cell.path("bbox").get(n).asDouble()):Math.max(rowBounds[n],cell.path("bbox").get(n).asDouble());text.add(cell.path("text").asText());}
        need(contained(rowBounds,points),"method_row_geometry");
        if("same_original_and_page_calibrated_frame_unique_contained_row_exact_whitespace_normalized_literal".equals(basis))need(!alignment.has("nativeCellFragmentProof")&&literal.equals(normal(String.join(" ",text))),"method_row_literal");
        else{
            need("same_original_page_frame_unique_contained_native_cell_fragment".equals(basis)&&alignment.path("nativeCellFragmentProof").isArray()&&alignment.path("nativeCellFragmentProof").size()==1,"method_fragment_recipe");JsonNode proof=alignment.path("nativeCellFragmentProof").get(0),cell=null;
            for(JsonNode candidate:cells)if(candidate.path("row").equals(proof.path("row"))&&candidate.path("col").equals(proof.path("col"))){need(cell==null,"method_fragment_cell_ambiguous");cell=candidate;}
            need(cell!=null&&Objects.equals(line.getEffectiveText(),proof.path("nativeLiteral").asText())&&cell.path("text").equals(proof.path("cellLiteral"))&&cell.path("bbox").equals(proof.path("cellBboxPoints")),"method_fragment_literal");String cellText=normal(cell.path("text").asText());int at=cellText.indexOf(literal),end=at+literal.length();need(!literal.isEmpty()&&at>=0&&cellText.indexOf(literal,at+1)<0&&(at==0||!Character.isLetterOrDigit(cellText.codePointBefore(at)))&&(end==cellText.length()||!Character.isLetterOrDigit(cellText.codePointAt(end)))&&proof.path("basis").asText().equals(cellText.equals(literal)?"complete_cell_literal":"unique_contiguous_cell_literal"),"method_fragment_unique_range");double[] cellBounds=new double[4];for(int n=0;n<4;n++)cellBounds[n]=cell.path("bbox").get(n).asDouble();need(contained(cellBounds,points),"method_fragment_geometry");
        }
    }
    private static boolean contained(double[] outer,double[] inner){return outer[0]<=inner[0]+.02&&outer[1]<=inner[1]+.02&&outer[2]+.02>=inner[2]&&outer[3]+.02>=inner[3];}
    private static JsonNode unique(JsonNode values,String field,String expected){JsonNode found=null;for(JsonNode value:values)if(expected.equals(value.path(field).asText())){need(found==null,"ambiguous_method_member");found=value;}return found;}
    private static void relation(JsonNode relation,Map<String,JsonNode> groups,Map<String,DocumentBlock> blocks,String sha){
        need(relation.path("semanticScopeVerified").isBoolean()&&!relation.path("semanticScopeVerified").asBoolean(),"relation_authority");String type=relation.path("type").asText();
        if(Arrays.asList("source_parent","source_parent_tail").contains(type)){JsonNode parent=groups.get(relation.path("from").asText()),child=groups.get(relation.path("to").asText());need(parent!=null&&child!=null&&parent.path("id").equals(child.path("parentId")),"relation_parent");return;}
        need(Arrays.asList("source_literal_reference","source_explicit_note_target").contains(type)&&blocks.containsKey(relation.path("fromBlockId").asText())&&relation.path("sourcePosition").path("blockId").equals(relation.path("fromBlockId")),"relation_member");position(relation.path("sourcePosition"),sha,blocks);
        JsonNode target=relation.hasNonNull("targetGroupId")?groups.get(relation.path("targetGroupId").asText()):null;need(!relation.hasNonNull("targetGroupId")||target!=null,"relation_target");if(target!=null)need(target.path("sourcePosition").equals(relation.path("targetSourcePosition")),"relation_target_position");
        ids(relation.path("targetBlockIds"),blocks);need(relation.path("to").equals(relation.path("targetGroupId")),"relation_to");
        if(target!=null)need("source_clause".equals(target.path("kind").asText())&&target.path("observedOwnBlockIds").equals(relation.path("targetBlockIds")),"relation_owned_target");
        if("source_literal_reference".equals(type)){
            JsonNode owner=groups.get(relation.path("from").asText());need(owner!=null,"reference_owner");spans(relation.path("sourceSpans"),blocks,ids(owner.path("bodyBlockIds"),blocks));need(!relation.path("sourceSpans").isEmpty()&&relation.path("fromBlockId").equals(relation.path("sourceSpans").get(0).path("blockId")),"reference_first_member");List<String> literals=new ArrayList<>();int previous=-1;List<String> body=new ArrayList<>(ids(owner.path("bodyBlockIds"),blocks));for(JsonNode span:relation.path("sourceSpans")){literals.add(span.path("literal").asText());int at=body.indexOf(span.path("blockId").asText());need(at>=previous,"reference_source_order");previous=at;}need(String.join("\n",literals).equals(relation.path("literal").asText()),"reference_literal");
            if(literals.size()==1){JsonNode span=relation.path("sourceSpans").get(0);need("utf16".equals(relation.path("offsetUnit").asText())&&relation.path("startOffset").equals(span.path("startOffset"))&&relation.path("endOffset").equals(span.path("endOffset")),"reference_offsets");}
            else need("utf16_per_source_span".equals(relation.path("offsetUnit").asText())&&"one_observed_layout_newline_between_native_blocks".equals(relation.path("literalProjection").asText())&&!relation.has("startOffset")&&!relation.has("endOffset"),"reference_span_recipe");
            int targets=0;for(JsonNode candidate:groups.values())if("source_clause".equals(candidate.path("kind").asText())&&candidate.path("reference").equals(relation.path("targetReference")))targets++;
            JsonNode referenceOwner=owner;
            while(referenceOwner!=null&&(referenceOwner.path("reference").asText().contains("(")||"parent_tail".equals(referenceOwner.path("kind").asText())))
                referenceOwner=groups.get(referenceOwner.path("parentId").asText());
            need(NormalPdfSourceGroups.referenceLiteralMatches(relation.path("literal").asText(),relation.path("targetReference").asText(),referenceOwner==null?null:referenceOwner.path("reference").asText()),"reference_literal_target");
            if(target==null)need(targets!=1&&relation.path("to").isNull()&&relation.path("targetBlockIds").isEmpty()&&"unknown".equals(relation.path("status").asText())&&!relation.has("targetSourcePosition"),"reference_unknown_target");
            else need(targets==1&&target.path("reference").equals(relation.path("targetReference"))&&"unique_observed_source_target".equals(relation.path("status").asText()),"reference_target_identity");
        }else{
            DocumentBlock note=blocks.get(relation.path("fromBlockId").asText());PdfFormField field=note.getPdfFormField();need(field!=null&&target!=null&&relation.path("from").equals(relation.path("fromBlockId"))&&"exact_observed_node_only".equals(relation.path("targetScope").asText())&&relation.path("targetIncludesDescendants").isBoolean()&&!relation.path("targetIncludesDescendants").asBoolean()&&Objects.equals(field.getId(),relation.path("fieldId").asText())&&Objects.equals(field.getFieldName(),relation.path("fieldName").asText())&&Objects.equals(field.getPageNo(),note.getPageNo())&&note.getPageNo()==target.path("sourcePosition").path("physicalPageNo").asInt()&&(field.getId()+"/glyph").equals(relation.path("appearanceGlyphNamespace").asText())&&"appearance_stream_local_matrix_no_page_box".equals(relation.path("appearanceCoordinateSpace").asText())&&field.isAppearancePresent()&&!field.isConflict()&&!compactText(field.getFieldValue()).isEmpty()&&compactText(field.getFieldValue()).equals(compactText(field.getAppearanceValue())),"note_binding");
            need(Objects.equals(NormalPdfSourceGroups.namedFormReference(field.getFieldName()),target.path("reference").asText()),"note_literal_target");
            int matches=0;for(JsonNode group:groups.values())if("source_margin_note".equals(group.path("kind").asText())&&group.path("sourcePosition").path("blockId").equals(relation.path("fromBlockId"))){need(group.path("parentId").equals(relation.path("targetGroupId"))&&group.path("bodyBlockIds").equals(relation.path("targetBlockIds"))&&ids(group.path("contextBlockIds"),blocks).contains(note.getId()),"note_group_target");matches++;}need(matches==1,"note_group_unique");
        }
    }
    private static boolean nativeEnd(JsonNode coverage,Map<String,DocumentBlock> blocks){Set<Integer> pages=new HashSet<>();for(DocumentBlock block:blocks.values())if("pdf_line".equals(block.getKind())&&block.getPageNo()!=null)pages.add(block.getPageNo());int count=coverage.path("totalPages").asInt();if(!coverage.path("complete").asBoolean()||coverage.path("ocrPages").asInt(-1)!=0||!coverage.path("failedPages").isArray()||!coverage.path("failedPages").isEmpty()||count<1||coverage.path("parsedPages").asInt()!=count||pages.size()!=count)return false;for(int page=1;page<=count;page++)if(!pages.contains(page))return false;return true;}
    private static void spans(JsonNode spans,Map<String,DocumentBlock> blocks,Set<String> body){need(spans.isArray(),"span_array");Map<String,Integer> ends=new HashMap<>();for(JsonNode span:spans){DocumentBlock block=blocks.get(span.path("blockId").asText());need(block!=null&&body.contains(block.getId())&&"utf16".equals(span.path("offsetUnit").asText())&&span.path("startOffset").isInt()&&span.path("endOffset").isInt(),"span_member");int start=span.path("startOffset").asInt(),end=span.path("endOffset").asInt();String text=nvl(block.getText());need(start>=ends.getOrDefault(block.getId(),0)&&end>start&&end<=text.length()&&text.substring(start,end).equals(span.path("literal").asText()),"span_literal_or_order");ends.put(block.getId(),end);}}
    private static String joinSpans(JsonNode spans){List<String> text=new ArrayList<>();for(JsonNode span:spans)text.add(span.path("literal").asText());return String.join(" ",text);}
    private static boolean contains(JsonNode values,String text){for(JsonNode value:values)if(text.equals(value.asText()))return true;return false;}
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    private static String normal(String value){return value.replaceAll("[\\s\\p{Z}]+"," ").trim();}
    private static String compactText(String value){return nvl(value).replaceAll("[\\s\\p{Z}]+","");}
    private static String nvl(String value){return value==null?"":value;}
    private static void need(boolean valid,String reason){if(!valid)throw new IllegalArgumentException("NORMAL_PDF_COMPACT_"+reason.toUpperCase(Locale.ROOT));}
}
