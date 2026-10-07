import type { DraftField, DraftPlanAction, DraftTarget, TemplateReading } from '@/api/types'

export type TemplateLocationQuality = 'exact' | 'approximate' | 'missing'
export interface TemplateLocation {
  id: string
  document: string
  clause: string
  actionId?: string
  fieldKeys: string[]
  paragraphId: string | null
  ordinal: number | null
  quality: TemplateLocationQuality
  sourceText: string
}

const normalize = (text: string) => text.normalize('NFKC').replace(/\s+/g, ' ').trim()

function inScope(ordinal: number, document: string, scope?: string): boolean {
  return (scope ?? '').split(/[,;，]/).some(part => {
    const referencedDocument = part.match(/\b(NTT|SCT|SCC)(?:\d+)?\b/i)?.[1]?.toUpperCase()
    if (referencedDocument && referencedDocument !== document || /\bWWQS\b/i.test(part)) return false
    return [...part.matchAll(/(?:^|[^A-Za-z0-9])[Pp]?(\d+)\s*(?:[-–—]\s*[Pp]?(\d+))?(?=$|[^A-Za-z0-9])/g)]
      .some(match => ordinal >= Number(match[1]) && ordinal <= Number(match[2] ?? match[1]))
  })
}

/** Navigation only. Matches never decide adoption or clause treatment. */
export function locateTemplateTargets(reading: TemplateReading, fields: DraftField[], actions: DraftPlanAction[]): TemplateLocation[] {
  const locations: TemplateLocation[] = []
  const targets: Array<{ target: DraftTarget; action?: DraftPlanAction; keys: string[] }> = []
  for (const action of actions) {
    if (action.document !== reading.fileKey) continue
    targets.push({ target: action, action, keys: [...new Set([...(action.inputKeys ?? []), ...(action.fieldKeys ?? [])])] })
  }
  for (const field of fields) for (const target of field.affects ?? []) {
    if (target.document !== reading.fileKey) continue
    if (targets.some(item => item.keys.includes(field.key) && item.target.clause === target.clause && item.target.paragraphs === target.paragraphs)) continue
    targets.push({ target, keys: [field.key] })
  }
  for (const [index, item] of targets.entries()) {
    const { target, action, keys } = item
    const base = { document: reading.fileKey, clause: target.clause, actionId: action?.id, fieldKeys: keys, sourceText: action?.sourceText ?? '' }
    const source = normalize(action?.sourceText ?? '')
    let matched = reading.catalogueSourceVerified ? reading.paragraphs.filter(paragraph => inScope(paragraph.ordinal, reading.fileKey, target.paragraphs)) : []
    let quality: TemplateLocationQuality = 'exact'
    // A current source quote takes precedence over a mismatching catalogue range.
    if (source && matched.length) matched = matched.filter(paragraph => !!normalize(paragraph.text) && source.includes(normalize(paragraph.text)))
    if (!matched.length && source) {
      matched = reading.paragraphs.filter(paragraph => normalize(paragraph.text) === source)
      quality = matched.length === 1 ? 'exact' : 'approximate'
    }
    if (!matched.length) {
      const terms = [target.clause, ...keys.flatMap(key => [key, fields.find(field => field.key === key)?.label.en ?? ''])].map(normalize).filter(term => term.length >= 4)
      matched = reading.paragraphs.filter(paragraph => terms.some(term => normalize(paragraph.text).toLowerCase().includes(term.toLowerCase())))
      quality = 'approximate'
    }
    for (const paragraph of matched) locations.push({ ...base, id: `${action?.id ?? `field-${index}`}:${paragraph.id}`, paragraphId: paragraph.id, ordinal: paragraph.ordinal, quality })
    if (!matched.length) locations.push({ ...base, id: action?.id ?? `field-${index}`, paragraphId: null, ordinal: null, quality: 'missing' })
  }
  return locations
}
