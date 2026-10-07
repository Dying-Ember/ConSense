import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

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
    proxy: {
      '/api': {
        target: process.env.CONSENSE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        // 整包起草会逐段处理完整 SCC；各 API 的客户端超时仍分别限制。
        timeout: 3600000,
        proxyTimeout: 3600000
      }
    }
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
