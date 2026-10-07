const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')
const { renderToString } = require('vue/server-renderer')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const words = loadTypeScript(path.join(__dirname, '../src/drafting/words.ts'), {})
const file = path.join(__dirname, '../src/components/DraftingBodyEditor.vue')
const script = compileScript(parse(fs.readFileSync(file, 'utf8'), { filename: file }).descriptor, { id: 'body-editor-test', inlineTemplate: true }).content
const compiled = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const moduleForComponent = { exports: {} }
vm.runInNewContext(compiled, { module: moduleForComponent, exports: moduleForComponent.exports, require: name => name === 'vue' ? vue : name === '@/drafting/words' ? words : (() => { throw new Error(name) })() })
;(async () => {
  for (const locale of ['en', 'zh-Hans', 'zh-Hant']) {
    const blocks = [{ id: 'source/p[2]', text: 'Exact formal\twording', textHash: 'h2', paragraphOrdinal: 2, editable: true }, { id: 'source/p[3]', text: 'TOC result', textHash: 'h3', paragraphOrdinal: 3, editable: false }]
    const html = await renderToString(vue.createSSRApp(moduleForComponent.exports.default, { blocks, draft: { texts: { 'source/p[2]': 'Edited body value', 'source/p[3]': 'TOC result' }, insertions: {} }, locale }))
    assert.match(html, /data-block-id="source\/p\[2\]"/)
    assert.match(html, /class="paragraph-text"[^>]*>Edited body value<\/p>/)
    assert.match(html, /data-block-id="source\/p\[3\]"/)
    assert.ok(html.includes('TOC result'))
    assert.ok(!html.includes('<textarea'))
    assert.ok(html.includes(words.draftWord('bodyEditParagraph', locale)))
    assert.ok(html.includes(words.draftWord('protectedBody', locale)))
    assert.ok(html.includes(words.draftWord('bodyReaderNote', locale)))
    assert.ok(html.includes(words.draftWord('bodyEditNote', locale)))
    assert.ok(!html.includes('<iframe') && !html.includes('<canvas'))
  }
  console.log('PASS: actual Vue body reader renders stable source IDs, current text, protected field guidance and localized controls without opening editors in three languages')
})().catch(error => { console.error(error); process.exitCode = 1 })
