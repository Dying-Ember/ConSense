import { api } from './client'
import type { FixedBaselineEvidence } from './fixedBaselineEvidence'
import { reviewSourceBindings } from './source-locations'
import type { ReviewBootstrap, ReviewGraph, ReviewHead, ReviewReading, ReviewRevision, ReviewRevisionSummary } from '@/drafting/source-review-types'
import type { DraftAutomationBinding, DraftAutomationRun } from '@/drafting/automation-types'
import type {
  AskResponse,
  AdviceSourceDocument,
  AdviceSourcePreparation,
  AdviceSession,
  AdviceBasisScope,
  DocumentScope,
  VettingChecklist,
  VettingChecklistItem,
  ChatMessage,
  DraftDocument,
  DraftDocumentBindings,
  DraftBodyPatch,
  DraftProgress,
  DraftVariable,
  DraftReviewCandidates,
  DraftVariablePatch,
  DraftIdentityConfirmation,
  DraftCatalog,
  DraftPlan,
  EvidenceBundle,
  EvidenceItem,
  ExtractTrace,
  ExtractRunSummary,
  Finding,
  FindingReviewUpdate,
  FindingWriteBinding,
  IndexStatus,
  Project,
  PromptSaveRequest,
  PromptTemplate,
  QuickQuestion,
  SkillDoc,
  SystemHealth,
  LlmProfiles,
  LlmSelection,
  TemplateItem,
  TemplateReading,
  UploadResult,
  VariableCreateRequest,
  VettingFile,
  VettingSourceRoleUpdate,
  VettingMetrics,
  VettingRunResult,
  VettingJob,
  VettingProgress,
  VettingReportFormat,
  VettingSourceRole
} from './types'

export * from './types'
export { api, ApiError, setApiErrorReporter, captureLlmSelection, setLlmProfileResolver } from './client'

/* ------------------------------------------------------------ 项目 */

export interface FixedSlSource { editionId: string; sha256: string; label?: string }
export interface FixedReferenceSelection { manifestSha256: string; slSources: FixedSlSource[] }
export interface FixedReferenceSelectionStatus { manifestSha256: string; availableSlSources: FixedSlSource[]; selection: FixedReferenceSelection | null }
export const fixedReferenceApi = {
  status: () => api.get<{ configured: boolean }>('/oer/fixed-baseline/status', { reportError: false }),
  selection: (projectId: string) => api.get<FixedReferenceSelectionStatus>(`/oer/fixed-baseline/projects/${encodeURIComponent(projectId)}/selection`, { reportError: false }),
  save: (projectId: string, selection: FixedReferenceSelection) => api.put<FixedReferenceSelectionStatus>(`/oer/fixed-baseline/projects/${encodeURIComponent(projectId)}/selection`, selection, { reportError: false }),
  search: (projectId: string, module: 'vetting' | 'advisory', query: string) => api.post<FixedBaselineEvidence[]>('/oer/fixed-baseline/search', { projectId, module, query, limit: 10 }, { reportError: false, timeout: 120000 })
}

export interface SlLibraryVersion {
  editionId: string; label?: string; technicalPages?: number | null; parsedPages?: number | null
  clauseCount?: number | null; indexedChunks?: number | null; status: string
}
export interface SlLibraryStatus {
  versions: SlLibraryVersion[]
  job: { status: string; stage?: string; currentEdition?: string | null; processedPages?: number | null; totalPages?: number | null; message?: string }
  retrievalAvailable: boolean
}
export const slLibraryApi = {
  status: () => api.get<SlLibraryStatus>('/oer/sl-library/status', { reportError: false }),
  build: () => api.post<unknown>('/oer/sl-library/build', {}, { reportError: false, timeout: 20000 })
}

export const projectApi = {
  list: () => api.get<Project[]>('/projects'),
  get: (id: string) => api.get<Project>(`/projects/${id}`),
  save: (body: Partial<Project> & { id: string; nameZhHans: string; nameZhHant?: string; nameEn?: string }) =>
    api.post<Project>('/projects', body),
  remove: (id: string) => api.delete<void>(`/projects/${id}`)
}

/* ------------------------------------------------------------ 起草 */

