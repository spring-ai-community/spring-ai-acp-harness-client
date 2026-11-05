package org.springaicommunity.acp.harness.boot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springaicommunity.acp.harness.client.AcpHarnessCapacityException;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.config.AcpHarnessOptions;
import org.springaicommunity.acp.harness.config.Validation;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.SessionOwnershipException;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.StoredSession;
import org.springaicommunity.acp.harness.session.UnsupportedAcpHarnessOperationException;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * An optional HTTP face for the agent, off unless
 * {@code spring.ai.acp.harness.controller.enabled} says otherwise.
 *
 * <p>
 * Opt-in because exposing an agent over HTTP is not a small decision: a caller who
 * reaches this endpoint can spend the application's model budget and can make the agent
 * act on the application's workspace, with the application's credentials. So the defaults
 * are the restrictive ones — authentication required, per-request model and provider
 * overrides refused, prompt length and timeout bounded — and each can only be relaxed
 * deliberately.
 *
 * <p>
 * Credentials and endpoints are fixed when the agent process starts and are not reachable
 * from here at all. What a request may vary is the session it runs in and, if the
 * application allows it, the negotiated options; there is no path by which a request
 * supplies an API key.
 *
 * <p>
 * Put it behind Spring Security or the platform's own SSO. {@code allow-unauthenticated}
 * exists for a developer running it on a laptop and says so in its name.
 */
@RestController
public class AcpController {

	private static final Logger logger = LoggerFactory.getLogger(AcpController.class);

	private static final String TURN_FAILED = "The agent did not complete the turn";

	private static final int SCOPE_HASH_BYTES = 8;

	/** Leaves room in a session name for the longest scope, {@code u-<16 hex>-}. */
	static final int MAX_SESSION_NAME_LENGTH = 128 - (2 + 2 * SCOPE_HASH_BYTES + 1);

	private final AcpHarnessClient agent;

	private final AcpProperties.Controller properties;

	public AcpController(AcpHarnessClient agent, AcpProperties properties) {
		this.agent = agent;
		this.properties = properties.controller();
	}

