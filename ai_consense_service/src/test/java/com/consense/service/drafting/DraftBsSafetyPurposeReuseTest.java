package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.domain.DraftVariable;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit authored associations only; template similarities do not authorize project values. */
class DraftBsSafetyPurposeReuseTest {
    private static Map<String,Object> row(String purpose){return DraftBusinessRules.map("type","BQ","number","7","description","AUTHORED exact safety description","purpose",purpose,"placement","DiscB","placementText","");}
    private static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(v->id.equals(v.get("id"))).findFirst().orElseThrow(AssertionError::new);}
    private static Map<String,Object> plan(List<Object> rows){return DraftBusinessRules.plan(DraftBusinessRules.map("billNos",rows,"subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services"),"electronicTendering","L10Pro"));}
    @Test void distinctExplicitSafetyPurposesKeepIndependentSccValuesAndOnePhysicalSctBill(){
        Map<String,Object> general=row("bsSafety"),fire=row("fireSafety");fire.put("trade","Fire services");
        List<Object> rows=Arrays.asList(general,fire);String before=JsonUtils.write(rows);assertNull(DraftBusinessRules.invalid("billNos",rows));
        Map<String,Object> plan=plan(rows);assertEquals("amend",action(plan,"scc-safety-pricing-general").get("action"));assertEquals("amend",action(plan,"scc-safety-pricing-SCC20.303").get("action"));
        assertEquals(1,DraftBusinessRules.list(action(plan,"scc-safety-pricing-general").get("value")).size());assertEquals(1,DraftBusinessRules.list(action(plan,"scc-safety-pricing-SCC20.303").get("value")).size());
        assertEquals(1,DraftBusinessRules.list(action(plan,"sct-shared-bill-list").get("value")).size());assertEquals(1,DraftBusinessRules.list(action(plan,"sct-source-bill-identities").get("value")).size());
        assertEquals(2,DraftBusinessRules.list(DraftBusinessRules.asMap(plan.get("inputValues")).get("billNos")).size());assertEquals(before,JsonUtils.write(rows));
    }
    @Test void repeatedPurposeDifferentDescriptionAndNonSafetyReuseRemainInvalid(){
        for(Map<String,Object> other:Arrays.asList(row("bsSafety"),row("general"),row("preliminaries"))){assertNotNull(DraftBusinessRules.invalid("billNos",Arrays.asList(row("bsSafety"),other)));}
        Map<String,Object> trailing=row("fireSafety");trailing.put("description",trailing.get("description")+" ");assertNotNull(DraftBusinessRules.invalid("billNos",Arrays.asList(row("bsSafety"),trailing)));
        assertNotNull(DraftBusinessRules.invalid("billNos",Arrays.asList(row("bsSafety"),row("fireSafety"),row("fireSafety"))));
    }
    @Test void placementConflictsAndUnconfirmedPricingTypeRemainInvalidAndVisible(){
        for(String key:Arrays.asList("placement","placementText")){Map<String,Object> changed=row("fireSafety");changed.put(key,key.equals("placement")?"DiscA":"different raw arrangement");List<Object> rows=Arrays.asList(row("bsSafety"),changed);assertNotNull(DraftBusinessRules.invalid("billNos",rows));assertEquals(2,DraftBusinessRules.list(DraftBusinessRules.asMap(plan(rows).get("inputValues")).get("billNos")).size());}
        Map<String,Object> a=row("bsSafety"),b=row("fireSafety");a.remove("type");b.remove("type");assertNotNull(DraftBusinessRules.invalid("billNos",Arrays.asList(a,b)));
    }
    @Test void validRowsRemainAdoptableAndExistingExplicitIdsArePreserved(){
        Map<String,Object> a=row("bsSafety"),b=row("fireSafety");a.put("id","first-explicit");b.put("id","second-explicit");String normalized=DraftInputRules.normalize(DraftBlueprint.find("billNos"),JsonUtils.write(Arrays.asList(a,b)));
        DraftVariable variable=new DraftVariable();variable.setVarKey("billNos");variable.setValueText(normalized);variable.setConfirmed(true);assertTrue(DraftInputRules.valid(variable));
        List<Object> rows=DraftBusinessRules.list(DraftBusinessRules.parse(normalized));assertEquals("first-explicit",DraftBusinessRules.asMap(rows.get(0)).get("id"));assertEquals("second-explicit",DraftBusinessRules.asMap(rows.get(1)).get("id"));
    }
    @Test void otherPricingNamespacesAreSeparateAndSpecialistTradeConflictsRemainPending(){
        Map<String,Object> a=row("bsSafety"),b=row("fireSafety");b.put("type","SOR");assertNull(DraftBusinessRules.invalid("billNos",Arrays.asList(a,b)));assertEquals(2,DraftBusinessRules.list(action(plan(Arrays.asList(a,b)),"sct-shared-bill-list").get("value")).size());
        b=row("fireSafety");b.put("trade","Fire services and water pump");Map<String,Object> plan=plan(Arrays.asList(a,b));assertEquals("amend",action(plan,"scc-safety-pricing-general").get("action"));assertEquals("pending",action(plan,"scc-safety-pricing-SCC20.303").get("action"));
    }
    @Test void resourceAndRuntimeRuleVersionsStaySynchronized()throws Exception {
        try(java.io.InputStream in=DraftBusinessRules.class.getResourceAsStream("/drafting/input-catalog.json")){
            assertNotNull(in);String resourceVersion=JsonUtils.mapper().readTree(in).path("ruleVersion").asText();
            assertEquals(DraftBusinessRules.RULE_VERSION,resourceVersion);assertEquals(resourceVersion,DraftBusinessRules.catalog().get("ruleVersion"));
        }
    }
    @Test void compiledPhysicalBillDistributionListsTheSharedIdentityOnceInBothIssueModes(){
        StringJoiner catalog=new StringJoiner("\n");for(int p=1;p<=1238;p++)catalog.add(DraftBusinessRules.standardParagraph("SCT",p));
        for(String mode:Arrays.asList("L10Pro","Hardcopy"))for(String type:Arrays.asList("BQ","SOR")){
            Map<String,Object> general=row("bsSafety"),fire=row("fireSafety");general.put("type",type);fire.put("type",type);
            String placement="L10Pro".equals(mode)?"DiscB":"Hardcopy";general.put("placement",placement);fire.put("placement",placement);
            List<Object> rows=Arrays.asList(DraftBusinessRules.map("type","BQ","number","1","description","Preliminaries","purpose","preliminaries","placement","standard"),DraftBusinessRules.map("type","BQ","number","2","description","Preambles","purpose","preambles","placement","standard"),general,fire);
            Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("billNos",rows,"electronicTendering",mode,"subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services")));
            Map<String,Object> distribution=action(plan,"sct-shared-bill-list");plan.put("actions",Arrays.asList(action(plan,"sct-issue-alternative"),distribution));
            String output=DraftClauseRules.apply(catalog.toString(),"SCT",plan);
            assertEquals("applied",distribution.get("applicationState"),JsonUtils.write(distribution));
            String label=("BQ".equals(type)?"Bill No. ":"Schedule No. ")+"7 – AUTHORED exact safety description";
            assertEquals(1,output.split(java.util.regex.Pattern.quote(label),-1).length-1,mode+"/"+type+": emitted physical list must contain exactly one shared pricing document");
            if("Hardcopy".equals(mode))assertTrue(output.contains(DraftBusinessRules.standardParagraph("SCT",387)),"The retained Disc C explanation containing the same Bill2 words must remain complete.");
            assertEquals(4,DraftBusinessRules.list(DraftBusinessRules.asMap(plan.get("effectiveValues")).get("billNos")).size(),"Raw purpose associations remain separate, so a compiler reading effective billNos would duplicate output.");
        }
    }

    @Test void hardcopyPricingParagraphNeedsItsUniqueCompleteSourceContext(){
        StringJoiner catalog=new StringJoiner("\n");for(int p=1;p<=1238;p++)catalog.add(DraftBusinessRules.standardParagraph("SCT",p));String original=catalog.toString();
        String left=DraftBusinessRules.standardParagraph("SCT",320),target=DraftBusinessRules.standardParagraph("SCT",322),right=DraftBusinessRules.standardParagraph("SCT",325);
        List<String> unverified=Arrays.asList(original.replace(left,"AUTHORED changed pricing heading"),original.replace("\n"+target+"\n","\nAUTHORED changed standalone Bill2 paragraph\n"),original.replace("\n"+right+"\n","\nAUTHORED changed hardcopy heading\n"),original+"\n"+left+"\n"+target+"\n"+right);
        for(String source:unverified){
            Map<String,Object> general=row("bsSafety"),fire=row("fireSafety");general.put("placement","Hardcopy");fire.put("placement","Hardcopy");
            Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("billNos",Arrays.asList(general,fire),"electronicTendering","Hardcopy"));
            Map<String,Object> distribution=action(plan,"sct-shared-bill-list");plan.put("actions",Arrays.asList(action(plan,"sct-issue-alternative"),distribution));
            String output=DraftClauseRules.apply(source,"SCT",plan);
            assertEquals("unresolved",distribution.get("applicationState"),"Missing, changed or duplicated complete context must not select a paragraph by order.");
            assertTrue(output.contains(DraftBusinessRules.standardParagraph("SCT",387)),"Unrelated matching substring remains intact.");
            assertFalse(output.contains("AUTHORED exact safety description"),"A failed source-context mapping cannot partially publish a physical distribution.");
        }
    }

}
