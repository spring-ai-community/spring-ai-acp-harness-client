package org.springaicommunity.acp.harness.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientFactory;
import org.springaicommunity.acp.harness.client.AcpHarnessClientPool;
import org.springaicommunity.acp.harness.config.AcpAgentSettings;
import org.springaicommunity.acp.harness.config.McpServerSpec;
import org.springaicommunity.acp.harness.config.OnUnsupported;
import org.springaicommunity.acp.harness.config.OptionResolution.Mechanism;
import org.springaicommunity.acp.harness.config.PoolSettings;
import org.springaicommunity.acp.harness.config.UnsupportedAcpAgentOptionException;
import org.springaicommunity.acp.harness.event.AcpAgentEvent;
import org.springaicommunity.acp.harness.runtime.AcpAgentLaunchSpec;
import org.springaicommunity.acp.harness.runtime.AcpAgentRuntime;
import org.springaicommunity.acp.harness.runtime.AcpAgentRuntime.PortableOption;
import org.springaicommunity.acp.harness.session.AcpAgentSessions;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.StoredSession;
import org.springaicommunity.acp.harness.session.UnsupportedAcpAgentOperationException;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client against a real JSON-RPC wire, with no subprocess.
 *
 * <p>
 * What this covers that the core's unit tests cannot: those mock {@code AcpAsyncClient},
 * so they prove the turn and resolution logic but never the format. Both of the acp-core
 * gaps this library works around are format gaps, invisible to a mock and reproducible
 * here.
 */
class AcpHarnessClientWireTests {

	@TempDir
	Path workspace;

	private static final Duration LIMIT = Duration.ofSeconds(10);

	private AcpAgentSettings.Builder settings() {
		return AcpAgentSettings.builder("scripted", workspace).timeout(LIMIT);
	}

	private AcpHarnessClient connect(ScriptedAcpAgent agent, AcpAgentSettings settings) {
		return AcpHarnessClientFactory.connect(new ScriptedRuntime(), settings, agent.transport());
	}

	private AcpHarnessClient connect(ScriptedAcpAgent agent, AcpAgentSettings settings,
			org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher) {
		return AcpHarnessClientFactory.connect(new ScriptedRuntime(), settings, agent.transport(),
				org.springaicommunity.acp.harness.observation.AcpAgentObservations.NONE, watcher);
	}

	/** A watcher over a log written by hand, standing in for one the agent wrote. */
	private org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher(java.nio.file.Path logs) {
		org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher = new org.springaicommunity.acp.harness.process.AcpAgentLogWatcher(
				logs,
				line -> line.startsWith("FAILED ")
						? java.util.Optional.of(org.springaicommunity.acp.harness.runtime.AcpAgentNotice
							.warning(line.substring(7).split(" ")[0], line))
						: java.util.Optional.empty());
		watcher.start();
		return watcher;
	}

	// --- protocol version negotiation
	// -------------------------------------------------------

