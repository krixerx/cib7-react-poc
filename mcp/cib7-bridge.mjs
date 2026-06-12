// Launcher that bridges Claude Desktop's stdio MCP transport to the cib7-poc
// HTTP MCP server. Spawned as `node cib7-bridge.mjs` from
// claude_desktop_config.json on Windows.
//
// We reach into mcp-remote's CLI entry by overriding process.argv before
// importing it. This bypasses BOTH cmd.exe and PowerShell — Windows shells
// strip the inner double quotes inside the inline JSON value of
// --static-oauth-client-info, so launching mcp-remote through any shell ends
// in "SyntaxError: Expected property name" inside its argv parser. Building
// the JSON in JS and handing it to import() avoids the shell layer entirely.
//
// Why not let mcp-remote do OAuth 2.0 Dynamic Client Registration? Keycloak's
// default Trusted Hosts policy rejects anonymous DCR. We pre-register
// cib7-mcp in the realm export and tell mcp-remote to use that client_id.

const MCP_URL = 'http://localhost:3000/mcp';
const PROXY_ENTRY = 'file:///C:/nvm4w/nodejs/node_modules/mcp-remote/dist/proxy.js';

const clientInfo = JSON.stringify({
  client_id: 'cib7-mcp',
  token_endpoint_auth_method: 'none',
});

process.argv = [process.argv[0], PROXY_ENTRY, MCP_URL, '--static-oauth-client-info', clientInfo];

await import(PROXY_ENTRY);
