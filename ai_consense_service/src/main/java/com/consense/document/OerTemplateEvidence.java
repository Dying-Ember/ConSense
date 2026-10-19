package com.consense.document;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Template evidence assembled from an existing parse; no source, renderer or model is invoked. */
public final class OerTemplateEvidence {
    public static final String SCHEMA="consense.drafting-template-evidence.v1";
    public static final String ANALYSIS_REVISION="native-template-rules-20261019-r21";
    private static final Pattern GUIDANCE=Pattern.compile("(?i)\\bguidance\\s+notes?(?:\\s+to\\s+(?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*)?\\s*:");
    private static final Pattern EDITORIAL=Pattern.compile("(?i)\\b(?:delete|amend|insert|select|adopt|project team|for use|for tenders|alternative|option|not used|check|fill in)\\b");
    private static final Pattern ALTERNATIVE=Pattern.compile("(?i)^\\s*(?:\\(|\\[)?\\s*(alternative|option)\\s*(?:\\(([a-z0-9]+)\\)|([a-z0-9]+))(?=\\s|[-:.)]|$)");
    private static final Pattern CLAUSE=Pattern.compile("^\\s*[*#]*\\s*((?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*)(?=\\D|$)");
    private static final Pattern CELL_NUMBER=Pattern.compile("^\\s*[*#]*\\s*(\\d+)\\.\\s*$");
    private static final Pattern REFERENCE=Pattern.compile("(?i)\\b(?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*");
    private static final Pattern SUBITEM=Pattern.compile("^\\s*[*#]*\\s*((?:\\([a-z0-9]+\\)\\s*)+)",Pattern.CASE_INSENSITIVE);
    private static final Pattern ENUMERATED_TARGETS=Pattern.compile("(?is)\\bin\\s+(?:the\\s+)?clauses?\\s+(.+?)\\s+(?:are|is)\\s+to\\s+be\\s+(?:deleted|amended|replaced)");
    private OerTemplateEvidence() { }

    /** Source syntax projection for ordinary consumers; the template r15 analysis remains unchanged. */
    public static ObjectNode nativeClauseRanges(JsonNode inventory) {
        String source=sha(inventory.path("sourceSha256").asText());ObjectNode out=object();out.put("version","docx-source-clause-scopes-v1");out.put("sourceSha256",source);
        if(inventory.path("storyRoots").isArray())out.set("storyRoots",inventory.path("storyRoots").deepCopy());
        ArrayNode ranges=out.putArray("ranges"),stored=out.putArray("members"),issues=out.putArray("issues");List<Member> members=new ArrayList<>();Map<String,Integer> idCounts=new HashMap<>();Map<String,Set<String>> partRevisions=new HashMap<>();
        for(JsonNode original:inventory.path("paragraphs")){idCounts.merge(original.path("id").asText(),1,Integer::sum);JsonNode position=original.path("sourcePosition");partRevisions.computeIfAbsent(position.path("partName").asText(),ignored->new HashSet<>()).add(position.path("partSha256").asText().toLowerCase(Locale.ROOT));}
        for(JsonNode original:inventory.path("paragraphs")) {
            ObjectNode value=original.deepCopy();JsonNode position=value.path("sourcePosition");boolean bound=source.equals(position.path("sourceSha256").asText())&&!value.path("id").asText().isEmpty()&&idCounts.get(value.path("id").asText())==1&&partRevisions.get(position.path("partName").asText()).size()==1&&!position.path("partName").asText().isEmpty()&&position.path("partSha256").asText().matches("(?i)[a-f0-9]{64}")&&!position.path("nodePath").asText().isEmpty()&&!value.path("storyKind").asText().isEmpty()&&!value.path("storyRootNodePath").asText().isEmpty();
            value.put("sourceClauseClassification",bound?"source_bound_native_member":"source_identity_unknown");stored.add(value);members.add(new Member(value,bound));
            if(!bound)issue(issues,"SOURCE_CLAUSE_MEMBER_UNBOUND",value.path("id").asText(),"The source member identity is missing, duplicated, foreign or inconsistent with the observed part revision");
        }
        ParseCoverage coverage=new ParseCoverage();coverage.setNativeDocxParagraphs(inventory);
        String profile=detectFamily(new DocumentParser.ParsedDocument("",Collections.emptyList(),0,false,null,Collections.emptyList(),coverage,source,Collections.emptyList()));
        if(profile==null){out.putNull("familyCandidate");issue(issues,"SOURCE_CLAUSE_FAMILY_UNRESOLVED",null,"No unique short native NTT/SCT/SCC title was observed");return out;}out.put("familyCandidate",profile);
        ArrayNode notes=array();guidance(profile,members,notes,array(),array(),issues);Set<String> noteMembers=noteMemberIds(notes),unknownNotes=new HashSet<>();
        for(JsonNode note:notes)if(!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean())for(JsonNode id:note.path("orderedMemberIds"))unknownNotes.add(id.asText());
        ArrayNode branches=array();List<Anchor> observedClauses=clauseAnchors(profile,members);
        if("SCC".equals(profile))bodyOptionScopes(profile,members,observedClauses,notes,noteMembers,branches,array());else completeAlternatives(profile,members,observedClauses,notes,branches,array());
        ArrayNode frames=out.putArray("referenceFrames");for(JsonNode branch:branches)if(branch.path("scopeComplete").asBoolean())frames.add(sourceBranchFrame(branch,members));
        Set<String> continuationHeadings=new HashSet<>();List<Anchor> roots=new ArrayList<>();for(Anchor anchor:observedClauses) {
            Anchor previous=roots.isEmpty()?null:roots.get(roots.size()-1);
            if(previous!=null&&previous.label.equals(anchor.label)&&sameStory(previous.member,anchor.member)&&sourceClauseContinuation(anchor,previous,frames,members)){continuationHeadings.add(anchor.member.id);continue;}
            roots.add(anchor);
        }
        Map<String,Integer> occurrences=new LinkedHashMap<>();for(Anchor root:roots)occurrences.merge(root.member.story+"|"+root.member.part+"|"+root.label+"|"+frameId(frames,root.member.id),1,Integer::sum);
        Map<Anchor,ObjectNode> rootRanges=new IdentityHashMap<>();Map<Anchor,Integer> rootEnds=new IdentityHashMap<>();
        for(Anchor owner:roots) {
            int localEnd=members.size(),wholeEnd=members.size();for(Anchor next:roots)if(next.ordinal>owner.ordinal&&sameStory(owner.member,next.member)) {
                if(localEnd==members.size())localEnd=next.ordinal;
                if(!next.label.startsWith(owner.label+".")){wholeEnd=next.ordinal;break;}
            }
            boolean ambiguous=occurrences.get(owner.member.story+"|"+owner.member.part+"|"+owner.label+"|"+frameId(frames,owner.member.id))>1;
            if(sourceContinuationHeading(owner.member,members))issue(issues,"SOURCE_CLAUSE_CONTINUATION_WITHOUT_START",owner.label,"A continuation heading without an earlier same-clause source start does not establish the complete clause");
            if(ambiguous)issue(issues,"SOURCE_CLAUSE_REFERENCE_AMBIGUOUS",owner.label,"Repeated non-continuation source headings do not establish one unique clause reference");
            JsonNode storyEnd=observedStoryEnd(inventory,owner.member);
            List<Unit> local=units(owner,localEnd,members,noteMembers,frames);Map<String,Integer> repeated=new HashMap<>();for(Unit unit:local)repeated.merge(unit.reference,1,Integer::sum);
            Set<SourceListFrame> storedFrames=Collections.newSetFromMap(new IdentityHashMap<>());for(Unit unit:local)if(repeated.get(unit.reference)>1&&unit.listFrame!=null&&storedFrames.add(unit.listFrame))frames.add(sourceListFrame(source,unit.listFrame,members,notes,noteMembers,storyEnd,frames));
            Map<Unit,ObjectNode> projected=new IdentityHashMap<>();Map<String,Integer> unitCounts=new HashMap<>();for(Unit unit:local)unitCounts.merge(unit.reference+"|"+frameId(frames,members.get(unit.start).id),1,Integer::sum);
            for(Unit unit:local) {
                if(unit.depth==0)unit.end=wholeEnd;
                String frame=frameId(frames,members.get(unit.start).id);JsonNode frameEnd=limitUnitToFrame(unit,frame,frames,members);ObjectNode range=sourceClauseRange(profile,source,owner,unit,storyEnd,members,notes,noteMembers,unknownNotes,continuationHeadings,ambiguous||unitCounts.get(unit.reference+"|"+frame)>1,frameEnd);
                if(!frame.isEmpty())range.put("sourceFrameId",frame);
                ranges.add(range);projected.put(unit,range);if(unit.depth==0){rootRanges.put(owner,range);rootEnds.put(owner,unit.end);}
            }
            for(Unit unit:local)if(unit.depth>0){String parent=unit.reference.replaceFirst("\\([^()]*\\)$","");List<Unit> matches=new ArrayList<>();for(Unit candidate:local)if(candidate.reference.equals(parent)&&candidate.start<=unit.start&&unit.start<candidate.end)matches.add(candidate);if(matches.size()==1)projected.get(unit).put("parentId",projected.get(matches.get(0)).path("id").asText());}
        }
        for(Anchor owner:roots) {
            Anchor parent=null;boolean conflict=false;for(Anchor candidate:roots)if(candidate!=owner&&sameStory(owner.member,candidate.member)&&owner.label.startsWith(candidate.label+".")&&candidate.ordinal<owner.ordinal&&owner.ordinal<rootEnds.get(candidate)) {
                if(occurrences.get(candidate.member.story+"|"+candidate.member.part+"|"+candidate.label+"|"+frameId(frames,candidate.member.id))>1){conflict=true;continue;}
                if(parent==null||candidate.label.length()>parent.label.length())parent=candidate;
            }
            if(parent!=null&&!conflict)rootRanges.get(owner).put("parentId",rootRanges.get(parent).path("id").asText());
        }
        return out;
    }

    private static ObjectNode sourceBranchFrame(JsonNode branch,List<Member> members){
        ObjectNode frame=object();frame.put("id",branch.path("id").asText());frame.put("kind","explicit_native_branch");frame.put("label",branch.path("label").asText());frame.put("status","source_syntax_complete");frame.put("scopeComplete",true);frame.put("automaticEditEligible",false);frame.put("semanticApplicabilityVerified",false);
        frame.set("orderedMemberIds",branch.path("orderedMemberIds").deepCopy());ArrayNode context=frame.putArray("contextMemberIds"),anchors=frame.putArray("anchorMemberIds");
        for(String key:Arrays.asList("guidanceContextMemberIds","parentContextMemberIds","interstitialContextMemberIds"))for(JsonNode id:branch.path(key))appendUnique(context,id.asText());
        for(JsonNode id:branch.path("continuationAnnotationMemberIds"))appendUnique(anchors,id.asText());if(anchors.isEmpty())anchors.add(branch.path("startBoundary").path("memberId").asText());
        int begin=indexOf(members,branch.path("startBoundary").path("memberId").asText()),end=indexOf(members,branch.path("endBoundary").path("memberId").asText());
        if(begin>=0&&end>=begin){
            Set<String> body=new HashSet<>();for(JsonNode id:frame.path("orderedMemberIds"))body.add(id.asText());
            for(int at=begin;at<end;at++){Member member=members.get(at);if(sameStory(members.get(begin),member)&&nativePureSpacer(member))body.add(member.id);}
            ArrayNode ordered=frame.putArray("orderedMemberIds");for(Member member:members)if(body.contains(member.id))ordered.add(member.id);
            for(int at=context.size()-1;at>=0;at--)if(body.contains(context.get(at).asText()))context.remove(at);
            for(int at=begin;at<end;at++)if(sameStory(members.get(begin),members.get(at))&&!body.contains(members.get(at).id))appendUnique(context,members.get(at).id);
        }
        for(String key:Arrays.asList("startBoundary","endBoundary")){ObjectNode boundary=branch.path(key).deepCopy();int at=indexOf(members,boundary.path("memberId").asText());if(at>=0)boundary.set("sourcePosition",members.get(at).value.path("sourcePosition").deepCopy());frame.set(key,boundary);}return frame;
    }

    private static boolean nativePureSpacer(Member member){
        JsonNode empty=member.value.path("nativeContentEmpty"),text=member.value.path("textObservation");if(!member.usable||!empty.isBoolean()||!empty.asBoolean())return false;
        for(String channel:Arrays.asList("text","originalText","deletedText","strikeText"))if(!text.path(channel).isTextual()||!text.path(channel).asText().trim().isEmpty())return false;return true;
    }

    private static JsonNode limitUnitToFrame(Unit unit,String id,JsonNode frames,List<Member> members){
        if(id.isEmpty())return null;for(JsonNode frame:frames)if(id.equals(frame.path("id").asText())){
            JsonNode boundary=frame.path("endBoundary");int end=indexOf(members,boundary.path("memberId").asText());Member first=members.get(unit.start);
            if(!frame.path("scopeComplete").isBoolean()||!frame.path("scopeComplete").booleanValue()||!"source_syntax_complete".equals(frame.path("status").asText())){unit.frameBoundaryUnknown=true;return null;}
            if(end<0){JsonNode position=boundary.path("sourcePosition"),own=first.value.path("sourcePosition");unit.frameBoundaryUnknown=!"native_container_end".equals(boundary.path("kind").asText())||!own.path("sourceSha256").equals(position.path("sourceSha256"))||!own.path("partName").equals(position.path("partName"))||!own.path("partSha256").equals(position.path("partSha256"))||!first.value.path("storyRootNodePath").equals(position.path("nodePath"));return null;}
            if(end<unit.start||!sameStory(first,members.get(end))||!members.get(end).usable||!members.get(end).value.path("sourcePosition").equals(boundary.path("sourcePosition"))){unit.frameBoundaryUnknown=true;return null;}
            boolean paragraphEnd=false;if(boundary.has("offset")){JsonNode offset=boundary.path("offset");if(!offset.isIntegralNumber()||!offset.canConvertToInt()||(offset.asInt()!=0&&offset.asInt()!=members.get(end).text.length())){unit.frameBoundaryUnknown=true;return null;}paragraphEnd=offset.asInt()>0;if(paragraphEnd)end++;}
            if(end<=unit.start){unit.frameBoundaryUnknown=true;return null;}
            if(unit.sharedTailStart>=0&&unit.sharedTailEnd>end){unit.sharedTailEnd=end;if(unit.sharedTailStart>=end){unit.sharedTailStart=-1;unit.sharedTailEnd=-1;}}
            if(end<unit.end||paragraphEnd&&end==unit.end){unit.end=end;return boundary;}return null;
        }return null;
    }

    private static String frameId(JsonNode frames,String memberId){JsonNode found=null;for(JsonNode frame:frames)if(frame.path("scopeComplete").asBoolean()&&hasId(frame.path("orderedMemberIds"),memberId)&&(found==null||frame.path("orderedMemberIds").size()<found.path("orderedMemberIds").size()))found=frame;return found==null?"":found.path("id").asText();}

    private static ObjectNode sourceListFrame(String source,SourceListFrame list,List<Member> members,ArrayNode notes,Set<String> noteMembers,JsonNode storyEnd,JsonNode frames){
        Member anchor=members.get(list.intro);ObjectNode frame=object();frame.put("id","source-list-frame:"+digest(source+"|"+anchor.id));frame.put("kind","explicit_native_list_introduction");frame.put("label","source-list:"+anchor.id);frame.put("scopeComplete",true);frame.put("status","source_syntax_complete");frame.put("automaticEditEligible",false);frame.put("semanticApplicabilityVerified",false);
        ArrayNode body=frame.putArray("orderedMemberIds"),context=frame.putArray("contextMemberIds");context.add(anchor.id);frame.putArray("anchorMemberIds").add(anchor.id);
        Unit interval=new Unit("",list.first,list.end,0);JsonNode branchEnd=null;
        for(JsonNode branch:frames)if("explicit_native_branch".equals(branch.path("kind").asText())&&hasId(branch.path("orderedMemberIds"),anchor.id)&&hasId(branch.path("orderedMemberIds"),members.get(list.first).id)){JsonNode boundary=limitUnitToFrame(interval,branch.path("id").asText(),frames,members);if(boundary!=null)branchEnd=boundary;}
        boolean known=anchor.usable&&!list.ownerAmbiguous&&!interval.frameBoundaryUnknown&&(interval.end<members.size()||storyEnd!=null);for(int at=list.first;at<interval.end;at++){Member member=members.get(at);if(!sameStory(anchor,member))continue;known&=member.usable;if(noteMembers.contains(member.id)||compilationCell(member,members,notes))context.add(member.id);else body.add(member.id);}
        for(String key:Arrays.asList("startBoundary","endBoundary")){int at=key.equals("startBoundary")?list.first:interval.end;ObjectNode boundary=frame.putObject(key);boundary.put("kind",key.equals("startBoundary")?"explicit_source_list_first_item":at<members.size()?"next_source_list_or_parent_boundary":"native_container_end");if(at<members.size()){boundary.put("memberId",members.get(at).id);boundary.set("sourcePosition",members.get(at).value.path("sourcePosition").deepCopy());}else if(storyEnd!=null)boundary.set("sourcePosition",storyEnd.path("sourcePosition").deepCopy());}if(branchEnd!=null)frame.set("endBoundary",branchEnd.deepCopy());frame.put("scopeComplete",known&&!body.isEmpty());frame.put("status",known&&!body.isEmpty()?"source_syntax_complete":"partial_unknown_scope");return frame;
    }

