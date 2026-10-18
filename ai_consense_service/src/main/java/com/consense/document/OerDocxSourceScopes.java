package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Complete source intervals over the existing paragraph observations, not legal clause adoption. */
final class OerDocxSourceScopes {
    private static final Pattern MANUAL_NUMBER=Pattern.compile("^\\s*([1-9][0-9]*)\\.\\s*(?=\\p{L})");
    private OerDocxSourceScopes() { }

    static ObjectNode observe(JsonNode inventory,JsonNode outline) {
        ObjectNode out=OerTemplateEvidence.nativeClauseRanges(inventory);
        ArrayNode ranges=(ArrayNode)out.path("ranges");List<JsonNode> members=new ArrayList<>();
        for(JsonNode member:out.path("members")) {
            ((ObjectNode)member).remove(Arrays.asList("nativeXml","nativeXmlSha256","xmlEncoding","nativeXmlStatus"));
            for(JsonNode declarations:member.path("directParagraphLayout"))for(JsonNode declaration:declarations)
                if(declaration.isObject())((ObjectNode)declaration).remove(Arrays.asList("nativeXml","nativeXmlStatus"));
            members.add(member);
        }
        if(!out.path("familyCandidate").isNull()&&!ranges.isEmpty())return out;
        Map<String,List<JsonNode>> descendants=new HashMap<>();Map<JsonNode,Integer> order=new IdentityHashMap<>();
        for(int i=0;i<members.size();i++) {
            JsonNode member=members.get(i);order.put(member,i);JsonNode position=member.path("sourcePosition");String path=position.path("nodePath").asText();
            while(!path.isEmpty()){descendants.computeIfAbsent(positionKey(position,path),key->new ArrayList<>()).add(member);int separator=path.lastIndexOf("/{");path=separator<=0?"":path.substring(0,separator);}
        }
        Map<Integer,ObjectNode> chapters=new LinkedHashMap<>();Map<String,ObjectNode> deepest=new HashMap<>();
        Map<ObjectNode,List<JsonNode>> chapterMembers=new IdentityHashMap<>();
        for(JsonNode heading:outline.path("headings")) {
            if(!out.path("sourceSha256").equals(heading.path("sourcePosition").path("sourceSha256")))continue;
            List<JsonNode> observed=new ArrayList<>();Set<JsonNode> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(JsonNode nativePath:heading.path("nativeMemberPaths"))for(JsonNode member:descendants.getOrDefault(positionKey(heading.path("sourcePosition"),nativePath.asText()),Collections.emptyList()))if(seen.add(member))observed.add(member);
            observed.sort(Comparator.comparingInt(order::get));
            if(observed.isEmpty())continue;
            String reference=heading.path("textObservation").path("text").asText();
            ObjectNode range=range("native-outline",reference,observed,heading.path("endBoundary"));
            range.put("basis","native_outline_membership");range.set("nativeOutlineOrdinalZeroBased",heading.path("ordinalZeroBased"));
            chapters.put(heading.path("ordinalZeroBased").asInt(),range);ranges.add(range);
            chapterMembers.put(range,observed);
            for(JsonNode member:observed)deepest.put(member.path("id").asText(),range);
        }
        for(JsonNode heading:outline.path("headings")) {
            ObjectNode child=chapters.get(heading.path("ordinalZeroBased").asInt());if(child==null)continue;
            ObjectNode parent=heading.path("parentOrdinalZeroBased").isInt()?chapters.get(heading.path("parentOrdinalZeroBased").asInt()):null;
            if(parent!=null)child.put("parentId",parent.path("id").asText());
        }
        // Template-specific cross-cell/parallel-column rules remain in the shared template projection.
        if(out.path("familyCandidate").isNull())for(ObjectNode chapter:chapters.values()) {
            manualIntervals(chapterMembers.get(chapter),chapter,ranges,deepest);
        }
        return out;
    }

