<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { promptApi, type PromptTemplate } from '@/api'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppIcon from '@/components/AppIcon.vue'

const { t } = useI18n()
const { pick } = useLocalized()
const store = useAppStore()

const loading = ref(false)
const saving = ref(false)
const reverting = ref(false)
const prompts = ref<PromptTemplate[]>([])
const activeKey = ref<string>('')

/** 编辑态（与后端返回的当前值分开，便于判断是否有未保存改动） */
const draftSystem = ref('')
const draftUser = ref('')

const GROUP_LABELS: Record<string, string> = {
  drafting: 'prompts.groupDrafting',
  vetting: 'prompts.groupVetting',
  advice: 'prompts.groupAdvice'
}

const orderedGroups = computed(() => {
  const groups: { key: string; label: string; items: PromptTemplate[] }[] = []
  for (const item of prompts.value) {
    let group = groups.find((g) => g.key === item.group)
    if (!group) {
      group = { key: item.group, label: GROUP_LABELS[item.group] ?? item.group, items: [] }
      groups.push(group)
    }
    group.items.push(item)
  }
  return groups
})

const active = computed(() => prompts.value.find((item) => item.key === activeKey.value) ?? null)

const dirty = computed(() => {
  if (!active.value) return false
  return draftSystem.value !== (active.value.systemText ?? '') || draftUser.value !== (active.value.userTemplate ?? '')
})

function select(item: PromptTemplate) {
  if (dirty.value && !window.confirm(t('prompts.unsavedConfirm'))) {
    return
  }
  activeKey.value = item.key
  draftSystem.value = item.systemText ?? ''
  draftUser.value = item.userTemplate ?? ''
}

function applyActive() {
  if (!active.value) return
  draftSystem.value = active.value.systemText ?? ''
  draftUser.value = active.value.userTemplate ?? ''
}

async function reload(keepKey = true) {
  loading.value = true
  try {
    prompts.value = await promptApi.list()
    const keep = keepKey ? prompts.value.find((item) => item.key === activeKey.value) : null
    activeKey.value = keep?.key ?? prompts.value[0]?.key ?? ''
    applyActive()
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!active.value) return
  saving.value = true
  try {
    const updated = await promptApi.save(active.value.key, {
      systemText: draftSystem.value,
      userTemplate: draftUser.value
    })
    replaceLocal(updated)
    store.notify(t('prompts.saved'))
  } finally {
    saving.value = false
  }
}

async function restore() {
  if (!active.value) return
  if (!window.confirm(t('prompts.resetConfirm'))) return
  reverting.value = true
  try {
    const updated = await promptApi.reset(active.value.key)
    replaceLocal(updated)
    store.notify(t('prompts.resetDone'))
  } finally {
    reverting.value = false
  }
}

function replaceLocal(updated: PromptTemplate) {
  const index = prompts.value.findIndex((item) => item.key === updated.key)
  if (index >= 0) {
    prompts.value.splice(index, 1, updated)
  }
  applyActive()
}

function restoreDraftToDefault() {
  if (!active.value) return
  draftSystem.value = active.value.defaultSystemText ?? ''
  draftUser.value = active.value.defaultUserTemplate ?? ''
}

