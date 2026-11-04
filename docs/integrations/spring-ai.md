# Spring AI ChatModel

Add `spring-ai-acp-harness-client-ai` and an `AcpChatModel` bean appears wherever a Spring AI application looks for a `ChatModel`:

```java
ChatClient chat = ChatClient.create(chatModel);

String answer = chat.prompt()
        .user("Review the pending changes")
        .options(AcpChatOptions.builder().session("review-123").mode("plan").build())
        .call()
        .content();
```

- **No session** (the default): each call is a throwaway ACP session, and the whole message list is sent. Use this with Spring AI's `ChatMemory`.
- **A named session**: the agent already holds the conversation, so only messages after the last assistant message are sent. Don't combine it with a `ChatMemory` advisor.
- `temperature`, `topP`, `maxTokens` and similar have no ACP equivalent; they are logged once and ignored. Spring AI `ToolCallback`s are not visible to the agent; give it tools as [MCP servers](../configuration/mcp-servers.md).
- Disable with `spring.acp.chat-model.enabled=false`.
