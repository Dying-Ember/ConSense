import type { DraftValue } from './state'

export type BillPlacement = 'discA' | 'discB' | 'hardcopy' | 'discC' | 'custom' | 'pending'
export type BillPortion = 'bodyWithoutTotals' | 'totalsOnly' | 'wholeBq' | 'excelCompanion' | 'preamblesPdf' | 'l10Bq' | 'adoptedPlacement' | 'sorPlacementPending' | 'pricingTypePending' | 'issuePending' | 'customPlacementPending'
export type BillSourceReview = 'hardcopyDiscBReview' | 'l10HardcopyReview' | 'customPlacementReview'
export type BillDistributionRow = { id: string; number: string; description: string; type: string; portion: BillPortion; exactText?: string; rolePending?: boolean; sourceAmendmentRequired?: boolean; reviewReason?: BillSourceReview }
export type BillDistributionGroup = { placement: BillPlacement; rows: BillDistributionRow[] }

/** A presentation of shared facts, never a rewriting of the Bill records. */
export function billDistribution(values: Record<string, DraftValue>) {
  const mode = values.electronicTendering === 'L10Pro' ? 'L10Pro' : values.electronicTendering === 'Hardcopy' ? 'Hardcopy' : 'unknown'
  const groups = new Map<BillPlacement, BillDistributionRow[]>()
  function add(placement: BillPlacement, row: BillDistributionRow, portion: BillPortion, extra: Partial<BillDistributionRow> = {}) {
    if (!groups.has(placement)) groups.set(placement, [])
    groups.get(placement)!.push({ ...row, portion, ...extra })
  }
  for (const [index, item] of (Array.isArray(values.billNos) ? values.billNos : []).entries()) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue
    const row: BillDistributionRow = { id: String(item.id ?? `row-${index}`), number: String(item.number ?? ''), description: String(item.description ?? ''), type: String(item.type ?? ''), portion: 'issuePending' }
    const placement = String(item.placement ?? '')
    const exactText = typeof item.placementText === 'string' ? item.placementText : ''
    if (!['BQ', 'SOR'].includes(row.type)) { add('pending', row, 'pricingTypePending'); continue }
    if (placement === 'custom') {
      add(exactText.trim() ? 'custom' : 'pending', row, exactText.trim() ? 'adoptedPlacement' : 'customPlacementPending', { exactText, sourceAmendmentRequired: true, reviewReason: 'customPlacementReview' })
      continue
    }
    if (['DiscA', 'DiscB', 'Hardcopy'].includes(placement)) {
      const reviewReason: BillSourceReview | undefined = mode === 'Hardcopy' && placement === 'DiscB' ? 'hardcopyDiscBReview' : mode === 'L10Pro' && placement === 'Hardcopy' ? 'l10HardcopyReview' : undefined
      add(({ DiscA: 'discA', DiscB: 'discB', Hardcopy: 'hardcopy' } as Record<string, BillPlacement>)[placement]!, row, 'adoptedPlacement', { exactText, sourceAmendmentRequired: !!reviewReason, reviewReason })
      continue
    }
    if (row.type === 'SOR') { add('pending', row, 'sorPlacementPending'); continue }
    if (mode === 'unknown') { add('pending', row, 'issuePending'); continue }
    const numeric = /^\d+$/.test(row.number.trim()) ? Number(row.number) : null
    const role = String(item.purpose ?? '').trim()
    const label = row.description.trim().toLowerCase()
    const isFirst = numeric === 1, isSecond = numeric === 2
    const rolePending = isFirst ? role !== 'preliminaries' && label !== 'preliminaries' : isSecond ? role !== 'preambles' && label !== 'preambles' : false
    if (mode === 'L10Pro') {
      if (isFirst || isSecond) { add('discA', row, 'bodyWithoutTotals', { rolePending }); add('discB', row, 'totalsOnly', { rolePending }) }
      else add('discB', row, 'l10Bq')
    } else {
      if (isSecond) add('discA', row, 'preamblesPdf', { rolePending })
      else { add('hardcopy', row, 'wholeBq', { rolePending }); add('discC', row, 'excelCompanion', { rolePending }) }
    }
  }
  const order: BillPlacement[] = ['discA', 'discB', 'hardcopy', 'discC', 'custom', 'pending']
  return { mode, groups: order.filter(placement => groups.has(placement)).map(placement => ({ placement, rows: groups.get(placement)! })), customTendering: ![true, 'true'].includes(values.twoEnvelopeTendering as boolean | string) }
}
