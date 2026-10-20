package com.consense.service.advice;

import com.consense.common.JsonUtils;
import com.consense.web.dto.AdviceDtos.CitationVO;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;

/** Bounded formal-attribution syntax only, over already admitted source pointers. */
final class AdviceClauseAttribution {
    private AdviceClauseAttribution() {}
    private static final String ID="[0-9]+(?:\\.[0-9]+)*(?:\\([A-Za-z0-9]+\\))*";
    private static final Pattern REF=Pattern.compile("\\b(?:Clauses?|Sub-clause|Article)\\s*("+ID+")|第\\s*("+ID+")\\s*[条條]",Pattern.CASE_INSENSITIVE);
    private static final Pattern BEFORE=Pattern.compile("(?is).*(\\bunder|\\baccording to|\\bpursuant to|\\bsubject to|\\bthis (?:paragraph|excerpt|source) is|根据|根據|依据|依據|按照|依照)[\\s\"“‘'「『`(]*$");
    private static final Pattern AFTER=Pattern.compile("(?is)^[\\s\"”’'」』`)\\]]*(?:(?:(?:of|in) [^.;。\\n]{1,60}? )?(?:states|provides|requires|stipulates|limits|governs|is subject to)\\b|规定|規定|要求|限定|载明|載明|约定|約定|约束|約束)");
    private static final Pattern SUBJECT_TAIL=Pattern.compile("(?s)^\\s*(?:[和及与與、]\\s*第\\s*"+ID+"\\s*[条條]\\s*){0,4}(?:约束|約束|限制)");
    private static final Pattern NONASSERTION=Pattern.compile("(?is)^\\s*(?:it is (?:unknown|unclear) whether|we cannot (?:confirm|establish) whether|the source does not (?:confirm|establish) whether|尚未确认|尚未確認|无法确认|無法確認|不能确认|不能確認|(?:this (?:paragraph|excerpt|source) is )?not (?:under|according to|pursuant to|subject to)|并非(?:根据|依据|按照|依照)|並非(?:根據|依據|按照|依照))[\\s\"“‘'「『`(]*$");
    private static final Pattern QUOTE=Pattern.compile("\"[^\"]+\"|“[^”]+”|‘[^’]+’|「[^」]+」|『[^』]+』|`[^`]+`|(?<![A-Za-z])'[^'\\n]+'");

    static void check(AdvisoryHarness.Section section,List<CitationVO> sources,List<String> problems) {
        checkText("section.content",section.getContent(),section.getCitations(),sources,problems);
        AdvisoryHarness.GraphPlan graph=section.getFlowchartPlan();if(graph==null)return;
        if(graph.getNodes()!=null)for(int i=0;i<graph.getNodes().size();i++){
            AdvisoryHarness.Node node=graph.getNodes().get(i);if(node!=null)checkText("flowchartPlan.nodes["+i+"].label",node.getLabel(),node.getCitations(),sources,problems);
        }
        if(graph.getEdges()!=null)for(int i=0;i<graph.getEdges().size();i++){
            AdvisoryHarness.Edge edge=graph.getEdges().get(i);if(edge==null)continue;
            String[] names={"condition","actor","action","period","startEvent"},values={edge.getCondition(),edge.getActor(),edge.getAction(),edge.getPeriod(),edge.getStartEvent()};
            for(int j=0;j<names.length;j++)checkText("flowchartPlan.edges["+i+"]."+names[j],values[j],edge.getCitations(),sources,problems);
        }
    }

