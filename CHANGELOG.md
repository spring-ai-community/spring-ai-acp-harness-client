# Changelog

All notable changes to Spring AI ACP Harness Client are documented here. The project follows
[Semantic Versioning](https://semver.org/) (MAJOR.MINOR.PATCH).

## [0.7.0] - Unreleased

### Fixed

- A turn that exceeded `timeout` ended with `Failed` but never sent `session/cancel`, so the agent
  kept working on the prompt, spending tokens on a result nobody received. A timed-out turn now
  sends `session/cancel`, as a cancelled stream already did. The turn still ends without waiting
  for the agent to stop, so a prompt sent straight afterwards on the same session can overlap the
  cancelled one ([#20](https://github.com/spring-ai-community/spring-ai-acp-harness-client/issues/20)).

## [0.6.0] - 2026-11-06

### Added

- `PromptSpec.waitIfBusy(Duration)` sets how long a turn waits for a named session that is still
  running an earlier turn, before failing with the new `AcpHarnessSessionBusyException`.
  `Duration.ZERO` fails at once; the default is still the turn's timeout. A session is now free
  again before its caller hears that a turn ended, so a caller that goes straight on to the next
  turn never finds it busy.

### Changed

- **Breaking:** Java packages move from `org.springaicommunity.acp.*` to
  `org.springaicommunity.acp.harness.*`, to match the artifacts and the properties. Update your
  imports, and any `logging.level.org.springaicommunity.acp` setting, which otherwise stops matching
  without an error. The JDBC schema for MCP OAuth moves with them, to
  `org/springaicommunity/acp/harness/mcp/oauth/acp-mcp-oauth-schema.sql`.
- **Breaking:** every `AcpAgent…` type becomes `AcpHarness…`, starting with `AcpAgentClient`,
  which becomes `AcpHarnessClient` (with `DefaultAcpHarnessClient`, `AcpHarnessClientPool`,
  `AcpHarnessClientFactory` and `AcpHarnessClientException`, and the bean `acpAgentClient`
  becomes `acpHarnessClient`). This library creates no agent: it configures an existing agent
  harness such as Goose, Codex or OpenCode, launches or attaches to it, and talks to it over ACP,
  so the types describe the harness and now say so, as the project, its artifacts, its packages
  and its `spring.ai.acp.harness.*` properties already did. ACP itself calls the far end of the
  connection the agent, and the documentation keeps that word for the running program. Hence
  `AcpHarnessRuntime`, `AcpHarnessRuntimeProvider`, `AcpHarnessLaunchSpec`, `AcpHarnessEnvironment`,
  `AcpHarnessNotice`, `AcpHarnessSession`, `AcpHarnessSessions`, `AcpHarnessTurn`,
  `AcpHarnessEvent`, `AcpHarnessEventMapper`, `AcpHarnessInfo`, `AcpHarnessSettings`,
  `AcpHarnessOptions`, `AcpHarnessObservations`, `AcpHarnessTurnObservationContext`,
  `AcpHarnessExecutor`, `AcpHarnessLogWatcher`, `AcpHarnessProcessSupervisor`,
  `UnsupportedAcpHarnessOperationException`, `UnsupportedAcpHarnessOptionException`, the nested
  `AcpHarnessClient.AcpHarnessResponse` and `AcpHarnessStream`, the registry runtime's
  `AcpHarnessRegistry`, `AcpHarnessInstaller` and `AcpHarnessInstallException`, the Boot
  `AcpHarnessConfigDataLoader`, `AcpHarnessConfigDataLocationResolver` and
  `AcpHarnessConfigDataResource` (formerly `AcpAgentsConfigData…`, with an s), and the test kit's
  `AcpHarnessRuntimeContract` and `AcpHarnessProbe`. `AcpHarnessClient.agentInfo()` becomes
  `harnessInfo()`, on the pool too. The beans `acpAgentSettings`, `acpAgentRegistry`,
  `acpAgentInstaller`, `acpAgentObservations` and `acpAgentExecutor` follow as `acpHarness…`, and
  the adapter beans an application overrides by name are now `gooseHarnessRuntime`,
  `codexHarnessRuntime`, `openCodeHarnessRuntime` and `registryHarnessRuntimeProvider` instead of
  `…AgentRuntime…`. The rule is mechanical, so replacing each `AcpAgent` or `AcpAgents` type-name
  prefix with `AcpHarness` migrates an application, and the compiler finds every site; a bean
  overridden by its old name is the one thing it cannot catch, so check those four by hand.
  Unchanged: packages, properties, metric names, and the test doubles
  that play the ACP agent role on the wire, `ScriptedAcpAgent` and `LiveAcpAgents`, since those
  are agents in the protocol's sense.
- **Breaking:** the observations and their tags are named the way Spring AI names its own, so a
  dashboard or alert on the old names must be updated. The two observations are
  `spring.ai.acp.harness.turn` and `spring.ai.acp.harness.tool.call` (were `acp.turn` and
  `acp.tool.call`); the model tag is the OpenTelemetry `gen_ai.response.model` (was `acp.model`), the
  key Spring AI's chat models carry, so one breakdown by model covers both; every other key keeps
  its suffix under `spring.ai.acp.harness.` instead of `acp.` (`.runtime`, `.session.kind`,
  `.outcome`, `.session.name`, `.context.used`, `.context.size`, `.cost`, `.tool.kind`,
  `.tool.status`, `.tool.title`, `.tool.id`); and both observations gain Spring AI's `spring.ai.kind`
  tag, `acp_harness_turn` or `acp_harness_tool_call`. The outcome deliberately stays a single-valued
  tag of our own rather than `gen_ai.response.finish_reasons`, which Spring AI records as a
  high-cardinality list on the span. The bare `acp.` root was no library's to claim: ACP itself
  defines no telemetry names, and any other ACP library could have used the same ones. The
  `AcpChatModel` response metadata keys follow, and now spell the same thing the same way as the
  turn span: `acp.runtime`, `acp.session`, `acp.context.used` and `acp.context.size` become
  `spring.ai.acp.harness.runtime`, `.session.name`, `.context.used` and `.context.size`;
  `acp.cost.amount` and `acp.cost.currency` keep their typed split as `.cost.amount` and
  `.cost.currency`. An absent model is tagged `none`, Spring AI's spelling on that key, where it was
  `unknown`. For a custom `ObservationConvention`, the key constants are unchanged in meaning: the
  new `spring.ai.kind` constant is `SPRING_AI_KIND` on both low-cardinality enums, and
  `ToolCallLowCardinalityKeys.KIND` is still the ACP tool kind.
- **Breaking:** configuration properties move from `spring.acp.*` to `spring.ai.acp.harness.*`,
  and environment variables from `SPRING_ACP_*` to `SPRING_AI_ACP_HARNESS_*`. The old root is shared
  with `acp-autoconfig` (`spring.acp.client.*`, `spring.acp.agent.*`), and the new one follows the
  `spring.ai.*` convention of the Spring AI Community projects. Update your configuration: the old
  keys are no longer read, and nothing reports them, so a `spring.acp.*.enabled=false` stops taking
  effect. The `acp:` config import prefix, metric names and Java packages are unchanged.
- **Breaking:** the API no longer returns or accepts `java.util.Optional`. A value that may be
  absent is now a plain reference annotated with JSpecify's `@Nullable`, as Spring Framework 7 and
  Spring AI do, so `client.session(name)`, `client.harnessInfo()`, `sessions().find(name)`,
  `SessionConfiguration.applied(option)`, `AcpHarnessSession.principal()`, `RuntimeOptions.text(path)`
  and the `find*` accessors of `ProviderSpec` and `AcpHarnessSettings` return null for "none". The
  SPI follows: `AcpHarnessRuntime.authMethod`, `toolNameOf`, `planOf`, `logDirectory` and `noticeOf`,
  `AcpHarnessRuntimeProvider.forId`, `McpCredentialsProvider.credentialsFor`,
  `McpCredentials.upstream`, `ModelCatalog.modelsOf`, `SessionPrincipalResolver.current`,
  `PermissionPrompt.ask` and both `PermissionPolicy.decide` methods return null instead of empty,
  and `decide` and `PermissionQuestion` take a nullable tool name. `AcpHarnessOptions` drops its
  `find*` methods, since its record accessors already say the same thing; `StoredSession` likewise
  drops `findCwd`, `findTitle` and `findUpdatedAt`, and `ProviderSpec` drops `findBaseUrl`.
  `AcpHarnessEvent.UsageUpdated.contextFraction()` returns a `Double` or null instead of an
  `OptionalDouble`. Replace `.isPresent()` with a null check and `.orElse(x)` with a conditional
  to migrate; the compiler finds every call site, since the types changed.
- Every package of a published module is `@NullMarked`, so a reference is non-null unless it says
  `@Nullable`, and the build enforces that with NullAway: main code that passes, returns or stores a
  possibly-null value where a non-null one is declared no longer compiles. Nothing changes for a
  caller, except that a `@Nullable` on this API is now something the compiler checked rather than
  a comment. A few signatures gained a `@Nullable` the behaviour already implied, such as
  `AcpHarnessExecutor.getVersion()`, `SkillFetcher.Fetched.revision()`, `McpServerSpec.Http.url()`,
  `AcpHarnessLaunchSpec.WebSocket.process()` and the optional components of `SkillSpec.Git` and
  `AcpController`'s request and response records; `SecretRedactor.redact` no longer accepts null.
  A few behaviours changed where a null used to flow on: `SessionConfiguration.of(option)` returns
  a `NOT_REQUESTED` resolution instead of null for an option the configuration never mentioned; a
  registry entry with no id, package, archive or command is skipped when the catalogue is parsed
  instead of failing at launch, and a registry agent with no distribution fails at launch with a
  message; a stored MCP OAuth client registration missing a required field fails with a message
  naming it; and an `auth: oauth` server declared without a `url` is refused when the credentials
  provider is built rather than when the first user's session opens. For a contributor, `.mvn/jvm.config` now opens the javac internals Error Prone needs, and
  `spring-boot-configuration-processor` is named on the compiler's processor path.
- **Breaking:** the core library is now the artifact `spring-ai-acp-harness-client`, dropping the
  `-core` suffix, the Spring AI `ChatModel` adapter is `spring-ai-acp-harness-client-chatmodel`
  instead of `-ai`, and the parent POM is published as `spring-ai-acp-harness-client-parent`, so each
  name says what it is. Applications use a starter and rarely name the core artifact; one that names
  either changes the artifact id. Java packages are unchanged. The modules are also grouped into `runtimes/`,
  `auto-configurations/`, `starters/` and `integrations/` folders in the repository, which
  changes no artifact id.
- Releases are published to Maven Central, and snapshots to the Maven Central snapshot repository.
  Versions up to 0.5.0 remain in the Artifact Registry repository.
- The reference documentation moved from a single `docs/user-guide.html` to a versioned MkDocs
  site, published to GitHub Pages from `docs/`.
- **Breaking:** `AcpController`'s handlers return `Mono` or `Flux`, all but `health`, so that no
  request blocks a Netty event-loop thread. Requests over HTTP are unaffected; an application that
  calls the controller's methods directly subscribes to what they return.
- **Breaking:** `GET {path}/sessions` on the HTTP endpoint returns
  `[{"name", "sessionId", "title", "updatedAt"}]`, naming each of the caller's open sessions the
  way the caller did, so it can be continued or closed, instead of the agent's stored sessions. It
  no longer needs the agent to implement `session/list` (no more 501), asks the agent nothing when
  the caller has nothing open, and lists every open session, including one the agent has not saved
  yet or that runs in another process of the pool.
- The HTTP endpoint answers a request for a named session that is still running an earlier turn
  with a 409 at once, instead of waiting for it for up to the request's timeout and then returning
  a 502. Waiting held a thread on the scheduler that also answers agents' permission and workspace
  requests, so one caller sending overlapping turns to one session could stall every other user's
  agent.

### Fixed

- A new session that failed while being configured, such as an unsupported model with
  `on-unsupported: fail`, stayed open on the agent until `session-ttl`, with its MCP route still
  live. In a pool these sessions used up capacity, so later turns were refused with a "holding its
  maximum" error instead of reporting the real failure. A session that was already open is kept, so
  a bad per-turn override does not end a conversation. The same applied to a session attached with
  `sessions().load` or `resume`, which stayed loaded on the agent; it is now closed there too.
- A `session/new` the agent answered after the caller stopped waiting, as when a cancelled turn
  interrupts its thread, named a session nothing could close; it is now closed when the answer
  arrives. A `session/new` not answered within `timeout` now fails with an
  `AcpHarnessClientException` instead of an `IllegalStateException`; an answer after that is
  dropped by the ACP SDK, so a session created that late still stays on the agent.
- Subscribing twice to one throwaway `AcpHarnessStream` now runs two sessions. They used to share one,
  so the first to finish could close it under the other.
- A closed `AcpHarnessClientPool` would start new agents on request, and a connection still being
  built when the pool closed was never closed. A closed pool now refuses work with an
  `AcpHarnessClientException`, and a connection that finishes building after the close is closed at
  once. Closing does not interrupt or wait for a connect in progress.
- The HTTP endpoint blocked on the Netty event loop, so on a real server `/prompt`, `/stream` and
  listing sessions failed with a 500, and closing a session never reached the agent. It now runs
  turns without holding a thread.
- The HTTP endpoint gave sessions no owner, so any caller could continue or close another user's
  session by naming it, and `GET {path}/sessions` listed everyone's. Session names are now scoped to
  the caller, and listing returns only the caller's open sessions. Names are limited to 109
  characters, and the endpoint can no longer reach a session the application opened under the same
  name. On OpenCode, `spring.ai.acp.harness.pool.max-processes` now limits how many endpoint users
  can hold sessions.
- `/stream` ended normally when a turn failed. It now returns a 502, or aborts the connection if
  text was already sent.
- An invalid session name on the HTTP endpoint returned a 500, after the pool had assigned the name
  to a process. It is now a 400 before anything else happens.
- A full pool is reported by the new `AcpHarnessCapacityException`, which the HTTP endpoint returns
  as a 503 instead of a 502 or 500.

## [0.5.0] - 2026-10-20

### Changed

- Renamed from Spring AI ACP to **Spring AI ACP Harness Client**, ahead of moving to the Spring AI
  Community organisation. Every artifact moves from `spring-ai-acp-*` to
  `spring-ai-acp-harness-client-*` (for example `spring-ai-acp-boot-starter-goose` becomes
  `spring-ai-acp-harness-client-boot-starter-goose`) under the same `org.springaicommunity`
  groupId; update your dependencies. Java packages (`org.springaicommunity.acp.*`) and
  configuration properties (`spring.acp.*`) are unchanged.
- The live-test switches follow the new name: `-Dspring-ai-acp-harness-client.test.live=true`,
  `-Dspring-ai-acp-harness-client.test.<runtimeId>.model` and
  `-Dspring-ai-acp-harness-client.test.registry.live=true`.
- The agent now sees the client as `spring-ai-acp-harness-client` in ACP `initialize`, as does
  an MCP authorization server registering it when `spring.application.name` is unset. State kept on
  disk keeps the former name so an upgrade finds it: the registry cache
  (`~/.spring-ai-acp/agents`), the `.spring-ai-acp-skill` marker, and the MCP sign-in file of an
  application without a `spring.application.name`. Such an application's console does take the
  new name, for its title and for its input history, which therefore starts afresh.
- Every type named for the agent now says **`AcpAgent`**, so that it no longer reads as one of
  Spring AI's own agent types beside it: `AgentClient` becomes `AcpAgentClient` (and its nested
  `AgentResponse` and `AgentStream`, `AcpAgentResponse` and `AcpAgentStream`),
  `AgentSession`/`AgentSessions` become
  `AcpAgentSession`/`AcpAgentSessions`, `AgentEvent` becomes `AcpAgentEvent`, and likewise
  `AcpAgentOptions`, `AcpAgentSettings`, `AcpAgentInfo`, `AcpAgentNotice`, `AcpAgentExecutor`,
  `AcpAgentClientException`, `UnsupportedAcpAgentOperationException` and
  `UnsupportedAcpAgentOptionException`. The adapter SPI follows (`AcpAgentRuntime`,
  `AcpAgentRuntimeProvider`, `AcpAgentLaunchSpec`, `AcpAgentEnvironment`), as does the test kit
  (`AcpAgentRuntimeContract`, `AcpAgentProbe`, `ScriptedAcpAgent`, `LiveAcpAgents`) and every
  internal type. The rule is mechanical, so replacing each `\bAgent…\b` type name with its
  `AcpAgent…` spelling migrates an application; packages, bean names, configuration properties
  and the standalone configuration file are unchanged. Two exceptions: the transport for a supervised served agent,
  `WebSocketAgentTransport`, becomes `SupervisedWebSocketTransport`, since the SDK's
  `*AcpAgentTransport` names are for the agent's side of the connection; and `AcpAgentSession`
  shares its simple name with the SDK's agent-side `com.agentclientprotocol.sdk.agent.AcpAgentSession`,
  a class an application using this library has no reason to import.
- **A standalone configuration file must now be imported with the `acp:` prefix**:
  `spring.config.import: optional:acp:agents.yaml`. A bare `optional:agents.yaml` is no longer
  claimed by name, because Boot asks this library's resolver before its own, and claiming the
  name took the file from any other library that reads an `agents.yaml`. Without the prefix
  Boot now loads the file as ordinary configuration, so its keys miss `spring.acp` and are
  silently ignored: add `acp:` to the import.

### Fixed

- `spring-ai-acp-harness-client-mcp-oauth`: a sign-in that had lapsed — its refresh token no
  longer honoured — was never cleared, so the user was never asked to sign in again and every
  session after failed to load the server (with goose, "discover and legacy initialize both failed"). The authorization
  server behind the Tanzu MCP gateway refuses such a refresh with a 401, which Spring Security
  reads as `invalid_token_response` instead of `invalid_grant`; an OAuth error in a 401 from the
  token endpoint is now read as what it says.
- `spring-ai-acp-harness-client-mcp-oauth`: an expired token is now refreshed as the session
  opens, so a refresh the server refuses leads straight to signing in again (a browser in
  `mode: local`, a redirect in `mode: web`) instead of failing the session's first request. A refresh that fails without a
  refusal, such as an authorization server that is down, still keeps the token for next time.

## [0.4.0] - 2026-10-04

### Changed

- ACP Java SDK 0.18.0, with its Jackson 3 JSON module (`acp-json-jackson3`). The SDK no longer
  carries a JSON implementation in `acp-core`, and this library now uses Jackson 3 throughout, as
  Spring Boot 4 and Spring AI 2 do. **Jackson 2 no longer arrives through Spring AI ACP**: an
  application that used `com.fasterxml.jackson.databind` without declaring it must now add it
  itself. Jackson's annotations (`com.fasterxml.jackson.annotation`) are unchanged.
- Spring Boot 4.1.1, which brings Jackson 3.1.5, the version the SDK is built against.

### Fixed

- With `goose serve`, an answer to the agent (a permission decision, a file read, a terminal
  result) could be dropped when it was sent at the same moment as a new request, leaving that
  turn waiting until it timed out. The SDK fixed the same race in its own WebSocket transport in
  0.18.0.
- With `goose serve`, a request in flight when the connection drops now fails at once instead of
  waiting out the request timeout, so the turn ends with an error straight away.

## [0.3.1] - 2026-10-01

### Added

- `auth: provided` for an MCP server whose credentials come from the application's own
  `McpCredentialsProvider`. Such a server may omit `url`: the provider supplies the upstream
  with `McpCredentials.routed(url, credentials)` when the session opens, as a credential broker
  that issues a token together with its endpoint does. A URL-less server the provider does not
  route is left out of that session instead of refusing it.
- A Tanzu AI Models (GenAI) service bound on Cloud Foundry becomes the provider with no
  configuration. Its OpenAI-compatible endpoint is used as `spring.acp.provider.base-url` and
  `api-key`, and when no `spring.acp.model` is set, the first `TOOLS`-capable model in its
  catalog is chosen.
  - An explicitly configured provider wins.
  - `spring.acp.tanzu-ai.enabled` and `service-name` control it.
- `CODEX_ACP_CLI_PATH` names a local codex-acp for the Codex runtime to launch in place of
  `npx`, as `GOOSE_CLI_PATH` and `OPENCODE_CLI_PATH` do for theirs. The Codex supply buildpack
  exports it. `spring.acp.runtimes.codex.command` still wins, and so does an explicit
  `package`, which asks for npx.
- Goose's `todo` list arrives as `AgentEvent.PlanUpdated`. Goose keeps its task list in a tool
  and sends no ACP `plan` update, so each `todo__todo_write` call is read as the agent's plan
  instead of being reported as a tool call. `AgentRuntime.planOf` is the hook any adapter can use
  for an agent that does the same.
- Helpers for runtime adapters, so their shared rules live in core:
  - `AgentEnvironment.layered` builds a launch environment in the order every adapter uses.
    Agent defaults come first, then provider credentials, then the tier-3 `env` block, which wins.
  - `Executables.fromEnvironment` reads an executable path from an environment variable such as
    `GOOSE_CLI_PATH`, and checks it at startup.
  - `AgentSettings.findModel()` returns the requested model, or empty when it is blank.

### Changed

- `AcpProperties` and its nested types are records, bound through their constructors. Code that
  reads them uses record accessors (`properties.model()`, `properties.pool().sessionTtl()`) in
  place of getters. Property names, defaults and metadata are unchanged, except that
  `spring.acp.protocol.max-version` now lists its default (`1`) in the metadata. A test that
  declared `new AcpProperties()` as a bean registers it with
  `@EnableConfigurationProperties(AcpProperties.class)` instead.

### Fixed

- OpenCode sessions no longer call MCP servers as another user, or through a closed session's
  route. OpenCode keeps one MCP client per server name for its whole process, replaced by each
  `session/new`, so every session used the route of the one opened last. A runtime now declares
  this with `AgentRuntime.mcpScope()` (`PROCESS` for OpenCode). The pool then keeps each such
  process to one principal at a time, restarting an idle process before it serves someone else,
  and `McpAccess` gives the principal one route shared by all of their sessions, held until the
  last closes. `spring.acp.pool.max-processes` caps how many users hold OpenCode sessions at once,
  and each costs a whole OpenCode process (about 650 MB); see "Processes, users and memory" in the
  user guide.

## [0.3.0] - 2026-09-24

Initial release as Spring AI ACP, under the `org.springaicommunity` groupId and the Apache
License 2.0.

### Agents and runtimes

- `AgentClient`, a fluent client that drives any Agent Client Protocol v1 coding agent and
  streams each turn as `AgentEvent`s, with exactly one terminal event per turn.
- Compiled runtime adapters for Goose, Codex (`codex-acp`) and OpenCode, selected by
  `spring.acp.runtime`, and `spring-ai-acp-boot-starter-registry`, which launches any agent the
  ACP registry publishes from a cached catalogue, with SHA-256-verified downloads.
- A supervised `goose serve` over WebSocket, as an alternative to stdio.
- ACP version negotiation; v2 is behind a feature flag and refused.

### Configuration

- Three configuration tiers: portable (`spring.acp.*`), negotiated (`model`, `mode` and
  `provider`, resolved against the options the agent advertises, with `on-unsupported` =
  fail/warn/ignore), and runtime-specific (`spring.acp.runtimes.<id>.*`).
- Bring-your-own OpenAI-compatible endpoints through `spring.acp.provider.base-url`, with the
  endpoint's own model listing deciding which models are valid.
- A standalone `agents.yaml`, loaded as Spring config data.

### Sessions, MCP and skills

- Capability-gated session list, load, resume and delete, with connection pooling and idle
  session sweeping.
- MCP servers declared per session, with credentials kept out of the agent: HTTP servers that
  need them are reached through a loopback proxy, one unguessable route per session.
- `spring-ai-acp-mcp-oauth`: per-user MCP-spec OAuth, redirecting through Spring Security in a
  web application or signing in through a loopback browser flow in a terminal one.
- Detection of MCP servers that goose fails to load, which ACP never reports on the wire,
  surfaced as `AgentClient.notices()` and acted on through `spring.acp.mcp.on-server-failure`.
- Skills installed from Git (optionally pinned to a commit) into the workspace.

### Permissions and the workspace

- Permission policies (`deny`, `allowlist`, `auto-approve` and `ask`), with `ask` answered by
  a person through a `PermissionPrompt`.
- A workspace jail that confines the agent's filesystem and terminal requests by real path.

### Integrations

- `AcpChatModel`, which publishes the agent as a Spring AI `ChatModel`.
- An opt-in HTTP endpoint, and the `AgentExecutor` facade.
- `spring-ai-acp-console`, a terminal chat over the application's agent for prototyping it.
- A Micrometer observation per turn and per tool call, and `AgentEvent.UsageUpdated`.

### Testing

- `spring-ai-acp-test`: `AgentRuntimeContract`, the cross-adapter contract test;
  `AgentProbe`, which gates live suites on a real session; and `ScriptedAgent`, a fake agent
  speaking raw JSON-RPC for wire-format tests. Live-agent suites are opt-in with
  `-Dspring-ai-acp.test.live=true`.
