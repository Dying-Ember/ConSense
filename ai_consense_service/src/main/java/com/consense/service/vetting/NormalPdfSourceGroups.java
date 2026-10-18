package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.document.DocumentBlock;
import com.consense.document.PdfPageEvidence.PdfFormField;
import com.consense.document.PdfPageEvidence.PdfGlyph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.*;

/** Source syntax derived only from the existing native PDF block observations. */
public final class NormalPdfSourceGroups {
    private static final String FRAME="normalized_rendered_crop_top_left",AP_FRAME="appearance_stream_local_matrix_no_page_box";
    private static final Pattern NUMBER=Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)(\\.)?(?=\\s|$)\\s*"),PAREN=Pattern.compile("^\\s*\\(([a-z0-9]{1,8})\\)\\s*",Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD=Pattern.compile("^\\s*(?:clause|condition)\\s*(\\d+(?:\\.\\d+)*)(?:\\(([a-z0-9]+)\\)|([a-z]))?\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE=Pattern.compile("\\b(?:clause|condition)\\s+(\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*)",Pattern.CASE_INSENSITIVE),LOCAL=Pattern.compile("(?<![\\p{L}\\p{N}_])\\([a-z0-9]{1,8}\\)",Pattern.CASE_INSENSITIVE);
    private static final Pattern ABOVE=Pattern.compile("^\\s*(?:(?:,|and/or|and|or)\\s*\\([a-z0-9]{1,8}\\)\\s*)*above\\b",Pattern.CASE_INSENSITIVE);
    private NormalPdfSourceGroups() { }
    public static ObjectNode build(List<DocumentBlock> blocks,String sourceSha256,JsonNode coverage) {
        ObjectNode out=object();out.put("version","native-pdf-source-groups-v1");out.put("sourceSha256",sourceSha256);
        ArrayNode groups=out.putArray("groups"),relations=out.putArray("relations"),context=out.putArray("contextBlocks"),issues=out.putArray("issues");
        out.put("scopeMeaning","Observed native source syntax only; visible ink, semantic and legal applicability and editing authority remain unknown");
        if(blocks==null||sourceSha256==null||!sourceSha256.matches("(?i)[a-f0-9]{64}")){issue(issues,null,"source_identity_unknown");return out;}
        Map<String,Integer> ids=new HashMap<>();for(DocumentBlock block:blocks)ids.merge(block.getId(),1,Integer::sum);
        Map<Integer,List<DocumentBlock>> pages=new TreeMap<>();List<DocumentBlock> forms=new ArrayList<>();Set<Integer> unknownPages=new HashSet<>();boolean unlocatedNativeObservation=false;
        for(DocumentBlock block:blocks){
            if(!"pdf_line".equals(block.getKind())&&!"pdf_form_widget".equals(block.getKind()))continue;
            String failure=null;
            if(block.getId()==null||ids.get(block.getId())!=1)failure="missing_or_duplicate_native_block_id";
            else if(!bound(block,sourceSha256))failure="source_or_physical_page_binding_unknown";
            else if("pdf_line".equals(block.getKind())&&!validLine(block))failure="native_glyph_text_frame_or_geometry_unknown";
            if(failure!=null){issue(issues,block.getId(),failure);context(context,block,failure);if("pdf_line".equals(block.getKind())){if(block.getPageNo()!=null&&block.getPageNo()>0)unknownPages.add(block.getPageNo());else unlocatedNativeObservation=true;}continue;}
            if(block.getPdfFormField()!=null)forms.add(block);else pages.computeIfAbsent(block.getPageNo(),key->new ArrayList<>()).add(block);
        }
        Map<String,Set<Integer>> occurrences=new HashMap<>();for(List<DocumentBlock> page:pages.values())for(DocumentBlock block:page)occurrences.computeIfAbsent(ws(block.getText()),key->new HashSet<>()).add(block.getPageNo());
        List<Node> nodes=new ArrayList<>(),stack=new ArrayList<>();Node tail=null;Row last=null;int ordinal=0,previousPage=-1;boolean previousBase=false;
        for(Map.Entry<Integer,List<DocumentBlock>> page:pages.entrySet()){
            List<Row> rows=rows(page.getValue(),issues,context);double base=Double.POSITIVE_INFINITY;
            for(Row row:rows)if(!row.wide&&row.numeric!=null)base=Math.min(base,row.x);
            boolean baseKnown=Double.isFinite(base);double referenceBase=baseKnown?base:0;
            if(previousPage!=-1&&(page.getKey()!=previousPage+1||!baseKnown||!previousBase)){
                markActive(stack,tail,"cross_page_native_column_or_continuity_unknown");stack.clear();tail=null;
            }
            previousPage=page.getKey();previousBase=baseKnown;last=null;
            double maxNumberY=rows.stream().filter(row->row.numeric!=null||row.paren!=null).mapToDouble(row->row.y).max().orElse(Double.POSITIVE_INFINITY);
            double firstNumberY=rows.stream().filter(row->row.numeric!=null||row.paren!=null).mapToDouble(row->row.y).min().orElse(0);
            double footer=Double.POSITIVE_INFINITY;
            List<Double> steps=new ArrayList<>();for(int i=1;i<rows.size();i++){double step=rows.get(i).y-rows.get(i-1).y;if(step>rows.get(i).emY*.7&&step<rows.get(i).emY*1.8)steps.add(step);}double lineStep=median(steps,.015);
            for(int i=1;i<rows.size();i++){Row row=rows.get(i);if(row.y<=maxNumberY+row.emY||row.y-rows.get(i-1).y<=lineStep*1.4||occurrences.getOrDefault(ws(row.text),Collections.emptySet()).size()<2)continue;
                boolean suffix=true;for(int n=i;n<rows.size();n++){String text=ws(rows.get(n).text);suffix&=occurrences.getOrDefault(text,Collections.emptySet()).size()>1||text.matches("[-\\s]*[A-Z]{2,8}/\\d+\\s*-?")||text.matches("\\d+");}if(suffix)footer=Math.min(footer,row.y);
            }
            if(rows.size()>1){Row end=rows.get(rows.size()-1),before=rows.get(rows.size()-2);if(end.text.trim().matches("\\d+")&&end.y-before.y>lineStep*1.4)footer=Math.min(footer,end.y);}
            for(Row row:rows){
                row.ordinal=ordinal++;row.markerX=row.x-referenceBase;row.contentX=bodyX(row)-referenceBase;
                boolean repeatedHeader=occurrences.getOrDefault(ws(row.text),Collections.emptySet()).size()>1&&row.y<firstNumberY;
                if(repeatedHeader||row.y>=footer){for(DocumentBlock block:row.blocks)context(context,block,repeatedHeader?"repeated_native_header_context":"native_footer_band_context");continue;}
                if(row.wide){markActive(stack,tail,"same_baseline_column_reading_order_unknown");continue;}
                if(row.numeric!=null||row.paren!=null){
                    if(!Double.isFinite(row.contentX)){issue(issues,row.first().getId(),"marker_has_no_observed_body_glyph");for(DocumentBlock block:row.blocks)context(context,block,"marker_body_unknown");markActive(stack,tail,"marker_body_unknown");continue;}
                    Node parent=null;
                    if(row.numeric!=null){String key=row.numeric;int dot=key.lastIndexOf('.');String parentKey=dot<0?null:key.substring(0,dot);
                        if(parentKey!=null)for(Node candidate:stack)if(candidate.key.equals(parentKey))parent=candidate;
                    }else{
                        for(Node candidate:stack)if(row.markerX>candidate.markerX+row.tolerance)parent=candidate;
                        if(parent==null){issue(issues,row.first().getId(),"parenthesized_marker_without_observed_indent_parent");for(DocumentBlock block:row.blocks)context(context,block,"unknown_parenthesized_marker");markActive(stack,tail,"unknown_parenthesized_marker");continue;}
                    }
                    int keep=parent==null?0:stack.indexOf(parent)+1;
                    while(stack.size()>keep)close(nodes,stack.remove(stack.size()-1),row,"next_source_peer_or_ancestor_marker",sourceSha256);
                    if(tail!=null){close(nodes,tail,row,"next_source_marker",sourceSha256);tail=null;}
                    String key=row.numeric!=null?row.numeric:parent.key+"("+row.paren+")";
                    Node node=new Node(key,"source_clause",parent,row,sourceSha256);nodes.add(node);stack.add(node);
                }else if(!stack.isEmpty()){
                    Node target=null;for(Node candidate:stack)if(Math.abs(row.markerX-candidate.contentX)<=row.tolerance)target=candidate;
                    if(target==null){issue(issues,row.first().getId(),"continuation_column_has_no_observed_ancestor");for(DocumentBlock block:row.blocks)context(context,block,"unknown_continuation_column");markActive(stack,tail,"continuation_column_has_no_observed_ancestor");continue;}
                    boolean parentTail=stack.indexOf(target)<stack.size()-1||tail!=null&&tail.parent==target;
                    if(parentTail){
                        while(stack.size()>stack.indexOf(target)+1)close(nodes,stack.remove(stack.size()-1),row,"next_observed_parent_content_tail",sourceSha256);
                        if(tail==null||tail.parent!=target||last!=null&&row.y-last.y>lineStep*1.4){
                            if(tail!=null)close(nodes,tail,row,"next_observed_parent_paragraph",sourceSha256);
                            int count=0;for(Node candidate:nodes)if("parent_tail".equals(candidate.kind)&&candidate.parent==target)count++;
                            tail=new Node(target.key+":tail"+(count+1),"parent_tail",target,row,sourceSha256);nodes.add(tail);
                        }else tail.rows.add(row);
                    }else{target.rows.add(row);tail=null;}
                }else for(DocumentBlock block:row.blocks)context(context,block,"native_preamble_or_unowned_text_context");
                last=row;
            }
        }
        boolean nativeEnd=coverage!=null&&coverage.path("complete").asBoolean()&&coverage.path("ocrPages").asInt(-1)==0&&coverage.path("failedPages").isArray()&&coverage.path("failedPages").isEmpty()
                &&coverage.path("totalPages").asInt()>0&&coverage.path("parsedPages").asInt()==coverage.path("totalPages").asInt()&&pages.size()==coverage.path("totalPages").asInt();
        if(nativeEnd)for(int page=1;page<=pages.size();page++)nativeEnd&=pages.containsKey(page);
        Map<String,List<Node>> labels=new HashMap<>();for(Node node:nodes)if("source_clause".equals(node.kind))labels.computeIfAbsent(node.key,key->new ArrayList<>()).add(node);
        for(Node node:nodes){
            if(node.end==null){node.end=object();node.end.put("kind",nativeEnd?"complete_observed_native_input_end":"observed_input_end_unverified");node.end.put("sourceSha256",sourceSha256);if(!nativeEnd)node.unknown.add("native_source_end_not_established");}
            if("source_clause".equals(node.kind)&&labels.get(node.key).size()!=1){
                node.referenceAmbiguous=true;issue(issues,node.rows.get(0).first().getId(),"source_reference_identity_ambiguous");
                for(Node other:labels.get(node.key))if(other!=node&&(node.parent!=null?other.parent==node.parent:other.parent==null&&other.rows.get(0).page==node.rows.get(0).page))node.unknown.add("duplicate_source_reference");
            }
            for(Row row:node.rows)if(unknownPages.contains(row.page))node.unknown.add("unresolved_native_observations_on_member_page");
            if(unknownPages.contains(node.end.path("sourcePosition").path("physicalPageNo").asInt(-1)))node.unknown.add("unresolved_native_observations_on_boundary_page");
            if(unlocatedNativeObservation)node.unknown.add("native_source_observation_physical_page_unknown");
        }
        for(Node node:nodes){ObjectNode group=group(node,nodes,sourceSha256);groups.add(group);node.group=group;if(node.parent!=null){ObjectNode relation=relation("parent_tail".equals(node.kind)?"source_parent_tail":"source_parent",node.parent.id,node.id);relations.add(relation);((ArrayNode)group.get("relations")).add(relation.deepCopy());}}
        forms(forms,nodes,groups,relations,sourceSha256,issues,context);
        references(nodes,labels,relations);
        for(Node node:nodes){ArrayNode linked=(ArrayNode)node.group.get("contextBlockIds");for(JsonNode relation:relations)if("source_explicit_note_target".equals(relation.path("type").asText()))for(Node candidate:nodes)if(candidate.id.equals(relation.path("targetGroupId").asText())&&descendant(candidate,node))add(linked,relation.path("fromBlockId").asText());ArrayNode required=(ArrayNode)node.group.get("requiredMemberBlockIds");for(JsonNode id:linked)add(required,id.asText());}
        return out;
    }

    private static boolean bound(DocumentBlock block,String sha){return block.getPageNo()!=null&&block.getPageNo()>0&&("native".equals(block.getSource())||"native_pdf".equals(block.getSource())||"native_form".equals(block.getSource())&&"pdf_form_widget".equals(block.getKind())&&block.getPdfFormField()!=null)
            &&block.getReadingContext()!=null&&sha.equals(block.getReadingContext().path("sourceSha256").asText())&&(block.getSourcePosition()==null||sha.equals(block.getSourcePosition().path("sourceSha256").asText()));}
    private static boolean validLine(DocumentBlock block){
        if(!box(block.getBbox())||block.getPdfGlyphs()==null||block.getPdfGlyphs().isEmpty())return false;
        StringBuilder text=new StringBuilder();Set<String> ids=new HashSet<>();
        for(PdfGlyph glyph:block.getPdfGlyphs()){
            if(glyph.getId()==null||!glyph.getId().startsWith("pdf-page/"+block.getPageNo()+"/glyph/")||!ids.add(glyph.getId())||!FRAME.equals(glyph.getCoordinateSpace())||!glyph.isEligibleForNativeText()||!box(glyph.getBbox())||glyph.getUnicode()==null||Math.abs(glyph.getDirection())>.01)return false;
            text.append(glyph.getUnicode());
        }
        return compact(text.toString()).equals(compact(block.getText()));
    }
    private static List<Row> rows(List<DocumentBlock> blocks,ArrayNode issues,ArrayNode context){
        blocks.sort(Comparator.comparingDouble((DocumentBlock block)->block.getBbox()[1]).thenComparingDouble(block->block.getBbox()[0]).thenComparing(DocumentBlock::getId));
        List<Row> rows=new ArrayList<>();
        for(DocumentBlock block:blocks){if(!rows.isEmpty()&&Math.abs(block.getBbox()[1]-rows.get(rows.size()-1).y)<em(block,false)*.3)rows.get(rows.size()-1).blocks.add(block);else rows.add(new Row(block));}
        for(Row row:rows){row.blocks.sort(Comparator.comparingDouble(block->block.getBbox()[0]));row.x=row.first().getBbox()[0];row.y=row.blocks.stream().mapToDouble(block->block.getBbox()[1]).min().orElse(row.y);row.text=String.join(" ",row.blocks.stream().map(DocumentBlock::getText).toArray(String[]::new));
            for(int i=1;i<row.blocks.size();i++){double[] before=row.blocks.get(i-1).getBbox(),next=row.blocks.get(i).getBbox();if(next[0]-before[0]-before[2]>row.emX*8)row.wide=true;}
            if(row.wide){issue(issues,row.first().getId(),"same_baseline_wide_column_gap_unknown");for(DocumentBlock block:row.blocks)context(context,block,"unknown_column");}
            Matcher numeric=NUMBER.matcher(row.text),paren=PAREN.matcher(row.text);
            if(numeric.find()&&(numeric.group(2)!=null||numeric.group(1).contains(".")||!row.text.substring(numeric.end()).isEmpty())){row.numeric=numeric.group(1);row.marker=numeric.group().trim();}
            else if(paren.find()){row.paren=paren.group(1).toLowerCase(Locale.ROOT);row.marker=paren.group().trim();}
        }return rows;
    }
    private static double bodyX(Row row){if(row.marker==null)return row.x;int skip=compact(row.marker).length(),seen=0;for(DocumentBlock block:row.blocks)for(PdfGlyph glyph:block.getPdfGlyphs()){String value=compact(glyph.getUnicode());if(!value.isEmpty()&&seen>=skip)return glyph.getBbox()[0];seen+=value.length();}return Double.NaN;}
    private static double em(DocumentBlock block,boolean horizontal){List<Double> sizes=new ArrayList<>();for(PdfGlyph glyph:block.getPdfGlyphs()){double[] matrix=glyph.getTextMatrix(),position=glyph.getPdfboxPosition();if(matrix!=null&&matrix.length>=4&&position!=null&&position.length>=10&&position[horizontal?8:9]>0)sizes.add(Math.abs(matrix[horizontal?0:3])/position[horizontal?8:9]);}return median(sizes,block.getBbox()[3]);}
    private static void close(List<Node> nodes,Node owner,Row row,String kind,String sha){for(Node node:nodes)if(node.end==null&&descendant(node,owner)){node.end=boundary(row,kind,sha);}}
    private static void markActive(List<Node> stack,Node tail,String reason){for(Node active:stack)active.unknown.add(reason);if(tail!=null)tail.unknown.add(reason);}
    private static boolean descendant(Node node,Node ancestor){for(Node current=node;current!=null;current=current.parent)if(current==ancestor)return true;return false;}
    private static ObjectNode group(Node node,List<Node> nodes,String sha){
        ObjectNode group=declarations(node.id,node.kind,node.key);group.put("parentId",node.parent==null?null:node.parent.id);group.set("sourcePosition",position(node.rows.get(0).first(),sha));
        if("source_clause".equals(node.kind)){group.put("referenceIdentityStatus",node.referenceAmbiguous?"ambiguous_repeated_source_reference":"unique_observed_source_reference");ArrayNode reasons=group.putArray("referenceIdentityUnknownReasons");if(node.referenceAmbiguous)reasons.add("duplicate_source_reference");}
        List<Row> members=new ArrayList<>();Set<String> reasons=new LinkedHashSet<>(node.unknown);for(Node child:nodes)if(descendant(child,node)){members.addAll(child.rows);reasons.addAll(child.unknown);}members.sort(Comparator.comparingInt(row->row.ordinal));
        ArrayNode body=(ArrayNode)group.get("bodyBlockIds"),ordered=(ArrayNode)group.get("orderedBlockIds"),required=(ArrayNode)group.get("requiredMemberBlockIds"),pages=group.putArray("physicalPageNos");
        for(Row row:members){if(!contains(pages,row.page))pages.add(row.page);for(DocumentBlock block:row.blocks){add(body,block.getId());add(ordered,block.getId());add(required,block.getId());}}
        group.set("startBoundary",boundary(node.rows.get(0),"observed_literal_source_marker",sha));group.set("endBoundary",node.end.deepCopy());
        group.put("scopeComplete",reasons.isEmpty());group.put("status",reasons.isEmpty()?"source_syntax_complete":"partial_unknown_scope");for(String reason:reasons)((ArrayNode)group.get("unknownReasons")).add(reason);return group;
    }
    private static void forms(List<DocumentBlock> forms,List<Node> nodes,ArrayNode groups,ArrayNode relations,String sha,ArrayNode issues,ArrayNode context){
        Map<String,Integer> ids=new HashMap<>();for(DocumentBlock block:forms)ids.merge(block.getPdfFormField().getId(),1,Integer::sum);
        for(DocumentBlock block:forms){PdfFormField field=block.getPdfFormField();Matcher match=FIELD.matcher(field.getFieldName()==null?"":field.getFieldName());if(!match.matches()){context(context,block,"native_form_context");continue;}
            String token=match.group(2)!=null?match.group(2):match.group(3),key=match.group(1)+(token==null?"":"("+token.toLowerCase(Locale.ROOT)+")");
            List<Node> targets=new ArrayList<>();for(Node node:nodes)if("source_clause".equals(node.kind)&&key.equals(node.key)&&node.rows.get(0).page==block.getPageNo())targets.add(node);
            String display=appearance(field);boolean agreement=display!=null&&!compact(display).isEmpty()&&compact(display).equals(compact(field.getFieldValue()))&&compact(display).equals(compact(field.getAppearanceValue()));
            Node target=targets.size()==1?targets.get(0):null;boolean ownerKnown=target!=null&&ids.get(field.getId())==1&&Objects.equals(field.getPageNo(),block.getPageNo())&&agreement&&!field.isConflict()&&!target.unknown.contains("duplicate_source_reference");
            boolean ownWindow=ownerKnown&&target.unknown.isEmpty();if(ownerKnown&&target.unknown.size()==1&&target.unknown.contains("native_source_end_not_established"))for(Node child:nodes)if(child.parent==target)ownWindow=true;
            boolean known=ownerKnown&&ownWindow;
            ObjectNode group=declarations("pdf-source-note:"+VettingCorpus.hash(sha+"|"+block.getId()),"source_margin_note",key);group.put("parentId",target==null?null:target.id);group.set("sourcePosition",position(block,sha));group.putArray("physicalPageNos").add(block.getPageNo());group.put("scopeComplete",known);group.put("status",known?"source_syntax_complete":"partial_unknown_scope");add((ArrayNode)group.get("contextBlockIds"),block.getId());
            if(ownerKnown){for(Row row:target.rows)for(DocumentBlock source:row.blocks){add((ArrayNode)group.get("bodyBlockIds"),source.getId());add((ArrayNode)group.get("orderedBlockIds"),source.getId());add((ArrayNode)group.get("requiredMemberBlockIds"),source.getId());}
                ObjectNode edge=relation("source_explicit_note_target",block.getId(),target.id);edge.put("fromBlockId",block.getId());edge.put("fieldId",field.getId());edge.put("fieldName",field.getFieldName());edge.put("targetGroupId",target.id);edge.put("targetScope","exact_observed_node_only");edge.put("targetIncludesDescendants",false);edge.set("targetBlockIds",group.path("bodyBlockIds").deepCopy());edge.set("sourcePosition",position(block,sha));edge.set("targetSourcePosition",target.group.path("sourcePosition").deepCopy());edge.put("appearanceGlyphNamespace",field.getId()+"/glyph");edge.put("appearanceCoordinateSpace",AP_FRAME);relations.add(edge);((ArrayNode)group.get("relations")).add(edge.deepCopy());((ArrayNode)target.group.get("relations")).add(edge.deepCopy());
            }
            if(!known){((ArrayNode)group.get("unknownReasons")).add(ownerKnown?"observed_owner_window_extent_unknown":"same_page_target_or_logical_AP_glyph_agreement_not_unique");issue(issues,block.getId(),ownerKnown?"named_form_owner_window_unknown":"named_form_target_or_appearance_unknown");}
            add((ArrayNode)group.get("requiredMemberBlockIds"),block.getId());groups.add(group);
        }
    }
    private static String appearance(PdfFormField field){
        if(!field.isAppearancePresent()||field.getId()==null||field.getAppearanceGlyphs()==null||field.getAppearanceGlyphs().isEmpty()||field.getAppearanceGlyphObservationStatus()==null||!field.getAppearanceGlyphObservationStatus().startsWith("observed_existing_normal_AP_"))return null;
        List<PdfGlyph> glyphs=new ArrayList<>(field.getAppearanceGlyphs());Set<String> ids=new HashSet<>();for(PdfGlyph glyph:glyphs)if(glyph.getId()==null||!glyph.getId().startsWith(field.getId()+"/glyph/")||!ids.add(glyph.getId())||!AP_FRAME.equals(glyph.getCoordinateSpace())||!glyph.isEligibleForNativeText()||glyph.getUnicode()==null||glyph.getTextMatrix()==null||glyph.getTextMatrix().length<6||!Double.isFinite(glyph.getTextMatrix()[4])||!Double.isFinite(glyph.getTextMatrix()[5])||Math.abs(glyph.getTextMatrix()[1])>.001||Math.abs(glyph.getTextMatrix()[2])>.001)return null;
        glyphs.sort(Comparator.comparingDouble((PdfGlyph glyph)->-Math.rint(glyph.getTextMatrix()[5]*10000)/10000).thenComparingDouble(glyph->glyph.getTextMatrix()[4]));StringBuilder display=new StringBuilder();double previous=Double.NaN;
        for(PdfGlyph glyph:glyphs){double y=Math.rint(glyph.getTextMatrix()[5]*10000)/10000;if(Double.isFinite(previous)&&y!=previous)display.append('\n');display.append(glyph.getUnicode());previous=y;}return display.toString();
    }
    private static void references(List<Node> nodes,Map<String,List<Node>> labels,ArrayNode relations){
        for(Node node:nodes){StringBuilder whole=new StringBuilder();List<DocumentBlock> blocks=new ArrayList<>();List<Integer> offsets=new ArrayList<>();for(Row row:node.rows)for(DocumentBlock block:row.blocks){offsets.add(whole.length());blocks.add(block);whole.append(block.getText()).append('\n');}
            List<int[]> named=new ArrayList<>();Matcher match=REFERENCE.matcher(whole);while(match.find()){named.add(new int[]{match.start(),match.end()});reference(node,blocks,offsets,whole.toString(),match.start(),match.end(),match.group(1).toLowerCase(Locale.ROOT),labels,relations);}
            Matcher local=LOCAL.matcher(whole);while(local.find()){boolean covered=local.start()==0;for(int[] span:named)covered|=local.start()>=span[0]&&local.end()<=span[1];if(covered||!ABOVE.matcher(whole.substring(local.end())).find())continue;Node owner=node;while(owner!=null&&(owner.key.contains("(")||"parent_tail".equals(owner.kind)))owner=owner.parent;if(owner!=null)reference(node,blocks,offsets,whole.toString(),local.start(),local.end(),owner.key+local.group().toLowerCase(Locale.ROOT),labels,relations);}
        }
    }
    private static void reference(Node owner,List<DocumentBlock> blocks,List<Integer> offsets,String whole,int start,int end,String key,Map<String,List<Node>> labels,ArrayNode relations){
        List<Node> targets=labels.getOrDefault(key,Collections.emptyList());Node target=targets.size()==1?targets.get(0):null;ObjectNode edge=relation("source_literal_reference",owner.id,target==null?null:target.id);ArrayNode spans=edge.putArray("sourceSpans");DocumentBlock first=null;
        for(int i=0;i<blocks.size();i++){DocumentBlock block=blocks.get(i);int begin=Math.max(start,offsets.get(i)),limit=Math.min(end,offsets.get(i)+block.getText().length());if(begin>=limit)continue;if(first==null)first=block;ObjectNode span=spans.addObject();span.put("blockId",block.getId());span.put("startOffset",begin-offsets.get(i));span.put("endOffset",limit-offsets.get(i));span.put("offsetUnit","utf16");span.put("literal",block.getText().substring(begin-offsets.get(i),limit-offsets.get(i)));}
        if(first==null)return;edge.put("fromBlockId",first.getId());if(spans.size()==1){edge.put("startOffset",spans.get(0).path("startOffset").asInt());edge.put("endOffset",spans.get(0).path("endOffset").asInt());edge.put("offsetUnit","utf16");}else{edge.put("offsetUnit","utf16_per_source_span");edge.put("literalProjection","one_observed_layout_newline_between_native_blocks");}
        edge.put("literal",whole.substring(start,end));edge.put("targetReference",key);edge.put("targetGroupId",target==null?null:target.id);edge.put("status",target==null?"unknown":"unique_observed_source_target");edge.set("sourcePosition",position(first,owner.group.path("sourcePosition").path("sourceSha256").asText()));if(target!=null)edge.set("targetSourcePosition",target.group.path("sourcePosition").deepCopy());ArrayNode ids=edge.putArray("targetBlockIds");if(target!=null)for(Row row:target.rows)for(DocumentBlock body:row.blocks)add(ids,body.getId());relations.add(edge);((ArrayNode)owner.group.get("relations")).add(edge.deepCopy());
    }
    private static ObjectNode declarations(String id,String kind,String reference){ObjectNode out=object();out.put("id",id);out.put("kind",kind);out.put("reference",reference);out.put("semanticScopeVerified",false);out.put("automaticEditEligible",false);out.put("legalClosure","unknown");for(String key:Arrays.asList("orderedBlockIds","bodyBlockIds","contextBlockIds","requiredMemberBlockIds","relations","unknownReasons"))out.putArray(key);return out;}
    private static ObjectNode relation(String type,String from,String to){ObjectNode out=object();out.put("type",type);out.put("from",from);out.put("to",to);out.put("semanticScopeVerified",false);return out;}
    private static ObjectNode boundary(Row row,String kind,String sha){ObjectNode out=object();out.put("kind",kind);out.put("blockId",row.first().getId());out.set("sourcePosition",position(row.first(),sha));return out;}
    private static ObjectNode position(DocumentBlock block,String sha){ObjectNode out=object();out.put("kind","pdf_observed_source");out.put("sourceSha256",sha);out.put("physicalPageNo",block.getPageNo());out.put("blockId",block.getId());out.put("coordinateSpace",FRAME);out.put("glyphNamespace","pdf-page/"+block.getPageNo()+"/glyph");return out;}
    private static void context(ArrayNode out,DocumentBlock block,String role){ObjectNode value=object();value.put("blockId",block.getId());value.put("role",role);if(block.getPageNo()!=null)value.put("physicalPageNo",block.getPageNo());out.add(value);}
    private static void issue(ArrayNode out,String block,String reason){ObjectNode value=object();if(block!=null)value.put("blockId",block);value.put("reason",reason);out.add(value);}
    private static boolean box(double[] box){if(box==null||box.length!=4)return false;for(double number:box)if(!Double.isFinite(number))return false;return box[0]>=0&&box[1]>=0&&box[2]>0&&box[3]>0&&box[0]+box[2]<=1.0001&&box[1]+box[3]<=1.0001;}
    private static String compact(String text){return text==null?"":text.replaceAll("[\\s\\p{Z}]+","");}
    private static String ws(String text){return text==null?"":text.replaceAll("[\\s\\p{Z}]+"," ").trim();}
    private static double median(List<Double> values,double fallback){if(values.isEmpty())return fallback;values.sort(Double::compare);return values.get(values.size()/2);}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static void add(ArrayNode array,String value){for(JsonNode prior:array)if(value.equals(prior.asText()))return;array.add(value);}
    private static boolean contains(ArrayNode array,int value){for(JsonNode prior:array)if(prior.asInt()==value)return true;return false;}
    private static final class Row {
        final List<DocumentBlock> blocks=new ArrayList<>();final int page;final double emX,emY,tolerance;double x,y;String text,numeric,paren,marker;boolean wide;int ordinal;double markerX,contentX;
        Row(DocumentBlock block){blocks.add(block);page=block.getPageNo();x=block.getBbox()[0];y=block.getBbox()[1];emX=em(block,true);emY=em(block,false);tolerance=emX*.3;}
        DocumentBlock first(){return blocks.get(0);}
    }
    private static final class Node {
        final String id,key,kind;final Node parent;final double markerX,contentX;final List<Row> rows=new ArrayList<>();final Set<String> unknown=new LinkedHashSet<>();boolean referenceAmbiguous;ObjectNode end,group;
        Node(String key,String kind,Node parent,Row first,String sha){this.key=key;this.kind=kind;this.parent=parent;markerX=first.markerX;contentX=first.contentX;rows.add(first);id="pdf-source-group:"+VettingCorpus.hash(sha+"|"+kind+"|"+key+"|"+first.first().getId());}
    }
}
