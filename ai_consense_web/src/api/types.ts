/** 后端统一返回的三语文案对象 */
export interface LocalizedText {
  zhHans: string
  zhHant: string
  en: string
}

export interface ModelIdentity {
  profileId: 'local' | 'minimax-cn'
  provider: string
  model: string
  configurationSha256: string
  identityScope: 'configured_profile'
}

export interface Project {
  id: string
  name: LocalizedText
  contractNo: string | null
  packageRef: string | null
  outputReferenceFile: string | null
  pages: string | null
  nttRange: string | null
  sctRange: string | null
  sccRange: string | null
  specification: string | null
  titleLines: LocalizedText[]
}

/* ------------------------------------------------------------ 起草 */

export interface TemplateItem {
  key: string
  id: string
  label: LocalizedText
  fileName: string
  note: LocalizedText
  status: LocalizedText
  tag: string
}

/** Native main-document identities are generated from the current uploaded source bytes. */
export interface TemplateReadingParagraph {
  id: string
  ordinal: number
  text: string
}

export interface TemplateReading {
  fileKey: string
  fileName: string
  sourceHash: string
  format: 'docx' | 'pdf' | 'unsupported'
  catalogueSourceVerified: boolean
  paragraphs: TemplateReadingParagraph[]
}

export interface EvidenceItem {
  id: number | null
  code: string
  type: LocalizedText
  status: string
  tag: string
  title: LocalizedText
  body: LocalizedText
  source: string
  fileName: string
  fileKey: string | null
  pageCount: number | null
  ocrUsed: boolean | null
  message: LocalizedText
}

export interface DraftVariable {
  key: string
  scope: 'INPUT' | 'BASE' | 'FILE'
  group: string
  fileKey: string | null
  label: LocalizedText
  action: string
  value: string
  choice: string | null
  options: LocalizedText[]
  confirmed: boolean
  confirmedFrom: string | null
  source: string | null
  result: string | null
  kind: string | null
  cols: string[]
  linkedBase: string | null
  derivedFrom: string[]
  affects: string[]
  note: string | null
  manuallyEdited?: boolean
  reviewRequired?: boolean
  adoptionState?: string
  validationIssue?: string | null
  candidates?: DraftCandidate[]
}

export interface DraftCandidate {
  value: string
  sourceDocumentId?: string | number
  fileName?: string
  sourceHash?: string
  sourceQuote?: string
  reason?: string
  confidence?: number
}

export interface DraftCondition {
  all: Array<{ field: string; operator: 'eq' | 'gt' | 'includes' | 'in' | 'countGt' | 'includesComponent' | 'unknown'; value: unknown }>
}

export interface DraftTarget {
  document: string
  clause: string
  paragraphs?: string
}

export interface DraftField {
  key: string
  label: LocalizedText
  kind: string
  optional?: boolean
  options?: Array<{ value: string | boolean | number; label: LocalizedText }>
  condition?: DraftCondition
  affects?: DraftTarget[]
  columnFields?: DraftField[]
  description?: LocalizedText
  hidden?: boolean
}

export interface DraftGroup {
  id: string
  label: LocalizedText
  description?: LocalizedText
  fields: DraftField[]
}

export interface DraftCatalog {
  ruleVersion: string
  groups: DraftGroup[]
  systemFields?: DraftField[]
}

export interface DraftPlanAction extends DraftTarget {
  id: string
  action: string
  detail?: LocalizedText | string
  value?: string
  fieldKeys?: string[]
  inputKeys?: string[]
  questionIds?: string[]
  overrideable?: boolean
  overridden?: boolean
  sourceText?: string
  sourceWarning?: LocalizedText
}

export interface DraftUnresolved {
  id: string
  actionId?: string
  kind: string
  document?: string
  clause?: string
  inputKeys: string[]
  message: LocalizedText
}

export interface DraftPlan {
  ruleVersion: string
  inputValues: Record<string, string>
  effectiveValues: Record<string, string>
  derived: Record<string, unknown>
  actions: DraftPlanAction[]
  unresolved: DraftUnresolved[]
  snapshotId?: string
}

