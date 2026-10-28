package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed-catalog navigation only; no model, product API, source-file or database calls. */
class DraftBaselineShortAcronymSearchTest {
    @Test void declaredShortAcronymsFindVisibleDefinitionsWithoutMatchingLegacyKeyPrefixes() {
        Map<String,String> expected=new LinkedHashMap<>();
        expected.put("WTO GPA","wtoGpaApplies");expected.put("wto","wtoGpaApplies");expected.put("GPA","wtoGpaApplies");
        expected.put("BQ","electronicTendering");expected.put("bq","electronicTendering");expected.put("NSC","subcontractArrangement");expected.put("nSc","subcontractArrangement");
        for(Map.Entry<String,String> entry:expected.entrySet()) {
            List<?> fields=list(DraftBaselineMatcher.searchIndex(Collections.singletonList(entry.getKey())).get("fields"));
            assertFalse(fields.isEmpty(),entry.getKey());assertEquals(entry.getValue(),map(fields.get(0)).get("key"),entry.getKey());
        }
        List<?> nsc=list(DraftBaselineMatcher.searchIndex(Collections.singletonList("NSC")).get("fields"));
        assertEquals(Collections.singletonList("subcontractArrangement"),keys(nsc));
        assertFalse(keys(nsc).contains("nscAlternativeText"),"The legacy NSC key describes BSSSC replacement wording.");
    }

    @Test void ordinaryShortWordsAndFragmentsDoNotBecomeCatalogQueries() {
        for(String query:Arrays.asList("to","no","if","and","the","yes","zz","SC","WT","GP","Q"))
            assertTrue(list(DraftBaselineMatcher.searchIndex(Collections.singletonList(query)).get("fields")).isEmpty(),query);
    }

    @Test void existingLongQueriesAndBoundedReadOnlyNavigationRemainUnchanged() {
        String before=JsonUtils.write(DraftBaselineMatcher.catalog());
        for(String[] query:new String[][]{{"IASM","iasmIncluded"},{"wtoGpaApplies","wtoGpaApplies"},{"electronicTendering","electronicTendering"},{"subcontractArrangement","subcontractArrangement"}}) {
            List<?> fields=list(DraftBaselineMatcher.searchIndex(Collections.singletonList(query[0])).get("fields"));
            assertEquals(Collections.singletonList(query[1]),keys(fields),query[0]);
        }
        Map<String,Object> results=DraftBaselineMatcher.searchIndex(Arrays.asList("contract works installation warranty","BQ WTO GPA NSC"));
        assertEquals(false,results.get("complete"));assertTrue(list(results.get("fields")).size()<=12);
        for(Object field:list(results.get("fields")))assertEquals(new HashSet<>(Arrays.asList("key","parentKey","label")),map(field).keySet());
        assertEquals(before,JsonUtils.write(DraftBaselineMatcher.catalog()));
        List<Object> requested=new ArrayList<>();for(Object field:list(DraftBaselineMatcher.catalog().get("fields")))requested.add(map(field).get("key"));
        assertEquals(8,list(DraftBaselineMatcher.requestedContext(requested).get("fields")).size());
    }

    private static List<String> keys(List<?> fields) {
        List<String> keys=new ArrayList<>();for(Object field:fields)keys.add(text(map(field).get("key")));return keys;
    }
}
