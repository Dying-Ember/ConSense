package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.vetting.VettingSemanticReview.Quote;
import com.consense.service.vetting.VettingSemanticReview.Record;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Packet citation syntax must not invent, remove or borrow native clause ownership. */
class VettingSourceCitationBoundaryTest {
    private static final String NTT_QUOTE="For the same monthly interim payment, the Employer shall pay each certified amount within 14 days after certification.";
    private static final String SCC_QUOTE="The Contractor shall submit an interim payment statement every month. The Employer shall pay each certified amount within 21 days after certification.";

    @Test void exactObservedPacketCitationsAreSeparateFromBothClauseOwnersAndLeaveRawFieldsUntouched(){
        Fixture f=new Fixture();Record record=f.record("NTT Clause 1 (R5) specifies 'within 14 days'. SCC Clause 1 (R12) specifies 'within 21 days'. Both refer to 'each certified amount' for 'monthly interim payment'.");
        String before=JsonUtils.write(record),sourcesBefore=JsonUtils.write(f.sources),bindingsBefore=JsonUtils.write(f.bindings);
        assertEquals(2,VettingSourceAttribution.reasons(Collections.singletonList(record),f.sources).size());
        assertEquals(2,VettingSourceAttribution.reasons(Collections.singletonList(record),f.sources,Collections.emptyMap()).size());
        assertTrue(f.reasons(record).isEmpty());assertEquals(before,JsonUtils.write(record));assertEquals(sourcesBefore,JsonUtils.write(f.sources));assertEquals(bindingsBefore,JsonUtils.write(f.bindings));
    }

    @Test void knownPacketAliasesCannotAuthorizeAnotherSourceOrAnUnownedBaseClause(){
        Fixture f=new Fixture();
        for(String assertion:Arrays.asList("NTT Clause 1 (R99) specifies a payment deadline.","NTT Clause 1 (R12) specifies a payment deadline.","SCC Clause 1 (R5) specifies a payment deadline.","NTT Clause 99 (R5) specifies a payment deadline.","NTT Clause 1 (R0) specifies a payment deadline.","NTT Clause 1 (R05) specifies a payment deadline.")){
            assertEquals(1,f.reasons(f.record(assertion)).size(),assertion);
        }
        Record onlyOtherSource=f.record("NTT Clause 1 (R5) specifies a payment deadline.");onlyOtherSource.setEvidence(Collections.singletonList(f.quote(f.scc,SCC_QUOTE)));
        assertEquals(1,f.reasons(onlyOtherSource).size(),"A packet directory entry is insufficient without this record quoting that source");
        Record withoutEvidence=f.record("NTT Clause 1 (R5) specifies a payment deadline.");withoutEvidence.setEvidence(Collections.emptyList());assertEquals(1,f.reasons(withoutEvidence).size());
        Map<String,String> foreignBinding=new LinkedHashMap<>(f.bindings);foreignBinding.put("R5",f.scc.getId());
        assertEquals(1,VettingSourceAttribution.reasons(Collections.singletonList(f.record("NTT Clause 1 (R5) specifies a payment deadline.")),f.sources,Collections.emptyMap(),foreignBinding).size());
    }

    @Test void lowercaseUnseparatedAndRealChildSyntaxIsNeverBlindlyDeleted(){
        Fixture f=new Fixture();
        for(String clause:Arrays.asList("1(r5)","1(R5)","1 (r5)","1(a)(r5)","1(a) (R5)","1 (a) (R5)")){
            Record record=f.record("NTT Clause "+clause+" specifies a payment deadline.");String before=JsonUtils.write(record);
            assertEquals(1,f.reasons(record).size(),clause);assertEquals(before,JsonUtils.write(record));
        }
        for(String owned:Arrays.asList("NTT1(r5)","NTT1(R5)")){
            f.ntt.setClauseId(owned);
            for(String suffix:Arrays.asList("1(r5)","1 (R5)"))assertTrue(f.reasons(f.record("NTT Clause "+suffix+" specifies a payment deadline.")).isEmpty(),"An exact original clause owner takes precedence: "+owned+" / "+suffix);
        }
    }

