const assert = require('node:assert/strict')
const path = require('node:path')
const { loadVue } = require('./helpers/load-vue.cjs')
const { reviewLocationLabel: loadedLabel } = loadVue(path.join(__dirname, '../src/drafting/review-location-label.ts'))
const reviewLocationLabel = (...args) => { const value = loadedLabel(...args); return value ? { ...value } : value }
const actions = [
  { id: 'envelope-site', document: 'SCT', clause: 'SCT5(3)(f)', inputKeys: ['siteFormationWorks'] },
  { id: 'site-submissions', document: 'SCT', clause: 'SCT6(1)(c)', inputKeys: ['siteFormationWorks'] },
  { id: 'foreign', document: 'NTT', clause: 'NTT9', inputKeys: ['siteFormationWorks'] }
]
const binding = { actionIds: actions.map(action => action.id), appliedActionIds: ['site-submissions'], operationIds: ['site-submissions-P17'] }
assert.deepEqual(reviewLocationLabel(binding, actions, 'SCT', 'siteFormationWorks', true),
  { clause: 'SCT6(1)(c)', actionId: 'site-submissions', ambiguous: false, authority: 'saved_operation' })
assert.deepEqual(reviewLocationLabel(binding, actions, 'SCT', 'siteFormationWorks', false),
  { clause: 'SCT5(3)(f) / SCT6(1)(c)', actionId: undefined, ambiguous: true, authority: 'registered_association' })
assert.deepEqual(reviewLocationLabel({ ...binding, operationIds: ['editorial-only'] }, actions, 'SCT', 'siteFormationWorks', true),
  { clause: 'SCT5(3)(f) / SCT6(1)(c)', actionId: undefined, ambiguous: true, authority: 'registered_association' })
assert.equal(reviewLocationLabel(binding, actions, 'SCC', 'siteFormationWorks', true), undefined)
assert.equal(reviewLocationLabel(binding, actions, 'SCT', 'anotherVariable', true), undefined)
assert.deepEqual(binding.operationIds, ['site-submissions-P17'])
console.log('PASS: 6 synthetic location authority, ambiguity, cross-file and immutable-navigation checks')
