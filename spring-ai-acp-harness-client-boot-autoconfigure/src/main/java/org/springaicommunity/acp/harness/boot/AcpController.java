package org.springaicommunity.acp.harness.boot;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.config.AcpAgentOptions;
import org.springaicommunity.acp.harness.event.AcpAgentEvent;
import org.springaicommunity.acp.harness.session.StoredSession;
import org.springaicommunity.acp.harness.session.UnsupportedAcpAgentOperationException;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;

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

	private final AcpHarnessClient agent;

	private final AcpProperties.Controller properties;

	public AcpController(AcpHarnessClient agent, AcpProperties properties) {
		this.agent = agent;
		this.properties = properties.controller();
	}

	/** Runs a turn and returns the whole answer. */
	@PostMapping("${spring.ai.acp.harness.controller.path:/api/acp}/prompt")
	public ResponseEntity<PromptResponse> prompt(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		String requestId = UUID.randomUUID().toString();
		try {
			AcpHarnessClient.AcpAgentResponse response = spec(request).call();
			AcpSchema.StopReason reason = response.completion().reason();
			return ResponseEntity
				.ok(new PromptResponse(response.content(), reason == null ? null : reason.toString(), null, requestId));
		}
		catch (AcpHarnessClientException ex) {
			// The agent's own words can carry anything it read; the caller gets the
			// request id.
			logger.warn("Agent request {} failed", requestId, ex);
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(new PromptResponse(null, null, "The agent did not complete the turn", requestId));
		}
	}

	/**
	 * Streams a turn as server-sent events.
	 *
	 * <p>
	 * Text only, deliberately: tool calls and plans name paths and commands inside the
	 * workspace, and an endpoint that may be exposed to a browser should not be the thing
	 * that decides those are safe to publish. An application that wants them has
	 * {@code AcpHarnessClient}.
	 */
	@PostMapping(value = "${spring.ai.acp.harness.controller.path:/api/acp}/stream",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> stream(@RequestBody PromptRequest request, Principal principal) {
		requireAuthorized(principal);
		validate(request);
		return spec(request).stream()
			.events()
			.filter(AcpAgentEvent.Text.class::isInstance)
			.map(AcpAgentEvent.Text.class::cast)
			.map(AcpAgentEvent.Text::text);
	}

	/**
	 * The conversations the agent has stored, when it implements {@code session/list}.
	 */
	@GetMapping("${spring.ai.acp.harness.controller.path:/api/acp}/sessions")
	public ResponseEntity<List<StoredSession>> sessions(Principal principal) {
		requireAuthorized(principal);
		try {
			return ResponseEntity.ok(agent.sessions().list());
		}
		catch (UnsupportedAcpAgentOperationException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
		}
	}

	/** Ends a named session. Always succeeds, whether or not the agent knew about it. */
	@DeleteMapping("${spring.ai.acp.harness.controller.path:/api/acp}/sessions/{name}")
	public ResponseEntity<Void> closeSession(@PathVariable String name, Principal principal) {
		requireAuthorized(principal);
		agent.sessions().close(name);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Unauthenticated on purpose: it says whether the agent is up and nothing about what
	 * it does.
	 */
	@GetMapping("${spring.ai.acp.harness.controller.path:/api/acp}/health")
	public ResponseEntity<HealthResponse> health() {
		boolean available = agent.isAlive();
		var info = agent.agentInfo();
		return ResponseEntity.status(available ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
			.body(new HealthResponse(available, agent.runtimeId(), info == null ? null : info.toString()));
	}

	private AcpHarnessClient.PromptSpec spec(PromptRequest request) {
		AcpHarnessClient.PromptSpec spec = agent.prompt().user(request.prompt());
		if (request.session() != null) {
			spec = spec.session(request.session());
		}
		return spec.options(options(request));
	}

	private AcpAgentOptions options(PromptRequest request) {
		AcpAgentOptions.Builder builder = AcpAgentOptions.builder();
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
