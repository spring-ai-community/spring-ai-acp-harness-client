package org.springaicommunity.acp.harness.console;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code spring.ai.acp.harness.console.*}: the terminal chat over the application's
 * agent.
 *
 * <p>
 * Everything here is presentation. What the agent is — runtime, model, MCP servers,
 * skills — stays under {@code spring.ai.acp.harness.*}, so the same configuration serves
 * this console, a web front end and a test.
 */
@ConfigurationProperties("spring.ai.acp.harness.console")
public class ConsoleProperties {

	/**
	 * Whether the console runs. Unset: only when the application is attached to an
	 * interactive terminal, so a test, a CI job or a deployed service never waits on
	 * stdin. {@code true}: always, reading plain lines when there is no terminal.
	 * {@code false}: never.
	 */
	private @Nullable Boolean enabled;

	/** The heading shown when the console starts. Defaults to spring.application.name. */
	private @Nullable String title;

	/** Shown once before the first prompt, to tell the user what to ask. */
	private @Nullable String greeting;

	/**
	 * The named session the conversation runs in, so the agent remembers earlier turns.
	 * {@code /new} moves to a fresh one.
	 */
	private String session = "console";

	/**
	 * Where input history is kept across runs. Defaults to
	 * {@code <config dir>/<spring.application.name>/console-history}.
	 */
	private @Nullable Path historyFile;

	/** Whether to show the reasoning the agent surfaces, one dimmed line per thought. */
	private boolean showThoughts = true;

	/**
	 * The history file in effect: the one configured, or one under the same config
	 * directory the MCP sign-ins use ({@code $XDG_CONFIG_HOME}, else {@code ~/.config}).
	 */
	Path effectiveHistoryFile(String application) {
		if (historyFile != null) {
			return historyFile;
		}
		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path config = xdg == null || xdg.isBlank() ? Paths.get(System.getProperty("user.home"), ".config")
				: Paths.get(xdg);
		return config.resolve(application).resolve("console-history");
	}

	public @Nullable Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(@Nullable Boolean enabled) {
		this.enabled = enabled;
	}

	public @Nullable String getTitle() {
		return title;
	}

	public void setTitle(@Nullable String title) {
		this.title = title;
	}

	public @Nullable String getGreeting() {
		return greeting;
	}

	public void setGreeting(@Nullable String greeting) {
		this.greeting = greeting;
	}

	public String getSession() {
		return session;
	}

	public void setSession(String session) {
		this.session = session;
	}

	public @Nullable Path getHistoryFile() {
		return historyFile;
	}

	public void setHistoryFile(@Nullable Path historyFile) {
		this.historyFile = historyFile;
	}

	public boolean isShowThoughts() {
		return showThoughts;
	}

	public void setShowThoughts(boolean showThoughts) {
		this.showThoughts = showThoughts;
	}

}
