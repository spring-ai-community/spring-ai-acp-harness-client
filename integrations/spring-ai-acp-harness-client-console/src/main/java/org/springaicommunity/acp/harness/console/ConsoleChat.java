package org.springaicommunity.acp.harness.console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.runtime.AcpHarnessNotice;
import org.springaicommunity.acp.harness.session.AcpHarnessSession;
import org.springaicommunity.acp.harness.session.AcpHarnessSessions;
import org.springaicommunity.acp.harness.session.StoredSession;

import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The read-prompt-stream loop.
 *
 * <p>
 * Runs on a thread of its own once the application is ready, so startup finishes normally
 * and a web application keeps serving while someone talks to its agent here. When the
 * user leaves, the application context is closed, which stops the agent with it.
 *
 * <p>
 * Turns are streamed rather than called, because the interesting part of an agent is the
 * work it does before it answers, and a blocking call would show a blank terminal for a
 * minute and then a paragraph. Ctrl-C during a turn cancels that turn — disposing the
 * subscription is what {@code AcpHarnessClient} documents as cancelling it — and leaves
 * the conversation where it was.
 */
public class ConsoleChat implements ApplicationListener<ApplicationReadyEvent> {

	private static final Logger logger = LoggerFactory.getLogger(ConsoleChat.class);

	private static final List<String> COMMANDS = List.of("/help", "/new", "/sessions", "/resume", "/notices", "/exit",
			"/quit");

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final AcpHarnessClient agent;

	private final Terminal terminal;

	private final ConsoleRenderer out;

	private final String title;

	private final @Nullable String greeting;

	private final String baseSession;

	private final @Nullable Path historyFile;

	private final Runnable onExit;

	private final AtomicBoolean started = new AtomicBoolean();

	/** The turn in flight, for Ctrl-C to cancel; null between turns. */
	private final AtomicReference<@Nullable Disposable> turn = new AtomicReference<>();

	/**
	 * How long the agent must be quiet before what it said between turns is printed: it
	 * arrives in chunks, and printing each above the prompt would break it into lines.
	 */
	private static final Duration QUIET = Duration.ofMillis(800);

	/** What the agent says on the current session between turns; replaced with it. */
	private final Disposable.Swap listening = Disposables.swap();

	/** Set once reading starts, so what arrives between turns prints above the prompt. */
	private volatile @Nullable LineReader lineReader;

	/** Notices already shown, so each is reported once rather than after every turn. */
	private final Set<AcpHarnessNotice> seenNotices = new HashSet<>();

	/** The named session turns currently run in. */
	private String session;

	/**
	 * How many conversations {@code /new} and {@code /resume} have started, for naming
	 * the next.
	 */
	private int generation;

	/**
	 * @param historyFile where input history is kept, or null to keep it only for this
	 * run
	 * @param onExit what to do once the user leaves; the auto-configuration closes the
	 * application
	 */
	public ConsoleChat(AcpHarnessClient agent, Terminal terminal, ConsoleRenderer renderer, String title,
			@Nullable String greeting, String session, @Nullable Path historyFile, Runnable onExit) {
		this.agent = agent;
		this.terminal = terminal;
		this.out = renderer;
		this.title = title;
		this.greeting = greeting;
		this.baseSession = session;
		this.session = session;
		this.historyFile = historyFile;
		this.onExit = onExit;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		if (started.compareAndSet(false, true)) {
			Thread thread = new Thread(this::runThenExit, "acp-console");
			thread.setDaemon(false);
			thread.start();
		}
	}

	private void runThenExit() {
		try {
			run();
		}
		catch (RuntimeException ex) {
			logger.error("The ACP console stopped", ex);
		}
		finally {
			onExit.run();
		}
	}

	/** Greets, then reads and answers until the user leaves. Returns when they do. */
	public void run() {
		LineReader reader = reader();
		lineReader = reader;
		greet();
		try {
			while (true) {
				out.beforePrompt();
				String line;
				try {
					line = reader.readLine(out.prompt());
				}
				catch (UserInterruptException ex) {
					if (ex.getPartialLine() == null || ex.getPartialLine().isBlank()) {
						out.info("(/exit or Ctrl-D to quit)");
					}
					continue;
				}
				catch (EndOfFileException ex) {
					break;
				}
				String input = line.strip();
				if (input.isEmpty()) {
					continue;
				}
				if (input.startsWith("/")) {
					if (!command(input)) {
						break;
					}
					continue;
				}
				ask(input);
			}
		}
		finally {
			listening.dispose();
			saveHistory(reader);
		}
		out.bye();
	}

	/** The named session the next turn runs in. */
	public String session() {
		return session;
	}

