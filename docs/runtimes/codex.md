# Codex

Codex has no ACP mode in its own binary. The adapter runs the `@agentclientprotocol/codex-acp` npm package, which wraps Codex, and the first start pays an `npx` download.

```yaml title="application.yaml: every Codex key"
spring:
  ai:
    acp:
      harness:
        runtimes:
          codex:
            package: "@agentclientprotocol/codex-acp@2.5.0"   # what npx runs (this is the default)
            # command: /usr/local/bin/codex-acp                # a pre-installed adapter instead of npx
            args: [ ]                                           # extra arguments for the adapter
            home: /var/lib/codex                                # CODEX_HOME; see "Codex's home directory"
            config-toml:                                        # written as CODEX_HOME/config.toml
              model_reasoning_effort: high
            env:
              RUST_LOG: warn
```

## Sandbox and approvals

codex-acp exposes Codex's approval and sandbox settings as three presets of its `mode` option, and `spring.ai.acp.harness.mode` selects one:

| `spring.ai.acp.harness.mode` | codex-acp name | Behaviour |
| --- | --- | --- |
| `read-only` | Ask for approval | Always asks to edit files outside the workspace and to use the internet. **Still writes inside the workspace without asking.** |
| `agent` *(default)* | Approve for me | Sandboxed; asks only for actions it detects as potentially unsafe. |
| `agent-full-access` | Full access | No sandbox: unrestricted access to the internet and to any file the process can reach. Never asks. |
| `plan` | Plan (`collaboration_mode`) | Plans before changing anything; the mode to pair with `permissions.policy: deny` for a reviewer. |

**To run Codex outside its sandbox**, select full access:

```yaml
spring:
  ai:
    acp:
      harness:
        runtime: codex
        mode: agent-full-access
        on-unsupported: fail        # refuse to run if the preset cannot be applied
```

or only for the turns that need it:

```java
agent.prompt().session("migration").user("Run the full integration build")
        .options(o -> o.mode("agent-full-access"))
        .call();
```

!!! danger "Important"

    Setting `sandbox_mode` or `approval_policy` in `config-toml` does **not** change this. Measured against codex-acp 1.13.0: with `sandbox_mode = "danger-full-access"` and `approval_policy = "never"` in `config.toml`, new sessions still start in `agent`. Use `spring.ai.acp.harness.mode`. With `on-unsupported: fail` you will know if it was not applied.

!!! warning

    Under `agent-full-access` Codex asks nothing, so `spring.ai.acp.harness.permissions` never gets a request to refuse. Whatever the JVM's user can reach, the agent can change, and the model's credentials are in its environment. Only run it where the machine or container is itself the sandbox: a disposable container with no secrets beyond the ones it needs, and nothing mounted that you would mind losing.

`spring.ai.acp.harness.mode` takes one value. codex-acp offers both a `mode` and a `collaboration_mode` option, and Spring AI ACP Harness Client applies the value to whichever option offers it.

## config.toml

`config-toml` is written as Codex's `config.toml`. YAML maps become TOML tables, and lists of scalars become arrays. Keys are Codex's own; see the [Codex configuration reference](https://github.com/openai/codex/blob/main/docs/config.md).

```yaml
spring:
  ai:
    acp:
      harness:
        runtimes:
          codex:
            config-toml:
              model_reasoning_effort: high           # low | medium | high | xhigh | max | ultra, as codex-acp advertises
              model_reasoning_summary: concise
              shell_environment_policy:              # becomes the [shell_environment_policy] table
                inherit: core
```

`model_reasoning_effort` is the way to set reasoning effort: codex-acp advertises it as a `reasoning_effort` option with no portable property, and a value in `config.toml` becomes that option's starting value (verified against 1.13.0).

!!! warning "Caution"

    The file the adapter writes is the **only** `config.toml` Codex reads. Setting `config-toml` moves `CODEX_HOME` to a directory the adapter owns, so a developer's `~/.codex/config.toml` (their model, MCP servers, profiles) stops applying. That is deliberate, since a server should not depend on a personal file, but it can surprise you locally.

## Codex's home directory and credentials

Codex keeps its config *and* its login (`auth.json`) under `CODEX_HOME`, so where that points decides both:

| Configuration | `CODEX_HOME` | Credentials used |
| --- | --- | --- |
| No `config-toml`, no key, no endpoint | Inherited (`~/.codex`) | The existing `codex login`, untouched |
| `config-toml` only | `<runtime-home>` | The existing login, linked in (a symlink to `~/.codex/auth.json`) |
| `spring.ai.acp.harness.provider.api-key` against OpenAI | `<runtime-home>` | That key, sent with ACP `authenticate` and held in memory only |
| A bring-your-own endpoint | `<runtime-home>` | The endpoint's key, read by Codex from the environment |
| `home` set | That directory | As above, but in the directory you chose |

!!! warning

    Do not point `home` at your own `~/.codex` while also setting `config-toml` or a provider: the adapter writes `config.toml` into its home and would replace yours.

## Pinning or pre-installing codex-acp

`package` pins the npm version `npx` runs; the default is the version this release was tested against. To avoid the download at startup, for example in a container image, install the adapter at build time and set `command` to it. `package` is then ignored, and `args` are passed to the command you named.
