const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { locateTemplateTargets } = loadTypeScript(path.join(__dirname, '../src/drafting/template-navigation.ts'))

// Saved read-only acceptance responses supplement synthetic regressions; never request live data here.
const evidence = process.env.DRAFTING_ACTUAL_SOURCE_EVIDENCE
if (!evidence) {
  console.log('SKIP: actual uploaded-template regression requires DRAFTING_ACTUAL_SOURCE_EVIDENCE pointing to the saved source acceptance run')
} else {
  const load = file => { const response = JSON.parse(fs.readFileSync(path.join(evidence, file), 'utf8')); return response.data ?? response }
  const plan = load('http/044-GET-api_drafting_DRAFT-A-ACCEPTANCE-20261005_plan.body')
  const catalog = load('http/045-GET-api_drafting_DRAFT-A-ACCEPTANCE-20261005_catalog.body')
  const fields = [...catalog.groups.flatMap(group => group.fields), ...(catalog.systemFields ?? [])]
  const readings = {}, locations = {}
  for (const file of ['NTT', 'SCT', 'SCC']) {
    const reading = readings[file] = load(`source/DRAFT-A-ACCEPTANCE-20261005/${file}-reading.json`)
    assert.equal(reading.catalogueSourceVerified, true, `${file}: saved source edition must be verified`)
    const result = locations[file] = locateTemplateTargets(reading, fields, plan.actions)
    const missing = result.filter(item => item.quality === 'missing')
    const approximate = result.filter(item => item.quality === 'approximate')
    assert.equal(missing.length, 0, `${file}: unsupported actual-source targets: ${missing.map(item => item.actionId ?? item.id).join(', ')}`)
    assert.equal(approximate.length, 0, `${file}: original verified source anchors must be exact`)
    assert.ok(plan.actions.filter(action => action.document === file).every(action => result.some(item => item.actionId === action.id)), `${file}: all actions are represented`)
    console.log(`PASS: ${file} actual upload (${reading.paragraphs.length} paragraphs): ${result.length} exact locations, ${missing.length} missing, ${approximate.length} approximate`)
  }
  const ordinals = (file, id) => Array.from(locations[file].filter(item => item.actionId === id), item => item.ordinal)
  assert.deepEqual(ordinals('NTT', 'NTT-2-L10PRO'), [100, 102, 104, 106, 120])
  assert.ok(ordinals('NTT', 'NTT-17-RAILWAY').includes(613))
  assert.ok(ordinals('NTT', 'NTT-17-RAILWAY').includes(619))
  assert.equal(ordinals('NTT', 'NTT-17-RAILWAY').includes(612), false, 'An omitted number-only paragraph must not disqualify the supported quote pieces')
  assert.deepEqual(ordinals('SCC', 'scc-safety-pricing-SCC20.302'), [1437, 1548])
  const railway = plan.actions.find(action => action.id === 'NTT-17-RAILWAY')
  const incompatible = { ...railway, sourceText: 'This current action quote has no corresponding paragraph in the uploaded template.' }
  assert.equal(locateTemplateTargets(readings.NTT, [], [incompatible]).some(item => item.quality === 'exact'), false, 'An incompatible current quote cannot recover exact anchors from old ordinals')
  const replaced = { ...readings.NTT, catalogueSourceVerified: false }
  assert.equal(locateTemplateTargets(replaced, [], [railway]).some(item => item.quality === 'exact'), false, 'An unverified edition cannot reuse the original native ordinals for a multi-paragraph quote')
  console.log('PASS: actual multi-paragraph L10Pro, railway and safety targets retain supported native identities while incompatible quotes and replacement editions cannot use ordinal guesses')
}
