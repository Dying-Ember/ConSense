const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript } = require('vue/compiler-sfc')

/** Load real local Vue/TS modules; only declared external boundaries can be replaced. */
function loadVue(file, { boundaries = {}, globals = {} } = {}) {
  const cache = new Map()
  const root = path.resolve(__dirname, '../../src')
  function load(target) {
    target = path.resolve(target)
    if (cache.has(target)) return cache.get(target).exports
    const source = fs.readFileSync(target, 'utf8')
    const script = target.endsWith('.vue')
      ? compileScript(parse(source, { filename: target }).descriptor, { id: path.basename(target), inlineTemplate: true }).content
      : source
    const output = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }, fileName: target }).outputText
    const module = { exports: {} }; cache.set(target, module)
    vm.runInNewContext(output, { AbortController, module, exports: module.exports, console, setTimeout, clearTimeout, ...globals,
      require(name) {
        if (name === 'vue') return vue
        if (Object.hasOwn(boundaries, name)) return boundaries[name]
        const resolved = name.startsWith('@/') ? path.resolve(root, name.slice(2)) : name.startsWith('.') ? path.resolve(path.dirname(target), name) : null
        if (!resolved) throw new Error(`Undeclared external boundary: ${name}`)
        const next = [resolved, `${resolved}.ts`, `${resolved}.vue`, path.join(resolved, 'index.ts')].find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile())
        if (!next) throw new Error(`Missing local dependency: ${name}`)
        return load(next)
      }
    }, { filename: target })
    return module.exports
  }
  return { ...load(file), loadLocal: load }
}
module.exports = { loadVue }
