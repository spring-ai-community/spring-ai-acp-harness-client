# Configuration Properties

All properties live under `spring.ai.acp.harness` (or at the top level of a file imported with `acp:`).

## Core

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.enabled` | `true` | Switches the auto-configuration off entirely. |
| `spring.ai.acp.harness.runtime` | — | Agent to run. Optional with exactly one adapter on the classpath. |
| `spring.ai.acp.harness.workspace` | JVM working dir | The agent's working directory. Must exist. |
| `spring.ai.acp.harness.runtime-home` | temp dir | Where adapters write agent config files. Never the workspace. |
| `spring.ai.acp.harness.timeout` | `5m` | Longest a turn may take (max 24h). |
| `spring.ai.acp.harness.model` | — | Requested model. Negotiated. |
| `spring.ai.acp.harness.mode` | — | Requested session mode. Negotiated. |
| `spring.ai.acp.harness.on-unsupported` | `warn` | `fail`, `warn` or `ignore`. |
| `spring.ai.acp.harness.provider.id` | — | Provider as the agent names it. Negotiated. |
| `spring.ai.acp.harness.provider.api-type` | — | API dialect; names the credential env vars. |
| `spring.ai.acp.harness.provider.base-url` | — | Your own endpoint; makes its `/models` authoritative. |
| `spring.ai.acp.harness.provider.api-key` | — | Provider key. Never logged. |
| `spring.ai.acp.harness.provider.headers` | — | Extra provider headers. |
| `spring.ai.acp.harness.tanzu-ai.enabled` | `true` | Use a bound Tanzu AI Models service as the provider; see [Tanzu AI Models](configuration/models.md#tanzu-ai-models-on-cloud-foundry). |
| `spring.ai.acp.harness.tanzu-ai.service-name` | — | Which bound service to use when there are several. |

## MCP and skills

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.mcp-servers[n].name` | — | Required, unique. |
| `spring.ai.acp.harness.mcp-servers[n].url`, `.headers`, `.auth` | —, —, `none` | HTTP transport. `.auth` is `none`, `oauth` or `provided`; only `provided` may omit `.url`. |
| `spring.ai.acp.harness.mcp-servers[n].command`, `.args`, `.env` | — | stdio transport. |
| `spring.ai.acp.harness.mcp.on-server-failure` | `warn` | `warn` or `fail` (Goose only). |
| `spring.ai.acp.harness.mcp.detect-timeout` | `2s` | Wait for a load-failure report when `fail`. |
| `spring.ai.acp.harness.mcp.oauth.mode` | `web` | `web` or `local`. Needs `spring-ai-acp-harness-client-mcp-oauth`. |
| `spring.ai.acp.harness.mcp.oauth.client-name` | app name | Shown on consent screens. |
| `spring.ai.acp.harness.mcp.oauth.base-url` | from request | Public base URL for the redirect URI. |
| `spring.ai.acp.harness.mcp.oauth.redirect-uri` | `{baseUrl}/login/oauth2/code/{registrationId}` | Registered callback. |
| `spring.ai.acp.harness.mcp.oauth.store` | by mode | `memory`, `jdbc` or `file`. |
| `spring.ai.acp.harness.mcp.oauth.file` | `~/.config/<app>/acp-mcp-oauth.json` | For `store: file`. |
| `spring.ai.acp.harness.mcp.oauth.sign-in-timeout` | `5m` | How long a `local` sign-in waits. |
| `spring.ai.acp.harness.mcp.oauth.allow-loopback` | `false` | Allow http/loopback OAuth endpoints (development only). |
| `spring.ai.acp.harness.skills[n].name`, `.path`, `.url`, `.ref`, `.token`, `.sha256` | — | See [Skill properties](configuration/skills.md#skill-properties). |

## Permissions, filesystem, terminal

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.permissions.policy` | `deny` | `deny`, `allowlist`, `auto-approve` or `ask`. |
| `spring.ai.acp.harness.permissions.allowed-tools` | `[]` | Tool names for `allowlist`. |
| `spring.ai.acp.harness.filesystem.enabled` | `false` | Answer `fs/read_text_file`. |
| `spring.ai.acp.harness.filesystem.write` | `false` | Also answer `fs/write_text_file`. |
| `spring.ai.acp.harness.terminal.enabled` | `false` | Answer `terminal/*`. |
| `spring.ai.acp.harness.terminal.allowed-commands` | `[]` (any) | Permitted command names. |
| `spring.ai.acp.harness.terminal.output-limit` | `1MB` | Output kept per terminal. |
| `spring.ai.acp.harness.terminal.command-timeout` | `5m` | Kill a command after this. |
| `spring.ai.acp.harness.terminal.max-concurrent` | `8` | Terminals per connection. |

## Pool, endpoint, protocol, integrations

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.pool.max-processes` | `1` | Agent processes to run. Raise for unrelated conversations in parallel. For OpenCode, also the number of users who can hold sessions at once, at about 650 MB each; see [Processes, users and memory](runtimes/opencode.md#processes-users-and-memory). |
| `spring.ai.acp.harness.pool.max-sessions-per-process` | `32` | Named sessions per process before another is preferred. For OpenCode, sessions per user. |
| `spring.ai.acp.harness.pool.session-ttl` | `60m` | Idle time before a named session is closed. |
| `spring.ai.acp.harness.pool.max-restarts` | `5` | Replacements within five minutes before a connection is left down. |
| `spring.ai.acp.harness.controller.*` | off | See [HTTP Endpoint](integrations/http-endpoint.md). |
| `spring.ai.acp.harness.console.*` | when in a terminal | See [Terminal Console](integrations/console.md); needs `spring-ai-acp-harness-client-console`. |
| `spring.ai.acp.harness.protocol.max-version` | `1` | Highest ACP version offered. v2 is refused. |
| `spring.ai.acp.harness.protocol.strict` | `false` | Fail, rather than clamp, when an agent answers a version it was not offered. |
| `spring.ai.acp.harness.observations.enabled` | `true` | Micrometer observations, when Micrometer is present. |
| `spring.ai.acp.harness.chat-model.enabled` | `true` | The `AcpChatModel` bean, when `spring-ai-acp-harness-client-ai` is present. |

## Registry

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.registry.enabled` | `true` | Consult the registry for a runtime no adapter claims. |
| `spring.ai.acp.harness.registry.url` | ACP CDN `registry.json` | Catalogue location; `file:` pins a snapshot. |
| `spring.ai.acp.harness.registry.cache` | `~/.spring-ai-acp/agents` | Snapshot and downloaded agents. |
| `spring.ai.acp.harness.registry.refresh` | `24h` | Catalogue refresh interval. |
| `spring.ai.acp.harness.registry.offline` | `false` | No network: bundled snapshot and installed agents only. |
| `spring.ai.acp.harness.registry.require-checksum` | `true` | Refuse agents published without a SHA-256. |
| `spring.ai.acp.harness.registry.download-timeout` | `10m` | Longest one download may take. |

## Runtime-specific (tier 3)

Described per runtime in [Runtime Configuration](runtimes/configuration.md).

| Runtime | Keys under `spring.ai.acp.harness.runtimes.<id>` |
| --- | --- |
| `goose` | `builtins`, `env`, `serve.transport`, `serve.host`, `serve.port`, `serve.startup-timeout`, `log-dir`, `mcp.answer-discover` |
| `codex` | `package`, `command`, `args`, `home`, `config-toml`, `env` |
| `opencode` | `command`, `args`, `config`, `config-file`, `env` |
| registry ids | `args`, `env` |

Keys bind relaxed (`config-toml`, `configToml` and `config_toml` are one key), while nested keys keep their spelling because they end up verbatim in the agent's own config file.
