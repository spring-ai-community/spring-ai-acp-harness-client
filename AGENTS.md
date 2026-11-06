# AGENTS.md

Guidance for AI coding agents (Claude Code, Goose, Codex, OpenCode, and others) working on this
repository. `CLAUDE.md` imports this file; keep project guidance here.

## What this is

`spring-ai-acp-harness-client`: a Spring Boot 4 / Java 21 library that drives any Agent Client Protocol (ACP)
coding agent (Goose, Codex, OpenCode, or any of the ~41 in the ACP registry) behind one API,
selected by `spring.ai.acp.harness.runtime`. Successor to the `java-wrapper` module of `../goose-buildpack`.
Built on the official `com.agentclientprotocol:acp-core` (pre-1.0, pinned exactly) with its
Jackson 3 JSON module, `acp-json-jackson3`; nothing here uses Jackson 2. Packaging the
agent binary is out of scope. `docs/` (MkDocs; pages in `mkdocs.yml`) is the reference; read the relevant page
before changing behaviour it documents.

## Build and test

Java 21 is required. Use the Maven wrapper:

```bash
./mvnw install                                  # build + all tests except the live-agent ones (also needed before running the sample)
./mvnw test                                     # all tests; live-agent suites are opt-in and skip by default
./mvnw test -Dspring-ai-acp-harness-client.test.live=true      # also run the live-agent suites (spends real tokens)
./mvnw -pl spring-ai-acp-harness-client test                                    # one module
./mvnw -pl spring-ai-acp-harness-client test -Dtest=ConfigResolverTests            # one class
./mvnw -pl spring-ai-acp-harness-client test -Dtest=ConfigResolverTests#someMethod # one method
./mvnw spring-javaformat:apply                  # format; the build fails on unformatted code
./mvnw -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.ai.acp.harness.runtime=codex   # goose|codex|opencode|gemini
```

- **Live-agent suites cost real tokens, so they are opt-in: `-Dspring-ai-acp-harness-client.test.live=true`**
  (`LiveAcpAgents`, honoured by `AcpHarnessProbe.isUsable`, which every live suite gates on). Without it
  nothing is probed and no agent is started. Run them when an adapter changes and before a
  release; `ScriptedAcpAgent`'s wire tests are the always-on gate against protocol regressions.
  They still skip per runtime when the agent is absent or has no credentials.
- Live contract tests pick the model via `-Dspring-ai-acp-harness-client.test.<runtimeId>.model=...`
  (otherwise `AcpHarnessProbe` chooses).
- Opt-in tests that download ~24 MB from the real registry:
  `-Dspring-ai-acp-harness-client.test.registry.live=true`.
- spring-javaformat validates every module in the `validate` phase, so run
  `./mvnw spring-javaformat:apply` after editing Java. JaCoCo writes coverage to
  `target/site/jacoco` during `verify`.
