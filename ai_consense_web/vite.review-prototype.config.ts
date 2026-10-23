import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFile } from 'node:fs/promises'
import { fileURLToPath, URL } from 'node:url'

const graphFile = 'D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/template-graph-20261017/graph-v2-original.json'
const localized = (zh: string, en: string) => ({ zhHans: zh, zhHant: zh, en })
const fixtures: Record<string, unknown> = {
  '/api/projects': [{ id: 'review-prototype-example', name: localized('示例项目 · 本机交互原型', 'Example project · local interaction prototype'), contractNo: 'EXAMPLE', packageRef: null, outputReferenceFile: null, pages: null, nttRange: null, sctRange: null, sccRange: null, specification: null, titleLines: [] }],
  '/api/system/health': { ready: false, storageRoot: 'Local interaction prototype', llm: { available: false, provider: 'prototype', baseUrl: '', chatModel: 'No model calls', embedModel: '' }, ocr: { available: false, enabled: false, baseUrl: '', mode: 'prototype' }, vector: { available: false, provider: 'prototype', baseUrl: '', collection: '' } },
  '/api/system/llm-profiles': { defaultProfile: 'local', profiles: [{ id: 'local', provider: 'prototype', model: 'Interaction prototype · no model calls', configured: false, unavailableReason: 'disabled' }] },
  '/api/skills': []
}

function localPrototype(): Plugin {
  return {
    name: 'consense-review-prototype-sandbox',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use(async (request, response, next) => {
        const path = new URL(request.url || '/', 'http://127.0.0.1').pathname
        const api = path === '/api' || path.startsWith('/api/')
        const prototype = path.startsWith('/__review-prototype/')
        if (!api && !prototype) return next()
        response.setHeader('Content-Type', 'application/json; charset=utf-8')
        response.setHeader('Cache-Control', 'no-store')
        response.setHeader('X-Content-Type-Options', 'nosniff')
        const reject = (status: number, message: string) => { response.statusCode = status; response.end(JSON.stringify({ code: status, message, data: null })) }
        if (request.method !== 'GET') return reject(405, '交互原型不执行服务写入。 / Service writes are disabled in this interaction prototype.')
        if (Object.prototype.hasOwnProperty.call(fixtures, path)) return response.end(JSON.stringify({ code: 0, message: 'Interaction prototype', data: fixtures[path] }))
        if (path === '/__review-prototype/graph.json') {
          try { return response.end(await readFile(graphFile)) }
          catch { return reject(503, '本机示例图谱暂不可用。 / The local example graph is unavailable.') }
        }
        return reject(404, '此接口不在交互原型中。 / This endpoint is unavailable in the interaction prototype.')
      })
    }
  }
}

// This standalone dev config intentionally has no API proxy or production-config import.
export default defineConfig({
  define: { __CONSENSE_REVIEW_PROTOTYPE__: 'true' },
  plugins: [vue(), localPrototype()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: { host: '127.0.0.1', port: 5192, strictPort: true, allowedHosts: ['127.0.0.1', 'localhost'] },
  optimizeDeps: { esbuildOptions: { target: 'esnext' } },
  worker: { format: 'es' }
})
