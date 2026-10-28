package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks explicit source-location assertions and can replace one unverified title label with its quoted document label. */
final class VettingSourceAttribution {
    static final String POLICY="explicit-source-clause-attribution-v6:bounded-missing-evidence-v1:bound-packet-citation-v1:title-source-label-recovery-v1:"+ObservedSourceClauseLocators.VERSION;
    private static final String NUMBER="\\d+(?:\\s*\\.\\s*\\d+)*(?:\\s*\\([a-z0-9]+\\))*";
    private static final Pattern CLAUSE=Pattern.compile("(?i)(?:(NTT|SCT|SCC|GCT|GCC|PRE|FT|AA|GS|SL)\\s+)?\\bClauses?\\s+("+NUMBER+"(?:\\s*(?:,|and|or|to)\\s*(?:Clauses?\\s+)?"+NUMBER+")*)");
    private static final Pattern NUMBERS=Pattern.compile(NUMBER,Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKET_CITATION=Pattern.compile("(?s)^(.*\\S)[\\s\\p{Z}]+\\((R[1-9][0-9]*)\\)$");
    private static final Pattern OWNER=Pattern.compile("^([A-Z][A-Z.]*?)[.]?(\\d.*)$");
    private static final Pattern QUESTION=Pattern.compile("(?i)^(?:(?:please\\s+)?(?:confirm|check|verify|review|determine)\\s+)?(?:whether|if|does|do|is|are|was|were|can|could|would|should|which|what|how)\\b|\\b(?:unknown|unverified|unconfirmed|uncertain)\\b.*\\bwhether\\b");
    private static final Pattern REFERENCE=Pattern.compile("(?i)(?:\\b(?:cross[- ]?)?references?\\s+to\\s*(?:(?:the|non-existent|unverified|missing|internal|external)\\s+)*|\\b(?:cites?|citing|refers?\\s+to)\\s*)$");
    private static final Pattern UNKNOWN_TARGET=Pattern.compile("(?i)^\\s*(?:itself\\s+)?(?:is|are|was|were|remains?)\\s+(?:(?:still|currently)\\s+)?(?:unknown|unverified|unconfirmed|uncertain|not\\s+(?:provided|supplied|available|verified|confirmed|established|included))\\b");
    private static final Pattern PREDICATE=Pattern.compile("(?i)^\\s*(?:(?:of|in)\\s+[^.,;?!]{0,100}?\\s+)?(?:states?|requires?|directs?|provides?|stipulates?|imposes?|specifies?|sets?|mandates?|defines?|contains?|governs?|obliges?|establishes?|says?|covers?|is|are|has|have)\\b");
    private static final Pattern LOCATION=Pattern.compile("(?i)\\b(?:between|under|pursuant\\s+to|according\\s+to|in|within|at|amend|delete|revise|modify|replace|align|either)\\s*$");
    private static final Pattern SOURCE_IDENTIFIER=Pattern.compile("(?i)\\b(?:NTT|SCT|SCC|GCT|GCC|PRE|FT|AA|GS|SL)\\.[A-Z]?\\d+(?:\\.\\d+)*");
    private static final Pattern CHECKLIST_QUESTION=Pattern.compile("(?i)^(?:the\\s+)?checklist\\s+(?:asks\\s+(?:whether|if)|requires\\s+(?:confirming|verifying|checking|ensuring)\\b)");
    private static final Pattern UNVERIFIABLE_QUESTION=Pattern.compile("(?i)\\b(?:cannot|unable\\s+to|impossible\\s+to)\\s+(?:verify|confirm|determine|assess|check)\\s+(?:whether|if)\\b");
    private static final Pattern NOT_SEARCHED=Pattern.compile("(?i)^(?:has|have|had)\\s+not\\s+(?:yet\\s+)?been\\s+(?:searched|retrieved|verified|confirmed)\\b");
    private static final Pattern MISSING_TARGET=Pattern.compile("(?i)^(?:is|are|was|were)\\s+(?:missing|absent)\\b");
    private static final Pattern NEITHER_AVAILABLE=Pattern.compile("(?i)^neither\\b.*\\bnor\\b");
    private static final Pattern AVAILABLE=Pattern.compile("(?i)^(?:is|are|was|were)\\s+(?:present|provided|supplied|available|included)\\b");
    private static final Pattern CONTRAST=Pattern.compile("(?i)\\b(?:but|however|nevertheless)\\b|(?<!not )\\byet\\b");
    private static final Pattern COORDINATION=Pattern.compile("(?i)\\b(?:and|or)\\s+");
    private static final Pattern COORDINATED_CLAUSE=Pattern.compile("(?i)\\b(?:and|or)\\s*(?:"+SOURCE_IDENTIFIER.pattern()+"\\s+)?(?:sub[- ]?)?\\s*$");
    private static final Pattern COORDINATED_SUBJECT=Pattern.compile("(?i)^(?:(?:it|they|(?:this|that|the)\\s+clause)\\s+)?(?:(?:also|still)\\s+)?");
    private VettingSourceAttribution() { }

    static List<Map<String,Object>> reasons(List<Record> records,Map<String,Chunk> byId) {
        return reasons(records,byId,Collections.emptyMap());
    }
    static List<Map<String,Object>> reasons(List<Record> records,Map<String,Chunk> byId,Map<String,ObservedSourceClauseLocators.Bound> locators) {
        return reasons(records,byId,locators,Collections.emptyMap());
    }
    static List<Map<String,Object>> reasons(List<Record> records,Map<String,Chunk> byId,Map<String,ObservedSourceClauseLocators.Bound> locators,Map<String,String> packetBindings) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(int i=0;records!=null&&i<records.size();i++) {
            Record r=records.get(i);if(r==null)continue;
            Set<String> owned=new LinkedHashSet<>();List<Map<String,Object>> bindings=new ArrayList<>();
            Map<String,Set<String>> exactQuotedOwners=new LinkedHashMap<>();
            for(Quote q:r.getEvidence()==null?Collections.<Quote>emptyList():r.getEvidence()) {
                Chunk c=q==null?null:byId.get(q.getChunkId());
                if(c==null||!VettingCorpus.evidence(c,q.getSide(),q.getQuote()).isLocated())continue;
                Map<String,Object> binding=VettingReviewProbe.map("chunkId",c.getId(),"role",c.getRole(),"clauseId",c.getClauseId(),"clauseHeadingLocation",c.getClauseHeadingLocation(),"anchor",c.getAnchor());
                ObservedSourceClauseLocators.Bound locator=locators.get(c.getId());if(locator!=null)binding.put("observedSourceClauseLocators",locator.model(c));bindings.add(binding);
                // A literal cross-reference, adjacent heading or array position never grants ownership.
                Set<String> sourceOwned=new LinkedHashSet<>();
                if(Arrays.asList("tender","standard").contains(c.getRole())&&!JsonUtils.isBlankText(c.getClauseId())&&!JsonUtils.isBlankText(c.getClauseHeadingLocation()))sourceOwned.add(canonical(c.getClauseId()));
                if(locator!=null)for(String reference:locator.references(c,q.getQuote()))sourceOwned.add(canonical(reference));
                owned.addAll(sourceOwned);
                if(exactUnique(c.getContent(),q.getQuote()))exactQuotedOwners.computeIfAbsent(c.getId(),key->new LinkedHashSet<>()).addAll(sourceOwned);
            }
            Map<String,String> fields=new LinkedHashMap<>();fields.put("title",r.getTitle());fields.put("comment",r.getComment());fields.put("impact",r.getImpact());fields.put("suggestion",r.getSuggestion());fields.put("factBasis",r.getFactBasis());
            for(int n=0;r.getPendingFacts()!=null&&n<r.getPendingFacts().size();n++)fields.put("pendingFacts["+n+"]",r.getPendingFacts().get(n));
            for(int n=0;r.getChecklistAssessments()!=null&&n<r.getChecklistAssessments().size();n++)if(r.getChecklistAssessments().get(n)!=null)fields.put("checklistAssessments["+n+"].reason",r.getChecklistAssessments().get(n).getReason());
            for(Map.Entry<String,String> field:fields.entrySet()) {
                String text=field.getValue();if(text==null)continue;Matcher mention=CLAUSE.matcher(text);
                while(mention.find()) {
                    if(!assertion(text,mention.start(),mention.end(),"insufficient_context".equals(r.getAssessment())))continue;
                    Matcher number=NUMBERS.matcher(mention.group(2));
                    while(number.find()) {
                        String id=canonical(number.group());String namespace=mention.group(1)==null?null:canonical(mention.group(1));
                        boolean supported=owned.stream().anyMatch(value->owned(value,namespace,id));
                        // A separated, case-sensitive packet citation is transport syntax only when its quoted source owns the base locator.
                        if(!supported){Matcher citation=PACKET_CITATION.matcher(number.group());if(citation.matches()){
                            String source=packetBindings.get(citation.group(2)),base=canonical(citation.group(1));
                            supported=exactQuotedOwners.getOrDefault(source,Collections.emptySet()).stream().anyMatch(value->owned(value,namespace,base));
                        }}
                        if(!supported)out.add(VettingReviewProbe.map("recordIndex",i,"field",field.getKey(),"clauseId",namespace==null?id:namespace+id,
                                "reason","unsupported_source_clause_attribution","quotedSourceBindings",bindings,
                                "requiredCorrection","Use the exact source anchor when source clause ownership is unverified; preserve supported substantive observations and literal references."));
                    }
                }
            }
        }
        return out;
    }

