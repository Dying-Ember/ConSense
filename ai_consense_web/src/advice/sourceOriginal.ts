import type { Citation } from '@/api/types'
import { fetchCanonicalPointer, type CanonicalEvidencePointer } from '@/api/canonicalEvidence'
import { fetchFixedOriginal, fetchFixedEvidence } from '@/api/fixedBaselineEvidence'
import { sha256Bytes } from '@/api/sha256Bytes'

export interface AdviceOriginal {
  bytes: ArrayBuffer
  sha256: string
  filename: string
  type: 'pdf' | 'docx' | 'unsupported'
  pointers: CanonicalEvidencePointer[]
  pages: number[]
}
function requireValue(value: unknown, reason: string): asserts value { if (!value) throw new Error(reason) }
const sha = (value: unknown): value is string => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)

/** No filename selection: fixed and project addresses keep separate identities even when IDs collide. */
export async function loadAdviceOriginal(projectId: string, citation: Citation, scope: string, signal?: AbortSignal): Promise<AdviceOriginal> {
  const fixed = citation.fixedBaselineEvidence, addresses = citation.canonicalEvidence ?? []
  requireValue(!(fixed && addresses.length), 'ADVICE_ORIGINAL_AMBIGUOUS_SOURCE')
  if (fixed) {
    // This call first checks the retained baseline manifest and the exact evidence member.
    const evidence = await fetchFixedEvidence(fixed, signal)
    requireValue(evidence.pageNo === fixed.pageNo, 'ADVICE_ORIGINAL_FIXED_PAGE_MISMATCH')
    const actualPages = evidence.payload.originalPageNos
    requireValue(!fixed.originalPageNos || JSON.stringify(fixed.originalPageNos) === JSON.stringify(actualPages), 'ADVICE_ORIGINAL_FIXED_PAGE_MISMATCH')
    const blob = await fetchFixedOriginal(fixed, signal)
    return { bytes: await blob.arrayBuffer(), sha256: fixed.originalSourceSha256, filename: citation.fileLabel, type: 'pdf', pointers: [],
      pages: Array.isArray(actualPages) && actualPages.length ? actualPages.map(Number) : [evidence.pageNo] }
  }
  requireValue(projectId && ['fullset', 'tender', 'contract'].includes(scope), 'ADVICE_ORIGINAL_SCOPE_INVALID')
  const first = addresses[0]
  const digest = first?.originalSourceSha256 ?? (citation as Citation & { originalSourceSha256?: string }).originalSourceSha256
  const sourceHash = first?.javaParsedSourceHash ?? citation.sourceHash
  const documentId = first?.documentId ?? citation.documentId
  requireValue(sha(digest) && sha(sourceHash) && /^[1-9][0-9]*$/.test(String(documentId))
    && ['tender', 'standard', 'project_fact', 'package_manifest'].includes(citation.sourceRole ?? ''), 'ADVICE_ORIGINAL_IDENTITY_UNAVAILABLE')
  requireValue(!first || (first.projectId === projectId && first.module === 'advisory'
    && (!citation.documentId || String(first.documentId) === String(citation.documentId))
    && (!citation.sourceHash || first.javaParsedSourceHash === citation.sourceHash)), 'ADVICE_ORIGINAL_ADDRESS_MISMATCH')
  requireValue(addresses.every(address => address.projectId === projectId && address.module === 'advisory'
    && String(address.documentId) === String(documentId) && address.originalSourceSha256 === digest
    && address.javaParsedSourceHash === sourceHash && address.projectionSha256 === first.projectionSha256), 'ADVICE_ORIGINAL_ADDRESS_MISMATCH')
  const pointers: CanonicalEvidencePointer[] = []
  for (const address of addresses) pointers.push(await fetchCanonicalPointer(address, signal))
  const query = new URLSearchParams({ scope, sourceHash, originalSourceSha256: digest, sourceRole: citation.sourceRole! })
  if (first) { query.set('projectionSha256', first.projectionSha256); query.set('unitId', first.unitId) }
  const response = await fetch(`/api/advice/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(String(documentId))}/original?${query}`, {
    credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal
  })
  if (!response.ok) {
    let message: string | undefined
    try { message = (await response.json())?.message } catch { /* Retain HTTP status for non-JSON failures. */ }
    throw new Error(message || `ADVICE_ORIGINAL_HTTP_${response.status}`)
  }
  requireValue(response.headers.get('X-Source-SHA256') === digest && response.headers.get('X-Source-Revision') === sourceHash, 'ADVICE_ORIGINAL_HEADER_MISMATCH')
  const bytes = await response.arrayBuffer()
  requireValue((await sha256Bytes(bytes)).hex === digest, 'ADVICE_ORIGINAL_BYTES_MISMATCH')
  const media = response.headers.get('Content-Type')?.split(';')[0].toLowerCase(), magic = new Uint8Array(bytes)
  const pdf = magic.length >= 5 && [37, 80, 68, 70, 45].every((byte, index) => magic[index] === byte)
  const docx = magic.length >= 4 && [80, 75, 3, 4].every((byte, index) => magic[index] === byte)
    && media === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  requireValue(media !== 'application/pdf' || pdf, 'ADVICE_ORIGINAL_PDF_INVALID')
  const pages = [...new Set(pointers.flatMap(pointer => pointer.physicalPages))]
  // Only a native pointer or declared physical page identifies a page, never a printed footer/anchor.
  if (!pages.length && !pointers.length && /^[1-9][0-9]*$/.test(citation.pageNo ?? '')) pages.push(Number(citation.pageNo))
  return { bytes, sha256: digest, filename: first?.sourceFilename ?? citation.fileLabel,
    type: pdf ? 'pdf' : docx ? 'docx' : 'unsupported', pointers, pages }
}
