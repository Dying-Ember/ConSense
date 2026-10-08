/** Render identity only: source/editor ids and their business values stay untouched. */
export function presentationRowKeys(rows: readonly unknown[]): string[] {
  const ids = rows.map(row => {
    if (!row || typeof row !== 'object' || Array.isArray(row)) return null
    const value = (row as Record<string, unknown>).id
    return value === null || value === undefined || value === '' ? null : String(value)
  })
  const counts = new Map<string, number>()
  for (const id of ids) if (id !== null) counts.set(id, (counts.get(id) ?? 0) + 1)
  const occurrences = new Map<string, number>()
  return ids.map((id, index) => {
    if (id === null) return `position:${index}`
    if (counts.get(id) === 1) return `id:${JSON.stringify(id)}`
    const occurrence = occurrences.get(id) ?? 0
    occurrences.set(id, occurrence + 1)
    return `duplicate:${JSON.stringify(id)}:${occurrence}`
  })
}
