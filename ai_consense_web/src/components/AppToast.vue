<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const { toast, busyMessage } = storeToRefs(store)
</script>

<template>
  <div v-if="busyMessage" class="loading-inline" style="position: fixed; left: 22px; bottom: 22px; background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 9px 14px; box-shadow: var(--shadow); z-index: 90">
    <span class="spinner" />
    <span>{{ busyMessage }}</span>
  </div>
  <div class="toast" :class="{ show: toast.visible }">{{ toast.message }}</div>
</template>
