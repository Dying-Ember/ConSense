import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { parse } from 'vue/compiler-sfc'
const root = fileURLToPath(new URL('../', import.meta.url))
const current = await readFile(new URL('../src/views/DraftingView.vue', import.meta.url), 'utf8')
// Publication fixture is the exact historical aaa0e928 scope baseline, not a new business rule.
const baseline = await readFile(new URL('../tests/fixtures/drafting-step12-baseline.vue', import.meta.url), 'utf8')
function stepTemplate(source, step) {
  const ast = parse(source.replace(/\r\n/g, '\n')).descriptor.template.ast
  function find(nodes) { for (const node of nodes) { if (node.type === 1 && node.props.some(prop => prop.name === 'if' && prop.exp?.content === `projectId && step === '${step}'`)) return node.loc.source; const hit = node.children && find(node.children); if (hit) return hit } }
  return find(ast.children)
}
assert.match(stepTemplate(current, 'preview'), /DraftingDocumentWorkspace/, 'prototype A is integrated into the existing third step')
for (const step of ['inputs', 'variables']) assert.equal(stepTemplate(current, step), stepTemplate(baseline, step), `step ${step} retains its complete baseline template and events`)
console.log('PASS: step 3 uses document review; upload/parse and input confirmation templates remain identical')
