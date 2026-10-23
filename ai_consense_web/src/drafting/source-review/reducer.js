export function createReviewReducer(data) {
  const copy = value => structuredClone(value);
  const edition = id => {
    const source = (data.sources || []).find(item => [String(item.id), item.docId, item.editionId].includes(String(id)));
    return source?.fileKey === 'SCC' ? source.format : id === 'scc-word' ? 'docx' : id === 'scc-pdf' ? 'pdf' : null;
  };
  const compatible = (a,b) => new Set([...a.docIds,...b.docIds].map(edition).filter(Boolean)).size < 2;
  const ruleBranches = c => c?.kind==='rule' ? (c.branches || [{...c,id:'case-1'}]) : [];
  const correctionClauses = c => ruleBranches(c).flatMap(b=>b.clauses||[]);
  const correctionInputs = c => [...(c?.inputIds||[]),...correctionClauses(c).map(x=>x.inputId),...ruleBranches(c).map(b=>b.scopeId).filter(Boolean)];
  const targetInputs = t => [t.valueSourceId,t.valueMode==='join'?t.listId:null].filter(Boolean);
  function isolatedSources(state){
    const byId=new Map(state.variables.map(v=>[v.id,v]));
    const context=v=>{const docs=new Set(v.docIds),seen=new Set();while(v?.parentId&&!seen.has(v.id)){seen.add(v.id);v=byId.get(v.parentId);for(const doc of v?.docIds||[])docs.add(doc);}return {docIds:[...docs]};};
    for(const v of state.variables){
      const own=context(v);if(!compatible(own,own))return false;if(v.status==='excluded')continue;
      const refs=[...(v.relatedInputIds||[]),...correctionInputs(v.correction?.needsRebind?null:v.correction),...(v.editTargets||[]).filter(t=>!t.needsRebind).flatMap(targetInputs)];
      for(const id of refs){const input=byId.get(id);if(input&&input.status!=='excluded'&&!compatible(own,context(input)))return false;}
    }
    return true;
  }
  const canShareSources=(state,v,docs)=>isolatedSources({...state,variables:state.variables.map(x=>x.id===v.id?{...x,docIds:[...new Set([...x.docIds,...docs])]}:x)});
  function affected(state, id) {
    const v = state.variables.find(x => x.id === id);
    const refs = x => [...(x.relatedInputIds||[]),...correctionInputs(x.correction),...(x.editTargets||[]).flatMap(targetInputs)];
    return state.variables.filter(x => x.id !== id && compatible(x,v) && x.status !== 'excluded' &&
      (refs(x).includes(id) || refs(v).includes(x.id)));
  }
  function mergeInto(s, id, targetId) {
    const v=s.variables.find(x=>x.id===id),other=s.variables.find(x=>x.id===targetId);
    v.status='excluded';v.replacedBy=[other.id];v.reason='人工合并 / Merged during review';
    other.docIds=[...new Set([...other.docIds,...v.docIds])];
    other.sourceUnitIds=[...new Set([...(other.sourceUnitIds||[]),...(v.sourceUnitIds||[])])];
    other.evidence=[...other.evidence,...v.evidence].filter((x,i,a)=>a.findIndex(y=>y.docId===x.docId&&y.quote===x.quote)===i);
    other.targets=[...other.targets,...v.targets].filter((x,i,a)=>a.findIndex(y=>y.docId===x.docId&&y.labelZh===x.labelZh)===i);
    other.sourceAnchors=[...(other.sourceAnchors||[]),...(v.sourceAnchors||[])];
    other.editTargets=[...(other.editTargets||[]),...(v.editTargets||[])];
    other.relatedInputIds=[...new Set([...(other.relatedInputIds||[]),...(v.relatedInputIds||[])])];
    other.relations=[...other.relations,...v.relations].filter((x,i,a)=>a.findIndex(y=>JSON.stringify(x)===JSON.stringify(y))===i);
    if(!other.correction&&v.correction)other.correction=copy(v.correction);
    if(other.catalogAlignment||v.catalogAlignment)other.catalogAlignment={...copy(other.catalogAlignment||v.catalogAlignment),confirmed:false};
    if(v.role.kind==='fixed'&&other.role.kind!=='fixed'){other.role=copy(v.role);other.fixedValue=v.fixedValue;}
    other.status='changed';other.manual=true;
    for (const x of s.variables) {
      if(x.parentId===v.id){x.parentId=other.id;if(x.status==='confirmed')x.status='pending';}
      x.relatedInputIds=[...new Set((x.relatedInputIds||[]).map(id=>id===v.id?other.id:id))].filter(id=>id!==x.id);
      if(x.correction?.inputIds) x.correction.inputIds=[...new Set(x.correction.inputIds.map(id=>id===v.id?other.id:id))];
      if(x.correction?.branches)for(const branch of x.correction.branches){branch.clauses=branch.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));if(branch.scopeId===v.id)branch.scopeId=other.id;}
      else if(x.correction?.clauses)x.correction.clauses=x.correction.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));
      for(const target of x.editTargets||[]){if(target.valueSourceId===v.id)target.valueSourceId=other.id;if(target.listId===v.id)target.listId=other.id;if(target.itemKey===v.id){target.itemKey=other.id;target.needsRebind=true;x.status='help';}}
    }
    for(const requirement of s.requirements||[])requirement.nodeIds=[...new Set((requirement.nodeIds||[]).map(id=>id===v.id?other.id:id))];
  }
  function mergeProblem(state, id, targetId) {
    const v=state.variables.find(x=>x.id===id),other=state.variables.find(x=>x.id===targetId);
    const problem=code=>({code,id,targetId});
    if(!v||!other)return problem('MERGE_NODE_MISSING');
    if(id===targetId)return problem('MERGE_SAME_NODE');
    if(v.status==='excluded'||other.status==='excluded')return problem('MERGE_EXCLUDED');
    if(v.type.kind!==other.type.kind)return problem('MERGE_TYPE_MISMATCH');
    if((v.type.unit||'')!==(other.type.unit||''))return problem('MERGE_UNIT_MISMATCH');
    if(v.catalogAlignment?.confirmed&&other.catalogAlignment?.confirmed&&['status','baselineKey','baselineVersion','scope'].some(key=>(v.catalogAlignment[key]||'')!==(other.catalogAlignment[key]||'')))return problem('MERGE_ALIGNMENT_REVIEW_REQUIRED');
    if(v.role.kind!==other.role.kind)return problem('MERGE_ROLE_MISMATCH');
    if(v.parentId!==other.parentId)return problem('MERGE_PARENT_MISMATCH');
    if(Boolean(v.optional)!==Boolean(other.optional))return problem('MERGE_OPTIONAL_MISMATCH');
    if(JSON.stringify(v.options||[])!==JSON.stringify(other.options||[]))return problem('MERGE_OPTIONS_MISMATCH');
    if(v.role.kind==='fixed'&&v.fixedValue!==other.fixedValue)return problem('MERGE_FIXED_VALUE_MISMATCH');
    const emptyUnresolved=c=>c?.kind==='rule'&&c.needsRebind===true&&Array.isArray(c.branches)&&!c.branches.length&&Array.isArray(c.inputIds)&&!c.inputIds.length&&Object.keys(c).every(key=>['kind','needsRebind','branches','inputIds'].includes(key));
    if(v.correction&&other.correction&&!(emptyUnresolved(v.correction)&&emptyUnresolved(other.correction)))return problem('MERGE_RULE_CONFLICT');
    if(!compatible(other,v))return problem('MERGE_SOURCE_EDITION_CONFLICT');
    if(!canShareSources(state,v,other.docIds)||!canShareSources(state,other,v.docIds))return problem('MERGE_SOURCE_SCOPE_CONFLICT');
    const projected=copy(state);mergeInto(projected,id,targetId);
    return isolatedSources(projected)?null:problem('MERGE_SOURCE_SCOPE_CONFLICT');
  }
  function mergeMany(state, ids, targetId, at, reason) {
    const failed=(code,id=targetId)=>({state,problem:{code,id,targetId}});
    if(!Array.isArray(ids)||ids.length<2)return failed('MERGE_SELECTION_REQUIRED');
    const duplicate=ids.find((id,index)=>ids.indexOf(id)!==index);
    if(duplicate!==undefined)return failed('MERGE_DUPLICATE_ID',duplicate);
    if(!ids.includes(targetId))return failed('MERGE_TARGET_REQUIRED');
    for(const id of ids)if(typeof id!=='string'||!id||!state.variables.some(v=>v.id===id))return failed('MERGE_NODE_MISSING',id);
    let next=state;
    for(const id of ids)if(id!==targetId){
      const merged=reduce(next,{type:'MERGE',id,targetId,at,reason});
      if(merged===next)return {state,problem:mergeProblem(next,id,targetId)||{code:'MERGE_SOURCE_SCOPE_CONFLICT',id,targetId}};
      next=merged;
    }
    return {state:next,problem:null};
  }
  // The preview records explicit human decisions; it does not reinterpret source wording.
  function reduce(old, action) {
    if(action.type==='MERGE_MANY')return mergeMany(old,action.ids,action.targetId,action.at,action.reason).state;
    const s = copy(old), find = id => s.variables.find(v => v.id === id);
    const v = find(action.id);
    const event = (zh,en,details=[]) => s.events.unshift({zh,en,details,time:action.at});
    const revisit = id => { for (const x of [find(id),...affected(s,id)]) {if(x.id!==id&&x.status==='confirmed')x.status='pending';for(const unitId of x.sourceUnitIds||[])delete s.sourceReviews[unitId];} };
    if (action.type === 'CLEAR') {
      s.variables=[];s.sourceReviews={};s.events=[];s.nextId=1;s.blank=true;s.guidanceMode=action.guidanceMode||'empty';
      event('已从空白开始核对','Started an empty review');
    } else if (action.type === 'EDIT') {
      revisit(v.id);
      const before = [v.nameZh,v.nameEn,v.type.zh,v.role.zh].join(' · ');
      const ownAnswerChanged=(action.values.type&&(action.values.type.kind!==v.type.kind||(action.values.type.unit||'')!==(v.type.unit||'')))||(action.values.options&&JSON.stringify(action.values.options)!==JSON.stringify(v.options||[]));
      if(!action.values.catalogAlignment&&v.catalogAlignment?.confirmed&&['nameZh','nameEn','type','options','optional','parentId','definitionScope'].some(key=>action.values[key]!==undefined&&JSON.stringify(action.values[key])!==JSON.stringify(v[key])))v.catalogAlignment={...v.catalogAlignment,confirmed:false};
      if(action.values.parentId!==undefined&&action.values.parentId!==v.parentId){
        const moved=new Set([v.id]);let changed=true;
        while(changed){changed=false;for(const x of s.variables)if(moved.has(x.parentId)&&!moved.has(x.id)){moved.add(x.id);changed=true;}}
        for(const x of s.variables)if(x.status!=='excluded'){
          if(x.correction&&correctionInputs(x.correction).some(id=>moved.has(id))){x.correction.needsRebind=true;x.status='help';}
          for(const target of x.editTargets||[])if(targetInputs(target).some(id=>moved.has(id))){target.needsRebind=true;x.status='help';}
        }
      }
      if(ownAnswerChanged||action.values.role?.kind==='fixed')for(const x of s.variables){if(x.status!=='excluded'&&x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction.needsRebind=true;x.status='help';}}
      Object.assign(v,action.values,{status:'changed',manual:true});
      if(ownAnswerChanged&&v.correction){v.correction.needsRebind=true;v.status='help';}
      if(ownAnswerChanged)for(const x of s.variables)if(x.status!=='excluded')for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
      event('调整了问题的内容或用途','Changed the question or its purpose',[before,'→ '+[v.nameZh,v.nameEn,v.type.zh,v.role.zh].join(' · ')]);
    } else if (action.type === 'SPLIT') {
      if(!compatible({docIds:action.items.flatMap(item=>(item.sourceAnchors||[]).map(a=>a.docId))},v))return old;
      revisit(v.id);
      const children = action.items.map(item => ({...copy(v),id:`review-${s.nextId++}`,parentId:v.parentId||null,definitionKind:'added',...item,
        docIds:[...new Set((item.sourceAnchors||[]).map(a=>a.docId))],sourceUnitIds:[],missingSource:false,
        evidence:(item.sourceAnchors||[]).map(a=>({docId:a.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:a.text})),
        questionZh:item.nameZh,questionEn:item.nameEn,type:item.type||{kind:'boolean',zh:'是／否',en:'Yes / No'},role:{kind:'input',zh:'项目资料',en:'Project information'},
        options:[],status:'changed',manual:true,originId:v.id,relatedInputIds:[],relations:[],correction:null,catalogAlignment:v.catalogAlignment?{...v.catalogAlignment,confirmed:false,status:'ambiguous'}:undefined,
        editTargets:(v.editTargets||[]).filter(target=>(item.sourceAnchors||[]).some(a=>a.docId===target.docId)).map(target=>({...target,needsRebind:true})),
        targets:(v.targets||[]).filter(target=>(item.sourceAnchors||[]).some(a=>a.docId===target.docId))}));
      v.status='excluded'; v.replacedBy=children.map(x=>x.id); v.reason='人工拆分 / Split during review';
      s.variables.push(...children);
      children[0].correction={kind:'split',mode:action.mode,inputIds:children.map(x=>x.id),originalZh:v.nameZh,originalEn:v.nameEn,needsRebind:action.mode==='unknown'};
      if(!isolatedSources(s))return old;
      for (const x of s.variables) if (x.id !== v.id && x.status!=='excluded' && !children.some(c=>c.id===x.id)) {
        for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
        x.relatedInputIds=[...new Set((x.relatedInputIds||[]).flatMap(id=>id===v.id?children.map(c=>c.id):[id]))];
        if(x.correction&&correctionInputs(x.correction).includes(v.id)) {
          x.correction.needsRebind=true;x.status='help';x.reason='引用的问题已拆分，请重新确认条件。 / A referenced question was split. Review the condition.';
        }
      }
      event('将一个问题拆成了两个独立问题','Split one question into two',[v.nameZh,...children.map(x=>x.nameZh),action.mode==='all'?'两个问题必须同时成立 / Both must be true':action.mode==='any'?'任一问题成立即可 / Either may be true':'条件仍待确认 / Conditions still need review']);
    } else if (action.type === 'MERGE') {
      const other=find(action.targetId);
      if(mergeProblem(old,action.id,action.targetId))return old;
      revisit(v.id); revisit(other.id);
      const oldName=v.nameZh;
      mergeInto(s,v.id,other.id);
      if(!isolatedSources(s))return old;
      event('确认两处是在问同一件事','Merged two references to the same question',[oldName,'→ '+other.nameZh,action.reason]);
    } else if (action.type === 'EXCLUDE') {
      revisit(v.id);v.status='excluded';v.reason=action.reason;v.manual=true;
      for(const x of s.variables)if(x.status!=='excluded'&&x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction.needsRebind=true;x.status='help';}
      for(const x of s.variables)if(x.status!=='excluded')for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
      event('将问题标记为不再提问','Marked the question as no longer needed',[v.nameZh,action.reason]);
    } else if (action.type === 'RESTORE') {
      revisit(v.id);
      v.status='pending';v.reason='';delete v.replacedBy;
      event('恢复了一个问题','Restored a question',[v.nameZh]);
    } else if (action.type === 'ADD') {
      const unit=(data.sourceUnits || []).find(x=>x.id===action.unitId), base=action.base;
      const id=`review-${s.nextId++}`;
      const units=unit?[unit.id]:(base?.sourceUnitIds||[]);
      for(const unitId of units)delete s.sourceReviews[unitId];
      for(const x of s.variables)if(x.status==='confirmed'&&(x.sourceUnitIds||[]).some(id=>units.includes(id)))x.status='pending';
      s.variables.push({id,parentId:null,definitionKind:'added',...action.values,docIds:action.docId?[action.docId]:[],options:action.values.options||[],optional:!!action.values.optional,
        guidance:!action.anchor&&!unit&&action.values.definitionOrigin?.kind==='manual'?{zh:'人工新增变量定义：'+action.values.definitionOrigin.reason,en:'Manually added variable definition: '+action.values.definitionOrigin.reason}:{zh:'人工根据原文补充。',en:'Added during source review.'},evidence:action.anchor?[{docId:action.anchor.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:action.anchor.text}]:unit?[{docId:unit.docId,locationZh:unit.locationZh,locationEn:unit.locationEn,quote:unit.quote}]:(base?.evidence||[]),
        relations:[],targets:[],relatedInputIds:[],sourceUnitIds:unit?[unit.id]:(base?.sourceUnitIds||[]),missingSource:!action.anchor&&!unit&&!base?.evidence?.length,
        status:'changed',reason:action.reason,manual:true,editTargets:[],sourceAnchors:action.anchor?[action.anchor]:[]});
      event('补充了一个遗漏的问题','Added a missing question',[action.values.nameZh,action.reason]);
    } else if (action.type === 'LINK') {
      if(!compatible(v,{docIds:[action.anchor.docId]})||!canShareSources(old,v,[action.anchor.docId]))return old;
      revisit(v.id);v.sourceAnchors=[...(v.sourceAnchors||[]),action.anchor];v.status='changed';v.manual=true;v.missingSource=false;
      v.docIds=[...new Set([...v.docIds,action.anchor.docId])];
      v.evidence.push({docId:action.anchor.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:action.anchor.text});
      event('重新关联了原文','Linked a source passage',[v.nameZh,action.anchor.text]);
    } else if (action.type === 'UNLINK') {
      const removed=v.sourceAnchors?.[action.index];if(!removed)return old;
      revisit(v.id);v.sourceAnchors.splice(action.index,1);v.manual=true;v.status='changed';
      v.evidence=v.evidence.filter(e=>e.docId!==removed.docId||e.quote!==removed.text||v.sourceAnchors.some(a=>a.docId===e.docId&&a.text===e.quote));
      v.missingSource=!v.sourceAnchors.length&&!v.sourceUnitIds?.length;
      event('移除了关联错误的原文，仍保留在调整记录中','Removed an incorrect source link; retained it in the change history',[v.nameZh,removed.docId,removed.text]);
    } else if (action.type === 'TARGETS') {
      const docs=[...new Set([...v.docIds,...action.targets.map(t=>t.docId)])];
      if(!canShareSources(old,v,docs))return old;v.docIds=docs;
      revisit(v.id);v.editTargets=action.targets;v.status='changed';v.manual=true;
      if(!isolatedSources(s))return old;
      event('调整了需要修改的原文位置','Changed the text to be amended',[v.nameZh,`${action.targets.length} 处 / passages`]);
    } else if (action.type === 'RULE') {
      revisit(v.id);v.correction=action.correction;v.status='changed';v.manual=true;
      if(!isolatedSources(s))return old;
      event('调整了条件与联动','Changed conditions and linked effects',[v.nameZh,...ruleBranches(action.correction).map(b=>b.effectZh)]);
    } else if (action.type === 'REVIEW') {
      v.status='confirmed';event('确认了一个问题','Reviewed a question',[v.nameZh]);
    } else if (action.type === 'HELP') {
      v.status='help';v.reason=action.reason;event('记录了需要补充的信息','Recorded a request for more context',[v.nameZh,action.reason]);
    } else if (action.type === 'CATALOG_EXCLUDE') {
      s.catalogReview??={exclusions:{}};
      if(action.reason===null)delete s.catalogReview.exclusions[action.key];
      else if(action.reason?.trim()&&action.baselineVersion)s.catalogReview.exclusions[action.key]={reason:action.reason.trim(),baselineVersion:action.baselineVersion};
      else return old;
      event('核对了基准变量适用性','Reviewed baseline applicability',[action.key,action.reason||'重新核对 / Review again']);
    } else if (action.type === 'SOURCE_REVIEW') {
      s.sourceReviews[action.unitId]=true;event('核对了一条原文说明','Reviewed a source note',[action.label]);
    }
    return s;
  }
  return { reduce, compatible, isolatedSources, mergeProblem, mergeMany };
}