    @Test void citationFallbackNeedsAnExactUniqueQuoteInThisRecordInsteadOfNormalizedLocationAlone(){
        Fixture f=new Fixture();
        for(String quote:Arrays.asList(NTT_QUOTE.replace(" ","  "),"Employer",NTT_QUOTE.toUpperCase(Locale.ROOT))){
            Record record=f.record("NTT Clause 1 (R5) specifies a payment deadline.");record.setEvidence(Collections.singletonList(f.quote(f.ntt,quote)));
            assertEquals(1,f.reasons(record).size(),quote);
        }
        assertTrue(VettingCorpus.evidence(f.ntt,"source",NTT_QUOTE.replace(" ","  ")).isLocated(),"The legacy quotation gate is intentionally weaker than packet-citation ownership");
        f.ntt.setContent(NTT_QUOTE+" "+NTT_QUOTE);
        assertTrue(VettingCorpus.evidence(f.ntt,"source",NTT_QUOTE).isLocated());assertEquals(1,f.reasons(f.record("NTT Clause 1 (R5) specifies a payment deadline.")).size());
    }

    @Test void packetCitationSupportDoesNotCrossRecordBoundariesOrReplaceMissingSourceAuthority(){
        Fixture f=new Fixture();Record first=f.record("The supplied excerpt is retained for review.");
        Record second=f.record("NTT Clause 1 (R5) specifies a payment deadline.");second.setEvidence(Collections.singletonList(f.quote(f.scc,SCC_QUOTE)));
        assertEquals(1,VettingSourceAttribution.reasons(Arrays.asList(first,second),f.sources,Collections.emptyMap(),f.bindings).size());
        for(String variation:Arrays.asList("no heading","no clause","project fact")){
            Fixture changed=new Fixture();if("no heading".equals(variation))changed.ntt.setClauseHeadingLocation(null);if("no clause".equals(variation))changed.ntt.setClauseId(null);if("project fact".equals(variation))changed.ntt.setRole("project_fact");
            assertEquals(1,changed.reasons(changed.record("NTT Clause 1 (R5) specifies a payment deadline.")).size(),variation);
        }
    }

    @Test void everyPublishedFieldStillChecksTheBaseClauseAndRetainsTheOriginalNarrative(){
        for(String field:Arrays.asList("title","comment","impact","suggestion","factBasis","pendingFacts","checklistAssessments")){
            Fixture f=new Fixture();ObjectNode raw=JsonUtils.mapper().valueToTree(f.record("Source comparison only."));
            String unsupported="NTT Clause 99 (R5) specifies a payment deadline.";
            if("pendingFacts".equals(field))raw.putArray(field).add(unsupported);
            else if("checklistAssessments".equals(field))raw.putArray(field).addObject().put("itemId","APP-I-002").put("reason",unsupported);
            else raw.put(field,unsupported);
            Record record=JsonUtils.read(JsonUtils.write(raw),Record.class);String before=JsonUtils.write(record);
            assertEquals(1,f.reasons(record).size(),field);assertEquals(before,JsonUtils.write(record));
        }
    }

    private static final class Fixture {
        final Chunk ntt=source("authored-ntt","tender","NTT1",NTT_QUOTE),scc=source("authored-scc","standard","SCC1",SCC_QUOTE);
        final Map<String,Chunk> sources=new LinkedHashMap<>();final Map<String,String> bindings=new LinkedHashMap<>();
        Fixture(){sources.put(ntt.getId(),ntt);sources.put(scc.getId(),scc);bindings.put("R5",ntt.getId());bindings.put("R12",scc.getId());}
        Record record(String factBasis){Record record=new Record();record.setFactBasis(factBasis);record.setEvidence(Arrays.asList(quote(ntt,NTT_QUOTE),quote(scc,SCC_QUOTE)));return record;}
        Quote quote(Chunk source,String text){Quote quote=new Quote();quote.setChunkId(source.getId());quote.setSide("source");quote.setQuote(text);return quote;}
        List<Map<String,Object>> reasons(Record record){return VettingSourceAttribution.reasons(Collections.singletonList(record),sources,Collections.emptyMap(),bindings);}
        private static Chunk source(String id,String role,String clause,String text){Chunk source=VettingStrictHybridTest.source(id,role,text);source.setClauseId(clause);source.setClauseHeadingLocation("body/1/paragraph");return source;}
    }
}
