package com.consense.service.vetting;

import com.consense.ai.*;
import com.consense.common.JsonUtils;
import com.consense.config.ConsenseProperties;
import com.consense.service.llm.*;
import com.consense.service.vetting.VettingCorpus.Chunk;
import com.consense.web.dto.VettingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual direction caller and quote gates with authored transport/retrieval; no provider or business calls. */
class VettingChecklistAttemptCoverageTest {
    @Test void mixedSchemaDecisionsForSameItemAreNotBothVerified()throws Exception{
        Exercise run=new Exercise("schema_same_id"); VettingSemanticReview.Result result=run.execute(); SemanticTopicVO topic=run.target(result);
        SemanticPacketVO first=topic.getPacketAudits().get(0); String id=run.targetIds.get(0);
        assertEquals("completed_with_rejections",first.getStatus()); assertEquals(1,first.getRejectedRecords());
        assertEquals(1,first.getChecklistAssessments().size()); assertEquals(id,first.getChecklistAssessments().get(0).getItemId());
        assertEquals("fulfilled",first.getChecklistAssessments().get(0).getStatus());
        assertEquals(Collections.singletonList(id),first.getUnverifiedChecklistItemIds());
        SemanticPacketVO restored=new SemanticPacketVO();
        VettingPacketCoverage.restore(JsonUtils.read(JsonUtils.write(first),SemanticPacketVO.class),new SemanticTopicVO(),restored);
        assertEquals(Collections.singletonList(id),restored.getUnverifiedChecklistItemIds(),"Checkpoint restoration must preserve the unverified item marker");
        CoverageVO exportedCoverage=new CoverageVO();exportedCoverage.setSemanticTopics(result.getTopicAudits());
        JsonNode exported=org.springframework.test.util.ReflectionTestUtils.invokeMethod(VettingService.class,"reportCoverage",exportedCoverage);
        assertEquals(id,exported.path("semanticTopics").get(topic.getTopicIndex()-1).path("packetAudits").get(0).path("unverifiedChecklistItemIds").get(0).asText());
        assertTrue(result.getWarnings().stream().anyMatch(s->s.contains("structural validation")&&s.contains("unassessed")));
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"The same item has an additional unverified model record");
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.set("semanticTopics",JsonUtils.mapper().valueToTree(result.getTopicAudits()));
        ObjectNode report=JsonUtils.mapper().createObjectNode();report.putObject("promptPolicy").put("sha256",VettingPromptPolicy.sha256());
        com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO row=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(item->id.equals(item.getItemId())).findFirst().orElseThrow();
        System.out.println("AUTHORED_SAME_ID_RESULT item="+id+" status="+row.getStatus()+" reason="+row.getReason()+" warning="+result.getWarnings().stream().filter(s->s.contains("structural validation")).findFirst().orElse("missing"));
        assertEquals(1,row.getAssessments().size());
        assertTrue(row.getReason().contains("failed structural validation"),"The affected row must disclose the unverified assertion");
        ObjectNode savedTopic=(ObjectNode)coverage.path("semanticTopics").get(topic.getTopicIndex()-1);
        ObjectNode savedPacket=(ObjectNode)savedTopic.path("packetAudits").get(0);
        ObjectNode assessment=(ObjectNode)savedPacket.path("checklistAssessments").get(0);
        ObjectNode evidence=(ObjectNode)assessment.path("evidence").get(0);
        String chunk=assessment.path("evidenceChunkIds").get(0).asText();
        ObjectNode member=JsonUtils.mapper().createObjectNode().put("addressKind","canonical_adoption_unit").put("projectId","authored")
            .put("canonicalChunkId",chunk).put("documentId",evidence.path("documentId").asText())
            .put("originalSourceSha256",evidence.path("originalSourceSha256").asText()).put("canonicalSourceHash",VettingCorpus.hash("AUTHORED canonical"))
            .put("javaParsedSourceHash",evidence.path("sourceHash").asText());
        evidence.set("canonicalEvidence",member);
        VettingChecklistCatalog.Item definition=VettingChecklistCatalog.items().stream().filter(item->id.equals(item.getId())).findFirst().orElseThrow();
        ObjectNode ledger=coverage.putObject("checklistDependencyLedger").put("projectId","authored").put("producerVersion",VettingChecklistDependencies.VERSION);
        ObjectNode ledgerRow=ledger.putObject("items").putObject(id).put("status","required_member_scope_closed")
            .put("requirementSha256",VettingChecklistDependencies.requirementHash(definition)).put("basisSha256",VettingCorpus.hash("AUTHORED basis"));
        ledgerRow.putArray("requiredMemberRefs").add(member.deepCopy());
        List<com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO> groups=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report);
        com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO closed=groups.stream().flatMap(g->g.getSubItemAssessments().stream()).filter(item->id.equals(item.getItemId())).findFirst().orElseThrow();
        System.out.println("AUTHORED_SAME_ID_CLOSED status="+closed.getStatus()+" reason="+closed.getReason());
        assertEquals("insufficient_evidence",closed.getStatus(),"A structurally invalid same-item assertion prevents a green automatic conclusion");
        assertTrue(closed.getReason().contains("failed structural validation"));
        assertEquals(1,closed.getAssessments().size(),"The independently accepted record stays available for review");
        assertTrue(VettingReportPresentation.readerChecklist(Collections.emptyList(),groups).stream().flatMap(table->table.getRows().stream()).flatMap(List::stream).anyMatch(value->value.contains("failed structural validation")),"The English report table must carry the item-level warning");
        assessment.put("status","not_fulfilled").put("issueTypeCode","REQ").put("reason","AUTHORED verified issue");
        com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO issue=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(item->id.equals(item.getItemId())).findFirst().orElseThrow();
        assertEquals("not_fulfilled",issue.getStatus(),"An independently verified issue is not hidden by an invalid record");
        assertTrue(issue.getReason().contains("failed structural validation"));assertEquals(1,issue.getAssessments().size());
        com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO issueGroup=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().filter(group->group.getSourceItemIds().contains(id)).findFirst().orElseThrow();
        assertFalse(issueGroup.isComplete(),"A structurally rejected same-item record must prevent a complete group reuse basis");
        assertFalse(VettingChecklistAutomaticResults.completeGroupBases(Collections.singletonList(issueGroup)).containsKey(id),"Unverified assertions must not become a reusable complete group basis");
        savedPacket.putArray("checklistAssessments");
        com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO unknown=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(item->id.equals(item.getItemId())).findFirst().orElseThrow();
        assertEquals("not_assessed",unknown.getStatus());assertTrue(unknown.getReason().contains("Review the unresolved record."));
        assertFalse(unknown.getReason().contains("retained assessment"));assertTrue(unknown.getAssessments().isEmpty());
    }
    @Test void verifiedDirectCoverageRecheckStopsAutomaticPendingButPreservesHumanWarning()throws Exception{
        Exercise run=new Exercise("schema_same_id_resolved"); VettingSemanticReview.Result result=run.execute(); SemanticTopicVO topic=run.target(result);
        String id=run.targetIds.get(0); SemanticPacketVO first=topic.getPacketAudits().get(0),recheck=topic.getPacketAudits().get(1);
        assertEquals("checklist_coverage_followup",recheck.getKind()); assertEquals(first.getPacketId(),recheck.getParentPacketId());
        assertTrue(run.rows(run.coveragePrompts.get(0)).contains(id));
        assertTrue(recheck.getChecklistAssessments().stream().anyMatch(row->id.equals(row.getItemId())&&"fulfilled".equals(row.getStatus())));
        assertFalse(VettingPacketCoverage.unassessedIds(topic).contains(id),"An exact direct verified follow-up should stop automatic rechecking");
        ObjectNode coverage=JsonUtils.mapper().createObjectNode(); coverage.set("semanticTopics",JsonUtils.mapper().valueToTree(result.getTopicAudits()));
        ObjectNode report=JsonUtils.mapper().createObjectNode(); report.putObject("promptPolicy").put("sha256",VettingPromptPolicy.sha256());
        com.consense.web.dto.VettingChecklistDtos.AutomaticItemVO row=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream()).filter(item->id.equals(item.getItemId())).findFirst().orElseThrow();
        assertTrue(row.getReason().contains("failed structural validation"),"Historical rejection must remain visible for human review");
        recheck.getUnverifiedChecklistItemIds().add(id);
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"A follow-up with a further rejected assertion cannot close automatic pending");
        recheck.getUnverifiedChecklistItemIds().clear(); recheck.getChecklistAssessments().get(0).getEvidence().get(0).setLocated(false);
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"Unlocated follow-up evidence cannot close automatic pending");
        recheck.getChecklistAssessments().get(0).getEvidence().get(0).setLocated(true);
        recheck.getChecklistAssessments().get(0).setStatus("insufficient_evidence");
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"An insufficient follow-up cannot close automatic pending");
        recheck.getChecklistAssessments().get(0).setStatus("fulfilled");
        String savedParent=first.getPacketId(); first.setPacketId(null); recheck.setParentPacketId(null);
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"Missing parent identity cannot establish lineage");
        first.setPacketId(savedParent); recheck.setParentPacketId(savedParent);
        recheck.setParentPacketId("unrelated-parent");
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"An unrelated packet cannot clear an invalid record marker");
        recheck.setParentPacketId(first.getPacketId()); recheck.setSourceSnapshotSha256(VettingCorpus.hash("AUTHORED changed snapshot"));
        assertTrue(VettingPacketCoverage.unassessedIds(topic).contains(id),"An unbound follow-up cannot clear the marker");
    }
    @Test void structurallyInvalidRecordCannotMarkAnUnassignedItem()throws Exception{
        Exercise run=new Exercise("schema_foreign_id");VettingSemanticReview.Result result=run.execute();SemanticTopicVO topic=run.target(result);
        SemanticPacketVO first=topic.getPacketAudits().get(0);
        assertEquals("completed_with_rejections",first.getStatus());assertEquals(1,first.getRejectedRecords());
        assertTrue(first.getUnverifiedChecklistItemIds().isEmpty(),"An item outside the assigned direction must not be marked");
        assertFalse(VettingPacketCoverage.unassessedIds(topic).contains(run.targetIds.get(0)),"The independent valid row remains recorded");
        assertTrue(result.getWarnings().stream().anyMatch(s->s.contains("structural validation")&&s.contains("unassessed")),"The unknown invalid record retains its packet warning");
    }
    @Test void partialSchemaRecordsCannotClaimUnverifiedChecklistRows()throws Exception{
        Exercise run=new Exercise("schema_partial"); SemanticTopicVO topic=run.target(run.execute());
        SemanticPacketVO first=topic.getPacketAudits().get(0);
        assertEquals("completed_with_rejections",first.getStatus()); assertEquals(1,first.getRejectedRecords());
        assertEquals(1,first.getChecklistAssessments().size());
        assertEquals(new LinkedHashSet<>(run.targetIds.subList(1,run.targetIds.size())),new LinkedHashSet<>(VettingPacketCoverage.unassessedIds(topic)));
        assertTrue(topic.getError().contains("single coverage follow-up"));
    }
    @Test void emptyArrayIsSavedThenGetsOneCoverageAttemptAfterEveryInitialDirection()throws Exception{
        Exercise run=new Exercise("empty");SemanticTopicVO topic=run.target(run.execute());
        assertEquals(27,run.initials.get());assertEquals(Collections.singletonList(27),run.coverageInitialCounts);
        assertEquals(1,run.coveragePrompts.size());assertEquals(2,topic.getPacketAudits().size());
        SemanticPacketVO first=topic.getPacketAudits().get(0),retry=topic.getPacketAudits().get(1);
        assertEquals("completed_empty",first.getStatus());assertEquals("not_completed",first.getCheckState());
        assertEquals("checklist_coverage_followup",retry.getKind());assertNull(retry.getRequestedEvidenceNeedId());
        assertTrue(first.getEvidenceNeeds().isEmpty());assertEquals(first.getSourceSnapshotSha256(),retry.getSourceSnapshotSha256());
        assertTrue(VettingPacketCoverage.unassessedIds(topic).isEmpty());assertEquals(0,run.supplementQueries.get());
        assertEquals(run.targetIds,new ArrayList<>(run.rows(run.coveragePrompts.get(0))));
        assertEquals(28,run.generations.get());assertTrue(topic.getPacketAudits().stream().allMatch(p->p.getRejectedRecords()==0));
    }
    @Test void partialArrayRetriesOnlyOmittedAssignedRowsWithoutExpandingSourceText()throws Exception{
        Exercise run=new Exercise("partial");SemanticTopicVO topic=run.target(run.execute());
        String assessed=run.targetIds.get(0);List<String> omitted=run.targetIds.subList(1,run.targetIds.size());
        assertEquals(1,topic.getPacketAudits().get(0).getChecklistAssessments().size());
        assertEquals(new LinkedHashSet<>(omitted),run.rows(run.coveragePrompts.get(0)));
        assertFalse(run.coveragePrompts.get(0).contains(assessed+" | "));assertTrue(VettingPacketCoverage.unassessedIds(topic).isEmpty());
        assertEquals(topic.getPacketAudits().get(0).getSubmittedChunkIds(),topic.getPacketAudits().get(1).getSubmittedChunkIds());
        assertEquals(0,run.supplementQueries.get());assertEquals(28,run.generations.get());
    }
    @Test void aSecondEmptyArrayStaysPendingAndDoesNotStartABlindLoop()throws Exception{
        Exercise run=new Exercise("still_empty");VettingSemanticReview.Result result=run.execute();SemanticTopicVO topic=run.target(result);
        assertEquals(1,run.coveragePrompts.size());assertEquals(2,topic.getPacketAudits().size());assertEquals(28,run.generations.get());
        assertEquals(run.targetIds,VettingPacketCoverage.unassessedIds(topic));assertEquals("not_completed",topic.getCheckState());
        assertEquals("partial",topic.getAggregateReviewStatus());assertTrue(topic.getError().contains("single coverage follow-up"));
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.set("semanticTopics",JsonUtils.mapper().valueToTree(result.getTopicAudits()));
        ObjectNode report=JsonUtils.mapper().createObjectNode();report.putObject("promptPolicy").put("sha256",VettingPromptPolicy.sha256());
        assertTrue(VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream())
                .filter(row->run.targetIds.contains(row.getItemId())).allMatch(row->"not_assessed".equals(row.getStatus())));
        assertTrue(topic.getPacketAudits().stream().allMatch(p->p.getEvidenceNeeds().isEmpty()),"Application omissions must not fabricate model evidence needs");
    }
    @Test void deadlineKeepsCoveragePendingAndDoesNotConsumeAnInitialDirectionSlot()throws Exception{
        Exercise run=new Exercise("deadline");SemanticTopicVO topic=run.target(run.execute());
        assertEquals(27,run.initials.get());assertTrue(run.coveragePrompts.isEmpty());assertEquals(27,run.generations.get());
        assertEquals(1,topic.getPacketAudits().size());assertTrue(topic.getError().contains("run time budget reached"));
        assertEquals(run.targetIds,VettingPacketCoverage.unassessedIds(topic));assertEquals("not_completed",topic.getCheckState());
    }
    @Test void aRealCitedGapFromCoverageUsesTheExistingEvidenceNeedLineage()throws Exception{
        Exercise run=new Exercise("gap");SemanticTopicVO topic=run.target(run.execute());
        assertEquals(1,run.coveragePrompts.size());assertEquals(1,run.supplementQueries.get());assertEquals(3,topic.getPacketAudits().size());
        SemanticPacketVO coverage=topic.getPacketAudits().get(1),source=topic.getPacketAudits().get(2);
        assertNull(coverage.getRequestedEvidenceNeedId());assertEquals(1,coverage.getEvidenceNeeds().size());
        assertEquals(coverage.getPacketId(),source.getParentPacketId());assertEquals(coverage.getEvidenceNeeds().get(0).getNeedId(),source.getRequestedEvidenceNeedId());
        assertEquals("resolved",coverage.getEvidenceNeeds().get(0).getProcessingStatus());assertTrue(VettingPacketCoverage.unassessedIds(topic).isEmpty());
        assertTrue(source.getChecklistAssessments().stream().allMatch(row->!row.getEvidence().isEmpty()&&row.getEvidence().stream().allMatch(FindingEvidence::isLocated)));
        assertEquals(29,run.generations.get());
    }
    @Test void aScopedSourceRoundKeepsOtherRowsForTheCoverageReplay()throws Exception{
        Exercise run=new Exercise("source_fills_omitted");SemanticTopicVO topic=run.target(run.execute());
        assertEquals(27,run.initials.get());assertEquals(1,run.supplementQueries.get());assertEquals(1,run.coveragePrompts.size());
        assertEquals(3,topic.getPacketAudits().size());assertEquals(29,run.generations.get());
        SemanticPacketVO first=topic.getPacketAudits().get(0),source=topic.getPacketAudits().get(1),coverage=topic.getPacketAudits().get(2);
        assertEquals(1,first.getChecklistAssessments().size());assertEquals(1,source.getChecklistAssessments().size());
        assertEquals(first.getPacketId(),source.getParentPacketId());assertEquals(first.getEvidenceNeeds().get(0).getNeedId(),source.getRequestedEvidenceNeedId());
        assertEquals(Collections.singletonList(run.targetIds.get(0)),first.getEvidenceNeeds().get(0).getChecklistItemIds());
        assertEquals("resolved",first.getEvidenceNeeds().get(0).getProcessingStatus());
        assertEquals("checklist_coverage_followup",coverage.getKind());assertEquals(first.getPacketId(),coverage.getParentPacketId());
        assertEquals(new LinkedHashSet<>(run.targetIds.subList(1,run.targetIds.size())),run.rows(run.coveragePrompts.get(0)));
        assertTrue(VettingPacketCoverage.unassessedIds(topic).isEmpty(),"Coverage must retain every assigned row and use verified assessments");
    }
    @Test void deadlineAfterScopedSourceRoundKeepsOtherRowsNotAssessed()throws Exception{
        Exercise run=new Exercise("source_fills_deadline");VettingSemanticReview.Result result=run.execute();SemanticTopicVO topic=run.target(result);
        assertEquals(27,run.initials.get());assertEquals(1,run.supplementQueries.get());assertTrue(run.coveragePrompts.isEmpty());
        assertEquals(2,topic.getPacketAudits().size());assertEquals(run.targetIds.subList(1,run.targetIds.size()),VettingPacketCoverage.unassessedIds(topic));
        assertEquals("not_completed",topic.getCheckState());assertTrue(topic.getError().contains("run time budget reached"));
        ObjectNode coverage=JsonUtils.mapper().createObjectNode();coverage.set("semanticTopics",JsonUtils.mapper().valueToTree(result.getTopicAudits()));
        ObjectNode report=JsonUtils.mapper().createObjectNode();report.putObject("promptPolicy").put("sha256",VettingPromptPolicy.sha256());
        Set<String> pending=new HashSet<>(run.targetIds.subList(1,run.targetIds.size()));
        Set<String> reported=VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream())
                .map(row->row.getItemId()).filter(pending::contains).collect(Collectors.toSet());
        assertEquals(pending,reported,"Every pending row must remain visible");
        assertTrue(VettingChecklistAutomaticResults.aggregate("COMPLETED",coverage,report).stream().flatMap(g->g.getSubItemAssessments().stream())
                .filter(row->pending.contains(row.getItemId())).allMatch(row->"not_assessed".equals(row.getStatus())));
    }
    @Test void rawRowIdsAndUnboundHistoricalRowsCannotSupplyCoverageOrPass(){
        SemanticTopicVO topic=new SemanticTopicVO();topic.setReviewKind("checklist_direction");topic.setReferenceIds(Arrays.asList("APP-I-002","APP-I-003"));topic.setStatus("completed");
        SemanticPacketVO packet=new SemanticPacketVO();packet.setPacketId("authored-unbound");packet.setStatus("completed");packet.setActualGatewayCallStarted(true);
        packet.setInputBudgetStatus("observed_tokens");packet.setCheckState("checked_pass");packet.setVerifiedConsistentAssessments(1);
        ChecklistAssessmentVO row=new ChecklistAssessmentVO();row.setItemId("APP-I-002");row.setStatus("fulfilled");row.setReason("Unbound historical claim");packet.getChecklistAssessments().add(row);topic.getPacketAudits().add(packet);
        VettingPacketCoverage.finish(topic,null);assertEquals(topic.getReferenceIds(),VettingPacketCoverage.unassessedIds(topic));
        assertEquals("not_completed",topic.getCheckState());assertEquals("partial",topic.getAggregateReviewStatus());
    }
    private static final class Exercise {
        final String mode;final ConsenseProperties props=new ConsenseProperties();final AtomicInteger initials=new AtomicInteger(),generations=new AtomicInteger(),supplementQueries=new AtomicInteger();final AtomicLong nanos=new AtomicLong();
        final List<String> coveragePrompts=new ArrayList<>();final List<Integer> coverageInitialCounts=new ArrayList<>();
        final VettingReviewDirections.Direction direction;
        final List<String> targetIds;final Chunk tender=chunk("coverage-tender","The Contractor shall supply the signed tender copy in accordance with the project submission requirements."),extra=chunk("coverage-extra","SCT7 requires a signed tender copy to accompany the project submission.");
        final LlmProfiles profiles;final VettingSemanticReview review;final List<Chunk> corpus;
        Exercise(String mode)throws Exception{
            this.mode=mode;direction=VettingReviewDirections.plan(new String[0]).stream().filter(d->d.items.size()>1)
                .filter(d->!mode.startsWith("schema_")||(VettingChecklistRequirementRelations.automaticEligible(d.items.get(0),"fulfilled")&&"in_scope".equals(d.items.get(0).getReviewScope())))
                .findFirst().orElseThrow();targetIds=direction.itemIds();props.getQwen().setEnabled(true);props.getQwen().setApiKey("AUTHORED_COVERAGE_CREDENTIAL");
            HttpSupport http=mock(HttpSupport.class);when(http.postJsonBoundedWithoutReplay(anyString(),anyString(),anyLong(),anyString(),anyInt(),any())).thenAnswer(call->{
                JsonNode request=JsonUtils.parse(call.getArgument(1));boolean preflight=request.path("max_tokens").asInt()==1;String output="{";
                if(!preflight){generations.incrementAndGet();String prompt=request.path("messages").get(1).path("content").asText();boolean coverage=prompt.contains("Application checklist coverage follow-up"),source=prompt.contains("Specific earlier evidence need");
                    if(coverage){coveragePrompts.add(prompt);coverageInitialCounts.add(initials.get());}else if(!source)initials.incrementAndGet();
                    output=response(prompt,coverage,source);if(("deadline".equals(mode)||"source_fills_deadline".equals(mode))&&initials.get()==27)nanos.set(TimeUnit.MINUTES.toNanos(55));
                }
                ObjectNode envelope=JsonUtils.mapper().createObjectNode();envelope.put("object","chat.completion").put("model","qwen3.8-27b");
                envelope.putArray("choices").addObject().put("index",0).put("finish_reason",preflight?"length":"stop").putObject("message").put("role","assistant").put("content",output);
                envelope.putObject("usage").put("prompt_tokens",200).put("completion_tokens",1);return JsonUtils.write(envelope);
            });
            LlmClient local=mock(LlmClient.class);profiles=new LlmProfiles(props,local,local,new QwenChatClient(props.getQwen(),http),local);
            review=new VettingSemanticReview(new AiGateway(local,profiles),props,mock(VettingRetrievalClient.class));review.setQwenUsagePreflight(new QwenChatUsagePreflight(props,mock(QwenGenerationLedger.class)));
            OerBusinessEvidence oer=mock(OerBusinessEvidence.class);review.setOerBusinessEvidence(oer);
            Constructor<OerBusinessEvidence.Session> constructor=OerBusinessEvidence.Session.class.getDeclaredConstructor(String.class,String.class,List.class,List.class,Map.class,Map.class);constructor.setAccessible(true);
            OerBusinessEvidence.Session session=constructor.newInstance("authored","vetting",Collections.singletonList(1L),Collections.emptyList(),new LinkedHashMap<>(),Collections.emptyMap());
            Class<?> type=Class.forName(OerBusinessEvidence.class.getName()+"$Corpus");Constructor<?> create=type.getDeclaredConstructor(OerBusinessEvidence.Session.class,List.class);create.setAccessible(true);
            @SuppressWarnings("unchecked") List<Chunk> bound=(List<Chunk>)create.newInstance(session,Arrays.asList(tender,extra));corpus=bound;
            when(oer.retrieveReviewText(eq(session),anyString(),nullable(String.class),any(),anyInt())).thenAnswer(call->{
                if("tender".equals(call.getArgument(2)))return Collections.singletonList(tender);
                if(call.<String>getArgument(1).startsWith("Need SCT 7 to confirm")){supplementQueries.incrementAndGet();return Collections.singletonList(extra);}return Collections.emptyList();
            });
        }
        VettingSemanticReview.Result execute()throws Exception{
            Method run=VettingSemanticReview.class.getDeclaredMethod("reviewDirections",String.class,VettingSemanticReview.Result.class,List.class,List.class,String.class,List.class,Runnable.class,VettingReviewProbe.class,java.util.function.LongSupplier.class);run.setAccessible(true);
            LlmOperation chat=profiles.resolve("qwen");VettingLlmOperation operation=review.captureOperation(chat);
            try(LlmProfiles.Scope profile=profiles.bind(chat);VettingSemanticReview.OperationScope bound=review.bindOperation(operation);QwenChatUsagePreflight.Operation.ActionScope action=operation.getQwen().openAction("AUTHORED checklist coverage",()->{})){
                return (VettingSemanticReview.Result)run.invoke(review,"authored",new VettingSemanticReview.Result(),corpus,corpus,"en",null,(Runnable)()->{},VettingReviewProbe.start(props,null,"authored"),(java.util.function.LongSupplier)nanos::get);
            }
        }
        SemanticTopicVO target(VettingSemanticReview.Result result){return result.getTopicAudits().stream().filter(a->a.getTopic().startsWith(direction.id+":" )).findFirst().orElseThrow();}
        Set<String> rows(String prompt){Set<String> ids=new LinkedHashSet<>();Matcher match=Pattern.compile("(?m)^(APP-I-\\d+) \\| ").matcher(prompt);while(match.find())ids.add(match.group(1));return ids;}
        String response(String prompt,boolean coverage,boolean source){
            List<String> ids=new ArrayList<>(rows(prompt));boolean target=prompt.contains("Assigned review direction: "+direction.id+". ");
            if(target&&!coverage&&!source){if(mode.startsWith("schema_"))ids=ids.subList(0,2);else if("partial".equals(mode)||mode.startsWith("source_fills_"))ids=ids.subList(0,1);else return "{\"records\":[]}";}
            if(target&&coverage&&("still_empty".equals(mode)||mode.startsWith("schema_")&&!"schema_same_id_resolved".equals(mode)))return "{\"records\":[]}";
            List<Map<String,Object>> records=new ArrayList<>();
            for(String id:ids){boolean gap=target&&((coverage&&"gap".equals(mode)&&id.equals(targetIds.get(0)))||(!coverage&&!source&&mode.startsWith("source_fills_")));Map<String,Object> record=new LinkedHashMap<>();VettingCompetitionFixture.details(record);record.put("assessment",gap?"insufficient_context":"consistent");record.put("type","requirement");record.put("severity","low");record.put("title","Authored checklist assessment");
                String reason=gap?"Need SCT 7 to confirm the signed-copy submission requirement.":"The supplied authored tender provision is retained for this checklist row.";
                record.put("comment",reason);record.put("impact","Review only");record.put("suggestion","Retain source-bound assessment");if(gap)record.put("pendingFacts",Collections.singletonList(reason));
                List<Map<String,Object>> quotes=new ArrayList<>();quotes.add(quote(reference(prompt,tender),"source",tender.getContent()));if(source)quotes.add(quote(reference(prompt,extra),"reference",extra.getContent()));record.put("evidence",quotes);
                Map<String,Object> row=new LinkedHashMap<>();row.put("itemId",id);row.put("status",gap?"insufficient_evidence":"fulfilled");row.put("reason",reason);row.put("support",null);record.put("checklistAssessments",Collections.singletonList(row));records.add(record);
            }
            if(target&&!coverage&&!source&&mode.startsWith("schema_"))records.get(1).put("severity",3);
            if(target&&!coverage&&!source&&(mode.startsWith("schema_same_id")||"schema_foreign_id".equals(mode))){
                records.get(1).put("assessment","issue");
                @SuppressWarnings("unchecked") List<Map<String,Object>> checks=(List<Map<String,Object>>)records.get(1).get("checklistAssessments");
                String item=mode.startsWith("schema_same_id")?targetIds.get(0):VettingChecklistCatalog.items().stream().map(VettingChecklistCatalog.Item::getId).filter(id->!targetIds.contains(id)).findFirst().orElseThrow();
                checks.get(0).put("itemId",item);checks.get(0).put("status","not_fulfilled");
            }
            return "{\"records\":"+JsonUtils.write(records)+"}";
        }
    }
    private static Map<String,Object> quote(String id,String side,String text){Map<String,Object> value=new LinkedHashMap<>();value.put("chunkId",id);value.put("side",side);value.put("quote",text);return value;}
    private static String reference(String prompt,Chunk source){int literal=prompt.indexOf("\n"+source.getContent());assertTrue(literal>=0);int header=prompt.lastIndexOf("\n\nR",literal);assertTrue(header>=0);return prompt.substring(header+2,prompt.indexOf(" | ",header));}
    private static Chunk chunk(String id,String text){Chunk c=VettingContextBudgetTest.chunk(id,"SCT1",text,text.length());c.setDocumentId("1");c.setFileName("Authored coverage tender.docx");c.setSourceHash(VettingCorpus.hash("AUTHORED coverage parsed source"));c.setOriginalSourceSha256(VettingCorpus.hash("AUTHORED coverage original source"));com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer p=new com.consense.web.dto.CanonicalEvidenceDtos.EvidencePointer();p.setCanonicalChunkId(id);p.setJavaParsedSourceHash(c.getSourceHash());c.setCanonicalEvidence(p);return c;}
}
