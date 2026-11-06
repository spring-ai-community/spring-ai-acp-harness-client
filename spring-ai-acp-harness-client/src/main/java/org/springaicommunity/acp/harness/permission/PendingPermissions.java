package org.springaicommunity.acp.harness.permission;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * The permission requests a client is still deciding, by session, so that a turn that
 * ends early can withdraw them.
 *
 * <p>
 * ACP requires it: once a client sends {@code session/cancel}, it "MUST respond to all
 * pending {@code session/request_permission} requests with the {@code cancelled}
 * outcome". A decision can take as long as a person takes, so it has to be answerable
 * from outside. {@link #withdraw} answers {@code cancelled} at once, interrupts the
 * thread still deciding, and answers every request that arrives afterwards the same way
 * until {@link #reopen}. A decision that comes back after its request was withdrawn is
 * discarded, so a person who answers "allow" to a stale question allows nothing.
 *
 * <p>
 * Decisions run on {@code boundedElastic}: the transport's inbound thread is also the one
 * delivering the rest of the turn, and a person thinking it over must not freeze the text
 * they are reading.
 */
public final class PendingPermissions {

	private static final Logger logger = LoggerFactory.getLogger(PendingPermissions.class);

	private final Map<String, Set<Pending>> pending = new ConcurrentHashMap<>();

	private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();

	/**
	 * Answers one request for {@code sessionId} with what {@code decision} returns, or
	 * with {@code cancelled} if the request is withdrawn first.
	 * @param decision the option to select, or null to cancel the request; may block
	 */
	public Mono<AcpSchema.RequestPermissionResponse> decide(String sessionId,
			Supplier<AcpSchema.@Nullable PermissionOption> decision) {
		return Mono.defer(() -> {
			Pending request = new Pending();
			// Added before the check, so a withdraw racing with this either finds the
			// request or has already left its mark: one or the other answers it.
			pending.compute(sessionId, (id, requests) -> {
				Set<Pending> set = requests == null ? ConcurrentHashMap.newKeySet() : requests;
				set.add(request);
				return set;
			});
			if (withdrawn.contains(sessionId)) {
				request.withdraw();
			}
			else {
				Schedulers.boundedElastic().schedule(() -> request.run(decision));
			}
			return request.answer.asMono()
				.map(AcpSchema.RequestPermissionResponse::new)
				.doFinally(signal -> pending.computeIfPresent(sessionId, (id, requests) -> {
					requests.remove(request);
					return requests.isEmpty() ? null : requests;
				}));
		});
	}

	/**
	 * Answers every pending request for {@code sessionId} with {@code cancelled}, and
	 * every later one too, until {@link #reopen}.
	 */
	public void withdraw(String sessionId) {
		withdrawn.add(sessionId);
		Set<Pending> requests = pending.get(sessionId);
		if (requests != null) {
			requests.forEach(Pending::withdraw);
		}
	}

	/** Lets requests for {@code sessionId} be decided again. */
	public void reopen(String sessionId) {
		withdrawn.remove(sessionId);
	}

	/** One request, and the thread deciding it while there is one. */
	private static final class Pending {

		private final Sinks.One<AcpSchema.RequestPermissionOutcome> answer = Sinks.one();

		/** Guarded by {@code this}. */
		private @Nullable Thread deciding;

		/** Guarded by {@code this}. */
		private boolean withdrawn;

		void run(Supplier<AcpSchema.@Nullable PermissionOption> decision) {
			synchronized (this) {
				if (withdrawn) {
					return;
				}
				deciding = Thread.currentThread();
			}
			try {
				AcpSchema.PermissionOption chosen = decision.get();
				AcpSchema.RequestPermissionOutcome outcome = chosen == null ? new AcpSchema.PermissionCancelled()
						: new AcpSchema.PermissionSelected(chosen.optionId());
				if (answer.tryEmitValue(outcome).isFailure()) {
					logger.debug("Permission decided after the request was withdrawn; not sent: {}",
							chosen == null ? "cancelled" : chosen.optionId());
				}
			}
			catch (RuntimeException ex) {
				answer.tryEmitError(ex);
			}
			finally {
				synchronized (this) {
					deciding = null;
				}
				// An interrupt meant for this question must not outlive it on a pooled
				// thread. None can arrive after deciding is cleared, so this clears only
				// ours.
				Thread.interrupted();
			}
		}

		void withdraw() {
			answer.tryEmitValue(new AcpSchema.PermissionCancelled());
			synchronized (this) {
				withdrawn = true;
				if (deciding != null) {
					deciding.interrupt();
				}
			}
		}

	}

}
