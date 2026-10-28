package com.consense.service.sources;

import com.consense.domain.SourceDocument;
import com.consense.service.vetting.VettingCorpus;
import com.fasterxml.jackson.core.JsonParser;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NormalPointerStreamCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"plain","style","nested_style","unicode","null_text","null_structure","null_coverage"})
    void streamedFieldsAndParsedIdentityMatchExistingContentDigests(String kind)throws Exception {
        String text="null_text".equals(kind)?null:"AUTHORED Unicode 😀 text with a split surrogate "+"😀".repeat(5000);
        String structure="null_structure".equals(kind)?null:"style".equals(kind)?" [ {\"paragraphStyleName\":\"AUTHORED style\",\"text\":\"😀 text\",\"ratio\":1e-05},{\"text\":\"second\"} ]"
            :"nested_style".equals(kind)?"[ {\"text\":\"literal\",\"nested\":{\"paragraphStyleName\":\"retain\"}} ]":" [ {\"text\":\"AUTHORED exact 😀\",\"ratio\":1e-05} ] ";
        String coverage="null_coverage".equals(kind)?null:"{\"complete\":true,\"ratio\":1e-05}";
        String candidate="{\"coverageSha256\":\""+VettingCorpus.hash(coverage==null?"":coverage)+"\",\"units\":[{\"ordinal\":1,\"text\":\"AUTHORED 😀\"}]}";
        NormalPointerStream.SourceProof proof=NormalPointerStream.source(reader(text),reader(structure),reader(coverage),reader(candidate));
        SourceDocument source=new SourceDocument();source.setTextContent(text);source.setStructuredContentJson(structure);
        assertEquals(VettingCorpus.sourceHash(source),proof.parsed,kind);
        assertArrayEquals(new String[]{SourceContentDigests.field(text),SourceContentDigests.field(structure),SourceContentDigests.field(coverage),SourceContentDigests.field(candidate)},proof.fields,kind);
        assertEquals(SourceContentDigests.combined(proof.fields[0],proof.fields[1],proof.fields[2],proof.fields[3]),proof.business());
        assertEquals(VettingCorpus.hash(coverage==null?"":coverage),proof.coverage);assertEquals(proof.coverage,proof.graphCoverage);
    }
    @Test void graphFingerprintPreservesJsonNodeContentEqualityWithoutRetainingAWholeTree()throws Exception {
        assertEquals(identity("{\"a\":1,\"b\":[1e-05,true,null,\"😀\"]}"),identity("{\"b\":[0.00001,true,null,\"😀\"],\"a\":1}"));
        assertNotEquals(identity("{\"a\":[1,2]}"),identity("{\"a\":[2,1]}"));
        assertNotEquals(identity("{\"a\":1}"),identity("{\"a\":1.0}"));
        assertNotEquals(identity("{\"a\":true}"),identity("{\"a\":false}"));
        assertNotEquals(identity("{\"a\":null}"),identity("{\"a\":\"null\"}"));
    }
    @ParameterizedTest @ValueSource(strings={"duplicate_candidate","trailing_candidate","duplicate_structure","trailing_structure","deep_candidate"})
    void malformedOrAmbiguousGraphsCannotEnterTheProof(String defect)throws Exception {
        String candidate="duplicate_candidate".equals(defect)?"{\"x\":1,\"x\":2}":"trailing_candidate".equals(defect)?"{} {}"
            :"deep_candidate".equals(defect)?"{\"deep\":"+"[".repeat(130)+"1"+"]".repeat(130)+"}":"{}";
        String structure="duplicate_structure".equals(defect)?"[{\"text\":\"a\",\"text\":\"b\"}]":"trailing_structure".equals(defect)?"[] []":"[]";
        assertThrows(IOException.class,()->NormalPointerStream.source(reader("AUTHORED"),reader(structure),reader("{}"),reader(candidate)));
    }
    private static String identity(String raw)throws Exception {try(JsonParser parser=NormalPointerStream.parser(reader(raw))){parser.nextToken();return NormalPointerStream.identity(parser);}}
    private static Reader reader(String text){return text==null?null:new StringReader(text);}
}
