import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Kafka/Orchestrator backend (Spring Boot, REST /api/v1 + unified gateway).
      // kafkaApi.ts default base is relative `/api/v1` → forwarded to 8080.
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      // Quarkus backend (quad-ui primary data plane). All other /api/* → 8086.
      '/api': {
        target: 'http://localhost:8086',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  css: {
    preprocessorOptions: {},
  },
  resolve: {
    alias: {
      '@': '/src',
    },
  },
  build: {
    rollupOptions: {
      output: {
        assetFileNames: 'assets/[name].[hash][extname]',
      },
    },
  },
})