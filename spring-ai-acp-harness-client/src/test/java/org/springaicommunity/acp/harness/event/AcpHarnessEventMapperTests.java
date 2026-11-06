package org.springaicommunity.acp.harness.event;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class AcpHarnessEventMapperTests {

	@Test
	void mapsAgentTextAndThoughtsToDistinctEvents() {
		assertThat(AcpHarnessEventMapper.map(new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent("hello"))))
			.isEqualTo(new AcpHarnessEvent.Text("hello"));

		assertThat(AcpHarnessEventMapper.map(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent("pondering"))))
			.isEqualTo(new AcpHarnessEvent.Thought("pondering"));
	}

	@Test
	void dropsTheEchoOfOurOwnPrompt() {
		assertThat(AcpHarnessEventMapper
			.map(new AcpSchema.UserMessageChunk(new AcpSchema.TextContent("what I just sent")))).isNull();
	}

	@Test
	void dropsReplayedHistorySoALoadedSessionDoesNotResurfaceAsThisTurnsOutput() {
		AcpSchema.AgentMessageChunk replayed = new AcpSchema.AgentMessageChunk("agent_message_chunk",
				new AcpSchema.TextContent("said last week"), null, Map.of("replay", true));

		assertThat(AcpHarnessEventMapper.map(replayed)).isNull();
	}

	@Test
	void mapsToolCallLifecycleToStartedThenUpdated() {
		AcpSchema.SessionUpdate started = new AcpSchema.ToolCall("tool_call", "t1", "Read file", null,
				AcpSchema.ToolKind.READ, AcpSchema.ToolCallStatus.PENDING, List.of(), List.of(), null, null, null);
		AcpSchema.SessionUpdate finished = new AcpSchema.ToolCallUpdateNotification("tool_call_update", "t1", null,
				null, null, AcpSchema.ToolCallStatus.COMPLETED, List.of(), null, null, null, null);

		assertThat(AcpHarnessEventMapper.map(started))
			.isEqualTo(new AcpHarnessEvent.ToolCallStarted("t1", "Read file", AcpSchema.ToolKind.READ));
		assertThat(AcpHarnessEventMapper.map(finished))
			.isEqualTo(new AcpHarnessEvent.ToolCallUpdated("t1", AcpSchema.ToolCallStatus.COMPLETED, List.of()));
	}

	@Test
	void skipsContentBlocksThatCarryNoText() {
		AcpSchema.SessionUpdate image = new AcpSchema.AgentMessageChunk(
				new AcpSchema.ImageContent("image", "data", "image/png", null, null, null));

		assertThat(AcpHarnessEventMapper.map(image)).isNull();
	}

	@Test
	void mapsUsageUpdatesIncludingTheCostAnAgentMayAttach() {
		// Measured against goose 1.51: it sends one of these with used=0 before the turn
		// starts and
		// another with the real numbers when it ends, the second one carrying a cost.
		AcpSchema.SessionUpdate update = new AcpSchema.UsageUpdate("usage_update", 4441L, 1_050_000L,
				new AcpSchema.Cost(0.008932, "USD"), null);

		assertThat(AcpHarnessEventMapper.map(update))
			.isEqualTo(new AcpHarnessEvent.UsageUpdated(4441L, 1_050_000L, 0.008932, "USD"));
	}

	@Test
	void reportsHowMuchOfTheContextWindowIsGone() {
		AcpHarnessEvent.UsageUpdated usage = new AcpHarnessEvent.UsageUpdated(500L, 1000L, null, null);

		assertThat(usage.contextFraction()).isEqualTo(0.5);
	}

	@Test
	void hasNoOpinionOnTheContextWindowWhenTheAgentDidNotSayHowBigItIs() {
		assertThat(new AcpHarnessEvent.UsageUpdated(500L, 0L, null, null).contextFraction()).isNull();
	}

	@Test
	void dropsAUsageUpdateThatMeasuredNothing() {
		// "0 of 0" would read as an empty context window rather than as no measurement,
		// and a gauge
		// cannot tell the difference.
		assertThat(AcpHarnessEventMapper.map(new AcpSchema.UsageUpdate("usage_update", null, null, null, null)))
			.isNull();
	}

	@Test
	void toleratesANullUpdate() {
		assertThat(AcpHarnessEventMapper.map(null)).isNull();
	}

}
