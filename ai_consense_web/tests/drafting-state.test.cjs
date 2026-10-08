/* Business state tests: cache, unknowns and adoption must survive real API refreshes. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const runtime = loadTypeScript(path.join(__dirname, '../src/drafting/state.ts'), { globals: { console }, dependencies: { './field-kinds': './field-kinds.ts' } })
const { decode, encode, applicability, validationIssue, mergeServerValues, dirtyPatch, documentCapabilities, replaceTargetOverride, restoreDraftView, reversedSiteDates, actionForUnresolved, adoptionPatch } = runtime
const plain = value => JSON.parse(JSON.stringify(value))
const field = (key, kind = 'text', other = {}) => ({ key, kind, label: { en: key, zhHans: key, zhHant: key }, ...other })
const condition = (key, value, operator = 'eq') => ({ all: [{ field: key, operator, value }] })
let checks = 0
function check(name, task) { task(); checks++; console.log(`PASS: ${name}`) }

check('stale snapshots stay readable without offering server-rejected edit, PDF or export actions', () => {
  assert.deepEqual(plain(documentCapabilities({ generated: true, stale: true }, false)), { readable: true, editable: false, previewable: false, exportable: false })
  assert.deepEqual(plain(documentCapabilities({ generated: true, stale: true }, true)), { readable: true, editable: false, previewable: false, exportable: false })
  assert.deepEqual(plain(documentCapabilities({ generated: true, stale: false }, true)), { readable: true, editable: true, previewable: true, exportable: false })
  assert.deepEqual(plain(documentCapabilities({ generated: true }, false)), { readable: true, editable: true, previewable: true, exportable: true })
  assert.deepEqual(plain(documentCapabilities(undefined, false)), { readable: false, editable: false, previewable: false, exportable: false })
})
check('language remounts preserve project working step and document without moving a new project into preview', () => {
  assert.deepEqual(plain(restoreDraftView({ step: 'variables', activeFile: 'SCT' }, [])), { step: 'variables', activeFile: 'SCT' })
  assert.deepEqual(plain(restoreDraftView({ step: 'preview', activeFile: 'SCC' }, [{ fileKey: 'SCC', generated: true }])), { step: 'preview', activeFile: 'SCC' })
  assert.deepEqual(plain(restoreDraftView({ step: 'preview', activeFile: 'SCC' }, [])), { step: 'variables', activeFile: 'SCC' })
  assert.deepEqual(plain(restoreDraftView(undefined, [])), { step: 'inputs', activeFile: 'NTT' })
})
check('re-adopting one target retains every other target source binding and exact adopted text', () => {
  const first = { actionId: 'NTT-9', action: 'retain', sourceMapping: 'Old edition', sourceRevision: { document: 'NTT', sourceDocumentId: 10, sourceHash: 'hash-before' } }
  const second = { actionId: 'SCC-22', action: 'amend', value: 'Line 1.\nLine 2.', sourceMapping: 'Existing target mapping', sourceRevision: { document: 'SCC', sourceDocumentId: 20, sourceHash: 'hash-other' } }
  const old = [first, second]
  const replacement = { actionId: 'NTT-9', action: 'retain', sourceMapping: 'Current source checked' }
  const updated = replaceTargetOverride(old, 'NTT-9', replacement)
  assert.deepEqual(plain(updated.find(item => item.actionId === 'SCC-22')), second)
  assert.equal(Object.hasOwn(updated.find(item => item.actionId === 'NTT-9'), 'sourceRevision'), false)
  assert.deepEqual(plain(old), [first, second])
  assert.deepEqual(plain(replaceTargetOverride(updated, 'NTT-9')), [second])
})

check('NSC hides BSSSC follow-ups, while an unknown arrangement stays editable', () => {
  assert.equal(applicability(condition('subcontractArrangement', 'BSSSC'), { subcontractArrangement: 'NSC' }), 'no')
  assert.equal(applicability(condition('subcontractArrangement', 'BSSSC'), { subcontractArrangement: null }), 'unknown')
  assert.equal(applicability(condition('subcontractArrangement', 'BSSSC'), { subcontractArrangement: 'BSSSC' }), 'yes')
})
check('known No decides an AND condition even when the second input is unknown', () => {
  const both = { all: [{ field: 'domesticBlocks', operator: 'eq', value: true }, { field: 'volumetricPrecastComponents', operator: 'eq', value: true }] }
  assert.equal(applicability(both, { domesticBlocks: 'false', volumetricPrecastComponents: null }), 'no')
  assert.equal(applicability(both, { domesticBlocks: 'true', volumetricPrecastComponents: null }), 'unknown')
  assert.equal(applicability(both, { domesticBlocks: true, volumetricPrecastComponents: 'true' }), 'yes')
})
check('component facts are not invalidated by unrelated optional blank columns', () => {
  const rows = [{ id: 'a', component: 'footings', design: 'true', execution: 'true', scope: null }]
  assert.equal(applicability(condition('designResponsibilities', 'footings', 'includesComponent'), { designResponsibilities: rows }), 'yes')
  assert.equal(applicability(condition('designResponsibilities', 'footings', 'includesComponent'), { designResponsibilities: [{ component: null }] }), 'unknown')
  assert.equal(applicability(condition('designResponsibilities', 'footings', 'includesComponent'), { designResponsibilities: [] }), 'no')
})
check('tree count uses complete unique serial identities, not arbitrary row length', () => {
  const c = condition('oldValuableTrees', 1, 'countGt')
  assert.equal(applicability(c, { oldValuableTrees: [{ serial: 'OVT-A', notes: null }, { serial: 'OVT-B', notes: null }] }), 'yes')
  assert.equal(applicability(c, { oldValuableTrees: [{ serial: 'OVT-A' }, { serial: 'ovt-a' }] }), 'unknown')
  assert.equal(applicability(c, { oldValuableTrees: [{ serial: 'OVT-A' }, { serial: '' }] }), 'unknown')
  assert.equal(applicability(c, { oldValuableTrees: [] }), 'no')
})
check('pricing-scheme unknown does not become the SOR or paper negative branch', () => {
  assert.equal(applicability(condition('pricingScheme', ['BQ', 'BQ_SOR'], 'in'), { pricingScheme: null }), 'unknown')
  assert.equal(applicability(condition('pricingScheme', ['BQ', 'BQ_SOR'], 'in'), { pricingScheme: 'BQ_SOR' }), 'yes')
  assert.equal(applicability(condition('pricingScheme', ['BQ', 'BQ_SOR'], 'in'), { pricingScheme: 'SOR' }), 'no')
})
check('explicit no-items remains distinguishable from an unanswered list', () => {
  const list = field('oldValuableTrees', 'list')
  assert.equal(decode(list, ''), null)
  assert.deepEqual(plain(decode(list, '[]')), [])
  assert.equal(encode(list, []), '[]')
  assert.equal(encode(list, null), '')
  assert.equal(validationIssue(list, []), null)
  assert.equal(validationIssue(list, null), 'missing')
})
check('zero is a valid photocopy rate; negative and invalid amounts remain review items', () => {
  const rate = field('photocopyRateUpToA3', 'number')
  assert.equal(encode(rate, 0), '0')
  assert.equal(validationIssue(rate, 0), null)
  assert.equal(validationIssue(rate, '-1'), 'negative')
  assert.equal(validationIssue(rate, 'not a number'), 'invalid')
})
check('invalid calendar dates and reversed site dates are identifiable without erasing them', () => {
  const date = field('siteInspectionStartDate', 'date')
  assert.equal(validationIssue(date, '2026-02-31'), 'invalid')
  assert.equal(validationIssue(date, '2026-10-05'), null)
  assert.equal(reversedSiteDates({ siteInspectionStartDate: '2026-10-15', siteInspectionEndDate: '2026-10-10' }), true)
  assert.equal(reversedSiteDates({ siteInspectionStartDate: '2026-10-10', siteInspectionEndDate: '2026-10-10' }), false)
})
check('formal bill text, user salutations and exact override wording are not translated', () => {
  const bills = field('billNos', 'list')
  const description = 'Not Preliminaries / external works — Schedule of Rates'
  const rows = [{ id: 'persisted-id', number: '1', description, type: 'SOR' }]
  assert.equal(decode(bills, encode(bills, rows))[0].description, description)
  assert.equal(encode(field('projectArchitectOtherTitle'), '其他经采用称谓'), '其他经采用称谓')
  assert.equal(encode(field('exactWording'), 'Line one.\nLine two — exact.'), 'Line one.\nLine two — exact.')
})
check('record validation ignores optional blanks and does not require users to type metadata IDs', () => {
  const bill = field('billNos', 'list', { columnFields: [field('id'), field('number'), field('description'), field('trade', 'text', { optional: true })] })
  assert.equal(validationIssue(bill, [{ number: '1', description: 'Preliminaries', trade: null }]), null)
  assert.equal(validationIssue(bill, [{ number: '1', description: '' }]), 'incomplete')
  assert.equal(validationIssue(bill, [{ number: '1', description: 'A' }, { number: '1', description: 'B' }]), 'duplicate')
})
check('BQ and SOR numbers have separate identities; same-type duplicates and unknown types need review', () => {
  const bills = field('billNos', 'list', { columnFields: [field('id'), field('number'), field('description'), field('type', 'select', { options: [{ value: 'BQ' }, { value: 'SOR' }] })] })
  const bq = { id: 'bq1', number: '1', description: 'Preliminaries', type: 'BQ' }
  const sor = { id: 'sor1', number: '1', description: 'Rates for drainage works', type: 'SOR' }
  assert.equal(validationIssue(bills, [bq, sor]), null)
  assert.equal(validationIssue(bills, [bq, { ...bq, id: 'bq1-again', description: 'Other BQ Bill' }]), 'duplicate')
  assert.equal(validationIssue(bills, [sor, { ...sor, id: 'sor1-again', description: 'Other SOR' }]), 'duplicate')
  assert.equal(validationIssue(bills, [{ ...bq, type: '' }]), 'incomplete')
})
check('Boolean catalog options accept HTML canonical strings without treating No as missing', () => {
  const bool = field('foundationIncluded', 'boolean', { options: [{ value: true }, { value: false }] })
  assert.equal(validationIssue(bool, 'false'), null)
  assert.equal(validationIssue(bool, 'true'), null)
  assert.equal(validationIssue(bool, 'unknown'), 'invalid')
})
check('refresh preserves unrelated local edits and loads fresh values only into clean fields', () => {
  const fields = [field('siteVisitRestrictions'), field('siteInspectionStartDate', 'date')]
  const local = { siteVisitRestrictions: 'User wording', siteInspectionStartDate: '2026-10-12' }
  const baseline = { siteVisitRestrictions: 'Prior wording', siteInspectionStartDate: '2026-10-12' }
  const result = mergeServerValues(fields, [{ key: 'siteVisitRestrictions', value: 'Remote wording', reviewRequired: true }, { key: 'siteInspectionStartDate', value: '2026-10-14' }], local, baseline)
  assert.deepEqual(plain(result.values), { siteVisitRestrictions: 'User wording', siteInspectionStartDate: '2026-10-14' })
  assert.equal(result.baseline.siteVisitRestrictions, 'Remote wording')
  assert.deepEqual(local, { siteVisitRestrictions: 'User wording', siteInspectionStartDate: '2026-10-12' })
})
check('a proposed plan includes only user edits, never untouched unadopted model suggestions', () => {
  const fields = [field('foundationIncluded', 'boolean'), field('wtoGpaApplies', 'boolean'), field('oldValuableTrees', 'list')]
  const baseline = { foundationIncluded: 'true', wtoGpaApplies: '', oldValuableTrees: '' }
  const patch = dirtyPatch(fields, { foundationIncluded: 'true', wtoGpaApplies: 'false', oldValuableTrees: [] }, baseline)
  assert.deepEqual(plain(patch), { wtoGpaApplies: 'false', oldValuableTrees: '[]' })
  assert.equal(Object.hasOwn(patch, 'foundationIncluded'), false)
})
check('source application failures lead to the exact original action, not an unrelated question', () => {
  const action = { id: 'SCC-TREE', document: 'SCC', clause: '22.320', action: 'amend' }
  assert.equal(actionForUnresolved({ id: 'application-SCC-TREE', kind: 'SourceTarget' }, [action]), action)
  assert.equal(actionForUnresolved({ id: 'other', actionId: 'SCC-TREE', kind: 'SourceTarget' }, [action]), action)
  assert.equal(actionForUnresolved({ id: 'missing-source', kind: 'SourceTarget' }, [action]), undefined)
})
check('adopting an untouched suggestion preserves its provenance instead of rewriting it as manual', () => {
  const displayed = { source: 'Source quote.', candidates: [{ value: 'true', sourceDocumentId: 7, sourceHash: 'hash', sourceQuote: 'Source quote.' }] }
  assert.deepEqual(plain(adoptionPatch('true', 'true', false, undefined, displayed)), { confirmed: true, suggestionSnapshot: { value: 'true', source: 'Source quote.', candidates: displayed.candidates, reviewRequired: false } })
  assert.deepEqual(plain(adoptionPatch('false', 'true', false)), { value: 'false', reviewed: true })
  assert.deepEqual(plain(adoptionPatch('false', 'true', true, 0, displayed)), { candidateIndex: 0, candidateSnapshot: displayed.candidates[0] })
  assert.throws(() => adoptionPatch('true', 'true', false), /Refresh/)
  assert.throws(() => adoptionPatch('true', 'true', false, 0, { candidates: [] }), /Refresh/)
})
check('reviewing an existing value adopts it for this draft and does not alter unrelated fields', () => {
  assert.deepEqual(plain(adoptionPatch('2026-10-12', '2026-10-12', true, undefined, { source: null, candidates: [] })), { reviewed: true, confirmed: true, suggestionSnapshot: { value: '2026-10-12', source: null, candidates: [], reviewRequired: true } })
})
check('adoption binds a copy of the shown source identity before later local metadata changes', () => {
  const shown = { source: 'Original source.', candidates: [{ value: 'A', sourceDocumentId: 7, sourceHash: 'hash-a', sourceQuote: 'Original source.' }] }
  const candidate = adoptionPatch('A', 'A', false, 0, shown)
  const suggestion = adoptionPatch('A', 'A', false, undefined, shown)
  shown.candidates[0].value = 'B'; shown.candidates[0].sourceHash = 'hash-b'; shown.source = 'Replacement source.'
  assert.equal(candidate.candidateSnapshot.value, 'A')
  assert.equal(candidate.candidateSnapshot.sourceHash, 'hash-a')
  assert.equal(suggestion.suggestionSnapshot.value, 'A')
  assert.equal(suggestion.suggestionSnapshot.source, 'Original source.')
  assert.equal(suggestion.suggestionSnapshot.candidates[0].sourceHash, 'hash-a')
})
check('cached inactive entries survive parent switching and remain independently dirty', () => {
  const fields = [field('subcontractArrangement'), field('subcontractors', 'multiselect')]
  const local = { subcontractArrangement: 'NSC', subcontractors: ['Electrical'] }
  const baseline = { subcontractArrangement: 'BSSSC', subcontractors: '["Electrical"]' }
  assert.deepEqual(plain(dirtyPatch(fields, local, baseline)), { subcontractArrangement: 'NSC' })
  assert.deepEqual(local.subcontractors, ['Electrical'])
})
console.log(`PASS: ${checks} drafting business-state scenarios`)