export interface DraftTargetOverride {
  actionId: string
  action: 'retain' | 'amend' | 'delete' | 'not_used'
  value?: string
  sourceMapping?: string
  document?: string
  clause?: string
  sourceRevision?: { document: string; sourceDocumentId: number | null; sourceHash: string | null }
}

export interface DraftVariablePatch {
  value?: string
  choice?: string
  confirmed?: boolean
  note?: string
  result?: string
  reviewed?: boolean
  candidateIndex?: number
  candidateSnapshot?: DraftCandidateIdentity
  suggestionSnapshot?: { value: string; source: string | null; candidates: DraftCandidateIdentity[]; reviewRequired: boolean }
}

export interface DraftCandidateIdentity {
  value: string
  sourceDocumentId: string | number | null
  sourceHash: string | null
  sourceQuote: string | null
}

/** 手工新增 FILE 变量的请求体 */
export interface VariableCreateRequest {
  key: string
  fileKey: string
  labelZhHans: string
  labelZhHant?: string
  labelEn?: string
  action: 'fill' | 'choice' | 'rewrite' | 'delete' | 'notused' | string
  options?: string[]
  value?: string
  affects?: string
  sourceQuote?: string
  reason?: string
  confidence?: number
}

/** 最近一次变量识别的过程留痕（提示词 + 模型原始返回） */
export interface ExtractTrace {
  modelIdentity?: ModelIdentity | null
  model: string
  finishedAt: string
  systemPrompt: string
  userPrompt: string
  rawResponses: string[]
  runId?: string
  harnessVersion?: string
  evidenceRevision?: string
  status?: 'completed' | 'failed'
  startedAt?: string
  failureCode?: string | null
  failureMessage?: string | null
  stale?: boolean
  parts?: ExtractPart[]
  decisions?: ExtractDecision[]
  fields?: ExtractFieldDiagnostic[]
  relations?: ExtractRelation[]
}

/** Source relationship proposals are read-only and never adopt or replace draft values. */
export interface ExtractRelation {
  key: string
  relation?: 'supplement' | 'explicit_replacement' | 'contradiction' | 'undetermined' | (string & {}) | null
  status: 'proposed' | 'undetermined' | 'skipped' | 'rejected' | 'failed' | (string & {})
  codes: string[]
  decisionRefs?: number[]
  fromDecisionRef?: number | null
  toDecisionRef?: number | null
  evidence?: (ExtractJointEvidence | null)[] | null
  scopeQuote?: string | null
  reason?: string | null
  confidence?: number | null
  systemPrompt?: string | null
  userPrompt?: string | null
  rawResponse?: string | null
  rawProposal?: unknown
}

export interface ExtractJointEvidence {
  decisionRef: number
  partId: string
  sourceDocumentId: number | null
  fileName: string
  sourceHash: string
  value: string | null
  sourceQuote: string | null
  context?: ExtractContext | null
}

export interface ExtractRunSummary {
  modelIdentity?: ModelIdentity | null
  runId: string
  harnessVersion?: string
  evidenceRevision?: string
  model: string
  status: 'completed' | 'failed'
  startedAt?: string
  finishedAt: string
  failureCode?: string | null
  failureMessage?: string | null
  stale?: boolean
}

export interface ExtractPart {
  partId: string
  sourceDocumentId: number | string | null
  fileName: string
  sourceHash: string
  partIndex: number
  sourceText: string
  attempts: ExtractAttempt[]
  context?: ExtractContext | null
}

export interface ExtractAttempt {
  attemptIndex: number
  kind: 'primary' | 'repair' | 'coverage' | 'recall' | (string & {})
  systemPrompt: string
  userPrompt: string
  rawResponse: string | null
  context?: ExtractContext | null
  status: string
  errorCode?: string | null
}

export interface ExtractContext {
  trigger?: string | null
  keys?: string[] | null
  sourceStart?: number | null
  sourceEnd?: number | null
  sourceText?: string | null
  stopReason?: string | null
}

export interface ExtractDecision {
  partId: string
  attemptIndex: number
  itemIndex: number
  key: string | null
  rawValue: unknown
  normalizedValue?: unknown
  sourceQuote?: string | null
  reason?: string | null
  confidence?: number | null
  status: 'accepted' | 'rejected' | 'unanswered'
  codes: string[]
}

