import { sha256Bytes } from './sha256Bytes'

/** Read-only canonical adoption addresses. Retrieval episode unit IDs are not addresses here. */
export type CanonicalEvidenceModule = 'vetting' | 'advisory' | 'drafting'
export type NormalizedOriginalPageBox = [number, number, number, number]

export interface CanonicalEvidenceAddress {
  addressKind: 'canonical_adoption_unit'
  projectId: string
  documentId: string | number
  module: CanonicalEvidenceModule
  unitId: string
  canonicalChunkId: string
  originalSourceSha256: string
  javaParsedSourceHash: string
  sourceRevisionSha256: string
  canonicalSourceHash: string
  projectionSha256: string
  projectionRevisionSha256: string
  sourceFilename: string
  sourceOrder: number | null
  sourceRenderEvidenceIdentitySha256?: string
}

export interface CanonicalEvidencePart extends Record<string, unknown> {
  blockId: string
  text: string
  physicalPageNo?: number
  sourceSha256: string
  bbox?: NormalizedOriginalPageBox | null
  bboxConvention?: 'normalized_xyxy_original_page'
  sourcePosition?: NativeDocxPosition
  textTransform?: { textChannel: string; sourceStartUtf16: number; sourceEndUtf16: number; [key: string]: unknown }
  sourceOrder?: number | null
}

export interface NativeDocxPosition extends Record<string, unknown> {
  kind: 'docx_native'
  sourceSha256: string
  partName: string
  partSha256: string
  nodePath: string
}

export interface CanonicalEvidenceAsset extends Record<string, unknown> {
  assetId: string
  unitId: string
  sourceSha256: string
  sha256: string
  mediaType: 'image/png' | 'image/jpeg' | 'image/webp'
  byteCount: number
  physicalPages: number[]
  blockIds: string[]
  sourceRegions: Array<{ blockId: string; bbox: NormalizedOriginalPageBox | null; bboxConvention: 'normalized_xyxy_original_page' }>
  humanConfirmed: false
  artifactName?: string
  kind?: 'source_page_render'
  sourceRevisionSha256?: string
  projectionSha256?: string
  combinedEvidenceIdentitySha256?: string
  widthPixels?: number
  heightPixels?: number
}

export interface CanonicalEvidencePointer extends CanonicalEvidenceAddress, Record<string, unknown> {
  projectionSchema?: 'consense.canonical-projection.v1' | 'consense.canonical-projection.v2'
  producerKind?: string
  storageShape?: string
  originalGeometryStatus?: string
  visualReturnStatus?: string
  bboxConvention?: 'normalized_xyxy_original_page' | null
  humanConfirmed: false
  physicalPages: number[]
  eligibleModules: CanonicalEvidenceModule[]
  parts: CanonicalEvidencePart[]
  sourceSpans: Array<Record<string, unknown>>
  assetRefs: CanonicalEvidenceAsset[]
  tableGroup: ({ tableGroupId: string; physicalPages: number[] } & Record<string, unknown>) | null
  relations: Array<Record<string, unknown>>
  adoption: Record<string, unknown>
  sourceQuality: Record<string, unknown>
  sourcePageRenderRefs?: CanonicalEvidenceAsset[]
  sourceRenderCaptureCompleteness?: Record<string, unknown>
  sourceRenderLimitations?: unknown[]
  unknowns: unknown[]
}

export class CanonicalEvidenceError extends Error {
  constructor(public readonly reason: string, message = reason) { super(message); this.name = 'CanonicalEvidenceError' }
}

