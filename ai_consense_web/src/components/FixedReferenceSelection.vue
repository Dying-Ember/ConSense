<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fixedReferenceApi, type FixedReferenceSelectionStatus } from '@/api'

const props = withDefaults(defineProps<{ projectId: string; locale?: string }>(), { locale: 'zh-Hans' })
const status = ref<FixedReferenceSelectionStatus | null>(null), configured = ref(false), loading = ref(false), saving = ref(false), error = ref(''), selected = ref<string[]>([])
let generation = 0
const label = (en: string, hans: string, hant: string) => props.locale === 'en' ? en : props.locale === 'zh-Hant' ? hant : hans
const title = computed(() => label('Standard reference editions', '标准参考版本', '標準參考版本'))
watch(() => props.projectId, async id => {
  const token = ++generation
  status.value = null; selected.value = []; error.value = ''; loading.value = true; saving.value = false
  try {
    const health = await fixedReferenceApi.status()
    if (token !== generation) return
    configured.value = health.configured
    if (!health.configured || !id) return
    const result = await fixedReferenceApi.selection(id)
    if (token !== generation) return
    status.value = result
    if (result.selection?.manifestSha256 === result.manifestSha256) selected.value = result.selection.slSources.map(source => source.sha256)
  } catch (failure) { if (token === generation && configured.value) error.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (token === generation) loading.value = false }
}, { immediate: true })

async function save() {
  if (!status.value || saving.value || loading.value || !selected.value.length) return
  const id = props.projectId, token = generation, current = status.value
  saving.value = true; error.value = ''
  try {
    const slSources = current.availableSlSources.filter(source => selected.value.includes(source.sha256)).map(({ editionId, sha256 }) => ({ editionId, sha256 }))
    const result = await fixedReferenceApi.save(id, { manifestSha256: current.manifestSha256, slSources })
    if (token === generation) status.value = result
  } catch (failure) { if (token === generation) error.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (token === generation) saving.value = false }
}
</script>

<template>
  <details v-if="configured" class="fixed-reference-selection">
    <summary>{{ title }}</summary>
    <p>{{ label('Choose the exact SL editions for this project’s standard-reference queries. This does not establish project adoption.', '选择本项目标准参考查询使用的确切 SL 版本；这不证明项目已采用。', '選擇本項目標準參考查詢使用的確切 SL 版本；這不證明項目已採用。') }}</p>
    <p v-if="loading" role="status">{{ label('Loading…', '读取中…', '讀取中…') }}</p>
    <template v-if="status">
      <label v-for="source in status.availableSlSources" :key="source.sha256" class="edition-choice">
        <input v-model="selected" type="checkbox" :value="source.sha256" :disabled="saving" />
        <span>{{ source.editionId }} <small>{{ source.sha256 }}</small></span>
      </label>
      <p v-if="!status.selection">{{ label('Select editions before starting a query or review.', '请先选择版本再启动查询或审查。', '請先選擇版本再啟動查詢或審查。') }}</p>
      <button type="button" class="btn" :disabled="saving || !selected.length" @click="save">{{ label('Save editions', '保存版本', '儲存版本') }}</button>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
  </details>
</template>

<style scoped>
.fixed-reference-selection { position: relative; max-width: 34rem; }
.fixed-reference-selection[open] { padding: .65rem; background: var(--panel-bg, white); border: 1px solid var(--border, #ccc); }
.edition-choice { display: flex; align-items: flex-start; gap: .4rem; margin-block: .5rem; }
small { display: block; overflow-wrap: anywhere; }
</style>
