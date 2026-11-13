package org.springaicommunity.acp.harness.turn;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.event.AcpHarnessEvent;
import org.springaicommunity.acp.harness.event.AcpHarnessEventMapper;
import org.springaicommunity.acp.harness.turn.AcpHarnessTurn.PlanTool;
import org.springaicommunity.acp.harness.turn.AcpHarnessTurn.Subagents;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Maps one stream of {@code session/update}s onto {@link AcpHarnessEvent}, with what the
 * runtime knows about its agent applied: a plan kept in a tool, and chunks a subagent
 * wrote.
 *
 * <p>
 * Stateful, so one per stream: a turn has its own, and so does what a session receives
 * between turns. The state is the tool calls reported as the plan, whose updates are then
 * dropped.
 */
public final class UpdateMapping {

	private static final Logger logger = LoggerFactory.getLogger(UpdateMapping.class);

	private final PlanTool planTool;

	private final Subagents subagents;

	private final Set<String> planCalls = ConcurrentHashMap.newKeySet();

	public UpdateMapping(@Nullable PlanTool planTool, @Nullable Subagents subagents) {
		this.planTool = planTool == null ? PlanTool.NONE : planTool;
		this.subagents = subagents == null ? Subagents.NONE : subagents;
	}

	/**
	 * Maps one update.
	 *
	 * <p>
	 * A tool call {@link #planTool} recognises becomes
	 * {@link AcpHarnessEvent.PlanUpdated} in place of
	 * {@link AcpHarnessEvent.ToolCallStarted}, and the updates that follow it —
	 * "completed", with the tool's acknowledgement as content — are dropped, so a caller
	 * sees the plan change and not the bookkeeping behind it. A message or thought chunk
	 * {@link #subagents} says a subagent wrote is dropped, so it is not appended to the
	 * agent's answer. Anything the mapper drops, a replayed call included, stays dropped.
	 *
	 * <p>
	 * A recogniser that throws is treated as one that did not recognise the update: this
	 * runs on the transport's inbound thread, and an update that cannot be read is still
	 * worth reporting as what it is.
	 * @return the event, or null when the update carries nothing a caller needs
	 */
	public @Nullable AcpHarnessEvent map(AcpSchema.SessionUpdate update) {
		AcpHarnessEvent event = AcpHarnessEventMapper.map(update);
		if (event == null) {
			return null;
		}
		if ((event instanceof AcpHarnessEvent.Text || event instanceof AcpHarnessEvent.Thought)
				&& writtenBySubagent(update)) {
			return null;
		}
		if (update instanceof AcpSchema.ToolCall call) {
			List<AcpSchema.PlanEntry> plan = planOf(call);
			if (plan != null) {
				if (call.toolCallId() != null) {
					planCalls.add(call.toolCallId());
				}
				return new AcpHarnessEvent.PlanUpdated(List.copyOf(plan));
			}
		}
		else if (update instanceof AcpSchema.ToolCallUpdateNotification updated
				&& planCalls.contains(updated.toolCallId())) {
			return null;
		}
		return event;
	}

	private @Nullable List<AcpSchema.PlanEntry> planOf(AcpSchema.ToolCall call) {
		try {
			return planTool.planOf(call);
		}
		catch (RuntimeException ex) {
			logger.debug("Could not read a plan from tool call {}", call.toolCallId(), ex);
			return null;
		}
	}

	private boolean writtenBySubagent(AcpSchema.SessionUpdate chunk) {
		try {
			return subagents.wrote(chunk);
		}
		catch (RuntimeException ex) {
			logger.debug("Could not tell whether a subagent wrote a chunk", ex);
			return false;
		}
	}

}
