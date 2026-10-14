const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { JSDOM } = require('jsdom')
const { loadVue } = require('./helpers/load-vue.cjs')

test('installed Mermaid really parses/renders a cited branching graph; invalid syntax fails', async () => {
  const dom = new JSDOM('<html><body></body></html>', { pretendToBeVisual: true })
  global.window = dom.window; global.document = dom.window.document
  global.CSSStyleSheet = dom.window.CSSStyleSheet
  // jsdom has no browser SVG layout. These metrics enable the actual library render,
  // but do not constitute browser visual acceptance or contractual-path verification.
  dom.window.SVGElement.prototype.getBBox = function () { return { x: 0, y: 0, width: (this.textContent?.length || 10) * 7, height: 24 } }
  dom.window.SVGElement.prototype.getComputedTextLength = function () { return (this.textContent?.length || 10) * 7 }
  const { default: mermaid } = await import('mermaid')
  const purify = require('dompurify')(dom.window)
  const { renderAnswerFlowchart } = loadVue(path.join(__dirname, '../src/advice/answerFlowchart.ts'), { globals: { document: dom.window.document },
    boundaries: { mermaid: { __esModule: true, default: mermaid }, dompurify: { __esModule: true, default: purify } } })
  const source = 'flowchart TD\nA["Receive notice [1]"] --> B{"Condition established? [2]"}\nB -->|Yes| C["Follow applicable procedure [2]"]\nB -->|Unknown| D["Confirm missing facts [1]"]'
  const svg = await renderAnswerFlowchart(source)
  const document = new JSDOM(svg, { contentType: 'image/svg+xml' }).window.document
  assert.ok(document.querySelector('svg')); assert.match(document.documentElement.textContent, /Receive notice/)
  assert.match(document.documentElement.textContent, /Confirm missing facts/)
  assert.ok(document.querySelectorAll('.flowchart-link').length >= 3)
  assert.equal(document.querySelector('script,a,image,foreignObject'), null)
  await assert.rejects(renderAnswerFlowchart('flowchart TD\nA["unclosed label'), /Parse|parse|syntax/i)
  dom.window.close()
})
