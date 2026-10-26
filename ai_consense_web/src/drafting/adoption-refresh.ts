import type { DraftCatalog, DraftPlan, DraftVariable } from '@/api/types'
import type { ReviewHead } from './source-review-types'

export interface AdoptionRefreshContext {
  revisionId: string
  planIdentity: string
  variableBindings: string
}

function planIdentity(plan: DraftPlan | null | undefined) {
  if (!plan || !('graphRevisionId' in plan) || typeof plan.graphRevisionId !== 'string' || !plan.graphRevisionId ||
    !('reviewStatus' in plan) || typeof plan.reviewStatus !== 'string' ||
    !('evidenceRevision' in plan) || typeof plan.evidenceRevision !== 'string' || !plan.evidenceRevision ||
    !('sourceIdentities' in plan) || !Array.isArray(plan.sourceIdentities) || !plan.sourceIdentities.length ||
    !('preview' in plan) || plan.preview !== false || !plan.snapshotId ||
    !('savedSnapshotId' in plan) || plan.savedSnapshotId !== plan.snapshotId ||
    plan.unresolved.some(issue => issue.id === 'graph_source_changed')) return null
  return { revisionId: plan.graphRevisionId, graphSha256: plan.ruleVersion, reviewStatus: plan.reviewStatus,
    signature: JSON.stringify([plan.graphRevisionId, plan.ruleVersion, plan.reviewStatus, plan.evidenceRevision, plan.sourceIdentities]) }
}

function variableBindings(variables: DraftVariable[], revisionId: string) {
  const keys = new Set<string>(), bindings: string[][] = []
  for (const variable of variables) {
    if (!variable.key || keys.has(variable.key)) return null
    keys.add(variable.key)
    if (variable.key === 'contractTitle' && !variable.graphRevisionId && !variable.fieldDefinitionHash) {
      bindings.push([variable.key, '', '']); continue
    }
    if (variable.graphRevisionId !== revisionId || !variable.fieldDefinitionHash) return null
    bindings.push([variable.key, variable.graphRevisionId, variable.fieldDefinitionHash])
  }
  return bindings.some(binding => binding[1]) ? JSON.stringify(bindings.sort((a, b) => a[0]!.localeCompare(b[0]!))) : null
}

/** Only a saved, source-bound graph may retain its schema and immutable document identities. */
export function captureAdoptionRefresh(catalog: DraftCatalog, variables: DraftVariable[], plan: DraftPlan | null, head?: ReviewHead): AdoptionRefreshContext | null {
  const identity = planIdentity(plan), revision = [head?.current, head?.draft].find(item => item?.id === catalog.graphRevisionId)
  if (!identity || !revision || revision.sourceStale || identity.revisionId !== revision.id ||
    identity.graphSha256 !== revision.graphSha256 || identity.reviewStatus !== revision.status) return null
  const selected = Object.values(revision.graph.selectedDocuments)
  if (!selected.length || selected.some(id => !head?.sources.some(source => String(source.id) === String(id) && source.evidenceStatus === 'bound' && source.format === 'docx' && source.canEdit))) return null
  const bindings = variableBindings(variables, revision.id)
  return bindings ? { revisionId: revision.id, planIdentity: identity.signature, variableBindings: bindings } : null
}

export function adoptionVariablesMatch(context: AdoptionRefreshContext, variables: DraftVariable[]) {
  return variableBindings(variables, context.revisionId) === context.variableBindings
}

export function adoptionPlanMatches(context: AdoptionRefreshContext, plan: DraftPlan) {
  return planIdentity(plan)?.signature === context.planIdentity
}