	@Test
	void reportsTheVersionTheConversationIsReallyIn() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			assertThat(client.protocolVersion()).isEqualTo(org.springaicommunity.acp.harness.protocol.AcpProtocol.V1);
		}
	}

	@Test
	void clampsAnAgentThatEchoesBackAVersionNobodyOffered() {
		// goose 1.51 answers whatever it is given, so the response's own number is not
		// evidence of
		// anything. A mock of AcpAsyncClient cannot demonstrate this; a wire agent can.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().echoesProtocolVersion(true).build();
				AcpHarnessClient client = connect(agent,
						settings().protocol(org.springaicommunity.acp.harness.protocol.ProtocolSettings.of(1))
							.build())) {
			assertThat(client.protocolVersion()).isEqualTo(1);
		}
	}

	@Test
	void refusesToRunATurnAgainstAnAgentClaimingAVersionThisLibraryCannotSpeak() {
		// The feature flag's honest failure: offering v2 finds out what an agent claims,
		// and an
		// agent that claims it is refused rather than misread, because acp-core decodes
		// v1 shapes.
		ScriptedAcpAgent agent = ScriptedAcpAgent.builder().echoesProtocolVersion(true).build();

		assertThatThrownBy(() -> connect(agent,
				settings()
					.protocol(org.springaicommunity.acp.harness.protocol.ProtocolSettings
						.of(org.springaicommunity.acp.harness.protocol.AcpProtocol.DRAFT_V2))
					.build()))
			.isInstanceOf(org.springaicommunity.acp.harness.protocol.UnsupportedProtocolVersionException.class)
			.hasMessageContaining("negotiated ACP v2");
		agent.close();
	}

	@Test
	void anAgentThatNegotiatesDownIsTakenAtItsWord() {
		// The case the ACP announcement says will be common for a long time: offer the
		// draft, get v1.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent,
						settings()
							.protocol(org.springaicommunity.acp.harness.protocol.ProtocolSettings
								.of(org.springaicommunity.acp.harness.protocol.AcpProtocol.DRAFT_V2))
							.build())) {
			assertThat(client.protocolVersion()).isEqualTo(1);
		}
	}

	@Test
	void namesTheModelTheAgentIsActuallySetToEvenWhenNothingAskedForOne() {
		// An application that never set spring.ai.acp.harness.model is the common case,
		// and a metric
		// tagged
		// "unknown" for all of them would be no use. The agent advertises what it is set
		// to; that
		// is the honest answer, and it is not the same as what was requested.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.select("model", "model", "gpt-5.4-mini", "gpt-5.4-mini", "gpt-6-astra")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {

			assertThat(client.openSession("s").advertised().select("model", "model")).get()
				.extracting(AcpSchema.SessionConfigSelect::currentValue)
				.isEqualTo("gpt-5.4-mini");
		}
	}

	// --- authentication
	// ---------------------------------------------------------------------

	/**
	 * A runtime whose adapter names an auth method, the way the Codex adapter does for a
	 * key.
	 */
	private AcpHarnessClient connectSigningInWith(ScriptedAcpAgent agent, String method) {
		ScriptedRuntime runtime = new ScriptedRuntime() {
			@Override
			public java.util.Optional<String> authMethod(AcpAgentSettings settings) {
				return java.util.Optional.of(method);
			}
		};
		return AcpHarnessClientFactory.connect(runtime, settings().build(), agent.transport());
	}

	@Test
	void signsInWithTheMethodTheAdapterNamedBeforeAnySessionIsOpened() {
		// codex-acp 1.12 refuses session/new with the key already on its environment
		// until
		// authenticate names api-key; nothing on the wire says so until a session is
		// asked for.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().authMethods("api-key", "chat-gpt").build();
				AcpHarnessClient client = connectSigningInWith(agent, "api-key")) {
			client.openSession("s");

			assertThat(agent.authentications()).containsExactly("api-key");
			assertThat(agent.methods()).containsSubsequence("initialize", "authenticate", "session/new");
		}
	}

	@Test
	void sendsNoAuthenticateWhenTheAdapterNamesNoMethod() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			client.openSession("s");

			assertThat(agent.methods()).doesNotContain("authenticate");
		}
	}

	@Test
	void aMethodTheAgentDoesNotOfferIsNotSent() {
		// The agent may be signed in another way; if it is not, its own refusal says so.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connectSigningInWith(agent, "api-key")) {
			client.openSession("s");

			assertThat(agent.authentications()).isEmpty();
		}
	}

	@Test
	void refusedCredentialsFailTheConnectionRatherThanEverySession() {
		ScriptedAcpAgent agent = ScriptedAcpAgent.builder().authMethods("api-key").refusesAuthentication(true).build();

		assertThatThrownBy(() -> connectSigningInWith(agent, "api-key"))
			.isInstanceOf(org.springaicommunity.acp.harness.client.AcpHarnessClientException.class)
			.hasMessageContaining("refused authentication with 'api-key'");
		agent.close();
	}

	// --- the two SDK gaps
	// -------------------------------------------------------------------

	@Test
	void recoversTheConfigOptionsThatNewSessionResponseDrops() {
		// The SDK's NewSessionResponse models sessionId, modes and models and ignores the
		// rest, so
		// this field is dropped before a client could read it. Without it the resolver
		// cannot tell a
		// model the agent has from one it does not.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a", "b").build();
				AcpHarnessClient client = connect(agent, settings().model("b").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(client.session("s")).get()
				.satisfies(session -> assertThat(session.advertised().configOptions()).hasSize(1));
			assertThat(agent.configSets()).containsExactly(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aSessionUpdateVariantThisSdkCannotModelIsSkippedRatherThanFatal() {
		// goose emits session_info_update several times a turn and acp-core has no record
		// for it. The
		// SDK's own sessionUpdateConsumer would log an ERROR per occurrence; this client
		// registers a
		// raw handler and decodes leniently, so the unknown one is dropped and the turn
		// is unaffected.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().emitsUnknownUpdate(true).reply("one", "two").build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			List<AcpAgentEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events).containsExactly(new AcpAgentEvent.Text("one"), new AcpAgentEvent.Text("two"),
					new AcpAgentEvent.Completed(AcpSchema.StopReason.END_TURN));
		}
	}

	// --- the negotiated tier over the wire
	// --------------------------------------------------

	@Test
	void anAgentThatAcceptsAnythingStillCannotGetAnUnknownModelPastTheResolver() {
		// The goose 1.51 behavior that makes "set it and see" unworkable.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.select("model", "model", "a", "a")
			.acceptsUnknownValues(true)
			.build(); AcpHarnessClient client = connect(agent, settings().model("made-up").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.configSets()).isEmpty();
			assertThat(client.session("s")).get()
				.satisfies(session -> assertThat(session.configuration().of(PortableOption.MODEL).mechanism())
					.isEqualTo(Mechanism.UNSUPPORTED));
		}
	}

	@Test
	void onUnsupportedFailStopsTheTurnBeforeItStarts() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").build();
				AcpHarnessClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.prompt("hi").call()).isInstanceOf(UnsupportedAcpAgentOptionException.class);
			assertThat(agent.prompts()).isZero();
		}
	}

	@Test
	void setModeIsUsedWhenTheAgentOffersModesButNoModeOption() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().modes("auto", "plan").build();
				AcpHarnessClient client = connect(agent, settings().mode("plan").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.methods()).contains("session/set_mode");
			assertThat(client.session("s")).get()
				.satisfies(session -> assertThat(session.configuration().of(PortableOption.MODE).mechanism())
					.isEqualTo(Mechanism.SESSION_MODE));
		}
	}

	@Test
	void providersSetIsUsedWhenTheAgentAdvertisesTheCapability() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().providers("openai").build();
				AcpHarnessClient client = connect(agent, settings().provider("openai").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.providerSets()).hasSize(1);
			assertThat(client.session("s")).get()
				.satisfies(session -> assertThat(session.configuration().of(PortableOption.PROVIDER).mechanism())
					.isEqualTo(Mechanism.PROVIDERS_SET));
		}
	}

	@Test
	void aProviderIsNotAttemptedOverTheWireWhenTheAgentDoesNotAdvertiseIt() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().provider("openai").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.methods()).doesNotContain("providers/set").doesNotContain("providers/list");
		}
	}

	// --- MCP credentials and principals
	// ----------------------------------------------------------

	/**
	 * A provider that gives every HTTP server a token naming whoever the session is for.
	 */
	private static org.springaicommunity.acp.harness.mcp.McpCredentialsProvider tokensPerUser(
			List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor) {
		return (server, principal) -> {
			askedFor.add(principal);
			return java.util.Optional.of(org.springaicommunity.acp.harness.mcp.McpCredentials
				.bearer(() -> "token-" + (principal == null ? "nobody" : principal.name())));
		};
	}

	private AcpAgentSettings.Builder withProtectedServer(
			org.springaicommunity.acp.harness.mcp.McpCredentialsProvider provider,
			org.springaicommunity.acp.harness.session.SessionPrincipalResolver principals) {
		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://gateway.example.com/finops-mcp/mcp"), Map.of("X-Tenant", "acme"));
		return settings().mcpServers(List.of(server))
			.mcp(org.springaicommunity.acp.harness.config.McpSettings.defaults()
				.withCredentials(provider)
				.withPrincipals(principals));
	}

	private static String declaredUrl(AcpSchema.NewSessionRequest request) {
		return ((AcpSchema.McpServerHttp) request.mcpServers().get(0)).url();
	}

	/** Whether the proxy still answers for a route; any answer but 404 means it does. */
	private static int statusOf(String url) throws Exception {
		return java.net.http.HttpClient.newHttpClient()
			.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(LIMIT)
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
				.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	@Test
	void aCredentialedMcpServerReachesTheAgentAsALoopbackRouteWithNoSecretsInIt() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(new java.util.ArrayList<>()), null).build())) {

			client.prompt()
				.session("s")
				.principal(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"))
				.user("hi")
				.call();

			AcpSchema.McpServerHttp declared = (AcpSchema.McpServerHttp) agent.newSessions().get(0).mcpServers().get(0);
			assertThat(declared.name()).isEqualTo("finops-mcp");
			assertThat(declared.url()).startsWith("http://127.0.0.1:")
				.endsWith("/finops-mcp")
				.doesNotContain("gateway.example.com");
			assertThat(declared.headers()).isEmpty();
		}
	}

	@Test
	void aSessionsRouteStopsAnsweringWhenTheSessionCloses() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().sessionOperations("close").build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(new java.util.ArrayList<>()), null).build())) {

			client.prompt().session("s").user("hi").call();
			String url = declaredUrl(agent.newSessions().get(0));
			// The upstream does not exist, so a live route answers 502; what matters is
			// that it is not 404.
			assertThat(statusOf(url)).isNotEqualTo(404);

			client.sessions().close("s");

			assertThat(statusOf(url)).isEqualTo(404);
		}
	}

	@Test
	void anEphemeralTurnsRouteEndsWithTheTurn() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(new java.util.ArrayList<>()), null).build())) {

			client.prompt("hi").call();

			assertThat(statusOf(declaredUrl(agent.newSessions().get(0)))).isEqualTo(404);
		}
	}

	@Test
	void closingTheClientStopsTheProxy() {
		String url;
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(new java.util.ArrayList<>()), null).build())) {
			client.prompt().session("s").user("hi").call();
			url = declaredUrl(agent.newSessions().get(0));
		}
		assertThatThrownBy(() -> statusOf(url)).isInstanceOf(java.io.IOException.class);
	}

	@Test
	void aSessionNameOpenForOneUserIsRefusedToAnother() {
		List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient client = connect(agent, withProtectedServer(tokensPerUser(askedFor), null).build())) {

			client.prompt()
				.session("ticket-42")
				.principal(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"))
				.user("hi")
				.call();

			assertThatThrownBy(() -> client.prompt()
				.session("ticket-42")
				.principal(org.springaicommunity.acp.harness.session.SessionPrincipal.of("bob"))
				.user("hi")
				.call()).isInstanceOf(org.springaicommunity.acp.harness.session.SessionOwnershipException.class);
			assertThatThrownBy(() -> client.openSession("ticket-42"))
				.isInstanceOf(org.springaicommunity.acp.harness.session.SessionOwnershipException.class);
			assertThat(client.prompt()
				.session("ticket-42")
				.principal(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"))
				.user("again")
				.call()
				.content()).isEqualTo("ok");
			assertThat(agent.newSessions()).hasSize(1);
			assertThat(askedFor)
				.containsExactly(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void thePrincipalIsReadOnTheCallersThreadNotWhereTheStreamIsSubscribed() {
		ThreadLocal<String> signedIn = new ThreadLocal<>();
		List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		org.springaicommunity.acp.harness.session.SessionPrincipalResolver resolver = () -> java.util.Optional
			.ofNullable(signedIn.get())
			.map(org.springaicommunity.acp.harness.session.SessionPrincipal::of);

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(askedFor), resolver).build())) {

			signedIn.set("alice");
			AcpHarnessClient.AcpAgentStream stream = client.prompt().session("s").user("hi").stream();
			signedIn.remove();

			StepVerifier
				.create(stream.events()
					.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
					.filter(AcpAgentEvent.Completed.class::isInstance))
				.expectNextCount(1)
				.verifyComplete();

			assertThat(askedFor)
				.containsExactly(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
			assertThat(client.session("s").orElseThrow().principal())
				.contains(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void theSamePathsHoldThroughThePool() {
		List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		ThreadLocal<String> signedIn = new ThreadLocal<>();
		org.springaicommunity.acp.harness.session.SessionPrincipalResolver resolver = () -> java.util.Optional
			.ofNullable(signedIn.get())
			.map(org.springaicommunity.acp.harness.session.SessionPrincipal::of);
		AcpAgentSettings settings = withProtectedServer(tokensPerUser(askedFor), resolver).build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient pool = new org.springaicommunity.acp.harness.client.AcpHarnessClientPool("scripted",
						settings, () -> connect(agent, settings))) {

			signedIn.set("alice");
			AcpHarnessClient.AcpAgentStream stream = pool.prompt().session("s").user("hi").stream();
			signedIn.remove();
			StepVerifier
				.create(stream.events()
					.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
					.filter(AcpAgentEvent.Completed.class::isInstance))
				.expectNextCount(1)
				.verifyComplete();

			assertThatThrownBy(
					() -> pool.openSession("s", org.springaicommunity.acp.harness.session.SessionPrincipal.of("bob")))
				.isInstanceOf(org.springaicommunity.acp.harness.session.SessionOwnershipException.class);
			assertThat(askedFor)
				.containsExactly(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void aLoadedSessionsServersAreRoutedForItsPrincipalToo() {
		List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().sessionOperations("load", "resume").build();
				AcpHarnessClient client = connect(agent, withProtectedServer(tokensPerUser(askedFor), null).build())) {

			client.sessions()
				.load("a", "stored-1", org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
			client.sessions()
				.resume("b", "stored-2", org.springaicommunity.acp.harness.session.SessionPrincipal.of("bob"));

			assertThat(agent.attachedMcpServers()).hasSize(2).allSatisfy(servers -> {
				assertThat(servers).hasSize(1);
				assertThat(String.valueOf(servers.get(0).get("url"))).startsWith("http://127.0.0.1:")
					.doesNotContain("gateway.example.com");
			});
			assertThat(askedFor).containsExactly(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"),
					org.springaicommunity.acp.harness.session.SessionPrincipal.of("bob"));
			assertThat(client.session("a").orElseThrow().principal())
				.contains(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void anAdaptersMcpWorkaroundPutsEveryHttpServerBehindTheProxyEvenWithoutCredentials() throws Exception {
		AcpAgentRuntime withWorkaround = new ScriptedRuntime() {
			@Override
			public List<org.springaicommunity.acp.harness.mcp.McpRequestFilter> mcpRequestFilters(
					AcpAgentSettings settings) {
				return List.of(request -> org.springaicommunity.acp.harness.mcp.McpRequestFilter.Outcome.Answer
					.json(200, "{}"));
			}
		};
		McpServerSpec server = new McpServerSpec.Http("tools", java.net.URI.create("https://tools.example.com/mcp"),
				Map.of());
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = AcpHarnessClientFactory.connect(withWorkaround,
						settings().mcpServers(List.of(server)).build(), agent.transport())) {

			client.prompt().session("s").user("hi").call();

			String url = declaredUrl(agent.newSessions().get(0));
			assertThat(url).startsWith("http://127.0.0.1:");
			assertThat(statusOf(url)).isEqualTo(200);
		}
	}

	@Test
	void aProviderThatRefusesTheUserRefusesTheSessionBeforeTheAgentHearsOfIt() {
		org.springaicommunity.acp.harness.mcp.McpCredentialsProvider notSignedIn = (server, principal) -> {
			throw new IllegalStateException(server.name() + " needs a sign-in first");
		};
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, withProtectedServer(notSignedIn, null).build())) {

			assertThatThrownBy(() -> client.openSession("s",
					org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice")))
				.hasMessageContaining("needs a sign-in first");
			assertThat(agent.newSessions()).isEmpty();
			assertThat(client.session("s")).isEmpty();
		}
	}

	// --- a session name left behind, on an agent that shares MCP servers across sessions
	// ----------------------------------------------------------

	private static final SessionPrincipal ALICE = SessionPrincipal.of("alice");

	private static final SessionPrincipal BOB = SessionPrincipal.of("bob");

	/**
	 * Refuses Alice's first session, as a provider does for a user not yet signed in,
	 * which leaves its name assigned in the pool with no session behind it.
	 */
	private static org.springaicommunity.acp.harness.mcp.McpCredentialsProvider refusingAliceOnce() {
		java.util.concurrent.atomic.AtomicBoolean refused = new java.util.concurrent.atomic.AtomicBoolean();
		return (server, principal) -> {
			if (ALICE.equals(principal) && refused.compareAndSet(false, true)) {
				throw new IllegalStateException("alice needs a sign-in first");
			}
			return java.util.Optional
				.of(org.springaicommunity.acp.harness.mcp.McpCredentials.bearer(() -> "token-" + principal.name()));
		};
	}

	/** Each connection is its own scripted agent, as each would be its own process. */
	private AcpHarnessClientPool processScopedPool(List<ScriptedAcpAgent> agents, int processes,
			org.springaicommunity.acp.harness.mcp.McpCredentialsProvider provider) {
		AcpAgentSettings settings = withProtectedServer(provider, null)
			.pool(new PoolSettings(processes, 32, PoolSettings.DEFAULT_MAX_RESTARTS))
			.build();
		return new AcpHarnessClientPool("scripted", settings, () -> {
			ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
			agents.add(agent);
			return AcpHarnessClientFactory.connect(new SharedMcpRuntime(), settings, agent.transport());
		}, AcpAgentRuntime.McpScope.PROCESS);
	}

	@Test
	void aNameLeftBehindDoesNotTakeAProcessToAnotherPrincipalWithoutARestart() {
		List<ScriptedAcpAgent> agents = new java.util.ArrayList<>();
		try (AcpHarnessClient pool = processScopedPool(agents, 1, refusingAliceOnce())) {
			assertThatThrownBy(() -> pool.openSession("s", ALICE)).hasMessageContaining("needs a sign-in first");

			pool.openSession("s", BOB);

			assertThat(agents).hasSize(2);
			assertThat(agents.get(0).newSessions()).isEmpty();
			assertThat(agents.get(1).newSessions()).hasSize(1);
		}
		finally {
			agents.forEach(ScriptedAcpAgent::close);
		}
	}

	@Test
	void aPrincipalIsNotSentBackToAProcessThatANameLeftBehindGaveToSomeoneElse() {
		List<ScriptedAcpAgent> agents = new java.util.ArrayList<>();
		try (AcpHarnessClient pool = processScopedPool(agents, 2, refusingAliceOnce())) {
			assertThatThrownBy(() -> pool.openSession("s", ALICE)).hasMessageContaining("needs a sign-in first");
			pool.openSession("s", BOB);

			pool.openSession("other", ALICE);

			assertThat(agents).hasSize(2);
			assertThat(agents).allSatisfy(agent -> assertThat(agent.newSessions()).hasSize(1));
		}
		finally {
			agents.forEach(ScriptedAcpAgent::close);
		}
	}

	@Test
	void aNameLeftBehindBesideALiveSessionMovesInsteadOfBeingRefused() {
		List<ScriptedAcpAgent> agents = new java.util.ArrayList<>();
		try (AcpHarnessClient pool = processScopedPool(agents, 2, refusingAliceOnce())) {
			assertThatThrownBy(() -> pool.openSession("s", ALICE)).hasMessageContaining("needs a sign-in first");
			pool.openSession("live", ALICE);

			pool.openSession("s", BOB);

			assertThat(agents).hasSize(2);
			assertThat(agents).allSatisfy(agent -> assertThat(agent.newSessions()).hasSize(1));
		}
		finally {
			agents.forEach(ScriptedAcpAgent::close);
		}
	}

	/** An agent like OpenCode, whose MCP servers belong to the process. */
	private static final class SharedMcpRuntime extends ScriptedRuntime {

		@Override
		public McpScope mcpScope() {
			return McpScope.PROCESS;
		}

	}

	// --- the portable tier
	// ------------------------------------------------------------------

	@Test
	void mcpServersReachSessionNewVerbatim() {
		McpServerSpec server = new McpServerSpec.Http("tools", java.net.URI.create("https://tools.example.com/mcp"),
				Map.of("Authorization", "Bearer secret"));

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().mcpServers(List.of(server)).build())) {

			client.prompt("hi").call();

			assertThat(agent.newSessions()).hasSize(1);
			assertThat(agent.newSessions().get(0).mcpServers()).hasSize(1)
				.first(org.assertj.core.api.InstanceOfAssertFactories.type(AcpSchema.McpServerHttp.class))
				.satisfies(http -> {
					assertThat(http.name()).isEqualTo("tools");
					assertThat(http.url()).isEqualTo("https://tools.example.com/mcp");
				});
			assertThat(agent.newSessions().get(0).cwd()).isEqualTo(workspace.toString());
		}
	}

	@Test
	void anMcpServerTheAgentCouldNotLoadFailsTheSessionWhenTheApplicationAsksItTo() throws java.io.IOException {
		// The failure this whole path exists for: the agent answers session/new normally
		// and says
		// nothing over the wire, so the only evidence is the line it wrote to its own
		// log.
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED finops-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AcpAgentSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofSeconds(2)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher = watcher(logs);
				AcpHarnessClient client = connect(agent, settings, watcher)) {

			assertThatThrownBy(() -> client.prompt().session("s").user("hi").call()).hasMessageContaining("finops-mcp")
				.hasMessageContaining("refused the connection");
			assertThat(client.notices()).isNotEmpty();
		}
	}

	@Test
	void aFailureAboutSomeOtherServerDoesNotFailThisSession() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED some-other-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AcpAgentSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("done").build();
				org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher = watcher(logs);
				AcpHarnessClient client = connect(agent, settings, watcher)) {

			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
		}
	}

	@Test
	void theDefaultIsToOpenTheSessionAndLetTheApplicationReadTheNotice() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));
		java.nio.file.Files.writeString(logs.resolve("agent.log"), "FAILED finops-mcp refused the connection\n");

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("done").build();
				org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher = watcher(logs);
				AcpHarnessClient client = connect(agent, settings().mcpServers(List.of(server)).build(), watcher)) {
			watcher.poll();

			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
			assertThat(client.notices()).singleElement()
				.satisfies(notice -> assertThat(notice.subject()).isEqualTo("finops-mcp"));
		}
	}

	@Test
	void anAgentThatReportsNothingOpensTheSessionEvenUnderFail() throws java.io.IOException {
		java.nio.file.Path logs = java.nio.file.Files.createDirectories(workspace.resolve("logs"));

		McpServerSpec server = new McpServerSpec.Http("finops-mcp",
				java.net.URI.create("https://tools.example.com/mcp"), Map.of());
		AcpAgentSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("done").build();
				org.springaicommunity.acp.harness.process.AcpAgentLogWatcher watcher = watcher(logs);
				AcpHarnessClient client = connect(agent, settings, watcher)) {

			// Silence is the normal case, and nothing here infers a failure from it.
			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("done");
			assertThat(client.notices()).isEmpty();
		}
	}

	@Test
	void toolCallsAreReportedAsEventsWithTheirIdentifiers() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().toolCall("developer__shell").reply("done").build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			List<AcpAgentEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events)
				.anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(AcpAgentEvent.ToolCallStarted.class,
						started -> assertThat(started.title()).isEqualTo("developer__shell")));
			assertThat(events).last().isInstanceOf(AcpAgentEvent.Completed.class);
		}
	}

	@Test
	void cancellingTheStreamSendsSessionCancel() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().promptDelay(Duration.ofSeconds(30)).build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			StepVerifier.create(client.prompt("hi").stream().events())
				.thenAwait(Duration.ofMillis(200))
				.thenCancel()
				.verify(LIMIT);

			// Fire-and-forget by necessity: the subscriber is already gone, so poll
			// briefly.
			Awaits.until(() -> agent.cancellations() == 1, LIMIT);
		}
	}

	@Test
	void aNamedSessionIsOpenedOnceAndReusedAcrossTurns() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			client.prompt().session("reused").user("one").call();
			client.prompt().session("reused").user("two").call();

			assertThat(agent.newSessions()).hasSize(1);
			assertThat(agent.prompts()).isEqualTo(2);
		}
	}

	@Test
	void anEphemeralSessionIsClosedWhenItsTurnEnds() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			client.prompt("hi").call();

			Awaits.until(() -> agent.methods().contains("session/close"), LIMIT);
		}
	}

	@Test
	void anEphemeralTurnDoesNotWaitOnItsOwnCleanup() {
		// A turn's teardown runs on the thread that delivers the agent's replies, so
		// waiting there for
		// a session/close acknowledgement deadlocks until the timeout expires. Nothing
		// fails when it
		// does, which is why this is asserted on the clock: three scripted turns take
		// milliseconds, and
		// the bug made each of them take five seconds.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
				client.prompt("one").call();
				client.prompt("two").call();
				client.prompt("three").call();
			});
		}
	}

	// --- session operations
	// -----------------------------------------------------------------

	@Test
	void anAgentThatAdvertisesNoSessionOperationsIsRefusedByNameRatherThanOnTheWire() {
		// Every one of these is optional in ACP and the three real runtimes implement
		// different
		// subsets, so "not here" has to be a typed answer rather than a JSON-RPC error
		// code.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			AcpAgentSessions sessions = client.sessions();

			assertThat(sessions.supports(AcpAgentSessions.Operation.LIST)).isFalse();
			assertThatThrownBy(sessions::list).isInstanceOf(UnsupportedAcpAgentOperationException.class);
			assertThatThrownBy(() -> sessions.load("a", "sid-1"))
				.isInstanceOf(UnsupportedAcpAgentOperationException.class);
			assertThatThrownBy(() -> sessions.delete("sid-1"))
				.isInstanceOf(UnsupportedAcpAgentOperationException.class);
			assertThat(agent.methods()).doesNotContain("session/list", "session/load", "session/delete");
		}
	}

	@Test
	void listingFollowsTheAgentsCursorToTheEnd() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("list")
			.storedSessions("sid-1", "sid-2", "sid-3")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {

			assertThat(client.sessions().list()).extracting(StoredSession::sessionId)
				.containsExactly("sid-1", "sid-2", "sid-3");
			assertThat(client.sessions().supports(AcpAgentSessions.Operation.LIST)).isTrue();
		}
	}

	/**
	 * {@code LoadSessionResponse} drops {@code configOptions} exactly as
	 * {@code NewSessionResponse} does, and with one extra difficulty: the response does
	 * not name its session, so there is nothing to key the recovered options on. A loaded
	 * session that can still negotiate its model is the proof that the claim-immediately
	 * arrangement works.
	 */
	@Test
	void aLoadedSessionStillNegotiatesItsModel() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("load", "list")
			.storedSessions("sid-1")
			.select("model", "model", "a", "a", "b")
			.build(); AcpHarnessClient client = connect(agent, settings().model("b").build())) {

			client.sessions().load("restored", "sid-1");

			assertThat(agent.loaded()).containsExactly("sid-1");
			assertThat(client.session("restored")).get()
				.extracting(session -> session.configuration().of(PortableOption.MODEL).applied())
				.isEqualTo("b");
			assertThat(agent.configSets()).contains(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aResumedSessionIsBoundWithoutReplayingHistory() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("resume")
			.storedSessions("sid-1")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {

			assertThat(client.sessions().resume("restored", "sid-1").sessionId()).isEqualTo("sid-1");
			assertThat(agent.methods()).contains("session/resume").doesNotContain("session/load");
		}
	}

	@Test
	void deletingRemovesTheSessionFromTheAgentAndTheNameFromTheClient() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("load", "delete", "list")
			.storedSessions("sid-1", "sid-2")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {
			client.sessions().load("restored", "sid-1");

			client.sessions().delete("sid-1");

			assertThat(agent.storedSessions()).containsExactly("sid-2");
			assertThat(client.session("restored")).isEmpty();
		}
	}

	@Test
	void theAgentsOwnVersionSurvivesTheHandshake() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().agentInfo("pretend", "9.9.9").build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			assertThat(client.agentInfo()).get().hasToString("pretend 9.9.9");
		}
	}

	/** A runtime that names no agent: only the core is under test here. */
	private static class ScriptedRuntime implements AcpAgentRuntime {

		@Override
		public String id() {
			return "scripted";
		}

		@Override
		public AcpAgentLaunchSpec launch(AcpAgentSettings settings) {
			throw new UnsupportedOperationException("the scripted agent is already running");
		}

		@Override
		public java.util.Optional<String> toolNameOf(AcpSchema.ToolCallUpdate toolCall) {
			return org.springaicommunity.acp.harness.runtime.ToolNames.fromRawInput(toolCall);
		}

	}

	/**
	 * Minimal polling, so a fire-and-forget notification can be asserted without a sleep.
	 */
	private static final class Awaits {

		private static void until(java.util.function.BooleanSupplier condition, Duration limit) {
			long deadline = System.nanoTime() + limit.toNanos();
			while (System.nanoTime() < deadline) {
				if (condition.getAsBoolean()) {
					return;
				}
				Thread.onSpinWait();
			}
			throw new AssertionError("condition was not met within " + limit);
		}

	}

}
