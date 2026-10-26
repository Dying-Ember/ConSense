import { api } from './client'

export type InspectionRecord = Record<string, unknown>
export interface InspectionDataset { id: string; label: string; projectId?: string; status?: string; unavailableReason?: string; scope?: string; vectorStatus?: string; vectorPointCount?: number; chunkCount?: number; windowCount?: number; documentCount?: number; provenance?: unknown }
export interface InspectionRun { id: string; label: string; datasetId?: string; status?: string; provenance?: unknown }
export interface InspectionCatalog { datasets: InspectionDataset[]; runs: InspectionRun[]; readOnly?: boolean; dataBoundary?: string }
export interface InspectionPage<T = InspectionRecord> { items: T[]; page: number; pageSize: number; total: number; status?: string; stage?: string; available?: boolean; unavailableReason?: string; provenance?: unknown; scoreMeaning?: unknown; requestedCandidates?: number; finalRetrievalLimit?: unknown; actualRequest?: unknown; outputSchema?: unknown; profile?: unknown }
export interface InspectionDocument extends InspectionRecord { id: string; fileName: string; sourceHash?: string; contentType?: string; pageCount?: number; ocrUsed?: boolean; ocrPageCount?: number; status?: string }
export interface InspectionTask extends InspectionRecord { id: string; label?: string; query?: string; role?: string; stages?: string[]; status?: string }

function params(values: Record<string, string | number | undefined>) {
  const p = new URLSearchParams(); for (const [k, v] of Object.entries(values)) if (v !== undefined && v !== '') p.set(k, String(v)); return `?${p}`
}
const base = '/vetting-inspection'
const get = <T>(path: string) => api.get<T>(base + path, { timeout: 30000 })
export const inspectionApi = {
  catalog: () => get<InspectionCatalog>('/catalog'),
  chunks: (datasetId: string, page: number, pageSize: number, q = '', documentId = '', role = '') => get<InspectionPage>(`/chunks${params({ datasetId, page, pageSize, q, documentId, role })}`),
  chunk: (datasetId: string, id: string) => get<InspectionRecord>(`/chunks/${encodeURIComponent(id)}${params({ datasetId })}`),
  documents: (datasetId: string, page = 1, pageSize = 100) => get<InspectionPage<InspectionDocument>>(`/documents${params({ datasetId, page, pageSize })}`),
  pages: (datasetId: string, documentId: string, page: number, pageSize: number) => get<InspectionPage>(`/documents/${encodeURIComponent(documentId)}/pages${params({ datasetId, page, pageSize })}`),
  page: (datasetId: string, documentId: string, pageNo: number) => get<InspectionRecord>(`/documents/${encodeURIComponent(documentId)}/pages/${pageNo}${params({ datasetId })}`),
  tasks: (runId: string, page = 1, pageSize = 100) => get<InspectionPage<InspectionTask>>(`/runs/${encodeURIComponent(runId)}/tasks${params({ page, pageSize })}`),
  ranking: async (runId: string, taskId: string, stage: string, page: number, pageSize: number) => {
    const value = await get<InspectionRecord>(`/runs/${encodeURIComponent(runId)}/tasks/${encodeURIComponent(taskId)}${params({ stage, page, pageSize })}`)
    const stages = value.stages as Record<string, InspectionRecord> | undefined
    const section = stages?.[stage] ?? value
    return { ...section, query: value.query, role: value.role, provenance: value.provenance ?? section.provenance } as unknown as InspectionPage & InspectionRecord
  }
}

/** Additional display filtering; source content is never parsed as HTML or as JSON. */
export function inspectionJson(value: unknown): string {
  const secret = /^(api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|password|secret|credential|cookie|requestHeaders|responseHeaders)$/i
  return JSON.stringify(value, (key, v) => secret.test(key) ? '[redacted]' : v, 2) ?? ''
}
export function inspectionOriginalUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  try { const u = new URL(value, window.location.origin); return u.origin === window.location.origin && u.pathname.startsWith('/api/') ? u.href : null } catch { return null }
}
