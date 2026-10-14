const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadVue } = require('./helpers/load-vue.cjs')
const { JSDOM } = require('jsdom')
const sanitize = require('dompurify')(new JSDOM('').window)
const vue = require('vue')
const { renderer, text, find, settle, deferred } = require('./helpers/render-controls.cjs')

function load(render) {
  return loadVue(path.join(__dirname, '../src/advice/answerFlowchart.ts'), { boundaries: {
    mermaid: { __esModule: true, default: { initialize() {}, render } },
    dompurify: { __esModule: true, default: sanitize }
  } })
}

test('untrusted SVG loses scripts, external resources, inline CSS and active HTML', async () => {
  const helper = load(async () => ({ svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><style>@import "https://example.com/"</style><text style="fill:url(https://example.com/x)">Safe [1]</text><script>run()</script><image href="https://example.com/x"/><a href="javascript:run()"><text>link</text></a><foreignObject><iframe src="https://example.com/"></iframe></foreignObject></svg>' }))
  const svg = await helper.renderAnswerFlowchart('flowchart TD\nA[Start]-->B[Finish]')
  assert.match(svg, /<svg/); assert.match(svg, /Safe \[1\]/)
  assert.doesNotMatch(svg, /script|style=|<style|href|foreignObject|iframe|<image|<a\b|https:/)
})

test('directives cannot override strict rendering and malformed input does not run the renderer', async () => {
  let count = 0
  const helper = load(async () => { count++; return { svg: '<svg></svg>' } })
  for (const source of ['%%{init: {securityLevel: "loose"}}%%\nflowchart TD\nA-->B', 'flowchart TD\n%%{init: {securityLevel: "loose"}}%%\nA-->B', '---\nconfig: {}\n---\nflowchart TD\nA-->B', 'sequenceDiagram\nA->>B: Test', 'flowchart TD\nA@{img: "https://example.com/x"}', 'flowchart TD\nA-->B\nclassDef foo fill:url(https://example.com/x)', 'flowchart TD\nA-->B;style A fill:url(https://example.com/x)', 'flowchart TD\nA-->B\nclick A "https://example.com/x"']) {
    await assert.rejects(helper.renderAnswerFlowchart(source), /UNSUPPORTED_FLOWCHART/)
  }
  assert.equal(count, 0)
})

test('oversized source rejects before Mermaid can return its successful error SVG', async () => {
  let calls = 0
  const helper = load(async () => { calls++; return { svg: '<svg><text>Maximum text size exceeded</text></svg>' } })
  const source = 'flowchart TD\nA["' + 'x'.repeat(100001) + '"]-->B'
  await assert.rejects(helper.renderAnswerFlowchart(source), /FLOWCHART_TOO_LARGE/)
  assert.equal(calls, 0)
})

test('a failed render does not poison later diagrams or allow concurrent Mermaid parser use', async () => {
  let active = 0, calls = 0
  const helper = load(async () => {
    assert.equal(active++, 0)
    const thisCall = ++calls
    try { await new Promise(resolve => setImmediate(resolve)); if (thisCall === 1) throw new Error('PARSE_ERROR'); return { svg: '<svg><text>Next</text></svg>' } }
    finally { active-- }
  })
  const outcomes = await Promise.allSettled([helper.renderAnswerFlowchart('flowchart TD\nBAD'), helper.renderAnswerFlowchart('flowchart TD\nA-->B')])
  assert.equal(outcomes[0].status, 'rejected'); assert.equal(outcomes[1].status, 'fulfilled')
  assert.match(outcomes[1].value, /Next/); assert.equal(calls, 2)
})

test('split retains every literal part and does not execute ordinary fenced code or unclosed Mermaid', () => {
  const helper = load(async () => ({ svg: '<svg></svg>' }))
  const answer = 'Before <img onerror=run()>\r\n```mermaid\r\nflowchart TD\r\nA-->B\r\n```\r\nAfter [1]\n```js\nalert(1)\n```'
  const parts = helper.answerParts(answer)
  assert.equal(parts.length, 3); assert.equal(parts[1].kind, 'flowchart')
  assert.equal(parts[0].source, 'Before <img onerror=run()>\r\n')
  assert.equal(parts[2].source, 'After [1]\n```js\nalert(1)\n```')
  assert.equal(helper.answerParts('```mermaid\nflowchart TD\nA-->B')[0].kind, 'text')
})

async function component(render) {
  const content = vue.ref('Before [1]\n```mermaid\nflowchart TD\nA-->B\n```\nAfter [1]')
  const loaded = loadVue(path.join(__dirname, '../src/components/AdviceAnswer.vue'), { boundaries: {
    'vue-i18n': require('vue-i18n'), mermaid: { __esModule: true, default: { initialize() {}, render } },
    dompurify: { __esModule: true, default: sanitize }
  }, globals: { document: { getElementById: () => null } } })
  const h = renderer(), app = h.createApp({ render: () => vue.h(loaded.default, { content: content.value }) })
  app.use(require('vue-i18n').createI18n({ legacy: false, locale: 'en', messages: { en: {} } }))
  app.mount(h.root)
  for (let i = 0; i < 8; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() }
  return { ...h, content, dispose: () => app.unmount() }
}

test('failed diagram displays an explicit message and the entire literal source alongside prose', async () => {
  const h = await component(async () => { throw new Error('PARSE_ERROR') })
  try {
    assert.match(text(h.root), /could not be displayed/)
    assert.equal(find(h.root, node => node.tag === 'pre').text, 'flowchart TD\nA-->B\n')
    assert.equal(find(h.root, node => node.tag === 'details').props.open, true)
    assert.match(text(h.root), /Before \[1\]/); assert.match(text(h.root), /After \[1\]/)
  } finally { h.dispose() }
})

test('late diagram result cannot overwrite a new plain answer or create page HTML from model text', async () => {
  const pending = deferred()
  const h = await component(() => pending.promise)
  try {
    h.content.value = '<img src=x onerror=run()> new answer [2]'
    await settle()
    pending.resolve({ svg: '<svg><text>OLD ANSWER</text></svg>' })
    for (let i = 0; i < 8; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() }
    assert.match(text(h.root), /<img src=x onerror=run\(\)> new answer \[2\]/)
    assert.doesNotMatch(text(h.root), /OLD ANSWER/)
    assert.equal(find(h.root, node => node.tag === 'img' || node.props?.innerHTML), undefined)
  } finally { h.dispose() }
})

test('oversized diagram retains every source character with a failure message and open source disclosure', async () => {
  let calls = 0
  const h = await component(async () => { calls++; return { svg: '<svg><text>Small graph</text></svg>' } })
  try {
    const source = 'flowchart TD\nA["' + 'x'.repeat(100001) + '"]-->B\n'
    h.content.value = '```mermaid\n' + source + '```\n'
    for (let i = 0; i < 8; i++) { await new Promise(resolve => setImmediate(resolve)); await settle() }
    assert.match(text(h.root), /could not be displayed/)
    assert.equal(find(h.root, node => node.tag === 'pre').text, source)
    assert.equal(find(h.root, node => node.tag === 'details').props.open, true)
    assert.equal(calls, 1, 'Only the previous small graph called the renderer')
  } finally { h.dispose() }
})
