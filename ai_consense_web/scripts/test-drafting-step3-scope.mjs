import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'vue/compiler-sfc'
const current = await readFile(new URL('../src/views/DraftingView.vue', import.meta.url), 'utf8')
function stepTemplate(source, step) {
  const ast = parse(source.replace(/\r\n/g, '\n')).descriptor.template.ast
  const prefix = `projectId && step === '${step}'`, matches = []
  function find(nodes) { for (const node of nodes) { if (node.type === 1 && node.props.some(prop => prop.name === 'if' && (prop.exp?.content === prefix || prop.exp?.content.startsWith(prefix + ' && ')))) matches.push(node.loc.source); if (node.children) find(node.children) } }
  find(ast.children)
  assert.ok(matches.length, `The ${step} step remains available`)
  return matches.join('\n')
}
assert.match(stepTemplate(current, 'preview'), /DraftingDocumentWorkspace/, 'Preview uses the verified document workspace')
// The 2026-10-22 workflow starts automatic identification after file upload and retains manual catalogue entry.
const inputs=stepTemplate(current,'inputs')
assert.match(inputs,/upload\(\$event, 'templates'\)/,'Template upload stays available')
assert.match(inputs,/upload\(\$event, 'inputs'\)/,'Correspondence upload stays available')
assert.match(inputs,/:disabled="!!busy \|\| automationActive \|\| !catalog.groups.length" @click="move\('variables'\)"/,'Manual entry needs a catalogue and waits for active identification without requiring extraction success')
const automaticEntry = inputs.match(/<button\b[^>]*data-start-automation[\s\S]*?<\/button>/)?.[0]
assert.ok(automaticEntry, 'File identification has an explicit automatic variable and graph entry')
assert.match(automaticEntry, /@click="runAutomation\(\)"/, 'The explicit entry starts the existing automatic workflow')
for (const guard of ['busy', 'automationActive', 'automationChecking', 'automationError', 'reviewDirty', 'candidateDirty', 'dirtyInputs', 'documentDirty']) assert.match(automaticEntry, new RegExp(`\\b${guard}\\b`), `Automatic identification retains the ${guard} guard`)
assert.match(inputs, /@click="move\('review'\)"/, 'An existing identification task has a direct review entry')
const variables=stepTemplate(current,'variables')
assert.match(variables,/DraftingInputField/,'Manual fields remain editable in the variable view')
assert.match(variables,/@click="saveGroup\(\)"/,'Manual values have an explicit save action')
console.log('PASS: file upload, guarded automatic identification, current graph review, manual catalogue entry, explicit save and verified preview paths remain available')
