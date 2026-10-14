const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { renderAdviceMarkdown } = loadTypeScript(path.join(__dirname, '../src/advice/answerMarkdown.ts'), {
  dependencies: { '@/composables/useMarkdown': loadTypeScript(path.join(__dirname, '../src/composables/useMarkdown.ts')) }
})
test('table content, surrounding prose and only real source IDs remain visible and clickable', () => {
  const html = renderAdviceMarkdown('Before [1]\n\n| Start | Deadline |\n| --- | --- |\n| notice [1] | **earlier** [2] | extra |\n\nAfter [9] and `<unsafe [1]>`', ['[1]', '[2]'])
  const document = new JSDOM(html).window.document
  assert.equal(document.querySelectorAll('table').length, 1)
  assert.equal(document.querySelectorAll('tbody td').length, 3)
  assert.match(document.body.textContent, /Before \[1\]/); assert.match(document.body.textContent, /After \[9\]/)
  assert.match(document.body.textContent, /extra/)
  assert.deepEqual([...document.querySelectorAll('button')].map(node => node.dataset.adviceCitation), ['[1]', '[1]', '[2]'])
  assert.equal(document.querySelector('code button'), null)
})
test('untrusted HTML, remote links and literal code never become active links or resources', () => {
  const source = '<img src="https://example.test/a" onerror=run()>\n[remote](javascript:run())\n```html\n<script>run()</script> [1]\n```\nAfter [1]'
  const document = new JSDOM(renderAdviceMarkdown(source, ['[1]'])).window.document
  assert.equal(document.querySelector('img,script,a,iframe'), null)
  assert.equal(document.querySelectorAll('button').length, 1)
  assert.equal(document.querySelector('pre button'), null)
  assert.match(document.body.textContent, /<script>run\(\)<\/script> \[1\]/)
})
test('answer component emits only a citation ID explicitly belonging to its answer', async () => {
  const vue = require('vue'), { loadVue } = require('./helpers/load-vue.cjs'), { renderer, find, settle } = require('./helpers/render-controls.cjs')
  const emitted = []
  const loaded = loadVue(path.join(__dirname, '../src/components/AdviceAnswer.vue'), { boundaries: { 'vue-i18n': require('vue-i18n') } })
  const h = renderer(), app = h.createApp({ render: () => vue.h(loaded.default, { content: 'Known [1] unknown [2]', citationIds: ['[1]'], onCitation: id => emitted.push(id) }) })
  app.use(require('vue-i18n').createI18n({ legacy: false, locale: 'en', messages: { en: {} } })); app.mount(h.root); await settle()
  try {
    const answer = find(h.root, node => node.props?.class === 'advice-answer')
    answer.props.onClick({ target: { closest: () => ({ getAttribute: () => '[2]' }) } })
    answer.props.onClick({ target: { closest: () => ({ getAttribute: () => '[1]' }) } })
    assert.deepEqual(emitted, ['[1]'])
  } finally { app.unmount() }
})
