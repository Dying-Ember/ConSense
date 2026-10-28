package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored source-bound text fixtures; no source reads, retrieval models or LLM calls. */
class OerReviewTextContextTest {
    @Test void namedAppendixProjectVariantRetainsItsUnownedStorageParagraph() {
        assertAppendixStorage(false);
    }
    @Test void namedAppendixProjectVariantRetainsItsDeclaredStorageParagraph() {
        assertAppendixStorage(true);
    }
    private static void assertAppendixStorage(boolean declared) {
        String clause=declared?"PRE.B8.APPEND12.P":null,heading=declared?"heading:append12":null;
        VettingCorpus.Chunk seed=chunk("seed",0,"The material-control requirements appear later in this source.",null,null);
        VettingCorpus.Chunk preceding=chunk("preceding",1,"PRE.B8.100.P Unrelated preceding requirements","PRE.B8.100.P","heading:preceding");
        VettingCorpus.Chunk appendix=chunk("appendix",40,"PRE.B8.APPEND12.P Guidelines on Scope and Contents of Quality Control System",clause,heading);
        VettingCorpus.Chunk storage=chunk("storage",41,"3.3. Storage",clause,heading);
        VettingCorpus.Chunk body=chunk("storage-a",42,"a. Submit the authored material storage plan to the CM within three months from commencement and update it when the storage arrangements change.",clause,heading);
        VettingCorpus.Chunk next=chunk("next",43,"PRE.B8.APPEND13.P Unrelated next appendix","PRE.B8.APPEND13.P","heading:next");
        List<VettingCorpus.Chunk> corpus=Arrays.asList(seed,preceding,appendix,storage,body,next);
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(seed),unknown(seed),"Retrieve PRE.B8.APPEND12 for the material storage plan submission time.",2000);
        assertTrue(ids(selected).containsAll(Arrays.asList("appendix","storage","storage-a")),"The explicitly requested appendix must supply its operative storage paragraph");
        assertFalse(ids(selected).contains("next"));assertEquals(JsonUtils.write(body),JsonUtils.write(find(selected,"storage-a")));
        assertFalse(ids(select(corpus,Collections.singletonList(seed),unknown(seed),"Retrieve PRE.B8.APPEND120 for the material storage plan submission time.",2000)).contains("storage-a"),"An appendix prefix must not match another number");
    }

    @Test void assignedExcavationClauseBodyOutranksUnrelatedSeedsWithinSameBudget() {
        List<VettingCorpus.Chunk> corpus=new ArrayList<>(),seeds=new ArrayList<>(),target=new ArrayList<>();
        for(int i=0;i<9;i++){
            VettingCorpus.Chunk noise=chunk("noise-"+i,i,sized("Unrelated pricing context "+i+": ",1000),"PRE.B1."+(10+i)+".P","heading:noise-"+i);
            corpus.add(noise);seeds.add(noise);
        }
        String[] text={"PRE.B6.810.P EXCAVATION PERMIT FOR WORKS","1. The following authored Site portions are subject to the specified excavation permit arrangements:",
                "a. On-Site approach works.","b. Areas outside the Site boundary for associated road works.","c. Areas outside the Site boundary for temporary perimeter works.",
                "d. Areas outside the Site boundary for utility connections.","e. Areas outside the Site boundary for monitoring stations.","f. Areas outside the Site boundary for utility diversions.","g. Areas outside the Site boundary for public landscaping."};
        int[] lengths={52,267,181,142,213,142,74,90,86};
        for(int i=0;i<text.length;i++)target.add(chunk("excavation-"+i,20+i,sized(text[i],lengths[i]),"PRE.B6.810.P","heading:excavation"));
        corpus.addAll(target);seeds.add(target.get(0));
        corpus.add(chunk("next",29,"PRE.B6.820.P Other requirements","PRE.B6.820.P","heading:next"));
        assertEquals(1247,target.stream().mapToInt(c->c.getContent().length()).sum());
        assertEquals(9052,seeds.stream().mapToInt(c->c.getContent().length()).sum());
        List<VettingCorpus.Chunk> selected=select(corpus,seeds,unknown(target.get(0)),"PRE.B6; 10. PRE.B6.810 sub-clause 1; 11. PRE.B6.APPEND5; Site portions SCC22.3(11), outside Site excavation permits; contractor design items PRE.B6.085(1)(e)(f), design intent and drawing number references Appendix B",10000);
        assertTrue(ids(selected).containsAll(ids(target)),"The complete 1247-character assigned clause fits when one unrelated seed is deferred");
        assertTrue(selected.stream().mapToInt(c->c.getContent().length()).sum()<=10000);assertFalse(ids(selected).contains("next"));
        for(VettingCorpus.Chunk original:target)assertEquals(JsonUtils.write(original),JsonUtils.write(find(selected,original.getId())));
    }
    private static String sized(String prefix,int length){assertTrue(prefix.length()<=length);return prefix+"x".repeat(length-prefix.length());}

    @Test void technicalIdentifiersRetainFourDigitComponentsAndTheirUnownedItems() {
        VettingCorpus.Chunk title=chunk("title",0,"PRE.B8.1422.P PROVISION OF BUILDER'S WORK",null,null);
        VettingCorpus.Chunk first=chunk("first",1,"1. Prepare the required builder's work details.",null,null);
        VettingCorpus.Chunk second=chunk("second",2,"2. Submit the drawings for the CM's approval.",null,null);
        VettingCorpus.Chunk qualifier=chunk("qualifier",3,"Retain the stated condition and responsible role.",null,null);
        VettingCorpus.Chunk next=chunk("next",4,"PRE.B8.1430.P OTHER WORK",null,null);
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(title,first,second,qualifier,next),Collections.singletonList(title),unknown(title),"Retrieve PRE.B8.1422",1000);
        assertEquals(Arrays.asList("title","first","second","qualifier"),ids(selected));
        assertNull(title.getClauseId());assertFalse(ids(selected).contains("next"));
    }
    @Test void aNamedFamilyClauseRecoversTheDeletionPreambleForItsListItem() {
        VettingCorpus.Chunk title=chunk("title",0,"SCC1.302 Definitions",null,null);
        VettingCorpus.Chunk earlier=chunk("earlier",1,"(1) Other source definitions apply.",null,null);
        VettingCorpus.Chunk preamble=chunk("preamble",2,"(3) Clause 1.1 is amended by deleting the following definitions:",null,null);
        VettingCorpus.Chunk sibling=chunk("sibling",3,"Nominated Sub-contractor",null,null);
        VettingCorpus.Chunk seed=chunk("seed",4,"Prime Cost Sum",null,null);
        VettingCorpus.Chunk next=chunk("next",5,"SCC1.303 Other definitions",null,null);
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(title,earlier,preamble,sibling,seed,next),Collections.singletonList(seed),unknown(seed),"Prime Cost Sum",1000);
        assertEquals(Arrays.asList("title","earlier","preamble","sibling","seed"),ids(selected));
        assertNull(seed.getClauseId());assertFalse(ids(selected).contains("next"));
    }
    @Test void dottedTechnicalFamiliesRetainTheirExistingReadingContext() {
        for(String[] labels:Arrays.asList(new String[]{"GCC.1.1","GCC.1.2"},new String[]{"PRE.5.7","PRE.5.8"},new String[]{"A.1","A.2"})){
            String label=labels[0];
            VettingCorpus.Chunk title=chunk("title",0,label+" Existing heading",null,null);
            VettingCorpus.Chunk body=chunk("body",1,"1. Preserve the stated source duty.",null,null);
            VettingCorpus.Chunk next=chunk("next",2,labels[1]+" Next peer heading",null,null);
            assertEquals(Arrays.asList("title","body"),ids(select(Arrays.asList(title,body,next),Collections.singletonList(title),unknown(title),"Read "+label,1000)),label);
        }
    }
    @Test void unownedProjectVariantHeadingRetainsItsFollowingSourceText() {
        VettingCorpus.Chunk title=chunk("title",7,"APU.A010.P",null,null);
        VettingCorpus.Chunk label=chunk("label",8,"LIST OF REFERENCE DRAWINGS",null,null);
        VettingCorpus.Chunk body=chunk("body",9,"Reference drawings are for reference only and do not form part",null,null);
        VettingCorpus.Chunk continuation=chunk("continuation",10,"of the Contract. Their accuracy is not assured.",null,null);
        VettingCorpus.Chunk next=chunk("next",11,"APU.A020.P OTHER INFORMATION",null,null);
        List<VettingCorpus.Chunk> corpus=Arrays.asList(title,label,body,continuation,next);
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(title),unknown(title),"reference drawings",1000);
        assertEquals(Arrays.asList("title","label","body","continuation"),ids(selected));
        assertNull(title.getClauseId());assertNull(body.getClauseId());
        assertEquals(JsonUtils.write(body),JsonUtils.write(find(selected,"body")));
    }

    @Test void baseClauseRequestFindsItsObservedProjectVariantAfterAnUnownedPageHeader() {
        VettingCorpus.Chunk header=chunk("header",0,"APPENDIX - REFERENCE LIST",null,null);
        List<VettingCorpus.Chunk> corpus=new ArrayList<>();corpus.add(header);
        for(int i=1;i<7;i++)corpus.add(chunk("cover-"+i,i,"Unowned cover text",null,null));
        VettingCorpus.Chunk title=chunk("title",7,"APU.A010.P",null,null);
        VettingCorpus.Chunk label=chunk("label",8,"LIST OF REFERENCE DRAWINGS",null,null);
        VettingCorpus.Chunk body=chunk("body",9,"Reference drawings do not form part of the Contract.",null,null);
        VettingCorpus.Chunk next=chunk("next",10,"APU.A020.P OTHER INFORMATION",null,null);
        corpus.addAll(Arrays.asList(title,label,body,next));
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(header),unknown(header),"Retrieve APU.A010 for reference drawings",1000);
        assertTrue(ids(selected).containsAll(Arrays.asList("title","label","body")));
        assertFalse(ids(selected).contains("next"));assertNull(title.getClauseId());
        assertFalse(ids(select(corpus,Collections.singletonList(header),unknown(header),"Retrieve APU.A01 for reference drawings",1000)).contains("body"));
    }

    @Test void technicalProjectClauseRetainsItsUnownedNumberedItemsUntilTheNextTechnicalPeer() {
        VettingCorpus.Chunk title=chunk("title",0,"PRE.B12.230.P BUILDER'S WORK",null,null);
        VettingCorpus.Chunk first=chunk("first",1,"1. Submit particulars of the necessary builder's work for agreement.",null,null);
        VettingCorpus.Chunk second=chunk("second",2,"2. Execute that work after the CM's agreement.",null,null);
        VettingCorpus.Chunk detail=chunk("detail",3,"Retain the stated attendance duties.",null,null);
        VettingCorpus.Chunk next=chunk("next",4,"PRE.B12.240.P OTHER ATTENDANCE",null,null);
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(title,first,second,detail,next),Collections.singletonList(title),unknown(title),"Retrieve PRE.B12.230",1000);
        assertEquals(Arrays.asList("title","first","second","detail"),ids(selected));
        assertNull(first.getClauseId());assertFalse(first.getCanonicalPayload().path("semanticScopeVerified").asBoolean());
    }

    @Test void aTechnicalTextHeadingCannotOverrideDeclaredDifferentChildOwnership() {
        VettingCorpus.Chunk title=chunk("title",0,"PRE.B12.230.P BUILDER'S WORK",null,null);
        VettingCorpus.Chunk foreign=chunk("foreign",1,"1. A separately owned requirement.","1","declared-other-heading");
        assertEquals(Collections.singletonList("title"),ids(select(Arrays.asList(title,foreign),Collections.singletonList(title),unknown(title),"Retrieve PRE.B12.230",1000)));
    }

    @Test void splitQueryBudgetFallbackRetainsRankedSeedIdentity()throws Exception {
        OerBusinessEvidence oer=org.mockito.Mockito.mock(OerBusinessEvidence.class);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);
        OerBusinessEvidence.Session session=ctor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        VettingSemanticReview review=new VettingSemanticReview(null,new com.consense.config.ConsenseProperties(),null);review.setOerBusinessEvidence(oer);
        VettingCorpus.Chunk seed=chunk("seed",1,"Ranked tender text.",null,null),context=chunk("context",0,"Observed preceding context.",null,null);
        org.mockito.Mockito.when(oer.retrieveReviewText(org.mockito.ArgumentMatchers.eq(session),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("tender"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(10000)))
                .thenThrow(new com.consense.common.BizException("HTTP 422 {\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}"))
                .thenReturn(new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(context,seed),Collections.singleton("seed")));
        String query=String.join(" ",Collections.nCopies(150,"obligation"));
        List<VettingCorpus.Chunk> found=review.retrieveDirectionText(session,query,"tender",()->{},10000);
        assertTrue(OerBusinessEvidence.rankedReviewOrigin(found,seed));assertFalse(OerBusinessEvidence.rankedReviewOrigin(found,context));
        org.mockito.Mockito.verify(oer,org.mockito.Mockito.atLeast(3)).retrieveReviewText(org.mockito.ArgumentMatchers.eq(session),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("tender"),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(10000));
    }
    @Test void ordinaryLeadingQuantitiesDoNotOpenOrTerminateAClause() {
        VettingCorpus.Chunk title=chunk("title",0,"5.7 Completion period",null,null);
        VettingCorpus.Chunk seed=chunk("seed",1,"The following period applies.",null,null);
        VettingCorpus.Chunk duration=chunk("duration",2,"12 months shall be allowed for completion.",null,null);
        VettingCorpus.Chunk amount=chunk("amount",3,"100 dollars per day shall be payable.",null,null);
        VettingCorpus.Chunk next=chunk("next",4,"5.8 Other obligations",null,null);
        assertEquals(Arrays.asList("title","seed","duration","amount"),ids(select(Arrays.asList(title,seed,duration,amount,next),Collections.singletonList(seed),unknown(seed),"completion period",1000)));
        List<VettingCorpus.Chunk> unknownRows=new ArrayList<>();unknownRows.add(duration);
        for(int i=3;i<30;i++)unknownRows.add(chunk("ordinary-"+i,i,"Ordinary source text.",null,null));
        assertEquals(2,select(unknownRows,Collections.singletonList(duration),unknown(duration),"completion period",10000).size());
    }
    @Test void repeatedTextHeadingWithoutLocationsDoesNotJoinItsOtherOccurrence() {
        VettingCorpus.Chunk earlier=chunk("earlier",0,"5.7 Earlier section",null,null);
        VettingCorpus.Chunk prior=chunk("prior",1,"Earlier terms.",null,null);
        VettingCorpus.Chunk current=chunk("current",2,"5.7 Current section",null,null);
        VettingCorpus.Chunk body=chunk("body",3,"Current terms.",null,null);
        assertEquals(Arrays.asList("current","body"),ids(select(Arrays.asList(earlier,prior,current,body),Collections.singletonList(current),unknown(current),"Retrieve clause 5.7",1000)));
    }
    @Test void finalDirectionalBudgetKeepsRankedTenderInsteadOfCheaperPrecedingContext()throws Exception {
        VettingCorpus.Chunk context=chunk("context",0,String.join("",Collections.nCopies(100,"c")),null,null);
        VettingCorpus.Chunk seed=chunk("seed",1,String.join("",Collections.nCopies(900,"s")),null,null);
        VettingCorpus.Chunk reference=chunk("reference","2","standard","current",0,String.join("",Collections.nCopies(9500,"r")),"5.7","reference-heading");
        List<VettingCorpus.Chunk> retrieved=new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(context,seed),Collections.singleton("seed"));
        java.lang.reflect.Method interleave=VettingSemanticReview.class.getDeclaredMethod("interleaveDirectionHits",List.class);interleave.setAccessible(true);
        @SuppressWarnings("unchecked") List<VettingCorpus.Chunk> ranked=(List<VettingCorpus.Chunk>)interleave.invoke(null,Collections.singletonList(retrieved));
        List<VettingCorpus.Chunk> references=Collections.singletonList(reference);
        List<VettingCorpus.Chunk> offered=VettingSemanticReview.directionEvidenceOrder(ranked,references,references,Collections.emptyList(),10000);
        List<VettingCorpus.Chunk> selected=VettingSemanticReview.directionSelection(offered,Collections.emptyList(),10000).getChunks();
        assertEquals(Arrays.asList("context","seed"),ids(selected),"Source ordering happens after retaining the actual ranked tender anchor");
        assertFalse(ids(selected).contains("reference"),"A reference that cannot fit with the ranked subject must remain pending");
    }
    @Test void shortSeedRetainsItsObservedMembersAsExactSourceOrderedText() {
        VettingCorpus.Chunk heading=chunk("heading",0,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk seed=chunk("seed",1,"Subject to the following conditions:","5.7","heading-57");
        VettingCorpus.Chunk body=chunk("body",2,"The Contractor shall complete the Works within the stated period.","5.7","heading-57");
        List<VettingCorpus.Chunk> corpus=Arrays.asList(body,seed,heading);
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(seed),observed(seed,body,seed,heading),"duration",1000);
        assertEquals(Arrays.asList("heading","seed","body"),ids(selected));
        for(VettingCorpus.Chunk original:corpus)assertEquals(JsonUtils.write(original),JsonUtils.write(find(selected,original.getId())));
        assertNotEquals(seed.getSourceHash(),seed.getCanonicalEvidence().getJavaParsedSourceHash(),"Canonical and parsed hashes are distinct identities");
    }

    @Test void namedClauseFollowupAddsRelatedIdsEvenWhenRetrievalRepeatsItsSeed() {
        VettingCorpus.Chunk seed=chunk("seed",0,"5.6 The duration is specified separately.","5.6","heading-56");
        VettingCorpus.Chunk boundary=chunk("boundary",1,"5.61 An unrelated requirement.","5.61","heading-561");
        VettingCorpus.Chunk heading=chunk("requested-heading",8,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk body=chunk("requested-body",9,"The completion period is twelve months.","5.7","heading-57");
        VettingCorpus.Chunk next=chunk("next-clause",10,"5.8 Payment", "5.8","heading-58");
        List<VettingCorpus.Chunk> corpus=Arrays.asList(seed,boundary,heading,body,next);
        List<VettingCorpus.Chunk> initial=select(corpus,Collections.singletonList(seed),unknown(seed),"duration evidence",1000);
        List<VettingCorpus.Chunk> followup=select(corpus,Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7 to establish the completion period.",1000);
        assertFalse(ids(initial).contains("requested-body"));
        assertTrue(ids(followup).containsAll(Arrays.asList("seed","requested-heading","requested-body")));
        assertFalse(ids(followup).contains("next-clause"));
        assertTrue(ids(followup).stream().anyMatch(id->!ids(initial).contains(id)),"A named followup must provide new relevant text");
    }

    @Test void namedPrefixedClauseUsesItsExactHeadingInsteadOfOnlyDecimalClauseSyntax() {
        VettingCorpus.Chunk seed=chunk("seed",0,"The required provision is in the preliminaries.",null,null);
        VettingCorpus.Chunk boundary=chunk("boundary",1,"PRE.B11.009 Previous provision", "PRE.B11.009","heading-009");
        VettingCorpus.Chunk heading=chunk("requested-heading",8,"PRE.B11.010 Bond", "PRE.B11.010","heading-010");
        VettingCorpus.Chunk body=chunk("requested-body",9,"The tendered bond wording shall be supplied.","PRE.B11.010","heading-010");
        VettingCorpus.Chunk next=chunk("next-clause",10,"PRE.B11.011 Other provision", "PRE.B11.011","heading-011");
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(seed,boundary,heading,body,next),Collections.singletonList(seed),unknown(seed),"Retrieve PRE.B11.010 for the bond wording.",1000);
        assertTrue(ids(selected).containsAll(Arrays.asList("requested-heading","requested-body")));
        assertFalse(ids(selected).contains("next-clause"));
    }

    @Test void exactTextHeadingCanLocateBoundedContextWithoutInventingClauseOwnership() {
        VettingCorpus.Chunk seed=chunk("seed",0,"The requested terms occur later in the source.",null,null);
        VettingCorpus.Chunk boundary=chunk("boundary",1,"5.6 Another provision", "5.6","heading-56");
        VettingCorpus.Chunk heading=chunk("text-heading",8,"5.7 Duration",null,null);
        VettingCorpus.Chunk body=chunk("text-body",9,"The completion period is twelve months.",null,null);
        VettingCorpus.Chunk next=chunk("text-next",10,"5.8 Payment",null,null);
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(seed,boundary,heading,body,next),Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7 for the completion period.",1000);
        assertTrue(ids(selected).containsAll(Arrays.asList("text-heading","text-body")));
        assertFalse(ids(selected).contains("text-next"));
        for(String id:Arrays.asList("text-heading","text-body")){VettingCorpus.Chunk value=find(selected,id);assertNull(value.getClauseId());assertNull(value.getClauseHeadingLocation());assertFalse(value.getCanonicalPayload().path("semanticScopeVerified").asBoolean());}
    }

    @Test void sameClauseNumberDoesNotBridgeDocumentsRolesOrSourceRevisions() {
        VettingCorpus.Chunk seed=chunk("seed",0,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk body=chunk("body",1,"The completion period is twelve months.","5.7","heading-57");
        VettingCorpus.Chunk otherDocument=chunk("other-document","2","tender","current",2,"Foreign document text.","5.7","heading-57");
        VettingCorpus.Chunk otherRole=chunk("other-role","1","standard","current",2,"Foreign role text.","5.7","heading-57");
        VettingCorpus.Chunk otherRevision=chunk("other-revision","1","tender","old",2,"Superseded revision text.","5.7","heading-57");
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(otherRevision,seed,otherDocument,body,otherRole),Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7.",1000);
        assertEquals(Arrays.asList("seed","body"),ids(selected));
    }

    @Test void projectionRevisionChangeCannotJoinAnOtherwiseMatchingSource() {
        VettingCorpus.Chunk seed=chunk("seed",0,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk current=chunk("current",1,"Current projection text.","5.7","heading-57");
        VettingCorpus.Chunk old=chunk("old",2,"Different projection text.","5.7","heading-57");
        old.getCanonicalEvidence().setProjectionRevisionSha256(sha("other projection revision"));
        ((ObjectNode)old.getCanonicalPayload().path("evidenceRef")).put("projectionRevisionSha256",old.getCanonicalEvidence().getProjectionRevisionSha256());
        assertEquals(Arrays.asList("seed","current"),ids(select(Arrays.asList(seed,current,old),Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7.",1000)));
    }

    @Test void repeatedClauseNumberUnderAnotherHeadingOccurrenceIsExcluded() {
        VettingCorpus.Chunk oldHeading=chunk("earlier-heading",0,"5.7 Earlier section", "5.7","earlier-occurrence");
        VettingCorpus.Chunk oldBody=chunk("earlier-body",1,"A different section's period.","5.7","earlier-occurrence");
        VettingCorpus.Chunk seed=chunk("seed",20,"5.7 Current section", "5.7","current-occurrence");
        VettingCorpus.Chunk body=chunk("body",21,"The current section's period.","5.7","current-occurrence");
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(oldHeading,oldBody,seed,body),Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7.",1000);
        assertEquals(Arrays.asList("seed","body"),ids(selected));
    }

    @Test void observedMemberAndRepeatedSeedIdsAreDeduplicatedAndOrdered() {
        VettingCorpus.Chunk seed=chunk("seed",10,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk body=chunk("body",11,"The source body remains unchanged.","5.7","heading-57");
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(body,seed),Arrays.asList(seed,seed),observed(seed,body,seed),"duration",1000);
        assertEquals(Arrays.asList("seed","body"),ids(selected));
    }

    @Test void textBudgetCountsWholeExactChunksAndNeverSlicesAnOversizedMember() {
        VettingCorpus.Chunk seed=chunk("seed",0,"5.7 Duration", "5.7","heading-57");
        VettingCorpus.Chunk body=chunk("body",1,"This complete paragraph cannot fit within the remaining allowance.","5.7","heading-57");
        int budget=seed.getContent().length()+body.getContent().length()-1;
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(seed,body),Collections.singletonList(seed),observed(seed,seed,body),"duration",budget);
        assertEquals(Collections.singletonList("seed"),ids(selected));
        assertTrue(selected.stream().mapToInt(c->c.getContent().length()).sum()<=budget);
        assertEquals(seed.getContent(),selected.get(0).getContent());
    }

    @Test void zeroBudgetCannotAdmitText() {
        VettingCorpus.Chunk seed=chunk("seed",0,"Source text",null,null);
        assertTrue(select(Collections.singletonList(seed),Collections.singletonList(seed),unknown(seed),"source",0).isEmpty());
    }

    @Test void missingSourceOrderDisablesGuessedAdjacency() {
        VettingCorpus.Chunk seed=chunk("seed",0,"Source text",null,null);
        VettingCorpus.Chunk adjacent=chunk("adjacent",1,"Possible adjacent text",null,null);
        seed.getCanonicalEvidence().setSourceOrder(null);((ObjectNode)seed.getCanonicalPayload()).remove("sourceOrder");
        assertEquals(Collections.singletonList("seed"),ids(select(Arrays.asList(seed,adjacent),Collections.singletonList(seed),unknown(seed),"source",1000)));
    }

    @Test void duplicateSourceOrderDisablesGuessedAdjacency() {
        VettingCorpus.Chunk seed=chunk("seed",0,"Source text",null,null);
        VettingCorpus.Chunk adjacent=chunk("adjacent",0,"Ambiguous source position",null,null);
        assertEquals(Collections.singletonList("seed"),ids(select(Arrays.asList(seed,adjacent),Collections.singletonList(seed),unknown(seed),"source",1000)));
    }

    @Test void nonIntegralSourceOrderDisablesGuessedAdjacency() {
        VettingCorpus.Chunk seed=chunk("seed",0,"Source text",null,null);
        VettingCorpus.Chunk adjacent=chunk("adjacent",1,"Possible adjacent text",null,null);
        JsonNode fractional=JsonUtils.mapper().getNodeFactory().numberNode(0.5);
        seed.getCanonicalEvidence().setSourceOrder(fractional);((ObjectNode)seed.getCanonicalPayload()).set("sourceOrder",fractional);
        assertEquals(Collections.singletonList("seed"),ids(select(Arrays.asList(seed,adjacent),Collections.singletonList(seed),unknown(seed),"source",1000)));
    }

    @Test void unknownOwnershipGetsSmallOrderedContextWithoutPromotingItsMetadata() {
        VettingCorpus.Chunk before=chunk("before",0,"An observed preceding sentence.",null,null);
        VettingCorpus.Chunk seed=chunk("seed",1,"An observed short sentence.",null,null);
        VettingCorpus.Chunk after=chunk("after",2,"An observed continuation sentence.",null,null);
        List<VettingCorpus.Chunk> corpus=Arrays.asList(after,seed,before);
        Map<String,String> original=corpus.stream().collect(Collectors.toMap(VettingCorpus.Chunk::getId,JsonUtils::write));
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(seed),unknown(seed),"continuation",1000);
        assertEquals(Arrays.asList("before","seed","after"),ids(selected));
        for(VettingCorpus.Chunk value:selected){assertEquals(original.get(value.getId()),JsonUtils.write(value));assertNull(value.getClauseId());assertFalse(value.getCanonicalPayload().path("semanticScopeVerified").asBoolean());assertFalse(value.getCanonicalPayload().path("legalClosureVerified").asBoolean());}
    }

    @Test void adjacentFallbackStopsBeforeTheNextKnownClause() {
        VettingCorpus.Chunk seed=chunk("seed",0,"5.7 Current requirement", "5.7","heading-57");
        VettingCorpus.Chunk next=chunk("next",1,"5.8 A different requirement", "5.8","heading-58");
        VettingCorpus.Chunk beyond=chunk("beyond",2,"An unlabelled paragraph after the next heading.",null,null);
        List<VettingCorpus.Chunk> selected=select(Arrays.asList(seed,next,beyond),Collections.singletonList(seed),unknown(seed),"current requirement",1000);
        assertEquals(Collections.singletonList("seed"),ids(selected));
    }

    @Test void aDistantProseMentionIsNotAnExplicitClauseHeading() {
        VettingCorpus.Chunk seed=chunk("seed",0,"The evidence is elsewhere in this document.",null,null);
        List<VettingCorpus.Chunk> corpus=new ArrayList<>();corpus.add(seed);
        for(int i=1;i<10;i++)corpus.add(chunk("spacer-"+i,i,"Unrelated ordinary paragraph "+i+".",null,null));
        corpus.add(chunk("prose-mention",10,"The pricing paragraph mentions clause 5.7 but does not contain its terms.",null,null));
        List<VettingCorpus.Chunk> selected=select(corpus,Collections.singletonList(seed),unknown(seed),"Retrieve clause 5.7 for its actual wording.",2000);
        assertFalse(ids(selected).contains("prose-mention"));
        assertTrue(selected.size()<=5,"Unknown context is a small adjacent window, not the remaining document");
    }

    private static List<VettingCorpus.Chunk> select(List<VettingCorpus.Chunk> corpus,List<VettingCorpus.Chunk> seeds,JsonNode result,String query,int budget){
        return OerBusinessEvidence.reviewTextContext(corpus,seeds,result,query,budget);
    }
    private static List<String> ids(List<VettingCorpus.Chunk> chunks){return chunks.stream().map(VettingCorpus.Chunk::getId).collect(Collectors.toList());}
    private static VettingCorpus.Chunk find(List<VettingCorpus.Chunk> chunks,String id){return chunks.stream().filter(c->id.equals(c.getId())).findFirst().orElseThrow(AssertionError::new);}
    private static String sha(String value){return VettingCorpus.hash(value);}
    private static VettingCorpus.Chunk chunk(String id,int order,String text,String clause,String heading){return chunk(id,"1","tender","current",order,text,clause,heading);}
    private static VettingCorpus.Chunk chunk(String id,String document,String role,String revision,int order,String text,String clause,String heading){
        String identity=document+":"+revision;
        VettingCorpus.Chunk c=new VettingCorpus.Chunk();c.setId(id);c.setDocumentId(document);c.setRole(role);c.setContent(text);c.setFileKey("PRE");c.setFileName("Authored project clauses.docx");c.setClauseId(clause);c.setClauseHeadingLocation(heading);
        c.setSourceHash(sha("canonical:"+identity));c.setOriginalSourceSha256(sha("original:"+identity));c.setSourceSha256(c.getOriginalSourceSha256());c.setEligibleModules(Collections.singletonList("vetting"));
        VettingCorpus.Part part=new VettingCorpus.Part();part.setBlockId("block:"+id);part.setAnchor(heading==null?"paragraph:"+id:heading);part.setText(text);part.setStartOffset(0);part.setEndOffset(text.length());part.setExtractionSource("semantic_source_unit");c.setParts(Collections.singletonList(part));c.setAnchor(part.getAnchor());
        ObjectNode raw=JsonUtils.mapper().createObjectNode();raw.put("id",id);raw.put("unitId","unit:"+id);raw.put("documentId",document);raw.put("role",role);raw.put("text",text);raw.put("sourceHash",c.getSourceHash());raw.put("sourceOrder",order);raw.put("producerKind","normal_observed");raw.put("semanticProjectionVersion",OerCanonicalRetrievalClient.SEMANTIC_READ);raw.put("qualifiersComplete","unknown");raw.put("semanticScopeVerified",false);raw.put("legalClosureVerified",false);raw.putArray("eligibleModules").add("vetting");raw.putArray("unknowns").add("legal_scope_not_verified");
        if(clause==null)raw.putNull("clauseId");else raw.put("clauseId",clause);
        if(heading==null)raw.putNull("clauseHeadingLocation");else raw.put("clauseHeadingLocation",heading);
        ObjectNode rawPart=raw.putArray("parts").addObject();rawPart.put("blockId",part.getBlockId());rawPart.put("anchor",part.getAnchor());rawPart.put("text",text);rawPart.put("startOffset",0);rawPart.put("endOffset",text.length());
        EvidencePointer p=new EvidencePointer();p.setProjectId("authored");p.setModule("vetting");p.setDocumentId(Long.valueOf(document));p.setCanonicalChunkId(id);p.setUnitId("unit:"+id);p.setOriginalSourceSha256(c.getOriginalSourceSha256());p.setJavaParsedSourceHash(sha("parsed:"+identity));p.setCanonicalSourceHash(c.getSourceHash());p.setSourceRevisionSha256(sha("source revision:"+identity));p.setProjectionSha256(sha("projection:"+identity));p.setProjectionRevisionSha256(sha("projection revision:"+identity));p.setProjectionSchema(OerCanonicalRetrievalClient.SEMANTIC_READ);p.setProducerKind("normal_observed");p.setAddressKind("semantic_source_unit");p.setSourceOrder(raw.path("sourceOrder").deepCopy());p.setParts(raw.path("parts").deepCopy());p.setUnknowns(raw.path("unknowns").deepCopy());p.setEligibleModules(raw.path("eligibleModules").deepCopy());
        ObjectNode ref=raw.putObject("evidenceRef");ref.put("canonicalChunkId",id);ref.put("module","vetting");ref.put("canonicalSourceHash",p.getCanonicalSourceHash());ref.put("projectionSha256",p.getProjectionSha256());ref.put("projectionRevisionSha256",p.getProjectionRevisionSha256());ref.put("sourceRevisionSha256",p.getSourceRevisionSha256());ref.put("originalSourceSha256",p.getOriginalSourceSha256());
        ObjectNode unit=raw.putObject("semanticUnit");unit.put("kind","unknown");unit.put("status","unknown");unit.put("reason","additional_source_context_deferred");unit.put("qualifiersComplete","unknown");unit.put("semanticScopeVerified",false);unit.put("legalClosureVerified",false);unit.put("derivedMembersCanBecomeOrigins",false);unit.putArray("requiredMemberIds");
        c.setCanonicalPayload(raw);c.setCanonicalEvidence(p);return c;
    }
    private static ObjectNode unknown(VettingCorpus.Chunk seed){
        ObjectNode result=JsonUtils.mapper().createObjectNode();result.putArray("hits").addObject().put("id",seed.getId());
        ObjectNode episode=result.putObject("sourceUnits");episode.put("policy","unique-source-structural-unit-seeds-v1");episode.put("derivedMembersCanBecomeOrigins",false);episode.putArray("originalHitIds").add(seed.getId());ObjectNode unit=episode.putArray("units").addObject();unit.put("status","unknown");unit.put("reason","additional_source_context_deferred");unit.put("unitId",sha("unit:"+seed.getId()));unit.put("derivedMembersCanBecomeOrigins",false);unit.putArray("originIds").add(seed.getId());unit.putArray("requiredMemberIds");unit.putArray("members");return result;
    }
    private static ObjectNode observed(VettingCorpus.Chunk seed,VettingCorpus.Chunk... members){
        ObjectNode result=unknown(seed),unit=(ObjectNode)result.path("sourceUnits").path("units").get(0);unit.put("status","complete_observed_unit");unit.putNull("reason");unit.put("kind","exact_clause_heading");unit.put("clauseId",seed.getClauseId());unit.put("headingLocation",seed.getClauseHeadingLocation());ArrayNode ids=unit.putArray("requiredMemberIds"),values=unit.putArray("members");
        for(VettingCorpus.Chunk member:members){ids.add(member.getId());values.add(member.getCanonicalPayload().deepCopy());}
        return result;
    }
}
