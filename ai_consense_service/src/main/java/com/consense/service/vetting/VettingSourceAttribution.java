package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks explicit source-location assertions without rewriting any model text. */
final class VettingSourceAttribution {
    static final String POLICY="explicit-source-clause-attribution-v3:bound-packet-citation-v1:"+ObservedSourceClauseLocators.VERSION;
    private static final String NUMBER="\\d+(?:\\s*\\.\\s*\\d+)*(?:\\s*\\([a-z0-9]+\\))*";
    private static final Pattern CLAUSE=Pattern.compile("(?i)(?:(NTT|SCT|SCC|GCT|GCC|PRE|FT|AA|GS|SL)\\s+)?\\bClauses?\\s+("+NUMBER+"(?:\\s*(?:,|and|or|to)\\s*(?:Clauses?\\s+)?"+NUMBER+")*)");
    private static final Pattern NUMBERS=Pattern.compile(NUMBER,Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKET_CITATION=Pattern.compile("(?s)^(.*\\S)[\\s\\p{Z}]+\\((R[1-9][0-9]*)\\)$");
    private static final Pattern OWNER=Pattern.compile("^([A-Z][A-Z.]*?)[.]?(\\d.*)$");
    private static final Pattern QUESTION=Pattern.compile("(?i)^(?:(?:please\\s+)?(?:confirm|check|verify|review|determine)\\s+)?(?:whether|if|does|do|is|are|was|were|can|could|would|should|which|what|how)\\b|\\b(?:unknown|unverified|unconfirmed|uncertain)\\b.*\\bwhether\\b");
    private static final Pattern REFERENCE=Pattern.compile("(?i)(?:\\b(?:cross[- ]?)?references?\\s+to\\s*(?:(?:the|non-existent|unverified|missing|internal|external)\\s+)*|\\b(?:cites?|citing|refers?\\s+to)\\s*)$");
    private static final Pattern UNKNOWN_TARGET=Pattern.compile("(?i)^\\s*(?:itself\\s+)?(?:is|are|was|were|remains?)\\s+(?:(?:still|currently)\\s+)?(?:unknown|unverified|unconfirmed|uncertain|not\\s+(?:provided|supplied|available|verified|confirmed|established|included))\\b");
    private static final Pattern PREDICATE=Pattern.compile("(?i)^\\s*(?:(?:of|in)\\s+[^.;?!]{0,100}?\\s+)?(?:states?|requires?|directs?|provides?|stipulates?|imposes?|specifies?|sets?|mandates?|defines?|contains?|governs?|obliges?|establishes?|says?|covers?|is|are|has|have)\\b");
    private static final Pattern LOCATION=Pattern.compile("(?i)\\b(?:between|under|pursuant\\s+to|according\\s+to|in|within|at|amend|delete|revise|modify|replace|align|either)\\s*$");
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
                    if(!assertion(text,mention.start(),mention.end()))continue;
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

    private static boolean exactUnique(String text,String quote){return text!=null&&quote!=null&&quote.length()>=12&&text.indexOf(quote)>=0&&text.indexOf(quote)==text.lastIndexOf(quote);}

    private static boolean owned(String value,String namespace,String number) {
        Matcher m=OWNER.matcher(value);if(!m.matches())return namespace==null&&value.equals(number);
        return number.equals(m.group(2))&&(namespace==null||namespace.equals(m.group(1).replaceAll("[.]$","")));
    }
    private static boolean assertion(String text,int start,int end) {
        int a=start,b=end;while(a>0&&!boundary(text,a-1))a--;while(b<text.length()&&!boundary(text,b))b++;
        String before=text.substring(a,start).trim(),after=text.substring(end,b).trim();
        if(QUESTION.matcher(before).find()||b<text.length()&&text.charAt(b)=='?')return false;
        if(REFERENCE.matcher(before).find()||UNKNOWN_TARGET.matcher(after).find())return false;
        // shortcut: only explicit English clause locator/predicate forms are mechanically checked; broader attribution semantics need a typed source-claim contract.
        return PREDICATE.matcher(after).find()||LOCATION.matcher(before).find();
    }
    private static boolean boundary(String text,int at) {
        char c=text.charAt(at);return c=='\n'||c=='?'||c=='!'||c==';'||c=='.'&&!(at>0&&at+1<text.length()&&Character.isDigit(text.charAt(at-1))&&Character.isDigit(text.charAt(at+1)));
    }
    private static String canonical(String value){return value.replaceAll("[\\s\\p{Z}]+","").toUpperCase(Locale.ROOT);}
}
