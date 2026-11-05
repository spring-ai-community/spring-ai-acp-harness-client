# Observability

With Micrometer on the classpath, every turn and every tool call is an observation: a timer, and a span parented to whatever the caller was in.

```text
spring.ai.acp.harness.turn{spring.ai.kind=acp_harness_turn, spring.ai.acp.harness.runtime=goose, gen_ai.response.model=gpt-5.6-terra, spring.ai.acp.harness.session.kind=named, spring.ai.acp.harness.outcome=END_TURN}
spring.ai.acp.harness.tool.call{spring.ai.kind=acp_harness_tool_call, spring.ai.acp.harness.runtime=goose, spring.ai.acp.harness.tool.kind=READ, spring.ai.acp.harness.tool.status=COMPLETED}
```

The names follow Spring AI's rule: an OpenTelemetry GenAI key where one means the same thing, and otherwise a key scoped under the observation's own name. So `spring.ai.kind` is the tag Spring AI puts on its chat client, advisor, tool call and vector store observations, and `gen_ai.response.model` the key its chat models tag the model with; a breakdown by kind or by model covers the harness beside them. Everything else is `spring.ai.acp.harness.*`, matching the configuration properties.

`gen_ai.response.model` is the model the session is *really* using, not the one requested, and `none` when the agent kept its own default and never said which, as Spring AI spells an absent value on that key. The outcome is the agent's stop reason, or the exception's simple name when the turn did not reach one; it stays a `spring.ai.acp.harness.outcome` tag rather than `gen_ai.response.finish_reasons`, which Spring AI records as a high-cardinality list on the span. Session names, tool titles, context usage and cost are span attributes, not meter tags; contribute an `ObservationConvention` to change that. Disable with `spring.ai.acp.harness.observations.enabled=false`.

| Observation | Low-cardinality tags (meter dimensions) | High-cardinality (span attributes) |
| --- | --- | --- |
| `spring.ai.acp.harness.turn` | `spring.ai.kind`, `spring.ai.acp.harness.runtime`, `gen_ai.response.model`, `spring.ai.acp.harness.session.kind`, `spring.ai.acp.harness.outcome` | `spring.ai.acp.harness.session.name`, `.context.used`, `.context.size`, `.cost` |
| `spring.ai.acp.harness.tool.call` | `spring.ai.kind`, `spring.ai.acp.harness.runtime`, `spring.ai.acp.harness.tool.kind`, `spring.ai.acp.harness.tool.status` | `spring.ai.acp.harness.tool.title`, `spring.ai.acp.harness.tool.id` |

The ACP specification defines no telemetry of its own. It does reserve `traceparent`, `tracestate` and `baggage` inside `_meta` for W3C trace context, which this client does not yet send.
