import type { DraftDocumentBinding, DraftDocumentBindings, DraftBindingPoint } from '@/api/types'

export async function pdfSha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

export function matchesBindingPdf(bundle: DraftDocumentBindings, fileKey: string, view: 'source' | 'result', pdfHash: string): boolean {
  return bundle.fileKey === fileKey && bundle.view === view && !!pdfHash && bundle.pdfSha256 === pdfHash && !!bundle.renderProfileHash
}

export interface PdfBindingLocation { bindingId: string; label: string; geometry: DraftBindingPoint; text?: string | null; highlight?: boolean }
export function bindingPoint(binding: DraftDocumentBinding): DraftBindingPoint | undefined {
  const point = binding.geometry
  if (binding.locationStatus !== 'exact' || binding.geometryStatus !== 'ready' || !point || point.kind !== 'point' || point.unit !== 'pt' || point.origin !== 'top-left' || point.pageBox !== 'crop' || point.rotation !== 0) return
  if (![point.pageNumber, point.x, point.y, point.pageWidth, point.pageHeight].every(Number.isFinite) || !Number.isInteger(point.pageNumber) || point.pageNumber < 1 || point.pageWidth <= 0 || point.pageHeight <= 0 || point.x < 0 || point.y < 0 || point.x > point.pageWidth || point.y > point.pageHeight) return
  return point
}
