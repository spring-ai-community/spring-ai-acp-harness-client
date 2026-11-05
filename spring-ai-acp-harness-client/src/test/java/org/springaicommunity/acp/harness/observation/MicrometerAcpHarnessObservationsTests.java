package org.springaicommunity.acp.harness.observation;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a turn's observation actually ends up looking like in a meter registry.
 *
 * <p>
 * Asserted through a real {@link ObservationRegistry} with the meter handler attached,
 * rather than against a mock, because the questions worth asking are about timers that do
 * or do not get published and tags that do or do not appear on them — and a mock would
 * answer all of them yes.
 */
class MicrometerAcpHarnessObservationsTests {

	private MeterRegistry meters;

	private MicrometerAcpHarnessObservations observations;

	@BeforeEach
	void setUp() {
		meters = new SimpleMeterRegistry();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
		observations = new MicrometerAcpHarnessObservations(registry);
	}

	@Test
	void timesACompletedTurnAndTagsItWithTheModelTheSessionReallyHas() {
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "review-123", "gpt-5.4-mini", false));

		turn.completed("END_TURN");

		assertThat(meters.get("spring.ai.acp.harness.turn")
			.tag("spring.ai.kind", "acp_harness_turn")
			.tag("spring.ai.acp.harness.runtime", "goose")
			.tag("gen_ai.response.model", "gpt-5.4-mini")
			.tag("spring.ai.acp.harness.session.kind", "named")
			.tag("spring.ai.acp.harness.outcome", "END_TURN")
			.timer()
			.count()).isEqualTo(1);
	}

	@Test
	void tagsAThrowawayTurnAsEphemeral() {
		observations.turnStarted(new AcpHarnessObservations.TurnContext("codex", "turn-abc", null, true))
			.completed("END_TURN");

		assertThat(meters.get("spring.ai.acp.harness.turn")
			.tag("spring.ai.acp.harness.session.kind", "ephemeral")
			.tag("gen_ai.response.model", "none")
			.timer()
			.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a turn that failed is tagged with the reason, not with the exception's message")
	void recordsTheFailureReasonWithoutLeakingItsMessage() {
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.failed("TimeoutException",
				new java.util.concurrent.TimeoutException("session 20260920_224 in /Users/someone/secrets timed out"));

		assertThat(meters.get("spring.ai.acp.harness.turn")
			.tag("spring.ai.acp.harness.outcome", "TimeoutException")
			.timer()
			.count()).isEqualTo(1);
		assertThat(meters.getMeters().stream().map(m -> m.getId().getTags().toString()))
			.noneMatch(tags -> tags.contains("secrets"));
	}

	@Test
	void timesEachToolCallSeparatelyFromItsTurn() {
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "Read the build file", "READ");
		turn.toolCallUpdated("t1", "IN_PROGRESS");
		turn.toolCallUpdated("t1", "COMPLETED");
		turn.completed("END_TURN");

		assertThat(meters.get("spring.ai.acp.harness.tool.call")
			.tag("spring.ai.kind", "acp_harness_tool_call")
			.tag("spring.ai.acp.harness.runtime", "goose")
			.tag("spring.ai.acp.harness.tool.kind", "READ")
			.tag("spring.ai.acp.harness.tool.status", "COMPLETED")
			.timer()
			.count()).isEqualTo(1);
		assertThat(meters.get("spring.ai.acp.harness.turn").timer().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a tool call the turn outlived is still stopped, tagged unfinished")
	void closesToolCallsTheAgentNeverFinished() {
		// A cancelled turn produces one of these every time: the agent announced a call
		// and the turn
		// ended before any status arrived. An observation left open is a leaked span.
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "Search the repository", "SEARCH");
		turn.failed("cancelled", null);

		assertThat(meters.get("spring.ai.acp.harness.tool.call")
			.tag("spring.ai.acp.harness.tool.status", "unfinished")
			.timer()
			.count()).isEqualTo(1);
	}

	@Test
	void finishesATurnOnlyOnce() {
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.completed("END_TURN");
		turn.failed("TimeoutException", new RuntimeException());

		assertThat(meters.get("spring.ai.acp.harness.turn").timers().stream().mapToLong(t -> t.count()).sum())
			.isEqualTo(1);
	}

	@Test
	void keepsTheLatestUsageTheAgentReported() {
		AcpHarnessTurnObservationContext context = new AcpHarnessTurnObservationContext(
				new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		context.usage(0, 1_050_000, null, null);
		context.usage(4441, 1_050_000, 0.008932, "USD");

		assertThat(new DefaultAcpHarnessTurnObservationConvention().getHighCardinalityKeyValues(context))
			.anyMatch(kv -> kv.getKey().equals("spring.ai.acp.harness.context.used") && kv.getValue().equals("4441"))
			.anyMatch(kv -> kv.getKey().equals("spring.ai.acp.harness.cost") && kv.getValue().equals("0.008932 USD"));
	}

	@Test
	@DisplayName("usage is left off entirely by an agent that never reported any")
	void omitsUsageRatherThanReportingZero() {
		AcpHarnessTurnObservationContext context = new AcpHarnessTurnObservationContext(
				new AcpHarnessObservations.TurnContext("codex", "s", "m", false));

		assertThat(new DefaultAcpHarnessTurnObservationConvention().getHighCardinalityKeyValues(context))
			.extracting(kv -> kv.getKey())
			.containsExactly("spring.ai.acp.harness.session.name");
	}

	@Test
	void doNothingObservationsCostNothingAndBreakNothing() {
		AcpHarnessObservations.TurnRecording turn = AcpHarnessObservations.NONE
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "anything", "READ");
		turn.usage(1, 2, 3.0, "USD");
		turn.completed("END_TURN");

		assertThat(meters.getMeters()).isEmpty();
	}

	@Test
	void aDuplicateToolCallIdDoesNotLeakTheObservationItWouldHaveReplaced() {
		AcpHarnessObservations.TurnRecording turn = observations
			.turnStarted(new AcpHarnessObservations.TurnContext("goose", "s", "m", false));

		turn.toolCallStarted("t1", "first", "READ");
		turn.toolCallStarted("t1", "second", "EDIT");
		turn.toolCallUpdated("t1", "COMPLETED");
		turn.completed("END_TURN");

		assertThat(meters.get("spring.ai.acp.harness.tool.call").timers().stream().mapToLong(t -> t.count()).sum())
			.isEqualTo(1);
		assertThat(List.of(meters.get("spring.ai.acp.harness.tool.call")
			.tag("spring.ai.acp.harness.tool.kind", "READ")
			.timer()
			.count())).containsExactly(1L);
	}

}
