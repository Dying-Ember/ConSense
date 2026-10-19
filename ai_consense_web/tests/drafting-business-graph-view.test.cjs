// Rendered public BG-02 seam. Synthetic catalogue/plan fixtures are explicit.
const assert = require('node:assert/strict'), path = require('node:path')
const { JSDOM } = require('jsdom'), dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window', 'document', 'Document', 'ShadowRoot', 'Element', 'HTMLElement', 'SVGElement', 'Node']) globalThis[key] = key === 'window' ? dom.window : dom.window[key]
const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs')
const label = (en, zhHans = en, zhHant = zhHans) => ({ en, zhHans, zhHant })
function fixture() {
  const row = { id: 'ROW-UUID-PRIVATE', number: '1', description: 'Complete formal Building Works description', type: 'building' }
  const foundation = { key: 'foundation', kind: 'boolean', label: label('Foundation included', '包括地基', '包括地基'), affects: [{ document: 'NTT', clause: 'NTT10', paragraphs: 'P104' }] }
  const duration = { key: 'duration', kind: 'boolean', label: label('Duration at least 39 months', '工期不少于39个月', '工期不少於39個月'), affects: [{ document: 'NTT', clause: 'NTT10', paragraphs: 'P104' }] }
  const bill = { key: 'billNos', kind: 'bills', label: label('Bills', '工程量清单', '工程量清單'), condition: { all: [{ field: 'foundation', operator: 'eq', value: true }, { field: 'duration', operator: 'eq', value: true }] }, columnFields: [{ key: 'id', kind: 'text', label: label('Technical row identifier') }, { key: 'number', kind: 'text', label: label('Bill number') }, { key: 'description', kind: 'text', label: label('Formal description') }, { key: 'type', kind: 'text', label: label('Bill type'), options: [{ value: 'building', label: label('Building Works') }] }], affects: [{ document: 'SCT', clause: 'SCT1', paragraphs: 'P170' }] }
  const phone = { key: 'phone', kind: 'text', label: label('Contact telephone', '联系电话', '聯絡電話'), affects: [{ document: 'SCC', clause: 'SCC6', paragraphs: 'P91' }] }
  const catalog = { ruleVersion: 'TEST-GRAPH-1', groups: [{ id: 'scope', label: label('Scope', '范围', '範圍'), fields: [foundation, duration] }, { id: 'billing', label: label('Bills and contact', '清单与联络', '清單與聯絡'), fields: [bill, phone] }] }
  const values = { foundation: true, duration: true, billNos: [row], phone: '1234' }
  const inputValues = { foundation: 'true', duration: 'true', billNos: JSON.stringify([row]), phone: '1234' }
  const actions = [{ id: 'NTT-BOND', document: 'NTT', clause: 'NTT10', paragraphs: 'P104', action: 'amend', inputKeys: ['foundation', 'duration'], detail: label('Foundation inclusion AND duration ≥39 months select G1a.'), value: 'Appendix G1a' }, { id: 'SCT-BILLS', document: 'SCT', clause: 'SCT1', paragraphs: 'P170', action: 'fill', inputKeys: ['billNos'], detail: label('Fill the formal Bill descriptions.') }, { id: 'SCC-PHONE', document: 'SCC', clause: 'SCC6', paragraphs: 'P91', action: 'fill', inputKeys: ['phone'], detail: label('Fill the telephone.') }]
  return { projectId: 'TEST-GRAPH-PROJECT', catalog, plan: { ruleVersion: 'TEST-GRAPH-1', inputValues, effectiveValues: { ...inputValues }, derived: {}, actions, unresolved: [] }, values, variables: [{ key: 'foundation', source: 'SYNTHETIC brief', candidates: [{ value: 'true', fileName: 'SYNTHETIC evidence.pdf', sourceQuote: 'Foundation works included.', reason: 'Recorded model explanation' }] }], fieldStates: { foundation: 'adopted' }, dirtyKeys: [], locale: 'en', disabled: false }
}
const settle = async () => { for (let n = 0; n < 8; n++) { await Promise.resolve(); await vue.nextTick() } }
async function harness(overrides = {}) {
  const props = vue.reactive({ ...fixture(), ...overrides }), events = []
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingBusinessGraph.vue'), { globals: { document, window, Element, HTMLElement, SVGElement, Node } }).default
  const element = document.createElement('div'); document.body.append(element)
  const app = vue.createApp({ render: () => vue.h(loaded, { ...props, onInput: key => events.push(['input', key]), onLocation: target => events.push(['location', target]), onClose: () => events.push(['close']) }) }); app.mount(element); await settle()
  return { props, events, element, async click(selector) { const node = element.querySelector(selector); assert(node, `Rendered public control ${selector}`); node.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await settle() }, async change(selector, value) { const node = element.querySelector(selector); assert(node, `Rendered public control ${selector}`); node.value = value; node.dispatchEvent(new window.Event(node.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); await settle() }, dispose() { app.unmount(); element.remove() } }
}
async function actualPlannedChainAndNavigation() {
  const h = await harness(), before = JSON.stringify(h.props.values)
  assert(h.element.querySelector('[role="dialog"]'), 'The graph is an independent readonly dialog')
  await h.click('[data-graph-input="foundation"]'); await h.click('[data-graph-action="NTT-BOND"]')
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Foundation inclusion AND duration ≥39 months select G1a/)
  assert.equal(h.element.querySelectorAll('[data-shared-input]').length, 2, 'One action exposes both exact inputs instead of inventing independent decisions')
  assert.match(h.element.textContent, /Planned actions/); assert.match(h.element.querySelector('[data-graph-evidence]').textContent, /Foundation works included/)
  await h.click('[data-graph-clause="NTT-BOND"]')
  assert.deepEqual(JSON.parse(JSON.stringify(h.events.at(-1))), ['location', { fieldKey: 'foundation', actionId: 'NTT-BOND', document: 'NTT', clause: 'NTT10' }])
  await h.click('[data-graph-return-input]'); assert.deepEqual(h.events.at(-1), ['input', 'foundation'])
  assert.equal(JSON.stringify(h.props.values), before, 'Graph selection and navigation never rewrite adopted or dirty values')
  h.dispose(); console.log('PASS: current planned chain, shared inputs, readonly source evidence and exact navigation events')
}
async function exactConditionsStructureAndPendingPlan() {
  const h = await harness()
  await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-input="billNos"]')
  const conditions = h.element.querySelector('[data-graph-condition]')
  assert.match(conditions.textContent, /All prerequisites \(AND\)/)
  assert.match(conditions.textContent, /Foundation included.*equals.*Yes/s)
  assert.match(conditions.textContent, /Duration at least 39 months.*equals.*Yes/s)
  const structure = h.element.querySelector('[data-graph-structure]')
  assert.match(structure.textContent, /Bill number/); assert.match(structure.textContent, /Formal description/)
  assert.doesNotMatch(h.element.textContent, /ROW-UUID-PRIVATE|Technical row identifier|"id"/)
  assert.match(h.element.querySelector('[data-graph-current-value]').textContent, /Complete formal Building Works description/)
  h.props.values.duration = false; h.props.dirtyKeys = ['duration']; await settle()
  assert.equal(h.element.querySelectorAll('[data-graph-action]').length, 0)
  assert.match(h.element.textContent, /plan does not match current inputs/)
  assert.equal(h.element.querySelector('[data-graph-return-input]').disabled, true, 'Inactive input remains inspectable but cannot route to a missing editor')
  await h.click('[data-graph-return-input]'); assert.equal(h.events.length, 0)
  await h.click('[data-graph-potential="SCT:SCT1"]')
  assert.deepEqual(JSON.parse(JSON.stringify(h.events.at(-1))), ['location', { fieldKey: 'billNos', actionId: '', document: 'SCT', clause: 'SCT1' }])
  h.props.values.billNos = null; await settle(); assert.match(h.element.querySelector('[data-graph-current-value]').textContent, /Unknown/i)
  h.props.values.billNos = []; await settle(); assert.match(h.element.querySelector('[data-graph-current-value]').textContent, /0 items|No items/i)
  h.props.locale = 'zh-Hant'; await settle(); assert.match(h.element.textContent, /業務關係圖譜|結構欄位/)
  h.dispose(); console.log('PASS: exact AND prerequisites, structural collection columns, null versus empty, hidden identifiers and stale potential targets')
}
async function filtersNeighborhoodAndInputFreeActions() {
  const base = fixture()
  base.plan.actions.push({ id: 'NTT-GUIDANCE', document: 'NTT', clause: 'Guidance', action: 'delete', inputKeys: [], detail: label('Remove native drafting guidance.') })
  base.catalog.systemFields = [{ key: 'targetOverrides', hidden: true, kind: 'object', label: label('Manual target override') }]
  base.plan.actions[0].inputKeys.push('targetOverrides')
  const h = await harness(base)
  await h.change('[data-graph-group]', '')
  assert.equal(h.element.querySelectorAll('[data-graph-input]').length, 4, 'All catalogue business inputs are inspectable')
  assert.equal(h.element.querySelectorAll('[data-graph-action]').length, 4, 'All mode includes source-only guidance actions without inventing inputs')
  await h.click('[data-graph-action="NTT-GUIDANCE"]')
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Remove native drafting guidance/)
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /no linked business input/i)
  assert.equal(h.element.querySelector('[data-graph-clause="NTT-GUIDANCE"]').disabled, true)
  await h.click('[data-graph-file="SCT"]')
  assert.equal(h.element.querySelectorAll('[data-graph-input]').length, 1)
  assert.equal(h.element.querySelectorAll('[data-graph-action]').length, 1)
  await h.click('[data-graph-clear]')
  await h.change('[data-graph-group]', ''); await h.change('[data-graph-search]', 'telephone')
  assert.deepEqual([...h.element.querySelectorAll('[data-graph-input]')].map(node => node.dataset.graphInput), ['phone'])
  await h.click('[data-graph-input="phone"]'); assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /1234/)
  await h.click('[data-graph-clear]'); await h.click('[data-graph-input="foundation"]'); await h.click('[data-graph-action="NTT-BOND"]')
  assert.equal(h.element.querySelectorAll('[data-shared-input="targetOverrides"]').length, 0, 'Hidden override metadata cannot create a selectable business input')
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Manual target override.*metadata/s)
  assert.match(h.element.querySelector('[data-graph-action="NTT-BOND"]').textContent, /Amend/)
  h.dispose(); console.log('PASS: all/group/search/file filters, selected neighborhoods, input-free guidance and hidden override metadata')
}
async function keyboardLifecycleProjectAndDisabledGuards() {
  const h = await harness(), initial = h.element.querySelector('[data-graph-search]')
  assert.equal(document.activeElement, initial, 'Dialog initial focus belongs to the graph')
  const dialog = h.element.querySelector('[role="dialog"]')
  const keydown = (node, key, extra = {}) => { const event = new window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...extra }); node.dispatchEvent(event); return event }
  const last = [...dialog.querySelectorAll('button:not(:disabled),input,select,[tabindex="0"]')].at(-1)
  last.focus(); assert(keydown(last, 'Tab').defaultPrevented); await settle()
  assert.equal(document.activeElement, dialog.querySelector('button:not(:disabled),input,select,[tabindex="0"]'), 'Tab wraps inside the dialog')
  await h.click('[data-graph-fullscreen]'); assert.equal(h.element.querySelector('[data-graph-fullscreen]').getAttribute('aria-pressed'), 'true')
  const escaped = keydown(dialog, 'Escape'); assert(escaped.defaultPrevented); await settle()
  assert.equal(h.events.length, 0, 'Escape leaves fullscreen before closing the overlay')
  assert.equal(document.activeElement, h.element.querySelector('[data-graph-fullscreen]'))
  keydown(dialog, 'Escape'); await settle(); assert.deepEqual(h.events.at(-1), ['close'])
  h.events.length = 0; await h.click('[data-graph-input="foundation"]'); h.props.disabled = true; await settle()
  await h.click('[data-graph-return-input]'); await h.click('[data-graph-clause="NTT-BOND"]'); await h.click('[data-graph-potential="NTT:NTT10"]')
  assert.equal(h.events.length, 0, 'Busy or body-dirty guards prevent both navigation routes while readonly inspection remains')
  h.props.projectId = 'TEST-OTHER-PROJECT'; await settle()
  assert.equal(h.element.querySelectorAll('[data-graph-action]').length, 0, 'A plan receipt from the former project cannot remain visible')
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Select an input/)
  h.props.plan = { ...h.props.plan }; h.props.disabled = false; await settle()
  assert(h.element.querySelector('[data-graph-action="NTT-BOND"]'), 'A fresh current-plan receipt restores actual action inspection')
  h.dispose(); console.log('PASS: keyboard focus trap, fullscreen Escape lifecycle, disabled routes and project identity reset')
}
async function localizedPlanTreatmentsAndInvalidStructuredValues() {
  const base = fixture(), bill = base.catalog.groups[1].fields[0]
  bill.columnFields.push({ key: 'detail', kind: 'object', label: label('Bill metadata') })
  base.values.billNos[0].detail = '{RAW-UUID-PRIVATE invalid JSON}'
  base.plan.inputValues.billNos = JSON.stringify(base.values.billNos)
  base.plan.actions.push({ id: 'NTT-NOT-USED', document: 'NTT', clause: 'Unused', action: 'not_used', inputKeys: ['foundation'], detail: label('Mark the source clause Not used.') }, { id: 'NTT-NOT-ADOPTED', document: 'NTT', clause: 'Not adopted', action: 'not_adopted', inputKeys: ['foundation'], detail: label('The alternative is not adopted.') }, { id: 'NTT-PENDING', document: 'NTT', clause: 'Pending', action: 'pending', inputKeys: ['foundation'], detail: label('This decision remains pending.') })
  const h = await harness(base)
  await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-input="billNos"]')
  assert.doesNotMatch(h.element.querySelector('[data-graph-current-value]').textContent, /RAW-UUID-PRIVATE|invalid JSON/)
  assert.match(h.element.querySelector('[data-graph-current-value]').textContent, /cannot be displayed as structured data/)
  for (const [locale, expected] of [['en', ['Mark Not used', 'Not adopted', 'Pending']], ['zh-Hans', ['标记 Not used', '本次不采用', '待核']], ['zh-Hant', ['標記 Not used', '本次不採用', '待核']]]) {
    h.props.locale = locale; await settle(); await h.click('[data-graph-clear]')
    assert.match(h.element.querySelector('[data-graph-action="NTT-NOT-USED"]').textContent, new RegExp(expected[0]))
    assert.match(h.element.querySelector('[data-graph-action="NTT-NOT-ADOPTED"]').textContent, new RegExp(expected[1]))
    assert.match(h.element.querySelector('[data-graph-action="NTT-PENDING"]').textContent, new RegExp(expected[2]))
  }
  h.dispose(); console.log('PASS: distinct planned clause treatments in three locales and no raw malformed collection data')
}
async function inactiveRecordedAdoptionRemainsSeparate() {
  const base = fixture()
  base.values.foundation = false; base.plan.inputValues.foundation = 'false'
  base.fieldStates.billNos = 'inactive'; base.dirtyKeys = ['billNos']
  base.variables.push({ key: 'billNos', confirmed: true, manuallyEdited: false, adoptionState: 'adopted', value: JSON.stringify(base.values.billNos) })
  const h = await harness(base)
  await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-input="billNos"]')
  const inspector = h.element.querySelector('[data-graph-inspector]')
  assert.match(inspector.textContent, /Recorded adoption.*Adopted/s, 'Inactive applicability must not erase the saved adoption record')
  assert.match(inspector.querySelector('[data-graph-applicability]').textContent, /Inactive for current inputs/)
  assert.match(inspector.querySelector('[data-graph-adoption]').textContent, /Recorded adoption.*Adopted/s)
  assert.match(inspector.querySelector('[data-graph-dirty]').textContent, /Unsaved input/)
  assert.equal(h.element.querySelector('[data-graph-return-input]').disabled, true)
  h.props.variables.find(variable => variable.key === 'billNos').manuallyEdited = true; await settle()
  assert.match(inspector.querySelector('[data-graph-adoption]').textContent, /Manually adopted/)
  const record = h.props.variables.find(variable => variable.key === 'billNos')
  record.reviewRequired = true; await settle()
  assert.match(inspector.textContent, /Recorded review required/, 'Inactive historical adoption must retain an explicit recorded review flag')
  assert.match(inspector.querySelector('[data-graph-adoption]').textContent, /Manually adopted/)
  record.reviewRequired = false; record.adoptionState = 'needs_review'; await settle()
  assert(inspector.querySelector('[data-graph-recorded-review]'), 'The recorded needs_review state remains visible even alongside manual adoption')
  for (const [locale, adopted, dirty] of [['zh-Hans', '人工采用', '输入未保存'], ['zh-Hant', '人工採用', '輸入未儲存']]) {
    h.props.locale = locale; await settle(); assert.match(inspector.querySelector('[data-graph-adoption]').textContent, new RegExp(adopted)); assert.match(inspector.querySelector('[data-graph-dirty]').textContent, new RegExp(dirty))
  }
  await h.click('[data-graph-return-input]'); assert.equal(h.events.length, 0)
  h.dispose(); console.log('PASS: inactive applicability retains recorded adopted/manual status and unsaved state separately')
}
async function selectedActionPrecedesLongSourceEvidence() {
  const base = fixture(); base.variables[0].candidates[0].sourceQuote = `LONG-SOURCE-EVIDENCE ${'Recorded source context. '.repeat(100)}`
  const h = await harness(base)
  await h.click('[data-graph-input="foundation"]')
  const inspector = h.element.querySelector('[data-graph-inspector]')
  const relationship = 'Foundation inclusion AND duration ≥39 months select G1a.'
  assert(inspector.textContent.indexOf(relationship) < inspector.textContent.indexOf('LONG-SOURCE-EVIDENCE'), 'Selected input relationship wording should be readable before long source evidence')
  await h.click('[data-graph-action="NTT-BOND"]')
  assert(inspector.textContent.indexOf(relationship) < inspector.textContent.indexOf('LONG-SOURCE-EVIDENCE'), 'Explicit action selection preserves relationship-first inspection')
  assert.match(inspector.querySelector('[data-graph-evidence]').textContent, /LONG-SOURCE-EVIDENCE/)
  await h.click('[data-graph-clause="NTT-BOND"]')
  assert.deepEqual(JSON.parse(JSON.stringify(h.events.at(-1))), ['location', { fieldKey: 'foundation', actionId: 'NTT-BOND', document: 'NTT', clause: 'NTT10' }])
  await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-input="billNos"]')
  assert(inspector.textContent.indexOf('Fill the formal Bill descriptions.') < inspector.textContent.indexOf('Complete formal Building Works description'), 'Plan wording also precedes a potentially long collection value')
  h.dispose(); console.log('PASS: selected input/action wording precedes long evidence while exact native navigation remains')
}
async function selectionDimsExistingPoolWithoutRemovingOrMovingSiblings() {
  const h = await harness()
  await h.change('[data-graph-group]', '')
  const pool = () => [...h.element.querySelectorAll('[data-graph-input],[data-graph-action],[data-graph-subfield],[data-graph-pool-group],[data-graph-parent],[data-graph-file]')].map(node => ({ key: node.dataset.graphInput || node.dataset.graphAction || node.dataset.graphSubfield || `group:${node.dataset.graphPoolGroup || node.dataset.graphParent || node.dataset.graphFile}`, position: node.getAttribute('transform') }))
  const baseline = pool()
  assert.equal(h.element.querySelectorAll('[data-graph-input],[data-graph-action]').length, 7, 'Synthetic all-group pool contains four inputs and three planned actions')
  await h.click('[data-graph-input="foundation"]')
  assert.deepEqual(pool(), baseline, 'Selecting an input preserves unrelated inputs/actions and their positions in the current pool')
  assert(h.element.querySelector('[data-graph-input="phone"]').classList.contains('dim'), 'Unrelated sibling is dimmed, not removed')
  assert(h.element.querySelector('[data-graph-action="SCC-PHONE"]').classList.contains('dim'), 'Unrelated planned action is dimmed, not removed')
  await h.click('[data-graph-action="NTT-BOND"]')
  assert.deepEqual(pool(), baseline, 'Selecting a shared action preserves the current pool')
  await h.click('[data-graph-input="phone"]')
  assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Contact telephone/)
  assert.equal(h.element.querySelector('[data-graph-input="phone"]').classList.contains('dim'), false, 'A dimmed sibling remains selectable')
  await h.change('[data-graph-group]', 'billing'); await h.change('[data-graph-search]', 'Bills')
  const filtered = pool(); await h.click('[data-graph-input="billNos"]')
  assert.equal(h.element.querySelector('[data-graph-search]').value, 'Bills', 'Selection does not clear explicit search')
  assert.equal(h.element.querySelector('[data-graph-group]').value, 'billing', 'Selection does not replace explicit group')
  for (const item of filtered) assert.deepEqual(pool().find(candidate => candidate.key === item.key), item, 'Related prerequisites may append without moving the filtered pool')
  assert(h.element.querySelector('[data-graph-input="foundation"]'), 'Outside-pool prerequisites remain inspectable as added context')
  await h.click('[data-graph-clear-selection]')
  assert.deepEqual(pool(), filtered, 'Clearing only selection restores the same explicitly filtered pool and layout')
  assert.equal(h.element.querySelector('[data-graph-search]').value, 'Bills')
  assert.equal(h.element.querySelector('[data-graph-group]').value, 'billing')
  assert.equal(h.element.querySelectorAll('.graph-node.dim').length, 0)
  await h.click('[data-graph-clear]'); await h.change('[data-graph-group]', '')
  assert.deepEqual(pool(), baseline, 'Resetting filters restores the complete baseline hierarchy')
  h.dispose(); console.log('PASS: selected chain dims, preserves positions and keeps sibling inputs/actions selectable without replacing filters')
}
async function visibleParentChildStructureDistinguishesVariablesFromConditionsAndColumns() {
  const base = fixture()
  base.values.billNos.push({ id: 'SECOND-ROW-UUID-PRIVATE', number: '2', description: 'Second complete formal description', type: 'building' })
  base.plan.inputValues.billNos = JSON.stringify(base.values.billNos)
  const before = JSON.stringify(base.values)
  base.catalog.groups.push({ id: 'contractType', label: label('Contract type and duration'), fields: [
    { key: 'foundationIncluded', kind: 'boolean', label: label('Combined contract includes foundation') },
    { key: 'periodAtLeast39Months', kind: 'boolean', label: label('Period at least 39 months') },
    { key: 'contractPeriodMonths', kind: 'number', optional: true, label: label('Contract period in months') }
  ] }, { id: 'identity', label: label('Contract identity'), fields: [{ key: 'contractTitle', kind: 'contract', label: label('Contract number and title'), columnFields: [{ key: 'number', kind: 'text', label: label('Contract number') }, { key: 'title', kind: 'text', label: label('Contract title') }] }] })
  const h = await harness(base)
  await h.change('[data-graph-group]', '')
  const parent = h.element.querySelector('[data-graph-parent="contractType"]')
  assert(parent, 'Confirmed composite contract type is visibly labeled as a parent variable')
  assert.match(parent.textContent, /Parent variable/)
  for (const key of ['foundationIncluded', 'periodAtLeast39Months']) assert.equal(h.element.querySelector(`[data-graph-input="${key}"]`).getAttribute('data-graph-input-kind'), 'subvariable', 'The existing composing inputs retain their keys but are visibly child variables')
  assert.equal(h.element.querySelector('[data-graph-input="contractPeriodMonths"]').getAttribute('data-graph-input-kind'), 'supporting-value', 'Optional month fact is related to the parent without inventing a third independent boolean decision')
  assert(h.element.querySelector('[data-graph-pool-group="billing"]'), 'Ordinary catalogue groups remain clearly labeled groups')
  assert.equal(h.element.querySelector('[data-graph-parent="billing"]'), null, 'A display group must not become a fabricated business parent')
  const billColumn = h.element.querySelector('[data-graph-subfield="billNos.description"]')
  assert(billColumn, 'Collection item fields are drawn in the graph, not only listed in inspector')
  assert.match(billColumn.textContent, /Item field/)
  assert.match(h.element.querySelector('[data-graph-subfield="contractTitle.title"]').textContent, /Sub-variable/)
  assert.equal(h.element.querySelector('[data-graph-subfield="billNos.id"]'), null)
  await h.click('[data-graph-subfield="billNos.description"]')
  const inspector = h.element.querySelector('[data-graph-inspector]')
  assert.match(inspector.querySelector('[data-graph-selected-subfield]').textContent, /Formal description.*Bills.*each item/s)
  assert.match(inspector.querySelector('[data-graph-condition]').textContent, /All prerequisites \(AND\)/, 'Conditional prerequisites remain distinct from structural ownership')
  assert.match(inspector.querySelector('[data-graph-selected-subfield]').textContent, /Complete formal Building Works description.*Second complete formal description/s, 'A selected collection column shows every current row value, not a false first-row answer')
  assert.doesNotMatch(h.element.textContent, /SECOND-ROW-UUID-PRIVATE/)
  await h.click('[data-graph-return-input]'); assert.deepEqual(h.events.at(-1), ['input', 'billNos'], 'Child navigation returns to its existing parent editor, not an invented independent input')
  await h.change('[data-graph-search]', 'contractTitle.title')
  assert.deepEqual([...h.element.querySelectorAll('[data-graph-input]')].map(node => node.dataset.graphInput), ['contractTitle'], 'Searching a child key retains its real parent and nested structure')
  await h.click('[data-graph-subfield="contractTitle.title"]')
  assert.match(inspector.querySelector('[data-graph-selected-subfield]').textContent, /Contract title.*Contract number and title.*component of the parent/s)
  await h.click('[data-graph-return-input]'); assert.deepEqual(h.events.at(-1), ['input', 'contractTitle'])
  assert.equal(JSON.stringify(h.props.values), before, 'Selecting structural fields remains read only')
  h.dispose(); console.log('PASS: visible composite parent, actual child inputs, object subvariables and item-field structure remain distinct from prerequisites')
}
async function conditionalCollectionFieldUsesEachRowsOwnPrerequisites() {
  const base = fixture(), bill = base.catalog.groups[1].fields[0]
  bill.columnFields.push({ key: 'placementText', kind: 'text', optional: true, label: label('Placement instructions'), condition: { all: [{ field: 'type', operator: 'eq', value: 'custom' }] } })
  base.values.billNos[0].placementText = 'Retained inactive row wording'
  base.values.billNos.push({ number: '2', description: 'Another complete bill', type: 'custom', placementText: 'Applicable custom issue instructions' })
  base.plan.inputValues.billNos = JSON.stringify(base.values.billNos)
  const h = await harness(base); await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-subfield="billNos.placementText"]')
  const rows = [...h.element.querySelectorAll('[data-graph-selected-subfield] tbody tr')].map(row => row.textContent)
  assert.match(rows[0], /Retained inactive row wording.*Inactive for current inputs/s, 'A structural column does not make an inactive stored row value currently applicable')
  assert.match(rows[1], /Applicable custom issue instructions.*Applicable/s, 'Applicability is evaluated against its own row, not global inputs or another row')
  h.props.values.billNos = null; await settle()
  assert.match(h.element.querySelector('[data-graph-selected-subfield]').textContent, /Unknown/i)
  h.props.values.billNos = []; await settle()
  assert.match(h.element.querySelector('[data-graph-selected-subfield]').textContent, /No items|0 items/i)
  h.dispose(); console.log('PASS: structural child inspection preserves each-row conditional applicability and unknown versus explicit empty parents')
}
async function explicitSearchDefinesCompactDestinationGeometry() {
  const base = fixture()
  for (let index = 0; index < 80; index++) base.plan.actions.push({ id: `SYNTHETIC-OTHER-${index}`, document: 'NTT', clause: `Other clause ${index}`, action: 'amend', inputKeys: ['foundation'], detail: label('Other foundation processing') })
  const h = await harness(base); await h.change('[data-graph-group]', ''); await h.change('[data-graph-search]', 'telephone')
  assert.equal(h.element.querySelectorAll('[data-graph-input]').length, 1)
  assert.equal(h.element.querySelectorAll('[data-graph-action]').length, 1)
  const filePositions = () => [...h.element.querySelectorAll('[data-graph-file]')].map(node => node.getAttribute('transform'))
  const baseline = filePositions()
  for (const position of baseline) assert(Number(position.match(/translate\(\S+ (\S+)\)/)[1]) < 400, 'Explicit search compacts destinations to the searched pool instead of retaining hidden action height')
  await h.click('[data-graph-input="phone"]'); assert.deepEqual(filePositions(), baseline, 'Selection preserves searched destination positions')
  h.dispose(); console.log('PASS: explicit search compacts destination geometry while selection keeps it stable')
}
async function compactCardsHaveReadableWideRoutingGutters() {
  const h = await harness(); await h.change('[data-graph-group]', '')
  const box = selector => { const node = h.element.querySelector(selector), rect = node.querySelector('rect'), [x, y] = node.getAttribute('transform').match(/[-\d.]+/g).map(Number); return { x, y, w: Number(rect.getAttribute('width')), h: Number(rect.getAttribute('height')) } }
  const input = box('[data-graph-input="billNos"]'), action = box('[data-graph-action="SCT-BILLS"]'), file = box('[data-graph-file="SCT"]')
  assert(input.w <= 225 && input.h <= 56, 'Input cards stay compact at native scale rather than filling the canvas')
  assert(action.w <= 220 && action.h <= 56, 'Action cards remain compact while retaining two readable text lines')
  assert(action.x - (input.x + input.w) >= 150, 'Input/action columns have a real routing gutter')
  assert(file.x - (action.x + action.w) >= 150, 'Action/file columns have a real routing gutter')
  const svg = h.element.querySelector('.graph-scroll svg')
  assert(Number(svg.getAttribute('width')) > 0, 'SVG owns a native pixel width instead of unbounded parent scaling')
  assert.equal(Number(svg.getAttribute('width')), Number(svg.getAttribute('viewBox').split(' ')[2]), 'Initial scale is 100%, keeping node text readable on wide and narrow canvases')
  h.dispose(); console.log('PASS: compact native-sized cards retain readable labels and wide routing gutters')
}
async function manyToManyRelationshipsUseDistinctStablePortsAndLanes() {
  const base = fixture()
  for (let index = 0; index < 8; index++) base.plan.actions.push({ id: `SCT-BILL-OTHER-${index}`, document: 'SCT', clause: `Bill instruction ${index}`, action: 'amend', inputKeys: ['billNos'], detail: label('Use this Bill list in this recorded SCT clause') })
  const h = await harness(base); await h.change('[data-graph-group]', '')
  const canvasHeight = Number(h.element.querySelector('.graph-scroll svg').getAttribute('viewBox').split(' ')[3])
  for (const node of h.element.querySelectorAll('[data-graph-action]')) {
    const y = Number(node.getAttribute('transform').match(/translate\(\S+ (\S+)\)/)[1]), h = Number(node.querySelector('rect').getAttribute('height'))
    assert(y + h <= canvasHeight - 20, 'The reserved top routing band must not clip the final action card or remove its bottom reading margin')
  }
  const routes = () => [...h.element.querySelectorAll('.graph-edge')].map(node => node.getAttribute('d')).sort()
  const before = routes(); await h.click('[data-graph-input="billNos"]')
  const numbers = node => node.getAttribute('d').match(/-?\d+(?:\.\d+)?/g).map(Number)
  const outgoing = [...h.element.querySelectorAll('.graph-edge.dependency.selected')].map(numbers)
  assert.equal(outgoing.length, 9, 'Synthetic Bill identity affects nine actual SCT actions without hiding relationships')
  assert.equal(new Set(outgoing.map(points => points.slice(0, 2).join(','))).size, 9, 'Fan-out relationships use distinct source ports instead of a single bundled centre endpoint')
  assert.equal(new Set(outgoing.map(points => points[2])).size, 9, 'Fan-out relationships have separate control lanes inside the wide gutter')
  const destinations = [...h.element.querySelectorAll('.graph-edge.destination.selected')].map(numbers)
  assert.equal(destinations.length, 9)
  assert.equal(new Set(destinations.map(points => points.slice(-2).join(','))).size, 9, 'The nine SCT destinations use distinct receiving ports instead of collapsing at one point')
  for (const points of [...outgoing, ...destinations]) assert(points[2] > points[0] && points[4] > points[2] && points[4] < points[6], 'Bezier control lanes stay in the gutter, clear of the node interiors')
  assert.deepEqual(routes(), before, 'Selection changes emphasis, not edge routing or visible relationship identity')
  await h.click('[data-graph-action="NTT-BOND"]')
  const shared = [...h.element.querySelectorAll('.graph-edge.dependency.selected')].map(numbers)
  assert.equal(shared.length, 2)
  assert.equal(new Set(shared.map(points => points.slice(-2).join(','))).size, 2, 'A shared action receives both prerequisite input edges through distinct ports')
  await h.click('[data-graph-clear-selection]'); assert.deepEqual(routes(), before)
  await h.change('[data-graph-group]', 'billing')
  const filtered = routes(); await h.click('[data-graph-input="billNos"]')
  for (const route of filtered) assert(routes().includes(route), 'Appending outside-pool prerequisites does not move existing ports or lanes')
  h.dispose(); console.log('PASS: many-to-many input/action/file relationships have distinct stable ports and gutter lanes')
}
async function catalogueOnlyLongRelationsBypassTheActionColumn() {
  const base = fixture()
  base.catalog.groups[0].fields.unshift({ key: 'catalogueOnly', kind: 'text', label: label('Catalogue-only source'), affects: [{ document: 'SCC', clause: 'SCC catalogue target' }] })
  for (let index = 0; index < 15; index++) base.plan.actions.push({ id: `OTHER-ACTION-${index}`, document: 'SCT', clause: `Unrelated clause ${index}`, action: 'retain', inputKeys: ['phone'], detail: label('Unrelated recorded action') })
  const h = await harness(base); await h.change('[data-graph-group]', '')
  const edge = h.element.querySelector('.graph-edge.potential')
  assert(edge, 'The catalogue-only relation remains drawn instead of being removed to hide a routing defect')
  const boxes = [...h.element.querySelectorAll('[data-graph-action]')].map(node => { const [x, y] = node.getAttribute('transform').match(/[-\d.]+/g).map(Number), rect = node.querySelector('rect'); return { x, y, w: Number(rect.getAttribute('width')), h: Number(rect.getAttribute('height')) } })
  let current = [0, 0]
  for (const command of edge.getAttribute('d').match(/[MLC][^MLC]*/g)) {
    const points = command.slice(1).match(/-?\d+(?:\.\d+)?/g).map(Number), start = current
    if (command[0] === 'M') { current = points; continue }
    const end = points.slice(-2)
    for (let step = 0; step <= 100; step++) {
      const t = step / 100, u = 1 - t
      const point = command[0] === 'L' ? [start[0] * u + end[0] * t, start[1] * u + end[1] * t] : [u ** 3 * start[0] + 3 * u ** 2 * t * points[0] + 3 * u * t ** 2 * points[2] + t ** 3 * end[0], u ** 3 * start[1] + 3 * u ** 2 * t * points[1] + 3 * u * t ** 2 * points[3] + t ** 3 * end[1]]
      assert(!boxes.some(box => point[0] > box.x && point[0] < box.x + box.w && point[1] > box.y && point[1] < box.y + box.h), 'A long input/file catalogue relation must not run through unrelated action cards')
    }
    current = end
  }
  h.dispose(); console.log('PASS: catalogue-only long relations stay visible and bypass unrelated action-card interiors')
}
async function insertPlanLabelsRemainExplicit() {
  const base = fixture(); base.plan.actions.find(action => action.id === 'SCT-BILLS').action = 'insert'
  const h = await harness(base)
  try {
    await h.change('[data-graph-group]', 'billing'); await h.click('[data-graph-input="billNos"]')
    assert.match(h.element.querySelector('[data-graph-action="SCT-BILLS"]').textContent, /Insert at source anchor/)
    assert.match(h.element.querySelector('[data-graph-inspector]').textContent, /Insert at source anchor/)
    h.props.locale = 'zh-Hant'; await settle(); assert.match(h.element.querySelector('[data-graph-action="SCT-BILLS"]').textContent, /在原件錨點插入/)
  } finally { h.dispose() }
}
;(async () => { await insertPlanLabelsRemainExplicit(); await catalogueOnlyLongRelationsBypassTheActionColumn(); await manyToManyRelationshipsUseDistinctStablePortsAndLanes(); await compactCardsHaveReadableWideRoutingGutters(); await explicitSearchDefinesCompactDestinationGeometry(); await conditionalCollectionFieldUsesEachRowsOwnPrerequisites(); await visibleParentChildStructureDistinguishesVariablesFromConditionsAndColumns(); await selectionDimsExistingPoolWithoutRemovingOrMovingSiblings(); await actualPlannedChainAndNavigation(); await exactConditionsStructureAndPendingPlan(); await filtersNeighborhoodAndInputFreeActions(); await keyboardLifecycleProjectAndDisabledGuards(); await localizedPlanTreatmentsAndInvalidStructuredValues(); await inactiveRecordedAdoptionRemainsSeparate(); await selectedActionPrecedesLongSourceEvidence() })().catch(error => { console.error(error); process.exitCode = 1 })
