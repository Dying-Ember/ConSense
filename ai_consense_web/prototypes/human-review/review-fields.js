(function () {
  'use strict';

  const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[char]);
  const kind = item => typeof item?.type === 'string' ? item.type : item?.type?.kind;

  function validParent(parent, { variables, docId, item }) {
    if (!parent || !['record', 'list'].includes(kind(parent)) || parent.status === 'excluded' ||
        (!parent.docIds?.includes(docId) && parent.id !== item?.parentId)) return false;
    const docs = new Set([docId, ...(item?.docIds || [])]);
    const seen = new Set();
    let ancestor = parent;
    while (ancestor) {
      if (ancestor.id === item?.id || seen.has(ancestor.id)) return false;
      seen.add(ancestor.id);
      for (const id of ancestor.docIds || []) docs.add(id);
      ancestor = variables.find(variable => variable.id === ancestor.parentId);
    }
    const family = item ? [item] : [], visited = new Set();
    for (const member of family) {
      if (visited.has(member.id)) continue;
      visited.add(member.id);
      for (const id of member.docIds || []) docs.add(id);
      family.push(...variables.filter(variable => variable.parentId === member.id));
    }
    return !(docs.has('scc-word') && docs.has('scc-pdf'));
  }

  function render({ variables, docId, item, values = {} }) {
    const parentId = values.parentId ?? item?.parentId ?? '';
    const parents = variables.filter(parent => validParent(parent, { variables, docId, item }));
    let html = '';
    if (parents.length || parentId) {
      const unavailable = parentId && !parents.some(parent => parent.id === parentId);
      html += `<details${parentId ? ' open' : ''}><summary>属于哪一组资料？ / Which group does this belong to?</summary><label class="label">资料组 / Group<select name="parentId"><option value="">不属于任何组 / No group</option>${unavailable ? `<option value="${escape(parentId)}" selected>不可用父项 / Unavailable parent</option>` : ''}${parents.map(parent => `<option value="${escape(parent.id)}"${parent.id === parentId ? ' selected' : ''}>${escape(parent.nameZh)} / ${escape(parent.nameEn)}</option>`).join('')}</select></label></details>`;
    }
    const selectedType = values.type || kind(item) || 'boolean';
    if (['choice', 'multiselect'].includes(selectedType)) {
      const options = item?.options || [];
      html += `<label class="label">选项（中文，每行一项） / Chinese options, one per line<textarea name="optionsZh" rows="4" required>${escape(values.optionsZh ?? options.map(option => option.labelZh).join('\n'))}</textarea></label><label class="label">选项（英文，每行一项） / English options, one per line<textarea name="optionsEn" rows="4" required>${escape(values.optionsEn ?? options.map(option => option.labelEn).join('\n'))}</textarea></label>`;
    }
    return html;
  }

  function read(fd, { variables, docId, item, compatible }) {
    const parentId = String(fd.get('parentId') || '').trim() || null;
    if (parentId) {
      const parent = variables.find(variable => variable.id === parentId);
      if (!validParent(parent, { variables, docId, item })) throw new Error('父项必须是本文件未排除的明细组，且不能形成循环或混合两份 SCC。 / Parent must be an active detail group in this document, without a cycle or mixed SCC sources.');
      if (typeof compatible !== 'function' || !compatible(item || { docIds: [docId] }, parent)) throw new Error('父项与当前问题的来源不兼容。 / Parent and question sources are incompatible.');
    }
    if (!['choice', 'multiselect'].includes(fd.get('type'))) return { parentId, options: [] };
    const labels = ['optionsZh', 'optionsEn'].map(name => String(fd.get(name) || '').replace(/\r\n?/g, '\n').split('\n').map(label => label.trim()));
    if (labels[0].length < 2 || labels[0].length !== labels[1].length) throw new Error('中英文选项数量必须相同，且至少两项。 / Chinese and English options must have equal counts and at least two items.');
    if (labels.some(language => language.some(label => !label) || new Set(language).size !== language.length)) throw new Error('选项不能重复或留空。 / Options cannot be duplicated or blank.');
    const oldOptions = item?.options || [];
    const used = new Set(oldOptions.map(option => String(option.value)));
    const options = labels[0].map((labelZh, index) => {
      const labelEn = labels[1][index];
      const old = oldOptions.find(option => option.labelZh === labelZh && option.labelEn === labelEn);
      if (old) return { value: old.value, labelZh, labelEn };
      let hash = 2166136261;
      for (const char of JSON.stringify([labelZh, labelEn])) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0;
      const base = `option-${hash.toString(16)}`;
      let value = base;
      for (let suffix = 2; used.has(value); suffix++) value = `${base}-${suffix}`;
      used.add(value);
      return { value, labelZh, labelEn };
    });
    return { parentId, options };
  }

  const api = { render, read };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.ReviewFields = api;
})();
