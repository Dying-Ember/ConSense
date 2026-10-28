package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Saved native point evidence and deterministic Auto replies only; no product, parser or model calls. */
class DraftInsertionPointEvidenceTest {
    private static final String SHA=String.join("",Collections.nCopies(64,"a")),OTHER=String.join("",Collections.nCopies(64,"b")),HOST="Bill No. 3 - ";

    @Test void initialGroupSuppliesOnlyValidatedHostNavigationRatherThanTheWholeParagraph() {
        ObjectNode source=source();String before=source.toString();JsonNode packet=tree(packet(source)),scope=scopeItem(packet,"bill-scope");
        assertEquals("bill_description",scope.path("fieldLabel").asText());assertTrue(scope.path("insertionPoint").asBoolean());assertEquals("bill-host",scope.path("memberId").asText());
        assertEquals("",scope.path("text").asText());assertFalse(scope.path("scopeComplete").asBoolean());assertTrue(scope.path("sourceInsertionAnchorComplete").asBoolean());
        for(String field:Arrays.asList("ranges","insertionAnchors","sourcePosition","native","paragraphId"))assertFalse(scope.has(field),field);
        assertFalse(packet.toString().contains(HOST));assertEquals(before,source.toString());
    }

    @Test void scopeReadPreservesTheZeroPointAndHostTextRequiresItsOwnMemberRead() {
        ObjectNode source=source();String before=source.toString();JsonNode context=tree(context(source,"scope","bill-scope")),point=context.path("items").path(0);
        assertTrue(context.path("issues").isEmpty());assertEquals(1,context.path("items").size());assertEquals("",point.path("text").asText());assertFalse(point.path("scopeComplete").asBoolean());assertFalse(point.path("editableAuthority").asBoolean());
        assertEquals("bill-host",point.path("memberId").asText());assertTrue(point.path("insertionPoint").asBoolean());JsonNode range=point.path("ranges").path(0);
        assertEquals("preNormalizedTextObservation.text",range.path("channel").asText());assertEquals(HOST.length(),range.path("start").asInt());assertEquals(range.path("start"),range.path("end"));assertEquals("utf16",range.path("offsetUnit").asText());assertFalse(point.toString().contains(HOST));
        for(String field:Arrays.asList("native","nativeBinding","sourcePosition","action"))assertFalse(point.has(field),field);
        JsonNode member=tree(context(source,"member","bill-host")).path("items").path(0);assertEquals(HOST+"\n",member.path("text").asText());assertEquals("member",member.path("kind").asText());assertFalse(member.path("editableAuthority").asBoolean());assertEquals(before,source.toString());
    }

