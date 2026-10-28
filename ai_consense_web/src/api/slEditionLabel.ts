const labels: Record<string, string> = {
  'sl2022-original': 'SL2022 Original',
  'sl2022-batch01': 'SL2022 Batch 1',
  'sl2022-batch02-rev1': 'SL2022 Batch 2 revision 1',
  'sl2022-batch03': 'SL2022 Batch 3',
  'sl2022-batch04': 'SL2022 Batch 4',
  'sl2026-original': 'SL2026 Original'
}

export const slEditionLabel = (id?: string | null) => id ? labels[id] : undefined
