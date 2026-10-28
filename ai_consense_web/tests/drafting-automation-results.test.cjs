const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingAutomationResults.vue')).default
const sha = 'a'.repeat(64), quote = 'TEST ONLY: enter the Contract Number <pending>.'
const revision = { id: 'TEST-result', sourceStale: true, graph: { sources: [{ id: '41', fileName: 'TEST NTT.docx' }], graph: {
  variables: [{ id: 'contract', nameZh: '合约编号', nameEn: 'Contract number' }, { id: 'identity', nameZh: '项目身份', nameEn: 'Project identity' }],
  requirements: [{ id: 'req-TEST', sourceId: '41', sourceSha256: sha, sourceQuote: quote, kind: 'input', nodeIds: ['contract'], reason: 'TEST supported requirement', status: 'pending' }],
  relationProposals: [{ fromId: 'contract', toId: 'identity', kind: 'dependency', fromQuote: quote, toQuote: 'TEST ONLY exact project identity evidence.', reason: 'TEST proposed dependency', status: 'pending', executable: false }],
  automaticDiscoveryIssues: [{ reason: 'incomplete_json_complete_members_only' }, { reason: 'SECRET_INTERNAL_EXCEPTION_do_not_display' }],
  automaticDiscovery: { totalMembers: 3, scannedMembers: 1, partialMembers: 1, failedMembers: 0, unexecutedMembers: 0, blockedMembers: 1, sources: [{ sourceId: '41', fileName: 'TEST NTT.docx', totalMembers: 3, memberCoverage: [{ memberId: 'm1', ordinal: 0, status: 'completed' }, { memberId: 'm2', ordinal: 1, status: 'partial' }, { memberId: 'm3', ordinal: 2, status: 'blocked', reason: 'member_identity_missing_or_duplicated' }] }] }
} } }
Object.assign(revision.graph.graph.automaticDiscovery, { requirementTasks: 3, completedRequirementTasks: 1, partialRequirementTasks: 1, failedRequirementTasks: 1, unexecutedRequirementTasks: 0 })
;(async () => {
  for (const locale of ['en', 'zh-Hans', 'zh-Hant']) {
    const host = renderer(), located = [], app = host.createApp(component, { revision, locale, onLocate: (...args) => located.push(args) }); app.mount(host.root); await settle()
    const clickTab=async label=>{const node=find(host.root,node=>node.tag==='button'&&text(node).trim()===label);assert.ok(node,label);await node.props.onClick();await settle()}
    assert.ok(text(host.root).includes(quote),'The exact source quote remains readable')
    assert.equal(find(host.root,node=>node.tag==='details'),undefined,'Task evidence uses bounded tabs, not nested disclosure trees')
    const locate=find(host.root,node=>node.tag==='button'&&/Review on original|在原件中核对/.test(text(node)));await locate.props.onClick()
    assert.deepEqual(located,[['41',sha]])
    await clickTab(locale==='en'?'Relationships':'关系建议')
    assert.match(text(host.root),locale==='en'?/Contract number \/ Project identity/:/合约编号 \/ 项目身份/)
    assert.match(text(host.root),locale==='en'?/Possible dependency/:/可能存在依赖/)
    assert.match(text(host.root),/TEST proposed dependency/)
    assert.match(text(host.root),/TEST ONLY exact project identity evidence/)
    const staleButtons = host.root.children.flatMap(function walk(node) { return [node, ...(node.children || []).flatMap(walk)] }).filter(node => node.tag === 'button' && /Review variable|打开变量核对/.test(text(node)))
    assert.equal(staleButtons.length, 2)
    assert.ok(staleButtons.every(node => node.props.disabled), 'Stale source proposals cannot navigate')
    assert.ok(find(host.root,node=>node.tag==='textarea').props.disabled,'Stale sources cannot record a new relationship review')
    await clickTab(locale==='en'?'Further review':'需补核')
    assert.match(text(host.root),locale==='en'?/model response was incomplete/:/模型返回不完整/)
    assert.doesNotMatch(text(host.root),/SECRET_INTERNAL_EXCEPTION_do_not_display/)
    assert.match(text(host.root),locale==='en'?/Sources have changed/:/来源已变化/)
    assert.equal(find(host.root,node=>node.props?.innerHTML!==undefined),undefined,'Source text never becomes HTML')
    assert.equal(find(host.root,node=>node.tag==='form'),undefined,'Browsing records cannot adopt answers or execute relations')
    app.unmount()
  }
  const host = renderer(), questions = [], reviews = []
  const fresh = { ...revision, sourceStale: false }
  const app = host.createApp(component, { revision: fresh, locale: 'en', onQuestion: (...args) => questions.push(args), onReviewRelations: request => reviews.push(request) }); app.mount(host.root); await settle()
  await find(host.root, node => node.tag === 'button' && text(node).trim() === 'Relationships').props.onClick(); await settle()
  const open = find(host.root, node => node.tag === 'button' && text(node).trim() === 'Review variable')
  assert.equal(open.props.disabled, false); await open.props.onClick()
  assert.deepEqual(questions, [['contract', 'TEST-result']], 'Navigation carries the exact saved revision')
  const single=()=>find(host.root,node=>node.tag==='button'&&text(node).trim()==='Save this review')
  assert.equal(single().props.disabled,true,'A human reason is mandatory')
  find(host.root,node=>node.tag==='textarea').props['onUpdate:modelValue'](' The graph merge is saved and both ranges are retained ');await settle()
  assert.equal(single().props.disabled,false);await single().props.onClick()
  assert.deepEqual(JSON.parse(JSON.stringify(reviews)),[{revisionId:'TEST-result',indices:[0],decision:'implemented',reason:'The graph merge is saved and both ranges are retained'}])
  find(host.root,node=>node.tag==='input'&&node.props.type==='checkbox').props.onChange();await settle()
  const selected=find(host.root,node=>node.tag==='button'&&/Save selected relationship reviews/.test(text(node)))
  assert.equal(selected.props.disabled,false);await selected.props.onClick();assert.deepEqual(JSON.parse(JSON.stringify(reviews[1].indices)),[0])
  assert.equal(fresh.graph.graph.relationProposals[0].status,'pending','The UI emits a review request; it cannot confirm a proposal before save')
  app.unmount()
  const reviewed=structuredClone(fresh);reviewed.id='TEST-reviewed';Object.assign(reviewed.graph.graph.relationProposals[0],{status:'unresolved',review:{decision:'rejected',reason:'Old decision retained',basis:'old-basis',status:'needs_review',confirmedAt:'2026-10-28T00:00:00Z'}})
  const staleReviewHost=renderer(),staleReviewApp=staleReviewHost.createApp(component,{revision:reviewed,locale:'en'});staleReviewApp.mount(staleReviewHost.root);await settle()
  await find(staleReviewHost.root,node=>node.tag==='button'&&text(node).trim()==='Relationships').props.onClick();await settle()
  assert.match(text(staleReviewHost.root),/Old decision retained/);assert.match(text(staleReviewHost.root),/Related definitions, conditions or ranges changed/);assert.match(text(staleReviewHost.root),/2026-10-28T00:00:00Z/)
  staleReviewApp.unmount()
  const grouped=structuredClone(revision);grouped.sourceStale=false
  grouped.graph.graph.automaticDiscovery.groups=[{id:'group-TEST',taskIds:['task-1','task-2'],sourceIds:['41'],rootKeys:[],basisKinds:['shared_current_scope'],status:'completed',modelCalls:1}]
  const groupHost=renderer(),groupApp=groupHost.createApp(component,{revision:grouped,locale:'en'});groupApp.mount(groupHost.root);await settle()
  await find(groupHost.root,node=>node.tag==='button'&&text(node).trim()==='Processing groups').props.onClick();await settle()
  assert.match(text(groupHost.root),/Shared current source range/)
  assert.match(text(groupHost.root),/TEST NTT.docx/)
  assert.match(text(groupHost.root),/Source items covered: 2/)
  assert.match(text(groupHost.root),/Actual model calls: 1/)
  assert.match(text(groupHost.root),/human review pending/)
  assert.match(text(find(groupHost.root,node=>node.props?.['data-group-coverage-note']!==undefined)),/do not mean that variables have been merged or confirmed/)
  assert.equal(grouped.graph.graph.requirements.length,1,'Group presentation does not replace the original requirement records')
  groupApp.unmount()
  console.log('PASS: saved task results show exact requirements, named relation proposals and quotations, safe unresolved reasons, bounded non-collapsing tabs in all supported locales without adoption')
})().catch(error => { console.error(error); process.exitCode = 1 })
