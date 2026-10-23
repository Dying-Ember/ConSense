const assert = require('node:assert/strict')
const path = require('node:path')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')

const label = (en, zhHans = en, zhHant = zhHans) => ({ en, zhHans, zhHant })
const field = (key, kind, title, extra = {}) => ({ key, kind, label: label(title), affects: [{ document: 'NTT', clause: title, paragraphs: 'P1' }], ...extra })
const groups = [
  { id: 'identity', label: label('Contract identity', '合约身份', '合約身份'), fields: [field('projectArchitectPost', 'text', 'Architect post'), field('projectArchitectName', 'text', 'Architect name')] },
  { id: 'railway', label: label('Railway protection', '铁路保护', '鐵路保護'), fields: [field('railwayProtectionAreaWorks', 'boolean', 'Railway works'), field('railwayProtectionPlansAvailable', 'boolean', 'Railway plans', { condition: { all: [{ field: 'railwayProtectionAreaWorks', operator: 'equals', value: 'true' }] } })] },
  { id: 'bills', label: label('Bill list', '清单', '清單'), fields: [field('billNos', 'list', 'Bill numbers and descriptions', { columnFields: [field('number', 'text', 'Number'), field('description', 'text', 'Description')] })] }
]

function harness() {
  const servers = new Map()
  const makeServer = () => groups.flatMap(group => group.fields).map(item => ({ key: item.key, value: item.key === 'projectArchitectName' ? 'Source Architect' : item.key === 'billNos' ? JSON.stringify([{ number: '1', description: 'Preliminaries' }]) : '', confirmed: false, manuallyEdited: false, candidates: item.key === 'projectArchitectName' ? [{ value: 'Source Architect', fileName: 'TEST ONLY email', sourceQuote: 'Architect: Source Architect.' }] : [] }))
  const server = id => { if (!servers.has(id)) servers.set(id, makeServer()); return servers.get(id) }
  const writes = [], llmCalls = [], readings = [], focusCalls = [], scrollCalls = [], pending = new Map(), pendingWrites = new Map(), failedWrites = new Set()
  const api = {
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: null, current: null, draft: null, sources: [] }),
    catalog: async () => ({ ruleVersion: 'unchanged', groups }),
    variables: async id => pending.has(id) ? pending.get(id).promise : server(id).map(item => ({ ...item })),
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, fileName: `${key}.bin`, tag: 'ok' })),
    inputs: async () => [], documents: async () => [], extractTrace: async () => null,
    plan: async () => ({ actions: [], unresolved: [{ document: 'NTT', clause: 'Railway works', inputKeys: ['railwayProtectionAreaWorks'], message: label('TEST ONLY unresolved railway') }] }),
    templateReading: async (id, fileKey) => { readings.push([id, fileKey]); return { fileKey, fileName: `${fileKey}.bin`, sourceHash: 'test-only', format: 'unsupported', paragraphs: [], catalogueSourceVerified: false } },
    templateSource: async () => new Blob(['TEST ONLY source']),
    updateVariable: async (id, key, patch) => { writes.push([id, key, patch]); if (pendingWrites.has(key)) await pendingWrites.get(key).promise; if (failedWrites.has(key)) throw new Error('TEST ONLY save failed'); const item = server(id).find(item => item.key === key); Object.assign(item, patch.value !== undefined ? { value: patch.value, manuallyEdited: true, confirmed: true } : { confirmed: true }); return { ...item } },
    extractVariables: async (...args) => { llmCalls.push(['extract', ...args]); throw new Error('Navigation must not extract') },
    generate: async (...args) => { llmCalls.push(['generate', ...args]); throw new Error('Navigation must not generate') }
  }
  let rendered, app
  function domNode(node) {
    if (!node) return null
    node.focus = () => focusCalls.push(node.props?.id)
    node.scrollIntoView = () => scrollCalls.push(node.props?.id)
    node.scrollTop = 0; node.clientTop = 0; node.offsetHeight = 40
    node.querySelector = selector => domNode(find(node, item => selector === '[data-preview-card]' ? item.props?.['data-preview-card'] !== undefined : selector.includes('header') ? item.tag === 'header' : selector.split(',').map(part => part.trim()).includes(item.tag)))
    node.getBoundingClientRect = () => ({ top: 100, bottom: 140, height: 40 })
    node.scrollTo = () => {}
    return node
  }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Source location transport not provided by this fixture') } }, '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('No generated document') } },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' },
    'mammoth/mammoth.browser': { convertToHtml() { throw new Error('Unsupported source') } }
  }, globals: { Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {}, setTimeout(...args) { const timer = setTimeout(...args); timer.unref(); return timer }, clearTimeout }, document: { documentElement: {}, getElementById(id) { return domNode(rendered && find(rendered.root, node => node.props?.id === id)) } } } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject('LAYOUT-P1')
  function mount() { rendered = renderer(); app = rendered.createApp(loaded.default); app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(rendered.root) }
  mount()
  const result = { store, writes, llmCalls, readings, focusCalls, scrollCalls, pending, pendingWrites, failedWrites, server,
    get root() { return rendered.root }, dispose() { app.unmount() }, remount() { app.unmount(); mount() },
    node(id) { return find(rendered.root, node => node.props?.id === id) },
    async click(title) { const node = find(rendered.root, node => node.tag === 'button' && text(node).trim() === title); assert.ok(node, `Rendered button: ${title}`); await node.props.onClick(); await settle() },
    async control(attr, value) { const node = find(rendered.root, node => node.tag === 'button' && node.props?.[attr] === value); assert.ok(node, `Rendered ${attr}=${value}`); await node.props.onClick(); await settle() },
    async input(id, value) { const node = result.node(id); assert.ok(node, `Rendered input ${id}`); (node.props.onInput ?? node.props.onChange)({ target: { value } }); await settle() },
    async filter(attribute, value) { const node = find(rendered.root, node => node.props?.['aria-label'] === attribute); assert.ok(node, `Rendered filter ${attribute}`); node.props['onUpdate:modelValue'](value); await settle() }
  }
  return result
}
function renderedGroups(root) { const result = []; function visit(node) { if (node.props?.['data-group']) result.push(node.props['data-group']); (node.children ?? []).forEach(visit) } visit(root); return result }

