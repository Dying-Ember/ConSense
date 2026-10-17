export type ReviewObject = Record<string, any>
export interface ReviewSource extends ReviewObject {
  id: string; sourceDocumentId: number | string; docId: string; editionId: string
  fileKey: string | null; fileName: string; format: 'docx' | 'pdf' | 'unsupported'
  sourceSha256: string; sourceHash?: string; analysisRevision?: string; parseStatus: string; canEdit: boolean
}
export interface ReviewParagraph { id: string; ordinal: number; text: string; paragraphSha256: string; sourceSha256: string }
export interface ReviewReading extends ReviewSource {
  paragraphs: ReviewParagraph[]; sourceMembers: ReviewObject[]; guidanceNotes: ReviewObject[]
  markerCandidates: ReviewObject[]; groups: ReviewObject[]; conditions: ReviewObject[]; issues: ReviewObject[]; blocks: ReviewObject[]
}
export interface ReviewSpan { paragraphId: string; paragraphSha256: string; start: number; end: number; expectedText: string; sourceSha256?: string; channel?: 'editableText' }
export interface ReviewAnchor extends ReviewObject { docId: string; text: string; sourceSha256: string; native?: ReviewSpan | { spans: ReviewSpan[] }; observation?: ReviewObject }
export interface ReviewVariable extends ReviewObject {
  id: string; nameZh: string; nameEn: string; type: ReviewObject; role: ReviewObject; status: string
  parentId?: string | null; docIds: string[]; options: ReviewObject[]; sourceAnchors: ReviewAnchor[]
  editTargets: ReviewObject[]; sourceUnitIds: string[]; relatedInputIds: string[]; relations: ReviewObject[]; targets: ReviewObject[]; evidence: ReviewObject[]
}
export interface ReviewState { variables: ReviewVariable[]; sourceReviews: Record<string, boolean>; events: ReviewObject[]; nextId: number; blank: boolean; guidanceMode: string; discoverySummary?: ReviewObject }
export interface ReviewGraph { kind: 'consense-template-graph-v1'; graph: ReviewState; sources: ReviewSource[]; selectedDocuments: Record<string, string> }
export interface ReviewRevision { id: string; parentRevisionId: string | null; status: 'DRAFT' | 'CONFIRMED'; graphSha256: string; graph: ReviewGraph; createdAt: string; confirmedAt?: string | null; sourceStale: boolean }
export type ReviewRevisionSummary = Omit<ReviewRevision, 'graph'>
export interface ReviewHead { currentRevisionId: string | null; draftRevisionId: string | null; current: ReviewRevision | null; draft: ReviewRevision | null; sources: ReviewSource[] }
export interface ReviewBootstrap { graph: ReviewGraph; sources: ReviewSource[] }
