import type { ReviewObject, ReviewState } from '../source-review-types'
declare const view: { build(state: ReviewState, options?: ReviewObject): ReviewObject; selection(view: ReviewObject, id: string): ReviewObject }
export default view
