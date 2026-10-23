(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ReviewPack = factory();
})(typeof window === 'object' ? window : globalThis, function () {
  'use strict';

  const record = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const text = value => typeof value === 'string' && value.trim().length > 0;
  const clone = value => JSON.parse(JSON.stringify(value));

  function validate(pack, { sourceKey, documents, types, statuses }) {
    const errors = [];
    const fail = message => { if (errors.length < 100) errors.push(message); };
    const result = () => ({ ok: errors.length === 0, errors });
    if (!record(pack) || pack.kind !== 'consense-review-v1') {
      fail('Invalid review package kind.');
      return result();
    }
    if (!text(sourceKey) || pack.sourceKey !== sourceKey) fail('Source identity does not match.');
    if (!record(documents) || !Array.isArray(types) || !Array.isArray(statuses)) {
      fail('Invalid validation context.');
      return result();
    }
    const state = pack.state;
    if (!record(state) || !Array.isArray(state.variables) || !Array.isArray(state.events) || !record(state.sourceReviews)) {
      fail('State requires variables, events and sourceReviews.');
      return result();
    }
    if (!Number.isSafeInteger(state.nextId) || state.nextId < 1) fail('nextId must be a positive safe integer.');
    if (typeof state.blank !== 'boolean' || !text(state.guidanceMode)) fail('Invalid review starting state.');
    for (const [id, reviewed] of Object.entries(state.sourceReviews)) {
      if (!text(id) || typeof reviewed !== 'boolean') fail('Invalid source review acknowledgement.');
    }
    for (const event of state.events) {
      if (!record(event) || !text(event.zh) || !text(event.en) || typeof event.time !== 'string' ||
          !Array.isArray(event.details) || event.details.some(detail => typeof detail !== 'string')) fail('Invalid review event.');
    }

    const byId = new Map();
    for (const variable of state.variables) {
      if (!record(variable) || !text(variable.id)) { fail('Every variable requires a string ID.'); continue; }
      if (byId.has(variable.id)) fail(`Duplicate variable ID: ${variable.id}`);
      else byId.set(variable.id, variable);
    }
    const checkIds = (value, label) => {
      if (!Array.isArray(value) || value.some(id => !text(id))) { fail(`${label} must contain string IDs.`); return false; }
      return true;
    };
    const compatible = (a, b) => {
      const contextDocs = item => {
        const docs = new Set(Array.isArray(item?.docIds) ? item.docIds : []), seen = new Set();
        while (item?.parentId && !seen.has(item.id)) {
          seen.add(item.id); item = byId.get(item.parentId);
          for (const doc of Array.isArray(item?.docIds) ? item.docIds : []) docs.add(doc);
        }
        return [...docs];
      };
      const docs = new Set([...contextDocs(a), ...contextDocs(b)]);
      return !(docs.has('scc-word') && docs.has('scc-pdf'));
    };
    const listForInput = item => {
      const seen = new Set();
      while (item?.parentId && !seen.has(item.id)) {
        seen.add(item.id); item = byId.get(item.parentId);
        if (item?.type?.kind === 'list') return item.id;
      }
      return '';
    };
    function checkClause(clause, variable, label, historical) {
      if (!record(clause) || !text(clause.inputId) || !['yes', 'no', 'known', 'eq', 'ge', 'le', 'gt', 'lt', 'has', 'lacks', 'empty', 'notempty'].includes(clause.operator) ||
          (clause.value !== undefined && typeof clause.value !== 'string')) { fail(`${label}: invalid rule clause.`); return; }
      if (historical) return;
      const input = byId.get(clause.inputId);
      if (!input?.type || input.status === 'excluded' || !compatible(input, variable)) { fail(`${label}: select an active compatible condition input.`); return; }
      const kind = input.type.kind, operator = clause.operator;
      if (['ge', 'le', 'gt', 'lt'].includes(operator) && kind !== 'number') fail(`${label}: numeric comparison requires a number input.`);
      if (['empty', 'notempty'].includes(operator) && !['text', 'multiselect', 'list'].includes(kind)) fail(`${label}: empty/content checks require text, multiple choices or a list.`);
      if (['yes', 'no'].includes(operator) && kind !== 'boolean') fail(`${label}: Yes / No requires a boolean input.`);
      if (['eq', 'ge', 'le', 'gt', 'lt'].includes(operator) && !String(clause.value || '').trim()) fail(`${label}: comparison value is required.`);
      if (['eq', 'ge', 'le', 'gt', 'lt'].includes(operator) && kind === 'number' && !Number.isFinite(Number(clause.value))) fail(`${label}: comparison value must be finite.`);
      if (operator === 'eq' && ['record', 'list', 'multiselect'].includes(kind)) fail(`${label}: equality requires an individual answer.`);
      if (['has', 'lacks'].includes(operator) && kind !== 'multiselect') fail(`${label}: includes requires a multiple-choice answer.`);
      if ((operator === 'eq' && kind === 'choice' || ['has', 'lacks'].includes(operator)) && !(input.options || []).some(option => option?.value === clause.value)) fail(`${label}: choose an existing answer option.`);
    }
    function checkAnchor(anchor, variable, label) {
      if (!record(anchor) || !text(anchor.docId) || !Object.hasOwn(documents, anchor.docId) || !Array.isArray(variable.docIds) || !variable.docIds.includes(anchor.docId)) {
        fail(`${label}: invalid document.`);
        return;
      }
      const pages = documents[anchor.docId].pages;
      if (!Array.isArray(pages) || !Number.isInteger(anchor.page) || anchor.page < 1 || anchor.page > pages.length) {
        fail(`${label}: invalid page.`);
        return;
      }
      const words = pages[anchor.page - 1]?.words;
      if (!Array.isArray(words)) { fail(`${label}: page has no word range.`); return; }
      if (anchor.wordIds !== undefined) {
        if (!Array.isArray(anchor.wordIds) || !anchor.wordIds.length ||
            anchor.wordIds.some(id => !Number.isInteger(id) || id < 0 || id >= words.length) ||
            new Set(anchor.wordIds).size !== anchor.wordIds.length) fail(`${label}: invalid wordIds.`);
      }
      if (anchor.wordIds === undefined || anchor.start !== undefined || anchor.end !== undefined) {
        if (!Number.isInteger(anchor.start) || !Number.isInteger(anchor.end) || anchor.start < 0 ||
            anchor.start >= anchor.end || anchor.end > words.length) fail(`${label}: invalid word range.`);
      }
      if (anchor.text !== undefined && typeof anchor.text !== 'string') fail(`${label}: invalid source text.`);
    }

    for (const variable of state.variables) {
      if (!record(variable) || !text(variable.id)) continue;
      const label = variable.id;
      if (!text(variable.nameZh) || !text(variable.nameEn)) fail(`${label}: bilingual names are required.`);
      if (!record(variable.type) || !types.includes(variable.type.kind)) fail(`${label}: invalid answer type.`);
      if (!record(variable.role) || !['input', 'decision', 'content', 'derived', 'verification', 'system', 'fixed'].includes(variable.role.kind) ||
          !text(variable.role.zh) || !text(variable.role.en)) fail(`${label}: invalid bilingual role.`);
      if (!statuses.includes(variable.status)) fail(`${label}: invalid status.`);
      if (variable.status === 'confirmed') {
        if (variable.missingSource || !Array.isArray(variable.sourceAnchors) || !variable.sourceAnchors.length) fail(`${label}: confirmation requires an explicit source passage.`);
        if (variable.role?.kind === 'derived' && !variable.correction) fail(`${label}: a derived answer requires explicit conditions before confirmation.`);
      }
      if (!Array.isArray(variable.docIds) || !variable.docIds.length ||
          variable.docIds.some(id => !text(id) || !Object.hasOwn(documents, id)) ||
          new Set(variable.docIds).size !== variable.docIds.length) fail(`${label}: invalid docIds.`);
      if (Array.isArray(variable.docIds) && variable.docIds.includes('scc-word') && variable.docIds.includes('scc-pdf')) fail(`${label}: both SCC versions cannot belong to one variable.`);
      if (variable.parentId !== undefined && variable.parentId !== null && variable.parentId !== '') {
        const parent = byId.get(variable.parentId);
        if (!parent || !['record', 'list'].includes(parent.type?.kind)) fail(`${label}: parent must be an existing record or list.`);
        else if (!compatible(parent, variable)) fail(`${label}: parent and child cannot mix SCC versions.`);
      }
      for (const field of ['evidence', 'targets', 'relations']) {
        if (!Array.isArray(variable[field]) || variable[field].some(entry => !record(entry))) { fail(`${label}: ${field} must contain records.`); continue; }
        for (const entry of variable[field]) {
          if (field !== 'relations' && (!text(entry.docId) || !Object.hasOwn(documents, entry.docId) || !Array.isArray(variable.docIds) || !variable.docIds.includes(entry.docId))) fail(`${label}: invalid ${field} document.`);
          if (field === 'evidence' && (typeof entry.quote !== 'string' || ['locationZh', 'locationEn'].some(key => entry[key] !== undefined && typeof entry[key] !== 'string'))) fail(`${label}: invalid evidence wording.`);
          if (field === 'targets' && ['labelZh', 'labelEn'].some(key => entry[key] !== undefined && typeof entry[key] !== 'string')) fail(`${label}: invalid target wording.`);
          if (field === 'relations' && entry.branches !== undefined && entry.branches !== null &&
              (!Array.isArray(entry.branches) || entry.branches.some(branch => !record(branch) ||
                ['whenZh', 'whenEn', 'resultZh', 'resultEn'].some(key => branch[key] !== undefined && typeof branch[key] !== 'string')))) fail(`${label}: invalid relation branches.`);
        }
      }
      for (const field of ['sourceAnchors', 'editTargets']) {
        if (field === 'editTargets' && variable[field] === undefined) continue;
        if (!Array.isArray(variable[field])) { fail(`${label}: ${field} must be an array.`); continue; }
        variable[field].forEach((anchor, index) => {
          checkAnchor(anchor, variable, `${label}.${field}[${index}]`);
          if (field === 'editTargets') {
            if (!['keep', 'delete', 'fill', 'replace', 'unused', 'check'].includes(anchor?.action)) fail(`${label}: invalid edit action.`);
            if (variable.status === 'confirmed' && (anchor?.action === 'check' ||
                ['fill', 'replace'].includes(anchor?.action) && !text(anchor.valueSourceId) && !text(anchor.replacementEn))) fail(`${label}: confirmation requires a decided action and new content for each affected passage.`);
            for (const key of ['branchId', 'valueSourceId', 'replacementZh', 'replacementEn', 'prefix', 'suffix', 'valueMode', 'listId', 'separator']) {
              if (anchor?.[key] !== undefined && typeof anchor[key] !== 'string') fail(`${label}: invalid target ${key}.`);
            }
            if (anchor?.valueMode !== undefined && !['single', 'join'].includes(anchor.valueMode)) fail(`${label}: invalid target value mode.`);
            if (anchor?.valueMode === 'join') {
              if (!['fill', 'replace'].includes(anchor.action) || !text(anchor.valueSourceId) || !text(anchor.listId) || typeof anchor.separator !== 'string') fail(`${label}: a joined target requires an answer, list and string separator.`);
              if (variable.status !== 'excluded' && !variable.correction?.needsRebind && !anchor.needsRebind) {
                const input = byId.get(anchor.valueSourceId), list = byId.get(anchor.listId);
                if (!input || input.status === 'excluded' || !['boolean', 'number', 'text', 'date', 'choice'].includes(input.type?.kind) ||
                    !list || list.status === 'excluded' || list.type?.kind !== 'list' || listForInput(input) !== anchor.listId || !compatible(input, variable) || !compatible(list, variable)) fail(`${label}: a joined target requires a scalar detail in its actual active compatible list.`);
              }
            } else if (anchor?.listId !== undefined || anchor?.separator !== undefined) fail(`${label}: list and separator require join mode.`);
            if (anchor?.needsRebind !== undefined && typeof anchor.needsRebind !== 'boolean') fail(`${label}: invalid target needsRebind.`);
            if (anchor?.needsRebind && variable.status === 'confirmed') fail(`${label}: a target awaiting rebinding cannot be confirmed.`);
            const correction = variable.correction;
            const hasBranch = Array.isArray(correction?.branches) ? correction.branches.some(branch => branch?.id === anchor?.branchId) :
              correction?.kind === 'rule' && correction.branches === undefined && Array.isArray(correction.clauses) && anchor?.branchId === 'case-1';
            if (anchor?.branchId && !hasBranch && !anchor.needsRebind && variable.status !== 'excluded' && !variable.correction?.needsRebind) fail(`${label}: target branch does not exist on this item.`);
            if (anchor?.valueSourceId && variable.status !== 'excluded' && !variable.correction?.needsRebind && !anchor.needsRebind) {
              const input = byId.get(anchor.valueSourceId);
              if (!input || input.status === 'excluded' || !compatible(input, variable) || ['record', 'list'].includes(input.type?.kind)) fail(`${label}: target value requires an active compatible field.`);
            }
          }
        });
      }
      for (const field of ['sourceUnitIds', 'relatedInputIds', 'replacedBy']) {
        if (variable[field] !== undefined) checkIds(variable[field], `${label}.${field}`);
      }
      if (variable.originId !== undefined && variable.originId !== null && !text(variable.originId)) fail(`${label}: invalid originId.`);
      if (variable.options !== undefined && (!Array.isArray(variable.options) || variable.options.some(option =>
        !record(option) || !text(option.value) || !text(option.labelZh) || !text(option.labelEn)))) fail(`${label}: invalid bilingual options.`);
      if (variable.correction !== undefined && variable.correction !== null) {
        const correction = variable.correction;
        const branched = correction?.kind === 'rule' && correction.branches !== undefined;
        if (!record(correction) || !['rule', 'split'].includes(correction.kind) || (!branched && !['all', 'any', 'unknown'].includes(correction.mode)) ||
            (correction.needsRebind !== undefined && typeof correction.needsRebind !== 'boolean') ||
            (correction.kind === 'rule' && !branched && !Array.isArray(correction.clauses)) ||
            (branched && !Array.isArray(correction.branches)) ||
            (correction.kind === 'split' && !Array.isArray(correction.inputIds))) {
          fail(`${label}: invalid correction.`);
          continue;
        }
        const historical = correction.needsRebind || variable.status === 'excluded';
        const checkDerivedAnswer = branch => {
          if (branch.resultValue !== undefined && !['boolean', 'string'].includes(typeof branch.resultValue)) fail(`${label}: invalid derived answer.`);
          if (!historical && variable.role?.kind === 'derived' && ['boolean', 'choice'].includes(variable.type?.kind)) {
            const validAnswer = variable.type.kind === 'boolean' ? typeof branch.resultValue === 'boolean' :
              (variable.options || []).some(option => option.value === branch.resultValue);
            if ((branch.resultValue !== undefined || variable.status === 'confirmed') && !validAnswer) fail(`${label}: choose an explicit derived answer for each case.`);
          }
        };
        if (!branched && correction.kind === 'rule') checkDerivedAnswer(correction);
        if (variable.status === 'confirmed') {
          if (correction.kind === 'split' && (!['all', 'any'].includes(correction.mode) || !correction.inputIds.length)) fail(`${label}: split conditions must be resolved before confirmation.`);
          const branches = correction.kind === 'rule' ? (branched ? correction.branches : [correction]) : null;
          if (branches && (!branches.length || branches.some(branch => !record(branch) || !['all', 'any'].includes(branch.mode) ||
              !Array.isArray(branch.clauses) || !branch.clauses.length || !text(branch.effectZh) || !text(branch.effectEn)))) fail(`${label}: confirmation requires complete conditions and bilingual effects for every case.`);
        }
        if (correction.otherwise !== undefined && correction.otherwise !== 'needs_review') fail(`${label}: unmatched cases must remain unresolved.`);
        if (correction.needsRebind && variable.status === 'confirmed') fail(`${label}: conditions awaiting rebinding cannot be confirmed.`);
        if (correction.inputIds !== undefined && checkIds(correction.inputIds, `${label}.correction.inputIds`) &&
            !historical && correction.inputIds.some(id => !byId.has(id))) fail(`${label}: rule inputs require rebinding.`);
        if (correction.kind === 'split' && Array.isArray(correction.inputIds)) {
          for (const inputId of correction.inputIds) checkClause({ inputId, operator: 'yes', value: '' }, variable, label, historical);
        }
        if (correction.clauses !== undefined && !Array.isArray(correction.clauses)) fail(`${label}: clauses must be an array.`);
        for (const clause of Array.isArray(correction.clauses) ? correction.clauses : []) {
          checkClause(clause, variable, label, historical);
        }
        const branchIds = new Set();
        for (const branch of Array.isArray(correction.branches) ? correction.branches : []) {
          if (!record(branch) || !text(branch.id) || branchIds.has(branch.id) || !['all', 'any', 'unknown'].includes(branch.mode) ||
              !Array.isArray(branch.clauses) || ['effectZh', 'effectEn'].some(key => branch[key] !== undefined && typeof branch[key] !== 'string')) {
            fail(`${label}: invalid rule branch.`);
            continue;
          }
          branchIds.add(branch.id);
          checkDerivedAnswer(branch);
          if (branch.scopeId !== undefined && typeof branch.scopeId !== 'string') fail(`${label}: invalid detail scope.`);
          if (branch.scopeId && !['each', 'any', 'all', 'none'].includes(branch.scopeMode)) fail(`${label}: invalid list decision mode.`);
          if (!historical) {
            const scope = branch.scopeId && byId.get(branch.scopeId);
            if (branch.scopeId && (!scope || scope.type?.kind !== 'list' || scope.status === 'excluded' || !compatible(scope, variable))) fail(`${label}: choose an active compatible list scope.`);
            for (const clause of branch.clauses) {
              const listId = listForInput(byId.get(clause?.inputId));
              if (listId && listId !== branch.scopeId) fail(`${label}: detail conditions must refer to the same list item.`);
            }
          }
          for (const clause of branch.clauses) checkClause(clause, variable, `${label}.${branch.id}`, historical);
        }
      }
    }

    const completed = new Set();
    for (const id of byId.keys()) {
      const path = new Set();
      let cursor = id;
      while (byId.has(cursor) && !completed.has(cursor)) {
        if (path.has(cursor)) { fail(`${id}: parent hierarchy contains a cycle.`); break; }
        path.add(cursor);
        cursor = byId.get(cursor).parentId;
      }
      for (const visited of path) completed.add(visited);
    }
    return result();
  }

  // append accepts a package already checked against the current source documents.
  function append(current, pack) {
    const output = clone(current);
    const incoming = clone(pack.state);
    const used = new Set(output.variables.map(variable => variable.id));
    const incomingIds = new Set(incoming.variables.map(variable => variable.id));
    const ids = new Map();
    let nextId = Math.max(output.nextId, incoming.nextId);
    if (!Number.isSafeInteger(nextId + incoming.variables.length)) throw new RangeError('Review ID counter exhausted.');
    for (const variable of incoming.variables) {
      let id = variable.id;
      if (used.has(id)) {
        do { id = `import-${nextId++}`; } while (used.has(id) || incomingIds.has(id));
      }
      ids.set(variable.id, id);
      used.add(id);
    }
    const remap = id => ids.get(id) ?? id;
    for (const variable of incoming.variables) {
      variable.id = remap(variable.id);
      for (const field of ['parentId', 'originId']) if (variable[field] !== undefined) variable[field] = remap(variable[field]);
      for (const field of ['relatedInputIds', 'replacedBy']) if (Array.isArray(variable[field])) variable[field] = variable[field].map(remap);
      if (Array.isArray(variable.editTargets)) for (const target of variable.editTargets) {
        if (target.valueSourceId !== undefined) target.valueSourceId = remap(target.valueSourceId);
        if (target.listId !== undefined) target.listId = remap(target.listId);
      }
      if (variable.correction) {
        if (Array.isArray(variable.correction.inputIds)) variable.correction.inputIds = variable.correction.inputIds.map(remap);
        if (Array.isArray(variable.correction.clauses)) variable.correction.clauses = variable.correction.clauses.map(clause => ({ ...clause, inputId: remap(clause.inputId) }));
        if (Array.isArray(variable.correction.branches)) for (const branch of variable.correction.branches) {
          if (Array.isArray(branch.clauses)) branch.clauses = branch.clauses.map(clause => ({ ...clause, inputId: remap(clause.inputId) }));
          if (branch.scopeId) branch.scopeId = remap(branch.scopeId);
        }
      }
    }
    output.variables = output.variables.concat(incoming.variables);
    const reviews = new Map(Object.entries(output.sourceReviews));
    for (const [id, reviewed] of Object.entries(incoming.sourceReviews)) reviews.set(id, Boolean(reviews.get(id)) || reviewed);
    output.sourceReviews = Object.fromEntries(reviews);
    output.events = output.events.concat(incoming.events);
    output.nextId = Math.max(nextId, output.nextId + incoming.variables.length);
    output.blank = output.blank || incoming.blank;
    if (!output.guidanceMode) output.guidanceMode = incoming.guidanceMode;
    return output;
  }

  return { validate, append };
});