const shaFields = ['originalSourceSha256', 'javaParsedSourceHash', 'sourceRevisionSha256', 'canonicalSourceHash', 'projectionSha256', 'projectionRevisionSha256'] as const
const modules = ['vetting', 'advisory', 'drafting']
const convention = 'normalized_xyxy_original_page'
const nativeSchema = 'consense.canonical-projection.v2'
const nativeStorage = 'cas-native-observation-adopted-public-v1'
const nativeVisual = 'unavailable_pending_verified_rendition'
const nativeChannels = ['observedRaw', 'deleted', 'directStrike', 'nonDeletedNonDirectStrike']
const pdfPartAliases = ['pageNo', 'physicalPageNo', 'bbox', 'bboxConvention']
function requireValue(ok: unknown, reason: string): asserts ok { if (!ok) throw new CanonicalEvidenceError(reason) }
function object(value: unknown, reason: string): Record<string, unknown> {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value), reason)
  return value as Record<string, unknown>
}
function sha(value: unknown) { return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) }
function integer(value: unknown, minimum = 0): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum }
function segment(value: unknown) { return typeof value === 'string' && value !== '.' && value !== '..' && /^[A-Za-z0-9_.:-]{1,256}$/.test(value) }
function documentId(value: unknown): string {
  requireValue((integer(value, 1)) || (typeof value === 'string' && /^[1-9][0-9]*$/.test(value)), 'CANONICAL_DOCUMENT_ID_INVALID')
  return String(value)
}
function pages(value: unknown, reason: string): number[] {
  requireValue(Array.isArray(value) && value.length > 0 && value.every(page => integer(page, 1)) && new Set(value).size === value.length, reason)
  return value as number[]
}
function box(value: unknown, reason: string) {
  if (value === null) return
  requireValue(Array.isArray(value) && value.length === 4 && value.every(x => typeof x === 'number' && Number.isFinite(x)), reason)
  const [x0, y0, x1, y1] = value as number[]
  requireValue(0 <= x0 && x0 < x1 && x1 <= 1 && 0 <= y0 && y0 < y1 && y1 <= 1, reason)
}

export function isNativeEvidence(pointer: CanonicalEvidencePointer): boolean {
  return pointer.projectionSchema === nativeSchema && pointer.producerKind === 'native_docx'
}

function nativePosition(value: unknown, sourceSha256: string): NativeDocxPosition {
  const position = object(value, 'CANONICAL_NATIVE_POSITION_INVALID')
  requireValue(position.kind === 'docx_native' && position.sourceSha256 === sourceSha256 && sha(position.partSha256), 'CANONICAL_NATIVE_POSITION_INVALID')
  requireValue(typeof position.partName === 'string' && !/[\\\u0000-\u001f]/.test(position.partName)
    && position.partName.split('/').every(part => part.length > 0 && part !== '.' && part !== '..'), 'CANONICAL_NATIVE_PART_NAME_INVALID')
  requireValue(typeof position.nodePath === 'string' && /^(?:\/\{[^{}\s]+\}[A-Za-z_][A-Za-z0-9_.-]*\[[1-9][0-9]*\])+$/.test(position.nodePath), 'CANONICAL_NATIVE_NODE_PATH_INVALID')
  requireValue(pdfPartAliases.every(key => !Object.prototype.hasOwnProperty.call(position, key)), 'CANONICAL_NATIVE_PDF_ALIAS_FORBIDDEN')
  return position as NativeDocxPosition
}

function nativeReference(value: unknown, address: CanonicalEvidenceAddress): Record<string, unknown> {
  const reference = object(value, 'CANONICAL_NATIVE_REFERENCE_INVALID')
  requireValue(reference.sourceSha256 === address.originalSourceSha256 && reference.sourceRevisionSha256 === address.sourceRevisionSha256
    && sha(reference.observerIdentitySha256) && sha(reference.payloadSha256) && typeof reference.jsonPointer === 'string', 'CANONICAL_NATIVE_REFERENCE_INVALID')
  const descriptor = object(reference.artifactDescriptor, 'CANONICAL_NATIVE_REFERENCE_INVALID')
  requireValue(descriptor.schema === 'consense.artifact-descriptor.v1' && descriptor.storage === 'content_addressed_disk'
    && sha(descriptor.sha256) && integer(descriptor.byteCount, 1)
    && descriptor.relativePath === `sha256/${String(descriptor.sha256).slice(0, 2)}/${descriptor.sha256}.blob`, 'CANONICAL_NATIVE_REFERENCE_INVALID')
  return reference
}

