import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    proxy: {
      '/oauth/token': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
      '/v1/insights': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
