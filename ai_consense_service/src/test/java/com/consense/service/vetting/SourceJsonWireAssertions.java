package com.consense.service.vetting;

import com.consense.common.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** JSON preserves numeric values and ordered fields, not Jackson's Java node widths. */
final class SourceJsonWireAssertions {
    private SourceJsonWireAssertions() { }
    static void assertRoundTrip(JsonNode original) {
        JsonNode decoded=VettingSourceMetadataWire.decode(VettingSourceMetadataWire.write(original));
        String difference=firstDifference(original,decoded,"");
        if(difference!=null)System.out.println("source-wire-node-diagnosis: "+difference);
        assertEquals(JsonUtils.write(original),JsonUtils.write(decoded),
                "Complete ordered JSON values must survive compact wire; node diagnosis: "+difference);
    }
    private static String firstDifference(JsonNode a,JsonNode b,String path) {
        if(a.equals(b))return null;
        if(a.isObject()&&b.isObject()) {
            Iterator<String> keys=a.fieldNames();
            while(keys.hasNext()) {
                String key=keys.next();JsonNode next=b.get(key);
                if(next==null)return path+"/"+key+": missing after decode";
                String difference=firstDifference(a.get(key),next,path+"/"+key);
                if(difference!=null)return difference;
            }
        } else if(a.isArray()&&b.isArray()&&a.size()==b.size()) {
            for(int i=0;i<a.size();i++) {
                String difference=firstDifference(a.get(i),b.get(i),path+"/"+i);
                if(difference!=null)return difference;
            }
        }
        return path+": "+a.getClass().getSimpleName()+"("+a+") -> "+b.getClass().getSimpleName()+"("+b+")";
    }
}
