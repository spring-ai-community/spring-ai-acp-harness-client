package org.springaicommunity.acp.harness.boot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.codec.autoconfigure.CodecsAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientFactory;
import org.springaicommunity.acp.harness.client.AcpHarnessClientPool;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.config.PoolSettings;
import org.springaicommunity.acp.harness.runtime.AcpHarnessLaunchSpec;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.test.ScriptedAcpAgent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The endpoint with nothing faked between it and the wire: a real Netty server, the real
 * pool and client, and JSON-RPC to a scripted agent. What
 * {@link AcpControllerServerTests} asserts against a stand-in, this checks holds for the
 * code it stands in for.
 */
class AcpControllerEndToEndTests {

	private static final String NAMED = "{\"prompt\":\"hi\",\"session\":\"%s\"}";

	@TempDir
	Path workspace;

	@Test
	@DisplayName("a prompt and a stream both reach the agent and come back")
	void promptAndStreamReachTheAgent() {
		run(ScriptedAcpAgent.builder().reply("hello"), PoolSettings.defaults(), (client, agent) -> {
			client.post()
				.uri("/api/acp/prompt")
				.header("X-User", "alice")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"prompt\":\"hi\"}")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.content")
				.isEqualTo("hello")
				.jsonPath("$.stopReason")
				.isNotEmpty();
			assertThat(AcpControllerServerTests.events(AcpControllerServerTests.stream(client, "alice", null)))
				.containsExactly("hello");
			assertThat(agent.prompts()).isEqualTo(2);
		});
	}

	@Test
	@DisplayName("two users choosing one name get two sessions on the agent, and each keeps theirs")
	void keepsUsersSessionsApart() {
		run(ScriptedAcpAgent.builder().reply("ok"), PoolSettings.defaults(), (client, agent) -> {
			AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();
			AcpControllerServerTests.prompt(client, "bob", "pr-1").expectStatus().isOk();
			AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();
			assertThat(agent.newSessions()).hasSize(2);
			assertThat(agent.prompts()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("closing reaches the agent for the caller's own session and not for anyone else's")
	void closesOnlyYourOwnSessionOnTheAgent() {
		run(ScriptedAcpAgent.builder().reply("ok").sessionOperations("close"), PoolSettings.defaults(),
				(client, agent) -> {
					AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();

					AcpControllerServerTests.close(client, "bob", "pr-1").expectStatus().isNoContent();
					assertThat(agent.methods()).doesNotContain("session/close");

					AcpControllerServerTests.close(client, "alice", "pr-1").expectStatus().isNoContent();
					assertThat(agent.methods()).contains("session/close");
				});
	}

	@Test
	@DisplayName("listing returns only what the caller has open, by the caller's names, with the agent's titles")
	void listsOnlyYourOwnSessions() {
		run(ScriptedAcpAgent.builder().reply("ok").sessionOperations("list").storedSessions("session-1", "session-2"),
				PoolSettings.defaults(), (client, agent) -> {
					AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();
					AcpControllerServerTests.prompt(client, "bob", "pr-1").expectStatus().isOk();

					assertThat(AcpControllerServerTests.sessions(client, "alice")).singleElement()
						.satisfies(session -> {
							assertThat(session.name()).isEqualTo("pr-1");
							assertThat(session.sessionId()).isEqualTo("session-1");
							assertThat(session.title()).isEqualTo("stored session-1");
						});
				});
	}

	@Test
	@DisplayName("an agent without session/list still lists the caller's sessions")
	void listsWithAnAgentThatCannotList() {
		run(ScriptedAcpAgent.builder().reply("ok"), PoolSettings.defaults(), (client, agent) -> {
			AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();

			assertThat(AcpControllerServerTests.sessions(client, "alice")).singleElement().satisfies(session -> {
				assertThat(session.name()).isEqualTo("pr-1");
				assertThat(session.title()).isNull();
			});
			assertThat(agent.methods()).doesNotContain("session/list");
		});
	}

	@Test
	@DisplayName("listing with nothing open does not start an agent")
	void listingNothingStartsNoAgent() {
		run(ScriptedAcpAgent.builder().reply("ok").sessionOperations("list"), PoolSettings.defaults(),
				(client, agent) -> {
					assertThat(AcpControllerServerTests.sessions(client, "alice")).isEmpty();
					assertThat(agent.methods()).isEmpty();
				});
	}

	/**
	 * The second turn would otherwise wait for the first on a boundedElastic thread, for
	 * as long as its timeout, and enough of those stall every agent's permission
	 * requests.
	 */
	@Test
	@DisplayName("a second turn on a busy session is a 409 at once, and the first still finishes")
	void refusesABusySessionAtOnce() {
		run(ScriptedAcpAgent.builder().reply("ok").promptDelay(Duration.ofSeconds(2)), PoolSettings.defaults(),
				(client, agent) -> {
					CompletableFuture<Void> first = CompletableFuture
						.runAsync(() -> AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk());
					until(() -> agent.prompts() == 1);

					long started = System.nanoTime();
					AcpControllerServerTests.prompt(client, "alice", "pr-1")
						.expectStatus()
						.isEqualTo(HttpStatus.CONFLICT);
					AcpControllerServerTests.stream(client, "alice", "pr-1")
						.expectStatus()
						.isEqualTo(HttpStatus.CONFLICT);
					assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));

					first.join();
					assertThat(agent.prompts()).isEqualTo(1);
				});
	}

	/**
	 * Refusing a busy session at once is only safe if a session is free by the time its
	 * caller hears the last turn ended; otherwise a caller going turn after turn would
	 * race the previous turn's cleanup and get a 409 now and then.
	 */
	@Test
	@DisplayName("a caller can continue a session as soon as its last turn has answered")
	void continuesASessionTurnAfterTurn() {
		run(ScriptedAcpAgent.builder().reply("ok"), PoolSettings.defaults(), (client, agent) -> {
			for (int turn = 0; turn < 25; turn++) {
				AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();
				assertThat(AcpControllerServerTests.events(AcpControllerServerTests.stream(client, "alice", "pr-1")))
					.containsExactly("ok");
			}
			assertThat(agent.prompts()).isEqualTo(50);
		});
	}

	@Test
	@DisplayName("a turn that times out is a 502 from /prompt, even after some text")
	void promptReportsARealTimeout() {
		run(ScriptedAcpAgent.builder().reply("partial").promptDelay(Duration.ofSeconds(3)), PoolSettings.defaults(),
				(client, agent) -> timingOut(client, "/api/acp/prompt").expectStatus()
					.isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	@DisplayName("a stream that times out before any text is a 502")
	void streamReportsARealTimeoutBeforeAnyText() {
		run(ScriptedAcpAgent.builder().reply().promptDelay(Duration.ofSeconds(3)), PoolSettings.defaults(), (client,
				agent) -> timingOut(client, "/api/acp/stream").expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	/**
	 * The status is already on the wire by then, so the only honest signal left is not to
	 * end the response normally.
	 */
	@Test
	@DisplayName("a stream that times out after some text is aborted, not ended cleanly")
	void streamAbortsOnARealTimeoutAfterText() {
		run(ScriptedAcpAgent.builder().reply("partial").promptDelay(Duration.ofSeconds(3)), PoolSettings.defaults(),
				(client, agent) -> {
					WebTestClient.ResponseSpec response = timingOut(client, "/api/acp/stream");
					assertThatThrownBy(() -> AcpControllerServerTests.events(response)).isNotNull();
				});
	}

	@Test
	@DisplayName("a full pool is a 503 on both endpoints")
	void reportsARealCapacityRefusal() {
		run(ScriptedAcpAgent.builder().reply("ok"), new PoolSettings(1, 1, PoolSettings.DEFAULT_MAX_RESTARTS),
				(client, agent) -> {
					AcpControllerServerTests.prompt(client, "alice", "pr-1").expectStatus().isOk();
					AcpControllerServerTests.prompt(client, "alice", "pr-2")
						.expectStatus()
						.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					AcpControllerServerTests.stream(client, "alice", "pr-2")
						.expectStatus()
						.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				});
	}

	@Test
	@DisplayName("an invalid session name never reaches the agent")
	void rejectsInvalidNamesBeforeTheAgent() {
		run(ScriptedAcpAgent.builder().reply("ok"), PoolSettings.defaults(), (client, agent) -> {
			client.post()
				.uri("/api/acp/prompt")
				.header("X-User", "alice")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(NAMED.formatted("bad name!"))
				.exchange()
				.expectStatus()
				.isBadRequest();
			assertThat(agent.newSessions()).isEmpty();
		});
	}

	@Test
	@DisplayName("the health check answers without starting an agent")
	void healthDoesNotStartAnAgent() {
		run(ScriptedAcpAgent.builder().reply("ok"), PoolSettings.defaults(), (client, agent) -> {
			client.get().uri("/api/acp/health").exchange().expectStatus().isOk();
			assertThat(agent.methods()).isEmpty();
		});
	}

	private static void until(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition was not met within 10s");
			}
			Thread.onSpinWait();
		}
	}

	private static WebTestClient.ResponseSpec timingOut(WebTestClient client, String path) {
		return client.post()
			.uri(path)
			.header("X-User", "alice")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("{\"prompt\":\"hi\",\"timeout\":\"PT0.2S\"}")
			.exchange();
	}

	private void run(ScriptedAcpAgent.Builder builder, PoolSettings pool,
			BiConsumer<WebTestClient, ScriptedAcpAgent> test) {
		AcpHarnessSettings settings = AcpHarnessSettings.builder("scripted", this.workspace)
			.timeout(Duration.ofSeconds(10))
			.pool(pool)
			.build();
		try (ScriptedAcpAgent agent = builder.build();
				AcpHarnessClientPool client = new AcpHarnessClientPool("scripted", settings,
						() -> AcpHarnessClientFactory.connect(new Scripted(), settings, agent.transport()))) {
			new ReactiveWebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(CodecsAutoConfiguration.class, WebFluxAutoConfiguration.class,
						AcpWebFluxAutoConfiguration.class))
				.withUserConfiguration(Properties.class)
				.withBean(AcpHarnessClient.class, () -> client)
				.withBean(WebFilter.class, AcpControllerServerTests::principalFromHeader)
				.withPropertyValues("spring.ai.acp.harness.controller.enabled=true")
				.run(context -> {
					try (AcpControllerServerTests.Server server = AcpControllerServerTests.Server.start(context)) {
						test.accept(server.client(), agent);
					}
				});
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AcpProperties.class)
	static class Properties {

	}

	private static final class Scripted implements AcpHarnessRuntime {

		@Override
		public String id() {
			return "scripted";
		}

		@Override
		public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
			throw new UnsupportedOperationException("the scripted agent is already running");
		}

	}

}
