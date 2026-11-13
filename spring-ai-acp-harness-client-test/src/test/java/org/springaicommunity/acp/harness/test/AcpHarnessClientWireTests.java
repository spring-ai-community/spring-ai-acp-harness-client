package org.springaicommunity.acp.harness.test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.client.AcpHarnessClientFactory;
import org.springaicommunity.acp.harness.client.AcpHarnessClientPool;
import org.springaicommunity.acp.harness.client.AcpHarnessSessionBusyException;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.config.McpServerSpec;
import org.springaicommunity.acp.harness.config.OnUnsupported;
import org.springaicommunity.acp.harness.config.OptionResolution.Mechanism;
import org.springaicommunity.acp.harness.config.PoolSettings;
import org.springaicommunity.acp.harness.config.UnsupportedAcpHarnessOptionException;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.permission.PermissionPolicy;
import org.springaicommunity.acp.harness.permission.PermissionPrompt;
import org.springaicommunity.acp.harness.runtime.AcpHarnessLaunchSpec;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime.PortableOption;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.AcpHarnessSessions;
import org.springaicommunity.acp.harness.session.StoredSession;
import org.springaicommunity.acp.harness.session.UnsupportedAcpHarnessOperationException;

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

	private AcpHarnessSettings.Builder settings() {
		return AcpHarnessSettings.builder("scripted", workspace).timeout(LIMIT);
	}

	private AcpHarnessClient connect(ScriptedAcpAgent agent, AcpHarnessSettings settings) {
		return AcpHarnessClientFactory.connect(new ScriptedRuntime(), settings, agent.transport());
	}

	private AcpHarnessClient connect(ScriptedAcpAgent agent, AcpHarnessSettings settings,
			org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher) {
		return AcpHarnessClientFactory.connect(new ScriptedRuntime(), settings, agent.transport(),
				org.springaicommunity.acp.harness.observation.AcpHarnessObservations.NONE, watcher);
	}

	/** A watcher over a log written by hand, standing in for one the agent wrote. */
	private org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher(java.nio.file.Path logs) {
		org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher = new org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher(
				logs, line -> line.startsWith("FAILED ") ? org.springaicommunity.acp.harness.runtime.AcpHarnessNotice
					.warning(line.substring(7).split(" ")[0], line) : null);
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

			assertThat(client.openSession("s").advertised().select("model", "model")).isNotNull()
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
			public String authMethod(AcpHarnessSettings settings) {
				return method;
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

			assertThat(client.session("s")).isNotNull()
				.satisfies(session -> assertThat(session.advertised().configOptions()).hasSize(1));
			assertThat(agent.configSets()).containsExactly(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aSessionUpdateVariantThisSdkCannotModelIsSkippedRatherThanFatal() {
		// An update kind newer than the SDK reads as UnknownSessionUpdate, which the
		// event mapper drops, so the turn is unaffected.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().emitsUnknownUpdate(true).reply("one", "two").build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			List<AcpHarnessEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events).containsExactly(new AcpHarnessEvent.Text("one"), new AcpHarnessEvent.Text("two"),
					new AcpHarnessEvent.Completed(AcpSchema.StopReason.END_TURN));
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
			assertThat(client.session("s")).isNotNull()
				.satisfies(session -> assertThat(session.configuration().of(PortableOption.MODEL).mechanism())
					.isEqualTo(Mechanism.UNSUPPORTED));
		}
	}

	@Test
	void onUnsupportedFailStopsTheTurnBeforeItStarts() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").build();
				AcpHarnessClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.prompt("hi").call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);
			assertThat(agent.prompts()).isZero();
		}
	}

	@Test
	void aModelOfferedOnlyInTheLegacyModelsStateIsUnsupported() {
		// session/set_model never became stable ACP, and acp-core 0.80.0 cannot send it.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().models("a", "b").build();
				AcpHarnessClient client = connect(agent,
						settings().model("b").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.prompt("hi").call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);
			assertThat(agent.methods()).doesNotContain("session/set_model");
			assertThat(agent.prompts()).isZero();
		}
	}

	@Test
	void setModeIsUsedWhenTheAgentOffersModesButNoModeOption() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().modes("auto", "plan").build();
				AcpHarnessClient client = connect(agent, settings().mode("plan").build())) {

			client.prompt().session("s").user("hi").call();

			assertThat(agent.methods()).contains("session/set_mode");
			assertThat(client.session("s")).isNotNull()
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
			assertThat(client.session("s")).isNotNull()
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
			return org.springaicommunity.acp.harness.mcp.McpCredentials
				.bearer(() -> "token-" + (principal == null ? "nobody" : principal.name()));
		};
	}

	private AcpHarnessSettings.Builder withProtectedServer(
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
		org.springaicommunity.acp.harness.session.SessionPrincipalResolver resolver = () -> signedIn.get() == null
				? null : org.springaicommunity.acp.harness.session.SessionPrincipal.of(signedIn.get());

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient client = connect(agent,
						withProtectedServer(tokensPerUser(askedFor), resolver).build())) {

			signedIn.set("alice");
			AcpHarnessClient.AcpHarnessStream stream = client.prompt().session("s").user("hi").stream();
			signedIn.remove();

			StepVerifier
				.create(stream.events()
					.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
					.filter(AcpHarnessEvent.Completed.class::isInstance))
				.expectNextCount(1)
				.verifyComplete();

			assertThat(askedFor)
				.containsExactly(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
			assertThat(client.session("s")).isNotNull()
				.extracting(org.springaicommunity.acp.harness.session.AcpHarnessSession::principal)
				.isEqualTo(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void theSamePathsHoldThroughThePool() {
		List<org.springaicommunity.acp.harness.session.SessionPrincipal> askedFor = new java.util.concurrent.CopyOnWriteArrayList<>();
		ThreadLocal<String> signedIn = new ThreadLocal<>();
		org.springaicommunity.acp.harness.session.SessionPrincipalResolver resolver = () -> signedIn.get() == null
				? null : org.springaicommunity.acp.harness.session.SessionPrincipal.of(signedIn.get());
		AcpHarnessSettings settings = withProtectedServer(tokensPerUser(askedFor), resolver).build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient pool = new org.springaicommunity.acp.harness.client.AcpHarnessClientPool("scripted",
						settings, () -> connect(agent, settings))) {

			signedIn.set("alice");
			AcpHarnessClient.AcpHarnessStream stream = pool.prompt().session("s").user("hi").stream();
			signedIn.remove();
			StepVerifier
				.create(stream.events()
					.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
					.filter(AcpHarnessEvent.Completed.class::isInstance))
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
			assertThat(client.session("a")).isNotNull()
				.extracting(org.springaicommunity.acp.harness.session.AcpHarnessSession::principal)
				.isEqualTo(org.springaicommunity.acp.harness.session.SessionPrincipal.of("alice"));
		}
	}

	@Test
	void anAdaptersMcpWorkaroundPutsEveryHttpServerBehindTheProxyEvenWithoutCredentials() throws Exception {
		AcpHarnessRuntime withWorkaround = new ScriptedRuntime() {
			@Override
			public List<org.springaicommunity.acp.harness.mcp.McpRequestFilter> mcpRequestFilters(
					AcpHarnessSettings settings) {
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
			assertThat(client.session("s")).isNull();
		}
	}

	// --- a throwaway turn that fails before it starts
	// ----------------------------------------------------------

	@Test
	void aThrowawayTurnThatCannotBeConfiguredLeavesNoSessionBehind() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.select("model", "model", "a", "a")
			.sessionOperations("close")
			.build();
				AcpHarnessClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.prompt("hi").call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(client.sessions().open()).isEmpty();
			assertThat(agent.methods()).contains("session/close");
		}
	}

	@Test
	void aThrowawayTurnThatCannotBeConfiguredReleasesItsMcpRoute() throws Exception {
		AcpHarnessSettings settings = withProtectedServer(
				tokensPerUser(new java.util.concurrent.CopyOnWriteArrayList<>()), null)
			.model("made-up")
			.onUnsupported(OnUnsupported.FAIL)
			.build();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").build();
				AcpHarnessClient client = connect(agent, settings)) {

			assertThatThrownBy(() -> client.prompt("hi").call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(statusOf(declaredUrl(agent.newSessions().get(0)))).isEqualTo(404);
		}
	}

	/**
	 * Each failure took a place in the pool until its TTL, so failures became refusals.
	 */
	@Test
	void failedThrowawayTurnsDoNotFillThePool() {
		AcpHarnessSettings settings = settings().model("made-up")
			.onUnsupported(OnUnsupported.FAIL)
			.pool(new PoolSettings(1, 2, PoolSettings.DEFAULT_MAX_RESTARTS))
			.build();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").build();
				AcpHarnessClient pool = new AcpHarnessClientPool("scripted", settings,
						() -> connect(agent, settings))) {

			for (int i = 0; i < 4; i++) {
				assertThatThrownBy(() -> pool.prompt("hi").call())
					.isInstanceOf(UnsupportedAcpHarnessOptionException.class);
			}
			assertThat(pool.sessions().open()).isEmpty();
		}
	}

	@Test
	void aNamedSessionSurvivesATurnThatCannotBeConfigured() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.select("model", "model", "a", "a")
			.sessionOperations("close")
			.build(); AcpHarnessClient client = connect(agent, settings().onUnsupported(OnUnsupported.FAIL).build())) {
			client.openSession("kept");

			assertThatThrownBy(() -> client.prompt()
				.session("kept")
				.user("hi")
				.options(options -> options.model("made-up"))
				.call()).isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(client.session("kept")).isNotNull();
			assertThat(agent.methods()).doesNotContain("session/close");
		}
	}

	@Test
	void aNewNamedSessionThatCannotBeConfiguredLeavesNoSessionBehind() throws Exception {
		AcpHarnessSettings settings = withProtectedServer(
				tokensPerUser(new java.util.concurrent.CopyOnWriteArrayList<>()), null)
			.model("made-up")
			.onUnsupported(OnUnsupported.FAIL)
			.build();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.select("model", "model", "a", "a")
			.sessionOperations("close")
			.build(); AcpHarnessClient client = connect(agent, settings)) {

			assertThatThrownBy(() -> client.prompt().session("s").user("hi").call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(client.session("s")).isNull();
			assertThat(statusOf(declaredUrl(agent.newSessions().get(0)))).isEqualTo(404);
			assertThat(agent.methods()).contains("session/close");
		}
	}

	@Test
	void newNamedSessionsThatCannotBeConfiguredDoNotFillThePool() {
		AcpHarnessSettings settings = settings().model("made-up")
			.onUnsupported(OnUnsupported.FAIL)
			.pool(new PoolSettings(1, 2, PoolSettings.DEFAULT_MAX_RESTARTS))
			.build();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").build();
				AcpHarnessClient pool = new AcpHarnessClientPool("scripted", settings,
						() -> connect(agent, settings))) {

			for (int i = 0; i < 4; i++) {
				String name = "conversation-" + i;
				assertThatThrownBy(() -> pool.prompt().session(name).user("hi").call())
					.isInstanceOf(UnsupportedAcpHarnessOptionException.class);
			}
			assertThat(pool.sessions().open()).isEmpty();
		}
	}

	@Test
	void aNameWhoseFirstOpenFailedCanBeRetried() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().select("model", "model", "a", "a").reply("ok").build();
				AcpHarnessClient client = connect(agent, settings().onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(
					() -> client.prompt().session("s").user("hi").options(options -> options.model("made-up")).call())
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(client.prompt().session("s").user("hi").call().content()).isEqualTo("ok");
			assertThat(agent.newSessions()).hasSize(2);
			assertThat(client.sessions().open()).hasSize(1);
		}
	}

	/**
	 * A cancelled turn on {@code boundedElastic} interrupts the thread waiting on
	 * {@code session/new}; the answer still comes, and names a session.
	 */
	@Test
	void aSessionNewAnsweredAfterTheCallerWasInterruptedIsClosedWhenItArrives() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.newSessionDelay(Duration.ofMillis(500))
			.sessionOperations("close")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {
			java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
			Thread caller = new Thread(() -> {
				Thread.currentThread().interrupt();
				try {
					client.openSession("s");
				}
				catch (RuntimeException ex) {
					failure.set(ex);
				}
			});
			caller.start();
			caller.join();

			assertThat(failure.get()).isInstanceOf(AcpHarnessClientException.class).hasMessageContaining("Interrupted");
			assertThat(client.session("s")).isNull();
			Awaits.until(() -> agent.methods().contains("session/close"), LIMIT);
			assertThat(agent.newSessions()).hasSize(1);
		}
	}

	/**
	 * The SDK drops an answer that comes after its timeout, so no close can follow. The
	 * timeout bounds every request, the handshake inside {@code connect} included, so it
	 * is kept well above what a cold JVM needs for those.
	 */
	@Test
	void aSessionNewNotAnsweredInTimeFailsAsAClientError() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().newSessionDelay(Duration.ofSeconds(3)).build();
				AcpHarnessClient client = connect(agent, settings().timeout(Duration.ofSeconds(1)).build())) {

			assertThatThrownBy(() -> client.openSession("s")).isInstanceOf(AcpHarnessClientException.class)
				.hasMessageContaining("did not answer session/new");
			assertThat(client.session("s")).isNull();
		}
	}

	/**
	 * It must not take down the session of the subscriber that is mid-turn. acp-core
	 * delivers the answer to {@code session/new} on its own thread, so the interrupt
	 * lands in the wait for that answer: the second subscriber gives up on the open, and
	 * the session the agent creates anyway is closed when its answer arrives.
	 */
	@Test
	void aSecondSubscriberThatFailsLeavesTheFirstTurnAlone() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.reply("ok")
			.promptDelay(Duration.ofSeconds(2))
			.sessionOperations("close")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {
			AcpHarnessClient.AcpHarnessStream stream = client.prompt("hi").stream();
			java.util.concurrent.CompletableFuture<Void> first = new java.util.concurrent.CompletableFuture<>();
			stream.events().subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe(event -> {
			}, first::completeExceptionally, () -> first.complete(null));
			Awaits.until(() -> agent.prompts() == 1, LIMIT);

			java.util.concurrent.atomic.AtomicReference<Throwable> secondFailure = new java.util.concurrent.atomic.AtomicReference<>();
			Thread second = new Thread(() -> {
				Thread.currentThread().interrupt();
				try {
					stream.events().blockLast();
				}
				catch (RuntimeException ex) {
					secondFailure.set(ex);
				}
			});
			second.start();
			second.join();

			assertThat(secondFailure.get()).isNotNull();
			Awaits.until(() -> agent.newSessions().size() == 2, LIMIT);
			Awaits.until(() -> agent.methods().contains("session/close"), LIMIT);
			assertThat(first).isNotDone();
			assertThat(client.sessions().open()).hasSize(1);
			first.get(10, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * A server answering many users passes zero, so that a second turn on a busy session
	 * fails at once instead of holding a thread for as long as the first one runs.
	 */
	@Test
	void aBusySessionFailsAtOnceForACallerThatWillNotWait() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").promptDelay(Duration.ofSeconds(2)).build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			assertBusySessionFailsAtOnce(agent, client);
		}
	}

	@Test
	void aBusySessionFailsAtOnceThroughThePool() throws Exception {
		AcpHarnessSettings settings = settings().build();
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").promptDelay(Duration.ofSeconds(2)).build();
				AcpHarnessClient pool = new AcpHarnessClientPool("scripted", settings,
						() -> connect(agent, settings))) {
			assertBusySessionFailsAtOnce(agent, pool);
		}
	}

	private static void assertBusySessionFailsAtOnce(ScriptedAcpAgent agent, AcpHarnessClient client) throws Exception {
		java.util.concurrent.CompletableFuture<AcpHarnessClient.AcpHarnessResponse> first = java.util.concurrent.CompletableFuture
			.supplyAsync(() -> client.prompt().session("shared").user("one").call());
		Awaits.until(() -> agent.prompts() == 1, LIMIT);

		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(1),
				() -> assertThatThrownBy(
						() -> client.prompt().session("shared").user("two").waitIfBusy(Duration.ZERO).call())
					.isInstanceOf(AcpHarnessSessionBusyException.class));

		assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).content()).isEqualTo("ok");
		assertThat(agent.prompts()).isEqualTo(1);
	}

	@Test
	void aNegativeWaitIsRefused() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			assertThatThrownBy(() -> client.prompt().waitIfBusy(Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	/**
	 * Without this, a caller that starts the next turn as soon as it hears this one ended
	 * races the turn's cleanup, which runs after the caller's onComplete: harmless while
	 * every caller waited for a busy session, a spurious failure for one that does not.
	 * The sleep is for the negative half: the first turn's cleanup runs on the thread
	 * that delivered its completion, right after this test saw it, and must not release
	 * the turn the test began in the meantime.
	 */
	@Test
	void aSessionIsFreeBeforeItsCallerHearsTheTurnEnded() throws Exception {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("ok").build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			java.util.concurrent.atomic.AtomicBoolean freeOnCompletion = new java.util.concurrent.atomic.AtomicBoolean();

			client.prompt().session("shared").user("one").stream().events().doOnComplete(() -> {
				try {
					freeOnCompletion.set(client.session("shared").tryBeginTurn(Duration.ZERO));
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}).blockLast(LIMIT);

			assertThat(freeOnCompletion).isTrue();
			AcpHarnessSession session = client.session("shared");
			Thread.sleep(200);
			assertThat(session.tryBeginTurn(Duration.ZERO)).isFalse();
			session.endTurn();
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
		AcpHarnessSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofSeconds(2)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher = watcher(logs);
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
		AcpHarnessSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("done").build();
				org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher = watcher(logs);
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
				org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher = watcher(logs);
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
		AcpHarnessSettings settings = settings().mcpServers(List.of(server))
			.mcp(new org.springaicommunity.acp.harness.config.McpSettings(
					org.springaicommunity.acp.harness.config.McpSettings.OnServerFailure.FAIL, Duration.ofMillis(250)))
			.build();

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("done").build();
				org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher watcher = watcher(logs);
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

			List<AcpHarnessEvent> events = client.prompt("hi").stream().events().collectList().block(LIMIT);

			assertThat(events)
				.anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(AcpHarnessEvent.ToolCallStarted.class,
						started -> assertThat(started.title()).isEqualTo("developer__shell")));
			assertThat(events).last().isInstanceOf(AcpHarnessEvent.Completed.class);
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
	void aPerTurnTimeoutCanBeLongerThanTheClientTimeout() {
		// The client timeout bounds every other request, but not session/prompt (#25).
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.reply("done")
			.promptDelay(Duration.ofSeconds(2))
			.build(); AcpHarnessClient client = connect(agent, settings().timeout(Duration.ofSeconds(1)).build())) {

			List<AcpHarnessEvent> events = client.prompt("hi")
				.options(options -> options.timeout(Duration.ofSeconds(10)))
				.stream()
				.events()
				.collectList()
				.block(LIMIT);

			assertThat(events).last().isEqualTo(new AcpHarnessEvent.Completed(AcpSchema.StopReason.END_TURN));
		}
	}

	@Test
	void aTurnThatTimesOutSendsSessionCancel() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().promptDelay(Duration.ofSeconds(30)).build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			StepVerifier.create(
					client.prompt("hi").options(options -> options.timeout(Duration.ofMillis(500))).stream().events())
				.thenConsumeWhile(event -> !(event instanceof AcpHarnessEvent.Failed))
				.expectNextMatches(AcpHarnessEvent.Failed.class::isInstance)
				.expectComplete()
				.verify(LIMIT);

			Awaits.until(() -> agent.cancellations() == 1, LIMIT);
		}
	}

	// --- what follows a turn that ended early (#26, #27)
	// ------------------------------------

	/**
	 * An agent winding down the way goose, OpenCode and Codex do: still sending after
	 * {@code session/cancel}, then answering {@code cancelled}.
	 */
	private static ScriptedAcpAgent windingDownAgent() {
		return ScriptedAcpAgent.builder()
			.reply("answer")
			.promptDelay(Duration.ofSeconds(1))
			.windsDownAfterCancel(Duration.ofMillis(300), "late, from the first turn")
			.build();
	}

	@Test
	void theNextTurnDoesNotReceiveWhatATimedOutTurnSentAfterItsCancel() {
		try (ScriptedAcpAgent agent = windingDownAgent();
				AcpHarnessClient client = connect(agent, settings().build())) {

			List<AcpHarnessEvent> first = client.prompt()
				.session("s")
				.user("one")
				.options(options -> options.timeout(Duration.ofMillis(200)))
				.stream()
				.events()
				.collectList()
				.block(LIMIT);
			List<AcpHarnessEvent> second = client.prompt()
				.session("s")
				.user("two")
				.stream()
				.events()
				.collectList()
				.block(LIMIT);

			assertThat(first).last().isInstanceOf(AcpHarnessEvent.Failed.class);
			assertThat(second).containsExactly(new AcpHarnessEvent.Text("answer"),
					new AcpHarnessEvent.Completed(AcpSchema.StopReason.END_TURN));
			assertThat(agent.overlappingPrompts()).as("prompts sent while the agent was still finishing one").isZero();
		}
	}

	@Test
	void theNextTurnDoesNotReceiveWhatACancelledTurnSentAfterItsCancel() {
		try (ScriptedAcpAgent agent = windingDownAgent();
				AcpHarnessClient client = connect(agent, settings().build())) {

			client.prompt()
				.session("s")
				.user("one")
				.stream()
				.events()
				.takeUntil(AcpHarnessEvent.Text.class::isInstance)
				.blockLast(LIMIT);
			List<AcpHarnessEvent> second = client.prompt()
				.session("s")
				.user("two")
				.stream()
				.events()
				.collectList()
				.block(LIMIT);

			assertThat(second).containsExactly(new AcpHarnessEvent.Text("answer"),
					new AcpHarnessEvent.Completed(AcpSchema.StopReason.END_TURN));
			assertThat(agent.overlappingPrompts()).isZero();
		}
	}

	@Test
	void aPermissionQuestionStillOpenWhenItsTurnTimesOutIsAnsweredCancelled() throws InterruptedException {
		// A person who has not answered by the time the turn gives up, and who would
		// answer "allow" once they are interrupted.
		CountDownLatch interrupted = new CountDownLatch(1);
		PermissionPrompt person = question -> {
			try {
				new CountDownLatch(1).await();
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
			}
			return question.options().getFirst();
		};

		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.asksPermission("developer__shell")
			.windsDownAfterCancel(Duration.ZERO)
			.build();
				AcpHarnessClient client = connect(agent,
						settings().permissions(PermissionPolicy.ask(person)).build())) {

			List<AcpHarnessEvent> events = client.prompt("hi")
				.options(options -> options.timeout(Duration.ofMillis(500)))
				.stream()
				.events()
				.collectList()
				.block(LIMIT);

			assertThat(events).last().isInstanceOf(AcpHarnessEvent.Failed.class);
			Awaits.until(() -> !agent.permissionAnswers().isEmpty(), LIMIT);
			assertThat(agent.permissionAnswers()).containsExactly("cancelled");
			assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the person was told the question is gone").isTrue();
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
		// session/close is gated by sessionCapabilities.close, which acp-core enforces.
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().sessionOperations("close").build();
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

			AcpHarnessSessions sessions = client.sessions();

			assertThat(sessions.supports(AcpHarnessSessions.Operation.LIST)).isFalse();
			assertThatThrownBy(sessions::list).isInstanceOf(UnsupportedAcpHarnessOperationException.class);
			assertThatThrownBy(() -> sessions.load("a", "sid-1"))
				.isInstanceOf(UnsupportedAcpHarnessOperationException.class);
			assertThatThrownBy(() -> sessions.delete("sid-1"))
				.isInstanceOf(UnsupportedAcpHarnessOperationException.class);
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
			assertThat(client.sessions().supports(AcpHarnessSessions.Operation.LIST)).isTrue();
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
			assertThat(client.session("restored")).isNotNull()
				.extracting(session -> session.configuration().of(PortableOption.MODEL).applied())
				.isEqualTo("b");
			assertThat(agent.configSets()).contains(Map.of("configId", "model", "value", "b"));
		}
	}

	@Test
	void aLoadedSessionThatCannotBeConfiguredIsClosedOnTheAgent() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("load", "close")
			.storedSessions("sid-1")
			.select("model", "model", "a", "a")
			.build();
				AcpHarnessClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.sessions().load("restored", "sid-1"))
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(agent.loaded()).containsExactly("sid-1");
			assertThat(client.session("restored")).isNull();
			assertThat(agent.methods()).contains("session/close");
		}
	}

	@Test
	void aResumedSessionThatCannotBeConfiguredIsClosedOnTheAgent() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("resume", "close")
			.storedSessions("sid-1")
			.select("model", "model", "a", "a")
			.build();
				AcpHarnessClient client = connect(agent,
						settings().model("made-up").onUnsupported(OnUnsupported.FAIL).build())) {

			assertThatThrownBy(() -> client.sessions().resume("restored", "sid-1"))
				.isInstanceOf(UnsupportedAcpHarnessOptionException.class);

			assertThat(client.session("restored")).isNull();
			assertThat(agent.methods()).contains("session/resume", "session/close");
		}
	}

	/** Nothing was attached, so there is nothing on the agent to close. */
	@Test
	void aLoadTheAgentRefusesSendsNoClose() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder()
			.sessionOperations("load", "close")
			.storedSessions("sid-1")
			.build(); AcpHarnessClient client = connect(agent, settings().build())) {

			assertThatThrownBy(() -> client.sessions().load("restored", "sid-2")).isInstanceOf(RuntimeException.class);

			assertThat(client.session("restored")).isNull();
			assertThat(agent.methods()).doesNotContain("session/close");
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
			assertThat(client.session("restored")).isNull();
		}
	}

	@Test
	void theAgentsOwnVersionSurvivesTheHandshake() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().agentInfo("pretend", "9.9.9").build();
				AcpHarnessClient client = connect(agent, settings().build())) {

			assertThat(client.harnessInfo()).isNotNull().hasToString("pretend 9.9.9");
		}
	}

	/** A runtime that names no agent: only the core is under test here. */
	// --- what the agent sends while no turn is running
	// ------------------------------------------------

	/**
	 * OpenCode 2.3 writes to a session nobody prompted when a background subagent reports
	 * back. That reaches whoever listens to the session, and the turn before it keeps
	 * only its own text.
	 */
	@Test
	void anUpdateSentBetweenTurnsReachesTheSessionsListeners() throws InterruptedException {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("Started.").build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			String answer = client.prompt().session("s").user("start a subagent").call().content();
			String sessionId = client.session("s").sessionId();
			List<AcpHarnessEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
			CountDownLatch arrived = new CountDownLatch(1);
			reactor.core.Disposable listening = client.unprompted("s").subscribe(event -> {
				heard.add(event);
				arrived.countDown();
			});

			agent.speak(sessionId, "DONE");

			assertThat(arrived.await(LIMIT.toSeconds(), TimeUnit.SECONDS)).as("the update arrived").isTrue();
			listening.dispose();
			assertThat(answer).isEqualTo("Started.");
			assertThat(heard).containsExactly(new AcpHarnessEvent.Text("DONE"));
		}
	}

	@Test
	void aTurnsOwnUpdatesDoNotReachTheSessionsListeners() throws InterruptedException {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().reply("one", "two").build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			client.openSession("s");
			List<AcpHarnessEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
			CountDownLatch arrived = new CountDownLatch(1);
			reactor.core.Disposable listening = client.unprompted("s").subscribe(event -> {
				heard.add(event);
				arrived.countDown();
			});

			client.prompt().session("s").user("hi").call();
			agent.speak(client.session("s").sessionId(), "later");

			assertThat(arrived.await(LIMIT.toSeconds(), TimeUnit.SECONDS)).as("the update arrived").isTrue();
			listening.dispose();
			assertThat(heard).containsExactly(new AcpHarnessEvent.Text("later"));
		}
	}

	@Test
	void listeningEndsWhenTheSessionIsClosed() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().sessionOperations("close").build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			client.openSession("s");

			StepVerifier.create(client.unprompted("s"))
				.then(() -> client.sessions().close("s"))
				.expectComplete()
				.verify(LIMIT);
		}
	}

	@Test
	void listeningToASessionThatIsNotOpenFails() {
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			assertThatThrownBy(() -> client.unprompted("never-opened")).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("never-opened");
		}
	}

	@Test
	void onlyTheSessionsOwnerMayListen() {
		org.springaicommunity.acp.harness.session.SessionPrincipal alice = org.springaicommunity.acp.harness.session.SessionPrincipal
			.of("alice");
		try (ScriptedAcpAgent agent = ScriptedAcpAgent.builder().build();
				AcpHarnessClient client = connect(agent, settings().build())) {
			client.openSession("s", alice);

			assertThatThrownBy(
					() -> client.unprompted("s", org.springaicommunity.acp.harness.session.SessionPrincipal.of("bob")))
				.isInstanceOf(org.springaicommunity.acp.harness.session.SessionOwnershipException.class);
			assertThatThrownBy(() -> client.unprompted("s", null))
				.isInstanceOf(org.springaicommunity.acp.harness.session.SessionOwnershipException.class);
			assertThat(client.unprompted("s", alice)).isNotNull();
		}
	}

	private static class ScriptedRuntime implements AcpHarnessRuntime {

		@Override
		public String id() {
			return "scripted";
		}

		@Override
		public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
			throw new UnsupportedOperationException("the scripted agent is already running");
		}

		@Override
		public @Nullable String toolNameOf(AcpSchema.@Nullable ToolCallUpdate toolCall) {
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
