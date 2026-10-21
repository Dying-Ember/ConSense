package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual 2a native target replay with explicit authored inputs; no PDF, model or project adoption proof. */
class DraftBsSafetyPricingRealSourceTest {
    private static final String SHA="9aa2fa06683652548e72dbf955c3f236d86cb0958a8fdf3b1ff6cf75b65c7208";
    private static final String[] IDS={"scc-safety-pricing-general","scc-safety-pricing-SCC20.302","scc-safety-pricing-SCC20.303","scc-safety-pricing-SCC20.304"};
    private static final String[] CLAUSES={"SCC20.301(7)","SCC20.302(5)","SCC20.303(5)","SCC20.304(5)"};
    private static final int[] BODY={1428,1548,1663,1777};
    private static final String[] PURPOSES={"bsSafety","electricalSafety","fireSafety","fireWaterSafety"};
    private static final String[] TRADES={"Lift","Electrical","Fire services","Fire services and water pump"};
    private static final String SINGULAR="Bill No. _____ / Schedule No. _____* of the Bills of Quantities / schedule of rates*";
    private static final String PLURAL="Bill No(s). _____ / Schedule No(s). _____** of the Bills of Quantities / schedule of rates**";
    private static Map<String,Object> action(Map<String,Object> plan,String id){return DraftBusinessRules.list(plan.get("actions")).stream().map(DraftBusinessRules::asMap).filter(row->id.equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);}
    private static Map<String,Object> binding(List<Map<String,Object>> rows,int ordinal){return rows.stream().filter(row->Objects.equals(ordinal,row.get("sourceParagraphOrdinal"))).findFirst().orElseThrow(AssertionError::new);}
    private static byte[] source()throws Exception {
        String directory=System.getProperty("consense.acceptance.sourceDir");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null&&!directory.trim().isEmpty(),"Actual 2a replay requires explicit consense.acceptance.sourceDir; absence is SKIP, not source acceptance.");
        byte[] bytes=Files.readAllBytes(Paths.get(directory,"06_Special Conditions of Contract (SCC).docx"));
        assertEquals(SHA,DraftPdfConverter.sha256(bytes));return bytes;
    }
    private static List<Map<String,Object>> compile(byte[] source,Map<String,Object> plan,String... ids){
        Set<String> selected=new LinkedHashSet<>(Arrays.asList(ids));List<Object> actions=new ArrayList<>();
        for(Object raw:DraftBusinessRules.list(plan.get("actions")))if(selected.contains(DraftBusinessRules.asMap(raw).get("id")))actions.add(raw);
        assertEquals(selected.size(),actions.size());plan.put("actions",actions);
        DocxTemplateEditor editor=new DocxTemplateEditor();TemplateIndex index=editor.inspect(source);
        List<Map<String,Object>> bindings=DraftDocumentBindings.source("SCC",index);DraftDocumentBindings.associateTargets("SCC",index,plan,bindings);
        DocxEditResult result=DraftFormattedRules.compile(source,"SCC",plan,DraftDocumentBindings.bookmarkNames(bindings));
        return DraftDocumentBindings.generated(bindings,result);
    }
    @Test void allFourMissingTargetsExplainTheirOwnBlankAndKeepExistingManualInputs() {
        Map<String,Object> values=DraftBusinessRules.map("contractTitle",DraftBusinessRules.map("title","AUTHORED PARTIAL PROJECT","number","AUTHORED-PARTIAL"));String before=JsonUtils.write(values);
        Map<String,Object> plan=DraftBusinessRules.plan(values);
        for(int i=0;i<IDS.length;i++){
            String id=IDS[i];Map<String,Object> target=action(plan,id);assertEquals("pending",target.get("action"));assertFalse(target.containsKey("value"));
            Map<String,Object> item=DraftBusinessRules.list(plan.get("unresolved")).stream().map(DraftBusinessRules::asMap).filter(row->id.equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals(Arrays.asList("billNos","subcontractors","subcontractArrangement"),item.get("inputKeys"));
            Map<String,Object> message=DraftBusinessRules.asMap(item.get("message"));String en=String.valueOf(message.get("en"));
            assertTrue(en.contains(CLAUSES[i]),en);assertTrue(en.contains("blank"),en);assertTrue(en.contains("BQ / SOR"),en);assertTrue(en.contains("*/**"),en);
            assertTrue(String.valueOf(message.get("zhHans")).contains("填空"));assertTrue(String.valueOf(message.get("zhHant")).contains(CLAUSES[i]));
        }
        Map<String,Object> field=DraftBusinessRules.fields().stream().filter(row->"billNos".equals(row.get("key"))).findFirst().orElseThrow(AssertionError::new);
        Map<String,Object> scope=DraftBusinessRules.list(field.get("affects")).stream().map(DraftBusinessRules::asMap).filter(row->"SCC".equals(row.get("document"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("P1334/P1428/P1441/P1548/P1558/P1663/P1673/P1777",scope.get("paragraphs"));
        assertFalse(DraftBusinessRules.asMap(plan.get("effectiveValues")).containsKey("billNos"));assertEquals(before,JsonUtils.write(values));
    }
    @Test void actualUnknownNativeBlanksRetainBothAlternativesAndTheirFourBindings()throws Exception {
        byte[] original=source();TemplateIndex index=new DocxTemplateEditor().inspect(original);Map<String,Object> plan=DraftBusinessRules.plan(Collections.emptyMap());
        List<Map<String,Object>> generated=compile(original,plan,IDS);
        for(int i=0;i<IDS.length;i++){
            Map<String,Object> row=binding(generated,BODY[i]);assertEquals(index.mainParagraph(BODY[i]).getText(),row.get("text"));
            assertTrue(String.valueOf(row.get("text")).contains(i==0?PLURAL:SINGULAR));assertEquals("exact",row.get("locationStatus"));
            assertTrue(DraftBusinessRules.list(row.get("fieldKeys")).containsAll(Arrays.asList("billNos","subcontractors","subcontractArrangement")));
            assertTrue(DraftBusinessRules.list(row.get("actionIds")).contains(IDS[i]));assertFalse(DraftBusinessRules.list(row.get("appliedActionIds")).contains(IDS[i]));
            assertEquals("pending",action(plan,IDS[i]).get("action"));
        }
    }
    @Test void actualBqAndSorInputsReplaceEachWholeNativeGroupWithoutCrossingTradeOrPurpose()throws Exception {
        byte[] original=source();TemplateIndex index=new DocxTemplateEditor().inspect(original);
        for(int target=0;target<IDS.length;target++)for(String type:Arrays.asList("BQ","SOR")){
            String number="AUTHORED-"+(target+1);Map<String,Object> row=DraftBusinessRules.map("number",number,"description","AUTHORED safety item","type",type,"purpose",PURPOSES[target],"trade",TRADES[target]);
            Map<String,Object> values=DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList(TRADES[target]),"billNos",Collections.singletonList(row));String before=JsonUtils.write(values);
            Map<String,Object> plan=DraftBusinessRules.plan(values);assertEquals("amend",action(plan,IDS[target]).get("action"));
            List<Map<String,Object>> generated=compile(original,plan,IDS[target]);Map<String,Object> result=binding(generated,BODY[target]);
            String label="BQ".equals(type)?"Bill":"Schedule",ending="BQ".equals(type)?"Bills of Quantities":"schedule of rates";
            String replacement=label+" No"+(target==0?"(s)":"")+". "+number+" of the "+ending;
            assertEquals(index.mainParagraph(BODY[target]).getText().replace(target==0?PLURAL:SINGULAR,replacement),result.get("text"));
            assertEquals("exact",result.get("locationStatus"));assertTrue(DraftBusinessRules.list(result.get("appliedActionIds")).contains(IDS[target]),JsonUtils.write(action(plan,IDS[target])));
            assertFalse(String.valueOf(result.get("text")).contains("_____"));assertFalse(String.valueOf(result.get("text")).contains("*"));
            for(int other=0;other<IDS.length;other++)if(other!=target)assertEquals(index.mainParagraph(BODY[other]).getText(),binding(generated,BODY[other]).get("text"));
            assertEquals(before,JsonUtils.write(values));
        }
    }
    @Test void aFireWaterNumberAndAnUnconfirmedPricingTypeNeverFillThePureFireBlank()throws Exception {
        byte[] original=source();String before=new DocxTemplateEditor().inspect(original).mainParagraph(1663).getText();
        for(Map<String,Object> row:Arrays.asList(
                DraftBusinessRules.map("number","12.3","description","AUTHORED fire/water safety","type","BQ","purpose","fireWaterSafety","trade","Fire services and water pump"),
                DraftBusinessRules.map("number","AUTHORED-FIRE","description","AUTHORED fire safety","purpose","fireSafety","trade","Fire services"))){
            Map<String,Object> values=DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services"),"billNos",Collections.singletonList(row));
            Map<String,Object> plan=DraftBusinessRules.plan(values);assertEquals("pending",action(plan,IDS[2]).get("action"));
            assertEquals(before,binding(compile(original,plan,IDS[2]),1663).get("text"));assertEquals("pending",action(plan,IDS[2]).get("action"));
        }
    }
    @Test void explicitPurposeTradeConflictsRetainOnlyTheirOwnNativeTargetWhileOtherValuesApply()throws Exception {
        byte[] original=source();TemplateIndex index=new DocxTemplateEditor().inspect(original);
        for(int target=1;target<IDS.length;target++){
            int other=target==1?2:1;String conflictingTrade=target==2?"Fire services and water pump":TRADES[other];
            Map<String,Object> conflict=DraftBusinessRules.map("number",target==2?"12.3":"AUTHORED-CONFLICT-"+target,"description","AUTHORED contradictory row","type","BQ","purpose",PURPOSES[target],"trade",conflictingTrade);
            Map<String,Object> ordinary=DraftBusinessRules.map("number","AUTHORED-OTHER","description","AUTHORED independent safety row","type","SOR","purpose",PURPOSES[other],"trade",TRADES[other]);
            Map<String,Object> general=DraftBusinessRules.map("number","AUTHORED-GENERAL","description","AUTHORED general safety row","type","BQ","purpose","bsSafety","trade","Lift");
            Map<String,Object> values=DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Arrays.asList(TRADES[target],TRADES[other],"Lift"),"billNos",Arrays.asList(conflict,ordinary,general));String before=JsonUtils.write(values);
            Map<String,Object> plan=DraftBusinessRules.plan(values);assertEquals("pending",action(plan,IDS[target]).get("action"));
            String message=String.valueOf(DraftBusinessRules.asMap(action(plan,IDS[target]).get("detail")).get("en"));assertTrue(message.contains("conflicts"),message);assertTrue(message.contains(TRADES[target]),message);
            assertEquals("amend",action(plan,IDS[other]).get("action"));assertEquals("amend",action(plan,IDS[0]).get("action"));
            assertEquals(values.get("billNos"),DraftBusinessRules.asMap(plan.get("effectiveValues")).get("billNos"),"A target conflict must not discard the pricing list");
            List<Map<String,Object>> generated=compile(original,plan,IDS[target],IDS[other],IDS[0]);
            Map<String,Object> retained=binding(generated,BODY[target]);assertEquals(index.mainParagraph(BODY[target]).getText(),retained.get("text"));assertFalse(DraftBusinessRules.list(retained.get("appliedActionIds")).contains(IDS[target]));
            assertTrue(String.valueOf(binding(generated,BODY[other]).get("text")).contains("Schedule No. AUTHORED-OTHER of the schedule of rates"));
            assertTrue(String.valueOf(binding(generated,BODY[0]).get("text")).contains("Bill No(s). AUTHORED-GENERAL of the Bills of Quantities"));
            assertEquals(before,JsonUtils.write(values));
        }
    }
    @Test void omittedTradeAndEachSelectedGeneralBsTradeKeepNativePricingCompatibility()throws Exception {
        byte[] original=source();
        Map<String,Object> omitted=DraftBusinessRules.map("number","AUTHORED-FIRE","description","AUTHORED safety with no trade column","type","BQ","purpose","fireSafety");
        Map<String,Object> values=DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services"),"billNos",Collections.singletonList(omitted));
        Map<String,Object> plan=DraftBusinessRules.plan(values);assertEquals("amend",action(plan,IDS[2]).get("action"));
        assertTrue(String.valueOf(binding(compile(original,plan,IDS[2]),1663).get("text")).contains("Bill No. AUTHORED-FIRE of the Bills of Quantities"));
        for(String trade:DraftBlueprint.SERVICES){
            Map<String,Object> row=DraftBusinessRules.map("number","AUTHORED-BS","description","AUTHORED general safety","type","SOR","purpose","bsSafety","trade",trade);
            Map<String,Object> general=DraftBusinessRules.plan(DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList(trade),"billNos",Collections.singletonList(row)));
            assertEquals("amend",action(general,IDS[0]).get("action"),trade);
            assertTrue(String.valueOf(binding(compile(original,general,IDS[0]),1428).get("text")).contains("Schedule No(s). AUTHORED-BS of the schedule of rates"),trade);
        }
    }
    @Test void actualExplicitSharedSafetyBillFillsGeneralAndPureFireWithoutDuplicatingPhysicalBill()throws Exception {
        byte[] original=source();TemplateIndex index=new DocxTemplateEditor().inspect(original);
        for(String type:Arrays.asList("BQ","SOR")){
            Map<String,Object> general=DraftBusinessRules.map("number","AUTHORED-SHARED-7","description","AUTHORED identical safety description","type",type,"purpose","bsSafety");
            Map<String,Object> fire=new LinkedHashMap<>(general);fire.put("purpose","fireSafety");fire.put("trade","Fire services");
            Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services"),"billNos",Arrays.asList(general,fire)));
            assertEquals("amend",action(plan,IDS[0]).get("action"));assertEquals("amend",action(plan,IDS[2]).get("action"));
            assertEquals(1,DraftBusinessRules.list(action(plan,"sct-shared-bill-list").get("value")).size());
            List<Map<String,Object>> generated=compile(original,plan,IDS[0],IDS[2]);
            String noun="BQ".equals(type)?"Bill":"Schedule",ending="BQ".equals(type)?"Bills of Quantities":"schedule of rates";
            for(int target:new int[]{0,2}){String replacement=noun+" No"+(target==0?"(s)":"")+". AUTHORED-SHARED-7 of the "+ending;
                assertEquals(index.mainParagraph(BODY[target]).getText().replace(target==0?PLURAL:SINGULAR,replacement),binding(generated,BODY[target]).get("text"));
                assertTrue(DraftBusinessRules.list(binding(generated,BODY[target]).get("appliedActionIds")).contains(IDS[target]));}
        }
    }
    @Test void actualSelectedPureFireWithoutItsOwnAdoptedPricingRetainsCompleteNativeBlank()throws Exception {
        byte[] original=source();String before=new DocxTemplateEditor().inspect(original).mainParagraph(1663).getText();
        List<Map<String,Object>> cases=new ArrayList<>();
        for(Object bills:Arrays.asList(null,Collections.emptyList(),Collections.singletonList(DraftBusinessRules.map("number","AUTHORED-GENERAL","description","AUTHORED general safety","type","BQ","purpose","bsSafety")))){
            Map<String,Object> values=DraftBusinessRules.map("subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services"));if(bills!=null)values.put("billNos",bills);cases.add(values);}
        for(Map<String,Object> values:cases){Map<String,Object> plan=DraftBusinessRules.plan(values);assertEquals("pending",action(plan,IDS[2]).get("action"));assertEquals(before,binding(compile(original,plan,IDS[2]),1663).get("text"));}
    }

    @Test void actualSctNativeDistributionWritesTheSharedPhysicalBillOnceInEachIssueMode()throws Exception {
        String directory=System.getProperty("consense.acceptance.sourceDir");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null&&!directory.trim().isEmpty(),"Actual 2a SCT requires explicit sourceDir; absence is SKIP, not source acceptance.");
        byte[] original=Files.readAllBytes(Paths.get(directory,"02_Special Conditions of Tender (SCT).docx"));
        assertEquals("7def01d62e07b35dd86beeff995e0e37d73ce18adca2746e2a6f6d9bafb5aaa4",DraftPdfConverter.sha256(original));
        for(String mode:Arrays.asList("L10Pro","Hardcopy"))for(String type:Arrays.asList("BQ","SOR")){
            String placement="L10Pro".equals(mode)?"DiscB":"Hardcopy";
            Map<String,Object> general=DraftBusinessRules.map("number","7","description","AUTHORED native shared safety description","type",type,"purpose","bsSafety","placement",placement);
            Map<String,Object> fire=new LinkedHashMap<>(general);fire.put("purpose","fireSafety");fire.put("trade","Fire services");
            List<Object> rows=Arrays.asList(DraftBusinessRules.map("number","1","description","Preliminaries","type","BQ","purpose","preliminaries","placement","standard"),DraftBusinessRules.map("number","2","description","Preambles","type","BQ","purpose","preambles","placement","standard"),general,fire);
            Map<String,Object> plan=DraftBusinessRules.plan(DraftBusinessRules.map("billNos",rows,"electronicTendering",mode,"subcontractArrangement","BSSSC","subcontractors",Collections.singletonList("Fire services")));
            Map<String,Object> distribution=action(plan,"sct-shared-bill-list");plan.put("actions",Arrays.asList(action(plan,"sct-issue-alternative"),distribution));
            DocxEditResult result=DraftFormattedRules.compile(original,"SCT",plan);
            String emitted=result.getIndex().getMainParagraphs().stream().map(p->p.getCatalogText()).collect(java.util.stream.Collectors.joining("\n"));
            String label=("BQ".equals(type)?"Bill No. ":"Schedule No. ")+"7 – AUTHORED native shared safety description";
            assertEquals("applied",distribution.get("applicationState"),JsonUtils.write(distribution));
            assertEquals(1,emitted.split(java.util.regex.Pattern.quote(label),-1).length-1,mode+"/"+type+": actual emitted DOCX contains one physical pricing-document entry");
            if("Hardcopy".equals(mode))assertTrue(result.getIndex().getMainParagraphs().stream().anyMatch(p->p.getCatalogText().equals(DraftBusinessRules.standardParagraph("SCT",387))),"Native emission must retain the complete Disc C explanation, not replace its Bill2 substring.");
            assertEquals(4,DraftBusinessRules.list(DraftBusinessRules.asMap(plan.get("effectiveValues")).get("billNos")).size());
        }
    }

}
