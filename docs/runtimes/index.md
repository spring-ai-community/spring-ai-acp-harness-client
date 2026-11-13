# ACP Agent Harness Runtimes

A runtime is an adapter that knows how to launch one agent and what that agent calls its options. Application code never names one; `spring.ai.acp.harness.runtime` does.

| `spring.ai.acp.harness.runtime` | Starter | Launches | Transport |
| --- | --- | --- | --- |
| `goose` | `spring-ai-acp-harness-client-boot-starter-goose` | `goose acp`, or a supervised `goose serve` | stdio, or WebSocket |
| `codex` | `spring-ai-acp-harness-client-boot-starter-codex` | `npx -y @agentclientprotocol/codex-acp@2.6.0` | stdio |
| `opencode` | `spring-ai-acp-harness-client-boot-starter-opencode` | `opencode acp` | stdio |
| any registry id, e.g. `gemini` | `spring-ai-acp-harness-client-boot-starter-registry` | whatever the [ACP registry](https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json) publishes | stdio |

## How the runtime is selected

- **One adapter on the classpath**: `spring.ai.acp.harness.runtime` may be left out.
- **Several adapters, or only the registry**: it must be set. There is no default agent; startup fails with a message listing the choices.
- **A compiled adapter always wins** over the registry for the same id, because it knows things the catalogue does not.
- **A `spring.ai.acp.harness.runtimes.<id>` block for an id nothing can supply** fails startup rather than being silently ignored.

## Swapping the runtime

There are three ways to move an application from one agent to another. None involves changing Java code.

### 1\. Replace the starter

Swap `spring-ai-acp-harness-client-boot-starter-goose` for `spring-ai-acp-harness-client-boot-starter-opencode` (say), and change or remove `spring.ai.acp.harness.runtime`. Best when a deployment only ever uses one agent: the other adapters stay off the classpath.

### 2\. Ship several adapters and choose at run time

Put every starter you want to support on the classpath and pick one with any Spring property source:

```bash
# command line
java -jar app.jar --spring.ai.acp.harness.runtime=opencode

# environment variable (relaxed binding)
SPRING_AI_ACP_HARNESS_RUNTIME=codex java -jar app.jar

# a profile that carries the runtime and everything that goes with it
java -jar app.jar --spring.profiles.active=codex
```

A profile is the right tool when the runtimes need different models or credentials, as in [the acp-meridian example](../guides/acp-meridian.md#running-it-on-each-runtime):

```yaml title="application.yaml"
spring:
  ai:
    acp:
      harness:
        runtime: goose
        model: deepseek-ai/DeepSeek-V4-Flash-0731
        provider: { id: openai, api-type: openai, base-url: ${GENAI_API_BASE}, api-key: ${GENAI_API_KEY} }
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
        provider: { id: openai, api-type: openai, base-url: "", api-key: ${OPENAI_API_KEY} }
```

### 3\. Let an operator decide with `agents.yaml`

Import a standalone file with the `acp:` prefix, and its keys are implicitly under `spring.ai.acp.harness`. It gets relaxed binding, profiles and `${…}` placeholders like any other config file, and validation errors point at its lines. The file may have any name; the prefix, not the name, is what routes it here, so a file another library reads is never taken from it:

```yaml title="application.yaml"
spring:
  config:
    import: optional:acp:agents.yaml      # or optional:acp:/etc/myapp/agent-config.yaml
```

```yaml title="agents.yaml (next to the jar)"
runtime: opencode
model: gpt-5.4-mini
permissions:
  policy: deny
runtimes:
  opencode:
    config: { share: disabled }
```

Keys that already start with `spring.` are left alone, so the file may carry an unrelated property too.

## What moves when you swap

Most configuration is portable and carries over unchanged. These are the exceptions worth checking:

| Area | Portable? | What to check |
| --- | --- | --- |
| MCP servers, skills, workspace, timeout, permissions policy | Yes | Nothing. |
| `AGENTS.md` | Goose, Codex, OpenCode | Registry agents may read another file name (Gemini CLI reads `GEMINI.md`). See [Agent Instructions](../configuration/agent-instructions.md). |
| `spring.ai.acp.harness.model` | Yes | Spelling differences (`gpt-5.4-mini` vs `openai/gpt-5.4-mini`) are matched for you, but each agent only offers the models its provider sells. |
| `spring.ai.acp.harness.mode` | Values differ | Goose says `approve` where Codex and OpenCode say `plan`. See [Mode](../configuration/models.md#mode). |
| Own endpoint (`provider.base-url`) | Mostly | Codex speaks only the OpenAI *Responses* API; an endpoint serving only `/chat/completions` works with Goose and OpenCode but not Codex. |
| `spring.ai.acp.harness.runtimes.<id>` | By design, no | Inert for every other runtime, so blocks for several runtimes can stay side by side. |
| Stored session operations | Partly | Guard with `sessions().supports(…)`. |
| MCP load-failure notices | Goose only | Other agents do not report an MCP server they failed to load. |

## Installing each agent

| Runtime | What must be on the machine | Pointing at a specific binary |
| --- | --- | --- |
| `goose` | The [goose CLI](https://block.github.io/goose/) | `GOOSE_CLI_PATH` environment variable |
| `codex` | Node.js with `npx` (codex-acp is downloaded on first start) | `CODEX_ACP_CLI_PATH`, or `spring.ai.acp.harness.runtimes.codex.command` |
| `opencode` | The [OpenCode CLI](https://opencode.ai) | `OPENCODE_CLI_PATH`, or `spring.ai.acp.harness.runtimes.opencode.command` |
| registry | Nothing; `npx` or `uvx` for agents distributed through npm or PyPI | Downloaded into `spring.ai.acp.harness.registry.cache` |

A path set with `GOOSE_CLI_PATH` or `OPENCODE_CLI_PATH` is checked at startup, so a wrong value fails with a message naming the file. Everything else an agent can be told is covered in [Runtime Configuration](configuration.md).
