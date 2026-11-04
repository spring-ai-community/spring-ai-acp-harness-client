# Runtime Configuration

Each agent has settings of its own that ACP does not standardize: Goose's built-in extensions, Codex's sandbox, OpenCode's per-tool permissions. They live in the third configuration tier, one block per runtime:

```yaml title="application.yaml"
spring:
  acp:
    runtime: goose
    runtimes:
      goose:    { builtins: developer }                       # used: goose is selected
      codex:    { config-toml: { model_reasoning_effort: high } }   # ignored until codex is selected
      opencode: { config: { share: disabled } }               # ignored until opencode is selected
```

- **Only the selected runtime reads its block.** Blocks for the others are inert, so an application can keep settings for every agent it supports side by side. A block for an id that no adapter or registry can supply fails startup, which catches a misspelled runtime name.

- **Applied last.** Whatever the adapter derives from portable properties (provider credentials, a bring-your-own endpoint) is written first, and the tier-3 block is laid over it. `env` therefore wins over every variable the adapter sets. For the config-file blocks the merge is by top-level key, so a top-level key you set replaces the adapter's value for that key, including a whole table.

- **Any property source works.** Top-level keys bind relaxed, while nested keys keep the spelling you wrote, because they end up verbatim in a file the agent parses:

    ```properties
    spring.acp.runtimes.codex.config-toml.model_reasoning_effort=high
    spring.acp.runtimes.goose.env.GOOSE_MAX_TURNS=40
    ```

- **Files are written to `spring.acp.runtime-home`** (a directory under the JVM's temp directory by default), never to the workspace.

!!! tip

    Prefer the portable property whenever one exists. It keeps working when you swap agents, and the negotiated ones report what was applied. Use tier 3 for what is genuinely agent-specific.

    | To set… | Use | Not |
    | --- | --- | --- |
    | Model, mode, provider | `spring.acp.model`, `.mode`, `.provider` | `GOOSE_MODEL`, `GOOSE_MODE`, `model` in `config-toml` or `config` |
    | MCP servers | `spring.acp.mcp-servers` | Goose extensions, Codex `mcp_servers`, OpenCode `mcp` |
    | Skills | `spring.acp.skills` | OpenCode `skills` paths |
    | Instructions | `<workspace>/AGENTS.md` | OpenCode `instructions`, unless you need more than one file |