export const draftingApi = {
  automationStart: (projectId: string, body: DraftAutomationBinding, llmSelection: LlmSelection) => api.post<DraftAutomationRun>(`/drafting/${projectId}/graph/automation`, body, { timeout: 30000, llmSelection, reportError: false }),
  automationLatest: (projectId: string, signal?: AbortSignal) => api.get<DraftAutomationRun | null>(`/drafting/${projectId}/graph/automation/latest`, { timeout: 30000, signal, llmSelection: { profileId: null }, reportError: false }),
  automationGet: (projectId: string, runId: string, signal?: AbortSignal) => api.get<DraftAutomationRun>(`/drafting/${projectId}/graph/automation/${encodeURIComponent(runId)}`, { timeout: 30000, signal, llmSelection: { profileId: null }, reportError: false }),
  automationResume: (projectId: string, runId: string, body: DraftAutomationBinding, llmSelection: LlmSelection) => api.post<DraftAutomationRun>(`/drafting/${projectId}/graph/automation/${encodeURIComponent(runId)}/resume`, body, { timeout: 30000, llmSelection, reportError: false }),
  automationCancel: (projectId: string, runId: string) => api.post<DraftAutomationRun>(`/drafting/${projectId}/graph/automation/${encodeURIComponent(runId)}/cancel`, undefined, { timeout: 30000, llmSelection: { profileId: null }, reportError: false }),
  confirmIdentity: (projectId: string, body: DraftIdentityConfirmation) => api.post<Project>(`/drafting/${projectId}/identity/confirm`, body, { reportError: false }),
  reviewHead: (projectId: string) => api.get<ReviewHead>(`/drafting/${projectId}/graph`),
  reviewBootstrap: (projectId: string) => api.post<ReviewBootstrap>(`/drafting/${projectId}/graph/bootstrap`),
  reviewExtract: (projectId: string, revisionId: string, llmSelection?: LlmSelection) => api.post<DraftVariable[]>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}/extract`, undefined, { timeout: 7200000, llmSelection }),
  reviewDiscover: (projectId: string, revisionId: string, expectedCurrentRevisionId: string | null, maxItems: number, llmSelection?: LlmSelection) => api.post<ReviewRevision>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}/discover`, { expectedCurrentRevisionId, maxItems }, { timeout: 7200000, llmSelection }),
  reviewCandidates: (projectId: string, revisionId: string) => api.get<DraftReviewCandidates>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}/candidates`, { reportError: false }),
  reviewCorrectCandidate: (projectId: string, revisionId: string, key: string, body: { expectedRunId: string; candidateIndex: number; value: string; sourceQuote: string; targetKey?: string }) => api.post<DraftReviewCandidates>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}/candidates/${encodeURIComponent(key)}`, body),
  reviewSave: (projectId: string, graph: ReviewGraph, expectedCurrentRevisionId: string | null, expectedDraftRevisionId: string | null) => api.post<ReviewRevision>(`/drafting/${projectId}/graph`, { graph, expectedCurrentRevisionId, expectedDraftRevisionId }),
  reviewConfirm: (projectId: string, revisionId: string, expectedCurrentRevisionId: string | null) => api.post<ReviewRevision>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}/confirm`, { expectedCurrentRevisionId }),
  reviewRevisions: (projectId: string) => api.get<ReviewRevisionSummary[]>(`/drafting/${projectId}/graph/revisions`),
  reviewRevision: (projectId: string, revisionId: string) => api.get<ReviewRevision>(`/drafting/${projectId}/graph/revisions/${encodeURIComponent(revisionId)}`),
  reviewReading: (projectId: string, sourceId: string) => api.get<ReviewReading>(`/drafting/${projectId}/graph/sources/${encodeURIComponent(sourceId)}/reading`),
  reviewSource: (projectId: string, sourceId: string, sourceSha256?: string) => api.blob(`/drafting/${projectId}/graph/sources/${encodeURIComponent(sourceId)}/original${sourceSha256 ? `?sourceSha256=${encodeURIComponent(sourceSha256)}` : ''}`, undefined, false),
  reviewPreview: (projectId: string, sourceId: string, sourceSha256: string) => api.blob(`/drafting/${projectId}/graph/sources/${encodeURIComponent(sourceId)}/preview.pdf?sourceSha256=${encodeURIComponent(sourceSha256)}`, undefined, false),
  reviewSourceBindings,
  templateEvidence: (projectId: string, fileKey: string) => api.get<Record<string, unknown>>(`/drafting/${projectId}/templates/${fileKey}/evidence`, { reportError: false }),
  catalog: (projectId: string) => api.get<DraftCatalog>(`/drafting/${projectId}/catalog`),
  baselineCatalog: (projectId: string) => api.get<DraftCatalog>(`/drafting/${projectId}/baseline-catalog`),
  plan: (projectId: string, values?: Record<string, string>) => values
    ? api.post<DraftPlan>(`/drafting/${projectId}/plan`, { values })
    : api.get<DraftPlan>(`/drafting/${projectId}/plan`),
  templates: (projectId: string) => api.get<TemplateItem[]>(`/drafting/${projectId}/templates`),
  templatePreview: (projectId: string, fileKey: string, sourceSha256?: string) =>
    api.blob(`/drafting/${projectId}/templates/${fileKey}/preview.pdf${sourceSha256 ? `?sourceSha256=${encodeURIComponent(sourceSha256)}` : ''}`),
  templateBindings: (projectId: string, fileKey: string, sourceSha256?: string) =>
    api.get<DraftDocumentBindings>(`/drafting/${projectId}/templates/${fileKey}/bindings${sourceSha256 ? `?sourceSha256=${encodeURIComponent(sourceSha256)}` : ''}`, { reportError: false }),
  documentBindings: (projectId: string, fileKey: string, revisionId: string, docxSha256: string) =>
    api.get<DraftDocumentBindings>(`/drafting/${projectId}/documents/${fileKey}/bindings?revisionId=${encodeURIComponent(revisionId)}&docxSha256=${encodeURIComponent(docxSha256)}`, { reportError: false }),
  templateSource: (projectId: string, fileKey: string) =>
    api.blob(`/drafting/${projectId}/templates/${fileKey}/source`, undefined, false),
  templateReading: (projectId: string, fileKey: string) =>
    api.get<TemplateReading>(`/drafting/${projectId}/templates/${fileKey}/reading`, { reportError: false }),
  getTemplateText: (projectId: string, fileKey: string) =>
    api.get<{ text: string }>(`/drafting/${projectId}/templates/${fileKey}/text`),
  updateTemplateText: (projectId: string, fileKey: string, text: string) =>
    api.put<{ text: string }>(`/drafting/${projectId}/templates/${fileKey}/text`, { text }),
  uploadTemplates: (projectId: string, files: File[]) =>
    api.upload<UploadResult>(`/drafting/${projectId}/templates/upload`, files),
  replaceTemplate: (projectId: string, fileKey: string, file: File) =>
    api.uploadOne<UploadResult>(`/drafting/${projectId}/templates/${fileKey}/replace`, file),

  inputs: (projectId: string) => api.get<EvidenceItem[]>(`/drafting/${projectId}/inputs`),
  deleteInput: (projectId: string, id: number) => api.delete<void>(`/drafting/${projectId}/inputs/${id}`),
  uploadInputs: (projectId: string, files: File[]) =>
    api.upload<UploadResult>(`/drafting/${projectId}/inputs/upload`, files),

  variables: (projectId: string) => api.get<DraftVariable[]>(`/drafting/${projectId}/variables`),
  extract: (projectId: string, llmSelection?: LlmSelection) =>
    // Local reasoning can take over an hour across a full correspondence pack.
    api.post<DraftVariable[]>(`/drafting/${projectId}/variables/extract`, undefined, { timeout: 7200000, llmSelection }),
  extractTrace: (projectId: string) =>
    api.get<ExtractTrace | null>(`/drafting/${projectId}/variables/extract-trace`),
  extractTraces: (projectId: string, limit = 20) =>
    api.get<ExtractRunSummary[]>(`/drafting/${projectId}/variables/extract-traces?limit=${Math.min(20, Math.max(1, limit))}`),
  extractRun: (projectId: string, runId: string) =>
    api.get<ExtractTrace>(`/drafting/${projectId}/variables/extract-traces/${encodeURIComponent(runId)}`),
  updateVariable: (
    projectId: string,
    key: string,
    patch: DraftVariablePatch
  ) => api.put<DraftVariable>(`/drafting/${projectId}/variables/${key}`, patch),
  createVariable: (projectId: string, body: VariableCreateRequest) =>
    api.post<DraftVariable>(`/drafting/${projectId}/variables`, body),
  confirmAll: (projectId: string, scope: 'INPUT' | 'BASE' | 'FILE' = 'INPUT', fileKey?: string) =>
    api.post<DraftVariable[]>(
      `/drafting/${projectId}/variables/confirm-all?scope=${scope}${fileKey ? `&fileKey=${fileKey}` : ''}`
    ),

  progress: (projectId: string) => api.get<DraftProgress>(`/drafting/${projectId}/progress`),
  generate: (projectId: string, llmSelection?: LlmSelection) =>
    // Full SCC templates are generated in many model calls; keep the request alive.
    api.post<DraftDocument[]>(`/drafting/${projectId}/generate?lang=en`, undefined, { timeout: 3600000, llmSelection }),
  documents: (projectId: string) => api.get<DraftDocument[]>(`/drafting/${projectId}/documents`),
  documentSource: (projectId: string, fileKey: string, revisionId: string) =>
    api.blob(`/drafting/${projectId}/documents/${fileKey}/source?revisionId=${encodeURIComponent(revisionId)}`, undefined, false),
  updateDocument: (projectId: string, fileKey: string, patch: DraftBodyPatch) =>
    api.put<DraftDocument>(`/drafting/${projectId}/documents/${fileKey}`, patch),
  refreshToc: (projectId: string, fileKey: string, head: { revisionId: string; docxSha256: string }) => api.post<{ document: DraftDocument; tocFinalization: { status: string; reason?: string; headingFlowStatus?: string; headingFlowReason?: string }; updated: boolean }>(`/drafting/${projectId}/documents/${fileKey}/refresh-toc`, head, { timeout: 300000, llmSelection: { profileId: null }, reportError: false }),
  previewDocument: (projectId: string, fileKey: string, revisionId?: string) =>
    api.blob(`/drafting/${projectId}/documents/${fileKey}/preview.pdf${revisionId ? `?revisionId=${encodeURIComponent(revisionId)}` : ''}`),
  exportDocument: (projectId: string, fileKey: string, format: 'docx' | 'pdf', revisionId?: string) =>
    api.download(`/drafting/${projectId}/documents/${fileKey}/export.${format}${revisionId ? `?revisionId=${encodeURIComponent(revisionId)}` : ''}`, `ConSense_${fileKey}.${format}`),
  download: (projectId: string, fileKey: string, fallbackName: string) =>
    api.download(`/drafting/${projectId}/documents/${fileKey}/download`, fallbackName)
}

/* ------------------------------------------------------------ 审查 */

export interface VettingSourceReviewFile extends VettingFile { businessContentSha256?: string | null }
export interface VettingParsedTextPage {
  documentId: string; fileName: string; parseStatus: string
  originalSourceSha256: string; routingSha256: string; businessContentSha256: string; textContentSha256: string
  physicalPage: number | null; pageCount: number; textPart?: number; textPartCount?: number
  status: 'saved_text' | 'no_saved_text' | 'detailed_parsing_not_started'
  pageNumberAuthority: 'saved_parser_physical_page_markers' | 'unpaginated_saved_text'
  text: string; semanticQuality: 'unknown'; canonicalAdoption: 'not_performed'
}
export interface VettingParsedTable {
  method: string; physicalPage: number; rowCount: number; colCount: number
  cells: Array<{ row: number; col: number; rowSpan: number; colSpan: number; text: string }>
  continuationStatus: 'unknown'
}
export interface VettingParsedTablesPage {
  documentId: string; physicalPage: number
  originalSourceSha256: string; routingSha256: string; businessContentSha256: string
  tableStatus: 'saved_tables' | 'no_saved_tables' | 'unavailable' | 'not_supported'
  tables: VettingParsedTable[]
  semanticQuality: 'unknown'; canonicalAdoption: 'not_performed'
}

export const vettingApi = {
  files: (projectId: string) => api.get<VettingSourceReviewFile[]>(`/vetting/${projectId}/files`),
  parsedText: (projectId: string, documentId: string, pins: { originalSourceSha256: string; routingSha256: string; businessContentSha256: string }, page: number) =>
    api.get<VettingParsedTextPage>(`/vetting/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(documentId)}/parsed-text?${new URLSearchParams({ ...pins, page: String(page) })}`, { reportError: false }),
  parsedTables: (projectId: string, documentId: string, pins: { originalSourceSha256: string; routingSha256: string; businessContentSha256: string }, page: number) =>
    api.get<VettingParsedTablesPage>(`/vetting/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(documentId)}/parsed-tables?${new URLSearchParams({ ...pins, page: String(page) })}`, { reportError: false }),
  sourceProgress: (projectId: string) => api.get<import('./types').VettingSourceProgress[]>(`/vetting/${projectId}/sources/progress`),
  confirmSourceRole: (projectId: string, documentId: string, request: VettingSourceRoleUpdate) =>
    api.put<VettingFile>(`/vetting/${encodeURIComponent(projectId)}/sources/${encodeURIComponent(documentId)}/role`, request, request.confirmTextContractMaterial ? { timeout: 7_200_000 } : undefined),
  uploadPackage: (projectId: string, files: File[], sourceRole?: VettingSourceRole, documentScope?: DocumentScope) => {
    const params = new URLSearchParams()
    if (sourceRole) params.set('sourceRole', sourceRole)
    if (documentScope && documentScope !== 'auto') params.set('documentScope', documentScope)
    return api.upload<UploadResult>(`/vetting/${projectId}/package/upload${params.size ? `?${params}` : ''}`, files, 7_200_000)
  },
  run: (projectId: string, lang: string) =>
    api.post<VettingRunResult>(`/vetting/${projectId}/run?lang=${encodeURIComponent(lang)}`),
  startRun: (projectId: string, lang: string, llmSelection?: LlmSelection) =>
    api.post<VettingJob>(`/vetting/${projectId}/runs?lang=${encodeURIComponent(lang)}`, undefined, { timeout: 300000, llmSelection }),
  latestRun: (projectId: string) =>
    api.get<VettingJob | null>(`/vetting/${projectId}/runs/latest`, { timeout: 15000 }),
  getRun: (projectId: string, id: string) =>
    api.get<VettingJob>(`/vetting/${projectId}/runs/${encodeURIComponent(id)}`, { timeout: 15000 }),
  getRunDisplay: (projectId: string, id: string) =>
    api.get<VettingJob>(`/vetting/${projectId}/runs/${encodeURIComponent(id)}/display`, { timeout: 15000 }),
  latestRunProgress: (projectId: string) =>
    api.get<VettingProgress | null>(`/vetting/${projectId}/runs/latest/progress`, { timeout: 15000 }),
  getRunProgress: (projectId: string, id: string) =>
    api.get<VettingProgress>(`/vetting/${projectId}/runs/${encodeURIComponent(id)}/progress`, { timeout: 15000 }),
  findings: (
    projectId: string,
    filters: { search?: string; group?: string; scope?: string; fileKey?: string; page?: string }
  ) => {
    const params = new URLSearchParams()
    Object.entries(filters).forEach(([key, value]) => {
      if (value && value !== 'all') params.set(key, value)
    })
    const query = params.toString()
    return api.get<Finding[]>(`/vetting/${projectId}/findings${query ? `?${query}` : ''}`)
  },
  metrics: (projectId: string) => api.get<VettingMetrics>(`/vetting/${projectId}/metrics`),
  updateStatus: (projectId: string, code: string, status: string, binding: FindingWriteBinding) =>
    api.post<Finding>(`/vetting/${encodeURIComponent(projectId)}/findings/${encodeURIComponent(code)}/status?${new URLSearchParams({ status, ...binding })}`),
  updateReview: (projectId: string, code: string, review: FindingReviewUpdate) =>
    api.put<Finding>(`/vetting/${encodeURIComponent(projectId)}/findings/${encodeURIComponent(code)}/review`, review),
  evidence: (projectId: string, code: string) =>
    api.get<EvidenceBundle>(`/vetting/${projectId}/findings/${code}/evidence`),
  checklist: (projectId: string, runId: string) =>
    api.get<VettingChecklist>(`/vetting/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/checklist`),
  saveChecklist: (projectId: string, binding: Pick<VettingChecklist, 'runId' | 'runSnapshotSha256' | 'sourceSnapshotSha256'>, items: Pick<VettingChecklistItem, 'id' | 'status' | 'remarks'>[]) =>
    api.put<VettingChecklist>(`/vetting/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(binding.runId)}/checklist`, {
      expectedRunId: binding.runId, expectedSourceSnapshotSha256: binding.sourceSnapshotSha256,
      runSnapshotSha256: binding.runSnapshotSha256, items
    }),
  exportReport: (projectId: string, fallbackName: string, format: VettingReportFormat = 'pdf') =>
    api.download(`/vetting/${projectId}/report.${format}?lang=en`, fallbackName, {
      timeout: 600000,
      jsonDocument: format === 'json' ? value => {
        if (!value || typeof value !== 'object') return false
        const report = value as Record<string, unknown>
        const project = report.project as { id?: unknown } | null
        const job = report.job as { id?: unknown; status?: unknown } | null
        const diagnostics = report.diagnostics as { retainedServerSide?: unknown; sourceRunId?: unknown; sourceReportSnapshotId?: unknown } | null
        const sha256 = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
        return report.schemaVersion === '2.0' && report.reportStructureVersion === 'competition-comments-and-checklist-v1'
          && report.presentationOnly === true && report.language === 'en' && report.reportLanguage === 'en'
          && project?.id === projectId && typeof job?.id === 'string' && job.id.length > 0 && job.status === 'COMPLETED'
          && report.sourceRunId === job.id && sha256(report.sourceReportSnapshotId) && sha256(report.sourceSnapshotSha256)
          && diagnostics?.retainedServerSide === true && diagnostics.sourceRunId === job.id && diagnostics.sourceReportSnapshotId === report.sourceReportSnapshotId
          && ['findings', 'commentsTables', 'checklistTables', 'checklistResults'].every(key => Array.isArray(report[key]))
      } : undefined
    })
}

/* ------------------------------------------------------------ 咨询 */

export const adviceApi = {
  prepareSources: (projectId: string, scope: string) =>
    api.post<AdviceSourcePreparation>(`/advice/${encodeURIComponent(projectId)}/sources/prepare?scope=${encodeURIComponent(scope)}`, undefined, { reportError: false }),
  sessions: (projectId: string) => api.get<AdviceSession[]>(`/advice/${encodeURIComponent(projectId)}/sessions`),
  createSession: (projectId: string, scope: string, basisScope: AdviceBasisScope) =>
    api.post<AdviceSession>(`/advice/${encodeURIComponent(projectId)}/sessions`, { scope, basisScope }),
  sources: (projectId: string, scope: string) =>
    api.get<AdviceSourceDocument[]>(`/advice/${encodeURIComponent(projectId)}/sources?scope=${encodeURIComponent(scope)}`),
  rebuildIndex: (projectId: string, scope = 'fullset') => api.post<IndexStatus>(`/advice/${encodeURIComponent(projectId)}/index/rebuild?scope=${encodeURIComponent(scope)}`, undefined, { reportError: false, timeout: 2400000 }),
  indexStatus: (projectId: string, scope = 'fullset') => api.get<IndexStatus>(`/advice/${encodeURIComponent(projectId)}/index/status?scope=${encodeURIComponent(scope)}`, { reportError: false }),
  messages: (projectId: string, sessionId?: string) => api.get<ChatMessage[]>(`/advice/${projectId}/messages${sessionId ? '?sessionId=' + encodeURIComponent(sessionId) : ''}`),
  clearMessages: (projectId: string, sessionId?: string) => api.delete<void>(`/advice/${projectId}/messages${sessionId ? '?sessionId=' + encodeURIComponent(sessionId) : ''}`),
  ask: (projectId: string, question: string, scope: string, llmSelection?: LlmSelection, replyToMessageId?: number, sessionId?: string, basisScope?: AdviceBasisScope) =>
    api.post<AskResponse>(`/advice/${projectId}/ask`, { question, scope, replyToMessageId, sessionId, basisScope }, { llmSelection }),
  quickQuestions: (projectId: string) => api.get<QuickQuestion[]>(`/advice/${projectId}/quick-questions`)
}

/* ------------------------------------------------------------ 技能与系统 */

export const skillApi = {
  list: () => api.get<SkillDoc[]>('/skills'),
  save: (id: string, doc: SkillDoc) => api.put<SkillDoc>(`/skills/${id}`, doc),
  reset: (id: string) => api.post<SkillDoc>(`/skills/${id}/reset`)
}

export const systemApi = {
  health: () => api.get<SystemHealth>('/system/health'),
  llmProfiles: () => api.get<LlmProfiles>('/system/llm-profiles')
}

/** 提示词配置（在线编辑，保存后立即对后续模型调用生效） */
export const promptApi = {
  list: () => api.get<PromptTemplate[]>('/prompts'),
  save: (key: string, body: PromptSaveRequest) =>
    api.put<PromptTemplate>(`/prompts/${encodeURIComponent(key)}`, body),
  reset: (key: string) => api.post<PromptTemplate>(`/prompts/${encodeURIComponent(key)}/reset`)
}
