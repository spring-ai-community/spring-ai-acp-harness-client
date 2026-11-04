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
| `GET {path}/sessions` | Stored sessions, or 501 when the agent cannot list them. |
| `DELETE {path}/sessions/{name}` | Closes a named session. |
| `GET {path}/health` | Unauthenticated; whether the agent is up, and which runtime. |
