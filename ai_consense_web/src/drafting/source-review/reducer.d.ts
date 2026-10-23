import type { ReviewObject, ReviewState, ReviewVariable } from '../source-review-types'
export interface MergeProblem { code: string; id: string; targetId: string }
export function createReviewReducer(data: ReviewObject): {
  reduce(state: ReviewState, action: ReviewObject): ReviewState
  compatible(a: ReviewVariable, b: ReviewVariable): boolean
  isolatedSources(state: ReviewState): boolean
  mergeProblem(state: ReviewState, id: string, targetId: string): MergeProblem | null
  mergeMany(state: ReviewState, ids: string[], targetId: string, at?: string, reason?: string): { state: ReviewState; problem: MergeProblem | null }
}
