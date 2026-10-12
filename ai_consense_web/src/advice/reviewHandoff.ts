import type { FindingEvidence, LocalizedText } from '@/api/types'
import { fixedEvidencePath } from '@/api/fixedBaselineEvidence'

const STORAGE_KEY = 'consense:advice:review-handoff:v1'
const SCHEMA = 'consense.review-advice-handoff.v1'
export const HANDOFF_TTL_MS = 30 * 60 * 1000
const MAX_LENGTH = 250_000

export interface ReviewHandoff {
  schema: typeof SCHEMA
  token: string
  projectId: string
  createdAt: number
  findingCode: string
  title: LocalizedText
  description: LocalizedText
  evidence: FindingEvidence[]
  sourceSetStale: boolean
}

type StoragePort = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
type ClaimResult = { status: 'claimed'; handoff: ReviewHandoff } | {
  status: 'missing' | 'invalid' | 'expired' | 'project_mismatch' | 'storage_unavailable'
}

export const reviewHandoffCopy = {
  consult: { zhHans: '带入合约咨询', zhHant: '帶入合約諮詢', en: 'Consult in Advisory' },
  entryNote: { zhHans: '带入问题与原文引用作为可编辑草稿，由你提交咨询。', zhHant: '帶入問題與原文引用作為可編輯草稿，由你提交諮詢。', en: 'Open an editable question with original quotations, then submit it yourself.' },
  saveFirst: { zhHans: '请先保存或放弃未保存的审阅／清单修改，再进入咨询。', zhHant: '請先儲存或放棄未儲存的審閱／清單修改，再進入諮詢。', en: 'Save or discard unsaved review or checklist edits before opening Advisory.' },
  transferFailed: { zhHans: '未能带入咨询草稿，请重试。', zhHant: '未能帶入諮詢草稿，請重試。', en: 'The question draft could not be transferred. Please try again.' },
  draft: { zhHans: '来自审查的问题草稿', zhHant: '來自審查的問題草稿', en: 'Question draft from Vetting' },
  draftNote: { zhHans: '审查发现与所附引用只是核查线索。可以编辑下面的问题；提交后按当前选定项目与范围独立检索原件。', zhHant: '審查發現與所附引用只是核查線索。可以編輯下面的問題；提交後按目前選定項目與範圍獨立檢索原件。', en: 'The finding and quotations are leads for verification. Edit the question below; submitting uses independent retrieval from the current project and scope.' },
  stale: { zhHans: '审查时的来源已变化，所附引用和位置可能已过期，请按当前原件重新核对。', zhHant: '審查時的來源已變化，所附引用和位置可能已過期，請按目前原件重新核對。', en: 'Sources changed after this review. These quotations and locations may be stale; check the current originals.' },
  unverified: { zhHans: '所附引用的版本尚未核验；不能把旧引用当作当前证据。', zhHant: '所附引用的版本尚未核驗；不能把舊引用當作目前證據。', en: 'Quotation versions have not been verified. Old quotations cannot be treated as current evidence.' },
  sourceUnavailable: { zhHans: '部分引用来源不在当前咨询范围内，请检查上方的资料范围。', zhHant: '部分引用來源不在目前諮詢範圍內，請檢查上方的資料範圍。', en: 'Some quoted sources are outside the current Advisory scope. Check the document scope above.' },
  noQuotes: { zhHans: '此发现没有原文引用，咨询需要从当前原件独立查证。', zhHant: '此發現沒有原文引用，諮詢需要從目前原件獨立查證。', en: 'This finding has no original quotations. Advisory must verify it independently against current originals.' },
  invalid: { zhHans: '咨询草稿已使用、过期或不可读取，请从审查重新带入。', zhHant: '諮詢草稿已使用、過期或不可讀取，請從審查重新帶入。', en: 'This question draft was already used, expired, or cannot be read. Open it again from Vetting.' },
  wrongProject: { zhHans: '该咨询草稿属于另一个项目，已拒绝带入，请返回原项目的审查重新打开。', zhHant: '該諮詢草稿屬於另一個項目，已拒絕帶入，請返回原項目的審查重新開啟。', en: 'This question belongs to another project and was not imported. Reopen it from that project’s Vetting view.' },
  remove: { zhHans: '清除带入草稿', zhHant: '清除帶入草稿', en: 'Clear imported draft' },
  prompt: { zhHans: '请根据当前项目原件独立核查下面的审查发现，说明适用条件并提供当前原文出处。审查意见及历史引用仅供核查，不能代替当前证据。', zhHant: '請根據目前項目原件獨立核查下面的審查發現，說明適用條件並提供目前原文出處。審查意見及歷史引用僅供核查，不能代替目前證據。', en: 'Independently check the following review finding against the current project originals, explain applicable conditions, and cite current sources. The finding and historical quotations are leads, not current evidence.' },
  finding: { zhHans: '审查发现', zhHant: '審查發現', en: 'Review finding' },
  quotation: { zhHans: '待核查的原文引用', zhHant: '待核查的原文引用', en: 'Original quotation to verify' },
  request: { zhHans: '请核对引用与当前版本，再回答这个问题。', zhHant: '請核對引用與目前版本，再回答這個問題。', en: 'Check the quotations against the current version before answering this question.' },
  identity: { zhHans: '来源身份', zhHant: '來源身份', en: 'Source identity' }
} satisfies Record<string, LocalizedText>

