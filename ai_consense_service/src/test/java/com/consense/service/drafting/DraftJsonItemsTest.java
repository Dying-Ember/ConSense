package com.consense.service.drafting;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DraftJsonItemsTest {
    @Test void onlyCompleteObjectsBeforeAnUnfinishedNestedValueAreReturned() {
        JsonNode values=DraftJsonItems.completePrefix("[{\"value\":\"quoted } , [ text\"},{\"nested\":[{\"unfinished\":", "items");
        assertEquals(1,values.size());assertEquals("quoted } , [ text",values.path(0).path("value").asText());
    }
    @Test void declaredArrayWrappersAreSupportedWithoutSearchingOtherObjectFields() {
        assertEquals(1,DraftJsonItems.completePrefix("{\"notes\":[],\"nodes\":[{\"id\":\"a\"},{\"id\":", "nodes").size());
        assertThrows(IllegalArgumentException.class,()->DraftJsonItems.completePrefix("{\"untrusted\":[{\"id\":\"a\"},{", "nodes"));
    }
    @Test void duplicateKeysAndBrokenFirstMembersCannotBeRepairedIntoCandidates() {
        assertEquals(1,DraftJsonItems.completePrefix("[{\"id\":\"a\"},{\"id\":\"b\",\"id\":\"c\"},{\"id\":\"d\"}]", "nodes").size());
        assertThrows(IllegalArgumentException.class,()->DraftJsonItems.completePrefix("[{\"id\": broken},{\"id\":\"later\"}]", "nodes"));
        assertThrows(IllegalArgumentException.class,()->DraftJsonItems.completePrefix("<think>[{\"id\":\"reasoning\"}]", "nodes"));
    }
}
