# MCP Servers

MCP servers give the agent tools. They are the most portable part of the configuration: ACP passes the list to the agent in `session/new`, so every compliant agent honours it with no adapter involved. Servers are offered to every session the client opens.

## Declaring servers

Each server sets **either** `url` (HTTP) **or** `command` (stdio). Both, or neither, fails at startup.

```yaml title="application.yaml"
spring:
  ai:
    acp:
      harness:
        mcp-servers:
          # Remote server over streamable HTTP.
          - name: inventory
            url: https://mcp.example.com/inventory/mcp
            headers:
              Authorization: "Bearer ${INVENTORY_TOKEN}"

          # Local server the agent launches as a child process.
          - name: filesystem
            command: npx
            args: [ "-y", "@modelcontextprotocol/server-filesystem", "/srv/agent-workspace" ]
            env:
              LOG_LEVEL: info
```

| Field | Transport | Notes |
| --- | --- | --- |
| `name` | both | Required, unique. Tools usually surface prefixed with it. |
| `url` | HTTP | HTTPS, or HTTP for `localhost`/loopback and `*.apps.internal`. No credentials in the URL. |
| `headers` | HTTP | Static headers such as a service token. Validated; never logged. |
| `auth` | HTTP | `none` (default), `oauth` for per-user sign-in ([below](#per-user-oauth)), or `provided` for your own `McpCredentialsProvider`, which may also supply the URL (`url` then omitted). |
| `command` | stdio | The executable the agent launches. |
| `args` | stdio | Arguments, in order. |
| `env` | stdio | Environment for the server process. |

A Spring AI MCP server of your own is just another HTTP entry. At `INFO`, Spring AI ACP Harness Client logs the host and path of each server it offers when a session opens, never its headers.

!!! danger "Important"

    **Declaring a server is not permission to call it.** If the agent asks permission before calling a tool, the default policy (`deny`) refuses it. Configuring MCP servers is two steps: declare them here, then grant them in [Permissions](permissions.md).

## When a server fails to load

An MCP server the agent cannot reach is reported nowhere in ACP: the session opens normally, the model answers, and it simply has no tools. Goose writes the failure only to its own log file, and Spring AI ACP Harness Client watches that log for agents it started:

```yaml
spring:
  ai:
    acp:
      harness:
        mcp:
          on-server-failure: fail    # warn (default): log it and carry on; fail: refuse the session
          detect-timeout: 2s         # how long session opening waits for a report (fail only)
```

```java
acpHarnessClient.notices().forEach(n -> log.warn("{} {}: {}", n.severity(), n.subject(), n.detail()));
```

!!! note

    Only the Goose adapter can report load failures today. On other runtimes these settings are inert and `notices()` stays empty; the symptom is the model saying it lacks tools its instructions say it has.

## Credentials the agent never sees

Static `headers` are handed to the agent and frozen for the session. For expiring tokens, or tokens that belong to a particular user, contribute an `McpCredentialsProvider` bean instead:

```java
@Bean
McpCredentialsProvider mcpCredentials(TokenService tokens) {
    return (server, principal) -> server.name().equals("inventory")
            ? Optional.of(McpCredentials.bearer(() -> tokens.currentToken(principal)))   // asked per request
            : Optional.empty();                                                         // as configured
}
```

Each server the provider answers for is routed through a loopback proxy on `127.0.0.1`. The agent is given `http://127.0.0.1:<port>/<random-token>/<server>`; the proxy adds the headers on every forwarded request (so a token refresh lands between two tool calls) and drops the agent's own `Authorization`. Each session gets its own unguessable route, released when the session ends. A provider that throws refuses the session before the agent hears of it.

An agent that shares MCP servers across its sessions changes that (`AcpAgentRuntime.mcpScope()` returns `PROCESS`; OpenCode does). Such an agent keeps one client per server name for the whole process, and every `session/new` replaces it, so all of its sessions call through the route declared last. The client therefore keeps each agent process to one principal at a time and gives that principal a single route, shared by all of their sessions and released when the last of them ends. A process that last served another principal is restarted before it is handed over. `spring.ai.acp.harness.pool.max-processes` is then the number of users who can hold sessions at once; beyond it a new user's session is refused with a message that says so. See [Processes, users and memory](../runtimes/opencode.md#processes-users-and-memory) for what that costs.

### URL from the provider

Some servers are only located when their credential is issued: a credential broker answers with a token *and* the endpoint it is good for. Declare such a server with `auth: provided` and no `url`, and return the endpoint with the credential:

```yaml
spring:
  ai:
    acp:
      harness:
        mcp-servers:
          - name: github
            auth: provided             # credentials, and here the url, come from your McpCredentialsProvider
```

```java
return (server, principal) -> broker.accessFor(server.name(), principal)       // your own lookup
        .map(grant -> McpCredentials.routed(grant.url(), McpCredentials.bearer(grant::token)));
```

The upstream is read once, when the session opens, and must be HTTPS (plain HTTP only for loopback and `*.apps.internal`). A server with no URL of its own that the provider does not route for a session, because the user has not granted access yet, is left out of that session rather than refusing it; the next session asks again. `McpCredentials.upstream()` also overrides a configured `url`.

## Per-user OAuth

For MCP servers that implement the MCP authorization spec, add `spring-ai-acp-harness-client-mcp-oauth` and mark the server `auth: oauth`. Authorization-server discovery, dynamic client registration, PKCE and the `resource` parameter are handled for you; each user signs in once per server, and tokens are refreshed as needed.

```xml title="pom.xml"
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-acp-harness-client-mcp-oauth</artifactId>
    <version>${spring-ai-acp-harness-client.version}</version>
</dependency>
```

```yaml title="application.yaml"
spring:
  ai:
    acp:
      harness:
        mcp-servers:
          - name: finops-mcp
            url: https://gateway.example.com/finops-mcp/mcp
            auth: oauth
        mcp:
          oauth:
            mode: web                  # web (default) | local
            client-name: Capacity Agent
            store: jdbc                # memory (web default) | jdbc | file (local default)
```

### Web applications

Users sign in through Spring Security's redirect. Add the MCP configurer to your filter chain:

```java
@Bean
SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2Login(Customizer.withDefaults())
            .with(McpClientOAuth2Configurer.mcpClientOAuth2(), mcp -> mcp.cimd(false))
            .build();
}
```

The signed-in user becomes the `SessionPrincipal` of every prompt described on a request thread. A named session belongs to its principal; another user reusing the name gets `SessionOwnershipException`. On a reactive stack, pass `.principal(SessionPrincipal.of(name))` explicitly. To send a user to sign in before a stream starts, call `OAuth2McpCredentialsProvider.requireAuthorized(principal)` in the controller. With `store: jdbc`, registrations and tokens are shared across instances (schema: `org/springaicommunity/acp/mcp/oauth/acp-mcp-oauth-schema.sql` plus Spring Security's `oauth2_authorized_client`).

### Terminal applications

`mode: local` needs no filter chain. The first run opens a browser for each server (a loopback redirect on `127.0.0.1`); the operating-system user is the principal, and sign-ins are kept in `~/.config/<spring.application.name>/acp-mcp-oauth.json` (mode 600), so later runs refresh silently. A sign-in the server will no longer refresh opens the browser again when the next session opens.

## Goose and MCP gateways

Goose 1.51 opens an HTTP MCP connection with a `server/discover` probe. Some gateways answer it with an error goose cannot correlate, so goose never falls back to `initialize` and the server silently never loads. `spring.ai.acp.harness.runtimes.goose.mcp.answer-discover: true` answers the probe correctly on the gateway's behalf. Enable it only for gateways that need it, and remove it once either side is fixed.
