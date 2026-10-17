import type { ReviewObject, ReviewState, ReviewVariable } from '../source-review-types'
export function createReviewReducer(data: ReviewObject): { reduce(state: ReviewState, action: ReviewObject): ReviewState; compatible(a: ReviewVariable, b: ReviewVariable): boolean; isolatedSources(state: ReviewState): boolean }
