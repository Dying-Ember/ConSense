import type { ReviewGraph, ReviewGraphDelta, ReviewRevision, ReviewSaveReceipt, ReviewVariable } from './source-review-types'

const same = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right)
const except = (value: object, keys: string[]) => Object.fromEntries(Object.entries(value).filter(([key]) => !keys.includes(key)))
const nodeIds = (nodes: ReviewVariable[]) => Array.isArray(nodes) && nodes.every(node => node && typeof node.id === 'string' && !!node.id) && new Set(nodes.map(node => node.id)).size === nodes.length

/** Existing-node edits only. Everything this request cannot express uses the full-save contract. */
export function reviewSaveDelta(base: ReviewRevision | null | undefined, proposed: ReviewGraph, current: string | null, draft: string | null): ReviewGraphDelta | undefined {
  if (!base || base.sourceStale || base.id !== (draft ?? current) || !/^[a-f0-9]{64}$/i.test(base.graphSha256 ?? '') || !base.graph?.graph || !proposed?.graph) return
  if (!same(except(base.graph, ['graph']), except(proposed, ['graph'])) || !same(except(base.graph.graph, ['variables', 'events']), except(proposed.graph, ['variables', 'events']))) return
  const previous = base.graph.graph.variables, next = proposed.graph.variables, oldEvents = base.graph.graph.events, events = proposed.graph.events
  if (!nodeIds(previous) || !nodeIds(next) || !same(previous.map(node => node.id), next.map(node => node.id)) || !Array.isArray(oldEvents) || !Array.isArray(events) || !same(oldEvents, events.slice(0, oldEvents.length))) return
  const variables = next.filter((node, index) => !same(node, previous[index]))
  if (!variables.length) return
  return { baseRevisionId: base.id, baseGraphSha256: base.graphSha256, variables, appendedEvents: events.slice(oldEvents.length) }
}

/** A receipt is relative to the saved baseline, never the unsaved proposal. */
export function restoreReviewSave(base: ReviewRevision | null | undefined, result: ReviewSaveReceipt): ReviewRevision | undefined {
  if (result.graph) return result as ReviewRevision
  const delta = result.graphDelta
  if (!base?.graph?.graph || !Array.isArray(base.graph.graph.events) || !delta || delta.baseRevisionId !== base.id || delta.baseGraphSha256 !== base.graphSha256 || !result.id || !result.graphSha256 || !nodeIds(delta.variables) || !Array.isArray(delta.appendedEvents) || !delta.stateChanges || typeof delta.stateChanges !== 'object' || Array.isArray(delta.stateChanges) || !Array.isArray(delta.removedStateKeys)) return
  const originals = base.graph.graph.variables, changes = new Map(delta.variables.map(node => [node.id, node]))
  if (!nodeIds(originals) || [...changes.keys()].some(id => !originals.some(node => node.id === id))) return
  const forbidden = ['variables', 'events', '__proto__', 'constructor', 'prototype']
  if (Object.keys(delta.stateChanges).some(key => forbidden.includes(key)) || delta.removedStateKeys.some(key => typeof key !== 'string' || forbidden.includes(key) || Object.prototype.hasOwnProperty.call(delta.stateChanges, key)) || new Set(delta.removedStateKeys).size !== delta.removedStateKeys.length) return
  const state = { ...base.graph.graph, ...delta.stateChanges, variables: originals.map(node => changes.get(node.id) ?? node), events: [...base.graph.graph.events, ...delta.appendedEvents] }
  for (const key of delta.removedStateKeys) delete (state as unknown as Record<string, unknown>)[key]
  const { graphDelta: _delta, ...metadata } = result
  return { ...metadata, graph: { ...base.graph, graph: state } }
}
