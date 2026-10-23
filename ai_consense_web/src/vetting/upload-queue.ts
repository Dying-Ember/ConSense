import { reactive } from 'vue'
import { vettingApi } from '@/api'
import type { VettingFile } from '@/api/types'

export type UploadProgress = { total: number; completed: number; current: number; fileName: string; failed: number;
  state: 'running' | 'completed' | 'uncertain' | 'refresh_failed' | 'interrupted'; messages: string[] }
type UploadQueue = { running: boolean; progress: UploadProgress | null; sources: VettingFile[] | null }
const queues = new Map<string, UploadQueue>()

function restoreProgress(projectId: string): UploadProgress | null {
  try {
    const saved = JSON.parse(window.sessionStorage.getItem(`vetting-upload:${projectId}`) ?? 'null')
    if (!saved || !Number.isSafeInteger(saved.total) || saved.total < 1 || !Number.isSafeInteger(saved.completed)
      || saved.completed < 0 || saved.completed > saved.total || !Number.isSafeInteger(saved.current) || saved.current < 1 || saved.current > saved.total
      || !Number.isSafeInteger(saved.failed) || saved.failed < 0 || saved.failed > saved.completed || typeof saved.fileName !== 'string'
      || !Array.isArray(saved.messages) || !saved.messages.every((message: unknown) => typeof message === 'string')
      || !['running', 'completed', 'uncertain', 'refresh_failed', 'interrupted'].includes(saved.state)) return null
    return { ...saved, state: saved.state === 'running' ? 'interrupted' : saved.state }
  } catch { return null }
}

function persistProgress(projectId: string, queue: UploadQueue) {
  try { window.sessionStorage.setItem(`vetting-upload:${projectId}`, JSON.stringify(queue.progress)) }
  catch { /* Saved server results remain readable when browser storage is unavailable. */ }
}

export function vettingUploadQueue(projectId: string): UploadQueue {
  let queue = queues.get(projectId)
  if (!queue) {
    queue = reactive({ running: false, progress: restoreProgress(projectId), sources: null })
    queues.set(projectId, queue)
  }
  return queue
}

export async function uploadVettingFiles(projectId: string, selected: File[]) {
  const queue = vettingUploadQueue(projectId)
  if (!projectId || !selected.length || queue.running) return
  queue.running = true
  queue.progress = { total: selected.length, completed: 0, current: 1, fileName: selected[0]!.name, failed: 0, state: 'running', messages: [] }
  persistProgress(projectId, queue)
  let acknowledged = false
  try {
    // The worker owns the files and captured project across SPA navigation; each response and source refresh precedes the next file.
    for (const [index, file] of selected.entries()) {
      const progress = queue.progress!
      progress.current = index + 1; progress.fileName = file.name
      acknowledged = false
      persistProgress(projectId, queue)
      const result = await vettingApi.uploadPackage(projectId, [file])
      acknowledged = true
      progress.completed++
      if (result.failed > 0 || result.accepted < 1) progress.failed++
      progress.messages.push(...result.messages)
      persistProgress(projectId, queue)
      queue.sources = await vettingApi.files(projectId)
    }
    queue.progress!.state = 'completed'
  } catch {
    queue.progress!.state = acknowledged ? 'refresh_failed' : 'uncertain'
    // Observe saved results once; an unknown response never authorizes replay or continuation.
    try { queue.sources = await vettingApi.files(projectId) } catch { /* Preserve the last readable source list. */ }
  } finally {
    queue.running = false
    persistProgress(projectId, queue)
  }
}
