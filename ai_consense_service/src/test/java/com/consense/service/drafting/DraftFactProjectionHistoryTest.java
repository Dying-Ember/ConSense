package com.consense.service.drafting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.consense.service.drafting.DraftReviewGraph.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftFactProjectionHistoryTest {
    Map<String,Object> fixture(boolean extra) {
        Map<String,Object> field=DraftCatalogAlignment.definitions().get("electronicTendering");
        Map<String,Object> root=obj("id","root","nameEn","Issue format","type",obj("kind","choice"),"role",obj("kind","input"),"status","confirmed","options",field.get("options"),"catalogAlignment",obj("status","matched","confirmed",true,"baselineKey","electronicTendering","baselineVersion",DraftBusinessRules.RULE_VERSION,"reason","Human review"));
        Map<String,Object> extraInput=obj("id","extra","nameEn","Additional condition","type",obj("kind","boolean"),"role",obj("kind","input"),"status","confirmed");
        List<Object> oldTerms=new ArrayList<>();oldTerms.add(obj("inputId","local","operator","yes"));if(extra)oldTerms.add(obj("inputId","extra","operator","yes"));
        Map<String,Object> old=obj("kind","rule","branches",Collections.singletonList(obj("id","old-case","mode","all","clauses",oldTerms)));
        Map<String,Object> converted=obj("kind","rule","otherwise","needs_review","branches",Arrays.asList(obj("id","choice-a","mode","all","clauses",Collections.singletonList(obj("inputId","root","operator","eq","value","L10Pro")),"resultValue",true),obj("id","choice-b","mode","all","clauses",Collections.singletonList(obj("inputId","root","operator","eq","value","Hardcopy")),"resultValue",false)));
        Map<String,Object> review=obj("origin","manual","reason","Compared both alternatives", "rootId","root","baselineKey","electronicTendering","branchValue","L10Pro","branchPolarity",true,"exhaustiveConfirmed",true,"previousRuleResolution",obj("status","pending"),"original",obj("role",obj("kind","input"),"correction",old,"targetBindings",Collections.singletonList(obj("id","target-a","branchId","old-case","needsRebind",false))),"convertedCorrection",converted);
        Map<String,Object> local=obj("id","local","nameEn","Alternative A","type",obj("kind","boolean"),"role",obj("kind","derived"),"status","confirmed","correction",converted,"factProjectionReview",review,"docIds",Collections.singletonList("ntt"),"editTargets",Collections.singletonList(obj("id","target-a","docId","ntt","branchId","old-case","needsRebind",true,"action","delete","text","Alternative A")));
        return obj("graph",obj("variables",Arrays.asList(root,extraInput,local)),"sources",Collections.singletonList(obj("id","ntt","fileKey","NTT","format","docx")),"selectedDocuments",obj("NTT","ntt"));
    }
    private Map<String,Object> local(Map<String,Object> graph){return nodes(graph).get(2);}
    @Test void priorRuleAndTargetRemainPendingUntilExplicitReview() {
        Map<String,Object> graph=fixture(false),local=local(graph),review=map(local.get("factProjectionReview"));assertDoesNotThrow(()->validate(graph));assertTrue(ruleNeedsReview(local));
        Map<String,Object> plan=plan(graph,obj("root","L10Pro","extra",true),true);assertFalse(map(plan.get("derived")).containsKey("local"));assertEquals("pending",map(list(plan.get("actions")).get(0)).get("action"));
        Map<String,Object> restored=JsonUtils.readMap(JsonUtils.write(graph)),history=map(map(local(restored).get("factProjectionReview")).get("original"));assertEquals("old-case",map(list(history.get("targetBindings")).get(0)).get("branchId"));assertEquals("old-case",map(list(map(history.get("correction")).get("branches")).get(0)).get("id"));
        review.put("previousRuleResolution",obj("status","resolved","decision","replace","reason","Compared old rule and targets"));assertDoesNotThrow(()->validate(graph));assertFalse(ruleNeedsReview(local));assertEquals("pending",map(list(plan(graph,obj("root","L10Pro"),true).get("actions")).get(0)).get("action"),"Target still requires explicit rebind");
    }
    @Test void extraPriorInputsCannotBeSilentlyDiscardedAndRebuildRequiresThemAndRoot() {
        Map<String,Object> graph=fixture(true),local=local(graph),review=map(local.get("factProjectionReview"));review.put("previousRuleResolution",obj("status","resolved","decision","replace","reason","Replace"));assertThrows(BizException.class,()->validate(graph));
        Map<String,Object> correction=obj("kind","rule","branches",Arrays.asList(obj("id","rebuilt-a","mode","all","clauses",Arrays.asList(obj("inputId","root","operator","eq","value","L10Pro"),obj("inputId","extra","operator","yes")),"resultValue",true),obj("id","rebuilt-b","mode","all","clauses",Collections.singletonList(obj("inputId","root","operator","eq","value","Hardcopy")),"resultValue",false)));
        local.put("correction",correction);review.put("previousRuleResolution",obj("status","resolved","decision","rebuilt","reason","Recreated the additional condition and reviewed target branches"));assertDoesNotThrow(()->validate(graph));assertFalse(ruleNeedsReview(local));
        map(list(map(list(correction.get("branches")).get(0)).get("clauses")).get(1)).put("inputId","root");assertThrows(BizException.class,()->validate(graph));
    }
}
