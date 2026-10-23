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
    assert.match(text(host.root),locale==='en'?/Contract number → Project identity/:/合约编号 → 项目身份/)
    assert.match(text(host.root),/TEST ONLY exact project identity evidence/)
    await clickTab(locale==='en'?'Further review':'需补核')
    assert.match(text(host.root),locale==='en'?/model response was incomplete/:/模型返回不完整/)
    assert.doesNotMatch(text(host.root),/SECRET_INTERNAL_EXCEPTION_do_not_display/)
    assert.match(text(host.root),locale==='en'?/Sources have changed/:/来源已变化/)
    assert.equal(find(host.root,node=>node.props?.innerHTML!==undefined),undefined,'Source text never becomes HTML')
    assert.equal(find(host.root,node=>node.tag==='form'),undefined,'Browsing records cannot adopt answers or execute relations')
    app.unmount()
  }
  console.log('PASS: saved task results show exact requirements, named relation proposals and quotations, safe unresolved reasons, bounded non-collapsing tabs in all supported locales without adoption')
})().catch(error => { console.error(error); process.exitCode = 1 })
