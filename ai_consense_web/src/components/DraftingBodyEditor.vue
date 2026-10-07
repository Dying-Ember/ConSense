<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DraftBlock } from '@/api/types'
import type { BodyDraft } from '@/drafting/body-edit'
import type { AppLocale } from '@/i18n'
import { draftWord } from '@/drafting/words'
const props = defineProps<{ blocks: DraftBlock[]; draft: BodyDraft; locale: AppLocale; disabled?: boolean; readonly?: boolean; immersive?: boolean }>()
const emit = defineEmits<{ text: [id: string, value: string]; insert: [id: string, paragraphs: string[]] }>()
const opened = ref<Record<string, boolean>>({})
const selectedId = ref('')
const search = ref('')
const showChanges = ref(false)
const w = (key: Parameters<typeof draftWord>[0]) => draftWord(key, props.locale)
const currentText = (block: DraftBlock) => props.draft.texts[block.id] ?? block.text
const changed = (block: DraftBlock) => currentText(block) !== block.text || !!props.draft.insertions[block.id]?.length
const changedCount = computed(() => props.blocks.filter(changed).length)
const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  return props.blocks.filter(block => (block.text.trim() || currentText(block).trim() || props.draft.insertions[block.id]?.length) && (!showChanges.value || changed(block)) && (!needle || [currentText(block), ...(props.draft.insertions[block.id] ?? [])].join('\n').toLowerCase().includes(needle) || String(block.paragraphOrdinal) === needle))
})
function restore(block: DraftBlock) {
  if (props.disabled || props.readonly || !block.editable) return
  if (currentText(block) !== block.text) emit('text', block.id, block.text)
  if (props.draft.insertions[block.id]?.length) emit('insert', block.id, [])
}
</script>
<template>
  <div class="body-blocks" :class="{ 'body-blocks--immersive': immersive }">
    <p class="hint">{{ w('bodyReaderNote') }}</p>
    <p class="hint">{{ w('bodyEditNote') }}</p>
    <div class="body-toolbar"><input v-model="search" :placeholder="w('searchBody')" :aria-label="w('searchBody')" /><button type="button" class="btn" :aria-pressed="showChanges" @click="showChanges = !showChanges">{{ showChanges ? w('bodyShowAll') : w('bodyShowChanges') }}</button><span v-if="changedCount" class="pending-count" role="status">{{ w('unsaved') }} · {{ changedCount }} {{ w('paragraph') }}</span></div>
    <p v-if="!visible.length" class="hint" role="status">{{ blocks.length ? w('bodyNoMatches') : w('bodyNoParagraphs') }}</p>
    <article v-for="block in visible" :key="block.id" class="body-block" :data-block-id="block.id">
      <div class="block-heading"><span class="paragraph-label">{{ w('paragraph') }} {{ block.paragraphOrdinal }}</span><span v-if="changed(block)" class="pending-count">{{ w('bodyModified') }}</span><span v-if="!block.editable" class="hint">{{ w('protectedBody') }}</span><template v-if="block.editable && !readonly"><button v-if="changed(block)" type="button" class="btn" :disabled="disabled" @click="restore(block)">{{ w('bodyRestoreParagraph') }}</button><button type="button" class="btn" :disabled="disabled" :aria-label="`${selectedId === block.id ? w('bodyDoneEditing') : w('bodyEditParagraph')} ${block.paragraphOrdinal}`" :aria-expanded="selectedId === block.id" @click="selectedId = selectedId === block.id ? '' : block.id">{{ selectedId === block.id ? w('bodyDoneEditing') : w('bodyEditParagraph') }}</button></template></div>
      <p class="paragraph-text" lang="en">{{ draft.texts[block.id] ?? block.text }}</p>
      <template v-if="selectedId === block.id && block.editable && !readonly">
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