export interface ExtractFieldDiagnostic {
  key: string
  status: 'suggested' | 'candidate_conflict' | 'rejected' | 'model_unanswered' | 'no_candidate' | 'not_assessed' | 'source_unresolved' | 'coverage_unresolved'
  candidateCount: number
  rejectionCount: number
  unansweredCount: number
  decisionRefs: number[]
}

export interface DraftProgress {
  inputTotal: number
  inputConfirmed: number
  inputsReady: boolean
  evidenceCount: number
  templateCount: number
  baseTotal: number
  baseConfirmed: number
  fileTotal: number
  fileConfirmed: number
  baseReady: boolean
  allReady: boolean
  generatedFiles: string[]
}

export interface DraftDocument {
  fileKey: string
  title: string
  content: string
  generated: boolean
  snapshotId?: string
  stale?: boolean
  ruleVersion?: string
  unresolved?: DraftUnresolved[]
  contentEdited?: boolean
  revisionId?: string
  docxSha256?: string
  sourceSha256?: string
  pdfSha256?: string
  renderProfileHash?: string
  fieldStatus?: string
  fieldImpacts?: unknown[]
  blocks?: DraftBlock[]
  nativeLayout?: DraftNativeLayout
}

/** A native DOCX layout receipt, separate from business inputs and adoption. */
export interface DraftNativeLayout {
  kind: 'native_layout'
  profile?: string
  status: 'applied' | 'skipped_unverified_source' | 'not_applied_to_saved_revision' | 'unavailable'
  sourceSha256?: string
  docxSha256?: string
  compiledDocxSha256?: string
  parentDocxSha256?: string
  actions?: { action: string; status: string; sourceId?: string; generatedId?: string; layoutId?: string }[]
}

export interface DraftBlock {
  id: string
  bindingId?: string
  text: string
  textHash: string
  paragraphOrdinal: number
  cellId?: string
  editable: boolean
  unsupportedReason?: string
}
export interface DraftBodyPatch {
  revisionId: string
  docxSha256: string
  blocks: { id: string; bindingId?: string; expectedTextHash: string; text: string; insertAfter?: string[] }[]
}

/** A paragraph anchor is a physical PDF point, not a full text rectangle. */
export interface DraftBindingPoint {
  kind: 'point'
  pageNumber: number
  x: number
  y: number
  pageWidth: number
  pageHeight: number
  unit: 'pt'
  origin: 'top-left'
  pageBox: 'crop'
  rotation: number
}
export interface DraftDocumentBinding {
  bindingId: string
  bookmarkName: string
  parentBindingId?: string | null
  sourceParagraphId?: string | null
  sourceParagraphOrdinal?: number | null
  sourceText?: string | null
  sourceTextHash?: string | null
  resultParagraphId?: string | null
  resultParagraphOrdinal?: number | null
  text?: string | null
  textHash?: string | null
  fieldKeys: string[]
  actionIds: string[]
  appliedActionIds?: string[]
  operationIds: string[]
  bodyOperationIds?: string[]
  applicationStatus: 'applied' | 'unapplied' | 'unchanged' | 'body_edited' | 'body_added' | 'generated_added'
  documentEditStatus?: 'applied' | 'unchanged'
  locationStatus: 'exact' | 'removed' | 'missing' | 'approximate' | 'conflicted'
  geometryStatus: 'pending' | 'ready' | 'unavailable'
  geometry?: DraftBindingPoint | null
}
export interface DraftDocumentBindings {
  fileKey: string
  view: 'source' | 'result'
  sourceSha256: string
  revisionId?: string | null
  docxSha256?: string | null
  pdfSha256?: string | null
  renderProfileHash?: string | null
  geometryStatus: 'pending' | 'ready' | 'unavailable'
  bindings: DraftDocumentBinding[]
  nativeLayout?: DraftNativeLayout
}

export interface UploadResult {
  accepted: number
  parsed: number
  failed: number
  messages: string[]
}

/* ------------------------------------------------------------ 审查 */

export interface VettingFile {
  key: string
  role: LocalizedText
  sourceRole?: VettingSourceRole
  fileName: string
  basis: string
  status: string
  generated: boolean
  parsed: boolean
  pageCount: number
  parseStatus?: string
  parseMessage?: string
  textChars?: number
  ocrUsed?: boolean
  warnings?: string[]
}

