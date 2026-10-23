(() => {
  'use strict';
  const data = JSON.parse(document.getElementById('prototype-data').textContent);
  const copy = value => structuredClone(value);
  const $ = id => document.getElementById(id);
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const bi = (zh, en) => `<span class="zh">${escape(zh)}</span><span class="en" lang="en">${escape(en)}</span>`;
  const button = (action, zh, en, extra = '', cls = '') => `<button type="button" class="btn ${cls}" data-action="${action}" ${extra}>${bi(zh, en)}</button>`;
  const title = (zh, en) => `<h3 class="section-title">${bi(zh, en)}</h3>`;
  const compatible = (a,b) => {const docs=new Set([...a.docIds,...b.docIds]);return !(docs.has('scc-word')&&docs.has('scc-pdf'));};
  const statusNames = {pending:['待确认','To review'],changed:['已调整，待确认','Changed · review needed'],confirmed:['已确认','Reviewed'],help:['需补充信息','More context needed'],excluded:['不再提问','No longer asked']};
  const typeNames = {boolean:['是／否','Yes / No'],number:['数字','Number'],text:['文字','Text'],date:['日期','Date'],choice:['单选','Single choice'],multiselect:['多选','Multiple choices'],record:['一组资料','Related details'],list:['条目清单','List of items']};
  const roleNames = {input:['项目资料','Project information'],decision:['是否采用','Adoption choice'],content:['待填写内容','Content to provide'],derived:['由其他答案决定','Determined by other answers'],verification:['需要核对的事项','Item to check'],system:['内部调整记录','Internal review record'],fixed:['固定内容／默认值','Fixed content / default']};
  const initial = () => ({variables:copy(data.variables).map(v => ({...v,status:'pending',reason:'',fixedValue:'',manual:false})),sourceReviews:{},events:[],nextId:1,blank:false,guidanceMode:'recognized'});
  const displayNames = new Map(data.variables.map(v => [v.id.split(':').at(-1),[v.nameZh,v.nameEn]]));
  function businessText(text, lang = 'zh') {
    let value = String(text ?? '');
    for (const [key,names] of [...displayNames].sort((a,b) => b[0].length-a[0].length)) {
      if (key.length > 3) value = value.split(key).join(names[lang === 'zh' ? 0 : 1]);
    }
    return value.replace(/\b(?:atomic[-\w:]+|(?:V|P|R|I|C|F|D|A|E)\d+(?:\.[\w.]+)?)\b/g, lang === 'zh' ? '对应规则' : 'the related rule')
      .replace(/\bunknown\b/g,lang === 'zh'?'尚不确定':'not yet known').replace(/\binvalid\b/g,lang === 'zh'?'内容需调整':'needs correction')
      .replace(/\btrue\b/g,lang === 'zh'?'是':'yes').replace(/\bfalse\b/g,lang === 'zh'?'否':'no');
  }
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
  // The preview records explicit human decisions; it does not reinterpret source wording.
  function reduce(old, action) {
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
      const ownAnswerChanged=(action.values.type&&action.values.type.kind!==v.type.kind)||(action.values.options&&JSON.stringify(action.values.options)!==JSON.stringify(v.options||[]));
      if(action.values.parentId!==undefined&&action.values.parentId!==v.parentId){
        const moved=new Set([v.id]);let changed=true;
        while(changed){changed=false;for(const x of s.variables)if(moved.has(x.parentId)&&!moved.has(x.id)){moved.add(x.id);changed=true;}}
        for(const x of s.variables)if(x.status!=='excluded'){
          if(x.correction&&correctionInputs(x.correction).some(id=>moved.has(id))){x.correction.needsRebind=true;x.status='help';}
          for(const target of x.editTargets||[])if(targetInputs(target).some(id=>moved.has(id))){target.needsRebind=true;x.status='help';}
        }
      }
      if(action.values.type?.kind!==v.type.kind||action.values.role?.kind==='fixed'||(action.values.options&&JSON.stringify(action.values.options)!==JSON.stringify(v.options)))for(const x of s.variables){if(x.status!=='excluded'&&x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction.needsRebind=true;x.status='help';}}
      Object.assign(v,action.values,{status:'changed',manual:true});
      if(ownAnswerChanged&&v.correction){v.correction.needsRebind=true;v.status='help';}
      if(ownAnswerChanged)for(const x of s.variables)if(x.status!=='excluded')for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
      event('调整了问题的内容或用途','Changed the question or its purpose',[before,'→ '+[v.nameZh,v.nameEn,v.type.zh,v.role.zh].join(' · ')]);
    } else if (action.type === 'SPLIT') {
      if(!compatible({docIds:action.items.flatMap(item=>(item.sourceAnchors||[]).map(a=>a.docId))},v))return old;
      revisit(v.id);
      const children = action.items.map(item => ({...copy(v),id:`review-${s.nextId++}`,parentId:v.parentId||null,definitionKind:'added',...item,
        docIds:[...new Set((item.sourceAnchors||[]).map(a=>a.docId))],sourceUnitIds:[],missingSource:false,
        evidence:(item.sourceAnchors||[]).map(a=>({docId:a.docId,locationZh:`第${a.page}页`,locationEn:`Page ${a.page}`,quote:a.text})),
        questionZh:item.nameZh,questionEn:item.nameEn,type:item.type||{kind:'boolean',zh:'是／否',en:'Yes / No'},role:{kind:'input',zh:'项目资料',en:'Project information'},
        options:[],status:'changed',manual:true,originId:v.id,relatedInputIds:[],relations:[],correction:null,
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
      if (!other || other.id === v.id || !compatible(other,v)||!canShareSources(old,v,other.docIds)||!canShareSources(old,other,v.docIds)) return old;
      if(v.correction&&other.correction)return old;
      if(v.parentId!==other.parentId||JSON.stringify(v.options||[])!==JSON.stringify(other.options||[]))return old;
      if(v.role.kind==='fixed'&&other.role.kind==='fixed'&&v.fixedValue!==other.fixedValue)return old;
      revisit(v.id); revisit(other.id);
      const oldName=v.nameZh;
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
      if(v.role.kind==='fixed'&&other.role.kind!=='fixed'){other.role=copy(v.role);other.fixedValue=v.fixedValue;}
      other.status='changed';other.manual=true;
      for (const x of s.variables) {
        x.relatedInputIds=[...new Set((x.relatedInputIds||[]).map(id=>id===v.id?other.id:id))].filter(id=>id!==x.id);
        if(x.correction?.inputIds) x.correction.inputIds=[...new Set(x.correction.inputIds.map(id=>id===v.id?other.id:id))];
        if(x.correction?.branches)for(const branch of x.correction.branches){branch.clauses=branch.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));if(branch.scopeId===v.id)branch.scopeId=other.id;}
        else if(x.correction?.clauses)x.correction.clauses=x.correction.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));
        for(const target of x.editTargets||[]){if(target.valueSourceId===v.id)target.valueSourceId=other.id;if(target.listId===v.id)target.listId=other.id;}
      }
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
      const unit=data.sourceUnits.find(x=>x.id===action.unitId), base=action.base;
      const id=`review-${s.nextId++}`;
      const units=unit?[unit.id]:(base?.sourceUnitIds||[]);
      for(const unitId of units)delete s.sourceReviews[unitId];
      for(const x of s.variables)if(x.status==='confirmed'&&(x.sourceUnitIds||[]).some(id=>units.includes(id)))x.status='pending';
      s.variables.push({id,parentId:null,definitionKind:'added',...action.values,docIds:[action.docId],options:action.values.options||[],optional:false,
        guidance:{zh:'人工根据原文补充。',en:'Added during source review.'},evidence:action.anchor?[{docId:action.anchor.docId,locationZh:`第${action.anchor.page}页`,locationEn:`Page ${action.anchor.page}`,quote:action.anchor.text}]:unit?[{docId:unit.docId,locationZh:unit.locationZh,locationEn:unit.locationEn,quote:unit.quote}]:(base?.evidence||[]),
        relations:[],targets:[],relatedInputIds:[],sourceUnitIds:unit?[unit.id]:(base?.sourceUnitIds||[]),missingSource:!action.anchor&&!unit&&!base?.evidence?.length,
        status:'changed',reason:action.reason,manual:true,sourceAnchors:action.anchor?[action.anchor]:[]});
      event('补充了一个遗漏的问题','Added a missing question',[action.values.nameZh,action.reason]);
    } else if (action.type === 'LINK') {
      if(!compatible(v,{docIds:[action.anchor.docId]})||!canShareSources(old,v,[action.anchor.docId]))return old;
      revisit(v.id);v.sourceAnchors=[...(v.sourceAnchors||[]),action.anchor];v.status='changed';v.manual=true;v.missingSource=false;
      v.docIds=[...new Set([...v.docIds,action.anchor.docId])];
      v.evidence.push({docId:action.anchor.docId,locationZh:`第${action.anchor.page}页`,locationEn:`Page ${action.anchor.page}`,quote:action.anchor.text});
      event('重新关联了原文','Linked a source passage',[v.nameZh,action.anchor.text]);
    } else if (action.type === 'UNLINK') {
      const removed=v.sourceAnchors?.[action.index];if(!removed)return old;
      revisit(v.id);v.sourceAnchors.splice(action.index,1);v.manual=true;v.status='changed';
      v.evidence=v.evidence.filter(e=>e.docId!==removed.docId||e.quote!==removed.text||v.sourceAnchors.some(a=>a.docId===e.docId&&a.text===e.quote));
      v.missingSource=!v.sourceAnchors.length&&!v.sourceUnitIds?.length;
      event('移除了关联错误的原文，仍保留在调整记录中','Removed an incorrect source link; retained it in the change history',[v.nameZh,removed.docId+' · '+removed.page,removed.text]);
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
    } else if (action.type === 'SOURCE_REVIEW') {
      s.sourceReviews[action.unitId]=true;event('核对了一条原文说明','Reviewed a source note',[action.label]);
    }
    return s;
  }
  function containsWord(range,index){return Array.isArray(range.wordIds)?range.wordIds.includes(index):index>=range.start&&index<range.end;}
  function visualWordIds(words,ids){
    const rows=[];
    for(const i of [...new Set(ids)].sort((a,b)=>(words[a].y+words[a].h/2)-(words[b].y+words[b].h/2)||words[a].x-words[b].x)){
      const w=words[i],y=w.y+w.h/2;
      let row=rows.find(r=>Math.abs(r.y-y)<=0.45*Math.min(r.height,w.h));
      if(!row){row={y,height:w.h,ids:[]};rows.push(row);}row.ids.push(i);
    }
    return rows.sort((a,b)=>a.y-b.y).flatMap(r=>r.ids.sort((a,b)=>words[a].x-words[b].x));
  }
  function wordsInBox(words,a,b){
    const left=Math.min(a.x,b.x),right=Math.max(a.x,b.x),top=Math.min(a.y,b.y),bottom=Math.max(a.y,b.y);
    return visualWordIds(words,words.flatMap((w,i)=>w.x+w.w/2>=left&&w.x+w.w/2<=right&&w.y<=bottom&&w.y+w.h>=top?[i]:[]));
  }
  function selectedText(words,ids){
    let previous=null;
    return ids.map(i=>{const w=words[i],next=(previous&&Math.abs(w.y+w.h/2-previous.y-previous.h/2)>0.45*Math.min(w.h,previous.h)?'\n':previous?' ':'')+w.text;previous=w;return next;}).join('');
  }
  const source = JSON.parse($('source-data').textContent);
  let state=initial(), undo=[], docId='ntt', page=1, selectedId='', panel='', selection=null, draft=null, capture=null, toastTimer, wordClickTimer, search='', drag=null, ignoreClickUntil=0;
  let pendingPack=null, savedReviewUrl, pendingGraph=null, savedGraphUrl, failedGraph=null, graphName='', graphRequest=0;
  let overviewOpen=false, overviewFocus='', overviewSearch='', overviewHistory=true, overviewAll=true, overviewLayout=null, overviewImpact=null, overviewRange=null, overviewBusinessView=null, overviewActionId='';
  const integrationOrigin='http://127.0.0.1:5192';
  let integrationLastState, integrationLoaded=false;
  const current=()=>state.variables.find(v=>v.id===selectedId);
  const doc=()=>data.documents.find(d=>d.id===docId);
  const pages=()=>source.documents[docId].pages;
  const pageData=()=>pages()[page-1];
  const getName=id=>state.variables.find(v=>v.id===id)||{nameZh:'已移除的问题',nameEn:'Removed question'};
  const status=v=>`<span class="status ${v.status}">${bi(...statusNames[v.status])}</span>`;
  const opts=(map,value)=>Object.entries(map).map(([k,n])=>`<option value="${escape(k)}" ${k===value?'selected':''}>${escape(n.join(' / '))}</option>`).join('');
  const field=(name,zh,en,value='',kind='text')=>`<label class="label">${bi(zh,en)}${kind==='textarea'?`<textarea name="${name}">${escape(value)}</textarea>`:`<input name="${name}" value="${escape(value)}">`}</label>`;
  const choose=(name,zh,en,options)=>`<label class="label">${bi(zh,en)}<select name="${name}">${options}</select></label>`;
  const note=(zh,en)=>`<div class="notice">${bi(zh,en)}</div>`;
  const quote=text=>`<blockquote class="quote" lang="en">${escape(text)}</blockquote>`;
  const variables=()=>state.variables.filter(v=>v.docIds.includes(docId));
  const activeChildren=v=>state.variables.some(x=>x.parentId===v.id&&x.status!=='excluded');
  const rangesForUnit=id=>source.anchors[id]?.status==='exact'?(source.anchors[id].ranges||[]):[];
  const unitVars=u=>state.variables.filter(v=>v.status!=='excluded'&&((v.sourceUnitIds||[]).includes(u.id)||(u.variableIds||[]).includes(v.id)||(v.sourceAnchors||[]).some(a=>a.docId===u.docId&&rangesForUnit(u.id).some(r=>r.page===a.page&&(a.wordIds||Array.from({length:a.end-a.start},(_,i)=>a.start+i)).some(i=>containsWord(r,i))))));
  const docNames=ids=>ids.map(id=>data.documents.find(d=>d.id===id)?.shortTitle||id).join(' · ');
  function anchorsFor(v){
    if(v.originId&&v.sourceAnchors?.length)return v.sourceAnchors;
    return [...(v.sourceAnchors||[]),...(v.sourceUnitIds||[]).flatMap(id=>{const u=data.sourceUnits.find(x=>x.id===id);return rangesForUnit(id).map(r=>({...r,docId:u.docId}));})];
  }
  function notify(zh,en){$('toast').innerHTML=bi(zh,en);clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').innerHTML='',5500);}
  function paintSelection(ids=[]){const chosen=new Set(ids);document.querySelectorAll('#paper [data-word]').forEach(e=>e.classList.toggle('selectedword',chosen.has(+e.dataset.word)));}
  function clearSelection(){clearTimeout(wordClickTimer);selection=null;$('selectionbar').hidden=true;window.getSelection()?.removeAllRanges();paintSelection();const box=$('selectionbox');if(box)box.hidden=true;}
  function openPanel(name,zh,en){panel=name;$('drawer').classList.add('open');$('drawer-title').innerHTML=bi(zh,en);$('drawer').scrollTop=0;}
  function closePanel(){if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');panel='';selectedId='';$('drawer').classList.remove('open');renderPage();}
  function apply(action){
    undo.push({state:copy(state),selectedId,docId,page});if(undo.length>50)undo.shift();
    overviewRange=null;state=reduce(state,{...action,at:new Date().toLocaleTimeString('zh-CN',{hour12:false})});
    if(action.type==='ADD')selectedId=state.variables.at(-1).id;
    if(action.type==='SPLIT')selectedId=state.variables.find(x=>x.originId===action.id&&x.status!=='excluded').id;
    if(action.type==='MERGE')selectedId=action.targetId;
    draft=null;capture=null;clearSelection();if(action.type==='SPLIT')navigateVariable(current());renderHeader();renderPage();showQuestion();
    notify('已记录。相关已确认内容需要重新核对。','Change recorded. Reviewed linked items need another check.');
  }
  function renderHeader(){
    integrationChanged();
    $('doc-tabs').innerHTML=data.documents.map(d=>`<button data-doc="${d.id}" class="${docId===d.id?'active':''}" aria-pressed="${docId===d.id}">${bi(d.shortTitle,d.id==='scc-word'?'特别合约条件 · Word':d.id==='scc-pdf'?'特别合约条件 · PDF':d.titleZh)}</button>`).join('');
    $('document-label').innerHTML=bi(doc().titleZh,source.documents[docId].kind==='pdf-original'?'Original PDF · select text on the page':'Word layout preview · select text on the page');
    $('page-number').value=page;$('page-number').max=pages().length;$('page-count').textContent=`/ ${pages().length}`;
    $('previous').disabled=page===1;$('next').disabled=page===pages().length;$('undo').disabled=!undo.length;
    $('jump-notes').disabled=!!state.blank&&state.guidanceMode!=='minimum';
    $('start-review').classList.toggle('active',!!state.blank);
  }
  function renderPage(){
    clearSelection();drag=null;
    $('graph-range-note').classList.toggle('visible',!!overviewRange);
    $('graph-range-note').innerHTML=overviewRange?bi(overviewRange.exclusive?`蓝色：${overviewRange.labelZh}的已记录修改位置；尚未按工程答案确定。`:`蓝色：${getName(overviewRange.id).nameZh}及其可能联动项目的修改范围；尚未按工程答案确定。`,overviewRange.exclusive?`Blue: recorded amendment passages for ${overviewRange.labelEn}; not evaluated against project answers.`:`Blue: potential amendment scope of ${getName(overviewRange.id).nameEn} and its linked items; not evaluated against project answers.`)+button('overview-clear-range','清除高亮','Clear highlighting'):'';
    const p=pageData(),v=current(),focused=v?anchorsFor(v).filter(r=>r.docId===docId&&r.page===page):[];
    const marks=(state.blank&&state.guidanceMode!=='minimum'?[]:data.sourceUnits).filter(u=>u.docId===docId).flatMap(u=>rangesForUnit(u.id).filter(r=>r.page===page).map(r=>({...r,unit:u})));
    const human=state.variables.filter(v=>v.status!=='excluded').flatMap(v=>(v.sourceAnchors||[]).filter(r=>r.docId===docId&&r.page===page).map(r=>({...r,id:v.id})));
    const targets=[...(!overviewRange?.exclusive&&v?(draft?.kind==='targets'?draft.targets:(v.editTargets||[])):[]),...(overviewRange?.targets||[])].filter(r=>r.docId===docId&&r.page===page);
    const picked=[draft?.a,draft?.b].filter(r=>r&&r.docId===docId&&r.page===page);
    const inside=(rs,i)=>rs.some(r=>containsWord(r,i));
    $('paper').innerHTML=`<img src="${escape(p.image)}" alt="${escape(doc().shortTitle)} 第 ${page} 页 / Page ${page}" draggable="false"><div class="words" aria-label="可框选原文 / Selectable original text">${p.words.map((w,i)=>`<span tabindex="0" class="word ${inside(marks,i)?'marked':''} ${inside(focused,i)?'focused':''} ${inside(human,i)?'human':''} ${inside(targets,i)?'target':''} ${inside(picked,i)?'picked':''}" data-word="${i}" style="left:${w.x/p.width*100}%;top:${w.y/p.height*100}%;width:${w.w/p.width*100}%;height:${w.h/p.height*100}%;font-size:${w.h/p.width*100}cqw" aria-label="${escape(w.text)}">${escape(w.text)} </span>`).join('')}</div><div class="selectionbox" id="selectionbox" hidden></div>${marks.filter((m,i,a)=>a.findIndex(n=>n.unit.id===m.unit.id)===i).map((m,i)=>{const w=p.words[m.start];return `<button class="note-pin ${v&&(v.sourceUnitIds||[]).includes(m.unit.id)?'active':''}" data-unit="${escape(m.unit.id)}" style="top:${w.y/p.height*100}%" aria-label="查看这段说明关联的问题 ${i+1} / Review passage ${i+1}">${unitVars(m.unit).length||'+'}</button>`;}).join('')}`;
  }
  function goPage(n,range){
    if(!Number.isInteger(n)||n<1||n>pages().length){$('page-number').value=page;return;}
    clearSelection();page=n;renderHeader();renderPage();$('reading-area').scrollTop=0;
    if(range)requestAnimationFrame(()=>$('reading-area').scrollTop=Math.max(0,pageData().words[range.wordIds?.[0]??range.start].y/pageData().height*$('paper').offsetHeight-90));
  }
  function navigateVariable(v){
    const a=anchorsFor(v).find(x=>x.docId===docId)||anchorsFor(v)[0];
    if(a){docId=a.docId;goPage(a.page,a);return true;}
    const unit=(v.sourceUnitIds||[]).map(id=>source.anchors[id]).find(a=>a?.pageHint&&a.docId===docId);
    if(unit)goPage(unit.pageHint);
    return false;
  }
  function row(v){const anchors=anchorsFor(v),located=anchors.some(a=>a.docId===docId);return `<button class="list-item ${v.status==='confirmed'?'reviewed':''} ${v.status==='excluded'?'excluded':''}" data-select="${escape(v.id)}">${bi(v.nameZh,v.nameEn)}<small>${v.parentId?'明细 / Detail · ':''}${statusNames[v.status].join(' / ')}${located?'':anchors.length?' · 原文在 / Source in '+escape(docNames([...new Set(anchors.map(a=>a.docId))])):' · 原文位置待确认 / Page location to check'}</small></button>`;}
  function showCatalog(){
    openPanel('catalog','已识别的问题','Recognized questions');
    $('drawer-body').innerHTML=`<input class="search" id="question-search" aria-label="搜索问题 / Search questions" placeholder="搜索名称或原文 / Search" value="${escape(search)}"><div id="count" class="subtle muted"></div><div class="actions">${button('save-pack','保存核对结果','Save review')}${button('load-pack','载入核对结果','Load review')}</div><div id="question-list"></div><details><summary>${bi('本次调整记录','Changes in this preview')}</summary><div id="history"></div>${button('download','下载可读记录','Download readable notes')}${button('reset','重新开始试用','Start over','','text reset-link')}</details>`;
    fillCatalog();$('question-search').oninput=e=>{search=e.target.value;fillCatalog();};
    $('history').innerHTML=state.events.slice(0,12).map(e=>`<p class="subtle">${bi(e.zh,e.en)}</p>`).join('')||bi('还没有调整。','No changes yet.');
  }
  function fillCatalog(){const all=variables(),q=search.trim().toLowerCase(),vs=all.filter(v=>!q||[v.nameZh,v.nameEn,...(v.evidence||[]).map(e=>e.quote)].join(' ').toLowerCase().includes(q));$('count').innerHTML=bi(`${vs.length} 项 · 包含明细和核对事项`,`${vs.length} items · including details and checks`);$('question-list').innerHTML=vs.map(row).join('');}
  function selectQuestion(id,navigate=true){if(overviewOpen)closeOverview();if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');selectedId=id;if(!current().docIds.includes(docId))docId=current().docIds[0];if(navigate)navigateVariable(current());clearSelection();renderHeader();renderPage();showQuestion();}
  function showUnit(id){
    if(draft)return;
    const u=data.sourceUnits.find(x=>x.id===id),vs=unitVars(u);selectedId='';openPanel('unit','这段原文提到了什么？','What does this passage cover?');
    $('drawer-body').innerHTML=`<p class="subtle muted">${bi(u.locationZh,u.locationEn)}</p>${u.incompleteSource?note('这条说明的范围仍待补充核对。','The full scope of this note still needs checking.'):''}${quote(u.quote)}${vs.map(row).join('')||note('这段尚未关联问题。可在原件上划选文字补充。','No linked question yet. Select words on the page to add one.')}<p class="subtle muted">${bi('黄色标记指向整段说明；具体词语可重新划选。','The highlight covers the source note. Select specific words to refine it.')}</p>`;
    renderPage();
  }
  function showQuestion(){
    const v=current();if(!v)return showCatalog();openPanel('question','核对这一项','Review this item');
    const exact=anchorsFor(v).some(a=>a.docId===docId),children=state.variables.filter(x=>x.parentId===v.id),a=affected(state,v.id);
    $('drawer-body').innerHTML=`${status(v)}<h3>${bi(v.nameZh,v.nameEn)}</h3><span class="chip">${bi(v.type.zh,v.type.en)}</span>${v.role.kind==='fixed'?`<p>${bi('使用固定内容','Use fixed content')}：${escape(v.fixedValue)}</p>`:''}${!exact?note('这项的原文位置还未唯一确认。请对照引文，在页面上重新划选并关联。','Its exact page location is not confirmed. Compare the quote, then select and link the right words.'):''}${v.docIds.length>1?`<p class="subtle">${bi('这项在多个文件中共用一个答案。','This item shares one answer across several documents.')}</p>`:''}
    ${v.parentId?`<p class="subtle">${bi('所属资料组：','Belongs to:')} ${bi(getName(v.parentId).nameZh,getName(v.parentId).nameEn)}</p>`:''}${v.options?.length?`<p class="subtle">${bi('可以选择：','Available answers:')}</p>${v.options.map(o=>`<div class="chip">${bi(o.labelZh,o.labelEn)}</div>`).join('')}`:''}
    ${v.status==='excluded'?`<p class="subtle">${escape(v.reason)}</p>${v.replacedBy?note('这是已拆分或合并的原记录，可用撤销恢复。','This is the original record after a split or merge. Use Undo to restore.'):button('restore','恢复这项','Restore item')}`:`<div class="actions">${button('review','确认','Confirm','','primary')}${button('correct','这里识别错了','Correct this')}</div><p class="subtle muted">${bi('使用这项内容的文件：','Used in:')} ${escape(docNames(v.docIds))}</p>`}
    ${v.originId?`<div class="notice">${bi('原来的一项已拆开，分别核对：','The original item is now split. Review each question:')}${state.variables.filter(x=>x.originId===v.originId&&x.status!=='excluded').map(row).join('')}</div>`:''}${v.correction?.needsRebind?note('关联问题已经变化，请重新确认条件。','A linked question changed. Review the conditions again.'):''}
    <details><summary>${bi('原文依据','Source wording')}</summary>${(v.sourceAnchors||[]).map((r,i)=>`<button class="list-item" data-anchor="${i}">${bi(`${docNames([r.docId])} · 第 ${r.page} 页 · 手动划选`,`${docNames([r.docId])} · Page ${r.page} · selected by reviewer`)}</button>${quote(r.text)}${button('unlink-source','这处原文关联错了，移除','Remove this incorrect source link',`data-index="${i}"`)}`).join('')}${!(v.sourceAnchors||[]).length?(v.evidence||[]).filter(e=>e.docId===docId).slice(0,3).map(e=>quote(e.quote)).join(''):''}<p class="subtle muted">${bi('中文是阅读辅助，原文保留英文。','Chinese wording is a reading aid; original wording remains in English.')}</p></details>
    <details><summary>${bi('回答后会影响哪里？','What changes with this answer?')}</summary><p class="subtle">${bi(businessText(v.guidance?.zh||''),businessText(v.guidance?.en||'','en'))}</p>${renderConditions(v)}${(v.targets||[]).map(t=>`<p class="subtle">${bi(t.labelZh,t.labelEn)}</p>`).join('')}${(v.editTargets||[]).map((r,i)=>`<button class="list-item" data-target="${i}">${bi(`${docNames([r.docId])} · 第 ${r.page} 页 · ${actionNames[r.action]?.[0]||'待决定'}`,`${docNames([r.docId])} · Page ${r.page} · ${actionNames[r.action]?.[1]||'To decide'}`)}</button>${quote(r.text)}${targetMeaning(r,v)}`).join('')}<div class="actions">${button('conditions','调整条件','Edit conditions')}${button('targets','划选修改位置','Select affected text')}</div></details>
    ${children.length?`<details><summary>${bi('这项包含的明细','Details included')}</summary>${children.map(row).join('')}</details>`:''}${a.length?`<details><summary>${bi('关联的其他问题','Other linked questions')}</summary>${a.map(row).join('')}</details>`:''}${v.manual?note(v.status==='confirmed'?'已保留人工确认和本次调整记录。':'已保留调整记录。这次调整仍需人工确认。',v.status==='confirmed'?'The human confirmation and change history are retained.':'The change history is retained. This change still needs review.'):''}`;
  }
  function renderConditions(v){
    const c=v.correction;
    if(c?.kind==='split')return `<div class="condition">${bi(c.mode==='all'?'以下两个问题需要同时为“是”':c.mode==='any'?'以下两个问题有任一个为“是”即可':'共同作用方式待确认',c.mode==='all'?'Both answers must be Yes':c.mode==='any'?'Either answer may be Yes':'How these combine is not yet confirmed')}${c.inputIds.map(id=>`<p>${bi(getName(id).nameZh,getName(id).nameEn)}</p>`).join('')}</div>`;
    if(c?.kind==='rule')return ruleBranches(c).map((branch,i)=>`<div class="condition"><strong>${bi(`情况 ${i+1}`,`Case ${i+1}`)}</strong>${branch.scopeId?`<p>${bi('针对同一条明细：','For the same detail item:')}${bi(getName(branch.scopeId).nameZh,getName(branch.scopeId).nameEn)} · ${bi(({each:'逐条判断',any:'任意一条符合',all:'每条都符合',none:'没有任何一条符合'})[branch.scopeMode]||'逐条判断',({each:'Each item',any:'At least one item',all:'Every item',none:'No item'})[branch.scopeMode]||'Each item')}</p>`:''}${branch.scopeId?scopeNotice(branch.scopeMode):''}${v.role.kind==='derived'&&branch.resultValue!==undefined?`<p>${bi('这个问题的答案：','Answer for this question:')}${v.type.kind==='boolean'?(typeof branch.resultValue==='boolean'?bi(branch.resultValue?'是':'否',branch.resultValue?'Yes':'No'):bi('答案待核对','Review the answer')):bi(v.options?.find(o=>o.value===branch.resultValue)?.labelZh||'答案待核对',v.options?.find(o=>o.value===branch.resultValue)?.labelEn||'Review the answer')}</p>`:''}${bi(branch.mode==='all'?'同时满足以下条件':branch.mode==='any'?'满足以下任一条件':'这些条件如何共同作用尚未确认',branch.mode==='all'?'All conditions must hold':branch.mode==='any'?'Any condition may hold':'How these conditions combine is not confirmed')}${branch.clauses.map(x=>{const option=getName(x.inputId).options?.find(o=>o.value===x.value);return `<p>${bi(getName(x.inputId).nameZh,getName(x.inputId).nameEn)} ${bi(...operators[x.operator])} ${option?bi(option.labelZh,option.labelEn):escape(x.value)}</p>`;}).join('')}<p>→ ${bi(branch.effectZh,branch.effectEn)}</p></div>`).join('');
    return (v.relations||[]).flatMap(r=>r.branches||[]).map(b=>`<div class="condition">${bi(b.whenZh,b.whenEn)}<p>→ ${bi(b.resultZh,b.resultEn)}</p></div>`).join('');
  }
  const actionNames={keep:['保留','Keep'],delete:['删除','Delete'],fill:['填写','Fill in'],replace:['替换','Replace'],unused:['标为不适用','Mark not used'],check:['待决定','To decide']};
  const operators={yes:['选“是”','Is Yes'],no:['选“否”','Is No'],known:['已填写','Is provided'],empty:['已明确填写为空','Confirmed empty'],notempty:['已填写且有内容','Provided with content'],eq:['等于','Equals'],has:['包含这一项','Includes this option'],lacks:['不包含这一项','Does not include this option'],gt:['多于','More than'],lt:['少于','Less than'],ge:['不少于','At least'],le:['不多于','At most']};
  function targetMeaning(target,v){
    const branch=ruleBranches(v.correction).find(b=>b.id===target.branchId);
    const joined=target.valueMode==='join',input=getName(target.valueSourceId),list=getName(target.listId);
    const usage=joined?bi(`合并清单“${list.nameZh||'待重新确认'}”中所有条目的“${input.nameZh||'待重新确认'}”答案；分隔符：${JSON.stringify(target.separator)}`,`Join the “${input.nameEn||'review required'}” answers from all items in “${list.nameEn||'review required'}”; separator: ${JSON.stringify(target.separator)}`):bi('使用这项的答案：','Use the answer from:')+bi(input.nameZh,input.nameEn);
    return `<p class="subtle">${bi(target.branchId?(branch?.effectZh||'适用情况需要重新确认'):'所有情况',target.branchId?(branch?.effectEn||'Review the applicable case'):'All cases')}</p>${target.valueSourceId?`<p class="subtle">${usage}</p>${target.prefix||target.suffix?quote((target.prefix||'')+(joined?'[合并后的答案 / Joined answers]':'[答案 / Answer]')+(target.suffix||'')):''}`:target.replacementEn?quote(target.replacementEn):''}`;
  }
  function startDraft(kind,extra={}){overviewRange=null;draft={kind,id:selectedId,docId,...extra};integrationChanged(true);clearSelection();renderDraft();renderPage();}
  function buttons(saveZh='记录调整',saveEn='Save change'){return `<p class="error" id="form-error" role="alert"></p><div class="actions">${button('save',saveZh,saveEn,'','primary')}${button('cancel','取消','Cancel')}</div>`;}
  function form(body){$('drawer-body').innerHTML=`<form id="edit-form">${body}</form>`;$('edit-form').onsubmit=e=>{e.preventDefault();saveDraft();};$('edit-form').onchange=e=>{if(e.target.name==='type'||/^(action|source|valueMode|input|op|scope)-/.test(e.target.name)){rememberDraft();renderDraft();}};}
  function captureBox(key,zh,en,r){return `<section class="pick ${capture===key?'active':''}"><strong>${bi(zh,en)}</strong>${r?`<p>${escape(r.text)}</p><p class="subtle muted">${escape(docNames([r.docId]))} · 第 ${r.page} 页 / Page ${r.page}</p>`:`<p class="subtle muted">${bi('请在左侧划选对应词语。','Select the matching words on the document.')}</p>`}${button('pick','划选原文','Select source words',`data-pick="${key}"`)}</section>`;}
  function rememberDraft(){if(!draft)return;const f=$('edit-form');if(f)draft.fields=Object.fromEntries(new FormData(f));}
  function renderDraft(){
    const v=current(),f=draft.fields||{};
    if(draft.kind==='add'){
      openPanel('edit','补充一个问题','Add a missing question');form(`${quote(draft.anchor.text)}${field('nameZh','要问什么？','Question in Chinese',f.nameZh)}${field('nameEn','同一问题的英文','The same question in English',f.nameEn)}${choose('type','怎么回答？','Answer format',opts(typeNames,f.type||'boolean'))}${window.ReviewFields.render({variables:state.variables,docId,item:null,values:f})}${buttons('补充并关联这段原文','Add and link this passage')}`);
    }else if(draft.kind==='link'){
      openPanel('edit','这段话对应哪个问题？','Which question does this refer to?');form(`${quote(draft.anchor.text)}<p class="subtle">${bi('可以选择其他文件中的同一项，共用一个答案，并保留各处原文。两版 SCC 保持分开。','Choose the same item from another document to share one answer and retain each source. The SCC editions remain separate.')}</p>${choose('target','选择已有问题','Choose an existing question',state.variables.filter(x=>x.status!=='excluded'&&compatible(x,{docIds:[draft.docId]})&&canShareSources(state,x,[draft.docId])).map(x=>`<option value="${escape(x.id)}" ${f.target===x.id?'selected':''}>${escape(x.nameZh)} / ${escape(x.nameEn)} — ${escape(docNames(x.docIds))}</option>`).join(''))}${buttons('共用这项并关联原文','Share this item and link the source')}`);
    }else if(draft.kind==='edit'){
      openPanel('edit','改名称或回答方式','Edit the name or answer');form(`${field('nameZh','中文问题','Question in Chinese',f.nameZh??v.nameZh)}${field('nameEn','英文问题','Question in English',f.nameEn??v.nameEn)}${choose('type','怎么回答？','Answer format',opts(typeNames,f.type??v.type.kind))}${window.ReviewFields.render({variables:state.variables,docId,item:v,values:f})}${choose('role','这项内容如何确定？','How is this content determined?',opts({input:['需要填写','Ask the reviewer'],derived:['由其他答案决定','Determined by other answers'],verification:['需人工核对','Requires a manual check'],fixed:['直接使用固定内容','Use fixed content']},f.role??(['derived','fixed','verification'].includes(v.role.kind)?v.role.kind:'input')))}${field('fixed','固定内容（如适用）','Fixed content, if applicable',f.fixed??v.fixedValue)}${buttons()}`);
    }else if(draft.kind==='split'){
      openPanel('edit','其实是两个问题','Split into two questions');
      const answerKind=['record','list','multiselect'].includes(v.type.kind)?'text':v.type.kind;
      form(`<p class="subtle">${bi('原问题保留在记录中。先分别划选，再命名。','The original stays in the record. Select each phrase, then name the questions.')}</p>${captureBox('a','第一个问题的原文','Source for the first question',draft.a)}${field('aZh','第一个问题','First question in Chinese',f.aZh||'')}${field('aEn','英文','In English',f.aEn||'')}${choose('aType','怎么回答第一问？','Answer format for the first question',opts(typeNames,f.aType||answerKind))}${captureBox('b','第二个问题的原文','Source for the second question',draft.b)}${field('bZh','第二个问题','Second question in Chinese',f.bZh||'')}${field('bEn','英文','In English',f.bEn||'')}${choose('bType','怎么回答第二问？','Answer format for the second question',opts(typeNames,f.bType||answerKind))}${choose('mode','这两个条件怎么共同起作用？','How do these conditions work together?',opts({all:['两项都选“是”','Both answers must be Yes'],any:['有一项选“是”即可','Either answer may be Yes'],unknown:['先分别记录，之后设置条件','Set the conditions later']},f.mode||(answerKind==='boolean'?'all':'unknown')))}${buttons('用两个问题替换','Replace with two questions')}`);
    }else if(draft.kind==='merge'){
      openPanel('edit','两处在问同一件事','Merge duplicate questions');form(`<p class="subtle">${bi('两版 SCC 分开处理。合并后，双方原文位置都保留。','The SCC editions stay separate. Both source locations are retained.')}</p>${choose('target','保留哪一个问题？','Which question should remain?',state.variables.filter(x=>x.id!==v.id&&compatible(x,v)&&x.status!=='excluded'&&x.type.kind===v.type.kind&&!activeChildren(x)).map(x=>`<option value="${escape(x.id)}">${escape(x.nameZh)} / ${escape(x.nameEn)} — ${escape(docNames(x.docIds))}</option>`).join(''))}${field('reason','为何认为是同一件事？','Why do these mean the same thing?',f.reason,'textarea')}${buttons('合并这两个问题','Merge these questions')}`);
    }else if(draft.kind==='remove'){
      openPanel('edit','这个问题不需要','Remove this question');form(`${field('reason','为什么不需要？','Why is this question unnecessary?',f.reason,'textarea')}<p class="subtle">${bi('如果答案已经确定，请用“改名称或回答方式”设置固定内容。','If the answer is known, use Edit name or answer to set fixed content.')}</p>${buttons('保留记录，不再提问','Keep the record, stop asking')}`);
    }else if(draft.kind==='help'){
      openPanel('edit','还需要核对什么？','What still needs checking?');form(`${field('reason','需要补充的信息','Information still needed',f.reason??v.reason,'textarea')}${buttons('记录待核对事项','Save the request')}`);
    }else if(draft.kind==='targets'){
      openPanel('edit','回答后修改哪些文字？','Which text changes with the answer?');
      form(`<p class="subtle">${bi('每处选择适用情况和处理方式。同一段可在不同情况下采用不同处理。','Choose a case and action for each passage. The same passage can have different actions in different cases.')}</p>${(draft.targets||[]).map((r,i)=>targetEditor(r,i,v,f)).join('')}${button('pick','再划选一处','Select another passage','data-pick="target"')}${buttons()}`);
    }else if(draft.kind==='conditions'){
      openPanel('edit','哪些情况下怎样修改？','What changes in each case?');
      form(`${draft.branches.map((b,i)=>branchEditor(b,i,v,f)).join('')}${button('add-case','添加另一种情况','Add another case')}${buttons()}`);

    }
  }
  function targetEditor(r,i,v,f){
    const action=f['action-'+i]||r.action||'check', branchId=f['branch-'+i]??r.branchId??'';
    const branches=ruleBranches(v.correction), branchOptions=`<option value="">所有情况 / All cases</option>${branchId&&!branches.some(b=>b.id===branchId)?`<option value="${escape(branchId)}" selected>原情况已变化，请重选 / Case changed; choose again</option>`:''}`+branches.map((b,n)=>`<option value="${escape(b.id)}" ${branchId===b.id?'selected':''}>${escape(`情况 ${n+1} / Case ${n+1}: ${b.effectZh}`)}</option>`).join('');
    const sourceId=f['source-'+i]??r.valueSourceId??(action==='fill'&&!['record','list'].includes(v.type.kind)?v.id:'');
    const valueUnavailable=sourceId&&!state.variables.some(x=>x.id===sourceId&&x.status!=='excluded'&&compatible(x,v)&&!['record','list'].includes(x.type.kind));
    const valueOptions=`<option value="">填写固定新文字 / Enter fixed wording</option>${valueUnavailable?`<option value="${escape(sourceId)}" selected>来源问题已变化，请重选 / Source answer changed; choose again</option>`:''}`+state.variables.filter(x=>x.status!=='excluded'&&compatible(x,v)&&!['record','list'].includes(x.type.kind)).map(x=>`<option value="${escape(x.id)}" ${sourceId===x.id?'selected':''}>${escape(x.nameZh)} / ${escape(x.nameEn)}</option>`).join('');
    const input=getName(sourceId),listId=['boolean','number','text','date','choice'].includes(input.type?.kind)?listForInput(input):'';
    const valueMode=f['valueMode-'+i]??r.valueMode??'single';
    const joinFields=listId||r.valueMode==='join'?`${choose('valueMode-'+i,'如何使用明细答案？','How should these detail answers be used?',opts({single:['使用单条答案','Use one item’s answer'],join:['合并所有条目的答案','Join answers from all items']},valueMode))}${valueMode==='join'?`${listId?note('合并清单：'+getName(listId).nameZh,'Join items in: '+getName(listId).nameEn):note('原清单或明细已变化，请重新选择答案来源。','The list or detail field changed. Choose the answer source again.')}${field('separator-'+i,'条目之间加的分隔符','Separator between items',f['separator-'+i]??r.separator??', ')}`:''}`:'';
    return `<section class="pick">${quote(r.text)}<p>${escape(docNames([r.docId]))} · 第 ${r.page} 页 / Page ${r.page}</p>${choose('branch-'+i,'适用于哪种情况？','Which case applies?',branchOptions)}${choose('action-'+i,'怎样处理这段文字？','What happens to this text?',opts(actionNames,action))}${['fill','replace'].includes(action)?`${choose('source-'+i,'新内容从哪里来？','Where does the new content come from?',valueOptions)}${sourceId?`${joinFields}${field('prefix-'+i,'答案前加的文字（可选）','Text before the answer, optional',f['prefix-'+i]??r.prefix??'')}${field('suffix-'+i,'答案后加的文字（可选）','Text after the answer, optional',f['suffix-'+i]??r.suffix??'')}`:`${field('replacementEn-'+i,'新文字（原件使用）','New wording for the source',f['replacementEn-'+i]??r.replacementEn??'','textarea')}${field('replacementZh-'+i,'中文说明（可选）','Chinese explanation, optional',f['replacementZh-'+i]??r.replacementZh??'','textarea')}`}`:''}${button('remove-target','移除这处','Remove passage',`data-index="${i}"`)}</section>`;
  }
  function scopeNotice(mode){return ['all','none'].includes(mode)?note('请先确认清单已完整；未填不代表没有。选择“每条都”时，还须至少有一条明细。','Confirm the list is complete; unanswered does not mean absent. Every item also requires at least one item.'):'';}
  function branchEditor(branch,i,v,f){
    const resultValue=f['result-'+i]??(v.type.kind==='boolean'&&typeof branch.resultValue==='boolean'||v.type.kind==='choice'&&typeof branch.resultValue==='string'?String(branch.resultValue):'');
    const resultOptions='<option value="">请选择 / Choose an answer</option>'+ (v.type.kind==='boolean'?opts({true:['是','Yes'],false:['否','No']},resultValue):(v.options||[]).map(o=>`<option value="${escape(o.value)}" ${o.value===resultValue?'selected':''}>${escape(o.labelZh)} / ${escape(o.labelEn)}</option>`).join(''));
    const scopeId=f['scope-'+i]??branch.scopeId??'',scopes=state.variables.filter(x=>x.type.kind==='list'&&x.status!=='excluded'&&compatible(inputContext(x),inputContext(v)));
    const scopeOptions=`<option value="">按整个项目判断 / Judge for the whole project</option>${scopeId&&!scopes.some(x=>x.id===scopeId)?`<option value="${escape(scopeId)}" selected>明细组已变化，请重选 / Detail list changed; choose again</option>`:''}`+scopes.map(x=>`<option value="${escape(x.id)}" ${x.id===scopeId?'selected':''}>${escape(x.nameZh)} / ${escape(x.nameEn)} — 每一条分别判断 / Each item separately</option>`).join('');
    return `<section class="pick"><strong>${bi(`情况 ${i+1}`,`Case ${i+1}`)}</strong>${scopes.length||scopeId?choose('scope-'+i,'这组条件针对谁？','What do these conditions describe?',scopeOptions):''}${scopeId?choose('scopeMode-'+i,'明细怎样决定这次修改？','How do these details control the change?',opts({each:['每条分别判断、分别修改','Check and amend each item separately'],any:['有一条符合，就修改整段','At least one item qualifies; amend the clause'],all:['每条都符合，才修改整段','Every item must qualify; amend the clause'],none:['没有任何一条符合，才修改整段','No item qualifies; amend the clause']},f['scopeMode-'+i]||branch.scopeMode||'each')):''}${choose('mode-'+i,'以下条件如何一起起作用？','How do the conditions combine?',opts({all:['全部满足','All must hold'],any:['满足其中一个','Any may hold']},f['mode-'+i]||branch.mode))}${branch.clauses.map((c,j)=>{
      const key=i+'-'+j, inputId=f['input-'+key]||c.inputId, input=getName(inputId), op=f['op-'+key]||c.operator;
      const value=f['value-'+key]??c.value??'';
      const inputs=state.variables.filter(x=>compatible(inputContext(x),inputContext(v))&&x.status!=='excluded'),inputOptions=(inputs.some(x=>x.id===inputId)?'':`<option value="${escape(inputId)}" selected>原问题已变化，请重选 / Question changed; choose again</option>`)+inputs.map(x=>`<option value="${escape(x.id)}" ${inputId===x.id?'selected':''}>${escape(x.nameZh)} / ${escape(x.nameEn)}</option>`).join('');
      const answerOptions=`<option value="">请选择 / Choose an answer</option>`+((input.options||[]).some(o=>o.value===value)||!value?'':`<option value="${escape(value)}" selected>原答案已变化，请重选 / Answer changed; choose again</option>`)+(input.options||[]).map(o=>`<option value="${escape(o.value)}" ${value===o.value?'selected':''}>${escape(o.labelZh)} / ${escape(o.labelEn)}</option>`).join('');
      return `<section class="condition">${choose('input-'+key,'看哪个问题的答案？','Which answer?',inputOptions)}${choose('op-'+key,'答案符合什么条件？','What must the answer be?',opts(operators,op))}${(input.type?.kind==='choice'&&op==='eq'||input.type?.kind==='multiselect'&&['has','lacks'].includes(op))?choose('value-'+key,'选择哪项？','Which answer?',answerOptions):['eq','ge','le','gt','lt'].includes(op)?field('value-'+key,'比较值（如适用）','Value, if applicable',value):''}${branch.clauses.length>1?button('remove-condition','移除这个条件','Remove condition',`data-case="${i}" data-index="${j}"`):''}</section>`;
    }).join('')}${button('add-condition','再加一个条件','Add a condition',`data-case="${i}"`)}${v.role.kind==='derived'&&['boolean','choice'].includes(v.type.kind)?choose('result-'+i,'这时，这个问题的答案是？','What is the answer in this case?',resultOptions):''}${scopeId?scopeNotice(f['scopeMode-'+i]||branch.scopeMode):''}${field('effectZh-'+i,'满足后怎样修改？','What changes when these hold?',f['effectZh-'+i]??branch.effectZh??'','textarea')}${field('effectEn-'+i,'同一结果的英文','The same effect in English',f['effectEn-'+i]??branch.effectEn??'','textarea')}${draft.branches.length>1?button('remove-case','移除这种情况','Remove case',`data-case="${i}"`):''}</section>`;
  }
  function readBranchFields(fd,d){return d.branches.map((b,i)=>{
    const v=current(),result={...b,scopeId:String(fd.get('scope-'+i)||''),scopeMode:String(fd.get('scopeMode-'+i)||''),mode:fd.get('mode-'+i),clauses:b.clauses.map((_,j)=>({inputId:fd.get('input-'+i+'-'+j),operator:fd.get('op-'+i+'-'+j),value:String(fd.get('value-'+i+'-'+j)||'')})),effectZh:String(fd.get('effectZh-'+i)||'').trim(),effectEn:String(fd.get('effectEn-'+i)||'').trim()};
    delete result.resultValue;
    const answer=fd.get('result-'+i);
    if(v.role.kind==='derived'&&v.type.kind==='boolean'&&['true','false'].includes(answer))result.resultValue=answer==='true';
    if(v.role.kind==='derived'&&v.type.kind==='choice'&&answer)result.resultValue=String(answer);
    return result;
  });}
  function readTargetFields(fd,d){return d.targets.map((r,i)=>{
    const {valueSourceId,replacementEn,replacementZh,prefix,suffix,valueMode,listId,separator,needsRebind,...passage}=r;
    const result={...passage,action:fd.get('action-'+i)||'check',branchId:String(fd.get('branch-'+i)||'')};
    if(['fill','replace'].includes(result.action)){result.valueSourceId=String(fd.get('source-'+i)||'');result.replacementEn=result.valueSourceId?'':String(fd.get('replacementEn-'+i)||'').trim();result.replacementZh=result.valueSourceId?'':String(fd.get('replacementZh-'+i)||'').trim();if(result.valueSourceId){result.prefix=String(fd.get('prefix-'+i)||'');result.suffix=String(fd.get('suffix-'+i)||'');if(fd.get('valueMode-'+i)==='join'){result.valueMode='join';result.listId=listForInput(getName(result.valueSourceId));result.separator=String(fd.get('separator-'+i)??', ');}}}
    return result;
  });}
  function validateJoinTarget(r,v){
    if(r.valueMode!=='join')return;
    const input=getName(r.valueSourceId),list=getName(r.listId);
    if(!['fill','replace'].includes(r.action)||!['boolean','number','text','date','choice'].includes(input.type?.kind)||input.status==='excluded'||!r.listId||list.type?.kind!=='list'||list.status==='excluded'||listForInput(input)!==r.listId||!compatible(inputContext(input),inputContext(v))||!compatible(inputContext(list),inputContext(v))||typeof r.separator!=='string')throw Error('请重新选择同版清单中的单项明细，并确认分隔符。 / Choose a scalar detail field from the same edition’s list and review its separator.');
  }
  function validateTargets(v){
    for(const r of v.editTargets||[]){
      if(r.needsRebind)throw Error('提供新内容的问题已变化，请重新核对修改位置。 / The source answer changed. Review the affected text again.');
      if(r.action==='check')throw Error('请决定每处文字的处理方式。 / Choose an action for every passage.');
      if(r.branchId&&!ruleBranches(v.correction).some(b=>b.id===r.branchId))throw Error('请重新选择这处文字适用的情况。 / Select an existing case for this passage.');
      validateJoinTarget(r,v);
      if(['fill','replace'].includes(r.action)){
        const x=r.valueSourceId&&getName(r.valueSourceId);
        if(r.valueSourceId&&(!x?.type||x.status==='excluded'||!compatible(x,v)||['record','list'].includes(x.type.kind)))throw Error('请重新选择提供新内容的具体问题。 / Select an active detail question for the new content.');
        if(!r.valueSourceId&&!r.replacementEn?.trim())throw Error('请填写新文字，或选择提供答案的问题。 / Enter new wording or choose the answer to use.');
      }
    }
  }
  function required(fd,key){const value=String(fd.get(key)||'').trim();if(!value)throw Error('请补全这项内容，并填写中英文名称。 / Please complete the fields in both languages.');return value;}
  function saveDraft(){
    try{
      const fd=new FormData($('edit-form')),v=current(),d=draft;
      if(d.kind==='add'){const nameZh=required(fd,'nameZh'),nameEn=required(fd,'nameEn'),kind=fd.get('type'),extra=window.ReviewFields.read(fd,{variables:state.variables,docId:d.docId,item:null,compatible});apply({type:'ADD',docId:d.docId,anchor:d.anchor,reason:'人工从原件补充 / Added from the source',values:{...extra,nameZh,nameEn,questionZh:nameZh,questionEn:nameEn,type:{kind,zh:typeNames[kind][0],en:typeNames[kind][1]},role:{kind:'input',zh:'项目资料',en:'Project information'}}});}
      else if(d.kind==='link'){const target=state.variables.find(x=>x.id===fd.get('target'));if(!target||!compatible(target,{docIds:[d.docId]})||!canShareSources(state,target,[d.docId]))throw Error('请选择可在当前模板组共用的问题，两版 SCC 保持分开。 / Choose an item compatible with this template set; SCC editions remain separate.');selectedId=target.id;apply({type:'LINK',id:target.id,anchor:d.anchor});}
      else if(d.kind==='edit'){const kind=fd.get('type'),role=fd.get('role'),nameZh=required(fd,'nameZh'),nameEn=required(fd,'nameEn'),extra=window.ReviewFields.read(fd,{variables:state.variables,docId,item:v,compatible});if(activeChildren(v)&&!['record','list'].includes(kind))throw Error('这项仍有明细，请先逐项调整。 / Review the detail fields first.');const fixedValue=role==='fixed'?required(fd,'fixed'):'';apply({type:'EDIT',id:v.id,values:{...extra,nameZh,nameEn,questionZh:nameZh,questionEn:nameEn,type:{kind,zh:typeNames[kind][0],en:typeNames[kind][1]},role:{kind:role,zh:roleNames[role][0],en:roleNames[role][1]},fixedValue}});}
      else if(d.kind==='split'){if(!d.a||!d.b)throw Error('请先为两个问题分别划选原文。 / Select a source phrase for each question.');if(!compatible(v,{docIds:[d.a.docId,d.b.docId]}))throw Error('两版SCC保持分开，请分别建立问题。 / Keep the SCC editions separate and create independent questions.');if(activeChildren(v))throw Error('请先选择具体明细再拆分。 / Choose an individual detail to split.');if(fd.get('mode')!=='unknown'&&(fd.get('aType')!=='boolean'||fd.get('bType')!=='boolean'))throw Error('只有是非问题可直接组合；请选择之后设置条件。 / For other answer formats, choose Set the conditions later.');apply({type:'SPLIT',id:v.id,mode:fd.get('mode'),items:['a','b'].map(k=>{const kind=fd.get(k+'Type');return {nameZh:required(fd,k+'Zh'),nameEn:required(fd,k+'En'),type:{kind,zh:typeNames[kind][0],en:typeNames[kind][1]},sourceAnchors:[d[k]]};})});}
      else if(d.kind==='merge'){const target=state.variables.find(x=>x.id===fd.get('target'));if(!target||target.id===v.id||!compatible(target,v)||target.type.kind!==v.type.kind||activeChildren(v)||activeChildren(target))throw Error('请选择同版本、回答方式相同的单个问题。 / Choose a same-edition question with the same answer format.');if(!canShareSources(state,v,target.docIds)||!canShareSources(state,target,v.docIds))throw Error('关联问题已使用另一版SCC，不能合并这两个来源。 / Linked questions use the other SCC edition. Keep these sources separate.');if(v.parentId!==target.parentId||JSON.stringify(v.options||[])!==JSON.stringify(target.options||[]))throw Error('两项的资料组或可选答案不同，请先核对后再合并。 / Their detail groups or available answers differ. Review them before merging.');if(v.correction&&target.correction)throw Error('双方都已设置条件，请先核对双方条件，暂不直接合并。 / Both have manual conditions. Review them before merging.');if(v.role.kind==='fixed'&&target.role.kind==='fixed'&&v.fixedValue!==target.fixedValue)throw Error('两项的固定内容不同，请先核对，暂不合并。 / The fixed values differ. Review them before merging.');apply({type:'MERGE',id:v.id,targetId:target.id,reason:required(fd,'reason')});}
      else if(d.kind==='remove'){if(activeChildren(v))throw Error('此项包含明细，请先逐项核对。 / Review the details first.');apply({type:'EXCLUDE',id:v.id,reason:required(fd,'reason')});}
      else if(d.kind==='help'){const reason=String(fd.get('reason')||'').trim();if(!reason)throw Error('请说明还需要核对什么。 / Describe what still needs checking.');apply({type:'HELP',id:v.id,reason});}
      else if(d.kind==='targets'){
        const targets=readTargetFields(fd,d);
        if(!canShareSources(state,v,targets.map(t=>t.docId)))throw Error('这些修改位置与已有问题使用了两版SCC，请分开处理。 / These passages would mix SCC editions through existing links. Keep them separate.');
        if(!isolatedSources({...state,variables:state.variables.map(x=>x.id===v.id?{...x,editTargets:targets}:x)}))throw Error('答案来源与修改位置使用了两版SCC，请分开处理。 / Keep answer sources and affected passages within the same SCC edition.');
        for(const target of targets){if(target.branchId&&!ruleBranches(v.correction).some(b=>b.id===target.branchId))throw Error('请重新选择适用情况。 / Choose an existing case.');const x=target.valueSourceId&&getName(target.valueSourceId);if(target.valueSourceId&&(!x?.type||x.status==='excluded'||!compatible(x,v)||['record','list'].includes(x.type.kind)))throw Error('请重新选择提供新内容的具体问题。 / Choose an active detail question for the new content.');validateJoinTarget(target,v);}
        apply({type:'TARGETS',id:v.id,targets});
      }
      else if(d.kind==='conditions'){
        const branches=readBranchFields(fd,d);
        for(const branch of branches){validateBranch(branch,v);if(!branch.effectZh||!branch.effectEn)throw Error('请写清每种情况的中英文修改结果。 / Describe each case in both languages.');}
        apply({type:'RULE',id:v.id,correction:{kind:'rule',branches,otherwise:'needs_review'}});
      }
    }catch(e){$('form-error').textContent=e.message;}
  }
  function validateClauses(clauses,v){
    for(const c of clauses){const x=getName(c.inputId);if(['empty','notempty'].includes(c.operator)&&!['text','multiselect','list'].includes(x.type?.kind))throw Error('空内容判断需要文字、多选或条目清单。 / Empty/content checks require text, multiple choices or a list.');if(!x.type||x.status==='excluded'||!compatible(inputContext(x),inputContext(v)))throw Error('关联问题已移除或不属于当前版本，请重新选择。 / Select an active question from this edition.');if(['ge','le','gt','lt'].includes(c.operator)&&x.type.kind!=='number')throw Error('大小比较需要数字问题。 / At least / at most needs a numeric question.');if(['yes','no'].includes(c.operator)&&x.type.kind!=='boolean')throw Error('是／否条件需要对应的是非问题。 / Yes / No conditions need a Yes / No question.');if(['eq','ge','le','gt','lt'].includes(c.operator)&&!String(c.value||'').trim())throw Error('请填写比较值。 / Enter a comparison value.');if(['eq','ge','le','gt','lt'].includes(c.operator)&&x.type.kind==='number'&&!Number.isFinite(Number(c.value)))throw Error('请输入有效数字。 / Enter a valid number.');if(['has','lacks'].includes(c.operator)&&x.type.kind!=='multiselect')throw Error('包含条件需要多选问题。 / Includes conditions need a multiple-choice question.');if((c.operator==='eq'&&x.type.kind==='choice'||['has','lacks'].includes(c.operator))&&!(x.options||[]).some(o=>o.value===c.value))throw Error('可选答案已经变化，请重新选择。 / The available answers changed. Choose an existing option.');if(c.operator==='eq'&&['record','list','multiselect'].includes(x.type.kind))throw Error('请选具体明细，或使用“已填写”。 / Choose a detail field or use Is provided.');}
  }
  function listForInput(item){
    const seen=new Set();let parent=item;
    while(parent?.parentId&&!seen.has(parent.id)){seen.add(parent.id);parent=getName(parent.parentId);if(parent.type?.kind==='list')return parent.id;}
    return '';
  }
  function inputContext(item){
    const docs=new Set(item.docIds||[]),seen=new Set();
    while(item?.parentId&&!seen.has(item.id)){seen.add(item.id);item=getName(item.parentId);for(const doc of item.docIds||[])docs.add(doc);}
    return {docIds:[...docs]};
  }
  function validateBranch(branch,v){
    if(!['all','any'].includes(branch.mode)||!branch.clauses?.length||!branch.effectZh?.trim()||!branch.effectEn?.trim())throw Error('请补全明确条件与中英文修改结果。 / Complete the conditions and effects in both languages.');
    validateClauses(branch.clauses,v);
    if(v.role.kind==='derived'&&(v.type.kind==='boolean'&&typeof branch.resultValue!=='boolean'||v.type.kind==='choice'&&!(v.options||[]).some(o=>o.value===branch.resultValue)))throw Error('请选择这种情况下本问题的明确答案。 / Choose this question’s answer for this case.');
    const scope=branch.scopeId&&getName(branch.scopeId);
    if(branch.scopeId&&!['each','any','all','none'].includes(branch.scopeMode))throw Error('请选择明细如何决定修改。 / Choose how the list controls this change.');
    if(branch.scopeId&&(!scope?.type||scope.type.kind!=='list'||scope.status==='excluded'||!compatible(inputContext(scope),inputContext(v))))throw Error('请重新选择逐条判断的明细组。 / Choose an active list for the conditions.');
    for(const clause of branch.clauses){const listId=listForInput(getName(clause.inputId));if(listId&&listId!==branch.scopeId)throw Error('明细里的条件必须针对同一条记录，请选择对应明细组。 / Conditions on detail fields must refer to the same list item.');}
  }
  function openCorrections(){openPanel('correct','这项哪里不对？','What needs correcting?');$('drawer-body').innerHTML=`<h3>${bi(current().nameZh,current().nameEn)}</h3>${button('edit','名称或回答方式不对','Edit name or answer','','list-item')}${button('split','其实是两个问题','Split into two questions','','list-item')}${button('merge','和另一个是同一个问题','Merge duplicate questions','','list-item')}${button('remove','这个问题不需要','Remove this question','','list-item')}${button('help','还需补充信息','Need more information','','list-item')}<div class="actions">${button('back','返回这一项','Back to this item')}</div>`;}
  function setSelection(ids){
    const wordIds=visualWordIds(pageData().words,ids);
    if(!wordIds.length){clearSelection();return;}
    selection={docId,page,start:Math.min(...wordIds),end:Math.max(...wordIds)+1,wordIds,text:selectedText(pageData().words,wordIds)};
    paintSelection(wordIds);
    if(draft&&!capture)return;
    const rects=wordIds.map(i=>$('paper').querySelector(`[data-word="${i}"]`).getBoundingClientRect());
    $('selection-preview').textContent=selection.text;
    const actions=$('selectionbar').querySelector('.selection-actions'),mode=capture?'capture':'question';
    // Scroll can run between mouse down and up; keep the pressed action mounted.
    if(actions.dataset.mode!==mode){actions.innerHTML=(capture?button('use-selection','使用这些文字','Use these words'):button('selection-add','补充问题','Add a question')+button('selection-link','关联已有问题','Link a question'))+'<button data-action="clear-selection" aria-label="清除选词 / Clear selection">×</button>';actions.dataset.mode=mode;}
    $('selectionbar').hidden=false;$('selectionbar').style.left=Math.max(10,Math.min(Math.min(...rects.map(r=>r.left)),innerWidth-360))+'px';$('selectionbar').style.top=Math.max(80,Math.min(Math.max(...rects.map(r=>r.bottom))+8,innerHeight-$('selectionbar').offsetHeight-12))+'px';
  }
  function pointOnPage(e){const r=$('paper').getBoundingClientRect(),p=pageData();return {x:(e.clientX-r.left)/r.width*p.width,y:(e.clientY-r.top)/r.height*p.height};}
  const packContext=()=>({sourceKey:data.reviewSourceKey,documents:source.documents,types:Object.keys(typeNames),statuses:Object.keys(statusNames)});
  // Read-only projection of the current review. It never creates another saved graph.
  const overviewPast=e=>e.referenceOnly||['replaced-by','split-origin'].includes(e.kind);
  function closeOverview(){
    overviewOpen=false;$('graph-overview').hidden=true;document.querySelector('.document').hidden=false;$('graph-overview-button').setAttribute('aria-pressed','false');$('graph-overview-button').focus();
  }
  function showOverview(id){
    if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');
    const preferred=id||overviewRange?.id||selectedId||overviewFocus;clearSelection();closePanel();overviewOpen=true;
    $('graph-overview').hidden=false;document.querySelector('.document').hidden=false;$('graph-overview-button').setAttribute('aria-pressed','true');
    const graph=window.GraphOverview.project(state),candidates=state.variables.filter(v=>(overviewAll||v.docIds.includes(docId))&&(overviewHistory||v.status!=='excluded'));
    overviewFocus=candidates.some(v=>v.id===preferred)?preferred:'';overviewActionId='';
    $('overview-search').value=overviewSearch;$('overview-history').checked=overviewHistory;$('overview-document').value=overviewAll?'':docId;renderOverviewList();renderOverviewDetail();requestAnimationFrame(()=>$('overview-search').focus());
  }
  function focusOverview(id){
    const item=state.variables.find(v=>v.id===id);if(!item)return;
    overviewFocus=id;overviewActionId='';
    if(item.status==='excluded'){overviewHistory=true;$('overview-history').checked=true;}
    renderOverviewList();renderOverviewDetail();$('overview-search-results').hidden=true;$('overview-detail').scrollTop=0;
    overviewZoom('center');focusOverviewNode();
  }
  function focusOverviewAction(id){
    const action=overviewBusinessView?.actions.find(item=>item.id===id);if(!action)return;
    overviewActionId=id;overviewFocus=action.ownerId;renderOverviewDetail();$('overview-detail').scrollTop=0;focusOverviewNode();
  }
  function focusOverviewNode(){
    const attribute=overviewActionId?'data-overview-action-id':'data-overview-id',id=overviewActionId||overviewFocus;
    const node=[...$('overview-canvas').querySelectorAll('button')].find(item=>item.getAttribute(attribute)===id);
    node?.focus({preventScroll:true});
  }
  function renderOverviewList(){
    const q=overviewSearch.trim().toLowerCase(),all=state.variables.filter(v=>(overviewAll||v.docIds.includes(docId))&&(overviewHistory||v.status!=='excluded'));
    const visible=all.filter(v=>[v.nameZh,v.nameEn].join(' ').toLowerCase().includes(q));
    $('overview-count').innerHTML=bi(`${overviewAll?'全部模板':docNames([docId])} · ${visible.length} 项${q?'符合搜索':''}`,`${overviewAll?'All templates':docNames([docId])} · ${visible.length} ${q?'matching ':''}items`);
    $('overview-search-results').hidden=!q;
    $('overview-list').innerHTML=!q?'':visible.length?visible.map(v=>`<button class="list-item" data-action="overview-focus" data-overview-id="${escape(v.id)}" aria-pressed="${overviewFocus===v.id}">${bi(v.nameZh,v.nameEn)}<small>${escape(v.type.zh)} / ${escape(v.type.en)} · ${escape(statusNames[v.status].join(' / '))}</small></button>`).join(''):`<p class="graph-empty">${bi('没有符合的项目。','No matching items.')}</p>`;
  }
  function renderOverviewDetail(){
    const v=state.variables.find(x=>x.id===overviewFocus);
    const graph=window.GraphOverview.project(state);renderOverviewMap(graph);
    if(!v){$('overview-detail').innerHTML=`<p class="graph-empty">${bi('选择一个变量或修改方案，查看它的条件、关联及原文位置。','Select a variable or amendment instruction to inspect its conditions, links and source locations.')}</p>`;return;}
    const links=window.GraphOverview.neighborhood(graph,v.id),action=overviewBusinessView?.actions.find(item=>item.id===overviewActionId);
    const trouble=links.edges.some(e=>e.needsRebind||e.missing||e.incompatible||e.excluded||e.unresolved),anchors=anchorsFor(v);
    const entries=action?action.targets:(v.editTargets||[]).map((target,index)=>({target,index}));
    const linked=[...new Set(links.edges.filter(e=>!overviewPast(e)&&!e.self).map(e=>e.from===v.id?e.to:e.from))];
    const actionPanel=action?`<section><h2>${bi(action.labelZh,action.labelEn)}</h2>${action.needsReview?note('这个方案仍有待核对事项，原文位置与条件需要确认。','This instruction requires review. Confirm its conditions and source positions.'):''}<p>${bi(action.summaryZh||'修改说明待补充',action.summaryEn||'Amendment description to review')}</p><p class="subtle">${bi('所属变量：','Owner:')}${bi(v.nameZh,v.nameEn)}</p>${action.branch?`<p class="subtle">${bi(`情况 ${(action.branchIndex??0)+1} · ${action.branch.mode==='all'?'全部条件同时满足':action.branch.mode==='any'?'满足任一条件':'共同条件尚未明确'}`,`Case ${(action.branchIndex??0)+1} · ${action.branch.mode==='all'?'All conditions must hold':action.branch.mode==='any'?'Any condition may hold':'Condition combination not confirmed'}`)}</p>`:''}${action.targets.length?button('overview-action-source','在原件查看这个方案','Read this instruction on the source','','primary'):note('这个方案尚未划选具体修改文字。','No exact amendment wording has been selected for this instruction.')}</section>`:'';
    const targetHtml=entries.map(({target:t,index})=>`<article class="graph-target">${t.needsRebind?note('这处修改的关联需要重新确认。','This amendment needs its links checked again.'):''}${button(action?'overview-action-target':'overview-target',`${docNames([t.docId])} · 第 ${t.page} 页 · ${actionNames[t.action]?.[0]||'待决定'}`,`${docNames([t.docId])} · Page ${t.page} · ${actionNames[t.action]?.[1]||'To decide'}`,`data-index="${index}"`)}${targetMeaning(t,v)}<details><summary>${bi('查看这段原文','Read this passage')}</summary>${quote(t.text)}</details></article>`).join('');
    $('overview-detail').innerHTML=`${actionPanel}<section><h2>${bi(v.nameZh,v.nameEn)}</h2><span class="chip">${bi(v.type.zh,v.type.en)}</span><span class="chip">${bi(v.role.zh,v.role.en)}</span><p class="subtle">${escape(docNames(v.docIds))}</p>${status(v)}${v.parentId?`<p class="subtle">${bi('所属资料：','Belongs to:')}${bi(getName(v.parentId).nameZh,getName(v.parentId).nameEn)}</p>`:''}${v.role.kind==='fixed'?`<p>${bi('固定内容','Fixed content')}：${escape(v.fixedValue)}</p>`:''}${v.options?.length?`<div>${v.options.map(o=>`<span class="chip">${bi(o.labelZh,o.labelEn)}</span>`).join('')}</div>`:''}<div class="actions">${button('overview-source','回原件核对／调整','Review or correct on the document')}</div>${trouble?note('部分关联已变化、停用或尚未明确，请核对后使用。','Some links changed, are retired or remain unresolved. Review before use.'):''}<p class="graph-focus-note">${bi('这是已记录的关系与修改方式；还没有按真实工程答案决定执行哪些修改。','Recorded relationships and amendment instructions; no changes have been selected using actual project answers.')}</p></section><section><h3>${bi('条件与修改方式','Conditions and amendment instructions')}</h3>${v.correction?.needsRebind?note('条件关联已变化，请重新确认。','Condition links changed; review them again.'):''}${!v.correction&&v.relations?.length?note('以下是较早的文字描述，仅供参考，尚未形成明确条件。','The following earlier descriptions are references, not explicit conditions.'):''}${renderConditions(v)||`<p class="graph-empty">${bi('尚未记录明确条件。请在原件上核对适用范围。','No explicit conditions are recorded. Review the applicable source scope.')}</p>`}${v.correction?.otherwise==='needs_review'?note('没有落入以上情况时，仍需人工核对，不能自动决定修改。','If none of these cases applies, manual review is still required. No amendment is decided automatically.'):''}${links.edges.some(e=>e.cyclic)?note('记录中存在相互引用，可能是核对或改文前提，不直接认定为答案循环。','Some records refer back to each other; these may be review or amendment prerequisites, not circular answer derivation.'):''}</section><section><h3>${bi(action?'这个方案修改哪些文字？':`具体影响哪些文字？（${entries.length}处）`,action?'Which passages does this instruction amend?':`Which passages are affected? (${entries.length})`)}</h3>${targetHtml||`<p class="graph-empty">${bi('尚未划选具体修改位置。','No exact amendment passages have been selected.')}</p>`}</section>${renderOverviewScope(v)}${linked.length?`<section><h3>${bi('关联的变量','Linked variables')}</h3><div class="graph-related">${linked.map(id=>{const item=getName(id);return button('overview-focus',item.nameZh,item.nameEn,`data-overview-id="${escape(id)}"`);}).join('')}</div></section>`:''}<section><h3>${bi('原文依据','Source evidence')}</h3>${anchors.map((a,i)=>`${button('overview-anchor',`${docNames([a.docId])} · 第 ${a.page} 页`,`${docNames([a.docId])} · Page ${a.page}`,`data-index="${i}"`)}<details><summary>${bi('查看原文摘录','Read the source excerpt')}</summary>${quote(a.text||'')}</details>`).join('')||note('精确原文位置尚未确认，请回原件核对。','Exact source locations are not confirmed. Review the document.')}</section>`;
  }
  function overviewSource(kind,index,ownerId){
    const selected=state.variables.find(x=>x.id===overviewFocus),v=state.variables.find(x=>x.id===(ownerId||overviewFocus));
    const action=kind==='action'?overviewBusinessView?.actions.find(item=>item.id===overviewActionId):null;
    overviewRange=selected?{id:selected.id,exclusive:!!action,labelZh:action?`${selected.nameZh} · ${action.labelZh}`:selected.nameZh,labelEn:action?`${selected.nameEn} · ${action.labelEn}`:selected.nameEn,targets:action?action.targets.map(x=>x.target):overviewScopeTargets().map(x=>x.target)}:null;closeOverview();if(!v)return;
    selectQuestion(v.id,kind!=='anchor'&&kind!=='target'&&kind!=='action');
    const anchor=kind==='anchor'?anchorsFor(v)[index]:['target','action'].includes(kind)?v.editTargets?.[index]:null;
    if(anchor){docId=anchor.docId;goPage(anchor.page,anchor);showQuestion();}
  }
  function overviewScopeTargets(){
    const ids=new Set([overviewFocus,...(overviewImpact?.downstreamIds||[])]);
    return state.variables.filter(v=>ids.has(v.id)&&v.status!=='excluded').flatMap(owner=>(owner.editTargets||[]).map((target,index)=>({owner,target,index})));
  }
  function renderOverviewScope(v){
    const scope=overviewScopeTargets(),owners=[...new Set(scope.map(x=>x.owner.id))];
    return `<section class="graph-section"><h2>${bi('选中变量的完整潜在修改范围','Complete potential amendment scope of the selection')}</h2><div class="graph-scope-summary">${bi(`${v.nameZh}及其下游关联，共有${owners.length}项记录了${scope.length}处修改指令。同一段在不同情况下的处理会分别保留。`,`${v.nameEn} and its downstream links: ${owners.length} items record ${scope.length} amendment instructions. Different cases for the same passage remain separate.`)}<p class="subtle">${bi('这是按已记录关系展开的可能范围；并不表示所有位置都会同时修改。待重新关联的修改位置也会显示提醒。','This is the potential scope of recorded links; the passages will not necessarily all change together. Stale amendment links remain flagged.')}</p>${scope.length?button('overview-scope-source','在原件中高亮这些位置','Highlight these passages on the document'):''}</div>${owners.map(id=>{const entries=scope.filter(x=>x.owner.id===id),owner=entries[0].owner;return `<details class="graph-scope-owner"><summary>${bi(`${owner.nameZh} · ${entries.length}处`,`${owner.nameEn} · ${entries.length} passages`)}</summary>${entries.map(({target:t,index})=>`<div class="graph-target">${t.needsRebind?note('此处关联已变化，需重新核对。','This link changed and requires review.'):''}${button('overview-scope-target',`${docNames([t.docId])} · 第${t.page}页 · ${actionNames[t.action]?.[0]||'待决定'}`,`${docNames([t.docId])} · Page ${t.page} · ${actionNames[t.action]?.[1]||'To decide'}`,`data-owner="${escape(id)}" data-index="${index}"`)}${targetMeaning(t,owner)}</div>`).join('')}</details>`;}).join('')}</section>`;
  }
  function renderOverviewMap(graph){
    overviewBusinessView=window.GraphBusinessView.build(state,{docId:overviewAll?undefined:docId,includeExcluded:overviewHistory,query:overviewSearch});
    overviewLayout=overviewBusinessView;overviewImpact=window.GraphOverview.impact(graph,overviewFocus);
    const view=overviewBusinessView,chain=window.GraphBusinessView.selection(view,overviewActionId||overviewFocus);
    const selected=new Set([...chain.variableIds,...chain.actionIds,...chain.fileIds]),edgeIds=new Set(chain.edgeIds),hasSelection=!!(overviewActionId||overviewFocus);
    const paths=view.edges.filter(edge=>edge.path).map(edge=>`<path d="${edge.path}" class="${edgeIds.has(edge.id)?'selected':hasSelection?'dim':''} ${['parent','reference','split-origin','replaced-by'].includes(edge.kind)||edge.needsRebind||edge.unresolved||edge.excluded||edge.incompatible?'relation':''}"/>`).join('');
    const variables=view.variables.map(entry=>{const v=entry.node;return `<button class="business-node business-variable ${selected.has(v.id)?'selected':hasSelection?'dim':''} ${v.status==='excluded'?'retired':''} ${entry.matched&&overviewSearch?'matched':''}" style="left:${entry.x}px;top:${entry.y}px;width:${entry.w}px;height:${entry.h}px" data-action="overview-focus" data-overview-id="${escape(v.id)}" aria-pressed="${overviewFocus===v.id&&!overviewActionId}" aria-label="${escape(v.nameZh)} / ${escape(v.nameEn)}" title="${escape(v.nameZh)} / ${escape(v.nameEn)}"><span class="node-kind">${entry.depth?'明细字段 / Detail field':'变量 / Variable'}</span>${bi(v.nameZh,v.nameEn)}${entry.depth?'':`<span class="node-sub">${escape(v.type.zh)} / ${escape(v.type.en)}</span>`}</button>`;}).join('');
    const actions=view.actions.map(a=>{
      const places=[...new Set(a.targets.map(({target:t})=>`${docNames([t.docId])} ${t.page}`))],where=places.length?places.slice(0,2).join(' / '):'位置待核对',whereEn=places.length?where:'Source location to review';
      const operations=(a.operations||[]).map(kind=>actionNames[kind]||['待核对','Review needed']);
      const kind=a.branch?`情况 ${(a.branchIndex??0)+1} / Case ${(a.branchIndex??0)+1}`:a.referenceOnly?'参考说明 / Reference':'所有情况 / All cases';
      const zh=`${where} · ${operations.map(x=>x[0]).join('、')||a.labelZh}`,en=`${whereEn} · ${operations.map(x=>x[1]).join(', ')||a.labelEn}`;
      return `<button class="business-node business-action ${selected.has(a.id)?'selected':hasSelection?'dim':''} ${a.needsReview?'needs-review':''}" style="left:${a.x}px;top:${a.y}px;width:${a.w}px;height:${a.h}px" data-action="overview-action" data-overview-action-id="${escape(a.id)}" aria-pressed="${overviewActionId===a.id}" aria-label="${escape(getName(a.ownerId).nameZh)} · ${escape(a.labelZh)} / ${escape(a.labelEn)}" title="${escape(getName(a.ownerId).nameZh)} · ${escape(a.summaryZh||a.labelZh)} / ${escape(a.summaryEn||a.labelEn)}"><span class="node-kind">${escape(kind)}</span>${bi(zh,en)}<span class="node-sub">${escape(a.needsReview?'待核对 / Review needed':a.summaryZh||'已记录 / Recorded')}</span></button>`;
    }).join('');
    const files=view.files.map(f=>`<button class="business-node business-file ${selected.has(f.id)?'selected':hasSelection?'dim':''}" style="left:${f.x}px;top:${f.y}px;width:${f.w}px;height:${f.h}px" data-action="overview-file" data-doc-scope="${escape(f.docId)}" aria-label="查看 ${escape(docNames([f.docId]))} / View ${escape(docNames([f.docId]))}">${bi(docNames([f.docId]),f.docId==='scc-word'?'SCC · Word edition':f.docId==='scc-pdf'?'SCC · PDF edition':docNames([f.docId]))}<span class="node-sub">${bi('原文文件','Source document')}</span></button>`).join('');
    $('overview-canvas').innerHTML=`<div class="graph-column-heading" style="left:26px">${bi(`输入变量 · ${view.variables.length}`,'Variables')}</div><div class="graph-column-heading" style="left:300px">${bi(`已记录修改方案 · ${view.actions.length}`,'Recorded amendment instructions')}</div><div class="graph-column-heading" style="left:600px">${bi('原文落点','Source destinations')}</div><svg width="${view.width}" height="${view.height}" aria-hidden="true">${paths}</svg>`+variables+actions+files;
    Object.assign($('overview-canvas').style,{width:view.width+'px',height:view.height+'px'});Object.assign($('overview-space').style,{width:view.width+'px',height:view.height+'px'});
    $('overview-map-count').innerHTML=bi(`${overviewAll?'全部模板':docNames([docId])} · ${view.variables.length}项变量与明细 · ${view.actions.length}项已记录方案 · 选中链路高亮，其余保留`,`${overviewAll?'All templates':docNames([docId])} · ${view.variables.length} variables and details · ${view.actions.length} recorded instructions · Selected chain highlighted; other nodes retained`);
  }
  function overviewZoom(action){
    if(!overviewBusinessView)return;
    if(action==='center'){
      const selected=overviewBusinessView.actions.find(a=>a.id===overviewActionId)||overviewBusinessView.variables.find(entry=>entry.node.id===overviewFocus);
      if(selected){$('overview-viewport').scrollTop=Math.max(0,selected.y-$('overview-viewport').clientHeight/3);$('overview-viewport').scrollLeft=overviewActionId?Math.max(0,selected.x-150):0;}
    }
  }
  const graphContext=()=>({...packContext(),templateBindings:data.templateBindings,sourceText:selectedText});
  const graphStore=()=>window.TemplateGraph.storage(window.indexedDB);
  const graphTime=value=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
  function graphDownload(saved){
    if(savedGraphUrl)URL.revokeObjectURL(savedGraphUrl);
    savedGraphUrl=URL.createObjectURL(new Blob([JSON.stringify(saved,null,2)],{type:'application/json'}));
    return `<a class="btn" href="${escape(savedGraphUrl)}" download="consense-template-graph.json">${bi('下载图谱备份','Download graph backup')}</a>`;
  }
  async function showGraphs(){
    const request=++graphRequest;
    clearSelection();openPanel('graphs','这套模板的变量图谱','Variable graphs for these templates');
    $('drawer-body').innerHTML=note('图谱与当前模板原件及原文位置绑定。保存变量、条件、关联和修改范围，未确认的事项继续保留。','Graphs are bound to these template files and source positions. Questions, conditions, links, affected text and unresolved items are retained.')+`<p class="subtle">${bi('绑定模板','Bound templates')} ${escape(docNames(data.documents.map(d=>d.id)))}</p>`+field('graphName','图谱名称','Graph name',graphName||'NTT / SCT / SCC · '+new Date().toLocaleDateString('zh-CN',{timeZone:'Asia/Shanghai'}))+button('save-graph','保存为新版本','Save a new version','','primary')+`<p class="subtle">${bi('保存在当前浏览器；每次保存保留一个新版本。请另下载备份，避免清理浏览器后丢失。','Saved in this browser as a new version each time. Download a backup before clearing browser data.')}</p>`+(failedGraph?note('上次保存没有完成，请先下载当时的图谱备份。','The previous save did not complete. Download that snapshot first.')+graphDownload(failedGraph):'')+button('choose-graph-file','从备份载入','Import a graph backup')+title('已保存的版本','Saved versions')+`<div id="graph-list">${bi('正在读取…','Loading…')}</div>`;
    try{
      const entries=await graphStore().list(data.reviewSourceKey);
      if(panel!=='graphs'||request!==graphRequest)return;
      entries.sort((a,b)=>b.savedAt.localeCompare(a.savedAt));
      $('graph-list').innerHTML=entries.length?entries.map(entry=>`<section class="list-item"><strong>${escape(entry.name)}</strong><p class="subtle">${escape(graphTime(entry.savedAt))} · ${bi(`${entry.count} 项有效记录`,`${entry.count} active items`)}</p>${button('preview-graph','查看并使用','Review and use',`data-graph-id="${escape(entry.id)}"`)}</section>`).join(''):note('尚未保存图谱。','No graph saved yet.');
    }catch(error){if(panel==='graphs'&&request===graphRequest)$('graph-list').innerHTML=note('本机保存暂不可用，请使用下载备份。','Local storage is unavailable. Use a downloaded backup.');}
  }
  async function saveGraph(b){
    const name=$('drawer-body').querySelector('[name="graphName"]').value.trim();
    if(!name||name.length>120)return notify('请填写不超过120字的图谱名称。','Enter a graph name of up to 120 characters.');
    let saved;
    try{saved=window.TemplateGraph.create(state,graphContext(),{id:crypto.randomUUID(),name,savedAt:new Date().toISOString()});}
    catch(error){return notify('当前内容还不能保存为图谱，请先修正核对文件中的问题。','Some review data needs correcting before saving this graph.');}
    b.disabled=true;
    try{
      await graphStore().save(saved);graphName=name;failedGraph=null;
      notify('图谱新版本已保存在本机。','A new graph version is saved in this browser.');
      if(panel!=='graphs')return;
      await showGraphs();
      if(panel==='graphs')$('graph-list').insertAdjacentHTML('afterbegin',note('新版本已保存；模板原件未改动。','A new version is saved. Template originals are unchanged.')+graphDownload(saved));
    }catch(error){
      failedGraph=saved;
      notify('图谱未存入本机，请回“模板图谱”下载备份。当前调整已保留。','Graph was not saved locally. Return to Template graphs and download the backup; current work is unchanged.');
      if(panel==='graphs')$('graph-list').innerHTML=note('尚未存入本机，当前调整保留。请下载这份图谱备份。','Local saving did not complete. Your current work is unchanged; download this graph backup.')+graphDownload(saved);
    }finally{if(b.isConnected)b.disabled=false;}
  }
  function previewGraph(saved){
    const checked=window.TemplateGraph.validate(saved,graphContext());
    if(!checked.ok)throw Error('Graph does not match these templates.');
    pendingGraph=saved;openPanel('graph-import','使用这个图谱版本？','Use this graph version?');
    $('drawer-body').innerHTML=title(saved.name,graphTime(saved.savedAt))+note('已核对为同一套模板。使用后将恢复这个版本的全部变量与关联，替换当前工作，可撤销。当前调整如需保留，请先另存一个版本。','These template files match. Restore all questions and links from this version, replacing current work. You can Undo; save your current work as another version first.')+`<p>${bi(`${saved.graph.variables.length} 项记录，包含保留的旧项。`,`${saved.graph.variables.length} records, including retained originals.`)}</p>`+graphDownload(saved)+button('use-graph','使用这个版本','Use this version','','primary')+button('graphs','返回版本列表','Back to saved versions');
  }
  async function readGraph(id){
    const request=++graphRequest;
    try{const saved=await graphStore().read(id);if(panel==='graphs'&&request===graphRequest)previewGraph(saved);}
    catch(error){notify('无法使用这份图谱，文件或模板版本不匹配。当前调整已保留。','Cannot use this graph. Its data or template version does not match; current work is unchanged.');}
  }
  function useGraph(){
    if(!pendingGraph)return;
    try{
      const saved=pendingGraph,review=window.TemplateGraph.toReview(saved,graphContext());
      undo.push({state:copy(state),selectedId,docId,page});if(undo.length>50)undo.shift();
      state=review.state;overviewRange=null;graphName=saved.name;pendingGraph=null;selectedId='';search='';clearSelection();goPage(firstGuidancePage());showCatalog();
      notify('已恢复图谱的变量与关联；需要时可撤销。','Graph questions and links restored. Undo is available.');
    }catch(error){pendingGraph=null;notify('模板或图谱内容不匹配，当前调整已保留。','Template or graph data does not match. Current work is unchanged.');}
  }
  function savePack(){
    const pack={kind:'consense-review-v1',sourceKey:data.reviewSourceKey,state:copy(state)},checked=window.ReviewPack.validate(pack,packContext());
    if(!checked.ok)return notify('有内容需要修正，尚未保存。',checked.errors.join(' · '));
    const content=JSON.stringify(pack,null,2);
    if(savedReviewUrl)URL.revokeObjectURL(savedReviewUrl);
    savedReviewUrl=URL.createObjectURL(new Blob([content],{type:'application/json'}));
    openPanel('export','保存本次核对','Save this review');
    $('drawer-body').innerHTML=`<p>${bi('文件已准备好。下载后，可在刷新或更新页面后载入，继续核对。','Your file is ready. Download it, then load it after refreshing or updating the page to continue.')}</p><a class="btn primary" href="${escape(savedReviewUrl)}" download="consense-review.json">${bi('下载核对文件','Download review file')}</a><details><summary>${bi('浏览器无法下载？复制备份内容','Download blocked? Copy the backup content')}</summary><p class="subtle">${bi('完整复制以下内容，另存为 .json 文件。','Copy all the content below and save it as a .json file.')}</p><textarea id="review-backup" readonly aria-label="完整备份内容 / Complete backup content" rows="8">${escape(content)}</textarea></details>${button('cancel','继续核对','Continue review')}`;
  }
  function importPack(){if(!pendingPack)return;undo.push({state:copy(state),selectedId,docId,page});overviewRange=null;state=window.ReviewPack.append(state,pendingPack);pendingPack=null;selectedId='';search='';clearSelection();goPage(firstGuidancePage());showCatalog();notify('已载入。相同名称不会自动合并，请核对后选择共用。','Loaded. Matching names are not automatically merged; review them before sharing.');}
  function prepareImport(content){
    try{
      if(new Blob([content]).size>16*1024*1024)throw Error('核对文件超过大小限制。 / Review file exceeds the size limit.');
      const pack=JSON.parse(content),checked=window.ReviewPack.validate(pack,packContext());
      if(!checked.ok)throw Error(checked.errors.join(' · '));
      pendingPack=pack;openPanel('import','载入这份核对结果？','Load this review?');
      $('drawer-body').innerHTML=note('将追加这份文件中的记录，保留现有内容。文件中的重名项目需要人工确认是否共用。','Append the file’s records and keep existing work. Review matching names before sharing them.')+`<p>${bi(`文件包含 ${pack.state.variables.length} 项记录（含保留的旧项）。`,`${pack.state.variables.length} records, including retained originals.`)}</p>`+button('import-pack','载入并保留现有内容','Load and keep existing work','','primary');
    }catch(err){pendingPack=null;notify('无法载入核对结果。',err.message);}
  }
  function toggleWord(i){if(draft&&!capture)return notify('先点“划选原文”，再选需要的词。','Choose Select source words before selecting a passage.');const ids=new Set(selection?.docId===docId&&selection.page===page?selection.wordIds:[]);ids.has(i)?ids.delete(i):ids.add(i);setSelection([...ids]);}
  function download(){const changed=state.variables.filter(v=>v.manual||v.status!=='pending');const html='<!doctype html><meta charset="utf-8"><title>原件核对记录 / Source review notes</title><style>body{font:15px/1.7 sans-serif;max-width:900px;margin:40px auto;padding:20px}section{border-bottom:1px solid #ddd;padding:15px}small{display:block;color:#667}</style><h1>原件核对记录 / Source review notes</h1><p>仅为交互原型记录，原件未改写。 / Preview notes only. Original documents unchanged.</p>'+changed.map(v=>`<section><h2>${escape(v.nameZh)}<small>${escape(v.nameEn)}</small></h2><p>${escape(docNames(v.docIds))} · ${escape(v.type.zh)} / ${escape(v.type.en)}</p>${v.parentId?`<p>所属资料组 / Belongs to: ${escape(getName(v.parentId).nameZh)} / ${escape(getName(v.parentId).nameEn)}</p>`:``}${(v.options||[]).map(o=>`<p>• ${escape(o.labelZh)} / ${escape(o.labelEn)}</p>`).join(``)}<p>${escape(statusNames[v.status].join(' / '))}</p>${(v.sourceAnchors||[]).map(a=>`<p>${escape(data.documents.find(d=>d.id===a.docId).shortTitle)} · 第 ${a.page} 页 / Page ${a.page}</p>${quote(a.text)}`).join('')}${renderConditions(v)}${(v.editTargets||[]).map(a=>`<p>${escape(actionNames[a.action].join(' / '))} · ${escape(data.documents.find(d=>d.id===a.docId).shortTitle)} · ${a.page}</p>${quote(a.text)}${targetMeaning(a,v)}`).join('')}<p>${escape(v.fixedValue||v.reason)}</p></section>`).join('');const url=URL.createObjectURL(new Blob([html],{type:'text/html;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download='source-review-notes.html';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  function act(action,b){
    const v=current();
    if(action==='selection-add'&&selection)return startDraft('add',{anchor:copy(selection)});
    if(action==='selection-link'&&selection)return startDraft('link',{anchor:copy(selection)});
    if(action==='use-selection'&&(!selection||!capture||!draft))return notify('选区或调整状态已变化，请重新点划选原文再选择。','The selection or draft changed. Choose Select source words and select again.');
    if(action==='use-selection'&&selection&&capture&&draft){rememberDraft();if(capture==='target')draft.targets.push({...selection,action:'check'});else draft[capture]=copy(selection);capture=null;clearSelection();renderDraft();renderPage();notify('已关联框选的原文。','Selected source words linked.');return;}
    if(action==='cancel'){draft=null;capture=null;clearSelection();renderPage();return v?showQuestion():showCatalog();}
    if(action==='save')return saveDraft();
    if(action==='correct')return openCorrections();
    if(action==='help')return startDraft('help');
    if(action==='back')return showQuestion();
    if(['edit','merge','remove'].includes(action))return startDraft(action);
    if(action==='split'){if(activeChildren(v))return notify('请先选择具体明细问题。','Choose an individual detail first.');return startDraft('split');}
    if(action==='conditions'){const branches=ruleBranches(v.correction);return startDraft('conditions',{branches:branches.length?copy(branches):[{id:'case-1',scopeId:v.type.kind==='list'?v.id:'',mode:v.correction?.mode==='any'?'any':'all',clauses:v.correction?.kind==='split'?v.correction.inputIds.map(inputId=>({inputId,operator:'yes',value:''})):[{inputId:v.id,operator:v.type.kind==='boolean'?'yes':v.type.kind==='number'?'ge':'known',value:''}],effectZh:'',effectEn:''}]});}
    if(action==='targets')return startDraft('targets',{targets:copy(v.editTargets||[])});
    if(action==='pick'){rememberDraft();capture=b.dataset.pick;clearSelection();renderDraft();notify('拖一个框圈住词语；也可以双击选一个词。','Drag a box around the words, or double-click one word.');return;}
    if(action==='remove-target'){draft.targets=readTargetFields(new FormData($('edit-form')),draft);draft.targets.splice(+b.dataset.index,1);draft.fields={};renderDraft();renderPage();return;}
    if(['add-condition','remove-condition','add-case','remove-case'].includes(action)){
      draft.branches=readBranchFields(new FormData($('edit-form')),draft);draft.fields={};
      const i=+b.dataset.case;
      if(action==='add-condition')draft.branches[i].clauses.push({inputId:v.id,operator:'known',value:''});
      if(action==='remove-condition')draft.branches[i].clauses.splice(+b.dataset.index,1);
      if(action==='add-case'){let n=1;while(draft.branches.some(x=>x.id==='case-'+n))n++;draft.branches.push({id:'case-'+n,scopeId:draft.branches[0]?.scopeId||'',mode:'all',clauses:[{inputId:v.id,operator:v.type.kind==='boolean'?'no':'known',value:''}],effectZh:'',effectEn:''});}
      if(action==='remove-case'){if((v.editTargets||[]).some(t=>t.branchId===draft.branches[i].id))return notify('有修改位置还在使用这种情况，请先调整那些位置。','Some affected text still uses this case. Update those passages first.');draft.branches.splice(i,1);}
      renderDraft();return;
    }
    if(action==='review'){if(v.missingSource||!anchorsFor(v).length)return notify('请先关联正确的原文。','Link the correct source wording first.');if(v.correction?.needsRebind)return notify('请先重新确认条件。','Please review the conditions first.');if(v.role.kind==='derived'&&!v.correction)return notify('请先说明它由哪些问题决定。','Specify the questions that determine this item first.');try{if(v.correction?.kind==='rule'&&!ruleBranches(v.correction).length)throw Error('请补充至少一种明确情况。 / Add at least one complete case.');for(const branch of ruleBranches(v.correction))validateBranch(branch,v);validateTargets(v);if(v.correction?.kind==='split')validateClauses(v.correction.inputIds.map(inputId=>({inputId,operator:'yes',value:''})),v);}catch(e){return notify('条件需要重新核对。',e.message);}return apply({type:'REVIEW',id:v.id});}
    if(action==='restore')return apply({type:'RESTORE',id:v.id});
    if(action==='unlink-source')return apply({type:'UNLINK',id:v.id,index:+b.dataset.index});
    if(action==='download')return download();
    if(action==='view-overview')return showOverview();
    if(action==='overview-focus')return focusOverview(b.dataset.overviewId);
    if(action==='overview-action')return focusOverviewAction(b.dataset.overviewActionId);
    if(action==='overview-close')return closeOverview();
    if(action==='overview-clear-selection'){overviewFocus='';overviewActionId='';renderOverviewDetail();return;}
    if(action==='overview-fullscreen'){const full=$('graph-overview').classList.toggle('fullscreen');$('overview-fullscreen').setAttribute('aria-pressed',String(full));$('overview-fullscreen').innerHTML=bi(full?'退出全屏':'全屏',full?'Exit fullscreen':'Fullscreen');return;}
    if(action==='overview-file'){overviewAll=false;docId=b.dataset.docScope;overviewSearch='';selectedId='';goPage(firstGuidancePage());showOverview();return;}
    if(action==='overview-action-source'){const a=overviewBusinessView?.actions.find(item=>item.id===overviewActionId);return a?.targets.length?overviewSource('action',a.targets[0].index,a.ownerId):undefined;}
    if(action==='overview-action-target')return overviewSource('action',+b.dataset.index);
    if(action==='overview-source')return overviewSource();
    if(action==='overview-anchor')return overviewSource('anchor',+b.dataset.index);
    if(action==='overview-target')return overviewSource('target',+b.dataset.index);
    if(action==='overview-scope-target')return overviewSource('target',+b.dataset.index,b.dataset.owner);
    if(action==='overview-scope-source'){const first=overviewScopeTargets()[0];return first?overviewSource('target',first.index,first.owner.id):overviewSource();}
    if(action==='overview-clear-range'){overviewRange=null;renderPage();return;}
    if(action==='overview-all'){overviewAll=true;overviewSearch='';showOverview(overviewFocus);return;}
    if(action==='overview-center')return overviewZoom('center');
    if(action==='graphs'){closeOverview();return showGraphs();}
    if(action==='save-graph')return saveGraph(b);
    if(action==='preview-graph')return readGraph(b.dataset.graphId);
    if(action==='choose-graph-file'){$('graph-import').value='';$('graph-import').click();return;}
    if(action==='use-graph')return useGraph();
    if(action==='save-pack')return savePack();
    if(action==='load-pack'){
      if(!state.blank)return notify('请先点“开始核对”，建立本轮空白变量清单。','Choose Start review before loading a review file.');
      openPanel('load','载入本次核对','Load a review');$('drawer-body').innerHTML=button('choose-review-file','选择已保存的核对文件','Choose a saved review file','','primary')+`<details><summary>${bi('浏览器无法选择文件？粘贴备份内容','File access blocked? Paste the backup content')}</summary><label class="label">${bi('完整备份内容','Complete backup content')}<textarea id="review-paste" rows="8"></textarea></label>${button('check-review-paste','核对这份备份','Check this backup')}</details>`;return;
    }
    if(action==='choose-review-file'){$('review-import').value='';$('review-import').click();return;}
    if(action==='check-review-paste')return prepareImport($('review-paste').value);
    if(action==='import-pack')return importPack();
    if(action==='clear-selection')return clearSelection();
    if(action==='begin-minimum'){undo.push({state:copy(state),selectedId,docId,page});overviewRange=null;state=reduce(state,{type:'CLEAR',guidanceMode:'minimum',at:new Date().toLocaleTimeString()});selectedId='';search='';clearSelection();goPage(firstGuidancePage());showCatalog();notify('变量已清空；保留原文说明的已有识别，仍需人工核对。','Questions cleared. Existing guidance recognition remains for review.');return;}
    if(action==='reset'){if(state.events.length&&!confirm('清空本次试用调整？ / Clear this preview’s edits?'))return;overviewRange=null;state=state.blank?reduce(initial(),{type:'CLEAR',guidanceMode:state.guidanceMode,at:new Date().toLocaleTimeString()}):initial();undo=[];selectedId='';clearSelection();renderHeader();renderPage();showCatalog();}
  }
  document.addEventListener('click',e=>{
    if(performance.now()<ignoreClickUntil&&$('paper').contains(e.target))return;
    const b=e.target.closest('button');if(b){
      if(b.dataset.doc){if(draft){if(!capture||!['split','targets'].includes(draft.kind))return notify('请先保存或取消当前调整。','Save or cancel the current change first.');if(!compatible(current(),{docIds:[b.dataset.doc,...(draft.targets||[]).map(t=>t.docId),draft.a?.docId,draft.b?.docId].filter(Boolean)})||!canShareSources(state,current(),[b.dataset.doc,...(draft.targets||[]).map(t=>t.docId),draft.a?.docId,draft.b?.docId].filter(Boolean)))return notify('两版SCC保持分开，请选择兼容的文件。','The SCC editions stay separate. Choose a compatible document.');rememberDraft();docId=b.dataset.doc;goPage(firstGuidancePage());renderDraft();return;}docId=b.dataset.doc;selectedId='';search='';clearSelection();goPage(firstGuidancePage());if(overviewOpen){overviewAll=false;overviewSearch='';showOverview();}else if(panel)showCatalog();}
      else if(b.dataset.select)selectQuestion(b.dataset.select);
      else if(b.dataset.unit)showUnit(b.dataset.unit);
      else if(b.dataset.anchor!==undefined){const a=current().sourceAnchors[+b.dataset.anchor];if(a.docId!==docId)docId=a.docId;goPage(a.page,a);}
      else if(b.dataset.target!==undefined){const a=current().editTargets[+b.dataset.target];if(a.docId!==docId)docId=a.docId;goPage(a.page,a);}
      else if(b.dataset.action)act(b.dataset.action,b);
      return;
    }
    const word=e.target.closest('[data-word]');if(word){clearTimeout(wordClickTimer);if(e.detail>1)return;const clickedDoc=docId,clickedPage=page,i=+word.dataset.word;if(selection||capture)return toggleWord(i);wordClickTimer=setTimeout(()=>{if(docId!==clickedDoc||page!==clickedPage)return;if(draft)return;const human=state.variables.find(v=>v.status!=='excluded'&&(v.sourceAnchors||[]).some(a=>a.docId===docId&&a.page===page&&containsWord(a,i)));if(human)return selectQuestion(human.id,false);if(state.blank&&state.guidanceMode!=='minimum')return;const u=data.sourceUnits.find(u=>u.docId===docId&&rangesForUnit(u.id).some(a=>a.page===page&&containsWord(a,i)));if(u)showUnit(u.id);},280);}
    else if($('paper').contains(e.target))clearSelection();
  });
  $('paper').addEventListener('pointerdown',e=>{if(e.button!==0||e.isPrimary===false||e.pointerType==='touch'||e.target.closest('button'))return;e.preventDefault();clearTimeout(wordClickTimer);window.getSelection()?.removeAllRanges();drag={start:pointOnPage(e),clientX:e.clientX,clientY:e.clientY,pointerId:e.pointerId,moved:false};});
  window.addEventListener('pointermove',e=>{if(!drag||e.pointerId!==drag.pointerId)return;if(!drag.moved&&Math.hypot(e.clientX-drag.clientX,e.clientY-drag.clientY)<4)return;if(draft&&!capture)return;drag.moved=true;$('paper').setPointerCapture(e.pointerId);const end=pointOnPage(e),p=pageData(),box=$('selectionbox');box.hidden=false;Object.assign(box.style,{left:Math.min(drag.start.x,end.x)/p.width*100+'%',top:Math.min(drag.start.y,end.y)/p.height*100+'%',width:Math.abs(end.x-drag.start.x)/p.width*100+'%',height:Math.abs(end.y-drag.start.y)/p.height*100+'%'});$('selectionbar').hidden=true;paintSelection(wordsInBox(p.words,drag.start,end));});
  window.addEventListener('pointerup',e=>{if(!drag||e.pointerId!==drag.pointerId)return;const d=drag;drag=null;if($('paper').hasPointerCapture(e.pointerId))$('paper').releasePointerCapture(e.pointerId);if(!d.moved)return;ignoreClickUntil=performance.now()+300;$('selectionbox').hidden=true;setSelection(wordsInBox(pageData().words,d.start,pointOnPage(e)));});
  window.addEventListener('pointercancel',()=>{drag=null;clearSelection();});
  window.addEventListener('blur',()=>{drag=null;clearSelection();});
  $('paper').addEventListener('dblclick',e=>{const word=e.target.closest('[data-word]');if(!word)return;e.preventDefault();clearTimeout(wordClickTimer);if(draft&&!capture)return;setSelection([+word.dataset.word]);});
  $('paper').addEventListener('keydown',e=>{const word=e.target.closest('[data-word]');if(word&&['Enter',' '].includes(e.key)){e.preventDefault();clearTimeout(wordClickTimer);toggleWord(+word.dataset.word);}});
  document.addEventListener('keydown',e=>{if(overviewOpen){if(e.key==='Escape'){e.preventDefault();e.stopPropagation();if($('graph-overview').classList.contains('fullscreen')){act('overview-fullscreen',$('overview-fullscreen'));}else closeOverview();return;}if(e.key==='Tab'){const controls=[...$('graph-overview').querySelectorAll('button:not([disabled]),input,select,[tabindex]')].filter(node=>node.getClientRects().length&&node.tabIndex>=0),first=controls[0],last=controls.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}return;}if(e.key==='Escape'){drag=null;clearSelection();}});
  document.addEventListener('mouseup',e=>{if(!drag&&!$('paper').contains(e.target)&&!$('selectionbar').contains(e.target))clearSelection();});
  $('reading-area').addEventListener('scroll',()=>{if(selection)requestAnimationFrame(()=>{if(selection)setSelection(selection.wordIds);});});
  $('selectionbar').addEventListener('mousedown',e=>e.preventDefault());
  $('catalog').onclick=()=>{if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');closeOverview();clearSelection();search='';showCatalog();};
  $('template-graphs').onclick=()=>{if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');closeOverview();showGraphs();};
  $('graph-overview-button').onclick=()=>showOverview();
  $('overview-search').oninput=e=>{overviewSearch=e.target.value;renderOverviewList();renderOverviewMap(window.GraphOverview.project(state));};
  $('overview-document').onchange=e=>{overviewAll=!e.target.value;if(e.target.value){docId=e.target.value;selectedId='';goPage(firstGuidancePage());}overviewSearch='';showOverview();};
  $('overview-history').onchange=e=>{overviewHistory=e.target.checked;showOverview(overviewFocus);};
  new ResizeObserver(entries=>document.documentElement.style.setProperty('--header-height',entries[0].target.offsetHeight+'px')).observe(document.querySelector('.header'));
  $('graph-import').onchange=async()=>{
    const file=$('graph-import').files[0];if(!file)return;
    try{
      if(file.size>16*1024*1024)throw Error('Graph file exceeds the size limit.');
      const content=await file.text();if(draft)throw Error('An edit is in progress.');
      previewGraph(JSON.parse(content));
    }catch(error){pendingGraph=null;notify('无法载入图谱，请检查文件及模板版本。当前调整已保留。','Cannot load this graph. Check its file and template version; current work is unchanged.');}
  };
  $('review-import').onchange=async e=>{try{const file=e.target.files?.[0];if(!file)return;if(file.size>16*1024*1024)throw Error('核对文件超过大小限制。 / Review file exceeds the size limit.');prepareImport(await file.text());}catch(err){pendingPack=null;notify('无法载入核对结果。',err.message);}};
  $('start-review').onclick=()=>{if(draft)return notify('请先保存或取消当前调整。','Save or cancel the current change first.');closeOverview();openPanel('start','开始一轮人工核对','Start a manual review');$('drawer-body').innerHTML=note('清空四份文件的变量，保留大部分说明的识别和选词能力。已有说明可能漏掉、范围不准，需对照原件检查。','Clear questions in all four documents. Keep imperfect guidance recognition and word selection; check missed notes and incorrect ranges against the source.')+button('begin-minimum','清空变量并开始','Clear questions and start','','primary');};
  $('close-drawer').onclick=closePanel;
  $('previous').onclick=()=>goPage(page-1);$('next').onclick=()=>goPage(page+1);
  $('page-number').onchange=e=>goPage(Number(e.target.value));
  $('page-number').oninput=e=>{if(e.target.value)goPage(Number(e.target.value));};
  $('page-number').onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();goPage(Number(e.target.value));}};
  function firstGuidancePage(){if(state.blank&&state.guidanceMode!=='minimum')return 1;const a=data.sourceUnits.filter(u=>u.docId===docId).map(u=>source.anchors[u.id]).find(a=>a?.ranges?.length);return a?.ranges?.[0]?.page||1;}
  $('jump-notes').onclick=()=>{if(draft)return notify('调整过程中可以用页码翻页。','Use the page controls while editing.');openPanel('notes','原文中的说明','Guidance notes in the source');$('drawer-body').innerHTML=data.sourceUnits.filter(u=>u.docId===docId).map(u=>{const a=source.anchors[u.id];return `<button class="list-item" data-note="${escape(u.id)}">${bi(u.locationZh,u.locationEn)}<small>${a?.status==='exact'?`第 ${a.ranges[0].page} 页 / Page ${a.ranges[0].page}`:'位置待确认 / Location to check'}</small></button>`;}).join('');};
  document.addEventListener('click',e=>{const b=e.target.closest('[data-note]');if(b){const a=source.anchors[b.dataset.note];if(a?.ranges?.length)goPage(a.ranges[0].page,a.ranges[0]);else if(a?.pageHint)goPage(a.pageHint);showUnit(b.dataset.note);}});
  $('undo').onclick=()=>{if(draft)return notify('请先取消当前调整，再撤销。','Cancel the current edit before undoing.');if(!undo.length)return;closeOverview();const prev=undo.pop();overviewRange=null;state=prev.state;selectedId=prev.selectedId;docId=prev.docId;page=prev.page;clearSelection();renderHeader();renderPage();current()?showQuestion():showCatalog();notify('已撤销，问题、标记和确认状态一并恢复。','Undone. Questions, highlights and review status are restored.');};
  function integrationSummary(){const active=state.variables.filter(v=>v.status!=='excluded');return{records:state.variables.length,active:active.length,unconfirmed:active.filter(v=>v.status!=='confirmed').length};}
  function integrationSend(type,details={}){if(window.parent!==window)window.parent.postMessage({type:'consense:review-prototype:'+type,...details},integrationOrigin);}
  function integrationChanged(editing=false){if(editing||integrationLastState!==state){integrationLastState=state;if(integrationLoaded)integrationSend('changed',{summary:integrationSummary(),inProgress:editing});}}
  // shortcut: a fixed loopback parent demonstrates integration; production will use versioned backend APIs.
  window.addEventListener('message',event=>{
    if(window.parent===window||event.source!==window.parent||event.origin!==integrationOrigin)return;
    const message=event.data;if(!message||typeof message.type!=='string')return;
    try{
      if(message.type==='consense:review-prototype:load'){
        if(draft||integrationLoaded)throw Error('本轮审核已开始，请保留当前调整。 / Review has started; keep the current work.');
        if(new Blob([JSON.stringify(message.graph)]).size>16*1024*1024)throw Error('图谱备份太大。 / Graph backup is too large.');
        if(message.graph===null){state=reduce(initial(),{type:'CLEAR',guidanceMode:'minimum',at:new Date().toLocaleTimeString('zh-CN',{hour12:false})});overviewRange=null;selectedId='';search='';clearSelection();goPage(firstGuidancePage());showCatalog();}
        else{previewGraph(message.graph);useGraph();}
        integrationLoaded=true;integrationSend('loaded',{summary:integrationSummary()});
      }else if(message.type==='consense:review-prototype:request-snapshot'){
        if(draft)throw Error('请先记录或取消当前调整。 / Save or cancel the current edit first.');
        window.TemplateGraph.create(state,graphContext(),{id:'integration-preview',name:'起草流程审核草稿',savedAt:new Date().toISOString()});
        integrationSend('snapshot',{pack:{kind:'consense-review-v1',sourceKey:data.reviewSourceKey,state:copy(state)},summary:integrationSummary(),templates:copy(data.templateBindings)});
      }else if(message.type==='consense:review-prototype:focus'){
        if(draft)throw Error('请先记录或取消当前调整。 / Save or cancel the current edit first.');
        if(!state.variables.some(v=>v.id===message.id))throw Error('这项问题不存在。 / This question is unavailable.');
        selectQuestion(message.id);
      }
    }catch(error){integrationSend('error',{message:error.message||'请重新检查本轮审核。 / Check this review again.'});}
  });
  // shortcut: the preview demonstrates manual correction; it does not infer rules or edit source documents.
  page=firstGuidancePage();renderHeader();renderPage();
  integrationSend('ready');
})();
