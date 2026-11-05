# Agent Instructions (AGENTS.md)

`AGENTS.md` is a Markdown file of standing instructions: who the agent is serving, what the project is, its conventions, what it must never do. It is effectively the agent's system prompt. Unlike a skill, which loads on demand, `AGENTS.md` is in context on **every** turn.

Agents read it from their working directory. Spring AI ACP Harness Client does not push instructions over the protocol; it sets the working directory with `spring.ai.acp.harness.workspace`. **So installing instructions means putting `AGENTS.md` at the workspace root.** Goose, Codex and OpenCode all read it there.

```text
workspace/                      ← spring.ai.acp.harness.workspace
├── AGENTS.md                   ← always in context
└── .agents/
    └── skills/                 ← installed by Spring AI ACP Harness Client; loaded on demand
        └── release-checks/
            └── SKILL.md
```

## Writing AGENTS.md

```markdown title="workspace/AGENTS.md"
# Orders service assistant

You help the order-management team investigate failed orders. The people you talk to
run operations, not infrastructure: lead with business impact and keep jargon out.

## Tools

- `orders__getOrder`, `orders__listFailures`: read-only, call freely.
- `orders__refund`: moves money. Present the refund and wait for an explicit "yes".

## Conventions

- Cite order ids in every answer.
- Never guess a customer's details; look them up or say you cannot.
```

Keep it short and about things that apply to every turn. It is paid for on every turn and competes for attention with everything else in context. Anything that starts "when asked to…" belongs in a [skill](skills.md).

## Installing it

### Option 1: a workspace directory in your project

The simplest approach, used by [acp-meridian](../guides/acp-meridian.md): keep a `workspace/` directory in the repository with `AGENTS.md` in it, and point at it.

```yaml
spring:
  ai:
    acp:
      harness:
        workspace: ${WORKSPACE_DIR:./workspace}
```

The relative path is resolved against the JVM's working directory, so run the application from the project root, or set `WORKSPACE_DIR` to an absolute path in deployment.

### Option 2: ship it in the jar and stage it at startup

When the application runs from a jar with a workspace it does not own (a container volume, a temp directory), package `AGENTS.md` as a resource and copy it into place. The agent is launched on the first prompt, so a component that runs during context startup is always in time:

```java title="src/main/java/com/example/orders/agent/InstructionsInstaller.java"
@Component
class InstructionsInstaller {

    /** Copies classpath:agent/AGENTS.md to the workspace root before the agent is launched. */
    InstructionsInstaller(AcpHarnessSettings settings,
            @Value("classpath:agent/AGENTS.md") Resource instructions) throws IOException {
        try (InputStream in = instructions.getInputStream()) {
            Files.copy(in, settings.workspace().resolve("AGENTS.md"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
```

`AcpHarnessSettings` is the auto-configured bean carrying the resolved, absolute workspace. The same component is the natural place to render the file from a template if it must vary per deployment.

## AGENTS.md, skills or prompt?

|  | `AGENTS.md` | Skill | Prompt text |
| --- | --- | --- | --- |
| In context | Every turn | When its description matches | This turn |
| Good for | Role, audience, constraints, tool etiquette | Multi-step procedures, checklists, scripts | The task at hand |
| Cost | Every turn: keep it short | Only when loaded | Per call |
| Configured by | `<workspace>/AGENTS.md` | `spring.ai.acp.harness.skills` | `.user(…)` |

!!! warning

    **Nothing validates it.** A misspelled file name, or a workspace other than the one you think, fails silently: the agent just behaves as if it had no instructions. The `ACP runtime '…' selected, workspace …` line at `INFO` names the directory actually used.

!!! warning "Caution"

    `AGENTS.md` is an agent convention, not part of ACP. Registry agents may use another name (Gemini CLI reads `GEMINI.md`; stage the same content under both names). Some agents also merge instruction files from parent directories or the user's home (for example `~/.codex/AGENTS.md`), so a workspace inside another project's checkout may pick up that project's instructions too.
