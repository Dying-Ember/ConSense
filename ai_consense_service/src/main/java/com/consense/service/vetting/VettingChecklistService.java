package com.consense.service.vetting;

import com.consense.common.BizException;
import com.consense.common.JsonUtils;
import com.consense.domain.VettingRun;
import com.consense.domain.VettingChecklistReview;
import com.consense.repository.VettingChecklistReviewRepository;
import com.consense.repository.VettingRunRepository;
import com.consense.service.ProjectService;
import com.consense.web.dto.VettingChecklistDtos.ChecklistVO;
import com.consense.web.dto.VettingChecklistDtos.ItemVO;
import com.consense.web.dto.VettingChecklistDtos.UpdateRequest;
import com.consense.web.dto.VettingChecklistDtos.UpdateItem;
import com.consense.web.dto.VettingChecklistDtos.LogicalGroupVO;
import com.consense.web.dto.VettingChecklistDtos.AutomaticGroupVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.Arrays;
import java.util.Objects;
import java.time.Instant;
import java.util.stream.Collectors;

/** Appendix I is a human countercheck record, independent of finding or run completion status. */
@Service
@RequiredArgsConstructor
public class VettingChecklistService {
    private final ProjectService projectService;
    private final VettingRunRepository runRepository;
    private final VettingChecklistReviewRepository reviewRepository;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private VettingService vetting;

    public ChecklistVO get(String projectId,String runId) {
        VettingRun run=requireRun(projectId,runId);
        String binding=snapshot(run);
        List<AutomaticGroupVO> automatic=VettingChecklistAutomaticResults.forRun(run);
        Map<String,com.fasterxml.jackson.databind.JsonNode> automaticBases=VettingChecklistAutomaticResults.completeGroupBases(automatic);
        boolean policyAware=JsonUtils.parse(run.getResultJson()).path("promptPolicy").hasNonNull("sha256");
        Map<String,VettingChecklistReview> reviews=reviewRepository.findByProjectIdAndRunId(projectId,runId).stream()
                .filter(review->binding.equals(review.getRunSnapshotSha256()))
                .collect(Collectors.toMap(VettingChecklistReview::getItemId,review->review));
        List<VettingChecklistReview> history=reviewRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
        List<ItemVO> items=VettingChecklistCatalog.items().stream().map(item->{
            VettingChecklistReview review=reviews.get(item.getId());
            String basis=basis(run,item,automaticBases.get(item.getId()),policyAware);
            if(review!=null&&review.getBasisSha256()!=null&&!Objects.equals(basis,review.getBasisSha256()))review=null;
            if(review==null&&basis!=null)review=history.stream().filter(saved->item.getId().equals(saved.getItemId())
                    &&basis.equals(saved.getBasisSha256())&&saved.getUpdatedAt()!=null&&run.getStartedAt()!=null
                    &&!saved.getUpdatedAt().isAfter(run.getStartedAt())).findFirst().orElse(null);
            ItemVO result=new ItemVO(item.getId(),item.getSection(),item.getClause(),item.getDescription(),item.getTemplatePage(),
                    item.getReviewScope(),item.getScopeNote(),item.getScopeNoteText(),
                    review==null?"unchecked":review.getStatus(),review==null?"":review.getRemarks(),review==null?null:review.getUpdatedAt(),
                    review==null?null:review.getRunId());
            com.fasterxml.jackson.databind.JsonNode dependency=VettingChecklistDependencies.itemLedger(run.getCoverageJson(),run.getProjectId(),item);
            boolean membersClosed=closedDependency(dependency);
            boolean scoped=basis!=null&&membersClosed&&(!policyAware||automaticBases.containsKey(item.getId()));
            result.setDependencyStatus(membersClosed?"required_member_scope_closed":"required_member_dependencies_unknown");result.setReuseScope(scoped?"exact_required_material_members":"exact_whole_generation");
            VettingChecklistReview previous=history.stream().filter(saved->item.getId().equals(saved.getItemId())
                    &&!runId.equals(saved.getRunId())&&saved.getUpdatedAt()!=null&&run.getStartedAt()!=null
                    &&!saved.getUpdatedAt().isAfter(run.getStartedAt())).findFirst().orElse(null);
            result.setReuseStatus(review!=null?(runId.equals(review.getRunId())?"current_run_record":scoped?"matched_exact_required_material_members":"matched_exact_whole_generation")
                    :previous!=null?"history_only_dependency_or_generation_unknown":"no_previous_record");
            if(previous!=null){result.setPreviousDecisionStatus(previous.getStatus());result.setPreviousRemarks(previous.getRemarks());result.setPreviousReviewedRunId(previous.getRunId());}
            return result;
        }).collect(Collectors.toList());
        ChecklistVO result=new ChecklistVO(projectId,runId,binding,vetting.currentReviewRun(run),items,run.getSourceSnapshotSha256());
        result.setLogicalGroups(VettingChecklistCatalog.logicalGroups().stream().map(group->new LogicalGroupVO(
                group.getId(),group.getSection(),group.getClause(),group.getSourceItemIds())).collect(Collectors.toList()));
        result.setAnnexItemIds(VettingChecklistCatalog.annexItemIds());result.setAutomaticResults(automatic);
        return result;
    }

