package com.consense.document;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OerReadingPlanTest {
    @Test void ordinaryPdfLinesDoNotRescanTheEntireSourceForEachOccurrence() {
        List<DocumentBlock> source=new ArrayList<>();
        for(int index=0;index<2000;index++)source.add(block("line-"+index,"pdf_line",index/40+1,"pdf-page/"+(index/40+1),"ordinary source line",new double[]{.1,.1,.2,.02}));
        class Counted extends AbstractList<DocumentBlock> {
            int reads;
            @Override public DocumentBlock get(int index){reads++;return source.get(index);}
            @Override public int size(){return source.size();}
        }
        Counted counted=new Counted();OerReadingPlan plan=new OerReadingPlan("authored-source","pdf",null);plan.annotate(counted);
        assertTrue(counted.reads<=source.size()*8,"Candidate lookup must be bounded by local page candidates, not the complete source for every line; reads="+counted.reads);
        assertEquals(2000,plan.metadata().path("observedOccurrenceCount").asInt());
        assertEquals("unknown",source.get(1999).getReadingContext().path("ownerStatus").asText());
    }
    @Test void localCandidateIndexesKeepPageAndWordStoryIsolationAndUnknownOwnership() {
        DocumentBlock one=block("container-one","table",1,"pdf-page/1","",new double[]{0,0,1,1});
        DocumentBlock other=block("container-other","table",2,"pdf-page/2","",new double[]{0,0,1,1});
        DocumentBlock control=block("control","checkbox",1,"pdf-page/1","",new double[]{.1,.1,.1,.1});
        DocumentBlock label=block("label-one","pdf_line",1,"pdf-page/1","Actual label:",null);
        DocumentBlock otherLabel=block("label-other","pdf_line",2,"pdf-page/2","Other label:",null);
        DocumentBlock body=block("body-field","writing_area",null,"body/2/paragraph","",null);
        DocumentBlock bodyLabel=block("body-label","paragraph",null,"body/1/paragraph","Body label:",null);
        DocumentBlock header=block("header-label","header",null,"header/0/paragraph","Header label:",null);
        new OerReadingPlan("authored-source","pdf",null).annotate(Arrays.asList(one,other,control,label,otherLabel,body,bodyLabel,header));
        assertEquals(1,control.getReadingContext().path("ownerCandidates").size());assertEquals("container-one",control.getReadingContext().path("ownerCandidates").get(0).path("ownerId").asText());
        assertEquals(1,control.getReadingContext().path("anchorCandidates").size());assertEquals("label-one",control.getReadingContext().path("anchorCandidates").get(0).path("anchorId").asText());
        assertEquals("unknown",control.getReadingContext().path("ownerStatus").asText());
        assertEquals(1,body.getReadingContext().path("anchorCandidates").size());assertEquals("body-label",body.getReadingContext().path("anchorCandidates").get(0).path("anchorId").asText());
    }
    private static DocumentBlock block(String id,String kind,Integer page,String location,String text,double[] box){
        DocumentBlock block=new DocumentBlock();block.setId(id);block.setKind(kind);block.setPageNo(page);block.setLocation(location);block.setText(text);block.setOriginalText(text);block.setBbox(box);return block;
    }
}
