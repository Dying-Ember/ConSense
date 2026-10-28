package com.consense.service.drafting;

import com.consense.ai.*;
import com.consense.common.*;
import com.consense.domain.*;
import com.consense.repository.*;
import com.consense.service.ProjectService;
import com.consense.service.llm.QwenGenerationLedger;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.annotation.PreDestroy;
import javax.persistence.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One persistence coordinator, bounded group inference, and no automatic paid replay. */
@Service
public class DraftAutomationService {
    private static final List<String> ACTIVE=Arrays.asList("QUEUED","RUNNING");
    private static final int MAX_TASKS=512,MAX_CALLS=768;
    private static final long LIFETIME=21600000L;
    private static final String GROUP_EXECUTION="fixed-root-groups-v1",GROUP_PHASE="GROUP_DISCOVERY";
    private final DraftAutomationRunRepository runs;
    private final DraftAutomationItemRepository items;
    private final DraftTemplateGraphHeadRepository heads;
    private final SourceDocumentRepository sources;
    private final DraftTemplateGraphService graphs;
    private final DraftPdfSccReference pdfReference;
    private final ProjectService projects;
    private final LlmProfiles profiles;
    private final AiGateway ai;
    private final QwenGenerationLedger ledger;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"draft-variable-graph");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor groupWorkers=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r);t.setName("draft-variable-group-"+t.getId());t.setDaemon(true);return t;});
    private int groupConcurrency=2;
    private boolean acceptanceProbeStopOnDefect;
    private volatile boolean stopping;

    @Value("${consense.drafting.group-concurrency:2}")
    void groupConcurrency(int value){if(value!=1&&value!=2)throw new IllegalArgumentException("Drafting group concurrency must be 1 or 2");groupConcurrency=value;}

    @Value("${consense.drafting.acceptance-probe-stop-on-defect:false}")
    void acceptanceProbeStopOnDefect(boolean value){acceptanceProbeStopOnDefect=value;}

    public DraftAutomationService(DraftAutomationRunRepository runs,DraftAutomationItemRepository items,
            DraftTemplateGraphHeadRepository heads,SourceDocumentRepository sources,DraftTemplateGraphService graphs,
            ProjectService projects,LlmProfiles profiles,AiGateway ai,QwenGenerationLedger ledger,DraftPdfSccReference pdfReference,
            EntityManager entityManager,PlatformTransactionManager manager) {
        this.runs=runs;this.items=items;this.heads=heads;this.sources=sources;this.graphs=graphs;
        this.projects=projects;this.profiles=profiles;this.ai=ai;this.ledger=ledger;this.pdfReference=pdfReference;this.entityManager=entityManager;
        tx=new TransactionTemplate(manager);
    }

    public Map<String,Object> start(String projectId,String expectedCurrent,String expectedDraft) {
        return start(projectId,expectedCurrent,expectedDraft,null);
    }
    public Map<String,Object> start(String projectId,String expectedCurrent,String expectedDraft,List<String> sourceIds) {
        LlmOperation selected=profiles.capture();requireProfile(selected);
        DraftAutomationRun run=tx.execute(s->{
            lockProject(projectId);
            checkHead(projectId,expectedCurrent,expectedDraft);
            Set<String> work=selectSources(sources.findDraftAutomationStamp(projectId),sourceIds);
            List<DraftAutomationRun> active=runs.findByProjectIdAndStatusIn(projectId,ACTIVE);
            if(!active.isEmpty()){DraftAutomationRun prior=active.get(0);Set<String> priorWork=selectedSources(prior.getManifestJson());boolean sameScope=priorWork==null?sourceIds==null&&Objects.equals(prior.getSourceStampJson(),stamp(projectId)):work.equals(priorWork);if(!Objects.equals(expectedCurrent,prior.getExpectedCurrentRevisionId())||!Objects.equals(expectedDraft,prior.getExpectedDraftRevisionId())||!sameScope)throw conflict("此项目已有另一来源范围或图谱版本的识别任务，请先停止它再开始。");return prior;}
            Set<String> protectedIds=new TreeSet<>(work);protectedIds.addAll(graphs.automationSourceDependencies(projectId,expectedDraft==null?expectedCurrent:expectedDraft));
            DraftAutomationRun row=new DraftAutomationRun();row.setId(UUID.randomUUID().toString());row.setProjectId(projectId);
            row.setStatus("QUEUED");row.setPhase("PREPARING_SOURCES");row.setMessage("已排队，准备读取已识别模板的完整来源。");
            row.setProfileId(selected.getIdentity().getProfileId());row.setConfigurationSha256(selected.getIdentity().getConfigurationSha256());
            row.setExpectedCurrentRevisionId(expectedCurrent);row.setExpectedDraftRevisionId(expectedDraft);
            row.setManifestJson(JsonUtils.write(DraftReviewGraph.obj("sourceSelection",DraftReviewGraph.obj("version",1,"sourceIds",new ArrayList<>(work),"protectedSourceIds",new ArrayList<>(protectedIds)))));
            row.setSourceStampJson(stamp(projectId,protectedIds));row.setCreatedAt(Instant.now());row.setUpdatedAt(row.getCreatedAt());return runs.saveAndFlush(row);
        });
        if("QUEUED".equals(run.getStatus())){
            LlmOperation original=profiles.resolve(run.getProfileId());requireProfile(original);
            if(!Objects.equals(run.getConfigurationSha256(),original.getIdentity().getConfigurationSha256()))throw conflict("任务所选模型配置已变化。");
            dispatch(run.getId(),original);
        }
        return view(require(projectId,run.getId()));
    }

    public Map<String,Object> latest(String projectId){projects.require(projectId);return runs.findFirstByProjectIdOrderByCreatedAtDesc(projectId).map(this::view).orElse(null);}
    public Map<String,Object> get(String projectId,String id){projects.require(projectId);return view(require(projectId,id));}
    public byte[] diagnostics(String projectId,String id){
        projects.require(projectId);require(projectId,id);
        return tx.execute(s->{DraftAutomationRun row=runs.lockRun(id).filter(run->projectId.equals(run.getProjectId())).orElseThrow(()->new BizException(4011,"识别任务不存在。"));
            if(ACTIVE.contains(row.getStatus()))throw conflict("请等待任务结束或停止后，再下载已保存的诊断记录。");
            LlmOperation selected=profiles.resolve(row.getProfileId());
            return DraftAutomationDiagnostics.encode(row,items.findByRunIdOrderByTaskOrderAsc(id),selected==null?null:selected.getIdentity());
        });
    }
    public Map<String,Object> cancel(String projectId,String id) {
        require(projectId,id);
        change(id,row->{if(ACTIVE.contains(row.getStatus())){row.setCancelRequested(true);row.setMessage("已请求停止；当前调用返回后保留已识别结果。");}});
        return get(projectId,id);
    }
    public Map<String,Object> resume(String projectId,String id,String expectedCurrent,String expectedDraft) {
        DraftAutomationRun previous=require(projectId,id);LlmOperation selected=profiles.resolve(previous.getProfileId());requireProfile(selected);
        if(!Objects.equals(previous.getConfigurationSha256(),selected.getIdentity().getConfigurationSha256()))throw conflict("模型配置已变化，请重新开始识别。");
        tx.executeWithoutResult(s->{lockProject(projectId);DraftAutomationRun row=runs.lockRun(id).orElseThrow(()->conflict("任务不存在。"));
            boolean saved=row.getResultRevisionId()!=null;
            if(!"INTERRUPTED".equals(row.getStatus())&&!(saved&&"PARTIAL".equals(row.getStatus())))throw conflict("只有中断任务或仍有未派发分组的部分结果可继续；已发送调用不会重发。");
            if(!runs.findByProjectIdAndStatusIn(projectId,ACTIVE).isEmpty())throw conflict("此项目已有识别任务正在执行。");
            checkHead(projectId,expectedCurrent,expectedDraft);
            Set<String> protectedIds=protectedSources(row.getManifestJson());if(protectedIds!=null&&!protectedIds.containsAll(graphs.automationSourceDependencies(projectId,expectedDraft==null?expectedCurrent:expectedDraft)))throw conflict("当前人工图谱新增了原任务保护范围外的原件依赖，请新建识别任务；原有结果、来源选择和累计调用预算保留。");
            if(System.currentTimeMillis()-row.getCreatedAt().toEpochMilli()>=LIFETIME)throw conflict("原识别任务的6小时时限已到，不能继续；已有结果和调用记录保留。");
            if(!Objects.equals(row.getSourceStampJson(),stamp(projectId,protectedSources(row.getManifestJson()))))throw conflict("模板或来源已变化，不能继续旧任务。");
            if(budgetCalls(receipts(id))>=MAX_CALLS&&!(saved&&!continuationTasks(row.getManifestJson()).isEmpty()))throw conflict("原识别任务的累计调用预算已用完；已有结果保留。");
            if(saved){
                Map<String,Object> manifest=JsonUtils.readMap(row.getManifestJson());if(!GROUP_EXECUTION.equals(manifest.get("execution")))throw conflict("此结果不是可继续的分组任务。");
                String head=expectedDraft==null?expectedCurrent:expectedDraft;requireResultAncestor(projectId,head,row.getResultRevisionId());
                Map<String,Object> continuation=map(manifest.get("continuation"));
                if(continuation.isEmpty()){
                    Set<String> groupIds=new HashSet<>();for(Object raw:list(manifest.get("groups")))groupIds.add(text(map(raw).get("taskId")));
                    List<String> pending=new ArrayList<>();boolean hasGroup=false;for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(id))if("PENDING".equals(item.getStatus())){pending.add(item.getTaskId());hasGroup|=groupIds.contains(item.getTaskId());}
                    if(!hasGroup)throw conflict("没有尚未派发的分组；失败、部分返回和未知调用不会重新发送。");
                    manifest.put("continuation",DraftReviewGraph.obj("baseResultRevisionId",row.getResultRevisionId(),"expectedCurrentRevisionId",expectedCurrent,"expectedDraftRevisionId",expectedDraft,"taskIds",pending,"startedAt",Instant.now().toString()));
                }else if(!Objects.equals(continuation.get("baseResultRevisionId"),row.getResultRevisionId())||!Objects.equals(continuation.get("expectedCurrentRevisionId"),expectedCurrent)||!Objects.equals(continuation.get("expectedDraftRevisionId"),expectedDraft))throw conflict("续跑尚未保存的结果绑定了另一图谱版本；不能覆盖后来的人工修改。");
                row.setManifestJson(JsonUtils.write(manifest));row.setExpectedCurrentRevisionId(expectedCurrent);row.setExpectedDraftRevisionId(expectedDraft);
            }else if(!Objects.equals(row.getExpectedCurrentRevisionId(),expectedCurrent)||!Objects.equals(row.getExpectedDraftRevisionId(),expectedDraft))throw conflict("图谱已变化，不能继续旧任务。");
            row.setCancelRequested(false);row.setStatus("QUEUED");row.setMessage("继续未执行事项；已完成、失败及结果不明的调用均不会重发。");row.setUpdatedAt(Instant.now());runs.save(row);
        });
        dispatch(id,selected);return get(projectId,id);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for(DraftAutomationRun run:runs.findByStatusIn(ACTIVE)) {
            tx.executeWithoutResult(s->{DraftAutomationRun row=runs.lockRun(run.getId()).orElseThrow(IllegalStateException::new);
                for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(row.getId()))if("IN_FLIGHT".equals(item.getStatus())){
                    Map<String,Object> receipt=missingReceipt(JsonUtils.readMap(item.getTaskJson()),"调用已发出但结果未保存，不自动重发。");
                    receipt.put("status","unknown");
                    Map<String,Object> previous=item.getReceiptJson()==null?Collections.emptyMap():JsonUtils.readMap(item.getReceiptJson());
                    if(isGroup(receipt)){receipt.put("modelCalls",null);receipt.put("reservedModelCalls",number(previous.get("reservedModelCalls")));}
                    else if(previous.containsKey("groupReceiptId")){receipt.put("groupReceiptId",previous.get("groupReceiptId"));receipt.put("owningTaskId",item.getTaskId());receipt.put("modelCalls",0);}
                    item.setStatus("UNKNOWN");item.setReceiptJson(JsonUtils.write(receipt));items.save(item);
                }
                boolean continuationProbe=continuationProbeStop(row);
                row.setStatus(row.isCancelRequested()&&!continuationProbe?"CANCELLED":row.getResultRevisionId()==null?"INTERRUPTED":"PARTIAL");row.setMessage(row.isCancelRequested()&&!continuationProbe?"任务已停止，已保存的图谱和调用记录保留；未执行事项不会自动重发。":row.getResultRevisionId()==null?"服务重启，任务已中断；可继续未发送的事项。":"续跑因服务重启中断；原图谱与已返回回执保留，可继续尚未派发的分组并保存新增结果。");row.setUpdatedAt(Instant.now());runs.save(row);
            });
        }
    }
    @PreDestroy public void shutdown(){stopping=true;worker.shutdownNow();groupWorkers.shutdown();}

    private final Set<String> scheduled=ConcurrentHashMap.newKeySet();
    private void dispatch(String id,LlmOperation selected) {
        if(!scheduled.add(id))return;
        try{worker.execute(()->{try{execute(id,selected);}finally{scheduled.remove(id);}});}
        catch(RejectedExecutionException full){scheduled.remove(id);change(id,row->{row.setStatus("INTERRUPTED");row.setMessage("识别队列已满，任务已保留，可稍后继续。");});}
    }
    private void execute(String id,LlmOperation selected) {
        DraftAutomationRun run=runs.findById(id).orElse(null);if(run==null)return;
        try(LlmProfiles.Scope ignored=profiles.bind(selected)) {
            guard(run,false);change(id,row->{row.setStatus("RUNNING");row.setPhase("PREPARING_SOURCES");row.setMessage("读取模板专用OER已存的指导说明、修改标记、控制范围和条件；此处不重复解析文件。");});
            List<JsonNode> readings=new ArrayList<>();Set<String> work=selectedSources(run.getManifestJson());Map<String,Object> base=graphs.automationInput(run.getProjectId(),run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId(),work,readings);
            Map<String,Object> restoration=map(map(base.get("graph")).get("baselineRestoration"));
            final boolean fixedCandidates=DraftFixedBaseline.installed(base);
            if(run.getExpectedCurrentRevisionId()==null&&run.getExpectedDraftRevisionId()==null&&(fixedCandidates||"complete_requires_review".equals(restoration.get("status")))){
                final int definitions=DraftReviewGraph.nodes(base).size(),roots=DraftReviewGraph.fields(base).size();
                final long conditions=DraftReviewGraph.nodes(base).stream().filter(node->!map(node.get("applicability")).isEmpty()).count();
                String manifest=preserveFirstProbeFailure(JsonUtils.write(fixedCandidates?DraftReviewGraph.obj("execution","fixed-catalog-candidates-v1","ruleVersion",DraftBusinessRules.RULE_VERSION,"definitions",definitions,"conditions",conditions,"modelCalls",0,"coverage","requires_review"):Collections.singletonMap("completeBaselineId",restoration.get("baselineId"))),run.getManifestJson());String previous=executionManifest(run.getManifestJson());if(hasExecutionPlan(previous)&&!previous.equals(executionManifest(manifest)))throw conflict("同版基线已变化，请重新读取。");
                change(id,row->{row.setManifestJson(preserveFirstProbeFailure(manifest,row.getManifestJson()));row.setTotalItems(0);row.setPhase("SAVING_GRAPH");row.setMessage(fixedCandidates?"恢复当前精确原件的内置固定定义和条件候选；本步骤不调用模型。":"恢复已完整核对的同版图谱，并重新绑定当前原件。");});
                tx.executeWithoutResult(s->{lockProject(run.getProjectId());guard(run,false);Map<String,Object> saved=graphs.save(run.getProjectId(),base,run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId(),true);DraftAutomationRun row=runs.lockRun(id).orElseThrow(IllegalStateException::new);row.setResultRevisionId(String.valueOf(saved.get("id")));row.setStatus(fixedCandidates?"PARTIAL":"COMPLETED");row.setPhase(fixedCandidates?"FIXED_BASELINE_READY":"READY_FOR_REVIEW");if(fixedCandidates){row.setNodesCount(definitions);row.setUnresolvedItems(roots);}row.setMessage(fixedCandidates?"已恢复 "+definitions+" 项固定定义候选和 "+conditions+" 项明确条件；模型调用为 0。请先按组核对确认，再提取项目取值；修改范围和例外继续单独核对。":"已恢复人工标记完整的同版图谱；请核对当前原件的修改范围，再单独处理项目取值。");row.setUpdatedAt(Instant.now());runs.save(row);});
                return;
            }
            guard(run,false);phase(id,"PLANNING",null,"按模板专用OER观察建立整套变量识别任务及来源覆盖清单。");
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings,base);
            boolean continuing=!continuationTasks(run.getManifestJson()).isEmpty();
            List<Map<String,Object>> groups=executionGroups(run.getManifestJson(),plan,continuing);
            String manifest=groups==null?JsonUtils.write(plan.descriptor()):groupManifest(plan,groups);
            change(id,row->{row.setManifestJson(preserveFirstProbeFailure(manifest,row.getManifestJson()));row.setTotalItems(plan.tasks().size());});
            ensureItems(id,plan.tasks());
            if(groups!=null)ensureItems(id,groups);
            if(isQwen(run))ledger.open("draft:"+id,run.getConfigurationSha256(),new QwenGenerationLedger.Limits(MAX_CALLS,MAX_CALLS,MAX_CALLS,LIFETIME));
            String selectedSccId=text(map(base.get("selectedDocuments")).get("SCC"));
            List<Map<String,Object>> receipts=receipts(id);boolean stopped=groups==null?processTasks(run,plan,plan.tasks(),receipts,false,selectedSccId):processGroups(run,selected,plan,groups,receipts);
            if(!stopped){List<Map<String,Object>> relations=DraftAutomaticDiscovery.planRelations(plan,receipts);ensureItems(id,relations);if(continuing)includeContinuationTasks(id,relations);change(id,row->row.setTotalItems(plan.tasks().size()+relations.size()));stopped=processTasks(run,plan,relations,receipts,true,selectedSccId);}
            guard(run,true);phase(id,"SAVING_GRAPH",null,"合并完整可用的变量和关系候选，保存一份待人工核对的图谱。");
            String finishingManifest=runs.findById(id).orElseThrow(IllegalStateException::new).getManifestJson();
            final String cycleProbeStop=continuing?continuationStop(finishingManifest):"";
            Map<String,Object> graph=continuing?DraftAutomaticDiscovery.finish(base,plan,receipts,continuationTasks(finishingManifest)):DraftAutomaticDiscovery.finish(base,plan,receipts);
            // The result handle commits with the only graph revision; a restart cannot publish it twice.
            String result=tx.execute(s->{lockProject(run.getProjectId());guard(run,true);Map<String,Object> saved=graphs.save(run.getProjectId(),graph,run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId(),true);
                DraftAutomationRun row=runs.lockRun(id).orElseThrow(IllegalStateException::new);String revision=String.valueOf(saved.get("id"));row.setResultRevisionId(revision);if(continuing){Map<String,Object> completed=JsonUtils.readMap(row.getManifestJson());Map<String,Object> cycle=map(completed.remove("continuation"));completed.put("lastContinuation",DraftReviewGraph.obj("baseResultRevisionId",cycle.get("baseResultRevisionId"),"resultRevisionId",revision,"startedAt",cycle.get("startedAt"),"finishedAt",Instant.now().toString(),"stop",cycle.get("stop")));row.setManifestJson(JsonUtils.write(completed));}row.setUpdatedAt(Instant.now());runs.save(row);return revision;});
            boolean partial=!"completed".equals(map(map(graph.get("graph")).get("automaticDiscovery")).get("scanStatus"));
            final String fallbackProbeStop=receipts.stream().map(this::probeReason).filter(value->!value.isEmpty()).findFirst().orElse("");
            final boolean incomplete=partial;change(id,row->{String probeStop=continuing?cycleProbeStop:firstProbeReason(row.getManifestJson(),fallbackProbeStop);row.setStatus(!probeStop.isEmpty()?"PARTIAL":row.isCancelRequested()?"CANCELLED":incomplete?"PARTIAL":"COMPLETED");row.setPhase("READY_FOR_REVIEW");row.setCurrentFile(null);
                row.setMessage(!probeStop.isEmpty()?"验收探针发现明确缺陷，已停止并保存已有结果；未派发事项保留待处理。原因："+probeStop:row.isCancelRequested()?"已停止，已识别的图谱结果已保存；未执行项和未决事项继续待核对。":incomplete?"可用图谱已保存；部分来源或关系仍需人工核对。确认变量定义后可单独提取项目候选。":"模板识别结果已保存。请先核对变量定义，再单独提取项目候选；不会自动采用。");});
        }catch(Throwable failure){
            if(failure instanceof VirtualMachineError){try{fail(id,"INTERRUPTED","识别任务因资源错误中断；已保存的分项结果保留，未知调用不会重发。");}finally{throw (VirtualMachineError)failure;}}
            fail(id,cancelled(id)?"CANCELLED":failure instanceof InterruptedException?"INTERRUPTED":"FAILED",PublicErrorMessages.from(failure,"识别任务未完成；已保存的分项结果保留，请核对来源与服务状态。"));
        }
    }

    private String groupManifest(DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> groups){Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("execution",GROUP_EXECUTION);manifest.put("plan",plan.descriptor());manifest.put("groups",groups);manifest.put("concurrency",groupConcurrency);return JsonUtils.write(manifest);}
    private List<Map<String,Object>> executionGroups(String existing,DraftAutomaticDiscovery.Plan plan){return executionGroups(existing,plan,false);}
    private List<Map<String,Object>> executionGroups(String existing,DraftAutomaticDiscovery.Plan plan,boolean continuing){
        String previous=existing;existing=executionManifest(existing);if(!hasExecutionPlan(existing))existing=null;
        if(existing!=null&&sameExecutionPlan(existing,executionManifest(preserveFirstProbeFailure(JsonUtils.write(plan.descriptor()),previous))))return null;
        List<Map<String,Object>> groups=DraftAutomaticDiscovery.planGroups(plan);
        String current=executionManifest(preserveFirstProbeFailure(groupManifest(plan,groups),previous));
        if(existing!=null&&!sameExecutionPlan(existing,current)&&!(continuing&&savedPlanCompatible(existing,current)))throw conflict("来源、分组、模型执行配置或业务根定义／核心状态已变化，不能续用旧计划；处理节点的人工修改仍保留，已发送调用不会重发。");
        Set<Object> covered=new HashSet<>();for(Map<String,Object> group:groups)for(Object id:list(group.get("taskIds")))if(!covered.add(id))throw conflict("分组来源任务重复，不能开始识别。");
        Set<Object> expected=new HashSet<>();for(Map<String,Object> task:plan.tasks())expected.add(task.get("taskId"));if(!expected.equals(covered))throw conflict("分组来源覆盖不完整，不能开始识别。");
        return groups;
    }

    /** Workers receive immutable run identity, never coordinator-owned JPA entities. */
    private static final class RunBinding {
        final String id,projectId,profileId,configurationSha256,currentRevision,draftRevision,sourceStamp;
        final Set<String> protectedSources;
        final long createdAt;
        RunBinding(DraftAutomationRun run){id=run.getId();projectId=run.getProjectId();profileId=run.getProfileId();configurationSha256=run.getConfigurationSha256();currentRevision=run.getExpectedCurrentRevisionId();draftRevision=run.getExpectedDraftRevisionId();sourceStamp=run.getSourceStampJson();protectedSources=protectedSources(run.getManifestJson());createdAt=run.getCreatedAt().toEpochMilli();}
    }
    private static final class GroupCall {
        final Map<String,Object> group;final int reserved;final AtomicInteger dispatched=new AtomicInteger();
        Future<Map<String,Object>> future;
        GroupCall(Map<String,Object> group,int reserved){this.group=group;this.reserved=reserved;}
    }
    private static int reserveGroupCalls(AtomicInteger charged){for(;;){int prior=charged.get(),amount=Math.min(DraftAutomaticDiscovery.GROUP_MAX_CALLS,MAX_CALLS-prior);if(amount<=0)return 0;if(charged.compareAndSet(prior,prior+amount))return amount;}}

    /** Saving a discovered root changes only its existence flag, never its definition or source identity. */
    static boolean savedPlanCompatible(String previous,String current){
        JsonNode before=JsonUtils.parse(previous),after=JsonUtils.parse(current);upgradeLegacyPlanSources(before,after);if(!GROUP_EXECUTION.equals(before.path("execution").asText())||!before.path("plan").path("knownRoots").isArray()||!after.path("plan").path("knownRoots").isArray())return false;
        JsonNode oldRoots=before.path("plan").path("knownRoots"),newRoots=after.path("plan").path("knownRoots");if(oldRoots.size()!=newRoots.size())return false;
        for(int i=0;i<oldRoots.size();i++){JsonNode old=oldRoots.path(i),next=newRoots.path(i);if(old.path("existing").isBoolean()&&!old.path("existing").asBoolean()&&next.path("existing").isBoolean()&&next.path("existing").asBoolean())((com.fasterxml.jackson.databind.node.ObjectNode)next).put("existing",false);}
        return before.equals(after);
    }
    static boolean sameExecutionPlan(String previous,String current){JsonNode before=JsonUtils.parse(previous),after=JsonUtils.parse(current);upgradeLegacyPlanSources(before,after);return before.equals(after);}
    private static void upgradeLegacyPlanSources(JsonNode before,JsonNode after){
        if(before.has("sourceSelection")||after.has("sourceSelection"))return;JsonNode old=before.has("plan")?before.path("plan"):before,next=after.has("plan")?after.path("plan"):after;if(old.has("sourceIds")||!next.path("sourceIds").isArray()||!old.path("coverage").path("sources").isArray())return;Set<String> sources=new TreeSet<>(),selected=new TreeSet<>();for(JsonNode source:old.path("coverage").path("sources"))sources.add(source.path("sourceId").asText());for(JsonNode source:next.path("sourceIds"))selected.add(source.asText());if(sources.equals(selected))((com.fasterxml.jackson.databind.node.ObjectNode)old).set("sourceIds",next.path("sourceIds").deepCopy());
    }
    static Set<String> continuationTasks(String manifest){Set<String> result=new LinkedHashSet<>();if(manifest!=null)for(Object id:list(map(JsonUtils.readMap(manifest).get("continuation")).get("taskIds")))result.add(text(id));return result;}
    private static String continuationStop(String manifest){Map<String,Object> cycle=map(JsonUtils.readMap(manifest).get("continuation"));List<String> reasons=new ArrayList<>();for(Object value:list(map(cycle.get("stop")).get("reasons")))reasons.add(text(value));return String.join(", ",reasons);}
    static boolean continuationProbeStop(DraftAutomationRun row){
        if(row.getManifestJson()==null)return false;Map<String,Object> manifest=JsonUtils.readMap(row.getManifestJson()),cycle=map(manifest.get("continuation"));if(!cycle.isEmpty())return !map(cycle.get("stop")).isEmpty();
        Map<String,Object> completed=map(manifest.get("lastContinuation"));return row.getResultRevisionId()!=null&&row.getResultRevisionId().equals(completed.get("resultRevisionId"))&&!map(completed.get("stop")).isEmpty();
    }
    private void includeContinuationTasks(String id,List<Map<String,Object>> tasks){
        Set<String> requested=new HashSet<>();for(Map<String,Object> task:tasks)requested.add(text(task.get("taskId")));
        change(id,row->{Map<String,Object> manifest=JsonUtils.readMap(row.getManifestJson()),cycle=map(manifest.get("continuation"));if(cycle.isEmpty())throw conflict("续跑写入范围记录缺失。");Set<String> delta=new LinkedHashSet<>(continuationTasks(row.getManifestJson()));for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(id))if(requested.contains(item.getTaskId())&&"PENDING".equals(item.getStatus()))delta.add(item.getTaskId());cycle.put("taskIds",new ArrayList<>(delta));row.setManifestJson(JsonUtils.write(manifest));});
    }
    private void requireResultAncestor(String projectId,String current,String result){
        Set<String> visited=new HashSet<>();for(int depth=0;current!=null&&depth<256&&visited.add(current);depth++){
            if(current.equals(result))return;List<String> parents=entityManager.createQuery("select r.parentRevisionId from DraftTemplateGraphRevision r where r.id = :id and r.projectId = :project",String.class).setParameter("id",current).setParameter("project",projectId).getResultList();if(parents.size()!=1)break;current=parents.get(0);
        }throw conflict("当前图谱不是本任务结果的后续保存版本，不能续用旧任务。");
    }

    private boolean processGroups(DraftAutomationRun run,LlmOperation selected,DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> groups,List<Map<String,Object>> receipts)throws InterruptedException {
        RunBinding binding=new RunBinding(run);Map<String,DraftAutomationItem> saved=new HashMap<>();for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(run.getId()))saved.put(item.getTaskId(),item);
        AtomicInteger charged=new AtomicInteger(budgetCalls(receipts));BlockingQueue<Future<Map<String,Object>>> finished=new LinkedBlockingQueue<>();CompletionService<Map<String,Object>> completion=new ExecutorCompletionService<>(groupWorkers,finished);Map<Future<Map<String,Object>>,GroupCall> active=new LinkedHashMap<>();int next=0,processed=0;boolean stopped=false,interrupted=false;RuntimeException failure=null;Error fatal=null;
        while(next<groups.size()||!active.isEmpty()){
            while(!stopped&&failure==null&&fatal==null&&next<groups.size()&&active.size()<groupConcurrency){
                Map<String,Object> group=groups.get(next++);DraftAutomationItem item=saved.get(text(group.get("taskId")));if(item==null){failure=conflict("分组任务记录不存在。");break;}if(!"PENDING".equals(item.getStatus()))continue;
                if(stopping||cancelled(binding.id)||Thread.currentThread().isInterrupted()||System.currentTimeMillis()-binding.createdAt>=LIFETIME||processed+list(group.get("taskIds")).size()>MAX_TASKS){stopped=true;break;}
                try{
                    guard(binding,false);int allowance=reserveGroupCalls(charged);if(allowance==0){if(active.isEmpty())stopped=true;else next--;break;}
                    phase(binding.id,"DISCOVERING_VARIABLES",null,"按固定变量和分支分组识别；组内按需补证，保留每条原始来源的覆盖记录。");
                    GroupCall call=new GroupCall(group,allowance);markGroupInFlight(call,saved);processed+=list(group.get("taskIds")).size();
                    try{call.future=completion.submit(()->inferGroup(binding,selected,plan,call));}
                    catch(RejectedExecutionException rejected){call.future=CompletableFuture.completedFuture(missingReceipt(group,"分组执行队列已关闭，本组未发送。"));finished.add(call.future);stopped=true;}
                    active.put(call.future,call);
                }catch(RuntimeException invalid){failure=invalid;}
            }
            if(active.isEmpty())break;
            Future<Map<String,Object>> ready;for(;;)try{ready=completion.take();break;}catch(InterruptedException stop){interrupted=true;stopped=true;}
            GroupCall call=active.remove(ready);Map<String,Object> receipt;
            for(;;)try{receipt=call.future.get();break;}catch(InterruptedException stop){interrupted=true;stopped=true;}catch(ExecutionException broken){
                Throwable cause=broken.getCause();receipt=missingReceipt(call.group,"分组执行中断，调用结果未知，不自动重发。");receipt.put("status","unknown");receipt.put("reservedModelCalls",call.reserved);if(cause instanceof Error)fatal=(Error)cause;else failure=new IllegalStateException("分组执行中断。",cause);break;
            }catch(CancellationException cancelled){receipt=missingReceipt(call.group,"分组执行中断，调用结果未知，不自动重发。");receipt.put("status","unknown");receipt.put("reservedModelCalls",call.reserved);stopped=true;break;}
            receipt.put("modelCalls",call.dispatched.get());
            try{persistGroupReceipt(binding.id,call.group,receipt,saved,receipts);if(!receipt.containsKey("reservedModelCalls"))charged.addAndGet(call.dispatched.get()-call.reserved);if(stopForProbe(binding.id,receipt))stopped=true;}
            catch(RuntimeException persistence){if(failure==null)failure=persistence;}
        }
        if(fatal!=null)throw fatal;if(failure!=null)throw failure;if(interrupted)throw new InterruptedException("分组识别已中断，已发送的结果已收集。");return stopped;
    }

    private void markGroupInFlight(GroupCall call,Map<String,DraftAutomationItem> saved){
        tx.executeWithoutResult(s->{DraftAutomationItem group=saved.get(text(call.group.get("taskId")));Map<String,Object> pending=new LinkedHashMap<>(call.group);pending.put("status","in_flight");pending.put("modelCalls",0);pending.put("reservedModelCalls",call.reserved);group.setStatus("IN_FLIGHT");group.setReceiptJson(JsonUtils.write(pending));items.save(group);
            for(Object taskId:list(call.group.get("taskIds"))){DraftAutomationItem child=saved.get(text(taskId));if(child==null||!"PENDING".equals(child.getStatus()))throw conflict("分组来源任务状态不一致，不能重发。");Map<String,Object> receipt=new LinkedHashMap<>();receipt.put("taskId",taskId);receipt.put("groupReceiptId",call.group.get("taskId"));receipt.put("owningTaskId",taskId);receipt.put("modelCalls",0);receipt.put("status","in_flight");child.setStatus("IN_FLIGHT");child.setReceiptJson(JsonUtils.write(receipt));items.save(child);}items.flush();
        });
    }
    private Map<String,Object> inferGroup(RunBinding binding,LlmOperation selected,DraftAutomaticDiscovery.Plan plan,GroupCall call){
        try(LlmProfiles.Scope ignored=profiles.bind(selected)){
            return DraftAutomaticDiscovery.processGroup((system,user)->{if(call.dispatched.get()>=call.reserved)throw conflict("识别调用预算已达到上限；未完成事项保留待核对。");return complete(binding,"discover-group",system,user,call.dispatched::incrementAndGet);},plan,call.group,acceptanceProbeStopOnDefect);
        }catch(RuntimeException failure){return missingReceipt(call.group,PublicErrorMessages.from(failure,"本组未完成，其他完整结果继续保留。"));}
    }
    private void persistGroupReceipt(String id,Map<String,Object> group,Map<String,Object> receipt,Map<String,DraftAutomationItem> saved,List<Map<String,Object>> receipts){
        Map<String,Map<String,Object>> children=new LinkedHashMap<>();for(Object raw:list(receipt.get("taskReceipts"))){Map<String,Object> child=map(raw);String taskId=text(child.get("taskId"));if(!list(group.get("taskIds")).contains(taskId)||children.put(taskId,new LinkedHashMap<>(child))!=null)throw conflict("分组回执来源身份不一致。");}
        Map<String,Object> groupReceipt=new LinkedHashMap<>(receipt);groupReceipt.remove("taskReceipts");List<Map<String,Object>> ordered=new ArrayList<>();
        for(Object taskId:list(group.get("taskIds"))){DraftAutomationItem item=saved.get(text(taskId));Map<String,Object> child=children.get(text(taskId));if(child==null){child=missingReceipt(JsonUtils.readMap(item.getTaskJson()),"本组未返回此来源的完整结果。");if("unknown".equals(receipt.get("status")))child.put("status","unknown");}child.put("groupReceiptId",group.get("taskId"));child.put("owningTaskId",taskId);child.put("modelCalls",0);for(String raw:Arrays.asList("rawResponse","systemPrompt","userPrompt","retainedResponse","providerResponse","evidenceRounds"))child.remove(raw);ordered.add(child);}
        tx.executeWithoutResult(s->{DraftAutomationItem owner=saved.get(text(group.get("taskId")));owner.setReceiptJson(JsonUtils.write(groupReceipt));owner.setStatus("unknown".equals(groupReceipt.get("status"))?"UNKNOWN":"DONE");items.save(owner);for(Map<String,Object> child:ordered){DraftAutomationItem item=saved.get(text(child.get("taskId")));item.setReceiptJson(JsonUtils.write(child));item.setStatus("unknown".equals(child.get("status"))?"UNKNOWN":"DONE");items.save(item);}items.flush();});
        receipts.addAll(ordered);receipts.add(groupReceipt);receipts.sort(Comparator.comparingInt(value->saved.get(text(value.get("taskId"))).getTaskOrder()));updateCounts(id,receipts);
    }

    private boolean processTasks(DraftAutomationRun run,DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> tasks,List<Map<String,Object>> receipts,boolean relations,String selectedSccId) {
        Map<String,DraftAutomationItem> saved=new HashMap<>();for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(run.getId()))saved.put(item.getTaskId(),item);
        int processed=0;
        for(Map<String,Object> task:tasks) {
            DraftAutomationItem item=saved.get(text(task.get("taskId")));if(item==null)break;
            if(!"PENDING".equals(item.getStatus()))continue;
            if(cancelled(run.getId())||Thread.currentThread().isInterrupted()||System.currentTimeMillis()-run.getCreatedAt().toEpochMilli()>=LIFETIME)return true;
            if(processed++>=MAX_TASKS||budgetCalls(receipts)>=MAX_CALLS)return true;
            guard(run,false);
            phase(run.getId(),relations?"LINKING_GRAPH":"DISCOVERING_VARIABLES",text(task.get("fileName")),relations?"检查跨事项关系并建议对应固定变量，供人工核对；不凭同名自动合并。":"依据OER已识别的指导说明、修改标记及控制范围，归纳变量和原子项。");
            tx.executeWithoutResult(s->{item.setStatus("IN_FLIGHT");items.saveAndFlush(item);});
            Map<String,Object> receipt;
            final int priorCalls=budgetCalls(receipts);final int[] attempted={0};
            try{
                java.util.function.BiFunction<String,String,String> completion=(system,user)->{if(priorCalls+attempted[0]>=MAX_CALLS)throw conflict("识别调用预算已达到上限；未完成事项保留待核对。");return complete(run,relations?"link":"discover",system,user,()->attempted[0]++);};
                receipt=relations?DraftAutomaticDiscovery.processRelation(completion,plan,receipts,task,pdfReference,selectedSccId):DraftAutomaticDiscovery.process(completion,plan,task,acceptanceProbeStopOnDefect);
            }catch(RuntimeException callFailure){receipt=missingReceipt(task,PublicErrorMessages.from(callFailure,"本项未完成，完整可用的其他项继续保留。"));}
            receipt.put("modelCalls",attempted[0]);
            item.setReceiptJson(JsonUtils.write(receipt));item.setStatus("DONE");tx.executeWithoutResult(s->items.saveAndFlush(item));receipts.add(receipt);updateCounts(run.getId(),receipts);if(stopForProbe(run.getId(),receipt))return true;
        }
        return false;
    }

    /** Meter the real existing gateway dispatch, after OER context preparation passes its guards. */
    private String complete(DraftAutomationRun run,String phase,String system,String user,Runnable onDispatch) {
        return complete(new RunBinding(run),phase,system,user,onDispatch);
    }
    private String complete(RunBinding run,String phase,String system,String user,Runnable onDispatch) {
        guard(run,false);if(stopping||Thread.currentThread().isInterrupted()||System.currentTimeMillis()-run.createdAt>=LIFETIME)throw conflict("识别任务已中断或到达时限。");QwenGenerationLedger.Reservation reservation=null;
        if(Arrays.asList("qwen","qwen-flash").contains(run.profileId)){reservation=ledger.reserve("draft:"+run.id,run.configurationSha256,phase,DraftAdoption.hash(system+"\n"+user),true);ledger.dispatch(reservation);}
        try{onDispatch.run();String raw=ai.complete(system,user);if(reservation!=null)ledger.complete(reservation,Collections.singletonMap("responseSha256",DraftAdoption.hash(raw)));return raw;}
        catch(RuntimeException failure){recordFailure(reservation,failure);throw failure;}
    }
    private void recordFailure(QwenGenerationLedger.Reservation reservation,RuntimeException failure) {
        if(reservation==null)return;
        if(failure instanceof IncompleteModelResponseException&&((IncompleteModelResponseException)failure).getRawResponse()!=null)
            ledger.complete(reservation,Collections.singletonMap("responseSha256",DraftAdoption.hash(((IncompleteModelResponseException)failure).getRawResponse())));
        else ledger.unknown(reservation,failure.getClass().getSimpleName());
    }

    private void ensureItems(String runId,List<Map<String,Object>> tasks) {
        tx.executeWithoutResult(s->{List<DraftAutomationItem> old=items.findByRunIdOrderByTaskOrderAsc(runId);Set<String> seen=new HashSet<>();for(DraftAutomationItem item:old)seen.add(item.getTaskId());int order=old.size();
            for(Map<String,Object> task:tasks)if(seen.add(text(task.get("taskId")))){DraftAutomationItem item=new DraftAutomationItem();item.setId(UUID.randomUUID().toString());item.setRunId(runId);item.setTaskId(text(task.get("taskId")));item.setTaskOrder(order++);item.setStatus("PENDING");item.setTaskJson(JsonUtils.write(task));items.save(item);}
        });
    }
    private List<Map<String,Object>> receipts(String id){List<Map<String,Object>> result=new ArrayList<>();for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(id))if(item.getReceiptJson()!=null)result.add(JsonUtils.readMap(item.getReceiptJson()));return result;}
    private static int budgetCalls(List<Map<String,Object>> receipts){int total=0;for(Map<String,Object> receipt:receipts)total+=Math.max(number(receipt.get("reservedModelCalls")),receipt.get("modelCalls") instanceof Number?number(receipt.get("modelCalls")):"unknown".equals(receipt.get("status"))?1:0);return total;}
    private static int number(Object value){return value instanceof Number?Math.max(0,((Number)value).intValue()):0;}
    private static boolean isGroup(Map<String,Object> receipt){return GROUP_PHASE.equals(receipt.get("phase"));}
    private static Map<String,Object> missingReceipt(Map<String,Object> task,String reason){Map<String,Object> receipt=new LinkedHashMap<>(task);receipt.put("status","failed");receipt.put("reason",reason);receipt.put("nodes",new ArrayList<>());receipt.put("requirements",new ArrayList<>());receipt.put("relations",new ArrayList<>());receipt.put("issues",Collections.singletonList(Collections.singletonMap("reason",reason)));return receipt;}
    private String probeReason(Map<String,Object> receipt){
        if(!acceptanceProbeStopOnDefect)return "";List<String> reasons=probeReasons(receipt);return String.join(", ",reasons.subList(0,Math.min(3,reasons.size())));
    }
    private boolean stopForProbe(String id,Map<String,Object> receipt){
        String reason=probeReason(receipt);if(reason.isEmpty())return false;change(id,row->{
            Map<String,Object> manifest=row.getManifestJson()==null?new LinkedHashMap<>():JsonUtils.readMap(row.getManifestJson());
            // Completion order is lost when receipts are sorted by task order; record the first stop under the run lock.
            if(!manifest.containsKey("firstProbeFailure")){manifest.put("firstProbeFailure",DraftReviewGraph.obj("taskId",receipt.get("taskId"),"phase",receipt.get("phase"),"reasons",probeReasons(receipt),"at",Instant.now().toString()));row.setManifestJson(JsonUtils.write(manifest));}
            Map<String,Object> cycle=map(manifest.get("continuation"));if(!cycle.isEmpty()&&!cycle.containsKey("stop")){cycle.put("stop",DraftReviewGraph.obj("taskId",receipt.get("taskId"),"reasons",probeReasons(receipt),"at",Instant.now().toString()));row.setManifestJson(JsonUtils.write(manifest));}
            row.setCancelRequested(true);row.setMessage("验收探针发现明确缺陷，停止继续派发并保存已有结果。原因："+(cycle.isEmpty()?firstProbeReason(row.getManifestJson(),reason):continuationStop(row.getManifestJson())));
        });return true;
    }
    private static List<String> probeReasons(Map<String,Object> receipt){List<String> reasons=new ArrayList<>();for(Object value:list(receipt.get("stopReasons")))if(value!=null&&!text(value).isEmpty()&&!reasons.contains(text(value)))reasons.add(text(value));if(reasons.isEmpty()&&"unknown".equals(receipt.get("status")))reasons.add("PROVIDER_RESULT_UNKNOWN");return reasons;}
    static String firstProbeReason(String manifest,String fallback){if(manifest==null)return fallback;List<?> reasons=list(map(JsonUtils.readMap(manifest).get("firstProbeFailure")).get("reasons"));List<String> shown=new ArrayList<>();for(Object value:reasons)if(value!=null&&!text(value).isEmpty()&&shown.size()<3)shown.add(text(value));return shown.isEmpty()?fallback:String.join(", ",shown);}
    static String executionManifest(String manifest){if(manifest==null)return null;Map<String,Object> execution=JsonUtils.readMap(manifest);boolean changed=false;for(String key:Arrays.asList("firstProbeFailure","continuation","lastContinuation"))changed|=execution.remove(key)!=null;return changed?JsonUtils.write(execution):manifest;}
    static String preserveFirstProbeFailure(String manifest,String previous){if(previous==null)return manifest;Map<String,Object> saved=JsonUtils.readMap(previous),updated=JsonUtils.readMap(manifest);for(String key:Arrays.asList("firstProbeFailure","continuation","lastContinuation","sourceSelection"))if(saved.containsKey(key))updated.put(key,saved.get(key));return JsonUtils.write(updated);}
    static boolean hasExecutionPlan(String manifest){if(manifest==null)return false;Map<String,Object> saved=JsonUtils.readMap(manifest);return saved.containsKey("execution")||saved.containsKey("tasks")||saved.containsKey("completeBaselineId");}
    static Set<String> selectedSources(String manifest){return sourceSelectionIds(manifest,"sourceIds");}
    static Set<String> protectedSources(String manifest){Set<String> protectedIds=sourceSelectionIds(manifest,"protectedSourceIds");if(protectedIds!=null&&!protectedIds.containsAll(selectedSources(manifest)))throw conflict("识别任务的来源保护范围缺失。");return protectedIds;}
    private static Set<String> sourceSelectionIds(String manifest,String field){if(manifest==null)return null;Map<String,Object> selection=map(JsonUtils.readMap(manifest).get("sourceSelection"));if(selection.isEmpty())return null;if(number(selection.get("version"))!=1)throw conflict("识别任务的来源选择版本不受支持。");Set<String> ids=new TreeSet<>();for(Object raw:list(selection.get(field))){String id=text(raw);if(!(raw instanceof String)||!id.matches("[1-9][0-9]*")||!ids.add(id))throw conflict("识别任务的来源选择记录无效。");try{Long.parseLong(id);}catch(NumberFormatException invalid){throw conflict("识别任务的来源选择记录无效。");}}if(ids.isEmpty())throw conflict("识别任务的来源选择记录缺失。");return Collections.unmodifiableSet(ids);}
    static Set<String> selectSources(List<Object[]> metadata,List<String> requested){
        Set<String> eligible=new TreeSet<>();for(Object[] row:metadata)if(row.length>=6&&"STANDARD_TEMPLATE".equals(row[1])&&Arrays.asList("NTT","SCT","SCC").contains(row[2])&&Arrays.asList("PARSED","PARTIAL").contains(row[4])&&text(row[3])!=null&&text(row[3]).matches("(?i)[0-9a-f]{64}")&&row[5] instanceof String&&!((String)row[5]).trim().isEmpty())eligible.add(text(row[0]));
        if(requested==null){if(eligible.isEmpty())throw new BizException(4007,"请先上传并识别模板文件，再开始识别变量。");return eligible;}
        Set<String> work=new TreeSet<>();if(requested.isEmpty()||requested.size()>MAX_TASKS)throw new BizException(4007,"AUTOMATION_SOURCE_SELECTION_INVALID");for(String id:requested){if(id==null||!id.matches("[1-9][0-9]*")||!work.add(id)||!eligible.contains(id))throw new BizException(4007,"所选来源不属于当前项目的已识别模板："+id);}return work;
    }
    private void updateCounts(String id,List<Map<String,Object>> receipts){change(id,row->{int completed=0,failed=0,unresolved=0,nodes=0,requirements=0;for(Map<String,Object> receipt:receipts){if(isGroup(receipt))continue;completed++;String status=text(receipt.get("status"));if(Arrays.asList("failed","blocked").contains(status))failed++;if(!Arrays.asList("completed","reviewed_no_requirement").contains(status))unresolved++;nodes+=list(receipt.get("nodes")).size();requirements+=list(receipt.get("requirements")).size();}row.setCompletedItems(completed);row.setFailedItems(failed);row.setUnresolvedItems(unresolved);row.setRequirementsCount(requirements);row.setNodesCount(nodes);});}
    private void guard(DraftAutomationRun run,boolean allowCancel){guard(new RunBinding(run),allowCancel);}
    private void guard(RunBinding run,boolean allowCancel){projects.require(run.projectId);if(!allowCancel&&cancelled(run.id))throw conflict("任务已请求停止。");checkHead(run.projectId,run.currentRevision,run.draftRevision);if(!Objects.equals(run.sourceStamp,stamp(run.projectId,run.protectedSources)))throw conflict("识别期间文件已变化，已停止，避免覆盖新的来源或人工修改。");}
    private boolean cancelled(String id){return runs.findById(id).map(DraftAutomationRun::isCancelRequested).orElse(true);}
    private void checkHead(String projectId,String expectedCurrent,String expectedDraft){DraftTemplateGraphHead head=heads.findById(projectId).orElseGet(DraftTemplateGraphHead::new);if(!Objects.equals(expectedCurrent,head.getCurrentRevisionId())||!Objects.equals(expectedDraft,head.getDraftRevisionId()))throw conflict("图谱已被修改，请刷新后重新识别；原有人工结果保留。");}
    private String stamp(String projectId){return JsonUtils.write(sources.findDraftAutomationStamp(projectId));}
    private String stamp(String projectId,Set<String> protectedIds){if(protectedIds==null)return stamp(projectId);List<Long> ids=new ArrayList<>();for(String id:protectedIds)ids.add(Long.valueOf(id));List<Object[]> rows=sources.findDraftAutomationStamp(projectId,ids);if(rows.size()!=ids.size())throw conflict("所选来源或已有图谱依赖的原件已不存在。");return JsonUtils.write(rows);}
    private void lockProject(String projectId){if(entityManager.find(Project.class,projectId,LockModeType.PESSIMISTIC_WRITE)==null)throw conflict("项目已不存在。");}
    private DraftAutomationRun require(String projectId,String id){return runs.findById(id).filter(row->projectId.equals(row.getProjectId())).orElseThrow(()->new BizException(4011,"识别任务不存在。"));}
    private void requireProfile(LlmOperation selected){if(selected==null||selected.getUnavailableReason()!=null)throw new BizException(5001,"请选择已配置的可用模型后开始变量识别。");}
    private void change(String id,Consumer<DraftAutomationRun> update){tx.executeWithoutResult(s->{DraftAutomationRun row=runs.lockRun(id).orElse(null);if(row==null)return;update.accept(row);row.setUpdatedAt(Instant.now());runs.save(row);});}
    private void phase(String id,String phase,String file,String message){change(id,row->{row.setPhase(phase);row.setCurrentFile(file);row.setMessage(message);});}
    private void fail(String id,String status,String message){change(id,row->{row.setStatus(row.getResultRevisionId()!=null&&!"CANCELLED".equals(status)?"PARTIAL":status);row.setMessage(message);});}
    private boolean isQwen(DraftAutomationRun run){return Arrays.asList("qwen","qwen-flash").contains(run.getProfileId());}
    private Map<String,Object> view(DraftAutomationRun row){Map<String,Object> out=new LinkedHashMap<>();out.put("id",row.getId());out.put("projectId",row.getProjectId());out.put("status",row.getStatus());out.put("phase",row.getPhase());out.put("message",row.getMessage());out.put("currentFile",row.getCurrentFile());out.put("completedItems",row.getCompletedItems());out.put("totalItems",row.getTotalItems());out.put("failedItems",row.getFailedItems());out.put("unresolvedItems",row.getUnresolvedItems());out.put("requirementsCount",row.getRequirementsCount());out.put("nodesCount",row.getNodesCount());out.put("resultRevisionId",row.getResultRevisionId());out.put("updatedAt",row.getUpdatedAt());
        Map<String,Object> manifest=row.getManifestJson()==null?Collections.emptyMap():JsonUtils.readMap(row.getManifestJson());Set<String> sourceIds=selectedSources(row.getManifestJson());if(sourceIds!=null)out.put("sourceIds",new ArrayList<>(sourceIds));if(GROUP_EXECUTION.equals(manifest.get("execution"))){Set<String> groups=new HashSet<>();for(Object group:list(manifest.get("groups")))groups.add(text(map(group).get("taskId")));int completed=0,inFlight=0,pending=0;
            // Poll only group states; loading receipt LOBs here would copy every model response on each UI refresh.
            if(!groups.isEmpty())for(Object[] count:entityManager.createQuery("select i.status, count(i) from DraftAutomationItem i where i.runId = :runId and i.taskId in :taskIds group by i.status",Object[].class).setParameter("runId",row.getId()).setParameter("taskIds",groups).getResultList()){if(Arrays.asList("DONE","UNKNOWN").contains(count[0]))completed+=number(count[1]);if("IN_FLIGHT".equals(count[0]))inFlight+=number(count[1]);if("PENDING".equals(count[0]))pending+=number(count[1]);}
            Map<String,Object> progress=new LinkedHashMap<>();progress.put("total",groups.size());progress.put("completed",completed);progress.put("inFlight",inFlight);progress.put("pending",pending);progress.put("concurrency",manifest.get("concurrency"));progress.put("sourceTasksTotal",list(map(manifest.get("plan")).get("tasks")).size());out.put("groupProgress",progress);
            out.put("canContinueSavedGroups",row.getResultRevisionId()!=null&&Arrays.asList("PARTIAL","INTERRUPTED").contains(row.getStatus())&&System.currentTimeMillis()-row.getCreatedAt().toEpochMilli()<LIFETIME&&(pending>0||!map(manifest.get("continuation")).isEmpty()));}return out;}
    private static BizException conflict(String message){return new BizException(4090,message);}
    private static String text(Object value){return value==null?null:String.valueOf(value);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    private static List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
}
