import { api } from './client'
import type {
  AskResponse,
  AdviceSourceDocument,
  DocumentScope,
  VettingChecklist,
  VettingChecklistItem,
  ChatMessage,
  DraftDocument,
  DraftDocumentBindings,
  DraftBodyPatch,
  DraftProgress,
  DraftVariable,
  DraftVariablePatch,
  DraftCatalog,
  DraftPlan,
  EvidenceBundle,
  EvidenceItem,
  ExtractTrace,
  ExtractRunSummary,
  Finding,
  FindingReviewUpdate,
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
  VettingMetrics,
  VettingRunResult,
  VettingJob,
  VettingReportFormat,
  VettingSourceRole
} from './types'

export * from './types'
export { api, ApiError, setApiErrorReporter, captureLlmSelection, setLlmProfileResolver } from './client'

/* ------------------------------------------------------------ 项目 */

export const projectApi = {
  list: () => api.get<Project[]>('/projects'),
  get: (id: string) => api.get<Project>(`/projects/${id}`),
  save: (body: Partial<Project> & { id: string; nameZhHans: string; nameZhHant?: string; nameEn?: string }) =>
    api.post<Project>('/projects', body),
  remove: (id: string) => api.delete<void>(`/projects/${id}`)
}

/* ------------------------------------------------------------ 起草 */

export const draftingApi = {
  catalog: (projectId: string) => api.get<DraftCatalog>(`/drafting/${projectId}/catalog`),
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
  generate: (projectId: string, lang: string, llmSelection?: LlmSelection) =>
    // Full SCC templates are generated in many model calls; keep the request alive.
    api.post<DraftDocument[]>(`/drafting/${projectId}/generate?lang=${encodeURIComponent(lang)}`, undefined, { timeout: 3600000, llmSelection }),
  documents: (projectId: string) => api.get<DraftDocument[]>(`/drafting/${projectId}/documents`),
  documentSource: (projectId: string, fileKey: string, revisionId: string) =>
    api.blob(`/drafting/${projectId}/documents/${fileKey}/export.docx?revisionId=${encodeURIComponent(revisionId)}`, undefined, false),
  updateDocument: (projectId: string, fileKey: string, patch: DraftBodyPatch) =>
    api.put<DraftDocument>(`/drafting/${projectId}/documents/${fileKey}`, patch),
  previewDocument: (projectId: string, fileKey: string, revisionId?: string) =>
    api.blob(`/drafting/${projectId}/documents/${fileKey}/preview.pdf${revisionId ? `?revisionId=${encodeURIComponent(revisionId)}` : ''}`),
  exportDocument: (projectId: string, fileKey: string, format: 'docx' | 'pdf', revisionId?: string) =>
    api.download(`/drafting/${projectId}/documents/${fileKey}/export.${format}${revisionId ? `?revisionId=${encodeURIComponent(revisionId)}` : ''}`, `ConSense_${fileKey}.${format}`),
  download: (projectId: string, fileKey: string, fallbackName: string) =>
    api.download(`/drafting/${projectId}/documents/${fileKey}/download`, fallbackName)
}

/* ------------------------------------------------------------ 审查 */

export const vettingApi = {
  files: (projectId: string) => api.get<VettingFile[]>(`/vetting/${projectId}/files`),
  uploadPackage: (projectId: string, files: File[], sourceRole?: VettingSourceRole, documentScope?: DocumentScope) => {
    const params = new URLSearchParams()
    if (sourceRole) params.set('sourceRole', sourceRole)
    if (documentScope && documentScope !== 'auto') params.set('documentScope', documentScope)
    return api.upload<UploadResult>(`/vetting/${projectId}/package/upload${params.size ? `?${params}` : ''}`, files)
  },
  run: (projectId: string, lang: string) =>
    api.post<VettingRunResult>(`/vetting/${projectId}/run?lang=${encodeURIComponent(lang)}`),
  startRun: (projectId: string, lang: string, llmSelection?: LlmSelection) =>
    api.post<VettingJob>(`/vetting/${projectId}/runs?lang=${encodeURIComponent(lang)}`, undefined, { timeout: 30000, llmSelection }),
  latestRun: (projectId: string) =>
    api.get<VettingJob | null>(`/vetting/${projectId}/runs/latest`, { timeout: 15000 }),
  getRun: (projectId: string, id: string) =>
    api.get<VettingJob>(`/vetting/${projectId}/runs/${encodeURIComponent(id)}`, { timeout: 15000 }),
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
  updateStatus: (projectId: string, code: string, status: string) =>
    api.post<Finding>(`/vetting/${projectId}/findings/${code}/status?status=${encodeURIComponent(status)}`),
  updateReview: (projectId: string, code: string, review: FindingReviewUpdate) =>
    api.put<Finding>(`/vetting/${encodeURIComponent(projectId)}/findings/${encodeURIComponent(code)}/review`, review),
  evidence: (projectId: string, code: string) =>
    api.get<EvidenceBundle>(`/vetting/${projectId}/findings/${code}/evidence`),
  checklist: (projectId: string, runId: string) =>
    api.get<VettingChecklist>(`/vetting/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/checklist`),
  saveChecklist: (projectId: string, runId: string, runSnapshotSha256: string, items: Pick<VettingChecklistItem, 'id' | 'status' | 'remarks'>[]) =>
    api.put<VettingChecklist>(`/vetting/${encodeURIComponent(projectId)}/runs/${encodeURIComponent(runId)}/checklist`, { runSnapshotSha256, items }),
  exportReport: (projectId: string, lang: string, fallbackName: string, format: VettingReportFormat = 'pdf') =>
    api.download(`/vetting/${projectId}/report.${format}?lang=${encodeURIComponent(lang)}`, fallbackName)
}

/* ------------------------------------------------------------ 咨询 */

export const adviceApi = {
  sources: (projectId: string, scope: string) =>
    api.get<AdviceSourceDocument[]>(`/advice/${encodeURIComponent(projectId)}/sources?scope=${encodeURIComponent(scope)}`),
  rebuildIndex: (projectId: string) => api.post<IndexStatus>(`/advice/${projectId}/index/rebuild`),
  indexStatus: (projectId: string) => api.get<IndexStatus>(`/advice/${projectId}/index/status`),
  messages: (projectId: string) => api.get<ChatMessage[]>(`/advice/${projectId}/messages`),
  clearMessages: (projectId: string) => api.delete<void>(`/advice/${projectId}/messages`),
  ask: (projectId: string, question: string, scope: string, llmSelection?: LlmSelection, replyToMessageId?: number) =>
    api.post<AskResponse>(`/advice/${projectId}/ask`, { question, scope, replyToMessageId }, { llmSelection }),
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
