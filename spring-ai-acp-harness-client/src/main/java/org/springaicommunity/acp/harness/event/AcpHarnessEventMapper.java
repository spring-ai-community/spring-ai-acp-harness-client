package org.springaicommunity.acp.harness.event;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Maps ACP {@code session/update} payloads onto {@link AcpHarnessEvent}.
 *
 * <p>
 * Two deliberate omissions. {@code user_message_chunk} is dropped: it echoes the prompt
 * we just sent, and replaying it to the caller as agent output is wrong. Updates carrying
 * {@code _meta.replay} are dropped for the same reason — {@code session/load} replays
 * history through the same channel a live turn uses, and history is not this turn's
 * output.
 */
public final class AcpHarnessEventMapper {

	private AcpHarnessEventMapper() {
	}

	/**
	 * @return the event this update represents, or null when it carries nothing a caller
	 * needs
	 */
	public static @Nullable AcpHarnessEvent map(AcpSchema.@Nullable SessionUpdate update) {
		if (update == null) {
			return null;
		}
		return switch (update) {
			case AcpSchema.AgentMessageChunk c -> {
				String text = isReplay(c.meta()) ? null : text(c.content());
				yield text == null ? null : new AcpHarnessEvent.Text(text);
			}
			case AcpSchema.AgentThoughtChunk c -> {
				String text = isReplay(c.meta()) ? null : text(c.content());
				yield text == null ? null : new AcpHarnessEvent.Thought(text);
			}
			case AcpSchema.ToolCall c ->
				isReplay(c.meta()) ? null : new AcpHarnessEvent.ToolCallStarted(c.toolCallId(), c.title(), c.kind());
			case AcpSchema.ToolCallUpdateNotification c -> isReplay(c.meta()) ? null
					: new AcpHarnessEvent.ToolCallUpdated(c.toolCallId(), c.status(), c.content());
			case AcpSchema.Plan c -> new AcpHarnessEvent.PlanUpdated(entries(c.entries()));
			case AcpSchema.ConfigOptionUpdate c -> new AcpHarnessEvent.ConfigChanged(options(c.configOptions()));
			case AcpSchema.CurrentModeUpdate c -> new AcpHarnessEvent.ModeChanged(c.currentModeId());
			case AcpSchema.UsageUpdate c -> usage(c);
			// user_message_chunk, available_commands_update: nothing a caller of a turn
			// needs.
			default -> null;
		};
	}

	/**
	 * Extracts readable text from a content block. Images, audio and resource links carry
	 * no text and are skipped rather than rendered as a placeholder.
	 */
	private static @Nullable String text(AcpSchema.@Nullable ContentBlock content) {
		return content instanceof AcpSchema.TextContent t && t.text() != null && !t.text().isEmpty() ? t.text() : null;
	}

	private static boolean isReplay(@Nullable Map<String, Object> meta) {
		return meta != null && Boolean.TRUE.equals(meta.get("replay"));
	}

	private static List<AcpSchema.PlanEntry> entries(@Nullable List<AcpSchema.PlanEntry> e) {
		return e == null ? List.of() : List.copyOf(e);
	}

	private static List<AcpSchema.SessionConfigOption> options(@Nullable List<AcpSchema.SessionConfigOption> o) {
		return o == null ? List.of() : List.copyOf(o);
	}

	/**
	 * Usage is dropped when the agent sent no numbers at all.
	 *
	 * <p>
	 * {@code used} and {@code size} are required by the schema and boxed by the SDK, so
	 * an update with neither is an agent sending the notification for the sake of its
	 * {@code cost} field or for nothing. Emitting {@code 0 of 0} would read as an empty
	 * context window rather than as no measurement, which is the wrong claim to put in
	 * front of a gauge.
	 */
	private static @Nullable AcpHarnessEvent usage(AcpSchema.UsageUpdate update) {
		if (update.used() == null && update.size() == null) {
			return null;
		}
		AcpSchema.Cost cost = update.cost();
		return new AcpHarnessEvent.UsageUpdated(update.used() == null ? 0L : update.used(),
				update.size() == null ? 0L : update.size(), cost == null ? null : cost.amount(),
				cost == null ? null : cost.currency());
	}

}
