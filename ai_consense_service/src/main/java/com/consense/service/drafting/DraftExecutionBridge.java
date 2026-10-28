package com.consense.service.drafting;

import com.consense.common.JsonUtils;
import com.consense.document.DocxTemplateEditor;
import com.consense.document.DocxTemplateEditor.*;
import java.util.*;
import static com.consense.service.drafting.DraftReviewGraph.*;

/** Reuses reviewed fixed rules and graph amendments against one unchanged original. */
final class DraftExecutionBridge {
    private static final String FIXED="fixed_catalog",COVERAGE="fixedRuleCoverage";
    private static final Set<String> EXPECTED_ACTIONS=expectedActions();
    private DraftExecutionBridge() { }

    /** The caller supplies the current graph plan; visible/unadopted inputValues are never projected. */
    static Map<String,Object> prepare(Map<String,Object> graph,Map<String,Object> plan) {
        List<Object> actions=new ArrayList<>(),issues=new ArrayList<>();
        for(Object raw:list(plan.get("actions")))if(!FIXED.equals(map(raw).get("executionKind")))actions.add(raw);
        for(Object raw:list(plan.get("unresolved")))if(!FIXED.equals(map(raw).get("executionKind")))issues.add(raw);
        plan.put("actions",actions);plan.put("unresolved",issues);
        Projection projection=new Projection(graph,map(plan.get("effectiveValues")));
        Map<String,Object> fixed=DraftBusinessRules.plan(projection.values);
        for(Object raw:list(fixed.get("actions"))) {
            Map<String,Object> action=map(raw);action.put("executionKind",FIXED);
            List<Object> catalogKeys=new ArrayList<>(list(action.get("inputKeys"))),nodeIds=new ArrayList<>();boolean customized=false;
            for(Object key:catalogKeys){String root=text(key);if(projection.nodeIds.containsKey(root))nodeIds.add(projection.nodeIds.get(root));else if("contractTitle".equals(root))nodeIds.add(root);if(projection.blocked.contains(root))customized=true;}
            action.put("catalogInputKeys",catalogKeys);action.put("inputKeys",nodeIds);
            if(!projection.installed)defer(action,"FixedBaselineReviewRequired");
            else if(!registeredSource(graph,text(action.get("document"))))defer(action,"FixedSourceUnverified");
            else if(customized)defer(action,"FixedDefinitionCustomized");
            actions.add(action);
        }
        for(String file:Arrays.asList("NTT","SCT","SCC"))if(registeredSource(graph,file)&&hasUnlocatedEditTarget(graph,file,DraftFormattedRules.verifiedSourceHash(file)))deferDocument(plan,file,true,"FixedGraphUnknownScope","A graph amendment target is unbound or needs rebinding in this document. Its overlap with fixed edits is unknown; fixed edits retain the original wording.");
        for(Object raw:list(fixed.get("unresolved"))) {
            Map<String,Object> issue=new LinkedHashMap<>(map(raw));List<Object> keys=new ArrayList<>();
            for(Object key:list(issue.get("inputKeys")))keys.add(projection.nodeIds.getOrDefault(text(key),key));
            issue.put("inputKeys",keys);issue.put("id","fixed-rule:"+text(issue.get("id")));issue.put("executionKind",FIXED);
            boolean actionIssue=isActionIssue(issue,fixed);if(actionIssue||issues.stream().noneMatch(existing->Objects.equals(map(existing).get("kind"),issue.get("kind"))&&Objects.equals(map(existing).get("inputKeys"),keys)&&!keys.isEmpty()))issues.add(issue);
        }
        if(!projection.installed)issue(plan,"baseline","FixedBaselineReviewRequired","",Collections.emptyList(),"The fixed-rule package is not registered for these exact selected originals; its actions remain pending.");
        for(String key:projection.blocked){List<String> affected=new ArrayList<>();for(Object raw:list(fixed.get("actions")))if(list(map(raw).get("catalogInputKeys")).contains(key))affected.add(text(map(raw).get("id")));issue(plan,"definition:"+key,"FixedDefinitionCustomized","",affected,"This fixed definition or its targets changed. Review an explicit rule replacement; the saved fixed descriptor does not authorize the changed scope.");}
        plan.put("fixedRuleValues",fixed.get("effectiveValues"));plan.put("fixedRuleDerived",fixed.get("derived"));
        plan.put(COVERAGE,obj("ruleVersion",DraftBusinessRules.RULE_VERSION,"graphHash",DraftAdoption.hash(JsonUtils.write(graph)),"adoptedValuesHash",DraftAdoption.hash(JsonUtils.write(plan.get("effectiveValues"))),"registered",projection.installed,"expectedActions",list(fixed.get("actions")).size()));
        updateCoverage(plan);return plan;
    }

