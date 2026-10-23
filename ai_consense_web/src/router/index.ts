import { defineAsyncComponent, defineComponent, h } from 'vue'
import { createRouter, createWebHashHistory, useRoute } from 'vue-router'

declare const __CONSENSE_REVIEW_PROTOTYPE__: boolean
const draftingView = () => import('@/views/DraftingView.vue')
const sandbox = import.meta.env.DEV && typeof __CONSENSE_REVIEW_PROTOTYPE__ !== 'undefined' && __CONSENSE_REVIEW_PROTOTYPE__
const draftingEntry = sandbox ? defineComponent({
  setup() {
    const route = useRoute()
    const ordinary = defineAsyncComponent(draftingView)
    const prototype = defineAsyncComponent(() => import('@/views/HumanReviewIntegrationPrototype.vue'))
    return () => h(route.query.reviewPrototype === '1' ? prototype : ordinary)
  }
}) : draftingView

const router = createRouter({
  // hash 模式，方便直接以静态文件方式部署
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/drafting' },
    {
      path: '/drafting',
      name: 'drafting',
      component: draftingEntry
    },
    {
      path: '/vetting',
      name: 'vetting',
      component: () => import('@/views/VettingView.vue')
    },
    {
      path: '/vetting-inspection',
      name: 'vetting-inspection',
      component: () => import('@/views/VettingInspectionView.vue')
    },
    {
      path: '/advice',
      name: 'advice',
      component: () => import('@/views/AdviceView.vue')
    },
    {
      path: '/prompts',
      name: 'prompts',
      component: () => import('@/views/PromptView.vue')
    },
    { path: '/:pathMatch(.*)*', redirect: '/drafting' }
  ]
})

export default router
