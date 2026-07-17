import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Component/unit tests for the SPA. jsdom + Testing Library; no browser, no dev server.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
