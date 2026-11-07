package org.springaicommunity.acp.harness.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.harness.client.AcpHarnessClientException;
import org.springaicommunity.acp.harness.runtime.AcpHarnessLaunchSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Restarts: a process that became ready and died is replaced once, and one that never
 * became ready is not restarted by its own exit.
 *
 * <p>
 * The agent is a shell script that appends its pid to a log as it starts, so a test can
 * count the spawns and check which of them are still alive. Each spawn decides how to
 * behave from how many came before it.
 */
@DisabledOnOs(OS.WINDOWS)
class AcpHarnessProcessSupervisorTests {

	@TempDir
	Path tempDir;

	private Path log;

	@AfterEach
	void killStragglers() throws IOException {
		if (log != null) {
			alive(pids()).forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
		}
	}

	@Test
	void aStartThatFailsIsNotRestarted() throws Exception {
		AcpHarnessProcessSupervisor supervisor = supervisor("exit 1", 3);
		try {
			assertThatThrownBy(supervisor::start).isInstanceOf(AcpHarnessClientException.class)
				.hasMessageContaining("did not become ready");

			// Past the first backoff: a supervisor that restarted the failed start would
			// have spawned again by now.
			Thread.sleep(1500);

			assertThat(pids()).hasSize(1);
		}
		finally {
			supervisor.close();
		}
	}

	@Test
	void anAgentThatDiesIsRestarted() throws Exception {
		// The first spawn crashes a second after becoming ready; the next stays up.
		AcpHarnessProcessSupervisor supervisor = supervisor("""
				case $n in
				  0) sleep 1; exit 1;;
				  *) exec sleep 60;;
				esac
				""", 3);
		try {
			supervisor.start();
			awaitTrue("the agent to be restarted", () -> spawns() >= 2, Duration.ofSeconds(8));
			awaitTrue("the restarted agent to be healthy", supervisor::isHealthy, Duration.ofSeconds(5));
			assertThat(alive(pids())).hasSize(1);
		}
		finally {
			supervisor.close();
		}
		assertThat(alive(pids())).isEmpty();
	}

	@Test
	void aRestartThatFailsIsRetriedOnceAndLeavesOneAgent() throws Exception {
		// Ready, then crashes; the restart dies during startup; the one after stays up.
		AcpHarnessProcessSupervisor supervisor = supervisor("""
				case $n in
				  0) sleep 1; exit 1;;
				  1) exit 1;;
				  *) exec sleep 60;;
				esac
				""", 5);
		try {
			supervisor.start();
			awaitTrue("the failed restart to be retried", () -> spawns() >= 3, Duration.ofSeconds(10));

			// Longer than the next backoff: a failed restart that was also restarted by
			// its
			// own exit would have spawned a fourth agent by now, beside the third.
			Thread.sleep(3000);

			assertThat(pids()).hasSize(3);
			assertThat(alive(pids())).containsExactly(pids().get(2));
			assertThat(supervisor.isHealthy()).isTrue();
		}
		finally {
			supervisor.close();
		}
		assertThat(alive(pids())).isEmpty();
	}

	/**
	 * A supervisor whose agent runs {@code behaviour} with {@code $n} set to the number
	 * of spawns before it.
	 */
	private AcpHarnessProcessSupervisor supervisor(String behaviour, int maxRestarts) throws IOException {
		log = tempDir.resolve("spawns.log");
		Path script = tempDir.resolve("agent.sh");
		Files.writeString(script, """
				#!/bin/sh
				n=$(cat "$1" 2>/dev/null | wc -l | tr -d ' ')
				echo $$ >> "$1"
				""" + behaviour);
		AcpHarnessLaunchSpec.ManagedProcess spec = new AcpHarnessLaunchSpec.ManagedProcess("/bin/sh",
				List.of(script.toString(), log.toString()), Map.of(), null, Duration.ofSeconds(5));
		return new AcpHarnessProcessSupervisor("probe", spec, maxRestarts);
	}

	private List<Long> pids() throws IOException {
		return Files.exists(log) ? Files.readAllLines(log).stream().map(Long::parseLong).toList() : List.of();
	}

	private int spawns() {
		try {
			return pids().size();
		}
		catch (IOException ex) {
			return 0;
		}
	}

	private static List<Long> alive(List<Long> pids) {
		return pids.stream().filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).toList();
	}

	private static void awaitTrue(String what, BooleanSupplier condition, Duration timeout)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(25);
		}
		fail(what + " did not happen within " + timeout);
	}

}
