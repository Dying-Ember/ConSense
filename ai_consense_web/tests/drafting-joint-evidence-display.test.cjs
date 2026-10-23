const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')
const { loadVue } = require('./helpers/load-vue.cjs')
const component = loadVue(path.join(__dirname, '../src/components/DraftingJointEvidence.vue')).default
;(async () => {
  const relation = {
    key: 'discovery-internal-identity', status: 'proposed', relation: 'explicit_replacement',
    codes: ['joint_source_identity_invalid'], fromDecisionRef: 0, toDecisionRef: 1,
    evidence: [0, 1].map(index => ({ decisionRef: index, fileName: `minutes-${index}.docx`,
      partId: 'INTERNAL-PART', sourceHash: 'INTERNAL-HASH', sourceDocumentId: 9999,
      sourceQuote: `Exact source ${index}`, value: `Candidate ${index}`,
      context: { trigger: 'joint_original_passage', keys: ['INTERNAL-KEY'], sourceStart: 987654, sourceEnd: 987699, sourceText: `Full context ${index}`, stopReason: 'candidate_found' } })),
    systemPrompt: 'Original system prompt', rawResponse: 'Original model response'
  }
  const before = JSON.stringify(relation)
  for (const locale of ['zh-Hans', 'zh-Hant', 'en']) {
    const html = await renderToString(vue.createSSRApp(component, { relation, locale }))
    assert.doesNotMatch(html, /INTERNAL-PART|INTERNAL-HASH|INTERNAL-KEY|987654|987699|joint_original_passage|candidate_found|9999|<code>/)
    assert.doesNotMatch(html.replace(/data-joint-review="[^"]*"/, ''), /discovery-internal-identity/)
    for (const preserved of ['minutes-0.docx', 'minutes-1.docx', 'Exact source 0', 'Exact source 1', 'Full context 0', 'Full context 1', 'Candidate 0', 'Candidate 1', 'Original system prompt', 'Original model response']) assert.ok(html.includes(preserved), preserved)
    assert.match(html, /<section><p><strong>[^<]+<\/strong><\/p><pre>Full context 0<\/pre><\/section>/)
    assert.match(html, /<pre>Candidate 0<\/pre>/)
    assert.equal(JSON.stringify(relation), before)
  }
  console.log('PASS: joint evidence keeps both sources, values, direction and diagnostics without visible internal identities or candidate disclosure steps')
})().catch(error => { console.error(error); process.exitCode = 1 })
