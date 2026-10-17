package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;

/** Reading methods follow confirmed identity; observed values and candidate relationships stay separate. */
final class OerReadingPlan {
    private final ObjectNode metadata=JsonUtils.mapper().createObjectNode();
    private final boolean formLayout;

    OerReadingPlan(String sourceSha,String format,OerDocumentIdentity identity) {
        if(identity!=null && (!sourceSha.equals(identity.getSourceSha256()) || "DRAWING".equals(identity.getKind())
                || !Arrays.asList("sha_verified","content_supported","content_supported_non_drawing","format_supported_non_drawing")
                .contains(identity.getIdentityStatus()))) throw new IllegalArgumentException("Reading identity is unresolved or belongs to different original bytes");
        String family=identity==null?"UNKNOWN":identity.getKind(),version=identity==null?null:identity.getVersion();
        formLayout="pdf".equals(format) && Arrays.asList("AA","FT").contains(family);
        metadata.put("schemaVersion","oer-reading-plan-v1");metadata.put("sourceSha256",sourceSha);metadata.put("documentFamily",family);
        metadata.put("documentVersion",version);metadata.put("versionStatus",version==null||version.trim().isEmpty()?"unknown":"identity_declared");
        metadata.put("identityStatus",identity==null?"legacy_unbound_generic":identity.getIdentityStatus());
        metadata.put("profile",formLayout?"generic_form":"generic_source");
        metadata.put("ruleBasis","actual native stories, numbering, controls and layout occurrences; no source values or fixed pages");
        ArrayNode tasks=metadata.putArray("tasks");
        if("pdf".equals(format)){tasks.add("native_text_and_page_frame");tasks.add("native_widgets");tasks.add("actual_image_coverage");tasks.add("visual_gap_reading");}
        else if("docx".equals(format)){tasks.add("native_stories");tasks.add("native_table_topology");tasks.add("native_numbering_and_revision");tasks.add("native_document_outline");metadata.put("textAuthority","original_ooxml");metadata.put("visualRole","layout_assistance_only");}
        else tasks.add("native_text_with_declared_format_limits");
        if(formLayout)tasks.add("whole_form_layout_and_controls");
        tasks.add("occurrence_and_relationship_inventory");metadata.put("sourceQualityStatus","unverified");
    }
    ObjectNode metadata(){return metadata;}
    boolean requiresFormLayout(){return formLayout;}
    boolean confirmedNonDrawing(){return !"legacy_unbound_generic".equals(metadata.path("identityStatus").asText());}

