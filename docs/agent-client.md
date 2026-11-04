# AcpAgentClient API

Every call starts with `prompt()` and describes one *turn*. Nothing is sent until `call()` or `stream()`.

## Blocking and streaming

```java
// Blocking: wait for the finished answer.
AcpAgentClient.AcpAgentResponse response = agent.prompt()
        .session("review-123")                          // a named, reusable conversation
        .user("Inspect the pending change")
        .options(o -> o.timeout(Duration.ofMinutes(10)))
        .call();

String text = response.content();                        // assistant text only
AcpSchema.StopReason why = response.completion().reason();

// Streaming: every event as it happens.
Flux<AcpAgentEvent> events = agent.prompt()
        .session("review-123")
        .user("Now focus on security")
        .stream()
        .events();

// Or just the text, e.g. for a server-sent-events endpoint.
Flux<String> text = agent.prompt("Explain the build").stream().content();
```

| Method | Meaning |
| --- | --- |
| `session(name)` | Runs in a named conversation; repeat calls with the same name keep context. Without it the turn runs in a throwaway session closed when the turn ends. Names match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. |
| `user(text)` | Appends user text; call more than once to build up a prompt. `prompt(text)` is shorthand. |
| `options(…)` | Per-turn overrides of `timeout`, `model`, `mode` and `provider`, as an `AcpAgentOptions` or a builder lambda. |
| `principal(p)` | Runs on behalf of a user: their MCP credentials, their session (see [per-user OAuth](configuration/mcp-servers.md#per-user-oauth)). |
| `call()` | Blocks and returns the finished turn. |
| `stream()` | A `Flux` ending in exactly one terminal event. Cancelling the subscription cancels the turn on the agent. |

!!! note

    Workspace, credentials and MCP servers are not per-request options. They are fixed when the agent process starts, so a per-request override would silently do nothing.

## Events

`AcpAgentEvent` is a sealed interface of records, so a `switch` over it can be exhaustive:

```java
agent.prompt().session("review-123").user("Review the diff").stream().events()
        .doOnNext(event -> {
            switch (event) {
                case AcpAgentEvent.Text t            -> ui.append(t.text());
                case AcpAgentEvent.Thought t         -> log.debug("thinking: {}", t.text());
                case AcpAgentEvent.ToolCallStarted s -> ui.toolStarted(s.id(), s.title(), s.kind());
                case AcpAgentEvent.ToolCallUpdated u -> ui.toolProgress(u.id(), u.status());
                case AcpAgentEvent.PlanUpdated p     -> ui.replacePlan(p.entries());
                case AcpAgentEvent.ConfigChanged c   -> log.info("agent reconfigured: {}", c.options());
                case AcpAgentEvent.ModeChanged m     -> log.info("mode is now {}", m.modeId());
                case AcpAgentEvent.UsageUpdated u    -> meter.context(u.contextUsed(), u.contextSize());
                case AcpAgentEvent.Completed c       -> ui.done(c.reason());
                case AcpAgentEvent.Failed f          -> ui.error(f.cause());
            }
        })
        .blockLast();
```

| Event | Meaning |
| --- | --- |
| `Text` | Assistant-visible text, as deltas. What `content()` collects. |
| `Thought` | Reasoning the agent chose to surface. Not part of the answer. |
| `ToolCallStarted` | A tool call began: id, human-readable title, kind. |
| `ToolCallUpdated` | Progress or completion of a call, keyed by the same id. |
| `PlanUpdated` | The agent's current plan. **Replaces** any earlier plan in the turn. Also sent for an agent that keeps its plan in a tool, such as goose's [`todo`](runtimes/goose.md#built-in-extensions): that tool's calls arrive as `PlanUpdated`, not as tool calls. |
| `ConfigChanged`, `ModeChanged` | The agent reconfigured itself mid-turn. |
| `UsageUpdated` | Context window used of its size, cumulative for the session, with cost when the agent reports one. |
| `Completed` *(terminal)* | The turn reached the agent's own stop condition. |
| `Failed` *(terminal)* | It did not: error, timeout, lost connection or cancellation. |

Every turn emits **exactly one** terminal event, whatever happens, so a UI can close its progress indicator in one place. `event.terminal()` tests for either.

## Named sessions

```java
agent.prompt().session("pr-482").user("What does this PR change?").call();
agent.prompt().session("pr-482").user("Any security concerns in it?").call();   // remembers the first turn
```

Idle named sessions are closed after `spring.ai.acp.harness.pool.session-ttl` (60 minutes by default). Scope names to a unit of work, such as a PR, a ticket or a chat, so context does not grow without bound. Only one turn runs in a session at a time.

`openSession(name)` opens a session without spending a turn. It is how to learn what the negotiated tier actually achieved before the first prompt:

```java
import org.springaicommunity.acp.runtime.AcpAgentRuntime.PortableOption;

SessionConfiguration config = agent.openSession("review-123").configuration();
config.applied(PortableOption.MODEL);          // Optional: the model this session is really using
config.unsupported();                          // what was asked for and could not be honoured
config.resolutions().values().forEach(r ->     // requested, applied, and by which mechanism
        log.info("{}: {} -> {} ({})", r.option(), r.requested(), r.applied(), r.mechanism()));
```

## Stored conversations

`agent.sessions()` manages the conversations the agent keeps. Every operation is optional in ACP, and the runtimes implement different subsets, so ask first:

```java
AcpAgentSessions sessions = agent.sessions();
if (sessions.supports(AcpAgentSessions.Operation.LIST)) {
    List<StoredSession> stored = sessions.list();
}
if (sessions.supports(AcpAgentSessions.Operation.LOAD)) {
    sessions.load("review-123", storedId);      // reattach and replay history under this name
}
sessions.close("review-123");                   // always succeeds, told or not
```

|  | `list` | `load` | `resume` | `delete` | `close` |
| --- | --- | --- | --- | --- | --- |
| goose 1.51 | yes | yes | no | yes | yes |
| codex-acp 1.12 | yes | yes | yes | yes | yes |
| opencode 1.18 | yes | yes | yes | no | yes |

An operation the agent does not advertise throws `UnsupportedAcpAgentOperationException` naming the ACP method, rather than failing on the wire.

## Other methods

| Method | Returns |
| --- | --- |
| `runtimeId()` | The selected runtime, e.g. `"goose"`. |
| `agentInfo()` | What the agent called itself during `initialize`. |
| `protocolVersion()` | The ACP version the connection settled on (always 1 today). |
| `notices()` | What the agent reported outside the protocol, such as an MCP server it could not load (see [When a server fails to load](configuration/mcp-servers.md#when-a-server-fails-to-load)). |
| `isAlive()` | Whether the connection is usable. Optimistic: `false` is never wrong, `true` may be. |

!!! tip

    Migrating from the `goose-buildpack` `java-wrapper`? An `AcpAgentExecutor` bean offers the old `GooseExecutor` method signatures and newline-delimited JSON events, with `AcpAgentOptions` in place of `GooseOptions`.
