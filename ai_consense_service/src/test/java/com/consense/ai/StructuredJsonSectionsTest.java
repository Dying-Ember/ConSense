package com.consense.ai;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredJsonSectionsTest {
    @Test void emitsOnlyCompleteRootSectionsAcrossEveryCharacterBoundary(){
        String answer="{\"nested\":{\"sections\":[{\"ignore\":true}]},\"sections\":[{\"content\":\"中文 🐴 } { \\\" \\\\ \\\\u4e2d\",\"plan\":{\"nodes\":[1,2]}},{\"content\":\"second\"}],\"grounded\":false}";
        List<JsonNode> sections=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(sections::add);
        for(int i=0;i<answer.length();i++)parser.accept(answer.substring(i,i+1));
        assertEquals(2,sections.size());assertEquals("second",sections.get(1).path("content").asText());assertEquals(JsonUtils.parse(answer),parser.finish());
    }
    @Test void closedObjectEmitsBeforeFinalEnvelope(){
        List<JsonNode> sections=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(sections::add);
        parser.accept("{\"sections\":[{\"content\":\"first\"}");assertEquals(1,sections.size());
        assertThrows(IllegalArgumentException.class,parser::finish);
    }
    @Test void partialIncorrectAndDuplicateObjectsAreNeverAccepted(){
        for(String raw:Arrays.asList("{\"sections\":[{\"content\":","{\"sections\":[{\"content\":1,\"content\":2}]}","{\"sections\":[{\"content\":invalid}]}")){
            List<JsonNode> sections=new ArrayList<>();StructuredJsonSections parser=new StructuredJsonSections(sections::add);
            assertThrows(IllegalArgumentException.class,()->{parser.accept(raw);parser.finish();});assertTrue(sections.isEmpty());
        }
    }
    @Test void rejectsWrongRootTrailingValuesAndNonObjectSections(){
        for(String raw:Arrays.asList("[]","{\"sections\":[1]}","{\"sections\":[]} {}","{\"sections\":[]}"+"x")){
            StructuredJsonSections parser=new StructuredJsonSections(s->{});assertThrows(IllegalArgumentException.class,()->{parser.accept(raw);parser.finish();});
        }
    }
    @Test void rejectsUnboundedOutput(){StructuredJsonSections parser=new StructuredJsonSections(s->{});char[] large=new char[4*1024*1024+1];Arrays.fill(large,' ');assertThrows(IllegalArgumentException.class,()->parser.accept(new String(large)));}
}