    static DocxEditResult compile(byte[] original,String fileKey,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        PreparedDocument document=prepareDocument(original,fileKey,new DocxTemplateEditor().inspect(original),graph,plan,bookmarks,layout);
        return applyAll(Collections.singletonList(document),graph,plan,false).get(fileKey);
    }

    static final class PreparedDocument {
        final byte[] original;final String fileKey;final TemplateIndex index;final Map<String,String> bookmarks;final NativeLayoutPlan layout;
        final DraftFormattedRules.PreparedBatch fixed;final SourceEditBatch fixedBusiness,graphBatch,protections,business;
        PreparedDocument(byte[] original,String fileKey,TemplateIndex index,Map<String,String> bookmarks,NativeLayoutPlan layout,DraftFormattedRules.PreparedBatch fixed,SourceEditBatch fixedBusiness,SourceEditBatch graphBatch,SourceEditBatch protections,SourceEditBatch business){this.original=original;this.fileKey=fileKey;this.index=index;this.bookmarks=Collections.unmodifiableMap(new LinkedHashMap<>(bookmarks));this.layout=layout;this.fixed=fixed;this.fixedBusiness=fixedBusiness;this.graphBatch=graphBatch;this.protections=protections;this.business=business;}
    }

    static PreparedDocument prepareDocument(byte[] original,String fileKey,TemplateIndex index,Map<String,Object> graph,Map<String,Object> plan,Map<String,String> bookmarks,NativeLayoutPlan layout) {
        if(!map(plan.get(COVERAGE)).containsKey("graphHash"))prepare(graph,plan);
        Map<String,Object> coverage=map(plan.get(COVERAGE));
        boolean current=Objects.equals(coverage.get("graphHash"),DraftAdoption.hash(JsonUtils.write(graph)))&&Objects.equals(coverage.get("adoptedValuesHash"),DraftAdoption.hash(JsonUtils.write(plan.get("effectiveValues"))));
        Map<String,Object> graphPlan=view(plan,false),fixedPlan=view(plan,true);
        DraftFormattedRules.PreparedBatch fixed=null;
        SourceEditBatch fixedBatch=empty(index),graphBatch=empty(index),protections=empty(index);
        boolean unknownScope=hasUnlocatedEditTarget(graph,fileKey,index.getSourceSha256());
        if(unknownScope)deferDocument(plan,fileKey,true,"FixedGraphUnknownScope","A graph amendment target is unbound or needs rebinding in this document. Its overlap with fixed edits is unknown; fixed edits retain the original wording.");
        if(current&&!unknownScope&&DraftFixedBaseline.installed(graph)&&registeredSource(graph,fileKey)&&index.getSourceSha256().equals(DraftFormattedRules.verifiedSourceHash(fileKey))) {
            try {fixed=DraftFormattedRules.prepareBatch(index,fileKey,fixedPlan,layout);fixedBatch=fixed.business();}
            catch(RuntimeException invalid){deferDocument(plan,fileKey,true,"FixedSourceCompileRequired",invalid.getMessage());fixedBatch=empty(index);}
        } else if(!unknownScope)deferDocument(plan,fileKey,true,"FixedSourceUnverified","The current original, rule package or adopted-value snapshot differs from the prepared fixed-rule plan.");
        if(!current)deferDocument(plan,fileKey,false,"GraphPreparedSnapshotChanged","The graph or adopted-value snapshot changed after this plan was prepared.");
        else try {
            graphBatch=DraftReviewGraph.compileBatch(index,fileKey,graphPlan,true);
            protections=protectedGraphScopes(index,fileKey,graphPlan);
        }catch(RuntimeException invalid){deferDocument(plan,fileKey,false,"GraphSourceCompileRequired",invalid.getMessage());graphBatch=empty(index);protections=empty(index);}
        SourceEditBatch merged=mergeBatches(original,fileKey,plan,fixedBatch,graphBatch,protections);
        return new PreparedDocument(original,fileKey,index,bookmarks,layout,fixed,fixedBatch,graphBatch,protections,merged);
    }

