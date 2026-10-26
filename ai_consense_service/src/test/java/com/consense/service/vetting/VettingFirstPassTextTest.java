package com.consense.service.vetting;

import com.consense.service.vetting.VettingCorpus.Chunk;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VettingFirstPassTextTest {
    @Test void completePackageSelectsProvisionTextBeforeTitlesFootersAndContents() {
        List<Chunk> scanned=new ArrayList<>();
        for(int document=0;document<33;document++){
            scanned.add(chunk(document,0,"SPECIAL CONDITIONS OF CONTRACT"));
            scanned.add(chunk(document,1,"The Contractor shall submit the signed tender in two sealed envelopes."));
            scanned.add(chunk(document,2,"For this Contract, Site means the land identified in the contract particulars."));
            scanned.add(chunk(document,3,"TABLE OF CONTENTS\nCONTRACT DOCUMENTATION........7\nSITE SECURITY........20"));
            scanned.add(chunk(document,4,"20250101 - SCC/21 -"));
        }
        String original= com.consense.common.JsonUtils.write(scanned);
        VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(scanned);
        assertEquals(48,pass.subjects.size());
        assertEquals(33,pass.subjects.stream().map(Chunk::getDocumentId).distinct().count());
        assertTrue(pass.subjects.stream().allMatch(c->c.getContent().contains(" shall ")||c.getContent().contains(" means ")));
        assertEquals(scanned.size()-48,pass.omittedOrigins);
        assertEquals(original,com.consense.common.JsonUtils.write(scanned),"Ranking cannot rewrite source data or source order");
    }

    @Test void provisionPreferenceKeepsWholeObservedUnitsAndRetainsUnsignalledFallbacks() {
        List<Chunk> scanned=new ArrayList<>();
        for(int document=0;document<3;document++)for(int row=0;row<24;row++)
            scanned.add(chunk(document,row,row==12?"The Contractor must retain the records for 28 days.":"Recorded source paragraph "+row+"."));
        String companion=scanned.get(13).getId();
        List<VettingRetrievalSourceUnits.Unit> units=scanned.stream().map(c->new VettingRetrievalSourceUnits.Unit(
                c.getId(),"complete_observed_unit",null,c==scanned.get(12)?Arrays.asList(c.getId(),companion):Collections.singletonList(c.getId()),
                Collections.singletonMap("unitId","unit-"+c.getId()))).collect(Collectors.toList());
        VettingSemanticReview.FirstPass pass=VettingSemanticReview.firstPass(new VettingRetrievalSourceUnits.RankedChunks(scanned,units));
        for(int document=0;document<3;document++)assertTrue(pass.subjects.contains(scanned.get(document*24+12)));
        assertTrue(pass.corpus.stream().anyMatch(c->companion.equals(c.getId())));
        assertTrue(pass.subjects.stream().anyMatch(c->!c.getContent().contains(" must ")),"Unsignalled text remains eligible");
        assertEquals(scanned.size()-pass.selectedOrigins,pass.omittedOrigins);
        assertEquals(0,pass.unrepresentedTenderDocuments);
    }

    private static Chunk chunk(int document,int row,String text){
        Chunk c=VettingContextBudgetTest.chunk("source-"+document+"-"+row,"SCC"+row,text,text.length());
        c.setDocumentId("document-"+document);c.setFileKey("SCC");return c;
    }
}