    @Transactional
    public ChecklistVO update(String projectId,String runId,UpdateRequest request) {
        projectService.require(projectId);
        if(request==null||!runId.equals(request.getExpectedRunId()))throw new BizException(4104,"Checklist run binding is missing or changed; reload before saving");
        VettingRun run=vetting.requireCurrentReviewRun(projectId,runId,request.getExpectedSourceSnapshotSha256());
        String binding=snapshot(run);
        if(request==null||!binding.equals(request.getRunSnapshotSha256()))
            throw new BizException(4123,"Review run or checklist requirements changed; reload before saving");
        validateItems(request.getItems());
        Map<String,com.fasterxml.jackson.databind.JsonNode> automaticBases=VettingChecklistAutomaticResults.completeGroupBases(VettingChecklistAutomaticResults.forRun(run));
        boolean policyAware=JsonUtils.parse(run.getResultJson()).path("promptPolicy").hasNonNull("sha256");
        Map<String,VettingChecklistReview> current=reviewRepository.findByProjectIdAndRunId(projectId,runId).stream()
                .collect(Collectors.toMap(VettingChecklistReview::getItemId,review->review));
        List<VettingChecklistReview> changes=new ArrayList<>();
        for(UpdateItem item:request.getItems()) {
            VettingChecklistReview review=current.get(item.getId());
            if(review==null) {review=new VettingChecklistReview();review.setId(UUID.randomUUID().toString());}
            review.setProjectId(projectId);review.setRunId(runId);review.setItemId(item.getId());
            VettingChecklistCatalog.Item requirement=VettingChecklistCatalog.items().stream().filter(i->i.getId().equals(item.getId())).findFirst().get();
            review.setBasisSha256(basis(run,requirement,automaticBases.get(item.getId()),policyAware));
            review.setRunSnapshotSha256(binding);review.setStatus(item.getStatus());
            review.setRemarks(item.getRemarks()==null?"":item.getRemarks());review.setUpdatedAt(Instant.now());changes.add(review);
        }
        reviewRepository.saveAllAndFlush(changes);
        return get(projectId,runId);
    }

    public List<VettingChecklistCatalog.Result> resultsForRun(String projectId,String runId) {
        return get(projectId,runId).getItems().stream().map(item->new VettingChecklistCatalog.Result(
                item.getId(),item.getStatus(),item.getRemarks())).collect(Collectors.toList());
    }

    private VettingRun requireRun(String projectId,String runId) {
        projectService.require(projectId);
        return runRepository.findById(runId).filter(run->projectId.equals(run.getProjectId()))
                .orElseThrow(()->new BizException(4102,"Review run is unavailable for this project"));
    }

    private static String snapshot(VettingRun run) {
        return VettingCorpus.hash("vetting-checklist-v1\n"+run.getId()+"\n"+
                (run.getCoverageJson()==null?"":run.getCoverageJson())+"\n"+
                VettingChecklistCatalog.items().stream().map(item->item.getId()+"\n"+item.getSection()+"\n"+
                        item.getClause()+"\n"+item.getDescription()+"\n"+item.getReviewScope()+"\n"+item.getScopeNote()).collect(Collectors.joining("\n")));
    }

