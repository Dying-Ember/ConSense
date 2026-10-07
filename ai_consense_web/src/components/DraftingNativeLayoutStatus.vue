<script setup lang="ts">
import { computed } from 'vue'
import type { DraftNativeLayout } from '@/api/types'
import type { AppLocale } from '@/i18n'

const props = defineProps<{ layout?: DraftNativeLayout; locale: AppLocale }>()
const messages = {
  applied: {
    en: 'Template pagination corrections applied to this document revision. Review the PDF before exporting.',
    'zh-Hans': '本次文稿已应用模板分页修正。导出前请核对 PDF。',
    'zh-Hant': '本次文稿已套用範本分頁修正。匯出前請核對 PDF。'
  },
  skipped_unverified_source: {
    en: 'This uploaded template has no verified pagination profile. Original layout retained; review its PDF.',
    'zh-Hans': '此上传模板没有已核验的分页方案。已保留原排版，请核对 PDF。',
    'zh-Hant': '此上傳範本沒有已核驗的分頁方案。已保留原排版，請核對 PDF。'
  },
  not_applied_to_saved_revision: {
    en: 'This saved revision is unchanged and has no applied pagination corrections. Review its existing PDF before exporting.',
    'zh-Hans': '此已保存版本保持原样，尚未应用分页修正。导出前请核对现有 PDF。',
    'zh-Hant': '此已儲存版本保持原樣，尚未套用分頁修正。匯出前請核對現有 PDF。'
  },
  unavailable: {
    en: 'The pagination receipt cannot be reconciled with this revision. Review the current PDF before exporting.',
    'zh-Hans': '分页记录无法与本次版本核对。导出前请核对当前 PDF。',
    'zh-Hant': '分頁記錄無法與本次版本核對。匯出前請核對目前 PDF。'
  }
}
const message = computed(() => props.layout?.kind === 'native_layout' ? messages[props.layout.status]?.[props.locale] : undefined)
</script>

<template>
  <p v-if="message" class="native-layout-status" role="status" data-native-layout-status :data-native-layout-state="layout?.status">{{ message }}</p>
</template>

<style scoped>
.native-layout-status { margin: 0; font-size: 12px; line-height: 1.5; color: var(--muted); }
</style>
