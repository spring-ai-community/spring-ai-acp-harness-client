# Example: acp-meridian

`acp-meridian` is a complete client built on Spring AI ACP Harness Client: a terminal chat application that helps business staff investigate cloud capacity and cost findings, and files service desk tickets for them. It shows every piece of this guide working together. **Everything that makes it this agent is configuration:**

| What | Where |
| --- | --- |
| The agent runtime | `spring.ai.acp.harness.runtime: goose`, or `opencode`, or the `codex` profile |
| The model and the endpoint behind it | `spring.ai.acp.harness.model` and `spring.ai.acp.harness.provider.*` |
| Two MCP servers, with per-user OAuth | `spring.ai.acp.harness.mcp-servers`, `spring.ai.acp.harness.mcp.oauth` |
| The system prompt | `workspace/AGENTS.md` |
| A skill that sends email | `spring.ai.acp.harness.skills`, fetched from Git into `workspace/.agents/skills/` |
| The terminal UI | `spring-ai-acp-harness-client-console`, titled and greeted by `spring.ai.acp.harness.console.*` |

## Project layout

```text
acp-meridian/
├── pom.xml
├── .envrc                        # GENAI_API_BASE, GENAI_API_KEY, MAILGUN_API_KEY
├── src/main/java/org/springaicommunity/acp/meridian/
│   └── MeridianApplication.java  # main() and nothing else
├── src/main/resources/application.yaml
└── workspace/                    # spring.ai.acp.harness.workspace
    ├── AGENTS.md                 # the system prompt (checked in)
    └── .agents/skills/mailgun/   # installed at startup (git-ignored)
```

## Dependencies

All three runtime starters are on the classpath, so `spring.ai.acp.harness.runtime` must be set and is the only thing that picks one. `spring-ai-acp-harness-client-mcp-oauth` signs the user in to the MCP servers, and `spring-ai-acp-harness-client-console` is the whole user interface.

```xml title="pom.xml"
<properties>
    <java.version>21</java.version>
    <spring-ai-acp-harness-client.version>0.5.0</spring-ai-acp-harness-client.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-acp-harness-client-boot-starter-goose</artifactId>
        <version>${spring-ai-acp-harness-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-acp-harness-client-boot-starter-opencode</artifactId>
        <version>${spring-ai-acp-harness-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-acp-harness-client-boot-starter-codex</artifactId>
        <version>${spring-ai-acp-harness-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-acp-harness-client-mcp-oauth</artifactId>
        <version>${spring-ai-acp-harness-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>spring-ai-acp-harness-client-console</artifactId>
        <version>${spring-ai-acp-harness-client.version}</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

## Configuration

```yaml title="src/main/resources/application.yaml (comments abridged)"
spring:
  application:
    name: acp-meridian              # also names ~/.config/acp-meridian/ for the MCP sign-ins
  ai:
    acp:
      harness:
        runtime: goose                  # the only line that picks the agent
        workspace: ${WORKSPACE_DIR:./workspace}   # AGENTS.md lives here

        model: deepseek-ai/DeepSeek-V4-Flash-0731
        provider:                       # a platform's OpenAI-compatible endpoint
          id: openai
          api-type: openai
          base-url: ${GENAI_API_BASE}
          api-key: ${GENAI_API_KEY}
        on-unsupported: warn

        mcp-servers:                    # each user signs in; the agent never sees a token
          - name: finops-mcp
            url: https://mcp-gateway.example.com/finops-mcp/mcp
            auth: oauth
          - name: servicedesk-mcp
            url: https://mcp-gateway.example.com/servicedesk-mcp/mcp
            auth: oauth
        mcp:
          oauth:
            mode: local                 # terminal app: browser sign-in, tokens in a mode-600 file
            client-name: Meridian Capacity & Cost Agent

        skills:                         # fetched into workspace/.agents/skills before the agent starts
          - url: https://github.com/cpage-pivotal/claude-plugin-marketplace
            path: plugins/mailgun/skills/mailgun

        runtimes:                       # read only by the runtime it names
          goose:
            mcp:
              answer-discover: true     # this gateway breaks goose's discovery probe

        permissions:
          policy: auto-approve          # the real gate is in AGENTS.md: confirm before createTicket
        console:                        # spring-ai-acp-harness-client-console
          title: Meridian Logistics — Capacity & Cost Agent
          greeting: Ask about your capacity and cost findings, or what they are costing you.
          session: meridian-console
        filesystem:
          enabled: false
        terminal:
          enabled: false
        timeout: 5m
        pool:
          max-processes: 1
          max-sessions-per-process: 4
          session-ttl: 60m

  main:
    web-application-type: none
```

## Instructions

The system prompt is `workspace/AGENTS.md`, checked in next to the code. It defines the audience, lists the MCP tools and which of them need the user's explicit confirmation, and lays out the workflow. An excerpt:

```markdown title="workspace/AGENTS.md (excerpt)"
# Meridian Logistics Capacity & Cost Agent

You help **business unit staff at Meridian Logistics** understand capacity and cost
issues flagged against their workloads on the shared private cloud, and file service
desk requests to get them fixed.

## Available Tools

**FinOps Insights**: read-only, no side effects, call freely:
- `getBusinessUnitSummary()`: the caller's business unit: budget, spend, forecast
- `listFindings(severity?, status?)`: flagged capacity/cost issues

**Service Desk**:

*Requires explicit human confirmation:*
- `createTicket(requestType, fields)`: files a real request that real people will act on
```

## The code

One class, and it names neither an agent nor a UI:

```java title="MeridianApplication.java"
@SpringBootApplication
public class MeridianApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeridianApplication.class, args);
    }
}
```

The read-prompt-stream loop, the rendering of tool calls and plans, Ctrl-C cancellation and the session commands all come from [spring-ai-acp-harness-client-console](../integrations/console.md). Before that module existed, this application carried two classes of its own for it, which is what prompted the module.

## Running it on each runtime

```bash
set -a && source .envrc && set +a
mvn package

java -jar target/acp-meridian-0.1.0-SNAPSHOT.jar                              # goose
java -jar target/acp-meridian-0.1.0-SNAPSHOT.jar --spring.ai.acp.harness.runtime=opencode   # same endpoint, same result
java -jar target/acp-meridian-0.1.0-SNAPSHOT.jar --spring.profiles.active=codex  # OpenAI itself
```

Goose and OpenCode reach the platform's Chat Completions endpoint unchanged. Codex speaks only the Responses API, which that endpoint does not serve, so a `codex` profile swaps model and provider for OpenAI:

```yaml title="application.yaml (continued)"
---
spring:
  config:
    activate:
      on-profile: codex
  ai:
    acp:
      harness:
        runtime: codex
        model: gpt-5.6-terra
        provider:
          id: openai
          api-type: openai
          base-url: ""                  # clear the platform endpoint
          api-key: ${OPENAI_API_KEY}    # reaches Codex via ACP authenticate; never written to disk
```

MCP servers, sign-in, skill and `AGENTS.md` are identical on all three.
