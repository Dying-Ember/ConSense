<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DraftBlock, DraftBodyCleanupScope } from '@/api/types'
import type { BodyDraft } from '@/drafting/body-edit'
import { bodyScopeConflict, canDeleteBodyBlock, canDeleteBodyScope } from '@/drafting/body-edit'
import type { AppLocale } from '@/i18n'
import { draftWord } from '@/drafting/words'
const props = defineProps<{ blocks: DraftBlock[]; cleanupScopes?: DraftBodyCleanupScope[]; draft: BodyDraft; locale: AppLocale; disabled?: boolean; readonly?: boolean; immersive?: boolean }>()
const emit = defineEmits<{ text: [id: string, value: string]; insert: [id: string, paragraphs: string[]]; remove: [id: string, remove: boolean]; scopeRemove: [id: string, remove: boolean] }>()
const opened = ref<Record<string, boolean>>({})
const selectedId = ref('')
const search = ref('')
const showChanges = ref(false)
const w = (key: Parameters<typeof draftWord>[0]) => draftWord(key, props.locale)
const currentText = (block: DraftBlock) => props.draft.texts[block.id] ?? block.text
const deleting = (block: DraftBlock) => !!props.draft.deletions?.[block.id]
const changed = (block: DraftBlock) => deleting(block) || currentText(block) !== block.text || !!props.draft.insertions[block.id]?.length
const changedCount = computed(() => props.blocks.filter(changed).length)
const cleanupScopes = computed(() => (props.cleanupScopes ?? []).filter(canDeleteBodyScope))
const scopeDeleting = (scope: DraftBodyCleanupScope) => !!props.draft.scopeDeletions?.[scope.scopeId]
const pendingScopeCount = computed(() => cleanupScopes.value.filter(scopeDeleting).length)
const pendingNumberingCount = computed(() => cleanupScopes.value.filter(scope => scopeDeleting(scope) && scope.kind === 'paragraph_numbering').length)
const scopeAction = (scope: DraftBodyCleanupScope) => scope.kind === 'paragraph_numbering' ? (scopeDeleting(scope) ? 'bodyCleanupNumberingCancel' : 'bodyCleanupNumberingMark') : (scopeDeleting(scope) ? 'bodyCleanupCancel' : 'bodyCleanupMark')
const retainedBookmarks = (scope: DraftBodyCleanupScope) => [...(scope.enclosingRetainedBookmarks ?? []), ...(scope.retainedCrossingBookmarks ?? []), ...(scope.retainedBoundaryBookmarks ?? [])].map(bookmark => bookmark.name).join(', ')
const scopeConflict = (scope: DraftBodyCleanupScope) => bodyScopeConflict({ blocks: props.blocks }, props.draft, scope)
function scopeLabel(scope: DraftBodyCleanupScope) {
  if (scope.kind === 'paragraph_numbering') return `${w('bodyCleanupNumberingScope')} · ${w('paragraph')} ${scope.paragraphOrdinals[0]}`
  if (scope.kind === 'body_range') return `${w('bodyCleanupRange')} · ${w('paragraph')} ${scope.paragraphOrdinals[0]}–${scope.paragraphOrdinals[scope.paragraphOrdinals.length - 1]}`
  const path = scope.scopeId.match(/\/w:tbl\[(\d+)\](?:\/w:tr\[(\d+)\])?$/)
  return `${w('bodyCleanupTable')} ${path?.[1] ?? ''}${path?.[2] ? ` · ${w('bodyCleanupRow')} ${path[2]}` : ''}`
}
function removeScope(scope: DraftBodyCleanupScope) {
  if (props.disabled || props.readonly || !canDeleteBodyScope(scope) || !scopeDeleting(scope) && scopeConflict(scope)) return
  emit('scopeRemove', scope.scopeId, !scopeDeleting(scope))
}
const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return props.blocks.filter(block => (block.text.trim() || currentText(block).trim() || props.draft.insertions[block.id]?.length) && (!showChanges.value || changed(block)) && (!needle || [currentText(block), ...(props.draft.insertions[block.id] ?? [])].join('\n').toLowerCase().includes(needle) || String(block.paragraphOrdinal) === needle))
})
function restore(block: DraftBlock) {
  if (props.disabled || props.readonly || !block.editable) return
  if (deleting(block)) emit('remove', block.id, false)
  if (currentText(block) !== block.text) emit('text', block.id, block.text)
  if (props.draft.insertions[block.id]?.length) emit('insert', block.id, [])
}
function remove(block: DraftBlock) {
  if (props.disabled || props.readonly || !canDeleteBodyBlock(block)) return
  selectedId.value = ''
  emit('remove', block.id, !deleting(block))
}
</script>
<template>
  <div class="body-blocks" :class="{ 'body-blocks--immersive': immersive }">
    <p class="hint">{{ w('bodyReaderNote') }}</p>
    <p class="hint">{{ w('bodyEditNote') }}</p>
    <div class="body-toolbar"><input v-model="search" :placeholder="w('searchBody')" :aria-label="w('searchBody')" /><button type="button" class="btn" :aria-pressed="showChanges" @click="showChanges = !showChanges">{{ showChanges ? w('bodyShowAll') : w('bodyShowChanges') }}</button><span v-if="changedCount" class="pending-count" role="status">{{ w('unsaved') }} · {{ changedCount }} {{ w('paragraph') }}</span></div>
    <section v-if="cleanupScopes.length" class="body-cleanup" data-body-cleanup>
      <h4>{{ w('bodyCleanupTitle') }} · {{ cleanupScopes.length }}</h4>
      <p class="hint">{{ w('bodyCleanupNote') }}</p>
      <p class="scope-counts" :class="{ 'pending-count': pendingScopeCount > 0 }" role="status" data-cleanup-pending-count>{{ pendingScopeCount }} {{ w('bodyCleanupPendingCount') }}</p>
      <p class="scope-counts"><span data-cleanup-delete-count>{{ pendingScopeCount - pendingNumberingCount }} {{ w('bodyCleanupDeleteCount') }}</span> · <span data-cleanup-numbering-count>{{ pendingNumberingCount }} {{ w('bodyCleanupFormatCount') }}</span></p>
      <article v-for="scope in cleanupScopes" :key="scope.scopeId" class="cleanup-scope" :class="{ 'cleanup-scope--pending': scopeDeleting(scope) }" :data-cleanup-scope="scope.scopeId">
        <strong>{{ scopeLabel(scope) }}</strong>
        <p class="scope-counts">{{ scope.memberBindingIds.length }} {{ w('bodyCleanupMembers') }} · {{ scope.paragraphCount }} {{ w('bodyCleanupParagraphs') }} · {{ scope.nativeNumberingCount ?? 0 }} {{ w('bodyCleanupNumbering') }}</p>
        <p v-if="scopeDeleting(scope)" class="pending-count" role="status">{{ w(scope.kind === 'paragraph_numbering' ? 'bodyCleanupNumberingPending' : 'bodyCleanupPending') }}</p>
        <p v-else-if="scopeConflict(scope)" class="hint">{{ w('bodyCleanupConflict') }}</p>
        <details><summary>{{ w('bodyCleanupDetails') }}</summary>
          <p class="scope-detail">{{ w('paragraph') }}: {{ scope.paragraphOrdinals.join(', ') }}</p>
          <p class="scope-detail">{{ w('bodyCleanupMembers') }}: {{ scope.memberBindingIds.join(', ') }}</p>
          <p v-if="retainedBookmarks(scope)" class="scope-detail">{{ w('bodyCleanupRetainedBookmarks') }}: {{ retainedBookmarks(scope) }}</p>
          <template v-if="scope.previousText"><strong>{{ w('bodyCleanupPrevious') }}</strong><p class="paragraph-text">{{ scope.previousText }}</p></template>
          <template v-if="scope.nextText"><strong>{{ w('bodyCleanupNext') }}</strong><p class="paragraph-text">{{ scope.nextText }}</p></template>
        </details>
        <button v-if="!readonly" type="button" class="btn" :disabled="disabled || !scopeDeleting(scope) && scopeConflict(scope)" :data-delete-empty-scope="scope.scopeId" :aria-label="`${w(scopeAction(scope))} ${scopeLabel(scope)}`" @click="removeScope(scope)">{{ w(scopeAction(scope)) }}</button>
      </article>
    </section>
    <p v-if="!visible.length" class="hint" role="status">{{ blocks.length ? w('bodyNoMatches') : w('bodyNoParagraphs') }}</p>
    <article v-for="block in visible" :key="block.id" class="body-block" :data-block-id="block.id">
      <p v-if="deleting(block)" class="pending-count" role="status">{{ w('bodyPendingDeletion') }}</p>
      <div class="block-heading"><span class="paragraph-label">{{ w('paragraph') }} {{ block.paragraphOrdinal }}</span><span v-if="changed(block)" class="pending-count">{{ w('bodyModified') }}</span><span v-if="!block.editable" class="hint">{{ w('protectedBody') }}</span><template v-if="block.editable && !readonly"><button v-if="changed(block)" type="button" class="btn" :disabled="disabled" @click="restore(block)">{{ w('bodyRestoreParagraph') }}</button><button type="button" class="btn" :disabled="disabled || deleting(block)" :aria-label="`${selectedId === block.id ? w('bodyDoneEditing') : w('bodyEditParagraph')} ${block.paragraphOrdinal}`" :aria-expanded="selectedId === block.id" @click="selectedId = selectedId === block.id ? '' : block.id">{{ selectedId === block.id ? w('bodyDoneEditing') : w('bodyEditParagraph') }}</button></template></div>
      <p class="paragraph-text" lang="en">{{ draft.texts[block.id] ?? block.text }}</p>
      <button v-if="canDeleteBodyBlock(block) && !readonly" type="button" class="btn" :disabled="disabled" :data-delete-paragraph="block.id" @click="remove(block)">{{ w(deleting(block) ? 'bodyCancelDeletion' : 'bodyDeleteParagraph') }}</button>
      <template v-if="selectedId === block.id && block.editable && !readonly && !deleting(block)">
        <div class="original-text"><strong>{{ w('bodyOriginalText') }}</strong><p class="paragraph-text" lang="en">{{ block.text }}</p></div>
        <label :for="`body-p-${block.paragraphOrdinal}`">{{ w('bodyDraftText') }}</label>
        <textarea :id="`body-p-${block.paragraphOrdinal}`" :value="draft.texts[block.id] ?? block.text" :rows="Math.min(8, Math.max(3, Math.ceil(block.text.length / 110)))" lang="en" :disabled="disabled" @input="emit('text', block.id, ($event.target as HTMLTextAreaElement).value)" />
        <button type="button" class="btn" :disabled="disabled" @click="opened[block.id] = !opened[block.id]">{{ w('insertParagraphs') }}</button>
        <label v-if="opened[block.id] || draft.insertions[block.id]?.length">{{ w('newParagraphs') }}<textarea :value="draft.insertions[block.id]?.join('\n') ?? ''" rows="3" lang="en" :disabled="disabled" @input="emit('insert', block.id, ($event.target as HTMLTextAreaElement).value ? ($event.target as HTMLTextAreaElement).value.split('\n') : [])" /></label>
      </template>
      <div v-if="draft.insertions[block.id]?.length" class="added-paragraphs"><strong>{{ w('bodyAddedParagraphs') }}</strong><p v-for="(paragraph, index) in draft.insertions[block.id]" :key="index" class="paragraph-text" lang="en">{{ paragraph }}</p></div>
    </article>
  </div>
