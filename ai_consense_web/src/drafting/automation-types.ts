export interface DraftAutomationBinding {
  expectedCurrentRevisionId: string | null
  expectedDraftRevisionId: string | null
}
export interface DraftAutomationStart extends DraftAutomationBinding {
  sourceIds?: string[]
}

export interface DraftAutomaticRequirement {
  id: string; sourceId: string; sourceSha256: string; sourceQuote: string
  analysisRevision?: string; analysisInputSha256?: string; analysisSha256?: string
  kind: string; nodeIds: string[]; reason: string; status: 'pending'
  sourceQuoteEvidence?: { kind?: string; sourceObservationId?: string; sourceScopeText?: string; sourceScopeObservation?: Record<string, any>; sourceScopeObservationRef?: string }
}
export interface DraftAutomaticRelation {
  fromId: string; toId: string; kind: string; fromQuote: string; toQuote: string
  reason: string; status: 'pending' | 'unresolved' | 'confirmed' | 'rejected' | 'excluded'; executable: false
  review?: { decision: RelationReviewDecision; reason: string; basis: string | null; status?: 'reviewed' | 'needs_review'; confirmedAt?: string }
}
export type RelationReviewDecision = 'implemented' | 'rejected' | 'not_applicable'
export interface RelationReviewRequest { revisionId: string; indices: number[]; decision: RelationReviewDecision; reason: string }
export interface DraftAutomaticIssue { reason: string; nodeId?: string; requirementId?: string }
export interface DraftAutomaticGroup {
  id: string; taskIds: string[]; sourceIds: string[]; rootKeys: string[]; basisKinds: string[]; status: string; modelCalls: number | null
}
export interface DraftAutomaticCoverage {
  groups?: DraftAutomaticGroup[]
  evidenceRounds?: number; supplementalSourceItems?: number; restoredDefinitionCount?: number
  totalMembers: number; scannedMembers: number; partialMembers: number; failedMembers?: number; unexecutedMembers: number; blockedMembers: number; blankMembers: number; relationTasksUnresolved: number
  requirementTasks?: number; completedRequirementTasks?: number; partialRequirementTasks?: number; failedRequirementTasks?: number; unexecutedRequirementTasks?: number
  scanStatus: string
  sources: { sourceId: string; sourceSha256: string; fileName: string; totalMembers: number; blankMembers: number; blockedMembers: number; status: string; memberCoverage: { memberId: string; ordinal: number; status: string; reason?: string }[] }[]
}

export interface DraftAutomationRun {
  id: string
  projectId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'INTERRUPTED' | 'CANCELLED'
  phase: 'PREPARING_SOURCES' | 'PLANNING' | 'DISCOVERING_VARIABLES' | 'LINKING_GRAPH' | 'SAVING_GRAPH' | 'EXTRACTING_CANDIDATES' | 'READY_FOR_REVIEW' | 'FIXED_BASELINE_READY'
  message: string
  currentFile: string | null
  completedItems: number
  totalItems: number
  failedItems: number
  unresolvedItems: number
  requirementsCount: number
  nodesCount: number
  groupProgress?: { total: number; completed: number; inFlight: number; pending?: number; concurrency: number; sourceTasksTotal: number }
  canContinueSavedGroups?: boolean
  resultRevisionId: string | null
  updatedAt: string
}