;(async () => {
  const single = harness()
  try {
    await settle(); await single.click('Enter inputs manually')
    const saveButton = key => find(single.root, node => node.props?.['data-save-input'] === key)
    const saveStatus = key => find(single.root, node => node.props?.['data-input-save-status'] === key)
    assert.ok(saveButton('projectArchitectPost'), 'Each editable variable has a nearby save action')
    assert.equal(saveButton('projectArchitectPost').props.disabled, true, 'An untouched field cannot be adopted through Save')
    await single.input('input-projectArchitectPost', 'Manual architect post')
    await single.input('input-projectArchitectName', 'Still unsaved architect name')
    await single.input('input-billNos-1-description', 'Unsubmitted formal Bill description')
    assert.match(text(saveStatus('projectArchitectPost')), /Unsaved/)
    await single.control('data-save-input', 'projectArchitectPost')
    assert.deepEqual(JSON.parse(JSON.stringify(single.writes)), [['LAYOUT-P1', 'projectArchitectPost', { value: 'Manual architect post' }]])
    assert.equal(single.node('input-projectArchitectPost').props.value, 'Manual architect post')
    assert.equal(saveButton('projectArchitectPost').props.disabled, true)
    assert.equal(text(saveStatus('projectArchitectPost')), 'Saved')
    assert.equal(single.node('input-projectArchitectName').props.value, 'Still unsaved architect name', 'Refresh preserves the other unsaved edit')
    assert.equal(single.server('LAYOUT-P1').find(item => item.key === 'projectArchitectName').value, 'Source Architect')
    assert.equal(saveButton('projectArchitectName').props.disabled, false)
    assert.equal(single.node('input-billNos-1-description').props.value, 'Unsubmitted formal Bill description', 'Structured unsaved edits also survive a one-field save')
    assert.equal(single.server('LAYOUT-P1').find(item => item.key === 'billNos').value, '[{"number":"1","description":"Preliminaries"}]')
    await single.control('data-save-input', 'projectArchitectPost')
    assert.equal(single.writes.length, 1, 'Repeated Save cannot create another write after success')
    await single.click('B · One question')
    assert.ok(saveButton('projectArchitectName'), 'The same per-field action is available in B')
    single.failedWrites.add('projectArchitectName')
    await single.control('data-save-input', 'projectArchitectName')
    assert.match(text(saveStatus('projectArchitectName')), /Save failed/)
    assert.equal(single.node('input-projectArchitectName').props.value, 'Still unsaved architect name')
    assert.equal(saveButton('projectArchitectName').props.disabled, false, 'Failure leaves the edit retryable')
    single.failedWrites.delete('projectArchitectName')
    const saving = deferred(); single.pendingWrites.set('projectArchitectName', saving)
    const saveTask = single.control('data-save-input', 'projectArchitectName'); await settle()
    assert.equal(text(saveStatus('projectArchitectName')), 'Saving…')
    assert.equal(saveButton('projectArchitectName').props.disabled, true)
    await single.control('data-save-input', 'projectArchitectName')
    assert.equal(single.writes.length, 3, 'A second click during a pending save is ignored')
    saving.resolve(); await saveTask; single.pendingWrites.delete('projectArchitectName')
    assert.equal(text(saveStatus('projectArchitectName')), 'Saved')
    await single.input('input-projectArchitectName', 'A fresh manual edit')
    assert.match(text(saveStatus('projectArchitectName')), /Unsaved/, 'A new edit clears the prior saved feedback')
    await single.click('Save values for this draft')
    assert.equal(single.server('LAYOUT-P1').find(item => item.key === 'projectArchitectName').value, 'A fresh manual edit', 'The group Save remains available')
    await single.click('Save all changes')
    assert.equal(JSON.parse(single.server('LAYOUT-P1').find(item => item.key === 'billNos').value)[0].description, 'Unsubmitted formal Bill description', 'Save all writes the remaining structured edit')
    assert.deepEqual(single.llmCalls, [])
    console.log('PASS: per-variable save writes one input, preserves other edits, reports failures/retries and prevents duplicate saves in A/B')
  } finally { single.dispose() }

  const switchedSave = harness()
  try {
    await settle(); await switchedSave.click('Enter inputs manually'); await switchedSave.input('input-projectArchitectPost', 'P1 save in flight')
    const saving = deferred(); switchedSave.pendingWrites.set('projectArchitectPost', saving)
    const saveTask = switchedSave.control('data-save-input', 'projectArchitectPost'); await settle()
    switchedSave.store.switchProject('LAYOUT-P2'); await settle(); await switchedSave.click('Enter inputs manually')
    saving.resolve(); await saveTask
    assert.equal(switchedSave.node('input-projectArchitectPost').props.value, '', 'A late P1 save cannot replace P2 values')
    assert.equal(find(switchedSave.root, node => node.props?.['data-input-save-status'] === 'projectArchitectPost'), undefined, 'Save feedback is isolated by project')
    assert.deepEqual(JSON.parse(JSON.stringify(switchedSave.writes)), [['LAYOUT-P1', 'projectArchitectPost', { value: 'P1 save in flight' }]])
    assert.deepEqual(switchedSave.llmCalls, [])
    console.log('PASS: a pending per-variable save cannot overwrite the replacement project or its feedback')
  } finally { switchedSave.dispose() }

  const h = harness()
  try {
    await settle(); await h.click('Enter inputs manually')
    assert.deepEqual(renderedGroups(h.root), ['identity', 'railway', 'bills'], 'A initially renders the existing catalogue list')
    await h.input('input-projectArchitectPost', 'Unsaved Architect post')
    await h.click('B · One question')
    assert.deepEqual(renderedGroups(h.root), ['identity'], 'B renders one business question with related sub-fields together')
    assert.equal(h.node('input-projectArchitectPost').props.value, 'Unsaved Architect post')
    assert.equal(h.node('input-projectArchitectName').props.value, 'Source Architect')
    assert.match(text(h.root), /TEST ONLY email/)
    await h.control('data-review-group', 'railway')
    assert.deepEqual(renderedGroups(h.root), ['railway'])
    await h.click('Previous question'); await h.click('A · List')
    assert.equal(h.node('input-projectArchitectPost').props.value, 'Unsaved Architect post')
    assert.deepEqual(h.writes, [], 'Switching and navigation never saves or adopts')
    assert.deepEqual(h.llmCalls, [], 'Switching and navigation never invokes a model')
    console.log('PASS: shared A/B group editor preserves unsaved scalar values, source candidates and zero-write navigation')
  } finally { h.dispose() }

  const conditional = harness()
  try {
    await settle(); await conditional.click('Enter inputs manually'); await conditional.click('B · One question')
    await conditional.control('data-review-group', 'railway')
    assert.ok(conditional.node('input-railwayProtectionAreaWorks'), 'Missing prerequisite remains editable')
    assert.ok(conditional.node('input-railwayProtectionPlansAvailable'), 'Unknown applicability does not remove the child input')
    await conditional.input('input-railwayProtectionAreaWorks', 'true'); await conditional.input('input-railwayProtectionPlansAvailable', 'true')
    await conditional.input('input-railwayProtectionAreaWorks', 'false')
    assert.equal(conditional.node('input-railwayProtectionPlansAvailable'), undefined, 'Inactive child input is not mounted')
    await conditional.click('A · List'); await conditional.click('B · One question')
    await conditional.input('input-railwayProtectionAreaWorks', 'true')
    assert.equal(conditional.node('input-railwayProtectionPlansAvailable').props.value, 'true', 'Conditional retained value survives layouts and parent changes')
    await conditional.control('data-review-group', 'bills')
    await conditional.input('input-billNos-1-description', 'Unsaved formal description')
    await conditional.click('A · List'); await conditional.click('B · One question')
    assert.equal(conditional.node('input-billNos-1-description').props.value, 'Unsaved formal description', 'Unsaved structured rows use the shared draft')
    await conditional.filter('Search questions, inputs or clauses', 'Bill list')
    assert.deepEqual(renderedGroups(conditional.root), ['bills'])
    assert.equal(text(find(conditional.root, node => node.tag === 'h3')).trim(), '3 Bill list', 'Filtered question retains catalogue number')
    const previous = find(conditional.root, node => node.tag === 'button' && text(node) === 'Previous question')
    const next = find(conditional.root, node => node.tag === 'button' && text(node) === 'Next question')
    assert.equal(previous.props.disabled, true); assert.equal(next.props.disabled, true)
    await conditional.filter('Search questions, inputs or clauses', 'No test group matches')
    assert.deepEqual(renderedGroups(conditional.root), [])
    assert.match(text(conditional.root), /No matching questions/)
    await conditional.click('Show all questions')
    await conditional.filter('All statuses', 'missing')
    assert.deepEqual(renderedGroups(conditional.root), ['identity'], 'Excluded selection falls back to a matching group')
    await conditional.input('input-projectArchitectPost', 'Complete missing post')
    assert.deepEqual(renderedGroups(conditional.root), [], 'A current group leaving the selected status produces a recoverable empty state')
    await conditional.click('Show all questions')
    assert.equal(find(conditional.root, node => node.props?.['data-review-group'] === 'identity').props['aria-current'], 'step')
    assert.deepEqual(conditional.writes, []); assert.deepEqual(conditional.llmCalls, [])
    console.log('PASS: B preserves missing/conditional controls and unsaved rows, catalogue numbering, filter fallback and empty recovery without writes')
  } finally { conditional.dispose() }

  const navigation = harness()
  try {
    await settle(); await navigation.click('Enter inputs manually'); await navigation.click('B · One question')
    await navigation.control('data-review-group', 'bills'); await navigation.control('data-open-template', 'billNos')
    const reader = find(navigation.root, node => node.props?.['data-template-panel'] !== undefined)
    await navigation.control('data-review-group', 'identity')
    await navigation.filter('Search questions, inputs or clauses', 'Contract identity')
    await navigation.control('data-preview-input', 'billNos')
    assert.deepEqual(renderedGroups(navigation.root), ['bills'], 'Template return selects the target group before focus in B')
    assert.equal(navigation.focusCalls.at(-1), 'input-billNos-1-number')
    await navigation.click('A · List'); await navigation.click('B · One question')
    assert.equal(find(navigation.root, node => node.props?.['data-template-panel'] !== undefined), reader, 'Layout changes preserve the actual reader instance')
    assert.ok(find(navigation.root, node => node.props?.['data-preview-card'] === 'billNos'), 'Reader selection survives layout changes')
    await navigation.filter('Search questions, inputs or clauses', 'Contract identity')
    await navigation.click('Railway works')
    assert.deepEqual(renderedGroups(navigation.root), ['railway'], 'Unresolved input return clears filters and selects its business question')
    assert.equal(navigation.focusCalls.at(-1), 'input-railwayProtectionAreaWorks')
    const back = find(navigation.root, node => node.props?.['data-preview-input'] === 'billNos')
    const oldFocusCount = navigation.focusCalls.length
    back.props.onClick(); navigation.store.switchProject('LAYOUT-P2'); await settle()
    assert.equal(navigation.focusCalls.length, oldFocusCount, 'Old-project pending source navigation cannot focus the replacement project')
    assert.deepEqual(navigation.writes, []); assert.deepEqual(navigation.llmCalls, [])
    console.log('PASS: source return clears filters and selects B group before focus, retains reader context and rejects stale project focus')
  } finally { navigation.dispose() }

  const cached = harness()
  try {
    await settle(); await cached.click('Enter inputs manually'); await cached.click('B · One question')
    await cached.control('data-review-group', 'bills'); await cached.input('input-billNos-1-description', 'P1 unsaved Bill')
    const loadingP1 = deferred(); cached.pending.set('LAYOUT-P1', loadingP1)
    cached.store.changeLocale('zh-Hant'); cached.remount(); await settle()
    loadingP1.resolve(cached.server('LAYOUT-P1')); cached.pending.delete('LAYOUT-P1'); await settle()
    assert.deepEqual(renderedGroups(cached.root), ['bills'], 'A delayed catalogue/variables refresh restores B and its non-first group after a language remount')
    assert.equal(cached.node('input-billNos-1-description').props.value, 'P1 unsaved Bill')
    assert.ok(find(cached.root, node => node.tag === 'button' && text(node) === 'B · 逐題核對'))
    assert.equal(find(cached.root, node => node.props?.['aria-label'] === '問題目錄').tag, 'nav')
    cached.store.switchProject('LAYOUT-P2'); await settle(); await cached.click('人工填寫')
    assert.deepEqual(renderedGroups(cached.root), ['identity', 'railway', 'bills'], 'A new project has its own default A list')
    assert.equal(cached.node('input-billNos-1-description').props.value, 'Preliminaries', 'Other project does not inherit dirty rows')
    await cached.click('B · 逐題核對'); await cached.control('data-review-group', 'railway')
    await cached.input('input-railwayProtectionAreaWorks', 'false')
    cached.store.switchProject('LAYOUT-P1'); await settle()
    assert.deepEqual(renderedGroups(cached.root), ['bills']); assert.equal(cached.node('input-billNos-1-description').props.value, 'P1 unsaved Bill')
    const late = deferred(); cached.pending.set('LAYOUT-P2', late)
    cached.store.switchProject('LAYOUT-P2'); await settle(); cached.store.switchProject('LAYOUT-P1'); await settle()
    late.resolve(cached.server('LAYOUT-P2')); cached.pending.delete('LAYOUT-P2'); await settle()
    assert.deepEqual(renderedGroups(cached.root), ['bills'], 'Delayed response from the departed project cannot restore its layout/group')
    cached.store.switchProject('LAYOUT-P2'); await settle()
    assert.deepEqual(renderedGroups(cached.root), ['railway'], 'P2 restores its own selected group')
    assert.equal(cached.node('input-railwayProtectionAreaWorks').props.value, 'false')
    await cached.click('儲存本次採用值')
    assert.deepEqual(renderedGroups(cached.root), ['railway'], 'An ordinary save refresh does not replay an earlier cached group')
    cached.store.changeLocale('zh-Hans'); cached.remount(); await settle()
    assert.ok(find(cached.root, node => node.tag === 'button' && text(node) === 'B · 逐题核对'))
    assert.deepEqual(renderedGroups(cached.root), ['railway'])
    assert.equal(cached.writes.length, 1, 'Only the explicit save writes a field')
    assert.equal(cached.writes[0][0], 'LAYOUT-P2'); assert.equal(cached.writes[0][1], 'railwayProtectionAreaWorks')
    assert.deepEqual(cached.llmCalls, [])
    console.log('PASS: delayed language restoration and project revisits isolate layout, selected group and dirty values; only explicit save writes')
  } finally { cached.dispose() }
})().catch(error => { console.error(error); process.exitCode = 1 })


