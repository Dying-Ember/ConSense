const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { renderToString } = require('vue/server-renderer')
const { loadVue } = require('./helpers/load-vue.cjs')
const { text, find, settle, renderer } = require('./helpers/render-controls.cjs')
const input = loadVue(path.join(__dirname, '../src/components/DraftingInputField.vue')).default
const processReport = loadVue(path.join(__dirname, '../src/components/DraftingExtractionReport.vue'), { boundaries: { '@/api': { draftingApi: {} } } }).default
const trace = {
  model: '7b', finishedAt: '2026-10-06T08:00:00Z', systemPrompt: 'protocol', userPrompt: 'source', rawResponses: ['raw'],
  runId: 'run-A', status: 'completed', stale: false, evidenceRevision: 'INTERNAL-EVIDENCE-HASH', modelIdentity: { model: '7b', provider: 'qwen', configurationSha256: 'INTERNAL-CONFIG-HASH' },
  parts: [{ partId: 'p1', sourceDocumentId: 8, fileName: 'meeting.docx', sourceHash: 'hash1', partIndex: 1, sourceText: 'Formal contract', attempts: [] }],
  decisions: [{ partId: 'p1', attemptIndex: 1, itemIndex: 0, key: 'contractTitle', rawValue: 'Wrong shape', sourceQuote: 'Formal contract', reason: 'Readable but invalid structure', confidence: 0.9, status: 'rejected', codes: ['invalid_value_shape'] }],
  fields: [{ key: 'contractTitle', status: 'rejected', candidateCount: 0, rejectionCount: 1, unansweredCount: 0, decisionRefs: [0] }]
}
const field = { key: 'contractTitle', kind: 'text', label: { en: 'Contract identity', zhHans: '合约身份', zhHant: '合約身份' } }
;(async () => {
  const batchTrace = { ...trace, parts: [{ ...trace.parts[0], attempts: [{ attemptIndex: 0, kind: 'primary-fields-0', status: 'completed', rawResponse: 'Preserved batch response' }] }] }
  const batchHtml = await renderToString(vue.createSSRApp(processReport, { trace: batchTrace, projectId: '', locale: 'en', open: false }))
  assert.match(batchHtml, /Primary extraction/)
  assert.doesNotMatch(batchHtml, /primary-fields-0/)
  assert.match(batchHtml, /Preserved batch response/)
  const html = await renderToString(vue.createSSRApp(input, { field, value: 'Manual value', values: {}, locale: 'en', trace }))
  assert.match(html, /No usable suggestion returned/)
  assert.match(html, /Value does not match the required structure/)
  assert.match(html, /Wrong shape/)
  assert.match(html, /meeting\.docx/)
  assert.match(html, /id="input-contractTitle"[^>]*value="Manual value"/)
  assert.doesNotMatch(html, /Adopt this candidate|No supported value found|No source evidence/)
  console.log('PASS: rejected model value has visible reason, raw value and source while manual input remains intact and editable')
  const expected = {
    en: ['No usable suggestion returned', 'Value does not match the required structure'],
    'zh-Hans': ['未返回可用建议', '取值不符合要求的数据结构'],
    'zh-Hant': ['未回傳可用建議', '取值不符合要求的資料結構']
  }
  for (const [locale, literals] of Object.entries(expected)) {
    const rendered = await renderToString(vue.createSSRApp(input, { field, value: 'Exact English name', values: {}, locale, trace }))
    for (const literal of literals) assert.ok(rendered.includes(literal), `${locale}: ${literal}`)
    assert.match(rendered, /value="Exact English name"/)
    assert.match(rendered, /Wrong shape/)
  }
  const mixed = { ...trace, status: 'completed', stale: false, decisions: [
    { ...trace.decisions[0], status: 'accepted', rawValue: false, normalizedValue: 'false', sourceQuote: 'First quotation', codes: [] },
    { ...trace.decisions[0], status: 'accepted', rawValue: [], normalizedValue: '[]', sourceQuote: 'Second quotation', codes: [] }
  ], fields: [{ ...trace.fields[0], status: 'candidate_conflict', candidateCount: 2, rejectionCount: 0, decisionRefs: [0, 1] }] }
  const conflict = await renderToString(vue.createSSRApp(input, { field, value: null, values: {}, locale: 'en', trace: mixed }))
  assert.match(conflict, /Candidate values disagree/)
  assert.match(conflict, /does not establish a conflict in the original sources/)
  assert.match(conflict, /First quotation/); assert.match(conflict, /Second quotation/)
  assert.match(conflict, /<pre>false<\/pre>/); assert.match(conflict, /<pre>\[\]<\/pre>/)
  assert.doesNotMatch(conflict, /Conflicting evidence|Adopt this candidate/)
  for (const [status, expectedText] of [['model_unanswered', 'Model returned an unknown value'], ['no_candidate', 'Model did not return a value for this variable'], ['not_assessed', 'Not assessed in this run']]) {
    const result = await renderToString(vue.createSSRApp(input, { field, value: 'Preserved manual value', values: {}, locale: 'en', trace: { ...trace, status: 'failed', stale: true, decisions: [], fields: [{ ...trace.fields[0], status, decisionRefs: [] }] } }))
    assert.ok(result.includes(expectedText)); assert.match(result, /value="Preserved manual value"/)
    assert.match(result, /This extraction failed/); assert.match(result, /Sources have changed/)
    assert.doesNotMatch(result, /Adopt this candidate/)
  }
  const legacy = await renderToString(vue.createSSRApp(input, { field, value: 'Manual value', values: {}, locale: 'en', trace: { model: 'legacy', finishedAt: '', systemPrompt: '', userPrompt: '', rawResponses: [] } }))
  assert.doesNotMatch(legacy, /No usable suggestion returned|data-extraction-field/)
  const escaped = await renderToString(vue.createSSRApp(input, { field, value: null, values: {}, locale: 'en', trace: { ...trace, decisions: [{ ...trace.decisions[0], rawValue: '<script>bad()</script>' }] } }))
  assert.match(escaped, /&lt;script&gt;bad\(\)&lt;\/script&gt;/); assert.doesNotMatch(escaped, /<script>bad/)
  console.log('PASS: three locales preserve English values; disagreement retains quotes/false/[]; unanswered/omitted/unassessed and legacy records remain distinct; raw text is escaped')
  for (const [locale, outcome, primary, caution] of [
    ['en', 'Extraction failed', 'Primary extraction', 'Business facts still require review and adoption'],
    ['zh-Hans', '识别失败', '主识别调用', '取值仍需业务人员核对采用'],
    ['zh-Hant', '識別失敗', '主識別呼叫', '取值仍需業務人員核對採用']
  ]) {
    const report = { ...trace, status: 'failed', stale: true, failureCode: 'invalid_json', failureMessage: 'Captured failure', parts: [{ ...trace.parts[0], attempts: [{ attemptIndex: 1, kind: 'primary', systemPrompt: 'exact system', userPrompt: 'exact user', rawResponse: 'exact raw', status: 'failed', errorCode: 'invalid_json' }] }] }
    const rendered = await renderToString(vue.createSSRApp(processReport, { trace: report, projectId: 'A', locale, open: false }))
    assert.ok(rendered.includes(outcome)); assert.ok(rendered.includes(primary)); assert.ok(rendered.includes(caution))
    assert.match(rendered, /Captured failure/); assert.doesNotMatch(rendered, /hash1|INTERNAL-CONFIG-HASH|INTERNAL-EVIDENCE-HASH/); assert.match(rendered, /exact system/); assert.match(rendered, /exact user/); assert.match(rendered, /exact raw/)
    assert.doesNotMatch(rendered, /Adopt this candidate|采用此候选值|採用此候選值/)
  }
  const oldReport = await renderToString(vue.createSSRApp(processReport, { trace: { model: 'legacy', finishedAt: '2026-10-05', systemPrompt: 'old system', userPrompt: 'old user', rawResponses: ['old raw'] }, projectId: 'A', locale: 'en', open: false }))
  assert.match(oldReport, /This legacy trace contains prompts and raw responses without field intake decisions/)
  assert.match(oldReport, /old raw/); assert.doesNotMatch(oldReport, /Extraction completed|Extraction failed/)
  const absent = await renderToString(vue.createSSRApp(processReport, { trace: null, projectId: 'A', locale: 'en', open: false }))
  assert.match(absent, /No extraction report is available/); assert.match(absent, /No saved historical reports are available/)
  console.log('PASS: actual process panel renders failed outcomes/attempts/intake caveat in all three languages and handles legacy/absent reports without adoption controls or model/API calls')
  for (const [locale, title, note, attemptTitle] of [
    ['en', 'Extraction partially available', 'Complete source-checked candidates are available for review, editing and explicit adoption', 'Attempt partially available'],
    ['zh-Hans', '识别部分可用', '可继续核对、修改和采用', '调用结果部分可用'],
    ['zh-Hant', '識別部分可用', '可繼續核對、修改和採用', '呼叫結果部分可用']
  ]) {
    const partial = { ...trace, status: 'partial', failureCode: 'incomplete_json', failureMessage: 'Unfinished source requires review', parts: [{ ...trace.parts[0], attempts: [{ attemptIndex: 1, kind: 'primary', systemPrompt: 'exact system', userPrompt: 'exact user', rawResponse: '[{"key":"complete"},{"key":', status: 'partial', errorCode: 'output_budget_exhausted', providerFailureKind: 'OUTPUT_BUDGET_EXHAUSTED', providerResponse: '{"choices":[{"finish_reason":"length"}],"note":"<script>untrusted</script>"}', providerResponseMetadata: { finishReason: 'length', usage: { total_tokens: 120 } } }] }] }
    const rendered = await renderToString(vue.createSSRApp(processReport, { trace: partial, projectId: 'A', locale, open: false }))
    assert.ok(rendered.includes(title)); assert.ok(rendered.includes(note)); assert.ok(rendered.includes(attemptTitle)); assert.match(rendered, /Unfinished source requires review/)
    assert.doesNotMatch(rendered, /<h3>Extraction completed<\/h3>|<h3>Extraction failed<\/h3>|Assessed parts are diagnostic only/)
    assert.match(rendered, /OUTPUT_BUDGET_EXHAUSTED/); assert.match(rendered, /&quot;finishReason&quot;: &quot;length&quot;/); assert.match(rendered, /&lt;script&gt;untrusted/); assert.doesNotMatch(rendered, /<script>untrusted/)
    const editable = await renderToString(vue.createSSRApp(input, { field, value: 'false', values: {}, locale, trace: partial }))
    assert.ok(editable.includes(note)); assert.match(editable, /id="input-contractTitle"[^>]*value="false"/)
  }
  console.log('PASS: partial candidates are explicitly available for manual completion and input remains editable in all three locales without automatic adoption')
  const outage = { ...trace, status: 'partial', failureCode: 'partial_source_failed', parts: [{ ...trace.parts[0], attempts: [{ attemptIndex: 1, kind: 'primary', systemPrompt: 'system', userPrompt: 'source', rawResponse: null, status: 'failed', errorCode: 'model_call_failed', errorMessage: 'Selected profile quota exhausted <script>untrusted</script>' }] }] }
  for (const locale of ['en', 'zh-Hans', 'zh-Hant']) {
    const rendered = await renderToString(vue.createSSRApp(processReport, { trace: outage, projectId: 'A', locale, open: false }))
    assert.match(rendered, /Selected profile quota exhausted/)
    assert.match(rendered, /&lt;script&gt;untrusted/); assert.doesNotMatch(rendered, /<script>untrusted/)
  }
  const sourceUnresolved = { ...trace, decisions: [{ ...trace.decisions[0], key: 'foundationIncluded', rawValue: null, status: 'unanswered', sourceQuote: 'Decision: leave that classification pending.', reason: 'The classification must await a separate scope review.', codes: ['value_unanswered', 'source_unresolved'] }], fields: [{ key: 'foundationIncluded', status: 'source_unresolved', candidateCount: 0, rejectionCount: 0, unansweredCount: 1, decisionRefs: [0] }] }
  const foundation = { key: 'foundationIncluded', kind: 'boolean', label: { en: 'Foundation classification', zhHans: '基础工程分类', zhHant: '基礎工程分類' } }
  for (const [locale, label, guidance] of [
    ['en', 'Source explicitly leaves this unresolved', 'The quoted source leaves this variable pending or not supplied'],
    ['zh-Hans', '资料明确未决', '原文明确表示待定或未提供'],
    ['zh-Hant', '資料明確未決', '原文明確表示待定或未提供']
  ]) {
    const rendered = await renderToString(vue.createSSRApp(input, { field: foundation, value: 'false', values: {}, locale, trace: sourceUnresolved }))
    assert.ok(rendered.includes(label), `${locale}: ${label}`)
    assert.ok(rendered.includes(guidance), `${locale}: ${guidance}`)
    assert.match(rendered, /The classification must await a separate scope review/)
    assert.match(rendered, /Decision: leave that classification pending/)
    assert.match(rendered, /meeting\.docx/)
    assert.match(rendered, /id="input-foundationIncluded"[^>]*value="false"/)
    assert.doesNotMatch(rendered, /No usable suggestion returned|未返回可用建议|未回傳可用建議|Adopt this candidate/)
  }
  console.log('PASS: source-explicit pending input shows truthful unresolved reason and quotation in three languages without erasing manual values or suggesting adoption')
  const coverage = { ...trace, decisions: [], fields: [{ key: 'domesticBlocks', status: 'coverage_unresolved', candidateCount: 0, rejectionCount: 0, unansweredCount: 0, decisionRefs: [] }], parts: [{ ...trace.parts[0], attempts: [{ attemptIndex: 2, kind: 'coverage', systemPrompt: 'coverage protocol', userPrompt: 'direct source clue', rawResponse: 'null', status: 'completed' }] }] }
  for (const [locale, label, attemptLabel] of [
    ['en', 'Direct source clue remains unresolved after a coverage check', 'Direct-fact coverage check'],
    ['zh-Hans', '直接资料线索补查后仍未识别', '直接事实补查'],
    ['zh-Hant', '直接資料線索補查後仍未識別', '直接事實補查']
  ]) {
    const rendered = await renderToString(vue.createSSRApp(input, { field: { ...foundation, key: 'domesticBlocks' }, value: null, values: {}, locale, trace: coverage }))
    assert.ok(rendered.includes(label), `${locale}: ${label}`)
    assert.doesNotMatch(rendered, /Source explicitly leaves this unresolved|资料明确未决|資料明確未決/)
    const report = await renderToString(vue.createSSRApp(processReport, { trace: coverage, projectId: 'A', locale, open: false }))
    assert.ok(report.includes(attemptLabel), `${locale}: ${attemptLabel}`)
    assert.match(report, /coverage protocol/)
    assert.match(report, /direct source clue/)
  }
  console.log('PASS: unresolved direct-fact coverage is distinct from source pending and its actual attempt is labelled truthfully in all three languages')
  const visible = renderer()
  const visibleApp = visible.createApp(input, { field: foundation, value: 'false', values: {}, locale: 'en', trace: sourceUnresolved })
  visibleApp.mount(visible.root); await settle()
  const withoutExpansion = node => { for (let parent = node.parent; parent; parent = parent.parent) if (parent.tag === 'details' && !parent.props.open) return false; return true }
  assert.ok(find(visible.root, node => node.tag === 'blockquote' && text(node).includes('Decision: leave that classification pending.') && withoutExpansion(node)), 'Explicit source-pending quotation is visible without expanding diagnostic details')
  assert.ok(find(visible.root, node => node.tag === 'p' && text(node).includes('The classification must await a separate scope review.') && withoutExpansion(node)))
  assert.ok(find(visible.root, node => text(node) === 'meeting.docx' && withoutExpansion(node)))
  visibleApp.unmount()
  console.log('PASS: matched source-unresolved reason, filename and quotation are visible beside the input without expanding details')
  for (const [locale, quoteLabel, metadataLabel, sourceLabel] of [
    ['en', 'Quotation does not support the proposed value', 'Unsupported Bill metadata was removed from the suggestion', 'Matching source text explicitly leaves this variable unresolved'],
    ['zh-Hans', '引文不能支持该候选取值', '缺乏依据的 Bill 元数据已从建议中移除', '匹配的原文明确表示该变量未决'],
    ['zh-Hant', '引文不能支持該候選取值', '缺乏依據的 Bill 元資料已從建議中移除', '匹配的原文明確表示該變量未決']
  ]) {
    const codes = { ...trace, decisions: [
      { ...trace.decisions[0], status: 'rejected', codes: ['quote_value_mismatch'] },
      { ...trace.decisions[0], key: 'billNos', status: 'accepted', rawValue: [{ number: '1', description: 'Preliminaries', type: 'BQ' }], normalizedValue: [{ number: '1', description: 'Preliminaries' }], codes: ['bill_metadata_unsupported:1:type'] },
      sourceUnresolved.decisions[0]
    ] }
    const report = await renderToString(vue.createSSRApp(processReport, { trace: codes, projectId: 'A', locale, open: false }))
    assert.ok(report.includes(quoteLabel), `${locale}: ${quoteLabel}`)
    assert.ok(report.includes(metadataLabel), `${locale}: ${metadataLabel}`)
    assert.ok(report.includes(sourceLabel), `${locale}: ${sourceLabel}`)
    assert.match(report, /bill_metadata_unsupported:1:type/)
    assert.match(report, /&quot;type&quot;: &quot;BQ&quot;/)
    assert.match(report, /&quot;description&quot;: &quot;Preliminaries&quot;/)
  }
  console.log('PASS: citation mismatch and sanitized Bill metadata have readable three-language explanations while exact codes and original raw values remain inspectable')
  for (const [locale, label] of [
    ['en', 'Coverage check returned a field outside the requested keys'],
    ['zh-Hans', '补查返回了目标字段以外的取值'],
    ['zh-Hant', '補查回傳了目標欄位以外的取值']
  ]) {
    const guarded = { ...trace, decisions: [{ ...trace.decisions[0], key: 'foundationIncluded', codes: ['coverage_key_not_allowed'] }] }
    const report = await renderToString(vue.createSSRApp(processReport, { trace: guarded, projectId: 'A', locale, open: false }))
    assert.ok(report.includes(label), `${locale}: ${label}`)
    assert.match(report, /coverage_key_not_allowed/)
    assert.match(report, /Wrong shape/)
    assert.doesNotMatch(report, /Other diagnostic reason|其他诊断原因|其他診斷原因/)
  }
  console.log('PASS: out-of-scope coverage fields show the exact guard reason in three languages while retaining rejected raw values and codes')
  for (const [locale, label] of [
    ['en', 'Unknown-source repair may correct the quotation, but cannot turn the unresolved variable into a definite value'],
    ['zh-Hans', '未决项修复只允许校正来源引文，不能将未知值改成确定取值'],
    ['zh-Hant', '未決項修復只允許校正來源引文，不能將未知值改成確定取值']
  ]) {
    const guarded = { ...trace, decisions: [{ ...trace.decisions[0], key: 'contractPeriodMonths', rawValue: 31, codes: ['unanswered_repair_value_not_allowed'] }] }
    const report = await renderToString(vue.createSSRApp(processReport, { trace: guarded, projectId: 'A', locale, open: false }))
    assert.ok(report.includes(label), `${locale}: ${label}`)
    assert.match(report, /unanswered_repair_value_not_allowed/)
    assert.match(report, /<pre>31<\/pre>/)
    assert.doesNotMatch(report, /Other diagnostic reason|其他诊断原因|其他診斷原因|outside the requested keys|目标字段以外|目標欄位以外/)
  }
  console.log('PASS: unknown-source repair cannot invent a definite value, with precise three-language explanations and rejected raw value retained')
})().catch(error => { console.error(error); process.exitCode = 1 })