</template>
<style scoped>
.body-blocks { display: flex; flex-direction: column; gap: 16px; max-height: 70vh; overflow: auto; padding: 8px; }
.body-blocks--immersive { height: 100%; max-height: none; min-height: 0; box-sizing: border-box; }
.body-block { border-bottom: 1px solid var(--line); padding: 8px 0 16px; }
.block-heading { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.body-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.body-toolbar input { flex: 1; min-width: min(220px, 100%); }
.body-cleanup { border: 1px solid var(--line); border-radius: 8px; padding: 14px; }
.body-cleanup h4 { margin: 0 0 8px; }
.cleanup-scope { padding: 12px 0; border-top: 1px solid var(--line); }
.cleanup-scope--pending { border-left: 3px solid var(--amber); padding-left: 12px; }
.scope-counts, .scope-detail { font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.cleanup-scope summary { cursor: pointer; font-size: 12px; margin-top: 10px; }
.pending-count { font-size: 12px; font-weight: 700; color: var(--amber); }
.paragraph-label { font-size: 11.5px; font-weight: 700; color: var(--muted); }
.paragraph-text { white-space: pre-wrap; overflow-wrap: anywhere; font-family: Georgia, 'Times New Roman', serif; font-size: 15px; line-height: 1.75; margin: 12px 0; }
.original-text { padding: 10px 14px; border-left: 3px solid var(--line); background: var(--surface-subtle); font-size: 12px; }
.added-paragraphs { border-left: 3px solid var(--accent); padding-left: 14px; margin-top: 14px; font-size: 12px; }
.hint { font-size: 12px; line-height: 1.55; color: var(--muted); }
label { display: block; font-size: 11.5px; font-weight: 700; color: var(--muted); margin: 8px 0; }
textarea, input { width: 100%; min-height: 36px; box-sizing: border-box; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; font: inherit; background: var(--surface); color: var(--ink); }
textarea { resize: vertical; font-family: Georgia, 'Times New Roman', serif; font-weight: 400; font-size: 14px; line-height: 1.55; }
textarea[readonly] { background: var(--surface-subtle); }
button { margin-top: 8px; }
</style>
