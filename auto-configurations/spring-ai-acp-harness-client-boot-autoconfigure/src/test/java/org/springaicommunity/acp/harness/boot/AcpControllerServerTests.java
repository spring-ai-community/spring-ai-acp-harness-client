package org.springaicommunity.acp.harness.boot;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import org.springaicommunity.acp.harness.client.AcpAgentInfo;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.config.AcpAgentOptions;
import org.springaicommunity.acp.harness.event.AcpAgentEvent;
import org.springaicommunity.acp.harness.session.AcpAgentSession;
import org.springaicommunity.acp.harness.session.AcpAgentSessions;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.SessionRegistry;
import org.springaicommunity.acp.harness.session.StoredSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint on a real Netty server, because that is where two of its failures live.
 *
 * <p>
 * {@code WebTestClient.bindToApplicationContext} never reaches an event loop, so a
 * handler that blocks passes there and fails in production: Reactor refuses to block on a
 * Netty thread. The client here blocks the way the real one does, and owns its sessions
 * through the real {@link SessionRegistry}, so a request from the wrong user meets the
 * same refusal it would in an application.
 */
class AcpControllerServerTests {

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(CodecsAutoConfiguration.class, WebFluxAutoConfiguration.class,
				AcpWebFluxAutoConfiguration.class))
		.withUserConfiguration(Config.class)
		.withPropertyValues("spring.ai.acp.harness.controller.enabled=true");

	@Test
	@DisplayName("a blocking turn completes on a real server")
	void promptCompletesOnTheEventLoopServer() {
		onServer((client, agent) -> prompt(client, "alice", null).expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.content")
			.isEqualTo("ok"));
	}

	@Test
	@DisplayName("a blocking stream completes on a real server")
	void streamCompletesOnTheEventLoopServer() {
		onServer((client, agent) -> {
			List<String> events = stream(client, "alice", null).expectStatus()
				.isOk()
				.returnResult(String.class)
				.getResponseBody()
				.collectList()
				.block(Duration.ofSeconds(10));
			assertThat(events).containsExactly("ok");
		});
	}

	@Test
	@DisplayName("listing and closing sessions complete on a real server")
	void sessionOperationsCompleteOnTheEventLoopServer() {
		onServer((client, agent) -> {
			client.get().uri("/api/acp/sessions").header("X-User", "alice").exchange().expectStatus().isOk();
			client.delete()
				.uri("/api/acp/sessions/none")
				.header("X-User", "alice")
				.exchange()
				.expectStatus()
				.isNoContent();
		});
	}

	@Test
	@DisplayName("the health check answers on a real server")
	void healthAnswersOnTheEventLoopServer() {
		onServer((client, agent) -> client.get().uri("/api/acp/health").exchange().expectStatus().isOk());
	}

	@Test
	@DisplayName("a failed turn is a 502 that does not repeat the agent's words")
	void reportsAFailedTurnWithoutItsDetails() {
		onServer((client, agent) -> prompt(client, "alice", "boom").expectStatus()
			.isEqualTo(HttpStatus.BAD_GATEWAY)
			.expectBody()
			.jsonPath("$.error")
			.isEqualTo("The agent did not complete the turn")
			.jsonPath("$.requestId")
			.isNotEmpty());
	}

	@Test
	@DisplayName("a turn runs on behalf of the authenticated caller")
	void passesThePrincipalToTheClient() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			assertThat(agent.lastPrincipal).isEqualTo(SessionPrincipal.of("alice"));
		});
	}

	@Test
	@DisplayName("a second user cannot continue another user's session")
	void refusesAnotherUsersSession() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			prompt(client, "bob", "pr-1").expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
			prompt(client, "alice", "pr-1").expectStatus().isOk();
		});
	}

	@Test
	@DisplayName("a second user cannot stream into another user's session")
	void refusesToStreamIntoAnotherUsersSession() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			stream(client, "bob", "pr-1").expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
		});
	}

	@Test
	@DisplayName("a user cannot close another user's session, and can close their own")
	void closesOnlyYourOwnSession() {
		onServer((client, agent) -> {
			prompt(client, "alice", "pr-1").expectStatus().isOk();
			client.delete()
				.uri("/api/acp/sessions/pr-1")
				.header("X-User", "bob")
				.exchange()
				.expectStatus()
				.isEqualTo(HttpStatus.FORBIDDEN);
			assertThat(agent.sessions.knows("pr-1")).isTrue();
			client.delete()
				.uri("/api/acp/sessions/pr-1")
				.header("X-User", "alice")
				.exchange()
				.expectStatus()
				.isNoContent();
			assertThat(agent.sessions.knows("pr-1")).isFalse();
		});
	}

	@Test
	@DisplayName("an anonymous caller cannot close a signed-in user's session")
	void anonymousCallersCannotCloseOwnedSessions() {
		runner.withPropertyValues("spring.ai.acp.harness.controller.allow-unauthenticated=true").run(context -> {
			try (Server server = Server.start(context)) {
				prompt(server.client(), "alice", "pr-1").expectStatus().isOk();
				server.client()
					.delete()
					.uri("/api/acp/sessions/pr-1")
					.exchange()
					.expectStatus()
					.isEqualTo(HttpStatus.FORBIDDEN);
			}
		});
	}

	private void onServer(ServerTest test) {
		runner.run(context -> {
			try (Server server = Server.start(context)) {
				test.run(server.client(), context.getBean(BlockingClient.class));
			}
		});
	}

	private static WebTestClient.ResponseSpec prompt(WebTestClient client, String user, String session) {
		return post(client, "/api/acp/prompt", user, session);
	}

	private static WebTestClient.ResponseSpec stream(WebTestClient client, String user, String session) {
		return post(client, "/api/acp/stream", user, session);
	}

	private static WebTestClient.ResponseSpec post(WebTestClient client, String path, String user, String session) {
		String body = session == null ? "{\"prompt\":\"hi\"}" : "{\"prompt\":\"hi\",\"session\":\"" + session + "\"}";
		return client.post()
			.uri(path)
			.header("X-User", user)
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange();
	}

	/** What the real client does on the way to the agent. */
	private static void blockLikeTheRealClient() {
		Mono.delay(Duration.ofMillis(1)).block();
	}

	@FunctionalInterface
	private interface ServerTest {

		void run(WebTestClient client, BlockingClient agent);

	}

	private record Server(DisposableServer netty, WebTestClient client) implements AutoCloseable {

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

		/** Stands in for Spring Security: the request says who it is. */
		@Bean
		WebFilter principalFromHeader() {
			return (exchange, chain) -> {
				String user = exchange.getRequest().getHeaders().getFirst("X-User");
				if (user == null) {
					return chain.filter(exchange);
				}
				Principal principal = () -> user;
				return chain.filter(exchange.mutate().principal(Mono.just(principal)).build());
			};
		}

	}

	/**
	 * Blocks where the real client blocks, and keeps sessions in the real registry.
	 */
	static final class BlockingClient implements AcpHarnessClient {

		final SessionRegistry sessions = new SessionRegistry();

		volatile SessionPrincipal lastPrincipal;

		@Override
		public PromptSpec prompt() {
			return new Spec();
		}

		@Override
		public String runtimeId() {
			return "fake";
		}

		@Override
		public Optional<AcpAgentInfo> agentInfo() {
			blockLikeTheRealClient();
			return Optional.of(new AcpAgentInfo("fake", "1.0"));
		}

		@Override
		public AcpAgentSessions sessions() {
			return new Sessions();
		}

		@Override
		public Optional<AcpAgentSession> session(String name) {
			return this.sessions.find(name);
		}

		@Override
		public AcpAgentSession openSession(String name) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
		}

		private void open(String name, SessionPrincipal principal) {
			blockLikeTheRealClient();
			BlockingClient.this.lastPrincipal = principal;
			if ("boom".equals(name)) {
				throw new AcpHarnessClientException("secret the agent read");
			}
			if (name != null) {
				this.sessions.resolve(name, principal, n -> new SessionRegistry.Opened("id-" + n, null));
			}
		}

		private final class Spec implements PromptSpec {

			private String name;

			private SessionPrincipal principal;

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
			public PromptSpec options(AcpAgentOptions options) {
				return this;
			}

			@Override
			public PromptSpec options(Consumer<AcpAgentOptions.Builder> customizer) {
				return this;
			}

			@Override
			public PromptSpec principal(SessionPrincipal principal) {
				this.principal = principal;
				return this;
			}

			@Override
			public AcpAgentResponse call() {
				open(this.name, this.principal);
				return new AcpAgentResponse() {

					@Override
					public String content() {
						return "ok";
					}

					@Override
					public AcpAgentEvent.Completed completion() {
						return new AcpAgentEvent.Completed(AcpSchema.StopReason.END_TURN);
					}
				};
			}

			@Override
			public AcpAgentStream stream() {
				return () -> Flux.defer(() -> {
					open(this.name, this.principal);
					return Flux.just(new AcpAgentEvent.Text("ok"),
							new AcpAgentEvent.Completed(AcpSchema.StopReason.END_TURN));
				});
			}

		}

		private final class Sessions implements AcpAgentSessions {

			@Override
			public List<StoredSession> list() {
				blockLikeTheRealClient();
				return List.of();
			}

			@Override
			public List<StoredSession> list(Path cwd) {
				return list();
			}

			@Override
			public AcpAgentSession load(String name, String sessionId) {
				throw new UnsupportedOperationException();
			}

			@Override
			public AcpAgentSession resume(String name, String sessionId) {
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
			public List<AcpAgentSession> open() {
				return BlockingClient.this.sessions.all();
			}

			@Override
			public Optional<AcpAgentSession> find(String name) {
				return BlockingClient.this.sessions.find(name);
			}

			@Override
			public boolean supports(Operation operation) {
				return true;
			}

		}

	}

}
