import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const apiProxy = {
  '/api': {
    target: process.env.CONSENSE_API_TARGET || 'http://localhost:8080',
    changeOrigin: true,
    // Leave five minutes beyond extraction's two-hour client wait.
    // Each API's client timeout still limits its own request.
    timeout: 7500000,
    proxyTimeout: 7500000
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    // 允许局域网 IP / 任意 host 访问（Vite 7+ 用 allowedHosts 替代已废弃的 cors）
    allowedHosts: true,
    proxy: apiProxy
  },
  preview: {
    proxy: apiProxy
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
    target: 'esnext'   // pdfjs-dist 4.x 使用了 top-level await
  },
  optimizeDeps: {
    // pdfjs-dist / tesseract.js 都是按需动态 import 的大依赖，预构建避免 dev 加载失败
    include: ['pdfjs-dist', 'tesseract.js'],
    esbuildOptions: {
      target: 'esnext'   // pdfjs-dist 4.x 含 top-level await
    }
  },
  worker: {
    format: 'es'
  }
})
