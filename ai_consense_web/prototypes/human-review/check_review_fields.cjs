'use strict';
const assert = require('node:assert/strict');
const moduleUnderTest = { exports: {} };
new Function('module', require('node:fs').readFileSync(require('node:path').join(__dirname, 'review-fields.js'), 'utf8'))(moduleUnderTest);
const { render, read } = moduleUnderTest.exports;
const group = (id, docs = ['scc-word'], extra = {}) => ({ id, nameZh: id, nameEn: id, type: { kind: 'record' }, docIds: docs, ...extra });
const item = { id: 'child', nameZh: '子项', nameEn: 'Child', type: { kind: 'choice' }, docIds: ['scc-word'], options: [{ value: 'old-a', labelZh: '甲', labelEn: 'A' }, { value: 'old-b', labelZh: '乙', labelEn: 'B' }] };
const variables = [group('parent'), item, group('descendant', ['scc-word'], { parentId: 'child' }), group('excluded', ['scc-word'], { status: 'excluded' }), group('pdf', ['scc-pdf']), group('mixed', ['scc-word', 'scc-pdf']), group('scalar', ['scc-word'], { type: { kind: 'text' } }), group('loop', ['scc-word'], { parentId: 'loop' }), group('list', ['scc-word'], { type: { kind: 'list' } })];
let calls = 0;
const context = { variables, docId: 'scc-word', item, compatible: (child, parent) => { calls++; return !new Set([...child.docIds, ...parent.docIds]).has('scc-pdf'); } };
const form = fields => new Map(Object.entries(fields));
const choice = { type: 'choice', optionsZh: '甲\n乙', optionsEn: 'A\nB' };

const html = render(context);
assert.match(html, /name="parentId"/);
assert.match(html, /value="parent"/);
assert.match(html, /value="list"/);
for (const id of ['child', 'descendant', 'excluded', 'pdf', 'mixed', 'scalar', 'loop']) assert.ok(!html.includes(`value="${id}"`), id);
assert.match(html, /name="optionsZh"/);
assert.match(html, /name="optionsEn"/);
assert.equal(render({ variables: [], docId: 'scc-word', item: null }), '');
assert.doesNotMatch(render({ ...context, values: { type: 'text' } }), /textarea/);
assert.match(render({ ...context, values: { parentId: 'absent' } }), /Unavailable parent/);
assert.match(render({ variables: [group('<script>')], docId: 'scc-word', item: null }), /&lt;script&gt;/);
const escaped = render({ ...context, values: { optionsZh: '</textarea><script>', optionsEn: 'A&B' } });
assert.ok(!escaped.includes('</textarea><script>'));
assert.match(escaped, /&lt;\/textarea&gt;&lt;script&gt;/);
assert.match(escaped, /A&amp;B/);

assert.deepEqual(read(form(choice), context), { parentId: null, options: item.options });
assert.deepEqual(read(form({ type: 'text', parentId: 'parent' }), context), { parentId: 'parent', options: [] });
assert.ok(calls > 0);
for (const parentId of ['child', 'descendant', 'excluded', 'pdf', 'mixed', 'scalar', 'loop', 'missing']) assert.throws(() => read(form({ type: 'text', parentId }), context), /父项/);
assert.throws(() => read(form({ type: 'text', parentId: 'parent' }), { ...context, compatible: () => false }), /不兼容/);
assert.throws(() => read(form({ type: 'text', parentId: 'parent' }), { ...context, compatible: undefined }), /不兼容/);
assert.equal(read(form({ type: 'text', parentId: 'parent' }), { ...context, item: null }).parentId, 'parent');
const sharedParent = group('shared-parent', ['ntt']);
const sharedChild = { ...item, parentId: sharedParent.id, docIds: ['ntt', 'sct'] };
const sharedContext = { variables: [sharedParent, sharedChild], docId: 'sct', item: sharedChild, compatible: () => true };
assert.match(render(sharedContext), /value="shared-parent" selected/);
assert.equal(read(form({ type: 'text', parentId: sharedParent.id }), sharedContext).parentId, sharedParent.id);
assert.throws(() => read(form({ type: 'text', parentId: sharedParent.id }), { ...sharedContext, item: null }), /父项/);
const neutralItem = group('neutral-item', ['ntt']);
const neutralDescendant = group('neutral-descendant', ['ntt'], { parentId: neutralItem.id });
const wordGrandchild = group('word-grandchild', ['scc-word'], { parentId: neutralDescendant.id });
const pdfAncestor = group('pdf-ancestor', ['scc-pdf']);
const neutralParent = group('neutral-parent', ['ntt'], { parentId: pdfAncestor.id });
const familyContext = { variables: [neutralItem, neutralDescendant, wordGrandchild, pdfAncestor, neutralParent], docId: 'ntt', item: neutralItem, compatible: () => true };
assert.doesNotMatch(render(familyContext), /value="neutral-parent"/);
assert.throws(() => read(form({ type: 'record', parentId: neutralParent.id }), familyContext), /父项/);
const sameEditionFamily = { ...familyContext, variables: familyContext.variables.map(value => value.id === pdfAncestor.id ? { ...value, docIds: ['scc-word'] } : value) };
assert.equal(read(form({ type: 'record', parentId: neutralParent.id }), sameEditionFamily).parentId, neutralParent.id);
// Moving away from a PDF ancestor does not carry that former ancestor with the item.
assert.equal(read(form({ type: 'record', parentId: neutralItem.id }), { ...familyContext, item: neutralParent }).parentId, neutralItem.id);
assert.throws(() => read(form({ type: 'record', parentId: neutralParent.id }), { ...familyContext, item: null, variables: [...familyContext.variables, group('word-ancestor', ['scc-word'], { parentId: null })].map(value => value.id === pdfAncestor.id ? { ...value, parentId: 'word-ancestor' } : value) }), /父项/);
for (const invalid of [
  { optionsZh: '甲', optionsEn: 'A' },
  { optionsZh: '甲\n乙', optionsEn: 'A' },
  { optionsZh: '甲\n', optionsEn: 'A\nB' },
  { optionsZh: '甲\n甲', optionsEn: 'A\nB' },
  { optionsZh: '甲\n乙', optionsEn: 'A\nA' }
]) assert.throws(() => read(form({ ...choice, ...invalid }), context), /选项/);

const renamed = read(form({ ...choice, optionsZh: '改名\n乙' }), context).options;
assert.notEqual(renamed[0].value, 'old-a');
assert.equal(renamed[1].value, 'old-b');
assert.deepEqual(read(form({ ...choice, optionsZh: '改名\n乙' }), context).options, renamed);
assert.deepEqual(read(form({ type: 'multiselect', optionsZh: '乙\r\n甲', optionsEn: 'B\r\nA' }), context).options.map(option => option.value), ['old-b', 'old-a']);
const reserved = { ...context, item: { ...item, options: [{ value: renamed[0].value, labelZh: '其它', labelEn: 'Other' }] } };
const collision = read(form({ ...choice, optionsZh: '改名\n乙' }), reserved).options;
assert.notEqual(collision[0].value, renamed[0].value);
assert.equal(new Set(collision.map(option => option.value)).size, 2);
assert.deepEqual(read(form(choice), { ...context, item: { ...item, options: item.options.map(option => ({ ...option, labelZh: ` ${option.labelZh}` })) } }).options.map(option => option.value === 'old-a' || option.value === 'old-b'), [false, false]);
console.log('ReviewFields synthetic checks passed: parent scope/cycles/compatibility, escaped rendering, bilingual option validation, exact-label value preservation and stable collision-safe values.');
