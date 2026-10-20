/** Call only after sanitization; keep native text, identities and list structure intact. */
export function hideUnverifiedListMarkers(flow: HTMLElement): number {
  // shortcut: hide converted markers until native labels are available for this exact revision.
  const lists = flow.querySelectorAll('ol,ul')
  for (const list of lists) list.classList.add('docx-unverified-list')
  return lists.length
}
