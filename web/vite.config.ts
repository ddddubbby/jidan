import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址默认本机 8080；别处开发时用环境变量 API_TARGET 覆盖
const target = process.env.API_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': { target, changeOrigin: true } },
  },
})
