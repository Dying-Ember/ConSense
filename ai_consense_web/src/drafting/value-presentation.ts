import type { DraftField } from '@/api/types'
import type { AppLocale } from '@/i18n'
import type { DraftValue } from './state'
import { isCollectionKind, isObjectKind, isVisibleDetail } from './field-kinds'
import { localized } from './words'
import { previewWord } from './preview-words'

/** Read-only decoding for current values and serialized extraction candidates. */
export function presentationValue(field: DraftField, raw: DraftValue | undefined): { value: DraftValue | undefined; invalid: boolean } {
  let value = raw
  if ((isCollectionKind(field.kind) || isObjectKind(field.kind)) && typeof value === 'string' && value !== '') {
    try { value = JSON.parse(value) as DraftValue } catch { return { value, invalid: true } }
  }
  if (value == null || value === '') return { value, invalid: false }
  const invalid = isCollectionKind(field.kind) ? !Array.isArray(value)
    : isObjectKind(field.kind) ? typeof value !== 'object' || Array.isArray(value)
      : typeof value === 'object'
  const invalidMember = Array.isArray(value) && (field.kind === 'multiselect'
    ? value.some(item => item !== null && typeof item === 'object')
    : !!field.columnFields?.length && value.some(item => item === null || typeof item !== 'object' || Array.isArray(item)))
  return { value, invalid: invalid || invalidMember }
}

export function presentationColumns(field: DraftField, records: DraftValue[]): DraftField[] {
  if (field.columnFields?.length) return field.columnFields.filter(isVisibleDetail)
  if (field.kind === 'contract') return [
    { key: 'number', kind: 'text', label: { en: 'Contract number', zhHans: '合约编号', zhHant: '合約編號' } },
    { key: 'title', kind: 'text', label: { en: 'Contract title', zhHans: '合约名称', zhHant: '合約名稱' } }
  ]
  const keys = [...new Set(records.flatMap(record => record && typeof record === 'object' && !Array.isArray(record) ? Object.keys(record) : []))].filter(key => key !== 'id')
  return keys.map(key => {
    const label = key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ')
    return { key, kind: 'text', label: { en: label, zhHans: label, zhHant: label } }
  })
}

export function scalarValueLabel(field: DraftField, value: DraftValue | undefined, locale: AppLocale): string {
  if (value == null || value === '') return previewWord('unknown', locale)
  const option = field.options?.find(option => String(option.value) === String(value))
  if (option) return localized(option.label, locale)
  if (field.kind === 'boolean' && ['true', 'false'].includes(String(value))) return previewWord(String(value) as 'true' | 'false', locale)
  return typeof value === 'object' ? previewWord('invalidValue', locale) : String(value)
}

/** Source markers are navigation controls, so a collection is represented by its count. */
export function valueSummary(field: DraftField, raw: DraftValue | undefined, locale: AppLocale): string {
  const { value, invalid } = presentationValue(field, raw)
  if (invalid) return previewWord('invalidValue', locale)
  if (value == null || value === '') return previewWord('unknown', locale)
  if (Array.isArray(value)) return `${value.length} ${previewWord(value.length === 1 ? 'item' : 'items', locale)}`
  let label: string
  if (typeof value === 'object') {
    const columns = presentationColumns(field, [value])
    const supplied = columns.filter(column => value[column.key] != null && value[column.key] !== '')
    label = field.kind === 'contract' ? supplied.map(column => scalarValueLabel(column, value[column.key], locale)).join(' · ')
      : `${supplied.length} ${previewWord('valueEntries', locale)}`
    if (!supplied.length) return previewWord('unknown', locale)
  } else label = scalarValueLabel(field, value, locale)
  const characters = Array.from(label.replace(/\s+/g, ' ').trim())
  return characters.length > 80 ? `${characters.slice(0, 80).join('')}…` : characters.join('')
}