function validateNativePointer(address: CanonicalEvidenceAddress, data: Record<string, unknown>): CanonicalEvidencePointer {
  requireValue(data.producerKind === 'native_docx' && data.storageShape === nativeStorage
    && data.originalGeometryStatus === 'not_defined_for_native_docx' && data.visualReturnStatus === nativeVisual
    && (data.bboxConvention === undefined || data.bboxConvention === null), 'CANONICAL_NATIVE_DISPLAY_CONTRACT_INVALID')
  requireValue(Array.isArray(data.physicalPages) && data.physicalPages.length === 0 && Array.isArray(data.assetRefs)
    && data.assetRefs.length === 0, 'CANONICAL_NATIVE_VISUAL_UNAVAILABLE')
  requireValue(Array.isArray(data.eligibleModules) && data.eligibleModules.includes(address.module), 'CANONICAL_MODULE_INELIGIBLE')
  requireValue(Array.isArray(data.parts) && data.parts.length > 0, 'CANONICAL_PARTS_INVALID')
  const blockIds: string[] = []
  for (const value of data.parts) {
    const part = object(value, 'CANONICAL_PART_INVALID')
    requireValue(typeof part.blockId === 'string' && /^native-[a-f0-9]{64}$/.test(part.blockId) && !blockIds.includes(part.blockId), 'CANONICAL_BLOCK_ID_INVALID')
    blockIds.push(part.blockId)
    requireValue(pdfPartAliases.every(key => !Object.prototype.hasOwnProperty.call(part, key)), 'CANONICAL_NATIVE_PDF_ALIAS_FORBIDDEN')
    requireValue(part.sourceSha256 === address.originalSourceSha256 && typeof part.text === 'string', 'CANONICAL_PART_SOURCE_MISMATCH')
    const position = nativePosition(part.sourcePosition, address.originalSourceSha256)
    const transform = object(part.textTransform, 'CANONICAL_NATIVE_TEXT_TRANSFORM_INVALID')
    const channels = object(part.nativeTextChannels, 'CANONICAL_NATIVE_TEXT_TRANSFORM_INVALID')
    requireValue(transform.version === 'native-channel-identity-utf16-v1' && transform.mode === 'identity'
      && nativeChannels.includes(transform.textChannel as string) && nativeChannels.every(channel => typeof channels[channel] === 'string')
      && channels[transform.textChannel as string] === part.text && transform.sourceStartUtf16 === 0
      && transform.sourceEndUtf16 === part.text.length && part.startOffset === 0 && part.endOffset === part.text.length, 'CANONICAL_NATIVE_TEXT_TRANSFORM_INVALID')
    const reference = nativeReference(part.nativeNodeRef, address)
    requireValue(reference.nativeNodeId === part.blockId && JSON.stringify(reference.sourcePosition) === JSON.stringify(position), 'CANONICAL_NATIVE_REFERENCE_INVALID')
    object(part.nativeEditingState, 'CANONICAL_NATIVE_EDIT_STATE_INVALID')
    requireValue(part.nativeSemanticQuality === 'unknown' && Array.isArray(part.nativeLimitations), 'CANONICAL_NATIVE_QUALITY_INVALID')
  }
  const adoption = object(data.adoption, 'CANONICAL_ADOPTION_INVALID')
  const tableGroup = data.tableGroup === undefined ? null : data.tableGroup
  requireValue(adoption.scope === 'region' || adoption.scope === 'table_group', 'CANONICAL_ADOPTION_INVALID')
  requireValue((adoption.scope === 'table_group') === (tableGroup !== null), 'CANONICAL_TABLE_GROUP_INVALID')
  if (tableGroup !== null) {
    const group = object(tableGroup, 'CANONICAL_TABLE_GROUP_INVALID')
    requireValue(typeof group.tableGroupId === 'string' && group.tableGroupId.length > 0 && group.visualReturnStatus === nativeVisual
      && Array.isArray(group.physicalPages) && group.physicalPages.length === 0, 'CANONICAL_TABLE_GROUP_INVALID')
    for (const key of ['rootTableIds', 'nativeTableIds', 'nativeParagraphIds']) {
      requireValue(Array.isArray(group[key]) && group[key].length > 0 && new Set(group[key]).size === group[key].length
        && group[key].every(id => typeof id === 'string' && /^native-[a-f0-9]{64}$/.test(id)), 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
    }
    requireValue(JSON.stringify(group.nativeParagraphIds) === JSON.stringify(blockIds)
      && (group.rootTableIds as string[]).every(id => (group.nativeTableIds as string[]).includes(id)), 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
    requireValue(Array.isArray(group.nativeTableTopology) && JSON.stringify(group.nativeTableTopology.map(value => object(value, 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID').tableId))
      === JSON.stringify(group.nativeTableIds), 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
    nativeReference(group.nativeTablesRef, address)
    for (const value of group.nativeTableTopology) {
      const table = object(value, 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
      requireValue(Array.isArray(table.rows) && table.rows.every(value => {
        const row = object(value, 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
        return Array.isArray(row.cells) && row.cells.every(cell => !!object(cell, 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID'))
      }), 'CANONICAL_NATIVE_TABLE_INVENTORY_INVALID')
    }
  }
  for (const field of ['sourceSpans', 'relations', 'unknowns']) requireValue(Array.isArray(data[field]), `CANONICAL_${field.toUpperCase()}_INVALID`)
  object(data.sourceQuality, 'CANONICAL_QUALITY_INVALID')
  return { ...data, tableGroup } as unknown as CanonicalEvidencePointer
}

export function validateCanonicalAddress(value: CanonicalEvidenceAddress): void {
  requireValue(value?.addressKind === 'canonical_adoption_unit', 'CANONICAL_ADOPTION_ADDRESS_REQUIRED')
  requireValue(segment(value.projectId) && segment(value.unitId) && typeof value.canonicalChunkId === 'string' && value.canonicalChunkId.length > 0, 'CANONICAL_ADDRESS_INVALID')
  documentId(value.documentId)
  requireValue(modules.includes(value.module), 'CANONICAL_MODULE_INVALID')
  requireValue(shaFields.every(field => sha(value[field])), 'CANONICAL_IDENTITY_INVALID')
  requireValue(typeof value.sourceFilename === 'string' && value.sourceFilename.length > 0, 'CANONICAL_SOURCE_FILENAME_REQUIRED')
  requireValue(value.sourceOrder === null || integer(value.sourceOrder), 'CANONICAL_SOURCE_ORDER_INVALID')
  requireValue(value.sourceRenderEvidenceIdentitySha256 === undefined || sha(value.sourceRenderEvidenceIdentitySha256), 'CANONICAL_SOURCE_RENDER_IDENTITY_INVALID')
}

/** Only this fixed relative route is used; response URL/backendUrl fields are never followed. */
export function canonicalEvidencePath(address: CanonicalEvidenceAddress, kind: 'units' | 'assets' | 'source-pages', id: string): string {
  validateCanonicalAddress(address)
  requireValue(segment(id), 'CANONICAL_ROUTE_ID_INVALID')
  if (kind === 'source-pages') requireValue(sha(address.sourceRenderEvidenceIdentitySha256), 'CANONICAL_SOURCE_RENDER_IDENTITY_REQUIRED')
  const extra = kind === 'source-pages' ? `&unitId=${encodeURIComponent(address.unitId)}&evidence=${address.sourceRenderEvidenceIdentitySha256}` : ''
  return `/api/evidence/${encodeURIComponent(address.projectId)}/documents/${documentId(address.documentId)}/canonical/${address.projectionSha256}/${kind}/${encodeURIComponent(id)}?module=${address.module}${extra}`
}

export function validateCanonicalPointer(address: CanonicalEvidenceAddress, value: unknown): CanonicalEvidencePointer {
  validateCanonicalAddress(address)
  const data = object(value, 'CANONICAL_POINTER_INVALID')
  for (const field of ['addressKind', 'projectId', 'module', 'unitId', 'canonicalChunkId', 'sourceFilename', 'sourceOrder', ...shaFields] as const) {
    requireValue(data[field] === address[field], `CANONICAL_IDENTITY_MISMATCH:${field}`)
  }
  requireValue(documentId(data.documentId) === documentId(address.documentId), 'CANONICAL_IDENTITY_MISMATCH:documentId')
  requireValue(data.humanConfirmed === false, 'CANONICAL_DISPLAY_CONTRACT_INVALID')
  if (data.projectionSchema === nativeSchema) {
    requireValue(data.sourcePageRenderRefs === undefined && data.sourceRenderEvidenceIdentitySha256 === undefined
      && data.sourceRenderCaptureCompleteness === undefined && data.sourceRenderLimitations === undefined
      && address.sourceRenderEvidenceIdentitySha256 === undefined, 'CANONICAL_NATIVE_SOURCE_PAGE_UNAVAILABLE')
    return validateNativePointer(address, data)
  }
  requireValue((data.projectionSchema === undefined || data.projectionSchema === 'consense.canonical-projection.v1')
    && (data.producerKind === undefined || data.producerKind === null) && data.storageShape === undefined
    && data.bboxConvention === convention, 'CANONICAL_DISPLAY_CONTRACT_INVALID')
  const sourcePages = pages(data.physicalPages, 'CANONICAL_PHYSICAL_PAGES_INVALID')
  requireValue(Array.isArray(data.eligibleModules) && data.eligibleModules.includes(address.module), 'CANONICAL_MODULE_INELIGIBLE')
  requireValue(Array.isArray(data.parts) && data.parts.length > 0, 'CANONICAL_PARTS_INVALID')
  const blockIds = new Set<string>()
  for (const value of data.parts) {
    const part = object(value, 'CANONICAL_PART_INVALID')
    requireValue(typeof part.blockId === 'string' && part.blockId.length > 0 && !blockIds.has(part.blockId), 'CANONICAL_BLOCK_ID_INVALID')
    blockIds.add(part.blockId)
    requireValue(part.sourceSha256 === address.originalSourceSha256 && sourcePages.includes(part.physicalPageNo as number), 'CANONICAL_PART_SOURCE_MISMATCH')
    requireValue(typeof part.text === 'string' && part.bboxConvention === convention, 'CANONICAL_PART_TEXT_OR_FRAME_INVALID')
    box(part.bbox, 'CANONICAL_PART_BBOX_INVALID')
    requireValue(part.sourceOrder === undefined || part.sourceOrder === null || integer(part.sourceOrder), 'CANONICAL_PART_SOURCE_ORDER_INVALID')
  }
  requireValue(Array.isArray(data.assetRefs), 'CANONICAL_ASSETS_INVALID')
  const partsById = new Map((data.parts as CanonicalEvidencePart[]).map(part => [part.blockId, part]))
  const assetIds = new Set<string>()
  for (const value of data.assetRefs) {
    const asset = object(value, 'CANONICAL_ASSET_INVALID')
    requireValue(typeof asset.assetId === 'string' && /^asset:[a-f0-9]{64}$/.test(asset.assetId) && !assetIds.has(asset.assetId), 'CANONICAL_ASSET_ID_INVALID')
    assetIds.add(asset.assetId)
    requireValue(asset.sourceSha256 === address.originalSourceSha256 && asset.unitId === address.unitId && sha(asset.sha256), 'CANONICAL_ASSET_SOURCE_MISMATCH')
    requireValue(['image/png', 'image/jpeg', 'image/webp'].includes(asset.mediaType as string) && integer(asset.byteCount, 1) && asset.humanConfirmed === false, 'CANONICAL_ASSET_CONTRACT_INVALID')
    const assetPages = pages(asset.physicalPages, 'CANONICAL_ASSET_PAGES_INVALID')
    requireValue(assetPages.every(page => sourcePages.includes(page)), 'CANONICAL_ASSET_PAGE_MISMATCH')
    requireValue(Array.isArray(asset.blockIds) && asset.blockIds.length > 0 && new Set(asset.blockIds).size === asset.blockIds.length
      && asset.blockIds.every(id => typeof id === 'string' && blockIds.has(id) && assetPages.includes(partsById.get(id)!.physicalPageNo!)), 'CANONICAL_ASSET_BLOCK_MISMATCH')
    requireValue(Array.isArray(asset.sourceRegions), 'CANONICAL_ASSET_REGIONS_INVALID')
    const regionIds = new Set<string>()
    for (const value of asset.sourceRegions) {
      const region = object(value, 'CANONICAL_ASSET_REGION_INVALID')
      requireValue(typeof region.blockId === 'string' && !regionIds.has(region.blockId)
        && asset.blockIds.includes(region.blockId) && region.bboxConvention === convention, 'CANONICAL_ASSET_REGION_MISMATCH')
      regionIds.add(region.blockId)
      box(region.bbox, 'CANONICAL_ASSET_BBOX_INVALID')
      requireValue(JSON.stringify(region.bbox) === JSON.stringify(partsById.get(region.blockId)!.bbox), 'CANONICAL_ASSET_REGION_BBOX_MISMATCH')
    }
  }
  if (data.sourcePageRenderRefs !== undefined || data.sourceRenderEvidenceIdentitySha256 !== undefined
    || data.sourceRenderCaptureCompleteness !== undefined || data.sourceRenderLimitations !== undefined
    || address.sourceRenderEvidenceIdentitySha256 !== undefined) {
    requireValue(Array.isArray(data.sourcePageRenderRefs) && data.sourcePageRenderRefs.length === sourcePages.length
      && sha(address.sourceRenderEvidenceIdentitySha256) && data.sourceRenderEvidenceIdentitySha256 === address.sourceRenderEvidenceIdentitySha256,
      'CANONICAL_SOURCE_RENDER_IDENTITY_OR_COMPLETENESS_INVALID')
    const seenPages = new Set<number>(), seenIds = new Set<string>()
    for (const value of data.sourcePageRenderRefs) {
      const asset = object(value, 'CANONICAL_SOURCE_PAGE_INVALID')
      const page = asset.physicalPageNo
      requireValue(asset.kind === 'source_page_render' && typeof asset.assetId === 'string' && /^source-render:[a-f0-9]{64}$/.test(asset.assetId)
        && !seenIds.has(asset.assetId) && asset.unitId === address.unitId && asset.sourceSha256 === address.originalSourceSha256
        && asset.sourceRevisionSha256 === address.sourceRevisionSha256 && asset.projectionSha256 === address.projectionSha256
        && asset.combinedEvidenceIdentitySha256 === address.sourceRenderEvidenceIdentitySha256 && sha(asset.sha256)
        && asset.mediaType === 'image/png' && integer(asset.byteCount, 1) && asset.humanConfirmed === false
        && integer(asset.widthPixels, 1) && integer(asset.heightPixels, 1)
        && integer(page, 1) && sourcePages.includes(page) && !seenPages.has(page)
        && Array.isArray(asset.physicalPages) && asset.physicalPages.length === 1 && asset.physicalPages[0] === page,
        'CANONICAL_SOURCE_PAGE_IDENTITY_INVALID')
      seenPages.add(page); seenIds.add(asset.assetId)
      const expectedBlocks = (data.parts as CanonicalEvidencePart[]).filter(part => part.physicalPageNo === page).map(part => part.blockId)
      requireValue(Array.isArray(asset.blockIds) && asset.blockIds.length === expectedBlocks.length && new Set(asset.blockIds).size === expectedBlocks.length
        && expectedBlocks.every(id => (asset.blockIds as unknown[]).includes(id)) && Array.isArray(asset.sourceRegions)
        && asset.sourceRegions.length === expectedBlocks.length, 'CANONICAL_SOURCE_PAGE_MEMBERSHIP_INVALID')
      const regionIds = new Set<string>()
      for (const value of asset.sourceRegions) {
        const region = object(value, 'CANONICAL_SOURCE_PAGE_REGION_INVALID')
        requireValue(typeof region.blockId === 'string' && expectedBlocks.includes(region.blockId) && !regionIds.has(region.blockId)
          && region.bboxConvention === convention && JSON.stringify(region.bbox) === JSON.stringify(partsById.get(region.blockId)!.bbox),
          'CANONICAL_SOURCE_PAGE_REGION_INVALID')
        regionIds.add(region.blockId)
      }
    }
    requireValue(sourcePages.every(page => seenPages.has(page)), 'CANONICAL_SOURCE_RENDER_INCOMPLETE_GROUP')
  }
  // Spring's NON_NULL serialization omits this optional field for region units.
  // Only absence is normalized; a declared whole table must still have a group.
  const adoption = object(data.adoption, 'CANONICAL_ADOPTION_INVALID')
  const tableGroup = data.tableGroup === undefined ? null : data.tableGroup
  requireValue(adoption.scope !== 'table_group' || tableGroup !== null, 'CANONICAL_TABLE_GROUP_INVALID')
  if (tableGroup !== null) {
    const group = object(tableGroup, 'CANONICAL_TABLE_GROUP_INVALID')
    requireValue(typeof group.tableGroupId === 'string' && group.tableGroupId.length > 0, 'CANONICAL_TABLE_GROUP_ID_INVALID')
    const groupPages = pages(group.physicalPages, 'CANONICAL_TABLE_GROUP_PAGES_INVALID')
    requireValue(groupPages.length === sourcePages.length && groupPages.every(page => sourcePages.includes(page)), 'CANONICAL_TABLE_GROUP_PAGE_MISMATCH')
  }
  for (const field of ['sourceSpans', 'relations', 'unknowns']) requireValue(Array.isArray(data[field]), `CANONICAL_${field.toUpperCase()}_INVALID`)
  object(data.sourceQuality, 'CANONICAL_QUALITY_INVALID')
  return { ...data, tableGroup } as unknown as CanonicalEvidencePointer
}

export function orderedEvidenceParts(pointer: CanonicalEvidencePointer): CanonicalEvidencePart[] {
  if (isNativeEvidence(pointer)) return [...pointer.parts]
  return pointer.parts.map((part, index) => ({ part, index })).sort((a, b) =>
    a.part.physicalPageNo! - b.part.physicalPageNo! || (a.part.sourceOrder ?? a.index) - (b.part.sourceOrder ?? b.index)
  ).map(row => row.part)
}

export function orderedEvidenceAssets(pointer: CanonicalEvidencePointer): CanonicalEvidenceAsset[] {
  const parts = orderedEvidenceParts(pointer)
  const rank = (asset: CanonicalEvidenceAsset) => Math.min(...asset.blockIds.map(id => parts.findIndex(part => part.blockId === id)).filter(index => index >= 0))
  return [...pointer.assetRefs, ...(pointer.sourcePageRenderRefs ?? [])].map((asset, index) => ({ asset, index })).sort((a, b) =>
    Math.min(...a.asset.physicalPages) - Math.min(...b.asset.physicalPages) || rank(a.asset) - rank(b.asset) || a.index - b.index
  ).map(row => row.asset)
}

export function evidenceCoverage(pointer: CanonicalEvidencePointer): { status: 'complete' | 'incomplete' | 'no_assets'; missingPages: number[]; missingBlocks: string[] } {
  const assets = [...pointer.assetRefs, ...(pointer.sourcePageRenderRefs ?? [])]
  const assetPages = new Set(assets.flatMap(asset => asset.physicalPages))
  const blocks = new Set(assets.flatMap(asset => asset.blockIds))
  const partPages = new Set(pointer.parts.map(part => part.physicalPageNo))
  const missingPages = pointer.physicalPages.filter(page => !assetPages.has(page) || !partPages.has(page))
  const missingBlocks = pointer.tableGroup ? pointer.parts.filter(part => !blocks.has(part.blockId)).map(part => part.blockId) : []
  return { status: assets.length === 0 ? 'no_assets' : (missingPages.length || missingBlocks.length) ? 'incomplete' : 'complete', missingPages, missingBlocks }
}

async function get(address: CanonicalEvidenceAddress, kind: 'units' | 'assets' | 'source-pages', id: string, signal?: AbortSignal): Promise<Response> {
  const response = await fetch(canonicalEvidencePath(address, kind, id), { method: 'GET', credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal })
  if (!response.ok) {
    let message: unknown
    try { message = (await response.json())?.message } catch { /* Non-JSON failures retain the HTTP status. */ }
    const known = response.status === 404 ? ['CANONICAL_BINDING_UNAVAILABLE']
      : response.status === 409 ? ['CANONICAL_SOURCE_STALE', 'SOURCE_ROUTING_STALE', 'SOURCE_ORIGINAL_BYTES_CHANGED'] : []
    if (typeof message === 'string' && known.includes(message)) throw new CanonicalEvidenceError(message)
    throw new CanonicalEvidenceError(`HTTP_${response.status}`, `证据接口返回 HTTP ${response.status}`)
  }
  return response
}

export async function fetchCanonicalPointer(address: CanonicalEvidenceAddress, signal?: AbortSignal): Promise<CanonicalEvidencePointer> {
  const response = await get(address, 'units', address.unitId, signal)
  const envelope = object(await response.json(), 'CANONICAL_RESPONSE_INVALID')
  requireValue(envelope.code === 0, 'CANONICAL_RESPONSE_REJECTED:' + String(envelope.message ?? 'unknown'))
  return validateCanonicalPointer(address, envelope.data)
}

export interface CanonicalAssetBytes { blob: Blob; browserShaVerification: 'verified'; browserShaImplementation: 'webcrypto' | 'portable' }
export async function fetchCanonicalAsset(address: CanonicalEvidenceAddress, asset: CanonicalEvidenceAsset, signal?: AbortSignal): Promise<CanonicalAssetBytes> {
  const sourcePage = asset.kind === 'source_page_render'
  requireValue(asset.sourceSha256 === address.originalSourceSha256 && asset.unitId === address.unitId && (sourcePage ? /^source-render:[a-f0-9]{64}$/ : /^asset:[a-f0-9]{64}$/).test(asset.assetId) && sha(asset.sha256), 'CANONICAL_ASSET_ADDRESS_MISMATCH')
  if (sourcePage) requireValue(sha(address.sourceRenderEvidenceIdentitySha256) && asset.sourceRevisionSha256 === address.sourceRevisionSha256 && asset.projectionSha256 === address.projectionSha256 && asset.combinedEvidenceIdentitySha256 === address.sourceRenderEvidenceIdentitySha256, 'CANONICAL_SOURCE_RENDER_IDENTITY_MISMATCH')
  const response = await get(address, sourcePage ? 'source-pages' : 'assets', asset.assetId, signal)
  if (sourcePage) requireValue(response.headers.get('X-Evidence-Identity-Sha256') === address.sourceRenderEvidenceIdentitySha256
    && response.headers.get('X-Source-Revision-Sha256') === address.sourceRevisionSha256, 'CANONICAL_SOURCE_RENDER_HEADER_IDENTITY_MISMATCH')
  const mediaType = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase()
  requireValue(response.headers.get('X-Source-Sha256') === address.originalSourceSha256 && response.headers.get('X-Projection-Sha256') === address.projectionSha256, 'CANONICAL_ASSET_HEADER_IDENTITY_MISMATCH')
  requireValue(response.headers.get('ETag') === `"${asset.sha256}"` && mediaType === asset.mediaType, 'CANONICAL_ASSET_HEADER_CONTENT_MISMATCH')
  const bytes = await response.arrayBuffer()
  requireValue(bytes.byteLength === asset.byteCount, 'CANONICAL_ASSET_SIZE_MISMATCH')
  const view = new Uint8Array(bytes)
  const magic = asset.mediaType === 'image/png' ? view.length >= 8 && [137,80,78,71,13,10,26,10].every((byte, index) => view[index] === byte)
    : asset.mediaType === 'image/jpeg' ? view.length >= 3 && view[0] === 255 && view[1] === 216 && view[2] === 255
      : view.length >= 12 && [82,73,70,70].every((byte,index) => view[index] === byte) && [87,69,66,80].every((byte,index) => view[index+8] === byte)
  requireValue(magic, 'CANONICAL_ASSET_MAGIC_MISMATCH')
  const digest = await sha256Bytes(bytes)
  requireValue(digest.hex === asset.sha256, 'CANONICAL_ASSET_BYTES_SHA_MISMATCH')
  return { blob: new Blob([bytes], { type: asset.mediaType }), browserShaVerification: 'verified', browserShaImplementation: digest.implementation }
}