    /** Raw model responses remain unchanged; only an isolated title locator may become a neutral source label. */
    static List<Map<String,Object>> recoverTitleLabels(List<Record> records,Map<String,Chunk> byId,List<Map<String,Object>> reasons) {
        Map<Integer,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        for(Map<String,Object> reason:reasons)if(reason.get("recordIndex") instanceof Integer)
            grouped.computeIfAbsent((Integer)reason.get("recordIndex"),key->new ArrayList<>()).add(reason);
        List<Map<String,Object>> recovered=new ArrayList<>();
        for(Map.Entry<Integer,List<Map<String,Object>>> entry:grouped.entrySet()) {
            int index=entry.getKey();List<Map<String,Object>> failures=entry.getValue();
            if(index<0||index>=records.size()||failures.size()!=1||!"title".equals(failures.get(0).get("field")))continue;
            Record record=records.get(index);String title=record.getTitle();if(title==null)continue;
            Matcher locator=CLAUSE.matcher(title);if(!locator.find()||locator.start()!=0||locator.group(1)==null)continue;
            String family=canonical(locator.group(1)),number=locator.group(2);int end=locator.end();
            if(!NUMBERS.matcher(number).matches()||number.matches("(?is).*\\(\\s*R[0-9]+\\s*\\).*")||locator.find()
                    ||!Objects.equals(family+canonical(number),failures.get(0).get("clauseId")))continue;
            Chunk source=null;boolean exact=record.getEvidence()!=null&&!record.getEvidence().isEmpty();
            for(Quote quote:record.getEvidence()==null?Collections.<Quote>emptyList():record.getEvidence()) {
                Chunk current=quote==null?null:byId.get(quote.getChunkId());
                if(current==null||!Objects.equals(current.getId(),quote.getChunkId())||!"source".equals(quote.getSide())
                        ||!"tender".equals(current.getRole())||JsonUtils.isBlankText(current.getDocumentId())
                        ||!exactUnique(current.getContent(),quote.getQuote())||source!=null&&!Objects.equals(source.getId(),current.getId())){exact=false;break;}
                source=current;
            }
            if(!exact||source==null||source.getFileName()==null
                    ||!Pattern.compile("(?i)(?:^|[^A-Z])"+Pattern.quote(family)+"(?:$|[^A-Z])").matcher(source.getFileName()).find())continue;
            String replacement=family+" passage"+title.substring(end);
            record.setTitle(replacement);
            recovered.add(VettingReviewProbe.map("recordIndex",index,"originalTitle",title,"title",replacement,
                    "sourceChunkId",source.getId(),"documentId",source.getDocumentId(),"sourceAnchor",source.getAnchor()));
        }
        return recovered;
    }

