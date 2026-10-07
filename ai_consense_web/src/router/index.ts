import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  // hash 模式，方便直接以静态文件方式部署
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/drafting' },
    {
      path: '/drafting',
      name: 'drafting',
      component: () => import('@/views/DraftingView.vue')
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
