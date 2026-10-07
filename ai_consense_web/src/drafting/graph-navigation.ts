/** Exact graph clause request; an empty actionId denotes a catalogue target. */
export interface GraphLocationTarget {
  fieldKey: string
  actionId: string
  document: string
  clause: string
}

/** Parent-routed receipt for the current project/file and one navigation intent. */
export interface GraphNavigation extends Pick<GraphLocationTarget, 'fieldKey' | 'actionId' | 'clause'> {
  sequence: number
  projectId: string
  fileKey: string
}
