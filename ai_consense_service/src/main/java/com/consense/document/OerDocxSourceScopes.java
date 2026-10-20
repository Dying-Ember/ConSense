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
    private static final Pattern SOURCE_NUMBER=Pattern.compile("^\\s*([1-9][0-9]*)\\.(?!\\s*\\()(?=\\s|\\p{L}|$)\\s*");
    private static final Pattern DELIMITED_MAIN=Pattern.compile("^[\\p{Z} \\t]*([1-9][0-9]*)(?:\\.(?=[\\p{Z}\\s]|$)([\\p{Z}\\s]*)|([\\p{Z} ]*\\t[\\p{Z} \\t]*))");
    private static final Pattern TAB_MAIN_WITHOUT_PERIOD=Pattern.compile("^[\\p{Z} ]*([1-9][0-9]*)[\\p{Z} ]*\\t[\\p{Z} \\t]*(?=\\p{L})");
    private static final Pattern GUIDANCE_OPEN=Pattern.compile("^\\s*\\(\\s*Guidance\\s+Notes(?:\\s+to\\s+[^:()\\r\\n]+)?\\s*:\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_ITEM=Pattern.compile("^\\s*([a-z]+)(?:\\.\\s*|\\t+)(?=\\p{L})");
    private static final Pattern ROMAN_ITEM=Pattern.compile("(?=[ivxlcdm]+$)m{0,3}(?:cm|cd|d?c{0,3})(?:xc|xl|l?x{0,3})(?:ix|iv|v?i{0,3})");
    private static final Pattern TAB_DECIMAL=Pattern.compile("^([1-9][0-9]*)\\.[\\p{Z} \\t]*\\t[\\p{Z} \\t]*(?:\\(([a-z]+)\\)[\\p{Z} \\t]*\\t[\\p{Z} \\t]*)?(?=\\p{L})");
    private static final Pattern PAREN_ITEM=Pattern.compile("^[\\p{Z} \\t]*\\(([a-z]+)\\)[\\p{Z} \\t]+(?=\\p{L})");
    private static final Pattern DECIMAL_PREFIX=Pattern.compile("^([1-9][0-9]*)\\.[\\p{Z} \\t]*(?:\\(([a-z]+)\\))?");
    private static final Pattern NAMED_GUIDANCE_OPEN=Pattern.compile("^\\s*\\(\\s*Guidance\\s+Notes?(?:\\s+to\\s+[^:()\\r\\n]+)?\\s*:\\s*$",Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_LIST_LABEL=Pattern.compile("^[\\p{Z}\\s]*([a-z]+)\\.[\\p{Z}\\s]+|^[\\p{Z}\\s]*([-\u2022])[\\p{Z}\\s]+");
    private static final Pattern COMPACT_ROMAN_LABEL=Pattern.compile("^[\\p{Z}\\s]*([ivxlcdm]+)\\.(?=\\p{L})(?!\\p{L}+\\.)");
    private static final Pattern NAMED_SOURCE_FRAME=Pattern.compile("(?i)^(Remarks:|Internal Note:|Annex [1-9][0-9]* to .+)$");
    private static final Pattern COMPOUND_SOURCE_FRAME=Pattern.compile("^[\\p{Z}\\s]*([1-9][0-9]*(?:\\.[1-9][0-9]*)+)\\.[\\p{Z}\\s]+(?=\\p{L})");
    private static final Pattern FORWARD_INCLUDE=Pattern.compile("(?i)\\binclude\\s+(?:all\\s+of\\s+)?the\\s+following\\b");
    private OerDocxSourceScopes() { }

    static ObjectNode observe(JsonNode inventory,JsonNode outline,JsonNode sections) {
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
        Set<String> sectionTransitions=sourceSectionTransitions(sections,members);
        // Template-specific cross-cell/parallel-column rules remain in the shared template projection.
        if(out.path("familyCandidate").isNull())for(ObjectNode chapter:chapters.values()) {
            manualIntervals(chapterMembers.get(chapter),chapter,ranges,deepest,sectionTransitions,false);
        }
        if(out.path("familyCandidate").isNull())tabDelimitedIntervals(members,ranges,deepest);
        if(out.path("familyCandidate").isNull()){
            sectionTransitions.addAll(sourceBlankLiteralTransitions(members));
            ArrayNode refined=ranges.deepCopy();
            for(ObjectNode chapter:chapters.values())explicitSourceRegions(chapterMembers.get(chapter),chapter,refined,deepest);
            for(ObjectNode chapter:chapters.values())manualIntervals(chapterMembers.get(chapter),chapter,refined,deepest,sectionTransitions,true);
            for(ObjectNode chapter:chapters.values()){
                List<JsonNode> flow=chapterMembers.get(chapter);List<JsonNode> named=new ArrayList<>();for(JsonNode region:refined)if("explicit_named_remarks_source_region".equals(region.path("basis").asText())&&chapter.path("id").equals(region.path("parentId")))named.add(region);
                for(JsonNode region:named){List<JsonNode> span=membersFrom(flow,region);manualSequence(span,chapter,(ObjectNode)region,region.path("endBoundary"),refined,deepest,manualCounts(span,chapter,deepest,SOURCE_NUMBER),sectionTransitions,true);}
                bindExplicitRegionParents(refined,chapter);
            }
            for(JsonNode candidate:refined)mergeRange(ranges,(ObjectNode)candidate);
            tabDelimitedIntervals(members,ranges,deepest);
            List<JsonNode> parents=new ArrayList<>();for(JsonNode candidate:ranges)if("manual-sequence".equals(candidate.path("kind").asText())||"source-region".equals(candidate.path("kind").asText())&&candidate.path("basis").asText().matches("explicit_(named|compound|decimal_restart).*"))parents.add(candidate);
            Map<String,JsonNode> byId=new HashMap<>();for(JsonNode member:members)byId.put(member.path("id").asText(),member);
            for(JsonNode parent:parents){List<JsonNode> flow=new ArrayList<>();for(JsonNode id:parent.path("orderedMemberIds"))flow.add(byId.get(id.asText()));refineListIntervals(flow,(ObjectNode)parent,ranges);}
        }
        if(out.path("familyCandidate").isNull()&&ranges.isEmpty())explicitFollowingRegions(members,ranges,sections);
        return out;
    }

    /** A literal forward introduction and unique payload prove only this unoccupied source interval. */
    private static void explicitFollowingRegions(List<JsonNode> flow,ArrayNode ranges,JsonNode sections){
        Set<String> ids=new HashSet<>(),positions=new HashSet<>();List<Integer> roots=new ArrayList<>();Map<java.math.BigInteger,Integer> counts=new HashMap<>();
        for(int n=0;n<flow.size();n++){
            JsonNode member=flow.get(n),position=member.path("sourcePosition");String id=member.path("id").asText();
            if(id.isEmpty()||!ids.add(id)||!positions.add(positionKey(position,position.path("nodePath").asText())))return;
            Matcher marker=tabDecimal(member);if(directBody(member)&&marker!=null&&marker.group(2)==null){roots.add(n);counts.merge(new java.math.BigInteger(marker.group(1)),1,Integer::sum);}
        }
        for(int i=0;i+1<roots.size();i++){
            int from=roots.get(i),end=roots.get(i+1);Matcher root=tabDecimal(flow.get(from)),next=tabDecimal(flow.get(end));
            java.math.BigInteger number=new java.math.BigInteger(root.group(1)),following=new java.math.BigInteger(next.group(1));
            if(!number.add(java.math.BigInteger.ONE).equals(following)||counts.get(number)!=1||counts.get(following)!=1||numbering(flow.get(from)).path("numbered").asBoolean()||numbering(flow.get(end)).path("numbered").asBoolean()||!boundObservedInterval(flow,from,end)||!oneObservedSection(flow,from,end,sections))continue;
            int children=0,introduction=-1,lastChild=-1,payloads=0;boolean valid=true;
            for(int n=from;n<=end&&valid;n++){
                JsonNode member=flow.get(n);JsonNode normal=member.path("textObservation"),raw=member.path("preNormalizedTextObservation");
                if(!normal.path("text").equals(normal.path("originalText"))||!raw.path("text").equals(raw.path("originalText"))||!normal.path("deletedText").asText().isEmpty()||!normal.path("strikeText").asText().isEmpty()||!raw.path("deletedText").asText().isEmpty()||!raw.path("strikeText").asText().isEmpty()){valid=false;break;}
                if(n==from||n==end)continue;
                String label=parenItem(member);
                if(directBody(member)&&label!=null){
                    if(label.length()!=1||label.charAt(0)!='a'+children){valid=false;break;}children++;lastChild=n;
                    Matcher forward=FORWARD_INCLUDE.matcher(text(member));if(forward.find()){if(introduction>=0||forward.find()||text(member).matches("(?s).*[\"'“”‘’].*")){valid=false;break;}introduction=n;}
                }else if(directBody(member)&&(sourceDecimal(member)!=null||sourceListMarker(member))){valid=false;break;}
            }
            if(!valid||children<3||introduction!=lastChild||introduction<0)continue;
            for(int n=from+1;n<introduction;n++)if(directBody(flow.get(n))&&!text(flow.get(n)).isBlank()&&parenItem(flow.get(n))==null)valid=false;
            for(int n=introduction;n<end&&valid;n++){
                JsonNode member=flow.get(n);JsonNode properties=member.path("paragraphProperties");
                if(!directBody(member)||!"outline_not_declared".equals(properties.path("status").asText())||numbering(member).path("numbered").asBoolean()){valid=false;break;}
                if(n>introduction&&!text(member).isBlank()){payloads++;if(parenItem(member)!=null||sourceDecimal(member)!=null||sourceListMarker(member)||FORWARD_INCLUDE.matcher(text(member)).find())valid=false;}
            }
            if(!valid||payloads!=1)continue;
            ObjectNode candidate=range("source-region",number+"("+parenItem(flow.get(introduction))+")",new ArrayList<>(flow.subList(introduction,end)),boundary("next_explicit_number_in_source_sequence",flow.get(end)));
            candidate.put("basis","explicit_forward_introduction_unique_payload_source_interval");candidate.put("hierarchyStatus","source_interval_containment_only");ranges.add(candidate);
        }
    }

    private static boolean oneObservedSection(List<JsonNode> flow,int from,int end,JsonNode inventory){
        if(!"docx-native-sections-v1".equals(inventory.path("version").asText())||!"observed_native_source_sections".equals(inventory.path("status").asText())||!samePart(flow.get(from).path("sourcePosition"),inventory.path("sourcePosition")))return false;
        JsonNode owner=null;for(int n=from;n<=end;n++){
            String path=flow.get(n).path("sourcePosition").path("nodePath").asText();JsonNode found=null;
            for(JsonNode section:inventory.path("sections"))for(JsonNode member:section.path("nativeMemberPaths"))if(path.equals(member.asText())||path.startsWith(member.asText()+"/")){if(found!=null&&found!=section)return false;found=section;}
            if(found==null||owner!=null&&owner!=found)return false;owner=found;
        }return owner!=null;
    }

    private static final class LiteralRoot {
        final int start;final java.math.BigInteger number;final String alpha;boolean valid=true;
        LiteralRoot(int start,Matcher marker){this.start=start;number=new java.math.BigInteger(marker.group(1));alpha=marker.group(2);}
    }

    /** Literal tabs delimit observed source intervals; they do not declare legal peers or hierarchy. */
    private static void tabDelimitedIntervals(List<JsonNode> flow,ArrayNode ranges,Map<String,ObjectNode> deepest){
        Map<Integer,Integer> guidance=namedGuidanceIntervals(flow);boolean[] inGuidance=new boolean[flow.size()];
        for(Map.Entry<Integer,Integer> region:guidance.entrySet())for(int n=region.getKey();n<region.getValue();n++)inGuidance[n]=true;
        List<LiteralRoot> roots=new ArrayList<>();Map<java.math.BigInteger,Integer> counts=new HashMap<>();
        for(int n=0;n<flow.size();n++){
            JsonNode member=flow.get(n);Matcher marker=tabDecimal(member);if(marker==null||!directBody(member)||numbering(member).path("numbered").asBoolean()||inGuidance[n])continue;
            LiteralRoot current=roots.isEmpty()?null:roots.get(roots.size()-1);
            if(marker.group(2)!=null&&!"a".equals(marker.group(2))){
                java.math.BigInteger number=new java.math.BigInteger(marker.group(1));
                if(current==null||!number.equals(current.number)){if(current!=null)current.valid=false;for(LiteralRoot prior:roots)if(number.equals(prior.number))prior.valid=false;}
                continue;
            }
            LiteralRoot root=new LiteralRoot(n,marker);roots.add(root);counts.merge(root.number,1,Integer::sum);
        }
        for(int i=0;i<roots.size();i++){
            LiteralRoot root=roots.get(i);int end=i+1<roots.size()?roots.get(i+1).start:flow.size();
            root.valid&=literalChildren(flow,root,end,inGuidance);ObjectNode existing=manualRange(ranges,flow.get(root.start));
            if(existing!=null&&existingContinuationConflict(flow,root,existing,inGuidance))incomplete(existing,"literal_main_number_or_child_sequence_is_unresolved");
            if(i+1>=roots.size())continue;LiteralRoot next=roots.get(i+1);JsonNode first=flow.get(root.start),last=flow.get(next.start);ObjectNode owner=deepest.get(first.path("id").asText());
            boolean consecutive=root.number.add(java.math.BigInteger.ONE).equals(next.number)&&counts.get(root.number)==1&&counts.get(next.number)==1;
            boolean previous=i>0&&roots.get(i-1).number.add(java.math.BigInteger.ONE).equals(root.number)&&sameStory(flow.get(roots.get(i-1).start),first)&&deepest.get(flow.get(roots.get(i-1).start).path("id").asText())==owner;
            boolean following=i+2<roots.size()&&next.number.add(java.math.BigInteger.ONE).equals(roots.get(i+2).number)&&sameStory(first,flow.get(roots.get(i+2).start))&&deepest.get(last.path("id").asText())==owner&&deepest.get(flow.get(roots.get(i+2).start).path("id").asText())==owner;
            if(owner==null||!consecutive||!previous&&!following)continue;
            boolean preserveComplete=existing!=null&&existing.path("scopeComplete").asBoolean();
            List<JsonNode> span=new ArrayList<>(flow.subList(root.start,end));boolean cross=false;
            for(JsonNode member:span)if(deepest.get(member.path("id").asText())!=owner){cross=true;break;}
            ObjectNode candidate=range("manual-sequence",root.number.toString(),span,boundary("next_explicit_number_in_source_sequence",last));
            candidate.put("basis","explicit_tab_delimited_literal_source_interval");candidate.put("hierarchyStatus","source_interval_containment_only");candidate.put("paragraphStyleId",first.path("paragraphProperties").path("paragraphStyleId").asText());
            if(cross){candidate.putNull("parentId");ArrayNode linked=candidate.putArray("nativeOutlineRangeIds");Set<String> ids=new HashSet<>();for(JsonNode member:span)ids.add(member.path("id").asText());
                for(JsonNode nativeRange:ranges)if("native-outline".equals(nativeRange.path("kind").asText()))for(JsonNode id:nativeRange.path("orderedMemberIds"))if(ids.contains(id.asText())){linked.add(nativeRange.path("id").asText());break;}
            }else candidate.put("parentId",owner.path("id").asText());
            if(!root.valid)incomplete(candidate,"literal_main_number_or_child_sequence_is_unresolved");
            if(!boundObservedInterval(flow,root.start,end))incomplete(candidate,"literal_source_interval_members_are_not_complete_and_bound");
            if(cross&&!crossOutlineInterval(flow,root.start,end,guidance,inGuidance))incomplete(candidate,"cross_native_outline_literal_continuation_is_unresolved");
            if(preserveComplete&&(!candidate.path("scopeComplete").asBoolean()||!provedTruncatedPrefix(existing,candidate,flow,root.start,end,guidance,inGuidance)))continue;
            if(existing==null){ranges.add(candidate);nativeListIntervals(span,candidate,ranges);}else{existing.removeAll();existing.setAll(candidate);}
        }
    }

    private static ObjectNode manualRange(ArrayNode ranges,JsonNode first){for(JsonNode candidate:ranges)if("manual-sequence".equals(candidate.path("kind").asText())&&first.path("id").equals(candidate.path("orderedMemberIds").path(0)))return (ObjectNode)candidate;return null;}
    private static Matcher tabDecimal(JsonNode member){String raw=rawText(member);if(raw==null)return null;Matcher match=TAB_DECIMAL.matcher(raw);return match.find()?match:null;}
    private static String parenItem(JsonNode member){String raw=rawText(member);if(raw==null)return null;Matcher match=PAREN_ITEM.matcher(raw);return match.find()?match.group(1):null;}

    private static boolean existingContinuationConflict(List<JsonNode> flow,LiteralRoot root,JsonNode existing,boolean[] inGuidance){
        Set<String> ids=new HashSet<>();for(JsonNode id:existing.path("orderedMemberIds"))ids.add(id.asText());
        List<JsonNode> original=new ArrayList<>();List<Boolean> regions=new ArrayList<>();boolean continuation=false;
        for(int n=0;n<flow.size();n++)if(ids.contains(flow.get(n).path("id").asText())){
            JsonNode member=flow.get(n);original.add(member);regions.add(inGuidance[n]);
            String raw=rawText(member);if(original.size()>1&&!inGuidance[n]&&directBody(member)&&raw!=null){Matcher prefix=DECIMAL_PREFIX.matcher(raw);if(prefix.find()&&prefix.group(2)!=null&&root.number.equals(new java.math.BigInteger(prefix.group(1))))continuation=true;}
        }
        if(!continuation||original.isEmpty())return false;Matcher first=tabDecimal(original.get(0));if(first==null)return false;
        boolean[] local=new boolean[original.size()];for(int n=0;n<local.length;n++)local[n]=regions.get(n);
        return !literalChildren(original,new LiteralRoot(0,first),original.size(),local);
    }

    private static boolean provedTruncatedPrefix(JsonNode existing,JsonNode candidate,List<JsonNode> flow,int from,int end,Map<Integer,Integer> guidance,boolean[] inGuidance){
        JsonNode old=existing.path("orderedMemberIds"),complete=candidate.path("orderedMemberIds");
        if(old.isEmpty()||old.size()>=complete.size()||!"next_native_outline_heading".equals(existing.path("endBoundary").path("kind").asText()))return false;
        for(int n=0;n<old.size();n++)if(!old.get(n).equals(complete.get(n)))return false;
        int stopped=from+old.size();if(stopped>=end||!flow.get(stopped).path("sourcePosition").equals(existing.path("endBoundary").path("sourcePosition")))return false;
        if(inGuidance[stopped])for(Map.Entry<Integer,Integer> region:guidance.entrySet())if(region.getKey()>=from&&region.getKey()<stopped&&stopped<region.getValue())return true;
        if(!text(flow.get(stopped)).isBlank())return false;
        int before=stopped-1,after=stopped+1;while(before>from&&text(flow.get(before)).isBlank())before--;while(after<end&&text(flow.get(after)).isBlank())after++;
        String a=parenItem(flow.get(before)),b=after<end?parenItem(flow.get(after)):null;return a!=null&&b!=null&&a.length()==1&&b.length()==1&&a.charAt(0)+1==b.charAt(0);
    }

    private static boolean literalChildren(List<JsonNode> flow,LiteralRoot root,int end,boolean[] inGuidance){
        int child=root.alpha==null?0:1;
        for(int n=root.start+1;n<end;n++){
            JsonNode member=flow.get(n);if(inGuidance[n]||!directBody(member))continue;
            Matcher marker=tabDecimal(member);String label;
            if(marker!=null){if(!root.number.equals(new java.math.BigInteger(marker.group(1))))return false;label=marker.group(2);if(label==null)return false;}
            else{String raw=rawText(member);if(raw!=null&&DECIMAL_PREFIX.matcher(raw).find())return false;label=parenItem(member);}
            if(label==null)continue;
            if(label.length()==1&&label.charAt(0)=='a'+child){child++;continue;}
            if(child>0&&"i".equals(label)){int last=parenthesizedRomanEnd(flow,n,end,inGuidance);if(last>=n){n=last;continue;}}
            return false;
        }
        return true;
    }
    private static int parenthesizedRomanEnd(List<JsonNode> flow,int from,int end,boolean[] inGuidance){
        int expected=1,last=from-1;for(int n=from;n<end;n++){
            if(inGuidance[n]||!directBody(flow.get(n)))return -1;if(tabDecimal(flow.get(n))!=null)break;
            String label=parenItem(flow.get(n));if(label==null)continue;if(romanOrdinal(label)!=expected)break;expected++;last=n;
        }return expected>2?last:-1;
    }

    private static Map<Integer,Integer> namedGuidanceIntervals(List<JsonNode> flow){
        Map<Integer,Integer> out=new LinkedHashMap<>();for(int i=0;i<flow.size();i++){
            JsonNode first=flow.get(i);if(!directBody(first)||!NAMED_GUIDANCE_OPEN.matcher(text(first)).matches())continue;
            int balance=0;boolean valid=true;for(int n=i;n<flow.size()&&valid;n++){
                JsonNode member=flow.get(n);String raw=rawText(member);if(!directBody(member)||!usable(member)||!sameStory(first,member)||raw==null||n>i&&NAMED_GUIDANCE_OPEN.matcher(text(member)).matches()){valid=false;break;}
                for(int c=0;c<raw.length();c++){
                    char value=raw.charAt(c);if(value=='(')balance++;else if(value==')')balance--;
                    if(balance<0||value==')'&&balance==0&&c+1<raw.length()&&!raw.substring(c+1).isBlank()){valid=false;break;}
                }
                if(valid&&balance==0){out.put(i,n+1);i=n;break;}
            }
            if(!valid||balance!=0)return out;
        }return out;
    }

    private static boolean boundObservedInterval(List<JsonNode> flow,int from,int end){
        JsonNode first=flow.get(from);int ordinal=-1;for(int n=from;n<=end;n++){
            JsonNode member=flow.get(n),observed=member.path("ordinalZeroBased");
            if(!usable(member)||!sameStory(first,member)||rawText(member)==null||!observed.isIntegralNumber()||!observed.canConvertToInt()||observed.asInt()<0||ordinal>=0&&observed.asInt()!=(long)ordinal+1)return false;
            ordinal=observed.asInt();
        }return true;
    }

    private static boolean crossOutlineInterval(List<JsonNode> flow,int from,int end,Map<Integer,Integer> guidance,boolean[] inGuidance){
        for(int n=from+1;n<end;n++){
            JsonNode member=flow.get(n),properties=member.path("paragraphProperties");
            if(!directBody(member)||!"resolved".equals(properties.path("status").asText())||properties.path("outlineLevel").asInt(9)>=9||inGuidance[n])continue;
            if(!text(member).isBlank())return false;
            int before=n-1,after=n+1;while(before>from&&text(flow.get(before)).isBlank())before--;while(after<end&&text(flow.get(after)).isBlank())after++;
            String a=parenItem(flow.get(before)),b=after<end?parenItem(flow.get(after)):null;
            if(a!=null&&b!=null&&a.length()==1&&b.length()==1&&a.charAt(0)+1==b.charAt(0))continue;
            boolean table=false;for(Map.Entry<Integer,Integer> region:guidance.entrySet())if(region.getValue()-1==before&&after<end&&flow.get(after).has("cellNodePath")){
                int intro=region.getKey()-1;while(intro>from&&text(flow.get(intro)).isBlank())intro--;table=parenItem(flow.get(intro))!=null&&text(flow.get(intro)).endsWith(":");break;
            }
            if(!table)return false;
        }return true;
    }

    private static void manualIntervals(List<JsonNode> flow,ObjectNode chapter,ArrayNode ranges,Map<String,ObjectNode> deepest,Set<String> sectionTransitions,boolean refined) {
        Pattern marker=refined?SOURCE_NUMBER:MANUAL_NUMBER;
        List<int[]> guidance=guidanceRegions(flow,chapter,deepest);List<JsonNode> outside=new ArrayList<>();int offset=0;
        for(int[] region:guidance){outside.addAll(flow.subList(offset,region[0]));offset=region[1];}outside.addAll(flow.subList(offset,flow.size()));
        Map<String,Integer> bodyCounts=manualCounts(refined?withoutSourceMembers(outside,sourceMask(ranges,chapter)):outside,chapter,deepest,marker);offset=0;
        for(int[] region:guidance){
            manualSequence(flow.subList(offset,region[0]),chapter,chapter,boundary("next_explicit_source_region",flow.get(region[0])),ranges,deepest,bodyCounts,sectionTransitions,refined);
            List<JsonNode> span=new ArrayList<>(flow.subList(region[0],region[1]));JsonNode end=region[1]<flow.size()?boundary("explicit_source_region_end",flow.get(region[1])):chapter.path("endBoundary");
            ObjectNode parent=range("source-region",text(span.get(0)),span,end);parent.put("basis","explicit_balanced_parenthetical_guidance_region");parent.put("hierarchyStatus","source_interval_containment_only");parent.put("parentId",chapter.path("id").asText());ranges.add(parent);
            manualSequence(span,chapter,parent,end,ranges,deepest,manualCounts(span,chapter,deepest,marker),sectionTransitions,refined);offset=region[1];
        }
        manualSequence(flow.subList(offset,flow.size()),chapter,chapter,chapter.path("endBoundary"),ranges,deepest,bodyCounts,sectionTransitions,refined);
    }

    private static List<int[]> guidanceRegions(List<JsonNode> flow,ObjectNode chapter,Map<String,ObjectNode> deepest){
        List<int[]> out=new ArrayList<>();
        for(int i=0;i<flow.size();i++){
            JsonNode first=flow.get(i);if(!manualMember(first,chapter,deepest)||!GUIDANCE_OPEN.matcher(text(first)).matches())continue;
            int balance=0,end=-1;boolean valid=true;
            for(int n=i;n<flow.size()&&valid;n++){
                JsonNode member=flow.get(n);if(!manualMember(member,chapter,deepest)||!sameStory(first,member)){valid=false;break;}
                String value=text(member);if(n>i&&GUIDANCE_OPEN.matcher(value).matches()){valid=false;break;}
                for(int c=0;c<value.length();c++){
                    if(value.charAt(c)=='(')balance++;else if(value.charAt(c)==')')balance--;
                    if(balance<0||value.charAt(c)==')'&&balance==0&&c+1<value.length()&&!value.substring(c+1).isBlank()){valid=false;break;}
                }
                if(valid&&balance==0){end=n+1;break;}
            }
            if(!valid||end<0)return out;
            List<JsonNode> starts=new ArrayList<>();for(int n=i+1;n<end;n++)if(MANUAL_NUMBER.matcher(text(flow.get(n))).find())starts.add(flow.get(n));
            boolean consecutive=false;for(int n=1;n<starts.size();n++)if(manualNumber(starts.get(n-1)).add(java.math.BigInteger.ONE).equals(manualNumber(starts.get(n)))&&samePeer(starts.get(n-1),starts.get(n)))consecutive=true;
            if(consecutive){out.add(new int[]{i,end});i=end-1;}
        }
        return out;
    }

    private static Map<String,Integer> manualCounts(List<JsonNode> flow,ObjectNode chapter,Map<String,ObjectNode> deepest,Pattern marker){
        if(marker==SOURCE_NUMBER){Map<String,Integer> counts=new HashMap<>();Set<String> nativePeers=nativeDecimalPeers(flow,chapter,deepest);for(JsonNode member:flow)if(sourceRootMember(member,chapter,deepest,nativePeers)){java.math.BigInteger number=sourceDecimal(member);if(number!=null)counts.merge(number.toString(),1,Integer::sum);}return counts;}
        Map<String,Integer> counts=new HashMap<>();for(JsonNode member:flow)if(manualMember(member,chapter,deepest)){Matcher match=marker.matcher(text(member));if(match.find())counts.merge(match.group(1),1,Integer::sum);}return counts;
    }
    private static boolean manualMember(JsonNode member,ObjectNode chapter,Map<String,ObjectNode> deepest){return deepest.get(member.path("id").asText())==chapter&&directBody(member)&&usable(member)&&!numbering(member).path("numbered").asBoolean();}
    private static java.math.BigInteger manualNumber(JsonNode member){Matcher match=MANUAL_NUMBER.matcher(text(member));if(!match.find())throw new IllegalArgumentException("Missing explicit source number");return new java.math.BigInteger(match.group(1));}

    private static void explicitSourceRegions(List<JsonNode> flow,ObjectNode chapter,ArrayNode ranges,Map<String,ObjectNode> deepest){
        List<Integer> named=new ArrayList<>();for(int n=0;n<flow.size();n++)if(deepest.get(flow.get(n).path("id").asText())==chapter&&directBody(flow.get(n))&&rawText(flow.get(n))!=null&&NAMED_SOURCE_FRAME.matcher(rawText(flow.get(n)).strip()).matches())named.add(n);
        for(int i=0;i<named.size();i++){
            int from=named.get(i),end=i+1<named.size()?named.get(i+1):flow.size();String title=rawText(flow.get(from)).strip();boolean remarks=title.equalsIgnoreCase("Remarks:");
            for(int n=from+1;n<end;n++)if(directBody(flow.get(n))&&Optional.ofNullable(rawText(flow.get(n))).orElse("").strip().matches("(?i)APPENDIX\\s+\\S+")){end=n;break;}
            if(remarks){List<java.math.BigInteger> starts=new ArrayList<>();for(int n=from+1;n<end;n++)if(directBody(flow.get(n))){java.math.BigInteger number=sourceDecimal(flow.get(n));if(number!=null)starts.add(number);}if(starts.size()<2||!starts.get(0).equals(java.math.BigInteger.ONE)||!starts.get(1).equals(java.math.BigInteger.TWO))continue;}
            mergeRange(ranges,explicitRegion(flow,from,end,chapter,remarks?"explicit_named_remarks_source_region":"explicit_named_detached_source_region"));
        }
        Map<String,List<Integer>> groups=new LinkedHashMap<>();Map<Integer,String> labels=new HashMap<>();
        for(int n=0;n<flow.size();n++)if(directBody(flow.get(n))&&rawText(flow.get(n))!=null){Matcher marker=COMPOUND_SOURCE_FRAME.matcher(rawText(flow.get(n)));if(marker.find()){String label=marker.group(1);groups.computeIfAbsent(label.substring(0,label.lastIndexOf('.')),key->new ArrayList<>()).add(n);labels.put(n,label);}}
        for(Map.Entry<String,List<Integer>> group:groups.entrySet()){
            List<Integer> starts=group.getValue();if(starts.size()<3||group.getKey().contains("."))continue;boolean consecutive=true;
            for(int n=1;n<starts.size();n++)if(!compoundSuffix(labels.get(starts.get(n-1))).add(java.math.BigInteger.ONE).equals(compoundSuffix(labels.get(starts.get(n)))))consecutive=false;if(!consecutive)continue;
            for(int i=0;i+1<starts.size();i++){
                int from=starts.get(i),end=starts.get(i+1),fresh=-1;java.math.BigInteger root=null;
                for(int n=from+1;n<end;n++)if(directBody(flow.get(n))&&!numbering(flow.get(n)).path("numbered").asBoolean()){java.math.BigInteger number=sourceDecimal(flow.get(n));if(number!=null&&!number.toString().equals(group.getKey())){fresh=n;root=number;break;}}
                if(fresh<0)continue;List<Integer> children=groups.getOrDefault(root.toString(),Collections.emptyList()).stream().filter(n->n>from&&n<end).toList();if(children.size()<2)continue;boolean childSequence=true;for(int n=1;n<children.size();n++)if(!compoundSuffix(labels.get(children.get(n-1))).add(java.math.BigInteger.ONE).equals(compoundSuffix(labels.get(children.get(n)))))childSequence=false;if(!childSequence)continue;
                ObjectNode compound=explicitRegion(flow,from,end,chapter,"explicit_compound_label_source_region");mergeRange(ranges,compound);ObjectNode restarted=explicitRegion(flow,fresh,end,compound,"explicit_decimal_restart_inside_compound_source_region");mergeRange(ranges,restarted);
                for(int n=0;n<children.size();n++)mergeRange(ranges,explicitRegion(flow,children.get(n),n+1<children.size()?children.get(n+1):end,restarted,"explicit_compound_label_source_region"));
            }
        }
    }
    private static java.math.BigInteger compoundSuffix(String label){return new java.math.BigInteger(label.substring(label.lastIndexOf('.')+1));}
    private static ObjectNode explicitRegion(List<JsonNode> flow,int from,int end,ObjectNode parent,String basis){ObjectNode region=range("source-region",text(flow.get(from)),new ArrayList<>(flow.subList(from,end)),end<flow.size()?boundary("next_explicit_source_region",flow.get(end)):parent.path("endBoundary"));region.put("basis",basis);region.put("hierarchyStatus","source_interval_containment_only");region.put("parentId",parent.path("id").asText());if(!boundSourceSlice(flow,from,end,parent))incomplete(region,"explicit_source_frame_members_or_boundary_are_not_bound");return region;}
    private static Set<String> sourceMask(ArrayNode ranges,JsonNode owner){Set<String> ids=new HashSet<>();for(JsonNode region:ranges)if(!region.path("id").equals(owner.path("id"))&&"source-region".equals(region.path("kind").asText())&&region.path("scopeComplete").asBoolean()&&region.path("basis").asText().matches("explicit_(named|compound|decimal_restart).*"))for(JsonNode id:region.path("orderedMemberIds"))ids.add(id.asText());return ids;}
    private static List<JsonNode> withoutSourceMembers(List<JsonNode> flow,Set<String> excluded){if(excluded.isEmpty())return flow;List<JsonNode> out=new ArrayList<>();for(JsonNode member:flow)if(!excluded.contains(member.path("id").asText()))out.add(member);return out;}
    private static List<JsonNode> membersFrom(List<JsonNode> flow,JsonNode region){Set<String> ids=new HashSet<>();for(JsonNode id:region.path("orderedMemberIds"))ids.add(id.asText());List<JsonNode> out=new ArrayList<>();for(JsonNode member:flow)if(ids.contains(member.path("id").asText()))out.add(member);return out;}
    private static void bindExplicitRegionParents(ArrayNode ranges,ObjectNode chapter){for(JsonNode region:ranges)if(chapter.path("id").equals(region.path("parentId"))&&("explicit_named_remarks_source_region".equals(region.path("basis").asText())||"explicit_compound_label_source_region".equals(region.path("basis").asText()))){ObjectNode smallest=null;Set<String> wanted=new HashSet<>();for(JsonNode id:region.path("orderedMemberIds"))wanted.add(id.asText());for(JsonNode candidate:ranges)if("manual-sequence".equals(candidate.path("kind").asText())){Set<String> observed=new HashSet<>();for(JsonNode id:candidate.path("orderedMemberIds"))observed.add(id.asText());if(observed.containsAll(wanted)&&(smallest==null||observed.size()<smallest.path("orderedMemberIds").size()))smallest=(ObjectNode)candidate;}if(smallest!=null)((ObjectNode)region).put("parentId",smallest.path("id").asText());}}

    private static void manualSequence(List<JsonNode> flow,ObjectNode chapter,ObjectNode owner,JsonNode sequenceEnd,ArrayNode ranges,Map<String,ObjectNode> deepest,Map<String,Integer> counts,Set<String> sectionTransitions,boolean refined) {
        List<Integer> starts=new ArrayList<>();List<java.math.BigInteger> numbers=new ArrayList<>();
        Set<String> excluded=refined?sourceMask(ranges,owner):Collections.emptySet();Set<String> nativePeers=refined?nativeDecimalPeers(withoutSourceMembers(flow,excluded),chapter,deepest):Collections.emptySet();
        for(int i=0;i<flow.size();i++) {
            JsonNode member=flow.get(i);if(refined){if(!excluded.contains(member.path("id").asText())&&sourceRootMember(member,chapter,deepest,nativePeers)){java.math.BigInteger number=sourceDecimal(member);if(number!=null){starts.add(i);numbers.add(number);}}continue;}if(!manualMember(member,chapter,deepest))continue;
            Matcher match=(refined?SOURCE_NUMBER:MANUAL_NUMBER).matcher(text(member));if(match.find()){starts.add(i);numbers.add(new java.math.BigInteger(match.group(1)));}
        }
        boolean[] physical=physicalIntervals(flow,starts,numbers,chapter,deepest),delimited=refined?delimitedIntervals(flow,starts,numbers,chapter,deepest,counts):new boolean[starts.size()];
        for(int i=0;i<starts.size();i++) {
            JsonNode first=flow.get(starts.get(i));String style=first.path("paragraphProperties").path("paragraphStyleId").asText();
            boolean peerNext=i+1<starts.size()&&numbers.get(i).add(java.math.BigInteger.ONE).equals(numbers.get(i+1))&&samePeer(first,flow.get(starts.get(i+1)));
            boolean peerPrevious=i>0&&numbers.get(i-1).add(java.math.BigInteger.ONE).equals(numbers.get(i))&&samePeer(first,flow.get(starts.get(i-1)));
            boolean originalNext=peerNext||physical[i]&&i+1<starts.size(),originalPrevious=peerPrevious||physical[i]&&i>0;
            boolean next=originalNext||delimited[i],previous=originalPrevious||i>0&&delimited[i-1];
            if(!next&&!previous)continue;
            int end=i+1<starts.size()?starts.get(i+1):flow.size();boolean nativeBoundary=false;
            boolean detached=false;if(refined)for(int n=starts.get(i)+1;n<end;n++){boolean found=false;for(JsonNode region:ranges)if("explicit_named_detached_source_region".equals(region.path("basis").asText())&&region.path("orderedMemberIds").path(0).equals(flow.get(n).path("id"))){found=true;break;}if(found){end=n;detached=true;break;}}
            for(int n=starts.get(i)+1;n<end;n++)if(directBody(flow.get(n))&&deepest.get(flow.get(n).path("id").asText())!=chapter&&"resolved".equals(flow.get(n).path("paragraphProperties").path("status").asText())&&flow.get(n).path("paragraphProperties").path("outlineLevel").asInt(9)<9){end=n;nativeBoundary=true;break;}
            List<JsonNode> span=new ArrayList<>(flow.subList(starts.get(i),end));
            JsonNode boundary=end<flow.size()?boundary(nativeBoundary?"next_native_outline_heading":detached?"next_explicit_source_region":"next_explicit_number_in_source_sequence",flow.get(end)):sequenceEnd;
            boolean nativeMain=refined&&numbering(first).path("numbered").asBoolean();ObjectNode parent=range(nativeMain?"native-list":"manual-sequence",nativeMain?numbering(first).path("label").asText():numbers.get(i).toString(),span,boundary);if(nativeMain)parent.set("numbering",numbering(first).deepCopy());
            boolean physicalUsed=!peerNext&&(next||!peerPrevious&&previous),crossStyle=peerNext&&!sameStyle(first,flow.get(starts.get(i+1)))||peerPrevious&&!sameStyle(first,flow.get(starts.get(i-1)));
            boolean delimitedUsed=!originalNext&&delimited[i]||!originalNext&&!originalPrevious&&i>0&&delimited[i-1];
            parent.put("basis",delimitedUsed?"explicit_consecutive_literal_markers_in_same_native_source_owner":physicalUsed?"physical_decimal_source_interval_with_literal_alpha_restart":crossStyle?"explicit_consecutive_decimal_markers_with_identical_resolved_source_indentation":"explicit_consecutive_decimal_markers_in_same_native_heading_and_paragraph_style");
            parent.put("hierarchyStatus","source_interval_containment_only");parent.put("parentId",owner.path("id").asText());parent.put("paragraphStyleId",style);
            if(i+1<starts.size()&&!next)incomplete(parent,"next_explicit_number_is_not_a_unique_consecutive_peer");
            if(counts.get(numbers.get(i).toString())!=1)incomplete(parent,"duplicate_explicit_number_in_same_source_sequence");
            if(emptyNativeOutlineBoundary(boundary,ranges,sectionTransitions))incomplete(parent,"empty_native_outline_heading_does_not_close_manual_source_scope");
            ranges.add(parent);nativeListIntervals(span,parent,ranges);
        }
    }
    /** A bounded literal sequence proves physical intervals, regardless of paragraph style or legal hierarchy. */
    private static boolean[] delimitedIntervals(List<JsonNode> flow,List<Integer> starts,List<java.math.BigInteger> numbers,ObjectNode chapter,Map<String,ObjectNode> deepest,Map<String,Integer> counts){
        boolean[] out=new boolean[starts.size()],bound=new boolean[starts.size()],tabs=new boolean[starts.size()];
        for(int i=0;i+1<starts.size();i++){
            if(!numbers.get(i).add(java.math.BigInteger.ONE).equals(numbers.get(i+1))||counts.get(numbers.get(i).toString())!=1||counts.get(numbers.get(i+1).toString())!=1)continue;
            int from=starts.get(i),end=starts.get(i+1);String first=rawText(flow.get(from)),next=rawText(flow.get(end));
            if(first==null||next==null)continue;Matcher a=DELIMITED_MAIN.matcher(first),b=DELIMITED_MAIN.matcher(next);
            if(!a.find()||!b.find()||!numbers.get(i).equals(new java.math.BigInteger(a.group(1)))||!numbers.get(i+1).equals(new java.math.BigInteger(b.group(1)))||!boundObservedInterval(flow,from,end))continue;
            tabs[i]=Optional.ofNullable(a.group(2)).orElse(Optional.ofNullable(a.group(3)).orElse("")).indexOf('\t')>=0||numbering(flow.get(from)).path("numbered").asBoolean();tabs[i+1]=Optional.ofNullable(b.group(2)).orElse(Optional.ofNullable(b.group(3)).orElse("")).indexOf('\t')>=0||numbering(flow.get(end)).path("numbered").asBoolean();
            boolean owner=true;for(int n=from;n<=end;n++)if(deepest.get(flow.get(n).path("id").asText())!=chapter){owner=false;break;}
            bound[i]=owner;
        }
        for(int i=0;i+1<out.length;i++){
            boolean tab=tabs[i]||tabs[i+1];
            out[i]=bound[i]&&(i>0&&bound[i-1]&&(tab||tabs[i-1])||i+2<out.length&&bound[i+1]&&(tab||tabs[i+2]));
        }
        return out;
    }
    private static java.math.BigInteger sourceDecimal(JsonNode member){JsonNode number=numbering(member);if(number.path("numbered").asBoolean()){if(!"resolved".equals(number.path("resolutionStatus").asText())||!"decimal".equals(number.path("format").asText())||!number.path("label").asText().matches("[1-9][0-9]*\\."))return null;return new java.math.BigInteger(number.path("label").asText().replace(".",""));}Matcher normal=SOURCE_NUMBER.matcher(text(member));if(normal.find())return new java.math.BigInteger(normal.group(1));String raw=rawText(member);if(raw!=null){Matcher marker=TAB_MAIN_WITHOUT_PERIOD.matcher(raw);if(marker.find())return new java.math.BigInteger(marker.group(1));}return null;}
    private static boolean sourceRootMember(JsonNode member,ObjectNode chapter,Map<String,ObjectNode> deepest,Set<String> nativePeers){return deepest.get(member.path("id").asText())==chapter&&directBody(member)&&usable(member)&&(!numbering(member).path("numbered").asBoolean()||nativePeers.contains(member.path("id").asText()));}
    private static Set<String> nativeDecimalPeers(List<JsonNode> flow,ObjectNode chapter,Map<String,ObjectNode> deepest){List<JsonNode> candidates=new ArrayList<>();List<java.math.BigInteger> values=new ArrayList<>();Map<java.math.BigInteger,Integer> counts=new HashMap<>();for(JsonNode member:flow)if(deepest.get(member.path("id").asText())==chapter&&directBody(member)&&usable(member)){java.math.BigInteger number=sourceDecimal(member);if(number!=null){candidates.add(member);values.add(number);counts.merge(number,1,Integer::sum);}}Set<String> peers=new HashSet<>();for(int i=0;i<candidates.size();){int end=i+1;while(end<candidates.size()&&values.get(end-1).add(java.math.BigInteger.ONE).equals(values.get(end)))end++;boolean nativeSeen=false,literalSeen=false,unique=true;for(int n=i;n<end;n++){nativeSeen|=numbering(candidates.get(n)).path("numbered").asBoolean();literalSeen|=!numbering(candidates.get(n)).path("numbered").asBoolean();unique&=counts.get(values.get(n))==1;}if(end-i>=3&&nativeSeen&&literalSeen&&unique)for(int n=i;n<end;n++)if(numbering(candidates.get(n)).path("numbered").asBoolean())peers.add(candidates.get(n).path("id").asText());i=end;}return peers;}
    private static boolean emptyNativeOutlineBoundary(JsonNode end,ArrayNode ranges,Set<String> sectionTransitions){
        if(!"next_native_outline_heading".equals(end.path("kind").asText()))return false;
        if(sectionTransitions.contains(positionKey(end.path("sourcePosition"),end.path("sourcePosition").path("nodePath").asText())))return false;
        for(JsonNode heading:ranges)if("native-outline".equals(heading.path("kind").asText())&&heading.path("reference").asText().isBlank()&&heading.path("startBoundary").path("sourcePosition").equals(end.path("sourcePosition")))return true;
        return false;
    }
    private static Set<String> sourceBlankLiteralTransitions(List<JsonNode> members){
        Set<String> out=new HashSet<>();
        for(int i=1;i<members.size();i++){
            JsonNode empty=members.get(i),before=members.get(i-1);String raw=rawText(before);if(raw==null||!TAB_MAIN_WITHOUT_PERIOD.matcher(raw).find()||!directBody(before)||!text(empty).isBlank()||!"resolved".equals(empty.path("paragraphProperties").path("status").asText())||empty.path("paragraphProperties").path("outlineLevel").asInt(9)>=9)continue;
            int end=i;while(end<members.size()&&directBody(members.get(end))&&text(members.get(end)).isBlank())end++;
            if(end>=members.size())continue;JsonNode heading=members.get(end);if(!directBody(heading)||!"resolved".equals(heading.path("paragraphProperties").path("status").asText())||heading.path("paragraphProperties").path("outlineLevel").asInt(9)>=9||!sameStory(before,heading)||!boundObservedInterval(members,i-1,end))continue;
            out.add(positionKey(empty.path("sourcePosition"),empty.path("sourcePosition").path("nodePath").asText()));
        }
        return out;
    }
    /** Reuses the native section observation; no XML reparsing or inferred section/layout state. */
    private static Set<String> sourceSectionTransitions(JsonNode sections,List<JsonNode> members){
        Set<String> out=new HashSet<>();if(!"docx-native-sections-v1".equals(sections.path("version").asText())||!"observed_native_source_sections".equals(sections.path("status").asText()))return out;
        Map<String,JsonNode> exact=new HashMap<>();for(JsonNode member:members){JsonNode position=member.path("sourcePosition");String key=positionKey(position,position.path("nodePath").asText());if(exact.containsKey(key))exact.put(key,null);else exact.put(key,member);}
        String suffix="/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}pPr[1]/{http://schemas.openxmlformats.org/wordprocessingml/2006/main}sectPr[1]";
        JsonNode rows=sections.path("sections");for(int i=0;i+1<rows.size();i++){
            JsonNode current=rows.get(i),next=rows.get(i+1),end=current.path("endBoundary"),start=next.path("startBoundary"),position=end.path("sourcePosition");String path=position.path("nodePath").asText();
            if(!"paragraph_sectPr".equals(end.path("kind").asText())||!"after_paragraph_sectPr".equals(start.path("kind").asText())||!position.equals(start.path("sourcePosition"))||!samePart(position,sections.path("sourcePosition"))||!path.endsWith(suffix))continue;
            String paragraph=path.substring(0,path.length()-suffix.length());JsonNode blank=exact.get(positionKey(position,paragraph));
            if(blank==null||!directBody(blank)||!usable(blank)||!text(blank).isBlank()||!"resolved".equals(blank.path("paragraphProperties").path("status").asText())||blank.path("paragraphProperties").path("outlineLevel").asInt(9)>=9||!blank.path("ordinalZeroBased").isIntegralNumber()||!blank.path("ordinalZeroBased").canConvertToInt())continue;
            JsonNode priorPaths=current.path("nativeMemberPaths");if(priorPaths.isEmpty()||!paragraph.equals(priorPaths.path(priorPaths.size()-1).asText()))continue;
            int ordinal=blank.path("ordinalZeroBased").asInt(),level=blank.path("paragraphProperties").path("outlineLevel").asInt();
            for(JsonNode nativePath:next.path("nativeMemberPaths")){
                JsonNode member=exact.get(positionKey(position,nativePath.asText()));if(member==null||!directBody(member)||!usable(member)||!sameStory(blank,member)||!member.path("ordinalZeroBased").isIntegralNumber()||!member.path("ordinalZeroBased").canConvertToInt()||member.path("ordinalZeroBased").asInt()!=ordinal+1)break;
                ordinal=member.path("ordinalZeroBased").asInt();if(text(member).isBlank())continue;
                JsonNode properties=member.path("paragraphProperties");if("resolved".equals(properties.path("status").asText())&&properties.path("outlineLevel").isIntegralNumber()&&properties.path("outlineLevel").asInt(9)<=level)out.add(positionKey(position,paragraph));break;
            }
        }
        return out;
    }

    /** Proves observed physical delimiters only; indentation and legal peer/parent status remain unknown. */
    private static boolean[] physicalIntervals(List<JsonNode> flow,List<Integer> starts,List<java.math.BigInteger> numbers,ObjectNode chapter,Map<String,ObjectNode> deepest){
        boolean[] out=new boolean[starts.size()];if(starts.size()<3)return out;
        for(int i=1;i<numbers.size();i++)if(!numbers.get(i-1).add(java.math.BigInteger.ONE).equals(numbers.get(i)))return out;
        for(int i=0;i+2<starts.size();i++)if(!samePeer(flow.get(starts.get(i)),flow.get(starts.get(i+1)))&&literalAlphaRun(flow,starts.get(i),starts.get(i+1),chapter,deepest)&&literalAlphaRun(flow,starts.get(i+1),starts.get(i+2),chapter,deepest)){out[i]=true;out[i+1]=true;}
        return out;
    }
    private static boolean literalAlphaRun(List<JsonNode> flow,int from,int end,ObjectNode chapter,Map<String,ObjectNode> deepest){
        JsonNode first=flow.get(from);int ordinal=-1;
        for(int n=from;n<=end;n++){
            JsonNode member=flow.get(n),observed=member.path("ordinalZeroBased");
            if(!manualMember(member,chapter,deepest)||!sameStory(first,member)||!observed.isIntegralNumber()||!observed.canConvertToInt()||ordinal>=0&&observed.asInt()!=ordinal+1||rawText(member)==null)return false;
            ordinal=observed.asInt();
        }
        int count=0;
        for(int n=from+1;n<end;n++){
            String label=literalItem(flow.get(n));if(label==null){if(count==0&&!text(flow.get(n)).isBlank())return false;continue;}
            if(label.length()==1&&label.charAt(0)=='a'+count){count++;continue;}
            if(count>0&&label.equals("i")){int last=literalRomanEnd(flow,n,end);if(last>=n){n=last;continue;}}
            return false;
        }
        return count>=2;
    }
    private static int literalRomanEnd(List<JsonNode> flow,int from,int end){
        int expected=1,last=from-1;for(int n=from;n<end;n++){String label=literalItem(flow.get(n));if(label==null)continue;if(romanOrdinal(label)!=expected)break;expected++;last=n;}return expected>2?last:-1;
    }
    private static int romanOrdinal(String value){
        if(!ROMAN_ITEM.matcher(value).matches())return -1;int out=0,previous=0;for(int n=value.length()-1;n>=0;n--){int current;switch(value.charAt(n)){case 'i':current=1;break;case 'v':current=5;break;case 'x':current=10;break;case 'l':current=50;break;case 'c':current=100;break;case 'd':current=500;break;default:current=1000;}out+=current<previous?-current:current;previous=current;}return out;
    }
    private static String literalItem(JsonNode member){String raw=rawText(member);if(raw==null)return null;Matcher match=LITERAL_ITEM.matcher(raw);return match.find()?match.group(1):null;}
    private static String rawText(JsonNode member){JsonNode raw=member.path("preNormalizedTextObservation");return "observed_parser_channels_before_whitespace_normalization".equals(raw.path("status").asText())&&raw.path("text").isTextual()?raw.path("text").asText():null;}

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

    /** Source label frames reuse the native observations; their containment does not adopt legal hierarchy. */
    private static void refineListIntervals(List<JsonNode> flow,ObjectNode parent,ArrayNode ranges){
        List<Integer> starts=new ArrayList<>(),families=new ArrayList<>();List<Long> ordinals=new ArrayList<>();List<String> labels=new ArrayList<>();List<Boolean> nativeLabels=new ArrayList<>();
        long lastAlpha=0;boolean mixed="source-region".equals(parent.path("kind").asText())&&parent.path("basis").asText().matches("explicit_(named|compound|decimal_restart).*"),hasNative=false;String previousKey=null;
        for(int n=1;n<flow.size();n++){
            JsonNode member=flow.get(n);if(member==null||!directBody(member)||!usable(member))continue;JsonNode number=numbering(member);String label=null;int family=0;long ordinal=0;boolean nativeLabel=number.path("numbered").asBoolean()&&"resolved".equals(number.path("resolutionStatus").asText());
            if(nativeLabel){label=number.path("label").asText();String format=number.path("format").asText();family=format.equals("bullet")?3:format.toLowerCase(Locale.ROOT).contains("letter")?1:format.toLowerCase(Locale.ROOT).contains("roman")?2:format.equals("decimal")?4:0;String token=label.replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT);if(family==1&&wordLetterOrdinal(token)!=lastAlpha+1&&romanDisplaySuccessor(flow,n,token))family=2;String key=listKey(number);if(previousKey!=null&&!key.equals(previousKey))mixed=true;previousKey=key;}
            else{String raw=rawText(member);if(raw!=null){Matcher marker=SOURCE_LIST_LABEL.matcher(raw);if(marker.find()){label=marker.group(1)==null?marker.group(2):marker.group(1)+".";String token=marker.group(1);family=token==null?3:token.length()==1&&(!ROMAN_ITEM.matcher(token).matches()||token.charAt(0)-'a'+1==lastAlpha+1||literalAlphaEnclosesRomanChildren(flow,n,token))?1:romanOrdinal(token)>0?2:0;mixed=true;}}}
            if(family==0)continue;String token=label.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);
            if(family==1){ordinal=wordLetterOrdinal(token);lastAlpha=ordinal;}else if(family==2)ordinal=romanOrdinal(token);else if(family==4){try{ordinal=label.matches("[0-9]+[.)]?")?Long.parseLong(token):-1;}catch(NumberFormatException ignored){ordinal=-1;}}else mixed=true;
            starts.add(n);families.add(family);ordinals.add(ordinal);labels.add(label);nativeLabels.add(nativeLabel);
            hasNative|=nativeLabel;
        }
        if(!mixed&&hasNative&&starts.size()>=2){
            int last=starts.size()-1;JsonNode a=flow.get(starts.get(last-1)),b=flow.get(starts.get(last));
            if(families.get(last)==1&&families.get(last-1)==1&&ordinals.get(last-1)>0&&ordinals.get(last)==ordinals.get(last-1)+1&&listKey(numbering(a)).equals(listKey(numbering(b)))){
                long[] left=sourceLeftBounds(b);for(int n=starts.get(last)+1;left!=null&&n<flow.size();n++)if(directBody(flow.get(n))&&!text(flow.get(n)).isBlank()&&!numbering(flow.get(n)).path("numbered").asBoolean()){long[] tail=sourceLeftBounds(flow.get(n));if(tail!=null&&tail[1]<left[0]){mixed=true;break;}}
            }
        }
        if(!mixed||!hasNative)return;int size=starts.size();int[] ends=new int[size],owners=new int[size];Arrays.fill(owners,-1);
        for(int i=0;i<size;i++){
            ends[i]=i+1<size?starts.get(i+1):flow.size();
            if(families.get(i)==1){int next=i+1;while(next<size&&families.get(next)!=1)next++;if(next<size)ends[i]=starts.get(next);else if(i+1<size){long[] a=sourceLeftBounds(flow.get(starts.get(i))),b=sourceLeftBounds(flow.get(starts.get(i+1)));boolean outdent=a!=null&&b!=null&&b[1]<a[0];int before=i-1;while(before>=0&&families.get(before)!=1)before--;boolean consecutive=before>=0&&ordinals.get(before)>0&&ordinals.get(i)==ordinals.get(before)+1;if(!outdent&&(consecutive||a!=null&&b!=null&&b[0]>a[1]))ends[i]=flow.size();}}
        }
        for(int i=0;i<size;i++)if(families.get(i)!=1){
            for(int j=i-1;j>=0;j--)if(families.get(j)==1&&starts.get(i)<ends[j]){owners[i]=j;break;}
            if(families.get(i)==3)for(int j=i-1;j>=0;j--){if(families.get(j)==1)break;if(families.get(j)==2){owners[i]=j;break;}}
        }
        for(int i=0;i<size;i++)if(families.get(i)==3&&text(flow.get(starts.get(i))).stripTrailing().endsWith(":")){
            int next=i+1;if(next>=size)continue;JsonNode first=numbering(flow.get(starts.get(next)));
            while(next<size&&families.get(next)==4&&nativeLabels.get(next)&&first.path("numId").equals(numbering(flow.get(starts.get(next))).path("numId"))&&first.path("abstractNumId").equals(numbering(flow.get(starts.get(next))).path("abstractNumId")))next++;
            int end=next<size?starts.get(next):flow.size();if(next>i+2&&boundSourceSlice(flow,starts.get(i),end,parent)){ends[i]=end;for(int n=i+1;n<next;n++)owners[n]=i;}
        }
        Set<Integer> unresolvedNestedRoman=new HashSet<>();for(int i=0;i<size;i++)if(families.get(i)==2){
            int next=i+1;boolean decimal=false;while(next<size){if(families.get(next)==3){next++;continue;}int owner=owners[next];if(families.get(next)==4&&owner>=0&&families.get(owner)==3&&owners[owner]==i){decimal=true;next++;continue;}break;}
            boolean closed=next<size&&families.get(next)==2&&ordinals.get(next)==ordinals.get(i)+1&&owners[next]==owners[i];if(next>i+1){ends[i]=next<size?starts.get(next):flow.size();if(decimal&&!closed)unresolvedNestedRoman.add(i);}
        }
        Map<Integer,int[]> textFrames=sourceLabelTextFrames(flow,starts,families,ordinals,ends);Map<Integer,ObjectNode> projected=new HashMap<>(),tails=new LinkedHashMap<>(),sourceFrames=new LinkedHashMap<>();
        for(int i=0;i<size;i++){
            int from=starts.get(i),end=ends[i];JsonNode member=flow.get(from);String kind=nativeLabels.get(i)?"native-list":"source-region";ObjectNode outer=owners[i]>=0?projected.get(owners[i]):parent;if(outer==null)outer=parent;
            if(families.get(i)!=1)for(Map.Entry<Integer,int[]> frame:textFrames.entrySet())if(from>frame.getKey()&&from<frame.getValue()[0]&&owners[i]==frame.getValue()[1]){outer=sourceFrames.get(frame.getKey());end=Math.min(end,frame.getValue()[0]);if(frame.getValue()[2]==1)owners[i]=-1;break;}
            if(families.get(i)==1||families.get(i)==2&&owners[i]>=0&&families.get(owners[i])==1){
                long[] left=sourceLeftBounds(member);for(int n=from+1;left!=null&&n<end;n++)if(directBody(flow.get(n))&&!text(flow.get(n)).isBlank()&&!numbering(flow.get(n)).path("numbered").asBoolean()&&!sourceListMarker(flow.get(n))){
                    long[] following=sourceLeftBounds(flow.get(n));if(following!=null&&following[1]<left[0]){ObjectNode tail=range("source-region",text(flow.get(n)),new ArrayList<>(flow.subList(n,end)),end<flow.size()?boundary("next_explicit_source_region",flow.get(end)):parent.path("endBoundary"));tail.put("basis","resolved_source_outdent_within_explicit_label_frame");tail.put("hierarchyStatus","source_interval_containment_only");tail.put("parentId",outer.path("id").asText());if(!boundSourceSlice(flow,n,end,parent))incomplete(tail,"source_shared_tail_members_or_boundary_are_not_bound");tails.put(i,tail);end=n;break;}
                }
            }
            int next=indexOf(starts,end);JsonNode finish=end<flow.size()?boundary(next>=0&&nativeLabels.get(next)?"next_resolved_native_list_member":"next_explicit_source_region",flow.get(end)):parent.path("endBoundary");
            ObjectNode candidate=range(kind,parent.path("reference").asText()+labels.get(i),new ArrayList<>(flow.subList(from,end)),finish);candidate.put("basis",nativeLabels.get(i)?"resolved_native_list_label_and_bound_source_frame":"explicit_literal_list_label_and_bound_source_frame");candidate.put("hierarchyStatus","source_interval_containment_only");candidate.put("parentId",outer.path("id").asText());
            if(nativeLabels.get(i))candidate.set("numbering",numbering(member).deepCopy());if(families.get(i)==3)candidate.put("sourceReferenceKind","nonordinal_glyph");
            if(!boundSourceSlice(flow,from,end,parent)||end==flow.size()&&!parent.path("scopeComplete").asBoolean())incomplete(candidate,"source_label_frame_boundary_is_unresolved");
            if(unresolvedNestedRoman.contains(i))incomplete(candidate,"nested_decimal_source_frame_has_no_verified_roman_peer_closure");
            ObjectNode prior=rangeById(ranges,candidate.path("id").asText());if(prior!=null)candidate.set("reference",prior.path("reference"));projected.put(i,candidate);
            for(Map.Entry<Integer,int[]> frame:textFrames.entrySet())if(frame.getValue()[1]==i){int[] spec=frame.getValue();sourceFrames.put(frame.getKey(),explicitRegion(flow,frame.getKey(),spec[0],spec[2]==1?parent:candidate,spec[2]==1?"observed_struck_literal_label_text_frame":"explicit_text_heading_and_restarted_label_source_frame"));}
        }
        Map<String,Integer> previous=new HashMap<>();for(int i=0;i<size;i++)if(families.get(i)!=3){
            ObjectNode candidate=projected.get(i);String key=candidate.path("parentId").asText()+"|"+families.get(i);Integer before=previous.put(key,i);if(before==null)continue;
            if(ordinals.get(i)>0&&ordinals.get(before)>0&&ordinals.get(i)!=ordinals.get(before)+1&&!sourceSequenceInterrupted(flow,starts.get(before),starts.get(i),candidate.path("parentId"),sourceFrames,tails)){String conflict=ordinals.get(i).equals(ordinals.get(before))?"duplicate_source_label":"nonconsecutive_source_label";JsonNode a=numbering(flow.get(starts.get(before))),b=numbering(flow.get(starts.get(i)));if(a.path("numId").equals(b.path("numId"))&&!a.path("level").equals(b.path("level")))conflict="native_level_label_disagreement";sourceConflict(projected.get(before),conflict,flow.get(starts.get(before)),flow.get(starts.get(i)),labels.get(before),labels.get(i));sourceConflict(candidate,conflict,flow.get(starts.get(before)),flow.get(starts.get(i)),labels.get(before),labels.get(i));}
        }
        Set<Integer> emitted=new HashSet<>();for(int i=0;i<size;i++)if(nativeLabels.get(i)){emitted.add(i);if(i>0&&!nativeLabels.get(i-1))emitted.add(i-1);if(i+1<size&&!nativeLabels.get(i+1))emitted.add(i+1);}
        for(int i:new ArrayList<>(emitted)){int owner=owners[i];while(owner>=0){emitted.add(owner);owner=owners[owner];}}
        for(int i=0;i<size;i++)if(emitted.contains(i)){ObjectNode candidate=projected.get(i);if(owners[i]>=0&&projected.get(owners[i]).has("sourceConflicts")){candidate.put("sourceReferenceStatus","observed_source_reference_conflict");ArrayNode conflicts=candidate.has("sourceConflicts")?(ArrayNode)candidate.path("sourceConflicts"):candidate.putArray("sourceConflicts");for(JsonNode inherited:projected.get(owners[i]).path("sourceConflicts")){boolean duplicate=false;for(JsonNode existing:conflicts)if(existing.equals(inherited)){duplicate=true;break;}if(!duplicate)conflicts.add(inherited.deepCopy());}}mergeRange(ranges,candidate);}
        for(Map.Entry<Integer,ObjectNode> frame:sourceFrames.entrySet()){int[] spec=textFrames.get(frame.getKey());boolean needed=spec[2]==0&&emitted.contains(spec[1]);for(int i:emitted)if(starts.get(i)>frame.getKey()&&starts.get(i)<spec[0]||spec[2]==1&&families.get(i)==1&&starts.get(i)==spec[0])needed=true;if(needed)mergeRange(ranges,frame.getValue());}
        Map<String,ObjectNode> sharedTails=new LinkedHashMap<>();for(Map.Entry<Integer,ObjectNode> entry:tails.entrySet())if(emitted.contains(entry.getKey())){
            ObjectNode tail=entry.getValue();JsonNode owner=rangeById(ranges,tail.path("parentId").asText());if(owner==null||!containsMembers(owner,tail))continue;ObjectNode previousTail=sharedTails.get(tail.path("id").asText());
            if(previousTail==null)sharedTails.put(tail.path("id").asText(),tail);else if(previousTail.path("orderedMemberIds").equals(tail.path("orderedMemberIds"))&&previousTail.path("endBoundary").equals(tail.path("endBoundary"))){JsonNode previousOwner=rangeById(ranges,previousTail.path("parentId").asText());if(owner.path("orderedMemberIds").size()<previousOwner.path("orderedMemberIds").size())sharedTails.put(tail.path("id").asText(),tail);}
        }
        for(ObjectNode tail:sharedTails.values())mergeRange(ranges,tail);
    }
    private static boolean containsMembers(JsonNode parent,JsonNode child){Set<String> ids=new HashSet<>();for(JsonNode id:parent.path("orderedMemberIds"))ids.add(id.asText());for(JsonNode id:child.path("orderedMemberIds"))if(!ids.contains(id.asText()))return false;return true;}
    private static boolean sourceListMarker(JsonNode member){String raw=rawText(member);if(raw==null)return false;if(SOURCE_LIST_LABEL.matcher(raw).find())return true;Matcher compact=COMPACT_ROMAN_LABEL.matcher(raw);return compact.find()&&ROMAN_ITEM.matcher(compact.group(1)).matches();}
    private static boolean romanDisplaySuccessor(List<JsonNode> flow,int from,String token){
        int ordinal=romanOrdinal(token);if(ordinal<=0)return false;
        for(int n=from+1;n<flow.size();n++){JsonNode member=flow.get(n);if(!directBody(member)||!usable(member))continue;String value=sourceLabelToken(member);if(value!=null)return romanOrdinal(value)==ordinal+1;if(!text(member).isBlank())return false;}
        return false;
    }
    private static String sourceLabelToken(JsonNode member){
        JsonNode number=numbering(member);if(number.path("numbered").asBoolean())return "resolved".equals(number.path("resolutionStatus").asText())?number.path("label").asText().replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT):null;
        String raw=rawText(member);if(raw==null)return null;Matcher marker=SOURCE_LIST_LABEL.matcher(raw);return marker.find()?marker.group(1):null;
    }
    private static Map<Integer,int[]> sourceLabelTextFrames(List<JsonNode> flow,List<Integer> starts,List<Integer> families,List<Long> ordinals,int[] ends){
        Map<Integer,int[]> frames=new LinkedHashMap<>();
        for(int i=0;i<starts.size();i++)if(families.get(i)==1){
            int end=ends[i];List<Integer> titles=new ArrayList<>();long[] ownerLeft=sourceLeftBounds(flow.get(starts.get(i)));
            for(int n=starts.get(i)+1;n<end;n++){
                JsonNode member=flow.get(n);if(!directBody(member)||!usable(member)||sourceLabelToken(member)!=null||numbering(member).path("numbered").asBoolean())continue;int next=0;while(next<starts.size()&&starts.get(next)<=n)next++;if(next>=starts.size()||starts.get(next)>=end||families.get(next)!=2||ordinals.get(next)!=1)continue;
                boolean adjacent=true;for(int at=n+1;at<starts.get(next);at++)if(!text(flow.get(at)).isBlank())adjacent=false;if(!adjacent)continue;
                if(struckSourceLabel(member)){frames.put(n,new int[]{end,i,1});ends[i]=Math.min(ends[i],n);continue;}
                long[] titleLeft=sourceLeftBounds(member);String title=text(member).strip();if(next+1<starts.size()&&starts.get(next+1)<end&&families.get(next+1)==2&&ordinals.get(next+1)==2&&ownerLeft!=null&&titleLeft!=null&&titleLeft[0]>ownerLeft[1]&&title.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} /-]*"))titles.add(n);
            }
            if(titles.size()>=2)for(int n=0;n<titles.size();n++)frames.put(titles.get(n),new int[]{n+1<titles.size()?titles.get(n+1):end,i,0});
        }
        return frames;
    }
    private static boolean struckSourceLabel(JsonNode member){
        String raw=rawText(member),original=member.path("preNormalizedTextObservation").path("originalText").asText(),strike=member.path("textObservation").path("strikeText").asText();if(raw==null||!raw.stripLeading().startsWith(".")||strike.isEmpty())return false;Matcher marker=SOURCE_LIST_LABEL.matcher(original);return marker.find()&&marker.group(1)!=null&&marker.group(1).equals(strike)&&original.replaceFirst(Pattern.quote(strike),"").equals(raw);
    }
    private static boolean sourceSequenceInterrupted(List<JsonNode> flow,int before,int current,JsonNode parent,Map<Integer,ObjectNode> frames,Map<Integer,ObjectNode> tails){
        for(Map.Entry<Integer,ObjectNode> frame:frames.entrySet())if(frame.getKey()>before&&frame.getKey()<current&&frame.getValue().path("parentId").equals(parent))return true;
        for(JsonNode tail:tails.values())if(tail.path("parentId").equals(parent))for(int n=before+1;n<current;n++)if(flow.get(n).path("sourcePosition").equals(tail.path("startBoundary").path("sourcePosition")))return true;
        for(int n=before+1;n<current;n++){JsonNode member=flow.get(n);if(directBody(member)&&(sourceDecimal(member)!=null||"resolved".equals(member.path("paragraphProperties").path("status").asText())&&member.path("paragraphProperties").path("outlineLevel").asInt(9)<9))return true;}
        return false;
    }
    private static boolean literalAlphaEnclosesRomanChildren(List<JsonNode> flow,int from,String token){
        if(token.length()!=1||!ROMAN_ITEM.matcher(token).matches())return false;boolean romanStart=false;
        for(int n=from+1;n<flow.size();n++){
            JsonNode member=flow.get(n);if(!directBody(member)||!usable(member))continue;String label=numbering(member).path("numbered").asBoolean()?numbering(member).path("label").asText():null;
            if(label==null){String raw=rawText(member);if(raw==null)continue;Matcher marker=SOURCE_LIST_LABEL.matcher(raw);if(!marker.find()){if(sourceDecimal(member)!=null)return false;continue;}label=marker.group(1);}
            if(label==null)continue;String value=label.replaceAll("[^A-Za-z]","").toLowerCase(Locale.ROOT);if(value.length()==1&&value.charAt(0)==token.charAt(0)+1)return romanStart;if(!ROMAN_ITEM.matcher(value).matches())return false;if(value.equals("i"))romanStart=true;
        }
        return false;
    }
    private static long wordLetterOrdinal(String token){if(token.isEmpty())return -1;char first=token.charAt(0);if(first<'a'||first>'z')return -1;for(int n=1;n<token.length();n++)if(token.charAt(n)!=first)return -1;return (token.length()-1)*26L+first-'a'+1;}
    private static int indexOf(List<Integer> starts,int index){return starts.indexOf(index);}
    private static ObjectNode rangeById(ArrayNode ranges,String id){for(JsonNode candidate:ranges)if(id.equals(candidate.path("id").asText()))return (ObjectNode)candidate;return null;}
    private static long[] sourceLeftBounds(JsonNode member){
        JsonNode properties=member.path("paragraphProperties"),layout=properties.path("layoutEvidence"),layers=layout.path("layers"),ind=layout.path("effective").path("ind");if(!layers.isArray()||layers.isEmpty()||properties.has("styleChainStatus"))return null;
        for(Iterator<String> names=ind.fieldNames();names.hasNext();){String name=names.next();if(name.endsWith("}leftChars")||name.endsWith("}startChars")||name.endsWith("}start"))return null;}
        Map<String,Long> candidates=new LinkedHashMap<>();
        for(JsonNode layer:layers){
            if(!sourceDeclarationBound(member,layer.path("sourcePosition")))return null;
            for(JsonNode declaration:layer.path("declarations")){
                String name=declaration.path("name").asText();JsonNode attributes=declaration.path("attributes");if(!sourceDeclarationBound(member,declaration.path("sourcePosition")))return null;
                if(Arrays.asList("bidi","mirrorIndents","textDirection").contains(name)){String value="";for(JsonNode attribute:attributes)value=attribute.asText();if(name.equals("textDirection")?!"lrTb".equals(value):!Arrays.asList("0","false","off").contains(value))return null;}
                if(!name.equals("ind"))continue;Iterator<Map.Entry<String,JsonNode>> values=attributes.fields();while(values.hasNext()){
                    Map.Entry<String,JsonNode> value=values.next();if(value.getKey().endsWith("}leftChars")||value.getKey().endsWith("}startChars")||value.getKey().endsWith("}start"))return null;
                    if(value.getKey().endsWith("}left"))try{candidates.put(layer.path("kind").asText().contains("numbering")?"numbering":"paragraph",Long.parseLong(value.getValue().asText()));}catch(NumberFormatException ignored){return null;}
                }
            }
        }
        for(Iterator<String> names=ind.fieldNames();names.hasNext();){String name=names.next();if(!name.endsWith("}left"))continue;JsonNode value=ind.path(name);
            if("resolved".equals(value.path("status").asText())){if(!sourceDeclarationBound(member,value.path("sourcePosition")))return null;try{long left=Long.parseLong(value.path("value").asText());return new long[]{left,left};}catch(NumberFormatException ignored){return null;}}
            if(!"unknown".equals(value.path("status").asText())||!"numbering_and_paragraph_style_precedence_not_verified".equals(value.path("reason").asText()))return null;
        }
        if(candidates.isEmpty())return null;long min=Long.MAX_VALUE,max=Long.MIN_VALUE;for(long value:candidates.values()){min=Math.min(min,value);max=Math.max(max,value);}return new long[]{min,max};
    }
    private static boolean sourceDeclarationBound(JsonNode member,JsonNode position){return "docx_native".equals(position.path("kind").asText())&&member.path("sourcePosition").path("sourceSha256").equals(position.path("sourceSha256"))&&!position.path("partSha256").asText().isEmpty()&&!position.path("partName").asText().isEmpty()&&!position.path("nodePath").asText().isEmpty();}
    private static boolean boundSourceSlice(List<JsonNode> flow,int from,int end,JsonNode parent){if(from<0||from>=end||end>flow.size())return false;if(end<flow.size())return boundObservedInterval(flow,from,end);if(!samePart(flow.get(from).path("sourcePosition"),parent.path("endBoundary").path("sourcePosition")))return false;return end-from==1?usable(flow.get(from))&&rawText(flow.get(from))!=null&&flow.get(from).path("ordinalZeroBased").isIntegralNumber()&&flow.get(from).path("ordinalZeroBased").canConvertToInt()&&flow.get(from).path("ordinalZeroBased").asInt()>=0:boundObservedInterval(flow,from,end-1);}
    private static void sourceConflict(ObjectNode candidate,String kind,JsonNode previous,JsonNode current,String previousLabel,String label){candidate.put("sourceReferenceStatus","observed_source_reference_conflict");ArrayNode conflicts=candidate.has("sourceConflicts")?(ArrayNode)candidate.path("sourceConflicts"):candidate.putArray("sourceConflicts");ObjectNode row=conflicts.addObject();row.put("kind",kind);row.set("previousMemberId",previous.path("id"));row.set("memberId",current.path("id"));row.put("previousLabel",previousLabel);row.put("label",label);}

    private static ObjectNode range(String kind,String reference,List<JsonNode> members,JsonNode end) {
        JsonNode first=members.get(0);ObjectNode range=JsonUtils.mapper().createObjectNode();
        range.put("id","docx-source-range:"+DocumentParseProbe.sha256((kind+"|"+first.path("id").asText()).getBytes(StandardCharsets.UTF_8)));
        range.put("reference",reference);range.putNull("parentId");range.put("kind",kind);range.put("status","source_syntax_complete");range.put("scopeComplete",true);
        range.put("automaticEditEligible",false);range.put("semanticApplicabilityVerified",false);
        range.put("scopeMeaning","Complete observed source interval only; native outline and source sequence bases remain distinct; legal applicability and editing adoption are unknown");
        range.set("startBoundary",boundary("observed_source_member",first));range.set("endBoundary",end.deepCopy());
        ArrayNode ordered=range.putArray("orderedMemberIds");range.putArray("contextMemberIds");range.putArray("contextObservations");
        for(JsonNode member:members){ordered.add(member.path("id").asText());if(!usable(member)||!sameStory(first,member))incomplete(range,"unresolved_or_overlapping_paragraph_channel_or_story");}
        if(!samePart(first.path("sourcePosition"),end.path("sourcePosition"))||!Arrays.asList("native_container_end","next_native_outline_heading","next_explicit_number_in_source_sequence","next_resolved_native_list_member","next_explicit_source_region","explicit_source_region_end").contains(end.path("kind").asText()))incomplete(range,"source_interval_end_boundary_is_unresolved");
        return range;
    }
    private static void incomplete(ObjectNode range,String reason){range.put("status","partial_unknown_scope");range.put("scopeComplete",false);ArrayNode reasons=range.has("unknownReasons")?(ArrayNode)range.get("unknownReasons"):range.putArray("unknownReasons");for(JsonNode prior:reasons)if(reason.equals(prior.asText()))return;reasons.add(reason);}
    private static ObjectNode boundary(String kind,JsonNode member){ObjectNode out=JsonUtils.mapper().createObjectNode();out.put("kind",kind);out.put("memberId",member.path("id").asText());out.set("sourcePosition",member.path("sourcePosition").deepCopy());return out;}
    private static boolean usable(JsonNode member){return "source_bound_native_member".equals(member.path("sourceClauseClassification").asText())&&member.path("textObservation").path("status").asText().startsWith("observed_")&&member.path("textObservation").path("text").isTextual()&&!member.has("textOverlapStatus")&&member.path("effectiveCandidate").asBoolean(true);}
    private static boolean directBody(JsonNode member){return "body".equals(member.path("storyKind").asText())&&!member.has("cellNodePath")&&!member.has("containingParagraphNodePath")&&!member.has("textOverlapStatus");}
    private static boolean sameStyle(JsonNode a,JsonNode b){String style=a.path("paragraphProperties").path("paragraphStyleId").asText();return !style.isEmpty()&&style.equals(b.path("paragraphProperties").path("paragraphStyleId").asText());}
    private static boolean samePeer(JsonNode a,JsonNode b){
        if(sameStyle(a,b))return true;if(!sameStory(a,b)||text(a).isEmpty()||text(b).isEmpty()||Character.isWhitespace(text(a).charAt(0))||Character.isWhitespace(text(b).charAt(0)))return false;
        JsonNode ap=a.path("paragraphProperties"),bp=b.path("paragraphProperties");if(ap.has("styleChainStatus")||bp.has("styleChainStatus"))return false;
        JsonNode al=ap.path("layoutEvidence").path("effective"),bl=bp.path("layoutEvidence").path("effective"),ai=al.path("ind"),bi=bl.path("ind");
        if(!ai.isObject()||ai.isEmpty()||ai.size()!=bi.size())return false;
        Iterator<Map.Entry<String,JsonNode>> attributes=ai.fields();while(attributes.hasNext()){
            Map.Entry<String,JsonNode> attribute=attributes.next();JsonNode av=attribute.getValue(),bv=bi.path(attribute.getKey());
            if(!"resolved".equals(av.path("status").asText())||!"resolved".equals(bv.path("status").asText())||!av.path("value").equals(bv.path("value"))||!av.path("value").asText().matches("-?[0-9]+"))return false;
        }
        for(String property:Arrays.asList("bidi","textDirection"))if(!al.path(property).equals(bl.path(property)))return false;
        return true;
    }
    private static boolean samePart(JsonNode a,JsonNode b){return !a.path("partSha256").asText().isEmpty()&&a.path("sourceSha256").equals(b.path("sourceSha256"))&&a.path("partName").equals(b.path("partName"))&&a.path("partSha256").equals(b.path("partSha256"));}
    private static void mergeRange(ArrayNode ranges,ObjectNode candidate){
        for(JsonNode observed:ranges)if(observed.path("id").equals(candidate.path("id"))){
            if(observed.path("scopeComplete").asBoolean()&&!candidate.path("scopeComplete").asBoolean())return;
            if(observed.path("scopeComplete").asBoolean()&&"next_native_outline_heading".equals(candidate.path("endBoundary").path("kind").asText())&&candidate.path("orderedMemberIds").size()<observed.path("orderedMemberIds").size()){boolean prefix=true;for(int n=0;n<candidate.path("orderedMemberIds").size();n++)if(!candidate.path("orderedMemberIds").get(n).equals(observed.path("orderedMemberIds").get(n))){prefix=false;break;}if(prefix)return;}
            if(observed.path("scopeComplete").asBoolean()&&observed.path("orderedMemberIds").equals(candidate.path("orderedMemberIds"))&&observed.path("startBoundary").equals(candidate.path("startBoundary"))&&observed.path("endBoundary").equals(candidate.path("endBoundary"))&&observed.path("parentId").equals(candidate.path("parentId")))candidate.put("basis",observed.path("basis").asText());
            if(observed!=candidate){ObjectNode target=(ObjectNode)observed;target.removeAll();target.setAll(candidate);}return;
        }
        ranges.add(candidate);
    }
    private static boolean sameStory(JsonNode a,JsonNode b){return samePart(a.path("sourcePosition"),b.path("sourcePosition"))&&a.path("storyKind").equals(b.path("storyKind"))&&a.path("storyRootNodePath").equals(b.path("storyRootNodePath"));}
    private static String text(JsonNode member){return member.path("textObservation").path("text").asText();}
    private static JsonNode numbering(JsonNode member){return member.path("paragraphProperties").path("layoutEvidence").path("numbering");}
    private static String listKey(JsonNode number){return number.path("numId").asText()+"|"+number.path("level").asText()+"|"+number.path("format").asText();}
    private static String positionKey(JsonNode position,String path){return position.path("sourceSha256").asText()+"\n"+position.path("partName").asText()+"\n"+position.path("partSha256").asText()+"\n"+path;}
}
