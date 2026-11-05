package org.springaicommunity.acp.harness.goose;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.runtime.AcpHarnessNotice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What goose says when it cannot load an MCP server, and where it says it.
 *
 * <p>
 * The log lines below are captured verbatim from goose 1.51.0 and 1.55.0, which reworded
 * them, by {@code tools/mcp-silence-check.py} and the live contract test — per version,
 * one HTTP server that refused the connection, one stdio server whose process exited.
 * They are fixtures rather than examples: this parser depends on goose's own wording,
 * which carries no compatibility promise, so the test that pins it has to be pinned to
 * something goose actually wrote. The live contract test catches the day the wording
 * changes; this one catches the day the parser does.
 */
class GooseNoticeTests {

	private static final String HTTP_FAILURE = """
			{"timestamp":"2026-09-21T01:27:23.412971Z","level":"WARN","fields":{"message":\
			"Failed to load extension finops-mcp: failed to initialize MCP client: Send message error \
			Transport [rmcp::transport::worker::WorkerTransport<rmcp::transport::streamable_http_client\
			::StreamableHttpClientWorker<reqwest::async_impl::client::Client>>] error: Client error: \
			error sending request for url (http://127.0.0.1:9/mcp), when send discover request"},\
			"target":"goose::agents::agent"}""";

	private static final String STDIO_FAILURE = """
			{"timestamp":"2026-09-21T01:27:37.988044Z","level":"WARN","fields":{"message":\
			"Failed to load extension local-tools: process quit before initialization: stderr = "},\
			"target":"goose::agents::agent"}""";

	private static final String HTTP_FAILURE_1_55 = """
			{"timestamp":"2026-10-20T13:45:39.648437Z","level":"WARN","fields":{"message":\
			"failed to start extension","extension":"spring-ai-acp-harness-client-contract-mcp","error":\
			"failed to initialize MCP client: Send message error Transport \
			[rmcp::transport::worker::WorkerTransport<rmcp::transport::streamable_http_client\
			::StreamableHttpClientWorker<reqwest::async_impl::client::Client>>] error: Client error: \
			error sending request for url (http://127.0.0.1:9/mcp), when send discover request"},\
			"target":"goose::agents::extension_manager"}""";

	private static final String STDIO_FAILURE_1_55 = """
			{"timestamp":"2026-10-20T13:49:24.545131Z","level":"WARN","fields":{"message":\
			"failed to start extension","extension":"silence-probe-mcp","error":\
			"process quit before initialization: stderr = "},"target":"goose::agents::extension_manager"}""";

	private final GooseRuntime runtime = new GooseRuntime();

	/** The notice the runtime reads out of {@code line}, which must be one. */
	private AcpHarnessNotice notice(String line) {
		AcpHarnessNotice notice = runtime.noticeOf(line);
		assertThat(notice).isNotNull();
		return notice;
	}

	@Test
	void anUnreachableHttpServerIsReportedAgainstTheNameTheApplicationGaveIt() {
		AcpHarnessNotice notice = notice(HTTP_FAILURE);

		assertThat(notice.severity()).isEqualTo(AcpHarnessNotice.Severity.WARNING);
		assertThat(notice.subject()).isEqualTo("finops-mcp");
		assertThat(notice.concerns("finops-mcp")).isTrue();
		assertThat(notice.detail()).contains("failed to initialize MCP client").contains("when send discover request");
	}

	@Test
	void theRestOfTheJsonRecordIsNotPartOfWhatTheAgentSaid() {
		AcpHarnessNotice notice = notice(HTTP_FAILURE);

		assertThat(notice.detail()).doesNotContain("goose::agents::agent").doesNotContain("\"target\"");
	}

	@Test
	void aStdioServerThatExitedIsReportedTheSameWay() {
		AcpHarnessNotice notice = notice(STDIO_FAILURE);

		assertThat(notice.subject()).isEqualTo("local-tools");
		assertThat(notice.detail()).contains("process quit before initialization");
	}

	@Test
	void sinceOneFiftyFiveTheNameAndReasonArriveAsFieldsOfTheirOwn() {
		AcpHarnessNotice notice = notice(HTTP_FAILURE_1_55);

		assertThat(notice.severity()).isEqualTo(AcpHarnessNotice.Severity.WARNING);
		assertThat(notice.subject()).isEqualTo("spring-ai-acp-harness-client-contract-mcp");
		assertThat(notice.detail()).contains("failed to initialize MCP client")
			.contains("when send discover request")
			.doesNotContain("goose::agents::extension_manager")
			.doesNotContain("\"target\"");
	}

	@Test
	void sinceOneFiftyFiveAStdioServerThatExitedIsReportedTheSameWay() {
		AcpHarnessNotice notice = notice(STDIO_FAILURE_1_55);

		assertThat(notice.subject()).isEqualTo("silence-probe-mcp");
		assertThat(notice.detail()).contains("process quit before initialization");
	}

	@Test
	void sinceOneFiftyFiveTheOtherExtensionManagerLinesAreNotNotices() {
		assertThat(runtime.noticeOf("""
				{"timestamp":"2026-10-20T13:45:39.648285Z","level":"ERROR","fields":{"message":\
				"worker quit with fatal: Transport channel closed"},"target":"rmcp::transport::worker"}""")).isNull();
	}

	@Test
	void everythingElseInTheLogIsNoneOfAClientsBusiness() {
		assertThat(runtime.noticeOf("""
				{"timestamp":"2026-09-21T01:27:23.400000Z","level":"INFO","fields":{"message":\
				"Starting session"},"target":"goose::session"}""")).isNull();
		assertThat(runtime.noticeOf("")).isNull();
		assertThat(runtime.noticeOf(null)).isNull();
	}

	@Test
	void theLogDirectoryFollowsTheStateHomeGooseItselfUses() {
		Path directory = runtime.logDirectory(settings(Map.of()));

		// Compared as strings: AssertJ's PathAssert.endsWith resolves the real path,
		// which requires the directory to exist — true on a machine with goose installed,
		// false on a fresh build machine where no goose has ever run.
		assertThat(directory).isAbsolute();
		assertThat(directory.normalize().toString()).endsWith(Paths.get("goose", "logs").toString());
	}

	@Test
	void aDeploymentThatPutsTheLogElsewhereSaysSoInTierThree() {
		Path directory = runtime.logDirectory(settings(Map.of("log-dir", "/var/log/goose")));

		assertThat(directory).isEqualTo(Paths.get("/var/log/goose"));
	}

	private AcpHarnessSettings settings(Map<String, Object> options) {
		return AcpHarnessSettings
			.builder(GooseRuntime.ID, Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath())
			.runtimeOptions(options)
			.build();
	}

}
