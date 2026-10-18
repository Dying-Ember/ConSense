/** Shared JSON value and editor classifications. Multiselect has its own editor. */
const collectionKinds = new Set(['bills', 'list', 'table', 'records', 'multiselect'])
const objectKinds = new Set(['contract', 'object'])

export const isCollectionKind = (kind: string): boolean => collectionKinds.has(kind)
export const isRecordCollectionKind = (kind: string): boolean => isCollectionKind(kind) && kind !== 'multiselect'
export const isObjectKind = (kind: string): boolean => objectKinds.has(kind)
export const isVisibleDetail = (field: { key: string; hidden?: boolean }): boolean => !field.hidden && (field.key !== 'id' || field.hidden === false)
