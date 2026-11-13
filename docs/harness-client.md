# AcpHarnessClient API

Every call starts with `prompt()` and describes one *turn*. Nothing is sent until `call()` or `stream()`.

The API is `@NullMarked` ([JSpecify](https://jspecify.dev)): a reference is non-null unless it is annotated `@Nullable`, and the build checks that, so where a method says `@Nullable` the null is real and where it does not you need no check. An IDE that reads JSpecify annotations shows the same.

## Blocking and streaming

```java
// Blocking: wait for the finished answer.
AcpHarnessClient.AcpHarnessResponse response = acpHarnessClient.prompt()
        .session("review-123")                          // a named, reusable conversation
        .user("Inspect the pending change")
        .options(o -> o.timeout(Duration.ofMinutes(10)))
        .call();

String text = response.content();                        // assistant text only
AcpSchema.StopReason why = response.completion().reason();

// Streaming: every event as it happens.
Flux<AcpHarnessEvent> events = acpHarnessClient.prompt()
        .session("review-123")
        .user("Now focus on security")
        .stream()
        .events();

// Or just the text, e.g. for a server-sent-events endpoint.
Flux<String> text = acpHarnessClient.prompt("Explain the build").stream().content();
```

| Method | Meaning |
| --- | --- |
| `session(name)` | Runs in a named conversation; repeat calls with the same name keep context. Without it the turn runs in a throwaway session closed when the turn ends. Names match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. |
| `user(text)` | Appends user text; call more than once to build up a prompt. `prompt(text)` is shorthand. |
| `options(…)` | Per-turn overrides of `timeout`, `model`, `mode` and `provider`, as an `AcpHarnessOptions` or a builder lambda. |
| `principal(p)` | Runs on behalf of a user: their MCP credentials, their session (see [per-user OAuth](configuration/mcp-servers.md#per-user-oauth)). |
| `call()` | Blocks and returns the finished turn. |
| `stream()` | A `Flux` ending in exactly one terminal event. Cancelling the subscription cancels the turn on the agent. |

A turn that ends early, because its subscription was cancelled or it exceeded `timeout`, is over for the caller at once. The agent is sent `session/cancel`, and the session stays busy until the agent answers the cancelled prompt. Goose, Codex and OpenCode answer within a few milliseconds; an agent that never answers is given 5 seconds. What the agent sends in the meantime is dropped rather than delivered to the next turn, and any permission request still open is answered `cancelled`. A turn started on the same session straight away waits for this, as it would for any turn still running.

!!! note

    Workspace, credentials and MCP servers are not per-request options. They are fixed when the agent process starts, so a per-request override would silently do nothing.

## Events

`AcpHarnessEvent` is a sealed interface of records, so a `switch` over it can be exhaustive:

```java
acpHarnessClient.prompt().session("review-123").user("Review the diff").stream().events()
        .doOnNext(event -> {
            switch (event) {
                case AcpHarnessEvent.Text t            -> ui.append(t.text());
                case AcpHarnessEvent.Thought t         -> log.debug("thinking: {}", t.text());
                case AcpHarnessEvent.ToolCallStarted s -> ui.toolStarted(s.id(), s.title(), s.kind());
                case AcpHarnessEvent.ToolCallUpdated u -> ui.toolProgress(u.id(), u.status());
                case AcpHarnessEvent.PlanUpdated p     -> ui.replacePlan(p.entries());
                case AcpHarnessEvent.ConfigChanged c   -> log.info("agent reconfigured: {}", c.options());
                case AcpHarnessEvent.ModeChanged m     -> log.info("mode is now {}", m.modeId());
                case AcpHarnessEvent.UsageUpdated u    -> meter.context(u.contextUsed(), u.contextSize());
                case AcpHarnessEvent.Completed c       -> ui.done(c.reason());
                case AcpHarnessEvent.Failed f          -> ui.error(f.cause());
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
acpHarnessClient.prompt().session("pr-482").user("What does this PR change?").call();
acpHarnessClient.prompt().session("pr-482").user("Any security concerns in it?").call();   // remembers the first turn
```

Idle named sessions are closed after `spring.ai.acp.harness.pool.session-ttl` (60 minutes by default). Scope names to a unit of work, such as a PR, a ticket or a chat, so context does not grow without bound. Only one turn runs in a session at a time: a second waits for the first, for up to its own timeout, holding the thread that started it. A server answering many users should not hold threads that long, so it passes `.waitIfBusy(Duration.ZERO)` and gets an `AcpHarnessSessionBusyException` at once instead.

`openSession(name)` opens a session without spending a turn. It is how to learn what the negotiated tier actually achieved before the first prompt:

```java
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime.PortableOption;

SessionConfiguration config = acpHarnessClient.openSession("review-123").configuration();
config.applied(PortableOption.MODEL);          // the model this session is really using, or null
config.unsupported();                          // what was asked for and could not be honoured
config.resolutions().values().forEach(r ->     // requested, applied, and by which mechanism
        log.info("{}: {} -> {} ({})", r.option(), r.requested(), r.applied(), r.mechanism()));
```

## Between turns

An agent may speak in a session when no turn is running. ACP allows it, and an agent that does background work does it: OpenCode 2.3.0 answers "I'll report when it finishes" while a subagent is still working, then writes the subagent's result into the session on its own once it arrives. A turn cannot carry that, because it has already ended. `unprompted(name)` does:

```java
Disposable listening = acpHarnessClient.unprompted("pr-482")
        .ofType(AcpHarnessEvent.Text.class)
        .subscribe(text -> chat.append(text.text()));
```

- The flux is hot and best-effort. It carries only what arrives while you are subscribed and no turn is running in that session. A turn's own updates still go to the turn, never here.
- It carries the same events a turn does (text, thoughts, tool calls, plan updates), mapped by the same runtime rules, so a subagent's words are left out here too. It never carries a terminal event: the agent sends no end signal and no usage for what it says unprompted, so there is nothing to say when it is finished. Wait for a pause instead, as the [console](integrations/console.md) does.
- It completes when the session closes, however it closes: `sessions().close(...)`, `session-ttl`, or the pool restarting the agent.
- The session must be open (`openSession`, or a turn run in it) and must belong to the caller, as for a turn. Otherwise `unprompted` throws `IllegalStateException` or `SessionOwnershipException`.

Most agents never speak between turns, and then the flux is simply quiet. Nothing is kept for a session nobody is listening to.

## Stored conversations

`acpHarnessClient.sessions()` manages the conversations the agent keeps. Every operation is optional in ACP, and the runtimes implement different subsets, so ask first:

```java
AcpHarnessSessions sessions = acpHarnessClient.sessions();
if (sessions.supports(AcpHarnessSessions.Operation.LIST)) {
    List<StoredSession> stored = sessions.list();
}
if (sessions.supports(AcpHarnessSessions.Operation.LOAD)) {
    sessions.load("review-123", storedId);      // reattach and replay history under this name
}
sessions.close("review-123");                   // always succeeds, told or not
```

|  | `list` | `load` | `resume` | `delete` | `close` |
| --- | --- | --- | --- | --- | --- |
| goose 1.59.0 | yes | yes | no | yes | yes |
| codex-acp 2.5.0 | yes | yes | yes | yes | yes |
| opencode 2.2.7 | yes | yes | yes | yes | yes |

An operation the agent does not advertise throws `UnsupportedAcpHarnessOperationException` naming the ACP method, rather than failing on the wire.

## Other methods

| Method | Returns |
| --- | --- |
| `runtimeId()` | The selected runtime, e.g. `"goose"`. |
| `harnessInfo()` | What the agent called itself during `initialize`. |
| `protocolVersion()` | The ACP version the connection settled on (always 1 today). |
| `notices()` | What the agent reported outside the protocol, such as an MCP server it could not load (see [When a server fails to load](configuration/mcp-servers.md#when-a-server-fails-to-load)). |
| `isAlive()` | Whether the connection is usable. Optimistic: `false` is never wrong, `true` may be. |

!!! tip

    Migrating from the `goose-buildpack` `java-wrapper`? An `AcpHarnessExecutor` bean offers the old `GooseExecutor` method signatures and newline-delimited JSON events, with `AcpHarnessOptions` in place of `GooseOptions`.