- **Nullness is enforced, not advisory.** Every package of a published module is `@NullMarked`
  (`package-info.java`) and NullAway runs as an Error Prone check on main code (`maven-compiler-plugin`
  in the root POM; `.mvn/jvm.config` opens the javac internals it needs). A possibly-null value
  passed, returned or stored where a non-null one is declared is a compile error. Tests are not
  checked. A new package needs its own `package-info.java`, or NullAway skips it silently.
  Build with an OpenJDK 21 such as Temurin, which CI uses: an Oracle JDK 21 build was seen to miss
  type-argument nullness from library class files (Reactor's `CompletableFuture<@Nullable T>`),
  so a build that passes there can still fail CI.

## Conventions

- Java uses tabs, not spaces, as spring-javaformat enforces.
- JSpecify nullness, Spring Framework 7 style: type-use `@Nullable` next to the type
  (`private @Nullable String x`, `AcpSchema.@Nullable StopReason`), never `Optional` on the API.
  Prefer a null check or a non-null default over `Objects.requireNonNull`; use the latter only where
  an invariant guarantees presence, and say which. `@SuppressWarnings("NullAway")` needs a comment
  saying why.
- Package-by-feature under `org.springaicommunity.acp.harness`: `client`, `session`, `turn`, `event`,
  `config`, `permission`, `mcp`, `workspace`, `process`, `transport`, `protocol`,
  `observation`, `executor`, `runtime`, `skill`.
- Core and application code must not name a specific agent; vendor knowledge lives only in its
  runtime adapter under `spring-ai-acp-harness-client-runtime-*`.

## Architecture

Naming: this library creates no agent; it configures an existing agent *harness* (Goose, Codex,
OpenCode, a registry agent), launches or attaches to it, and talks to it over ACP. So every type is
`AcpHarness…` (`AcpHarnessClient`, `AcpHarnessRuntime`, `AcpHarnessSession`, `AcpHarnessEvent`),
never `AcpAgent…`, which is the ACP SDK's own prefix. The exceptions are test doubles that play the
ACP agent role on the wire (`ScriptedAcpAgent`, `LiveAcpAgents`). In prose, "the agent" is the
running program, as ACP calls it.

Folder layout: `runtimes/`, `auto-configurations/`, `starters/` and `integrations/` (ai, console,
mcp-oauth) hold the modules described below; core, test support and `samples/` stay at the root.
Every nested module names its parent with `<relativePath>../../pom.xml</relativePath>`.

Module dependency shape: `AcpHarnessClient` API → `AcpHarnessClientPool` → `spring-ai-acp-harness-client` →
`acp-core` SDK → agent subprocess (stdio) or supervised `goose serve` (our own
`SupervisedWebSocketTransport`).

- **spring-ai-acp-harness-client**: `client` (fluent `AcpHarnessClient`, pool), `session` (optional session
  ops, gated by capability), `turn` + `event` (demultiplexing session updates into
  `AcpHarnessEvent`; exactly one terminal event per turn), `config` (`ConfigResolver`),
  `permission`, `mcp` (credential-holding loopback proxy), `workspace` (jail for fs/terminal),
  `process`, `protocol` (version negotiation), `observation`, `executor` (legacy
  `GooseExecutor`-shaped facade), `skill` (installs configured skills into the workspace).
- **Runtime adapters** (`spring-ai-acp-harness-client-runtime-goose|codex|opencode`) implement the
  `AcpHarnessRuntime` SPI (launch spec, provisioning, candidate option ids).
  `spring-ai-acp-harness-client-runtime-registry` implements `AcpHarnessRuntimeProvider`: launches any registry
  agent from catalogue data with SHA-256-verified downloads. A compiled adapter always wins
  over the registry for the same id.
- **spring-ai-acp-harness-client-boot-autoconfigure / starters**: `spring-ai-acp-harness-client-boot-starter` carries no
  agent; `spring-ai-acp-harness-client-boot-starter-{goose,codex,opencode,registry}` each add one.
  `spring.ai.acp.harness.runtime` has no default (unset = the only adapter on the classpath).
  `AcpProperties` binding, a standalone file imported as `acp:<path>` via a `ConfigData`
  loader (the prefix only: a bare file name is never claimed from other libraries), optional HTTP
  controller, registry/observation/ChatModel configs.
- **spring-ai-acp-harness-client-mcp-oauth**: per-user MCP-spec OAuth (`auth: oauth` on a server).
  `OAuth2McpCredentialsProvider` bridges a session's principal to Spring Security's
  `OAuth2AuthorizedClientService`; discovery/DCR/`resource=` come from mcp-security (pinned,
  Spring AI excluded). Carries its own auto-configuration, unlike `spring-ai-acp-harness-client-chatmodel`, so Spring
  Security stays out of apps that don't add it. `mode: web` redirects via Spring Security;
  `mode: local` (terminal apps) runs a loopback browser sign-in (`LoopbackSignIn`) and keeps
  state in a mode-600 file.
- **spring-ai-acp-harness-client-console**: terminal chat over the application's `AcpHarnessClient` (JLine 3), with
  its own auto-configuration like mcp-oauth so JLine stays out of other apps. Registers nothing
  unless the console will run: `spring.ai.acp.harness.console.enabled` unset means only when
  `System.console()` is a real terminal, so tests and CI never block on stdin. `ConsoleChat`
  runs on its own thread after `ApplicationReadyEvent` and closes the context when the user
  leaves; Ctrl-C during a turn disposes the subscription (= `session/cancel`). Also the
  `PermissionPrompt` behind `permissions.policy: ask`. Tests use JLine's `DumbTerminal`
  directly: `TerminalBuilder` over streams opens a pty whose output is pumped asynchronously.
- **spring-ai-acp-harness-client-chatmodel**: `AcpChatModel` adapter (a named session sends only unheard messages;
  unnamed sends full history).
- **spring-ai-acp-harness-client-test**: `AcpHarnessRuntimeContract` (the real definition of the abstraction,
  extended once per adapter), `AcpHarnessProbe` (gates suites on a real session, not `--version`),
  `ScriptedAcpAgent` (fake agent speaking raw JSON-RPC over the SDK's in-memory transport).

### Non-obvious design points

- **Three config tiers**: portable (`spring.ai.acp.harness.*`), negotiated (`model`/`mode`/`provider`,
  resolved against options the agent advertised; `on-unsupported` = fail/warn/ignore),
  runtime-specific (`spring.ai.acp.harness.runtimes.<id>.*`). The resolver reads before writing and never
  sends a value the agent didn't advertise, because e.g. goose accepts unknown model ids and
  fails later inside the turn. Adapters supply candidate option-id spellings; core matches
  values across them.
- **Except for a BYO endpoint.** With `spring.ai.acp.harness.provider.base-url` set, the agent's
  advertised model list is neither authoritative nor even stable (goose refreshes it from the
  endpoint *after* the provider is set), so the endpoint's own `/models` listing decides and an
  unadvertised model is sent anyway (`Mechanism.ENDPOINT`). `base-url` is canonicalised to end
  at the version segment; each adapter derives its vendor spelling from that. Model only — not
  mode or provider. See "Bring your own endpoint" in `docs/configuration/models.md`.
- **Don't pin the OpenAI wire API.** goose picks per model (gpt-5.6-terra → `/v1/responses`,
  deepseek → `/v1/chat/completions`) from `OPENAI_HOST` alone, so the adapter sets no
  `OPENAI_BASE_PATH`: Responses keeps reasoning items across a turn and is worth preferring
  wherever it exists. An application that must pin one dialect uses the tier-3 `env` block.
- **Not every runtime can reach every endpoint.** Verified live against a Tanzu GenAI
  (OpenAI-compatible) endpoint: goose and opencode answer; codex-acp 1.12+ speaks only the
  Responses API (with `wire_api = "chat"` its config fails to load and every `session/new`
  fails, as "Authentication required" until it is signed in), so a gateway serving only
  `/chat/completions` 404s inside its first turn. The adapter is wired correctly; the dialect
  is the constraint.
- **codex-acp ignores `OPENAI_API_KEY` until the client sends ACP `authenticate`**
  (`AcpHarnessRuntime.authMethod` → `api-key`). A developer's `~/.codex/auth.json` masks this, so
  test Codex with an empty `CODEX_HOME`. A configured key moves `CODEX_HOME` and sets
  `cli_auth_credentials_store = "ephemeral"` so the key is never written to disk.
- **acp-core gaps are worked around, not suppressed.** They are wire-format gaps invisible to
  mocks, hence `ScriptedAcpAgent` wire tests. (0.80.0 closed the ones that started this:
  `configOptions` dropped from session responses, update kinds with no record, and the provider
  id spelled `id`.) Each workaround is commented where it lives; keep it that way, since there
  is no separate list. acp-core's value types (`StopReason`, `ToolCallStatus`,
  `PermissionOptionKind`, …) are open records: compare with `equals`, switch on `value()`.
