package org.springaicommunity.acp.harness.boot;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springaicommunity.acp.harness.client.AcpHarnessInfo;
import org.springaicommunity.acp.harness.client.AcpHarnessCapacityException;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.client.AcpHarnessSessionBusyException;
import org.springaicommunity.acp.harness.config.AcpHarnessOptions;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.AcpHarnessSessions;
import org.springaicommunity.acp.harness.session.SessionOwnershipException;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.SessionRegistry;
import org.springaicommunity.acp.harness.session.StoredSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The endpoint on a real Netty server, because that is where its failures live.
 *
 * <p>
 * {@code WebTestClient.bindToApplicationContext} never reaches an event loop, so a
 * handler that blocks passes there and fails in production: Reactor refuses to block on a
 * Netty thread. The client here blocks where the real one does and nowhere else, keeps
 * sessions in the real {@link SessionRegistry}, reports failed turns as events, and falls
 * back to a resolved principal when given none, as the real ones do.
 */
class AcpControllerServerTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(CodecsAutoConfiguration.class, WebFluxAutoConfiguration.class,
				AcpWebFluxAutoConfiguration.class))
		.withUserConfiguration(Config.class)
		.withPropertyValues("spring.ai.acp.harness.controller.enabled=true");

	@Test
	@DisplayName("a turn completes on a real server, without holding a thread through call()")
	void promptCompletesOnARealServer() {
		onServer((client, agent) -> prompt(client, "alice", null).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content")
			.isEqualTo("ok")
			.jsonPath("$.stopReason")
			.isEqualTo("END_TURN"));
	}

	@Test
	@DisplayName("a stream completes on a real server")
	void streamCompletesOnARealServer() {
		onServer((client, agent) -> assertThat(events(stream(client, "alice", null))).containsExactly("ok"));
	}

	@Test
	@DisplayName("listing and closing sessions complete on a real server")
	void sessionOperationsCompleteOnARealServer() {
		onServer((client, agent) -> {
			client.get().uri("/api/acp/sessions").header("X-User", "alice").exchange().expectStatus().isOk();
			close(client, "alice", "none").expectStatus().isNoContent();
		});
	}

	@Test
	@DisplayName("the health check stays on the event loop, so busy turns cannot hold it up")
	void healthStaysOnTheEventLoop() {
		onServer((client, agent) -> {
			client.get().uri("/api/acp/health").exchange().expectStatus().isOk();
			assertThat(agent.healthThread).contains("reactor-http");
		});
	}

	@Test
	@DisplayName("a turn runs on behalf of the authenticated caller")
	void passesThePrincipalToTheClient() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			assertThat(agent.owners.values()).containsExactly(SessionPrincipal.of("alice"));
		});
	}

	@Test
	@DisplayName("a repeated name continues the same user's session")
	void continuesTheSameSession() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			assertThat(agent.sessions.size()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("two users choosing the same name get separate sessions")
	void keepsUsersSessionsApart() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			prompt(client, "bob", "pr-1").expectStatus().isOk();
			assertThat(events(stream(client, "bob", "pr-1"))).containsExactly("ok");
			assertThat(agent.owners.values()).containsExactlyInAnyOrder(SessionPrincipal.of("alice"),
					SessionPrincipal.of("bob"));
		});
	}

	@Test
	@DisplayName("closing touches only the caller's session, and never says whether a name is taken")
	void closesOnlyYourOwnSession() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();

			close(client, "bob", "pr-1").expectStatus().isNoContent();
			close(client, "bob", "never-used").expectStatus().isNoContent();
			assertThat(agent.sessions.size()).isEqualTo(1);

			close(client, "alice", "pr-1").expectStatus().isNoContent();
			assertThat(agent.sessions.size()).isZero();
		});
	}

	/**
	 * With no principal the client asks its resolver, which on a laptop answers with the
	 * OS user, so the session has an owner the anonymous caller cannot name.
	 */
	@Test
	@DisplayName("an anonymous caller can close the session they opened")
	void anonymousCallersCloseTheirOwnSessions() {
		runner.withPropertyValues("spring.ai.acp.harness.controller.allow-unauthenticated=true").run(context -> {
			try (Server server = Server.start(context)) {
				BlockingClient agent = context.getBean(BlockingClient.class);
				prompt(server.client(), null, "pr-1").expectStatus().isOk();
				assertThat(agent.owners.values()).containsExactly(BlockingClient.RESOLVED_PRINCIPAL);

				close(server.client(), null, "pr-1").expectStatus().isNoContent();
				assertThat(agent.sessions.size()).isZero();
			}
		});
	}

	@Test
	@DisplayName("an anonymous caller cannot close a signed-in user's session")
	void anonymousCallersCannotCloseOwnedSessions() {
		runner.withPropertyValues("spring.ai.acp.harness.controller.allow-unauthenticated=true").run(context -> {
			try (Server server = Server.start(context)) {
				BlockingClient agent = context.getBean(BlockingClient.class);
				prompt(server.client(), "alice", "pr-1").expectStatus().isOk();

				close(server.client(), null, "pr-1").expectStatus().isNoContent();
				assertThat(agent.sessions.size()).isEqualTo(1);
			}
		});
	}

	@Test
	@DisplayName("listing shows only the caller's sessions, because a title can repeat a prompt")
	void listsOnlyYourOwnSessions() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			prompt(client, "bob", "pr-2").expectStatus().isOk();

			assertThat(sessions(client, "bob")).singleElement()
				.satisfies(session -> assertThat(session.title()).endsWith("-pr-2"));
		});
	}

	@Test
	@DisplayName("listing names each session the way the caller named it, so it can be continued or closed")
	void listsSessionsByTheCallersNames() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-2").expectStatus().isOk();
			prompt(client, "alice", "pr-1").expectStatus().isOk();

			List<AcpController.SessionResponse> listed = sessions(client, "alice");

			assertThat(listed).extracting(AcpController.SessionResponse::name).containsExactly("pr-1", "pr-2");
			assertThat(listed).allSatisfy(session -> {
				assertThat(session.sessionId()).startsWith("id-u-").endsWith("-" + session.name());
				assertThat(session.title()).isEqualTo("title of " + session.sessionId().substring(3));
			});
			close(client, "alice", listed.get(0).name()).expectStatus().isNoContent();
			assertThat(sessions(client, "alice")).extracting(AcpController.SessionResponse::name)
				.containsExactly("pr-2");
		});
	}

	@Test
	@DisplayName("an agent that cannot list sessions still lists the caller's, without titles")
	void listsWithoutAnAgentThatLists() {
		onServer((client, agent) -> {
			agent.lists = false;
			prompt(client, "alice", "pr-1").expectStatus().isOk();

			assertThat(sessions(client, "alice")).singleElement().satisfies(session -> {
				assertThat(session.name()).isEqualTo("pr-1");
				assertThat(session.title()).isNull();
			});
			assertThat(agent.listed).isZero();
		});
	}

	@Test
	@DisplayName("a listing the agent fails costs the titles, not the answer")
	void listsWhenTheAgentsListingFails() {
		onServer((client, agent) -> {
			agent.listFails = true;
			prompt(client, "alice", "pr-1").expectStatus().isOk();

			assertThat(sessions(client, "alice")).singleElement().satisfies(session -> {
				assertThat(session.name()).isEqualTo("pr-1");
				assertThat(session.title()).isNull();
			});
		});
	}

	@Test
	@DisplayName("a caller with nothing open is answered without asking the agent")
	void listsNothingWithoutAskingTheAgent() {
		onServer((client, agent) -> {
			prompt(client, "bob", "pr-1").expectStatus().isOk();

			assertThat(sessions(client, "alice")).isEmpty();
			assertThat(agent.listed).isZero();
		});
	}

	/**
	 * Waiting would hold a thread on the scheduler that answers every running agent's
	 * permission requests, so the fake fails the test if the endpoint is willing to.
	 */
	@Test
	@DisplayName("a session busy with another turn is a 409 at once, on both endpoints")
	void refusesABusySessionAtOnce() {
		onServer((client, agent) -> {
			prompt(client, "alice", "busy").expectStatus().isEqualTo(HttpStatus.CONFLICT);
			stream(client, "alice", "busy").expectStatus().isEqualTo(HttpStatus.CONFLICT);
		});
	}

	@Test
	@DisplayName("a turn that fails is a 502 that does not repeat the agent's words")
	void reportsAFailedTurnWithoutItsDetails() {
		onServer((client, agent) -> {
			String body = prompt(client, "alice", "failed").expectStatus()
				.isEqualTo(HttpStatus.BAD_GATEWAY)
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();
			assertThat(body).contains("The agent did not complete the turn").doesNotContain("secret");
		});
	}

	@Test
	@DisplayName("a turn that cannot start is a 502 on both endpoints")
	void reportsAClientFailure() {
		onServer((client, agent) -> {
			prompt(client, "alice", "boom").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
			stream(client, "alice", "boom").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
		});
	}

	@Test
	@DisplayName("a turn with no terminal event is a 502, not an answer")
	void reportsAMissingTerminalEvent() {
		onServer((client, agent) -> prompt(client, "alice", "no-terminal").expectStatus()
			.isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	@DisplayName("a stream that fails before any text is a 502")
	void reportsAFailedStreamBeforeAnyText() {
		onServer((client, agent) -> stream(client, "alice", "failed").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	@DisplayName("a stream that fails after some text does not end as if it had finished")
	void abortsAStreamThatFailsPartWay() {
		onServer((client, agent) -> {
			WebTestClient.ResponseSpec response = stream(client, "alice", "failed-late");
			assertThatThrownBy(() -> events(response)).isNotNull();
		});
	}

	@Test
	@DisplayName("being at capacity is a 503 on both endpoints, not an agent failure")
	void reportsCapacitySeparately() {
		onServer((client, agent) -> {
			prompt(client, "alice", "full").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			stream(client, "alice", "full").expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		});
	}

	/** Only reachable when a session was opened outside the endpoint under that name. */
	@Test
	@DisplayName("a session held by someone else is still refused")
	void refusesASessionHeldBySomeoneElse() {
		onServer((client, agent) -> {
			prompt(client, "alice", "taken").expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
			stream(client, "alice", "taken").expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		});
	}

	/**
	 * Asserting that nothing was opened is the point: past this check the pool assigns
	 * the name to a process, and may restart one for it.
	 */
	@Test
	@DisplayName("an invalid session name is a 400, before the agent is involved")
	void rejectsInvalidSessionNames() {
		onServer((client, agent) -> {
			prompt(client, "alice", "bad name!").expectStatus().isBadRequest();
			stream(client, "alice", "bad name!").expectStatus().isBadRequest();
			client.delete()
				.uri("/api/acp/sessions/{name}", "bad name!")
				.header("X-User", "alice")
				.exchange()
				.expectStatus()
				.isBadRequest();
			assertThat(agent.opened).isZero();
		});
	}

	@Test
	@DisplayName("a session name leaves room for the caller's scope")
	void limitsSessionNameLength() {
		onServer((client, agent) -> {
			prompt(client, "alice", "x".repeat(AcpController.MAX_SESSION_NAME_LENGTH)).expectStatus().isOk();
			prompt(client, "alice", "x".repeat(AcpController.MAX_SESSION_NAME_LENGTH + 1)).expectStatus()
				.isBadRequest();
		});
	}

	private void onServer(ServerTest test) {
		runner.run(context -> {
			try (Server server = Server.start(context)) {
				test.run(server.client(), context.getBean(BlockingClient.class));
			}
		});
	}

	static List<AcpController.SessionResponse> sessions(WebTestClient client, String user) {
		return client.get()
			.uri("/api/acp/sessions")
			.header("X-User", user)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(new ParameterizedTypeReference<List<AcpController.SessionResponse>>() {
			})
			.returnResult()
			.getResponseBody();
	}

	static WebTestClient.ResponseSpec prompt(WebTestClient client, String user, String session) {
		return post(client, "/api/acp/prompt", user, session);
	}

	static WebTestClient.ResponseSpec stream(WebTestClient client, String user, String session) {
		return post(client, "/api/acp/stream", user, session);
	}

	static List<String> events(WebTestClient.ResponseSpec response) {
		return response.expectStatus()
			.isOk()
			.returnResult(String.class)
			.getResponseBody()
			.collectList()
			.block(Duration.ofSeconds(10));
	}

	private static WebTestClient.ResponseSpec post(WebTestClient client, String path, String user, String session) {
		String body = session == null ? "{\"prompt\":\"hi\"}" : "{\"prompt\":\"hi\",\"session\":\"" + session + "\"}";
		WebTestClient.RequestBodySpec request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
		if (user != null) {
			request = request.header("X-User", user);
		}
		return request.bodyValue(body).exchange();
	}

	static WebTestClient.ResponseSpec close(WebTestClient client, String user, String session) {
		WebTestClient.RequestHeadersSpec<?> request = client.delete().uri("/api/acp/sessions/{name}", session);
		if (user != null) {
			request = request.header("X-User", user);
		}
		return request.exchange();
	}

	/** Stands in for Spring Security: the request says who it is. */
	static WebFilter principalFromHeader() {
		return (exchange, chain) -> {
			String user = exchange.getRequest().getHeaders().getFirst("X-User");
			if (user == null) {
				return chain.filter(exchange);
			}
			Principal principal = () -> user;
			return chain.filter(exchange.mutate().principal(Mono.just(principal)).build());
		};
	}

	/** What the real client does on the way to the agent. */
	private static void blockLikeTheRealClient() {
		Mono.delay(Duration.ofMillis(1)).block();
	}

	@FunctionalInterface
	private interface ServerTest {

		void run(WebTestClient client, BlockingClient agent);

	}

	record Server(DisposableServer netty, WebTestClient client) implements AutoCloseable {

		static Server start(ApplicationContext context) {
			DisposableServer netty = HttpServer.create()
				.host("127.0.0.1")
				.port(0)
				.handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build()))
				.bindNow();
			WebTestClient client = WebTestClient.bindToServer()
				.baseUrl("http://127.0.0.1:" + netty.port())
				.responseTimeout(Duration.ofSeconds(10))
				.build();
			return new Server(netty, client);
		}

		@Override
		public void close() {
			this.netty.disposeNow();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AcpProperties.class)
	static class Config {

		@Bean
		BlockingClient acpHarnessClient() {
			return new BlockingClient();
		}

		@Bean
		WebFilter principalFromHeader() {
			return AcpControllerServerTests.principalFromHeader();
		}

	}

	/**
	 * Behaves like the real client where the endpoint can tell: opening a session and
	 * closing one block, reading its identity and liveness does not, a turn's failure is
	 * an event, and no principal means asking the resolver. {@code call()} throws,
	 * because a turn must not hold a thread for its whole length.
	 */
	static final class BlockingClient implements AcpHarnessClient {

		/** What a resolver answers with on a laptop: the OS user. */
		static final SessionPrincipal RESOLVED_PRINCIPAL = SessionPrincipal.of("os-user");

		final SessionRegistry sessions = new SessionRegistry();

		final Map<String, SessionPrincipal> owners = new ConcurrentHashMap<>();

		volatile int opened;

		volatile int listed;

		volatile boolean lists = true;

		volatile boolean listFails;

		volatile String healthThread;

		@Override
		public PromptSpec prompt() {
			return new Spec();
		}

		@Override
		public String runtimeId() {
			return "fake";
		}

		@Override
		public @Nullable AcpHarnessInfo harnessInfo() {
			this.healthThread = Thread.currentThread().getName();
			return new AcpHarnessInfo("fake", "1.0");
		}

		@Override
		public AcpHarnessSessions sessions() {
			return new Sessions();
		}

		@Override
		public @Nullable AcpHarnessSession session(String name) {
			return this.sessions.find(name);
		}

		@Override
		public AcpHarnessSession openSession(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}

		private void open(String name, SessionPrincipal principal, @Nullable Duration busyWait) {
			blockLikeTheRealClient();
			this.opened++;
			if (name == null) {
				return;
			}
			if (name.endsWith("-busy")) {
				if (!Duration.ZERO.equals(busyWait)) {
					throw new AssertionError("waited " + busyWait + " for a busy session, holding a thread");
				}
				throw new AcpHarnessSessionBusyException("Session '" + name + "' is busy with another turn");
			}
			if (name.endsWith("-boom")) {
				throw new AcpHarnessClientException("secret the agent read");
			}
			if (name.endsWith("-full")) {
				throw new AcpHarnessCapacityException("no process can take this session");
			}
			if (name.endsWith("-taken")) {
				throw new SessionOwnershipException(name);
			}
			SessionPrincipal owner = principal != null ? principal : RESOLVED_PRINCIPAL;
			this.sessions.resolve(name, owner, n -> new SessionRegistry.Opened("id-" + n, null));
			this.owners.put(name, owner);
		}

		private final class Spec implements PromptSpec {

			private String name;

			private SessionPrincipal principal;

			private @Nullable Duration busyWait;

			@Override
			public PromptSpec waitIfBusy(@Nullable Duration wait) {
				this.busyWait = wait;
				return this;
			}

			@Override
			public PromptSpec session(String name) {
				this.name = name;
				return this;
			}

			@Override
			public PromptSpec user(String text) {
				return this;
			}

			@Override
			public PromptSpec options(AcpHarnessOptions options) {
				return this;
			}

			@Override
			public PromptSpec options(Consumer<AcpHarnessOptions.Builder> customizer) {
				return this;
			}

			@Override
			public PromptSpec principal(SessionPrincipal principal) {
				this.principal = principal;
				return this;
			}

			@Override
			public AcpHarnessResponse call() {
				throw new UnsupportedOperationException("A turn must not hold a thread for its whole length");
			}

			@Override
			public AcpHarnessStream stream() {
				return () -> Flux.defer(() -> {
					open(this.name, this.principal, this.busyWait);
					return Flux.fromIterable(eventsFor(this.name));
				});
			}

			private List<AcpHarnessEvent> eventsFor(String name) {
				AcpHarnessEvent text = new AcpHarnessEvent.Text("ok");
				AcpHarnessEvent failed = new AcpHarnessEvent.Failed(new IllegalStateException("secret the agent read"));
				if (name != null && name.endsWith("-failed")) {
					return List.of(failed);
				}
				if (name != null && name.endsWith("-failed-late")) {
					return List.of(text, failed);
				}
				if (name != null && name.endsWith("-no-terminal")) {
					return List.of(text);
				}
				return List.of(text, new AcpHarnessEvent.Completed(AcpSchema.StopReason.END_TURN));
			}

		}

		private final class Sessions implements AcpHarnessSessions {

			@Override
			public List<StoredSession> list() {
				blockLikeTheRealClient();
				BlockingClient.this.listed++;
				if (BlockingClient.this.listFails) {
					throw new AcpHarnessClientException("listing failed");
				}
				return BlockingClient.this.sessions.all()
					.stream()
					.map(session -> new StoredSession(session.sessionId(), null, "title of " + session.name(), null))
					.toList();
			}

			@Override
			public List<StoredSession> list(Path cwd) {
				return list();
			}

			@Override
			public AcpHarnessSession load(String name, String sessionId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public AcpHarnessSession resume(String name, String sessionId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void delete(String sessionId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void close(String name) {
				blockLikeTheRealClient();
				BlockingClient.this.sessions.remove(name);
			}

			@Override
			public List<AcpHarnessSession> open() {
				return BlockingClient.this.sessions.all();
			}

			@Override
			public @Nullable AcpHarnessSession find(String name) {
				return BlockingClient.this.sessions.find(name);
			}

			@Override
			public boolean supports(Operation operation) {
				return operation != Operation.LIST || BlockingClient.this.lists;
			}

		}

	}

}
