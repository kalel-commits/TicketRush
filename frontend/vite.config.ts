import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { demoHarness } from './demo-harness/plugin';

export default defineConfig({
  plugins: [react(), demoHarness()],   // demo harness: dev server only (apply: 'serve')
  server: {
    port: 5173, strictPort: true,
    // Vite 5.4.10 answers any origin with ACAO:* (CVE-2025-24010): only this app's own origin may read dev files
    cors: { origin: /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/ },
    fs: { deny: ['.env', '.env.*', '*.{crt,pem}', '**/.state.json'] },
  },
  test: { environment: 'jsdom', setupFiles: ['./src/test/setup.ts'], include: ['src/**/*.test.{ts,tsx}', 'demo-harness/**/*.test.ts'] },
});