- **An MCP server the agent cannot load is reported nowhere on the wire.** `session/new`
  succeeds, no `session/update` arrives, and goose writes nothing to stdout or stderr — only to
  its own log file. Hence `AcpHarnessRuntime.logDirectory`/`noticeOf` (declarations;
  `AcpHarnessLogWatcher` does the reading), `AcpHarnessClient.notices()`, and
  `spring.ai.acp.harness.mcp.on-server-failure`. Goose-only, and only for an agent this client started.
  `tools/mcp-silence-check.py` re-measures it after a goose upgrade. See "When a server fails to
  load" in `docs/configuration/mcp-servers.md`.
- **MCP credentials never reach the agent.** An `McpCredentialsProvider` routes the HTTP
  servers it answers for through a loopback proxy (`core/mcp`), one unguessable path per
  session, asking for headers on every request so expiring tokens refresh mid-session. Routes
  are released by `SessionRegistry` whenever it forgets a session. A named session belongs to
  its `SessionPrincipal` (`SessionOwnershipException` otherwise); the resolver is read on the
  caller's thread, never on subscription. An agent's MCP interop workarounds are
  `McpRequestFilter`s its adapter returns from `mcpRequestFilters` (goose: opt-in
  `mcp.answer-discover`), never code in core. See "Credentials the agent never sees" in
  `docs/configuration/mcp-servers.md`.
