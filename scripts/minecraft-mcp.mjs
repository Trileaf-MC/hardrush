#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { spawn } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const side = process.argv[2] ?? 'server';
if (!['server', 'client'].includes(side)) throw new Error('Expected server or client');
const lock = JSON.parse(readFileSync(resolve(root, 'scripts/debug-tools.lock.json'), 'utf8'));
const cfg = JSON.parse(readFileSync(resolve(root, `.local/debug/${side}/config/mcpfabric.config.json`), 'utf8'));
if (cfg.host !== '127.0.0.1' || cfg.port !== (side === 'server' ? 25599 : 25601) || !cfg.requireAuth || !cfg.token) {
  throw new Error('Refusing unexpected bridge endpoint/auth settings');
}
const mcp = resolve(root, `.local/devtools/mcpfabric-${lock.mcpfabricSource.commit}/mcp-server`);
const child = spawn(process.execPath, [resolve(mcp, 'dist/index.js')], {
  cwd: mcp, stdio: 'inherit', env: {
    ...process.env, MCPFABRIC_URL: `http://127.0.0.1:${cfg.port}`, MCPFABRIC_TOKEN: cfg.token,
    MCPFABRIC_TRANSPORT: 'stdio', MCPFABRIC_TIMEOUT_MS: '45000',
    MCPFABRIC_DATA_DIR: resolve(root, `.local/debug/${side}/agent`),
  },
});
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => child.kill(signal));
child.on('exit', code => { process.exitCode = code ?? 1; });
