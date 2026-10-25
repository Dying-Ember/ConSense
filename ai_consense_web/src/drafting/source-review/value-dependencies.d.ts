import type { ReviewObject, ReviewState } from '../source-review-types'
export function isSourceCheck(node?: ReviewObject): boolean
export function valueDependencies(state: ReviewState): { from: string; to: string; kind: string; cyclic: boolean }[]
