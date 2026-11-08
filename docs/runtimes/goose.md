# Goose

The adapter launches `goose acp` over stdio and sets `GOOSE_DISABLE_KEYRING=1` and `GOOSE_TELEMETRY_ENABLED=false`. Goose still reads its own `~/.config/goose/config.yaml` for anything not set here, including which of its [built-in extensions](#built-in-extensions) are on.

```yaml title="application.yaml: every Goose key"
spring:
  ai:
    acp:
      harness:
        runtimes:
          goose:
            builtins: [ developer, todo, skills ]  # --with-builtin; a comma-separated string works too
            env:                                   # environment for the goose process, applied last
              GOOSE_THINKING_EFFORT: high
              GOOSE_MAX_TURNS: "40"
            serve:                                 # speak ACP over goose's WebSocket server instead of stdio
              transport: websocket                 # stdio (default) | websocket
              host: 127.0.0.1
              port: 0                              # 0 = any free port
              startup-timeout: PT30S
            log-dir: /var/log/goose/logs           # where goose writes its log, if not the default
            mcp:
              answer-discover: true                # workaround for MCP gateways; see below
```

## Built-in extensions

Goose's own tools come from extensions bundled in the binary. A session gets two sets of them:

1. **Goose's defaults**, as its `config.yaml` enables them. With no `config.yaml`, which is usual on a server or in a container, goose enables the defaults listed below. They load alongside the servers in `spring.ai.acp.harness.mcp-servers`, not instead of them.
2. **Whatever `builtins` names**, each passed as `--with-builtin`. These are added to the first set.

So `builtins` can only add extensions. Leaving `developer` out of it does not take the shell away from the agent; see [Turning a goose default off](#turning-a-goose-default-off).

Goose 1.52's extensions, by the name `builtins` takes:

| Name | On by default | Provides |
| --- | --- | --- |
| `developer` | Yes | `shell`, `write`, `edit`, `tree` and `read_image`: commands and file changes in the workspace. Needed to run a skill's scripts. |
| `analyze` | Yes | Code structure through tree-sitter: directory overviews, file details, call graphs. |
| `apps` | Yes | Tools to create and manage goose "apps" (HTML/CSS/JavaScript run in a sandboxed window). |
| `extensionmanager` | Yes | Tools to search for, enable and disable further extensions during a session. |
| `summon` | Yes | `load` and `delegate`: load knowledge, and hand tasks to subagents. |
| `skills` | Yes | `load_skill`: Agent Skills from the workspace's `.agents/skills`. |
| `tom` | Yes | "Top of mind": adds the text in `GOOSE_MOIM_MESSAGE_TEXT` or `GOOSE_MOIM_MESSAGE_FILE` to every turn. No tools. |
| `scheduler` | Yes, but not loaded | Scheduled recipe runs. Goose loads it only under `goose acp --enable-scheduler`, which the adapter does not pass. |
| `todo` | No | A task list the agent keeps for multi-step work. Reported as the agent's plan (`PlanUpdated`); see below. |
| `chatrecall` | No | Searching past conversations and loading their summaries. |
| `summarize` | No | Loading files or directories and returning an LLM summary in one call. |
| `code_execution` | No | "Code mode": the agent calls other extensions by writing code, to save tokens. |
| `orchestrator` | No | Managing other goose sessions: list, start, message, interrupt and stop them. |
| `computercontroller` | No | Web scraping, file caching and automation. Runs as a bundled MCP server. |
| `memory` | No | Tools to save and retrieve durable memories. Runs as a bundled MCP server. |
| `autovisualiser` | No | Data visualisation and UI generation (charts, diagrams). Runs as a bundled MCP server. |
| `tutorial` | No | Interactive goose tutorials. Rarely useful in an application. |

The list and the defaults change between goose releases. `todo` was on by default in goose 1.45 and is off in 1.52, for example. To see what your goose enables, run `goose info -v` with the same environment the application gives it: it prints the effective `extensions` section, defaults included.

```yaml title="application.yaml: add the task list to goose's defaults"
spring:
  ai:
    acp:
      harness:
        runtimes:
          goose:
            builtins: todo                 # or a list: [ todo, memory ]
```

!!! warning

    Because of the defaults, an agent on goose has a shell and file tools unless something turns them off. Under `spring.ai.acp.harness.mode: auto` or an `auto-approve` permission policy it uses them without asking, and the shell inherits your application's environment, secrets included. `spring.ai.acp.harness.filesystem` and `spring.ai.acp.harness.terminal` do not govern it: those lend the *client's* disk and terminal, and this shell is the agent's own.

## The todo list as the agent's plan

Goose sends no ACP `plan` updates. Its task list is the `todo` extension, and each change to it is an ordinary tool call, `todo__todo_write`, carrying the whole list as markdown. The adapter reads that call as the plan: it arrives as `AcpHarnessEvent.PlanUpdated`, and neither the call nor its update is reported as a tool call. A UI that renders the plan for Codex or OpenCode renders goose's task list the same way.

- `- [x]` is completed, `- [ ]` is pending, and `- [-]`, `- [~]`, `- [/]` or `- [>]` is in progress. Goose's own template uses only the first two, so a list usually shows items done and not yet done, rarely one in progress.
- A status word is read wherever a model tends to put it: trailing ("Draft report — in progress", "… (done)", "… \[DONE\]") or leading ("\[DONE\] Draft report"). It is dropped from the entry's text. A ✅, ✔ or ☑ in front of an item, or strikethrough, means completed. Anything else is pending. Models do not always follow goose's template.
- Sub-items become entries of their own, since ACP's plan is flat. Headings and prose lines are skipped.
- A write with no list items in it is reported as the tool call it is. An empty write clears the plan.

The agent writes a list only when it decides to, so name the tool in `AGENTS.md` or the prompt when you want one: "Keep a todo list with one item per request you draft."

## Turning a goose default off

Goose reads which defaults to enable from its `config.yaml`, and an entry with `enabled: false` takes one away. Nothing else needs to be in the file:

```yaml title="config.yaml: no shell, no extension manager"
extensions:
  developer:
    enabled: false
  extensionmanager:
    enabled: false
```

The adapter does not move goose's configuration, so goose looks for this file in its usual place: `~/.config/goose/config.yaml` for the user the application runs as. On a developer machine that is your own goose configuration. To give the application a file of its own, point `GOOSE_PATH_ROOT` at a directory through the `env` block. Goose then reads `<root>/config/config.yaml`, and keeps its sessions and logs under that root too, so set `log-dir` to `<root>/state/logs` as well ([see below](#log-directory-and-mcp-gateways)). A name disabled in the file and also listed in `builtins` is loaded: `builtins` wins.

!!! note

    A misspelled name does not stop goose. It writes `Failed to load extension <name>: … Unknown extension` (since 1.55.0, `failed to start extension` with the name and `Unknown extension` in fields of their own) to its log and carries on. Spring AI ACP Harness Client (SAAHC) reads that log for agents it started, so the mistake appears as a WARN line and in `AcpHarnessClient.notices()`, worded as a server that could not be loaded.

For tools that are not built in, declare an MCP server under `spring.ai.acp.harness.mcp-servers` rather than a goose extension. It is the same thing to goose, and it stays portable.

## Environment variables

Goose reads each of its `config.yaml` settings from an environment variable of the same name first, so the `env` block can set any of them. Some that are useful from an application:

| Variable | Effect |
| --- | --- |
| `GOOSE_THINKING_EFFORT` | Reasoning effort: `off`, `low`, `medium`, `high`, `max`. Goose advertises this as its `thinking_effort` option, which has no portable property. |
| `GOOSE_MAX_TURNS` | Maximum agent turns without user input. |
| `GOOSE_AUTO_COMPACT_THRESHOLD`, `GOOSE_CONTEXT_LIMIT` | When goose compacts its context, and the context size it assumes. |
| `GOOSE_TEMPERATURE` | Sampling temperature, for providers that accept one. |
| `OPENAI_BASE_PATH` | Pins the OpenAI wire API (e.g. `v1/chat/completions`). Left unset, goose chooses per model; see [Bring your own endpoint](../configuration/models.md#bring-your-own-endpoint). |
| `GOOSE_DISABLE_KEYRING` | `"0"` lets goose read secrets from the OS keychain again (the adapter disables it for servers). |

Environment values are strings, so quote numbers in YAML. `GOOSE_MODE`, `GOOSE_PROVIDER` and `GOOSE_MODEL` work too, but use the portable `spring.ai.acp.harness.mode`, `.provider` and `.model` instead.

## Modes

| `spring.ai.acp.harness.mode` | Behaviour |
| --- | --- |
| `auto` *(default)* | Edits, creates and deletes files and uses extensions freely. No permission requests. |
| `approve` | Every tool call, extension use and file change asks first. |
| `smart_approve` | Asks before editing, creating or deleting files and using extensions. |
| `chat` | No tools at all: the model only converses. |

In `approve` and `smart_approve`, each question is answered by [`spring.ai.acp.harness.permissions`](../configuration/permissions.md).

## Running goose as a WebSocket server

With `serve.transport: websocket`, the adapter starts `goose serve` on `host` and `port`, generates a secret it sends as a header on every connection, and supervises the process: it is health-checked and restarted with backoff, and stopped when the application closes. Use it when you want goose to outlive individual connections, as the goose buildpack did. Stdio stays the default because it is what every other runtime supports. Keep `host` on loopback unless you mean to expose the server.

## Log directory and MCP gateways

- `log-dir` tells SAAHC where goose writes its log, which is the only place goose reports an MCP server or extension it could not load. Defaults to `$XDG_STATE_HOME/goose/logs`, else `~/.local/state/goose/logs`. Set it only if your deployment moves goose's state.
- `mcp.answer-discover: true` answers goose's `server/discover` probe on behalf of MCP gateways that reply to it with an error goose cannot correlate. See [MCP Servers](../configuration/mcp-servers.md#goose-and-mcp-gateways).
