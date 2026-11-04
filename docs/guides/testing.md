# Testing

## Testing your application

Switch the auto-configuration off and mock the client. No agent process, no provider key:

```java
@SpringBootTest(properties = "spring.acp.enabled=false")
class ReviewControllerTests {

    @MockitoBean
    AcpAgentClient agent;

    @Test
    void summarizes() {
        AcpAgentClient.PromptSpec spec = mock(AcpAgentClient.PromptSpec.class, RETURNS_SELF);
        AcpAgentClient.AcpAgentResponse response = mock(AcpAgentClient.AcpAgentResponse.class);
        given(agent.prompt()).willReturn(spec);
        given(agent.prompt(anyString())).willReturn(spec);   // prompt(text) is a default method
        given(spec.call()).willReturn(response);
        given(response.content()).willReturn("Looks good");
        // …
    }
}
```

For a test against a real agent, gate it on the agent being usable so CI without one stays green, and pin the model so a developer's local agent config cannot change the outcome.

## Testing a runtime adapter

`spring-ai-acp-harness-client-test` is for adapter authors. `AcpAgentRuntimeContract` is the conformance suite every compiled adapter extends; it asserts only what ACP standardizes. `ScriptedAcpAgent` is a fake agent speaking raw JSON-RPC over an in-memory transport, and `AcpAgentProbe` skips live suites when an agent is absent or has no credentials. Live suites cost real tokens, so they only run with `-Dspring-ai-acp-harness-client.test.live=true`.
