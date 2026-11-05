package org.springaicommunity.acp.harness.console;

import java.util.List;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.permission.PermissionPrompt;
import org.springaicommunity.acp.harness.permission.PermissionQuestion;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Puts an agent's permission request to the person at the terminal, as a numbered choice
 * between the options the agent offered.
 *
 * <p>
 * Called on a client thread while the console thread waits for the turn, so reading the
 * terminal here does not compete with the prompt. It has a line reader of its own so that
 * "1" and "2" do not end up in the conversation's input history. Ctrl-C or end of input
 * cancels the request, which the agent hears as a cancellation rather than a refusal.
 */
public class TerminalPermissionPrompt implements PermissionPrompt {

	private final ConsoleRenderer renderer;

	private final LineReader reader;

	public TerminalPermissionPrompt(Terminal terminal, ConsoleRenderer renderer) {
		this.renderer = renderer;
		this.reader = LineReaderBuilder.builder()
			.terminal(terminal)
			.history(new DefaultHistory())
			.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
			.variable(LineReader.DISABLE_HISTORY, true)
			.build();
	}

	@Override
	public synchronized AcpSchema.@Nullable PermissionOption ask(PermissionQuestion question) {
		List<AcpSchema.PermissionOption> options = question.options();
		if (options.isEmpty()) {
			return null;
		}
		renderer.permission(question);
		while (true) {
			String answer;
			try {
				answer = reader.readLine(renderer.choicePrompt(options.size()));
			}
			catch (UserInterruptException | EndOfFileException ex) {
				renderer.info("  request cancelled");
				return null;
			}
			AcpSchema.PermissionOption chosen = choose(answer, options);
			if (chosen != null) {
				return chosen;
			}
			renderer.info("  answer with a number from 1 to " + options.size());
		}
	}

	private static AcpSchema.@Nullable PermissionOption choose(String answer,
			List<AcpSchema.PermissionOption> options) {
		try {
			int index = Integer.parseInt(answer.strip()) - 1;
			return index >= 0 && index < options.size() ? options.get(index) : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
