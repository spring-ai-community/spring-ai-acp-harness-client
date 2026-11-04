# Permissions and the Workspace

## Permission policy

When an agent asks permission to run a tool (`session/request_permission`), Spring AI ACP Harness Client answers on your behalf, since a server-side application has no human at the keyboard. Every request gets an answer, so no turn stalls. The exception is `ask`, for an application someone *is* sitting at.

```yaml
spring:
  acp:
    permissions:
      policy: allowlist          # deny (default) | allowlist | auto-approve | ask
      allowed-tools:
        - developer__text_editor
        - inventory__lookup_part
```

| Policy | Behaviour |
| --- | --- |
| `deny` | Rejects everything. The agent degrades to answering from context. |
| `allowlist` | Approves exactly the named tools (case-insensitive) and rejects the rest, including any request whose tool name cannot be determined. |
| `auto-approve` | Approves everything. Only in a sandbox, or when the gate that matters is elsewhere (see acp-meridian's `AGENTS.md`). |
| `ask` | Puts each request to a person through the application's `PermissionPrompt` bean. `spring-ai-acp-harness-client-console` provides one (see [the console](../integrations/console.md#answering-permission-requests)); without one, the application fails to start rather than guess. |

To find the names an agent reports, run once with `logging.level.org.springaicommunity.acp: DEBUG` and read them off the permission requests.

## Mode decides whether the question is asked

!!! warning

    `policy: deny` alone does **not** stop an agent writing files. In their default modes, Goose, Codex and OpenCode all edit files with their own tools without asking at all. The **mode** decides whether permission is requested; the **policy** decides the answer. For a read-only reviewer, set both:

    ```yaml
    spring:
      acp:
        mode: plan            # goose: approve
        permissions:
          policy: deny
    ```

    Codex's `read-only` mode still writes inside the workspace without asking; use `plan`.

## Lending the client's filesystem and terminal

ACP lets an agent borrow the *client's* file and terminal access. A server-side application rarely needs either, so both are off, and are declared unsupported to the agent:

```yaml
spring:
  acp:
    filesystem:
      enabled: false        # answer fs/read_text_file
      write: false          # also answer fs/write_text_file (implies enabled)
    terminal:
      enabled: false        # answer terminal/*: arbitrary execution as this JVM's user
      allowed-commands: []  # empty allows any; matched by file name
      output-limit: 1MB
      command-timeout: 5m
      max-concurrent: 8
```

When enabled, every path is confined to the workspace by its *real* path, with symlinks followed, so neither `..` nor a planted symlink escapes. This governs only what the agent asks *this client* to do. Its own process's file access is the operating system's to restrict: run the agent as a user, or in a container, that can reach only what it should.

## Where adapters write

Adapters write agent config files (`config.toml`, `opencode.json`) to `spring.acp.runtime-home`, never to the workspace. It defaults to a directory under the JVM's temp directory named for the runtime. The only thing Spring AI ACP Harness Client writes into the workspace is `.agents/skills/`.
