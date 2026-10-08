const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const piniaRuntime = require('pinia')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

function harness({ failRemoval = false, duration, missingTemplates = [], templateTags = {}, templateReadingError, customFields, customVariables, readerCardAboveViewport = false } = {}) {
  const fields = customFields ?? (duration ? [
    { key: 'contractPeriodMonths', kind: 'number', optional: true, label: { en: 'Completion period' }, affects: [{ document: 'NTT', clause: 'Completion', paragraphs: 'P1' }] },
    { key: 'periodAtLeast39Months', kind: 'boolean', label: { en: 'At least 39 months' }, affects: [{ document: 'NTT', clause: 'Completion', paragraphs: 'P1' }] }
  ] : [
    { key: 'foundationIncluded', kind: 'boolean', label: { en: 'Foundation', zhHans: '基础', zhHant: '基礎' }, affects: [{ document: 'NTT', clause: 'NTT10', paragraphs: 'P1' }] },
    { key: 'projectArchitectPost', kind: 'text', label: { en: 'Architect post' }, affects: [{ document: 'SCT', clause: 'SCT8', paragraphs: 'P1' }] }
  ])
  const server = customVariables ?? fields.map((field, i) => ({ key: field.key, value: duration ? i ? duration.threshold : duration.months : i ? 'Source post' : 'false', confirmed: duration?.adopted ?? false, manuallyEdited: false, adoptionState: duration?.adoptionState, reviewRequired: duration?.reviewRequired, validationIssue: duration?.validationIssue, candidates: [] }))
  let removed = false
  const writes = [], removals = [], readings = [], uploads = [], focusCalls = [], scrollCalls = [], fileDialogs = [], panelScrolls = []
  const api = {
    catalog: async () => ({ ruleVersion: 'unchanged', groups: [{ id: 'scope', label: { en: 'Scope' }, fields }] }),
    variables: async () => server.map(variable => ({ ...variable })),
    templates: async () => ['NTT', 'SCT', 'SCC'].map(key => ({ key, fileName: `${key}.bin`, tag: templateTags[key] ?? (missingTemplates.includes(key) ? 'missing' : 'ok') })),
    inputs: async () => removed ? [] : [{ id: 12, fileName: 'TEST ONLY correspondence.docx', status: 'PARSED' }],
    documents: async () => [], extractTrace: async () => null,
    plan: async () => ({ actions: [{ id: 'NTT-10', document: 'NTT', clause: 'NTT10', paragraphs: 'P1', inputKeys: ['foundationIncluded'], action: 'pending', sourceText: 'Known source paragraph' }], unresolved: [] }),
    deleteInput: async (project, id) => { removals.push([project, id]); if (failRemoval) throw new Error('Removal failed'); removed = true; server[0].reviewRequired = true },
    templateReading: async (project, fileKey) => { readings.push([project, fileKey]); if (templateReadingError) throw templateReadingError; return { fileKey, fileName: `${fileKey}.bin`, sourceHash: 'not-docx', format: 'unsupported', catalogueSourceVerified: false, paragraphs: [] } },
    templateSource: async () => new Blob(['source'], { type: 'application/octet-stream' }),
    replaceTemplate: async (project, fileKey, file) => { uploads.push([project, fileKey, file]); templateTags[fileKey] = 'ok'; return { parsed: 1, accepted: 1, failed: 0, messages: [] } },
    updateVariable: async (project, key, patch) => { writes.push([project, key, patch]); const v = server.find(variable => variable.key === key); Object.assign(v, { ...(patch.value !== undefined ? { value: patch.value, manuallyEdited: true } : {}), confirmed: true }); return { ...v } }
  }
  let rendered
  const domNodes = new WeakSet()
  function domNode(node) {
    if (!node) return null
    if (domNodes.has(node)) return node
    domNodes.add(node)
    const id = node.props?.id
    node.focus = options => focusCalls.push([id, options])
    node.scrollIntoView = options => scrollCalls.push([id, options])
    node.click = () => { if (node.tag === 'input' && node.props.type === 'file') fileDialogs.push(id); else node.props.onClick?.() }
    node.scrollTop = readerCardAboveViewport && node.props?.['data-template-panel'] !== undefined ? 600 : 0
    node.clientTop = 1
    node.offsetHeight = node.tag === 'header' ? 48 : 40
    node.querySelector = selector => {
      if (selector === '[data-preview-card]') return domNode(find(node, item => item.props?.['data-preview-card'] !== undefined))
      if (selector === 'header' || selector === ':scope > header') return domNode(node.children?.find(item => item.tag === 'header'))
      return domNode(find(node, item => selector.split(',').map(part => part.trim()).includes(item.tag)))
    }
    node.getBoundingClientRect = () => {
      let top = 300, height = node.offsetHeight
      if (readerCardAboveViewport && node.props?.['data-template-panel'] !== undefined) { top = 80; height = 773 }
      else if (readerCardAboveViewport && node.props?.['data-preview-card'] !== undefined) {
        const panel = domNode(find(rendered.root, item => item.props?.['data-template-panel'] !== undefined))
        top = -421 + 600 - panel.scrollTop
      } else if (readerCardAboveViewport && node.tag === 'header') top = 80
      return { top, bottom: top + height, height, left: 0, right: 680, width: 680 }
    }
    node.scrollTo = options => { panelScrolls.push({ id, from: node.scrollTop, top: options.top }); node.scrollTop = options.top }
    return node
  }
  const documentBoundary = { documentElement: {}, getElementById(id) { return domNode(rendered && find(rendered.root, item => item.props?.id === id)) } }
  const loaded = loadVue(path.join(__dirname, '../src/views/DraftingView.vue'), { boundaries: {
    '@/api': { draftingApi: api, setLlmProfileResolver() {} }, pinia: piniaRuntime, 'vue-i18n': require('vue-i18n'),
    'pdfjs-dist': { GlobalWorkerOptions: {}, getDocument() { throw new Error('No final document requested') } },
    'pdfjs-dist/build/pdf.worker.min.mjs?url': { default: 'external-worker' },
    'mammoth/mammoth.browser': { convertToHtml() { throw new Error('Unsupported source must not be parsed as DOCX') } }
  }, globals: { Blob, URL, crypto: require('node:crypto').webcrypto, localStorage: { getItem: () => 'en', setItem() {} }, window: { addEventListener() {}, removeEventListener() {}, setTimeout(...args) { const timer = setTimeout(...args); timer.unref(); return timer }, clearTimeout }, document: documentBoundary } })
  const pinia = piniaRuntime.createPinia(); piniaRuntime.setActivePinia(pinia)
  const store = loaded.loadLocal(path.join(__dirname, '../src/stores/app.ts')).useAppStore()
  store.switchProject('TEMPLATE-VIEW-TEST')
  rendered = renderer(); const app = rendered.createApp(loaded.default)
  app.use(pinia); app.use(loaded.loadLocal(path.join(__dirname, '../src/i18n/index.ts')).i18n); app.mount(rendered.root)
  return { ...rendered, store, writes, removals, readings, uploads, focusCalls, scrollCalls, fileDialogs, panelScrolls, document: documentBoundary, dispose: () => app.unmount(),
    async click(label) { const button = find(rendered.root, node => node.tag === 'button' && text(node).trim() === label); assert.ok(button, `Rendered button: ${label}`); await button.props.onClick(); await settle() },
    async control(attr, value) { const button = find(rendered.root, node => node.tag === 'button' && node.props[attr] === value); assert.ok(button, `Rendered ${attr}=${value}`); await button.props.onClick(); await settle() }
  }
}

