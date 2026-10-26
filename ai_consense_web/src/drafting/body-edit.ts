import type { DraftBlock, DraftBodyCleanupScope, DraftBodyPatch, DraftDocument } from '@/api/types'

export interface BodyDraft { revisionId: string; docxSha256: string; texts: Record<string, string>; insertions: Record<string, string[]>; deletions?: Record<string, boolean>; scopeDeletions?: Record<string, boolean> }
export function createBodyDraft(document: DraftDocument | undefined): BodyDraft {
  return { revisionId: document?.revisionId ?? '', docxSha256: document?.docxSha256 ?? '', texts: Object.fromEntries((document?.blocks ?? []).map(block => [block.id, block.text])), insertions: {}, deletions: {}, scopeDeletions: {} }
}
export function canDeleteBodyBlock(block: DraftBlock): boolean {
  return block.editable && !!block.bindingId && !block.cellId && /^word\/document\.xml#\/w:document\[1\]\/w:body\[1\]\/w:p\[\d+\]$/.test(block.id)
}
export function setBodyDeletion(block: DraftBlock, draft: BodyDraft, remove: boolean): void {
  if (!canDeleteBodyBlock(block)) throw new Error('PROTECTED_PARAGRAPH_DELETE: ' + block.id)
  if (remove) {
    draft.texts[block.id] = block.text
    delete draft.insertions[block.id]
    draft.deletions ??= {}
    draft.deletions[block.id] = true
  } else if (draft.deletions) delete draft.deletions[block.id]
}
export function bodyDirty(document: DraftDocument | undefined, draft: BodyDraft): boolean {
  if (!document) return false
  return draft.revisionId !== (document.revisionId ?? '') || Object.values(draft.deletions ?? {}).some(Boolean) || Object.values(draft.scopeDeletions ?? {}).some(Boolean) || (document.blocks ?? []).some(block => draft.texts[block.id] !== block.text || (draft.insertions[block.id]?.length ?? 0) > 0)
}
export function canDeleteBodyScope(scope: DraftBodyCleanupScope): boolean {
  if (!scope || typeof scope.scopeId !== 'string' || !/^[a-f0-9]{64}$/.test(scope.nodeSha256)) return false
  const paragraph = /^word\/document\.xml#\/w:document\[1\]\/w:body\[1\]\/w:p\[\d+\]$/
  const members = scope.memberBindingIds, ids = scope.paragraphIds
  if (!Array.isArray(members) || members.some(id => !/^CS[A-Za-z0-9]{1,38}$/.test(id)) || new Set(members).size !== members.length
    || !Array.isArray(ids) || ids.length !== scope.paragraphCount || !Number.isInteger(scope.paragraphCount) || scope.paragraphCount < 1 || new Set(ids).size !== ids.length
    || !Array.isArray(scope.paragraphOrdinals) || scope.paragraphOrdinals.length !== scope.paragraphCount || scope.paragraphOrdinals.some(ordinal => !Number.isInteger(ordinal) || ordinal < 1)) return false
  if (scope.kind === 'paragraph_numbering') return scope.operation === 'clear_empty_numbering' && paragraph.test(scope.scopeId) && ids.length === 1 && ids[0] === scope.scopeId
  if (!members.length) return false
  if (scope.kind === 'body_range') {
    if (scope.operation !== 'delete_empty_body_range' || scope.scopeId !== `body-range:${scope.nodeSha256}` || !Array.isArray(scope.nativeTargets) || !scope.nativeTargets.length) return false
    const union: string[] = [], targets = new Set<string>()
    for (const target of scope.nativeTargets) {
      if (!target || targets.has(target.id) || !/^[a-f0-9]{64}$/.test(target.sha256) || !Array.isArray(target.paragraphIds) || !target.paragraphIds.length) return false
      targets.add(target.id)
      if (target.kind === 'p') { if (!paragraph.test(target.id) || target.paragraphIds.length !== 1 || target.paragraphIds[0] !== target.id) return false }
      else if (target.kind === 'tbl') { if (!/^word\/document\.xml#\/w:document\[1\]\/w:body\[1\]\/w:tbl\[\d+\]$/.test(target.id) || target.paragraphIds.some(id => typeof id !== 'string' || !id.startsWith(target.id + '/'))) return false }
      else return false
      union.push(...target.paragraphIds)
    }
    return union.length === ids.length && union.every((id, index) => id === ids[index]) && new Set(union).size === union.length
  }
  const suffix = scope.kind === 'table' ? '' : scope.kind === 'row' ? '/w:tr\\[\\d+\\]' : null
  return suffix !== null && new RegExp('^word/document\\.xml#/w:document\\[1\\]/w:body\\[1\\]/w:tbl\\[\\d+\\]' + suffix + '$').test(scope.scopeId)
    && scope.operation === (scope.kind === 'table' ? 'delete_empty_table' : 'delete_empty_row') && ids.every(id => typeof id === 'string' && id.startsWith(scope.scopeId + '/'))
}
export function bodyScopeConflict(document: Pick<DraftDocument, 'blocks'>, draft: BodyDraft, scope: DraftBodyCleanupScope): boolean {
  return (document.blocks ?? []).some(block => scope.paragraphIds.includes(block.id) && (draft.texts[block.id] !== block.text || !!draft.insertions[block.id]?.length || !!draft.deletions?.[block.id]))
}
export function setBodyScopeDeletion(document: DraftDocument, draft: BodyDraft, scopeId: string, remove: boolean): void {
  const scopes = (document.bodyCleanupScopes ?? []).filter(scope => scope.scopeId === scopeId)
  if (scopes.length !== 1 || !canDeleteBodyScope(scopes[0])) throw new Error('BODY_SCOPE_NOT_AVAILABLE')
  if (remove && bodyScopeConflict(document, draft, scopes[0])) throw new Error('BODY_SCOPE_BLOCK_CONFLICT')
  if (remove) { draft.scopeDeletions ??= {}; draft.scopeDeletions[scopeId] = true }
  else if (draft.scopeDeletions) delete draft.scopeDeletions[scopeId]
}
/** Submit only explicit source blocks; the server assesses formatting-safe spans against these expectations. */
export function bodyPatch(document: DraftDocument, draft: BodyDraft): DraftBodyPatch {
  if (document.stale || !document.generated) throw new Error('STALE_ARTIFACT')
  if (!document.revisionId || !document.docxSha256 || draft.revisionId !== document.revisionId || draft.docxSha256 !== document.docxSha256) throw new Error('ARTIFACT_REVISION_CONFLICT')
  const blocks: DraftBodyPatch['blocks'] = []
  const known = new Set((document.blocks ?? []).map(block => block.id))
  if (Object.entries(draft.deletions ?? {}).some(([id, remove]) => remove && !known.has(id))) throw new Error('SOURCE_BLOCK_INVALID')
  for (const block of document.blocks ?? []) {
    const text = draft.texts[block.id], insertAfter = draft.insertions[block.id]
    if (draft.deletions?.[block.id]) {
      if (!canDeleteBodyBlock(block)) throw new Error('PROTECTED_PARAGRAPH_DELETE: ' + block.id)
      if (text !== block.text || insertAfter?.length) throw new Error('PARAGRAPH_DELETE_CONFLICT: ' + block.id)
      blocks.push({ id: block.id, bindingId: block.bindingId, expectedTextHash: block.textHash, operation: 'delete_paragraph' })
      continue
    }
    if (text === block.text && !insertAfter?.length) continue
    if (!block.editable || text === undefined) throw new Error('PROTECTED_BLOCK: ' + block.id)
    blocks.push({ id: block.id, ...(block.bindingId ? { bindingId: block.bindingId } : {}), expectedTextHash: block.textHash, text, ...(insertAfter?.length ? { insertAfter: [...insertAfter] } : {}) })
  }
  const scopeEdits: NonNullable<DraftBodyPatch['scopeEdits']> = []
  const selectedParagraphIds = new Set<string>()
  for (const [scopeId, remove] of Object.entries(draft.scopeDeletions ?? {})) {
    if (!remove) continue
    const scopes = (document.bodyCleanupScopes ?? []).filter(scope => scope.scopeId === scopeId), scope = scopes[0]
    if (scopes.length !== 1 || !canDeleteBodyScope(scope)) throw new Error('BODY_SCOPE_NOT_AVAILABLE')
    if (bodyScopeConflict(document, draft, scope)) throw new Error('BODY_SCOPE_BLOCK_CONFLICT')
    if (scopeEdits.some(other => scopeId.startsWith(other.scopeId + '/') || other.scopeId.startsWith(scopeId + '/') || other.memberBindingIds.some(id => scope.memberBindingIds.includes(id)))) throw new Error('BODY_SCOPE_OVERLAP')
    if (scope.paragraphIds.some(id => selectedParagraphIds.has(id))) throw new Error('BODY_SCOPE_OVERLAP')
    scope.paragraphIds.forEach(id => selectedParagraphIds.add(id))
    scopeEdits.push({ scopeId, expectedScopeHash: scope.nodeSha256, operation: scope.operation, memberBindingIds: [...scope.memberBindingIds] })
  }
  return { revisionId: document.revisionId, docxSha256: document.docxSha256, blocks, ...(scopeEdits.length ? { scopeEdits } : {}) }
}
