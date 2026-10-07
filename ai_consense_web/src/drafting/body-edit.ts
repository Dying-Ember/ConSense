import type { DraftBodyPatch, DraftDocument } from '@/api/types'

export interface BodyDraft { revisionId: string; docxSha256: string; texts: Record<string, string>; insertions: Record<string, string[]> }
export function createBodyDraft(document: DraftDocument | undefined): BodyDraft {
  return { revisionId: document?.revisionId ?? '', docxSha256: document?.docxSha256 ?? '', texts: Object.fromEntries((document?.blocks ?? []).map(block => [block.id, block.text])), insertions: {} }
}
export function bodyDirty(document: DraftDocument | undefined, draft: BodyDraft): boolean {
  if (!document) return false
  return draft.revisionId !== (document.revisionId ?? '') || (document.blocks ?? []).some(block => draft.texts[block.id] !== block.text || (draft.insertions[block.id]?.length ?? 0) > 0)
}
/** Submit only explicit source blocks; the server assesses formatting-safe spans against these expectations. */
export function bodyPatch(document: DraftDocument, draft: BodyDraft): DraftBodyPatch {
  if (document.stale || !document.generated) throw new Error('STALE_ARTIFACT')
  if (!document.revisionId || !document.docxSha256 || draft.revisionId !== document.revisionId || draft.docxSha256 !== document.docxSha256) throw new Error('ARTIFACT_REVISION_CONFLICT')
  const blocks: DraftBodyPatch['blocks'] = []
  for (const block of document.blocks ?? []) {
    const text = draft.texts[block.id], insertAfter = draft.insertions[block.id]
    if (text === block.text && !insertAfter?.length) continue
    if (!block.editable || text === undefined) throw new Error('PROTECTED_BLOCK: ' + block.id)
    blocks.push({ id: block.id, ...(block.bindingId ? { bindingId: block.bindingId } : {}), expectedTextHash: block.textHash, text, ...(insertAfter?.length ? { insertAfter: [...insertAfter] } : {}) })
  }
  return { revisionId: document.revisionId, docxSha256: document.docxSha256, blocks }
}
