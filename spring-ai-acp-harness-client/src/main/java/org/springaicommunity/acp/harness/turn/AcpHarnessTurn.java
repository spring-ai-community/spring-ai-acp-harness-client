package org.springaicommunity.acp.harness.turn;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.event.AcpHarnessEventMapper;
import org.springaicommunity.acp.harness.observation.AcpHarnessObservations;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * Runs one prompt turn and publishes it as a {@link Flux} of {@link AcpHarnessEvent}.
 *
 * <p>
 * The contract this class exists to uphold: <strong>every turn emits exactly one terminal
 * event.</strong> Normal completion, an RPC error, a timeout, a dropped transport, or a
 * consumer that walks away — all five end with {@link AcpHarnessEvent.Completed} or
 * {@link AcpHarnessEvent.Failed} and nothing after it. Consumers switch over
 * {@code AcpHarnessEvent} without a default branch, so a turn that ended silently would
 * hang them.
 *
 * <p>
 * Streamed content and the turn's outcome arrive on two independent channels — the router
 * and the {@code prompt()} Mono respectively — and both feed the same {@link FluxSink},
 * which serializes them. The sink is the join point.
 */
public final class AcpHarnessTurn {

	private static final Logger logger = LoggerFactory.getLogger(AcpHarnessTurn.class);

	private final AcpAsyncClient client;

	private final SessionUpdateRouter router;

	private final AcpHarnessObservations observations;

	private final PlanTool planTool;

