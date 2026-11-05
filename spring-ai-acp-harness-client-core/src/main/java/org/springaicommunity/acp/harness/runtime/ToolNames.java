package org.springaicommunity.acp.harness.runtime;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Digs a stable tool identifier out of a tool call, for adapters whose agents bury one.
 *
 * <p>
 * ACP has no required field for this. {@code toolCall.title} is prose written for a human
 * — "Read the build file" — and an allowlist keyed on prose is not an allowlist. Agents
 * that do expose an identifier put it in {@code rawInput} or their own {@code _meta},
 * each under a name of their choosing, so this tries the handful that are actually in use
 * and gives up rather than guessing.
 *
 * <p>
 * Giving up is the important part:
 * {@link org.springaicommunity.acp.harness.permission.PermissionPolicy#allowlist} rejects
 * a request whose tool it cannot name, so a wrong answer here would be a security hole
 * and a null one is merely a denied tool call.
 */
public final class ToolNames {

	/**
	 * Checked in order. {@code toolName} is Goose's; the snake and plain forms cover the
	 * rest.
	 */
	private static final List<String> KEYS = List.of("toolName", "tool_name", "name");

	private ToolNames() {
	}

	/**
	 * An identifier from the call's {@code rawInput}, or null. Never falls back to the
	 * title.
	 */
	public static @Nullable String fromRawInput(AcpSchema.@Nullable ToolCallUpdate toolCall) {
		if (toolCall == null || !(toolCall.rawInput() instanceof Map<?, ?> raw)) {
			return null;
		}
		return firstKnownKey(raw);
	}

	/**
	 * An identifier if there is one, and the title if there is not.
	 *
	 * <p>
	 * Only appropriate for an agent whose titles are known to be stable tool names. For
	 * anything else this trades a denied tool call for an approved one, which is the
	 * wrong direction.
	 */
	public static @Nullable String fromRawInputOrTitle(AcpSchema.@Nullable ToolCallUpdate toolCall) {
		String fromInput = fromRawInput(toolCall);
		if (fromInput != null) {
			return fromInput;
		}
		return toolCall == null ? null : toolCall.title();
	}

	private static @Nullable String firstKnownKey(Map<?, ?> map) {
		return KEYS.stream()
			.map(map::get)
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.filter(s -> !s.isBlank())
			.findFirst()
			.orElse(null);
	}

}
