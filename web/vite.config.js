import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 部署在 https://chutang.work/liteark/ 子路径下，资源路径需带前缀
// 默认根路径部署；部署到子路径时用 VITE_BASE=/liteark/ npm run build
const BASE = process.env.VITE_BASE || '/'

export default defineConfig({
  base: BASE,
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1200,
  },
})
