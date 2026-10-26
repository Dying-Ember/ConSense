import axios, { type AxiosRequestConfig } from 'axios'
import type { LlmProfileId, LlmSelection } from './types'

interface ApiEnvelope<T> {
  code: number
  message: string
  reason?: string
  data: T
}

export class ApiError extends Error {
  code: number
  reason?: string

  constructor(code: number, message: string, reason?: string) {
    super(message)
    this.code = code
    this.reason = typeof reason === 'string' ? reason : undefined
    this.name = 'ApiError'
  }
}

const http = axios.create({
  baseURL: '/api',
  // 变量抽取 / 审查 / 文稿生成都会调用本地模型，给足时间
  timeout: 600000,
  headers: { 'Content-Type': 'application/json' }
})

let llmProfileResolver: (() => LlmProfileId | null) | null = null
export function setLlmProfileResolver(resolver: () => LlmProfileId | null) { llmProfileResolver = resolver }
export function captureLlmSelection(): LlmSelection { return { profileId: llmProfileResolver?.() ?? null } }
/** Set reportError false only when the caller renders an actionable inline error. */
type RequestConfig = AxiosRequestConfig & { llmSelection?: LlmSelection; reportError?: boolean }
function profileConfig(config: RequestConfig = {}): AxiosRequestConfig {
  const { llmSelection, reportError: _reportError, ...request } = config
  const profileId = (llmSelection ?? captureLlmSelection()).profileId
  return { ...request, headers: { ...request.headers, ...(profileId ? { 'X-ConSense-Llm-Profile': profileId } : {}) } }
}

/** 错误提示回调由 app store 注入，避免 api 层依赖 UI */
let errorReporter: ((message: string) => void) | null = null

export function setApiErrorReporter(reporter: (message: string) => void) {
  errorReporter = reporter
}

async function unwrap<T>(promise: Promise<{ data: ApiEnvelope<T> }>, reportError = true): Promise<T> {
  try {
    const response = await promise
    const envelope = response.data
    if (envelope.code !== 0) {
      throw new ApiError(envelope.code, envelope.message || '请求失败', envelope.reason)
    }
    return envelope.data
  } catch (error) {
    const message = toMessage(error)
    if (reportError) errorReporter?.(message)
    if (error instanceof ApiError) throw error
    const envelope = axios.isAxiosError(error) ? error.response?.data as Partial<ApiEnvelope<unknown>> | undefined : undefined
    throw new ApiError(typeof envelope?.code === 'number' ? envelope.code : -1, message, envelope?.reason)
  }
}

function toMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  if (axios.isAxiosError(error)) {
    if (error.code === 'ECONNABORTED') {
      return '请求超时，请检查本地服务或稍后重试'
    }
    if (!error.response) {
      return '无法连接后端服务，请确认 service 已启动及 API 地址配置正确'
    }
    const data = error.response.data as ApiEnvelope<unknown> | undefined
    return data?.message || `请求失败：HTTP ${error.response.status}`
  }
  return error instanceof Error ? error.message : '未知错误'
}

type DownloadOptions = { jsonDocument?: (value: unknown) => boolean; timeout?: number }

/** JSON files require an explicit document validator; business envelopes remain errors. */
async function requireBinary(blob: Blob, options: DownloadOptions = {}): Promise<Blob> {
  if (blob.type.toLowerCase().includes('json')) {
    let result: unknown
    try { result = JSON.parse(await blob.text()) } catch {
      throw new ApiError(-1, '服务器返回无效 JSON，未生成报告文件')
    }
    if (result && typeof result === 'object' && Object.prototype.hasOwnProperty.call(result, 'code')) {
      const envelope = result as Partial<ApiEnvelope<unknown>>
      throw new ApiError(typeof envelope.code === 'number' && envelope.code !== 0 ? envelope.code : -1,
        typeof envelope.message === 'string' && envelope.message ? envelope.message : '服务器返回错误信息，未生成文件')
    }
    if (!options.jsonDocument?.(result)) throw new ApiError(-1, '服务器返回的 JSON 不是当前请求的报告，未生成文件')
  } else if (options.jsonDocument) {
    throw new ApiError(-1, '服务器未返回 JSON 报告，未生成文件')
  }
  return blob
}

export const api = {
  get: <T>(url: string, config?: RequestConfig) => unwrap<T>(http.get(url, profileConfig(config)), config?.reportError),
  post: <T>(url: string, body?: unknown, config?: RequestConfig) => unwrap<T>(http.post(url, body, profileConfig(config)), config?.reportError),
  put: <T>(url: string, body?: unknown, config?: RequestConfig) => unwrap<T>(http.put(url, body, profileConfig(config)), config?.reportError),
  delete: <T>(url: string) => unwrap<T>(http.delete(url, profileConfig())),
  upload: <T>(url: string, files: File[], timeoutMs?: number) => {
    const form = new FormData()
    files.forEach((file) => form.append('files', file))
    return unwrap<T>(http.post(url, form, profileConfig({ ...(timeoutMs === undefined ? {} : { timeout: timeoutMs }), headers: { 'Content-Type': 'multipart/form-data' } })))
  },
  /** 单文件上传（字段名 file）：用于替换指定标准模板等场景 */
  uploadOne: <T>(url: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return unwrap<T>(http.post(url, form, profileConfig({ headers: { 'Content-Type': 'multipart/form-data' } })))
  },
  /** 下载二进制（文稿 / 审查报告） */
  download: async (url: string, fallbackName: string, options: DownloadOptions = {}) => {
    try {
      const response = await http.get(url, profileConfig({ responseType: 'blob', timeout: options.timeout ?? 300000 }))
      const content = await requireBinary(response.data as Blob, options)
      const disposition = String(response.headers['content-disposition'] || '')
      const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
      const name = match ? decodeURIComponent(match[1]) : fallbackName
      const blobUrl = URL.createObjectURL(content)
      const link = document.createElement('a')
      link.href = blobUrl
      link.download = name
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(blobUrl), 5000)
    } catch (error) {
      const message = toMessage(error)
      errorReporter?.(message)
      throw error
    }
  },
  /** 取二进制 Blob（原件预览：PDF / 文本），不触发下载 */
  blob: async (url: string, timeout = 300000, reportError = true): Promise<Blob> => {
    try {
      const response = await http.get(url, profileConfig({ responseType: 'blob', timeout }))
      return await requireBinary(response.data as Blob)
    } catch (error) {
      const message = toMessage(error)
      if (reportError) errorReporter?.(message)
      throw error
    }
  }
}