function text(value: unknown): value is string { return typeof value === 'string' }
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
function localized(value: unknown): value is LocalizedText {
  return object(value) && text(value.zhHans) && text(value.zhHant) && text(value.en)
}
function evidenceValid(value: unknown): value is FindingEvidence {
  if (object(value) && value.fixedBaselineEvidence != null) {
    try { fixedEvidencePath(value.fixedBaselineEvidence as NonNullable<FindingEvidence['fixedBaselineEvidence']>, 'evidence') }
    catch { return false }
  }
  return object(value) && text(value.side) && text(value.fileKey) && text(value.fileName) && text(value.quote)
    && typeof value.located === 'boolean'
    && (value.documentId == null || text(value.documentId) || typeof value.documentId === 'number' && Number.isFinite(value.documentId))
    && ['sourceHash', 'pageNo', 'anchor'].every(key => value[key] == null || text(value[key]))
}
function valid(value: unknown): value is ReviewHandoff {
  return object(value) && value.schema === SCHEMA && text(value.token) && /^[a-zA-Z0-9-]{16,80}$/.test(value.token)
    && text(value.projectId) && !!value.projectId.trim() && typeof value.createdAt === 'number' && Number.isFinite(value.createdAt)
    && text(value.findingCode) && !!value.findingCode.trim() && localized(value.title) && localized(value.description)
    && typeof value.sourceSetStale === 'boolean' && Array.isArray(value.evidence) && value.evidence.every(evidenceValid)
}

// Keep only the issue and literal source quotations: no report, review edits, or suggested answer.
export function createReviewHandoff(input: {
  projectId: string; findingCode: string; title: LocalizedText; description: LocalizedText
  evidence: FindingEvidence[]; sourceSetStale: boolean
}, token: string, now = Date.now()): ReviewHandoff {
  const handoff = {
    schema: SCHEMA, token, projectId: input.projectId, createdAt: now, findingCode: input.findingCode,
    title: { ...input.title }, description: { ...input.description }, sourceSetStale: input.sourceSetStale,
    evidence: input.evidence.map(item => ({
      side: item.side, documentId: item.documentId, fileKey: item.fileKey, fileName: item.fileName,
      pageNo: item.pageNo, anchor: item.anchor, quote: item.quote, located: item.located, sourceHash: item.sourceHash,
      ...(item.fixedBaselineEvidence ? { fixedBaselineEvidence: { ...item.fixedBaselineEvidence } } : {})
    }))
  }
  if (!valid(handoff)) throw new Error('invalid_review_handoff')
  return handoff
}

