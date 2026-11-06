package org.springaicommunity.acp.harness.client;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.config.AdvertisedSessionConfig;
import org.springaicommunity.acp.harness.config.AcpHarnessOptions;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.config.ConfigResolver;
import org.springaicommunity.acp.harness.config.McpServerSpec;
import org.springaicommunity.acp.harness.config.McpSettings;
import org.springaicommunity.acp.harness.config.SessionConfiguration;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.mcp.McpAccess;
import org.springaicommunity.acp.harness.observation.AcpHarnessObservations;
import org.springaicommunity.acp.harness.process.AcpHarnessLogWatcher;
import org.springaicommunity.acp.harness.protocol.AcpProtocol;
import org.springaicommunity.acp.harness.runtime.AcpHarnessNotice;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime.PortableOption;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.AcpHarnessSessions;
import org.springaicommunity.acp.harness.session.DefaultAcpHarnessSessions;
import org.springaicommunity.acp.harness.session.SessionPrincipal;
import org.springaicommunity.acp.harness.session.SessionPrincipalResolver;
import org.springaicommunity.acp.harness.session.SessionRegistry;
import org.springaicommunity.acp.harness.turn.AcpHarnessTurn;
import org.springaicommunity.acp.harness.turn.SessionUpdateRouter;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The default {@link AcpHarnessClient}: one agent connection, many named sessions.
 */
