import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api (including the SSE stream) to the Cajun-powered
// Javalin backend on port 7070, so the frontend and API share an origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:7070',
        changeOrigin: true,
      },
    },
  },
});
