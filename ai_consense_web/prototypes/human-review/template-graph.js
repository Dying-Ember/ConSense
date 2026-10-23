(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./review-pack.js'));
  else root.TemplateGraph = factory(root.ReviewPack);
})(typeof window === 'object' ? window : globalThis, function (ReviewPack) {
  'use strict';
  const copy = value => JSON.parse(JSON.stringify(value));
  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
  const identityFields = ['format', 'originalSha256', 'visualSha256', 'layoutSha256'];

  function validate(saved, context) {
    const errors = [];
    if (!record(saved) || saved.kind !== 'consense-template-graph-v1') return { ok: false, errors: ['请选择模板图谱文件。 / Choose a template graph file.'] };
    if (typeof saved.id !== 'string' || !/^[A-Za-z0-9_-]{1,96}$/.test(saved.id) ||
        typeof saved.name !== 'string' || !saved.name.trim() || saved.name.length > 120 ||
        typeof saved.savedAt !== 'string' || !Number.isFinite(Date.parse(saved.savedAt))) errors.push('图谱名称或版本信息不完整。 / The graph name or version is incomplete.');
    const expected = context.templateBindings;
    if (!Array.isArray(expected) || !expected.length || !Array.isArray(saved.templates) || saved.templates.length !== expected.length) {
      errors.push('模板文件不齐，不能套用这份图谱。 / The template set does not match this graph.');
    } else {
      const seen = new Set();
      for (const binding of saved.templates) {
        const current = record(binding) && expected.find(item => item.id === binding.id);
        if (!current || seen.has(binding.id) || !['originalSha256', 'visualSha256', 'layoutSha256'].every(key => digest(binding[key]) && digest(current[key])) ||
            identityFields.some(key => binding[key] !== current[key])) errors.push('模板内容或原文位置已变化，请重新核对。 / Template content or source positions have changed. Review this version separately.');
        if (record(binding)) seen.add(binding.id);
      }
    }
    const review = { kind: 'consense-review-v1', sourceKey: saved.sourceKey, state: saved.graph };
    const checked = ReviewPack.validate(review, context);
    errors.push(...checked.errors);
    if (checked.ok) {
      if (typeof context.sourceText !== 'function') errors.push('原文核对暂不可用。 / Source wording verification is unavailable.');
      else for (const variable of saved.graph.variables) for (const passage of [...(variable.sourceAnchors || []), ...(variable.editTargets || [])]) {
        const words = context.documents[passage.docId].pages[passage.page - 1].words;
        const ids = passage.wordIds || Array.from({ length: passage.end - passage.start }, (_, i) => passage.start + i);
        if (passage.text !== undefined && context.sourceText(words, ids) !== passage.text) errors.push('保存的原文与模板位置不符，请重新核对。 / Saved wording does not match its template position.');
      }
    }
    return { ok: errors.length === 0, errors };
  }

  function create(state, context, metadata) {
    const saved = { kind: 'consense-template-graph-v1', id: metadata.id, name: metadata.name.trim(), savedAt: metadata.savedAt,
      sourceKey: context.sourceKey, templates: copy(context.templateBindings), graph: copy(state) };
    const checked = validate(saved, context);
    if (!checked.ok) throw Error(checked.errors.join(' · '));
    return saved;
  }

  function toReview(saved, context) {
    const checked = validate(saved, context);
    if (!checked.ok) throw Error(checked.errors.join(' · '));
    return { kind: 'consense-review-v1', sourceKey: saved.sourceKey, state: copy(saved.graph) };
  }

  function storage(indexedDB) {
    async function open() {
      if (!indexedDB) throw Error('浏览器未提供本机保存，请下载图谱备份。 / Local storage is unavailable. Download a graph backup.');
      return new Promise((resolve, reject) => {
        const request = indexedDB.open('consense-template-graphs-prototype', 1);
        let blocked = false;
        request.onupgradeneeded = () => request.result.createObjectStore('graphs', { keyPath: 'id' }).createIndex('sourceKey', 'sourceKey');
        request.onsuccess = () => { if (blocked) request.result.close(); else resolve(request.result); };
        request.onerror = () => reject(request.error);
        request.onblocked = () => { blocked = true; reject(Error('请关闭另一个图谱保存窗口后重试。 / Close the other graph storage window and retry.')); };
      });
    }
    async function run(mode, action) {
      const db = await open();
      return new Promise((resolve, reject) => {
        let result;
        const transaction = db.transaction('graphs', mode);
        transaction.oncomplete = () => { db.close(); resolve(result); };
        transaction.onabort = () => { db.close(); reject(transaction.error || Error('图谱保存未完成。 / Graph storage did not complete.')); };
        try { action(transaction.objectStore('graphs'), value => { result = value; }); }
        catch (error) { transaction.abort(); reject(error); }
      });
    }
    return {
      save: saved => run('readwrite', (store, result) => { store.add(saved); result(saved.id); }),
      read: id => run('readonly', (store, result) => { const request = store.get(id); request.onsuccess = () => result(request.result || null); }),
      list: sourceKey => run('readonly', (store, result) => {
        const entries = []; result(entries);
        const request = store.index('sourceKey').openCursor(sourceKey);
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) return;
          const saved = cursor.value;
          entries.push({ id: saved.id, name: saved.name, savedAt: saved.savedAt, count: saved.graph.variables.filter(v => v.status !== 'excluded').length });
          cursor.continue();
        };
      })
    };
  }

  return { create, validate, toReview, storage };
});