function formatTime(value: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

onMounted(() => reload(false))

watch(activeKey, () => applyActive())
</script>

<template>
  <section class="screen">
    <div class="screen-head">
      <div>
        <span class="eyebrow">{{ t('screen.prompts') }}</span>
        <h3>{{ t('prompts.title') }}</h3>
        <p>{{ t('prompts.subtitle') }}</p>
      </div>
      <div class="row">
        <button class="btn" type="button" :disabled="loading" @click="reload()">
          <AppIcon name="refresh" :size="15" />{{ t('common.refresh') }}
        </button>
      </div>
    </div>

    <div class="layout-grid two-col-wide">
      <!-- 左：提示词清单 -->
      <div class="surface">
        <div class="surface-head">
          <div>
            <h4>{{ t('prompts.title') }}</h4>
            <p class="muted small">{{ t('prompts.subtitle') }}</p>
          </div>
        </div>
        <div class="surface-body">
          <div v-if="!prompts.length" class="empty-state">
            {{ loading ? t('common.loading') : t('prompts.empty') }}
          </div>
          <div v-else class="prompt-list">
            <div v-for="group in orderedGroups" :key="group.key" class="prompt-group">
              <div class="prompt-group-title">{{ t(group.label) }}</div>
              <button
                v-for="item in group.items"
                :key="item.key"
                type="button"
                class="prompt-item"
                :class="{ active: item.key === activeKey }"
                @click="select(item)"
              >
                <div class="prompt-item-main">
                  <strong>{{ pick(item.name) }}</strong>
                  <span class="mono small">{{ item.key }}</span>
                </div>
                <span class="tag" :class="item.customized ? 'warn' : 'ok'">
                  {{ item.customized ? t('prompts.customized') : t('prompts.builtin') }}
                </span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 右：编辑区 -->
      <div class="surface">
        <div v-if="!active" class="surface-body">
          <div class="empty-state">{{ t('prompts.pick') }}</div>
        </div>
        <template v-else>
          <div class="surface-head">
            <div>
              <h4>{{ pick(active.name) }}</h4>
              <p class="muted small">{{ pick(active.description) }}</p>
            </div>
            <div class="row">
              <span v-if="dirty" class="tag warn">{{ t('prompts.unsaved') }}</span>
              <button class="btn" type="button" :disabled="reverting || saving" @click="restore">
                <AppIcon name="refresh" :size="15" />{{ reverting ? t('prompts.reverting') : t('prompts.reset') }}
              </button>
              <button class="btn primary" type="button" :disabled="saving || reverting" @click="save">
                <AppIcon name="check" :size="15" />{{ saving ? t('prompts.saving') : t('prompts.save') }}
              </button>
            </div>
          </div>

          <div class="surface-body">
            <div class="prompt-field">
              <label>{{ t('prompts.systemLabel') }}</label>
              <textarea v-model="draftSystem" rows="14" spellcheck="false" />
            </div>

            <div class="prompt-field">
              <label>{{ t('prompts.userLabel') }}</label>
              <textarea v-model="draftUser" rows="14" spellcheck="false" />
              <div class="muted small">
                <template v-if="active.placeholderCount > 0">
                  <AppIcon name="info" :size="13" />
                  {{ t(active.group === 'vetting' ? 'prompts.vettingPlaceholderHint' : 'prompts.placeholderHint', { count: active.placeholderCount }) }}
                </template>
                <template v-else>
                  <AppIcon name="info" :size="13" />{{ t('prompts.noPlaceholderHint') }}
                </template>
              </div>
            </div>

            <div class="prompt-meta row">
              <span class="muted small">
                {{ t('prompts.updatedAt') }}: {{ formatTime(active.updatedAt) || '—' }}
              </span>
              <span class="tag neutral mono">%s × {{ active.placeholderCount }}</span>
              <button v-if="dirty" class="btn soft" type="button" @click="restoreDraftToDefault">
                {{ t('prompts.reset') }}
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.prompt-list {
  display: grid;
  gap: 14px;
}

.prompt-group {
  display: grid;
  gap: 8px;
}

.prompt-group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.prompt-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  padding: 10px 12px;
  cursor: pointer;
}

.prompt-item:hover {
  border-color: #8dc9c4;
}

.prompt-item.active {
  border-color: #00877f;
  background: #f4fbf8;
}

.prompt-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
  min-width: 0;
}

.prompt-item-main .mono {
  color: var(--muted);
}

.prompt-field {
  display: grid;
  gap: 6px;
  margin-bottom: 16px;
}

.prompt-field label {
  font-size: 12.5px;
  font-weight: 600;
}

.prompt-field textarea {
  width: 100%;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.55;
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 10px 12px;
  resize: vertical;
  min-height: 160px;
}

.prompt-meta {
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