    static Map<String,DocxEditResult> compileAll(List<PreparedDocument> documents,Map<String,Object> graph,Map<String,Object> plan) {return applyAll(documents,graph,plan,true);}
    private static Map<String,DocxEditResult> applyAll(List<PreparedDocument> documents,Map<String,Object> graph,Map<String,Object> plan,boolean allowEditorial) {
        Map<String,PreparedDocument> byKey=new LinkedHashMap<>();for(PreparedDocument document:documents)if(byKey.put(document.fileKey,document)!=null)throw new IllegalArgumentException("DUPLICATE_PREPARED_DRAFT: "+document.fileKey);
        Map<String,SourceEditBatch> editorialBatches=new LinkedHashMap<>();
        boolean editorial=allowEditorial&&byKey.keySet().equals(new LinkedHashSet<>(Arrays.asList("NTT","SCT","SCC")))&&editorialAllowed(graph,plan);
        if(editorial)for(PreparedDocument document:documents) {
            if(document.fixed==null){editorial=false;break;}
            try {editorialBatches.put(document.fileKey,mergeBatches(document.original,document.fileKey,plan,document.fixed.withEditorial(),document.graphBatch,document.protections));}
            catch(RuntimeException invalid){issue(plan,"editorial:"+document.fileKey,"EditorialReviewRequired",document.fileKey,Collections.emptyList(),invalid.getMessage());}
            if(!editorialAllowed(graph,plan)){editorial=false;break;}
        }
        Map<String,DocxEditResult> result=new LinkedHashMap<>();DocxTemplateEditor editor=new DocxTemplateEditor();
        for(PreparedDocument document:documents) {
            SourceEditBatch batch=withoutPendingActions(editorial?editorialBatches.get(document.fileKey):document.business,plan,document.fileKey);
            map(map(map(plan.get(COVERAGE)).get("documents")).get(document.fileKey)).put("editorialApplied",editorial);
            result.put(document.fileKey,editor.applyWithParagraphBookmarks(document.original,batch,document.bookmarks,!document.bookmarks.isEmpty(),document.layout));
        }
        return result;
    }