	/**
	 * Opens the session before the first prompt rather than letting the first turn do it,
	 * so the model that was actually negotiated is on screen before any tokens are spent
	 * finding out, and a misconfigured agent says so now rather than after the first
	 * question.
	 */
	private void greet() {
		out.banner(title, agent.runtimeId());
		try {
			open(session);
		}
		catch (RuntimeException ex) {
			out.error(ConsoleRenderer.messageOf(ex));
		}
		showNewNotices();
		if (greeting != null && !greeting.isBlank()) {
			out.greeting(greeting.strip());
		}
	}

	private void open(String name) {
		AcpHarnessSession opened = agent.openSession(name);
		listen(name);
		opened.configuration()
			.resolutions()
			.values()
			.stream()
			.filter(resolution -> resolution.requested() != null)
			.forEach(resolution -> out.resolution(String.valueOf(resolution.option()), resolution.requested(),
					Objects.toString(resolution.applied(), "(not applied)"), String.valueOf(resolution.mechanism())));
	}

	private void ask(String question) {
		CountDownLatch done = new CountDownLatch(1);
		// Ctrl-C is ours only while a turn runs: at the prompt JLine turns it into an
		// interrupt of the line, and before the first prompt it should still stop the
		// application.
		Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> cancel());
		// Published before subscribing, because the turn starts inside subscribe(): a
		// cancel that lands before subscribe() returns disposes the holder, and update()
		// then disposes the subscription it is handed.
		Disposable.Swap subscription = Disposables.swap();
		turn.set(subscription);
		try {
			subscription.update(agent.prompt()
				.session(session)
				.user(question)
				.stream()
				.events()
				.doFinally(signal -> done.countDown())
				.subscribe(out::render, error -> out.error(ConsoleRenderer.messageOf(error))));
			try {
				done.await();
			}
			catch (InterruptedException ex) {
				subscription.dispose();
				Thread.currentThread().interrupt();
			}
		}
		catch (RuntimeException ex) {
			out.error(ConsoleRenderer.messageOf(ex));
		}
		finally {
			turn.set(null);
			terminal.handle(Terminal.Signal.INT, previous);
		}
		out.endOfTurn();
		showNewNotices();
	}

	/**
	 * Prints what the agent says on {@code name} while no turn runs, in place of whatever
	 * session was listened to before. Text only, gathered until the agent goes quiet.
	 */
	private void listen(String name) {
		try {
			Flux<String> text = agent.unprompted(name)
				.ofType(AcpHarnessEvent.Text.class)
				.map(AcpHarnessEvent.Text::text);
			listening.update(text.publish(shared -> shared.buffer(shared.sampleTimeout(chunk -> Mono.delay(QUIET))))
				.map(chunks -> String.join("", chunks))
				.filter(said -> !said.isBlank())
				.subscribe(this::showUnprompted,
						error -> logger.debug("Stopped listening to session {}: {}", name, error.getMessage())));
		}
		catch (RuntimeException ex) {
			logger.debug("Cannot listen to session {}: {}", name, ex.getMessage());
		}
	}

	private void showUnprompted(String said) {
		String shown = out.unprompted(said);
		LineReader reader = lineReader;
		if (reader != null) {
			reader.printAbove(shown);
		}
		else {
			terminal.writer().println(shown);
			terminal.writer().flush();
		}
	}

	/** Cancels the turn in flight, if there is one. What Ctrl-C does during a turn. */
	public void cancel() {
		Disposable current = turn.getAndSet(null);
		if (current != null && !current.isDisposed()) {
			current.dispose();
			out.cancelled();
		}
	}

	/**
	 * @return false when the command is to leave
	 */
	private boolean command(String input) {
		String[] parts = input.split("\\s+", 2);
		String argument = parts.length > 1 ? parts[1].strip() : "";
		try {
			switch (parts[0].toLowerCase(Locale.ROOT)) {
				case "/exit", "/quit" -> {
					return false;
				}
				case "/help" -> help();
				case "/new" -> startNew();
				case "/sessions" -> listSessions();
				case "/resume" -> resume(argument);
				case "/notices" -> notices();
				default -> out.error("unknown command " + parts[0] + " — /help lists them");
			}
		}
		catch (RuntimeException ex) {
			out.error(ConsoleRenderer.messageOf(ex));
		}
		return true;
	}

	private void help() {
		out.info("  /new              start a new conversation");
		out.info("  /sessions         list the conversations the agent has stored");
		out.info("  /resume <id>      continue a stored conversation");
		out.info("  /notices          what the agent reported outside the protocol");
		out.info("  /exit             quit (or Ctrl-D)");
		out.info("  Ctrl-C during a turn cancels it.");
	}

	private void startNew() {
		String previous = session;
		String next = nextName();
		open(next);
		session = next;
		if (agent.sessions().supports(AcpHarnessSessions.Operation.CLOSE)) {
			try {
				agent.sessions().close(previous);
			}
			catch (RuntimeException ex) {
				logger.debug("Could not close session {}: {}", previous, ex.getMessage());
			}
		}
		out.info("  new conversation");
	}

	private void listSessions() {
		AcpHarnessSessions sessions = agent.sessions();
		if (!sessions.supports(AcpHarnessSessions.Operation.LIST)) {
			out.info("  " + agent.runtimeId() + " cannot list its conversations (no "
					+ AcpHarnessSessions.Operation.LIST.method() + ")");
			return;
		}
		List<StoredSession> stored = sessions.list();
		if (stored.isEmpty()) {
			out.info("  no stored conversations");
			return;
		}
		AcpHarnessSession open = agent.session(session);
		String current = open == null ? null : open.sessionId();
		for (StoredSession each : stored) {
			String marker = each.sessionId().equals(current) ? "* " : "  ";
			String when = each.updatedAt() == null ? "" : "  " + WHEN.format(each.updatedAt());
			String name = each.title() == null || each.title().isBlank() ? "" : "  " + each.title().strip();
			out.info("  " + marker + each.sessionId() + when + name);
		}
	}

	private void resume(String sessionId) {
		if (sessionId.isEmpty()) {
			out.error("usage: /resume <id> — /sessions lists them");
			return;
		}
		AcpHarnessSessions sessions = agent.sessions();
		String next = nextName();
		if (sessions.supports(AcpHarnessSessions.Operation.RESUME)) {
			sessions.resume(next, sessionId);
		}
		else if (sessions.supports(AcpHarnessSessions.Operation.LOAD)) {
			sessions.load(next, sessionId);
		}
		else {
			out.info("  " + agent.runtimeId() + " can neither resume nor load a conversation (no "
					+ AcpHarnessSessions.Operation.RESUME.method() + " or " + AcpHarnessSessions.Operation.LOAD.method()
					+ ")");
			return;
		}
		session = next;
		listen(next);
		out.info("  continuing " + sessionId);
	}

	private void notices() {
		List<AcpHarnessNotice> notices = agent.notices();
		if (notices.isEmpty()) {
			out.info("  no notices from the agent");
			return;
		}
		notices.forEach(notice -> {
			seenNotices.add(notice);
			out.notice(notice);
		});
	}

	/**
	 * Shows what the agent reported outside the protocol since last time — most usefully
	 * an MCP server it could not load, which would otherwise surface only as an agent
	 * that has no tools.
	 */
	private void showNewNotices() {
		try {
			agent.notices().stream().filter(seenNotices::add).forEach(out::notice);
		}
		catch (RuntimeException ex) {
			logger.debug("Could not read the agent's notices: {}", ex.getMessage());
		}
	}

	private String nextName() {
		return baseSession + "-" + (++generation);
	}

	private LineReader reader() {
		// Chat is prose, not a command line: an apostrophe is not an unclosed quote and a
		// backslash
		// is not an escape.
		DefaultParser parser = new DefaultParser();
		parser.setQuoteChars(new char[0]);
		parser.setEscapeChars(new char[0]);
		LineReaderBuilder builder = LineReaderBuilder.builder()
			.terminal(terminal)
			.appName(title)
			.parser(parser)
			.completer((reader, line, candidates) -> {
				if (line.wordIndex() == 0 && line.word().startsWith("/")) {
					COMMANDS.forEach(command -> candidates.add(new Candidate(command)));
				}
			})
			// "!" is punctuation here, not a reference to an earlier line.
			.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
		Path history = persistentHistory();
		if (history != null) {
			builder.variable(LineReader.HISTORY_FILE, history);
		}
		return builder.build();
	}

	/**
	 * The history file, when there is a person typing to keep it for: input piped into a
	 * dumb terminal is a script, and does not belong in anyone's history.
	 */
	private @Nullable Path persistentHistory() {
		if (historyFile == null || terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
			return null;
		}
		try {
			Path parent = historyFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			return historyFile;
		}
		catch (IOException ex) {
			logger.debug("Not keeping console history at {}: {}", historyFile, ex.getMessage());
			return null;
		}
	}

	private static void saveHistory(LineReader reader) {
		try {
			reader.getHistory().save();
		}
		catch (IOException ex) {
			logger.debug("Could not save console history: {}", ex.getMessage());
		}
	}

}