public final class DefaultAcpHarnessClient implements AcpHarnessClient {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAcpHarnessClient.class);

	private final AcpAsyncClient acp;

	private final AcpHarnessRuntime runtime;

	private final AcpHarnessSettings settings;

	private final SessionRegistry sessions;

	private final SessionUpdateRouter router;

	private final SessionConfigRecorder recorder;

	private final ConfigResolver configResolver;

	private final @Nullable AcpHarnessInfo harnessInfo;

	private final AcpHarnessSessions sessionOperations;

	private final int protocolVersion;

	private final AcpHarnessObservations observations;

	/**
	 * Null when the transport cannot tell; see {@code AcpHarnessClientFactory.Liveness}.
	 */
	private final @Nullable BooleanSupplier alive;

	private final AtomicBoolean closed = new AtomicBoolean();

	private final Runnable onClose;

	/** Set once by the factory before this client is handed out, or left null. */
	private volatile @Nullable AcpHarnessLogWatcher watcher;

	/**
	 * Each session's MCP servers as the agent is to see them; owns the loopback proxy, if
	 * any.
	 */
	private final McpAccess mcpAccess;

	/**
	 * @param recorder the config-option recorder wrapped around the transport, or null
	 * for a fresh one
	 * @param initialized the agent's {@code initialize} answer, or null when none was
	 * kept
	 * @param alive whether the transport is still up, or null when it cannot tell
	 * @param onClose run after the connection closes, or null for nothing
	 */
	public DefaultAcpHarnessClient(AcpAsyncClient acp, AcpHarnessRuntime runtime, AcpHarnessSettings settings,
			SessionRegistry sessions, SessionUpdateRouter router, @Nullable SessionConfigRecorder recorder,
			AcpSchema.@Nullable InitializeResponse initialized, @Nullable BooleanSupplier alive,
			@Nullable Runnable onClose) {
		this(acp, runtime, settings, sessions, router, recorder, initialized, AcpProtocol.V1,
				AcpHarnessObservations.NONE, alive, onClose);
	}

	/** Same, with the negotiated protocol version and an observation sink. */
	public DefaultAcpHarnessClient(AcpAsyncClient acp, AcpHarnessRuntime runtime, AcpHarnessSettings settings,
			SessionRegistry sessions, SessionUpdateRouter router, @Nullable SessionConfigRecorder recorder,
			AcpSchema.@Nullable InitializeResponse initialized, int protocolVersion,
			@Nullable AcpHarnessObservations observations, @Nullable BooleanSupplier alive,
			@Nullable Runnable onClose) {
		this.protocolVersion = protocolVersion;
		this.observations = observations == null ? AcpHarnessObservations.NONE : observations;
		this.acp = acp;
		this.runtime = runtime;
		this.settings = settings;
		this.sessions = sessions;
		this.router = router;
		this.recorder = recorder == null ? new SessionConfigRecorder() : recorder;
		this.configResolver = new ConfigResolver(runtime, settings.onUnsupported());
		this.harnessInfo = initialized == null ? null : AcpHarnessInfo.from(initialized.agentInfo());
		this.mcpAccess = new McpAccess(settings.mcp().credentials(), settings.timeout(),
				runtime.mcpRequestFilters(settings), runtime.mcpScope());
		this.sessionOperations = new DefaultAcpHarnessSessions(acp, runtime.id(), settings,
				initialized == null ? null : initialized.agentCapabilities(), sessions, this.recorder,
				session -> configure(session, settings), mcpAccess);
		this.alive = alive;
		this.onClose = onClose == null ? () -> {
		} : onClose;
	}

	/**
	 * Gives this client the watcher on the agent's own log.
	 *
	 * <p>
	 * Not a constructor parameter because it is optional, runtime-specific and already
	 * the eleventh thing this class is handed; set once, by
	 * {@code AcpHarnessClientFactory}, before anything can call {@link #notices()}.
	 */
	void watch(@Nullable AcpHarnessLogWatcher watcher) {
		this.watcher = watcher;
	}

	@Override
	public List<AcpHarnessNotice> notices() {
		AcpHarnessLogWatcher current = watcher;
		return current == null ? List.of() : current.notices();
	}

	@Override
	public boolean isAlive() {
		return !closed.get() && (alive == null || alive.getAsBoolean());
	}

	@Override
	public int protocolVersion() {
		return protocolVersion;
	}

	@Override
	public @Nullable AcpHarnessInfo harnessInfo() {
		return harnessInfo;
	}

	@Override
	public AcpHarnessSessions sessions() {
		return sessionOperations;
	}

	@Override
	public PromptSpec prompt() {
		return new DefaultPromptSpec();
	}

	@Override
	public String runtimeId() {
		return runtime.id();
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		sessions.all().forEach(s -> closeRemote(s.sessionId()));
		sessions.clear();
		try {
			acp.closeGracefully().block(Duration.ofSeconds(10));
		}
		catch (RuntimeException ex) {
			logger.debug("Graceful close did not complete cleanly", ex);
		}
		finally {
			mcpAccess.close();
			onClose.run();
		}
	}

	@Override
	public void evictIdleSessions() {
		sessions.evictIdle(settings.sessionTtl()).forEach(s -> closeRemote(s.sessionId()));
	}

	/**
	 * Closes a session on the agent and waits for the acknowledgement.
	 *
	 * <p>
	 * Only safe from a thread that is not the one delivering the agent's replies, which
	 * in practice means the lifecycle path: {@link #close()} and idle eviction. See
	 * {@link #closeRemoteWithoutWaiting} for why the distinction is not a stylistic one.
	 */
	private void closeRemote(String sessionId) {
		recorder.forget(sessionId);
		try {
			acp.closeSession(new AcpSchema.CloseSessionRequest(sessionId)).block(Duration.ofSeconds(5));
		}
		catch (RuntimeException ex) {
			// The agent may not support session/close, or may already have dropped the
			// session.
			logger.debug("Could not close session {} on the agent", sessionId, ex);
		}
	}

	/**
	 * Closes a session on the agent without waiting, for the end of a throwaway turn.
	 *
	 * <p>
	 * The waiting version cannot be used here, and the reason is worth writing down. A
	 * turn's teardown runs on whichever thread delivered the turn's last event, and for a
	 * stdio agent that is the transport's own inbound thread — the thread that would have
	 * to deliver the reply this call is waiting for. Blocking it deadlocks, and the
	 * deadlock is invisible because the five-second timeout resolves it: every ephemeral
	 * turn simply took five seconds longer than it should have, and the session was left
	 * open on the agent anyway. Nothing failed, so nothing said so.
	 */
	private void closeRemoteWithoutWaiting(String sessionId) {
		recorder.forget(sessionId);
		acp.closeSession(new AcpSchema.CloseSessionRequest(sessionId)).subscribe(ignored -> {
		}, ex -> logger.debug("Could not close session {} on the agent", sessionId, ex));
	}

	@Override
	public AcpHarnessSession openSession(String name) {
		return openSession(name, settings, currentPrincipal());
	}

	@Override
	public AcpHarnessSession openSession(String name, @Nullable SessionPrincipal principal) {
		return openSession(name, settings, principal);
	}

	/**
	 * Asked on the caller's thread, never later: see {@link SessionPrincipalResolver}.
	 */
	private @Nullable SessionPrincipal currentPrincipal() {
		return settings.mcp().principals().current();
	}

	/**
	 * Opens — or reuses — the named session, and records what the agent advertised about
	 * it.
	 *
	 * <p>
	 * The advertised configuration comes from two places because the SDK splits it:
	 * {@code modes} and {@code models} off the typed response, {@code configOptions} out
	 * of the recorder, which is the only way to see a field {@code NewSessionResponse}
	 * drops.
	 *
	 * <p>
	 * The MCP grant is taken inside the factory, so only a session that is really being
	 * created asks for credentials, and it is released there if {@code session/new}
	 * fails; once the session exists the registry owns it and releases it whenever the
	 * session is forgotten.
	 */
	private AcpHarnessSession openSession(String name, AcpHarnessSettings effective,
			@Nullable SessionPrincipal principal) {
		AtomicReference<AdvertisedSessionConfig> advertised = new AtomicReference<>();
		AtomicBoolean opened = new AtomicBoolean();
		AcpHarnessSession session = sessions.resolve(name, principal, n -> {
			opened.set(true);
			McpAccess.Grant grant = mcpAccess.grant(principal, effective.mcpServers());
			try {
				logMcpServers(effective, grant, "session/new");
				AcpSchema.NewSessionResponse response = awaitNewSession(n,
						acp.newSession(new AcpSchema.NewSessionRequest(effective.workspace().toString(),
								grant.servers().stream().map(m -> m.toAcp()).toList())),
						effective.timeout());
				if (response == null || response.sessionId() == null) {
					throw new AcpHarnessClientException("Agent did not return a session id for '" + n + "'");
				}
				advertised.set(new AdvertisedSessionConfig(recorder.configOptionsFor(response.sessionId()),
						response.modes(), response.models()));
				return new SessionRegistry.Opened(response.sessionId(), grant::close);
			}
			catch (RuntimeException ex) {
				grant.close();
				throw ex;
			}
		});
		if (advertised.get() != null) {
			session.advertised(advertised.get());
		}
		if (opened.get()) {
			failIfAnMcpServerDidNotLoad(name, session, effective);
		}
		try {
			configure(session, effective);
		}
		catch (RuntimeException ex) {
			// A session this call created has no conversation to keep yet, and a caller
			// that gets an error has no reason to think it exists.
			if (opened.get()) {
				forgetAndClose(name);
			}
			throw ex;
		}
		return session;
	}

	/**
	 * Waits for {@code session/new}, and if the caller stops waiting first, closes the
	 * session the agent may still be creating: the request is already on the wire, and an
	 * answer that arrives after the caller has gone would name a session nothing could
	 * close.
	 *
	 * <p>
	 * That covers an interrupt, which is what a cancelled turn on {@code boundedElastic}
	 * delivers. It cannot cover the timeout: the SDK bounds every request by the same
	 * {@code timeout} and drops an answer that comes after it, so a session the agent
	 * creates that late stays on the agent.
	 */
	private AcpSchema.NewSessionResponse awaitNewSession(String name, Mono<AcpSchema.NewSessionResponse> request,
			Duration timeout) {
		// Reactor's future completes with null for an empty Mono; the SDK's never is.
		CompletableFuture<AcpSchema.@Nullable NewSessionResponse> answer = request.toFuture();
		try {
			AcpSchema.NewSessionResponse response = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (response == null) {
				throw new AcpHarnessClientException("Agent answered session/new for '" + name + "' with nothing");
			}
			return response;
		}
		catch (TimeoutException ex) {
			closeWhenAnswered(name, answer);
			throw notAnswered(name, timeout);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			closeWhenAnswered(name, answer);
			throw new AcpHarnessClientException("Interrupted waiting for session/new for '" + name + "'", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof TimeoutException) {
				throw notAnswered(name, timeout);
			}
			if (ex.getCause() instanceof RuntimeException failure) {
				throw failure;
			}
			throw new AcpHarnessClientException("session/new failed for '" + name + "'", ex.getCause());
		}
	}

	private AcpHarnessClientException notAnswered(String name, Duration timeout) {
		return new AcpHarnessClientException("Agent did not answer session/new for '" + name + "' within " + timeout);
	}

	/** Runs on the thread delivering the late answer, so it must not wait. */
	private void closeWhenAnswered(String name, CompletableFuture<AcpSchema.@Nullable NewSessionResponse> answer) {
		answer.thenAccept(late -> {
			if (late != null && late.sessionId() != null) {
				logger.warn("Agent answered session/new for '{}' after the caller gave up; closing session {}", name,
						late.sessionId());
				closeRemoteWithoutWaiting(late.sessionId());
			}
		});
	}

	/**
	 * Forgets a session and closes it on the agent, waiting for the acknowledgement. For
	 * the caller's thread; see {@link #closeRemote}.
	 */
	private void forgetAndClose(String name) {
		AcpHarnessSession session = sessions.remove(name);
		if (session != null) {
			closeRemote(session.sessionId());
		}
	}

	/**
	 * Same without waiting, for teardown at the end of a turn; see
	 * {@link #closeRemoteWithoutWaiting}.
	 */
	private void forgetAndCloseWithoutWaiting(String name) {
		AcpHarnessSession session = sessions.remove(name);
		if (session != null) {
			closeRemoteWithoutWaiting(session.sessionId());
		}
	}

	/**
	 * Refuses a session whose MCP servers the agent says it could not load.
	 *
	 * <p>
	 * Off unless the application asks for it, and it can only ever act on a report the
	 * agent actually made — silence opens the session, because silence is the normal case
	 * and the whole point of the feature is that this library never infers an MCP failure
	 * it was not told about.
	 *
	 * <p>
	 * The wait is what makes it reliable rather than a coin toss: the agent writes the
	 * line and answers {@code session/new} within the same tenth of a second, in no
	 * guaranteed order. The session is closed on the way out, because an application that
	 * asked to fail did not ask to leave a half-equipped session open on the agent.
	 *
	 * <p>
	 * Only ever on the turn that opened the session. A named session is resolved again on
	 * every prompt, and waiting out the detection window each time would charge every
	 * later turn for a report that can only arrive when the servers are first handed
	 * over.
	 */
	private void failIfAnMcpServerDidNotLoad(String name, AcpHarnessSession session, AcpHarnessSettings effective) {
		AcpHarnessLogWatcher current = watcher;
		if (current == null || effective.mcpServers().isEmpty()
				|| effective.mcp().onServerFailure() != McpSettings.OnServerFailure.FAIL) {
			return;
		}
		for (McpServerSpec server : effective.mcpServers()) {
			AcpHarnessNotice failure = current.awaitNotice(server.name(), effective.mcp().detectTimeout());
			if (failure == null) {
				continue;
			}
			forgetAndClose(name);
			throw new AcpHarnessClientException("Agent '" + runtime.id() + "' could not load MCP server '"
					+ server.name() + "', so this session would have run without its tools; the agent reported: "
					+ failure.detail());
		}
	}

	/**
	 * Names the MCP servers this session asks the agent to connect to.
	 *
	 * <p>
	 * At INFO on purpose, and not something the library can do better. An agent that
	 * fails to connect to an MCP server reports nothing over ACP — {@code session/new}
	 * succeeds, no {@code session/update} is sent, and on goose 1.51 not even a line on
	 * stderr — so the application gets a working session whose model silently has no
	 * tools, and the only symptom is the model saying so in prose. This cannot detect
	 * that. It does put what was requested in the log beside it, which is most of the
	 * distance to the diagnosis. See {@code docs/configuration/mcp-servers.md}.
	 */
	private static void logMcpServers(AcpHarnessSettings effective, McpAccess.Grant grant, String method) {
		List<String> handed = McpAccess.describeHanded(effective.mcpServers(), grant);
		if (handed.isEmpty() || !logger.isInfoEnabled()) {
			return;
		}
		logger.info("Handing {} MCP server(s) to the agent on {}: {}. The agent does not report back "
				+ "whether it connected to them.", handed.size(), method, handed);
	}

	/**
	 * Applies the negotiated tier, once per session rather than once per turn.
	 *
	 * <p>
	 * Re-sending the same three options before every turn would be wire traffic that
	 * cannot change anything, so this skips the work when the request is the one already
	 * applied. It does not skip when a turn carried per-request overrides, because then
	 * the request genuinely differs from the session's current state and the agent has to
	 * be told.
	 */
	private void configure(AcpHarnessSession session, AcpHarnessSettings effective) {
		if (isAlreadyRequested(session.configuration(), effective)) {
			return;
		}
		configResolver.apply(acp, session, effective).block(effective.timeout());
	}

	private static boolean isAlreadyRequested(SessionConfiguration applied, AcpHarnessSettings effective) {
		return same(applied.of(PortableOption.MODEL).requested(), effective.model())
				&& same(applied.of(PortableOption.PROVIDER).requested(), effective.provider().id())
				&& same(applied.of(PortableOption.MODE).requested(), effective.mode());
	}

	private static boolean same(@Nullable String applied, @Nullable String requested) {
		return Objects.equals(blankToNull(applied), blankToNull(requested));
	}

	private static @Nullable String blankToNull(@Nullable String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private final class DefaultPromptSpec implements PromptSpec {

		private final StringBuilder text = new StringBuilder();

		private @Nullable String sessionName;

		private AcpHarnessOptions options = AcpHarnessOptions.none();

		private @Nullable SessionPrincipal principal;

		private @Nullable Duration busyWait;

		@Override
		public PromptSpec session(String name) {
			this.sessionName = name;
			return this;
		}

		@Override
		public PromptSpec principal(@Nullable SessionPrincipal principal) {
			this.principal = principal;
			return this;
		}

		@Override
		public PromptSpec waitIfBusy(@Nullable Duration wait) {
			if (wait != null && wait.isNegative()) {
				throw new IllegalArgumentException("wait must not be negative but was " + wait);
			}
			this.busyWait = wait;
			return this;
		}

		@Override
		public PromptSpec user(String content) {
			if (content != null && !content.isEmpty()) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(content);
			}
			return this;
		}

		@Override
		public PromptSpec options(@Nullable AcpHarnessOptions options) {
			this.options = options == null ? AcpHarnessOptions.none() : options;
			return this;
		}

		@Override
		public PromptSpec options(Consumer<AcpHarnessOptions.Builder> customizer) {
			AcpHarnessOptions.Builder builder = AcpHarnessOptions.builder();
			customizer.accept(builder);
			return options(builder.build());
		}

		@Override
		public AcpHarnessResponse call() {
			StringBuilder content = new StringBuilder();
			AtomicReference<AcpHarnessEvent.Completed> completion = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();

			stream().events().doOnNext(event -> {
				switch (event) {
					case AcpHarnessEvent.Text t -> content.append(t.text());
					case AcpHarnessEvent.Completed c -> completion.set(c);
					case AcpHarnessEvent.Failed f -> failure.set(f.cause());
					default -> {
					}
				}
			}).blockLast();

			if (failure.get() != null) {
				throw new AcpHarnessClientException("Agent turn failed", failure.get());
			}
			if (completion.get() == null) {
				// Would mean the terminal-event contract was broken upstream.
				throw new AcpHarnessClientException("Agent turn ended without a terminal event");
			}
			return new DefaultAcpHarnessResponse(content.toString(), completion.get());
		}

		@Override
		public AcpHarnessStream stream() {
			if (text.isEmpty()) {
				throw new IllegalStateException("prompt is empty; call user(...) before call() or stream()");
			}
			AcpHarnessSettings effective = settings.merge(options);
			// Resolved now, on the caller's thread, not inside the deferred turn below.
			SessionPrincipal owner = principal != null ? principal : currentPrincipal();
			String named = sessionName;
			boolean ephemeral = named == null;
			Duration wait = busyWait != null ? busyWait : effective.timeout();
			List<AcpSchema.ContentBlock> prompt = List.of(new AcpSchema.TextContent(text.toString()));

			Flux<AcpHarnessEvent> events = Flux.defer(() -> {
				// Named per subscription, so subscribing twice is two throwaway turns.
				String name = named != null ? named : "turn-" + UUID.randomUUID();
				AcpHarnessSession session = openSession(name, effective, owner);
				try {
					acquireTurn(session, wait);
				}
				catch (RuntimeException ex) {
					if (ephemeral) {
						forgetAndCloseWithoutWaiting(name);
					}
					throw ex;
				}
				try {
					// A turn with per-request overrides re-negotiates; an unchanged one
					// does not.
					configure(session, effective);
				}
				catch (RuntimeException ex) {
					session.endTurn();
					if (ephemeral) {
						forgetAndCloseWithoutWaiting(name);
					}
					throw ex;
				}
				// The session is free again before the caller hears the turn is over, so
				// a caller that starts the next turn as soon as this one ends finds it
				// free, rather than racing a cleanup that runs after its onComplete. Once
				// only: by the time doFinally runs, the next turn may hold the permit.
				AtomicBoolean ended = new AtomicBoolean();
				Runnable endTurn = () -> {
					if (ended.compareAndSet(false, true)) {
						session.endTurn();
					}
				};
				return AcpHarnessTurn.on(acp, router, observations, runtime::planOf)
					.prompt(session, prompt, effective.timeout(), turnContext(session, effective, ephemeral))
					.doOnTerminate(endTurn)
					.doOnCancel(endTurn)
					.doFinally(signal -> {
						endTurn.run();
						if (ephemeral) {
							forgetAndCloseWithoutWaiting(name);
						}
					});
			});
			return new DefaultAcpHarnessStream(events);
		}

		private void acquireTurn(AcpHarnessSession session, Duration wait) {
			try {
				if (!session.tryBeginTurn(wait)) {
					throw new AcpHarnessSessionBusyException(
							wait.isZero() ? "Session '" + session.name() + "' is busy with another turn"
									: "Session '" + session.name() + "' is busy with another turn after " + wait);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AcpHarnessClientException("Interrupted waiting for session '" + session.name() + "'", ex);
			}
		}

	}

	/**
	 * What an observation is told about this turn.
	 *
	 * <p>
	 * The model reported is the one the session is <em>really</em> using, and the order
	 * the three sources are tried in is the whole point. The negotiated tier's applied
	 * value first; then what the agent says it is currently set to, for the common case
	 * where the application asked for no model at all and the agent is running its own
	 * default; and only then the request. A dashboard grouped by the model an application
	 * asked for, while {@code on-unsupported: warn} quietly ran it on another, would be
	 * worse than having no dashboard — and one that said "unknown" for every application
	 * that never set {@code spring.ai.acp.harness.model} would be worse still.
	 */
	private static AcpHarnessObservations.TurnContext turnContext(AcpHarnessSession session,
			AcpHarnessSettings effective, boolean ephemeral) {
		String model = session.configuration().applied(PortableOption.MODEL);
		if (model == null) {
			model = currentModelOf(session);
		}
		if (model == null) {
			model = effective.model();
		}
		return new AcpHarnessObservations.TurnContext(effective.runtime(), session.name(), model, ephemeral);
	}

	/**
	 * The model the agent says this session is set to, or null when it advertises none.
	 *
	 * <p>
	 * Matched by the portable {@code model} category as well as the id, because that pair
	 * is all ACP standardizes — an adapter's candidate ids belong to the resolver, and a
	 * tag is not worth threading one through for.
	 */
	private static @Nullable String currentModelOf(AcpHarnessSession session) {
		AcpSchema.SessionConfigSelect select = session.advertised().select("model", "model");
		String current = select == null ? null : select.currentValue();
		if (current != null && !current.isBlank()) {
			return current;
		}
		AcpSchema.SessionModelState models = session.advertised().findModels();
		return models == null ? null : models.currentModelId();
	}

	private record DefaultAcpHarnessResponse(String content,
			AcpHarnessEvent.Completed completion) implements AcpHarnessResponse {
	}

	private record DefaultAcpHarnessStream(Flux<AcpHarnessEvent> events) implements AcpHarnessStream {
	}

	@Override
	public @Nullable AcpHarnessSession session(String name) {
		return sessions.find(name);
	}

}
