import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { captureLlmSelection, projectApi, setApiErrorReporter, setLlmProfileResolver, skillApi, systemApi } from '@/api'
import type { LlmProfileId, LlmProfiles, Project, SkillDoc, SystemHealth } from '@/api/types'
import { currentLocale, setLocale, type AppLocale } from '@/i18n'

export interface ToastState {
  message: string
  visible: boolean
}

const LLM_PROFILE_STORAGE = 'consense-llm-profile'
function storedLlmProfile(): LlmProfileId | null {
  try {
    const stored = localStorage.getItem(LLM_PROFILE_STORAGE)
    return stored === 'local' || stored === 'minimax-cn' || stored === 'qwen' || stored === 'qwen-flash' ? stored : null
  } catch { return null }
}

export const useAppStore = defineStore('app', () => {
  const projects = ref<Project[]>([])
  const activeProjectId = ref<string>('')
  const health = ref<SystemHealth | null>(null)
  const llmProfiles = ref<LlmProfiles | null>(null)
  const llmProfileId = ref<LlmProfileId | null>(storedLlmProfile())
  const llmProfilesLoading = ref(false)
  const llmProfilesFailed = ref(false)
  const selectedLlmProfile = computed(() => llmProfiles.value?.profiles.find(profile => profile.id === llmProfileId.value) ?? null)
  setLlmProfileResolver(() => llmProfileId.value)
  const skills = ref<SkillDoc[]>([])
  const bootstrapped = ref(false)
  const busyMessage = ref('')
  const toast = ref<ToastState>({ message: '', visible: false })
  let toastTimer: number | undefined

  const activeProject = computed<Project | null>(
    () => projects.value.find((item) => item.id === activeProjectId.value) ?? projects.value[0] ?? null
  )

  const locale = ref<AppLocale>(currentLocale())

  function notify(message: string, duration = 3200) {
    toast.value = { message, visible: true }
    if (toastTimer) window.clearTimeout(toastTimer)
    toastTimer = window.setTimeout(() => {
      toast.value = { ...toast.value, visible: false }
    }, duration)
  }

  function setBusy(message: string) {
    busyMessage.value = message
  }

  function clearBusy() {
    busyMessage.value = ''
  }

  function changeLocale(next: AppLocale) {
    locale.value = next
    setLocale(next)
  }

  async function loadProjects() {
    projects.value = await projectApi.list()
    if (!projects.value.some((item) => item.id === activeProjectId.value)) {
      activeProjectId.value = projects.value[0]?.id ?? ''
    }
  }

  async function switchProject(projectId: string) {
    activeProjectId.value = projectId
  }

  /** 新建项目：id 由前端生成（后端按 id upsert）；新建后自动切换 */
  async function createProject(nameZhHans: string, contractNo = '') {
    const id = `proj_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
    const created = await projectApi.save({
      id,
      nameZhHans,
      nameZhHant: nameZhHans,
      nameEn: nameZhHans,
      contractNo: contractNo || null
    })
    await loadProjects()
    activeProjectId.value = created.id
    return created
  }

  /** 编辑项目名称和合约编号，保留完整项目配置。 */
  async function renameProject(projectId: string, nameZhHans: string, contractNo: string) {
    const current = projects.value.find((item) => item.id === projectId)
    if (!current) throw new Error('project not found')
    const saved = await projectApi.save({
      id: projectId,
      nameZhHans,
      nameZhHant: nameZhHans,
      nameEn: nameZhHans,
      contractNo: contractNo.trim() || null,
      packageRef: current.packageRef,
      outputReferenceFile: current.outputReferenceFile,
      pages: current.pages,
      nttRange: current.nttRange,
      sctRange: current.sctRange,
      sccRange: current.sccRange,
      specification: current.specification
    })
    if (saved.id !== projectId) throw new Error('Saved project response does not match the requested project')
    projects.value = projects.value.map(item => item.id === projectId ? saved : item)
    try { await loadProjects(); return true }
    catch { return false }
  }

  /** 删除项目：至少保留一个项目（FR-P-03），删除后切到剩余第一个 */
  async function deleteProject(projectId: string) {
    if (projects.value.length <= 1) return
    await projectApi.remove(projectId)
    if (activeProjectId.value === projectId) {
      activeProjectId.value = ''
    }
    await loadProjects()
  }

  async function loadSkills() {
    skills.value = await skillApi.list()
  }

  async function loadHealth() {
    try {
      health.value = await systemApi.health()
    } catch {
      health.value = null
    }
  }

  async function loadLlmProfiles() {
    llmProfilesLoading.value = true
    llmProfilesFailed.value = false
    try {
      llmProfiles.value = await systemApi.llmProfiles()
      if (!llmProfiles.value.profiles.some(profile => profile.id === llmProfileId.value)) llmProfileId.value = llmProfiles.value.defaultProfile
    } catch {
      llmProfilesFailed.value = true
    } finally {
      llmProfilesLoading.value = false
    }
  }

  function changeLlmProfile(id: string) {
    if (!llmProfiles.value?.profiles.some(profile => profile.id === id && profile.configured)) return
    llmProfileId.value = id as LlmProfileId
    try { localStorage.setItem(LLM_PROFILE_STORAGE, id) } catch { /* Keep this session's explicit choice when browser storage is unavailable. */ }
  }

  async function bootstrap() {
    if (bootstrapped.value) return
    setApiErrorReporter((message) => notify(message, 5000))
    try {
      await Promise.all([loadProjects(), loadHealth(), loadLlmProfiles()])
      loadSkills().catch(() => undefined)
      bootstrapped.value = true
    } catch {
      // 错误已通过 notify 暴露，页面保持可用状态
    }
  }

  return {
    projects,
    activeProjectId,
    activeProject,
    health,
    llmProfiles,
    llmProfileId,
    selectedLlmProfile,
    llmProfilesLoading,
    llmProfilesFailed,
    loadLlmProfiles,
    changeLlmProfile,
    captureLlmSelection,
    skills,
    busyMessage,
    toast,
    locale,
    bootstrap,
    notify,
    setBusy,
    clearBusy,
    changeLocale,
    loadProjects,
    switchProject,
    createProject,
    renameProject,
    deleteProject,
    loadSkills,
    loadHealth
  }
})