    private static boolean exactUnique(String text,String quote){return text!=null&&quote!=null&&quote.length()>=12&&text.indexOf(quote)>=0&&text.indexOf(quote)==text.lastIndexOf(quote);}

    private static boolean owned(String value,String namespace,String number) {
        Matcher m=OWNER.matcher(value);if(!m.matches())return namespace==null&&value.equals(number);
        return number.equals(m.group(2))&&(namespace==null||namespace.equals(m.group(1).replaceAll("[.]$","")));
    }
    private static boolean assertion(String text,int start,int end,boolean pending) {
        int a=start,b=end;while(a>0&&!boundary(text,a-1))a--;while(b<text.length()&&!boundary(text,b))b++;
        String before=text.substring(a,start).trim(),after=text.substring(end,b).trim();
        if(QUESTION.matcher(before).find()||b<text.length()&&text.charAt(b)=='?')return false;
        if(REFERENCE.matcher(before).find()||UNKNOWN_TARGET.matcher(after).find())return false;
        // Missing-evidence framing applies to this mention only, never to the entire assessment.
        if(pending&&!CONTRAST.matcher(before).find()&&!CONTRAST.matcher(after).find()&&!coordinatedPredicate(after)){
            if(NOT_SEARCHED.matcher(after).find()||MISSING_TARGET.matcher(after).find()
                    ||NEITHER_AVAILABLE.matcher(before).find()&&AVAILABLE.matcher(after).find())return false;
            if(!COORDINATED_CLAUSE.matcher(before).find()
                    &&(CHECKLIST_QUESTION.matcher(before).find()||UNVERIFIABLE_QUESTION.matcher(before).find()))return false;
        }
        // shortcut: only explicit English clause locator/predicate forms are mechanically checked; broader attribution semantics need a typed source-claim contract.
        return PREDICATE.matcher(after).find()||LOCATION.matcher(before).find();
    }
    private static boolean coordinatedPredicate(String text){
        Matcher join=COORDINATION.matcher(text);while(join.find())if(PREDICATE.matcher(COORDINATED_SUBJECT.matcher(text.substring(join.end())).replaceFirst("")).find())return true;
        return false;
    }
    private static boolean boundary(String text,int at) {
        char c=text.charAt(at);if(c!='.')return c=='\n'||c=='?'||c=='!'||c==';';
        if(at>0&&at+1<text.length()&&Character.isDigit(text.charAt(at-1))&&Character.isDigit(text.charAt(at+1)))return false;
        Matcher identifier=SOURCE_IDENTIFIER.matcher(text);
        while(identifier.find())if(at>=identifier.start()&&at<identifier.end())return false;
        return true;
    }
    private static String canonical(String value){return value.replaceAll("[\\s\\p{Z}]+","").toUpperCase(Locale.ROOT);}
}