    private static void checkText(String path,String text,List<String> fieldRefs,List<CitationVO> sources,List<String> problems) {
        if(text==null||fieldRefs==null)return;Matcher refs=REF.matcher(text);int checked=0;
        while(refs.find()){
            int from=boundary(text,refs.start(),false),to=boundary(text,refs.end(),true);
            String before=text.substring(Math.max(from,refs.start()-80),refs.start()),after=text.substring(refs.end(),Math.min(to,refs.end()+100));
            Matcher prefix=BEFORE.matcher(before),predicate=AFTER.matcher(after);boolean attributed=prefix.matches(),says=predicate.find();
            boolean subject=before.trim().endsWith("受")&&!before.trim().endsWith("接受")&&SUBJECT_TAIL.matcher(after).find();
            boolean heading=before.trim().matches("(?:[-*]\\s*)?")&&after.matches("(?s)^\\s*[:：—].*");
            if(!attributed&&!subject&&!says&&!heading)continue;
            if(NONASSERTION.matcher(before).matches())continue;
            if(++checked>64){problems.add(path+": too many explicit formal clause attributions");return;}
            List<String> local=AdvisoryHarness.explicitBodyAliases(text.substring(from,to));
            if(local.isEmpty())local=fieldRefs;
            final List<String> selected=local;
            List<CitationVO> cited=new ArrayList<>();for(CitationVO source:sources)if(selected.contains(source.getEvidenceId()))cited.add(source);
            int claimStart=attributed?refs.start()-before.length()+prefix.start(1):subject?refs.start()-before.length()+before.lastIndexOf('受'):refs.start(),claimEnd=says?refs.end()+predicate.end():refs.end();
            if(trueQuote(text,claimStart,claimEnd,cited))continue;
            // A genuine referring source may itself state a rule about another clause; match the whole asserted wording, not its ID token.
            String asserted=text.substring(claimStart,to);for(String alias:selected)asserted=asserted.replaceAll("[\\t ]*"+Pattern.quote(alias)+"[\\t ]*(?=[.,;:!?，。；：！？]|$)","").replace(alias,"");
            if(!asserted.trim().isEmpty()&&literalContains(asserted.trim(),cited))continue;
            String id=refs.group(1)==null?refs.group(2):refs.group(1);boolean supported=false;
            // A field-wide fallback can identify a source only when its document/version is unambiguous.
            Set<String> identities=new HashSet<>();for(CitationVO source:cited)identities.add(source.getDocumentId()+"|"+source.getSourceHash());
            if(identities.size()==1)for(CitationVO source:cited)if(declared(source,id)){supported=true;break;}
            if(!supported)problems.add(path+": unsupported_formal_clause_attribution at "+refs.start()+".."+refs.end()+"; designation="+JsonUtils.write(refs.group())+"; offsetUnit=utf16; aliases="+selected
                    +". A source item or external clause reference is not this excerpt's formal owner. Recheck the WHOLE cited assertion against original wording, including its governing conditions, trigger, effect and exceptions; deleting only this designation does not repair unsupported substance. If the formal owner remains unknown, use a supported source-item description or complete source quotation while preserving genuine cross-references. Return independently supported rule/mechanism sections with their own full conditions, and isolate only unresolved judgments under the existing uncertainty/gaps contract; preserve the requested basis, checks and immutable accepted units.");
        }
    }

    private static boolean declared(CitationVO source,String id) {
        if(source.getCanonicalEvidence()==null)return false;
        JsonNode unit=source.getCanonicalSourceUnit();
        for(EvidencePointer pointer:source.getCanonicalEvidence()){
            if(pointer==null||!Objects.equals(source.getDocumentId(),String.valueOf(pointer.getDocumentId())))continue;
            // OerBusinessEvidence already verifies this admitted member/pointer chain and current-source guard.
            if(unit!=null&&unit.path("members").isArray())for(JsonNode member:unit.path("members"))
                if(Objects.equals(pointer.getCanonicalChunkId(),member.path("id").asText(null))&&Objects.equals(pointer.getUnitId(),member.path("unitId").asText(null))
                        &&id.equals(member.path("clauseId").asText())&&member.path("clauseHeadingLocation").isTextual()&&!member.path("clauseHeadingLocation").asText().trim().isEmpty())return true;
            JsonNode adoption=pointer.getAdoption();
            if(adoption!=null&&id.equals(adoption.path("clause").path("clauseId").asText())&&adoption.path("clauseHeadingLocation").isTextual()
                    &&!adoption.path("clauseHeadingLocation").asText().trim().isEmpty())return true;
        }
        return false;
    }

    private static boolean trueQuote(String text,int start,int end,List<CitationVO> sources) {
        Matcher quotes=QUOTE.matcher(text);
        while(quotes.find())if(quotes.start()<start&&quotes.end()>=end){
            String literal=text.substring(quotes.start()+1,quotes.end()-1);
            if(literalContains(literal,sources))return true;
        }
        int lineStart=text.lastIndexOf('\n',start)+1,lineEnd=text.indexOf('\n',end);if(lineEnd<0)lineEnd=text.length();
        return text.startsWith("> ",lineStart)&&end<=lineEnd&&literalContains(text.substring(lineStart+2,lineEnd),sources);
    }

    private static boolean literalContains(String literal,List<CitationVO> sources) {
        for(CitationVO source:sources){
            if(source.getCanonicalEvidence()!=null&&!source.getCanonicalEvidence().isEmpty()){
                for(EvidencePointer pointer:source.getCanonicalEvidence())if(pointer!=null&&pointer.getParts()!=null&&pointer.getParts().isArray())
                    for(JsonNode part:pointer.getParts())if(part.path("text").isTextual()&&part.path("text").asText().contains(literal))return true;
            }else if(source.getContent()!=null&&source.getContent().contains(literal))return true;
        }
        return false;
    }

    private static int boundary(String text,int offset,boolean forward) {
        int i=offset;while(forward?i<text.length():i>0){int at=forward?i:i-1;char c=text.charAt(at);
            if(c=='\n'||c=='。'||c==';'||c=='；'||c=='.'&&at+1<text.length()&&Character.isWhitespace(text.charAt(at+1)))return forward?at:at+1;
            i+=forward?1:-1;
        }return forward?text.length():0;
    }
}
