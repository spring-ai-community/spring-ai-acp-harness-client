package org.springaicommunity.acp.harness.session;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.config.Validation;

/**
 * Maps caller-chosen session names onto the ids an agent issues.
 *
 * <p>
 * One rule matters more than the rest: <strong>registry membership is authoritative over
 * the caller's resume flag.</strong> A caller that retries its first message sends
 * {@code resume=false} again; honoring that literally would discard the context the first
 * attempt established. So a known name resumes whatever the caller asked for, and only an
 * unknown name creates.
 *
 * <p>
 * The mapping is in memory. A restarted process cannot reattach to sessions it issued
 * before, and callers should expect a fresh conversation rather than a resumed one.
 */
public final class SessionRegistry {

	private static final Logger logger = LoggerFactory.getLogger(SessionRegistry.class);

	private final Map<String, AcpHarnessSession> sessions = new ConcurrentHashMap<>();

	/**
	 * Returns the session for {@code name}, creating it through {@code factory} only if
	 * this registry has never seen the name.
	 * @param factory called at most once per name, under the map's per-key lock
	 */
	public AcpHarnessSession resolve(String name, Function<String, String> factory) {
		return resolve(name, null, n -> new Opened(factory.apply(n), null));
	}

	/**
	 * Same, for a session that belongs to someone and may hold resources beyond the
	 * agent.
	 *
	 * <p>
	 * A name already open for a different principal is refused, not shared. Session names
	 * are chosen by the application, and one that derives them from something two users
	 * can both produce — a ticket number, a document id — would otherwise hand the second
	 * user the first user's conversation, and with it MCP tools calling out under the
	 * first user's credentials.
	 * @param principal who the caller is acting for, or null for nobody
	 * @param factory called at most once per name, under the map's per-key lock; must
	 * release whatever it acquired if it throws
	 * @throws SessionOwnershipException if the name is open for someone else
	 */
	public AcpHarnessSession resolve(String name, @Nullable SessionPrincipal principal,
			Function<String, Opened> factory) {
		Validation.requireName(name, "session name");
		AcpHarnessSession session = sessions.computeIfAbsent(name, n -> {
			Opened opened = factory.apply(n);
			logger.debug("Created session '{}' as {}", n, opened.sessionId());
			return new AcpHarnessSession(n, opened.sessionId(), principal, opened.release());
		});
		if (!session.belongsTo(principal)) {
			throw new SessionOwnershipException(name);
		}
		return session;
	}

	/**
	 * What a factory reports back about a session it opened.
	 *
	 * @param release run once when the registry forgets the session; null for nothing
	 */
	public record Opened(String sessionId, @Nullable Runnable release) {
	}

	/**
	 * Registers a name for a session the agent already has.
	 *
	 * <p>
	 * Deliberately not {@link #resolve}: that one is idempotent because a caller retrying
	 * its first prompt must not get a second conversation, whereas binding a name that is
	 * already in use to a <em>different</em> agent-side session can only be a mistake,
	 * and doing it silently would strand whichever session lost.
	 * @throws IllegalStateException if the name is already registered
	 */
	public AcpHarnessSession adopt(String name, String sessionId) {
		return adopt(name, sessionId, null, null);
	}

	/**
	 * Same, for a session that belongs to someone and may hold resources beyond the
	 * agent.
	 * @param release run once when the registry forgets the session — including when the
	 * name turns out to be taken, so the caller never has to clean up after a refusal
	 */
	public AcpHarnessSession adopt(String name, String sessionId, @Nullable SessionPrincipal principal,
			@Nullable Runnable release) {
		Validation.requireName(name, "session name");
		AcpHarnessSession adopted = new AcpHarnessSession(name, sessionId, principal, release);
		AcpHarnessSession existing = sessions.putIfAbsent(name, adopted);
		if (existing != null) {
			adopted.release();
			throw new IllegalStateException("Session '" + name + "' is already open as " + existing.sessionId()
					+ "; close it before binding the name to " + sessionId);
		}
		logger.debug("Adopted session '{}' as {}", name, sessionId);
		return adopted;
	}

	/** The session open under {@code name}, or null. */
	public @Nullable AcpHarnessSession find(String name) {
		return sessions.get(name);
	}

	/** The open session the agent knows as {@code sessionId}, or null when none is. */
	public @Nullable AcpHarnessSession findBySessionId(String sessionId) {
		for (AcpHarnessSession session : sessions.values()) {
			if (session.sessionId().equals(sessionId)) {
				return session;
			}
		}
		return null;
	}

	public boolean knows(String name) {
		return sessions.containsKey(name);
	}

	/**
	 * Forgets a session and releases what it held here. Does not close it on the agent —
	 * the caller owns that.
	 * @return the session forgotten, or null if the name was not open
	 */
	public @Nullable AcpHarnessSession remove(String name) {
		AcpHarnessSession removed = sessions.remove(name);
		if (removed != null) {
			removed.release();
		}
		return removed;
	}

	/**
	 * Removes sessions idle longer than {@code ttl} that are not mid-turn.
	 * @return the sessions evicted, so the caller can close them on the agent
	 */
	public List<AcpHarnessSession> evictIdle(Duration ttl) {
		Instant cutoff = Instant.now().minus(ttl);
		List<AcpHarnessSession> evicted = sessions.values().stream().filter(s -> s.idleSince(cutoff)).toList();
		evicted.forEach(s -> {
			if (sessions.remove(s.name(), s)) {
				s.release();
			}
		});
		if (!evicted.isEmpty()) {
			logger.debug("Evicted {} idle session(s)", evicted.size());
		}
		return evicted;
	}

	public List<AcpHarnessSession> all() {
		return List.copyOf(sessions.values());
	}

	public void clear() {
		List<AcpHarnessSession> all = List.copyOf(sessions.values());
		sessions.clear();
		all.forEach(AcpHarnessSession::release);
	}

	public int size() {
		return sessions.size();
	}

}
