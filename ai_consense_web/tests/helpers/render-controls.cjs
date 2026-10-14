const vue = require('vue')
// Runtime DOM v-model checks these constructors when updating custom-host controls.
globalThis.Document ??= class Document {}
globalThis.ShadowRoot ??= class ShadowRoot {}

const htmlText = new (require('jsdom').JSDOM)('').window.document.createElement('div')
const text = node => {
  if (typeof node.props?.innerHTML === 'string') { htmlText.innerHTML = node.props.innerHTML; return htmlText.textContent }
  return node.text || (node.children ?? []).map(text).join(' ')
}
const find = (node, predicate) => predicate(node) ? node : (node.children ?? []).map(child => find(child, predicate)).find(Boolean)
const settle = async () => { for (let i = 0; i < 25; i++) await Promise.resolve(); await vue.nextTick() }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

function renderer() {
  const root = { children: [] }
  const host = {
    createElement(tag) {
      return { tag, tagName: tag.toUpperCase(), children: [], props: {}, listeners: {}, style: {}, value: '',
        getRootNode() { return { activeElement: null } },
        addEventListener(name, handler) { this.listeners[name] = handler }, removeEventListener(name) { delete this.listeners[name] },
        get options() { return this.children.filter(child => child.tag === 'option') } }
    }, createText: text => ({ text }), createComment: text => ({ comment: text }),
    insert(node, parent, anchor) { if (node.parent) host.remove(node); node.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1; index < 0 ? parent.children.push(node) : parent.children.splice(index, 0, node) },
    remove(node) { const index = node.parent?.children.indexOf(node); if (index >= 0) node.parent.children.splice(index, 1); node.parent = null },
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.children = []; node.text = text },
    parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
    patchProp: (node, key, _old, value) => { node.props[key] = value; if (key === 'value') node.value = value },
    insertStaticContent(content, parent, anchor) { const node = { text: content, props: {}, children: [] }; host.insert(node, parent, anchor); return [node, node] }
  }
  return { root, createApp: (...args) => vue.createRenderer(host).createApp(...args) }
}

module.exports = { text, find, settle, deferred, renderer }
