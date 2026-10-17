import type { ReviewObject, ReviewState } from '../source-review-types'
declare const overview: { project(state: ReviewState): ReviewObject; neighborhood(graph: ReviewObject, id: string): ReviewObject; impact(graph: ReviewObject, id: string): ReviewObject }
export default overview
