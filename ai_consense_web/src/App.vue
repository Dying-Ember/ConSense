<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import AppSidebar from '@/components/AppSidebar.vue'
import AppTopbar from '@/components/AppTopbar.vue'
import AppToast from '@/components/AppToast.vue'
import SkillLabModal from '@/components/SkillLabModal.vue'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const collapsed = ref(false)
const skillOpen = ref(false)

watch(collapsed, (value) => {
  document.body.classList.toggle('nav-collapsed', value)
})

onMounted(() => {
  store.bootstrap()
})

// 侧栏的「Skills 配置」按钮通过事件总线式的自定义事件打开弹窗
window.addEventListener('consense:open-skills', () => {
  skillOpen.value = true
  store.loadSkills().catch(() => undefined)
})
</script>

<template>
  <div class="app">
    <AppSidebar v-model:collapsed="collapsed" />
    <div class="main">
      <AppTopbar />
      <router-view />
    </div>
  </div>

  <SkillLabModal :open="skillOpen" @close="skillOpen = false" />
  <AppToast />
</template>