;(async () => {
  const billCandidateRows = [{ number: '9', description: 'Electrical Works', type: 'SOR', id: 'test-only-internal-bill-record' }, { number: '10', description: 'Fire Services and Water Pump Works', type: 'SOR', id: 'test-only-internal-second-record' }]
  const candidateField = { key: 'billNos', kind: 'list', label: { en: 'Bills', zhHans: '清单', zhHant: '清單' }, columnFields: [
    { key: 'number', kind: 'text', label: { en: 'Bill / Schedule number', zhHans: 'Bill／Schedule编号', zhHant: 'Bill／Schedule編號' } },
    { key: 'description', kind: 'text', label: { en: 'Formal description', zhHans: '正式说明', zhHant: '正式說明' } },
    { key: 'type', kind: 'select', label: { en: 'Pricing document type', zhHans: '计价文件类型', zhHant: '計價文件類型' }, options: [{ value: 'SOR', label: { en: 'Schedule of Rates (SOR)', zhHans: '单价表（SOR）', zhHant: '單價表（SOR）' } }] }
  ] }
  const candidate = harness({ customFields: [candidateField], customVariables: [{ key: 'billNos', value: JSON.stringify(billCandidateRows), confirmed: false, manuallyEdited: false, adoptionState: 'suggested', candidates: [{ value: JSON.stringify(billCandidateRows), fileName: 'SIMULATED Bill email.docx', sourceQuote: 'Bill 9 | Electrical Works | Schedule of Rates.', reason: 'SIMULATED source for presentation regression.' }] }] })
  await settle(); await candidate.click('Enter inputs manually')
  const candidateCard = find(candidate.root, node => node.props?.class === 'candidate')
  assert.ok(candidateCard, 'The main input list retains the evidence candidate card')
  assert.ok(find(candidateCard, node => node.tag === 'table'), 'The main input evidence candidate is a readable Bill table')
  assert.doesNotMatch(text(candidateCard), /"number"|"description"|\[\{|test-only-internal/, 'The main list uses the same readable candidate display as template reading')
  assert.match(text(candidateCard), /Electrical Works/)
  assert.match(text(candidateCard), /Fire Services and Water Pump Works/)
  assert.match(text(candidateCard), /Schedule of Rates \(SOR\)/)
  assert.match(text(candidateCard), /SIMULATED Bill email\.docx/)
  assert.match(text(candidateCard), /Bill 9 \| Electrical Works \| Schedule of Rates\./)
  assert.match(text(candidateCard), /SIMULATED source for presentation regression\./)
  assert.deepEqual(candidate.writes, [], 'Reading a candidate never adopts it')
  await candidate.click('Adopt this candidate')
  assert.equal(candidate.writes.length, 1, 'Existing explicit adoption remains available')
  assert.equal(candidate.writes[0][1], 'billNos')
  assert.equal(candidate.writes[0][2].candidateIndex, 0, 'The existing candidate-index adoption contract is retained')
  assert.deepEqual(JSON.parse(JSON.stringify(candidate.writes[0][2].candidateSnapshot)), { value: JSON.stringify(billCandidateRows), sourceDocumentId: null, sourceHash: null, sourceQuote: 'Bill 9 | Electrical Works | Schedule of Rates.' }, 'The selected displayed value and source are bound along with the position')
  candidate.dispose()
  console.log('PASS: the main input evidence candidate uses readable exact Bill rows with source evidence intact and retains explicit candidate adoption')

  const visibleCard = harness({ customFields: [candidateField], customVariables: [{ key: 'billNos', value: JSON.stringify(billCandidateRows), confirmed: false, candidates: [] }], readerCardAboveViewport: true })
  await settle(); await visibleCard.click('Enter inputs manually'); await visibleCard.control('data-open-template', 'billNos')
  await visibleCard.control('data-preview-input', 'billNos')
  assert.equal(visibleCard.panelScrolls.length, 1, 'Selecting a structured source input reveals the readable selected card inside its own side panel')
  assert.equal(visibleCard.panelScrolls[0].id, 'drafting-template-panel', 'Source-to-input coordination scrolls only the reading side panel to expose its card')
  const visiblePanel = visibleCard.document.getElementById('drafting-template-panel')
  const visibleSelected = visiblePanel.querySelector('[data-preview-card]')
  assert.ok(visibleSelected.getBoundingClientRect().top >= visiblePanel.getBoundingClientRect().top + visiblePanel.querySelector('header').offsetHeight, 'The card begins below the sticky panel header')
  assert.ok(visibleSelected.getBoundingClientRect().top < visiblePanel.getBoundingClientRect().bottom, 'The selected card is inside the visible panel after navigation')
  assert.ok(visibleCard.scrollCalls.some(([id]) => id === 'field-billNos'), 'The existing main-list input navigation is retained')
  assert.deepEqual(visibleCard.writes, [], 'Revealing structured details never saves or adopts values')
  const returnToInput = find(visibleCard.root, node => node.props?.['data-preview-input'] === 'billNos')
  const oldScrollCount = visibleCard.panelScrolls.length
  returnToInput.props.onClick()
  visibleCard.store.switchProject('OTHER-READABLE-TEST')
  await settle()
  assert.equal(visibleCard.panelScrolls.length, oldScrollCount, 'A pending old-project selection cannot scroll a replacement project panel')
  assert.equal(find(visibleCard.root, node => node.props?.['data-template-panel'] !== undefined), undefined)
  assert.deepEqual(visibleCard.writes, [])
  visibleCard.dispose()
  console.log('PASS: source input coordination reveals the selected readable card below the sticky panel header, preserves main-list navigation and rejects stale project scrolling without writes')

  for (const [fileKey, selectedKey] of [['NTT', 'foundationIncluded'], ['SCT', 'projectArchitectPost']]) {
    const missing = harness({ missingTemplates: ['NTT', 'SCT', 'SCC'] })
    await settle(); await missing.click('Enter inputs manually')
    find(missing.root, node => node.props?.id === 'input-projectArchitectPost').props.onInput({ target: { value: 'Retain unsaved human wording' } }); await settle()
    await missing.control('data-open-template', selectedKey)
    assert.match(text(missing.root), new RegExp(`No ${fileKey} template has been uploaded to this project`))
    assert.deepEqual(missing.readings, [], 'The parent passes known missing availability so opening the reader never causes a failed HTTP request')
    await missing.click('Go to template upload')
    assert.equal(find(missing.root, node => node.props?.['data-template-panel'] !== undefined), undefined)
    const row = find(missing.root, node => node.props?.id === `template-source-${fileKey}`)
    assert.ok(row, 'Upload navigation returns to the corresponding source row')
    assert.deepEqual(missing.focusCalls.map(call => call[0]), [`template-upload-button-${fileKey}`], 'Navigation focuses the visible matching upload action')
    assert.deepEqual(missing.scrollCalls.map(call => call[0]), [`template-source-${fileKey}`])
    assert.deepEqual(missing.fileDialogs, [], 'Navigating to upload never opens a file dialog automatically')
    assert.deepEqual(missing.uploads, [], 'Navigating to upload never uploads or replaces another project source')
    assert.deepEqual(missing.writes, [], 'Navigating to upload never saves or adopts inputs')
    await missing.click('Enter inputs manually')
    assert.equal(find(missing.root, node => node.props?.id === 'input-projectArchitectPost').props.value, 'Retain unsaved human wording')
    await missing.click('Template reading preview')
    assert.ok(find(missing.root, node => node.props?.['data-preview-card'] === selectedKey), 'Reopening keeps the previously selected input')
    assert.match(text(missing.root), new RegExp(`No ${fileKey} template has been uploaded to this project`), 'Reopening keeps the previously selected file')
    assert.deepEqual(missing.readings, [])
    await missing.click('Go to template upload')
    await missing.control('id', `template-upload-button-${fileKey}`)
    assert.deepEqual(missing.fileDialogs, [`template-upload-${fileKey}`], 'Only the explicit visible upload button opens the matching file dialog')
    const selectedFile = new Blob(['TEST ONLY source-upload boundary fixture'])
    const uploadInput = find(missing.root, node => node.props?.id === `template-upload-${fileKey}`)
    await uploadInput.props.onChange({ target: { files: [selectedFile], value: 'selected-file' } }); await settle()
    assert.equal(missing.uploads.length, 1)
    assert.deepEqual(missing.uploads[0], ['TEMPLATE-VIEW-TEST', fileKey, selectedFile], 'The existing template replacement API receives only the matching selected source')
    await missing.click('Enter inputs manually'); await missing.click('Template reading preview')
    assert.equal(find(missing.root, node => node.props?.id === 'input-projectArchitectPost').props.value, 'Retain unsaved human wording', 'Source-upload refresh retains unrelated dirty inputs')
    assert.ok(find(missing.root, node => node.props?.['data-preview-card'] === selectedKey))
    assert.deepEqual(missing.readings, [['TEMPLATE-VIEW-TEST', fileKey]], 'After the upload refresh, reopening reads the correct newly available source')
    assert.match(text(missing.root), /Unsupported template format/)
    assert.deepEqual(missing.writes, [], 'Uploading a source never adopts or saves existing input values')
    missing.dispose()
  }
  console.log('PASS: missing-template upload navigation targets the matching visible control without opening a dialog, uploading, writing inputs or losing dirty values and selection')

  const parseFailure = harness({ templateTags: { NTT: 'failed' }, templateReadingError: Object.assign(new Error('SOURCE_DOCX_INVALID: The saved DOCX source cannot be parsed.'), { code: 4013 }) })
  await settle(); await parseFailure.click('Enter inputs manually'); await parseFailure.control('data-open-template', 'foundationIncluded')
  assert.deepEqual(parseFailure.readings, [['TEMPLATE-VIEW-TEST', 'NTT']], 'An uploaded failed template must discover and report its actual source failure rather than claim that it was never uploaded')
  assert.match(text(parseFailure.root), /Could not read the uploaded template/)
  assert.match(text(parseFailure.root), /SOURCE_DOCX_INVALID: The saved DOCX source cannot be parsed/)
  assert.doesNotMatch(text(parseFailure.root), /No NTT template has been uploaded|Go to template upload/)
  await parseFailure.click('Retry')
  assert.equal(parseFailure.readings.length, 2, 'An actual parsing failure retains retry')
  assert.deepEqual(parseFailure.writes, [])
  parseFailure.dispose()
  const unknownAvailability = harness({ templateTags: { NTT: '' } })
  await settle(); await unknownAvailability.click('Enter inputs manually'); await unknownAvailability.control('data-open-template', 'foundationIncluded')
  assert.deepEqual(unknownAvailability.readings, [['TEMPLATE-VIEW-TEST', 'NTT']], 'Incomplete template metadata leaves availability unknown and discovers the source API')
  assert.match(text(unknownAvailability.root), /Unsupported template format/)
  assert.doesNotMatch(text(unknownAvailability.root), /No NTT template has been uploaded|Go to template upload/)
  unknownAvailability.dispose()
  console.log('PASS: uploaded failed and unknown metadata retain real API discovery and error or format states rather than claiming a missing upload')

  const h = harness()
  await settle()
  await h.click('Enter inputs manually')
  const post = find(h.root, node => node.tag === 'input' && node.props.id === 'input-projectArchitectPost')
  assert.ok(post)
  post.props.onInput({ target: { value: 'Unsaved human wording' } }); await settle()
  await h.click('1. Read documents')
  await h.control('data-remove-input', 12)
  assert.match(text(h.root), /TEST ONLY correspondence\.docx/)
  assert.match(text(h.root), /retained|archive/i)
  assert.deepEqual(h.removals, [], 'Opening confirmation is read-only')
  await h.click('Remove from project')
  assert.deepEqual(h.removals, [['TEMPLATE-VIEW-TEST', 12]])
  assert.equal(find(h.root, node => node.props?.['data-remove-input'] === 12), undefined)
  await h.click('Enter inputs manually')
  assert.equal(find(h.root, node => node.props?.id === 'input-projectArchitectPost').props.value, 'Unsaved human wording', 'Removal refresh retains unrelated unsaved edits')
  assert.deepEqual(h.writes, [], 'Removal never adopts untouched suggestions or saves unrelated edits')
  h.dispose()
  console.log('PASS: rendered correspondence removal confirms the filename and archive semantics, then refreshes active inputs')
  const failed = harness({ failRemoval: true }); await settle()
  await failed.control('data-remove-input', 12); await failed.click('Remove from project')
  assert.ok(find(failed.root, node => node.props?.['data-remove-input'] === 12))
  assert.match(text(failed.root), /Removal failed/)
  failed.dispose()
  console.log('PASS: removal failure retains the active source and reports the error')
  const preview = harness(); await settle(); await preview.click('Enter inputs manually')
  const unrelated = find(preview.root, node => node.tag === 'input' && node.props.id === 'input-projectArchitectPost')
  unrelated.props.onInput({ target: { value: 'Other unsaved wording' } }); await settle()
  await preview.control('data-open-template', 'foundationIncluded')
  assert.ok(find(preview.root, node => node.props?.['data-template-panel'] !== undefined), 'Field click opens template reading panel')
  await preview.control('data-preview-edit', 'foundationIncluded')
  assert.deepEqual(preview.writes, [], 'Viewing a model suggestion does not adopt it')
  const scalar = find(preview.root, node => node.tag === 'select' && node.props.id === 'preview-edit-foundationIncluded')
  assert.ok(scalar, 'Preview edit uses existing stable-value field control')
  scalar.props.onChange({ target: { value: 'true' } }); await settle()
  assert.equal(find(preview.root, node => node.props?.id === 'input-foundationIncluded').props.value, 'true', 'Preview change is shared with main input')
  assert.deepEqual(preview.writes, [], 'Editing alone remains unsaved')
  await preview.control('data-preview-save', 'foundationIncluded')
  assert.equal(preview.writes.length, 1)
  assert.equal(preview.writes[0][1], 'foundationIncluded')
  assert.equal(preview.writes[0][2].value, 'true')
  assert.equal(find(preview.root, node => node.props?.id === 'input-projectArchitectPost').props.value, 'Other unsaved wording')
  preview.store.switchProject('OTHER-TEST'); await settle()
  assert.equal(find(preview.root, node => node.props?.['data-template-panel'] !== undefined), undefined, 'Switching project closes source selection')
  preview.dispose()
  console.log('PASS: preview edits share inputs, save only genuine manual changes and retain unrelated draft values')

  const derived = harness({ duration: { months: '40', threshold: 'true', adopted: true } })
  await settle(); await derived.click('Enter inputs manually')
  assert.equal(find(derived.root, node => node.props?.id === 'input-periodAtLeast39Months').props.disabled, true)
  await derived.control('data-open-template', 'periodAtLeast39Months')
  await derived.control('data-preview-edit', 'periodAtLeast39Months')
  const threshold = find(derived.root, node => node.props?.id === 'preview-edit-periodAtLeast39Months')
  assert.equal(threshold.props.disabled, true, 'Preview uses the main input guard for an adopted duration')
  assert.match(text(threshold.parent.parent), /This answer follows the entered duration/)
  assert.deepEqual(derived.writes, [], 'Reading the derived answer never adopts or changes it')
  threshold.props.onChange({ target: { value: 'false' } }); await settle()
  assert.equal(threshold.props.value, 'true', 'A stale control event cannot change a derived answer that is currently locked')
  await derived.control('data-preview-save', 'periodAtLeast39Months')
  assert.deepEqual(derived.writes, [], 'The unchanged derived answer cannot be submitted as a manual override')
  for (const [locale, explanation] of [['zh-Hans', '该判断由已填写的工期派生'], ['zh-Hant', '該判斷由已填寫的工期派生']]) {
    derived.store.locale = locale; await settle()
    assert.match(text(threshold.parent.parent), new RegExp(explanation))
    assert.equal(threshold.props.disabled, true)
  }
  derived.store.locale = 'en'; await settle(); await derived.click('Close')
  const months = find(derived.root, node => node.props?.id === 'input-contractPeriodMonths')
  months.props.onInput({ target: { value: '' } }); await settle()
  await derived.control('data-open-template', 'periodAtLeast39Months')
  await derived.control('data-preview-edit', 'periodAtLeast39Months')
  const direct = find(derived.root, node => node.props?.id === 'preview-edit-periodAtLeast39Months')
  assert.equal(direct.props.disabled, false, 'Clearing the optional duration unlocks the direct threshold answer')
  direct.props.onChange({ target: { value: 'false' } }); await settle()
  await derived.control('data-preview-save', 'periodAtLeast39Months')
  assert.equal(derived.writes.length, 1)
  assert.equal(derived.writes[0][1], 'periodAtLeast39Months')
  assert.equal(derived.writes[0][2].value, 'false')
  assert.equal(find(derived.root, node => node.props?.id === 'input-contractPeriodMonths').props.value, '', 'Saving the threshold preserves the unrelated unsaved duration clearing')
  derived.dispose()
  console.log('PASS: the preview explains and disables the derived 39-month answer consistently with the main input')
  for (const duration of [{ months: '40', threshold: 'false', adopted: true }, { months: '40', threshold: 'true', adopted: false }, { months: '40', threshold: '', adopted: true }]) {
    const editable = harness({ duration }); await settle(); await editable.click('Enter inputs manually')
    assert.equal(find(editable.root, node => node.props?.id === 'input-periodAtLeast39Months').props.disabled, false)
    await editable.control('data-open-template', 'periodAtLeast39Months'); await editable.control('data-preview-edit', 'periodAtLeast39Months')
    assert.equal(find(editable.root, node => node.props?.id === 'preview-edit-periodAtLeast39Months').props.disabled, false, 'Conflicting, suggested-duration or unknown threshold remains editable in both controls')
    if (duration.adopted && duration.threshold === 'false') {
      find(editable.root, node => node.props?.id === 'preview-edit-periodAtLeast39Months').props.onChange({ target: { value: 'true' } }); await settle()
      await editable.control('data-preview-save', 'periodAtLeast39Months')
      assert.equal(editable.writes.length, 1, 'A genuine correction from a conflict to the derived answer can still be saved')
      assert.equal(editable.writes[0][2].value, 'true')
    }
    editable.dispose()
  }
  console.log('PASS: threshold conflict, unknown value and unadopted duration keep the existing direct-answer semantics in the preview')

  const emptyOptional = harness({ duration: { months: '', threshold: '', adopted: false } })
  await settle(); await emptyOptional.click('Enter inputs manually')
  await emptyOptional.control('data-open-template', 'contractPeriodMonths'); await emptyOptional.control('data-preview-edit', 'contractPeriodMonths')
  const optionalModal = find(emptyOptional.root, node => node.props?.id === 'preview-edit-contractPeriodMonths').parent.parent
  assert.equal(!!find(optionalModal, node => node.tag === 'button' && text(node).trim() === 'Adopt current suggestion'), false, 'An empty optional input has no current suggestion to adopt')
  assert.deepEqual(emptyOptional.writes, [])
  emptyOptional.dispose()
  console.log('PASS: an empty optional preview input never presents adoption as an available action')
  for (const duration of [
    { months: '', threshold: '', adoptionState: 'conflict' },
    { months: '', threshold: '', reviewRequired: true },
    { months: '-1', threshold: '', reviewRequired: true },
    { months: 'unknown', threshold: '' },
    { months: '40', threshold: 'true', reviewRequired: true, validationIssue: 'invalid' }
  ]) {
    const unavailable = harness({ duration }); await settle(); await unavailable.click('Enter inputs manually')
    await unavailable.control('data-open-template', 'contractPeriodMonths'); await unavailable.control('data-preview-edit', 'contractPeriodMonths')
    const modal = find(unavailable.root, node => node.props?.id === 'preview-edit-contractPeriodMonths').parent.parent
    assert.equal(!!find(modal, node => node.tag === 'button' && text(node).trim() === 'Adopt current suggestion'), false, 'Missing/conflicting blank and invalid current inputs cannot be adopted from the preview')
    assert.deepEqual(unavailable.writes, [])
    unavailable.dispose()
  }
  for (const scenario of [
    { key: 'foundationIncluded' },
    { key: 'contractPeriodMonths', duration: { months: '0', threshold: 'false', adopted: false } },
    { key: 'contractPeriodMonths', duration: { months: '40', threshold: 'true', adopted: true, reviewRequired: true } }
  ]) {
    const available = harness({ duration: scenario.duration }); await settle(); await available.click('Enter inputs manually')
    await available.control('data-open-template', scenario.key); await available.control('data-preview-edit', scenario.key)
    const modal = find(available.root, node => node.props?.id === `preview-edit-${scenario.key}`).parent.parent
    const adoptCurrent = find(modal, node => node.tag === 'button' && text(node).trim() === 'Adopt current suggestion')
    assert.ok(adoptCurrent, 'False, zero and an existing valid value requiring review remain explicitly adoptable')
    await adoptCurrent.props.onClick(); await settle()
    assert.equal(available.writes.length, 1)
    assert.equal(available.writes[0][1], scenario.key)
    assert.equal(available.writes[0][2].confirmed, true)
    assert.equal(available.writes[0][2].value, undefined, 'Adoption does not turn an untouched current value into a manual override')
    if (scenario.duration?.reviewRequired) assert.equal(available.writes[0][2].reviewed, true)
    available.dispose()
  }
  const manualClear = harness({ duration: { months: '40', threshold: 'true', adopted: true } })
  await settle(); await manualClear.click('Enter inputs manually')
  await manualClear.control('data-open-template', 'contractPeriodMonths'); await manualClear.control('data-preview-edit', 'contractPeriodMonths')
  const clearInput = find(manualClear.root, node => node.props?.id === 'preview-edit-contractPeriodMonths')
  clearInput.props.onInput({ target: { value: '' } }); await settle()
  assert.equal(!!find(clearInput.parent.parent, node => node.tag === 'button' && text(node).trim() === 'Adopt current suggestion'), false)
  await manualClear.control('data-preview-save', 'contractPeriodMonths')
  assert.equal(manualClear.writes.length, 1)
  assert.equal(manualClear.writes[0][2].value, '', 'A genuine manual clearing still saves through the manual-change flow')
  manualClear.dispose()
  console.log('PASS: preview adoption requires a valid current value, preserves explicit false/zero/review adoption, and keeps manual clearing saveable')
  const staleAdoption = harness({ duration: { months: '40', threshold: 'true', adopted: false } })
  await settle(); await staleAdoption.click('Enter inputs manually')
  await staleAdoption.control('data-open-template', 'contractPeriodMonths'); await staleAdoption.control('data-preview-edit', 'contractPeriodMonths')
  const staleInput = find(staleAdoption.root, node => node.props?.id === 'preview-edit-contractPeriodMonths')
  const oldAdoptClick = find(staleInput.parent.parent, node => node.tag === 'button' && text(node).trim() === 'Adopt current suggestion').props.onClick
  staleInput.props.onInput({ target: { value: '' } }); await settle()
  await oldAdoptClick(); await settle()
  assert.equal(staleAdoption.writes.length, 0, 'A captured preview-adoption callback must recheck the current blank/dirty answer before submitting')
  await staleAdoption.control('data-preview-save', 'contractPeriodMonths')
  assert.equal(staleAdoption.writes.length, 1, 'The same current blank value can still be saved as an explicit manual clearing')
  assert.equal(staleAdoption.writes[0][2].value, '')
  staleAdoption.dispose()
  console.log('PASS: stale preview adoption cannot submit a newly blank input while explicit manual clearing still saves')
})().catch(error => { console.error(error); process.exitCode = 1 })
