# Configuration Properties

All properties live under `spring.acp` (or at the top level of a file imported with `acp:`).

## Core

| Property | Default | Description |
| --- | --- | --- |
| `spring.acp.enabled` | `true` | Switches the auto-configuration off entirely. |
| `spring.acp.runtime` | — | Agent to run. Optional with exactly one adapter on the classpath. |
| `spring.acp.workspace` | JVM working dir | The agent's working directory. Must exist. |
| `spring.acp.runtime-home` | temp dir | Where adapters write agent config files. Never the workspace. |
| `spring.acp.timeout` | `5m` | Longest a turn may take (max 24h). |
| `spring.acp.model` | — | Requested model. Negotiated. |
| `spring.acp.mode` | — | Requested session mode. Negotiated. |
| `spring.acp.on-unsupported` | `warn` | `fail`, `warn` or `ignore`. |
| `spring.acp.provider.id` | — | Provider as the agent names it. Negotiated. |
| `spring.acp.provider.api-type` | — | API dialect; names the credential env vars. |
| `spring.acp.provider.base-url` | — | Your own endpoint; makes its `/models` authoritative. |
| `spring.acp.provider.api-key` | — | Provider key. Never logged. |
| `spring.acp.provider.headers` | — | Extra provider headers. |
| `spring.acp.tanzu-ai.enabled` | `true` | Use a bound Tanzu AI Models service as the provider; see [Tanzu AI Models](configuration/models.md#tanzu-ai-models-on-cloud-foundry). |
| `spring.acp.tanzu-ai.service-name` | — | Which bound service to use when there are several. |

## MCP and skills

| Property | Default | Description |
| --- | --- | --- |
| `spring.acp.mcp-servers[n].name` | — | Required, unique. |
| `spring.acp.mcp-servers[n].url`, `.headers`, `.auth` | —, —, `none` | HTTP transport. `.auth` is `none`, `oauth` or `provided`; only `provided` may omit `.url`. |
| `spring.acp.mcp-servers[n].command`, `.args`, `.env` | — | stdio transport. |
| `spring.acp.mcp.on-server-failure` | `warn` | `warn` or `fail` (Goose only). |
| `spring.acp.mcp.detect-timeout` | `2s` | Wait for a load-failure report when `fail`. |
| `spring.acp.mcp.oauth.mode` | `web` | `web` or `local`. Needs `spring-ai-acp-harness-client-mcp-oauth`. |
| `spring.acp.mcp.oauth.client-name` | app name | Shown on consent screens. |
| `spring.acp.mcp.oauth.base-url` | from request | Public base URL for the redirect URI. |
| `spring.acp.mcp.oauth.redirect-uri` | `{baseUrl}/login/oauth2/code/{registrationId}` | Registered callback. |
| `spring.acp.mcp.oauth.store` | by mode | `memory`, `jdbc` or `file`. |
| `spring.acp.mcp.oauth.file` | `~/.config/<app>/acp-mcp-oauth.json` | For `store: file`. |
| `spring.acp.mcp.oauth.sign-in-timeout` | `5m` | How long a `local` sign-in waits. |
| `spring.acp.mcp.oauth.allow-loopback` | `false` | Allow http/loopback OAuth endpoints (development only). |
| `spring.acp.skills[n].name`, `.path`, `.url`, `.ref`, `.token`, `.sha256` | — | See [Skill properties](configuration/skills.md#skill-properties). |

## Permissions, filesystem, terminal

| Property | Default | Description |
| --- | --- | --- |
| `spring.acp.permissions.policy` | `deny` | `deny`, `allowlist`, `auto-approve` or `ask`. |
| `spring.acp.permissions.allowed-tools` | `[]` | Tool names for `allowlist`. |
| `spring.acp.filesystem.enabled` | `false` | Answer `fs/read_text_file`. |
| `spring.acp.filesystem.write` | `false` | Also answer `fs/write_text_file`. |
| `spring.acp.terminal.enabled` | `false` | Answer `terminal/*`. |
| `spring.acp.terminal.allowed-commands` | `[]` (any) | Permitted command names. |
| `spring.acp.terminal.output-limit` | `1MB` | Output kept per terminal. |
| `spring.acp.terminal.command-timeout` | `5m` | Kill a command after this. |
| `spring.acp.terminal.max-concurrent` | `8` | Terminals per connection. |

## Pool, endpoint, protocol, integrations

| Property | Default | Description |
| --- | --- | --- |
| `spring.acp.pool.max-processes` | `1` | Agent processes to run. Raise for unrelated conversations in parallel. For OpenCode, also the number of users who can hold sessions at once, at about 650 MB each; see [Processes, users and memory](runtimes/opencode.md#processes-users-and-memory). |
| `spring.acp.pool.max-sessions-per-process` | `32` | Named sessions per process before another is preferred. For OpenCode, sessions per user. |
| `spring.acp.pool.session-ttl` | `60m` | Idle time before a named session is closed. |
| `spring.acp.pool.max-restarts` | `5` | Replacements within five minutes before a connection is left down. |
| `spring.acp.controller.*` | off | See [HTTP Endpoint](integrations/http-endpoint.md). |
| `spring.acp.console.*` | when in a terminal | See [Terminal Console](integrations/console.md); needs `spring-ai-acp-harness-client-console`. |
| `spring.acp.protocol.max-version` | `1` | Highest ACP version offered. v2 is refused. |
| `spring.acp.protocol.strict` | `false` | Fail, rather than clamp, when an agent answers a version it was not offered. |
| `spring.acp.observations.enabled` | `true` | Micrometer observations, when Micrometer is present. |
| `spring.acp.chat-model.enabled` | `true` | The `AcpChatModel` bean, when `spring-ai-acp-harness-client-ai` is present. |

## Registry

| Property | Default | Description |
| --- | --- | --- |
| `spring.acp.registry.enabled` | `true` | Consult the registry for a runtime no adapter claims. |
| `spring.acp.registry.url` | ACP CDN `registry.json` | Catalogue location; `file:` pins a snapshot. |
| `spring.acp.registry.cache` | `~/.spring-ai-acp/agents` | Snapshot and downloaded agents. |
| `spring.acp.registry.refresh` | `24h` | Catalogue refresh interval. |
| `spring.acp.registry.offline` | `false` | No network: bundled snapshot and installed agents only. |
| `spring.acp.registry.require-checksum` | `true` | Refuse agents published without a SHA-256. |
| `spring.acp.registry.download-timeout` | `10m` | Longest one download may take. |

## Runtime-specific (tier 3)

Described per runtime in [Runtime Configuration](runtimes/configuration.md).

| Runtime | Keys under `spring.acp.runtimes.<id>` |
| --- | --- |
| `goose` | `builtins`, `env`, `serve.transport`, `serve.host`, `serve.port`, `serve.startup-timeout`, `log-dir`, `mcp.answer-discover` |
| `codex` | `package`, `command`, `args`, `home`, `config-toml`, `env` |
| `opencode` | `command`, `args`, `config`, `config-file`, `env` |
| registry ids | `args`, `env` |

Keys bind relaxed (`config-toml`, `configToml` and `config_toml` are one key), while nested keys keep their spelling because they end up verbatim in the agent's own config file.
