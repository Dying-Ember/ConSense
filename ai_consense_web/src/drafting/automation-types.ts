export interface DraftAutomationBinding {
  expectedCurrentRevisionId: string | null
  expectedDraftRevisionId: string | null
}

export interface DraftAutomaticRequirement {
  id: string; sourceId: string; sourceSha256: string; sourceQuote: string
  kind: string; nodeIds: string[]; reason: string; status: 'pending'
}
export interface DraftAutomaticRelation {
  fromId: string; toId: string; kind: string; fromQuote: string; toQuote: string
  reason: string; status: 'pending' | 'unresolved'; executable: false
}
export interface DraftAutomaticIssue { reason: string; nodeId?: string; requirementId?: string }
export interface DraftAutomaticCoverage {
  totalMembers: number; scannedMembers: number; partialMembers: number; failedMembers?: number; unexecutedMembers: number; blockedMembers: number; blankMembers: number; relationTasksUnresolved: number
  requirementTasks?: number; completedRequirementTasks?: number; partialRequirementTasks?: number; failedRequirementTasks?: number; unexecutedRequirementTasks?: number
  scanStatus: string
  sources: { sourceId: string; sourceSha256: string; fileName: string; totalMembers: number; blankMembers: number; blockedMembers: number; status: string; memberCoverage: { memberId: string; ordinal: number; status: string; reason?: string }[] }[]
}

export interface DraftAutomationRun {
  id: string
  projectId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'INTERRUPTED' | 'CANCELLED'
  phase: 'PREPARING_SOURCES' | 'PLANNING' | 'DISCOVERING_VARIABLES' | 'LINKING_GRAPH' | 'SAVING_GRAPH' | 'EXTRACTING_CANDIDATES' | 'READY_FOR_REVIEW'
  message: string
  currentFile: string | null
  completedItems: number
  totalItems: number
  failedItems: number
  unresolvedItems: number
  requirementsCount: number
  nodesCount: number
  resultRevisionId: string | null
  updatedAt: string
}
