<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import { useI18n } from 'vue-i18n'

defineProps<{ open: boolean; title: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
</script>

<template>
  <div class="modal-backdrop" :class="{ show: open }" :aria-hidden="!open" :inert="!open || undefined" @click.self="emit('close')">
    <div class="modal" :class="{ wide }" role="dialog" aria-modal="true">
      <div class="modal-head">
        <h4>{{ title }}</h4>
        <button class="btn icon-only" type="button" :aria-label="t('common.close')" @click="emit('close')">
          <AppIcon name="close" />
        </button>
      </div>
      <div class="modal-body">
        <slot />
      </div>
      <div class="modal-foot" v-if="$slots.footer">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop { grid-template-columns: minmax(0, 1fr); }
.modal { min-width: 0; max-width: 100%; }
.modal-body { min-width: 0; min-height: 0; }
</style>
