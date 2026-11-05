package org.springaicommunity.acp.harness.permission;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * What an agent is asking permission for, in the terms a person can judge it by.
 *
 * <p>
 * The tool name alone is enough for a policy but not for a person:
 * {@code developer__shell} says nothing about which command. The title is the agent's own
 * one-line description of the call, which is what a human actually needs to see before
 * answering.
 *
 * @param toolName the tool being requested, or null when the agent did not make it
 * knowable
 * @param title the agent's description of the call, or null when it gave none
 * @param kind what sort of operation the call is, or null when the agent did not say
 * @param options the choices the agent offered; the answer must be one of them
 */
public record PermissionQuestion(@Nullable String toolName, @Nullable String title, AcpSchema.@Nullable ToolKind kind,
		List<AcpSchema.PermissionOption> options) {

	public PermissionQuestion {
		options = options == null ? List.of() : List.copyOf(options);
	}

	static PermissionQuestion of(AcpSchema.RequestPermissionRequest request, @Nullable String toolName) {
		AcpSchema.ToolCallUpdate call = request.toolCall();
		return new PermissionQuestion(toolName, call == null ? null : call.title(), call == null ? null : call.kind(),
				request.options());
	}

	/**
	 * The best short description available: the title, else the tool name, else a
	 * placeholder.
	 */
	public String describe() {
		if (title != null && !title.isBlank()) {
			return title.strip();
		}
		return toolName != null ? toolName : "an unnamed tool";
	}
}
