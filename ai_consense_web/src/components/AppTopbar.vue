<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { LOCALE_LABELS, SUPPORTED_LOCALES, type AppLocale } from '@/i18n'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from './AppIcon.vue'
import AppModal from './AppModal.vue'
import { inspectionWords } from '@/i18n/inspection'

const { t } = useI18n()
const route = useRoute()
const store = useAppStore()
const { pick } = useLocalized()

const title = computed(() => route.name === 'vetting-inspection' ? inspectionWords(store.locale).title : t(`screen.${(route.name as string) || 'drafting'}`))

const healthLabel = computed(() => {
  const health = store.health
  if (!health) return t('system.notReady')
  return health.ready ? t('system.healthy') : t('system.degraded')
})

const llmReadiness = computed(() => {
  if (store.llmProfilesLoading) return t('llm.loading')
  if (store.llmProfilesFailed || !store.selectedLlmProfile) return t('llm.unavailable')
  if (store.selectedLlmProfile.configured) return t('llm.configured')
  const reason = store.selectedLlmProfile.unavailableReason
  return t(`llm.${reason === 'missing_api_key' ? 'missingKey' : reason === 'disabled' ? 'disabled' : 'notConfigured'}`)
})

/* ------------------------------------------------------------ 项目工作区：新建 / 重命名 / 删除（FR-P-01/02/03） */
type ProjectDialogMode = 'create' | 'rename' | 'delete' | null
const projectDialog = ref<ProjectDialogMode>(null)
const projectNameInput = ref('')
const projectContractInput = ref('')
const projectBusy = ref(false)

const activeProjectName = computed(() =>
  store.activeProject ? pick(store.activeProject.name) : ''
)

function openCreate() {
  projectNameInput.value = ''
  projectContractInput.value = ''
  projectDialog.value = 'create'
}

function openRename() {
  projectNameInput.value = activeProjectName.value
  projectContractInput.value = store.activeProject?.contractNo ?? ''
  projectDialog.value = 'rename'
}

function openDelete() {
  projectDialog.value = 'delete'
}

async function submitProjectDialog() {
  const name = projectNameInput.value.trim()
  if (projectDialog.value === 'create' || projectDialog.value === 'rename') {
    if (!name) return
  }
  projectBusy.value = true
  try {
    if (projectDialog.value === 'create') {
      await store.createProject(name, projectContractInput.value.trim())
      store.notify(t('topbar.projectCreated'))
    } else if (projectDialog.value === 'rename' && store.activeProjectId) {
      await store.renameProject(store.activeProjectId, name)
      store.notify(t('common.saved'))
    } else if (projectDialog.value === 'delete' && store.activeProjectId) {
      await store.deleteProject(store.activeProjectId)
      store.notify(t('topbar.projectDeleted'))
    }
    projectDialog.value = null
  } finally {
    projectBusy.value = false
  }
}

function onProjectChange(event: Event) {
  store.switchProject((event.target as HTMLSelectElement).value)
}

function onLocaleChange(event: Event) {
  store.changeLocale((event.target as HTMLSelectElement).value as AppLocale)
}
</script>

