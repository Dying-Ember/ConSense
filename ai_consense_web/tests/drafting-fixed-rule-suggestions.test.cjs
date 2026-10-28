const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingFixedRuleSuggestions.vue')).default
const rule = { kind:'field_condition', ruleVersion:'fixed-v1', document:'SCC', historicalClause:'SCC1.3', detail:{zhHans:'仅供参考',en:'Historical reference'}, targetBaselineKey:'controlled', targetNodeIds:['target'], inputs:[{baselineKey:'root',nodeIds:['root']},{baselineKey:'missing',nodeIds:[]}], conditions:[{field:'root',operator:'eq',value:false}], targetBinding:'unbound', status:'pending', executable:false, coverage:'catalog_condition' }
const revision = { id:'revision-1', sourceStale:false, graph:{ graph:{ variables:[{id:'root',nameEn:'Root choice',status:'confirmed'},{id:'target',nameEn:'Target',status:'pending'}] } }, fixedRuleSuggestions:[rule] }
;(async () => {
  const host=renderer(), selected=[]
  const app=host.createApp(component,{revision,locale:'en',onQuestion:(...args)=>selected.push(args)}); app.mount(host.root); await settle()
  assert.match(text(host.root),/no current-template amendment range is bound/)
  assert.match(text(host.root),/Historical clause.*SCC1\.3/)
  assert.match(text(host.root),/Condition: root · eq · false/)
  assert.match(text(host.root),/No current variable mapped/)
  const button=find(host.root,node=>node.tag==='button'&&text(node).trim()==='Review Root choice')
  assert.equal(button.props.disabled,false); await button.props.onClick(); assert.deepEqual(selected,[['root','revision-1']])
  app.unmount()
  for (const props of [{revision:{...revision,sourceStale:true},locale:'en'}, {revision,locale:'en',disabled:true}]) {
    const other=renderer(), instance=other.createApp(component,props); instance.mount(other.root); await settle()
    assert.equal(find(other.root,node=>node.tag==='button'&&text(node).trim()==='Review Root choice').props.disabled,true)
    instance.unmount()
  }
  const completeRule={...rule,actionId:'field:controlled',bindingFingerprint:'source-basis-hash',inputs:[{baselineKey:'root',nodeIds:['root']}]}
  const mappedRevision={...revision,graph:{graph:{variables:[{id:'root',nameEn:'Root',status:'confirmed',catalogAlignment:{status:'matched',confirmed:true,baselineKey:'root',baselineVersion:'fixed-v1'}},{id:'target',nameEn:'Target',status:'confirmed',catalogAlignment:{status:'matched',confirmed:true,baselineKey:'controlled',baselineVersion:'fixed-v1'}}]}},fixedRuleSuggestions:[completeRule]}
  const mapped=renderer(), adoptions=[]
  const mappedApp=mapped.createApp(component,{revision:mappedRevision,locale:'en',onAdopt:(...args)=>adoptions.push(args)});mappedApp.mount(mapped.root);await settle()
  const control=attr=>find(mapped.root,node=>node.props?.[attr]!==undefined)
  assert.ok(control('data-fixed-applicability'),'A fully mapped condition offers a human decision')
  assert.equal(control('data-fixed-applicability-adopt').props.disabled,true)
  control('data-fixed-applicability-reason').props['onUpdate:modelValue']('Checked this source');await settle()
  assert.equal(control('data-fixed-applicability-adopt').props.disabled,true,'Checking a reason alone is insufficient')
  control('data-fixed-applicability-confirmed').props['onUpdate:modelValue'](true);await settle()
  await control('data-fixed-applicability-adopt').props.onClick();await settle()
  assert.deepEqual(JSON.parse(JSON.stringify(adoptions)),[['target',{kind:'fixed_catalog',ruleVersion:'fixed-v1',targetBaselineKey:'controlled',bindingFingerprint:'source-basis-hash',mode:'all',confirmed:true,reason:'Checked this source',clauses:[{baselineKey:'root',inputId:'root',operator:'eq',value:false}]}]])
  mappedApp.unmount()
  for (const [status,en,zh] of [ ['covered','Represented','已表示'], ['missing','Missing','缺少'], ['stale','Review again','需重核'], ['ambiguous','Cannot determine uniquely','无法唯一判断'] ]) {
    for (const [locale,label] of [['en',en],['zh-Hans',zh]]) {
      const other=renderer(), emitted=[], current={...mappedRevision,fixedRuleSuggestions:[{...completeRule,reviewCoverage:{status,reason:'INTERNAL_COVERAGE_REASON'}}]}
      const instance=other.createApp(component,{revision:current,locale,onAdopt:(...args)=>emitted.push(args)});instance.mount(other.root);await settle()
      const coverage=find(other.root,node=>node.props?.['data-fixed-rule-coverage']!==undefined)
      assert.ok(text(coverage).includes(label),`${status} must describe current condition coverage`)
      assert.doesNotMatch(text(other.root),/INTERNAL_COVERAGE_REASON|executable|自动确认/)
      const adopt=find(other.root,node=>node.props?.['data-fixed-applicability-adopt']!==undefined)
      assert.equal(adopt.props.disabled,true,'Coverage does not grant a human decision')
      await adopt.props.onClick();await settle();assert.deepEqual(emitted,[],'Displaying coverage must never adopt a condition')
      instance.unmount()
    }
  }
  for (const kind of ['field_condition','action_reference']) {
    const other=renderer(), emitted=[], current={...mappedRevision,sourceStale:true,fixedRuleSuggestions:[{...completeRule,kind,reviewCoverage:{status:'covered',reason:'INTERNAL_COVERAGE_REASON'}}]}
    const instance=other.createApp(component,{revision:current,locale:'zh-Hans',onAdopt:(...args)=>emitted.push(args)});instance.mount(other.root);await settle()
    assert.equal(text(find(other.root,node=>node.props?.['data-fixed-rule-coverage']!==undefined)),'原件已变化，请重新核对。')
    assert.equal(!!find(other.root,node=>node.props?.['data-fixed-applicability']!==undefined),false)
    assert.deepEqual(emitted,[]);instance.unmount()
  }
  const reference=renderer(), referenceAdoptions=[], referenceApp=reference.createApp(component,{revision:{...mappedRevision,fixedRuleSuggestions:[{...completeRule,kind:'action_reference',coverage:'partial_examples'}]},locale:'en',onAdopt:(...args)=>referenceAdoptions.push(args)})
  referenceApp.mount(reference.root);await settle()
  assert.equal(text(find(reference.root,node=>node.props?.['data-fixed-rule-coverage']!==undefined)),'Current amendment range: unbound.')
  assert.equal(!!find(reference.root,node=>node.props?.['data-fixed-applicability']!==undefined),false)
  assert.deepEqual(referenceAdoptions,[]);referenceApp.unmount()
  for (const broken of [ {...mappedRevision,sourceStale:true}, {...mappedRevision,fixedRuleSuggestions:[{...completeRule,bindingFingerprint:''}]}, {...mappedRevision,graph:{graph:{variables:[mappedRevision.graph.graph.variables[0],{...mappedRevision.graph.graph.variables[1],catalogAlignment:{...mappedRevision.graph.graph.variables[1].catalogAlignment,confirmed:false}}]}}} ]) {
    const other=renderer(), instance=other.createApp(component,{revision:broken,locale:'en'});instance.mount(other.root);await settle()
    assert.equal(!!find(other.root,node=>node.props?.['data-fixed-applicability']!==undefined),false,'Stale or unconfirmed mappings cannot be adopted')
    instance.unmount()
  }
  console.log('PASS: fixed references remain unbound and navigation carries the current saved revision')
})().catch(error=>{console.error(error);process.exitCode=1})
