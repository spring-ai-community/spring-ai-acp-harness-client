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
| `GET {path}/sessions` | Every session the agent has stored, whoever opened it, or 501 when the agent cannot list them. |
| `DELETE {path}/sessions/{name}` | Closes one of the caller's named sessions. 403 if someone else holds the name. |
| `GET {path}/health` | Unauthenticated; whether the agent is up, and which runtime. |

## Sessions belong to the caller

The request's `Principal` becomes the session's owner. Another user sending the same `session` name while it is open gets a 403, on `/prompt`, `/stream` and `DELETE`. Names are shared across users, so include something specific to the user in any name two users could both choose.

On OpenCode, each process serves one user at a time, so `spring.ai.acp.harness.pool.max-processes` limits how many users can hold sessions at once; see [Processes, users and memory](../runtimes/opencode.md#processes-users-and-memory).
