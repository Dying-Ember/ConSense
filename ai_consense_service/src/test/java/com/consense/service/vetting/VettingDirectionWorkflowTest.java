package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.service.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ordinary authored software checks; no provider, original, index or business endpoint is used. */
class VettingDirectionWorkflowTest {
    @Test @SuppressWarnings("unchecked") void mixedScopeInstructionsReachTheActualTextPacketAndKeepUnknownContractEvidencePending()throws Exception{
        AiGateway ai=mock(AiGateway.class);VettingRetrievalClient retrieval=mock(VettingRetrievalClient.class);
        VettingSemanticReview review=new VettingSemanticReview(ai,new ConsenseProperties(),retrieval);
        java.lang.reflect.Field field=VettingSemanticReview.class.getDeclaredField("activeDirection");field.setAccessible(true);
        ThreadLocal<VettingReviewDirections.Direction> active=(ThreadLocal<VettingReviewDirections.Direction>)field.get(review);
        java.lang.reflect.Method packet=VettingSemanticReview.class.getDeclaredMethod("textFirstPacket",String.class,VettingContextBuilder.Selection.class);packet.setAccessible(true);
        Chunk tender=chunk("scope-source","The Contractor shall provide the builder's works expressly required by this tender.");
        VettingContextBuilder.Selection selection=VettingSemanticReview.directionSelection(Collections.singletonList(tender),Collections.emptyList(),1000);
        List<VettingReviewDirections.Direction> plan=VettingReviewDirections.plan(new String[0]);
        for(String id:Arrays.asList("APP-I-007","APP-I-027","APP-I-048","APP-I-055")){
            VettingReviewDirections.Direction direction=plan.stream().filter(d->d.itemIds().contains(id)).findFirst().orElseThrow();
            VettingChecklistCatalog.Item item=direction.items.stream().filter(i->id.equals(i.getId())).findFirst().orElseThrow();
            active.set(direction);
            try{
                String user=(String)packet.invoke(review,direction.title,selection);
                assertTrue(user.contains(item.getId()+" | "+item.getSection()+" | "+item.getClause()+" | "+item.getDescription()),"The complete official requirement is retained");
                assertTrue(user.contains("Scope for "+id+" ("+item.getReviewScope()+"): "+item.getScopeNote()),id+" must carry its existing scope policy into the actual packet");
                assertTrue(user.contains("Do not withhold a supported contract-text assessment merely because excluded pricing or drawing evidence is unavailable."));
                assertTrue(user.contains("retain insufficient_context and name the missing evidence"));
                assertTrue(user.contains("Do not mark the entire row fulfilled from a partial check."));
                assertEquals(1,user.split(java.util.regex.Pattern.quote(tender.getContent()),-1).length-1,"Scope guidance must not repeat or alter source evidence");
            }finally{active.remove();}
        }
        assertFalse(plan.get(0).instructions().contains("Scope for "),"Unrelated in-scope rows do not receive redundant mixed-scope instructions");
        assertEquals(Collections.singletonList(tender),selection.getChunks());verifyNoInteractions(ai,retrieval);
    }
    @Test void explicitProjectLocatorsComeFromAssignedClausesRatherThanGeneralQueryHints(){
        List<VettingReviewDirections.Direction> plan=VettingReviewDirections.plan(new String[]{"APU.A010 GCC1.1 general discussion"});
        assertEquals(Collections.singletonList("APB.A010"),plan.stream().filter(d->d.itemIds().contains("APP-I-056")).findFirst().orElseThrow().explicitProjectClauses());
        assertEquals(Collections.singletonList("APU.A010"),plan.stream().filter(d->d.itemIds().contains("APP-I-057")).findFirst().orElseThrow().explicitProjectClauses());
        assertTrue(plan.get(plan.size()-1).explicitProjectClauses().isEmpty(),"General topics do not silently become required source selections");
    }
    @Test void theActualDirectionCallerSuppliesItsNamedManifestWithoutChangingItsRole()throws Exception{
        exerciseTargetedFollowup(false,0,false,55,false,0,0,false,true);
    }
    @Test void directionSummariesDistinguishRetrievalAdmissionAndModelOutcomeWithoutExtraCalls()throws Exception {
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VettingReviewProbe.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<>();logs.start();logger.addAppender(logs);
        try{
            exerciseInitialCounterpart(true,false,false);
            List<String> summaries=new ArrayList<>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list)
                if(event.getFormattedMessage().startsWith("ConSense stage kind=review"))summaries.add(event.getFormattedMessage());
            String selected=summaries.stream().filter(s->s.contains("stage=direction_selection")&&s.contains("selectedChunks=2")).findFirst().orElseThrow();
            assertTrue(selected.contains("rankedSeeds=1"));assertTrue(selected.contains("offeredChunks=3"));assertTrue(selected.contains("budgetOmittedChunks=1"));
            assertTrue(selected.contains("tenderChunks=1"));assertTrue(selected.contains("standardChunks=1"));
            assertTrue(summaries.stream().anyMatch(s->s.contains("stage=counterpart_admission")&&s.contains("counterpartChunksRetrieved=1")&&s.contains("counterpartChunksAdmitted=1")));
            assertTrue(summaries.stream().anyMatch(s->s.contains("stage=direction_packet_result")&&s.contains("actualGatewayCall=true")&&s.contains("returnedAssessments=0")));
            for(String summary:summaries){assertTrue(summary.length()<1300);assertFalse(summary.contains("The programme shall"));assertFalse(summary.contains("Unrelated project text"));assertFalse(summary.contains("Submit the programme within"));}
        }finally{logger.detachAppender(logs);logs.stop();}
    }
    @Test void retrievalHintsStaySmallWhileTheModelReceivesEveryCompleteRequirement(){
        for(VettingReviewDirections.Direction direction:VettingReviewDirections.plan(new String[]{"payment"})){
            List<String> initial=VettingReviewDirections.searchQueries(direction.initialQuery());
            assertEquals(1,initial.size(),direction.id+" must start with one intentionally concise query");
            for(VettingChecklistCatalog.Item item:direction.items)assertTrue(initial.get(0).contains(item.getClause()),"Every assigned clause anchor remains available");
            List<String> queries=VettingReviewDirections.searchQueries(direction.query);
            for(String query:queries)assertTrue(query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1200,direction.id+" query exceeds the retrieval hint budget");
            assertEquals(direction.query.trim().replaceAll("\\s+"," "),String.join(" ",queries).replaceAll("\\s+"," "));
            for(VettingChecklistCatalog.Item item:direction.items)
                assertTrue(direction.instructions().contains(item.getDescription()),"The full requirement must remain in the review instructions");
        }
        String longWord="😀".repeat(150);List<String> unicode=VettingReviewDirections.searchQueries(longWord);
        assertEquals(longWord,String.join("",unicode));
        for(String query:unicode)assertTrue(query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1200);
    }
    @Test void onlyATokenBudgetRejectionSplitsTheRejectedQueryAndPreservesItsText(){
        String query="extension of time ".repeat(60).trim();List<String> attempts=new ArrayList<>();
        List<String> hits=VettingReviewDirections.searchWithFallback(query,part->{
            attempts.add(part);
            if(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>400)
                throw new com.consense.common.BizException("HTTP 422 {\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}");
            return Collections.singletonList(part);
        });
        assertEquals(query,String.join(" ",hits));assertEquals(query,attempts.get(0));assertTrue(hits.size()>1);
        assertThrows(IllegalStateException.class,()->VettingReviewDirections.searchWithFallback(query,part->{throw new IllegalStateException("source changed");}));
        assertThrows(com.consense.common.BizException.class,()->VettingReviewDirections.searchWithFallback("short",part->{
            throw new com.consense.common.BizException("HTTP 422 {\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}");
        }));
    }
    @Test void selectedSlSearchAlsoRetriesOnlyTheOverBudgetQuery(){
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);
        when(fixed.projectSelection("project")).thenReturn(JsonUtils.parse("{}"));
        String query="selected standard clause ".repeat(40).trim();List<String> attempts=new ArrayList<>();
        when(fixed.searchForProject(eq("project"),eq("vetting"),anyString(),eq(10))).thenAnswer(call->{
            String part=call.getArgument(2);attempts.add(part);
            if(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>400)
                throw new com.consense.common.BizException("HTTP 422 {\"detail\":{\"kind\":\"QUERY_TOKEN_BUDGET_EXHAUSTED\"}}");
            return Collections.emptyList();
        });
        assertTrue(FixedVettingReferences.retrieve(fixed,"project",Collections.singletonList(query),10).isEmpty());
        assertEquals(query,attempts.get(0));assertEquals(query,String.join(" ",attempts.subList(1,attempts.size())));
        verify(fixed,atLeast(attempts.size())).projectSelection("project");
    }
    @Test void everyInScopeRequirementIsAssignedOnceWithItsMeaningAndNoExcludedOrAnnexAnswerRows(){
        List<VettingReviewDirections.Direction> plan=VettingReviewDirections.plan(new String[]{"payment","submission","scope","language"});
        Set<String> expected=new LinkedHashSet<>();for(VettingChecklistCatalog.Item item:VettingChecklistCatalog.items())
            if(!VettingChecklistCatalog.annexItemIds().contains(item.getId())&&!"pricing_document_excluded".equals(item.getReviewScope()))expected.add(item.getId());
        List<String> assigned=new ArrayList<>();for(VettingReviewDirections.Direction direction:plan){
            assigned.addAll(direction.itemIds());for(VettingChecklistCatalog.Item item:direction.items)assertTrue(direction.instructions().contains(item.getId()+" | "+item.getSection()+" | "+item.getClause()+" | "+item.getDescription()));
            assertTrue(direction.itemIds().size()<expected.size(),"No packet receives the entire checklist");
        }
        assertEquals(expected,new LinkedHashSet<>(assigned));assertEquals(expected.size(),assigned.size());
        assertEquals(2,plan.stream().filter(d->d.items.isEmpty()).count());
    }
    @Test void assignedSchemaContainsOnlyAssignedIdsAndGeneralReviewHasNoChecklistRows(){
        JsonNode schema=VettingOutputSchema.forChunkIds(Collections.singletonList("R1"),Arrays.asList("APP-I-001","APP-I-011"));
        assertEquals(JsonUtils.parse("[\"APP-I-001\",\"APP-I-011\"]"),schema.path("items").path("properties").path("checklistAssessments").path("items").path("properties").path("itemId").path("enum"));
        assertEquals(0,VettingOutputSchema.forChunkIds(Collections.singletonList("R1"),Collections.emptyList()).path("items").path("properties").path("checklistAssessments").path("maxItems").asInt());
    }
    @Test void followupPreservesRequiredAffectedTextAndNeverSilentlyTrimsAnOversizedNeed(){
        Chunk a=chunk("a","Affected source requires a signed tender submission."),b=chunk("b","Named counterpart specifies the required copies."),unrelated=chunk("other","Unrelated insurance conditions are not needed here.");
        VettingContextBuilder.Selection result=VettingSemanticReview.directionSelection(Arrays.asList(b,unrelated),Collections.singletonList(a),a.getContent().length()+b.getContent().length());
        assertEquals(Arrays.asList(a,b),result.getChunks());
        assertTrue(VettingSemanticReview.directionSelection(Collections.singletonList(b),Collections.singletonList(a),a.getContent().length()-1).getChunks().isEmpty());
    }
    @Test void selectedTextKeepsSameSourcePassagesTogetherInObservedOrderWithoutAddingEvidence(){
        Chunk later=ordered("later","1","revision-a",40,"The specification includes the named standard."),
                other=ordered("other","2","revision-b",3,"A different document."),
                earlier=ordered("earlier","1","revision-a",38,"For works outside the boundary, the following applies.");
        VettingContextBuilder.Selection selection=VettingSemanticReview.directionSelection(Arrays.asList(later,other,earlier),Collections.emptyList(),1000);
        assertEquals(Arrays.asList(earlier,later,other),selection.getChunks());
        assertEquals(earlier.getId(),new VettingPacketReferences(selection.getChunks()).bindings().get("R1"));
        String prompt=VettingSemanticReview.textFirstUser("Applicability",selection);
        assertTrue(prompt.indexOf(earlier.getContent())<prompt.indexOf(later.getContent()));
        assertTrue(prompt.indexOf(later.getContent())<prompt.indexOf(other.getContent()));
    }
    @Test void textOrderingDoesNotJoinDifferentRevisionsOrInventUnknownSourceOrder(){
        Chunk a=ordered("a","1","revision-a",9,"A later passage."),
                other=ordered("other","2","revision-b",1,"Another source."),
                changed=ordered("changed","1","revision-c",1,"A different revision.");
        assertEquals(Arrays.asList(a,other,changed),VettingSemanticReview.directionSelection(Arrays.asList(a,other,changed),Collections.emptyList(),1000).getChunks());
        Chunk unknown=ordered("unknown","1","revision-a",1,"An unknown source order.");unknown.getCanonicalEvidence().setSourceOrder(null);
        assertEquals(Arrays.asList(a,unknown,other),VettingSemanticReview.directionSelection(Arrays.asList(a,other,unknown),Collections.emptyList(),1000).getChunks());
    }
    @Test void projectTextAndSelectedSlBothFitBeforeExtraProjectHitsFillTheBudget(){
        Chunk first=chunk("first","aaa"),second=chunk("second","bbb"),sl=chunk("sl","ss");sl.setRole("standard");
        List<Chunk> project=Arrays.asList(first,second),standards=Collections.singletonList(sl);
        List<Chunk> offered=VettingSemanticReview.directionEvidenceOrder(project,standards,standards,Collections.emptyList(),5);
        assertEquals(Arrays.asList(first,sl),VettingSemanticReview.directionSelection(offered,Collections.emptyList(),5).getChunks());
        Chunk large=chunk("large","aaaa"),small=chunk("small","bb"),longSl=chunk("long-sl","sss");longSl.setRole("standard");
        List<Chunk> fitting=VettingSemanticReview.directionEvidenceOrder(Arrays.asList(large,small),Collections.singletonList(longSl),Collections.singletonList(longSl),Collections.emptyList(),5);
        assertEquals(Collections.singletonList(large),VettingSemanticReview.directionSelection(fitting,Collections.emptyList(),5).getChunks(),"A reference cannot replace the first ranked tender origin with a smaller one");
        List<Chunk> followup=VettingSemanticReview.directionEvidenceOrder(Collections.singletonList(second),standards,standards,Collections.singletonList(first),8);
        assertEquals(Arrays.asList(first,second,sl),VettingSemanticReview.directionSelection(followup,Collections.singletonList(first),8).getChunks());
    }
    @Test void contradictoryIssueWithExplicitMissingEvidenceRemainsPending(){
        VettingSemanticReview.ChecklistAssessment check=new VettingSemanticReview.ChecklistAssessment();check.setStatus("insufficient_evidence");
        VettingSemanticReview.Record record=new VettingSemanticReview.Record();record.setAssessment("issue");record.setSuggestedAction("review");
        record.setPendingFacts(Collections.singletonList("The applicable scope needs confirmation"));record.setChecklistAssessments(Collections.singletonList(check));
        assertEquals(1,VettingSemanticReview.retainContradictoryIssuesAsPending(Collections.singletonList(record)));
        assertEquals("insufficient_context",record.getAssessment());assertEquals("insufficient_evidence",check.getStatus());
        record.setAssessment("issue");record.setSuggestedAction("insert");
        assertEquals(0,VettingSemanticReview.retainContradictoryIssuesAsPending(Collections.singletonList(record)));
        assertEquals("issue",record.getAssessment());
    }
    @Test void assignedGccCounterpartReachesTheFirstModelCallBeforeUnrelatedProjectText()throws Exception{exerciseInitialCounterpart(true,false,false);}
    @Test void missingAssignedCounterpartRemainsExplicitlyPending()throws Exception{exerciseInitialCounterpart(false,false,false);}
    @Test void generalDirectionDoesNotSearchTheStandardLibraryEagerly()throws Exception{exerciseInitialCounterpart(true,true,false);}
    @Test void selectedReferenceScopeChangeStopsKnownCounterpartPublication()throws Exception{exerciseInitialCounterpart(true,false,true);}
    @Test void matchingProjectCounterpartPrecedesTheLibraryCopyWithoutClaimingAdoption()throws Exception{exerciseInitialCounterpart(true,false,false,true);}
    @Test void actualCallerKeepsNamedRankedCounterpartBeforeAFullProjectContext()throws Exception{exerciseInitialCounterpart(true,false,false,true,true);}
    @Test void actualAppendixUCallerKeepsNamedSeedAndOperativeTextBeforeBulkTableRows()throws Exception{exerciseInitialCounterpart(true,false,false,true,true,true);}
    @Test void allRankedTenderOriginsPrecedeBulkNamedCounterparts(){
        Chunk a=chunk("a","aaa"),b=chunk("b","bbb"),gcc=chunk("gcc","cc"),gct=chunk("gct","tt");gcc.setRole("standard");gct.setRole("standard");
        List<Chunk> named=Arrays.asList(gcc,gct);
        List<Chunk> offered=VettingSemanticReview.directionEvidenceOrder(Arrays.asList(a,b),named,named,Collections.emptyList(),9);
        VettingContextBuilder.Selection selection=VettingSemanticReview.directionSelection(offered,Collections.emptyList(),9);
        assertEquals(Arrays.asList(a,b,gcc),selection.getChunks());assertEquals(8,selection.getContentChars());
        assertTrue(VettingSemanticReview.directionSelection(VettingSemanticReview.directionEvidenceOrder(Collections.singletonList(a),named,named,Collections.emptyList(),4),Collections.emptyList(),4).getChunks().contains(a));
        assertTrue(VettingSemanticReview.initialReferenceNote(Collections.singletonList("GCC 5.7"),true,named,Collections.singletonList(a)).contains("within the text budget"));
    }
    @Test void severalRankedTenderSeedsAndTheirRelatedTextSurviveManyDeduplicatedCounterparts(){
        Chunk first=chunk("ranked-a",String.join("",Collections.nCopies(177,"a")));
        Chunk second=chunk("ranked-b",String.join("",Collections.nCopies(1000,"b")));
        Chunk third=chunk("ranked-c",String.join("",Collections.nCopies(700,"c")));
        Chunk context=chunk("related-project",String.join("",Collections.nCopies(1200,"p")));
        List<Chunk> project=new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(context,first,second,third),Arrays.asList(first.getId(),second.getId(),third.getId()));
        List<Chunk> references=new ArrayList<Chunk>();
        for(int i=0;i<20;i++){Chunk reference=chunk("counterpart-"+i,String.join("",Collections.nCopies(600,"r")));
            reference.setRole(i==0?"package_manifest":i%2==0?"tender":"standard");references.add(reference);references.add(reference);}
        List<Chunk> rankedReferences=new OerBusinessEvidence.ReviewTextChunks(references,Collections.singleton(references.get(0).getId()));
        VettingContextBuilder.Selection selection=VettingSemanticReview.directionSelection(VettingSemanticReview.directionEvidenceOrder(project,rankedReferences,rankedReferences,Collections.emptyList(),6000),Collections.emptyList(),6000);
        assertEquals(Arrays.asList(first,second,third,references.get(0),context),selection.getChunks().subList(0,5),"Ranked project and counterpart seeds precede both sources' bulk context");
        Set<String> ids=new HashSet<String>();int tender=0,standard=0,manifest=0,chars=0;
        for(Chunk selected:selection.getChunks()){assertTrue(ids.add(selected.getId()),"Repeated counterpart IDs must consume the budget once");chars+=selected.getContent().length();
            if("tender".equals(selected.getRole()))tender++;else if("standard".equals(selected.getRole()))standard++;else if("package_manifest".equals(selected.getRole()))manifest++;}
        assertEquals(5,tender);assertEquals(2,standard);assertEquals(1,manifest);assertEquals(5477,chars);assertEquals(chars,selection.getContentChars());assertTrue(chars<=6000);
        for(Chunk original:Arrays.asList(first,second,third,context))assertSame(original,selection.getChunks().stream().filter(c->c.getId().equals(original.getId())).findFirst().orElseThrow(AssertionError::new));
    }
    @Test void requiredManifestBodyKeepsItsPriorityWhenANamedCounterpartCannotFit(){
        Chunk tender=chunk("tender-anchor","The reference drawings listed in Appendix U do not form part of the Contract.");
        Chunk heading=chunk("list-heading","APU.A010.P");heading.setRole("package_manifest");
        Chunk body=chunk("list-body","These drawings are for reference only and are not part of the Drawings or Contract.");body.setRole("package_manifest");
        Chunk bulk=chunk("bulk-project","p".repeat(9800));
        List<Chunk> required=new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(heading,body),Collections.singleton(heading.getId()));
        List<Chunk> project=VettingSemanticReview.requiredProjectTextFirst(new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(tender,bulk),Collections.singleton(tender.getId())),required);
        Chunk oversized=chunk("named-reference","r".repeat(10000));oversized.setRole("standard");
        List<Chunk> named=Collections.singletonList(oversized);
        VettingContextBuilder.Selection selected=VettingSemanticReview.directionSelection(VettingSemanticReview.directionEvidenceOrder(project,named,named,required,10000),Collections.emptyList(),10000);
        assertTrue(selected.getChunks().containsAll(Arrays.asList(tender,heading,body)));assertFalse(selected.getChunks().contains(oversized));
        assertEquals("package_manifest",body.getRole());assertSame(body,selected.getChunks().stream().filter(c->c.getId().equals(body.getId())).findFirst().orElseThrow());
        assertTrue(selected.getContentChars()<=10000);assertEquals(10000,oversized.getContent().length());
    }
    @Test void followupKeepsItsAffectedTextAndNamedGccSeedBeforeBulkContext(){
        Chunk affected=chunk("affected","Affected programme submission provision.");
        Chunk seed=chunk("retrieved-seed","The matching project Letter of Acceptance specifies the submission period.");
        Chunk bulk=chunk("bulk","p".repeat(19900));
        Chunk gcc=chunk("named-gcc","GCC 5.7: Submit the programme within fourteen days unless otherwise specified.");gcc.setRole("standard");
        List<Chunk> project=new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(seed,bulk),Collections.singleton(seed.getId()));
        List<Chunk> named=Collections.singletonList(gcc),required=Collections.singletonList(affected);
        VettingContextBuilder.Selection selected=VettingSemanticReview.directionSelection(VettingSemanticReview.directionEvidenceOrder(project,named,named,required,20000),required,20000);
        assertTrue(selected.getChunks().containsAll(Arrays.asList(affected,seed,gcc)));assertFalse(selected.getChunks().contains(bulk));
        assertTrue(selected.getContentChars()<=20000);assertEquals(19900,bulk.getContent().length());
    }
    @Test void knownReferenceQueriesUseOnlyAssignedNamesAndPreserveExplicitClauseIdentifiers(){
        VettingReviewDirections.Direction programme=VettingReviewDirections.plan(new String[0]).stream().filter(d->d.itemIds().contains("APP-I-017")).findFirst().orElseThrow();
        assertEquals(Collections.singletonList("GCC 5.7"),VettingSemanticReview.initialReferenceQueries(programme));
        VettingReviewDirections.Direction definitions=VettingReviewDirections.plan(new String[0]).get(1);
        List<String> references=VettingSemanticReview.initialReferenceQueries(definitions);
        assertEquals(Arrays.asList("GCC 1.1","GCC 8.1"),references,"A broad SL family mention must wait for a concrete evidence need");
        assertTrue(VettingSemanticReview.initialReferenceQueries(new VettingReviewDirections.Direction("general","General source review","GCC SL wording",Collections.emptyList())).isEmpty());
        for(VettingReviewDirections.Direction direction:VettingReviewDirections.plan(new String[0]))for(String query:VettingSemanticReview.initialReferenceQueries(direction))
            assertTrue(query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=1200);
    }
    private void exerciseInitialCounterpart(boolean available,boolean general,boolean selectionChanged)throws Exception{
        exerciseInitialCounterpart(available,general,selectionChanged,false);
    }
    private void exerciseInitialCounterpart(boolean available,boolean general,boolean selectionChanged,boolean projectCopy)throws Exception{
        exerciseInitialCounterpart(available,general,selectionChanged,projectCopy,false);
    }
    private void exerciseInitialCounterpart(boolean available,boolean general,boolean selectionChanged,boolean projectCopy,boolean fullProjectContext)throws Exception{
        exerciseInitialCounterpart(available,general,selectionChanged,projectCopy,fullProjectContext,false);
    }
    private void exerciseInitialCounterpart(boolean available,boolean general,boolean selectionChanged,boolean projectCopy,boolean fullProjectContext,boolean fullRequiredManifest)throws Exception{
        ConsenseProperties props=new ConsenseProperties();props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_TEST_CREDENTIAL");props.getVetting().setSemanticContextChars(180);
        Chunk tender=chunk("tender","The programme shall be submitted within the period stated in the project Letter of Acceptance.");
        Chunk unrelated=chunk("extra","Unrelated project text. ".repeat(20));
        Chunk gcc=chunk("gcc","GCC 5.7: Submit the programme within 14 days of the Letter of Acceptance, unless otherwise stated.");gcc.setRole("standard");gcc.setFileKey("GCC");
        Chunk projectGcc=chunk("project-gcc","Project-supplied GCC 5.7: Use the programme period expressly stated in the Letter of Acceptance.");projectGcc.setRole("standard");projectGcc.setFileKey("GCC");
        props.getVetting().setSemanticContextChars(tender.getContent().length()+(projectCopy?projectGcc:gcc).getContent().length()+5);
        Chunk secondTender=chunk("second-tender","The project Letter of Acceptance states the programme submission period.");
        Chunk counterpartContext=chunk("counterpart-context","Named reference context. ".repeat(500));
        if(fullProjectContext){props.getVetting().setSemanticContextChars(10000);unrelated.setContent("p".repeat(10000-tender.getContent().length()-secondTender.getContent().length()));}
        Chunk manifestHeading=chunk("ap-u-heading","APU.A010.P");manifestHeading.setRole("package_manifest");manifestHeading.setFileKey("APU");
        Chunk manifestBody=chunk("ap-u-body","The listed reference drawings are not part of the Drawings or Contract.");manifestBody.setRole("package_manifest");manifestBody.setFileKey("APU");
        List<Chunk> manifestText=new ArrayList<>(Arrays.asList(manifestHeading,manifestBody));
        if(fullRequiredManifest){
            gcc.setContent("GCC 1.1: Drawings means the Contract drawings and their agreed modifications.");
            projectGcc.setContent("Project GCC 1.1: Drawings means the Contract drawings issued under the Contract.");
            int remaining=10000-tender.getContent().length()-secondTender.getContent().length()-manifestHeading.getContent().length()-manifestBody.getContent().length();
            for(int i=0;remaining>0;i++){int chars=Math.min(96,remaining);Chunk row=chunk("ap-u-table-"+i,"t".repeat(chars));row.setRole("package_manifest");row.setFileKey("APU");manifestText.add(row);remaining-=chars;}
        }
        ObjectNode raw=JsonUtils.mapper().valueToTree(gcc);raw.putObject("sourceQuality").put("family","GCC");
        FixedReferenceEvidence proof=new FixedReferenceEvidence(gcc.getId(),0.9,raw,null,null);com.consense.vector.VectorStore.SearchHit hit=proof.asHit();
        HttpSupport http=mock(HttpSupport.class);List<String> users=new ArrayList<>();List<String> standardQueries=new ArrayList<>();List<String> counterpartProjectQueries=new ArrayList<>();
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
            JsonNode request=JsonUtils.parse(call.getArgument(1));boolean preflight=request.path("max_tokens").asInt()==1;
            if(!preflight){users.add(request.path("messages").get(1).path("content").asText());if(!general)assertFalse(standardQueries.isEmpty(),"The assigned standard lookup must precede the first paid answer");}
            ObjectNode response=JsonUtils.mapper().createObjectNode();response.put("object","chat.completion");response.put("model","qwen3.8-27b");
            ObjectNode choice=response.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason",preflight?"length":"stop");
            choice.putObject("message").put("role","assistant").put("content",preflight?"{":"{\"records\":[]}");response.putObject("usage").put("prompt_tokens",100).put("completion_tokens",1);return JsonUtils.write(response);
        });
        LlmClient local=mock(LlmClient.class);LlmProfiles profiles=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local);
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local,profiles),props,mock(VettingRetrievalClient.class));
        review.setQwenUsagePreflight(new QwenChatUsagePreflight(props,mock(QwenGenerationLedger.class)));
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);when(fixed.enabled()).thenReturn(true);
        JsonNode selected=JsonUtils.parse("{\"slSources\":[{\"editionId\":\"selected-edition\",\"sha256\":\"selected-sha\"}]}");
        if(selectionChanged)when(fixed.projectSelection("authored")).thenReturn(selected,JsonUtils.parse("{\"slSources\":[]}"));else when(fixed.projectSelection("authored")).thenReturn(selected);
        when(fixed.searchForProject(eq("authored"),eq("vetting"),anyString(),anyInt())).thenAnswer(call->{standardQueries.add(call.getArgument(2));assertTrue(users.isEmpty(),"Known evidence should not need a prior model rejection");return available?Collections.singletonList(hit):Collections.emptyList();});
        when(fixed.evidence(hit)).thenReturn(proof);review.setFixedBaselineRetrievalClient(fixed);
        OerBusinessEvidence oer=mock(OerBusinessEvidence.class);review.setOerBusinessEvidence(oer);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);
        OerBusinessEvidence.Session session=ctor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        List<Chunk> corpusChunks=new ArrayList<>(Arrays.asList(tender,secondTender,unrelated,projectGcc,counterpartContext));if(fullRequiredManifest)corpusChunks.addAll(manifestText);
        List<Chunk> corpus=corpus(session,corpusChunks);
        when(oer.retrieveRequiredProjectText(eq(session),eq("APU.A010"),any(),anyInt())).thenReturn(fullRequiredManifest?new OerBusinessEvidence.ReviewTextChunks(manifestText,Collections.singleton(manifestHeading.getId())):Collections.emptyList());
        when(oer.retrieveReviewText(eq(session),anyString(),nullable(String.class),any(),anyInt())).thenAnswer(call->{String query=call.getArgument(1),role=call.getArgument(2);
            if(role==null){counterpartProjectQueries.add(query);return projectCopy?(fullProjectContext?new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(counterpartContext,projectGcc),Collections.singleton(projectGcc.getId())):Collections.singletonList(projectGcc)):Collections.emptyList();}
            boolean target=fullRequiredManifest?query.contains("contractual execution requirements in Appendices A B C versus Appendix U"):general?query.contains("payment interim certificates"):query.contains("programme submission period GCC 5.7");return target?new OerBusinessEvidence.ReviewTextChunks(fullProjectContext?Arrays.asList(tender,secondTender,unrelated):Arrays.asList(tender,unrelated),fullProjectContext?Arrays.asList(tender.getId(),secondTender.getId()):Collections.singleton(tender.getId())):Collections.emptyList();});
        java.lang.reflect.Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);run.setAccessible(true);
        LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation operation=review.captureOperation(chat);VettingSemanticReview.Result result;
        try(LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope bound=review.bindOperation(operation);QwenChatUsagePreflight.Operation.ActionScope action=operation.getQwen().openAction("AUTHORED initial counterpart",()->{})){
            result=(VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"));
        }
        if(selectionChanged){assertTrue(users.isEmpty());assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("Fixed reference editions changed")));return;}
        assertEquals(1,users.size());String prompt=users.get(0);assertTrue(prompt.contains(tender.getContent()));
        if(general){assertTrue(standardQueries.isEmpty());assertTrue(counterpartProjectQueries.isEmpty());assertTrue(prompt.contains("Assigned review direction: GENERAL-"));return;}
        assertTrue(standardQueries.contains(fullRequiredManifest?"GCC 1.1":"GCC 5.7"));assertTrue(counterpartProjectQueries.contains(fullRequiredManifest?"GCC 1.1":"GCC 5.7"));
        assertFalse(prompt.contains("Standard references have not yet been searched"));
        if(fullProjectContext){
            System.out.println("AUTHORED_FULL_CONTEXT requiredManifest="+fullRequiredManifest+" namedCounterpartAdmitted="+(prompt.contains(projectGcc.getContent())?1:0)+" budgetChars=10000");
            assertTrue(prompt.contains(projectGcc.getContent()),"The actual named-reference caller must retain its ranked counterpart before bulk project context");
            assertTrue(prompt.contains(secondTender.getContent()));assertFalse(prompt.contains(unrelated.getContent()));assertFalse(prompt.contains(counterpartContext.getContent()));
            if(fullRequiredManifest){assertTrue(prompt.contains("Assigned review direction: CHECKLIST-21"));assertTrue(prompt.contains(manifestBody.getContent()));assertTrue(prompt.contains("package_manifest"));}
            assertTrue(result.getTopicAudits().stream().flatMap(t->t.getPacketAudits().stream()).allMatch(p->p.getSubmittedChars()<=10000));return;
        }
        if(available){assertTrue(prompt.contains((projectCopy?projectGcc:gcc).getContent()));if(projectCopy)assertFalse(prompt.contains(gcc.getContent()));assertFalse(prompt.contains(unrelated.getContent()));assertFalse(prompt.contains("Requested standard reference text was not supplied"));assertTrue(prompt.contains("retrieval alone does not establish a matching clause, project adoption or completeness"));}
        else{assertFalse(prompt.contains(gcc.getContent()));assertTrue(prompt.contains("Requested standard reference text was not supplied"));assertTrue(prompt.contains("remain pending"));}
        assertTrue(result.getTopicAudits().stream().flatMap(t->t.getPacketAudits().stream()).allMatch(p->p.getSubmittedChars()<=props.getVetting().getSemanticContextChars()));
        assertTrue(result.getCandidates().isEmpty(),"Retrieval alone must not create a finding");verify(fixed,never()).search(anyString(),anyString(),anyInt());
    }
    @Test void actualDirectionCallerUsesOneNamedNeedForTargetedFollowupAndKeepsTheRestPending()throws Exception{ exerciseTargetedFollowup(false,0); }
    @Test void namedStandardNeedRetrievesSelectedStandardAfterTheFirstReview()throws Exception{ exerciseTargetedFollowup(true,0); }
    @Test void actualPendingFactNamesPssAndSlAndRetainsItsChecklistLineage()throws Exception{exerciseTargetedFollowup(true,0,false,55,true);}
    @Test void slowInitialDirectionsAllowOneEarlyNeedAndRetainEveryInitialAssignment()throws Exception{exerciseTargetedFollowup(false,50);}
    @Test void deadlineLeavesUnsubmittedDirectionsAndTheSecondQueuedNeedExplicitlyPending()throws Exception{exerciseTargetedFollowup(false,60,true,24);}
    @Test void actualCallerDoesNotDiscardASecondDistinctNamedEvidenceNeed()throws Exception{exerciseTargetedFollowup(false,0,true);}
    @Test void defaultFiftyFiveMinuteScheduleAllowsDirectionsBeyondTheFormerDeadline()throws Exception{exerciseTargetedFollowup(false,60,false,55);}
    @Test void realisticTwentySevenNonemptyDirectionsCountRetrievalNamedLookupAndModelTime()throws Exception{exerciseTargetedFollowup(false,50,false,55,false,45,27,true);}
    @Test void realisticSlowerModelsRetainAllAssignmentsAndTheUnprocessedNeedAtTheDeadline()throws Exception{exerciseTargetedFollowup(false,50,false,55,false,80,27,true);}
    @Test void queuedNeedSummariesCountAttemptsWithoutLoggingTheNeedText()throws Exception{
        ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(VettingReviewProbe.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try{exerciseTargetedFollowup(false,0);List<String> summaries=new ArrayList<String>();for(ch.qos.logback.classic.spi.ILoggingEvent event:logs.list)
            if(event.getFormattedMessage().contains("stage=evidence_need_queue"))summaries.add(event.getFormattedMessage());
            assertTrue(summaries.stream().anyMatch(s->s.contains("status=queued")&&s.contains("queued=1")&&s.contains("processed=0")&&s.contains("pending=1")));
            assertTrue(summaries.stream().anyMatch(s->s.contains("status=processed")&&s.contains("queued=1")&&s.contains("processed=1")&&s.contains("pending=0")));
            assertTrue(summaries.stream().anyMatch(s->s.contains("status=terminal")&&s.contains("queued=1")&&s.contains("processed=1")&&s.contains("pending=0")));
            for(String summary:summaries){assertTrue(summary.length()<600);assertFalse(summary.contains("matching tender notice"));assertFalse(summary.contains("submission method"));}
        }finally{logger.detachAppender(logs);logs.stop();}
    }
    @Test void configuredSchedulingWindowIsPositiveAndBoundedWithoutChangingRequestBudgets(){
        ConsenseProperties props=new ConsenseProperties();assertEquals(55,props.getVetting().getSemanticRunMinutes());
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toNanos(55),VettingSemanticReview.directionRunNanos(props.getVetting().getSemanticRunMinutes()));
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toNanos(1),VettingSemanticReview.directionRunNanos(0));
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toNanos(1),VettingSemanticReview.directionRunNanos(-5));
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toNanos(120),VettingSemanticReview.directionRunNanos(Integer.MAX_VALUE));
    }
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds)throws Exception{exerciseTargetedFollowup(standardNeed,initialSeconds,false);}
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds,boolean multipleNeeds)throws Exception{exerciseTargetedFollowup(standardNeed,initialSeconds,multipleNeeds,initialSeconds==0?55:24);}
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds,boolean multipleNeeds,int runMinutes)throws Exception{
        exerciseTargetedFollowup(standardNeed,initialSeconds,multipleNeeds,runMinutes,false);
    }
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds,boolean multipleNeeds,int runMinutes,boolean pendingOnly)throws Exception{
        exerciseTargetedFollowup(standardNeed,initialSeconds,multipleNeeds,runMinutes,pendingOnly,0,0,false);
    }
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds,boolean multipleNeeds,int runMinutes,boolean pendingOnly,int modelSeconds,int fixedSeconds,boolean allNonempty)throws Exception{
        exerciseTargetedFollowup(standardNeed,initialSeconds,multipleNeeds,runMinutes,pendingOnly,modelSeconds,fixedSeconds,allNonempty,false);
    }
    private void exerciseTargetedFollowup(boolean standardNeed,long initialSeconds,boolean multipleNeeds,int runMinutes,boolean pendingOnly,int modelSeconds,int fixedSeconds,boolean allNonempty,boolean requiredList)throws Exception{
        ConsenseProperties props=new ConsenseProperties();props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_TEST_CREDENTIAL");
        assertEquals(55,props.getVetting().getSemanticRunMinutes());if(runMinutes!=55)props.getVetting().setSemanticRunMinutes(runMinutes);
        HttpSupport http=mock(HttpSupport.class);QwenGenerationLedger ledger=mock(QwenGenerationLedger.class);QwenChatUsagePreflight usage=new QwenChatUsagePreflight(props,ledger);
        java.util.concurrent.atomic.AtomicLong nanos=new java.util.concurrent.atomic.AtomicLong();AtomicInteger needAfterInitials=new AtomicInteger(-1);
        List<Long> queryStarts=new ArrayList<Long>(),providerStarts=new ArrayList<Long>();
        Chunk a=chunk("a","The Tenderer shall use the submission method specified in the matching tender notice."),b=chunk("b","The matching tender notice requires electronic submission through the named tender portal.");
        String need=pendingOnly?"Whether the PSS or SL further delineates GS CEW applicability by Works part":standardNeed?"Need SL clause 3 to compare the quoted submission requirement.":"Need the matching tender notice submission method for the quoted tender requirement.";
        String otherNeed="Need project Appendix B to compare the stated tender submission requirement.";
        if(standardNeed)b.setRole("standard");
        ObjectNode standardPayload=JsonUtils.mapper().valueToTree(b);standardPayload.putObject("sourceQuality").put("family","SL");
        FixedReferenceEvidence standardEvidence=new FixedReferenceEvidence(b.getId(),0.9,standardPayload,null,null);com.consense.vector.VectorStore.SearchHit standardHit=standardEvidence.asHit();
        AtomicInteger generations=new AtomicInteger();List<String> users=new ArrayList<>();
        when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
            JsonNode request=JsonUtils.parse(call.getArgument(1));boolean preflight=request.path("max_tokens").asInt()==1;
            if(allNonempty){providerStarts.add(nanos.get());nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(preflight?1:modelSeconds-1));}
            ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion");envelope.put("model","qwen3.8-27b");
            ObjectNode choice=envelope.putArray("choices").addObject();choice.put("index",0);choice.put("finish_reason",preflight?"length":"stop");
            String output="{";if(!preflight){int turn=generations.getAndIncrement();users.add(request.path("messages").get(1).path("content").asText());
                Map<String,Object> record=new LinkedHashMap<>();VettingCompetitionFixture.details(record);record.put("assessment",turn==0?"issue":"consistent");record.put("type",turn==0?"conflict":"requirement");record.put("severity","low");
                record.put("title","Authored source assessment");record.put("comment",turn==0?(multipleNeeds?otherNeed:"More evidence is required for this assessment."):"The supplied affected text and named counterpart state the submission method.");record.put("impact","Review only");record.put("suggestion","Retain the source-supported result");
                if(turn==0){record.put("pendingFacts",pendingOnly?Arrays.asList("Whether the project has multiple Work Sections where GS CEW applies only to some",need):Collections.singletonList(need));record.put("suggestedAction","review");Map<String,Object> check=new LinkedHashMap<>();check.put("itemId","APP-I-001");check.put("status","insufficient_evidence");check.put("reason",pendingOnly?"SP/1 adopts GS CEW but does not state which parts of the Works are subject to it.":need);check.put("support",null);record.put("checklistAssessments",Collections.singletonList(check));}
                record.put("evidence",Collections.singletonList(new LinkedHashMap<String,Object>(){{put("chunkId","R1");put("side","source");put("quote",a.getContent());}}));
                output="{\"records\":"+JsonUtils.write(Collections.singletonList(record))+"}";
            }
            choice.putObject("message").put("role","assistant").put("content",output);envelope.putObject("usage").put("prompt_tokens",100).put("completion_tokens",1);return JsonUtils.write(envelope);
        });
        LlmClient local=mock(LlmClient.class);LlmProfiles profiles=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local);
        VettingSemanticReview review=new VettingSemanticReview(new AiGateway(local,profiles),props,mock(VettingRetrievalClient.class));review.setQwenUsagePreflight(usage);
        FixedBaselineRetrievalClient fixed=mock(FixedBaselineRetrievalClient.class);List<String> standardQueries=new ArrayList<>();AtomicInteger prematureStandardSearches=new AtomicInteger();
        when(fixed.enabled()).thenReturn(true);when(fixed.projectSelection("authored")).thenReturn(JsonUtils.mapper().createObjectNode());
        when(fixed.searchForProject(eq("authored"),eq("vetting"),anyString(),anyInt())).thenAnswer(call->{
            if(generations.get()==0)prematureStandardSearches.incrementAndGet();String query=call.getArgument(2);standardQueries.add(query);if(allNonempty)queryStarts.add(nanos.get());nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(fixedSeconds));return standardNeed&&need.equals(query)?Collections.singletonList(standardHit):Collections.emptyList();});
        when(fixed.evidence(standardHit)).thenReturn(standardEvidence);
        review.setFixedBaselineRetrievalClient(fixed);
        List<int[]> progress=new ArrayList<>();java.lang.reflect.Field progressField=VettingSemanticReview.class.getDeclaredField("activeProgress");progressField.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<VettingSemanticReview.ReviewProgress> progressScope=(ThreadLocal<VettingSemanticReview.ReviewProgress>)progressField.get(review);
        progressScope.set((completed,total,message)->progress.add(new int[]{completed,total}));
        OerBusinessEvidence oer=mock(OerBusinessEvidence.class);review.setOerBusinessEvidence(oer);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);
        OerBusinessEvidence.Session session=ctor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        Chunk manifest=chunk("required-list","Reference drawings are for reference only and do not form part of the Contract. ".repeat(12));manifest.setRole("package_manifest");manifest.setFileKey("APU");
        Chunk manifestHeading=chunk("required-list-heading","APU.A010.P");manifestHeading.setRole("package_manifest");manifestHeading.setFileKey("APU");
        Chunk bulk=chunk("bulk-context","Unrelated context. ".repeat(500));
        when(oer.retrieveRequiredProjectText(eq(session),eq("APU.A010"),any(),anyInt())).thenReturn(new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(manifestHeading,manifest),Collections.singleton(manifestHeading.getId())));
        List<Chunk> corpus=corpus(session,requiredList?Arrays.asList(a,b,manifestHeading,manifest,bulk):Arrays.asList(a,b));AtomicInteger initial=new AtomicInteger();List<String> queries=new ArrayList<>();
        when(oer.retrieveReviewText(eq(session),anyString(),nullable(String.class),any(),anyInt())).thenAnswer(call->{String query=call.getArgument(1);queries.add(query);if(allNonempty)queryStarts.add(nanos.get());
            if(need.equals(query)||otherNeed.equals(query)){assertNull(call.getArgument(2));needAfterInitials.compareAndSet(-1,initial.get());if(allNonempty)nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(initialSeconds));return standardNeed?Collections.emptyList():Collections.singletonList(b);}
            if(call.getArgument(2)==null){if(allNonempty)nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(initialSeconds));return Collections.emptyList();}
            nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(initialSeconds));int ordinal=initial.getAndIncrement();if(requiredList)return new OerBusinessEvidence.ReviewTextChunks(Arrays.asList(a,b,bulk),new LinkedHashSet<>(Arrays.asList(a.getId(),b.getId())));return allNonempty||ordinal<2?Collections.singletonList(a):Collections.emptyList();});
        java.lang.reflect.Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class,java.util.function.LongSupplier.class);run.setAccessible(true);
        LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation operation=review.captureOperation(chat);VettingSemanticReview.Result result;
        try(LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope bound=review.bindOperation(operation);QwenChatUsagePreflight.Operation.ActionScope action=operation.getQwen().openAction("AUTHORED directions",()->{})){
            result=(VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"),(java.util.function.LongSupplier)nanos::get);
        }
        if(requiredList){
            String prompt=users.stream().filter(user->user.contains("Assigned review direction: CHECKLIST-21")).findFirst().orElseThrow();
            assertTrue(prompt.contains(manifest.getContent()),"The actual assigned-list review must receive the required source text");
            assertTrue(prompt.contains("package_manifest"));assertTrue(prompt.contains(a.getContent()));
            assertTrue(prompt.contains(b.getContent()),"Every actual ranked tender seed still fits");assertFalse(prompt.contains(bulk.getContent()),"Required list context must precede bulk unrelated project context");
            verify(oer).retrieveRequiredProjectText(eq(session),eq("APU.A010"),any(),eq(props.getVetting().getSemanticContextChars()));
            assertEquals("package_manifest",manifest.getRole());assertTrue(result.getCandidates().isEmpty());progressScope.remove();return;
        }
        if(allNonempty){
            assertEquals(27,result.getTopicAudits().size(),"Every checklist/general direction retains its independent audit");
            int started=(int)result.getTopicAudits().stream().filter(t->!t.getPacketAudits().isEmpty()).count();
            System.out.println("AUTHORED_DIRECTION_SCHEDULE modelSeconds="+modelSeconds+" initialRagSeconds="+initialSeconds+" fixedLookupSeconds="+fixedSeconds+" elapsedSeconds="+java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(nanos.get())+" initialCalls="+started+" firstNeedAfterInitials="+needAfterInitials.get()+" generations="+generations.get());
            assertEquals(3,needAfterInitials.get(),"One concrete requested round is allowed after the first three initial directions");
            for(Long startedAt:queryStarts)assertTrue(startedAt<java.util.concurrent.TimeUnit.MINUTES.toNanos(55),"No new retrieval starts after the scheduling deadline");
            for(Long startedAt:providerStarts)assertTrue(startedAt<java.util.concurrent.TimeUnit.MINUTES.toNanos(55),"No new provider reservation starts after the scheduling deadline in this scenario");
            if(modelSeconds==45){assertEquals(27,initial.get());assertEquals(27,started);assertEquals(28,generations.get());assertTrue(nanos.get()<java.util.concurrent.TimeUnit.MINUTES.toNanos(55));}
            else{assertEquals(22,started);assertEquals(23,generations.get(),"One supplement consumes time formerly available for one additional initial direction");
                assertEquals(5,result.getTopicAudits().stream().filter(t->t.getPacketAudits().isEmpty()).count());
                assertTrue(result.getTopicAudits().stream().filter(t->t.getPacketAudits().isEmpty()).allMatch(t->"not_submitted".equals(t.getStatus())&&"not_completed".equals(t.getCheckState())&&t.getError().contains("budget")));}
            progressScope.remove();return;
        }
        if(initialSeconds>=60&&runMinutes==24){
            assertEquals(24,initial.get());assertEquals(3,generations.get());assertTrue(queries.contains(need));assertFalse(queries.contains(otherNeed));
            assertEquals(3,result.getTopicAudits().stream().filter(t->t.getError()!=null&&t.getError().contains("Run evidence/time budget")).count());
            assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("1 requested evidence follow-ups remain pending")));
            progressScope.remove();return;
        }
        assertEquals(multipleNeeds?4:3,generations.get());assertEquals(multipleNeeds?4:3,result.getActualGatewayCallCount());assertEquals(!standardNeed||pendingOnly,queries.contains(need));
        assertEquals(0,prematureStandardSearches.get(),"The first pass must review project text before searching standards");
        VettingReviewDirections.Direction longDirection=VettingReviewDirections.plan(new String[0]).stream().filter(d->"CHECKLIST-2".equals(d.id)).findFirst().orElseThrow();
        List<String> knownReferences=VettingSemanticReview.initialReferenceQueries(longDirection);
        List<String> expectedStandards=new ArrayList<>(knownReferences);if(standardNeed)expectedStandards.add(need);
        assertEquals(expectedStandards,standardQueries,"Known checklist counterparts precede only the additional model-requested source family");
        List<String> longQueries=VettingReviewDirections.searchQueries(longDirection.query);
        assertTrue(longQueries.size()>1);assertFalse(queries.containsAll(longQueries),"Initial retrieval must use a concise direction query, retaining the complete checklist only in model instructions");
        assertEquals(result.getTopicAudits().size(),initial.get(),"Each direction receives one initial retrieval, before any model-requested evidence search");
        if(!standardNeed)assertEquals(3+knownReferences.size(),queries.indexOf(need),"The first concrete supplement must not wait behind every initial direction");
        assertTrue(result.getCandidates().isEmpty(),"An unconfirmed conflict need cannot become a published conflict");
        assertTrue(users.get(0).contains("APP-I-001"));assertFalse(users.get(0).contains("APP-I-002"));assertTrue(users.get(0).contains(VettingChecklistCatalog.items().get(0).getDescription()));
        assertTrue(users.get(0).contains("Standard references have not yet been searched"));
        assertTrue(users.get(1).contains("Assigned review direction: CHECKLIST-2"),"Other directions receive their first review before requested follow-ups");
        assertFalse(users.get(1).contains("Specific earlier evidence need"));
        assertTrue(users.get(2).contains(need));assertTrue(users.get(2).contains(a.getContent()));assertTrue(users.get(2).contains(b.getContent()));
        assertTrue(result.getTopicAudits().size()>2);assertEquals(multipleNeeds?3:2,result.getTopicAudits().get(0).getPacketAudits().size());
        com.consense.web.dto.VettingDtos.SemanticPacketVO first=result.getTopicAudits().get(0).getPacketAudits().get(0),child=result.getTopicAudits().get(0).getPacketAudits().get(1);
        assertEquals(multipleNeeds?2:1,first.getEvidenceNeeds().size());assertEquals(first.getPacketId(),child.getParentPacketId());
        assertEquals(first.getEvidenceNeeds().get(0).getNeedId(),child.getRequestedEvidenceNeedId());
        assertEquals(Collections.singletonList("APP-I-001"),first.getEvidenceNeeds().get(0).getChecklistItemIds());
        assertEquals(Collections.singletonList("a"),first.getEvidenceNeeds().get(0).getAffectedChunkIds());
        assertEquals(Collections.singletonList(0),first.getEvidenceNeeds().get(0).getVerifiedAssessmentIndexes(),"A precise checklist need must retain its enclosing assessment even when its comment is vague");
        assertEquals(Integer.valueOf(0),first.getChecklistAssessments().get(0).getVerifiedAssessmentIndex());
        if(multipleNeeds){
            assertEquals(7+knownReferences.size(),queries.indexOf(otherNeed),"The second named need is searched after the next three initial directions");
            assertEquals(first.getEvidenceNeeds().get(1).getNeedId(),result.getTopicAudits().get(0).getPacketAudits().get(2).getRequestedEvidenceNeedId());
            assertEquals(Collections.singletonList(0),first.getEvidenceNeeds().get(1).getVerifiedAssessmentIndexes());
        }
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("without a verified assessment remain pending")));
        assertTrue(result.getWarnings().stream().anyMatch(w->w.contains("treated as insufficient context")));
        assertFalse(progress.isEmpty());int previous=0;for(int[] step:progress){assertEquals(result.getTopicAudits().size(),step[1]);assertTrue(step[0]>=previous,"A newly requested packet cannot move direction progress backwards");previous=step[0];}
        progressScope.remove();
    }
    @Test void unavailableRequiredHybridCannotCompleteAnyDirectionOrSilentlyUseOrdinaryRetrieval()throws Exception{
        ConsenseProperties props=new ConsenseProperties();props.getVetting().setRequireHybridRetrieval(true);
        AiGateway ai=mock(AiGateway.class);VettingRetrievalClient ordinary=mock(VettingRetrievalClient.class);OerBusinessEvidence oer=mock(OerBusinessEvidence.class);
        VettingSemanticReview review=new VettingSemanticReview(ai,props,ordinary);review.setOerBusinessEvidence(oer);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);
        OerBusinessEvidence.Session session=ctor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        List<Chunk> corpus=corpus(session,Collections.singletonList(chunk("a","The Tenderer shall retain the signed tender submission.")));
        when(oer.retrieveReviewText(eq(session),anyString(),eq("tender"),any(),anyInt())).thenThrow(new IllegalStateException("AUTHORED required hybrid index unavailable"));
        java.lang.reflect.Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);run.setAccessible(true);
        VettingSemanticReview.Result result=(VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"));
        assertFalse(result.getTopicAudits().isEmpty());assertEquals(0,result.getActualGatewayCallCount());assertTrue(result.getReviewedChunkIds().isEmpty());
        assertTrue(result.getTopicAudits().stream().allMatch(t->"not_completed".equals(t.getCheckState())&&t.getError().contains("pending")));
        verify(oer,times(result.getTopicAudits().size())).retrieveReviewText(eq(session),anyString(),eq("tender"),any(),anyInt());verifyNoInteractions(ordinary);
    }
    @Test void sourceGuardRevocationDuringHybridRetrievalAbortsInsteadOfBecomingDirectionInsufficiency()throws Exception{
        ConsenseProperties props=new ConsenseProperties();AiGateway ai=mock(AiGateway.class);VettingRetrievalClient ordinary=mock(VettingRetrievalClient.class);OerBusinessEvidence oer=mock(OerBusinessEvidence.class);
        VettingSemanticReview review=new VettingSemanticReview(ai,props,ordinary);review.setOerBusinessEvidence(oer);
        java.lang.reflect.Constructor<OerBusinessEvidence.Session> ctor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);ctor.setAccessible(true);
        OerBusinessEvidence.Session session=ctor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
        List<Chunk> corpus=corpus(session,Collections.singletonList(chunk("a","The Tenderer shall retain the signed tender submission.")));
        java.util.concurrent.atomic.AtomicBoolean revoked=new java.util.concurrent.atomic.AtomicBoolean();Runnable sourceGuard=()->{if(revoked.get())throw new IllegalStateException("AUTHORED selected source revision changed");};
        when(oer.retrieveReviewText(eq(session),anyString(),eq("tender"),any(),anyInt())).thenAnswer(call->{revoked.set(true);call.<Runnable>getArgument(3).run();return Collections.emptyList();});
        java.lang.reflect.Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class);run.setAccessible(true);
        VettingSemanticReview.Result result=new VettingSemanticReview.Result();java.lang.reflect.InvocationTargetException failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->run.invoke(review,"authored",result,corpus,corpus,"en",null,sourceGuard,VettingReviewProbe.start(props,null,"authored")));
        assertTrue(failure.getCause().getMessage().contains("guard failed"));assertTrue(failure.getCause().getCause().getMessage().contains("revision changed"));
        assertEquals(1,result.getTopicAudits().size());assertTrue(result.getReviewedChunkIds().isEmpty());assertEquals(0,result.getActualGatewayCallCount());
        verify(oer,times(1)).retrieveReviewText(eq(session),anyString(),eq("tender"),any(),anyInt());verifyNoInteractions(ordinary);
    }
    @Test void checklistEvidenceNeedRequiresAssignedVerifiedAffectedTextAndANamedGap(){
        Chunk source=chunk("a","The Tenderer shall follow the separately supplied submission procedure.");
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();
        com.consense.web.dto.VettingDtos.ChecklistAssessmentVO item=new com.consense.web.dto.VettingDtos.ChecklistAssessmentVO();item.setItemId("APP-I-001");item.setStatus("insufficient_evidence");item.setReason("Retrieve SCT5(4) to compare the stated tender submission procedure.");
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(source,"source",source.getContent());assertTrue(quote.isLocated());item.setEvidence(Collections.singletonList(quote));item.setEvidenceChunkIds(Collections.singletonList(source.getId()));packet.getChecklistAssessments().add(item);
        List<VettingSemanticReview.EvidenceNeed> needs=VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-001"),packet,Collections.singletonList(source));
        assertEquals(1,needs.size());assertEquals(item.getReason(),needs.get(0).query);assertEquals(Collections.singletonList(source),needs.get(0).affected);
        assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-011"),packet,Collections.singletonList(source)).isEmpty(),"Another direction's row cannot trigger a query");
        item.setReason("More evidence is required for this assessment.");assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-001"),packet,Collections.singletonList(source)).isEmpty());
        item.setReason("Retrieve SCT5(4) to compare the stated tender submission procedure.");quote.setLocated(false);assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-001"),packet,Collections.singletonList(source)).isEmpty());
    }
    @Test void distinctNamedNeedsAreRetainedAndDuplicateNeedsMergeOnlyTheirVerifiedAffectedRows(){
        Chunk a=chunk("a","The first affected tender passage."),b=chunk("b","The second affected tender passage.");
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();packet.setPacketId("authored-parent");
        String first="Retrieve SCT5(4) to compare the stated tender submission procedure.",second="Need project Appendix B to compare the stated tender requirement.";
        for(int index=0;index<3;index++){
            Chunk source=index==2?b:a;
            com.consense.web.dto.VettingDtos.ChecklistAssessmentVO row=new com.consense.web.dto.VettingDtos.ChecklistAssessmentVO();
            row.setItemId(index==2?"APP-I-002":"APP-I-001");row.setStatus("insufficient_evidence");row.setReason(index==1?second:first);
            row.setEvidence(Collections.singletonList(VettingCorpus.evidence(source,"source",source.getContent())));row.setEvidenceChunkIds(Collections.singletonList(source.getId()));packet.getChecklistAssessments().add(row);
        }
        packet.getVerifiedAssessments().add(new com.consense.web.dto.VettingDtos.VerifiedAssessmentVO("insufficient_context","Named gap",first,Collections.singletonList(VettingCorpus.evidence(a,"source",a.getContent()))));
        List<VettingSemanticReview.EvidenceNeed> needs=VettingSemanticReview.directionEvidenceNeeds(Arrays.asList("APP-I-001","APP-I-002"),packet,Arrays.asList(a,b));
        assertEquals(2,needs.size());assertEquals(Arrays.asList(a,b),needs.get(0).affected);assertEquals(Arrays.asList("APP-I-001","APP-I-002"),needs.get(0).itemIds);
        assertEquals(Collections.singletonList(0),needs.get(0).assessmentIndexes);assertEquals(Collections.singletonList("APP-I-001"),needs.get(1).itemIds);
        com.consense.web.dto.VettingDtos.SemanticEvidenceNeedVO lineage=VettingSemanticReview.evidenceNeedLineage(packet,needs.get(0));
        assertEquals(Arrays.asList("a","b"),lineage.getAffectedChunkIds());assertEquals(Collections.singletonList(0),lineage.getVerifiedAssessmentIndexes());
        assertEquals(lineage.getNeedId(),VettingSemanticReview.evidenceNeedLineage(packet,needs.get(0)).getNeedId());
        assertNotEquals(lineage.getNeedId(),VettingSemanticReview.evidenceNeedLineage(packet,needs.get(1)).getNeedId());
        packet.setPacketId("different-parent");assertNotEquals(lineage.getNeedId(),VettingSemanticReview.evidenceNeedLineage(packet,needs.get(0)).getNeedId());
    }
    @Test void pendingFactsAddTheNamedPssAndSlNeedWithoutReplacingTheExistingBroadComment(){
        Chunk source=chunk("a","The Specification applies to the whole of the Works.");
        String fact="Whether the PSS or SL further delineates GS CEW applicability by Works part";
        String comment="The PRE base tender states the Specification applies to the whole of the Works. The SP/1 page does not delineate which parts are subject to GS CEW as required by the checklist.";
        com.consense.web.dto.VettingDtos.SemanticPacketVO packet=new com.consense.web.dto.VettingDtos.SemanticPacketVO();packet.setPacketId("pending-parent");
        com.consense.web.dto.VettingDtos.FindingEvidence quote=VettingCorpus.evidence(source,"source",source.getContent());
        com.consense.web.dto.VettingDtos.VerifiedAssessmentVO assessment=new com.consense.web.dto.VettingDtos.VerifiedAssessmentVO("insufficient_context","GS CEW scope",comment,Collections.singletonList(quote),Arrays.asList("Whether the project has multiple Work Sections",fact));packet.getVerifiedAssessments().add(assessment);
        com.consense.web.dto.VettingDtos.ChecklistAssessmentVO row=new com.consense.web.dto.VettingDtos.ChecklistAssessmentVO();row.setItemId("APP-I-001");row.setStatus("insufficient_evidence");row.setReason("SP/1 does not delineate the applicable Works parts.");row.setVerifiedAssessmentIndex(0);row.setEvidence(Collections.singletonList(quote));row.setEvidenceChunkIds(Collections.singletonList(source.getId()));packet.getChecklistAssessments().add(row);
        List<VettingSemanticReview.EvidenceNeed> needs=VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-001"),packet,Collections.singletonList(source));
        assertEquals(Arrays.asList(fact,comment),needs.stream().map(need->need.query).collect(java.util.stream.Collectors.toList()),"The explicit missing source must be searched before the broad fallback comment");
        assertEquals(Collections.singletonList("APP-I-001"),needs.get(0).itemIds);assertEquals(Collections.singletonList(0),needs.get(0).assessmentIndexes);assertEquals(Collections.singletonList(source),needs.get(0).affected);
        assertNotEquals(VettingSemanticReview.evidenceNeedLineage(packet,needs.get(0)).getNeedId(),VettingSemanticReview.evidenceNeedLineage(packet,needs.get(1)).getNeedId());
        assessment.setComment("Further evidence is needed.");assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-002"),packet,Collections.singletonList(source)).isEmpty());
        quote.setLocated(false);assertTrue(VettingSemanticReview.directionEvidenceNeeds(Collections.singletonList("APP-I-001"),packet,Collections.singletonList(source)).isEmpty());
    }
    @Test void pendingFactsSurviveSavedAssessmentRoundTripAndOldAssessmentsRemainReadable()throws Exception{
        com.consense.web.dto.VettingDtos.VerifiedAssessmentVO old=JsonUtils.mapper().readValue("{\"assessment\":\"insufficient_context\",\"title\":\"Pending\",\"comment\":\"More evidence is needed\",\"evidence\":[]}",com.consense.web.dto.VettingDtos.VerifiedAssessmentVO.class);
        assertTrue(old.getPendingFacts().isEmpty());old.setPendingFacts(Collections.singletonList("Whether PSS sets the applicable Works scope"));
        com.consense.web.dto.VettingDtos.VerifiedAssessmentVO restored=JsonUtils.mapper().readValue(JsonUtils.write(old),com.consense.web.dto.VettingDtos.VerifiedAssessmentVO.class);
        assertEquals(old.getPendingFacts(),restored.getPendingFacts());
    }
    @Test void broadGeneralChecklistQueryRetainsEveryKeySubjectWithoutImportingAnExampleAnswer(){
        VettingReviewDirections.Direction direction=VettingReviewDirections.plan(new String[0]).get(1);String query=direction.initialQuery();
        for(String subject:Arrays.asList("SL guidance","optional clause",".P suffix","DCMBI","Technical Feedback","GCC 1.1","capitalization","GCC 8.1","extension of time","cost claims","CM instructions","optional works","timing or location","appendix","headings","page headers"))assertTrue(query.contains(subject),subject);
        assertTrue(query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<1200);assertEquals(7,direction.items.size());
    }
    @Test void slUnavailableNoHitsAndBudgetOmissionsStayExplicitWithoutSuppressingIndependentText(){
        Chunk tender=chunk("a","The tender passage retains its own independent obligation."),standard=chunk("sl","The selected standard describes the related obligation.");standard.setRole("standard");
        assertTrue(VettingSemanticReview.standardEvidenceNote(false,Collections.emptyList(),Collections.singletonList(tender)).contains("unavailable"));
        assertTrue(VettingSemanticReview.standardEvidenceNote(true,Collections.emptyList(),Collections.singletonList(tender)).contains("no reference text"));
        String omitted=VettingSemanticReview.standardEvidenceNote(true,Collections.singletonList(standard),Collections.singletonList(tender));assertTrue(omitted.contains("text budget"));assertTrue(omitted.contains("Standard-dependent checks remain pending"));assertTrue(omitted.contains("independent project-text findings may continue"));
        assertEquals("",VettingSemanticReview.standardEvidenceNote(true,Collections.singletonList(standard),Arrays.asList(tender,standard)));
    }
    @Test void gccGctOrUnknownReferenceFamiliesCannotCountAsSuppliedSl(){
        for(String family:Arrays.asList("GCC","GCT","unknown","")){
            FixedVettingReferences references=referencesWithFamily(family);
            assertEquals(1,references.chunks().size());assertTrue(references.slChunks().isEmpty());
            assertTrue(VettingSemanticReview.standardEvidenceNote(true,references.slChunks(),references.chunks()).contains("selected SL editions returned no reference text"));
        }
        FixedVettingReferences sl=referencesWithFamily("SL");assertEquals(1,sl.slChunks().size());
        assertEquals("",VettingSemanticReview.standardEvidenceNote(true,sl.slChunks(),sl.chunks()));
    }
    @Test void fixedReferencesMergeSeveralQueriesWithoutDuplicatingTheSamePassage(){
        Chunk first=chunk("fixed-a","First relevant standard text."),second=chunk("fixed-b","Second relevant standard text.");first.setRole("standard");second.setRole("standard");
        FixedReferenceEvidence a=new FixedReferenceEvidence(first.getId(),0.9,JsonUtils.mapper().valueToTree(first),null,null);
        FixedReferenceEvidence b=new FixedReferenceEvidence(second.getId(),0.8,JsonUtils.mapper().valueToTree(second),null,null);
        com.consense.vector.VectorStore.SearchHit hitA=a.asHit(),hitB=b.asHit();FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);
        when(client.enabled()).thenReturn(true);when(client.evidence(hitA)).thenReturn(a);when(client.evidence(hitB)).thenReturn(b);
        when(client.searchForProject("authored","vetting","first query",1)).thenReturn(Collections.singletonList(hitA));
        when(client.searchForProject("authored","vetting","second query",1)).thenReturn(Arrays.asList(hitB,hitA));
        FixedVettingReferences combined=FixedVettingReferences.retrieve(client,"authored",Arrays.asList("first query","second query"),1);
        assertEquals(Arrays.asList("fixed-a","fixed-b"),combined.chunks().stream().map(Chunk::getId).collect(java.util.stream.Collectors.toList()));
    }
    private static FixedVettingReferences referencesWithFamily(String family){
        Chunk reference=chunk("fixed-reference","The fixed reference preserves this original standard wording.");reference.setRole("standard");
        ObjectNode raw=JsonUtils.mapper().valueToTree(reference);raw.putObject("sourceQuality").put("family",family);
        FixedReferenceEvidence evidence=new FixedReferenceEvidence(reference.getId(),0.9,raw,null,null);com.consense.vector.VectorStore.SearchHit hit=evidence.asHit();
        FixedBaselineRetrievalClient client=mock(FixedBaselineRetrievalClient.class);when(client.enabled()).thenReturn(true);
        when(client.searchForProject(anyString(),eq("vetting"),anyString(),anyInt())).thenReturn(Collections.singletonList(hit));when(client.evidence(hit)).thenReturn(evidence);
        return FixedVettingReferences.retrieve(client,"authored","related reference",1);
    }
    @SuppressWarnings("unchecked") private static List<Chunk> corpus(OerBusinessEvidence.Session session,List<Chunk> sources)throws Exception{
        Class<?> type=Class.forName(OerBusinessEvidence.class.getName()+"$Corpus");java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructor(OerBusinessEvidence.Session.class,List.class);ctor.setAccessible(true);
        return (List<Chunk>)ctor.newInstance(session,new ArrayList<>(sources));
    }
    private static Chunk ordered(String id,String documentId,String revision,int order,String text){
        Chunk c=chunk(id,text);c.setDocumentId(documentId);c.setOriginalSourceSha256(VettingCorpus.hash("original-"+revision));
        com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer p=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();
        p.setProjectId("authored");p.setModule("vetting");p.setDocumentId(Long.valueOf(documentId));p.setCanonicalChunkId(id);
        p.setOriginalSourceSha256(c.getOriginalSourceSha256());p.setJavaParsedSourceHash(c.getSourceHash());p.setSourceRevisionSha256(VettingCorpus.hash(revision));
        p.setCanonicalSourceHash(VettingCorpus.hash("canonical-"+revision));p.setProjectionSha256(VettingCorpus.hash("projection-"+revision));
        p.setProjectionRevisionSha256(VettingCorpus.hash("projection-revision-"+revision));p.setSourceOrder(JsonUtils.mapper().getNodeFactory().numberNode(order));c.setCanonicalEvidence(p);return c;
    }
    private static Chunk chunk(String id,String text){Chunk c=VettingContextBudgetTest.chunk(id,"SCT1",text,text.length());c.setDocumentId("1");c.setFileName("Authored tender.docx");return c;}
}
