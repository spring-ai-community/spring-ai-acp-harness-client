# HTTP Endpoint

An optional WebFlux controller exposes the agent over HTTP. It requires `spring-boot-starter-webflux` *and* `spring.ai.acp.harness.controller.enabled=true`, so adding either alone publishes nothing.

```yaml
spring:
  ai:
    acp:
      harness:
        controller:
          enabled: true
          path: /api/acp
          allow-unauthenticated: false    # requests need a Principal
          allow-request-overrides: false  # a request may not choose its own model or provider
          max-prompt-chars: 32000
          max-timeout: 10m
```

| Endpoint | Behaviour |
| --- | --- |
| `POST {path}/prompt` | Body `{"prompt", "session", "timeout", "model", "provider", "mode"}`; returns `{"content", "stopReason", "error", "requestId"}`. |
| `POST {path}/stream` | Same body; server-sent events of assistant text only (tool calls and plans can name workspace paths). |
| `GET {path}/sessions` | The caller's open sessions, sorted by name: `[{"name", "sessionId", "title", "updatedAt"}]`. `name` is the name the caller gave, which continues or closes the session. `title` and `updatedAt` come from the agent and are null when it does not implement `session/list`. |
| `DELETE {path}/sessions/{name}` | Closes one of the caller's named sessions. Returns 204 whether or not the name was in use. |
| `GET {path}/health` | Unauthenticated; whether the agent is up, and which runtime. |

## Sessions belong to the caller

The `session` name in a request is scoped to whoever sent it, so two users choosing the same name get separate conversations, and neither can see, continue or close the other's. Without a `Principal`, as with `allow-unauthenticated`, all callers share one scope.

Names follow the usual session name rules, up to 109 characters; anything else is a 400. The scope means the endpoint cannot reach a session your application opened itself through `AcpHarnessClient` under the same name. `GET {path}/sessions` lists only sessions open in this process, so it does not include stored sessions from earlier runs. It works whether or not the agent implements `session/list`, and with nothing open it answers without starting an agent.

A session runs one turn at a time. A request for a session that is still running an earlier turn gets a 409 at once, rather than waiting for it: waiting would hold a thread on the scheduler that also answers every running agent's permission and workspace requests, so one caller sending overlapping requests could stall everyone else's. Wait for the earlier turn's response, then send the next.

## When a turn does not finish

| Response | Meaning |
| --- | --- |
| 502 | The turn failed or timed out. The response never repeats what the agent said; on `/prompt`, its `requestId` finds the details in the logs. |
| 409 | The named session is still running an earlier turn. Send the next turn once that one has answered. |
| 503 | The agent is fine but cannot take another session right now. Try again later. See `spring.ai.acp.harness.pool`. |
| 200, then an aborted connection | `/stream` only: the turn failed after text was already sent. A stream that ends normally finished its turn. |

On OpenCode, each process serves one user at a time, so `spring.ai.acp.harness.pool.max-processes` limits how many users can hold sessions at once, and a user beyond that gets a 503; see [Processes, users and memory](../runtimes/opencode.md#processes-users-and-memory).
