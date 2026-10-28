const assert=require('node:assert/strict'),path=require('node:path'),vue=require('vue')
const {loadVue}=require('./helpers/load-vue.cjs'),{renderer,find,text,settle}=require('./helpers/render-controls.cjs')
const calls=[], source={id:'41',docId:'scc',editionId:'pdf-1',fileKey:'SCC',format:'pdf',sourceSha256:'source-sha'}
let mismatch=false, pending
const api={
  pdfSccReference:async (...args)=>{calls.push(['list',...args]);if(pending)return new Promise(resolve=>pending=resolve);return {available:true,sourceSha256:mismatch?'wrong-sha':'source-sha',total:1,nextOffset:null,limitations:['Parent clause is incomplete'],candidates:[{pointId:'pdf-1',label:'PDF-only definition',kind:'input',clause:'SCC1.3',relationStatus:'partial',summary:'Historical meaning',ruleCount:1}]}},
  pdfSccReferenceFacts:async (...args)=>{calls.push(['facts',...args]);return {available:true,sourceSha256:'source-sha',facts:[{inputRef:'pdf-fact',valueType:'V1',referencePoints:[{pointId:'pdf-1',label:'PDF fact',clause:'SCC1.3',sourceStatus:'explicit'}]},{inputRef:'wrong-type',valueType:'V2',referencePoints:[]}]}},
  pdfSccReferencePoint:async (...args)=>{calls.push(['detail',...args]);return {available:true,sourceSha256:'source-sha',point:{pointId:'pdf-1',label:'PDF-only definition',clause:'SCC1.3',meaning:'Historical meaning',applicability:'Review the source',valueSource:'Project facts',impacts:['Review clause'],evidence:[{page:4,lineStart:2,lineEnd:3,quote:'Exact historical text'}]},rules:[{label:'Historic branch',relationType:'condition',impactType:'amend',inputs:[{ref:'format',role:'input',stage:'project'}],cases:[{id:'paper'}]}]}}
}
const component=loadVue(path.join(__dirname,'../src/components/DraftingPdfSccReference.vue'),{boundaries:{'@/api':{draftingApi:api}},globals:{crypto:require('node:crypto').webcrypto}}).default
;(async()=>{
  const props=vue.reactive({projectId:'P',revisionId:'R',source,variables:[{id:'v',nameEn:'Current SCC variable',status:'pending',type:{kind:'boolean'},docIds:['41'],semanticSourceQuote:'Exact current source',sourceAnchors:[{docId:'41',sourceSha256:'source-sha',text:'Exact current source',sourceObservationBinding:{status:'bound'}}]},{id:'other',nameEn:'Other',status:'pending',docIds:['99']}],locale:'en'}),opened=[],aligned=[]
  const host=renderer(),app=host.createApp({setup:()=>()=>vue.h(component,{...props,onQuestion:(...args)=>opened.push(args),onAlign:(...args)=>aligned.push(args)})});app.mount(host.root);await settle()
  const control=(attr,val)=>find(host.root,node=>val===undefined?node.props?.[attr]!==undefined:node.props?.[attr]===val)
  const click=async(attr,val)=>{const button=control(attr,val);assert.ok(button,attr);assert.notEqual(button.props.disabled,true);await button.props.onClick();await settle()}
  assert.equal(calls.length,0,'Reference requests are on demand')
  assert.match(text(host.root),/74 processing points are not 74 variables/)
  control('data-pdf-scc-query').props['onUpdate:modelValue']('SCC1.3');await click('data-pdf-scc-search')
  assert.deepEqual(calls[0],['list','P','R','41','SCC1.3',0])
  assert.match(text(host.root),/Parent clause is incomplete/)
  await click('data-pdf-scc-point','pdf-1')
  assert.match(text(host.root),/Exact historical text/);assert.match(text(host.root),/PDF page 4/);assert.match(text(host.root),/Historic branch/)
  control('data-pdf-scc-variable').props['onUpdate:modelValue']('v');await settle();await click('data-pdf-scc-review-variable')
  assert.deepEqual(opened,[['v','R']]);assert.doesNotMatch(text(host.root),/Other/,'Only same-source variables can be selected')
  await click('data-pdf-scc-load-facts');assert.doesNotMatch(text(host.root),/wrong-type/,'Only facts of the same type can be selected')
  control('data-pdf-scc-fact').props['onUpdate:modelValue']('pdf-fact');await settle()
  control('data-pdf-scc-quote').props['onUpdate:modelValue']('not in source');await settle()
  control('data-pdf-scc-reason').props['onUpdate:modelValue']('Reviewed this edition');await settle()
  control('data-pdf-scc-acknowledged').props['onUpdate:modelValue'](true);await settle()
  assert.equal(control('data-pdf-scc-align').props.disabled,true,'A quotation outside the bound source cannot be confirmed')
  control('data-pdf-scc-quote').props['onUpdate:modelValue']('current source');await settle()
  control('data-pdf-scc-acknowledged').props['onUpdate:modelValue'](true);await settle();await click('data-pdf-scc-align')
  assert.equal(aligned.length,1);assert.equal(aligned[0][0],'v');assert.equal(aligned[0][1].inputRef,'pdf-fact');assert.equal(aligned[0][1].sourceQuote,'current source');assert.equal(aligned[0][1].basis,null)
  mismatch=true;await click('data-pdf-scc-search');assert.match(text(host.root),/does not match this PDF/);assert.equal(!!control('data-pdf-scc-point'),false,'Wrong edition is never displayed')
  mismatch=false;pending=true;const loading=control('data-pdf-scc-search').props.onClick();await settle();props.source={...source,id:'42',sourceSha256:'new-sha'};await settle();pending({available:true,sourceSha256:'source-sha',candidates:[{pointId:'old',label:'Old version'}]});await loading;await settle()
  assert.doesNotMatch(text(host.root),/Old version/,'An old in-flight source response is discarded after switching source')
  app.unmount();console.log('PASS: PDF SCC reference is on-demand, SHA-bound, read-only, and navigates only current-source variables')
})().catch(error=>{console.error(error);process.exitCode=1})