	/** Runs a turn and returns the whole answer. */
	@PostMapping("${spring.ai.acp.harness.controller.path:/api/acp}/prompt")
	public Mono<ResponseEntity<PromptResponse>> prompt(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		String requestId = UUID.randomUUID().toString();
		return turn(request, principal).collect(TurnResult::new, TurnResult::accept)
			.map(result -> ResponseEntity
				.ok(new PromptResponse(result.content(), result.stopReason(), null, requestId)))
			.onErrorResume(AcpHarnessClientException.class, ex -> {
				// The agent's own words can carry anything it read; the caller gets the
				// request id.
				logger.warn("Agent request {} failed", requestId, ex);
				return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
					.body(new PromptResponse(null, null, TURN_FAILED, requestId)));
			});
	}

	/**
	 * Streams a turn as server-sent events.
	 *
	 * <p>
	 * Text only, deliberately: tool calls and plans name paths and commands inside the
	 * workspace, and an endpoint that may be exposed to a browser should not be the thing
	 * that decides those are safe to publish. An application that wants them has
	 * {@code AcpHarnessClient}.
	 *
	 * <p>
	 * A turn that fails is an error, not a quiet end: a 502 if nothing has been sent yet,
	 * and otherwise an aborted connection, so a caller can tell a truncated answer from a
	 * finished one.
	 */
	@PostMapping(value = "${spring.ai.acp.harness.controller.path:/api/acp}/stream",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> stream(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		String requestId = UUID.randomUUID().toString();
		return turn(request, principal).ofType(AcpHarnessEvent.Text.class)
			.map(AcpHarnessEvent.Text::text)
			.onErrorMap(AcpHarnessClientException.class, ex -> {
				logger.warn("Agent request {} failed", requestId, ex);
				return new ResponseStatusException(HttpStatus.BAD_GATEWAY, TURN_FAILED);
			});
	}

	/**
	 * The caller's open sessions, as the agent reports them, when it implements
	 * {@code session/list}.
	 *
	 * <p>
	 * Limited to those because a stored session's title can repeat its first prompt, and
	 * the agent does not record who a session belongs to.
	 */
	@GetMapping("${spring.ai.acp.harness.controller.path:/api/acp}/sessions")
	public Mono<ResponseEntity<List<StoredSession>>> sessions(Principal principal) {
		requireAuthorized(principal);
		String scope = scope(principal);
		return blocking(() -> {
			Set<String> mine = agent.sessions()
				.open()
				.stream()
				.filter(session -> session.name().startsWith(scope))
				.map(AcpHarnessSession::sessionId)
				.collect(Collectors.toSet());
			return agent.sessions().list().stream().filter(stored -> mine.contains(stored.sessionId())).toList();
		}).map(ResponseEntity::ok)
			.onErrorMap(UnsupportedAcpHarnessOperationException.class,
					ex -> new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage()));
	}

	/**
	 * Ends one of the caller's named sessions. Always succeeds, whether or not the name
	 * was in use, so it says nothing about what other callers have open.
	 */
	@DeleteMapping("${spring.ai.acp.harness.controller.path:/api/acp}/sessions/{name}")
	public Mono<ResponseEntity<Void>> closeSession(@PathVariable String name, Principal principal) {
		requireAuthorized(principal);
		requireSessionName(name);
		String scoped = scope(principal) + name;
		return blocking(() -> {
			agent.sessions().close(scoped);
			return ResponseEntity.noContent().build();
		});
	}

	/**
	 * Unauthenticated on purpose: it says whether the agent is up and nothing about what
	 * it does. Neither call blocks or starts an agent, so a probe answers even when every
	 * turn is busy.
	 */
	@GetMapping("${spring.ai.acp.harness.controller.path:/api/acp}/health")
	public ResponseEntity<HealthResponse> health() {
		boolean available = agent.isAlive();
		var info = agent.harnessInfo();
		return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
			.body(new HealthResponse(available, agent.runtimeId(), info == null ? null : info.toString()));
	}

	/**
	 * Runs client calls off the event loop. {@link AcpHarnessClient} blocks on the agent,
	 * and Reactor refuses to block on a Netty thread.
	 */
	private static <T> Mono<T> blocking(Callable<T> work) {
		return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * A turn's events, with a failed turn as an error signal.
	 *
	 * <p>
	 * Subscribed off the event loop, because opening the session blocks. Built on the
	 * stream rather than {@code call()} so that a turn holds no thread once it is
	 * running: the agent's permission requests are answered on the same scheduler, and
	 * turns blocking on them could fill it and wait for a reply that never gets to run.
	 */
	private Flux<AcpHarnessEvent> turn(PromptRequest request, Principal principal) {
		return spec(request, principal).stream()
			.events()
			.subscribeOn(Schedulers.boundedElastic())
			.<AcpHarnessEvent>handle((event, sink) -> {
				if (event instanceof AcpHarnessEvent.Failed failed) {
					sink.error(new AcpHarnessClientException("Agent turn failed", failed.cause()));
				}
				else {
					sink.next(event);
				}
			})
			.onErrorMap(SessionOwnershipException.class,
					ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage()))
			.onErrorMap(AcpHarnessCapacityException.class, ex -> {
				logger.warn("Agent request refused: {}", ex.getMessage());
				return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"The agent is at capacity; try again later");
			});
	}

	/**
	 * Passes the caller on explicitly, because a principal resolver has no thread-bound
	 * context to read on a reactive stack, and scopes the session name to them.
	 */
	private AcpHarnessClient.PromptSpec spec(PromptRequest request, Principal principal) {
		AcpHarnessClient.PromptSpec spec = agent.prompt().principal(sessionPrincipal(principal)).user(request.prompt());
		if (request.session() != null) {
			spec = spec.session(scope(principal) + request.session());
		}
		return spec.options(options(request));
	}

	/**
	 * What keeps each caller's session names to themselves.
	 *
	 * <p>
	 * The client's session names are one namespace, so without this two users choosing
	 * {@code ticket-1234} would collide, and a refusal would tell the second that the
	 * first has it open. Hashed because a principal's name can hold characters a session
	 * name cannot, and should not end up in the agent's logs.
	 */
	private static String scope(@Nullable Principal principal) {
		if (principal == null) {
			return "anon-";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(principal.getName().getBytes(StandardCharsets.UTF_8));
			return "u-" + HexFormat.of().formatHex(digest, 0, SCOPE_HASH_BYTES) + "-";
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JVM has SHA-256", ex);
		}
	}

	private static @Nullable SessionPrincipal sessionPrincipal(@Nullable Principal principal) {
		return principal == null ? null : SessionPrincipal.of(principal.getName());
	}

	/**
	 * Checked here rather than left to the session registry, because by then the pool has
	 * assigned the name to a process and may have restarted one for it.
	 */
	private static void requireSessionName(String name) {
		try {
			Validation.requireName(name, "session name");
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}
		if (name.length() > MAX_SESSION_NAME_LENGTH) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"session name must be at most " + MAX_SESSION_NAME_LENGTH + " characters");
		}
	}

	private AcpHarnessOptions options(PromptRequest request) {
		AcpHarnessOptions.Builder builder = AcpHarnessOptions.builder();
		if (request.timeout() != null) {
			builder.timeout(request.timeout());
		}
		if (properties.allowRequestOverrides()) {
			if (request.model() != null) {
				builder.model(request.model());
			}
			if (request.provider() != null) {
				builder.provider(request.provider());
			}
			if (request.mode() != null) {
				builder.mode(request.mode());
			}
		}
		return builder.build();
	}

	private void requireAuthorized(Principal principal) {
		if (!properties.allowUnauthenticated() && principal == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
		}
	}

	private void validate(PromptRequest request) {
		if (request == null || request.prompt() == null || request.prompt().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
		}
		if (request.prompt().length() > properties.maxPromptChars()) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "prompt is too large");
		}
		if (request.session() != null) {
			requireSessionName(request.session());
		}
		if (request.timeout() != null && (request.timeout().isNegative() || request.timeout().isZero()
				|| request.timeout().compareTo(properties.maxTimeout()) > 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"timeout must be positive and at most " + properties.maxTimeout());
		}
		if (!properties.allowRequestOverrides()
				&& (request.model() != null || request.provider() != null || request.mode() != null)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"model, provider and mode overrides are disabled; see spring.ai.acp.harness.controller"
							+ ".allow-request-overrides");
		}
	}

	/**
	 * @param session a named conversation to continue, or null for a throwaway turn
	 */
	public record PromptRequest(String prompt, @Nullable String session, @Nullable Duration timeout,
			@Nullable String model, @Nullable String provider, @Nullable String mode) {
	}

	/** A turn's events, added up into its answer. */
	private static final class TurnResult {

		private final StringBuilder content = new StringBuilder();

		private AcpHarnessEvent.@Nullable Completed completion;

		void accept(AcpHarnessEvent event) {
			if (event instanceof AcpHarnessEvent.Text text) {
				this.content.append(text.text());
			}
			else if (event instanceof AcpHarnessEvent.Completed completed) {
				this.completion = completed;
			}
		}

		String content() {
			return this.content.toString();
		}

		@Nullable
		String stopReason() {
			if (this.completion == null) {
				// Would mean the terminal-event contract was broken upstream.
				throw new AcpHarnessClientException("Agent turn ended without a terminal event");
			}
			AcpSchema.StopReason reason = this.completion.reason();
			return reason == null ? null : reason.toString();
		}

	}

	/**
	 * @param content what the agent said, or null when the turn failed
	 * @param stopReason why the agent stopped, or null when it did not say or the turn
	 * failed
	 * @param error what went wrong, or null when nothing did
	 */
	public record PromptResponse(@Nullable String content, @Nullable String stopReason, @Nullable String error,
			String requestId) {
	}

	/**
	 * @param agent the agent's name and version, or null before it has introduced itself
	 */
	public record HealthResponse(boolean available, String runtime, @Nullable String agent) {
	}

}