    private static void manualIntervals(List<JsonNode> flow,ObjectNode chapter,ArrayNode ranges,Map<String,ObjectNode> deepest) {
        List<Integer> starts=new ArrayList<>();List<java.math.BigInteger> numbers=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        for(int i=0;i<flow.size();i++) {
            JsonNode member=flow.get(i);if(deepest.get(member.path("id").asText())!=chapter||!directBody(member)||!usable(member)||numbering(member).path("numbered").asBoolean())continue;
            Matcher match=MANUAL_NUMBER.matcher(text(member));if(match.find()){starts.add(i);numbers.add(new java.math.BigInteger(match.group(1)));counts.merge(member.path("paragraphProperties").path("paragraphStyleId").asText()+"|"+match.group(1),1,Integer::sum);}
        }
        for(int i=0;i<starts.size();i++) {
            JsonNode first=flow.get(starts.get(i));String style=first.path("paragraphProperties").path("paragraphStyleId").asText();
            boolean next=i+1<starts.size()&&numbers.get(i).add(java.math.BigInteger.ONE).equals(numbers.get(i+1))&&sameStyle(first,flow.get(starts.get(i+1)));
            boolean previous=i>0&&numbers.get(i-1).add(java.math.BigInteger.ONE).equals(numbers.get(i))&&sameStyle(first,flow.get(starts.get(i-1)));
            if(!next&&!previous)continue;
            int end=i+1<starts.size()?starts.get(i+1):flow.size();boolean nativeBoundary=false;
            for(int n=starts.get(i)+1;n<end;n++)if(directBody(flow.get(n))&&deepest.get(flow.get(n).path("id").asText())!=chapter&&"resolved".equals(flow.get(n).path("paragraphProperties").path("status").asText())&&flow.get(n).path("paragraphProperties").path("outlineLevel").asInt(9)<9){end=n;nativeBoundary=true;break;}
            List<JsonNode> span=new ArrayList<>(flow.subList(starts.get(i),end));
            JsonNode boundary=end<flow.size()?boundary(nativeBoundary?"next_native_outline_heading":"next_explicit_number_in_source_sequence",flow.get(end)):chapter.path("endBoundary");
            ObjectNode parent=range("manual-sequence",numbers.get(i).toString(),span,boundary);
            parent.put("basis","explicit_consecutive_decimal_markers_in_same_native_heading_and_paragraph_style");
            parent.put("hierarchyStatus","source_interval_containment_only");parent.put("parentId",chapter.path("id").asText());parent.put("paragraphStyleId",style);
            if(i+1<starts.size()&&!next)incomplete(parent,"next_explicit_number_is_not_a_unique_consecutive_peer");
            if(counts.get(style+"|"+numbers.get(i))!=1)incomplete(parent,"duplicate_explicit_number_in_same_source_sequence");
            ranges.add(parent);nativeListIntervals(span,parent,ranges);
        }
    }

    private static void nativeListIntervals(List<JsonNode> flow,ObjectNode parent,ArrayNode ranges) {
        List<Integer> starts=new ArrayList<>();Map<String,Integer> counts=new HashMap<>();
        for(int i=1;i<flow.size();i++) {
            JsonNode number=numbering(flow.get(i));
            if(directBody(flow.get(i))&&number.path("numbered").asBoolean()&&"resolved".equals(number.path("resolutionStatus").asText())){
                starts.add(i);String key=listKey(number)+"|"+number.path("label").asText();counts.merge(key,1,Integer::sum);
            }
        }
        for(int i=0;i<starts.size();i++) {
            JsonNode member=flow.get(starts.get(i)),number=numbering(member);int end=i+1<starts.size()?starts.get(i+1):flow.size();
            ObjectNode range=range("native-list",parent.path("reference").asText()+number.path("label").asText(),new ArrayList<>(flow.subList(starts.get(i),end)),i+1<starts.size()?boundary("next_resolved_native_list_member",flow.get(end)):parent.path("endBoundary"));
            range.put("basis","resolved_native_list_label_and_source_interval");range.put("hierarchyStatus","source_interval_containment_only");range.put("parentId",parent.path("id").asText());range.set("numbering",number.deepCopy());
            if(counts.get(listKey(number)+"|"+number.path("label").asText())!=1)incomplete(range,"duplicate_native_list_label_within_source_interval");
            if(i+1<starts.size()&&!listKey(number).equals(listKey(numbering(flow.get(end)))))incomplete(range,"next_native_list_identity_or_level_differs");
            if(i+1==starts.size()&&!parent.path("scopeComplete").asBoolean())incomplete(range,"parent_source_interval_boundary_is_unresolved");
            ranges.add(range);
        }
    }

