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
import java.util.function.Consumer;
import javax.annotation.PreDestroy;
import javax.persistence.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Single bounded worker, durable delta receipts, one graph publication, no automatic paid replay. */
@Service
public class DraftAutomationService {
    private static final List<String> ACTIVE=Arrays.asList("QUEUED","RUNNING");
    private static final int MAX_TASKS=512,MAX_CALLS=768;
    private static final long LIFETIME=21600000L;
    private final DraftAutomationRunRepository runs;
    private final DraftAutomationItemRepository items;
    private final DraftTemplateGraphHeadRepository heads;
    private final SourceDocumentRepository sources;
    private final DraftTemplateGraphService graphs;
    private final ProjectService projects;
    private final LlmProfiles profiles;
    private final AiGateway ai;
    private final QwenGenerationLedger ledger;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"draft-variable-graph");t.setDaemon(true);return t;});

    public DraftAutomationService(DraftAutomationRunRepository runs,DraftAutomationItemRepository items,
            DraftTemplateGraphHeadRepository heads,SourceDocumentRepository sources,DraftTemplateGraphService graphs,
            ProjectService projects,LlmProfiles profiles,AiGateway ai,QwenGenerationLedger ledger,
            EntityManager entityManager,PlatformTransactionManager manager) {
        this.runs=runs;this.items=items;this.heads=heads;this.sources=sources;this.graphs=graphs;
        this.projects=projects;this.profiles=profiles;this.ai=ai;this.ledger=ledger;this.entityManager=entityManager;
        tx=new TransactionTemplate(manager);
    }

    public Map<String,Object> start(String projectId,String expectedCurrent,String expectedDraft) {
        LlmOperation selected=profiles.capture();requireProfile(selected);
        DraftAutomationRun run=tx.execute(s->{
            lockProject(projectId);
            List<DraftAutomationRun> active=runs.findByProjectIdAndStatusIn(projectId,ACTIVE);
            if(!active.isEmpty())return active.get(0);
            checkHead(projectId,expectedCurrent,expectedDraft);
            DraftAutomationRun row=new DraftAutomationRun();row.setId(UUID.randomUUID().toString());row.setProjectId(projectId);
            row.setStatus("QUEUED");row.setPhase("PREPARING_SOURCES");row.setMessage("已排队，准备读取已识别模板的完整来源。");
            row.setProfileId(selected.getIdentity().getProfileId());row.setConfigurationSha256(selected.getIdentity().getConfigurationSha256());
            row.setExpectedCurrentRevisionId(expectedCurrent);row.setExpectedDraftRevisionId(expectedDraft);
            row.setSourceStampJson(stamp(projectId));row.setCreatedAt(Instant.now());row.setUpdatedAt(row.getCreatedAt());return runs.saveAndFlush(row);
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
    public Map<String,Object> cancel(String projectId,String id) {
        require(projectId,id);
        change(id,row->{if(ACTIVE.contains(row.getStatus())){row.setCancelRequested(true);row.setMessage("已请求停止；当前调用返回后保留已识别结果。");}});
        return get(projectId,id);
    }
    public Map<String,Object> resume(String projectId,String id,String expectedCurrent,String expectedDraft) {
        DraftAutomationRun previous=require(projectId,id);LlmOperation selected=profiles.resolve(previous.getProfileId());requireProfile(selected);
        if(!Objects.equals(previous.getConfigurationSha256(),selected.getIdentity().getConfigurationSha256()))throw conflict("模型配置已变化，请重新开始识别。");
        tx.executeWithoutResult(s->{lockProject(projectId);DraftAutomationRun row=runs.lockRun(id).orElseThrow(()->conflict("任务不存在。"));
            if(!"INTERRUPTED".equals(row.getStatus()))throw conflict("只有中断任务可继续；已发送但结果不明的调用不会重发。");
            if(row.getResultRevisionId()!=null)throw conflict("图谱已经保存，请先核对已有结果。");
            if(!runs.findByProjectIdAndStatusIn(projectId,ACTIVE).isEmpty())throw conflict("此项目已有识别任务正在执行。");
            checkHead(projectId,expectedCurrent,expectedDraft);
            if(!Objects.equals(row.getExpectedCurrentRevisionId(),expectedCurrent)||!Objects.equals(row.getExpectedDraftRevisionId(),expectedDraft)||!Objects.equals(row.getSourceStampJson(),stamp(projectId)))throw conflict("模板或图谱已变化，不能继续旧任务。");
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
                    item.setStatus("UNKNOWN");item.setReceiptJson(JsonUtils.write(receipt));items.save(item);
                }
                row.setStatus(row.isCancelRequested()?"CANCELLED":row.getResultRevisionId()==null?"INTERRUPTED":"PARTIAL");row.setMessage(row.isCancelRequested()?"任务已停止，已保存的图谱和调用记录保留；未执行事项不会自动重发。":row.getResultRevisionId()==null?"服务重启，任务已中断；可继续未发送的事项。":"图谱已保存，后续候选提取因重启中断；请先核对现有结果。");row.setUpdatedAt(Instant.now());runs.save(row);
            });
        }
    }
    @PreDestroy public void shutdown(){worker.shutdownNow();}

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
            List<JsonNode> readings=new ArrayList<>();Map<String,Object> base=graphs.automationInput(run.getProjectId(),run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId(),readings);
            guard(run,false);phase(id,"PLANNING",null,"按模板专用OER观察建立整套变量识别任务及来源覆盖清单。");
            DraftAutomaticDiscovery.Plan plan=DraftAutomaticDiscovery.plan(readings);
            String manifest=JsonUtils.write(plan.descriptor());
            if(run.getManifestJson()!=null&&!run.getManifestJson().equals(manifest))throw conflict("来源清单或识别规则已变化，不能续用旧结果。");
            change(id,row->{row.setManifestJson(manifest);row.setTotalItems(plan.tasks().size());});
            ensureItems(id,plan.tasks());
            if(isQwen(run))ledger.open("draft:"+id,run.getConfigurationSha256(),new QwenGenerationLedger.Limits(MAX_CALLS,MAX_CALLS,MAX_CALLS,LIFETIME));
            List<Map<String,Object>> receipts=receipts(id);boolean stopped=processTasks(run,plan,plan.tasks(),receipts,false);
            if(!stopped){List<Map<String,Object>> relations=DraftAutomaticDiscovery.planRelations(plan,receipts);ensureItems(id,relations);change(id,row->row.setTotalItems(plan.tasks().size()+relations.size()));stopped=processTasks(run,plan,relations,receipts,true);}
            guard(run,true);phase(id,"SAVING_GRAPH",null,"合并完整可用的变量和关系候选，保存一份待人工核对的图谱。");
            Map<String,Object> graph=DraftAutomaticDiscovery.finish(base,plan,receipts);
            // The result handle commits with the only graph revision; a restart cannot publish it twice.
            String result=tx.execute(s->{lockProject(run.getProjectId());guard(run,true);Map<String,Object> saved=graphs.save(run.getProjectId(),graph,run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId(),true);
                DraftAutomationRun row=runs.lockRun(id).orElseThrow(IllegalStateException::new);String revision=String.valueOf(saved.get("id"));row.setResultRevisionId(revision);row.setUpdatedAt(Instant.now());runs.save(row);return revision;});
            boolean partial=!"completed".equals(map(map(graph.get("graph")).get("automaticDiscovery")).get("scanStatus"));
            if(!stopped&&!cancelled(id)) {
                phase(id,"EXTRACTING_CANDIDATES",null,"图谱已保存，正在从项目沟通资料提取可供人工核对的取值候选。");
                CandidateMonitor monitor=new CandidateMonitor(run);
                try{graphs.extract(run.getProjectId(),result,monitor);partial|=monitor.partial;}
                catch(RuntimeException candidateFailure){partial=true;change(id,row->row.setUnresolvedItems(row.getUnresolvedItems()+1));}
            }
            final boolean incomplete=partial;change(id,row->{row.setStatus(row.isCancelRequested()?"CANCELLED":incomplete?"PARTIAL":"COMPLETED");row.setPhase("READY_FOR_REVIEW");row.setCurrentFile(null);
                row.setMessage(row.isCancelRequested()?"已停止，完整可用结果已保存，未执行项保留待核对。":incomplete?"可用图谱已保存；部分来源、关系或取值仍需人工补充核对，未自动采用。":"整套模板识别与关系检查已完成，请对着原文核对变量和图谱，再选择采用。");});
        }catch(Throwable failure){
            if(failure instanceof VirtualMachineError){try{fail(id,"INTERRUPTED","识别任务因资源错误中断；已保存的分项结果保留，未知调用不会重发。");}finally{throw (VirtualMachineError)failure;}}
            fail(id,cancelled(id)?"CANCELLED":failure instanceof InterruptedException?"INTERRUPTED":"FAILED",PublicErrorMessages.from(failure,"识别任务未完成；已保存的分项结果保留，请核对来源与服务状态。"));
        }
    }

    private boolean processTasks(DraftAutomationRun run,DraftAutomaticDiscovery.Plan plan,List<Map<String,Object>> tasks,List<Map<String,Object>> receipts,boolean relations) {
        Map<String,DraftAutomationItem> saved=new HashMap<>();for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(run.getId()))saved.put(item.getTaskId(),item);
        int processed=0;
        for(Map<String,Object> task:tasks) {
            DraftAutomationItem item=saved.get(text(task.get("taskId")));if(item==null)break;
            if(!"PENDING".equals(item.getStatus()))continue;
            if(cancelled(run.getId())||Thread.currentThread().isInterrupted()||System.currentTimeMillis()-run.getCreatedAt().toEpochMilli()>=LIFETIME)return true;
            if(processed++>=MAX_TASKS||modelCalls(receipts)>=MAX_CALLS)return true;
            guard(run,false);
            phase(run.getId(),relations?"LINKING_GRAPH":"DISCOVERING_VARIABLES",text(task.get("fileName")),relations?"检查跨事项关系并建议对应固定变量，供人工核对；不凭同名自动合并。":"依据OER已识别的指导说明、修改标记及控制范围，归纳变量和原子项。");
            tx.executeWithoutResult(s->{item.setStatus("IN_FLIGHT");items.saveAndFlush(item);});
            Map<String,Object> receipt;
            try{
                receipt=relations?DraftAutomaticDiscovery.processRelation((system,user)->complete(run,"link",system,user),plan,receipts,task):DraftAutomaticDiscovery.process((system,user)->complete(run,"discover",system,user),plan,task);
            }catch(RuntimeException callFailure){receipt=missingReceipt(task,PublicErrorMessages.from(callFailure,"本项未完成，完整可用的其他项继续保留。"));}
            item.setReceiptJson(JsonUtils.write(receipt));item.setStatus("DONE");tx.executeWithoutResult(s->items.saveAndFlush(item));receipts.add(receipt);updateCounts(run.getId(),receipts);
        }
        return false;
    }

    /** Meter the real existing gateway dispatch, after OER context preparation passes its guards. */
    private String complete(DraftAutomationRun run,String phase,String system,String user) {
        guard(run,false);QwenGenerationLedger.Reservation reservation=null;
        if(isQwen(run)){reservation=ledger.reserve("draft:"+run.getId(),run.getConfigurationSha256(),phase,DraftAdoption.hash(system+"\n"+user),true);ledger.dispatch(reservation);}
        try{String raw=ai.complete(system,user);if(reservation!=null)ledger.complete(reservation,Collections.singletonMap("responseSha256",DraftAdoption.hash(raw)));return raw;}
        catch(RuntimeException failure){recordFailure(reservation,failure);throw failure;}
    }
    private void recordFailure(QwenGenerationLedger.Reservation reservation,RuntimeException failure) {
        if(reservation==null)return;
        if(failure instanceof IncompleteModelResponseException&&((IncompleteModelResponseException)failure).getRawResponse()!=null)
            ledger.complete(reservation,Collections.singletonMap("responseSha256",DraftAdoption.hash(((IncompleteModelResponseException)failure).getRawResponse())));
        else ledger.unknown(reservation,failure.getClass().getSimpleName());
    }

    private final class CandidateMonitor implements DraftTemplateGraphService.ExtractionMonitor {
        private final DraftAutomationRun run;private QwenGenerationLedger.Reservation reservation;private int calls;private final int priorCalls;private boolean partial;
        CandidateMonitor(DraftAutomationRun run){this.run=run;priorCalls=modelCalls(receipts(run.getId()));}
        public boolean beforeCall(String system,String user,String fileName) {
            if(cancelled(run.getId())||calls+priorCalls>=MAX_CALLS||System.currentTimeMillis()-run.getCreatedAt().toEpochMilli()>=LIFETIME){partial=true;return false;}
            guardPublished(run);calls++;
            phase(run.getId(),"EXTRACTING_CANDIDATES",fileName,"按完整变量组核对项目资料（第"+calls+"次候选提取调用）；等待人工采用。");
            if(isQwen(run)){reservation=ledger.reserve("draft:"+run.getId(),run.getConfigurationSha256(),"candidates",DraftAdoption.hash(system+"\n"+user),true);ledger.dispatch(reservation);}
            return true;
        }
        public void completed(String raw){if(reservation!=null){ledger.complete(reservation,Collections.singletonMap("responseSha256",DraftAdoption.hash(raw)));reservation=null;}}
        public void failed(RuntimeException error){if(reservation!=null){recordFailure(reservation,error);reservation=null;}partial=true;change(run.getId(),row->{row.setFailedItems(row.getFailedItems()+1);row.setMessage("候选提取调用失败，已保留已有结果；连续失败将停止后续调用。");});}
        public void finished(boolean partial){this.partial=partial;}
    }

    private void ensureItems(String runId,List<Map<String,Object>> tasks) {
        tx.executeWithoutResult(s->{List<DraftAutomationItem> old=items.findByRunIdOrderByTaskOrderAsc(runId);Set<String> seen=new HashSet<>();for(DraftAutomationItem item:old)seen.add(item.getTaskId());int order=old.size();
            for(Map<String,Object> task:tasks)if(seen.add(text(task.get("taskId")))){DraftAutomationItem item=new DraftAutomationItem();item.setId(UUID.randomUUID().toString());item.setRunId(runId);item.setTaskId(text(task.get("taskId")));item.setTaskOrder(order++);item.setStatus("PENDING");item.setTaskJson(JsonUtils.write(task));items.save(item);}
        });
    }
    private List<Map<String,Object>> receipts(String id){List<Map<String,Object>> result=new ArrayList<>();for(DraftAutomationItem item:items.findByRunIdOrderByTaskOrderAsc(id))if(item.getReceiptJson()!=null)result.add(JsonUtils.readMap(item.getReceiptJson()));return result;}
    private static int modelCalls(List<Map<String,Object>> receipts){int total=0;for(Map<String,Object> receipt:receipts)total+=receipt.get("modelCalls") instanceof Number?((Number)receipt.get("modelCalls")).intValue():"unknown".equals(receipt.get("status"))?1:0;return total;}
    private static Map<String,Object> missingReceipt(Map<String,Object> task,String reason){Map<String,Object> receipt=new LinkedHashMap<>(task);receipt.put("status","failed");receipt.put("reason",reason);receipt.put("nodes",new ArrayList<>());receipt.put("requirements",new ArrayList<>());receipt.put("relations",new ArrayList<>());receipt.put("issues",Collections.singletonList(Collections.singletonMap("reason",reason)));return receipt;}
    private void updateCounts(String id,List<Map<String,Object>> receipts){change(id,row->{int failed=0,unresolved=0,nodes=0,requirements=0;for(Map<String,Object> receipt:receipts){String status=text(receipt.get("status"));if(Arrays.asList("failed","blocked").contains(status))failed++;if(!Arrays.asList("completed","reviewed_no_requirement").contains(status))unresolved++;nodes+=list(receipt.get("nodes")).size();requirements+=list(receipt.get("requirements")).size();}row.setCompletedItems(receipts.size());row.setFailedItems(failed);row.setUnresolvedItems(unresolved);row.setRequirementsCount(requirements);row.setNodesCount(nodes);});}
    private void guard(DraftAutomationRun run,boolean allowCancel){projects.require(run.getProjectId());if(!allowCancel&&cancelled(run.getId()))throw conflict("任务已请求停止。");checkHead(run.getProjectId(),run.getExpectedCurrentRevisionId(),run.getExpectedDraftRevisionId());if(!Objects.equals(run.getSourceStampJson(),stamp(run.getProjectId())))throw conflict("识别期间文件已变化，已停止，避免覆盖新的来源或人工修改。");}
    private void guardPublished(DraftAutomationRun run){if(cancelled(run.getId()))throw conflict("任务已停止，图谱已保留。");DraftAutomationRun current=require(run.getProjectId(),run.getId());checkHead(run.getProjectId(),run.getExpectedCurrentRevisionId(),current.getResultRevisionId());if(!Objects.equals(run.getSourceStampJson(),stamp(run.getProjectId())))throw conflict("项目资料已变化，停止候选提取。");}
    private boolean cancelled(String id){return runs.findById(id).map(DraftAutomationRun::isCancelRequested).orElse(true);}
    private void checkHead(String projectId,String expectedCurrent,String expectedDraft){DraftTemplateGraphHead head=heads.findById(projectId).orElseGet(DraftTemplateGraphHead::new);if(!Objects.equals(expectedCurrent,head.getCurrentRevisionId())||!Objects.equals(expectedDraft,head.getDraftRevisionId()))throw conflict("图谱已被修改，请刷新后重新识别；原有人工结果保留。");}
    private String stamp(String projectId){return JsonUtils.write(sources.findDraftAutomationStamp(projectId));}
    private void lockProject(String projectId){if(entityManager.find(Project.class,projectId,LockModeType.PESSIMISTIC_WRITE)==null)throw conflict("项目已不存在。");}
    private DraftAutomationRun require(String projectId,String id){return runs.findById(id).filter(row->projectId.equals(row.getProjectId())).orElseThrow(()->new BizException(4011,"识别任务不存在。"));}
    private void requireProfile(LlmOperation selected){if(selected==null||selected.getUnavailableReason()!=null)throw new BizException(5001,"请选择已配置的可用模型后开始变量识别。");}
    private void change(String id,Consumer<DraftAutomationRun> update){tx.executeWithoutResult(s->{DraftAutomationRun row=runs.lockRun(id).orElse(null);if(row==null)return;update.accept(row);row.setUpdatedAt(Instant.now());runs.save(row);});}
    private void phase(String id,String phase,String file,String message){change(id,row->{row.setPhase(phase);row.setCurrentFile(file);row.setMessage(message);});}
    private void fail(String id,String status,String message){change(id,row->{row.setStatus(row.getResultRevisionId()!=null&&!"CANCELLED".equals(status)?"PARTIAL":status);row.setMessage(message);});}
    private boolean isQwen(DraftAutomationRun run){return Arrays.asList("qwen","qwen-flash").contains(run.getProfileId());}
    private Map<String,Object> view(DraftAutomationRun row){Map<String,Object> out=new LinkedHashMap<>();out.put("id",row.getId());out.put("projectId",row.getProjectId());out.put("status",row.getStatus());out.put("phase",row.getPhase());out.put("message",row.getMessage());out.put("currentFile",row.getCurrentFile());out.put("completedItems",row.getCompletedItems());out.put("totalItems",row.getTotalItems());out.put("failedItems",row.getFailedItems());out.put("unresolvedItems",row.getUnresolvedItems());out.put("requirementsCount",row.getRequirementsCount());out.put("nodesCount",row.getNodesCount());out.put("resultRevisionId",row.getResultRevisionId());out.put("updatedAt",row.getUpdatedAt());return out;}
    private static BizException conflict(String message){return new BizException(4090,message);}
    private static String text(Object value){return value==null?null:String.valueOf(value);}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return value instanceof Map?(Map<String,Object>)value:Collections.emptyMap();}
    private static List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
}
