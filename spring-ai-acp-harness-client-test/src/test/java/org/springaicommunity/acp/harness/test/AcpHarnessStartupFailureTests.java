package org.springaicommunity.acp.harness.test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.client.AcpHarnessClientFactory;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.runtime.AcpHarnessLaunchSpec;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an operator is told when an agent refuses to start.
 *
 * <p>
 * This has a test because getting it wrong is cheap and costs someone an afternoon. An
 * agent that will not start explains itself on stderr — an invalid config file, a missing
 * login — while the only thing this library observes is a handshake that never completed.
 * Reporting just the handshake gives an operator a message that names the runtime and
 * nothing else, for a problem the agent already described in full.
 */
class AcpHarnessStartupFailureTests {

	@TempDir
	Path workspace;

	@Test
	void theAgentsOwnExplanationIsInTheException() {
		assertThatThrownBy(() -> AcpHarnessClientFactory
			.create(new FailingRuntime("Configuration is invalid at line 3"), settings()))
			.isInstanceOf(AcpHarnessClientException.class)
			.hasMessageContaining("Failed to initialize runtime 'failing'")
			.hasMessageContaining("Configuration is invalid at line 3");
	}

	@Test
	void theColourCodesAnAgentWritesForATerminalAreStrippedOut() {
		// OpenCode's real complaint arrives as "\u001B[91m\u001B[1mError:
		// \u001B[0mConfiguration is
		// invalid …", and an exception message is not a terminal.
		assertThatThrownBy(() -> AcpHarnessClientFactory
			.create(new FailingRuntime("\u001B[91m\u001B[1mError: \u001B[0mbad config"), settings()))
			.hasMessageContaining("Error: bad config")
			.hasMessageNotContaining("\u001B");
	}

	@Test
	void anAgentThatFailsSilentlyStillFailsCleanly() {
		assertThatThrownBy(() -> AcpHarnessClientFactory.create(new FailingRuntime(null), settings()))
			.isInstanceOf(AcpHarnessClientException.class)
			.hasMessageContaining("Failed to initialize runtime");
	}

	/**
	 * A served agent that exits before becoming ready, the way a misconfigured
	 * {@code goose serve} does. The factory has a supervisor by then, and must close it
	 * rather than let it outlive the failed construction.
	 */
	@Test
	void aServedAgentThatDoesNotBecomeReadyIsReported() {
		assertThatThrownBy(() -> AcpHarnessClientFactory.create(new FailingServedRuntime(), settings()))
			.isInstanceOf(AcpHarnessClientException.class)
			.hasMessageContaining("did not become ready");
	}

	/**
	 * A served agent that started, followed by a construction step that fails. The
	 * supervisor owns the process, so a supervisor that was closed stops it and one that
	 * was not leaves it running. Resolving the log directory is the last step before the
	 * transport is created, which puts the failure after the agent is already up.
	 *
	 * <p>
	 * The process is the assertion on purpose: the supervisor is local to
	 * {@link AcpHarnessClientFactory#create}, and after the factory has thrown there is
	 * no instance to read {@code isHealthy()} or its shutdown hook from. This test fails
	 * on {@code main}, where the process is still running when {@code create} returns.
	 */
	@Test
	void aServedAgentThatStartedIsStoppedWhenConstructionFailsAfterwards() throws Exception {
		Path pidFile = workspace.resolve("agent.pid");
		try {
			assertThatThrownBy(() -> AcpHarnessClientFactory.create(new StartedServedRuntime(pidFile), settings()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cannot locate the agent's log");

			awaitStopped(awaitPid(pidFile));
		}
		finally {
			stopIfRunning(pidFile);
		}
	}

	private AcpHarnessSettings settings() {
		return AcpHarnessSettings.builder("failing", workspace).timeout(Duration.ofSeconds(5)).build();
	}

	private static long awaitPid(Path pidFile) throws IOException, InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (Files.exists(pidFile)) {
				String pid = Files.readString(pidFile).strip();
				if (!pid.isEmpty()) {
					try {
						return Long.parseLong(pid);
					}
					catch (NumberFormatException stillBeingWritten) {
						// The shell may not have flushed the whole line yet; retry.
					}
				}
			}
			Thread.sleep(25);
		}
		throw new AssertionError("The agent did not write its pid to " + pidFile);
	}

	private static void awaitStopped(long pid) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("The agent process " + pid + " was still running");
	}

	/** On main the assertion above fails with the agent still running; do not leak it. */
	private static void stopIfRunning(Path pidFile) {
		try {
			if (!Files.exists(pidFile)) {
				return;
			}
			String pid = Files.readString(pidFile).strip();
			if (!pid.isEmpty()) {
				ProcessHandle.of(Long.parseLong(pid)).ifPresent(ProcessHandle::destroyForcibly);
			}
		}
		catch (IOException | NumberFormatException ex) {
			// The file can be missing or half-written; there is nothing to stop.
		}
	}

	/**
	 * Launches something that complains and exits, the way a misconfigured agent does.
	 */
	private record FailingRuntime(String complaint) implements AcpHarnessRuntime {

		@Override
		public String id() {
			return "failing";
		}

		/**
		 * Complains, then lingers before exiting.
		 *
		 * <p>
		 * The lingering is not padding. A process that dies within microseconds of
		 * writing to stderr races the SDK's own stderr plumbing, which subscribes to the
		 * child's error stream slightly after starting it and loses anything already
		 * written — measured at roughly one run in five. That race belongs to acp-core,
		 * and no real agent is in it: an agent that rejects its configuration has parsed
		 * a file first. Reproducing it here would only make this suite flaky about
		 * somebody else's bug.
		 */
		@Override
		public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
			String script = complaint == null ? "sleep 1; exit 1"
					: "printf '%s\\n' \"" + complaint + "\" >&2; sleep 1; exit 1";
			return new AcpHarnessLaunchSpec.Stdio("sh", List.of("-c", script), Map.of());
		}
	}

	/**
	 * A served agent that exits before it becomes ready, with no health endpoint to wait
	 * on.
	 */
	private record FailingServedRuntime() implements AcpHarnessRuntime {

		@Override
		public String id() {
			return "failing-served";
		}

		@Override
		public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
			AcpHarnessLaunchSpec.ManagedProcess process = new AcpHarnessLaunchSpec.ManagedProcess("sh",
					List.of("-c", "exit 1"), Map.of(), null, Duration.ofMillis(500));
			return new AcpHarnessLaunchSpec.WebSocket(URI.create("ws://127.0.0.1:1/acp"), Map.of(), process);
		}

	}

	/**
	 * A served agent that starts, and then fails the next construction step: resolving
	 * its log directory throws. The process stays up after writing its pid, so only
	 * closing the supervisor stops it.
	 */
	private record StartedServedRuntime(Path pidFile) implements AcpHarnessRuntime {

		@Override
		public String id() {
			return "started-served";
		}

		@Override
		public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
			AcpHarnessLaunchSpec.ManagedProcess process = new AcpHarnessLaunchSpec.ManagedProcess("sh",
					List.of("-c", "echo $$ > \"$PID_FILE\"; exec sleep 60"), Map.of("PID_FILE", pidFile.toString()),
					null, Duration.ofSeconds(5));
			return new AcpHarnessLaunchSpec.WebSocket(URI.create("ws://127.0.0.1:1/acp"), Map.of(), process);
		}

		/**
		 * The failure this test injects: the last step before the transport is created,
		 * so the agent is already running when it is thrown.
		 */
		@Override
		public Path logDirectory(AcpHarnessSettings settings) {
			throw new IllegalStateException("cannot locate the agent's log");
		}

	}

}
