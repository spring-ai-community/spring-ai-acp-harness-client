# Testing

## Testing your application

Switch the auto-configuration off and mock the client. No agent process, no provider key:

```java
@SpringBootTest(properties = "spring.ai.acp.harness.enabled=false")
class ReviewControllerTests {

    @MockitoBean
    AcpHarnessClient acpHarnessClient;

    @Test
    void summarizes() {
        AcpHarnessClient.PromptSpec spec = mock(AcpHarnessClient.PromptSpec.class, RETURNS_SELF);
        AcpHarnessClient.AcpHarnessResponse response = mock(AcpHarnessClient.AcpHarnessResponse.class);
        given(acpHarnessClient.prompt()).willReturn(spec);
        given(acpHarnessClient.prompt(anyString())).willReturn(spec);   // prompt(text) is a default method
        given(spec.call()).willReturn(response);
        given(response.content()).willReturn("Looks good");
        // …
    }
}
```

For a test against a real agent, gate it on the agent being usable so CI without one stays green, and pin the model so a developer's local agent config cannot change the outcome.

## Testing a runtime adapter

`spring-ai-acp-harness-client-test` is for adapter authors. `AcpHarnessRuntimeContract` is the conformance suite every compiled adapter extends; it asserts only what ACP standardizes. `ScriptedAcpAgent` is a fake agent speaking raw JSON-RPC over an in-memory transport, and `AcpHarnessProbe` skips live suites when an agent is absent or has no credentials. Live suites cost real tokens, so they only run with `-Dspring-ai-acp-harness-client.test.live=true`.
