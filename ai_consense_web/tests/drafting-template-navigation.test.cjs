const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { locateTemplateTargets } = loadTypeScript(path.join(__dirname, '../src/drafting/template-navigation.ts'))
const plain = value => JSON.parse(JSON.stringify(value))
const label = { en: 'Completion period', zhHans: '工期', zhHant: '工期' }
const reading = { fileKey: 'NTT', fileName: 'uploaded.docx', sourceHash: 'current', format: 'docx', catalogueSourceVerified: true, paragraphs: [
  { id: 'native-body-1', ordinal: 1, text: 'Invitation to tender' },
  { id: 'native-table-2', ordinal: 2, text: 'Complete the Works in [period] months.' },
  { id: 'native-body-3', ordinal: 3, text: 'Complete the Works in [period] months.' }
] }
const fields = [{ key: 'contractPeriodMonths', label, kind: 'number', affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2' }] }, { key: 'periodAtLeast39Months', label, kind: 'boolean', affects: [{ document: 'NTT', clause: 'Completion', paragraphs: '2' }] }]
const action = { id: 'NTT-completion', document: 'NTT', clause: 'Completion', paragraphs: '2', sourceText: 'Complete the Works in [period] months.', inputKeys: ['contractPeriodMonths', 'periodAtLeast39Months'], action: 'fill' }
const located = locateTemplateTargets(reading, fields, [action])
assert.deepEqual(plain(located.filter(item => item.actionId === action.id).map(item => ({ id: item.paragraphId, quality: item.quality, fields: item.fieldKeys }))), [{ id: 'native-table-2', quality: 'exact', fields: ['contractPeriodMonths', 'periodAtLeast39Months'] }])
console.log('PASS: verified native source scope locates repeated text in its table paragraph and preserves both linked inputs')

const replaced = { ...reading, catalogueSourceVerified: false, paragraphs: [{ id: 'replacement-1', ordinal: 1, text: 'New unrelated provision' }, { id: 'replacement-2', ordinal: 2, text: 'Other clause' }] }
assert.equal(locateTemplateTargets(replaced, fields, [action]).every(item => item.quality === 'missing' && item.paragraphId === null), true)
const currentText = { ...replaced, paragraphs: [...replaced.paragraphs, { id: 'replacement-3', ordinal: 3, text: 'Complete the Works in [period] months.' }] }
assert.equal(locateTemplateTargets(currentText, fields, [action]).find(item => item.actionId === action.id).paragraphId, 'replacement-3')
assert.equal(locateTemplateTargets(currentText, fields, [action]).find(item => item.actionId === action.id).quality, 'exact')
const repeatedReplacement = { ...reading, catalogueSourceVerified: false }
assert.deepEqual(plain(locateTemplateTargets(repeatedReplacement, fields, [action]).filter(item => item.actionId === action.id).map(item => item.quality)), ['approximate', 'approximate'])
console.log('PASS: replacement editions never reuse historical ordinals, current unique source text can locate independently, and repeated matches remain explicitly approximate')

const weakField = { key: 'contractNo', kind: 'text', label: { en: 'Contract number' }, affects: [{ document: 'NTT', clause: 'Contract identity' }] }
const weakReading = { ...replaced, paragraphs: [{ id: 'placeholder', ordinal: 1, text: 'Contract number: ____' }] }
assert.deepEqual(plain(locateTemplateTargets(weakReading, [weakField], []).map(item => [item.paragraphId, item.quality])), [['placeholder', 'approximate']])
const incompatibleAction = { ...action, sourceText: 'Current action source has changed.' }
assert.equal(locateTemplateTargets(reading, [], [incompatibleAction])[0].quality, 'missing')
console.log('PASS: weak labels/placeholder navigation is approximate and a current quote mismatch cannot silently use a verified historical target range')

const slashAction = { ...action, sourceText: undefined, paragraphs: 'P2/P3' }
assert.deepEqual(plain(locateTemplateTargets(reading, [], [slashAction]).map(item => [item.paragraphId, item.quality])), [['native-table-2', 'exact'], ['native-body-3', 'exact']])
const slashRanges = { ...action, sourceText: undefined, paragraphs: 'P1–2/P3–4' }
assert.deepEqual(plain(locateTemplateTargets(reading, [], [slashRanges]).map(item => item.ordinal)), [1, 2, 3])
console.log('PASS: real catalogue slash-delimited native ranges navigate each referenced paragraph exactly')

const descriptiveScope = { ...reading, paragraphs: [
  { id: 'main-100', ordinal: 100, text: 'Electronic tender files.' },
  { id: 'guidance-108', ordinal: 108, text: 'Select the applicable dissemination route.' },
  { id: 'main-120', ordinal: 120, text: 'Check the supplied DVD-ROM.' },
  { id: 'guidance-123', ordinal: 123, text: 'For electronic dissemination with L10Pro.' },
  { id: 'outside-121', ordinal: 121, text: 'Unrelated clause.' }
] }
const descriptiveAction = { ...action, sourceText: undefined, paragraphs: 'P97–P106 and P117–P120; guidance P107–P109 / P122–P124' }
assert.deepEqual(plain(locateTemplateTargets(descriptiveScope, [], [descriptiveAction]).map(item => [item.ordinal, item.quality])), [[100, 'exact'], [108, 'exact'], [120, 'exact'], [123, 'exact']])
console.log('PASS: verified catalogue ranges preserve and-separated body ranges and labelled guidance ranges without selecting outside paragraphs')

const quotedScope = { ...reading, paragraphs: [
  { id: 'number-612', ordinal: 612, text: '17.' },
  { id: 'heading-613', ordinal: 613, text: 'Works within the Railway Protection Area' },
  { id: 'guidance-614', ordinal: 614, text: 'Select the applicable route.' },
  { id: 'body-619', ordinal: 619, text: 'The Contractor shall return an indemnity form to the MTR Corporation Limited.' },
  { id: 'other-700', ordinal: 700, text: 'Works within the Railway Protection Area' }
] }
const quotedAction = { ...action, paragraphs: 'P612–P613 / P619; guidance P614', sourceText: 'Works within the Railway Protection Area\nThe Contractor shall return an indemnity form to the MTR Corporation Limited.' }
assert.deepEqual(plain(locateTemplateTargets(quotedScope, [], [quotedAction]).map(item => [item.paragraphId, item.quality])), [['heading-613', 'exact'], ['body-619', 'exact']])
console.log('PASS: a verified multi-paragraph quote retains its exact heading and body when number-only and guidance paragraphs are omitted from that quote')

const foreignScope = { ...reading, fileKey: 'SCC', paragraphs: [
  { id: 'scc-1017', ordinal: 1017, text: 'Current SCC provision.' },
  { id: 'scc-627', ordinal: 627, text: 'Unrelated SCC provision at an SCT reference number.' },
  { id: 'scc-638', ordinal: 638, text: 'Unrelated SCC provision at a WWQS cue number.' }
] }
const foreignAction = { ...action, document: 'SCC', sourceText: undefined, paragraphs: 'P1017; SCT5 P627–637; WWQS cues638–642' }
assert.deepEqual(plain(locateTemplateTargets(foreignScope, [], [foreignAction]).map(item => [item.ordinal, item.quality])), [[1017, 'exact']])
console.log('PASS: foreign-document references and WWQS cue numbers cannot become exact locations in the current uploaded document')