    private static ObjectNode sourceClauseRange(String profile,String source,Anchor owner,Unit unit,JsonNode storyEnd,List<Member> members,ArrayNode notes,Set<String> noteMembers,Set<String> unknownNotes,Set<String> continuationHeadings,boolean ambiguous,JsonNode frameEnd) {
        ObjectNode range=object();range.put("id","source-clause:"+digest(source+"|"+members.get(unit.start).id+"|"+unit.reference));range.put("reference",unit.reference);range.putNull("parentId");range.put("automaticEditEligible",false);range.put("semanticApplicabilityVerified",false);
        ArrayNode ids=range.putArray("orderedMemberIds"),context=range.putArray("contextMemberIds"),reasons=range.putArray("contextObservations");boolean known=(unit.end<members.size()||storyEnd!=null||frameEnd!=null)&&!ambiguous&&!sourceContinuationHeading(owner.member,members)&&!unit.ambiguousTail&&!unit.frameBoundaryUnknown&&(unit.sharedTailStart<0||unit.sharedTailEnd<members.size()||storyEnd!=null);
        Set<String> ownedFormulaTables=new HashSet<>();for(int n=unit.start;n<unit.end;n++){Member member=members.get(n);if(sameStory(owner.member,member)&&!member.table.isEmpty()&&!noteMembers.contains(member.id)&&member.text.matches("(?is).*\\b(?:formula|equation)\\s+(?:below|as\\s+follows)\\b.*"))ownedFormulaTables.add(member.table);}
        Member body=owner.member;if(owner.label.matches("\\d+")&&!body.row.isEmpty())for(int n=owner.ordinal+1;n<unit.end;n++){Member candidate=members.get(n);if(!candidate.row.equals(body.row)||!sameStory(body,candidate))break;if(!candidate.cell.isEmpty()&&!candidate.column.equals(body.column)&&!candidate.text.trim().isEmpty()&&!noteMembers.contains(candidate.id)){body=candidate;break;}}
        if(unit.depth>0&&!body.cell.isEmpty()&&!sameOuterLane(body,members.get(unit.start)))known=false;
        for(int n=unit.start;n<unit.end;n++) {
            Member member=members.get(n);if(!sameStory(owner.member,member))continue;known&=member.usable&&!unknownNotes.contains(member.id)&&(storyEnd==null||descendant(member.path,storyEnd.path("sourcePosition").path("nodePath").asText()));String reason=null;
            if(noteMembers.contains(member.id)){if(instructionOnly(member,notes))reason="source_compilation_instruction_context";else{reason="mixed_source_body_and_compilation_instruction_unknown";known=false;}}
            else if(compilationCell(member,members,notes))reason="source_compilation_instruction_context";
            else if(!member.text.trim().isEmpty()&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members)))reason="source_physical_furniture_context";
            else if(n!=owner.ordinal&&(continuationHeadings.contains(member.id)||sourceContinuationHeading(member,members)))reason="source_repeated_clause_heading_context";
            else if(sourceReferenceColumnContext(member,members))reason="source_native_reference_column_context";
            else if(!body.cell.isEmpty()&&!member.cell.isEmpty()&&!sameOuterLane(body,member)&&!ownedFormulaTables.contains(member.table)&&!(unit.depth==0&&n==owner.ordinal)){reason="parallel_native_cell_context";if(!member.text.trim().isEmpty())known=false;}
            if(reason==null)ids.add(member.id);else{context.add(member.id);reasons.addObject().put("memberId",member.id).put("reason",reason);}
        }
        if(unit.sharedTailStart>=0)for(int n=unit.sharedTailStart;n<unit.sharedTailEnd;n++){
            Member member=members.get(n);if(!sameStory(owner.member,member))continue;String reason=unit.ambiguousTail?"unresolved_shared_parent_tail_context":"shared_parent_predicate_context";
            if(noteMembers.contains(member.id)){if(instructionOnly(member,notes))reason="source_compilation_instruction_context";else{reason="mixed_source_body_and_compilation_instruction_unknown";known=false;}}
            else if(compilationCell(member,members,notes))reason="source_compilation_instruction_context";
            else if(pageFurniture(profile,member,members)||furnitureRow(profile,member,members))reason="source_physical_furniture_context";
            else if(continuationHeadings.contains(member.id)||sourceContinuationHeading(member,members))reason="source_repeated_clause_heading_context";
            else if(sourceReferenceColumnContext(member,members))reason="source_native_reference_column_context";
            appendUnique(context,member.id);reasons.addObject().put("memberId",member.id).put("reason",reason);known&=member.usable&&!unknownNotes.contains(member.id)&&(storyEnd==null||descendant(member.path,storyEnd.path("sourcePosition").path("nodePath").asText()));
        }
        range.put("scopeComplete",known&&ids.size()>0);range.put("status",ambiguous?"ambiguous_source_reference":known&&ids.size()>0?"source_syntax_complete":"partial_unknown_scope");range.put("scopeMeaning","Source-defined clause or enumeration membership only; context is separate and applicability remains unknown");
        range.putObject("startBoundary").put("kind","explicit_source_clause_or_subitem_label").put("memberId",members.get(unit.start).id).set("sourcePosition",members.get(unit.start).value.path("sourcePosition").deepCopy());
        ObjectNode end=range.putObject("endBoundary");if(frameEnd!=null){range.set("endBoundary",frameEnd.deepCopy());return range;}end.put("kind",unit.end<members.size()?"next_explicit_source_peer_or_owner_boundary":storyEnd!=null?"native_container_end":"source_story_end_unverified");if(unit.end<members.size()){end.put("memberId",members.get(unit.end).id);end.set("sourcePosition",members.get(unit.end).value.path("sourcePosition").deepCopy());}else if(storyEnd!=null)end.set("sourcePosition",storyEnd.path("sourcePosition").deepCopy());
        return range;
    }

    private static JsonNode observedStoryEnd(JsonNode inventory,Member owner){
        JsonNode found=null,own=owner.value.path("sourcePosition");
        for(JsonNode root:inventory.path("storyRoots"))if(root.path("storyKind").equals(owner.value.path("storyKind"))&&root.path("storyRootNodePath").equals(owner.value.path("storyRootNodePath"))&&root.path("sourcePosition").path("partName").equals(own.path("partName"))){
            if(found!=null)return null;found=root;
        }
        if(found==null||!"observed_loaded_native_root".equals(found.path("status").asText()))return null;
        JsonNode position=found.path("sourcePosition");
        return own.path("sourceSha256").equals(position.path("sourceSha256"))&&own.path("partSha256").equals(position.path("partSha256"))&&found.path("storyRootNodePath").equals(position.path("nodePath"))&&descendant(owner.path,position.path("nodePath").asText())?found:null;
    }

    private static boolean sourceContinuationHeading(Member member){return member.text.trim().matches("(?is).*\\(\\s*Cont[’']?d\\s*\\)\\.?$");}
    private static boolean sourceContinuationHeading(Member member,List<Member> members){
        if(sourceContinuationHeading(member))return true;
        if(member.row.isEmpty()||!CELL_NUMBER.matcher(member.text).matches())return false;
        for(Member peer:members)if(peer.row.equals(member.row)&&sameStory(member,peer)&&!peer.cell.equals(member.cell)&&sourceContinuationHeading(peer))return true;
        return false;
    }
    private static boolean sourceClauseContinuation(Anchor current,Anchor previous,JsonNode frames,List<Member> members){
        if(sourceContinuationHeading(current.member,members))return true;
        String frame=frameId(frames,current.member.id);if(frame.isEmpty()||!frame.equals(frameId(frames,previous.member.id))||!current.member.text.trim().equals(previous.member.text.trim()))return false;
        for(int at=current.ordinal+1;at<members.size();at++){Member member=members.get(at);if(!sameStory(current.member,member))continue;if(member.text.trim().isEmpty()||!sameLane(current.member,member))continue;return SUBITEM.matcher(member.text).find()&&sourceContinuationHeading(member);}return false;
    }


    public static JsonNode analyse(String fileKey,DocumentParser.ParsedDocument parsed) {
        if(parsed==null)throw new IllegalArgumentException("An actual parsed document is required");
        String detected=detectFamily(parsed);
        if(fileKey==null)fileKey=detected;
        if(fileKey==null)throw new IllegalArgumentException("TEMPLATE_FAMILY_UNRESOLVED: A native or parsed title is required");
        key(fileKey);
        if(detected!=null&&!fileKey.equals(detected))throw new IllegalArgumentException("TEMPLATE_FAMILY_MISMATCH: Explicit profile disagrees with the observed title");
        String source=sha(parsed.getSourceSha256());
        JsonNode blocks=JsonUtils.mapper().valueToTree(parsed.getBlocks());
        JsonNode coverage=JsonUtils.mapper().valueToTree(parsed.getCoverage());
        ObjectNode out=object();out.put("schema",SCHEMA);
        ObjectNode binding=out.putObject("sourceBinding");binding.put("fileKey",fileKey);binding.put("sourceSha256",source);
        binding.put("analysisRevision",ANALYSIS_REVISION);
        binding.put("inputSha256",inputSha(fileKey,source,blocks,coverage));
        binding.put("generationMeaning","Deterministic derivative evidence payload SHA256; not original-byte identity");
        ObjectNode route=out.putObject("route");route.put("profile",fileKey);route.put("eligibleModule","drafting");
        route.put("sourceVersionStatus","source_text_candidates_only");route.put("editionFusionAllowed",false);
        route.put("familyIdentityStatus",detected==null?"explicit_unverified":"title_supported_candidate");route.put("strictIdentityVerified",false);
        if(detected==null)route.putNull("titleFamilyCandidate");else route.put("titleFamilyCandidate",detected);
        ArrayNode members=out.putArray("sourceMembers"),notes=out.putArray("guidanceNotes"),markers=out.putArray("markerCandidates"),groups=out.putArray("groups"),conditions=out.putArray("conditions"),issues=out.putArray("issues");
        ObjectNode structure=out.putObject("structure");
        JsonNode paragraphs=coverage.path("nativeDocxParagraphs");
        boolean word=paragraphs.isObject();route.put("format",word?"docx":"pdf_or_other_observed_format");
        route.put("primaryEvidence",word?"native_ooxml_paragraph_channels":"existing_effective_blocks_and_native_pdf_observations");
        List<Member> sourceMembers=new ArrayList<>();
        if(word) {
            structure.set("nativeDocxTables",coverage.path("nativeDocxTables").deepCopy());
            structure.set("nativeDocxOutline",coverage.path("nativeDocxOutline").deepCopy());
            structure.set("nativeDocxSections",coverage.path("nativeDocxSections").deepCopy());
            boolean inventoryBound=source.equals(paragraphs.path("sourceSha256").asText());
            if(!inventoryBound)issue(issues,"NATIVE_INVENTORY_SOURCE_MISMATCH",null,"Native paragraph inventory belongs to different original bytes");
            Set<String> observedIds=new HashSet<>();
            for(JsonNode original:paragraphs.path("paragraphs")) {
                ObjectNode member=original.deepCopy();member.put("memberOrderZeroBased",sourceMembers.size());member.put("offsetUnit","utf16_code_units_in_named_observed_channel");
                boolean bound=inventoryBound&&source.equals(member.path("sourcePosition").path("sourceSha256").asText())&&!member.path("id").asText().isEmpty()&&observedIds.add(member.path("id").asText());
                member.put("identityStatus",bound?"source_bound":"source_identity_mismatch");members.add(member);
                if(!bound)issue(issues,"MEMBER_SOURCE_MISMATCH",member.path("id").asText(),"Member must not support another source revision");
                if(member.has("textOverlapStatus"))issue(issues,"NATIVE_PARAGRAPH_CHANNEL_OVERLAP",member.path("id").asText(),"Overlapping parent and nested paragraph channels remain observable but are not concatenated into complete groups");
                sourceMembers.add(new Member(member,bound));
            }
        } else {
            structure.put("physicalPages",parsed.getPageCount());structure.put("physicalWordPageKnown",false);
            int ordinal=0,pageGroups=0,unselectedGroups=0;boolean foreignGroup=false,ocrObserved=false;
            for(DocumentBlock block:parsed.getBlocks()) {
                ObjectNode member=object();String id=block.getId()==null?"parsed-block:"+digest(source+"|"+ordinal):block.getId();
                member.put("id",id);member.put("memberOrderZeroBased",ordinal++);member.put("storyKind","physical_document");member.put("storyRootNodePath","physical_document");
                JsonNode blockObservation=JsonUtils.mapper().valueToTree(block);member.set("blockObservation",blockObservation);member.put("offsetUnit","utf16_code_units_in_named_observed_channel");
                ObjectNode text=member.putObject("textObservation");text.put("status","observed_existing_block_channels");
                text.put("text",block.getEffectiveText());text.put("originalText",block.getOriginalText());text.put("deletedText",block.getDeletedText());text.put("strikeText",block.getStrikeText());
                member.put("effectiveCandidate",!Arrays.asList("ocr_candidate","native_table_candidate","parser_page_group_candidate").contains(block.getSource()));
                JsonNode pageGroup=blockObservation.path("parserPageGroup");boolean groupBound=true;if(pageGroup.isObject()){pageGroups++;groupBound=pageGroupBound(pageGroup,source,block.getPageNo(),parsed.getPageCount());if(!groupBound){foreignGroup=true;issue(issues,"PARSER_PAGE_GROUP_SOURCE_BINDING_MISMATCH",id,"Complete parser candidates are retained, but their original source SHA or physical page binding disagrees with this parse");}if(!pageGroup.path("selectedParser").isTextual()||pageGroup.path("selectedParser").asText().isEmpty()||!pageGroup.path("sourceQualityAccepted").asBoolean())unselectedGroups++;}
                ocrObserved|="ocr".equals(block.getSource())||"ocr_candidate".equals(block.getSource());member.put("identityStatus",groupBound?"parsed_source_bound":"parser_page_group_source_mismatch");members.add(member);sourceMembers.add(new Member(member,groupBound,source));
            }
            route.put("retainedCompleteParserPageGroupCount",pageGroups);route.put("unselectedOrUnverifiedParserPageGroupCount",unselectedGroups);route.put("parserPageGroupBindingStatus",foreignGroup?"source_or_page_mismatch_present":pageGroups>0?"observed_groups_source_and_page_bound":"no_complete_parser_page_groups_observed");
            if(unselectedGroups>0){route.put("parserCandidateSelectionStatus","complete_candidates_retained_without_verified_selection");route.put("legacyEffectiveTextAuthority","unverified_observation_only_no_source_quality_acceptance");issue(issues,"PARSER_PAGE_CANDIDATES_UNSELECTED",null,"Complete page candidates remain retained without an accepted selection; legacy effective body text is only an unverified observation");}
            if(ocrObserved)route.put("ocrSourceSyntaxAuthority","observed_ocr_delimiters_do_not_establish_original_source_syntax");
            issue(issues,"PDF_SCOPE_REQUIRES_REVIEW",null,"Existing physical pages, glyphs and visual provenance are retained; cross-page ownership and editable Word anchors are not inferred");
        }
        if(sourceMembers.isEmpty())issue(issues,"SOURCE_MEMBER_INVENTORY_EMPTY",null,"No source members were available from the actual parse");
        route.put("sourceMemberCount",sourceMembers.size());
        ArrayNode candidates=route.putArray("candidates");
        if(detected!=null)candidates.addObject().put("kind","template_family").put("value",detected).put("status","short_observed_title_candidate");
        int titleMembers=0;for(Member member:sourceMembers)if(member.usable&&member.body()&&!member.text.trim().isEmpty()) {
            if(++titleMembers>40)break;
            if(member.text.length()<200&&member.text.matches("(?is).*\\b(?:edition|consolidated|revision|revised)\\b.*"))candidates.addObject().put("kind","source_version").put("memberId",member.id).put("text",member.text).put("status","literal_source_version_candidate_not_identity_proof");
        }
        if(!word)bindSourceReviewedVisualMembers(sourceMembers);
        nativeOutlineGroups(source,sourceMembers,coverage.path("nativeDocxOutline"),groups,issues);
        literalGroups(fileKey,sourceMembers,groups);
        guidance(fileKey,sourceMembers,notes,conditions,groups,issues);
        markers(sourceMembers,markers,conditions,groups);
        resolvedScopes(fileKey,sourceMembers,notes,conditions,groups,issues);
        markerDispositions(fileKey,sourceMembers,notes,conditions,groups,markers,issues);
        resolveSourceReferenceCandidates(fileKey,sourceMembers,notes,conditions,groups);
        if(word){nativeSourceFields(fileKey,sourceMembers,notes,groups);markerSourceContexts(fileKey,sourceMembers,notes,conditions,groups,markers);}
        structure.put("scopeMeaning","Native physical membership and literal source candidates; legal applicability and editing adoption remain separate");
        binding.put("generationSha256",payloadSha(out));return out;
    }

    /** Only short cover/title observations establish a family candidate; body references do not. */
    public static String detectFamily(DocumentParser.ParsedDocument parsed) {
        if(parsed==null)return null;
        List<String> titles=new ArrayList<>();JsonNode inventory=JsonUtils.mapper().valueToTree(parsed.getCoverage());
        String source=parsed.getSourceSha256();boolean nativeBound=source!=null&&source.equals(inventory.path("nativeDocxParagraphs").path("sourceSha256").asText());
        for(JsonNode p:inventory.path("nativeDocxParagraphs").path("paragraphs")) {
            if(!nativeBound||!source.equals(p.path("sourcePosition").path("sourceSha256").asText())||p.has("textOverlapStatus")||!p.path("textObservation").path("status").asText().startsWith("observed_")||!"body".equals(p.path("storyKind").asText()))continue;
            String text=p.path("textObservation").path("text").asText().trim();
            if(!text.isEmpty()){titles.add(text);if(titles.size()>=40)break;}
        }
        if(titles.isEmpty()&&parsed.getBlocks()!=null)for(DocumentBlock block:parsed.getBlocks()) {
            if(block.getPageNo()!=null&&block.getPageNo()>3)continue;
            String text=block.getEffectiveText();if(text==null)continue;
            for(String line:text.split("\\R"))if(!line.trim().isEmpty()){titles.add(line.trim());if(titles.size()>=40)break;}
            if(titles.size()>=40)break;
        }
        Set<String> families=new LinkedHashSet<>();
        for(int n=0;n<titles.size();n++)for(int width=1;width<=3&&n+width<=titles.size();width++) {
            String candidate=String.join(" ",titles.subList(n,n+width)).trim().replaceAll("\\s+"," ");
            if(candidate.length()>100)continue;
            candidate=candidate.replaceFirst("(?i)^\\s*(?:[0-9]+[. )-]*|appendix [a-z0-9]+[. :-]*)","").trim();
            if(candidate.matches("(?i)(?:notes?|notice) to tenderers?(?: \\(NTT\\))?"))families.add("NTT");
            if(candidate.matches("(?i)special conditions? of tender(?: \\(SCT\\))?"))families.add("SCT");
            if(candidate.matches("(?i)special conditions? of contract(?: \\(SCC\\))?"))families.add("SCC");
            if(candidate.matches("(?i)(?:HKHA )?Standard Library of SCC(?: for .+)?"))families.add("SCC");
        }
        boolean contents=titles.stream().anyMatch(t->t.trim().matches("(?i)table of contents?"));Set<String> sections=new HashSet<>();int sccHeadings=0;
        if(contents)for(String title:titles){Matcher heading=Pattern.compile("^\\s*SCC(\\d+)(?:\\.\\d+)?(?=[A-Za-z\\s]).+").matcher(title);if(heading.matches()){sccHeadings++;sections.add(heading.group(1));}}
        if(contents&&sccHeadings>=3&&sections.size()>=2)families.add("SCC");
        return families.size()==1?families.iterator().next():null;
    }

    /** Reject stale, foreign or modified evidence using only the actual persisted parse inputs. */
    public static JsonNode requireBound(String fileKey,String sourceSha,JsonNode blocks,JsonNode coverage,JsonNode evidence) {
        key(fileKey);String source=sha(sourceSha);
        if(evidence==null||!SCHEMA.equals(evidence.path("schema").asText()))throw new IllegalArgumentException("TEMPLATE_EVIDENCE_SCHEMA_MISMATCH");
        JsonNode binding=evidence.path("sourceBinding");
        if(!ANALYSIS_REVISION.equals(binding.path("analysisRevision").asText()))throw new IllegalArgumentException("TEMPLATE_EVIDENCE_REVISION_STALE");
        if(!fileKey.equals(binding.path("fileKey").asText())||!source.equals(binding.path("sourceSha256").asText()))throw new IllegalArgumentException("TEMPLATE_EVIDENCE_SOURCE_MISMATCH");
        if(!inputSha(fileKey,source,blocks,coverage).equals(binding.path("inputSha256").asText()))throw new IllegalArgumentException("TEMPLATE_EVIDENCE_INPUT_CHANGED");
        if(!payloadSha(evidence).equals(binding.path("generationSha256").asText()))throw new IllegalArgumentException("TEMPLATE_EVIDENCE_PAYLOAD_CHANGED");
        return evidence.deepCopy();
    }


    private static void bindSourceReviewedVisualMembers(List<Member> members){
        for(Member member:members){ObjectNode binding=sourceReviewedVisualMemberBinding(member,members);if(binding!=null)member.value.set("sourceReviewBinding",binding);}
    }
    private static ObjectNode sourceReviewedVisualMemberBinding(Member member,List<Member> members){
        JsonNode block=member.value.path("blockObservation"),region=block.path("visualRegion"),provider=region.path("providerBlock"),frame=block.path("visualFrame"),context=block.path("visualProvenance").path("sourceContext"),transform=context.path("originalPageTransform"),geometry=region.path("originalGeometry");
        if(!member.usable||member.expectedPdfSource==null||!"ocr".equals(block.path("source").asText())||!"source_review_selected_candidate".equals(region.path("selectionStatus").asText())||!"observed_input_frame".equals(region.path("geometryStatus").asText())
                ||!provider.isObject()||!region.path("id").isTextual()||!region.path("id").equals(provider.path("blockId"))||!region.path("kind").equals(provider.path("label"))||!region.path("readingOrder").equals(provider.path("readingOrder"))
                ||!provider.path("text").isTextual()||!member.text.equals(provider.path("text").asText())||!region.path("text").equals(provider.path("text"))||!region.path("originalText").equals(provider.path("text"))||!block.path("originalText").equals(provider.path("text"))
                ||!member.expectedPdfSource.equals(context.path("source").path("sourceSha256").asText())||!member.expectedPdfSource.equals(block.path("readingContext").path("sourceSha256").asText())
                ||!block.path("pageNo").isIntegralNumber()||!block.path("pageNo").equals(context.path("physicalPageNo"))||pdfPage(member)<1||context.path("source").path("pageCount").asInt()<pdfPage(member)
                ||!context.path("imageSha256").asText().matches("[a-f0-9]{64}")||!context.path("imageSha256").equals(frame.path("imageSha256"))||!context.path("imageSha256").equals(transform.path("inputFrame").path("sha256"))
                ||!"pixel".equals(frame.path("coordinateUnit").asText())||!"xywh".equals(frame.path("bboxFormat").asText())||!frame.path("width").equals(context.path("width"))||!frame.path("height").equals(context.path("height"))
                ||!frame.path("width").equals(transform.path("inputFrame").path("width"))||!frame.path("height").equals(transform.path("inputFrame").path("height"))||frame.path("width").asInt()<1||frame.path("height").asInt()<1
                ||!"verified".equals(geometry.path("status").asText())||!geometry.path("targetFrame").equals(transform.path("targetFrame"))||!member.expectedPdfSource.equals(geometry.path("targetFrame").path("sourceSha256").asText())||geometry.path("targetFrame").path("physicalPageNo").asInt()!=pdfPage(member)
                ||!digest(canonical(transform).toString()).equals(geometry.path("transformSha256").asText()))return null;
        double[] box=sourceReviewedVisualBox(region,frame);if(box==null||!sourceReviewedNumbersMatch(geometry.path("xyxyNormalized"),box,false)||!sourceReviewedNumbersMatch(block.path("bbox"),box,true))return null;
        ObjectNode found=null;for(Member groupMember:members){JsonNode group=groupMember.value.path("blockObservation").path("parserPageGroup");if(!group.isObject()||group.path("physicalPageNo").asInt()!=pdfPage(member)||!"source_review_selected_candidate".equals(group.path("selectionStatus").asText()))continue;
            if(found!=null||!"parsed_source_bound".equals(groupMember.value.path("identityStatus").asText())||!"parser_page_group_candidate".equals(groupMember.value.path("blockObservation").path("source").asText())||!pageGroupBound(group,member.expectedPdfSource,pdfPage(member),context.path("source").path("pageCount").asInt())
                    ||!"whole_page".equals(group.path("scope").asText())||!"candidate_unverified".equals(group.path("adoptionStatus").asText())||!group.path("sourceQualityAccepted").isBoolean()||group.path("sourceQualityAccepted").asBoolean()
                    ||!group.path("reviewManifestSha256").asText().matches("[a-f0-9]{64}")||!member.expectedPdfSource.equals(group.path("source").path("sourceRevisionSha256").asText())||!group.path("source").path("pageCount").equals(context.path("source").path("pageCount"))
                    ||!group.path("selectedParser").equals(region.path("channel"))||!group.path("reviewedBlockIds").isArray()||!group.path("candidates").isArray()||group.path("candidates").size()!=2)return null;
            Set<String> reviewed=new HashSet<>(),observed=new HashSet<>(),parsers=new HashSet<>();for(JsonNode id:group.path("reviewedBlockIds"))if(!id.isTextual()||id.asText().isEmpty()||!reviewed.add(id.asText()))return null;
            JsonNode selected=null;for(JsonNode candidate:group.path("candidates")){String parser=candidate.path("parser").asText();if(!parsers.add(parser)||!Arrays.asList("paddleocr-vl","mineru").contains(parser)||!candidate.path("runId").asText().matches(Pattern.quote(parser)+":[a-f0-9]{64}")||!candidate.path("features").path("completeRawCapture").isBoolean()||!candidate.path("features").path("completeRawCapture").asBoolean()||!candidate.path("blocks").isArray()
                        ||!candidate.path("pageId").asText().equals(candidate.path("runId").asText()+"/page/"+pdfPage(member))||!candidate.path("pageId").equals(candidate.path("page").path("pageId"))||!candidate.path("page").path("mapping").path("originalPageTransform").equals(transform)||!candidate.path("blocks").equals(candidate.path("page").path("blocks")))return null;
                if(candidate.path("parser").equals(group.path("selectedParser"))){if(selected!=null||!candidate.path("features").path("allGeometryBound").isBoolean()||!candidate.path("features").path("allGeometryBound").asBoolean())return null;selected=candidate;for(JsonNode observedBlock:candidate.path("blocks"))if(!observedBlock.path("blockId").isTextual()||!observed.add(observedBlock.path("blockId").asText()))return null;}}
            if(selected==null||reviewed.isEmpty()||!reviewed.equals(observed))return null;int exact=0;for(JsonNode observedBlock:selected.path("blocks"))if(observedBlock.equals(provider))exact++;if(exact!=1||!provider.path("blockId").asText().startsWith(selected.path("pageId").asText()+"/block/"))return null;
            found=object();found.put("schema","consense.source-reviewed-visual-member-binding.v1");found.put("sourceSha256",member.expectedPdfSource);found.put("physicalPageNo",pdfPage(member));found.put("pageGroupMemberId",groupMember.id);found.set("reviewManifestSha256",group.path("reviewManifestSha256").deepCopy());found.set("reviewedBlockIds",group.path("reviewedBlockIds").deepCopy());found.set("runId",selected.path("runId").deepCopy());found.set("pageId",selected.path("pageId").deepCopy());found.set("providerBlock",provider.deepCopy());found.set("frame",frame.deepCopy());found.set("originalPageTransform",transform.deepCopy());found.set("originalGeometry",geometry.deepCopy());found.put("authority","existing_source_reviewed_complete_page_candidate_not_global_quality_or_project_applicability");
        }return found;
    }
    private static double[] sourceReviewedVisualBox(JsonNode region,JsonNode frame){
        JsonNode observed=region.path("providerBlock").path("bbox"),values=observed.path("xyxy");if(!values.isArray()||values.size()!=4||!observed.path("pageWidth").equals(frame.path("width"))||!observed.path("pageHeight").equals(frame.path("height"))||!"top-left".equals(observed.path("origin").asText()))return null;
        double[] box=new double[4];for(int i=0;i<4;i++){if(!values.path(i).isNumber()||!Double.isFinite(values.path(i).asDouble()))return null;box[i]=values.path(i).asDouble();}
        String mode=observed.path("coordinateFrame").asText();if(mode.equals("raster")){if(!"pixel".equals(observed.path("coordinateUnit").asText()))return null;for(int i=0;i<4;i++)box[i]/=frame.path(i%2==0?"width":"height").asDouble();}
        else if(mode.equals("normalized_1000")){for(int i=0;i<4;i++)box[i]/=1000;}
        else if(!mode.equals("normalized")||!"ratio".equals(observed.path("coordinateUnit").asText()))return null;
        if(box[0]<0||box[1]<0||box[2]>1||box[3]>1||box[0]>=box[2]||box[1]>=box[3])return null;double[] pixels={box[0]*frame.path("width").asDouble(),box[1]*frame.path("height").asDouble(),box[2]*frame.path("width").asDouble(),box[3]*frame.path("height").asDouble()};
        if(!sourceReviewedNumbersMatch(region.path("bbox"),pixels,true))return null;return box;
    }
    private static boolean sourceReviewedNumbersMatch(JsonNode values,double[] xyxy,boolean xywh){
        if(!values.isArray()||values.size()!=4)return false;for(int i=0;i<4;i++){double expected=xywh&&i>=2?xyxy[i]-xyxy[i-2]:xyxy[i];if(!values.path(i).isNumber()||!Double.isFinite(values.path(i).asDouble())||Math.abs(values.path(i).asDouble()-expected)>1e-9)return false;}return true;
    }
    private static boolean sameSourceReviewedVisualPage(Member left,Member right){
        JsonNode a=left.value.path("sourceReviewBinding"),b=right.value.path("sourceReviewBinding");if(!a.isObject()||!b.isObject())return false;for(String key:Arrays.asList("sourceSha256","physicalPageNo","pageGroupMemberId","reviewManifestSha256","runId","frame","originalPageTransform"))if(!a.path(key).equals(b.path(key)))return false;return true;
    }
    private static void sourceReviewedVisualSidebarScopes(List<Member> members,ArrayNode notes,ArrayNode groups){
        Pattern marked=Pattern.compile("(?is)^\\s*[*#]\\s*(\\([a-z0-9]+\\))\\s+.+[.!?]\\s*$"),boundary=Pattern.compile("(?is)^\\s*[*#]?\\s*(?:(?:NTT|SCT|SCC)\\s*)?(?:\\d+(?:\\.\\d+)*\\.|\\([a-z0-9]+\\))\\s*\\p{L}.*"),numberedLine=Pattern.compile("(?im)^\\h*[*#]?\\h*(?:(?:NTT|SCT|SCC)\\h*\\d+(?:\\.\\d+)*\\.?|\\d+(?:\\.\\d+)*\\.|\\([a-z0-9]+\\))\\s*\\S");
        for(JsonNode raw:notes){ObjectNode note=(ObjectNode)raw;if(!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean()||note.path("orderedMemberIds").size()!=1||!note.path("text").asText().matches("(?is)^\\s*\\(\\s*delete\\s+if\\b.+\\)\\s*[.]?\\s*$"))continue;
            Member aside=members.get(indexOf(members,note.path("orderedMemberIds").get(0).asText()));if(!aside.value.path("sourceReviewBinding").isObject()||!"aside_text".equals(aside.value.path("blockObservation").path("visualRegion").path("kind").asText()))continue;double[] margin=sourceReviewedVisualBox(aside.value.path("blockObservation").path("visualRegion"),aside.value.path("blockObservation").path("visualFrame"));List<Member> targets=new ArrayList<>();
            for(Member candidate:members){if(!sameSourceReviewedVisualPage(aside,candidate)||!"text".equals(candidate.value.path("blockObservation").path("visualRegion").path("kind").asText())||!marked.matcher(candidate.text).matches()||candidate.text.substring(candidate.text.indexOf(candidate.text.contains("*")?'*':'#')+1).matches("(?s).*[*#].*"))continue;Matcher anchors=numberedLine.matcher(candidate.text);if(anchors.find()&&anchors.find())continue;double[] box=sourceReviewedVisualBox(candidate.value.path("blockObservation").path("visualRegion"),candidate.value.path("blockObservation").path("visualFrame"));if(box!=null&&box[2]<=margin[0]&&Math.min(box[3],margin[3])>Math.max(box[1],margin[1]))targets.add(candidate);}
            if(targets.size()!=1)continue;Member target=targets.get(0),next=null;boolean completeProjection=true;Set<String> projected=new HashSet<>(),reviewed=new HashSet<>();for(JsonNode id:target.value.path("sourceReviewBinding").path("reviewedBlockIds"))reviewed.add(id.asText());for(Member candidate:members)if(candidate.value.path("effectiveCandidate").asBoolean(true)&&pdfPage(candidate)==pdfPage(target)){if(!sameSourceReviewedVisualPage(target,candidate)||!projected.add(candidate.value.path("sourceReviewBinding").path("providerBlock").path("blockId").asText())){completeProjection=false;break;}}if(!completeProjection||!projected.equals(reviewed))continue;double[] box=sourceReviewedVisualBox(target.value.path("blockObservation").path("visualRegion"),target.value.path("blockObservation").path("visualFrame"));double nextY=Double.POSITIVE_INFINITY;boolean ambiguous=false;
            for(Member candidate:members){if(candidate==target||!sameSourceReviewedVisualPage(target,candidate))continue;double[] other=sourceReviewedVisualBox(candidate.value.path("blockObservation").path("visualRegion"),candidate.value.path("blockObservation").path("visualFrame"));if(other==null||Math.min(other[2],box[2])<=Math.max(other[0],box[0]))continue;if(other[1]<box[3]){if(other[3]>box[3]){ambiguous=true;break;}continue;}if(other[1]<nextY){next=candidate;nextY=other[1];ambiguous=false;}else if(other[1]==nextY)ambiguous=true;}
            if(next==null||ambiguous||!boundary.matcher(next.text).matches())continue;Matcher own=marked.matcher(target.text);own.matches();if(next.text.replaceFirst("^\\s*[*#]?\\s*","").startsWith(own.group(1)))continue;
            ObjectNode group=lexicalGroup(note,"source_unit_instruction_scope",target.id,groups);group.put("scopeMeaning","The complete source-reviewed single marked paragraph uniquely aligned with its same-page conditional sidebar, ending before the next explicit numbered source block; project selection remains unknown");group.put("captureComplete",true);group.put("scopeComplete",true);group.put("wholeParagraphTargetAssumed",false);addRange(group.putArray("memberRanges"),group.putArray("orderedMemberIds"),target,0,target.text.length());group.set("guidanceContextMemberIds",note.path("orderedMemberIds").deepCopy());ArrayNode proof=group.putArray("sourceReviewBindings");for(Member member:Arrays.asList(target,aside,next))proof.add(member.value.path("sourceReviewBinding").deepCopy());group.putObject("startBoundary").put("kind","source_reviewed_marked_visual_paragraph_start").put("memberId",target.id).put("offset",0);group.putObject("endBoundary").put("kind","next_source_reviewed_visual_numbered_block").put("memberId",next.id).put("offset",0);note.put("targetScopeStatus","source_reviewed_single_visual_subitem_bound");note.put("scopeComplete",true);
        }
    }

    private static void guidance(String profile,List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups,ArrayNode issues) {
        Set<String> consumed=new HashSet<>();List<Anchor> nativeClauses="NTT".equals(profile)?clauseAnchors(profile,members):Collections.emptyList();
        for(int index=0;index<members.size();index++) {
            Member first=members.get(index);if(!first.usable)continue;
            Matcher explicit=GUIDANCE.matcher(first.text);List<Integer> starts=new ArrayList<>();while(explicit.find())if(guidanceCue(first.text,explicit.start()))starts.add(explicit.start());
            if(starts.isEmpty()&&!("SCC".equals(profile))&&sidebarStart(first,members))starts.add(first.text.indexOf('('));
            for(int noteIndex=0;noteIndex<starts.size();noteIndex++) {
                int cue=starts.get(noteIndex),open=openingBefore(first.text,cue),start=open>=0?open:cue;
                String signature=first.id+":"+start;if(!consumed.add(signature))continue;
                ObjectNode note=object();String id="guidance:"+digest(profile+"|"+signature);note.put("id",id);
                note.put("kind",GUIDANCE.matcher(first.text.substring(cue)).lookingAt()?"explicit_guidance":"editorial_parenthetical_candidate");
                note.put("profile",profile);ArrayNode ranges=note.putArray("memberRanges"),ids=note.putArray("orderedMemberIds");StringBuilder text=new StringBuilder();
                char opening=open<0?'\0':first.text.charAt(open),closing=opening=='['?']':')';int depth=0;boolean closed=open<0,unknown=false,crossTable=false;Member last=first;
                for(int cursor=index;cursor<members.size();cursor++) {
                    Member current=members.get(cursor);if(!closed&&cursor>index&&unclosedNttSpacerBoundary(first,index,cursor,members,nativeClauses))break;if(!sameLane(first,current))continue;
                    if(cursor>index&&hasGuidanceCue(current.text))break;
                    if(!current.usable){unknown=true;break;}
                    int from=cursor==index?start:0,end=current.text.length();
                    if(cursor==index&&noteIndex+1<starts.size())end=Math.min(end,openingBefore(first.text,starts.get(noteIndex+1))>=0?openingBefore(first.text,starts.get(noteIndex+1)):starts.get(noteIndex+1));
                    if(opening!='\0')for(int n=from;n<end;n++) {char c=current.text.charAt(n);if(c==opening)depth++;if(c==closing)depth--;if(depth==0&&c==closing){int boundary=end;end=n+1;while(end<boundary&&".;,:".indexOf(current.text.charAt(end))>=0)end++;closed=true;break;}}
                    addRange(ranges,ids,current,from,end);if(text.length()>0)text.append('\n');text.append(current.text,from,end);last=current;
                    crossTable|=!Objects.equals(first.table,current.table);
                    if(closed||opening=='\0')break;
                }
                boolean sourceSyntaxKnown=true;ArrayNode sourceReviews=array();for(JsonNode memberId:ids){Member observed=members.get(indexOf(members,memberId.asText()));if(observed.value.has("blockObservation")&&!Arrays.asList("native","native_pdf").contains(observed.value.path("blockObservation").path("source").asText())){JsonNode review=observed.value.path("sourceReviewBinding");if(!review.isObject())sourceSyntaxKnown=false;else sourceReviews.add(review.deepCopy());}}
                if(!sourceReviews.isEmpty())note.set("sourceReviewBindings",sourceReviews);
                note.put("text",text.toString());note.put("captureComplete",closed&&!unknown);note.put("observedSyntaxComplete",closed);note.put("sourceSyntaxKnown",sourceSyntaxKnown);note.put("sourceSyntaxAuthority",sourceSyntaxKnown?!sourceReviews.isEmpty()?"source_reviewed_complete_visual_page_member":first.value.has("blockObservation")?"native_pdf_text_observation":"native_ooxml_parser_channels":"ocr_or_other_derived_observation_without_original_syntax_authority");note.put("sourceSyntaxComplete",closed&&sourceSyntaxKnown);note.put("scopeComplete",false);
                note.put("semanticStatus","source_instruction_candidate");note.put("targetScopeStatus","requires_source_relationship_review");
                note.put("crossTableContinuationCandidate",crossTable);note.set("referenceCandidates",references(text.toString()));notes.add(note);
                if(first.value.has("blockObservation")){projectPdfGuidance(note,members,issues);text.setLength(0);text.append(note.path("text").asText());note.set("referenceCandidates",references(text.toString()));}
                ObjectNode condition=conditions.addObject();condition.put("id","condition:"+digest(id));condition.put("guidanceNoteId",id);condition.put("text",text.toString());condition.set("memberRanges",ranges.deepCopy());condition.put("status","literal_source_condition_not_project_boolean");condition.set("referenceCandidates",note.path("referenceCandidates").deepCopy());
                ObjectNode group=groups.addObject();group.put("id",id+":capture");group.put("kind","guidance_capture");group.set("orderedMemberIds",ids.deepCopy());group.set("memberRanges",ranges.deepCopy());group.put("captureComplete",note.path("captureComplete").asBoolean());group.put("observedSyntaxComplete",closed);group.put("sourceSyntaxKnown",sourceSyntaxKnown);group.put("sourceSyntaxAuthority",note.path("sourceSyntaxAuthority").asText());group.put("sourceSyntaxComplete",note.path("sourceSyntaxComplete").asBoolean());group.put("scopeComplete",false);if(note.has("pdfLaneProjection")){group.set("pdfLaneProjection",note.path("pdfLaneProjection").deepCopy());group.set("revisionMarginContextRanges",note.path("revisionMarginContextRanges").deepCopy());}
                if(note.has("sourceReviewBindings"))group.set("sourceReviewBindings",note.path("sourceReviewBindings").deepCopy());
                group.putObject("startBoundary").put("kind","literal_instruction_start").put("memberId",first.id).put("offset",start);
                group.putObject("endBoundary").put("kind",closed?"literal_closing_delimiter":"unclosed_or_unobserved_continuation").put("memberId",last.id);
                if(!closed)issue(issues,"GUIDANCE_SOURCE_SYNTAX_INCOMPLETE",id,"All observed members are retained; no closing delimiter is invented");
                if(!sourceSyntaxKnown)issue(issues,"DERIVED_GUIDANCE_SOURCE_SYNTAX_UNVERIFIED",id,"Observed delimiters may be captured, but OCR or another derived text observation cannot prove that the original source contains those delimiters");
                if(crossTable)issue(issues,"GUIDANCE_CROSS_TABLE_LINK_UNVERIFIED",id,"Matching source-column continuation is a candidate until the relationship is reviewed");
            }
        }
    }

    private static boolean unclosedNttSpacerBoundary(Member first,int start,int cursor,List<Member> members,List<Anchor> clauses){
        Member boundary=members.get(cursor);if(clauses.isEmpty()||first.table.isEmpty()||first.cell.isEmpty()||!sameNativeSource(first,boundary)||!first.table.equals(boundary.table)||!CELL_NUMBER.matcher(boundary.text).matches())return false;
        Anchor owner=null,next=null;for(Anchor clause:clauses)if("explicit_native_number_cell".equals(clause.basis)&&sameNativeSource(first,clause.member)&&first.table.equals(clause.member.table)){if(clause.ordinal<start)owner=clause;if(clause.ordinal==cursor)next=clause;}
        if(owner==null||next==null||owner.label.equals(next.label)||sameOuterLane(first,boundary))return false;
        boolean observed=false;for(int at=cursor;at<members.size();at++){Member continuation=members.get(at);if(!sameLane(first,continuation))continue;if(hasGuidanceCue(continuation.text))break;if(!first.table.equals(continuation.table)||!sameNativeSource(first,continuation)||!nativePureSpacer(continuation))return false;observed=true;}
        return observed;
    }

    private static void nativeOutlineGroups(String source,List<Member> members,JsonNode outline,ArrayNode groups,ArrayNode issues) {
        for(JsonNode heading:outline.path("headings")) {
            ObjectNode group=groups.addObject();String id="native-outline:"+digest(heading.path("sourcePosition").toString());group.put("id",id);group.put("kind","native_outline_physical_scope");group.set("nativeOutlineObservation",heading.deepCopy());
            ArrayNode ids=group.putArray("orderedMemberIds");boolean known=true;
            for(Member member:members)for(JsonNode path:heading.path("nativeMemberPaths"))if(descendant(member.path,path.asText())&&samePart(member,heading.path("sourcePosition"))) {ids.add(member.id);known&=member.usable;break;}
            String end=heading.path("endBoundary").path("kind").asText();boolean boundary=Arrays.asList("next_native_outline_heading","native_container_end").contains(end)
                    &&source.equals(heading.path("sourcePosition").path("sourceSha256").asText())&&source.equals(heading.path("endBoundary").path("sourcePosition").path("sourceSha256").asText())&&ids.size()>0;
            group.put("captureComplete",known&&boundary);group.put("scopeComplete",known&&boundary);group.put("scopeMeaning","Source-defined native physical heading membership only");group.put("semanticApplicabilityVerified",false);
            if(!known)issue(issues,"NATIVE_SCOPE_MEMBER_TEXT_UNKNOWN",id,"Physical source membership exists but at least one paragraph channel is unknown");
        }
    }

    private static void literalGroups(String profile,List<Member> members,ArrayNode groups) {
        List<Anchor> clauses=new ArrayList<>(),options=new ArrayList<>();
        for(int n=0;n<members.size();n++) {
            Member m=members.get(n);if(!m.usable||!m.body())continue;Matcher clause=CLAUSE.matcher(m.text),number=CELL_NUMBER.matcher(m.text),option=ALTERNATIVE.matcher(m.text);
            if(clause.find())clauses.add(new Anchor(n,clause.group(1).replaceAll("\\s+",""),"explicit_template_clause_label",m));
            else if("NTT".equals(profile)&&!m.cell.isEmpty()&&number.matches())clauses.add(new Anchor(n,number.group(1),"native_cell_numeric_label_candidate",m));
            if(option.find())options.add(new Anchor(n,option.group(1).toLowerCase(Locale.ROOT)+":"+(option.group(2)==null?option.group(3):option.group(2)).toLowerCase(Locale.ROOT),"literal_option_label_candidate",m));
        }
        for(int n=0;n<clauses.size();n++)serialGroup("literal_clause_candidate",clauses.get(n),n+1<clauses.size()?clauses.get(n+1):null,members,groups);
        List<Anchor> scanClauses=scanClauseCandidates(members);Map<Anchor,Anchor> scanAssociations=new IdentityHashMap<>();
        for(Anchor option:options)if(scanObservation(option.member))scanAssociations.put(option,scanOptionHeading(option,scanClauses));
        for(int n=0;n<options.size();n++) {
            Anchor a=options.get(n),next=null;
            if(scanObservation(a.member)){scanOptionCandidate(a,options,scanClauses,scanAssociations,members,groups);continue;}
            for(int k=n+1;k<options.size();k++)if(options.get(k).label.split(":")[0].equals(a.label.split(":")[0])&&sameStory(a.member,options.get(k).member)){next=options.get(k);break;}
            for(Anchor clause:clauses)if(clause.ordinal>a.ordinal&&(next==null||clause.ordinal<next.ordinal)){next=clause;break;}
            serialGroup("literal_option_candidate",a,next,members,groups);
        }
    }

    private static boolean scanObservation(Member member){return "ocr".equals(member.value.path("blockObservation").path("source").asText());}

    /** Candidate geometry bounds OCR body membership; it does not grant source syntax or edit authority. */
    private static ScanFrame scanFrame(Member member){
        JsonNode block=member.value.path("blockObservation"),region=block.path("visualRegion"),geometry=region.path("originalGeometry"),frame=geometry.path("targetFrame"),box=geometry.path("xyxyNormalized");
        if(!member.usable||!scanObservation(member)||member.expectedPdfSource==null||!"verified".equals(geometry.path("status").asText())||!member.expectedPdfSource.equals(frame.path("sourceSha256").asText())||!"original_page".equals(frame.path("coordinateFrame").asText())||!"top-left".equals(frame.path("origin").asText())||!frame.path("physicalPageNo").isIntegralNumber()||frame.path("physicalPageNo").asInt()!=pdfPage(member)||pdfPage(member)<1||!geometry.path("transformSha256").asText().matches("(?i)[a-f0-9]{64}")||!box.isArray()||box.size()!=4||!region.path("kind").isTextual()||!region.path("kind").equals(region.path("providerBlock").path("label")))return null;
        double[] xy=new double[4];for(int at=0;at<4;at++){if(!box.get(at).isNumber())return null;xy[at]=box.get(at).asDouble();if(!Double.isFinite(xy[at])||xy[at]<0||xy[at]>1)return null;}if(xy[0]>=xy[2]||xy[1]>=xy[3])return null;
        return new ScanFrame(frame,geometry.path("transformSha256").asText(),xy,region.path("kind").asText());
    }

    private static List<Anchor> scanClauseCandidates(List<Member> members){
        List<Anchor> result=new ArrayList<>();Pattern title=Pattern.compile("(?i)^\\s*[*#]*\\s*((?:(?:NTT|SCT|SCC)\\s*)?\\d+(?:\\.\\d+)*)(?:[.:]\\s*|\\s+)(\\S.*)$");
        for(int at=0;at<members.size();at++){Member member=members.get(at);ScanFrame frame=scanFrame(member);Matcher match=title.matcher(member.text);if(frame!=null&&"paragraph_title".equals(frame.role)&&match.matches())result.add(new Anchor(at,match.group(1).replaceAll("\\s+",""),"scan_positive_title_role_and_original_page_geometry",member));}
        result.sort(Comparator.comparingInt((Anchor anchor)->pdfPage(anchor.member)).thenComparingDouble(anchor->scanFrame(anchor.member).xy[1]).thenComparingDouble(anchor->scanFrame(anchor.member).xy[0]));return result;
    }

    private static Anchor scanOptionHeading(Anchor option,List<Anchor> clauses){
        ScanFrame annotation=scanFrame(option.member);if(annotation==null||!"aside_text".equals(annotation.role))return null;Anchor found=null;
        for(Anchor clause:clauses){ScanFrame title=scanFrame(clause.member);if(!sameScanPage(option.member,annotation,clause.member,title)||title.xy[2]>annotation.xy[0]||Math.max(title.xy[1],annotation.xy[1])>=Math.min(title.xy[3],annotation.xy[3]))continue;if(found!=null)return null;found=clause;}return found;
    }

    private static boolean sameScanPage(Member a,ScanFrame first,Member b,ScanFrame second){return first!=null&&second!=null&&sameStory(a,b)&&Objects.equals(a.expectedPdfSource,b.expectedPdfSource)&&first.frame.equals(second.frame)&&first.transform.equals(second.transform);}
    private static String scanTitle(Member member){return member.text.replaceFirst("^\\s*[*#]*\\s*","").replaceFirst("(?i)\\s*\\(\\s*Cont[’']?d\\s*\\)\\s*$","").replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);}
    private static boolean scanContinuation(Anchor previous,Anchor current){return previous!=null&&current!=null&&current.member.text.matches("(?is).*\\(\\s*Cont[’']?d\\s*\\)\\s*$")&&pdfPage(current.member)==pdfPage(previous.member)+1&&Objects.equals(previous.member.expectedPdfSource,current.member.expectedPdfSource)&&sameStory(previous.member,current.member)&&scanTitle(previous.member).equals(scanTitle(current.member));}

    private static Anchor scanAnnotation(Anchor heading,String label,List<Anchor> options,Map<Anchor,Anchor> associations){Anchor found=null;for(Anchor option:options)if(associations.get(option)==heading){if(!option.label.equals(label)||found!=null)return null;found=option;}return found;}

    private static void scanOptionCandidate(Anchor option,List<Anchor> options,List<Anchor> clauses,Map<Anchor,Anchor> associations,List<Member> members,ArrayNode groups){
        ObjectNode group=groups.addObject();group.put("id","literal_option_candidate:"+digest(option.member.id+"|"+option.label));group.put("kind","literal_option_candidate");group.put("label",option.label);group.put("boundaryBasis","scan_positive_roles_and_source_bound_original_geometry");group.put("scopeComplete",false);group.put("semanticApplicabilityVerified",false);group.put("automaticEditEligible",false);group.put("crossTable",false);
        ArrayNode ids=group.putArray("orderedMemberIds"),annotations=group.putArray("continuationAnnotationMemberIds"),context=group.putArray("physicalContextMemberIds"),headings=group.putArray("targetAnchorMemberIds");
        Anchor heading=associations.get(option);if(heading==null||scanAnnotation(heading,option.label,options,associations)==null){ids.add(option.member.id);annotations.add(option.member.id);group.set("targetMemberIds",ids.deepCopy());group.put("captureComplete",false);group.put("scopeStatus","scan_annotation_only_without_unique_positive_title_and_layout_association");group.putObject("startBoundary").put("kind","literal_instruction_start").put("memberId",option.member.id);group.putObject("endBoundary").put("kind","observed_annotation_member_end").put("memberId",option.member.id).put("offset",option.member.text.length());return;}
        int first=clauses.indexOf(heading);while(first>0&&scanContinuation(clauses.get(first-1),clauses.get(first))&&scanAnnotation(clauses.get(first-1),option.label,options,associations)!=null)first--;
        int last=first;while(last+1<clauses.size()&&scanContinuation(clauses.get(last),clauses.get(last+1))&&scanAnnotation(clauses.get(last+1),option.label,options,associations)!=null)last++;
        Anchor end=last+1<clauses.size()?clauses.get(last+1):null;boolean known=true;Set<String> targets=new LinkedHashSet<>();
        for(int at=first;at<=last;at++){
            Anchor start=clauses.get(at),annotation=scanAnnotation(start,option.label,options,associations);ScanFrame title=scanFrame(start.member),margin=scanFrame(annotation.member);Anchor peer=at+1<clauses.size()?clauses.get(at+1):null;double limit=peer!=null&&pdfPage(peer.member)==pdfPage(start.member)?scanFrame(peer.member).xy[1]:1;
            annotations.add(annotation.member.id);if(at==first){headings.add(start.member.id);targets.add(start.member.id);}else appendUnique(context,start.member.id);
            for(Member member:members){if(pdfPage(member)!=pdfPage(start.member)||!sameStory(start.member,member))continue;ScanFrame frame=scanFrame(member);if(frame==null||!sameScanPage(start.member,title,member,frame)){known&=member.value.path("blockObservation").path("parserPageGroup").isObject()||!member.usable;appendUnique(context,member.id);continue;}
                if(member==start.member)continue;boolean bodyRole=Arrays.asList("text","paragraph_title","equation","table").contains(frame.role),inside=frame.xy[1]>=title.xy[1]&&frame.xy[3]<=limit;boolean body=bodyRole&&inside&&frame.xy[2]<=margin.xy[0];if(bodyRole&&frame.xy[1]<limit&&frame.xy[3]>title.xy[1]&&frame.xy[0]<margin.xy[0]&&(!inside||frame.xy[2]>margin.xy[0]))known=false;
                if(body)targets.add(member.id);else appendUnique(context,member.id);
            }
        }
        for(Member member:members)if(targets.contains(member.id))ids.add(member.id);for(int at=context.size()-1;at>=0;at--)if(targets.contains(context.get(at).asText()))context.remove(at);
        group.set("targetMemberIds",ids.deepCopy());group.put("parentClauseLabel",clauses.get(first).label);group.put("captureComplete",known&&end!=null);group.put("scopeStatus","scan_layout_bound_literal_option_candidate_requires_source_scope_review");group.put("scopeMeaning","Body under the uniquely aligned numeric title; an exact repeated title with an explicit continuation and repeated same-label annotation may join consecutive physical pages. Furniture, side notes and parser containers remain context.");
        group.putObject("startBoundary").put("kind","scan_positive_numeric_title_with_uniquely_aligned_margin_annotation").put("memberId",clauses.get(first).member.id);
        ObjectNode boundary=group.putObject("endBoundary");boundary.put("kind",end==null?"unverified_physical_page_end":"next_positive_numeric_body_title");if(end!=null){boundary.put("memberId",end.member.id);boundary.put("text",end.member.text);}
    }

    private static final class ScanFrame{final JsonNode frame;final String transform,role;final double[] xy;ScanFrame(JsonNode frame,String transform,double[] xy,String role){this.frame=frame;this.transform=transform;this.xy=xy;this.role=role;}}

    private static void serialGroup(String kind,Anchor start,Anchor next,List<Member> members,ArrayNode groups) {
        ObjectNode group=groups.addObject();group.put("id",kind+":"+digest(start.member.id+"|"+start.label));group.put("kind",kind);group.put("label",start.label);group.put("boundaryBasis",start.basis);
        int begin=start.ordinal,end=next==null?members.size():next.ordinal;
        if(!start.member.row.isEmpty())while(begin>0&&members.get(begin-1).row.equals(start.member.row)&&sameStory(start.member,members.get(begin-1)))begin--;
        if(next!=null&&!next.member.row.isEmpty())while(end>begin&&members.get(end-1).row.equals(next.member.row)&&sameStory(next.member,members.get(end-1)))end--;
        ArrayNode ids=group.putArray("orderedMemberIds");boolean known=true,crossTable=false;
        for(int n=begin;n<end;n++){Member m=members.get(n);if(!sameStory(start.member,m))continue;ids.add(m.id);known&=m.usable;crossTable|=!Objects.equals(start.member.table,m.table);}
        group.put("captureComplete",known&&next!=null);group.put("scopeComplete",false);group.put("crossTable",crossTable);group.put("semanticApplicabilityVerified",false);
        group.put("scopeStatus","literal_boundary_candidate_requires_parent_and_continuation_review");
        group.putObject("startBoundary").put("kind",start.basis).put("memberId",start.member.id);
        ObjectNode boundary=group.putObject("endBoundary");boundary.put("kind",next==null?"unverified_story_end":"next_literal_peer_candidate");if(next!=null){boundary.put("memberId",next.member.id);boundary.put("text",next.member.text);}
    }

    private static void markers(List<Member> members,ArrayNode markers,ArrayNode conditions,ArrayNode groups) {
        Set<String> coveredInstructions=new HashSet<>(),rowGroups=new HashSet<>();
        for(JsonNode condition:conditions)for(JsonNode range:condition.path("memberRanges"))coveredInstructions.add(range.path("memberId").asText());
        for(Member member:members) {
            if(!member.usable)continue;
            for(int n=0;n<member.text.length();n++)if(member.text.charAt(n)=='*'||member.text.charAt(n)=='#') {
                ObjectNode marker=markers.addObject();marker.put("id","marker:"+digest(member.id+"|"+n));marker.put("memberId",member.id);marker.put("symbol",String.valueOf(member.text.charAt(n)));marker.put("channel","text");marker.put("start",n);marker.put("end",n+1);marker.put("offsetUnit","utf16");marker.put("status","unclassified_literal_marker");marker.put("scopeComplete",false);marker.put("wholeParagraphTargetAssumed",false);
                int left=n,right=n+1;while(left>0&&!sentenceBoundary(member.text,left-1))left--;while(right<member.text.length()&&!sentenceBoundary(member.text,right))right++;if(right<member.text.length())right++;
                ObjectNode local=marker.putObject("localSentenceObservation");local.put("text",member.text.substring(left,right));local.put("start",left);local.put("end",right);local.put("meaning","Literal sentence context, not an asserted edit target");
                if(!member.row.isEmpty()&&rowGroups.add(member.part+"|"+member.row)) {
                    ObjectNode group=groups.addObject();group.put("id","marker-row:"+digest(member.part+"|"+member.row));group.put("kind","marker_native_row_candidate");group.put("rowNodePath",member.row);group.put("scopeComplete",false);group.put("semanticApplicabilityVerified",false);ArrayNode ids=group.putArray("orderedMemberIds");boolean known=true;
                    for(Member peer:members)if(member.row.equals(peer.row)&&sameStory(member,peer)){ids.add(peer.id);known&=peer.usable;}
                    group.put("captureComplete",known);group.put("scopeStatus","Complete physical row retained including reference cells; marker target requires source interpretation");
                }
            }
            if(!coveredInstructions.contains(member.id)&&!GUIDANCE.matcher(member.text).find()&&EDITORIAL.matcher(member.text).find()) {ObjectNode condition=conditions.addObject();condition.put("id","instruction-candidate:"+digest(member.id));condition.put("memberId",member.id);condition.put("text",member.text);condition.put("status","editorial_keyword_candidate_requires_classification");condition.set("referenceCandidates",references(member.text));}
        }
    }

    private static boolean sentenceBoundary(String text,int at){char c=text.charAt(at);return c=='\n'||c=='!'||c=='?'||c=='.'&&(at+1==text.length()||Character.isWhitespace(text.charAt(at+1)));}

    private static void resolvedScopes(String profile,List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups,ArrayNode issues) {
        List<Anchor> clauses=clauseAnchors(profile,members);Set<String> noteMembers=new HashSet<>();
        for(JsonNode note:notes)for(JsonNode id:note.path("orderedMemberIds"))noteMembers.add(id.asText());
        if(!"SCC".equals(profile))completeAlternatives(profile,members,clauses,notes,groups,issues);
        if("SCC".equals(profile))bodyOptionScopes(profile,members,clauses,notes,noteMembers,groups,issues);
        for(JsonNode note:notes) {
            int noteAt=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(noteAt<0)continue;
            Matcher enumerated=ENUMERATED_TARGETS.matcher(note.path("text").asText());
            if(enumerated.find())resolveReferenceSet(profile,members,clauses,noteMembers,noteAt,note,enumerated.group(1),groups,issues);
            if(note.path("text").asText().matches("(?is).*\\bdelete\\s+the\\s+last\\s+sentence\\b.*"))lastSentence(members,noteAt,note,groups,issues);
        }
    }

    private static List<Anchor> clauseAnchors(String profile,List<Member> members) {
        List<Anchor> anchors=new ArrayList<>();
        for(int n=0;n<members.size();n++) {
            Member member=members.get(n);if(!member.usable||!member.body()||isTocCopy(member))continue;int headingStart=member.value.has("blockObservation")?pdfHeadingStart(profile,member,members):0;Matcher explicit=CLAUSE.matcher(member.text.substring(Math.max(0,headingStart))),number=CELL_NUMBER.matcher(member.text);
            if(headingStart>=0&&explicit.find()&&explicit.group(1).replaceAll("\\s+","").startsWith(profile)&&member.text.substring(headingStart+explicit.end()).trim().matches("(?s)^\\p{L}.+"))anchors.add(new Anchor(n,explicit.group(1).replaceAll("\\s+",""),headingStart==0?"explicit_native_clause_heading":"native_pdf_heading_after_complete_revision_margin",member));
            else if("NTT".equals(profile)&&!member.cell.isEmpty()&&number.matches()&&firstCell(member,members))anchors.add(new Anchor(n,number.group(1),"explicit_native_number_cell",member));
            else if(!member.value.has("blockObservation")&&nativeAppendixHeading(profile,n,members)!=null)anchors.add(new Anchor(n,profile+"/Appendix "+nativeAppendixHeading(profile,n,members),"explicit_native_appendix_title_and_parent_document",member));
        }
        return anchors;
    }
    private static String nativeAppendixHeading(String profile,int at,List<Member> members){
        Member member=members.get(at);Matcher title=Pattern.compile("(?i)^\\s*APPENDIX\\s+([A-Z0-9]+)\\s*(?:\\(\\s*Cont[’']?d\\s*\\))?\\s*$").matcher(member.text);if(!title.matches())return null;
        String expected="SCT".equals(profile)?"SPECIAL CONDITIONS? OF TENDER":"SCC".equals(profile)?"SPECIAL CONDITIONS? OF CONTRACT":"NOTES? TO TENDERERS?";
        for(int next=at+1;next<members.size();next++){Member peer=members.get(next);if(!sameStory(member,peer)||!peer.usable)return null;if(peer.text.trim().isEmpty())continue;return peer.text.trim().matches("(?i)TO\\s+(?:THE\\s+)?"+expected)?title.group(1).toUpperCase(Locale.ROOT):null;}return null;
    }

    private static void bodyOptionScopes(String profile,List<Member> members,List<Anchor> clauses,ArrayNode notes,Set<String> noteMembers,ArrayNode groups,ArrayNode issues){
        Pattern heading=Pattern.compile("(?i)^\\s*(option|alternative)\\s*\\(?([a-z0-9]+)\\)?\\s*[:.]?\\s*$"),select=Pattern.compile("(?i)\\bselect\\s+either\\s+(option|alternative)\\s*\\(?([a-z0-9]+)\\)?\\s+or\\s+\\1\\s*\\(?([a-z0-9]+)\\)?(?=\\s|[.,;:]|$)");List<Anchor> options=new ArrayList<>();
        for(int n=0;n<members.size();n++){Member member=members.get(n);if(!member.usable||!member.value.path("storyKind").asText().equals("body")||!member.row.isEmpty()||noteMembers.contains(member.id))continue;Matcher match=heading.matcher(member.text);if(match.matches())options.add(new Anchor(n,match.group(1).toLowerCase(Locale.ROOT)+":"+match.group(2).toLowerCase(Locale.ROOT),"standalone_native_body_option_heading",member));}
        for(Anchor option:options){Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,option.ordinal));if(owner==null)continue;Anchor ownerEnd=null;for(Anchor next:clauses)if(next.ordinal>owner.ordinal&&!next.label.equals(owner.label)&&sameStory(owner.member,next.member)&&clauseDepth(next.label)<=clauseDepth(owner.label)){ownerEnd=next;break;}int ownerLimit=ownerEnd==null?members.size():ownerEnd.ordinal;List<Anchor> peers=new ArrayList<>();for(Anchor peer:options)if(peer.ordinal>=owner.ordinal&&peer.ordinal<ownerLimit&&sameStory(owner.member,peer.member))peers.add(peer);
            List<JsonNode> instructions=new ArrayList<>();Set<String> expected=new LinkedHashSet<>();for(JsonNode note:notes){int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());Anchor noteOwner=firstClauseInSequence(clauses,enclosing(clauses,at));if(at<owner.ordinal||at>=peers.get(0).ordinal||noteOwner==null||noteOwner.ordinal!=owner.ordinal)continue;Matcher choice=select.matcher(note.path("text").asText());while(choice.find()){String first=choice.group(1).toLowerCase(Locale.ROOT)+":"+choice.group(2).toLowerCase(Locale.ROOT),second=choice.group(1).toLowerCase(Locale.ROOT)+":"+choice.group(3).toLowerCase(Locale.ROOT);if(!first.equals(second)){instructions.add(note);expected.add(first);expected.add(second);}}}
            Set<String> actual=new LinkedHashSet<>();boolean duplicates=false,sameOwners=true;for(Anchor peer:peers){if(!actual.add(peer.label))duplicates=true;Anchor peerOwner=firstClauseInSequence(clauses,enclosing(clauses,peer.ordinal));sameOwners&=peerOwner!=null&&peerOwner.ordinal==owner.ordinal;}boolean pairKnown=instructions.size()==1&&peers.size()==2&&expected.size()==2&&actual.equals(expected)&&!duplicates&&sameOwners&&ownerEnd!=null;
            int at=peers.indexOf(option),limit=at+1<peers.size()?peers.get(at+1).ordinal:ownerLimit;ObjectNode group=groups.addObject();group.put("id","body-option-scope:"+digest(option.member.id+"|"+option.label));group.put("kind","complete_body_option_scope");group.put("label",option.label);group.put("parentClauseLabel",owner.label);group.put("automaticEditEligible",false);group.put("semanticApplicabilityVerified",false);group.put("markerRequired",false);group.put("scopeMeaning","Complete native body Option branch from its standalone heading to the next source Option or same/parent-level clause; common preceding provisions and source selection guidance remain context");ArrayNode ids=group.putArray("orderedMemberIds"),nativeIds=group.putArray("nativeMemberIds"),ranges=group.putArray("memberRanges"),physical=group.putArray("physicalContextMemberIds"),guidance=group.putArray("guidanceContextMemberIds"),parent=group.putArray("parentContextMemberIds"),noteIds=group.putArray("guidanceNoteIds");boolean known=true;
            for(int n=option.ordinal;n<limit;n++){Member member=members.get(n);if(!sameStory(owner.member,member))continue;nativeIds.add(member.id);known&=member.usable;if(noteMembers.contains(member.id)){guidance.add(member.id);continue;}if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members))){physical.add(member.id);continue;}addRange(ranges,ids,member,0,member.text.length());}
            for(int n=owner.ordinal;n<peers.get(0).ordinal;n++){Member member=members.get(n);if(!sameStory(owner.member,member))continue;if(noteMembers.contains(member.id)){appendUnique(guidance,member.id);continue;}if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members))){appendUnique(physical,member.id);continue;}parent.add(member.id);}
            group.set("targetMemberIds",ids.deepCopy());boolean notesComplete=true;for(JsonNode note:instructions){appendUnique(noteIds,note.path("id").asText());notesComplete&=note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();}ObjectNode proof=group.putObject("selectionEvidence");proof.put("sameExplicitNativeOwner",sameOwners);proof.put("expectedOptionCount",expected.size());proof.put("observedStandaloneOptionCount",peers.size());proof.put("distinctLabelsMatchInstruction",actual.equals(expected)&&!duplicates);proof.put("selectionInstructionCount",instructions.size());proof.set("ownerSourcePosition",owner.member.value.path("sourcePosition").deepCopy());group.put("captureComplete",known&&(at+1<peers.size()||ownerEnd!=null));group.put("scopeComplete",pairKnown&&known&&notesComplete&&ids.size()>1);group.putObject("startBoundary").put("kind","standalone_native_body_option_heading").put("memberId",option.member.id).put("offset",0);ObjectNode boundary=group.putObject("endBoundary");boundary.put("kind",at+1<peers.size()?"next_standalone_native_body_option_heading":ownerEnd!=null?"next_same_or_parent_level_native_clause":"unverified_native_story_end");if(limit<members.size())boundary.put("memberId",members.get(limit).id);if(!group.path("scopeComplete").asBoolean())issue(issues,"BODY_OPTION_SCOPE_INCOMPLETE",group.path("id").asText(),"The same-owner explicit selection instruction, distinct complete Option pair, text channels or source peer boundary is unresolved");
        }
    }
    private static int clauseDepth(String label){int depth=1;for(int n=0;n<label.length();n++)if(label.charAt(n)=='.')depth++;return depth;}

    private static void completeAlternatives(String profile,List<Member> members,List<Anchor> clauses,ArrayNode notes,ArrayNode groups,ArrayNode issues) {
        List<Anchor> options=new ArrayList<>();
        for(int n=0;n<members.size();n++){Member member=members.get(n);if(!member.usable||member.row.isEmpty()||!member.body())continue;Matcher matcher=ALTERNATIVE.matcher(member.text);if(matcher.find())options.add(new Anchor(n,matcher.group(1).toLowerCase(Locale.ROOT)+":"+(matcher.group(2)==null?matcher.group(3):matcher.group(2)).toLowerCase(Locale.ROOT),"explicit_native_row_option_annotation",member));}
        for(int n=0;n<options.size();) {
            Anchor first=options.get(n),parent=enclosing(clauses,first.ordinal);String parentLabel=parent==null?"":parent.label;int last=n;
            while(last+1<options.size()&&options.get(last+1).label.equals(first.label)&&parentLabel.equals(label(enclosing(clauses,options.get(last+1).ordinal))))last++;
            Anchor peer=last+1<options.size()?options.get(last+1):null,nextClause=null;
            for(Anchor clause:clauses)if(clause.ordinal>first.ordinal&&!clause.label.equals(parentLabel)&&sameStory(first.member,clause.member)){nextClause=clause;break;}
            if(peer!=null&&(!sameStory(first.member,peer.member)||!parentLabel.equals(label(enclosing(clauses,peer.ordinal)))))peer=null;
            Anchor end=peer!=null&&(nextClause==null||peer.ordinal<nextClause.ordinal)?peer:nextClause;
            Anchor subitem="SCT".equals(profile)?nextNumericSubitem(members,clauses,parentLabel,first.ordinal):null;
            if(subitem!=null&&(end==null||subitem.ordinal<end.ordinal))end=subitem;
            Anchor prelude=null;for(JsonNode note:notes){int noteAt=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(noteAt<=first.ordinal||end!=null&&noteAt>=end.ordinal)continue;Matcher following=Pattern.compile("(?i)\\bCondition\\s+((?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*)\\s+below\\s+applies").matcher(note.path("text").asText());if(following.find()&&!following.group(1).replaceAll("\\s+","").equals(parentLabel)){prelude=new Anchor(noteAt,following.group(1),"next_clause_leading_guidance",members.get(noteAt));break;}}if(prelude!=null)end=prelude;
            int begin=rowStart(members,first.ordinal),limit=end==null?members.size():rowStart(members,end.ordinal);
            ObjectNode group=groups.addObject();String id="alternative-scope:"+digest(first.member.id+"|"+first.label);group.put("id",id);group.put("kind","complete_alternative_scope");group.put("label",first.label);group.put("parentClauseLabel",parentLabel);group.put("semanticApplicabilityVerified",false);group.put("automaticEditEligible",false);
            ArrayNode ids=group.putArray("orderedMemberIds"),nativeRows=group.putArray("nativeRowMemberIds"),guidanceContext=group.putArray("guidanceContextMemberIds"),parentContext=group.putArray("parentContextMemberIds"),interstitial=group.putArray("interstitialContextMemberIds"),annotations=group.putArray("continuationAnnotationMemberIds"),tableProof=group.putArray("continuationTableEvidence");boolean known=true,unclassifiedOutside=false,mixedInstruction=false;
            String subitemLabel="SCT".equals(profile)?numericSubitemInRow(first.member,members):"";
            Set<String> allowedTables=new HashSet<>();allowedTables.add(first.member.table);for(int k=n+1;k<=last;k++){Anchor continuation=options.get(k);boolean owner=false;for(Anchor clause:clauses)if(clause.label.equals(parentLabel)&&clause.member.table.equals(continuation.member.table))owner=true;Set<String> originalColumns=rowColumns(first.member,members),continuationColumns=rowColumns(continuation.member,members);boolean compatible=!originalColumns.isEmpty()&&originalColumns.equals(continuationColumns)&&first.member.column.equals(continuation.member.column);ObjectNode proof=tableProof.addObject();proof.put("tableNodePath",continuation.member.table);proof.put("sameExplicitOwner",owner);proof.put("sameBranchAnnotation",true);proof.put("compatiblePhysicalCellColumns",compatible);proof.put("comparisonBasis","explicit_branch_annotation_rows_only_excluding_other_header_rows");proof.put("originalAnnotationRowNodePath",first.member.row);proof.put("continuationAnnotationRowNodePath",continuation.member.row);if(owner&&compatible)allowedTables.add(continuation.member.table);}
            for(int cursor=begin;cursor<limit;cursor++) {
                Member member=members.get(cursor);if(!sameStory(first.member,member))continue;
                if(member.row.isEmpty()||furnitureRow(profile,member,members)||!allowedTables.contains(member.table)){interstitial.add(member.id);if(!member.text.trim().isEmpty()&&!pageFurniture(profile,member,members))unclassifiedOutside=true;}
                else {nativeRows.add(member.id);known&=member.usable;if(!subitemLabel.isEmpty()&&parentContinuationRow(member,parentLabel,members)){parentContext.add(member.id);}else if(memberHasInstruction(member,notes)||compilationCell(member,members,notes)){guidanceContext.add(member.id);mixedInstruction|=!member.text.trim().isEmpty()&&!instructionOnly(member,notes);}else ids.add(member.id);}
            }
            for(int k=n;k<=last;k++)annotations.add(options.get(k).member.id);
            ArrayNode targetAnchors=group.putArray("targetAnchorMemberIds");for(int k=n;k<=last;k++){int row=rowStart(members,options.get(k).ordinal);for(int cursor=row;cursor<members.size()&&members.get(cursor).row.equals(options.get(k).member.row);cursor++){Member member=members.get(cursor);if(!member.text.trim().isEmpty()&&!memberHasInstruction(member,notes)){appendUnique(targetAnchors,member.id);break;}}}
            ArrayNode guidanceIds=group.putArray("guidanceNoteIds");boolean notesClosed=true;for(int k=n;k<=last;k++){boolean found=false;for(JsonNode note:notes)if(hasId(note.path("orderedMemberIds"),options.get(k).member.id)){found=true;appendUnique(guidanceIds,note.path("id").asText());notesClosed&=note.path("sourceSyntaxComplete").asBoolean()&&note.path("captureComplete").asBoolean()&&!note.path("crossTableContinuationCandidate").asBoolean();}notesClosed&=found;}
            boolean parallelRow=false;for(Anchor option:options)if(option!=first&&option.member.row.equals(first.member.row)&&!option.label.equals(first.label))parallelRow=true;
            boolean complete=parent!=null&&end!=null&&known&&notesClosed&&!unclassifiedOutside&&!mixedInstruction&&ids.size()>0&&!parallelRow;
            Member parallelTarget=null;if(parallelRow){group.put("scopeStatus","parallel_options_in_one_native_row_require_paragraph_association");group.set("rowContextMemberIds",rowIds(first.member,members));ids.removeAll();complete=false;Member paired=parallelOptionTarget(first,options,members,notes);if(paired!=null){parallelTarget=paired;ids.add(paired.id);nativeRows.removeAll();for(Member member:members)if(member.row.equals(first.member.row)&&sameStory(first.member,member))nativeRows.add(member.id);targetAnchors.removeAll();targetAnchors.add(paired.id);addRange(group.putArray("memberRanges"),array(),paired,0,paired.text.length());group.put("scopeStatus","parallel_source_lexical_signature_and_distinct_marked_subitem_paragraph");ObjectNode proof=group.putObject("paragraphAssociationEvidence");proof.put("targetMemberId",paired.id);proof.put("annotationMemberId",first.member.id);proof.put("basis","unique_source_program_token_or_explicit_original_hard_copy_phrase_with_one_to_one_parallel_assignment");proof.put("sourcePositionMeaning","one_complete_marked_source_subitem_paragraph_no_unlabelled_continuation_absorbed");complete=notesClosed&&paired.usable;}}
            group.set("targetMemberIds",ids.deepCopy());group.put("captureComplete",known&&end!=null);group.put("scopeComplete",complete);group.put("scopeMeaning","Body and business-reference members of the explicitly annotated Alternative; compilation guidance and physical row evidence are separate context, and project selection remains undecided");
            group.putObject("startBoundary").put("kind","native_row_start_of_explicit_option_annotation").put("memberId",members.get(begin).id);
            ObjectNode boundary=group.putObject("endBoundary");boundary.put("kind",end==null?"unverified_story_end":end==prelude?"next_clause_leading_guidance_native_row":end==subitem?"next_distinct_parent_subitem_native_row":end==peer?"next_distinct_option_native_row":"next_distinct_clause_native_row");if(end!=null)boundary.put("memberId",members.get(limit).id);
            if(parallelTarget!=null){group.putObject("startBoundary").put("kind","explicit_marked_parallel_source_subitem_paragraph").put("memberId",parallelTarget.id).put("offset",0);group.putObject("endBoundary").put("kind","observed_complete_parallel_source_subitem_paragraph_end").put("memberId",parallelTarget.id).put("offset",parallelTarget.text.length());}
            if(!complete)issue(issues,"ALTERNATIVE_SCOPE_INCOMPLETE",id,"A parent, next peer, complete annotation or interstitial disposition is missing");n=last+1;
        }
    }

    private static Member parallelOptionTarget(Anchor option,List<Anchor> all,List<Member> members,ArrayNode notes){
        List<Anchor> rowOptions=new ArrayList<>();for(Anchor peer:all)if(peer.member.row.equals(option.member.row)&&sameStory(peer.member,option.member))rowOptions.add(peer);if(rowOptions.size()<2)return null;Set<String> labels=new HashSet<>();for(Anchor peer:rowOptions)if(!labels.add(peer.label))return null;
        List<Member> targets=new ArrayList<>();String label="",cell="";int first=-1,last=-1;for(int n=0;n<members.size();n++){Member member=members.get(n);if(!member.usable||!member.row.equals(option.member.row)||!sameStory(member,option.member)||memberHasInstruction(member,notes))continue;Matcher marked=Pattern.compile("^\\s*[*#]\\s*(\\([a-z0-9]+\\))\\s+.+",Pattern.CASE_INSENSITIVE).matcher(member.text);if(!marked.matches())continue;if(label.isEmpty()){label=marked.group(1);cell=member.cell;first=n;}if(!label.equals(marked.group(1))||!cell.equals(member.cell))return null;targets.add(member);last=n;}if(targets.size()!=rowOptions.size())return null;
        for(int n=first;n<members.size();n++){Member member=members.get(n);if(!member.row.equals(option.member.row)||!sameStory(member,option.member))break;if(member.cell.equals(cell)&&!member.text.trim().isEmpty()&&!targets.contains(member))return null;}
        Map<Anchor,Member> assignment=new HashMap<>();Set<String> assigned=new HashSet<>();for(Anchor peer:rowOptions){String instruction="";for(JsonNode note:notes)if(hasId(note.path("orderedMemberIds"),peer.member.id)){if(!instruction.isEmpty())return null;instruction=note.path("text").asText();}if(instruction.isEmpty())return null;List<Member> matches=new ArrayList<>();for(Member target:targets){boolean supported=false;Matcher token=Pattern.compile("\\b(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]+\\b").matcher(instruction);while(token.find())if(Pattern.compile("(?i)(?<![A-Za-z0-9])"+Pattern.quote(token.group())+"(?![A-Za-z0-9])").matcher(target.text).find())supported=true;if(instruction.matches("(?is).*\\bhard\\s+copy\\b.*")&&target.text.matches("(?is)^\\s*[*#]\\s*\\([a-z0-9]+\\)\\s*(?:the\\s+)?original\\s+hard\\s+copy\\b.*"))supported=true;if(supported)matches.add(target);}if(matches.size()!=1||!assigned.add(matches.get(0).id))return null;assignment.put(peer,matches.get(0));}
        return assignment.get(option);
    }

    private static void resolveReferenceSet(String profile,List<Member> members,List<Anchor> clauses,Set<String> noteMembers,int noteAt,JsonNode note,String enumeration,ArrayNode groups,ArrayNode issues) {
        Matcher references=REFERENCE.matcher(enumeration);List<String> requested=new ArrayList<>();while(references.find())requested.add(references.group().replaceAll("\\s+",""));
        ObjectNode group=groups.addObject();String id="explicit-reference-scope:"+digest(note.path("id").asText()+"|"+enumeration);group.put("id",id);group.put("kind","explicit_reference_scope");group.put("guidanceNoteId",note.path("id").asText());group.put("instructionReferenceText",enumeration);group.put("scopeMeaning","Exactly the explicitly enumerated source locations; this does not authorize deleting entire referenced units");group.put("automaticEditEligible",false);group.put("semanticApplicabilityVerified",false);
        ArrayNode targets=group.putArray("targets"),ids=group.putArray("orderedMemberIds"),physicalContext=group.putArray("physicalContextMemberIds"),parentContext=group.putArray("parentContextMemberIds");Set<String> selected=new HashSet<>();Map<Integer,GlyphMap> pdfFrames=new HashMap<>();boolean complete=!requested.isEmpty()&&note.path("sourceSyntaxComplete").asBoolean()&&note.path("captureComplete").asBoolean();
        for(String reference:requested) {
            String root=reference.replaceFirst("\\(.*$","");List<Anchor> matching=new ArrayList<>();for(Anchor clause:clauses)if(clause.label.equals(root))matching.add(clause);
            Anchor owner=enclosing(clauses,noteAt),chosen=owner!=null&&owner.label.equals(root)?owner:matching.size()==1?matching.get(0):null;
            if(chosen==null&&note.path("text").asText().matches("(?is).*\\bCondition\\s+"+Pattern.quote(reference)+"\\s+below\\s+applies.*"))for(Anchor candidate:matching)if(candidate.ordinal>noteAt){chosen=candidate;break;}
            chosen=firstClauseInSequence(clauses,chosen);
            ObjectNode target=targets.addObject();target.put("reference",reference);List<Unit> units=chosen==null?Collections.emptyList():units(chosen,nextClause(clauses,chosen,members.size()),members,noteMembers,array());
            List<Unit> exact=new ArrayList<>();for(Unit unit:units)if(unit.reference.equals(reference))exact.add(unit);
            if(exact.size()!=1){target.put("scopeComplete",false);target.put("status",exact.isEmpty()?"missing_source_unit":"ambiguous_source_unit");complete=false;continue;}
            Unit unit=exact.get(0);ArrayNode targetIds=target.putArray("orderedMemberIds"),targetRanges=target.putArray("memberRanges"),context=target.putArray("ancestorContextMemberIds"),shared=target.putArray("sharedParentTailContextMemberIds"),physical=target.putArray("physicalContextMemberIds"),parentContinuation=target.putArray("parentContextMemberIds");boolean known=!unit.ambiguousTail&&unit.sourcePagesContiguous;
            boolean pdf=chosen.member.value.has("blockObservation");if(pdf)known&=unit.end<members.size()&&pdfBoundaryFrame(profile,members.get(unit.end),members,pdfFrames);
            for(int cursor=unit.sharedTailStart;cursor>=0&&cursor<unit.sharedTailEnd;cursor++){shared.add(members.get(cursor).id);known&=members.get(cursor).usable&&(!pdf||pdfReferenceFrame(members.get(cursor),pdfFrames));}
            String parentReference=reference.replaceFirst("\\([^()]*\\)$","");for(Unit parent:units)if(parent.reference.equals(parentReference)&&parent.depth<unit.depth){for(int cursor=parent.start;cursor<unit.start;cursor++){Member member=members.get(cursor);if(noteMembers.contains(member.id))continue;if(cursor==parent.start||!SUBITEM.matcher(member.text).find()&&cursor<firstChild(units,parent))context.add(member.id);}break;}
            for(int cursor=unit.start;cursor<unit.end;cursor++){Member member=members.get(cursor);if(!sameStory(chosen.member,member)||noteMembers.contains(member.id))continue;if(pdf)known&=pdfReferenceFrame(member,pdfFrames)&&!unresolvedPdfRevisionHeading(profile,member,members);if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members)||sourceReferenceColumnContext(member,members))){physical.add(member.id);appendUnique(physicalContext,member.id);continue;}if(member.usable&&reference.contains("(")&&parentContinuationRow(member,root,members)){parentContinuation.add(member.id);appendUnique(parentContext,member.id);continue;}addRange(targetRanges,targetIds,member,0,member.text.length());selected.add(member.id);known&=member.usable;}
            target.put("scopeComplete",known&&targetIds.size()>0);target.put("status",!unit.sourcePagesContiguous?"source_page_interval_incomplete":unit.ambiguousTail?"ambiguous_parent_tail":known?"resolved_explicit_native_unit":"source_member_unknown");target.putObject("startBoundary").put("kind","explicit_native_clause_or_subitem_label").put("memberId",members.get(unit.start).id);
            ObjectNode end=target.putObject("endBoundary");end.put("kind",unit.sharedTailStart==unit.end?"shared_parent_predicate_tail_start":unit.end<members.size()?"next_native_peer_or_parent_label":"native_story_end");if(unit.end<members.size())end.put("memberId",members.get(unit.end).id);complete&=known&&targetIds.size()>0;
        }
        for(Member member:members)if(selected.contains(member.id))ids.add(member.id);group.put("captureComplete",complete);group.put("scopeComplete",complete);if(!complete)issue(issues,"EXPLICIT_REFERENCE_SCOPE_INCOMPLETE",id,"At least one explicitly requested source unit is missing, ambiguous or has unknown channels");
    }

    private static List<Unit> units(Anchor clause,int end,List<Member> members,Set<String> noteMembers,JsonNode frames) {
        List<Unit> result=new ArrayList<>();result.add(new Unit(clause.label,clause.ordinal,end,0));List<String> path=new ArrayList<>(),types=new ArrayList<>(),quotePrefix=new ArrayList<>(),pendingQuotePrefix=null;int pendingIntro=-1,pendingIntroDepth=-1,articleIntro=-1,ambiguousOwnerDepth=-1;String articleReference=null;List<String> articlePrefix=null;Map<Integer,SourceListFrame> listFrames=new TreeMap<>();String profile=clause.label.startsWith("SCT")?"SCT":clause.label.startsWith("SCC")?"SCC":"NTT";
        for(int cursor=clause.ordinal+1;cursor<end;cursor++) {
            Member member=members.get(cursor);if(!member.usable||!sameStory(clause.member,member)||noteMembers.contains(member.id)||parallelFormColumnLabel(member,members)||sourceReferenceColumnContext(member,members))continue;Matcher marker=SUBITEM.matcher(member.text);List<String> labels=new ArrayList<>();int labelEnd;boolean labelled=marker.find();
            if(!labelled&&articleReference!=null&&!member.value.has("blockObservation")&&sameNativeSource(members.get(articleIntro),member)&&path.size()>=articlePrefix.size()&&path.subList(0,articlePrefix.size()).equals(articlePrefix)){marker=Pattern.compile("^\\s*[“‘\\\"']?\\s*"+Pattern.quote(articleReference)+"\\s*((?:\\([a-z0-9]+\\)\\s*)+)",Pattern.CASE_INSENSITIVE).matcher(member.text);labelled=marker.find();}
            if(labelled){Matcher tokens=Pattern.compile("\\(([a-z0-9]+)\\)",Pattern.CASE_INSENSITIVE).matcher(marker.group(1));while(tokens.find())labels.add(tokens.group(1));labelEnd=marker.end();}
            else {Matcher literal=Pattern.compile("^\\s*([0-9]+\\.|[a-zA-Z]+\\))\\s+\\S").matcher(member.text);if(!literal.find()||member.value.has("blockObservation")){if(!member.value.has("blockObservation")&&sourceListIntroduction(member.text)){pendingIntro=cursor;pendingIntroDepth=member.text.trim().matches("(?is)^Provided\\b.*")?-1:path.size()-1;}continue;}labels.add(literal.group(1).substring(0,literal.group(1).length()-1));labelEnd=literal.end()-1;}
            boolean enumeration=true;for(String label:labels)enumeration&=label.matches("[0-9]+[a-zA-Z]*|([a-z])\\1*|([A-Z])\\2*|[ivxlcdm]+|[IVXLCDM]+")||romanSuffix(label);if(!enumeration)continue;
            if(pendingIntro>=0&&pendingIntroDepth<0&&members.get(pendingIntro).text.trim().matches("(?is)^Provided\\b.*")){Member introduction=members.get(pendingIntro);int aligned=sourceAlignedOwner(introduction,clause,path,result,members),owner=aligned;if(owner<0)owner=sourceReferencedOwner(introduction,clause,path,quotePrefix,result,members);ambiguousOwnerDepth=owner<0&&aligned==-2?path.size()-1:-1;if(owner>=0)while(path.size()>owner+1){path.remove(path.size()-1);types.remove(types.size()-1);}}
            int sourcePeer=sourcePeerDepth(member,clause,path,result,members);if(sourcePeer>=0&&sourcePeer<quotePrefix.size())quotePrefix.clear();
            boolean enteringQuote=pendingQuotePrefix!=null&&sourcePeer<0&&!sourceAmendmentHeading(member.text);if(enteringQuote){quotePrefix=new ArrayList<>(pendingQuotePrefix);path=new ArrayList<>(quotePrefix);types.clear();for(String label:path)types.add(labelType(label,member));}pendingQuotePrefix=null;
            if(labels.size()>1){path=new ArrayList<>(quotePrefix);types.clear();for(String label:path)types.add(labelType(label,member));for(String label:labels){path.add(label);types.add(labelType(label,member));}}
            else {
                String label=labels.get(0),type=sourceLabelType(label,member,cursor,end,members,noteMembers);int depth=sourcePeer;
                if(enteringQuote)depth=path.size();
                else if(depth>=0&&nextLabel(path.get(depth),types.get(depth)).equals(label))type=types.get(depth);
                else for(int at=path.size()-1;at>=0;at--)if(nextLabel(path.get(at),types.get(at)).equals(label)){depth=at;type=types.get(at);break;}
                if(depth<0&&!types.isEmpty()&&types.get(types.size()-1).endsWith("Roman")&&type.endsWith("Roman")&&!member.value.path("paragraphProperties").path("layoutEvidence").path("numbering").path("numbered").asBoolean()){depth=types.size()-1;type=types.get(depth);}
                if(depth<0&&type.startsWith("decimal"))depth=0;
                if(depth<0&&!path.isEmpty()&&(label.equals("a")||label.equals("A"))&&types.get(types.size()-1).endsWith("Roman"))depth=path.size();
                if(depth<0&&label.equals("A"))for(int at=types.size()-1;at>=0;at--)if(types.get(at).endsWith("Roman")){depth=at+1;break;}
                if(depth<0)for(int at=path.size()-1;at>=0;at--)if(path.get(at).equals(label)){depth=at;type=types.get(at);break;}
                if(depth<0)for(int at=path.size()-1;at>=0;at--)if(types.get(at).equals(type)){depth=at;break;}
                if(depth<0)depth=path.size();if(!enteringQuote&&depth<quotePrefix.size())quotePrefix.clear();while(path.size()>depth){path.remove(path.size()-1);types.remove(types.size()-1);}path.add(label);types.add(type);
            }
            int itemDepth=path.size()-1;boolean newList=pendingIntro>=0&&(pendingIntroDepth<0||itemDepth>pendingIntroDepth)&&!member.value.has("blockObservation");Iterator<Map.Entry<Integer,SourceListFrame>> active=listFrames.entrySet().iterator();while(active.hasNext()){Map.Entry<Integer,SourceListFrame> entry=active.next();if(entry.getKey()>itemDepth||newList&&entry.getKey()==itemDepth){entry.getValue().end=newList?pendingIntro:cursor;active.remove();}}
            if(newList){SourceListFrame frame=new SourceListFrame(pendingIntro,cursor,end);frame.ownerAmbiguous=ambiguousOwnerDepth>=0&&itemDepth>ambiguousOwnerDepth;if(frame.ownerAmbiguous){String ownerReference=clause.label;for(int d=0;d<itemDepth;d++)ownerReference+="("+path.get(d)+")";for(int p=result.size()-1;p>=0;p--)if(result.get(p).reference.equals(ownerReference)){result.get(p).ambiguousTail=true;break;}}listFrames.put(itemDepth,frame);}pendingIntro=-1;pendingIntroDepth=-1;ambiguousOwnerDepth=-1;
            StringBuilder reference=new StringBuilder(clause.label);boolean continuation=member.text.substring(labelEnd).trim().matches("(?i)^\\(\\s*Cont[’']?d\\s*\\)\\.?$");
            for(int depth=0;depth<path.size();depth++){
                reference.append('(').append(path.get(depth)).append(')');Unit prior=null;
                for(int at=result.size()-1;at>=0;at--){Unit candidate=result.get(at);if(candidate.depth<depth+1)break;if(candidate.reference.equals(reference.toString())){prior=candidate;break;}}
                if(depth+1<path.size()&&prior!=null||continuation&&prior!=null)continue;
                Unit unit=new Unit(reference.toString(),cursor,end,depth+1);for(Map.Entry<Integer,SourceListFrame> entry:listFrames.entrySet())if(entry.getKey()<=depth)unit.listFrame=entry.getValue();result.add(unit);
            }
            if(articlePrefix!=null&&(path.size()<articlePrefix.size()||!path.subList(0,articlePrefix.size()).equals(articlePrefix))){articleReference=null;articlePrefix=null;articleIntro=-1;}
            String introducedArticle=member.value.has("blockObservation")?null:articleReplacementNumber(member.text);
            if(amendmentInstruction(member.text)||introducedArticle!=null)pendingQuotePrefix=new ArrayList<>(path);
            if(introducedArticle!=null){articleReference=introducedArticle;articlePrefix=new ArrayList<>(path);articleIntro=cursor;}
            if(!member.value.has("blockObservation")&&sourceListIntroduction(member.text)){pendingIntro=cursor;pendingIntroDepth=itemDepth;}
        }
        for(int n=1;n<result.size();n++)for(int next=n+1;next<result.size();next++)if(result.get(next).depth<=result.get(n).depth){result.get(n).end=result.get(next).start;break;}
        for(Unit unit:result)if(unit.listFrame!=null)unit.end=Math.min(unit.end,unit.listFrame.end);
        for(Unit unit:result)limitUnitToFrame(unit,frameId(frames,members.get(unit.start).id),frames,members);
        for(Unit last:new ArrayList<>(result))if(last.depth>1) {
            List<Unit> siblings=new ArrayList<>();String parent=last.reference.replaceFirst("\\([^()]*\\)$","");for(Unit unit:result)if(unit.depth==last.depth&&unit.listFrame==last.listFrame&&unit.reference.replaceFirst("\\([^()]*\\)$","").equals(parent))siblings.add(unit);
            if(siblings.size()<2||siblings.get(siblings.size()-1)!=last)continue;
            if(members.get(last.start).value.has("blockObservation")){int observed=pdfSharedTail(last,siblings,result,members,noteMembers);if(observed>=0){for(Unit sibling:siblings){sibling.sharedTailStart=observed;sibling.sharedTailEnd=last.end;sibling.ambiguousTail=false;}last.end=observed;continue;}if(pdfChildContinuation(last,members,noteMembers))continue;}
            int tail=-1;for(int cursor=last.start+1;cursor<last.end;cursor++){Member member=members.get(cursor);if(!sameStory(clause.member,member)||noteMembers.contains(member.id)||member.text.trim().isEmpty()||sourceReferenceColumnContext(member,members))continue;if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members)||parentContinuationRow(member,clause.label,members)||sourceContinuationHeading(member,members)))continue;Member head=members.get(last.start);DirectIndent child=directIndent(head),next=directIndent(member);boolean labelledDescendant=false;for(Unit descendant:result)if(descendant.depth>last.depth&&descendant.reference.startsWith(last.reference+"(")&&(descendant.start<=cursor&&cursor<descendant.end||descendant.listFrame!=null&&(descendant.listFrame.intro==cursor||cursor<descendant.listFrame.intro&&child!=null&&next!=null&&child.unit.equals(next.unit)&&child.left==next.left))){labelledDescendant=true;break;}if(labelledDescendant||member.text.trim().matches("(?is)^Provided\\b.*")&&head.text.trim().matches("(?s).*[.!?]$")&&sameSourceListDeclaration(head,member)&&!sourceNamesParent(member,last.reference))continue;if(sourceFormulaContinuation(head,member))break;if(!sourceSharedTailCue(member)&&(child!=null&&next!=null&&child.unit.equals(next.unit)&&next.left>=child.left||!head.cell.isEmpty()&&sameLane(head,member)&&!head.text.trim().matches("(?s).*[.!?,;]$")))continue;tail=cursor;break;}
            if(tail<0)continue;boolean shared=sourceSharedTailCue(members.get(tail));
            DirectIndent parentIndent=directIndent(members.get(tail));
            for(Unit sibling:siblings){DirectIndent childIndent=directIndent(members.get(sibling.start));shared&=parentIndent!=null&&childIndent!=null&&childIndent.unit.equals(parentIndent.unit)&&childIndent.left>parentIndent.left&&childIndent.hanging>parentIndent.hanging;}
            shared|=sourceParentPredicate(members.get(tail),siblings,members);
            for(Unit sibling:siblings){sibling.sharedTailStart=tail;sibling.sharedTailEnd=last.end;sibling.ambiguousTail=!shared;}
            last.end=tail;
        }
        for(int at=result.size()-1;at>0;at--){Unit child=result.get(at);if(child.ambiguousTail&&child.sharedTailStart>=0)for(Unit parent:result)if(parent.depth>0&&parent.depth+1==child.depth&&child.start>=parent.start&&child.start<parent.end&&child.sharedTailEnd==parent.end&&child.reference.startsWith(parent.reference+"(")){parent.ambiguousTail=true;if(parent.listFrame!=null){String owner=parent.reference.replaceFirst("\\([^()]*\\)$","");for(Unit sibling:result)if(sibling.depth==parent.depth&&sibling.listFrame==parent.listFrame&&sibling.reference.replaceFirst("\\([^()]*\\)$","").equals(owner))sibling.ambiguousTail=true;}}}
        for(Unit unit:result)if(unit.listFrame!=null&&unit.listFrame.ownerAmbiguous)unit.ambiguousTail=true;
        for(Unit unit:result)if(members.get(unit.start).value.has("blockObservation"))unit.sourcePagesContiguous=pdfContiguousInterval(members,unit.start,Math.max(unit.end,unit.sharedTailEnd));
        return result;
    }

    private static String labelType(String label,Member member){
        if(label.matches("[0-9]+"))return "decimal";
        if(label.matches("[0-9]+[a-zA-Z]+"))return "decimalSuffix";
        if(romanSuffix(label))return "romanSuffix";
        String format=member.value.path("paragraphProperties").path("layoutEvidence").path("numbering").path("format").asText();
        if(format.equals("lowerRoman")||format.equals("upperRoman"))return Character.isUpperCase(label.charAt(0))?"upperRoman":"lowerRoman";
        if(format.equals("lowerLetter")||format.equals("upperLetter"))return Character.isUpperCase(label.charAt(0))?"upperLetter":"lowerLetter";
        return label.matches("(?:i|[ivxlcdm]{2,})")?"lowerRoman":label.matches("(?:I|[IVXLCDM]{2,})")?"upperRoman":Character.isUpperCase(label.charAt(0))?"upperLetter":"lowerLetter";
    }

    private static String sourceLabelType(String label,Member member,int cursor,int end,List<Member> members,Set<String> noteMembers){
        String type=labelType(label,member);if(member.value.path("paragraphProperties").path("layoutEvidence").path("numbering").path("numbered").asBoolean())return type;
        if(label.matches("[ivxlcdmIVXLCDM]"))for(int at=cursor+1;at<end;at++){Member next=members.get(at);if(!next.usable||!sameStory(member,next)||noteMembers.contains(next.id))continue;Matcher marker=SUBITEM.matcher(next.text);if(!marker.find())continue;Matcher token=Pattern.compile("^\\(([a-zA-Z0-9]+)\\)").matcher(marker.group(1));if(token.find()){String letter=Character.isUpperCase(label.charAt(0))?"upperLetter":"lowerLetter",roman=Character.isUpperCase(label.charAt(0))?"upperRoman":"lowerRoman";if(token.group(1).equals(nextLabel(label,letter)))return letter;if(token.group(1).equals(nextLabel(label,roman)))return roman;}break;}return type;
    }
    private static boolean parallelFormColumnLabel(Member member,List<Member> members){if(member.row.isEmpty()||!member.text.trim().matches("\\([A-Za-z]+\\)"))return false;for(Member peer:members)if(sameStory(member,peer)&&member.row.equals(peer.row)&&!member.cell.equals(peer.cell)&&peer.text.trim().matches("\\([A-Za-z]+\\)"))return true;return false;}

    private static int sourcePeerDepth(Member member,Anchor clause,List<String> path,List<Unit> units,List<Member> members){
        Matcher marker=SUBITEM.matcher(member.text);if(!marker.find())return -1;Matcher token=Pattern.compile("\\(([a-zA-Z0-9]+)\\)").matcher(marker.group(1));if(!token.find())return -1;String label=token.group(1),reference=clause.label;int found=-1;
        for(int depth=0;depth<path.size();depth++){reference+="("+path.get(depth)+")";for(int at=units.size()-1;at>=0;at--){Unit candidate=units.get(at);if(!candidate.reference.equals(reference))continue;Member prior=members.get(candidate.start);if(sameSourceListDeclaration(member,prior)&&(nextLabel(path.get(depth),labelType(path.get(depth),prior)).equals(label)||sourceAmendmentHeading(member.text)&&sourceAmendmentHeading(prior.text)))found=depth;break;}}return found;
    }

    private static boolean sameSourceListDeclaration(Member first,Member second){
        JsonNode a=first.value.path("paragraphProperties").path("layoutEvidence").path("numbering"),b=second.value.path("paragraphProperties").path("layoutEvidence").path("numbering");
        if(a.path("numbered").asBoolean()&&b.path("numbered").asBoolean()&&a.path("numId").equals(b.path("numId"))&&a.path("level").equals(b.path("level")))return true;
        Map<String,String> left=sourceIndentDeclarations(first),right=sourceIndentDeclarations(second);return !left.isEmpty()&&left.equals(right);
    }

    private static Map<String,String> sourceIndentDeclarations(Member member){
        Map<String,String> values=new HashMap<>();JsonNode properties=member.value.path("paragraphProperties"),layout=properties.path("layoutEvidence"),layers=layout.path("layers");if(properties.has("styleChainStatus"))return values;
        if(!"paragraph".equals(layout.path("numbering").path("source").asText()))for(JsonNode value:layout.path("effective").path("ind"))if("numbering_and_paragraph_style_precedence_not_verified".equals(value.path("reason").asText()))return values;
        if(layers.isArray()&&!layers.isEmpty()){for(JsonNode layer:layers){int count=0;for(JsonNode declaration:layer.path("declarations"))if("ind".equals(declaration.path("name").asText())){if(++count>1)return Collections.emptyMap();mergeWordIndent(values,declaration.path("attributes"));}}}
        else {JsonNode declarations=member.value.path("directParagraphLayout").path("ind");if(declarations.size()!=1)return values;mergeWordIndent(values,declarations.get(0).path("attributes"));}return values;
    }
    private static void mergeWordIndent(Map<String,String> values,JsonNode attributes){
        Map<String,String> declared=new HashMap<>();attributes.fields().forEachRemaining(field->{String key=field.getKey().replaceFirst("^.*}","");if(key.matches("(?:left|start|hanging|firstLine)(?:Chars)?"))declared.put(key,field.getValue().asText());});
        for(String suffix:new String[]{"","Chars"})if(declared.containsKey("left"+suffix)||declared.containsKey("start"+suffix)){values.remove("left"+suffix);values.remove("start"+suffix);}
        declared.forEach((key,value)->{
            // MS-OI29500 2.1.44: Word ignores zero character indents and their earlier character declarations.
            boolean conflictingAlias=(key.equals("leftChars")||key.equals("startChars"))&&declared.containsKey("leftChars")&&declared.containsKey("startChars")&&!declared.get("leftChars").equals(declared.get("startChars"));
            if(key.endsWith("Chars")&&value.matches("[+-]?0+")&&!conflictingAlias)values.remove(key);else values.put(key,value);
        });
    }

    private static int sourceAlignedOwner(Member introduction,Anchor clause,List<String> path,List<Unit> units,List<Member> members){
        DirectIndent declared=directIndent(introduction);if(declared==null)return -1;String reference=clause.label;int found=-1;
        for(int depth=0;depth<path.size();depth++){reference+="("+path.get(depth)+")";for(int at=units.size()-1;at>=0;at--){Unit candidate=units.get(at);if(!candidate.reference.equals(reference))continue;DirectIndent prior=directIndent(members.get(candidate.start));if(prior!=null&&declared.unit.equals(prior.unit)&&declared.left==prior.left){if(found>=0)return -2;found=depth;}break;}}return found;
    }

    private static int sourceReferencedOwner(Member introduction,Anchor clause,List<String> path,List<String> replacementPrefix,List<Unit> units,List<Member> members){
        if(replacementPrefix.isEmpty())return -1;String frame=clause.label;for(String label:replacementPrefix)frame+="("+label+")";
        Unit instruction=null;for(Unit unit:units)if(unit.reference.equals(frame))instruction=unit;if(instruction==null)return -1;
        Matcher amended=Pattern.compile("(?i)\\b(?:sub-)?clauses?\\s+([0-9]+(?:\\.[0-9]+)*)(?:\\([a-z0-9]+\\))*").matcher(members.get(instruction.start).text);if(!amended.find()||!amendmentInstruction(members.get(instruction.start).text))return -1;
        Matcher references=Pattern.compile("(?i)(?<![A-Za-z0-9.])"+Pattern.quote(amended.group(1))+"((?:\\([a-z0-9]+\\))+)").matcher(introduction.text);List<String> common=null;int count=0;
        while(references.find()){
            if(count==0&&!introduction.text.substring(0,references.start()).trim().matches("(?is)^Provided(?:\\s+further)?\\s+that\\s+(?:the\\s+)?(?:total|aggregate|combined)\\b(?:(?!\\b(?:using|this\\s+(?:sub-)?clause)\\b).)*\\b(?:sub-)?clauses?\\s*$"))return -1;
            Matcher labels=Pattern.compile("\\(([a-zA-Z0-9]+)\\)").matcher(references.group(1));List<String> observed=new ArrayList<>();String reference=frame;while(labels.find()){observed.add(labels.group(1));reference+="("+labels.group(1)+")";}
            boolean exists=false;for(Unit unit:units)if(unit.start>=instruction.start&&unit.reference.equals(reference))exists=true;if(!exists)return -1;
            if(common==null)common=new ArrayList<>(observed);else{int shared=0;while(shared<common.size()&&shared<observed.size()&&common.get(shared).equals(observed.get(shared)))shared++;common=new ArrayList<>(common.subList(0,shared));}count++;
        }
        if(count<2||common==null||common.isEmpty()||replacementPrefix.size()+common.size()>path.size())return -1;
        for(int at=0;at<common.size();at++)if(!common.get(at).equals(path.get(replacementPrefix.size()+at)))return -1;return replacementPrefix.size()+common.size()-1;
    }

    private static boolean romanSuffix(String label){
        Matcher suffix=Pattern.compile("^([ivxlcdm]+)([A-Z])\\2*$|^([IVXLCDM]+)([a-z])\\4*$").matcher(label);if(!suffix.matches())return false;String prefix=suffix.group(1)==null?suffix.group(3):suffix.group(1);return !nextLabel(prefix,Character.isUpperCase(prefix.charAt(0))?"upperRoman":"lowerRoman").isEmpty();
    }

    private static String nextLabel(String label,String type){
        if(type.equals("decimal"))return new java.math.BigInteger(label).add(java.math.BigInteger.ONE).toString();
        if(type.equals("decimalSuffix")||type.equals("romanSuffix")){Matcher suffix=Pattern.compile(type.equals("decimalSuffix")?"^([0-9]+)([a-zA-Z]+)$":"^([ivxlcdm]+)([A-Z]+)$|^([IVXLCDM]+)([a-z]+)$").matcher(label);if(!suffix.matches())return "";String prefix=suffix.group(1)==null?suffix.group(3):suffix.group(1),letters=suffix.group(2)==null?suffix.group(4):suffix.group(2);return prefix+nextLabel(letters,Character.isUpperCase(letters.charAt(0))?"upperLetter":"lowerLetter");}
        if(type.endsWith("Roman")){long current=0;int previous=0;String text=label.toUpperCase(Locale.ROOT);for(int at=text.length()-1;at>=0;at--){int index="IVXLCDM".indexOf(text.charAt(at));if(index<0)return "";int number=new int[]{1,5,10,50,100,500,1000}[index];current+=number<previous?-number:number;previous=Math.max(previous,number);}return current>0&&DocxNumberingResolver.format(current,type).equals(label)?DocxNumberingResolver.format(current+1,type):"";}
        for(int at=1;at<label.length();at++)if(label.charAt(at)!=label.charAt(0))return "";
        long number=(label.length()-1L)*26+Character.toLowerCase(label.charAt(0))-'a'+1;return DocxNumberingResolver.format(number+1,type);
    }

    private static boolean amendmentInstruction(String text){return text.matches("(?is).*\\b(?:clause|sub-clause)s?\\s+[0-9]+(?:\\.[0-9]+)*(?:\\([a-z0-9]+\\))*.*\\b(?:substituting|replacing|inserted\\s+as|replaced\\s+by|amended\\s+to\\s+read|(?:adding|inserting)\\b.*\\bfollowing)\\b.*");}
    private static String articleReplacementNumber(String text){
        if(!sourceListIntroduction(text))return null;Matcher article=Pattern.compile("(?i)\\bArticle\\s+([0-9]+(?:\\.[0-9]+)*)\\b").matcher(text);if(!article.find())return null;String reference=article.group(1);int end=article.end();if(text.substring(end).matches("(?s)^\\s*\\(.*")||article.find())return null;
        return text.substring(end).matches("(?is).*\\b(?:substituting\\s+(?:with\\s+|by\\s+)?|replacing\\s+(?:with\\s+|by\\s+)?|replaced\\s+by\\s+|amended\\s+to\\s+read\\s+)(?:the\\s+)?following\\s*[:：]\\s*[-–—]?\\s*$")?reference:null;
    }
    private static boolean sourceAmendmentHeading(String text){return text.matches("(?is).*\\b(?:clause|sub-clause)s?\\s+[0-9]+(?:\\.[0-9]+)*(?:\\([a-z0-9]+\\))*.*\\b(?:amended|substituting|replacing|inserted|replaced|adding|inserting|deleting|deleted)\\b.*");}
    private static boolean sourceListIntroduction(String text){return text.trim().matches("(?s).*\\p{L}.*[:：]\\s*[-–—]?\\s*$");}
    private static boolean sourceParentPredicate(Member tail,List<Unit> siblings,List<Member> members){
        if(siblings.isEmpty()||siblings.get(0).listFrame==null)return false;String text=tail.text.trim(),intro=members.get(siblings.get(0).listFrame.intro).text;boolean unfinished=true;
        for(Unit sibling:siblings)unfinished&=!members.get(sibling.start).text.trim().matches("(?s).*[.!?]$");
        if(unfinished&&text.matches("(?is)^(?:for\\s+(?:a|the)\\s+(?:period|duration)\\b|shall\\b|which\\s+(?:is|are)\\b|whichever\\b).*"))return true;
        if(unfinished&&text.matches("(?is)^and\\s+we\\s+(?:undertake|agree|confirm|warrant)\\b.*")&&intro.matches("(?is)^\\s*[*#]*\\s*(?:(?:\\([a-z0-9]+\\)|[a-z0-9]+[.)])\\s*)*(?:we|our)\\b.*"))return true;
        if(sourceSharedTailCue(tail)&&sourceNamesParent(tail,siblings.get(0).reference))return true;
        if(sourceSharedTailCue(tail)){Matcher defined=Pattern.compile("\\(([^()]+)\\)").matcher(intro);while(defined.find()){String name=defined.group(1).trim(),shortName=name.replaceFirst("(?is)\\s+in\\s+respect\\s+of\\b.*$","");if(!shortName.equals(name)&&!intro.substring(defined.end()).trim().matches("(?is)^(?:shall|will|must)\\b.*"))continue;if(shortName.matches("[A-Za-z][A-Za-z -]+")&&Pattern.compile("(?is)^Provided(?:\\s+further)?\\s+that(?:\\s+in\\s+the\\s+(?:case|event)\\b[^,]*,)?\\s+the\\s+(?:"+Pattern.quote(name)+"|"+Pattern.quote(shortName)+")\\s+(?:shall|will|must)\\b").matcher(text).find())return true;}}
        DirectIndent parent=directIndent(tail);if(parent==null)return false;Matcher subject=Pattern.compile("(?i)^(?:the|a|an)\\s+([a-z][a-z -]*?)\\s+(?:shall|will|must)\\b").matcher(text);boolean repeatedSubject=subject.find()&&Pattern.compile("(?i)\\b(?:the|a|an)\\s+"+Pattern.quote(subject.group(1))+"(?![a-z])").matcher(intro).find();
        DirectIndent introduction=directIndent(members.get(siblings.get(0).listFrame.intro));if(repeatedSubject&&introduction!=null&&parent.unit.equals(introduction.unit)&&parent.left==introduction.left)return true;
        for(Unit sibling:siblings){DirectIndent child=directIndent(members.get(sibling.start));if(child==null||!child.unit.equals(parent.unit)||child.left<=parent.left||child.hanging<=parent.hanging)return false;}
        if(unfinished&&(text.matches("(?is)^(?:and|but)\\b.*")||text.matches("(?is).*\\b(?:shall|will|must)\\b.*")||intro.matches("(?is).*\\bshall\\b.*")&&text.matches("(?is)^\\([^)]*\\),?\\s*have\\b.*")))return true;
        return repeatedSubject;
    }
    private static boolean sourceFormulaContinuation(Member owner,Member continuation){return owner.text.matches("(?is).*\\b(?:fraction|formula|equation)\\b.*")&&continuation.text.trim().matches("(?is)^\\(?i\\.e\\.\\s+[^=]+=[^=]+.*");}
    private static boolean sourceNamesParent(Member member,String childReference){
        String parent=childReference.replaceFirst("\\([^()]*\\)$","");Matcher references=REFERENCE.matcher(member.text);
        while(references.find())if(references.group().replaceAll("\\s+","").equals(parent)){String prefix=member.text.substring(0,references.start());if(!prefix.matches("(?is).*\\b(?:using|this\\s+(?:item|(?:sub-)?clause))\\b.*")&&prefix.matches("(?is).*\\b(?:for\\s+the\\s+purposes?\\s+of|(?:sum|amount|value)\\s+(?:(?:referred\\s+to|defined)\\s+in|under))\\s+(?:clause\\s+)?$"))return true;}return false;
    }
    private static boolean sourceSharedTailCue(Member member){return member.text.trim().matches("(?is)^(?:provided\\b|subject\\s+to\\b|except\\s+that\\b|whichever\\b|for\\s+(?:a|the)\\s+(?:period|duration)\\b|shall\\b|which\\s+(?:is|are)\\b).*" );}

    private static void lastSentence(List<Member> members,int noteAt,JsonNode note,ArrayNode groups,ArrayNode issues) {
        if(members.get(noteAt).value.has("blockObservation")){if(!pdfLastSentence(members,noteAt,note,groups))issue(issues,"LAST_SENTENCE_TARGET_UNVERIFIED",note.path("id").asText(),"The marked final sentence lacks complete same-source native glyph, page, flow or sentence-boundary evidence");return;}
        int previous=noteAt-1;while(previous>=0&&members.get(previous).usable&&members.get(previous).text.trim().isEmpty()&&sameStory(members.get(noteAt),members.get(previous)))previous--;
        if(previous<0)return;Member member=members.get(previous);if(!member.usable||!sameStory(members.get(noteAt),member))return;
        int end=member.text.length();while(end>0&&Character.isWhitespace(member.text.charAt(end-1)))end--;int start=end;
        if(start>0&&sentenceBoundary(member.text,start-1))start--;
        while(start>0&&!sentenceBoundary(member.text,start-1))start--;while(start<end&&Character.isWhitespace(member.text.charAt(start)))start++;
        boolean marked=start<end&&(member.text.charAt(start)=='*'||member.text.charAt(start)=='#');if(!marked){issue(issues,"LAST_SENTENCE_TARGET_UNVERIFIED",note.path("id").asText(),"The immediately preceding source paragraph has no leading marker on its last sentence");return;}
        ObjectNode group=groups.addObject();group.put("id","last-sentence:"+digest(note.path("id").asText()+"|"+member.id));group.put("kind","inline_last_sentence_scope");group.put("guidanceNoteId",note.path("id").asText());group.put("captureComplete",true);group.put("scopeComplete",note.path("sourceSyntaxComplete").asBoolean()&&note.path("captureComplete").asBoolean());group.put("scopeMeaning","Only the marked last sentence explicitly identified by the adjacent source instruction");group.put("automaticEditEligible",false);group.put("wholeParagraphTargetAssumed",false);ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds");addRange(ranges,ids,member,start,end);group.put("text",member.text.substring(start,end));group.putObject("startBoundary").put("kind","marked_sentence_after_previous_sentence_boundary").put("memberId",member.id).put("offset",start);group.putObject("endBoundary").put("kind","observed_paragraph_end").put("memberId",member.id).put("offset",end);
    }

    private static void markerDispositions(String profile,List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups,ArrayNode markers,ArrayNode issues) {
        Set<String> noteMembers=new HashSet<>();for(JsonNode note:notes)for(JsonNode id:note.path("orderedMemberIds"))noteMembers.add(id.asText());List<Anchor> clauses=clauseAnchors(profile,members);
        quotedInstructionScopes(members,clauses,noteMembers,notes,groups,issues);
        sidebarUnitScopes(profile,members,clauses,noteMembers,notes,groups,issues);
        sourceReviewedVisualSidebarScopes(members,notes,groups);
        placeholderScopes(members,clauses,noteMembers,notes,groups);
        inlineConditionalScopes(members,noteMembers,notes,groups);
        inlineChoiceScopes(members,noteMembers,notes,groups);
        explicitListScopes(members,clauses,noteMembers,notes,groups);
        for(JsonNode note:notes) {
            int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;Member instruction=members.get(at);String text=note.path("text").asText();
            Matcher below=Pattern.compile("(?i)\\bCondition\\s+((?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*(?:\\([a-z0-9]+\\))*)\\s+below\\s+applies").matcher(text);
            if(below.find()){resolveReferenceSet(profile,members,clauses,noteMembers,at,note,below.group(1),groups,issues);ObjectNode group=(ObjectNode)groups.get(groups.size()-1);group.put("kind","explicit_condition_unit_scope");group.put("scopeMeaning","The complete native source unit explicitly named by the applicability instruction; inner markers retain independent conditions");}
            Matcher above=Pattern.compile("(?i)delete\\s+items?\\s+marked\\s+with\\s+([*#])\\s+above").matcher(text);
            if(above.find())markedItemsAbove(members,clauses,at,note,above.group(1),groups,issues);
            boolean external=text.matches("(?is).*\\bcheck\\b.*\\b(?:latest|current|prevailing)\\b.*\\b(?:rate|fee|price)s?\\b.*");
            if(external){externalRateScope(members,instruction,note,groups);for(JsonNode condition:conditions)if(note.path("id").asText().equals(condition.path("guidanceNoteId").asText()))((ObjectNode)condition).put("requiredAction","external_verification_required");}
            Matcher words=Pattern.compile("(?is)[*#].*\\bdelete\\s+the\\s+words\\b").matcher(text);
            if(words.find()&&!instruction.row.isEmpty())for(Member target:members)if(target.usable&&target.row.equals(instruction.row)&&sameStory(target,instruction)&&!noteMembers.contains(target.id)) {
                char symbol=words.group().charAt(0);int symbolAt=target.text.indexOf(symbol);if(symbolAt<0)continue;int open=target.text.lastIndexOf('(',symbolAt),close=parenthesisEnd(target.text,open);
                if(open>=0&&close>symbolAt){ObjectNode group=groups.addObject();group.put("id","inline-words:"+digest(note.path("id").asText()+"|"+target.id+"|"+open));group.put("kind","inline_parenthetical_scope");group.put("guidanceNoteId",note.path("id").asText());group.put("captureComplete",true);group.put("scopeComplete",note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean());group.put("scopeMeaning","Only the marked words inside the source parentheses; wrapper cleanup requires an independent decision");group.put("automaticEditEligible",false);group.put("symbol",String.valueOf(symbol));group.put("text",target.text.substring(symbolAt,close-1));addRange(group.putArray("memberRanges"),group.putArray("orderedMemberIds"),target,symbolAt,close-1);ArrayNode wrappers=group.putArray("wrapperContextRanges");addRange(wrappers,array(),target,open,symbolAt);addRange(wrappers,array(),target,close-1,close);group.put("wrapperCleanupStatus","independent_decision_required");}
            }
        }
        commonAlternativeSelections(members,clauses,notes,conditions,groups);
        nestedSourceConditions(members,notes,conditions,groups);
        for(JsonNode raw:markers) {
            ObjectNode marker=(ObjectNode)raw;String memberId=marker.path("memberId").asText();int offset=marker.path("start").asInt();Member member=members.get(indexOf(members,memberId));ArrayNode targetGroups=marker.putArray("targetGroupIds"),contexts=marker.putArray("contextGroupIds"),noteIds=marker.putArray("guidanceNoteIds"),conditionIds=marker.putArray("conditionIds");
            marker.put("disposition","unknown");boolean negative=false,external=false,signpost=false;
            for(JsonNode note:notes)if(rangeContains(note.path("memberRanges"),memberId,offset)) {negative=true;appendUnique(noteIds,note.path("id").asText());}
            for(JsonNode note:notes)if(note.path("text").asText().matches("(?is).*\\bcheck\\b.*\\b(?:latest|current|prevailing)\\b.*\\b(?:rate|fee|price)s?\\b.*")){int noteAt=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(noteAt>=0&&!member.row.isEmpty()&&member.row.equals(members.get(noteAt).row)&&sameStory(member,members.get(noteAt))){external=true;appendUnique(noteIds,note.path("id").asText());}}
            for(JsonNode group:groups) {
                if(!hasId(group.path("orderedMemberIds"),memberId))continue;String kind=group.path("kind").asText();boolean exact=rangeContains(group.path("memberRanges"),memberId,offset)||rangeContains(group.path("markerRanges"),memberId,offset);
                boolean leading=member.text.substring(0,Math.min(offset,member.text.length())).trim().isEmpty();boolean unitInstruction=kind.equals("source_unit_instruction_scope");boolean target=exact&&!unitInstruction&&!kind.equals("complete_body_option_scope")&&!kind.equals("complete_alternative_scope")||kind.equals("explicit_reference_scope")&&noteHasSymbol(notes,group.path("guidanceNoteId").asText(),marker.path("symbol").asText())||kind.equals("explicit_marked_item_scope")&&leading&&group.path("symbol").asText().equals(marker.path("symbol").asText())||kind.equals("explicit_condition_unit_scope")&&leading&&unitMarkerAnchor(group,member,true)||unitInstruction&&leading&&unitMarkerAnchor(group,member,false)||kind.equals("complete_alternative_scope")&&leading&&hasId(group.path("targetAnchorMemberIds"),memberId);
                if(group.path("scopeComplete").asBoolean()&&target&&!kind.equals("native_outline_physical_scope")){appendUnique(targetGroups,group.path("id").asText());if(group.has("guidanceNoteId"))appendUnique(noteIds,group.path("guidanceNoteId").asText());for(JsonNode id:group.path("guidanceNoteIds"))appendUnique(noteIds,id.asText());external|=kind.equals("external_verification_scope");}
                else appendUnique(contexts,group.path("id").asText());
            }
            if(targetGroups.isEmpty()){Matcher heading=CLAUSE.matcher(member.text);if(heading.find())for(JsonNode note:notes){String symbol=marker.path("symbol").asText(),root=heading.group(1).replaceAll("\\s+","");int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at>=0&&root.equals(label(enclosing(clauses,at)))&&note.path("text").asText().contains(symbol)){Matcher reference=REFERENCE.matcher(note.path("text").asText());while(reference.find())if(reference.group().replaceAll("\\s+","").startsWith(root+"(")){signpost=true;appendUnique(noteIds,note.path("id").asText());}}}}
            for(JsonNode condition:conditions)if(hasId(noteIds,condition.path("guidanceNoteId").asText()))appendUnique(conditionIds,condition.path("id").asText());
            if(isTocCopy(member)){marker.put("status","negative_toc_source_copy");marker.put("disposition","negative_toc_source_copy");marker.put("negativeEvidence","Native TOC hyperlink and matching PAGEREF field; this copied marker has no independent body edit target");targetGroups.removeAll();}
            else if(negative){marker.put("status","negative_instruction_symbol");marker.put("disposition","negative_instruction_symbol");}
            else if(external){marker.put("status","external_verification_required");marker.put("disposition","external_verification_required");marker.put("requiredAction","verify_current_external_rate_before_any_value_change");}
            else if(targetGroups.size()>0){marker.put("status","source_scope_resolved_project_condition_unknown");marker.put("disposition","conditional_source_target");}
            else if(signpost){marker.put("status","source_signpost_exact_targets_defined_by_guidance");marker.put("disposition","source_instruction_signpost");marker.put("scopeMeaning","Heading marker points to the same clause's explicit guidance targets; the entire heading is not an edit target");}
            else {marker.put("status","unknown_marker_scope");marker.put("unknownReasonCode",unknownMarkerReason(member,offset));marker.put("unknownReason","The literal marker is retained, but no complete source instruction establishes its exact target under the current rules");}
            marker.put("scopeComplete",!negative&&targetGroups.size()>0);
        }
    }

    /** Context references preserve instructions without granting their marker an edit target. */
    private static void markerSourceContexts(String profile,List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups,ArrayNode markers){
        List<Anchor> clauses=clauseAnchors(profile,members);Set<String> noteMembers=noteMemberIds(notes);
        for(JsonNode raw:markers){
            if(!"unknown_marker_scope".equals(raw.path("status").asText()))continue;ObjectNode marker=(ObjectNode)raw;int at=uniqueMemberIndex(members,marker.path("memberId").asText());if(at<0)continue;Member member=members.get(at);int offset=marker.path("start").asInt();ArrayNode noteIds=(ArrayNode)marker.path("guidanceNoteIds");boolean choice=false,quoted=false,instruction=false;
            for(JsonNode group:groups)if("native_source_field_candidate".equals(group.path("kind").asText())&&group.path("sourceChoiceComplete").asBoolean()&&rangeContains(group.path("markerRanges"),member.id,offset)&&nativeRangesBound(group.path("markerRanges"),member,members)&&nativeRangesBound(group.path("memberRanges"),member,members)){
                choice=true;appendUnique((ArrayNode)marker.path("contextGroupIds"),group.path("id").asText());for(JsonNode id:group.path("guidanceNoteIds"))appendUnique(noteIds,id.asText());for(JsonNode relationship:group.path("sourceInstructionRelationships"))addSourceRelationship(marker,(ObjectNode)relationship);
            }
            for(JsonNode note:notes){
                int noteAt=uniqueMemberIndex(members,note.path("orderedMemberIds").path(0).asText());String text=note.path("text").asText(),symbol=marker.path("symbol").asText();
                if(noteAt<0||!sameNativeSource(member,members.get(noteAt))||!contextNoteBound(note,member,members)||sourceCondition(note,conditions)==null||!text.contains(symbol)||!text.matches("(?is).*\\b(?:delete|amend)\\b.*")||!instructionTargetMembers(members,clauses,noteMembers,noteAt,text,true).contains(member.id))continue;
                boolean quotedAction=Pattern.compile("(?is)\\b(?:delete|amend)\\s+(?:the\\s+)?(?:words?\\s+)?[“‘\"']").matcher(text).find();
                if(quotedAction){
                    Matcher literal=Pattern.compile("[“‘\"']([^”’\"']+)[”’\"']").matcher(text);int markedQuotes=0;while(literal.find())if(literal.group(1).contains(symbol))markedQuotes++;if(markedQuotes!=1)continue;
                    if(!sameRowNote(member,note,members))continue;boolean resolved=false;for(JsonNode group:groups)if(note.path("id").asText().equals(group.path("guidanceNoteId").asText())&&group.path("scopeComplete").asBoolean()&&group.path("kind").asText().startsWith("explicit_quoted_"))resolved=true;if(resolved)continue;
                    int candidates=0;for(JsonNode candidate:markers){int candidateAt=uniqueMemberIndex(members,candidate.path("memberId").asText());if(candidateAt>=0&&!noteMembers.contains(candidate.path("memberId").asText())&&sameNativeSource(member,members.get(candidateAt))&&member.row.equals(members.get(candidateAt).row)&&symbol.equals(candidate.path("symbol").asText()))candidates++;}if(candidates!=1)continue;quoted=true;
                }
                appendUnique(noteIds,note.path("id").asText());instruction=true;
                ObjectNode relationship=object();relationship.put("id","source-instruction-relation:"+digest("marker-context|"+marker.path("id").asText()+"|"+note.path("id").asText()));relationship.put("kind","source_instruction_context");relationship.put("guidanceNoteId",note.path("id").asText());relationship.set("conditionId",sourceCondition(note,conditions).path("id").deepCopy());relationship.put("basis",quotedAction?"same_native_row_unique_marker_quoted_target_unreconciled":"same_native_source_unit_and_literal_instruction_symbol");relationship.set("instructionMemberRanges",note.path("memberRanges").deepCopy());relationship.put("status","source_context_only_exact_edit_target_unknown");relationship.put("automaticEditEligible",false);relationship.put("projectApplicabilityVerified",false);addSourceRelationship(marker,relationship);
            }
            for(JsonNode condition:conditions)if(hasId(noteIds,condition.path("guidanceNoteId").asText()))appendUnique((ArrayNode)marker.path("conditionIds"),condition.path("id").asText());
            if(choice){marker.put("unknownReasonCode","SOURCE_CHOICE_OBSERVED_PROJECT_SELECTION_REQUIRED");marker.put("unknownReason","The exact source choice and its existing field context are retained; no project selection or edit instruction is supplied");}
            else if(quoted){marker.put("unknownReasonCode","EXPLICIT_QUOTED_INSTRUCTION_WITHOUT_EXACT_MARKED_TARGET");marker.put("unknownReason","The same native row supplies a quoted instruction, but its literal target has not been reconciled with one exact marked source range; fuzzy wording does not authorize an edit");}
            else if(instruction){marker.put("unknownReasonCode","SOURCE_INSTRUCTION_CONTEXT_WITHOUT_EXACT_TARGET_RANGE");marker.put("unknownReason","The same source unit supplies an explicit instruction context, but the exact local deletion or amendment boundary remains unknown");}
        }
    }
    private static boolean contextNoteBound(JsonNode note,Member source,List<Member> members){
        if(!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean()||note.path("memberRanges").isEmpty())return false;
        for(JsonNode range:note.path("memberRanges")){if(nativeRangesBound(array().add(range),source,members))continue;int at=uniqueMemberIndex(members,range.path("memberId").asText());if(at<0||!sameNativeSource(source,members.get(at))||!nativePureSpacer(members.get(at))||!"text".equals(range.path("channel").asText())||!range.path("sourcePosition").equals(members.get(at).value.path("sourcePosition"))||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber()||range.path("start").asInt()!=0||range.path("end").asInt()!=0)return false;}return true;
    }

    private static void commonAlternativeSelections(List<Member> members,List<Anchor> clauses,ArrayNode notes,ArrayNode conditions,ArrayNode groups){
        Pattern selection=Pattern.compile("(?is)^\\s*[\\[(]?\\s*Guidance\\s+Notes?\\s*:\\s*Select\\s+Alternative\\s*\\(([a-z0-9]+)\\)\\s+or\\s*\\(([a-z0-9]+)\\)\\s+for\\s+(?:condition\\s+)?((?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*)(?![\\p{L}\\d.(])");
        Map<String,List<JsonNode>> selections=new LinkedHashMap<>();for(JsonNode note:notes){Matcher match=selection.matcher(note.path("text").asText());if(match.find())selections.computeIfAbsent(match.group(3).replaceAll("\\s+","").toUpperCase(Locale.ROOT),ignored->new ArrayList<>()).add(note);}
        for(Map.Entry<String,List<JsonNode>> entry:selections.entrySet()){
            if(entry.getValue().size()!=1)continue;JsonNode note=entry.getValue().get(0);Matcher match=selection.matcher(note.path("text").asText());if(!match.find()||!note.path("text").asText().substring(match.end()).matches("\\s*\\.?\\s*(?:\\)|\\])?\\s*"))continue;
            Set<String> expected=new HashSet<>(Arrays.asList("alternative:"+match.group(1).toLowerCase(Locale.ROOT),"alternative:"+match.group(2).toLowerCase(Locale.ROOT)));if(expected.size()!=2)continue;
            JsonNode condition=sourceCondition(note,conditions);int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0||condition==null)continue;Member source=members.get(at);if(!completeNativeNote(note,source,members))continue;
            List<ObjectNode> branches=new ArrayList<>();for(JsonNode group:groups)if("complete_alternative_scope".equals(group.path("kind").asText())&&entry.getKey().equals(group.path("parentClauseLabel").asText()))branches.add((ObjectNode)group);if(branches.size()!=2)continue;
            Set<String> observed=new HashSet<>();boolean complete=true;int first=members.size(),lastInstruction=at;for(JsonNode id:note.path("orderedMemberIds"))lastInstruction=Math.max(lastInstruction,indexOf(members,id.asText()));
            for(JsonNode branch:branches){complete&=branch.path("scopeComplete").asBoolean()&&nativeGroupBound(branch,source,members)&&observed.add(branch.path("label").asText());int begin=indexOf(members,branch.path("startBoundary").path("memberId").asText());complete&=begin>=0;first=Math.min(first,begin);}
            if(!complete||!expected.equals(observed)||lastInstruction>=first)continue;
            for(Anchor clause:clauses)if(clause.ordinal>lastInstruction&&clause.ordinal<first&&sameNativeSource(source,clause.member)&&!entry.getKey().equals(clause.label))complete=false;if(!complete)continue;
            ObjectNode relationship=object();relationship.put("id","source-instruction-relation:"+digest("selection|"+note.path("id").asText()+"|"+branches.get(0).path("id").asText()+"|"+branches.get(1).path("id").asText()));relationship.put("kind","explicit_alternative_selection");relationship.put("guidanceNoteId",note.path("id").asText());relationship.put("conditionId",condition.path("id").asText());relationship.put("ownerReference",entry.getKey());ArrayNode groupIds=relationship.putArray("groupIds");for(JsonNode branch:branches)groupIds.add(branch.path("id").asText());relationship.set("instructionMemberRanges",note.path("memberRanges").deepCopy());relationship.put("status","literal_source_relationship_project_choice_unknown");
            for(ObjectNode branch:branches){appendUnique((ArrayNode)branch.path("guidanceNoteIds"),note.path("id").asText());addSourceRelationship(branch,relationship);}
        }
    }

    private static void nestedSourceConditions(List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups){
        for(JsonNode child:groups){if(!"inline_parenthetical_scope".equals(child.path("kind").asText())||!child.path("scopeComplete").asBoolean())continue;
            List<JsonNode> parents=new ArrayList<>();for(JsonNode parent:groups)if("explicit_condition_unit_scope".equals(parent.path("kind").asText())){boolean overlap=false;for(JsonNode target:parent.path("targets"))overlap|=rangesOverlap(target.path("memberRanges"),child.path("memberRanges"));if(overlap)parents.add(parent);}if(parents.size()!=1)continue;
            JsonNode parent=parents.get(0),target=parent.path("targets").path(0);if(!parent.path("scopeComplete").asBoolean()||parent.path("targets").size()!=1||!target.path("scopeComplete").asBoolean()||!strictlyContainsRanges(target.path("memberRanges"),child.path("memberRanges")))continue;
            JsonNode parentNote=sourceNote(notes,parent.path("guidanceNoteId").asText()),childNote=sourceNote(notes,child.path("guidanceNoteId").asText());if(parentNote==null||childNote==null||parentNote==childNote)continue;JsonNode parentCondition=sourceCondition(parentNote,conditions),childCondition=sourceCondition(childNote,conditions);int at=indexOf(members,parentNote.path("orderedMemberIds").path(0).asText());if(at<0||parentCondition==null||childCondition==null)continue;Member source=members.get(at);
            if(!completeNativeNote(parentNote,source,members)||!completeNativeNote(childNote,source,members)||!nativeGroupBound(parent,source,members)||!nativeGroupBound(child,source,members))continue;
            ObjectNode relationship=object();relationship.put("id","source-instruction-relation:"+digest("nested|"+parent.path("id").asText()+"|"+child.path("id").asText()));relationship.put("kind","nested_source_condition");relationship.put("parentGroupId",parent.path("id").asText());relationship.put("parentGuidanceNoteId",parentNote.path("id").asText());relationship.put("parentConditionId",parentCondition.path("id").asText());relationship.put("childGroupId",child.path("id").asText());relationship.put("childGuidanceNoteId",childNote.path("id").asText());relationship.put("childConditionId",childCondition.path("id").asText());relationship.put("status","physical_source_containment_local_conditions_independent");addSourceRelationship((ObjectNode)parent,relationship);addSourceRelationship((ObjectNode)child,relationship);
        }
    }

    private static JsonNode sourceNote(ArrayNode notes,String id){JsonNode found=null;for(JsonNode note:notes)if(id.equals(note.path("id").asText())){if(found!=null)return null;found=note;}return found;}
    private static JsonNode sourceCondition(JsonNode note,ArrayNode conditions){JsonNode found=null;for(JsonNode condition:conditions)if(note.path("id").asText().equals(condition.path("guidanceNoteId").asText())){if(found!=null||!"literal_source_condition_not_project_boolean".equals(condition.path("status").asText())||!note.path("text").equals(condition.path("text"))||!note.path("memberRanges").equals(condition.path("memberRanges")))return null;found=condition;}return found;}
    private static boolean completeNativeNote(JsonNode note,Member source,List<Member> members){return note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean()&&nativeRangesBound(note.path("memberRanges"),source,members);}
    private static boolean sameNativeSource(Member a,Member b){JsonNode first=a.value.path("sourcePosition"),second=b.value.path("sourcePosition");return a.usable&&b.usable&&!a.value.has("blockObservation")&&!b.value.has("blockObservation")&&a.body()&&b.body()&&sameStory(a,b)&&!a.part.isEmpty()&&!a.value.path("storyRootNodePath").asText().isEmpty()&&descendant(a.path,a.value.path("storyRootNodePath").asText())&&descendant(b.path,b.value.path("storyRootNodePath").asText())&&first.path("sourceSha256").asText().equals(second.path("sourceSha256").asText())&&first.path("partSha256").asText().matches("(?i)[a-f0-9]{64}")&&first.path("partSha256").asText().equalsIgnoreCase(second.path("partSha256").asText());}
    private static boolean nativeRangesBound(JsonNode ranges,Member source,List<Member> members){if(!ranges.isArray()||ranges.isEmpty())return false;for(JsonNode range:ranges){int at=indexOf(members,range.path("memberId").asText());if(at<0)return false;Member member=members.get(at);if(!sameNativeSource(source,member)||!"text".equals(range.path("channel").asText())||!range.path("sourcePosition").equals(member.value.path("sourcePosition"))||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber()||range.path("start").asInt()<0||range.path("end").asInt()<=range.path("start").asInt()||range.path("end").asInt()>member.text.length())return false;}return true;}
    private static boolean nativeGroupBound(JsonNode group,Member source,List<Member> members){
        if(group.path("orderedMemberIds").isEmpty())return false;for(String key:Arrays.asList("orderedMemberIds","nativeRowMemberIds","guidanceContextMemberIds","parentContextMemberIds","continuationAnnotationMemberIds","targetAnchorMemberIds"))for(JsonNode id:group.path(key)){int at=indexOf(members,id.asText());if(at<0||!sameNativeSource(source,members.get(at)))return false;}
        if(group.has("memberRanges")&&!nativeRangesBound(group.path("memberRanges"),source,members))return false;for(JsonNode target:group.path("targets"))if(!nativeRangesBound(target.path("memberRanges"),source,members)||!nativeBoundaries(target,source,members))return false;return nativeBoundaries(group,source,members);
    }
    private static boolean nativeBoundaries(JsonNode group,Member source,List<Member> members){for(String key:Arrays.asList("startBoundary","endBoundary"))if(group.path(key).has("memberId")){int at=indexOf(members,group.path(key).path("memberId").asText());if(at<0||!sameNativeSource(source,members.get(at)))return false;}return true;}
    private static boolean rangesOverlap(JsonNode parent,JsonNode child){for(JsonNode a:parent)for(JsonNode b:child)if(a.path("memberId").equals(b.path("memberId"))&&a.path("channel").equals(b.path("channel"))&&a.path("start").asInt()<b.path("end").asInt()&&b.path("start").asInt()<a.path("end").asInt())return true;return false;}
    private static boolean strictlyContainsRanges(JsonNode parent,JsonNode child){if(child.isEmpty())return false;long parentLength=0,childLength=0;for(JsonNode range:parent)parentLength+=range.path("end").asInt()-range.path("start").asInt();for(JsonNode local:child){int matches=0;for(JsonNode range:parent)if(range.path("memberId").equals(local.path("memberId"))&&range.path("channel").equals(local.path("channel"))&&range.path("start").asInt()<=local.path("start").asInt()&&range.path("end").asInt()>=local.path("end").asInt())matches++;if(matches!=1)return false;childLength+=local.path("end").asInt()-local.path("start").asInt();}return childLength<parentLength;}
    private static void addSourceRelationship(ObjectNode group,ObjectNode relationship){ArrayNode relationships=group.has("sourceInstructionRelationships")?(ArrayNode)group.path("sourceInstructionRelationships"):group.putArray("sourceInstructionRelationships");relationships.add(relationship.deepCopy());}
    private static void resolveSourceReferenceCandidates(String profile,List<Member> members,ArrayNode notes,ArrayNode conditions,ArrayNode groups){
        for(JsonNode rawNote:notes){ObjectNode note=(ObjectNode)rawNote;JsonNode condition=sourceCondition(note,conditions);int at=uniqueMemberIndex(members,note.path("orderedMemberIds").path(0).asText());if(at<0||condition==null||!condition.path("referenceCandidates").equals(note.path("referenceCandidates"))||!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean())continue;Member source=members.get(at);if(!sourceRangesBound(note.path("memberRanges"),source,members))continue;
            for(JsonNode rawReference:note.path("referenceCandidates")){ObjectNode reference=(ObjectNode)rawReference;int start=reference.path("start").asInt(-1),end=reference.path("end").asInt(-1);String text=note.path("text").asText(),requested=reference.path("text").asText().replaceAll("\\s+","");if(!"literal_reference_candidate_not_resolved".equals(reference.path("status").asText())||!reference.path("start").isIntegralNumber()||!reference.path("end").isIntegralNumber()||!"utf16_in_containing_text".equals(reference.path("offsetUnit").asText())||start<0||end<=start||end>text.length()||!text.substring(start,end).equals(reference.path("text").asText()))continue;
                JsonNode foundGroup=null,foundTarget=null;int matches=0;for(JsonNode group:groups){String kind=group.path("kind").asText();if(!Arrays.asList("explicit_reference_scope","explicit_condition_unit_scope","source_unit_instruction_scope").contains(kind)||!note.path("id").asText().equals(group.path("guidanceNoteId").asText())||!group.path("scopeComplete").asBoolean()||!sourceGroupBound(profile,group,source,members))continue;if(requested.equals(group.path("sourceReference").asText())){matches++;foundGroup=group;foundTarget=group;}for(JsonNode target:group.path("targets"))if(requested.equals(target.path("reference").asText())&&target.path("scopeComplete").asBoolean()){matches++;foundGroup=group;foundTarget=target;}}
                if(matches!=1)continue;reference.put("status","resolved_explicit_source_target");ObjectNode target=reference.putObject("resolvedSourceTarget");target.put("groupId",foundGroup.path("id").asText());target.put("reference",requested);target.put("memberRangesSha256",digest(canonical(foundTarget.path("memberRanges")).toString()));target.set("startBoundary",foundTarget.path("startBoundary").deepCopy());target.set("endBoundary",foundTarget.path("endBoundary").deepCopy());target.put("projectApplicabilityVerified",false);target.put("automaticEditEligible",false);
            }
            ((ObjectNode)condition).set("referenceCandidates",note.path("referenceCandidates").deepCopy());
        }
    }
    private static int uniqueMemberIndex(List<Member> members,String id){int found=-1;for(int n=0;n<members.size();n++)if(id.equals(members.get(n).id)){if(found>=0)return -1;found=n;}return found;}
    private static boolean sourceRangesBound(JsonNode ranges,Member source,List<Member> members){
        if(!ranges.isArray()||ranges.isEmpty())return false;for(JsonNode range:ranges)if(uniqueMemberIndex(members,range.path("memberId").asText())<0)return false;if(!source.value.has("blockObservation"))return nativeRangesBound(ranges,source,members);if(source.expectedPdfSource==null)return false;for(JsonNode range:ranges){int at=uniqueMemberIndex(members,range.path("memberId").asText());if(!"text".equals(range.path("channel").asText())||!"utf16".equals(range.path("offsetUnit").asText())||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber())return false;Member member=members.get(at);if(!sameStory(source,member)||!source.expectedPdfSource.equals(member.expectedPdfSource)||!range.path("sourcePosition").equals(member.value.path("sourcePosition"))||nativePdfRangeLayout(member,range.path("start").asInt(),range.path("end").asInt())==null)return false;}return true;
    }
    private static boolean sourceGroupBound(String profile,JsonNode group,Member source,List<Member> members){
        boolean pdf=source.value.has("blockObservation");if(group.path("orderedMemberIds").isEmpty())return false;for(String key:Arrays.asList("orderedMemberIds","ancestorContextMemberIds","sharedParentTailContextMemberIds","physicalContextMemberIds","parentContextMemberIds","guidanceContextMemberIds"))for(JsonNode id:group.path(key)){int at=uniqueMemberIndex(members,id.asText());if(at<0||!members.get(at).usable||!sameStory(source,members.get(at))||pdf&&!Objects.equals(source.expectedPdfSource,members.get(at).expectedPdfSource))return false;}
        if(group.has("memberRanges")&&!sourceRangesBound(group.path("memberRanges"),source,members))return false;for(JsonNode target:group.path("targets"))if(!target.path("scopeComplete").asBoolean()||!sourceGroupBound(profile,target,source,members))return false;for(String key:Arrays.asList("startBoundary","endBoundary"))if(group.path(key).has("memberId")){int at=uniqueMemberIndex(members,group.path(key).path("memberId").asText());if(at<0||!sameStory(source,members.get(at))||pdf&&(!Objects.equals(source.expectedPdfSource,members.get(at).expectedPdfSource)||!pdfBoundaryFrame(profile,members.get(at),members,new HashMap<>())))return false;}return pdf||nativeGroupBound(group,source,members);
    }

    private static void quotedInstructionScopes(List<Member> members,List<Anchor> clauses,Set<String> noteMembers,ArrayNode notes,ArrayNode groups,ArrayNode issues) {
        Pattern alternatives=Pattern.compile("(?is)delete\\s+(?:the\\s+)?inapplicable\\s+[“‘\"']([^”’\"']+)[”’\"']\\s+or\\s+[“‘\"']([^”’\"']+)[”’\"']");
        Pattern amendedWords=Pattern.compile("(?is)amend\\s+the\\s+words?\\s+((?:[“‘\"'][^”’\"']+[”’\"'])(?:\\s*(?:,|and|or)\\s*[“‘\"'][^”’\"']+[”’\"'])*)");
        Pattern deletedWord=Pattern.compile("(?is)delete\\s+(?:(?:the\\s+)?words?\\s+)?[“‘\"']([^”’\"']+)[”’\"']");
        for(JsonNode note:notes) {
            int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;
            Set<String> allowed=instructionTargetMembers(members,clauses,noteMembers,at,note.path("text").asText(),true);String text=note.path("text").asText();
            Matcher pair=alternatives.matcher(text);while(pair.find())quotedChoiceScope(members,allowed,note,pair.group(1),pair.group(2),groups,issues);
            Matcher amended=amendedWords.matcher(text);while(amended.find()){Matcher quote=Pattern.compile("[“‘\"']([^”’\"']+)[”’\"']").matcher(amended.group(1));while(quote.find())markedQuotedWordScope(members,allowed,note,quote.group(1),"amend_explicitly_quoted_word",groups,issues);}
            // Quoted references are text to remove; conditional references do not name the removal target.
            String unquoted=text.replaceAll("[“‘\"'][^”’\"']+[”’\"']","");Matcher target=Pattern.compile("(?i)\\b(?:in|from)\\s+(?:the\\s+)?(?:sub[- ]clauses?|clauses?)\\s+").matcher(unquoted);
            Set<String> deletionAllowed=instructionTargetMembers(members,clauses,noteMembers,at,target.find()?unquoted.substring(target.start()):"",true);
            Matcher deleted=deletedWord.matcher(text);while(deleted.find())if(!deleted.group(1).matches("[*#]+|_+|\\s*/\\s*"))markedQuotedWordScope(members,deletionAllowed,note,deleted.group(1),"delete_explicitly_quoted_word",groups,issues);
            Matcher named=Pattern.compile("(?is)([*#])\\s+(?:-\\s+)?(?:the\\s+)?([^#*]+?)\\s+in\\s+(?:the\\s+)?clauses?\\s+((?:NTT|SCT|SCC)\\s*\\d)").matcher(text);
            while(named.find())for(String name:named.group(2).split("(?i)\\s*,\\s*(?:(?:and|or)\\s+)?|\\s+(?:and|or)\\s+")){String term=name.replaceFirst("(?i)^the\\s+","").trim();if(term.matches("[\\p{L}][\\p{L} -]{2,100}"))markedQuotedWordScope(members,allowed,note,term,"explicitly_named_marked_object",groups,issues);}
        }
    }

    private static Set<String> instructionTargetMembers(List<Member> members,List<Anchor> clauses,Set<String> noteMembers,int noteAt,String instruction) {
        return instructionTargetMembers(members,clauses,noteMembers,noteAt,instruction,false);
    }

    private static Set<String> instructionTargetMembers(List<Member> members,List<Anchor> clauses,Set<String> noteMembers,int noteAt,String instruction,boolean lexicalPrefixOnlyWhenTailAmbiguous) {
        Member source=members.get(noteAt);Anchor owner=enclosing(clauses,noteAt);if(owner!=null&&!sameStory(source,owner.member))owner=null;Set<String> requested=new LinkedHashSet<>();Matcher explicit=REFERENCE.matcher(instruction);while(explicit.find())requested.add(explicit.group().replaceAll("\\s+",""));
        Matcher relative=Pattern.compile("(?i)sub[- ]clauses?\\s+((?:\\([a-z0-9]+\\))+(?:\\s*(?:,|and|or)\\s*(?:\\([a-z0-9]+\\))+)*)").matcher(instruction);
        if(owner!=null)while(relative.find()){Matcher path=Pattern.compile("(?:\\([a-z0-9]+\\))+",Pattern.CASE_INSENSITIVE).matcher(relative.group(1));while(path.find())requested.add(owner.label+path.group());}
        if(requested.isEmpty()&&owner!=null)requested.add(owner.label);Set<String> ids=new LinkedHashSet<>();
        for(String reference:requested) {
            String root=reference.replaceFirst("\\(.*$","");List<Anchor> possible=new ArrayList<>();for(Anchor clause:clauses)if(clause.label.equals(root)&&sameStory(source,clause.member))possible.add(clause);
            Anchor chosen=owner!=null&&owner.label.equals(root)?owner:possible.size()==1?possible.get(0):null;chosen=firstClauseInSequence(clauses,chosen);if(chosen==null)continue;
            List<Unit> nativeUnits=units(chosen,nextClause(clauses,chosen,members.size()),members,noteMembers,array());List<Unit> exact=new ArrayList<>();for(Unit unit:nativeUnits)if(unit.reference.equals(reference))exact.add(unit);if(exact.size()!=1||exact.get(0).ambiguousTail&&!lexicalPrefixOnlyWhenTailAmbiguous)continue;Unit unit=exact.get(0);
            for(int n=unit.start;n<unit.end;n++)if(members.get(n).usable&&sameStory(chosen.member,members.get(n))&&!noteMembers.contains(members.get(n).id))ids.add(members.get(n).id);
            if(!unit.ambiguousTail)for(int n=unit.sharedTailStart;n>=0&&n<unit.sharedTailEnd;n++)if(members.get(n).usable&&sameStory(chosen.member,members.get(n))&&!noteMembers.contains(members.get(n).id))ids.add(members.get(n).id);
        }
        return ids;
    }

    private static void quotedChoiceScope(List<Member> members,Set<String> allowed,JsonNode note,String first,String second,ArrayNode groups,ArrayNode issues) {
        Pattern pair=Pattern.compile("(?<![\\p{L}\\d_.-])("+literalWhitespace(first)+")(?<separator>\\s*/\\s*)("+literalWhitespace(second)+")(?![\\p{L}\\d_-]|\\.[\\p{L}\\d])(?<marker>\\s*[*#]{1,2})(?![*#])",Pattern.CASE_INSENSITIVE);
        ObjectNode group=lexicalGroup(note,"explicit_quoted_choice_scope",first+"|"+second,groups);group.put("scopeMeaning","The two source literals explicitly named as alternatives; project selection and separator/marker cleanup remain independent decisions");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),markers=group.putArray("markerRanges"),cleanup=group.putArray("cleanupCandidateRanges"),choices=group.putArray("choices");int matches=0;
        for(Member member:members)if(allowed.contains(member.id)){Matcher match=pair.matcher(member.text);while(match.find()){matches++;ObjectNode choice=choices.addObject();choice.put("memberId",member.id);choice.put("firstLiteral",member.text.substring(match.start(1),match.end(1)));choice.put("secondLiteral",member.text.substring(match.start(3),match.end(3)));ArrayNode options=choice.putArray("optionRanges");addRange(options,array(),member,match.start(1),match.end(1));addRange(options,array(),member,match.start(3),match.end(3));addRange(ranges,ids,member,match.start(1),match.end(1));addRange(ranges,ids,member,match.start(3),match.end(3));addRange(markers,array(),member,match.start("marker"),match.end("marker"));addRange(cleanup,array(),member,match.start("separator"),match.end("separator"));addRange(cleanup,array(),member,match.start("marker"),match.end("marker"));}}
        boolean complete=matches==1&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();group.put("captureComplete",matches>0);group.put("scopeComplete",complete);group.put("cleanupStatus","independent_decision_required");if(!complete)issue(issues,"QUOTED_CHOICE_SCOPE_INCOMPLETE",group.path("id").asText(),"The explicitly quoted alternatives require one exact marked pair in the named native source units");
    }

    private static void markedQuotedWordScope(List<Member> members,Set<String> allowed,JsonNode note,String term,String action,ArrayNode groups,ArrayNode issues) {
        List<int[]> offsets=new ArrayList<>();List<Member> matches=new ArrayList<>(),ends=new ArrayList<>();Pattern literal=Pattern.compile("(?<![\\p{L}\\d])"+literalWhitespace(term)+"(?![\\p{L}\\d])",Pattern.CASE_INSENSITIVE);
        for(Member member:members)if(allowed.contains(member.id)){Matcher match=literal.matcher(member.text);while(match.find()){int left=match.start(),right=match.end(),markerLeft=left,markerRight=right;while(markerLeft>0&&Character.isWhitespace(member.text.charAt(markerLeft-1)))markerLeft--;if(markerLeft>0&&(member.text.charAt(markerLeft-1)=='*'||member.text.charAt(markerLeft-1)=='#')){markerLeft--;markerRight=markerLeft+1;}else{markerLeft=right;while(markerLeft<member.text.length()&&(Character.isWhitespace(member.text.charAt(markerLeft))||"”’\"'".indexOf(member.text.charAt(markerLeft))>=0))markerLeft++;if(markerLeft>=member.text.length()||"*#".indexOf(member.text.charAt(markerLeft))<0)continue;markerRight=markerLeft+1;}matches.add(member);ends.add(member);offsets.add(new int[]{left,right,markerLeft,markerRight});}}
        // A quoted slash alternative may carry one shared suffix marker, as in ten/twenty*.
        if(matches.isEmpty()&&term.matches("[\\p{L}][\\p{L} -]*"))for(Member member:members)if(allowed.contains(member.id)){Matcher choice=Pattern.compile("(?<![\\p{L}])("+literalWhitespace(term)+")\\s*/\\s*[\\p{L}]+([*#])(?![*#])",Pattern.CASE_INSENSITIVE).matcher(member.text);while(choice.find()){matches.add(member);ends.add(member);offsets.add(new int[]{choice.start(1),choice.end(1),choice.start(2),choice.end(2)});}}
        for(int n=0;n+1<members.size();n++){int[] range=nativePdfMarkedWordContinuation(members.get(n),members.get(n+1),allowed,note,groups,literal,action.equals("delete_explicitly_quoted_word"));if(range!=null){matches.add(members.get(n));ends.add(members.get(n+1));offsets.add(range);}}
        if(matches.isEmpty())return;
        ObjectNode group=lexicalGroup(note,"explicit_quoted_word_scope",term+"|"+action,groups);group.put("requiredAction",action);group.put("quotedSourceLiteral",term);group.put("scopeMeaning","Exact source word or named object established by the literal instruction; surrounding sentence and punctuation are context");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),markers=group.putArray("markerRanges");
        for(Member member:members)for(int n=0;n<matches.size();n++)if(matches.get(n)==member){int[] range=offsets.get(n);Member end=ends.get(n);if(end==member)addRange(ranges,ids,member,range[0],range[1]);else{addPdfRange(ranges,ids,member,range[0],member.text.length(),nativePdfLayout(member));addPdfRange(ranges,ids,end,0,range[1],nativePdfLayout(end));}addRange(markers,array(),range.length==5&&range[4]==0?member:end,range[2],range[3]);}
        boolean repeatedObject=action.equals("explicitly_named_marked_object"),complete=(repeatedObject||matches.size()==1)&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();group.put("captureComplete",true);group.put("scopeComplete",complete);if(!complete)issue(issues,"QUOTED_WORD_SCOPE_INCOMPLETE",group.path("id").asText(),"The explicitly quoted word has ambiguous marked occurrences in the named source units");
    }

    private static int[] nativePdfMarkedWordContinuation(Member first,Member second,Set<String> allowed,JsonNode note,ArrayNode groups,Pattern literal,boolean quotedDeletion){
        if(!first.value.has("blockObservation")||!second.value.has("blockObservation")||!allowed.contains(first.id)||!allowed.contains(second.id)||!sameStory(first,second))return null;
        String joined=first.text+"\n"+second.text;int boundary=first.text.length()+1;Matcher match=literal.matcher(joined);
        while(match.find())if(match.start()<first.text.length()&&match.end()>boundary){
            int end=match.end()-boundary,marker=match.start(),markerMember=1;while(marker>0&&Character.isWhitespace(first.text.charAt(marker-1)))marker--;
            if(marker>0&&"*#".indexOf(first.text.charAt(marker-1))>=0){marker--;markerMember=0;if(marker>0&&"*#".indexOf(first.text.charAt(marker-1))>=0)continue;}
            else{marker=end;while(marker<second.text.length()&&(Character.isWhitespace(second.text.charAt(marker))||"”’\"'".indexOf(second.text.charAt(marker))>=0))marker++;if(marker>=second.text.length()||"*#".indexOf(second.text.charAt(marker))<0||marker+1<second.text.length()&&"*#".indexOf(second.text.charAt(marker+1))>=0)continue;}
            GlyphMap a=nativePdfLayout(first),b=nativePdfLayout(second);if(a==null||b==null||pdfPage(first)!=pdfPage(second)||!samePdfFrame(a,b)||!quotedDeletion&&!uniqueCompletePdfTarget(note,groups,first,second))return null;
            GlyphSpan left=a.glyphs.get(0),right=b.glyphs.get(0);double height=Math.max(left.height,right.height),gap=right.y-left.y;
            if(gap<=0||gap>4*height||Math.abs(left.x-right.x)*a.pageWidth>height*a.pageHeight||Math.abs(left.font-right.font)>.2)return null;
            return new int[]{match.start(),end,marker,marker+1,markerMember};
        }
        return null;
    }

    private static boolean uniqueCompletePdfTarget(JsonNode note,ArrayNode groups,Member first,Member second){
        int count=0;for(JsonNode group:groups)if("explicit_reference_scope".equals(group.path("kind").asText())&&note.path("id").equals(group.path("guidanceNoteId"))&&group.path("scopeComplete").asBoolean())for(JsonNode target:group.path("targets"))if(target.path("scopeComplete").asBoolean()&&hasId(target.path("orderedMemberIds"),first.id)&&hasId(target.path("orderedMemberIds"),second.id))count++;
        return count==1;
    }

    private static ObjectNode lexicalGroup(JsonNode note,String kind,String identity,ArrayNode groups){ObjectNode group=groups.addObject();group.put("id",kind+":"+digest(note.path("id").asText()+"|"+identity));group.put("kind",kind);group.put("guidanceNoteId",note.path("id").asText());group.put("automaticEditEligible",false);group.put("semanticApplicabilityVerified",false);return group;}
    private static void placeholderScopes(List<Member> members,List<Anchor> clauses,Set<String> noteMembers,ArrayNode notes,ArrayNode groups){
        for(JsonNode note:notes){String instruction=note.path("text").asText();Matcher specify=Pattern.compile("(?is)^\\s*\\(\\s*([*#])\\s*(?:please\\s+)?specify\\s+(?:the\\s+)?[^()]+\\)\\.?\\s*$").matcher(instruction);boolean adjacent=specify.matches();if(!adjacent&&!instruction.matches("(?is).*\\bfill\\s+in\\s+the\\s+(?:blank|period)\\b.*"))continue;int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;Member source=members.get(at);Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,at));if(owner==null||!sameStory(source,owner.member))continue;int end=nextClause(clauses,owner,members.size());boolean rowOnly=instruction.matches("(?is).*\\bfill\\s+in\\s+the\\s+period\\b.*");
            ObjectNode group=lexicalGroup(note,"explicit_placeholder_scope",owner.label,groups);group.put("requiredAction","source_value_required");group.put("scopeMeaning","Only the marked source placeholder runs bound to the fill-in instruction; no value is supplied");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),markers=group.putArray("markerRanges");
            boolean explicitUnit=Pattern.compile("(?i)\\bsub[- ]clauses?\\s*\\(|\\b(?:in|under|of)\\s+(?:the\\s+)?Clause\\s+(?:NTT|SCT|SCC)\\s*\\d").matcher(instruction).find();Set<String> allowed=explicitUnit?instructionTargetMembers(members,clauses,noteMembers,at,instruction):null;
            if(adjacent){int previous=at-1;while(previous>owner.ordinal&&sameNativeSource(source,members.get(previous))&&source.cell.equals(members.get(previous).cell)&&members.get(previous).text.trim().isEmpty())previous--;if(previous<owner.ordinal||!sameNativeSource(source,members.get(previous))||!source.cell.equals(members.get(previous).cell))continue;allowed=Collections.singleton(members.get(previous).id);}
            for(int n=owner.ordinal;n<end;n++){Member member=members.get(n);if(!member.usable||!sameStory(source,member)||noteMembers.contains(member.id)||allowed!=null&&!allowed.contains(member.id)||rowOnly&&(source.row.isEmpty()||!source.row.equals(member.row)))continue;Matcher run=Pattern.compile("(?:(?<before>[*#])\\s*(?<afterBlank>[_.]{3,})|(?<beforeBlank>[_.]{3,})\\s*(?<after>[*#]))").matcher(member.text);while(run.find()){boolean before=run.group("before")!=null;if(adjacent&&!specify.group(1).equals(run.group(before?"before":"after")))continue;int start=run.start(before?"afterBlank":"beforeBlank"),finish=run.end(before?"afterBlank":"beforeBlank");addRange(ranges,ids,member,start,finish);addRange(markers,array(),member,run.start(before?"before":"after"),run.end(before?"before":"after"));}}
            boolean complete=ranges.size()>0&&(!adjacent||ranges.size()==1)&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();group.put("captureComplete",ranges.size()>0);group.put("scopeComplete",complete);
        }
    }
    private static void inlineConditionalScopes(List<Member> members,Set<String> noteMembers,ArrayNode notes,ArrayNode groups){
        for(JsonNode note:notes){String instruction=note.path("text").asText();if(!instruction.matches("(?is)^\\s*\\(\\s*[*#]\\s*amend\\s+if\\b.*"))continue;int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;Member source=members.get(at);if(source.row.isEmpty())continue;for(Member member:members)if(member.usable&&source.row.equals(member.row)&&sameStory(source,member)&&!noteMembers.contains(member.id)){Matcher symbol=Pattern.compile("[*#]").matcher(member.text);if(!symbol.find())continue;int start=symbol.start();if(symbol.find()||member.text.substring(0,start).trim().isEmpty())continue;int before=start-1;while(before>=0&&Character.isWhitespace(member.text.charAt(before)))before--;if(before<0||!sentenceBoundary(member.text,before))continue;int end=start;while(end<member.text.length()&&!sentenceBoundary(member.text,end))end++;if(end<member.text.length())end++;ObjectNode group=lexicalGroup(note,"explicit_inline_sentence_scope",member.id+"|"+start,groups);group.put("scopeMeaning","Only the independently marked source sentence associated with the conditional amendment instruction");addRange(group.putArray("memberRanges"),group.putArray("orderedMemberIds"),member,start,end);group.put("captureComplete",true);group.put("scopeComplete",note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean());}}
    }
    private static void inlineChoiceScopes(List<Member> members,Set<String> noteMembers,ArrayNode notes,ArrayNode groups){
        Pattern pair=Pattern.compile("(?<leftMarker>[*#])\\s*(?<left>[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)*)(?<separator>\\s*/\\s*)(?<rightMarker>[*#])?\\s*(?<right>[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z0-9]+)*)(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])");
        for(JsonNode note:notes){String instruction=note.path("text").asText();if(!instruction.matches("(?is).*\\b(?:use|select\\s+either)\\b.*"))continue;int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;Member source=members.get(at);if(source.row.isEmpty())continue;for(Member member:members)if(member.usable&&source.row.equals(member.row)&&sameStory(source,member)&&!noteMembers.contains(member.id)){Matcher match=pair.matcher(member.text);while(match.find()){boolean mentions=false;for(String name:Arrays.asList("left","right"))if(Pattern.compile("(?i)(?<![A-Za-z0-9.])"+Pattern.quote(match.group(name))+"(?![A-Za-z0-9.])").matcher(instruction).find())mentions=true;if(!mentions)continue;ObjectNode group=lexicalGroup(note,"inline_source_choice_scope",member.id+"|"+match.start(),groups);group.put("requiredAction","project_selection_required");group.put("scopeMeaning","The complete literal slash-choice group observed in the source, associated with a same-row use/select instruction naming at least one option; project selection remains unknown");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),markers=group.putArray("markerRanges"),cleanup=group.putArray("cleanupCandidateRanges");for(String name:Arrays.asList("left","right"))addRange(ranges,ids,member,match.start(name),match.end(name));addRange(markers,array(),member,match.start("leftMarker"),match.end("leftMarker"));if(match.group("rightMarker")!=null)addRange(markers,array(),member,match.start("rightMarker"),match.end("rightMarker"));addRange(cleanup,array(),member,match.start("separator"),match.end("separator"));group.put("cleanupStatus","independent_decision_required");group.put("captureComplete",true);group.put("scopeComplete",note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean());}}}
    }
    private static void explicitListScopes(List<Member> members,List<Anchor> clauses,Set<String> noteMembers,ArrayNode notes,ArrayNode groups){
        for(JsonNode note:notes){String instruction=note.path("text").asText();boolean references=instruction.matches("(?is).*\\bamend\\b.*\\breference\\s+of\\s+SCC\\s+Clauses\\b.*"),specialists=instruction.matches("(?is).*\\bamend\\s+types\\s+of\\s+Specialist\\s+Sub-contract\\s+Works\\b.*");Matcher definition=Pattern.compile("(?i)definition\\s+of\\s+[“‘\"']([^”’\"']+)[”’\"']").matcher(instruction);String defined=definition.find()?definition.group(1):"";if(!references&&!specialists&&defined.isEmpty())continue;int at=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(at<0)continue;
            Set<String> allowed=instructionTargetMembers(members,clauses,noteMembers,at,instruction,true);Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,at));if(owner!=null&&sameStory(members.get(at),owner.member)&&references&&!instruction.matches("(?is).*\\bsub[- ]clauses?\\b.*")){allowed=new LinkedHashSet<>();for(int n=owner.ordinal;n<nextClause(clauses,owner,members.size());n++)if(members.get(n).usable&&sameStory(owner.member,members.get(n))&&!noteMembers.contains(members.get(n).id))allowed.add(members.get(n).id);}
            for(Member member:members)if(allowed.contains(member.id)){
                if(!defined.isEmpty()){Matcher body=Pattern.compile("(?is)[“‘\"']"+literalWhitespace(defined)+"[”’\"']\\s+means\\s+(?<list>[^*#]+?)(?<marker>[*#])").matcher(member.text);while(body.find())listGroup(note,member,body.start("list"),body.end("list"),body.start("marker"),body.end("marker"),"explicit_quoted_definition_list_scope",groups);}
                if(specialists){Matcher body=Pattern.compile("(?is)\\b(?:in\\s+respect\\s+of|for)\\s+(?<list>[^.;*#]+?\\bSpecialist\\s+Sub-contract\\s+Works)(?<marker>[*#])").matcher(member.text);while(body.find())listGroup(note,member,body.start("list"),body.end("list"),body.start("marker"),body.end("marker"),"explicit_specialist_type_list_scope",groups);}
                if(references){Matcher body=Pattern.compile("(?i)(?<list>SCC\\s*\\d+(?:\\.\\d+)+(?:\\([a-z0-9]+\\))*(?:(?:\\s*,\\s*|\\s+(?:and|to)\\s+)(?:Clauses?\\s+)?SCC\\s*\\d+(?:\\.\\d+)+(?:\\([a-z0-9]+\\))*)+)").matcher(member.text);while(body.find()){int markerStart=body.start("list")-1,markerEnd=-1;while(markerStart>=0&&Character.isWhitespace(member.text.charAt(markerStart)))markerStart--;if(markerStart>=0&&"*#".indexOf(member.text.charAt(markerStart))>=0)markerEnd=markerStart+1;else{Matcher suffix=Pattern.compile("(?is)^\\s*(?:of\\s+the\\s+Special\\s+Conditions\\s+of\\s+Contract)?\\s*([*#])").matcher(member.text.substring(body.end("list")));if(suffix.find()){markerStart=body.end("list")+suffix.start(1);markerEnd=body.end("list")+suffix.end(1);}}if(markerEnd>markerStart)listGroup(note,member,body.start("list"),body.end("list"),markerStart,markerEnd,"explicit_adopted_reference_list_scope",groups);}}
            }
        }
    }
    private static void listGroup(JsonNode note,Member member,int start,int end,int markerStart,int markerEnd,String kind,ArrayNode groups){
        int observedStart=start,observedEnd=end;String namespace="";if(kind.equals("explicit_adopted_reference_list_scope")){Matcher named=Pattern.compile("(?i)\\bfor\\s+[“‘\"']((?:NTT|SCT|SCC)\\s*\\d+)\\s+[^”’\"']+[”’\"']").matcher(note.path("text").asText());if(named.find()){namespace=named.group(1).replaceAll("\\s+","");Matcher references=REFERENCE.matcher(member.text.substring(start,end));int first=-1,last=-1;boolean departed=false;while(references.find()){String reference=references.group().replaceAll("\\s+","");if(reference.startsWith(namespace+".")){if(departed)return;if(first<0)first=observedStart+references.start();last=observedStart+references.end();}else if(first>=0)departed=true;}if(first<0)return;start=first;end=last;}}
        ObjectNode group=lexicalGroup(note,kind,member.id+"|"+start+"|"+end,groups);group.put("requiredAction","amend_source_list_from_project_facts");group.put("scopeMeaning","Exact source list bound to the source amendment instruction; surrounding payment, warranty or definition prose remains context");if(!namespace.isEmpty()){group.put("instructionNamedNamespace",namespace);group.put("scopeMeaning","Only the contiguous reference sublist in the namespace explicitly named by the source instruction; other clause references remain context");}if(kind.equals("explicit_adopted_reference_list_scope"))addRange(group.putArray("sourceListObservationRanges"),array(),member,observedStart,observedEnd);addRange(group.putArray("memberRanges"),group.putArray("orderedMemberIds"),member,start,end);addRange(group.putArray("markerRanges"),array(),member,markerStart,markerEnd);ArrayNode context=group.putArray("surroundingContextRanges");if(start>0)addRange(context,array(),member,0,start);if(end<member.text.length())addRange(context,array(),member,end,member.text.length());group.put("captureComplete",true);group.put("scopeComplete",note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean());
    }
    /** Field evidence is emitted after marker decisions; source labels and role notes never approve edits. */
    private static void nativeSourceFields(String profile,List<Member> members,ArrayNode notes,ArrayNode groups){
        List<Anchor> clauses=clauseAnchors(profile,members);Set<String> noteMembers=new HashSet<>();for(JsonNode note:notes)for(JsonNode id:note.path("orderedMemberIds"))noteMembers.add(id.asText());
        String blank="(?:_{3,}|[.\u2026]{3,})";
        String[][] visible={
            {"bill_range_endpoint","\\bBills?\\s+Nos?\\.?\\s+\\d+\\s+to\\s+(?<slot>"+blank+")"},
            {"floor_identifier","(?<slot>[_.\u2026]{3,})\\s+floor\\b"},
            {"block_identifier","\\bBlock\\s+(?<slot>[_.\u2026]{3,})"},
            {"envelope_number","\\bEnvelope\\s+(?<slot>"+blank+")"},
            {"clause_number_suffix","\\bClause\\s+(?:NTT|SCT|SCC)(?<slot>"+blank+")"},
            {"tree_serial_number","\\bTree\\s+No\\.\\s*(?<slot>"+blank+")"}
        };
        String spaces="[ \\t\\u00A0]{3,}";String[][] whitespace={
            {"officer_designation_suffix","\\bofficer\\s+is\\s+A/(?<slot>"+spaces+")(?=\\()"},
            {"person_name","\\bMr\\s*/\\s*Ms(?<slot>"+spaces+")(?=\\))"},
            {"telephone_number","\\b(?:telephone|tel\\.?|phone)\\b(?<slot>"+spaces+")(?=[.,;)]|$)"}
        };
        for(int at=0;at<members.size();at++){Member member=members.get(at);if(!member.usable||!member.body()||noteMembers.contains(member.id)||isTocCopy(member))continue;
            for(String[] spec:visible){Matcher match=Pattern.compile(spec[1],Pattern.CASE_INSENSITIVE).matcher(member.text);while(match.find())if(!attachedMarker(member.text,match.start("slot"),match.end("slot")))sourceField(spec[0],member,at,match.start("slot"),match.end("slot"),"text",false,members,clauses,notes,groups);}
            String raw=preNormalizedText(member);if(raw!=null)for(String[] spec:whitespace){Matcher match=Pattern.compile(spec[1],Pattern.CASE_INSENSITIVE).matcher(raw);while(match.find())sourceField(spec[0],member,at,match.start("slot"),match.end("slot"),"preNormalizedTextObservation.text",false,members,clauses,notes,groups);}
            if(member.text.matches("(?i)^\\s*Bill\\s+No\\.?\\s*\\d+\\s*[\u2013\u2014-]\\s*$")&&fieldNotes("bill_description",member,at,members,clauses,notes).size()==1){int end=raw==null?member.text.length():raw.length();if(raw!=null)while(end>0&&(raw.charAt(end-1)=='\n'||raw.charAt(end-1)=='\r'))end--;sourceField("bill_description",member,at,end,end,raw==null?"text":"preNormalizedTextObservation.text",true,members,clauses,notes,groups);}
            Matcher choice=Pattern.compile("(?i)(?<marker>[*#])(?<first>Mr)(?<separator>\\s*/\\s*)(?<second>Ms)(?![\\p{L}\\d])").matcher(member.text);while(choice.find()){ObjectNode group=sourceField("person_title_choice",member,at,choice.start("first"),choice.end("second"),"text",false,members,clauses,notes,groups);group.put("sourceChoiceComplete",true);group.put("sourceFieldRangeComplete",true);group.put("requiredAction","project_selection_required");group.put("markerDispositionMeaning","Literal title choice capture supplies no deletion or selection authority; existing marker disposition is retained");ArrayNode options=group.putArray("optionRanges");addRange(options,array(),member,choice.start("first"),choice.end("first"));addRange(options,array(),member,choice.start("second"),choice.end("second"));addRange(group.putArray("markerRanges"),array(),member,choice.start("marker"),choice.end("marker"));addRange(group.putArray("separatorContextRanges"),array(),member,choice.start("separator"),choice.end("separator"));}
        }
    }
    private static ObjectNode sourceField(String label,Member member,int at,int start,int end,String channel,boolean insertion,List<Member> members,List<Anchor> clauses,ArrayNode notes,ArrayNode groups){
        ObjectNode group=groups.addObject();group.put("id","source-field:"+digest(member.id+"|"+label+"|"+channel+"|"+start+"|"+end));group.put("kind","native_source_field_candidate");group.put("fieldLabel",label);group.put("captureComplete",true);group.put("sourceFieldRangeComplete",!insertion);group.put("scopeComplete",false);group.put("scopeStatus","source_field_candidate_no_automatic_edit");group.put("scopeMeaning",insertion?"Observed paragraph insertion anchor only; future text extent and Bill list cardinality remain unknown":"Only the explicitly observed local field run or literal choice; value, project applicability and edit authority remain unknown");group.put("automaticEditEligible",false);group.put("semanticApplicabilityVerified",false);group.put("fieldValueStatus","unknown_requires_project_source");group.put("requiredAction",insertion?"project_description_and_list_structure_required":"project_source_value_required");
        ArrayNode ranges=group.putArray(insertion?"insertionAnchors":"memberRanges"),ids=group.putArray("orderedMemberIds");addRange(ranges,ids,member,start,end);ObjectNode range=(ObjectNode)ranges.get(0);range.put("channel",channel);range.put("coordinateMeaning",channel.equals("text")?"UTF16 in normalized parser text observation; not an original-package editing coordinate":"UTF16 in the named pre-normalized parser text observation, including parser label/control projection; not raw XML or an original-package editing coordinate");String observed=channel.equals("text")?member.text:preNormalizedText(member);range.put("observedText",observed.substring(start,end));if(insertion){group.put("listCardinalityStatus","unknown_from_existing_numbered_source_rows");group.put("sourceInsertionAnchorComplete",true);}
        ArrayNode noteIds=group.putArray("guidanceNoteIds"),relationships=group.putArray("sourceInstructionRelationships");List<JsonNode> related=fieldNotes(label,member,at,members,clauses,notes);for(JsonNode note:related){String noteId=note.path("id").asText();noteIds.add(noteId);ObjectNode relationship=relationships.addObject();relationship.put("guidanceNoteId",noteId);relationship.put("basis",!member.row.isEmpty()&&sameRowNote(member,note,members)?"same_native_row_and_literal_field_or_role_context":"same_native_clause_and_explicit_field_instruction");relationship.set("instructionMemberRanges",note.path("memberRanges").deepCopy());relationship.put("projectValueSupplied",false);if(label.equals("tree_serial_number")&&note.path("text").asText().matches("(?is).*\\bsingle\\s+Old\\s+and\\s+Valuable\\s+Tree\\b.*")){group.put("sameValueSourceGroupId","source-field-value:"+digest(noteId+"|tree_serial_number"));group.put("sameValueMeaning","Source guidance limits the clause to a single tree and asks for its serial number in the named subclauses; no identifier is inferred");}}
        group.put("instructionAssociationStatus",related.size()==1?"one_source_context_relationship":related.isEmpty()?"no_dedicated_source_instruction_observed":"multiple_source_context_relationships_require_review");return group;
    }
    private static List<JsonNode> fieldNotes(String label,Member member,int at,List<Member> members,List<Anchor> clauses,ArrayNode notes){
        List<JsonNode> result=new ArrayList<>();Anchor owner=enclosing(clauses,at);for(JsonNode note:notes){if(!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean())continue;int noteAt=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(noteAt<0||!sameStory(member,members.get(noteAt)))continue;String text=note.path("text").asText();boolean row=sameRowNote(member,note,members),sameClause=owner!=null&&sameStory(owner.member,member)&&owner.label.equals(label(enclosing(clauses,noteAt)));
            boolean match=label.equals("bill_description")&&row&&text.matches("(?is).*\\binsert\\s+all\\s+Bill\\s+Nos?\\.?\\s+and\\s+descriptions\\b.*")
                ||(label.equals("floor_identifier")||label.equals("block_identifier"))&&row&&text.matches("(?is)^\\s*\\(\\s*Address\\b.*")
                ||(label.equals("officer_designation_suffix")||label.equals("person_name")||label.equals("telephone_number")||label.equals("person_title_choice"))&&row&&text.matches("(?is)^\\s*\\(\\s*Project\\s+\\p{L}[\\p{L}\\s]{0,70}\\)\\.?\\s*$")
                ||label.equals("envelope_number")&&sameClause&&text.matches("(?is).*\\binsert\\b.*\\bEnvelope\\s+number\\b.*")&&uniqueInstructionField(label,member,owner,text,members,clauses,notes)
                ||label.equals("clause_number_suffix")&&sameClause&&text.matches("(?is).*\\binsert\\b.*\\b(?:NTT|SCT|SCC)\\s+clause\\s+number\\b.*")&&uniqueInstructionField(label,member,owner,text,members,clauses,notes)
                ||label.equals("tree_serial_number")&&sameClause&&text.matches("(?is).*\\bserial\\s+number\\b.*\\bTree\\b.*\\binsert\\b.*")&&treeInstructionUnitContains(member,at,owner,text,members,clauses,notes);
            if(match)result.add(note);
        }return result;
    }
    private static boolean treeInstructionUnitContains(Member member,int at,Anchor owner,String instruction,List<Member> members,List<Anchor> clauses,ArrayNode notes){
        Matcher directive=Pattern.compile("(?is)\\bserial\\s+number\\b[^\\r\\n]*?\\binsert\\b(?<targets>.*?)(?=\\.(?:\\s|\\]|$)|$)").matcher(instruction);if(!directive.find())return false;Set<String> requested=new LinkedHashSet<>();Matcher references=REFERENCE.matcher(directive.group("targets"));while(references.find()){String reference=references.group().replaceAll("\\s+","");if(reference.startsWith(owner.label+"("))requested.add(reference);}if(requested.isEmpty())return false;
        Set<String> noteMembers=noteMemberIds(notes);Anchor start=firstClauseInSequence(clauses,owner);List<Unit> units=units(start,nextClause(clauses,start,members.size()),members,noteMembers,array());for(String reference:requested){List<Unit> exact=new ArrayList<>();for(Unit unit:units)if(unit.reference.equals(reference))exact.add(unit);if(exact.size()==1){Unit unit=exact.get(0);if(at>=unit.start&&at<unit.end&&sameStory(start.member,member))return true;}}return false;
    }
    private static boolean uniqueInstructionField(String label,Member target,Anchor owner,String instruction,List<Member> members,List<Anchor> clauses,ArrayNode notes){
        if(!definitionNamedByInstruction(target,instruction))return false;String pattern=label.equals("envelope_number")?"\\bEnvelope\\s+(?:_{3,}|[.\u2026]{3,})":"\\bClause\\s+(?:NTT|SCT|SCC)(?:_{3,}|[.\u2026]{3,})";Set<String> noteMembers=noteMemberIds(notes);int matches=0;Anchor start=firstClauseInSequence(clauses,owner);for(int at=start.ordinal;at<nextClause(clauses,start,members.size());at++){Member member=members.get(at);if(!member.usable||!sameStory(target,member)||noteMembers.contains(member.id)||!definitionNamedByInstruction(member,instruction))continue;Matcher field=Pattern.compile(pattern,Pattern.CASE_INSENSITIVE).matcher(member.text);while(field.find())matches++;}return matches==1;
    }
    private static boolean definitionNamedByInstruction(Member member,String instruction){String text=member.text;Matcher subitem=SUBITEM.matcher(text);if(subitem.find())text=text.substring(subitem.end());text=text.replaceFirst("^\\s*[*#]+\\s*","");Matcher definition=Pattern.compile("^\\s*[“‘\"']([^”’\"']+)[”’\"']\\s+means\\b",Pattern.CASE_INSENSITIVE).matcher(text);if(definition.find())return Pattern.compile("(?i)(?<![\\p{L}\\d])"+literalWhitespace(definition.group(1))+"(?![\\p{L}\\d])").matcher(instruction).find();return !Pattern.compile("(?i)\\bmeans\\b").matcher(text).find();}
    private static Set<String> noteMemberIds(ArrayNode notes){Set<String> ids=new HashSet<>();for(JsonNode note:notes)for(JsonNode id:note.path("orderedMemberIds"))ids.add(id.asText());return ids;}
    private static boolean sameRowNote(Member member,JsonNode note,List<Member> members){if(member.row.isEmpty())return false;for(JsonNode id:note.path("orderedMemberIds")){int at=indexOf(members,id.asText());if(at<0||!member.row.equals(members.get(at).row)||!sameStory(member,members.get(at)))return false;}return note.path("orderedMemberIds").size()>0;}
    private static boolean attachedMarker(String text,int start,int end){int left=start-1,right=end;while(left>=0&&Character.isWhitespace(text.charAt(left)))left--;while(right<text.length()&&Character.isWhitespace(text.charAt(right)))right++;return left>=0&&"*#".indexOf(text.charAt(left))>=0||right<text.length()&&"*#".indexOf(text.charAt(right))>=0;}
    private static String preNormalizedText(Member member){JsonNode raw=member.value.path("preNormalizedTextObservation");if(!raw.path("status").asText().equals("observed_parser_channels_before_whitespace_normalization")||!raw.path("text").isTextual())return null;String text=raw.path("text").asText();return text.replaceAll("[\\s\\u00A0]+","").equals(member.text.replaceAll("[\\s\\u00A0]+",""))?text:null;}
    private static boolean isTocCopy(Member member){
        if(member.value.has("nativeFieldRoles")){
            StringBuilder observed=new StringBuilder();boolean seen=false;
            for(JsonNode role:member.value.path("nativeFieldRoles")){
                if(role.path("deleted").asBoolean())continue;
                if(!"toc_result".equals(role.path("fieldRole").asText())||!role.path("sourceText").isTextual()||!tocPosition(member,role.path("sourcePosition"))||!descendant(role.path("sourcePosition").path("nodePath").asText(),member.path)||!tocPosition(member,role.path("fieldSourcePosition")))return false;
                observed.append(role.path("sourceText").asText());seen=true;
            }
            return seen&&!member.text.trim().isEmpty()&&observed.toString().replaceAll("[\\s\\p{Z}]+","").equals(member.text.replaceAll("[\\s\\p{Z}]+",""));
        }
        String xml=member.value.path("nativeXml").asText();Matcher link=Pattern.compile("(?is)<(?:[\\w.-]+:)?hyperlink\\b[^>]*\\b(?:[\\w.-]+:)?anchor\\s*=\\s*[\"'](_Toc[^\"']+)[\"']").matcher(xml);while(link.find())if(Pattern.compile("(?i)\\bPAGEREF\\s+"+Pattern.quote(link.group(1))+"(?=\\s|<)").matcher(xml).find())return true;return false;}
    private static boolean tocPosition(Member member,JsonNode position){JsonNode own=member.value.path("sourcePosition");return !position.path("nodePath").asText().isEmpty()&&own.path("sourceSha256").equals(position.path("sourceSha256"))&&own.path("partName").equals(position.path("partName"))&&own.path("partSha256").equals(position.path("partSha256"));}

    private static GlyphMap nativePdfLayout(Member member){
        if(member.pdfLayoutChecked)return member.pdfLayout;member.pdfLayoutChecked=true;member.pdfLayout=nativePdfRangeLayout(member,0,member.text.length());return member.pdfLayout;
    }
    private static GlyphMap nativePdfRangeLayout(Member member,int start,int end){
        if(start<0||end<=start||end>member.text.length())return null;
        JsonNode block=member.value.path("blockObservation");int page=block.path("pageNo").asInt(-1);
        if(!member.usable||member.expectedPdfSource==null||!member.expectedPdfSource.equals(block.path("readingContext").path("sourceSha256").asText())||!Arrays.asList("native","native_pdf").contains(block.path("source").asText())||!"pdf_line".equals(block.path("kind").asText())||page<1)return null;
        GlyphMap original=glyphMap(member),map=new GlyphMap();if(!original.complete)return null;for(GlyphSpan glyph:original.glyphs)if(glyph.start<end&&glyph.end>start)map.glyphs.add(glyph);map.complete=true;double[] line=pdfNumbers(block.path("bbox"),4);if(map.glyphs.isEmpty()||line==null||line[0]<0||line[1]<0||line[2]<=0||line[3]<=0||line[0]+line[2]>1.0001||line[1]+line[3]>1.0001)return null;Set<String> ids=new HashSet<>();Map<String,JsonNode> observed=new HashMap<>();for(JsonNode glyph:block.path("pdfGlyphs"))if(observed.putIfAbsent(glyph.path("id").asText(),glyph)!=null)return null;double baseline=Double.NaN;
        for(GlyphSpan glyph:map.glyphs){if(!glyph.id.startsWith("pdf-page/"+page+"/glyph/")||!ids.add(glyph.id)||glyph.x<0||glyph.y<0||glyph.width<=0||glyph.x+glyph.width>1.0001||glyph.y+glyph.height>1.0001)return null;JsonNode raw=observed.get(glyph.id);if(raw==null||!raw.path("direction").isNumber()||Math.abs(raw.path("direction").asDouble())>.01)return null;double[] position=pdfNumbers(raw.path("pdfboxPosition"),10),matrix=pdfNumbers(raw.path("textMatrix"),6);if(position==null||matrix==null||position[8]<=0||position[9]<=0||position[6]<=0||position[7]<=0||matrix[0]<=0||matrix[3]<=0||Math.abs(matrix[1])>.001||Math.abs(matrix[2])>.001)return null;
            if(Double.isNaN(baseline)){baseline=position[5];map.pageWidth=position[8];map.pageHeight=position[9];}else if(Math.abs(position[5]-baseline)>.02||Math.abs(position[8]-map.pageWidth)>.02||Math.abs(position[9]-map.pageHeight)>.02)return null;double w=map.pageWidth,h=map.pageHeight;
            if(Math.abs(glyph.x*w-position[4])>.02||Math.abs((glyph.y+glyph.height)*h-position[5])>.02||Math.abs(glyph.width*w-position[6])>.02||Math.abs(glyph.height*h-position[7])>.02||Math.abs(matrix[4]-position[4])>.02||Math.abs(matrix[5]-(h-position[5]))>.02)return null;
            for(int n=0;n<4;n++)if(Math.abs(position[n]-position[n+4])>.02)return null;
            if((line[0]-glyph.x)*w>.02||(line[1]-glyph.y)*h>.02||(glyph.x+glyph.width-line[0]-line[2])*w>.02||(glyph.y+glyph.height-line[1]-line[3])*h>.02)return null;
        }
        return map;
    }
    private static double[] pdfNumbers(JsonNode values,int size){if(!values.isArray()||values.size()!=size)return null;double[] out=new double[size];for(int n=0;n<size;n++){if(!values.path(n).isNumber()||!Double.isFinite(values.path(n).asDouble()))return null;out[n]=values.path(n).asDouble();}return out;}
    private static boolean samePdfFrame(GlyphMap a,GlyphMap b){return Math.abs(a.pageWidth-b.pageWidth)<=.02&&Math.abs(a.pageHeight-b.pageHeight)<=.02;}
    private static boolean pdfReferenceFrame(Member member,Map<Integer,GlyphMap> frames){GlyphMap map=nativePdfLayout(member);if(map==null)return false;GlyphMap prior=frames.putIfAbsent(pdfPage(member),map);return prior==null||samePdfFrame(prior,map);}
    private static boolean pdfBoundaryFrame(String profile,Member member,List<Member> members,Map<Integer,GlyphMap> frames){if(pdfReferenceFrame(member,frames))return true;int start=pdfHeadingStart(profile,member,members);GlyphMap map=start>0?nativePdfRangeLayout(member,start,member.text.length()):null;if(map==null)return false;GlyphMap prior=frames.putIfAbsent(pdfPage(member),map);return prior==null||samePdfFrame(prior,map);}
    private static boolean samePdfPrintLane(GlyphMap a,GlyphMap b){if(!samePdfFrame(a,b))return false;GlyphSpan first=a.glyphs.get(0),second=b.glyphs.get(0);if(Math.abs(first.x-second.x)*a.pageWidth>.02||Math.abs(first.y-second.y)*a.pageHeight>.02||Math.abs(first.font-second.font)>.2)return false;for(GlyphSpan glyph:a.glyphs)if(Math.abs(glyph.font-first.font)>.2)return false;for(GlyphSpan glyph:b.glyphs)if(Math.abs(glyph.font-second.font)>.2)return false;return true;}
    private static int pdfPage(Member member){return member.value.path("blockObservation").path("pageNo").asInt(-1);}
    private static double pdfBodyLeft(Member member,GlyphMap map){Matcher marker=SUBITEM.matcher(member.text);if(!marker.find())return Double.NaN;int at=marker.end();while(at<member.text.length()&&(Character.isWhitespace(member.text.charAt(at))||"#*".indexOf(member.text.charAt(at))>=0))at++;for(GlyphSpan glyph:map.glyphs)if(glyph.start>=at)return glyph.x;return Double.NaN;}
    private static boolean pdfContiguousInterval(List<Member> members,int start,int end){
        if(start<0||end<start||end>=members.size())return false;int previous=pdfPage(members.get(start));if(previous<1)return false;
        for(int at=start+1;at<=end;at++){int page=pdfPage(members.get(at));if(page<previous||page>previous+1)return false;previous=page;}return true;
    }
    private static boolean pdfChildContinuation(Unit unit,List<Member> members,Set<String> noteMembers){
        if(unit.end>=members.size())return false;Member start=members.get(unit.start),boundary=members.get(unit.end);GlyphMap first=nativePdfLayout(start),last=nativePdfLayout(boundary);
        if(first==null||last==null||!samePdfFrame(first,last)||!SUBITEM.matcher(boundary.text).find()&&!CLAUSE.matcher(boundary.text).find())return false;
        double bodyLeft=pdfBodyLeft(start,first),tolerance=first.glyphs.get(0).height*.65;if(!Double.isFinite(bodyLeft))return false;int previousPage=pdfPage(start);
        for(int at=unit.start+1;at<unit.end;at++){
            Member member=members.get(at);GlyphMap map=nativePdfLayout(member);int page=pdfPage(member);if(map==null||!samePdfFrame(first,map)||page<previousPage||page>previousPage+1)return false;previousPage=page;
            if(noteMembers.contains(member.id)||member.text.trim().isEmpty()||pdfPageFurniture(member,members))continue;
            if(SUBITEM.matcher(member.text).find()||Math.abs(map.glyphs.get(0).x-bodyLeft)>tolerance)return false;
        }
        return pdfPage(boundary)>=previousPage&&pdfPage(boundary)<=previousPage+1;
    }
    private static int pdfSharedTail(Unit last,List<Unit> siblings,List<Unit> units,List<Member> members,Set<String> noteMembers){
        if(last.end>=members.size())return -1;Member boundary=members.get(last.end);if(nativePdfLayout(boundary)==null||!SUBITEM.matcher(boundary.text).find()&&!CLAUSE.matcher(boundary.text).find())return -1;
        String parentReference=last.reference.replaceFirst("\\([^()]*\\)$","");Unit parent=null;for(Unit unit:units)if(unit.reference.equals(parentReference)&&unit.depth==last.depth-1){if(parent!=null)return -1;parent=unit;}if(parent==null)return -1;for(Unit sibling:siblings)if(sibling.start<=parent.start||sibling.start>=parent.end)return -1;
        Member owner=members.get(parent.start);GlyphMap parentMap=nativePdfLayout(owner);if(parentMap==null||!owner.text.trim().matches("(?s).*(?::|:-)$"))return -1;int page=pdfPage(owner);if(pdfPage(boundary)!=page||!samePdfFrame(parentMap,nativePdfLayout(boundary)))return -1;
        double parentLeft=pdfBodyLeft(owner,parentMap),tolerance=parentMap.glyphs.get(0).height*.65,childLeft=Double.NaN;if(!Double.isFinite(parentLeft))return -1;
        for(Unit sibling:siblings){Member member=members.get(sibling.start);GlyphMap map=nativePdfLayout(member);if(map==null||pdfPage(member)!=page||!samePdfFrame(parentMap,map))return -1;double left=pdfBodyLeft(member,map);if(!Double.isFinite(left)||left<=parentLeft+4*tolerance||Double.isFinite(childLeft)&&Math.abs(left-childLeft)>tolerance)return -1;childLeft=left;}
        int tail=-1;for(int cursor=last.start+1;cursor<last.end;cursor++){Member member=members.get(cursor);if(noteMembers.contains(member.id)||member.text.trim().isEmpty()||pdfPageFurniture(member,members))continue;GlyphMap map=nativePdfLayout(member);if(map==null||pdfPage(member)!=page||!samePdfFrame(parentMap,map))return -1;double left=map.glyphs.get(0).x;
            if(tail<0&&Math.abs(left-childLeft)<=tolerance)continue;
            if(tail<0){if(!member.text.trim().matches("(?is)^(?:shall\\b|which\\s+(?:is|are)\\b).*")||Math.abs(left-parentLeft)>tolerance)return -1;tail=cursor;}
            else if(Math.abs(left-parentLeft)>tolerance)return -1;
        }
        if(tail<0)return -1;
        for(Unit sibling:siblings){String end="";int limit=sibling==last?tail:sibling.end;for(int cursor=sibling.start;cursor<limit;cursor++){Member member=members.get(cursor);if(noteMembers.contains(member.id)||member.text.trim().isEmpty()||pdfPageFurniture(member,members))continue;GlyphMap map=nativePdfLayout(member);if(map==null||pdfPage(member)!=page||!samePdfFrame(parentMap,map))return -1;end=member.text.trim();}if(end.isEmpty()||end.matches("(?s).*[.!?][”’\\\"')\\]]*$"))return -1;}
        return tail;
    }
    private static boolean pdfPageFurniture(Member member,List<Member> members){
        GlyphMap map=nativePdfLayout(member);if(map==null)return false;String text=member.text.replaceAll("\\s+"," ").trim();int page=pdfPage(member);double y=map.glyphs.get(0).y,height=map.glyphs.get(0).height;
        boolean publication=text.matches("(?i)^(?:[A-Z][A-Z0-9&()./-]*\\s+)?Standard Library of .+\\bEdition\\s*$")||text.matches("(?i)^\\(\\s*Consolidated\\s+up\\s+to\\s+\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4}\\s*\\)$");
        if(y<.1&&publication&&!CLAUSE.matcher(text).find()&&!SUBITEM.matcher(text).find()){
            double firstMarker=Double.POSITIVE_INFINITY;for(Member peer:members)if(pdfPage(peer)==page&&(CLAUSE.matcher(peer.text).find()||SUBITEM.matcher(peer.text).find())){GlyphMap observed=nativePdfLayout(peer);if(observed!=null)firstMarker=Math.min(firstMarker,observed.glyphs.get(0).y);}
            if(y+3*height<firstMarker)for(Member peer:members)if(pdfPage(peer)!=page&&peer.text.replaceAll("\\s+"," ").trim().equals(text)){GlyphMap observed=nativePdfLayout(peer);if(observed!=null&&observed.glyphs.get(0).y<.1&&samePdfPrintLane(map,observed))return true;}
        }
        if(y>.9&&text.matches("\\d{1,6}")){
            double prior=Double.NEGATIVE_INFINITY;for(Member peer:members)if(peer!=member&&pdfPage(peer)==page){GlyphMap observed=nativePdfLayout(peer);if(observed!=null){double other=observed.glyphs.get(0).y;if(other>y)return false;prior=Math.max(prior,other);}}
            if(y-prior>4*height)for(Member peer:members)if(Math.abs(pdfPage(peer)-page)==1&&peer.text.trim().matches("\\d{1,6}")){GlyphMap observed=nativePdfLayout(peer);if(observed!=null&&observed.glyphs.get(0).y>.9&&samePdfPrintLane(map,observed)&&Integer.parseInt(peer.text.trim())-Integer.parseInt(text)==pdfPage(peer)-page)return true;}
        }
        return false;
    }
    private static boolean pdfLastSentence(List<Member> members,int noteAt,JsonNode note,ArrayNode groups){
        if(!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean())return false;Member instruction=members.get(noteAt);if(nativePdfLayout(instruction)==null)return false;
        List<Member> lines=new ArrayList<>();Member next=instruction;
        for(int cursor=noteAt-1;cursor>=0;cursor--){Member member=members.get(cursor);GlyphMap map=nativePdfLayout(member),nextMap=nativePdfLayout(next);if(map==null||nextMap==null||pdfPage(member)!=pdfPage(instruction)||!samePdfFrame(map,nextMap))return false;
            double height=Math.max(map.glyphs.get(0).height,nextMap.glyphs.get(0).height),gap=nextMap.glyphs.get(0).y-map.glyphs.get(0).y;if(gap<=0||gap>(lines.isEmpty()?8:4)*height||Math.abs(map.glyphs.get(0).x-nextMap.glyphs.get(0).x)>height)return false;
            lines.add(0,member);String text=String.join("\n",lines.stream().map(line->line.text).toArray(String[]::new));int end=text.length();while(end>0&&Character.isWhitespace(text.charAt(end-1)))end--;int start=end;if(start>0&&pdfSentenceBoundary(text,start-1))start--;while(start>0&&!pdfSentenceBoundary(text,start-1))start--;while(start<end&&Character.isWhitespace(text.charAt(start)))start++;
            if(start<end&&text.charAt(start)=='*'){
                ObjectNode group=object();group.put("id","last-sentence:"+digest(note.path("id").asText()+"|"+member.id));group.put("kind","inline_last_sentence_scope");group.put("guidanceNoteId",note.path("id").asText());group.put("captureComplete",true);group.put("scopeComplete",true);group.put("scopeMeaning","Only the marked final sentence in complete same-source native PDF line observations");group.put("automaticEditEligible",false);group.put("wholeParagraphTargetAssumed",false);ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds");int offset=0;
                for(Member line:lines){int from=Math.max(0,start-offset),limit=Math.min(line.text.length(),end-offset);if(from<limit)addPdfRange(ranges,ids,line,from,limit,nativePdfLayout(line));offset+=line.text.length()+1;}
                if(ranges.isEmpty())return false;group.put("text",text.substring(start,end));JsonNode first=ranges.get(0),last=ranges.get(ranges.size()-1);group.putObject("startBoundary").put("kind","marked_sentence_after_previous_sentence_boundary").put("memberId",first.path("memberId").asText()).put("offset",first.path("start").asInt());group.putObject("endBoundary").put("kind","observed_source_sentence_end").put("memberId",last.path("memberId").asText()).put("offset",last.path("end").asInt());groups.add(group);return true;
            }
            if(start>0||CLAUSE.matcher(member.text).find()||SUBITEM.matcher(member.text).find())return false;next=member;
        }
        return false;
    }

    private static boolean pdfSentenceBoundary(String text,int at){return text.charAt(at)!='\n'&&sentenceBoundary(text,at);}

    /** Projects only observed source slices; glyph text must align before geometry can exclude a margin. */
    private static void projectPdfGuidance(ObjectNode note,List<Member> members,ArrayNode issues){
        ArrayNode ranges=(ArrayNode)note.path("memberRanges"),ids=(ArrayNode)note.path("orderedMemberIds"),raw=ranges.deepCopy();note.set("rawMemberRanges",raw);note.put("rawText",note.path("text").asText());ObjectNode projection=note.putObject("pdfLaneProjection");projection.put("offsetMeaning","utf16_code_units_in_original_normalized_text_channel");projection.put("sourceMutationPerformed",false);projection.put("frameStatus","not_persisted_in_bound_inputs");projection.put("geometryScope","same_physical_page_and_observed_glyph_coordinate_space_only_no_rotation_or_cross_page_transform_inferred");ArrayNode context=note.putArray("revisionMarginContextRanges");
        boolean nativeMode=true,anyGlyph=false;for(JsonNode range:raw){Member member=members.get(indexOf(members,range.path("memberId").asText()));JsonNode block=member.value.path("blockObservation");nativeMode&=Arrays.asList("native","native_pdf").contains(block.path("source").asText());anyGlyph|=block.path("pdfGlyphs").isArray()&&!block.path("pdfGlyphs").isEmpty();}
        if(!nativeMode||!anyGlyph){projection.put("status",nativeMode?"native_glyph_unavailable_raw_syntax_capture_preserved":"not_applicable_ocr_or_region_observation_raw_syntax_capture_preserved");projection.put("glyphMappingStatus","not_attempted_without_uniform_native_glyph_mode");projection.put("marginLaneStatus","not_assessed");return;}
        Map<String,GlyphMap> maps=new HashMap<>();Set<String> rawIds=new HashSet<>();int page=-1;boolean unknown=false;double anchorX=Double.NaN,anchorFont=Double.NaN;
        for(JsonNode range:raw){Member member=members.get(indexOf(members,range.path("memberId").asText()));rawIds.add(member.id);GlyphMap map=glyphMap(member);maps.put(member.id,map);int observedPage=member.value.path("blockObservation").path("pageNo").asInt(-1);if(page<0)page=observedPage;else if(page!=observedPage)unknown=true;if(!map.complete)unknown=true;if(Double.isNaN(anchorX))for(GlyphSpan glyph:map.glyphs)if(glyph.end>range.path("start").asInt()&&glyph.start<range.path("end").asInt()){anchorX=glyph.x;anchorFont=glyph.font;break;}}
        if(page<1||!Double.isFinite(anchorX)||!Double.isFinite(anchorFont)||anchorFont<=0)unknown=true;
        List<MarginRun> candidates=new ArrayList<>();if(!unknown)for(Member member:members)if(member.usable&&member.value.path("blockObservation").path("pageNo").asInt(-1)==page){GlyphMap map=maps.computeIfAbsent(member.id,id->glyphMap(member));if(map.complete)candidates.addAll(marginRuns(member,map,anchorX,anchorFont));}
        List<MarginRun> selected=new ArrayList<>();for(MarginRun approved:candidates)if(approved.role.equals("approval")){
            MarginRun seed=null,date=null;for(MarginRun candidate:candidates)if(sameMargin(approved,candidate)){double delta=candidate.y-approved.y;if(candidate.role.equals("seed")&&delta<=approved.height&&-delta<=8*Math.max(approved.height,candidate.height)&&(seed==null||candidate.y>seed.y))seed=candidate;if(candidate.role.equals("date")&&delta>=-approved.height&&delta<=8*Math.max(approved.height,candidate.height)&&(date==null||candidate.y<date.y))date=candidate;}
            if(seed!=null&&date!=null&&completeMarginCluster(seed,approved,date)&&(rawIds.contains(seed.member.id)||rawIds.contains(approved.member.id)||rawIds.contains(date.member.id))){if(!selected.contains(seed))selected.add(seed);if(!selected.contains(approved))selected.add(approved);if(!selected.contains(date))selected.add(date);}
        }
        for(MarginRun candidate:candidates)if(rawIds.contains(candidate.member.id)&&!selected.contains(candidate))unknown=true;
        if(unknown){projection.put("status","lane_unknown_raw_source_retained");projection.put("glyphMappingStatus","missing_mismatched_or_cross_page_evidence");note.put("captureComplete",false);issue(issues,"PDF_GUIDANCE_LANE_UNKNOWN",note.path("id").asText(),"Original text is retained because glyph-to-text alignment, page continuity or a complete geometric metadata cluster is unverified");return;}
        for(MarginRun run:selected){addPdfRange(context,array(),run.member,run.start,run.end,maps.get(run.member.id));ObjectNode record=(ObjectNode)context.get(context.size()-1);record.put("contextRole",run.role);record.put("exclusionBasis","complete_contiguous_smaller_font_glyph_run_left_of_source_cluster_body_lane_and_revision_approval_date_cluster");record.put("completeRunRight",run.right);record.put("allGlyphsLocallyContinuous",run.continuous);if(Double.isFinite(run.bodyLaneLeft))record.put("sameBlockBodyLaneLeft",run.bodyLaneLeft);}
        ranges.removeAll();ids.removeAll();StringBuilder projected=new StringBuilder();for(JsonNode original:raw){String memberId=original.path("memberId").asText();Member member=members.get(indexOf(members,memberId));int from=original.path("start").asInt(),end=original.path("end").asInt();List<MarginRun> remove=new ArrayList<>();for(MarginRun run:selected)if(run.member.id.equals(memberId)&&run.start<end&&run.end>from)remove.add(run);remove.sort(Comparator.comparingInt(run->run.start));int cursor=from;
            for(MarginRun run:remove){appendPdfBody(ranges,ids,projected,member,cursor,Math.max(cursor,Math.min(end,run.start)),maps.get(member.id));cursor=Math.max(cursor,Math.min(end,run.end));}appendPdfBody(ranges,ids,projected,member,cursor,end,maps.get(member.id));}
        note.put("text",projected.toString());projection.put("status",selected.isEmpty()?"exact_glyph_projection_no_margin_cluster":"exact_glyph_projection_with_revision_margin_context");projection.put("glyphMappingStatus","exact_nonwhitespace_unicode_sequence_to_utf16_text_offsets");projection.put("crossPageOwnershipVerified",false);projection.put("pageNo",page);projection.put("marginContextRangeCount",context.size());if(persistPdfFrames(projection,ranges,members))projection.put("frameStatus","source_bound_observed_glyph_frames");
    }
    private static void appendPdfBody(ArrayNode ranges,ArrayNode ids,StringBuilder text,Member member,int start,int end,GlyphMap map){if(start>=end||member.text.substring(start,end).trim().isEmpty())return;addPdfRange(ranges,ids,member,start,end,map);if(text.length()>0)text.append('\n');text.append(member.text,start,end);}
    private static void addPdfRange(ArrayNode ranges,ArrayNode ids,Member member,int start,int end,GlyphMap map){addRange(ranges,ids,member,start,end);ObjectNode range=(ObjectNode)ranges.get(ranges.size()-1),provenance=range.putObject("pdfGlyphProvenance");provenance.put("kind","exact_observed_glyph_text_projection");provenance.put("blockId",member.id);provenance.put("pageNo",member.value.path("blockObservation").path("pageNo").asInt());provenance.put("coordinateSpace","normalized_rendered_crop_top_left");ArrayNode glyphIds=provenance.putArray("glyphIds");if(map!=null)for(GlyphSpan glyph:map.glyphs)if(glyph.start<end&&glyph.end>start)appendUnique(glyphIds,glyph.id);}
    private static GlyphMap glyphMap(Member member){
        JsonNode observed=member.value.path("blockObservation").path("pdfGlyphs");GlyphMap map=new GlyphMap();if(!observed.isArray()||observed.isEmpty())return map;StringBuilder nonwhite=new StringBuilder();List<Integer> positions=new ArrayList<>();for(int n=0;n<member.text.length();n++)if(!Character.isWhitespace(member.text.charAt(n))){nonwhite.append(member.text.charAt(n));positions.add(n);}int consumed=0;
        for(JsonNode glyph:observed){String unicode=glyph.path("unicode").asText(),id=glyph.path("id").asText();StringBuilder visible=new StringBuilder();for(int n=0;n<unicode.length();n++)if(!Character.isWhitespace(unicode.charAt(n)))visible.append(unicode.charAt(n));if(visible.length()==0)continue;JsonNode box=glyph.path("bbox");if(!glyph.path("eligibleForNativeText").asBoolean()||!glyph.path("coordinateSpace").asText().equals("normalized_rendered_crop_top_left")||id.isEmpty()||box.size()!=4||consumed+visible.length()>nonwhite.length()||!nonwhite.substring(consumed,consumed+visible.length()).equals(visible.toString()))return map;double x=box.path(0).asDouble(Double.NaN),y=box.path(1).asDouble(Double.NaN),width=box.path(2).asDouble(Double.NaN),height=box.path(3).asDouble(Double.NaN),font=glyph.path("fontSizeInPt").asDouble(Double.NaN);if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(width)||!Double.isFinite(height)||!Double.isFinite(font)||width<0||height<=0||font<=0)return map;int start=positions.get(consumed),end=positions.get(consumed+visible.length()-1)+1;map.glyphs.add(new GlyphSpan(id,start,end,x,y,width,height,font));consumed+=visible.length();}
        map.complete=consumed==nonwhite.length();return map;
    }
    private static List<MarginRun> marginRuns(Member member,GlyphMap map,double anchorX,double anchorFont){List<MarginRun> runs=new ArrayList<>();for(int n=0;n<map.glyphs.size();){GlyphSpan first=map.glyphs.get(n);if(first.font>=anchorFont*.8||first.x+first.width>=anchorX){n++;continue;}int last=n;while(last+1<map.glyphs.size()&&Math.abs(map.glyphs.get(last+1).font-first.font)<.2&&Math.abs(map.glyphs.get(last+1).y-first.y)<first.height)last++;GlyphSpan end=map.glyphs.get(last);String text=member.text.substring(first.start,end.end).replaceAll("\\s+","");String role=text.matches("(?i)(?:SCC|Rev\\.\\d+)")?"seed":text.equalsIgnoreCase("approvedon")?"approval":text.matches("\\d{2}\\.\\d{2}\\.\\d{4}")?"date":"";if(!role.isEmpty()){boolean continuous=true;double right=first.x+first.width,bodyLeft=Double.NaN;for(int k=n;k<=last;k++){GlyphSpan glyph=map.glyphs.get(k);right=Math.max(right,glyph.x+glyph.width);if(k>n){GlyphSpan prior=map.glyphs.get(k-1);double tolerance=4*Math.max(Math.max(prior.width,glyph.width),Math.max(prior.height,glyph.height));if(glyph.x<prior.x-prior.width||glyph.x-(prior.x+prior.width)>tolerance)continuous=false;}}for(int k=last+1;k<map.glyphs.size();k++){GlyphSpan body=map.glyphs.get(k);if(body.font>=anchorFont*.8&&Math.abs(body.y-first.y)<=body.height+first.height){bodyLeft=Double.isNaN(bodyLeft)?body.x:Math.min(bodyLeft,body.x);}}runs.add(new MarginRun(member,first.start,end.end,first.x,first.y,first.width,first.height,first.font,role,right,bodyLeft,continuous));}n=last+1;}return runs;}
    private static boolean completeMarginCluster(MarginRun... runs){double bodyLeft=Double.NaN;for(MarginRun run:runs)if(Double.isFinite(run.bodyLaneLeft))bodyLeft=Double.isNaN(bodyLeft)?run.bodyLaneLeft:Math.min(bodyLeft,run.bodyLaneLeft);if(!Double.isFinite(bodyLeft))return false;for(MarginRun run:runs)if(!run.continuous||run.right>=bodyLeft)return false;return true;}
    private static boolean sameMargin(MarginRun a,MarginRun b){return Math.abs(a.x-b.x)<=2*Math.max(a.width,b.width)&&Math.abs(a.font-b.font)<.2;}
    private static boolean persistPdfFrames(ObjectNode value,JsonNode ranges,List<Member> members){
        if(!ranges.isArray()||ranges.isEmpty())return false;Map<Integer,GlyphMap> frames=new TreeMap<>();String source=null;for(JsonNode range:ranges){int at=indexOf(members,range.path("memberId").asText());if(at<0||!"text".equals(range.path("channel").asText())||!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber())return false;Member member=members.get(at);GlyphMap frame=nativePdfRangeLayout(member,range.path("start").asInt(),range.path("end").asInt());if(frame==null||source!=null&&!source.equals(member.expectedPdfSource))return false;source=member.expectedPdfSource;GlyphMap prior=frames.putIfAbsent(pdfPage(member),frame);if(prior!=null&&!samePdfFrame(prior,frame))return false;}
        ArrayNode observed=value.putArray("observedGlyphFrames");for(Map.Entry<Integer,GlyphMap> entry:frames.entrySet()){ObjectNode frame=observed.addObject();frame.put("sourceSha256",source);frame.put("physicalPageNo",entry.getKey());frame.put("coordinateSpace","normalized_rendered_crop_top_left");frame.put("pageWidthInPt",entry.getValue().pageWidth);frame.put("pageHeightInPt",entry.getValue().pageHeight);frame.put("basis","source_bound_glyph_pdfbox_position_and_text_matrix");frame.put("originalPageTransformInferred",false);}return true;
    }
    private static int pdfHeadingStart(String profile,Member member,List<Member> members){
        if(CLAUSE.matcher(member.text).find())return 0;Matcher reference=REFERENCE.matcher(member.text);if(!reference.find()||!reference.group().replaceAll("\\s+","").startsWith(profile)||!member.text.substring(reference.end()).trim().matches("(?s)^\\p{L}.+"))return -1;int start=reference.start();return pdfHeadingMargins(member,start,members).isEmpty()?-1:start;
    }
    private static boolean unresolvedPdfRevisionHeading(String profile,Member member,List<Member> members){return member.text.matches("(?is)^\\s*(?:Rev\\.\\s*\\d+|SCC)\\s+(?:NTT|SCT|SCC)\\s*\\d+(?:\\.\\d+)*\\s+\\p{L}.*")&&pdfHeadingStart(profile,member,members)<0;}
    private static List<MarginRun> pdfHeadingMargins(Member member,int start,List<Member> members){
        GlyphMap body=nativePdfRangeLayout(member,start,member.text.length());if(body==null)return Collections.emptyList();GlyphSpan anchor=body.glyphs.get(0);List<MarginRun> candidates=new ArrayList<>();for(Member peer:members)if(peer.usable&&sameStory(member,peer)&&member.expectedPdfSource.equals(peer.expectedPdfSource)&&pdfPage(peer)==pdfPage(member)){GlyphMap map=glyphMap(peer);if(map.complete)for(MarginRun run:marginRuns(peer,map,anchor.x,anchor.font)){GlyphMap frame=nativePdfRangeLayout(peer,run.start,run.end);if(frame!=null&&samePdfFrame(body,frame))candidates.add(run);}}
        List<MarginRun> match=null;for(MarginRun seed:candidates)if(seed.member==member&&seed.role.equals("seed")&&seed.end<=start){boolean prefix=true;for(int n=0;n<start;n++)if(!Character.isWhitespace(member.text.charAt(n))&&(n<seed.start||n>=seed.end))prefix=false;if(!prefix)continue;for(MarginRun approval:candidates)if(approval.role.equals("approval")&&sameMargin(seed,approval)&&approval.y>=seed.y-seed.height&&approval.y-seed.y<=8*Math.max(seed.height,approval.height))for(MarginRun date:candidates)if(date.role.equals("date")&&sameMargin(approval,date)&&date.y>=approval.y-approval.height&&date.y-approval.y<=8*Math.max(approval.height,date.height)&&completeMarginCluster(seed,approval,date)){if(match!=null)return Collections.emptyList();match=Arrays.asList(seed,approval,date);}}
        return match==null?Collections.emptyList():match;
    }
    private static final class GlyphMap {final List<GlyphSpan> glyphs=new ArrayList<>();boolean complete;double pageWidth,pageHeight;}
    private static final class GlyphSpan {final String id;final int start,end;final double x,y,width,height,font;GlyphSpan(String id,int start,int end,double x,double y,double width,double height,double font){this.id=id;this.start=start;this.end=end;this.x=x;this.y=y;this.width=width;this.height=height;this.font=font;}}
    private static final class MarginRun {final Member member;final int start,end;final double x,y,width,height,font,right,bodyLaneLeft;final String role;final boolean continuous;MarginRun(Member member,int start,int end,double x,double y,double width,double height,double font,String role,double right,double bodyLaneLeft,boolean continuous){this.member=member;this.start=start;this.end=end;this.x=x;this.y=y;this.width=width;this.height=height;this.font=font;this.role=role;this.right=right;this.bodyLaneLeft=bodyLaneLeft;this.continuous=continuous;}}
    private static boolean unitMarkerAnchor(JsonNode group,Member member,boolean explicit){JsonNode target=explicit?group.path("targets").path(0):group;if(target.path("startBoundary").path("memberId").asText().equals(member.id))return true;String reference=explicit?target.path("reference").asText():group.path("sourceReference").asText();Matcher heading=CLAUSE.matcher(member.text);return !reference.contains("(")&&heading.find()&&heading.group(1).replaceAll("\\s+","").equals(reference)&&member.text.matches("(?is).*\\(\\s*Cont[’']?d\\s*\\)\\s*$");}
    private static void sidebarUnitScopes(String profile,List<Member> members,List<Anchor> clauses,Set<String> noteMembers,ArrayNode notes,ArrayNode groups,ArrayNode issues) {
        for(JsonNode note:notes){String instruction=note.path("text").asText();if(instruction.matches("(?is).*\\b(?:alternative|option|delete\\s+the\\s+words|delete\\s+items?\\s+marked)\\b.*")||!instruction.matches("(?is).*\\b(?:delete\\s+if|not\\s+used.*?\\bif|for\\s+use\\s+where)\\b.*"))continue;int noteAt=indexOf(members,note.path("orderedMemberIds").path(0).asText());if(noteAt<0)continue;Member source=members.get(noteAt);if(source.value.has("blockObservation")){pdfSidebarUnitScope(profile,members,clauses,noteMembers,noteAt,note,notes,groups,issues);continue;}if(wordLeadingUnitScope(profile,members,clauses,noteMembers,noteAt,note,groups,issues))continue;if(source.row.isEmpty())continue;
            List<Member> candidates=new ArrayList<>();for(Member member:members)if(member.usable&&member.row.equals(source.row)&&sameStory(source,member)&&!noteMembers.contains(member.id)&&member.text.matches("(?s)^\\s*[*#]\\s*(?:\\([a-z0-9]+\\)|(?:NTT|SCT|SCC)?\\s*\\d+).*$"))candidates.add(member);if(candidates.size()!=1)continue;Member target=candidates.get(0);int targetAt=indexOf(members,target.id);Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,targetAt));if(owner==null)continue;List<Unit> candidatesUnits=new ArrayList<>();for(Unit unit:units(owner,nextClause(clauses,owner,members.size()),members,noteMembers,array()))if(unit.start==targetAt)candidatesUnits.add(unit);if(candidatesUnits.size()!=1)continue;Unit unit=candidatesUnits.get(0);
            ObjectNode group=lexicalGroup(note,"source_unit_instruction_scope",target.id,groups);group.put("sourceReference",unit.reference);group.put("scopeMeaning","Complete native clause or subitem explicitly associated with the conditional source sidebar; project applicability remains unknown");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),context=group.putArray("physicalContextMemberIds"),parentContext=group.putArray("parentContextMemberIds");boolean known=!unit.ambiguousTail&&unit.sourcePagesContiguous;
            for(int n=unit.start;n<unit.end;n++){Member member=members.get(n);if(!sameStory(target,member)||noteMembers.contains(member.id))continue;if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members))){context.add(member.id);continue;}if(member.usable&&unit.reference.contains("(")&&parentContinuationRow(member,owner.label,members)){parentContext.add(member.id);continue;}known&=member.usable;addRange(ranges,ids,member,0,member.text.length());}
            boolean complete=known&&ids.size()>0&&unit.end<members.size()&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();group.put("captureComplete",known);group.put("scopeComplete",complete);group.putObject("startBoundary").put("kind","explicit_native_clause_or_subitem_label").put("memberId",target.id);ObjectNode boundary=group.putObject("endBoundary");boundary.put("kind","next_native_peer_or_parent_label");if(unit.end<members.size())boundary.put("memberId",members.get(unit.end).id);if(!complete)issue(issues,"SOURCE_UNIT_INSTRUCTION_SCOPE_INCOMPLETE",group.path("id").asText(),"The complete source unit, native peer boundary or sidebar syntax is unresolved");
        }
    }
    private static boolean wordLeadingUnitScope(String profile,List<Member> members,List<Anchor> clauses,Set<String> noteMembers,int noteAt,JsonNode note,ArrayNode groups,ArrayNode issues){
        Member source=members.get(noteAt);Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,noteAt));if(owner==null||owner.ordinal>=noteAt||!sameNativeSource(source,owner.member)||!source.cell.equals(owner.member.cell)||!completeNativeNote(note,source,members))return false;
        for(int n=owner.ordinal+1;n<noteAt;n++){Member member=members.get(n);if(!sameNativeSource(source,member)||!member.text.trim().isEmpty())return false;}int end=nextClause(clauses,owner,members.size());if(end>=members.size()||!sameNativeSource(source,members.get(end)))return false;
        ObjectNode group=lexicalGroup(note,"source_unit_instruction_scope",owner.member.id,groups);group.put("sourceReference",owner.label);group.put("scopeMeaning","Complete native Word clause following its leading conditional guidance; project applicability and external referenced clauses remain separate");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),context=group.putArray("physicalContextMemberIds"),guidance=group.putArray("guidanceContextMemberIds");boolean known=true;
        for(int n=owner.ordinal;n<end;n++){Member member=members.get(n);known&=sameNativeSource(source,member)&&uniqueMemberIndex(members,member.id)==n;if(noteMembers.contains(member.id)){guidance.add(member.id);continue;}if(member.usable&&(pageFurniture(profile,member,members)||furnitureRow(profile,member,members))){context.add(member.id);continue;}if(!member.text.isEmpty())addRange(ranges,ids,member,0,member.text.length());}
        known&=ids.size()>1&&nativeRangesBound(ranges,source,members);group.put("captureComplete",known);group.put("scopeComplete",known);group.putObject("startBoundary").put("kind","source_bound_native_word_clause_heading").put("memberId",owner.member.id).put("offset",0);group.putObject("endBoundary").put("kind","next_source_bound_native_word_clause_heading").put("memberId",members.get(end).id).put("offset",0);if(!known)issue(issues,"SOURCE_UNIT_INSTRUCTION_SCOPE_INCOMPLETE",group.path("id").asText(),"The complete original Word clause, native source identity or next clause boundary remains unverified");
        if(known){Set<String> requested=new LinkedHashSet<>();for(JsonNode reference:note.path("referenceCandidates")){String value=reference.path("text").asText().replaceAll("\\s+","");if(!value.startsWith(owner.label+"("))continue;boolean linked=false;for(JsonNode existing:groups)if(note.path("id").asText().equals(existing.path("guidanceNoteId").asText())&&existing.path("scopeComplete").asBoolean())for(JsonNode target:existing.path("targets"))if(value.equals(target.path("reference").asText())&&target.path("scopeComplete").asBoolean())linked=true;if(!linked)requested.add(value);}if(!requested.isEmpty())resolveReferenceSet(profile,members,clauses,noteMembers,noteAt,note,String.join(", ",requested),groups,issues);}return true;
    }
    private static void pdfSidebarUnitScope(String profile,List<Member> members,List<Anchor> clauses,Set<String> noteMembers,int noteAt,JsonNode note,ArrayNode notes,ArrayNode groups,ArrayNode issues){
        Anchor owner=firstClauseInSequence(clauses,enclosing(clauses,noteAt));if(owner==null||owner.ordinal>=noteAt||!note.path("captureComplete").asBoolean()||!note.path("sourceSyntaxComplete").asBoolean()||!"source_bound_observed_glyph_frames".equals(note.path("pdfLaneProjection").path("frameStatus").asText()))return;int start=pdfHeadingStart(profile,owner.member,members);if(start<0)return;List<MarginRun> headingMargins=start>0?pdfHeadingMargins(owner.member,start,members):Collections.emptyList();
        for(int n=owner.ordinal+1;n<noteAt;n++){Member member=members.get(n);if(!sameStory(owner.member,member)||!pdfMarginOnly(member,notes,headingMargins)||!member.usable)return;}
        int end=nextClause(clauses,owner,members.size());if(end>=members.size())return;Member boundary=members.get(end);int stop=pdfHeadingStart(profile,boundary,members);if(stop<0||!sameStory(owner.member,boundary))return;
        ObjectNode group=lexicalGroup(note,"source_unit_instruction_scope",owner.member.id,groups);group.put("sourceReference",owner.label);group.put("scopeMeaning","Complete observed native PDF clause following its leading conditional guidance; project applicability and external referenced clauses remain separate");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds"),context=group.putArray("physicalContextMemberIds"),metadata=group.putArray("revisionMarginContextRanges");group.set("guidanceContextMemberIds",note.path("orderedMemberIds").deepCopy());boolean known=true;int previousPage=pdfPage(owner.member);Map<Integer,GlyphMap> frames=new HashMap<>();
        for(int n=owner.ordinal;n<end;n++){Member member=members.get(n);int page=pdfPage(member);known&=sameStory(owner.member,member)&&member.usable&&page>=previousPage&&page<=previousPage+1&&!unresolvedPdfRevisionHeading(profile,member,members);previousPage=page;if(noteMembers.contains(member.id))continue;if(pdfMarginOnly(member,notes,headingMargins)||pageFurniture(profile,member,members)){context.add(member.id);known&=pdfReferenceFrame(member,frames);continue;}int from=n==owner.ordinal?start:0;GlyphMap map=nativePdfRangeLayout(member,from,member.text.length());known&=map!=null;addPdfRange(ranges,ids,member,from,member.text.length(),map);}
        for(JsonNode observed:note.path("revisionMarginContextRanges"))metadata.add(observed.deepCopy());for(MarginRun run:headingMargins)addPdfRange(metadata,array(),run.member,run.start,run.end,nativePdfRangeLayout(run.member,run.start,run.end));
        known&=pdfBoundaryFrame(profile,boundary,members,frames)&&persistPdfFrames(group,ranges,members);group.put("captureComplete",known);group.put("scopeComplete",known&&ids.size()>0);group.putObject("startBoundary").put("kind","source_bound_pdf_clause_heading").put("memberId",owner.member.id).put("offset",start);group.putObject("endBoundary").put("kind","next_source_bound_pdf_clause_heading").put("memberId",boundary.id).put("offset",stop);if(!known)issue(issues,"PDF_SOURCE_UNIT_INSTRUCTION_SCOPE_INCOMPLETE",group.path("id").asText(),"The complete observed native PDF clause, adjacent boundary or glyph frame remains unverified");
        if(known){Set<String> requested=new LinkedHashSet<>();for(JsonNode reference:note.path("referenceCandidates")){String value=reference.path("text").asText().replaceAll("\\s+","");if(value.startsWith(owner.label+"("))requested.add(value);}if(!requested.isEmpty())resolveReferenceSet(profile,members,clauses,noteMembers,noteAt,note,String.join(", ",requested),groups,issues);}
    }
    private static boolean pdfMarginOnly(Member member,ArrayNode notes,List<MarginRun> headingMargins){
        if(member.text.trim().isEmpty())return false;boolean any=false;for(int n=0;n<member.text.length();n++)if(!Character.isWhitespace(member.text.charAt(n))){boolean covered=false;for(JsonNode note:notes)for(JsonNode range:note.path("revisionMarginContextRanges"))if(member.id.equals(range.path("memberId").asText())&&range.path("start").asInt()<=n&&range.path("end").asInt()>n)covered=true;for(MarginRun range:headingMargins)if(range.member==member&&range.start<=n&&range.end>n)covered=true;if(!covered)return false;any=true;}return any;
    }
    private static String literalWhitespace(String literal){StringBuilder regex=new StringBuilder();for(String token:literal.trim().split("\\s+")){if(regex.length()>0)regex.append("\\s+");regex.append(Pattern.quote(token));}return regex.toString();}
    private static String unknownMarkerReason(Member member,int at){String before=member.text.substring(0,Math.min(at,member.text.length())),after=member.text.substring(Math.min(at+1,member.text.length()));if(CLAUSE.matcher(member.text).find())return "HEADING_OR_CONTINUATION_MARKER_WITHOUT_EXACT_TARGET_PROOF";if(before.matches("(?s).*_+$")||after.matches("(?s)^_+.*"))return "PLACEHOLDER_WITHOUT_BOUND_FILL_IN_SCOPE";if(after.matches("(?s)^\\p{L}+/\\p{L}.*")||before.matches("(?s).*\\p{L}/\\p{L}+$"))return "SLASH_CHOICE_WITHOUT_COMPLETE_SOURCE_INSTRUCTION";if(!before.trim().isEmpty()&&!after.matches("(?s)^\\p{L}.*"))return "SUFFIX_MARKER_WITHOUT_EXPLICIT_EXACT_WORD_SCOPE";return "SOURCE_INSTRUCTION_TARGET_ASSOCIATION_UNRESOLVED";}

    private static void markedItemsAbove(List<Member> members,List<Anchor> clauses,int noteAt,JsonNode note,String symbol,ArrayNode groups,ArrayNode issues) {
        Anchor parent=enclosing(clauses,noteAt);int start=parent==null?0:parent.ordinal;for(int n=start;n<noteAt;n++){Member member=members.get(n);if(member.usable&&firstCell(member,members)&&member.text.matches("(?s)^\\s*[*#]*\\s*\\(\\d+\\).*$"))start=n;}
        ObjectNode group=groups.addObject();String id="marked-items:"+digest(note.path("id").asText());group.put("id",id);group.put("kind","explicit_marked_item_scope");group.put("symbol",symbol);group.put("guidanceNoteId",note.path("id").asText());group.put("scopeMeaning","Only explicitly marked native item rows above the instruction, including each row's reference cells");group.put("automaticEditEligible",false);ArrayNode ids=group.putArray("orderedMemberIds"),rows=group.putArray("targetRows");Set<String> selected=new HashSet<>();boolean known=parent!=null&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean();
        for(int n=start;n<noteAt;n++){Member member=members.get(n);if(member.row.isEmpty()||!firstCell(member,members)||!member.text.matches("(?s)^\\s*"+Pattern.quote(symbol)+"\\s*\\([a-z0-9]+\\).*$"))continue;ObjectNode row=rows.addObject();row.put("rowNodePath",member.row);ArrayNode rowIds=row.putArray("orderedMemberIds");for(Member peer:members)if(peer.row.equals(member.row)&&sameStory(peer,member)){selected.add(peer.id);rowIds.add(peer.id);known&=peer.usable;}}
        for(Member member:members)if(selected.contains(member.id))ids.add(member.id);known&=rows.size()>0;group.put("captureComplete",known);group.put("scopeComplete",known);if(!known)issue(issues,"MARKED_ITEMS_ABOVE_SCOPE_INCOMPLETE",id,"The marked item set, parent or native row channels are unverified");
    }

    private static void externalRateScope(List<Member> members,Member instruction,JsonNode note,ArrayNode groups) {
        ObjectNode group=groups.addObject();group.put("id","external-rate:"+digest(note.path("id").asText()));group.put("kind","external_verification_scope");group.put("guidanceNoteId",note.path("id").asText());group.put("requiredAction","external_verification_required");group.put("automaticEditEligible",false);group.put("scopeMeaning","Marked rate literals require the current external authority; the row is not a deletion target");ArrayNode ranges=group.putArray("memberRanges"),ids=group.putArray("orderedMemberIds");
        for(Member member:members)if(member.usable&&!instruction.row.isEmpty()&&member.row.equals(instruction.row)&&sameStory(member,instruction)&&!member.id.equals(instruction.id)){Matcher amount=Pattern.compile("[#*]\\s*[$£€]?\\s*(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?![\\p{L}\\d.,])").matcher(member.text);while(amount.find())addRange(ranges,ids,member,amount.start(),amount.end());}
        group.put("captureComplete",ranges.size()>0);group.put("scopeComplete",ranges.size()>0&&note.path("captureComplete").asBoolean()&&note.path("sourceSyntaxComplete").asBoolean());
    }

    private static int parenthesisEnd(String text,int start){if(start<0)return -1;int depth=0;for(int n=start;n<text.length();n++){if(text.charAt(n)=='(')depth++;if(text.charAt(n)==')'&&--depth==0)return n+1;}return -1;}
    private static boolean rangeContains(JsonNode ranges,String member,int offset){for(JsonNode range:ranges)if(member.equals(range.path("memberId").asText())&&range.path("start").asInt()<=offset&&range.path("end").asInt()>offset)return true;return false;}
    private static boolean noteHasSymbol(ArrayNode notes,String id,String symbol){for(JsonNode note:notes)if(id.equals(note.path("id").asText()))return note.path("text").asText().contains(symbol);return false;}
    private static void appendUnique(ArrayNode values,String value){if(value!=null&&!value.isEmpty()&&!hasId(values,value))values.add(value);}

    private static boolean sourceReferenceColumnContext(Member member,List<Member> members){
        if(member.table.isEmpty()||member.column.isEmpty()||firstCell(member,members))return false;
        int at=members.indexOf(member),annotation=-1;String continued=null,annotationCell="",annotationText="";boolean earlierBody=false,preludeBody=false;if(at<0)return false;
        for(int n=0;n<=at;n++){Member peer=members.get(n);if(!peer.usable||!sameStory(peer,member)||!peer.table.equals(member.table))continue;
            if(peer.column.equals(member.column)){String alternative=ALTERNATIVE.matcher(peer.text).find()?sourceAlternativeCell(peer,members):"";if(peer.text.trim().matches("(?i)References?")&&!earlierBody&&sourceReferenceHeaderRow(peer,members))return true;if(!alternative.isEmpty()){annotation=n;annotationCell=peer.cell;annotationText=alternative;preludeBody=earlierBody;}else if(!peer.cell.equals(annotationCell)&&!peer.text.trim().isEmpty())earlierBody=true;}
            Matcher clause=CLAUSE.matcher(peer.text);if(sourceContinuationHeading(peer)&&clause.find()&&firstCell(peer,members))continued=clause.group(1).replaceAll("\\s+","");
        }
        if(annotation<0||annotation==at||continued==null||preludeBody)return false;Member current=members.get(annotation);
        for(int n=annotation+1;n<=at;n++){Member value=members.get(n);if(value.table.equals(member.table)&&value.column.equals(member.column)&&sameStory(value,member)&&!value.cell.equals(annotationCell)&&!value.text.trim().isEmpty()&&(!REFERENCE.matcher(value.text).find()||!REFERENCE.matcher(value.text).replaceAll("").replaceAll("(?i)\\b(?:and|or)\\b","").matches("[\\p{Z}\\s,;&/()]*")))return false;}
        for(int n=annotation-1;n>=0;n--){Member prior=members.get(n);if(!prior.usable||prior.table.equals(current.table)||!sameStory(prior,current)||!ALTERNATIVE.matcher(prior.text).find()||!annotationText.equals(sourceAlternativeCell(prior,members)))continue;
            String priorRoot=null;for(int p=0;p<n;p++){Member heading=members.get(p);Matcher label=CLAUSE.matcher(heading.text);if(heading.usable&&sameStory(heading,prior)&&label.find()&&(heading.cell.isEmpty()||firstCell(heading,members)))priorRoot=label.group(1).replaceAll("\\s+","");}
            if(!continued.equals(priorRoot))continue;for(int p=n+1;p<annotation;p++){Member header=members.get(p);if(header.usable&&sameStory(header,prior)&&header.table.equals(prior.table)&&header.column.equals(member.column)&&header.text.trim().matches("(?i)References?")&&sourceReferenceHeaderRow(header,members)&&sourceReferenceHeaderPrelude(header,members))return true;}
        }
        return false;
    }
    private static boolean sourceReferenceHeaderRow(Member header,List<Member> members){for(Member peer:members)if(peer.usable&&sameStory(header,peer)&&header.row.equals(peer.row)&&!header.cell.equals(peer.cell)&&!peer.text.trim().isEmpty()&&!CELL_NUMBER.matcher(peer.text).matches()&&!ALTERNATIVE.matcher(peer.text).find())return true;return false;}
    private static boolean sourceReferenceHeaderPrelude(Member header,List<Member> members){Set<String> annotations=new HashSet<>();for(Member peer:members){if(peer==header)return true;if(!peer.table.equals(header.table)||!peer.column.equals(header.column)||!sameStory(peer,header))continue;if(ALTERNATIVE.matcher(peer.text).find()&&!sourceAlternativeCell(peer,members).isEmpty())annotations.add(peer.cell);else if(!annotations.contains(peer.cell)&&!peer.text.trim().isEmpty())return false;}return false;}
    private static String sourceAlternativeCell(Member source,List<Member> members){
        if(source.cell.isEmpty())return "";StringJoiner joined=new StringJoiner(" ");int parts=0;for(Member member:members)if(sameStory(source,member)&&source.cell.equals(member.cell)){if(!member.usable)return "";if(!member.text.trim().isEmpty()){joined.add(member.text.trim());parts++;}}
        String text=joined.toString();if(!ALTERNATIVE.matcher(text).find())return "";char opening=text.charAt(0);if(opening!='('&&opening!='[')return parts==1?text:"";char closing=opening=='('?')':']';int depth=0;for(int at=0;at<text.length();at++){char c=text.charAt(at);if(c==opening)depth++;else if(c==closing&&--depth==0)return text.substring(at+1).trim().matches("[.;,:]*")?text:"";}return "";
    }
    private static boolean furnitureRow(String profile,Member member,List<Member> members){if(member.row.isEmpty())return false;boolean title=false;for(Member peer:members)if(peer.row.equals(member.row)&&sameStory(member,peer)&&!peer.text.trim().isEmpty()){if(!pageFurniture(profile,peer,members))return false;title=true;}return title;}
    private static boolean pageFurniture(String profile,Member member,List<Member> members){if(member.value.has("blockObservation")&&pdfPageFurniture(member,members))return true;String text=member.text.trim();if(text.isEmpty())return true;if(CLAUSE.matcher(text).find()||SUBITEM.matcher(text).find()||CELL_NUMBER.matcher(text).matches())return false;String month="(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{4}";if(text.matches("(?i)(?:notes? to tenderers?|notice to tenderers?|special conditions? of (?:tender|contract))\\s*(?:\\(Cont[’']?d\\))?"))return true;if(text.length()<120&&text.matches("(?i)(?:"+month+"\\s*[\\p{Pd}−]?\\s*)?[\\p{Pd}−]?\\s*"+Pattern.quote(profile)+"/\\d+\\s*[\\p{Pd}−]?\\s*"))return true;if(text.length()<100&&text.matches("HD\\(QS\\)\\s+[0-9A-Za-z /()._-]+"))return true;if(text.matches("(?i)"+month)){if(member.value.path("storyKind").asText().equals("footer"))return true;return adjacentHdPageFooter(profile,member,members,-1)||adjacentHdPageFooter(profile,member,members,1);}return false;}
    private static boolean adjacentHdPageFooter(String profile,Member member,List<Member> members,int direction){for(int n=indexOf(members,member.id)+direction;n>=0&&n<members.size();n+=direction){Member peer=members.get(n);if(!sameStory(member,peer))continue;if(!peer.usable)return false;String text=peer.text.trim();if(text.isEmpty())continue;return text.length()<100&&text.matches("(?i)HD\\(QS\\)\\s+[0-9A-Za-z /()._-]+\\s+[\\p{Pd}−]?\\s*"+Pattern.quote(profile)+"/\\d+\\s*[\\p{Pd}−]?\\s*");}return false;}
    private static int rowStart(List<Member> members,int ordinal){Member member=members.get(ordinal);while(ordinal>0&&!member.row.isEmpty()&&member.row.equals(members.get(ordinal-1).row)&&sameStory(member,members.get(ordinal-1)))ordinal--;return ordinal;}
    private static boolean memberHasInstruction(Member member,ArrayNode notes){for(JsonNode note:notes)if(hasId(note.path("orderedMemberIds"),member.id))return true;return false;}
    private static boolean instructionOnly(Member member,ArrayNode notes){boolean[] covered=new boolean[member.text.length()];for(JsonNode note:notes)for(JsonNode range:note.path("memberRanges"))if(member.id.equals(range.path("memberId").asText()))for(int n=Math.max(0,range.path("start").asInt());n<Math.min(covered.length,range.path("end").asInt());n++)covered[n]=true;for(int n=0;n<covered.length;n++)if(!covered[n]&&!Character.isWhitespace(member.text.charAt(n)))return false;return true;}
    private static boolean compilationCell(Member member,List<Member> members,ArrayNode notes){if(member.cell.isEmpty())return false;boolean instruction=false;for(Member peer:members)if(peer.cell.equals(member.cell)&&sameStory(peer,member)&&!peer.text.trim().isEmpty()){if(!memberHasInstruction(peer,notes)||!instructionOnly(peer,notes))return false;instruction=true;}return instruction;}
    private static Set<String> rowColumns(Member annotation,List<Member> members){Set<String> columns=new HashSet<>();for(Member member:members)if(annotation.row.equals(member.row)&&sameStory(annotation,member)&&!member.column.isEmpty())columns.add(member.column);return columns;}
    private static String numericSubitemInRow(Member annotation,List<Member> members){for(Member member:members)if(member.usable&&member.row.equals(annotation.row)&&sameStory(member,annotation)&&firstCell(member,members)&&!member.text.trim().isEmpty()){if(CLAUSE.matcher(member.text).find())return "";Matcher label=Pattern.compile("^\\s*[*#]*\\s*\\((\\d+)\\)").matcher(member.text);if(label.find())return label.group(1);}return "";}
    private static boolean parentContinuationRow(Member member,String parentLabel,List<Member> members){boolean found=false;for(Member peer:members)if(peer.row.equals(member.row)&&sameStory(member,peer)&&!peer.text.trim().isEmpty()){Matcher heading=CLAUSE.matcher(peer.text);if(!heading.find()||!heading.group(1).replaceAll("\\s+","").equals(parentLabel)||!peer.text.matches("(?is).*\\(\\s*Cont[’']?d\\s*\\)\\s*$"))return false;found=true;}return found;}
    private static Anchor nextNumericSubitem(List<Member> members,List<Anchor> clauses,String parentLabel,int optionAt){if(parentLabel.isEmpty())return null;Member annotation=members.get(optionAt);String current="";for(Member member:members)if(member.usable&&member.row.equals(annotation.row)&&sameStory(member,annotation)&&firstCell(member,members)){Matcher heading=CLAUSE.matcher(member.text);if(heading.find())return null;Matcher label=Pattern.compile("^\\s*[*#]*\\s*\\((\\d+)\\)").matcher(member.text);if(label.find()){current=label.group(1);break;}}if(current.isEmpty())return null;for(int n=optionAt+1;n<members.size();n++){Member member=members.get(n);if(!member.usable||member.row.isEmpty()||!firstCell(member,members))continue;Matcher label=Pattern.compile("^\\s*[*#]*\\s*\\((\\d+)\\)").matcher(member.text);if(label.find()&&!label.group(1).equals(current))return new Anchor(n,parentLabel+"("+label.group(1)+")","next_native_numeric_subitem",member);}return null;}
    private static boolean firstCell(Member member,List<Member> members){for(Member peer:members)if(peer.row.equals(member.row)&&sameStory(peer,member)&&!peer.cell.isEmpty())return peer.cell.equals(member.cell);return false;}
    private static boolean hasId(JsonNode ids,String id){for(JsonNode member:ids)if(id.equals(member.asText()))return true;return false;}
    private static int firstChild(List<Unit> units,Unit parent){int first=parent.end;for(Unit unit:units)if(unit.start>parent.start&&unit.start<parent.end&&unit.depth>parent.depth)first=Math.min(first,unit.start);return first;}
    private static DirectIndent directIndent(Member member){
        Map<String,String> values=sourceIndentDeclarations(member);
        boolean chars=values.containsKey("startChars")||values.containsKey("leftChars");String suffix=chars?"Chars":"";
        try{
            String start=values.get("start"+suffix),left=values.get("left"+suffix);if(start==null&&left==null||start!=null&&left!=null&&!start.equals(left))return null;
            if(!chars&&(Long.parseLong(values.getOrDefault("hangingChars","0"))!=0||Long.parseLong(values.getOrDefault("firstLineChars","0"))!=0))return null;
            if(chars&&!values.containsKey("hangingChars")&&Long.parseLong(values.getOrDefault("hanging","0"))!=0||chars&&!values.containsKey("firstLineChars")&&Long.parseLong(values.getOrDefault("firstLine","0"))!=0)return null;
            long resolved=Long.parseLong(start==null?left:start),hanging=Long.parseLong(values.getOrDefault("hanging"+suffix,"0"));
            if(Long.parseLong(values.getOrDefault("firstLine"+suffix,"0"))!=0||resolved<0||hanging<0)return null;
            return new DirectIndent(resolved,hanging,chars?"characters":"twips");
        }catch(NumberFormatException invalid){return null;}
    }
    private static final class DirectIndent {final long left,hanging;final String unit;DirectIndent(long left,long hanging,String unit){this.left=left;this.hanging=hanging;this.unit=unit;}}
    private static Anchor enclosing(List<Anchor> anchors,int ordinal){Anchor result=null;for(Anchor anchor:anchors)if(anchor.ordinal<=ordinal)result=anchor;return result;}
    private static Anchor firstClauseInSequence(List<Anchor> anchors,Anchor chosen){if(chosen==null)return null;Anchor first=chosen;for(int n=anchors.indexOf(chosen)-1;n>=0;n--){Anchor previous=anchors.get(n);if(!previous.label.equals(chosen.label)||!sameStory(previous.member,chosen.member))break;first=previous;}return first;}
    private static String label(Anchor anchor){return anchor==null?"":anchor.label;}
    private static int nextClause(List<Anchor> anchors,Anchor current,int fallback){for(Anchor anchor:anchors)if(anchor.ordinal>current.ordinal&&!anchor.label.equals(current.label)&&sameStory(current.member,anchor.member))return anchor.ordinal;return fallback;}
    private static int indexOf(List<Member> members,String id){for(int n=0;n<members.size();n++)if(members.get(n).id.equals(id))return n;return -1;}
    private static boolean pageGroupBound(JsonNode group,String source,Integer page,int pageCount){
        if(page==null||page<1||page>pageCount||group.path("physicalPageNo").asInt(-1)!=page||!source.equals(group.path("source").path("sourceSha256").asText()))return false;JsonNode revision=group.path("source").path("sourceRevisionSha256");if(!revision.isMissingNode()&&!source.equals(revision.asText()))return false;
        for(JsonNode candidate:group.path("candidates")){JsonNode observed=candidate.path("page");if(observed.path("physicalPageNo").asInt(-1)!=page)return false;JsonNode frame=observed.path("mapping").path("originalPageTransform").path("targetFrame");if(frame.has("sourceSha256")&&!source.equals(frame.path("sourceSha256").asText())||frame.has("physicalPageNo")&&frame.path("physicalPageNo").asInt(-1)!=page)return false;}return true;
    }
    private static boolean guidanceCue(String text,int cue){int open=openingBefore(text,cue);if(insideQuotation(text,open>=0?open:cue))return false;if(open>=0)return text.substring(open+1,cue).trim().isEmpty();String prefix=text.substring(0,cue);int line=Math.max(prefix.lastIndexOf('\n'),prefix.lastIndexOf('\r'));String before=prefix.substring(line+1).trim();return before.isEmpty()||before.matches("(?s).*[.!?][\\])]*$");}
    private static boolean insideQuotation(String text,int limit){char closing='\0';int line=Math.max(text.lastIndexOf('\n',limit),text.lastIndexOf('\r',limit));for(int at=line+1;at<limit;at++){char c=text.charAt(at);if(closing!='\0'){if(c==closing)closing='\0';}else if(c=='“')closing='”';else if(c=='‘')closing='’';else if(c=='\"')closing='\"';else if(c=='\''&&!(at>0&&at+1<text.length()&&Character.isLetterOrDigit(text.charAt(at-1))&&Character.isLetterOrDigit(text.charAt(at+1))))closing='\'';}return closing!='\0';}
    private static boolean hasGuidanceCue(String text){Matcher match=GUIDANCE.matcher(text);while(match.find())if(guidanceCue(text,match.start()))return true;return false;}
    private static final class SourceListFrame {final int intro,first;int end;boolean ownerAmbiguous;SourceListFrame(int intro,int first,int end){this.intro=intro;this.first=first;this.end=end;}}
    private static final class Unit {final String reference;final int start,depth;int end,sharedTailStart=-1,sharedTailEnd=-1;boolean ambiguousTail,frameBoundaryUnknown,sourcePagesContiguous=true;SourceListFrame listFrame;Unit(String reference,int start,int end,int depth){this.reference=reference;this.start=start;this.end=end;this.depth=depth;}}

    private static boolean sidebarStart(Member member,List<Member> members){String t=member.text.trim();if(!t.startsWith("(")||t.matches("^\\([a-zA-Z0-9]+\\).*"))return false;if(EDITORIAL.matcher(t).find()||t.matches("(?is)^\\(\\s*(?:use|include|[*#]?\\s*(?:please\\s+)?(?:specify|enter))\\b.*"))return true;return !member.cell.isEmpty()&&!firstCell(member,members)&&t.matches("(?is)^\\(\\s*(?:address\\b.*|project\\s+\\p{L}[\\p{L}\\s]{0,70})\\)\\.?$");}
    private static int openingBefore(String text,int cue){Deque<Integer> active=new ArrayDeque<>();for(int n=0;n<=cue&&n<text.length();n++){char c=text.charAt(n);if(c=='['||c=='(')active.push(n);else if(c==']'||c==')'){if(!active.isEmpty()&&text.charAt(active.peek())==(c==']'?'[':'('))active.pop();else active.clear();}}return active.isEmpty()?-1:active.peek();}
    private static ArrayNode rowIds(Member source,List<Member> members){ArrayNode ids=array();for(Member member:members)if(member.row.equals(source.row)&&sameStory(member,source))ids.add(member.id);return ids;}
    private static boolean sameLane(Member first,Member current) {
        if(!sameStory(first,current))return false;
        if(first.cell.isEmpty())return current.cell.isEmpty();
        if(first.cell.equals(current.cell))return true;
        return !first.column.isEmpty()&&first.column.equals(current.column)&&!current.cell.isEmpty();
    }
    private static boolean sameOuterLane(Member first,Member current){return sameStory(first,current)&&!outerColumn(first).isEmpty()&&outerColumn(first).equals(outerColumn(current));}
    private static String outerColumn(Member member){int at=member.cell.indexOf("}tc[");if(at<0)return "";int end=member.cell.indexOf(']',at);return end<0?"":member.cell.substring(at,end+1);}
    private static boolean sameStory(Member a,Member b){return a.story.equals(b.story)&&a.part.equals(b.part);}
    private static boolean samePart(Member member,JsonNode position){return member.part.equals(position.path("partName").asText());}
    private static boolean descendant(String path,String ancestor){return !ancestor.isEmpty()&&(path.equals(ancestor)||path.startsWith(ancestor+"/"));}
    private static ArrayNode references(String text){ArrayNode out=array();Matcher matcher=REFERENCE.matcher(text);while(matcher.find()){ObjectNode r=out.addObject();r.put("text",matcher.group());r.put("start",matcher.start());r.put("end",matcher.end());r.put("offsetUnit","utf16_in_containing_text");r.put("status","literal_reference_candidate_not_resolved");}return out;}
    private static void addRange(ArrayNode ranges,ArrayNode ids,Member member,int start,int end){ObjectNode r=ranges.addObject();r.put("memberId",member.id);r.put("channel","text");r.put("start",start);r.put("end",end);r.put("offsetUnit","utf16");r.set("sourcePosition",member.value.path("sourcePosition").deepCopy());appendUnique(ids,member.id);}
    private static String inputSha(String key,String source,JsonNode blocks,JsonNode coverage){ObjectNode input=object();input.put("schema",SCHEMA);input.put("analysisRevision",ANALYSIS_REVISION);input.put("fileKey",key);input.put("sourceSha256",source);input.set("blocks",blocks==null?JsonUtils.mapper().nullNode():blocks.deepCopy());ObjectNode c=coverage!=null&&coverage.isObject()?(ObjectNode)coverage.deepCopy():object();c.remove("templateEvidence");input.set("coverage",c);return digest(canonical(input).toString());}
    private static String payloadSha(JsonNode evidence){ObjectNode payload=evidence.deepCopy();JsonNode binding=payload.path("sourceBinding");if(binding.isObject())((ObjectNode)binding).remove("generationSha256");return digest(canonical(payload).toString());}
    private static JsonNode canonical(JsonNode value){if(value.isObject()){ObjectNode out=object();List<String> keys=new ArrayList<>();value.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);for(String k:keys)out.set(k,canonical(value.get(k)));return out;}if(value.isArray()){ArrayNode out=array();for(JsonNode member:value)out.add(canonical(member));return out;}return value;}
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String sha(String value){if(value==null||!value.matches("(?i)[a-f0-9]{64}"))throw new IllegalArgumentException("Actual original source SHA256 is required");return value.toLowerCase(Locale.ROOT);}
    private static void key(String key){if(!Arrays.asList("NTT","SCT","SCC").contains(key))throw new IllegalArgumentException("Only NTT/SCT/SCC template profiles are supported");}
    private static ObjectNode object(){return JsonUtils.mapper().createObjectNode();}
    private static ArrayNode array(){return JsonUtils.mapper().createArrayNode();}
    private static void issue(ArrayNode issues,String code,String ref,String message){ObjectNode issue=issues.addObject();issue.put("code",code);if(ref!=null)issue.put("ref",ref);issue.put("status","open");issue.put("message",message);}
    private static final class Member {
        final ObjectNode value;final String id,text,part,path,story,table,row,cell,column,expectedPdfSource;final boolean usable;boolean pdfLayoutChecked;GlyphMap pdfLayout;
        Member(ObjectNode value,boolean bound){this(value,bound,null);}
        Member(ObjectNode value,boolean bound,String expectedPdfSource){this.expectedPdfSource=expectedPdfSource;this.value=value;id=value.path("id").asText();JsonNode position=value.path("sourcePosition"),observation=value.path("textObservation");part=position.path("partName").asText();path=position.path("nodePath").asText();story=value.path("storyKind").asText()+"|"+value.path("storyRootNodePath").asText();table=value.path("tableNodePath").asText();row=value.path("rowNodePath").asText();cell=value.path("cellNodePath").asText();int i=cell.lastIndexOf("}tc[");column=i<0?"":cell.substring(i);text=observation.path("text").asText();usable=bound&&observation.path("status").asText().startsWith("observed_")&&observation.path("text").isTextual()&&value.path("effectiveCandidate").asBoolean(true)&&!value.has("textOverlapStatus");}
        boolean body(){return value.path("storyKind").asText().equals("body")||value.path("storyKind").asText().equals("physical_document");}
    }
    private static final class Anchor {final int ordinal;final String label,basis;final Member member;Anchor(int ordinal,String label,String basis,Member member){this.ordinal=ordinal;this.label=label;this.basis=basis;this.member=member;}}
}
