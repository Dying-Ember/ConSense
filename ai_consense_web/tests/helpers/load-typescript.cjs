const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

/** Each test declares its runtime globals, dependencies and compiler / VM options. */
function loadTypeScript(file, { globals = {}, dependencies = {}, compilerOptions = {}, vmOptions = {} } = {}) {
  const source = fs.readFileSync(file, 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, ...compilerOptions }, fileName: file }).outputText
  const module = { exports: {} }
  const runtime = { ...globals, module, exports: module.exports, require(name) {
    if (!Object.hasOwn(dependencies, name)) throw new Error(`Unexpected runtime dependency: ${name} in ${file}`)
    const dependency = dependencies[name]
    return typeof dependency === 'string' ? loadTypeScript(path.resolve(path.dirname(file), dependency), { globals, compilerOptions, vmOptions }) : dependency
  } }
  vm.runInNewContext(compiled, runtime, { filename: file, ...vmOptions })
  return module.exports
}
module.exports = { loadTypeScript }