    static SourceEditBatch mergeBatches(byte[] original,String fileKey,Map<String,Object> plan,SourceEditBatch fixedBatch,SourceEditBatch graphBatch,SourceEditBatch protections) {
        DocxTemplateEditor editor=new DocxTemplateEditor();Set<String> conflicts=new LinkedHashSet<>();
        try {
            List<Edit> graphQueries=new ArrayList<>(graphBatch.getEdits());graphQueries.addAll(protections.getEdits());
            conflicts.addAll(editor.conflictingOperationIds(original,fixedBatch,new SourceEditBatch(graphBatch.getExpectedSourceSha256(),graphQueries)));
            Set<String> fixedIds=new HashSet<>();for(Edit edit:fixedBatch.getEdits())fixedIds.add(edit.getOperationId());
            for(Edit edit:graphBatch.getEdits())if(fixedIds.contains(edit.getOperationId()))conflicts.add(edit.getOperationId());
        }catch(RuntimeException invalid){deferDocument(plan,fileKey,true,"SourceBatchReviewRequired",invalid.getMessage());deferDocument(plan,fileKey,false,"SourceBatchReviewRequired",invalid.getMessage());updateCoverage(plan);return new SourceEditBatch(fixedBatch.getExpectedSourceSha256(),Collections.emptyList());}
        Set<String> deferredActions=new LinkedHashSet<>();
        for(String operation:conflicts)for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if(fileKey.equals(action.get("document"))&&owns(action,operation)){deferredActions.add(text(action.get("id")));defer(action,"FixedGraphEditConflict");}}
        if(!conflicts.isEmpty())issue(plan,"overlap:"+fileKey,"FixedGraphEditConflict",fileKey,new ArrayList<>(deferredActions),"Fixed and graph operations overlap. Both treatments retain the original wording until an explicit fixed-action replacement is reviewed. Operations: "+String.join(", ",conflicts));
        List<Edit> merged=new ArrayList<>();
        for(SourceEditBatch batch:Arrays.asList(fixedBatch,graphBatch))for(Edit edit:batch.getEdits()) {
            if(conflicts.contains(edit.getOperationId()))continue;boolean deferred=false;
            for(String action:deferredActions)if(edit.getOperationId().equals(action)||edit.getOperationId().startsWith(action+"-")){deferred=true;break;}
            if(!deferred)merged.add(edit);
        }
        updateCoverage(plan);
        return new SourceEditBatch(fixedBatch.getExpectedSourceSha256(),merged);
    }

    private static Map<String,Object> view(Map<String,Object> plan,boolean fixed) {
        Map<String,Object> result=new LinkedHashMap<>(plan);List<Object> actions=new ArrayList<>();for(Object raw:list(plan.get("actions")))if(fixed==FIXED.equals(map(raw).get("executionKind")))actions.add(raw);result.put("actions",actions);
        if(fixed){result.put("effectiveValues",plan.get("fixedRuleValues"));result.put("derived",plan.get("fixedRuleDerived"));}return result;
    }
    private static SourceEditBatch empty(TemplateIndex index){return new SourceEditBatch(index.getSourceSha256(),Collections.emptyList());}
    private static SourceEditBatch withoutPendingActions(SourceEditBatch batch,Map<String,Object> plan,String file){List<Edit> edits=new ArrayList<>();for(Edit edit:batch.getEdits()){boolean pending=false;for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if(file.equals(action.get("document"))&&"pending".equals(action.get("action"))&&owns(action,edit.getOperationId())){pending=true;break;}}if(!pending)edits.add(edit);}return new SourceEditBatch(batch.getExpectedSourceSha256(),edits);}
    static boolean editorialAllowed(Map<String,Object> graph,Map<String,Object> plan) {
        if(!DraftFixedBaseline.installed(graph)||!list(plan.get("unresolved")).isEmpty())return false;
        Map<String,Object> coverage=map(plan.get(COVERAGE));if(!Boolean.TRUE.equals(coverage.get("registered"))||!DraftBusinessRules.RULE_VERSION.equals(coverage.get("ruleVersion"))||!Objects.equals(coverage.get("graphHash"),DraftAdoption.hash(JsonUtils.write(graph)))||!Objects.equals(coverage.get("adoptedValuesHash"),DraftAdoption.hash(JsonUtils.write(plan.get("effectiveValues")))))return false;
        Map<String,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> node:nodes(graph))byId.put(text(node.get("id")),node);
        for(Object id:map(map(state(graph).get("fixedRuleBaseline")).get("nodeIdsByBaselineKey")).values()){Map<String,Object> node=byId.get(text(id));if(node==null||!DraftFixedBaseline.exactSource(graph,node)||!"confirmed".equals(node.get("status"))||!Boolean.TRUE.equals(map(node.get("catalogAlignment")).get("confirmed"))||!Boolean.TRUE.equals(map(node.get("definitionReview")).get("confirmed")))return false;}
        Set<String> fixedIds=new HashSet<>();int fixedCount=0;for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if("pending".equals(action.get("action")))return false;if(FIXED.equals(action.get("executionKind"))){fixedCount++;fixedIds.add(text(action.get("id")));}}
        if(fixedCount!=EXPECTED_ACTIONS.size()||!fixedIds.equals(EXPECTED_ACTIONS))return false;
        for(String file:Arrays.asList("NTT","SCT","SCC"))if(hasUnlocatedEditTarget(graph,file,DraftFormattedRules.verifiedSourceHash(file)))return false;
        return true;
    }
    private static Set<String> expectedActions(){Set<String> ids=new LinkedHashSet<>();for(Object raw:list(DraftBusinessRules.plan(Collections.emptyMap()).get("actions")))ids.add(text(map(raw).get("id")));return Collections.unmodifiableSet(ids);}
    private static boolean isActionIssue(Map<String,Object> issue,Map<String,Object> fixed){String id=text(issue.get("id")).replaceFirst("^fixed-rule:","");for(Object raw:list(fixed.get("actions")))if(id.equals(map(raw).get("id")))return true;return false;}
    private static boolean owns(Map<String,Object> action,String operation){String id=text(action.get("id"));return !id.isEmpty()&&(operation.equals(id)||operation.startsWith(id+"-"));}
    private static void defer(Map<String,Object> action,String reason){action.putIfAbsent("proposedAction",action.get("action"));action.put("action","pending");if(action.containsKey("decisionAction"))action.put("decisionAction","pending");action.put("applicationState","pending");action.put("executionIssue",reason);}
    private static void deferDocument(Map<String,Object> plan,String file,boolean fixed,String kind,String detail){List<String> ids=new ArrayList<>();for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if(file.equals(action.get("document"))&&fixed==FIXED.equals(action.get("executionKind"))){ids.add(text(action.get("id")));defer(action,kind);}}if(!ids.isEmpty())issue(plan,kind+":"+file,kind,file,ids,detail==null?kind:detail);}
    private static void issue(Map<String,Object> plan,String id,String kind,String document,List<String> actionIds,String message){String key="fixed-bridge:"+id;List<Object> issues=DraftBusinessRules.list(plan.get("unresolved"));if(issues.stream().noneMatch(raw->key.equals(map(raw).get("id"))))issues.add(obj("id",key,"kind",kind,"document",document,"actionIds",actionIds,"inputKeys",Collections.emptyList(),"executionKind",FIXED,"message",obj("zhHans",message,"en",message)));}
    private static boolean registeredSource(Map<String,Object> graph,String file) {
        String selected=text(map(graph.get("selectedDocuments")).get(file)),expected=DraftFormattedRules.verifiedSourceHash(file);if(selected.isEmpty()||expected.isEmpty())return false;
        int found=0;for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(selected.equals(text(source.get("id")))&&file.equals(source.get("fileKey"))&&"docx".equals(source.get("format"))&&"bound".equals(source.get("evidenceStatus"))&&expected.equals(source.get("sourceSha256")))found++;}return found==1;
    }
    private static boolean hasUnlocatedEditTarget(Map<String,Object> graph,String file,String sha) {
        String selected=text(map(graph.get("selectedDocuments")).get(file));Set<String> sourceIds=new HashSet<>();sourceIds.add(selected);
        for(Object raw:list(graph.get("sources"))){Map<String,Object> source=map(raw);if(selected.equals(text(source.get("id"))))for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId"))if(source.get(key)!=null)sourceIds.add(text(source.get(key)));}
        for(Map<String,Object> node:nodes(graph))if(!"excluded".equals(node.get("status")))for(Object raw:list(node.get("editTargets"))) {
            Map<String,Object> target=map(raw),binding=map(target.get("nativeBinding"));String document=text(target.get("docId"));boolean known=false;
            for(Object sourceRaw:list(graph.get("sources"))){Map<String,Object> source=map(sourceRaw);for(String key:Arrays.asList("id","docId","sourceDocumentId","editionId"))if(!document.isEmpty()&&document.equals(text(source.get(key))))known=true;}
            if(!sourceIds.contains(document)&&known)continue;
            if(Boolean.TRUE.equals(target.get("needsRebind"))||Boolean.FALSE.equals(target.get("scopeComplete"))||!"bound".equals(binding.get("status"))||!sha.equals(binding.get("sourceSha256"))||list(binding.get("spans")).isEmpty())return true;
        }return false;
    }
    private static SourceEditBatch protectedGraphScopes(TemplateIndex index,String file,Map<String,Object> plan) {
        List<Edit> edits=new ArrayList<>();for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if(!file.equals(action.get("document"))||!Arrays.asList("retain","pending").contains(text(action.get("action"))))continue;Map<String,Object> binding=map(action.get("nativeBinding"));if(!"bound".equals(binding.get("status"))||!index.getSourceSha256().equals(binding.get("sourceSha256")))continue;int n=0;for(Object spanRaw:list(binding.get("spans"))){Map<String,Object> span=map(spanRaw);Paragraph p=index.paragraph(text(span.get("paragraphId")));if(!p.getSha256().equals(span.get("paragraphSha256")))throw new IllegalArgumentException("GRAPH_PARAGRAPH_CHANGED: "+action.get("id"));int start=((Number)span.get("start")).intValue(),end=((Number)span.get("end")).intValue();String expected=text(span.get("expectedText")),id=text(action.get("id"))+"-"+(n++);if(start==end)edits.add(Edit.insertText(id,p,start,""));else if("catalogText".equals(span.get("channel")))edits.add(Edit.replaceCatalogSpan(id,p,start,end,expected,expected));else if("editableText".equals(span.get("channel")))edits.add(Edit.replaceSpan(id,p,start,end,expected,expected));else throw new IllegalArgumentException("GRAPH_TEXT_CHANNEL_REQUIRED: "+action.get("id"));}}return new SourceEditBatch(index.getSourceSha256(),edits);
    }
    private static void updateCoverage(Map<String,Object> plan) {
        Map<String,Object> coverage=map(plan.get(COVERAGE)),previous=map(coverage.get("documents")),documents=new LinkedHashMap<>();int pending=0,total=0;
        for(String file:Arrays.asList("NTT","SCT","SCC")){int count=0,waiting=0;for(Object raw:list(plan.get("actions"))){Map<String,Object> action=map(raw);if(FIXED.equals(action.get("executionKind"))&&file.equals(action.get("document"))){count++;if("pending".equals(action.get("action")))waiting++;}}Map<String,Object> item=obj("expectedActions",count,"pendingActions",waiting,"decidedActions",count-waiting);if(map(previous.get(file)).containsKey("editorialApplied"))item.put("editorialApplied",map(previous.get(file)).get("editorialApplied"));documents.put(file,item);total+=count;pending+=waiting;}
        coverage.put("expectedActions",total);coverage.put("pendingActions",pending);coverage.put("decidedActions",total-pending);coverage.put("status",pending>0?"partial_requires_review":"ready_for_source_compile");coverage.put("documents",documents);
    }

    private static final class Projection {
        final boolean installed;final Map<String,Object> values=new LinkedHashMap<>(),nodeIds;final Set<String> blocked=new LinkedHashSet<>();
        final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>(),confirmed;final Map<String,Boolean> allowed=new HashMap<>();
        Projection(Map<String,Object> graph,Map<String,Object> effective) {
            installed=DraftFixedBaseline.installed(graph);nodeIds=map(map(state(graph).get("fixedRuleBaseline")).get("nodeIdsByBaselineKey"));confirmed=DraftCatalogAlignment.confirmedDefinitions(graph);
            for(Map<String,Object> node:nodes(graph))nodes.put(text(node.get("id")),node);
            for(Map.Entry<String,Object> entry:nodeIds.entrySet()){String id=text(entry.getValue());Map<String,Object> node=nodes.get(id);boolean exact=installed&&node!=null&&DraftFixedBaseline.exactSource(graph,node);allowed.put(id,exact&&"confirmed".equals(node.get("status"))&&confirmed.containsKey(id));if(installed&&node!=null&&!exact)blocked.add(entry.getKey().split("\\.")[0]);}
            if(!installed)return;
            for(Map.Entry<String,Object> entry:nodeIds.entrySet())if(!entry.getKey().contains(".")){String id=text(entry.getValue());if(!Boolean.TRUE.equals(allowed.get(id))||blocked.contains(entry.getKey())||!effective.containsKey(id))continue;Object value=project(id,effective.get(id));if(value!=null)values.put(entry.getKey(),value);}
            // Package identity already passed the caller's independent source-adoption checks.
            if(effective.containsKey("contractTitle"))values.put("contractTitle",effective.get("contractTitle"));
        }
        Object project(String id,Object value) {
            if(value==null||!Boolean.TRUE.equals(allowed.get(id)))return null;Map<String,Object> node=nodes.get(id);String kind=text(map(node.get("type")).get("kind"));
            if("list".equals(kind)){if(!(value instanceof List))return null;List<Object> rows=new ArrayList<>();for(Object raw:list(value)){Object row=record(id,map(raw));if(row==null)return null;rows.add(row);}return rows;}
            if("record".equals(kind))return value instanceof Map?record(id,map(value)):null;return value;
        }
        Object record(String parent,Map<String,Object> raw) {
            Map<String,Object> result=new LinkedHashMap<>();String parentKey=text(map(confirmed.get(parent)).get("baselineKey"));for(Map<String,Object> child:nodes.values())if(parent.equals(text(child.get("parentId")))){String id=text(child.get("id"));if(!raw.containsKey(id)){if(!Boolean.TRUE.equals(child.get("optional")))return null;continue;}Object value=project(id,raw.get(id));String key=text(map(confirmed.get(id)).get("baselineKey"));if(value==null||!key.startsWith(parentKey+"."))return null;result.put(key.substring(parentKey.length()+1),value);}return result;
        }
    }
}
