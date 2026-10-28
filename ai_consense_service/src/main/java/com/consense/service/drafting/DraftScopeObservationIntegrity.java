package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Read-only integrity checks for referenced historical scope observations. */
final class DraftScopeObservationIntegrity {
    private DraftScopeObservationIntegrity() { }

    static Map<String,String> inspect(Map<String,Object> graph) {
        Map<String,Object> state=state(graph),table=map(state.get("sourceScopeObservations"));
        Map<String,String> result=new LinkedHashMap<>();
        for(Object raw:list(state.get("requirements"))) {
            Object reference=map(map(raw).get("sourceQuoteEvidence")).get("sourceScopeObservationRef");
            if(!(reference instanceof String)||((String)reference).isEmpty())continue;
            String ref=(String)reference;if(result.containsKey(ref))continue;
            Object entry=table.get(ref);
            String actual=entry instanceof Map?(ref.startsWith("v2:")?reference(entry):DraftAdoption.hash(JsonUtils.write(entry))):null;
            result.put(ref,entry==null?"missing":ref.equals(actual)?"valid":"invalid");
        }
        return result;
    }

    /** Stable across Java and browser JSON key ordering and numeric spelling. */
    static String reference(Object entry) {
        try{return "v2:"+DraftAdoption.hash(canonical(JsonUtils.mapper().valueToTree(entry)));}
        catch(IllegalArgumentException unsafe){return null;}
    }
    static boolean sameObservation(Object first,Object second) {
        try{return canonical(JsonUtils.mapper().valueToTree(first)).equals(canonical(JsonUtils.mapper().valueToTree(second)));}
        catch(IllegalArgumentException unsafe){return false;}
    }
    private static String canonical(JsonNode value) {
        if(value.isObject()) {
            List<String> keys=new ArrayList<>();value.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);
            StringJoiner result=new StringJoiner(",","{","}");for(String key:keys)result.add(JsonUtils.write(key)+":"+canonical(value.get(key)));return result.toString();
        }
        if(value.isArray()){StringJoiner result=new StringJoiner(",","[","]");for(JsonNode item:value)result.add(canonical(item));return result.toString();}
        if(value.isNumber()) {
            double number=value.doubleValue();if(!Double.isFinite(number))throw new IllegalArgumentException("Non-finite number");
            BigDecimal decimal=value.decimalValue(),browserNumber=BigDecimal.valueOf(number);
            if(decimal.abs().compareTo(new BigDecimal("9007199254740991"))>0||decimal.compareTo(browserNumber)!=0)throw new IllegalArgumentException("Unsafe browser number");
            return browserNumber.stripTrailingZeros().toPlainString();
        }
        return JsonUtils.write(value);
    }
}
