import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// During `npm run dev`, /engine-rest calls are proxied to the CIB seven
// backend so the browser always talks to a same-origin URL (no CORS needed).
// In the Docker image, nginx performs the equivalent proxy (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/engine-rest': 'http://localhost:8080',
    },
  },
});
