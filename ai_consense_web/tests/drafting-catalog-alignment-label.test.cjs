const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingVariableCatalog.vue'), { boundaries: { '@/api': { draftingApi: {} } } }).default
const catalog = { ruleVersion:'label-v1', groups:[] }
const cases = [
  [{status:'new',confirmed:true},'new','新变量','New variable'],
  [{status:'matched',confirmed:true,baselineKey:'reviewed',baselineVersion:'label-v1'},'matched','已确认对齐','Alignment confirmed'],
  [{status:'matched',confirmed:false},'matched','对应待确认','Correspondence awaiting confirmation'],
  [{status:'matched',confirmed:'true'},'matched','对应待确认','Correspondence awaiting confirmation'],
  [{status:'ambiguous',confirmed:false},'ambiguous','歧义待核','Ambiguous'],
  [{status:'ambiguous',confirmed:true},'ambiguous','歧义待核','Ambiguous'],
  [{status:'unreviewed',confirmed:true},'new','待人工审核','Awaiting review'],
  [{status:'unexpected',confirmed:true},'new','待人工审核','Awaiting review'],
  [undefined,'new','待人工审核','Awaiting review'],
]
;(async () => {
  for (const [alignment,tab,zh,en] of cases) for (const [locale,expected] of [['zh',zh],['en',en]]) {
    const variable = { id:'manual-bq',nameZh:'BQ发放格式是否为L10Pro',nameEn:'BQ issue in L10Pro',role:{kind:'input'},type:{kind:'boolean'},status:'pending',definitionOrigin:{kind:'manual',reason:'Manually added'},sourceAnchors:[],editTargets:[],options:[],...(alignment ? {catalogAlignment:{...alignment,reason:'Review record'}} : {}) }
    const before = JSON.stringify(variable), emitted=[], draft=vue.reactive({tab,nodeId:'',baselineKey:'',query:'',baselineQuery:'',reason:'',scope:''})
    const host=renderer(), app=host.createApp(component,{catalog,state:{variables:[variable],catalogReview:{exclusions:{}}},draft,locale,onAlign:(...args)=>emitted.push(args)})
    app.mount(host.root);await settle()
    const option=find(host.root,node=>node.props?.['data-picker-option']==='manual-bq')
    assert.ok(option,'The variable remains available in its existing group')
    assert.equal(text(option).trim(),`${locale === 'en' ? variable.nameEn : variable.nameZh} · ${expected}`)
    await option.props.onClick();await settle()
    if (alignment) assert.ok(text(host.root).includes(`${locale === 'en' ? 'Recorded' : '已记录'}: ${expected} · Review record`),'The option and recorded-detail labels agree')
    assert.deepEqual(emitted,[],'Reading a status cannot confirm correspondence')
    assert.equal(JSON.stringify(variable),before,'The wording repair cannot rewrite the graph or manual definition')
    app.unmount()
  }
  console.log('PASS: new/manual, confirmed correspondence, ambiguous and unknown labels stay distinct')
})().catch(error=>{console.error(error);process.exitCode=1})
