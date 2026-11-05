package org.springaicommunity.acp.harness.executor;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.client.AcpHarnessInfo;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.config.AcpHarnessOptions;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link AcpHarnessExecutor} over an {@link AcpHarnessClient}.
 *
 * <p>
 * A thin adapter on purpose. Everything it does — sessions, turns, the one-terminal-event
 * invariant, cancellation on close — is the client's; what this adds is the older shape
 * of the calls and the older shape of the output.
 */
public final class DefaultAcpHarnessExecutor implements AcpHarnessExecutor {

	private final AcpHarnessClient client;

	private final LegacyEventFormat format;

	public DefaultAcpHarnessExecutor(AcpHarnessClient client) {
		this.client = client;
		this.format = new LegacyEventFormat(client.runtimeId());
	}

	@Override
	public String execute(String prompt) {
		return execute(prompt, AcpHarnessOptions.none());
	}

	@Override
	public String execute(String prompt, AcpHarnessOptions options) {
		return client.prompt(require(prompt)).options(options).call().content();
	}

	@Override
	public CompletableFuture<String> executeAsync(String prompt) {
		return executeAsync(prompt, AcpHarnessOptions.none());
	}

	@Override
	public CompletableFuture<String> executeAsync(String prompt, AcpHarnessOptions options) {
		// The blocking call is real work on a real thread; boundedElastic is where that
		// belongs. execute() never returns null, so the Mono never completes empty: the
		// last step only narrows Reactor's CompletableFuture<@Nullable T> back.
		return Mono.fromSupplier(() -> execute(prompt, options))
			.subscribeOn(Schedulers.boundedElastic())
			.toFuture()
			.thenApply(Objects::requireNonNull);
	}

	@Override
	public Stream<String> executeStreaming(String prompt) {
		return executeStreaming(prompt, AcpHarnessOptions.none());
	}

	@Override
	public Stream<String> executeStreaming(String prompt, AcpHarnessOptions options) {
		return text(client.prompt(require(prompt)).options(options).stream().events());
	}

	@Override
	public boolean isAvailable() {
		return client.isAlive();
	}

	@Override
	public @Nullable String getVersion() {
		AcpHarnessInfo info = client.harnessInfo();
		return info == null ? null : info.version();
	}

	@Override
	public String executeInSession(String sessionName, String prompt, boolean resume) {
		return executeInSession(sessionName, prompt, resume, AcpHarnessOptions.none());
	}

	@Override
	public String executeInSession(String sessionName, String prompt, boolean resume, AcpHarnessOptions options) {
		return client.prompt().session(sessionName).user(require(prompt)).options(options).call().content();
	}

	@Override
	public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume) {
		return executeInSessionStreaming(sessionName, prompt, resume, AcpHarnessOptions.none());
	}

	@Override
	public Stream<String> executeInSessionStreaming(String sessionName, String prompt, boolean resume,
			AcpHarnessOptions options) {
		return text(client.prompt().session(sessionName).user(require(prompt)).options(options).stream().events());
	}

	@Override
	public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume) {
		return executeInSessionStreamingJson(sessionName, prompt, resume, AcpHarnessOptions.none());
	}

	@Override
	public Stream<String> executeInSessionStreamingJson(String sessionName, String prompt, boolean resume,
			AcpHarnessOptions options) {
		return client.prompt()
			.session(sessionName)
			.user(require(prompt))
			.options(options)
			.stream()
			.events()
			.flatMapIterable(format::render)
			.toStream();
	}

	/**
	 * Assistant text only, one line per chunk.
	 *
	 * <p>
	 * {@code Flux.toStream} is what makes closing the stream cancel the turn: the
	 * returned stream holds the subscription, and its {@code close} disposes it, which
	 * reaches {@code AcpHarnessTurn} as consumer cancellation and sends
	 * {@code session/cancel}.
	 */
	private Stream<String> text(Flux<AcpHarnessEvent> events) {
		return events.filter(AcpHarnessEvent.Text.class::isInstance)
			.map(AcpHarnessEvent.Text.class::cast)
			.map(AcpHarnessEvent.Text::text)
			.toStream();
	}

	private static String require(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be blank");
		}
		return prompt;
	}

}