<template>
  <header class="topbar">
    <div class="top-title">
      <h2>{{ title }}</h2>
    </div>
    <div class="top-actions">
      <span class="row" :title="healthLabel">
        <span class="health-dot" :class="{ ok: store.health?.ready, bad: store.health && !store.health.ready }" />
        <span class="muted small">{{ t('topbar.offline') }}</span>
      </span>

      <div class="llm-profile-switcher">
        <label>
          <span class="switcher-label">{{ t('llm.source') }}</span>
          <select :value="store.llmProfileId" :aria-label="t('llm.source')" :disabled="store.llmProfilesLoading || !store.llmProfiles" @change="store.changeLlmProfile(($event.target as HTMLSelectElement).value)">
            <option v-if="!store.llmProfiles" :value="store.llmProfileId || ''" disabled>{{ store.llmProfileId ? t(store.llmProfileId === 'local' ? 'llm.local' : 'llm.minimax') : llmReadiness }}</option>
            <option v-for="profile in store.llmProfiles?.profiles ?? []" :key="profile.id" :value="profile.id" :disabled="!profile.configured">{{ t(profile.id === 'local' ? 'llm.local' : 'llm.minimax') }}</option>
          </select>
        </label>
        <details class="llm-profile-detail">
          <summary class="small" aria-live="polite"><span>{{ t('llm.model') }}: {{ store.selectedLlmProfile?.model || '—' }}</span> · {{ llmReadiness }}</summary>
          <p class="muted small">{{ t('llm.newOperations') }}</p>
        </details>
      </div>

      <label class="project-switcher">
        <span class="switcher-label">{{ t('topbar.project') }}</span>
        <select :value="store.activeProjectId" @change="onProjectChange">
          <option v-for="project in store.projects" :key="project.id" :value="project.id">
            {{ pick(project.name) }}
          </option>
        </select>
        <button class="btn icon-only" type="button" :title="t('topbar.newProject')" @click="openCreate">
          <AppIcon name="plus" :size="14" />
        </button>
        <button
          class="btn icon-only"
          type="button"
          :disabled="!store.activeProject"
          :title="t('topbar.renameProject')"
          @click="openRename"
        >
          <AppIcon name="edit" :size="14" />
        </button>
        <button
          class="btn icon-only"
          type="button"
          :disabled="store.projects.length <= 1 || !store.activeProject"
          :title="store.projects.length <= 1 ? t('topbar.deleteGuard') : t('topbar.deleteProject')"
          @click="openDelete"
        >
          <AppIcon name="trash" :size="14" />
        </button>
      </label>

      <label class="lang-switcher">
        <AppIcon name="globe" :size="16" />
        <span class="switcher-label" style="margin-left: 6px">{{ t('topbar.language') }}</span>
        <select :value="store.locale" @change="onLocaleChange">
          <option v-for="code in SUPPORTED_LOCALES" :key="code" :value="code">
            {{ LOCALE_LABELS[code] }}
          </option>
        </select>
      </label>
    </div>

    <!-- 新建 / 重命名项目 -->
    <AppModal
      :open="projectDialog === 'create' || projectDialog === 'rename'"
      :title="projectDialog === 'create' ? t('topbar.newProject') : t('topbar.renameProject')"
      @close="projectDialog = null"
    >
      <div class="form-grid">
        <label>
          <span>{{ t('topbar.projectName') }}</span>
          <input
            v-model="projectNameInput"
            type="text"
            maxlength="120"
            :placeholder="t('topbar.projectNamePlaceholder')"
          />
        </label>
        <label>
          <span>{{ t('topbar.projectContract') }}</span>
          <input v-model="projectContractInput" type="text" maxlength="60" placeholder="e.g. 20230196" />
        </label>
        <p v-if="projectDialog === 'create'" class="muted small">{{ t('topbar.createHint') }}</p>
        <p v-else class="muted small">{{ t('topbar.renameHint') }}</p>
      </div>
      <template #footer>
        <button class="btn" type="button" @click="projectDialog = null">{{ t('common.cancel') }}</button>
        <button
          class="btn primary"
          type="button"
          :disabled="projectBusy || !projectNameInput.trim()"
          @click="submitProjectDialog"
        >
          {{ projectBusy ? t('common.loading') : t('common.save') }}
        </button>
      </template>
    </AppModal>

    <!-- 删除项目（二次确认，FR-P-03） -->
    <AppModal :open="projectDialog === 'delete'" :title="t('topbar.deleteProject')" @close="projectDialog = null">
      <p>{{ t('topbar.deleteConfirm', { name: activeProjectName }) }}</p>
      <p class="muted small">{{ t('topbar.deleteHint') }}</p>
      <template #footer>
        <button class="btn" type="button" @click="projectDialog = null">{{ t('common.cancel') }}</button>
        <button class="btn danger" type="button" :disabled="projectBusy" @click="submitProjectDialog">
          {{ projectBusy ? t('common.loading') : t('common.delete') }}
        </button>
      </template>
    </AppModal>
  </header>
</template>

<style scoped>
.top-title { flex: 0 0 auto; }
.top-title h2 { white-space: nowrap; }
.top-actions { flex: 1 1 auto; align-items: center; gap: 8px 10px; }
.top-actions > .row,
.project-switcher,
.lang-switcher { flex: 0 0 auto; gap: 4px; white-space: nowrap; }
.switcher-label { margin-right: 2px; }
.project-switcher select { min-width: 160px; max-width: 210px; width: clamp(160px, 18vw, 210px); }
.lang-switcher select { width: 120px; }
.llm-profile-switcher { display: grid; grid-template-columns: minmax(0, 1fr); gap: 3px; min-width: 0; max-width: 280px; position: relative; }
.llm-profile-switcher label { display: flex; align-items: center; gap: 4px; }
.llm-profile-switcher .switcher-label { white-space: nowrap; }
.llm-profile-switcher select { min-width: 0; width: 200px; }
.llm-profile-detail { min-width: 0; }
.llm-profile-detail summary { cursor: pointer; color: var(--muted); line-height: 1.4; overflow-wrap: anywhere; }
.llm-profile-detail summary:focus-visible { outline: none; box-shadow: var(--focus); border-radius: var(--radius-sm); }
.llm-profile-detail p { position: absolute; z-index: 20; top: calc(100% + 8px); left: 0; width: min(280px, calc(100vw - 32px)); margin: 0; padding: 10px 12px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); line-height: 1.6; overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .top-actions { justify-content: flex-start; }
  .project-switcher { flex-wrap: wrap; }
  .project-switcher select { width: 160px; }
}
@media (max-width: 560px) {
  .llm-profile-detail p { left: auto; right: 0; }
}
.form-grid {
  display: grid;
  gap: 12px;
}
.form-grid label {
  display: grid;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}
.form-grid input {
  width: 100%;
}
</style>
