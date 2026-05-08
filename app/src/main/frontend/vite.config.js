import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],

  // During 'npm run dev', proxy /api to the Spring Boot server on :8282
  server: {
    port: 5173,
    allowedHosts: true,
    proxy: {
      '/api': 'http://localhost:8282'
    }
  },

  build: {
    // Output goes directly into Spring's static resource directory
    outDir: resolve(__dirname, '../resources/static'),
    emptyOutDir: true
  },

  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  }
})
