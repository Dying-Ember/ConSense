import { sha256Bytes } from './sha256Bytes'

export interface FixedBaselineEvidenceAddress {
  evidenceBackend: 'fixed_development_baseline'
  parentId: string
  manifestSha256: string
  baselineVersion: string
  sourceFamily: string
  sourceRole: 'standard'
  originalSourceSha256: string
  originalPageNos?: number[]
  pageNo: number
  projectApplicability: string
  qualifiersComplete: string
  sourceQualityAccepted: boolean
  tableUrl?: string
}
export interface FixedBaselineEvidence {
  id: string
  fileLabel: string
  content: string
  pageNo: number
  sourceFamily: string
  payload: Record<string, unknown>
  sourceUnit: Record<string, unknown> | null
  tableArtifact: Record<string, unknown> | null
}
const sha = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
function requireValue(value: unknown, reason: string): asserts value { if (!value) throw new Error(reason) }
function object(value: unknown): Record<string, unknown> {
  requireValue(value && typeof value === 'object' && !Array.isArray(value), 'FIXED_EVIDENCE_INVALID')
  return value as Record<string, unknown>
}
export function fixedEvidencePath(address: FixedBaselineEvidenceAddress, kind: 'evidence' | 'original' | 'table'): string {
  requireValue(address.evidenceBackend === 'fixed_development_baseline' && address.sourceRole === 'standard'
    && typeof address.parentId === 'string' && address.parentId.length > 0 && address.parentId.length <= 512
    && sha(address.manifestSha256) && sha(address.originalSourceSha256)
    && typeof address.baselineVersion === 'string' && address.baselineVersion.length > 0, 'FIXED_EVIDENCE_ADDRESS_INVALID')
  return `/api/oer/fixed-baseline/${kind}?${kind === 'original' ? 'sourceSha256=' + encodeURIComponent(address.originalSourceSha256) : 'id=' + encodeURIComponent(address.parentId)}`
}
async function get(address: FixedBaselineEvidenceAddress, kind: 'evidence' | 'original' | 'table', signal?: AbortSignal) {
  const response = await fetch(fixedEvidencePath(address, kind), { method: 'GET', credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal })
  requireValue(response.ok, response.status === 409 ? 'FIXED_EVIDENCE_STALE' : response.status === 404 ? 'FIXED_EVIDENCE_UNAVAILABLE' : `HTTP_${response.status}`)
  return response
}
export async function fetchFixedEvidence(address: FixedBaselineEvidenceAddress, signal?: AbortSignal): Promise<FixedBaselineEvidence> {
  const response = await get(address, 'evidence', signal)
  const envelope = object(await response.json())
  requireValue(envelope.code === 0, 'FIXED_EVIDENCE_REJECTED')
  const data = object(envelope.data), payload = object(data.payload)
  const original = payload.originalSourceSha256 ?? payload.sourceHash
  requireValue(data.id === address.parentId && payload.fixedBaselineManifestSha256 === address.manifestSha256
    && payload.fixedBaselineVersion === address.baselineVersion && original === address.originalSourceSha256
    && payload.sourceRole === 'standard' && data.sourceFamily === address.sourceFamily, 'FIXED_EVIDENCE_IDENTITY_MISMATCH')
  requireValue(typeof data.content === 'string' && typeof data.fileLabel === 'string', 'FIXED_EVIDENCE_INVALID')
  return data as unknown as FixedBaselineEvidence
}
export async function fetchFixedOriginal(address: FixedBaselineEvidenceAddress, signal?: AbortSignal): Promise<Blob> {
  const response = await get(address, 'original', signal)
  requireValue(response.headers.get('X-Source-SHA256') === address.originalSourceSha256
    && response.headers.get('Content-Type')?.split(';')[0].trim() === 'application/pdf', 'FIXED_ORIGINAL_HEADER_MISMATCH')
  const bytes = await response.arrayBuffer(), magic = new Uint8Array(bytes)
  requireValue(magic.length >= 5 && [37,80,68,70,45].every((byte, index) => magic[index] === byte), 'FIXED_ORIGINAL_INVALID_PDF')
  requireValue((await sha256Bytes(bytes)).hex === address.originalSourceSha256, 'FIXED_ORIGINAL_SHA_MISMATCH')
  return new Blob([bytes], { type: 'application/pdf' })
}
export async function fetchFixedTable(address: FixedBaselineEvidenceAddress, artifact: Record<string, unknown>, signal?: AbortSignal): Promise<unknown> {
  requireValue(sha(artifact.sha256) && Number.isSafeInteger(artifact.bytes) && Number(artifact.bytes) >= 0, 'FIXED_TABLE_DESCRIPTOR_INVALID')
  const response = await get(address, 'table', signal), bytes = await response.arrayBuffer()
  requireValue(response.headers.get('X-Source-SHA256') === artifact.sha256
    && response.headers.get('Content-Type')?.split(';')[0].trim() === 'application/json'
    && bytes.byteLength === artifact.bytes && (await sha256Bytes(bytes)).hex === artifact.sha256, 'FIXED_TABLE_SHA_MISMATCH')
  const table = object(JSON.parse(new TextDecoder().decode(bytes)))
  requireValue(table.id === address.parentId && table.originalSourceSha256 === address.originalSourceSha256, 'FIXED_TABLE_IDENTITY_MISMATCH')
  return table
}