export function storeReviewHandoff(storage: StoragePort, handoff: ReviewHandoff): void {
  const serialized = JSON.stringify(handoff)
  if (!valid(handoff) || serialized.length > MAX_LENGTH) throw new Error('invalid_review_handoff')
  storage.setItem(STORAGE_KEY, serialized)
}

export function newReviewHandoffToken(): string {
  // getRandomValues is also available on a plain HTTP LAN origin; randomUUID is not.
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, '0')).join('')
}

export function claimReviewHandoff(storage: StoragePort, token: unknown, projectId: string, now = Date.now()): ClaimResult {
  if (!text(token) || !/^[a-zA-Z0-9-]{16,80}$/.test(token)) return { status: 'invalid' }
  try {
    const serialized = storage.getItem(STORAGE_KEY)
    if (serialized === null) return { status: 'missing' }
    let value: unknown
    try { value = serialized.length <= MAX_LENGTH ? JSON.parse(serialized) : null } catch { value = null }
    if (!valid(value)) { storage.removeItem(STORAGE_KEY); return { status: 'invalid' } }
    if (value.token !== token) return { status: 'missing' }
    // Remove before applying, including failed identity/expiry checks. Reload cannot replay it.
    storage.removeItem(STORAGE_KEY)
    if (value.projectId !== projectId) return { status: 'project_mismatch' }
    if (value.createdAt > now + 60_000 || now - value.createdAt > HANDOFF_TTL_MS) return { status: 'expired' }
    return { status: 'claimed', handoff: value }
  } catch { return { status: 'storage_unavailable' } }
}

export function removeReviewHandoff(storage: StoragePort, token: string): void {
  try {
    const serialized = storage.getItem(STORAGE_KEY)
    if (serialized && JSON.parse(serialized)?.token === token) storage.removeItem(STORAGE_KEY)
  } catch { /* An unavailable draft is never applied. */ }
}

export function reviewQuestion(handoff: ReviewHandoff, pick: (value: LocalizedText) => string): string {
  const lines = [pick(reviewHandoffCopy.prompt), '', `${pick(reviewHandoffCopy.finding)} ${handoff.findingCode}: ${pick(handoff.title)}`, pick(handoff.description)]
  if (handoff.sourceSetStale) lines.push('', pick(reviewHandoffCopy.stale))
  lines.push('', pick(reviewHandoffCopy.unverified))
  handoff.evidence.forEach((item, index) => {
    lines.push('', `${pick(reviewHandoffCopy.quotation)} ${index + 1}: ${[item.fileKey, item.fileName, item.pageNo, item.anchor].filter(Boolean).join(' · ')}`,
      `${pick(reviewHandoffCopy.identity)}: ${JSON.stringify({ documentId: item.documentId ?? null, sourceHash: item.sourceHash ?? null, ...(item.fixedBaselineEvidence ? { fixedBaselineEvidence: item.fixedBaselineEvidence } : {}) })}`, item.quote)
  })
  // Keep the last quotation's literal trailing whitespace inside the draft even when the composer trims its envelope.
  lines.push('', pick(reviewHandoffCopy.request))
  return lines.join('\n')
}

export function handoffSourceWarnings(handoff: ReviewHandoff, documents: { documentId: string; included: boolean; sourceHash?: string | null }[], loaded: boolean) {
  let unavailable = false
  let unverified = handoff.evidence.length === 0
  let stale = handoff.sourceSetStale
  for (const item of handoff.evidence) {
    if (item.fixedBaselineEvidence) { unverified = true; continue }
    const current = documents.find(document => item.documentId != null && document.documentId === String(item.documentId))
    if (loaded && (!current || !current.included)) unavailable = true
    if (!item.sourceHash || !current?.sourceHash) unverified = true
    else if (current.sourceHash !== item.sourceHash) stale = true
  }
  return { stale, unverified, unavailable }
}
