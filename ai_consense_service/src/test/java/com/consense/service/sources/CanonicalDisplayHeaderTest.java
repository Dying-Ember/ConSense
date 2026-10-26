package com.consense.service.sources;

import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored bounded-header checks; no full source or business acceptance claim. */
class CanonicalDisplayHeaderTest {
    static final class Counted extends StringReader {
        int characters;
        Counted(String value){super(value);}
        @Override public int read(char[] buffer,int offset,int length)throws IOException{int count=super.read(buffer,offset,length);if(count>0)characters+=count;return count;}
    }
    @Test void normalHeaderDoesNotReadTheCompleteSourceBody()throws Exception {
        Counted reader=new Counted("{\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\",\"body\":\""+"X".repeat(1_000_000)+"\"}");
        assertTrue(CanonicalEvidenceService.normalDisplayHeader(reader));assertTrue(reader.characters<=16384);assertTrue(reader.characters<1_000_000);
    }
    @Test void reviewedHeadersStayDistinctFromNormalObservation()throws Exception {
        assertFalse(CanonicalEvidenceService.normalDisplayHeader(new StringReader("{\"schema\":\"consense.canonical-projection.v1\",\"body\":[]} ")));
        assertFalse(CanonicalEvidenceService.normalDisplayHeader(new StringReader("{\"schema\":\"consense.native-pdf-source-family.v1\",\"producerKind\":\"native_pdf_source_family\"}")));
    }
    @Test void lateHeaderCannotMakePollingStreamAWholeLargeGraph() {
        Counted reader=new Counted("{\"body\":\""+"X".repeat(1_000_000)+"\",\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\"}");
        assertThrows(IOException.class,()->CanonicalEvidenceService.normalDisplayHeader(reader));assertEquals(16384,reader.characters);
    }
    @Test void unknownContradictoryDuplicateAndMalformedHeadersRemainUnavailable() {
        for(String value:new String[]{"[]","{\"schema\":false}","{\"schema\":\"unknown\",\"producerKind\":\"normal_observed\"}",
            "{\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"native_docx\"}",
            "{\"schema\":\"consense.normal-observed.v1\",\"schema\":\"consense.normal-observed.v1\",\"producerKind\":\"normal_observed\"}","{\"schema\":"})
            assertThrows(Exception.class,()->CanonicalEvidenceService.normalDisplayHeader(new StringReader(value)),value);
    }
}