export interface FindingEvidence {
  side: string
  documentId?: string | number | null
  fileKey: string
  fileName: string
  pageNo?: string | null
  anchor?: string | null
  quote: string
  located: boolean
  sourceHash?: string
  bbox?: number[] | null
  packetId?: string | null
  packetSourceSnapshotSha256?: string | null
}

export interface Finding {
  code: string
  types: string[]
  group: string
  scope: string
  severity: 'high' | 'medium' | 'low' | string
  status: string
  title: LocalizedText
  body: LocalizedText
  impact: LocalizedText
  suggestion: LocalizedText
  pattern: string | null
  refs: string | null
  location: string | null
  expected: string | null
  evidenceId: string | null
  fileKey: string | null
  pageNo: string | null
  bucketKey: string | null
  fingerprint?: string
  runId?: string
  source?: 'rule' | 'model' | string
  verification?: 'verified' | 'partial' | 'unverified'
  confidence?: number | null
  evidence?: FindingEvidence[]
  reviewRemarks?: string | null
  actionTaken?: string | null
  addendumRequired?: boolean | null
  reviewUpdatedAt?: string | null
}

export interface FindingReviewUpdate {
  reviewRemarks: string
  actionTaken: string
  addendumRequired: boolean | null
}

export interface VettingMetrics {
  reference: number
  conflict: number
  language: number
  risk: number
  total: number
  crossFile: number
}

export interface VettingRunResult {
  modelIdentity?: ModelIdentity | null
  total: number
  metrics: VettingMetrics
  messages: string[]
  model: string
  coverage?: VettingCoverage
}

export interface VettingDocumentCoverage {
  fileKey: string
  fileName: string
  parseStatus: string
  textChars: number
  reviewedChars: number
  totalSegments: number
  reviewedSegments: number
  warnings: string[]
}

export interface VettingCoverage {
  documents: VettingDocumentCoverage[]
  totalDocuments: number
  reviewedDocuments: number
  warnings: string[]
  semanticTopics?: VettingSemanticTopic[]
}

export interface VettingSemanticTopic {
  topicIndex: number
  topic: string
  reviewKind?: 'topic' | 'project_reference'
  selectionStrategy?: string
  referenceIds?: string[]
  status: string
  globalCallStatus?: string
  aggregateReviewStatus?: 'failed' | 'not_submitted' | 'partial' | 'observed_requests_decoded_scope_unknown' | string
  sourceRequestCount?: number
  pendingSourceRequestCount?: number
  extraPacketCount?: number
  failedPacketCount?: number
  notSubmittedPacketCount?: number
  reviewCoverageScope?: string
  semanticScopeVerified?: boolean
  packetAudits?: VettingSemanticPacket[]
  sourceRequests?: VettingSourceRequestAudit[]
  submittedChunkIds: string[]
  submittedChars: number
  budgetDroppedGroups: number
  partialContextGroups: number
  unresolvedSegments: number
  returnedAssessments: number
  issueAssessments: number
  consistentAssessments: number
  insufficientContextAssessments: number
  acceptedFindings: number
  rejectedRecords: number
  error?: string | null
  rawResponseSha256?: string | null
}

export interface VettingSemanticPacket {
  packetId: string
  sourceObservationPacketId?: string | null
  sourceSnapshotSha256?: string | null
  promptEnvelopeSha256?: string | null
  actualGatewayCallStarted?: boolean
  semanticScopeVerified?: boolean
  inputBudgetMetadata?: {
    status?: string
    reason?: string
    extraDispatchPermitted?: boolean
    observation?: { inputTokens?: number; outputReserveTokens?: number; effectiveContextTokens?: number; scope?: string } | null
  } | null
  packetIndex: number
  kind: string
  status: string
  failureKind?: string | null
  error?: string | null
  inputBudgetStatus?: 'budget_unknown' | 'over_budget' | 'observed_tokens' | 'provider_estimated_fit' | string
  submittedChunkIds?: string[]
  submittedChars?: number
  requestIds?: string[]
}

