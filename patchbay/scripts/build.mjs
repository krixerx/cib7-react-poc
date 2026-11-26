// Builds the core mail-send and pdf-render integrations in a fresh Patchbay 0.1.0 through its admin API.
// Usage: node build.mjs <baseUrl> <user:password> <mailpitSwagger> <pdfOpenapi> [publish]
import fs from 'node:fs';
const [base, login, mailSwagger, pdfOpenapi, publish] = process.argv.slice(2);
const auth = 'Basic ' + Buffer.from(login).toString('base64');
async function call(method, path, body) {
  const r = await fetch(base + path, { method, headers: { Authorization: auth, ...(body ? { 'Content-Type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${t}`);
  return t ? JSON.parse(t) : null;
}
const f = (id, name, type, required, extra = {}) => ({ id, name, type, required, ...extra });
const defs = [
  { id: 'mail-send', name: 'Mail send (v1)', fields: [
    f('ms-from', 'from', 'object', true, { fields: [f('ms-from-email', 'email', 'text', true), f('ms-from-name', 'name', 'text', false)] }),
    f('ms-to', 'to', 'list', true, { items: { id: 'ms-to-item', name: 'item', type: 'object', required: false, fields: [f('ms-to-email', 'email', 'text', true), f('ms-to-name', 'name', 'text', false)] } }),
    f('ms-subject', 'subject', 'text', true),
    f('ms-text', 'text', 'text', true),
    f('ms-attachments', 'attachments', 'list', false, { items: { id: 'ms-att-item', name: 'item', type: 'object', required: false, fields: [f('ms-att-filename', 'filename', 'text', true), f('ms-att-content-type', 'contentType', 'text', true), f('ms-att-base64', 'base64', 'text', true)] } }),
  ] },
  { id: 'mail-send-result', name: 'Mail send result (v1)', fields: [f('msr-message-id', 'messageId', 'text', false)] },
  { id: 'pdf-render', name: 'PDF render (v1)', fields: [f('pr-html', 'html', 'text', true), f('pr-filename', 'filename', 'text', true)] },
  { id: 'pdf-render-result', name: 'PDF render result (v1)', fields: [f('prr-filename', 'filename', 'text', false), f('prr-base64', 'base64', 'text', false)] },
];
await call('POST', '/admin/api/contracts', { name: 'mailpit', content: fs.readFileSync(mailSwagger, 'utf8') });
await call('POST', '/admin/api/contracts', { name: 'pdf-renderer', content: fs.readFileSync(pdfOpenapi, 'utf8') });
for (const d of defs) await call('PUT', '/admin/api/definitions/' + d.id, d);
const integrations = [
  { slug: 'mail-send', input: 'mail-send', output: 'mail-send-result', contract: 'mailpit', op: 'SendMessageParams',
    request: [
      ['ms-from-email', 'body.From.Email'], ['ms-from-name', 'body.From.Name'],
      ['ms-to', 'body.To[]'], ['ms-to-email', 'body.To[].Email'], ['ms-to-name', 'body.To[].Name'],
      ['ms-subject', 'body.Subject'], ['ms-text', 'body.Text'],
      ['ms-attachments', 'body.Attachments[]'], ['ms-att-filename', 'body.Attachments[].Filename'],
      ['ms-att-content-type', 'body.Attachments[].ContentType'], ['ms-att-base64', 'body.Attachments[].Content'],
    ],
    response: [['ID', 'msr-message-id']] },
  { slug: 'pdf-render', input: 'pdf-render', output: 'pdf-render-result', contract: 'pdf-renderer', op: 'render',
    request: [['pr-html', 'body.html'], ['pr-filename', 'body.filename']],
    response: [['filename', 'prr-filename'], ['base64', 'prr-base64']] },
];
for (const i of integrations) {
  await call('POST', '/admin/api/integrations', { name: i.slug });
  await call('PUT', `/admin/api/integrations/${i.slug}/payloads`, { input: i.input, output: i.output });
  await call('PUT', `/admin/api/integrations/${i.slug}/operation`, { contract: i.contract, operationId: i.op });
  await call('PUT', `/admin/api/integrations/${i.slug}/links`, {
    request: i.request.map(([from, to]) => ({ from, to })), response: i.response.map(([from, to]) => ({ from, to })) });
  if (publish === 'publish') console.log(i.slug, JSON.stringify(await call('POST', `/admin/api/integrations/${i.slug}/publish`)));
}
console.log('built');
