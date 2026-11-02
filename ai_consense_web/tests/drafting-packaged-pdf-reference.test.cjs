const assert=require('node:assert/strict'),path=require('node:path'),vue=require('vue')
const {loadVue}=require('./helpers/load-vue.cjs'),{renderer,find,text,settle}=require('./helpers/render-controls.cjs')
const calls=[]
const api={
  packagedPdfSccReference:async (...args)=>{calls.push(['list',...args]);return {available:true,executable:false,sourceSha256:'source-sha',total:74,nextOffset:null,candidates:[{pointId:'pdf-1',label:'PDF fact',kind:'input',clause:'SCC1',relationStatus:'partial',summary:'Review',ruleCount:1}]}},
  packagedPdfSccReferencePoint:async (...args)=>{calls.push(['detail',...args]);return {available:true,executable:false,sourceSha256:'source-sha',point:{pointId:'pdf-1',label:'PDF fact',clause:'SCC1',meaning:'Historical meaning',impacts:['Review scope'],evidence:[{page:4,quote:'PDF evidence'}]},rules:[{label:'Historical rule',relationType:'condition',impactType:'amend',inputs:[{ref:'pdf:input:x',role:'input',stage:'effective'}],cases:[{id:'A'}]}]}}
}
const component=loadVue(path.join(__dirname,'../src/components/DraftingPdfSccReference.vue'),{boundaries:{'@/api':{draftingApi:api}}}).default
;(async()=>{
 const source={id:'packaged',docId:'packaged',editionId:'pdf',fileKey:'SCC',format:'pdf',sourceSha256:'source-sha'}
 const host=renderer(),app=host.createApp({setup:()=>()=>vue.h(component,{projectId:'P',revisionId:'packaged',source,variables:[],locale:'en',disabled:true,packaged:true})});app.mount(host.root);await settle()
 const get=(attr,val)=>find(host.root,n=>val===undefined?n.props?.[attr]!==undefined:n.props?.[attr]===val)
 const click=async(attr,val)=>{const button=get(attr,val);assert.ok(button,attr);await button.props.onClick();await settle()}
 assert.equal(calls.length,0);assert.match(text(host.root),/human review required/)
 assert.equal(get('data-pdf-scc-related-load'),undefined,'Selected-source alignment is hidden in the packaged read-only view')
 await click('data-pdf-scc-search');assert.deepEqual(calls[0],['list','P','',0]);await click('data-pdf-scc-point','pdf-1')
 assert.deepEqual(calls[1],['detail','P','pdf-1']);assert.match(text(host.root),/PDF evidence/);assert.match(text(host.root),/Historical rule/)
 assert.equal(get('data-pdf-scc-align'),undefined);assert.equal(get('data-pdf-scc-load-facts'),undefined)
 app.unmount();console.log('PASS: packaged PDF reference uses project-bound read-only list and detail')
})().catch(e=>{console.error(e);process.exitCode=1})
