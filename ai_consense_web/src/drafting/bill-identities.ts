type BillRow = Record<string, unknown>
const safetyPurposes = new Set(['bsSafety', 'electricalSafety', 'fireSafety', 'fireWaterSafety'])
function record(value: unknown): BillRow | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as BillRow : null
}
function identity(row: BillRow): string {
  return `${String(row.type ?? '').trim().toUpperCase()}:${String(row.number ?? '').trim().toUpperCase()}`
}
function sameSafetyBill(first: BillRow, second: BillRow): boolean {
  return identity(first) === identity(second) && ['BQ', 'SOR'].includes(String(first.type ?? ''))
    && typeof first.description === 'string' && !!first.description.trim() && first.description === second.description
    && safetyPurposes.has(String(first.purpose ?? '')) && safetyPurposes.has(String(second.purpose ?? '')) && first.purpose !== second.purpose
    && String(first.placement ?? '') === String(second.placement ?? '') && String(first.placementText ?? '') === String(second.placementText ?? '')
}
export function conflictingBillDuplicates(rows: readonly unknown[]): boolean {
  const seen = new Map<string, BillRow[]>()
  for (const value of rows) {
    const row = record(value)
    if (!row) continue
    const previous = seen.get(identity(row)) ?? []
    if (previous.some(other => !sameSafetyBill(other, row))) return true
    previous.push(row)
    seen.set(identity(row), previous)
  }
  return false
}
/** Keep purpose associations editable while listing their physical pricing document once. */
export function physicalBillRows<T>(rows: readonly T[]): T[] {
  if (conflictingBillDuplicates(rows)) return [...rows]
  const seen = new Set<string>()
  return rows.filter(value => {
    const row = record(value)
    if (!row) return true
    const key = identity(row)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
