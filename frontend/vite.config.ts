import { readFile } from 'node:fs';
import { resolve, sep } from 'node:path';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Serves the reference service pack's data (`packs/reference/frontend`) at
 * /pack/ during `npm run dev`, as nginx does in the image, so the SPA finds
 * the catalog, texts and form definitions. Dev only; refuses paths that leave
 * the pack directory.
 */
function servePack(): Plugin {
  const root = resolve(__dirname, '../packs/reference/frontend');
  return {
    name: 'serve-pack',
    configureServer(server) {
      server.middlewares.use('/pack', (req, res) => {
        const file = resolve(root, '.' + decodeURIComponent((req.url ?? '').split('?')[0]));
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
          res.setHeader('Content-Type', 'application/json');
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
