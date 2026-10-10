import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// Dev proxy targets. Defaults match each backend's own default:
//   Quarkus backend   → http://localhost:8086
//   Kafka orchestrator → http://localhost:8080 (agent-orchestrator server.port)
// Override without touching this file, e.g. when the orchestrator runs via the
// compose stack on host port 19500:
//   VITE_ORCH_PROXY_TARGET=http://localhost:19500 npm run dev
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const orchTarget = env.VITE_ORCH_PROXY_TARGET || 'http://localhost:8080'
  const quadTarget = env.VITE_QUAD_PROXY_TARGET || 'http://localhost:8086'

  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        // Unified API Gateway (Spring Boot orchestrator, REST /api/v1).
        '/api/v1': { target: orchTarget, changeOrigin: true, secure: false },
        // Quarkus backend (primary data plane). All other /api/* → Quarkus.
        '/api': { target: quadTarget, changeOrigin: true, secure: false },
      },
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
  }
})