    private static String basis(VettingRun run,VettingChecklistCatalog.Item item,com.fasterxml.jackson.databind.JsonNode automaticBasis,boolean policyAware) {
        if(!"COMPLETED".equals(run.getStatus())||run.getSourceSnapshotSha256()==null||!run.getSourceSnapshotSha256().matches("[a-f0-9]{64}")
                ||JsonUtils.isBlankText(run.getCoverageJson())||JsonUtils.isBlankText(run.getResultJson())
                ||JsonUtils.isBlankText(run.getModelIdentityJson())||run.getRecoveryContractSha256()==null
                ||!run.getRecoveryContractSha256().matches("[a-f0-9]{64}"))return null;
        com.consense.ai.ModelIdentity model=JsonUtils.read(run.getModelIdentityJson(),com.consense.ai.ModelIdentity.class);
        if(model==null||JsonUtils.isBlankText(model.getProfileId())||JsonUtils.isBlankText(model.getProvider())||JsonUtils.isBlankText(model.getModel())
                ||JsonUtils.isBlankText(model.getIdentityScope())||model.getConfigurationSha256()==null||!model.getConfigurationSha256().matches("[a-f0-9]{64}"))return null;
        com.fasterxml.jackson.databind.JsonNode dependency=VettingChecklistDependencies.itemLedger(run.getCoverageJson(),run.getProjectId(),item);
        if(closedDependency(dependency)&&(!policyAware||automaticBasis!=null)) {
            if(policyAware)return VettingCorpus.hash(JsonUtils.write(Arrays.asList("manual-check-scoped-required-material-and-assessment-basis-v2",run.getProjectId(),dependency.path("basisSha256"),
                    run.getModelIdentityJson(),run.getRecoveryContractSha256(),VettingChecklistDependencies.requirementHash(item),automaticBasis)));
            return VettingCorpus.hash(JsonUtils.write(Arrays.asList("manual-check-scoped-required-material-basis-v1",run.getProjectId(),dependency.path("basisSha256"),
                    run.getModelIdentityJson(),run.getRecoveryContractSha256(),VettingChecklistDependencies.requirementHash(item))));
        }
        // shortcut: unclosed checklist dependencies use the whole generation; no filename or user-selected subset proves non-impact.
        return VettingCorpus.hash(JsonUtils.write(Arrays.asList("manual-check-basis-v1",run.getProjectId(),run.getSourceSnapshotSha256(),
                run.getCoverageJson(),run.getResultJson(),run.getModelIdentityJson(),run.getRecoveryContractSha256(),item.getId(),
                item.getSection(),item.getClause(),item.getDescription(),item.getReviewScope(),item.getScopeNote())));
    }

    private static boolean closedDependency(com.fasterxml.jackson.databind.JsonNode dependency) {
        return dependency!=null&&"required_member_scope_closed".equals(dependency.path("status").asText())
                &&dependency.path("basisSha256").asText().matches("[a-f0-9]{64}")
                &&dependency.path("requiredMemberRefs").isArray()&&!dependency.path("requiredMemberRefs").isEmpty();
    }

    private static void validateItems(List<UpdateItem> items) {
        if(items==null||items.isEmpty()||items.size()>VettingChecklistCatalog.items().size())
            throw new BizException(4121,"Provide one or more distinct checklist requirements");
        Set<String> ids=VettingChecklistCatalog.items().stream().map(VettingChecklistCatalog.Item::getId).collect(Collectors.toSet());
        Set<String> seen=new HashSet<>();
        for(UpdateItem item:items) {
            if(item==null||!ids.contains(item.getId())||!seen.add(item.getId()))
                throw new BizException(4121,"Unknown or repeated checklist requirement");
            if(!"unchecked".equals(item.getStatus())&&!"fulfilled".equals(item.getStatus())&&!"not_fulfilled".equals(item.getStatus())&&!"not_applicable".equals(item.getStatus()))
                throw new BizException(4121,"Checklist state must be unchecked, fulfilled, not_fulfilled or not_applicable");
            if("not_fulfilled".equals(item.getStatus())&&JsonUtils.isBlankText(item.getRemarks()))
                throw new BizException(4121,"A not fulfilled human countercheck requires its specific reason");
            if(item.getRemarks()!=null&&item.getRemarks().length()>4000)
                throw new BizException(4121,"Checklist remarks must not exceed 4000 characters");
        }
    }
}