	private AcpHarnessTurn(AcpAsyncClient client, SessionUpdateRouter router,
			@Nullable AcpHarnessObservations observations, @Nullable PlanTool planTool) {
		this.client = client;
		this.router = router;
		this.observations = observations == null ? AcpHarnessObservations.NONE : observations;
		this.planTool = planTool == null ? PlanTool.NONE : planTool;
	}

	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router) {
		return new AcpHarnessTurn(client, router, AcpHarnessObservations.NONE, PlanTool.NONE);
	}

	/** Same, reporting what the turn does to {@code observations}. */
	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router,
			AcpHarnessObservations observations) {
		return new AcpHarnessTurn(client, router, observations, PlanTool.NONE);
	}

	/**
	 * Same, reporting the tool calls {@code planTool} recognises as the agent's plan.
	 */
	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router,
			AcpHarnessObservations observations, PlanTool planTool) {
		return new AcpHarnessTurn(client, router, observations, planTool);
	}

	/**
	 * Reads the plan out of a tool call, for an agent that keeps its plan in a tool
	 * rather than sending ACP {@code plan} updates.
	 *
	 * @see org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime#planOf
	 */
	@FunctionalInterface
	public interface PlanTool {

		/** Recognises nothing: every tool call is reported as one. */
		PlanTool NONE = toolCall -> null;

		/**
		 * @return the whole plan the call carries, or null when it is not a plan
		 */
		@Nullable
		List<AcpSchema.PlanEntry> planOf(AcpSchema.ToolCall toolCall);

	}

	/**
	 * Prompts {@code session} and streams the result.
	 *
	 * <p>
	 * Cold: nothing is sent until the returned Flux is subscribed, and each subscription
	 * would run its own turn. Callers hold the session's turn permit around a single
	 * subscription.
	 */
	public Flux<AcpHarnessEvent> prompt(AcpHarnessSession session, List<AcpSchema.ContentBlock> content,
			Duration timeout) {
		return prompt(session, content, timeout, null);
	}

	/**
	 * Same, with what an observation needs to say which turn this was.
	 *
	 * <p>
	 * The context is built by the caller rather than derived here because the only parts
	 * of it worth recording — the runtime's id, the model the negotiated tier settled on
	 * — are things a turn does not know and its client does.
	 */
	public Flux<AcpHarnessEvent> prompt(AcpHarnessSession session, List<AcpSchema.ContentBlock> content,
			Duration timeout, AcpHarnessObservations.@Nullable TurnContext context) {
		return Flux.create(sink -> start(sink, session, content, timeout, context), FluxSink.OverflowStrategy.BUFFER);
	}

	private void start(FluxSink<AcpHarnessEvent> sink, AcpHarnessSession session, List<AcpSchema.ContentBlock> content,
			Duration timeout, AcpHarnessObservations.@Nullable TurnContext context) {
		String sessionId = session.sessionId();
		AcpHarnessObservations.TurnRecording recording = observations.turnStarted(context == null
				? new AcpHarnessObservations.TurnContext("unknown", session.name(), null, false) : context);

		// Guards the terminal contract. Both channels race to finish the turn; the first
		// one wins
		// and the other becomes a no-op.
		AtomicBoolean finished = new AtomicBoolean();
		// Distinguishes "the consumer walked away" from "the turn ended on its own", so
		// we only
		// send session/cancel in the former case.
		AtomicBoolean cancelledByConsumer = new AtomicBoolean();
		// The tool calls this turn reported as its plan, whose updates are then dropped.
		Set<String> planCalls = ConcurrentHashMap.newKeySet();

		try {
			router.register(sessionId, update -> {
				AcpHarnessEvent event = map(update, planCalls);
				if (event != null) {
					record(recording, event);
					sink.next(event);
				}
			});
		}
		catch (RuntimeException ex) {
			// Not unregistered here: the registration that exists belongs to another
			// turn.
			recording.failed("registration", ex);
			sink.next(new AcpHarnessEvent.Failed(ex));
			sink.complete();
			return;
		}

		Disposable subscription = client.prompt(new AcpSchema.PromptRequest(sessionId, content))
			.timeout(timeout)
			.subscribe(
					response -> finish(sink, finished, recording, sessionId,
							new AcpHarnessEvent.Completed(response.stopReason())),
					error -> finish(sink, finished, recording, sessionId, new AcpHarnessEvent.Failed(error)));

		sink.onCancel(() -> cancelledByConsumer.set(true));

		// onDispose covers every exit: completion, error, and cancellation.
		sink.onDispose(() -> {
			router.unregister(sessionId);
			if (cancelledByConsumer.get() && !finished.get()) {
				cancelRemoteTurn(sessionId);
				// The consumer is gone, so no terminal event can be delivered to it — but
				// something
				// is still counting, and a turn that vanished from the metrics would be
				// the one
				// worth knowing about.
				if (finished.compareAndSet(false, true)) {
					recording.failed("cancelled", null);
				}
			}
			subscription.dispose();
		});
	}

	/**
	 * Emits the turn's one terminal event, after taking the session out of the router.
	 *
	 * <p>
	 * The order of those two is load-bearing, and getting it wrong is a race that only
	 * shows up under a fast agent. {@code FluxCreate} runs the downstream's
	 * {@code onComplete} before it runs the sink's {@code onDispose} — so a caller
	 * blocking on this turn is released, and can start the next one, while the
	 * registration for this turn is still in the router. The next turn's {@code register}
	 * then finds the session occupied and fails with "already has an active turn", for a
	 * turn that had finished. Unregistering before the sink completes closes that window;
	 * {@code onDispose} still unregisters, for the cancellation path, and the second call
	 * is a no-op.
	 *
	 * <p>
	 * Nothing is lost by unregistering first: an update arriving after the turn's stop
	 * reason does not belong to this turn, and was already being dropped a microsecond
	 * later.
	 */
	private void finish(FluxSink<AcpHarnessEvent> sink, AtomicBoolean finished,
			AcpHarnessObservations.TurnRecording recording, String sessionId, AcpHarnessEvent terminal) {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		router.unregister(sessionId);
		if (terminal instanceof AcpHarnessEvent.Failed failed) {
			logger.debug("Turn failed", failed.cause());
			recording.failed(reasonOf(failed.cause()), failed.cause());
		}
		else if (terminal instanceof AcpHarnessEvent.Completed completed) {
			recording.completed(completed.reason() == null ? null : completed.reason().name());
		}
		sink.next(terminal);
		sink.complete();
	}

	/**
	 * Maps one update, reporting a tool call {@link #planTool} recognises as a plan.
	 *
	 * <p>
	 * The call becomes {@link AcpHarnessEvent.PlanUpdated} in place of
	 * {@link AcpHarnessEvent.ToolCallStarted}, and the updates that follow it —
	 * "completed", with the tool's acknowledgement as content — are dropped, so a caller
	 * sees the plan change and not the bookkeeping behind it. Anything the mapper drops,
	 * a replayed call included, stays dropped. A recogniser that throws is treated as one
	 * that did not recognise the call: this runs on the transport's inbound thread, and a
	 * plan that cannot be read is still a tool call worth reporting.
	 */
	private @Nullable AcpHarnessEvent map(AcpSchema.SessionUpdate update, Set<String> planCalls) {
		AcpHarnessEvent event = AcpHarnessEventMapper.map(update);
		if (event == null) {
			return null;
		}
		if (update instanceof AcpSchema.ToolCall call) {
			List<AcpSchema.PlanEntry> plan = planOf(call);
			if (plan != null) {
				if (call.toolCallId() != null) {
					planCalls.add(call.toolCallId());
				}
				return new AcpHarnessEvent.PlanUpdated(List.copyOf(plan));
			}
		}
		else if (update instanceof AcpSchema.ToolCallUpdateNotification updated
				&& planCalls.contains(updated.toolCallId())) {
			return null;
		}
		return event;
	}

	private @Nullable List<AcpSchema.PlanEntry> planOf(AcpSchema.ToolCall call) {
		try {
			return planTool.planOf(call);
		}
		catch (RuntimeException ex) {
			logger.debug("Could not read a plan from tool call {}", call.toolCallId(), ex);
			return null;
		}
	}

	/**
	 * Feeds the recording everything it counts, leaving the events themselves untouched.
	 */
	private static void record(AcpHarnessObservations.TurnRecording recording, AcpHarnessEvent event) {
		switch (event) {
			case AcpHarnessEvent.ToolCallStarted started -> recording.toolCallStarted(started.id(), started.title(),
					started.kind() == null ? null : started.kind().name());
			case AcpHarnessEvent.ToolCallUpdated updated ->
				recording.toolCallUpdated(updated.id(), updated.status() == null ? null : updated.status().name());
			case AcpHarnessEvent.UsageUpdated usage ->
				recording.usage(usage.contextUsed(), usage.contextSize(), usage.costAmount(), usage.costCurrency());
			default -> {
			}
		}
	}

	/**
	 * A low-cardinality name for why a turn failed.
	 *
	 * <p>
	 * An exception message names a session id, a model or a path, and a tag that carries
	 * one turns a counter into a leak. The class name is the fact worth grouping by.
	 */
	private static String reasonOf(@Nullable Throwable cause) {
		return cause == null ? "unknown" : cause.getClass().getSimpleName();
	}

	/**
	 * Tells the agent to stop work the consumer is no longer listening to.
	 * Fire-and-forget by necessity — the subscriber is already gone, so there is nobody
	 * left to report an error to — but it must be sent before the turn is torn down, or
	 * the agent keeps burning tokens.
	 */
	private void cancelRemoteTurn(String sessionId) {
		client.cancel(new AcpSchema.CancelNotification(sessionId))
			.subscribe(null, ex -> logger.debug("Failed to cancel session {}", sessionId, ex));
	}

}
