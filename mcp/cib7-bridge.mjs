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

// Claude Desktop, claude.ai and Claude Code can now add the server by URL as a
// connector (docs/mcp.md); this bridge is only for a local stack the cloud
// cannot reach. CIB7_MCP_URL points it elsewhere, and mcp-remote is found in
// the global npm root, so no path in this file is specific to one machine.

import { execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const MCP_URL = process.env.CIB7_MCP_URL ?? 'http://localhost:3000/mcp';
const npmRoot = execSync('npm root -g', { encoding: 'utf8', shell: true }).trim();
const proxyPath = join(npmRoot, 'mcp-remote', 'dist', 'proxy.js');
if (!existsSync(proxyPath)) {
  console.error(`mcp-remote not found at ${proxyPath}. Run: npm install -g mcp-remote`);
  process.exit(1);
}
const PROXY_ENTRY = pathToFileURL(proxyPath).href;

const clientInfo = JSON.stringify({
  client_id: 'cib7-mcp',
  token_endpoint_auth_method: 'none',
});

process.argv = [process.argv[0], PROXY_ENTRY, MCP_URL, '--static-oauth-client-info', clientInfo];

await import(PROXY_ENTRY);