    void annotate(List<DocumentBlock> blocks) {
        Map<Integer,List<DocumentBlock>> containers=new HashMap<>();Map<List<Object>,List<DocumentBlock>> anchors=new HashMap<>();
        for(DocumentBlock candidate:blocks){
            if(candidate.getBbox()!=null && Arrays.asList("table","form_group","field_group").contains(candidate.getKind()))
                containers.computeIfAbsent(candidate.getPageNo(),key->new ArrayList<>()).add(candidate);
            String label=candidate.getText()==null?"":candidate.getText().trim();
            if(label.matches("(?s).{1,120}[:：]"))anchors.computeIfAbsent(Arrays.asList(candidate.getPageNo(),story(candidate)),key->new ArrayList<>()).add(candidate);
        }
        for(DocumentBlock block:blocks) {
            ObjectNode context=block.getReadingContext() instanceof ObjectNode?(ObjectNode)block.getReadingContext():JsonUtils.mapper().createObjectNode();
            context.put("occurrenceId",block.getId());context.put("profile",metadata.path("profile").asText());
            context.put("sourceSha256",metadata.path("sourceSha256").asText());context.put("semanticStatus","unverified");
            if(!context.has("adoptionStatus"))context.put("adoptionStatus","ocr_candidate".equals(block.getSource())?"blocked_visual_candidate":"observed_unverified");
            String kind=block.getKind()==null?"":block.getKind(),text=block.getOriginalText()==null?"":block.getOriginalText().trim();
            String story=story(block);context.put("story",story);context.put("role",role(block,text));
            context.put("ownerStatus","unknown");context.put("valueState","unknown");
            ArrayNode owners=context.putArray("ownerCandidates");
            if(!block.getWordCells().isEmpty()) {
                String table=block.getWordCells().get(0).getTableLocation();
                if(table!=null){context.put("containerId",table);context.put("ownerStatus","native_ooxml_container");}
            }
            if(block.getPdfFormField()!=null) {
                context.put("fieldType",block.getPdfFormField().getFieldType());context.put("nativeFieldName",block.getPdfFormField().getFieldName());
                context.put("valueState",block.getPdfFormField().getValueStatus());
            }
            if(block.getNativeTableCandidate()!=null) {
                context.put("role","native_table_method_candidate");context.put("adoptionStatus","candidate_unverified");
                context.put("effectiveTextStatus","withheld_whole_method_candidate");
            }
            if(block.getParserPageGroup()!=null) {
                context.put("role","whole_parser_page_candidate");context.put("adoptionStatus","candidate_unverified");
                context.put("effectiveTextStatus","withheld_complete_alternatives");context.set("selectedParser",block.getParserPageGroup().path("selectedParser").deepCopy());
            }
            if(block.getBbox()!=null)for(DocumentBlock candidate:containers.getOrDefault(block.getPageNo(),Collections.emptyList())) {
                if(candidate!=block && contains(candidate.getBbox(),block.getBbox()))
                    owners.addObject().put("ownerId",candidate.getId()).put("basis","same_page_observed_geometry_containment").put("status","unverified");
            }
            if("header".equals(kind)||"footer".equals(kind)||"footnote".equals(kind)||"endnote".equals(kind)) {
                context.put("containerId",story);context.put("ownerStatus","native_ooxml_story");
            }
            ArrayNode labels=context.putArray("anchorCandidates");
            if(Arrays.asList("writing_area","checkbox","radio","form_control","pdf_form_widget").contains(kind))for(DocumentBlock candidate:anchors.getOrDefault(Arrays.asList(block.getPageNo(),story),Collections.emptyList())) {
                String label=candidate.getText()==null?"":candidate.getText().trim();
                if(candidate!=block)labels.addObject().put("anchorId",candidate.getId()).put("text",label).put("status","candidate_not_assigned");
            }
            block.setReadingContext(context);
        }
        for(int index=0;index<blocks.size();index++) {
            DocumentBlock block=blocks.get(index);ObjectNode context=(ObjectNode)block.getReadingContext();
            String role=context.path("role").asText(),text=block.getOriginalText()==null?"":block.getOriginalText();
            ArrayNode relations=context.putArray("relationCandidates");
            if(Arrays.asList("note_anchor_candidate","branch_anchor_candidate").contains(role)
                    || text.matches("(?is).*(?:\\bcontinued\\b|\\bcontinuation\\b).*")) {
                for(int neighbour: new int[]{index-1,index+1}) {
                    if(neighbour<0 || neighbour>=blocks.size())continue;DocumentBlock target=blocks.get(neighbour);
                    boolean sameStory=story(block).equals(story(target));
                    boolean adjacentPages=block.getPageNo()!=null && target.getPageNo()!=null && Math.abs(block.getPageNo()-target.getPageNo())==1;
                    if(sameStory||adjacentPages)relations.addObject().put("kind",role.startsWith("branch")?"branch_scope":role.startsWith("note")?"note_scope":"continuation")
                            .put("fromOccurrenceId",block.getId()).put("toOccurrenceId",target.getId()).put("basis","actual marker and observed adjacency")
                            .put("status","unknown_not_assigned");
                }
            }
        }
        metadata.put("observedOccurrenceCount",blocks.size());metadata.put("relationshipStatus","explicit native containers retained; geometric and label candidates not source adopted");
    }
    private static String role(DocumentBlock block,String text) {
        String kind=block.getKind()==null?"":block.getKind();
        if("table_row".equals(kind)||"table".equals(kind))return "table_group_member";
        com.fasterxml.jackson.databind.JsonNode outline=block.getReadingContext()==null?null:block.getReadingContext().get("nativeDocxParagraph");
        if(outline!=null&&"resolved".equals(outline.path("status").asText())&&outline.has("outlineLevel")&&outline.path("outlineLevel").asInt()<9)return "native_heading";
        if(Arrays.asList("writing_area","checkbox","radio","form_control","pdf_form_widget").contains(kind))return "field_or_control_observation";
        if(text.matches("(?i)^(?:notes?|remarks?)\\s*[:：]?.*"))return "note_anchor_candidate";
        if(text.matches("(?i)^OR(?:\\s.*)?$"))return "branch_anchor_candidate";
        if(text.matches("(?s)^(?:[0-9]+(?:\\.[0-9]+)*[.)]?|\\([a-zA-Z0-9]+\\))\\s+.*"))return "numbered_member_candidate";
        if(outline==null && block.getParagraphStyleName()!=null && block.getParagraphStyleName().matches("(?i).*heading.*"))return "native_heading";
        return kind;
    }
    private static String story(DocumentBlock block) {
        if(block.getPageNo()!=null)return "pdf-page/"+block.getPageNo();
        String location=block.getLocation();if(location==null)return "body";
        if(location.startsWith("body/"))return "body";
        String[] parts=location.split("/");return parts.length>1?parts[0]+"/"+parts[1]:parts[0];
    }
    private static boolean contains(double[] outer,double[] inner) {
        return outer.length==4 && inner.length==4 && outer[0]<=inner[0] && outer[1]<=inner[1]
                && outer[0]+outer[2]>=inner[0]+inner[2] && outer[1]+outer[3]>=inner[1]+inner[3];
    }
}
