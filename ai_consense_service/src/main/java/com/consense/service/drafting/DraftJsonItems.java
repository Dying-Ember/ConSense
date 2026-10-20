package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps only fully decoded array members; never closes or repairs an unfinished JSON value. */
final class DraftJsonItems {
    private DraftJsonItems() { }

    static ArrayNode completePrefix(String raw,String... wrappers) {
        ArrayNode complete=JsonUtils.mapper().createArrayNode();
        Set<String> allowed=new HashSet<>(Arrays.asList(wrappers));
        try(JsonParser parser=JsonUtils.mapper().getFactory().createParser(JsonUtils.extractJson(raw))) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonToken token=parser.nextToken();
            if(token==JsonToken.START_OBJECT) {
                while(parser.nextToken()==JsonToken.FIELD_NAME) {
                    String name=parser.currentName();token=parser.nextToken();
                    if(allowed.contains(name)&&token==JsonToken.START_ARRAY)break;
                    parser.skipChildren();
                }
            }
            if(token!=JsonToken.START_ARRAY)throw new IllegalArgumentException("A recognized candidate array is required");
            while((token=parser.nextToken())!=null&&token!=JsonToken.END_ARRAY) {
                JsonNode item=JsonUtils.mapper().readTree(parser);
                if(item==null)break;
                complete.add(item);
            }
        }catch(Exception incomplete) {
            if(complete.isEmpty())throw new IllegalArgumentException("No complete JSON candidate is available",incomplete);
        }
        if(complete.isEmpty())throw new IllegalArgumentException("No complete JSON candidate is available");
        return complete;
    }
}