    @Test void autoCanReadScopeThenHostWithinThreeCallsAndKeepTheUnknownBillInsertionPending() {
        ObjectNode source=source();String before=source.toString();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};String[] memberAlias={null};
        Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{
            JsonNode wire=JsonUtils.parse(user),group=wire.has("group")?wire.path("group"):wire,instruction=group.path("instructions").path(0),scope=scopeItem(group,instruction.path("scopeObligationIds").path(0).asText());String owner=instruction.path("sourceTaskId").asText(),scopeAlias=scope.path("id").asText();
            int call=++calls[0];if(call==1){assertTrue(scope.path("insertionPoint").asBoolean());memberAlias[0]=scope.path("memberId").asText();assertTrue(memberAlias[0].matches("m[0-9]+"));assertFalse(user.contains(HOST));return request(owner,"scope",scopeAlias);}
            JsonNode retainedPoint=contextItem(wire,owner,"scope",scopeAlias);assertEquals("",retainedPoint.path("text").asText());assertEquals(memberAlias[0],retainedPoint.path("memberId").asText());assertEquals(HOST.length(),retainedPoint.path("ranges").path(0).path("start").asInt());assertEquals(retainedPoint.path("ranges").path(0).path("start"),retainedPoint.path("ranges").path(0).path("end"));assertEquals("preNormalizedTextObservation.text",retainedPoint.path("ranges").path(0).path("channel").asText());assertEquals("utf16",retainedPoint.path("ranges").path(0).path("offsetUnit").asText());assertFalse(retainedPoint.path("scopeComplete").asBoolean());
            if(call==2)return request(owner,"member",memberAlias[0]);
            assertEquals(3,call);JsonNode item=contextItem(wire,owner,"member",memberAlias[0]);assertEquals(HOST+"\n",item.path("text").asText());return pending(group,"check");
        },plan,group(plan),true);
        assertEquals(3,calls[0]);assertEquals(3,receipt.get("modelCalls"));assertTrue(list(receipt.get("stopReasons")).isEmpty(),JsonUtils.write(receipt.get("issues")));
        Map<String,Object> target=target(receipt);assertEquals("check",target.get("action"));assertEquals(true,target.get("needsRebind"));assertEquals(false,target.get("scopeComplete"));assertEquals("",target.get("text"));assertEquals("bill-scope",target.get("sourceScopeId"));
        Map<String,Object> span=map(list(map(target.get("native")).get("spans")).get(0));assertEquals(HOST.length(),span.get("start"));assertEquals(span.get("start"),span.get("end"));assertEquals("insert",span.get("operation"));assertEquals("paragraph-bill-host",span.get("paragraphId"));assertEquals(SHA,span.get("sourceSha256"));assertEquals(scope(source),tree(target.get("sourceScopeObservation")));assertEquals(before,source.toString());
    }

    @Test void invalidOrUnprovenPointsNeitherReturnScopeEvidenceNorLeakHostNavigation() {
        for(String defect:Arrays.asList("stale-member","stale-anchor","duplicate-member","duplicate-paragraph","duplicate-scope","multiple-anchors","negative","outside","nonzero","fractional","wrong-channel","missing-channel","nontext-channel","missing-unit","byte-unit","nontext-unit","normalized-host","surrogate","foreign-member","pdf","unbound","missing-analysis")) {
            ObjectNode source=source(),scope=scope(source),anchor=(ObjectNode)scope.path("insertionAnchors").path(0),host=host(source);
            if("stale-member".equals(defect))((ObjectNode)host.path("sourcePosition")).put("sourceSha256",OTHER);
            if("stale-anchor".equals(defect))((ObjectNode)anchor.path("sourcePosition")).put("sourceSha256",OTHER);
            if("duplicate-member".equals(defect))source.withArray("sourceMembers").add(host.deepCopy());
            if("duplicate-paragraph".equals(defect))source.withArray("paragraphs").add(source.path("paragraphs").path(1).deepCopy());
            if("duplicate-scope".equals(defect))source.withArray("groups").add(scope.deepCopy());
            if("multiple-anchors".equals(defect))scope.withArray("insertionAnchors").add(anchor.deepCopy());
            if("negative".equals(defect))anchor.put("start",-1).put("end",-1);
            if("outside".equals(defect))anchor.put("start",HOST.length()+2).put("end",HOST.length()+2);
            if("nonzero".equals(defect))anchor.put("end",HOST.length()+1);
            if("fractional".equals(defect))anchor.put("start",HOST.length()+0.5);
            if("wrong-channel".equals(defect)){anchor.put("channel","deletedText");((ObjectNode)host.path("textObservation")).put("deletedText",HOST);}
            if("missing-channel".equals(defect))anchor.remove("channel");if("nontext-channel".equals(defect))anchor.put("channel",true);
            if("missing-unit".equals(defect))anchor.remove("offsetUnit");if("byte-unit".equals(defect))anchor.put("offsetUnit","bytes");if("nontext-unit".equals(defect))anchor.put("offsetUnit",16);
            if("normalized-host".equals(defect))((ObjectNode)host.path("preNormalizedTextObservation")).put("text",HOST.replace("Bill No.","Bill  No.")+"\n");
            if("surrogate".equals(defect)){((ObjectNode)host.path("textObservation")).put("text","\uD83D\uDE00");((ObjectNode)host.path("preNormalizedTextObservation")).put("text","\uD83D\uDE00\n");((ObjectNode)source.path("paragraphs").path(1)).put("text","\uD83D\uDE00");anchor.put("start",1).put("end",1);}
            if("foreign-member".equals(defect))anchor.put("memberId","outside-this-source");if("pdf".equals(defect))source.put("format","pdf");if("unbound".equals(defect))source.put("evidenceStatus","unbound");if("missing-analysis".equals(defect))source.remove("analysisSha256");
            assertTrue(DraftReviewDiscovery.insertionPointContext(source,scope).isEmpty(),defect);JsonNode context=tree(context(source,"scope","bill-scope"));assertTrue(context.path("items").isEmpty(),defect);assertFalse(context.toString().contains(HOST),defect);
        }
    }

    @Test void mixedRangesCannotVerifyOnePointAndProjectAnotherAnchor() {
        for(String bad:Arrays.asList("foreign","outside","stale"))for(boolean badAnchor:Arrays.asList(true,false)) {
            ObjectNode source=source(),scope=scope(source),anchor=(ObjectNode)scope.path("insertionAnchors").path(0),range=anchor.deepCopy();scope.putArray("memberRanges").add(range);ObjectNode changed=badAnchor?anchor:range;
            if("foreign".equals(bad))changed.put("memberId","outside-this-source");if("outside".equals(bad))changed.put("start",HOST.length()+2).put("end",HOST.length()+2);if("stale".equals(bad))((ObjectNode)changed.path("sourcePosition")).put("sourceSha256",OTHER);
            assertTrue(DraftReviewDiscovery.insertionPointContext(source,scope).isEmpty(),bad+" badAnchor="+badAnchor);assertTrue(list(context(source,"scope","bill-scope").get("items")).isEmpty(),bad+" badAnchor="+badAnchor);
        }
        ObjectNode source=source(),scope=scope(source);scope.putArray("memberRanges").add(scope.path("insertionAnchors").path(0).deepCopy());assertTrue(DraftReviewDiscovery.insertionPointContext(source,scope).isEmpty(),"Even agreeing mixed representations remain unproven at this narrow seam");
    }

    @Test void aCopiedOrForeignObservationCannotSubstituteAnotherValidNativePoint() {
        ObjectNode source=source(),copied=scope(source).deepCopy();((ObjectNode)copied.path("insertionAnchors").path(0)).put("start",HOST.length()-1).put("end",HOST.length()-1);
        assertTrue(DraftReviewDiscovery.insertionPointContext(source,copied).isEmpty());ObjectNode foreign=source();foreign.put("sourceSha256",OTHER);assertTrue(DraftReviewDiscovery.insertionPointContext(foreign,scope(source)).isEmpty());
        JsonNode denied=tree(DraftDiscoveryEvidence.context(source,tree(Collections.singletonList(obj("sourceId","foreign-source","kind","scope","id","bill-scope")))));assertTrue(denied.path("items").isEmpty());
    }

    @Test void hostNavigationIsOwnerLocalAndNeverBecomesAScopeEditAlias() {
        ObjectNode source=source();add(source,"peer","A different source instruction.","Peer Bill No. 4 - ");Map<String,Object> packet=packet(source);DraftDiscoveryReferences refs=DraftDiscoveryReferences.group(packet);JsonNode wire=refs.encode(packet),first=wire.path("instructions").path(0),peer=wire.path("instructions").path(1),scope=scopeItem(wire,first.path("scopeObligationIds").path(0).asText());String host=scope.path("memberId").asText();
        assertTrue(host.matches("m[0-9]+"));JsonNode denied=refs.decode(JsonUtils.parse(request(peer.path("sourceTaskId").asText(),"member",host)));assertTrue(denied.path("sourceContextRequests").path(0).path("id").asText().startsWith("unresolved-source-alias:"));
        JsonNode wrongScope=refs.decode(tree(obj("scopeRules",Collections.singletonList(obj("id","bad","sourceTaskId",first.path("sourceTaskId").asText(),"scopeId",host,"action","delete")))));assertTrue(wrongScope.path("scopeRules").path(0).path("scopeId").asText().startsWith("unresolved-source-alias:"));
    }

    @Test void readingAZeroPointDoesNotTurnADeleteProposalIntoParagraphAuthority() {
        DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source()));Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{JsonNode wire=JsonUtils.parse(user);return pending(wire.has("group")?wire.path("group"):wire,"delete");},plan,group(plan),true);
        Map<String,Object> target=target(receipt);assertEquals("check",target.get("action"));assertEquals("delete",target.get("proposedAction"));assertEquals(true,target.get("needsRebind"));assertEquals(false,target.get("scopeComplete"));assertEquals("",target.get("text"));Map<String,Object> span=map(list(map(target.get("native")).get("spans")).get(0));assertEquals(span.get("start"),span.get("end"));assertEquals("insert",span.get("operation"));
    }

    @Test void invalidInsertionCoordinatesCannotBypassReadChecksThroughActualAutoCompilation() {
        for(String defect:Arrays.asList("mixed-foreign-anchor","mixed-outside-anchor","mixed-stale-anchor","mixed-bad-member-range","missing-unit","byte-unit","nontext-unit")){
            ObjectNode source=source(),scope=scope(source),anchor=(ObjectNode)scope.path("insertionAnchors").path(0);
            if(defect.startsWith("mixed-")){
                ObjectNode range=anchor.deepCopy();scope.putArray("memberRanges").add(range);
                if("mixed-foreign-anchor".equals(defect))anchor.put("memberId","outside-this-source");if("mixed-outside-anchor".equals(defect))anchor.put("start",HOST.length()+2).put("end",HOST.length()+2);if("mixed-stale-anchor".equals(defect))((ObjectNode)anchor.path("sourcePosition")).put("sourceSha256",OTHER);if("mixed-bad-member-range".equals(defect))range.put("memberId","outside-this-source");
            }
            if("missing-unit".equals(defect))anchor.remove("offsetUnit");if("byte-unit".equals(defect))anchor.put("offsetUnit","bytes");if("nontext-unit".equals(defect))anchor.put("offsetUnit",16);
            String before=source.toString();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));int[] calls={0};Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{calls[0]++;JsonNode wire=JsonUtils.parse(user);return pending(wire.has("group")?wire.path("group"):wire,"insert");},plan,group(plan),true);
            Map<String,Object> target=target(receipt);assertEquals("check",target.get("action"),defect);assertEquals("insert",target.get("proposedAction"),defect);assertEquals(true,target.get("needsRebind"),defect);assertEquals(false,target.get("scopeComplete"),defect);assertTrue(list(map(target.get("native")).get("spans")).isEmpty(),defect);assertNotEquals("bound",map(target.get("nativeBinding")).get("status"),defect);assertTrue(calls[0]<=DraftAutomaticDiscovery.GROUP_MAX_CALLS);assertEquals(before,source.toString(),defect);
        }
    }

    @Test void aValidCurrentInsertionStillReachesTheFinalTargetAsAPendingPointCandidate() {
        ObjectNode source=source();String before=source.toString();DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(Collections.singletonList(source));Map<String,Object> receipt=DraftAutomaticDiscovery.processGroup((system,user)->{JsonNode wire=JsonUtils.parse(user);return pending(wire.has("group")?wire.path("group"):wire,"insert");},plan,group(plan),true);
        Map<String,Object> child=map(list(receipt.get("taskReceipts")).get(0)),node=map(list(child.get("nodes")).get(0)),target=target(receipt);assertEquals("insert",target.get("action"));assertEquals("insert",target.get("proposedAction"));assertEquals(false,target.get("needsRebind"));assertEquals(true,target.get("scopeComplete"));assertEquals("pending",node.get("status"));assertNotEquals("bound",map(target.get("nativeBinding")).get("status"));assertFalse(target.containsKey("replacementEn"));assertEquals("",target.get("text"));
        Map<String,Object> span=map(list(map(target.get("native")).get("spans")).get(0));assertEquals("paragraph-bill-host",span.get("paragraphId"));assertEquals(HOST.length(),span.get("start"));assertEquals(span.get("start"),span.get("end"));assertEquals("insert",span.get("operation"));assertEquals("",span.get("expectedText"));assertEquals(before,source.toString());
    }

    private static String pending(JsonNode group,String action){JsonNode instruction=group.path("instructions").path(0);String owner=instruction.path("sourceTaskId").asText();Map<String,Object> rule=obj("id","point-review","sourceTaskId",owner,"scopeId",instruction.path("scopeObligationIds").path(0).asText(),"action",action);if(!"check".equals(action))rule.put("when",obj("mode","all","clauses",Collections.singletonList(obj("inputId","billNos","operator","notempty"))));return JsonUtils.write(obj("nodes",Collections.emptyList(),"requirements",Collections.singletonList(obj("id","bill-reference","sourceTaskId",owner,"sourceQuote",instruction.path("text").asText(),"kind","reference","nodeIds",Collections.singletonList("billNos"))),"scopeRules",Collections.singletonList(rule),"taskOutcomes",Collections.singletonList(obj("sourceTaskId",owner,"status","unresolved","reason","Project Bill values and the insertion treatment still need human review.")),"notes",Collections.emptyList()));}
    private static String request(String owner,String kind,String id){return JsonUtils.write(obj("sourceContextRequests",Collections.singletonList(obj("sourceTaskId",owner,"kind",kind,"id",id))));}
    private static JsonNode contextItem(JsonNode wire,String owner,String kind,String id){JsonNode found=null;for(JsonNode context:wire.path("supplement").path("currentSourceContext"))if(owner.equals(context.path("sourceTaskId").asText()))for(JsonNode item:context.path("context").path("items"))if(kind.equals(item.path("kind").asText())&&id.equals(item.path("id").asText())){assertNull(found,"The owner's requested evidence identity must be unique");assertEquals("1",item.path("sourceId").asText());assertEquals(SHA,item.path("sourceSha256").asText());assertEquals("current-analysis",item.path("analysisRevision").asText());assertEquals(SHA,item.path("analysisInputSha256").asText());assertEquals(SHA,item.path("analysisSha256").asText());found=item;}assertNotNull(found,"Missing owner-local "+kind+" "+id);return found;}
    private static JsonNode tree(Object value){return JsonUtils.mapper().valueToTree(value);}
    private static Map<String,Object> context(ObjectNode source,String kind,String id){return DraftDiscoveryEvidence.context(source,tree(Collections.singletonList(obj("kind",kind,"id",id))));}
    private static Map<String,Object> packet(ObjectNode source){return DraftDiscoveryGroups.payload(Collections.singletonMap("1",source),DraftReviewDiscovery.sourceTasks(Collections.singletonList(source)),Collections.emptyList());}
    private static JsonNode scopeItem(JsonNode packet,String id){for(JsonNode row:packet.path("sharedEvidence"))if("scope".equals(row.path("kind").asText())&&id.equals(row.path("id").asText()))return row;throw new AssertionError(id);}
    private static Map<String,Object> group(DraftAutomaticDiscovery.Plan plan){List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);assertEquals(1,groups.size());return groups.get(0);}
    private static Map<String,Object> target(Map<String,Object> receipt){return map(list(map(list(map(list(receipt.get("taskReceipts")).get(0)).get("nodes")).get(0)).get("editTargets")).get(0));}
    private static ObjectNode scope(ObjectNode source){return (ObjectNode)source.path("groups").path(0);}
    private static ObjectNode host(ObjectNode source){return (ObjectNode)source.path("sourceMembers").path(1);}
    private static ObjectNode source(){ObjectNode source=JsonUtils.mapper().createObjectNode().put("id","1").put("sourceDocumentId",1).put("fileKey","SCT").put("format","docx").put("sourceSha256",SHA).put("evidenceStatus","bound").put("analysisRevision","current-analysis").put("analysisInputSha256",SHA).put("analysisSha256",SHA);for(String collection:Arrays.asList("guidanceNotes","groups","conditions","sourceMembers","paragraphs","markerCandidates"))source.putArray(collection);add(source,"bill",DraftBusinessRules.standardParagraph("SCT",189)+"\n"+DraftBusinessRules.standardParagraph("SCT",190),HOST);return source;}
    private static void add(ObjectNode source,String prefix,String instruction,String body){
        String noteId=prefix+"-note",noteMember=prefix+"-note-member",hostId=prefix+"-host";ObjectNode note=source.withArray("guidanceNotes").addObject().put("id",noteId).put("text",instruction).put("captureComplete",true);note.putArray("orderedMemberIds").add(noteMember);note.putArray("memberRanges").addObject().put("memberId",noteMember).put("channel","text").put("start",0).put("end",instruction.length());member(source,noteMember,instruction,false);ObjectNode host=member(source,hostId,body,true);
        ObjectNode scope=source.withArray("groups").addObject().put("id",prefix+"-scope").put("kind","native_source_field_candidate").put("fieldLabel","bill_description").put("captureComplete",true).put("scopeComplete",false).put("sourceFieldRangeComplete",false).put("sourceInsertionAnchorComplete",true).put("listCardinalityStatus","unknown_from_existing_numbered_source_rows");scope.putArray("guidanceNoteIds").add(noteId);scope.putArray("orderedMemberIds").add(hostId);ObjectNode point=scope.putArray("insertionAnchors").addObject().put("memberId",hostId).put("channel","preNormalizedTextObservation.text").put("start",body.length()).put("end",body.length()).put("offsetUnit","utf16").put("observedText","");point.set("sourcePosition",host.path("sourcePosition").deepCopy());
    }
    private static ObjectNode member(ObjectNode source,String id,String text,boolean parserNewline){ObjectNode member=source.withArray("sourceMembers").addObject().put("id",id).put("identityStatus","source_bound");member.putObject("textObservation").put("text",text);if(parserNewline)member.putObject("preNormalizedTextObservation").put("status","observed_parser_channels_before_whitespace_normalization").put("text",text+"\n");member.putObject("sourcePosition").put("sourceSha256",SHA).put("partName","word/document.xml").put("partSha256",SHA).put("nodePath","/current/"+id);ObjectNode paragraph=source.withArray("paragraphs").addObject().put("id","paragraph-"+id).put("text",text).put("sourceSha256",SHA).put("paragraphSha256",DraftAdoption.hash(text));paragraph.set("sourcePosition",member.path("sourcePosition").deepCopy());return member;}
}
