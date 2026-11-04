# OpenCode

The adapter launches `opencode acp` over stdio. OpenCode advertises exactly two options, `model` and `mode`, so nearly all of its behaviour is configured through its own `opencode.json`.

```yaml title="application.yaml: every OpenCode key"
spring:
  ai:
    acp:
      harness:
        runtimes:
          opencode:
            command: /opt/opencode/bin/opencode   # default: OPENCODE_CLI_PATH, else "opencode" on the PATH
            args: [ --print-logs ]
            config:                               # written as opencode.json, pointed at by OPENCODE_CONFIG
              share: disabled
              autoupdate: false
            config-file: /etc/myapp/opencode.json # where to write it, instead of <runtime-home>/opencode.json
            env:
              HTTPS_PROXY: http://proxy.internal:3128
```

## opencode.json

`config` is written as JSON with OpenCode's own keys; see the [OpenCode config reference](https://opencode.ai/docs/config/). OpenCode *merges* configuration files rather than replacing them, in this order (later wins, key by key):

1. the global `~/.config/opencode/opencode.json`;
2. the file the adapter writes (`OPENCODE_CONFIG`);
3. a project `opencode.json` in the workspace.

Measured against opencode 1.18.31: a `model` in the workspace's `opencode.json` overrode the one in the adapter's file, while the adapter's other keys still applied. Unlike Codex, relocating the config file does not affect OpenCode's login, which lives elsewhere.

## Per-tool permissions

OpenCode's `permission` block sets, per tool, whether it may act (`allow`), must not (`deny`), or must ask (`ask`). An `ask` reaches your application as an ACP permission request and is answered by [`spring.ai.acp.harness.permissions`](../configuration/permissions.md). This gives you a review gate in the default `build` mode, finer than switching to `plan`:

```yaml
spring:
  ai:
    acp:
      harness:
        runtimes:
          opencode:
            config:
              permission:
                edit: ask                  # every file change asks
                bash:
                  "*": ask                 # shell commands ask…
                  "git status": allow      # …except these
                  "git diff*": allow
                webfetch: deny
                external_directory: deny   # nothing outside the workspace
        permissions:
          policy: deny                     # every ask is refused: the agent reads, and runs only git status/diff
```

Tools include `read`, `edit`, `glob`, `grep`, `list`, `bash`, `task`, `webfetch`, `websearch`, `skill` and `external_directory`. A single value (`permission: ask`) applies to all of them.

## Modes and custom agents

OpenCode's modes are its *primary agents*: `build` (the default) and `plan`. Define your own in `config.agent` and it becomes a mode that `spring.ai.acp.harness.mode` can select. Verified against 1.18.31, where a custom `review` agent appeared as a mode beside `build` and `plan` and could be set:

```yaml
spring:
  ai:
    acp:
      harness:
        runtime: opencode
        mode: review                        # select the agent defined below
        runtimes:
          opencode:
            config:
              agent:
                review:
                  mode: primary             # primary agents are ACP modes
                  description: Reviews changes without editing them
                  prompt: You review code for correctness and security. Never edit files.
                  permission:
                    edit: deny
                    bash: ask
```

`default_agent` in `config` sets the mode new sessions start in. Prefer `spring.ai.acp.harness.mode`, which reports whether it was applied.

## Processes, users and memory

OpenCode keeps MCP clients per process rather than per session. It holds one client per server name, and every `session/new` replaces it, so all the sessions in a process call MCP through whichever session declared the servers last (see [Credentials the agent never sees](../configuration/mcp-servers.md#credentials-the-agent-never-sees)). To keep one user's tools from running with another user's credentials, the pool gives each OpenCode process to **one principal at a time**:

- A user's sessions all share one process, up to `spring.ai.acp.harness.pool.max-sessions-per-process`.
- A user who needs a process gets an idle one. An idle process that last served someone else is restarted first, which takes a second or two and does not count against `max-restarts`.
- When every process is serving other users, a new user's session is refused with an error that names `spring.ai.acp.harness.pool.max-processes`. A process becomes free when its user's last session closes, either explicitly or after `spring.ai.acp.harness.pool.session-ttl` of inactivity.

So `max-processes` is the number of users who can hold OpenCode sessions at the same time, and each of them costs a whole OpenCode process. Processes start lazily, so memory grows with concurrent users rather than being allocated at startup. The goose and Codex adapters keep MCP servers per session, so for them `max-processes` is only about parallelism.

Measured resident memory of one `opencode acp` process:

| Point | Linux x86_64, 1.18.33 | macOS arm64, 1.18.32 |
| --- | --- | --- |
| After `initialize` | 490 MB | 420 MB |
| After the first `session/new` | 430 MB | 550 MB |
| After a first short turn | not measured | 660 MB |
| Each further session in the same process | not measured | about 1 MB |

Budget about **650 MB per OpenCode process**, plus your application's own JVM. Size the host or container for the worst case:

```text
memory ≥ JVM + max-processes × 650 MB
```

```yaml title="application.yaml: up to three concurrent users"
spring:
  ai:
    acp:
      harness:
        runtime: opencode
        pool:
          max-processes: 3             # 3 users, ~2 GB of OpenCode
          max-sessions-per-process: 8  # chats per user
          session-ttl: 30m             # free idle users sooner
```

!!! note

    The agent processes are children of the JVM and share its memory limit. On Cloud Foundry, the Java buildpack's memory calculator gives the JVM nearly the whole container by default, so leave room for OpenCode with `memory_calculator: { headroom: … }` in `JBP_CONFIG_OPEN_JDK_JRE` (a percentage of the container), or cap the heap with `-Xmx` in `JAVA_OPTS`. Otherwise the container can be killed for exceeding its limit while the JVM still believes it has headroom.

## Other useful settings

| Key under `config` | Effect |
| --- | --- |
| `share: disabled` | Never publish sessions to OpenCode's share service. Recommended for servers. |
| `autoupdate: false` | Do not update the binary at run time; pin the version in your image instead. |
| `small_model` | A cheaper `provider/model` for side tasks such as session titles. |
| `instructions` | Extra instruction files or globs, loaded as well as `AGENTS.md`. |
| `enabled_providers`, `disabled_providers` | Restrict which of OpenCode's built-in providers can be used. |
| `snapshot: false` | Stop recording filesystem snapshots for undo, which saves disk in large workspaces. |

!!! note

    With a bring-your-own endpoint, the adapter writes `provider`, `model` and `disabled_providers` into the same file. A top-level key of the same name in `config` replaces the adapter's value outright, so leave those three to `spring.ai.acp.harness.provider` unless you intend to describe the providers yourself.