export interface VettingSourceRequestAudit {
  requestId: string
  kind?: string
  observationId?: string | null
  contentChars?: number
  applicability?: string
  qualifiersComplete?: string
  semanticScopeVerified?: boolean
  transportStatus: string
  reviewExecutionStatus: string
  unknownReason?: string | null
  requiredChunkIds?: string[]
  missingChunkIds?: string[]
  eligibleOriginIds?: string[]
  packIndex?: number
  packetId?: string | null
}

export interface VettingJob {
  modelIdentity?: ModelIdentity | null
  id: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  phase: string
  completedUnits: number
  totalUnits: number
  message: string
  startedAt?: string | null
  finishedAt?: string | null
  result?: VettingRunResult | null
  error?: string | null
  coverage?: VettingCoverage | null
}

export type VettingReportFormat = 'pdf' | 'docx' | 'json'
export type VettingSourceRole = 'tender' | 'standard' | 'project_fact' | 'package_manifest'

export interface EvidenceSnippet {
  code: string
  /** 原文中该片段所在页码，由正文 --- Pn --- 标记反推 */
  pageNo: string | null
  text: string
  side?: string
  documentId?: string | number | null
  fileKey?: string
  fileName?: string
  anchor?: string | null
  quote?: string
  located?: boolean
  sourceHash?: string
  bbox?: number[] | null
  packetId?: string | null
  packetSourceSnapshotSha256?: string | null
}

export interface EvidenceBundle {
  title: string
  /** 是否在原文里逐字定位到；false 时 items 为空，应提示人工复核 */
  located: boolean
  items: EvidenceSnippet[]
}

/* ------------------------------------------------------------ 咨询 */

export interface Citation {
  fileLabel: string
  pageNo: string
  anchor: string
  content: string
  score: number
}

export interface ChatMessage {
  modelIdentity?: ModelIdentity | null
  model?: string | null
  id: number | null
  role: 'user' | 'assistant'
  title: LocalizedText | null
  content: LocalizedText | null
  unknownScope: string | null
  citations: string[]
  evidenceId: string | null
  sources: Citation[]
}

export interface AskResponse {
  modelIdentity?: ModelIdentity | null
  grounded: boolean
  title: LocalizedText
  content: LocalizedText | null
  unknownScope: string | null
  citations: string[]
  evidenceId: string | null
  sources: Citation[]
  model: string
}

export interface IndexStatus {
  chunks: number
  indexed: number
  vectorProvider: string
  collection: string
  embedModel: string
  llmModel: string
  llmReady: boolean
  vectorReady: boolean
  ocrReady: boolean
  message: LocalizedText
}

export interface QuickQuestion {
  text: LocalizedText
  unknown: boolean | null
  evidenceId: string | null
}

/* ------------------------------------------------------------ 技能与系统 */

export interface SkillDoc {
  id: string
  code: string
  name: LocalizedText
  purpose: LocalizedText
  badges: LocalizedText[]
  steps: LocalizedText[][]
  ruleHead: LocalizedText[]
  rules: string[][]
  outItems: LocalizedText[]
  guardItems: LocalizedText[]
  order: number
}

export type LlmProfileId = 'local' | 'minimax-cn'
export interface LlmSelection { readonly profileId: LlmProfileId | null }

export interface LlmProfile {
  id: LlmProfileId
  provider: string
  model: string
  configured: boolean
  unavailableReason?: 'missing_api_key' | 'disabled' | 'missing_configuration' | string
}

export interface LlmProfiles {
  defaultProfile: LlmProfileId
  profiles: LlmProfile[]
}

export interface SystemHealth {
  llm: { available: boolean; provider: string; baseUrl: string; chatModel: string; embedModel: string }
  ocr: { available: boolean; enabled: boolean; baseUrl: string; mode: string }
  vector: { available: boolean; provider: string; baseUrl: string; collection: string }
  storageRoot: string
  ready: boolean
}

/** 可在线编辑的模型提示词 */
export interface PromptTemplate {
  key: string
  group: string
  name: LocalizedText
  description: LocalizedText
  systemText: string
  userTemplate: string
  defaultSystemText: string
  defaultUserTemplate: string
  customized: boolean
  placeholderCount: number
  sortOrder: number
  updatedAt: string
}

export interface PromptSaveRequest {
  systemText: string
  userTemplate: string
}
