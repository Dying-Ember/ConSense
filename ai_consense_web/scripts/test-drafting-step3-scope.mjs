import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'vue/compiler-sfc'
const current = await readFile(new URL('../src/views/DraftingView.vue', import.meta.url), 'utf8')
function stepTemplate(source, step) {
  const ast = parse(source.replace(/\r\n/g, '\n')).descriptor.template.ast
  function find(nodes) { for (const node of nodes) { if (node.type === 1 && node.props.some(prop => prop.name === 'if' && prop.exp?.content === `projectId && step === '${step}'`)) return node.loc.source; const hit = node.children && find(node.children); if (hit) return hit } }
  return find(ast.children)
}
assert.match(stepTemplate(current, 'preview'), /DraftingDocumentWorkspace/, 'Preview uses the verified document workspace')
// The historical prototype text predates source review; retain the current usable input paths.
const inputs=stepTemplate(current,'inputs')
assert.match(inputs,/upload\(\$event, 'templates'\)/,'Template upload stays available')
assert.match(inputs,/upload\(\$event, 'inputs'\)/,'Correspondence upload stays available')
assert.match(inputs,/:disabled="!!busy \|\| !catalog.groups.length" @click="move\('variables'\)"/,'Manual entry needs a catalogue, not a successful extraction')
assert.match(inputs,/@click="move\('identify'\)"/,'Source review has a direct entry')
const variables=stepTemplate(current,'variables')
assert.match(variables,/DraftingInputField/,'Manual fields remain editable in the variable view')
assert.match(variables,/@click="saveGroup\(\)"/,'Manual values have an explicit save action')
console.log('PASS: source upload, source review, manual entry, save and verified preview paths remain available')
