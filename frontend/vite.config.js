import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Builds straight into the eod-ui module so both runtimes serve it from classpath:/static.
// `npm run dev` proxies the API to a locally running backend on :8080.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../eod-ui/src/main/resources/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 800
  },
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: false } }
  }
});
