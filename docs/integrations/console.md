# Terminal Console

`spring-ai-acp-harness-client-console` turns the application into a terminal chat with its agent. It is for the stage where you are designing the agent itself — its `AGENTS.md`, its skills, its MCP servers — and want to talk to it before there is any other front end. Add the dependency and run the application; there is no code to write.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-acp-harness-client-console</artifactId>
    <version>${spring-ai-acp-harness-client.version}</version>
</dependency>
```

It opens the session before the first prompt, so the model the negotiated tier actually applied is on screen before any tokens are spent, along with anything the agent reported outside the protocol, such as an MCP server it could not load. Each turn is streamed: assistant text as it arrives, a dimmed line per tool call, thought and plan update, a line for any tool call that fails, and the agent's context usage when the turn ends.

```text
Meridian Logistics — Capacity & Cost Agent
runtime: opencode   ·   /help for commands, /exit to quit
  MODEL: deepseek-ai/DeepSeek-V4-Flash-0731 → acp/deepseek-ai/DeepSeek-V4-Flash-0731 (CONFIG_OPTION)

Ask about your capacity and cost findings, or what they are costing you.

you › what are my open findings costing me?
  → getBusinessUnitSummary
  → listFindings
(the answer streams in here as the agent writes it)
```

## When it runs

The module is safe to leave on the classpath of an application that is also tested and deployed, because by default the console only starts when the process is attached to an interactive terminal. Nothing is registered otherwise: no bean, no terminal, and nothing reading stdin.

| `spring.ai.acp.harness.console.enabled` | Behaviour |
| --- | --- |
| unset (default) | Runs when stdin and stdout are a terminal: `java -jar` or `mvn spring-boot:run` from a shell. A test, a CI job or a deployed service is left alone. |
| `true` | Always runs, reading plain lines when there is no terminal. For driving it from a pipe or a script. |
| `false` | Never runs. |

The console starts on its own thread once the application is ready, so startup completes normally and a web application keeps serving while it runs. When the user leaves, the console closes the application context, which stops the agent and ends the process. In a web application that stops the server too.

## Using it

| Input | Effect |
| --- | --- |
| Any text | A turn in the current named session, so the agent remembers earlier turns. Line editing and history (kept across runs) come from JLine; a multi-line paste is one prompt. |
| Ctrl-C during a turn | Cancels the turn (the client sends `session/cancel`) and returns to the prompt with the conversation intact. |
| Ctrl-C at the prompt | Clears the line. |
| `/exit`, `/quit`, Ctrl-D | Leaves. |
| `/new` | Starts a fresh conversation in a new named session, closing the old one where the agent supports `session/close`. |
| `/sessions` | Lists the conversations the agent has stored, marking the current one. Needs `session/list`. |
| `/resume <id>` | Continues a stored conversation, with `session/resume` or else `session/load`. |
| `/notices` | Everything the agent has reported outside the protocol (see [When a server fails to load](../configuration/mcp-servers.md#when-a-server-fails-to-load)). |
| `/help` | Lists the commands. Tab completes them. |

When the agent does not implement a session operation, the command says which ACP method is missing rather than failing.

## Properties

| Property | Default | Description |
| --- | --- | --- |
| `spring.ai.acp.harness.console.enabled` | unset | See [When it runs](#when-it-runs). |
| `spring.ai.acp.harness.console.title` | `spring.application.name` | The heading shown at startup. |
| `spring.ai.acp.harness.console.greeting` | none | Shown once before the first prompt, to tell the user what to ask. |
| `spring.ai.acp.harness.console.session` | `console` | The named session turns run in. `/new` and `/resume` move to `<session>-1`, `-2`, and so on. |
| `spring.ai.acp.harness.console.history-file` | `~/.config/<spring.application.name>/console-history` | Input history, kept across runs. Honours `$XDG_CONFIG_HOME`, like the MCP sign-in file. Not written when input is piped. |
| `spring.ai.acp.harness.console.show-thoughts` | `true` | Show the reasoning the agent surfaces, one dimmed line per thought. |

## Answering permission requests

With `policy: ask`, the console puts each of the agent's permission requests to you, part-way through the turn, as a numbered choice between the options the agent offered:

```yaml
spring:
  ai:
    acp:
      harness:
        mode: plan              # so the agent asks before it acts (see "Mode decides whether the question is asked")
        permissions:
          policy: ask
```

```text
  ? The agent wants to: Run `./send-report.sh`  (execute · developer__shell)
    1) Allow once
    2) Always allow
    3) Reject
  choose 1-3 (Ctrl-C to cancel) › 3
```

Ctrl-C at that prompt cancels the request, which the agent hears as a cancellation rather than a refusal. A question still open when its turn ends, because it exceeded `timeout`, is taken down and answered the same way. This is the most direct way to see what an agent is about to do while designing it. It is also a prompt per tool call, so an agent whose job is calling tools may be better served by `allowlist` and a gate in its instructions, as [acp-meridian](../guides/acp-meridian.md) does.

!!! note

    Time spent answering counts against the turn's `spring.ai.acp.harness.timeout`. The prompt applies to every session in the application, not only the console's, so an application that also serves web users should leave `ask` to the console-only profile it runs locally.

## Customizing

- **How turns look**: register a subclass of `ConsoleRenderer` as a bean. Every method is an override point, and `render(AcpHarnessEvent)` is the one each event goes through.
- **Who answers `ask`**: register your own `PermissionPrompt` bean, and the console's backs off. `PermissionQuestion` carries the tool name, the agent's title for the call, its kind and the options.
- **The terminal**: a JLine `Terminal` bean replaces the system one.

!!! tip

    Log lines are written to the same terminal and land in the middle of the conversation. A console application usually wants `logging.level.root: WARN` and a bare pattern such as `logging.pattern.console: "%msg%n"`, as acp-meridian uses.
