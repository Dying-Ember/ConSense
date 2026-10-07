<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import AppIcon from './AppIcon.vue'
import { inspectionWords } from '@/i18n/inspection'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useAppStore()

const navCollapsed = defineModel<boolean>('collapsed', { default: false })

const NAV_ITEMS = computed(() => [
  { name: 'drafting', label: t('nav.drafting'), icon: 'drafting' },
  { name: 'vetting', label: t('nav.vetting'), icon: 'vetting' },
  { name: 'vetting-inspection', label: inspectionWords(store.locale).title, icon: 'layers' },
  { name: 'advice', label: t('nav.advice'), icon: 'advice' }
])

const active = computed(() => (route.name as string) || 'drafting')

function go(name: string) {
  router.push({ name })
}

function openSkills() {
  window.dispatchEvent(new CustomEvent('consense:open-skills'))
}

function openPrompts() {
  router.push({ name: 'prompts' })
}
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark">CS</div>
      <h1>{{ t('app.name') }}</h1>
      <p>{{ t('app.tagline') }}</p>
    </div>

    <nav class="nav">
      <button
        v-for="item in NAV_ITEMS"
        :key="item.name"
        type="button"
        :class="{ active: active === item.name }"
        @click="go(item.name)"
      >
        <AppIcon :name="item.icon" />
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="nav-sub">
      <button type="button" @click="openPrompts">
        <AppIcon name="wand" />
        <span>{{ t('nav.prompts') }}</span>
      </button>
      <button type="button" @click="openSkills">
        <AppIcon name="settings" />
        <span>{{ t('nav.skills') }}</span>
      </button>
      <button type="button" @click="navCollapsed = !navCollapsed">
        <AppIcon :name="navCollapsed ? 'expand' : 'collapse'" />
        <span>{{ t('nav.collapse') }}</span>
      </button>
    </div>
  </aside>
</template>
