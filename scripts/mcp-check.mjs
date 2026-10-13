#!/usr/bin/env node
// Uses the real SDK/stdio transport. Can also invoke any discovered MCP tool.
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, dirname } from 'node:path';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const lock = JSON.parse(readFileSync(resolve(root, 'scripts/debug-tools.lock.json'), 'utf8'));
const require = createRequire(resolve(root, `.local/devtools/mcpfabric-${lock.mcpfabricSource.commit}/mcp-server/package.json`));
const { Client } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/index.js')).href);
const { StdioClientTransport } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/stdio.js')).href);
const side = process.argv[2] ?? 'server';
assert(['server', 'client'].includes(side));
const client = new Client({ name: 'hardrush-verification', version: '1.0.0' });
const transport = new StdioClientTransport({ command: process.execPath,
  args: [resolve(root, 'scripts/minecraft-mcp.mjs'), side], stderr: 'inherit' });
const evidence = { side, timestamp: new Date().toISOString(), results: {} };
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args }, undefined, { timeout: 60000 });
  assert(!result.isError, JSON.stringify(result));
  for (const block of result.content ?? []) {
    if (block.type === 'image') {
      const path = resolve(root, `.local/debug/${side}/screenshots/mcp-${name}.png`);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, Buffer.from(block.data, 'base64'));
      block.data = `<saved to ${path}>`;
    }
  }
  evidence.results[name] = result;
  if (result.structuredContent) return result.structuredContent;
  const text = result.content.find(x => x.type === 'text')?.text ?? '';
  try { return JSON.parse(text); } catch { return text; }
}
try {
  await client.connect(transport);
  const list = await client.listTools();
  evidence.toolCount = list.tools.length;
  assert(list.tools.some(x => x.name === 'get_status'));
  const status = await call('get_status');
  assert.equal(status.minecraftVersion, '26.3');
  assert.equal(status.side, side === 'server' ? 'dedicated_server' : 'client');
  if (process.argv[3]) {
    await call(process.argv[3], JSON.parse(process.argv[4] ?? '{}'));
  } else if (side === 'server') {
    assert.equal(status.serverPresent, true);
    assert.equal((await call('run_command', { command: 'hardrushp0 status' })).success, true);
    await call('list_dimensions');
  } else {
    const before = await call('get_self'); await call('get_inventory');
    await call('look', { yaw: 0, pitch: 0 });
    try {
      await call('set_movement', { forward: true });
      await new Promise(r => setTimeout(r, 1200));
    } finally { await call('stop_movement'); }
    const after = await call('get_self');
    assert(Math.hypot(after.x - before.x, after.z - before.z) > .1, 'MCP movement did not move the actual player');
    evidence.movement = { before: { x: before.x, z: before.z }, after: { x: after.x, z: after.z } };
    // Screenshots have text metadata instead of a JSON object.
    const result = await client.callTool({ name: 'screenshot', arguments: {} });
    assert(!result.isError, JSON.stringify(result));
    const block = result.content.find(x => x.type === 'image');
    assert(block);
    const path = resolve(root, '.local/debug/client/screenshots/mcp-screenshot.png');
    mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, Buffer.from(block.data, 'base64'));
    evidence.results.screenshot = { path, bytes: Buffer.from(block.data, 'base64').length };
  }
  const target = resolve(root, `.local/debug/mcp-${side}-evidence.json`);
  writeFileSync(target, JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify({ side, toolCount: evidence.toolCount, evidence: target, movement: evidence.movement,
    ...(process.argv[3] ? { result: evidence.results[process.argv[3]] } : {}) }, null, 2));
} finally { await client.close(); }
