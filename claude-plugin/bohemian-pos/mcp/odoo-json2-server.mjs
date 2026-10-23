#!/usr/bin/env node
// Odoo 19 JSON-2 MCP server (stdio, zero dependencies) for the Bohemian Odoo POS project.
//
// Credentials, first match wins:
//   1. ODOO_URL / ODOO_DB / ODOO_USER / ODOO_API_KEY in the environment
//   2. the file named by ODOO_ENV_FILE
//   3. $CLAUDE_PROJECT_DIR/.env   (git-ignored in this repo)
//   4. ~/.config/bohemian-pos/odoo.env
//
// Read-only by default. Mutating methods (create, write, sync_from_ui, …) are refused unless
// ODOO_MCP_ALLOW_WRITE=1 — the target is the LIVE production database.

import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import { createInterface } from "node:readline";

const READ_METHODS = new Set([
  "search_read", "search_count", "search", "read", "fields_get", "name_search",
  "read_group", "default_get", "compute_all", // account.tax.compute_all is pure computation
]);

function loadEnvFile(path) {
  if (!path || !existsSync(path)) return {};
  const out = {};
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m) out[m[1]] = m[2].replace(/^["'<]|["'>]$/g, "");
  }
  return out;
}

function config() {
  const candidates = [
    process.env.ODOO_ENV_FILE,
    process.env.CLAUDE_PROJECT_DIR && join(process.env.CLAUDE_PROJECT_DIR, ".env"),
    join(homedir(), ".config", "bohemian-pos", "odoo.env"),
  ];
  let file = {};
  for (const c of candidates) {
    file = loadEnvFile(c);
    if (file.ODOO_API_KEY) break;
  }
  const get = (k) => process.env[k] || file[k];
  return {
    url: (get("ODOO_URL") || "").replace(/\/+$/, ""),
    db: get("ODOO_DB"),
    key: get("ODOO_API_KEY"),
    allowWrite: get("ODOO_MCP_ALLOW_WRITE") === "1",
  };
}

async function odoo(model, method, body = {}) {
  const c = config();
  if (!c.url || !c.db || !c.key) throw new Error("Odoo credentials not found (see header of odoo-json2-server.mjs)");
  if (!READ_METHODS.has(method) && !c.allowWrite) {
    throw new Error(`'${method}' may change production data; refused. Set ODOO_MCP_ALLOW_WRITE=1 to allow.`);
  }
  const resp = await fetch(`${c.url}/json/2/${model}/${method}`, {
    method: "POST",
    headers: {
      Authorization: `bearer ${c.key}`,
      "X-Odoo-Database": c.db,
      "Content-Type": "application/json",
      "User-Agent": "bohemian-pos-mcp",
    },
    body: JSON.stringify(body),
  });
  const text = await resp.text();
  if (!resp.ok) {
    let msg = text;
    try { const j = JSON.parse(text); msg = `${j.name || resp.status}: ${j.message || text}`; } catch {}
    throw new Error(`HTTP ${resp.status} ${msg}`.slice(0, 2000));
  }
  return text ? JSON.parse(text) : null;
}

const TOOLS = [
  {
    name: "odoo_search_read",
    description: "Search and read records from the live Odoo 19 DB (zenzebra1) via JSON-2. Always pass a small `fields` list and a `limit`.",
    inputSchema: {
      type: "object",
      properties: {
        model: { type: "string", description: "e.g. pos.config, product.product" },
        domain: { type: "array", description: 'Odoo domain, e.g. [["available_in_pos","=",true]]' },
        fields: { type: "array", items: { type: "string" } },
        limit: { type: "number", default: 20 },
        order: { type: "string" },
      },
      required: ["model"],
    },
    run: (a) => odoo(a.model, "search_read", { domain: a.domain || [], fields: a.fields || ["display_name"], limit: a.limit ?? 20, ...(a.order ? { order: a.order } : {}) }),
  },
  {
    name: "odoo_search_count",
    description: "Count records matching a domain.",
    inputSchema: { type: "object", properties: { model: { type: "string" }, domain: { type: "array" } }, required: ["model"] },
    run: (a) => odoo(a.model, "search_count", { domain: a.domain || [] }),
  },
  {
    name: "odoo_fields",
    description: "List a model's fields (name, type, string, relation). Use to verify a field exists before coding against it.",
    inputSchema: { type: "object", properties: { model: { type: "string" }, filter: { type: "string", description: "substring to match field names" } }, required: ["model"] },
    run: async (a) => {
      const f = await odoo(a.model, "fields_get", { attributes: ["type", "string", "relation", "required"] });
      return Object.fromEntries(Object.entries(f).filter(([k]) => !a.filter || k.includes(a.filter)));
    },
  },
  {
    name: "odoo_call",
    description: "Call any JSON-2 method with named args (e.g. account.tax.compute_all). Non-read methods are refused unless ODOO_MCP_ALLOW_WRITE=1.",
    inputSchema: { type: "object", properties: { model: { type: "string" }, method: { type: "string" }, args: { type: "object" } }, required: ["model", "method"] },
    run: (a) => odoo(a.model, a.method, a.args || {}),
  },
];

// ---- minimal MCP over stdio (newline-delimited JSON-RPC 2.0) ----
const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
const rl = createInterface({ input: process.stdin });
rl.on("line", async (line) => {
  if (!line.trim()) return;
  let req;
  try { req = JSON.parse(line); } catch { return; }
  const { id, method, params } = req;
  if (id === undefined) return; // notification
  try {
    if (method === "initialize") {
      return send({ jsonrpc: "2.0", id, result: {
        protocolVersion: params?.protocolVersion || "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "odoo-json2", version: "0.1.0" },
      } });
    }
    if (method === "ping") return send({ jsonrpc: "2.0", id, result: {} });
    if (method === "tools/list") {
      return send({ jsonrpc: "2.0", id, result: { tools: TOOLS.map(({ run, ...t }) => t) } });
    }
    if (method === "tools/call") {
      const tool = TOOLS.find((t) => t.name === params.name);
      if (!tool) throw new Error(`unknown tool ${params.name}`);
      try {
        const out = await tool.run(params.arguments || {});
        return send({ jsonrpc: "2.0", id, result: { content: [{ type: "text", text: JSON.stringify(out, null, 1) }] } });
      } catch (e) {
        return send({ jsonrpc: "2.0", id, result: { isError: true, content: [{ type: "text", text: String(e.message || e) }] } });
      }
    }
    send({ jsonrpc: "2.0", id, error: { code: -32601, message: `method not found: ${method}` } });
  } catch (e) {
    send({ jsonrpc: "2.0", id, error: { code: -32603, message: String(e.message || e) } });
  }
});
