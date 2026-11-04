# Observability

With Micrometer on the classpath, every turn and every tool call is an observation: a timer, and a span parented to whatever the caller was in.

```text
acp.turn{acp.runtime=goose, acp.model=gpt-5.6-terra, acp.session.kind=named, acp.outcome=END_TURN}
acp.tool.call{acp.runtime=goose, acp.tool.kind=READ, acp.tool.status=COMPLETED}
```

`acp.model` is the model the session is *really* using, not the one requested. Session names, tool titles and usage are span attributes, not meter tags; contribute an `ObservationConvention` to change that. Disable with `spring.acp.observations.enabled=false`.
