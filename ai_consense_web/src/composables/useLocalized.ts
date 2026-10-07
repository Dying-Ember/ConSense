import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocalizedText } from '@/api/types'

const KEY_MAP: Record<string, keyof LocalizedText> = {
  'zh-Hans': 'zhHans',
  'zh-Hant': 'zhHant',
  en: 'en'
}

export function useLocalized() {
  const { locale } = useI18n()

  const currentKey = computed<keyof LocalizedText>(() => KEY_MAP[locale.value as string] ?? 'zhHans')

  /** 取三语文案中当前语言的值，缺失时回落到简体 */
  function pick(text?: LocalizedText | null): string {
    if (!text) return ''
    return text[currentKey.value] || text.zhHans || ''
  }

  return { pick, currentKey, locale }
}