    private static ObjectNode range(String kind,String reference,List<JsonNode> members,JsonNode end) {
        JsonNode first=members.get(0);ObjectNode range=JsonUtils.mapper().createObjectNode();
        range.put("id","docx-source-range:"+DocumentParseProbe.sha256((kind+"|"+first.path("id").asText()).getBytes(StandardCharsets.UTF_8)));
        range.put("reference",reference);range.putNull("parentId");range.put("kind",kind);range.put("status","source_syntax_complete");range.put("scopeComplete",true);
        range.put("automaticEditEligible",false);range.put("semanticApplicabilityVerified",false);
        range.put("scopeMeaning","Complete observed source interval only; native outline and source sequence bases remain distinct; legal applicability and editing adoption are unknown");
        range.set("startBoundary",boundary("observed_source_member",first));range.set("endBoundary",end.deepCopy());
        ArrayNode ordered=range.putArray("orderedMemberIds");range.putArray("contextMemberIds");range.putArray("contextObservations");
        for(JsonNode member:members){ordered.add(member.path("id").asText());if(!usable(member)||!sameStory(first,member))incomplete(range,"unresolved_or_overlapping_paragraph_channel_or_story");}
        if(!samePart(first.path("sourcePosition"),end.path("sourcePosition"))||!Arrays.asList("native_container_end","next_native_outline_heading","next_explicit_number_in_source_sequence","next_native_outline_heading","next_resolved_native_list_member").contains(end.path("kind").asText()))incomplete(range,"source_interval_end_boundary_is_unresolved");
        return range;
    }
    private static void incomplete(ObjectNode range,String reason){range.put("status","partial_unknown_scope");range.put("scopeComplete",false);ArrayNode reasons=range.has("unknownReasons")?(ArrayNode)range.get("unknownReasons"):range.putArray("unknownReasons");for(JsonNode prior:reasons)if(reason.equals(prior.asText()))return;reasons.add(reason);}
    private static ObjectNode boundary(String kind,JsonNode member){ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("kind",kind);out.put("memberId",member.path("id").asText());out.set("sourcePosition",member.path("sourcePosition").deepCopy());return out;}
    private static boolean usable(JsonNode member){return "source_bound_native_member".equals(member.path("sourceClauseClassification").asText())&&member.path("textObservation").path("status").asText().startsWith("observed_")&&member.path("textObservation").path("text").isTextual()&&!member.has("textOverlapStatus")&&member.path("effectiveCandidate").asBoolean(true);}
    private static boolean directBody(JsonNode member){return "body".equals(member.path("storyKind").asText())&&!member.has("cellNodePath")&&!member.has("containingParagraphNodePath")&&!member.has("textOverlapStatus");}
    private static boolean sameStyle(JsonNode a,JsonNode b){String style=a.path("paragraphProperties").path("paragraphStyleId").asText();return !style.isEmpty()&&style.equals(b.path("paragraphProperties").path("paragraphStyleId").asText());}
    private static boolean samePart(JsonNode a,JsonNode b){return !a.path("partSha256").asText().isEmpty()&&a.path("sourceSha256").equals(b.path("sourceSha256"))&&a.path("partName").equals(b.path("partName"))&&a.path("partSha256").equals(b.path("partSha256"));}
    private static boolean sameStory(JsonNode a,JsonNode b){return samePart(a.path("sourcePosition"),b.path("sourcePosition"))&&a.path("storyKind").equals(b.path("storyKind"))&&a.path("storyRootNodePath").equals(b.path("storyRootNodePath"));}
    private static String text(JsonNode member){return member.path("textObservation").path("text").asText();}
    private static JsonNode numbering(JsonNode member){return member.path("paragraphProperties").path("layoutEvidence").path("numbering");}
    private static String listKey(JsonNode number){return number.path("numId").asText()+"|"+number.path("level").asText()+"|"+number.path("format").asText();}
    private static String positionKey(JsonNode position,String path){return position.path("sourceSha256").asText()+"\n"+position.path("partName").asText()+"\n"+position.path("partSha256").asText()+"\n"+path;}
}
