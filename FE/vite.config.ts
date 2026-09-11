import react from '@vitejs/plugin-react'
// 'vitest/config' re-exports vite's defineConfig with the `test` key typed —
// plain 'vite' doesn't know about it.
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // BE runs on :5000 (Spring Boot, see ../BE/README.md). Proxying in dev
      // avoids needing ALLOWED_ORIGIN_PATTERNS/CORS config just to run locally.
      '/api': 'http://localhost:5000',
      '/ws': { target: 'ws://localhost:5000', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
  },
})