- **Some agents share MCP servers across their sessions.** OpenCode keeps one MCP client per
  server name for the process and each `session/new` replaces it, so every session would call
  through the newest session's route: another user's, or a closed one's.
  `AcpHarnessRuntime.mcpScope()` = `PROCESS` makes `AcpHarnessClientPool` keep a process to one principal
  (restarting an idle one before handing it to another) and `McpAccess` give that principal one
  reference-counted route. `max-processes` is then the concurrent-user cap.
- **`permissions.policy: ask` blocks on a person.** `PermissionPolicy.decide(request, toolName)`
  is what the client calls (the 2-arg form is the rule-only default), on `boundedElastic` so
  the transport's inbound thread keeps delivering the turn. `ask` without a `PermissionPrompt`
  bean fails at startup. A turn that ends early withdraws its open questions
  (`PendingPermissions`): the agent is answered `cancelled`, as ACP requires, the prompt's
  thread is interrupted, and a late answer is discarded.
- **A cancelled or timed-out turn keeps its session until the agent answers the prompt.** ACP
  lets the agent send updates after `session/cancel` until then, and goose, Codex and OpenCode
  all do. `AcpHarnessTurn` drops them and releases the router registration and the turn permit
  only on that answer, or after `CANCEL_GRACE`. It owns the permit's release for that reason;
  callers acquire it and must not release it around the turn.
- **`permissions.policy: deny` does not stop an agent writing files.** Combine with an agent
  mode that asks first (`mode: plan`). Workspace jail confines fs/terminal requests by real
  path (symlinks followed), not just `normalize()`.
- **A plan kept in a tool is reported as the plan.** Goose sends no ACP `plan`; its `todo`
  extension's `todo__todo_write` tool call carries the whole list as markdown. `AcpHarnessRuntime.planOf`
  recognises such a call (by `_meta`, never the title) and `AcpHarnessTurn` emits `PlanUpdated` in its
  place, dropping the call's updates. Goose's parser is `TodoPlan`, pinned by a captured 1.52.0
  `tool_call`.
- Optional session operations throw `UnsupportedAcpHarnessOperationException` naming the ACP method
  rather than failing on the wire.
- ACP v2 is gated behind a flag and refused; target v1.

## Testing conventions

- Contract tests (`*ContractTests` per runtime module) assert only what ACP standardizes;
  never assert a model name, tool name or agent phrasing. Skip (don't fail) where an agent
  legitimately lacks an optional capability.
- Registry tests use fake agents (tiny shell scripts) via a `file:` registry, not real
  downloads.
- Tests that create git commits must disable signing (`-c commit.gpgsign=false`), so a
  developer's own signing setup cannot hang the suite.
- Keep the always-on suite deterministic and free of network access to real agents.

## Pull requests

- Sign every commit and keep history linear; rebase instead of adding merge commits.
- Direct changes to `main` are not accepted; submit a pull request.
- Confirm your contribution is compatible with the Apache License 2.0.
