import { readFile } from 'node:fs';
import { resolve, sep } from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

const CONTENT_TYPES: Record<string, string> = {
  json: 'application/json',
  svg: 'image/svg+xml',
  png: 'image/png',
  webp: 'image/webp',
};

/**
 * Serves the reference service pack's data at /pack/ during `npm run dev`, as
 * nginx does in the image: `packs/reference/frontend` (catalog, texts, form
 * definitions) and `packs/reference/branding` under /pack/branding/. Dev
 * only; refuses paths that leave the pack directory.
 */
function servePack(): Plugin {
  const frontendRoot = resolve(__dirname, '../packs/reference/frontend');
  const brandingRoot = resolve(__dirname, '../packs/reference/branding');
  return {
    name: 'serve-pack',
    configureServer(server) {
      server.middlewares.use('/pack', (req, res) => {
        const path = decodeURIComponent((req.url ?? '').split('?')[0]);
        const branding = path.startsWith('/branding/');
        const root = branding ? brandingRoot : frontendRoot;
        const file = resolve(root, '.' + (branding ? path.slice('/branding'.length) : path));
        if (!file.startsWith(root + sep)) {
          res.statusCode = 404;
          res.end();
          return;
        }
        readFile(file, (err, data) => {
          if (err) {
            res.statusCode = 404;
            res.end();
            return;
          }
          const type = CONTENT_TYPES[file.split('.').pop() ?? ''] ?? 'application/octet-stream';
          res.setHeader('Content-Type', type);
          res.end(data);
        });
      });
    },
  };
}

// During `npm run dev`, /engine-rest is proxied to the CIB seven engine
// (cib7/, port 8080) and /api to the business backend (backend/, port
// 8085) so the browser always talks to a same-origin URL (no CORS
// needed). In the Docker image, nginx performs the equivalent proxy
// (see nginx.conf).
//
// /api/public/** carries the public, unauthenticated confirmation and
// payment endpoints. /engine-rest/** is the CIB seven engine REST API
// and is always bearer-token authenticated.
export default defineConfig({
  plugins: [react(), servePack()],
  server: {
    port: 5173,
    proxy: {
      '/engine-rest': 'http://localhost:8080',
      '/api': 'http://localhost:8085',
    },
  },
});
