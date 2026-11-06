package org.springaicommunity.acp.harness.turn;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.event.AcpHarnessEventMapper;
import org.springaicommunity.acp.harness.observation.AcpHarnessObservations;
import org.springaicommunity.acp.harness.permission.PendingPermissions;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

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
 *
 * <p>
 * A turn that ends early — the consumer walked away, or it timed out — is over for the
 * consumer at once, but not for the agent. ACP lets the agent keep sending updates after
 * {@code session/cancel}, provided it does so before it answers the prompt with
 * {@code cancelled}, and goose, OpenCode and Codex all do: a {@code usage_update} or
 * {@code session_info_update} a few milliseconds after the cancel. So such a turn keeps
 * the session until that answer arrives, dropping what comes meanwhile and answering the
 * agent's permission requests {@code cancelled}, and only then lets the next turn have
 * it. An agent that never answers is waited for {@link #CANCEL_GRACE} and no longer.
 */
public final class AcpHarnessTurn {

	private static final Logger logger = LoggerFactory.getLogger(AcpHarnessTurn.class);

	/**
	 * How long a cancelled turn waits for the agent to answer its prompt before handing
	 * the session on anyway. Measured: goose 1.57, OpenCode 1.19 and codex-acp 2.5 all
	 * answer within 30 ms, so this bounds a misbehaving agent rather than a slow one.
	 */
	static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

	private final AcpAsyncClient client;

	private final SessionUpdateRouter router;

	private final AcpHarnessObservations observations;

	private final PlanTool planTool;

	private final PendingPermissions permissions;

	private final Duration cancelGrace;

	private AcpHarnessTurn(AcpAsyncClient client, SessionUpdateRouter router,
			@Nullable AcpHarnessObservations observations, @Nullable PlanTool planTool,
			@Nullable PendingPermissions permissions, Duration cancelGrace) {
		this.client = client;
		this.router = router;
		this.observations = observations == null ? AcpHarnessObservations.NONE : observations;
		this.planTool = planTool == null ? PlanTool.NONE : planTool;
		this.permissions = permissions == null ? new PendingPermissions() : permissions;
		this.cancelGrace = cancelGrace;
	}

	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router) {
		return new AcpHarnessTurn(client, router, AcpHarnessObservations.NONE, PlanTool.NONE, null, CANCEL_GRACE);
	}

	/** Same, reporting what the turn does to {@code observations}. */
	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router,
			AcpHarnessObservations observations) {
		return new AcpHarnessTurn(client, router, observations, PlanTool.NONE, null, CANCEL_GRACE);
	}

	/**
	 * Same, reporting the tool calls {@code planTool} recognises as the agent's plan.
	 */
	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router,
			AcpHarnessObservations observations, PlanTool planTool) {
		return new AcpHarnessTurn(client, router, observations, planTool, null, CANCEL_GRACE);
	}

	/**
	 * Same, withdrawing the session's pending permission requests from
	 * {@code permissions} when the turn is cancelled, as ACP requires.
	 */
	public static AcpHarnessTurn on(AcpAsyncClient client, SessionUpdateRouter router,
			AcpHarnessObservations observations, PlanTool planTool, PendingPermissions permissions) {
		return new AcpHarnessTurn(client, router, observations, planTool, permissions, CANCEL_GRACE);
	}

	/** This turn, waiting {@code grace} for a cancelled prompt's answer. For tests. */
	AcpHarnessTurn cancelGrace(Duration grace) {
		return new AcpHarnessTurn(client, router, observations, planTool, permissions, grace);
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
	 * would run its own turn. Callers acquire the session's turn permit before
	 * subscribing, once; the turn releases it ({@link AcpHarnessSession#endTurn}) when
	 * the agent is done with the session. That is just before the terminal event for a
	 * turn the agent finished, and later than it for one that was cancelled or timed out
	 * — see the class description.
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
		// one wins and the other becomes a no-op.
		AtomicBoolean finished = new AtomicBoolean();
		// Distinguishes "the consumer walked away" from "the turn ended on its own", so
		// onDispose sends session/cancel in the former case only. A timeout sends its
		// own.
		AtomicBoolean cancelledByConsumer = new AtomicBoolean();
		// Set once session/cancel is sent: the agent is only winding down from then on,
		// and nothing it sends is this turn's to report.
		AtomicBoolean cancelling = new AtomicBoolean();
		// The tool calls this turn reported as its plan, whose updates are then dropped.
		Set<String> planCalls = ConcurrentHashMap.newKeySet();

		Consumer<AcpSchema.SessionUpdate> listener = update -> {
			if (cancelling.get()) {
				logger.trace("Session {} is winding down a cancelled turn; dropping update", sessionId);
				return;
			}
			AcpHarnessEvent event = map(update, planCalls);
			if (event != null) {
				record(recording, event);
				sink.next(event);
			}
		};
		try {
			router.register(sessionId, listener);
		}
		catch (RuntimeException ex) {
			// Not unregistered here: the registration that exists belongs to another
			// turn. The permit is the caller's, acquired for this turn, so it goes back.
			recording.failed("registration", ex);
			session.endTurn();
			sink.next(new AcpHarnessEvent.Failed(ex));
			sink.complete();
			return;
		}

		// Hands the session to the next turn: out of the router first, so that turn's
		// register finds it free, then the permit. Once, whichever path gets here first.
		AtomicBoolean released = new AtomicBoolean();
		Runnable release = () -> {
			if (released.compareAndSet(false, true)) {
				router.unregister(sessionId, listener);
				// Answers anything still pending — an agent that answered its prompt
				// with a question open, or died with one — and lets the next turn's be
				// asked.
				permissions.withdraw(sessionId);
				permissions.reopen(sessionId);
				session.endTurn();
			}
		};

		// The agent's answer, kept apart from the turn's view of it: the timeout below
		// gives up on the answer without cancelling the request, so a timed-out turn can
		// still wait for the agent to finish.
		Sinks.One<AcpSchema.PromptResponse> answer = Sinks.one();
		Disposable remote = client.prompt(new AcpSchema.PromptRequest(sessionId, content))
			.subscribe(answer::tryEmitValue, answer::tryEmitError, answer::tryEmitEmpty);

		Runnable cancel = () -> {
			if (cancelling.compareAndSet(false, true)) {
				cancelRemoteTurn(sessionId);
				permissions.withdraw(sessionId);
				releaseWhenSettled(sessionId, answer, remote, release);
			}
		};

		Disposable outcome = answer.asMono()
			.switchIfEmpty(Mono.error(() -> new IllegalStateException("The agent answered the prompt with nothing")))
			.timeout(timeout)
			.subscribe(response -> finish(sink, finished, recording,
					new AcpHarnessEvent.Completed(response.stopReason()), release), error -> {
						if (error instanceof TimeoutException) {
							// The agent is still working on a prompt whose result nobody
							// will receive.
							cancel.run();
							finish(sink, finished, recording, new AcpHarnessEvent.Failed(error), null);
						}
						else {
							finish(sink, finished, recording, new AcpHarnessEvent.Failed(error), release);
						}
					});

		sink.onCancel(() -> cancelledByConsumer.set(true));

		// onDispose covers every exit: completion, error, and cancellation.
		sink.onDispose(() -> {
			outcome.dispose();
			if (cancelledByConsumer.get() && finished.compareAndSet(false, true)) {
				// The consumer is gone, so no terminal event can be delivered to it — but
				// something is still counting, and a turn that vanished from the metrics
				// would be the one worth knowing about.
				recording.failed("cancelled", null);
				cancel.run();
			}
		});
	}

	/**
	 * Releases the session once the agent has answered a cancelled prompt, or once
	 * {@link #cancelGrace} has passed without an answer.
	 *
	 * <p>
	 * The answer is the agent's own statement that it has stopped: ACP requires every
	 * update for the cancelled turn to come before it, and the SDK hands each one to the
	 * router before it completes the prompt, so nothing of this turn can reach the next.
	 * An agent that never answers is given up on, and its request is withdrawn — which in
	 * acp-core 0.80.0 also sends {@code $/cancel_request} and forgets the request — so it
	 * cannot hold the session for good.
	 */
	private void releaseWhenSettled(String sessionId, Sinks.One<AcpSchema.PromptResponse> answer, Disposable remote,
			Runnable release) {
		answer.asMono().timeout(cancelGrace).subscribe(response -> release.run(), error -> {
			if (error instanceof TimeoutException) {
				logger.debug("Session {} did not answer its cancelled prompt within {}; handing it on", sessionId,
						cancelGrace);
				remote.dispose();
			}
			release.run();
		}, release);
	}

	/**
	 * Emits the turn's one terminal event, after handing the session on when
	 * {@code release} is given.
	 *
	 * <p>
	 * The order of those two is load-bearing, and getting it wrong is a race that only
	 * shows up under a fast agent. {@code FluxCreate} runs the downstream's
	 * {@code onComplete} before it runs the sink's {@code onDispose} — so a caller
	 * blocking on this turn is released, and can start the next one, while the
	 * registration for this turn is still in the router. The next turn's {@code register}
	 * then finds the session occupied and fails with "already has an active turn", for a
	 * turn that had finished. Releasing before the sink completes closes that window.
	 *
	 * <p>
	 * Nothing is lost by releasing first: an update arriving after the turn's stop reason
	 * does not belong to this turn. A timed-out turn passes no {@code release}: the agent
	 * has not stopped, and the session is released when it has.
	 */
	private void finish(FluxSink<AcpHarnessEvent> sink, AtomicBoolean finished,
			AcpHarnessObservations.TurnRecording recording, AcpHarnessEvent terminal, @Nullable Runnable release) {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		if (release != null) {
			release.run();
		}
		if (terminal instanceof AcpHarnessEvent.Failed failed) {
			logger.debug("Turn failed", failed.cause());
			recording.failed(reasonOf(failed.cause()), failed.cause());
		}
		else if (terminal instanceof AcpHarnessEvent.Completed completed) {
			recording.completed(completed.reason() == null ? null : tagOf(completed.reason().value()));
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
			case AcpHarnessEvent.ToolCallUpdated updated -> recording.toolCallUpdated(updated.id(),
					updated.status() == null ? null : tagOf(updated.status().value()));
			case AcpHarnessEvent.UsageUpdated usage ->
				recording.usage(usage.contextUsed(), usage.contextSize(), usage.costAmount(), usage.costCurrency());
			default -> {
			}
		}
	}

	/**
	 * A stop reason or tool call status as an observation tag: its wire value in upper
	 * case, the form these tags took while acp-core modelled them as enums
	 * ({@code end_turn} is {@code END_TURN}), so dashboards built on them keep working.
	 */
	private static String tagOf(String wireValue) {
		return wireValue.toUpperCase(Locale.ROOT);
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
	 * Tells the agent to stop work nobody is listening to any more: the consumer walked
	 * away, or the turn timed out. Fire-and-forget by necessity — the turn is already
	 * over for the consumer, so there is nobody left to report an error to — but it must
	 * be sent, or the agent keeps burning tokens.
	 */
	private void cancelRemoteTurn(String sessionId) {
		client.cancel(new AcpSchema.CancelNotification(sessionId))
			.subscribe(null, ex -> logger.debug("Failed to cancel session {}", sessionId, ex));
	}

}
