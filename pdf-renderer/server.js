// JSON-in / JSON-out wrapper around Gotenberg's /forms/chromium/convert/html
// endpoint. Exists because CIB seven's http-connector returns the HTTP body as
// a String (corrupting binary PDFs) and has no native multipart support. By
// hiding both warts behind a tiny REST facade, the BPMN can keep using the
// same connector + FreeMarker pattern it already uses for the email tasks.
//
// Request:  POST /render  { "html": "<html>...</html>", "filename": "x.pdf" }
// Response: 200 { "filename": "x.pdf", "base64": "JVBERi0x..." }

const express = require('express');

const GOTENBERG_URL = process.env.GOTENBERG_URL || 'http://gotenberg:3000';
const PORT = Number(process.env.PORT || 8088);

const app = express();
app.use(express.json({ limit: '5mb' }));

app.get('/health', (_req, res) => res.json({ ok: true }));

app.post('/render', async (req, res) => {
  const { html, filename } = req.body || {};
  if (typeof html !== 'string' || !html.length) {
    return res.status(400).json({ error: 'html is required' });
  }
  const outName = typeof filename === 'string' && filename.length ? filename : 'document.pdf';

  // Gotenberg expects multipart/form-data with a file part named "files"
  // whose filename is exactly "index.html". FormData.set with a Blob is
  // available natively on Node 18+ (undici), so no extra dependency.
  const form = new FormData();
  form.set('files', new Blob([html], { type: 'text/html' }), 'index.html');

  let upstream;
  try {
    upstream = await fetch(`${GOTENBERG_URL}/forms/chromium/convert/html`, {
      method: 'POST',
      body: form,
    });
  } catch (err) {
    return res.status(502).json({ error: 'gotenberg unreachable', detail: String(err) });
  }
  if (!upstream.ok) {
    const text = await upstream.text().catch(() => '');
    return res.status(502).json({ error: 'gotenberg failed', status: upstream.status, detail: text });
  }

  const pdfBytes = Buffer.from(await upstream.arrayBuffer());
  res.json({ filename: outName, base64: pdfBytes.toString('base64') });
});

app.listen(PORT, () => {
  console.log(`pdf-renderer listening on :${PORT}, gotenberg=${GOTENBERG_URL}`);
});
