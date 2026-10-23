# bohemian-pos — Claude Code plugin

Project knowledge and tools for the Bohemian Odoo POS (Pine Labs E700, Odoo 19).

## Install

```
/plugin marketplace add diwakar-bhagat/pinelabs
/plugin install bohemian-pos@pinelabs
```

## Odoo credentials

The `odoo-json2` MCP server reads, first match wins: environment variables, `$ODOO_ENV_FILE`,
`<repo>/.env` (git-ignored), `~/.config/bohemian-pos/odoo.env`:

```
ODOO_URL=https://zenzebra1.odoo.com
ODOO_DB=zenzebra1
ODOO_USER=<login>
ODOO_API_KEY=<key>
```

Read-only by default (search_read, search_count, read, fields_get, compute_all, …).
Set `ODOO_MCP_ALLOW_WRITE=1` only when you mean to change the production database.

## Skills

| Skill | Use |
|---|---|
| `money-safety-review` | Check diffs against the 12 hard rules and invariants I1–I10 |
| `pine-sdk` | Verified Pine SDK 1.0.0 facts; `scripts/sdk-signatures.sh` dumps signatures from the AAR |
| `odoo-json2` | JSON-2 API shape and live-DB facts |
| `build-test` | Fresh-clone setup, Gradle tasks, test baseline |
| `uat-e700` | 10-case UAT script and how to record results |
| `deploy-e700` | Signed APK → Pine → PAXSTORE pipeline and signing-key rules |
