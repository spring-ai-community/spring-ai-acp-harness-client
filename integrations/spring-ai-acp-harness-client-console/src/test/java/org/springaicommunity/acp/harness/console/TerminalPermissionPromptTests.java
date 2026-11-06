package org.springaicommunity.acp.harness.console;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springaicommunity.acp.harness.permission.PermissionQuestion;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalPermissionPromptTests {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("allow", "Allow once",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE);

	private static final AcpSchema.PermissionOption ALWAYS = new AcpSchema.PermissionOption("always", null,
			AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);

	private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("reject", "Reject",
			AcpSchema.PermissionOptionKind.REJECT_ONCE);

	private static final PermissionQuestion QUESTION = new PermissionQuestion("developer__shell",
			"Run `./send-report.sh`", AcpSchema.ToolKind.EXECUTE, List.of(ALLOW, ALWAYS, REJECT));

	@Test
	void showsWhatTheAgentWantsAndTakesTheNumberedChoice() {
		TestTerminals.Scripted terminal = TestTerminals.typing("3\n");

		AcpSchema.PermissionOption chosen = prompt(terminal).ask(QUESTION);

		assertThat(chosen).isEqualTo(REJECT);
		assertThat(terminal.written()).contains("The agent wants to: Run `./send-report.sh`",
				"(execute · developer__shell)", "1) Allow once", "2) Always allow", "3) Reject");
	}

	@Test
	void asksAgainUntilTheAnswerIsOneOfTheOptions() {
		TestTerminals.Scripted terminal = TestTerminals.typing("yes\n9\n1\n");

		assertThat(prompt(terminal).ask(QUESTION)).isEqualTo(ALLOW);
		assertThat(terminal.written()).contains("answer with a number from 1 to 3");
	}

	@Test
	void endOfInputCancelsTheRequest() {
		TestTerminals.Scripted terminal = TestTerminals.typing("");

		assertThat(prompt(terminal).ask(QUESTION)).isNull();
		assertThat(terminal.written()).contains("request cancelled");
	}

	@Test
	void aRequestWithNoOptionsIsCancelledWithoutAsking() {
		TestTerminals.Scripted terminal = TestTerminals.typing("1\n");
		PermissionQuestion empty = new PermissionQuestion(null, "x", null, List.of());

		assertThat(prompt(terminal).ask(empty)).isNull();
		assertThat(terminal.written()).doesNotContain("wants to");
	}

	/**
	 * The client interrupts a prompt whose turn ended before the person answered; the
	 * question has already been answered "cancelled" for them.
	 */
	@Test
	void aQuestionWithdrawnWhileTheTerminalWaitsStopsWaiting() throws Exception {
		TestTerminals.Scripted terminal = TestTerminals.silent();
		java.util.concurrent.CompletableFuture<AcpSchema.PermissionOption> answer = new java.util.concurrent.CompletableFuture<>();
		Thread asking = new Thread(() -> answer.complete(prompt(terminal).ask(QUESTION)));
		asking.start();
		Thread.sleep(300);

		asking.interrupt();

		assertThat(answer.get(5, java.util.concurrent.TimeUnit.SECONDS)).isNull();
		assertThat(terminal.written()).contains("request cancelled");
	}

	@Test
	void aQuestionWithdrawnBeforeItIsShownIsNotShown() {
		TestTerminals.Scripted terminal = TestTerminals.typing("1\n");
		Thread.currentThread().interrupt();
		try {
			assertThat(prompt(terminal).ask(QUESTION)).isNull();
		}
		finally {
			Thread.interrupted();
		}
		assertThat(terminal.written()).doesNotContain("wants to");
	}

	private static TerminalPermissionPrompt prompt(TestTerminals.Scripted terminal) {
		return new TerminalPermissionPrompt(terminal.terminal(), new ConsoleRenderer(terminal.terminal(), true));
	}

}
