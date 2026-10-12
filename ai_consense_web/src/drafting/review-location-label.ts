import type { DraftDocumentBinding, DraftPlanAction } from '@/api/types'

export interface ReviewLocationLabel {
  clause: string
  actionId?: string
  ambiguous: boolean
  authority: 'saved_operation' | 'registered_association'
}

// A catalogue association may include reference context outside the action's
// actual edit. Prefer a verified saved operation; retain all candidates when
// that evidence cannot establish one clause. This never changes navigation.
export function reviewLocationLabel(binding: DraftDocumentBinding, actions: DraftPlanAction[], fileKey: string, selectedKey: string, savedBindingVerified: boolean): ReviewLocationLabel | undefined {
  const registered = actions.filter(action => action.document === fileKey && binding.actionIds.includes(action.id) && (action.inputKeys ?? action.fieldKeys ?? []).includes(selectedKey))
  const applied = savedBindingVerified ? registered.filter(action => binding.appliedActionIds?.includes(action.id) && binding.operationIds.some(operation => operation === action.id || operation.startsWith(`${action.id}-`))) : []
  const candidates = applied.length ? applied : registered
  const clauses = [...new Set(candidates.map(action => action.clause).filter(Boolean))]
  if (!clauses.length) return undefined
  return { clause: clauses.join(' / '), actionId: candidates.length === 1 ? candidates[0]?.id : undefined, ambiguous: candidates.length > 1, authority: applied.length ? 'saved_operation' : 'registered_association' }
}
