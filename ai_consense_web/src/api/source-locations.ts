import { api } from './client'
import type { DraftDocumentBindings } from './types'

export const reviewSourceBindings = (projectId: string, sourceId: string, sourceSha256: string) =>
  api.get<DraftDocumentBindings>(`/drafting/${projectId}/graph/sources/${encodeURIComponent(sourceId)}/bindings?sourceSha256=${encodeURIComponent(sourceSha256)}`, { reportError: false })
