<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { skillApi } from '@/api'
import type { SkillDoc } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { useLocalized } from '@/composables/useLocalized'
import AppModal from './AppModal.vue'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const store = useAppStore()
const { pick, currentKey } = useLocalized()

const activeId = ref('')
const editing = ref(false)
const saving = ref(false)
const draft = ref<SkillDoc | null>(null)

const skills = computed(() => store.skills)

/** 编辑态用 draft，预览态用原数据 */
const current = computed<SkillDoc | null>(() => {
  if (editing.value && draft.value) return draft.value
  return skills.value.find((item) => item.id === activeId.value) ?? null
})

watch(
  () => props.open,
  (open) => {
    if (open) {
      editing.value = false
      draft.value = null
      if (!activeId.value && skills.value.length) {
        activeId.value = skills.value[0].id
      }
    }
  }
)

watch(skills, (list) => {
  if (!activeId.value && list.length) activeId.value = list[0].id
})

function select(id: string) {
  if (editing.value && draft.value) {
    draft.value = null
    editing.value = false
  }
  activeId.value = id
}

function startEdit() {
  if (!current.value) return
  draft.value = JSON.parse(JSON.stringify(current.value)) as SkillDoc
  editing.value = true
}

async function save() {
  if (!draft.value) return
  saving.value = true
  try {
    const saved = await skillApi.save(draft.value.id, draft.value)
    const index = store.skills.findIndex((item) => item.id === saved.id)
    if (index >= 0) store.skills.splice(index, 1, saved)
    store.notify(t('common.done'))
    editing.value = false
    draft.value = null
  } finally {
    saving.value = false
  }
}

async function restore() {
  if (!current.value) return
  const restored = await skillApi.reset(current.value.id)
  const index = store.skills.findIndex((item) => item.id === restored.id)
  if (index >= 0) store.skills.splice(index, 1, restored)
  editing.value = false
  draft.value = null
  store.notify(t('common.reset'))
}

function addRow() {
  current.value?.rules.push(['', '', ''])
}

function removeRow(index: number) {
  current.value?.rules.splice(index, 1)
}

function addItem(field: 'outItems' | 'guardItems') {
  current.value?.[field].push({ zhHans: '', zhHant: '', en: '' })
}

function removeItem(field: 'outItems' | 'guardItems', index: number) {
  current.value?.[field].splice(index, 1)
}

function cell(row: string[], index: number) {
  return row[index] ?? ''
}

function setCell(row: string[], index: number, value: string) {
  row[index] = value
}
</script>

<template>
  <AppModal :open="open" :title="t('skills.title')" wide @close="emit('close')">
    <div class="skill-toolbar">
      <div class="row">
        <button
          v-for="skill in skills"
          :key="skill.id"
          type="button"
          class="btn"
          :class="{ soft: activeId === skill.id }"
          @click="select(skill.id)"
        >
          {{ pick(skill.name) }}
        </button>
      </div>
      <div class="row">
        <span class="muted small">{{ t('skills.hint') }}</span>
        <button v-if="!editing" class="btn" type="button" :disabled="!current" @click="startEdit">
          <AppIcon name="wand" :size="15" />{{ t('skills.edit') }}
        </button>
        <button v-else class="btn primary" type="button" :disabled="saving" @click="save">
          <AppIcon name="check" :size="15" />{{ t('skills.save') }}
        </button>
        <button class="btn" type="button" :disabled="!current" @click="restore">
          <AppIcon name="refresh" :size="15" />{{ t('skills.restore') }}
        </button>
      </div>
    </div>

    <div v-if="!current" class="empty-state">{{ t('common.empty') }}</div>

    <template v-else>
      <div class="skill-cards">
        <div v-for="skill in skills" :key="skill.id" class="skill-card" :class="{ active: activeId === skill.id }">
          <span class="code mono">{{ skill.code }}</span>
          <strong>{{ pick(skill.name) }}</strong>
          <div class="skill-badges">
            <span v-for="(badge, i) in skill.badges" :key="i" class="tag neutral">{{ pick(badge) }}</span>
          </div>
        </div>
      </div>

      <div class="surface flat">
        <div class="surface-head">
          <div>
            <h4>{{ pick(current.name) }}</h4>
            <p>{{ pick(current.purpose) }}</p>
          </div>
          <div class="skill-badges">
            <span v-for="(badge, i) in current.badges" :key="i" class="tag demo">{{ pick(badge) }}</span>
          </div>
        </div>
        <div class="surface-body">
          <section class="skill-sec">
            <h5>{{ t('skills.steps') }}</h5>
            <ol class="skill-steps">
              <li v-for="(step, i) in current.steps" :key="i">
                <template v-if="editing">
                  <input :value="step[0]?.[currentKey]" @input="step[0][currentKey] = ($event.target as HTMLInputElement).value" />
                  <textarea
                    style="margin-top: 6px; min-height: 54px"
                    :value="step[1]?.[currentKey]"
                    @input="step[1][currentKey] = ($event.target as HTMLTextAreaElement).value"
                  />
                </template>
                <template v-else>
                  <strong>{{ pick(step[0]) }}</strong>
                  <span>{{ pick(step[1]) }}</span>
                </template>
              </li>
            </ol>
          </section>

          <section class="skill-sec">
            <h5>{{ t('skills.rules') }}</h5>
            <div class="table-wrap">
              <table class="skill-table">
                <thead>
                  <tr>
                    <th v-for="(head, i) in current.ruleHead" :key="i">{{ pick(head) }}</th>
                    <th v-if="editing" />
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, rowIndex) in current.rules" :key="rowIndex">
                    <td v-for="(_, cellIndex) in current.ruleHead" :key="cellIndex">
                      <input
                        v-if="editing"
                        :value="cell(row, cellIndex)"
                        @input="setCell(row, cellIndex, ($event.target as HTMLInputElement).value)"
                      />
                      <span v-else>{{ cell(row, cellIndex) }}</span>
                    </td>
                    <td v-if="editing">
                      <button class="btn icon-only danger" type="button" @click="removeRow(rowIndex)">
                        <AppIcon name="trash" :size="15" />
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <button v-if="editing" class="btn" type="button" style="margin-top: 8px" @click="addRow">
              <AppIcon name="plus" :size="15" />{{ t('common.add') }}
            </button>
          </section>

          <div class="skill-grid2">
            <section class="skill-out">
              <h6>{{ t('skills.output') }}</h6>
              <ul>
                <li v-for="(item, i) in current.outItems" :key="i">
                  <input
                    v-if="editing"
                    :value="item[currentKey]"
                    @input="item[currentKey] = ($event.target as HTMLInputElement).value"
                  />
                  <template v-else>{{ pick(item) }}</template>
                </li>
              </ul>
              <button v-if="editing" class="btn" type="button" style="margin-top: 8px" @click="addItem('outItems')">
                <AppIcon name="plus" :size="15" />{{ t('common.add') }}
              </button>
            </section>

            <section class="skill-guard">
              <h6>{{ t('skills.guardrails') }}</h6>
              <ul>
                <li v-for="(item, i) in current.guardItems" :key="i">
                  <input
                    v-if="editing"
                    :value="item[currentKey]"
                    @input="item[currentKey] = ($event.target as HTMLInputElement).value"
                  />
                  <template v-else>{{ pick(item) }}</template>
                </li>
              </ul>
              <button v-if="editing" class="btn" type="button" style="margin-top: 8px" @click="addItem('guardItems')">
                <AppIcon name="plus" :size="15" />{{ t('common.add') }}
              </button>
            </section>
          </div>
        </div>
      </div>
    </template>
  </AppModal>
</template>
