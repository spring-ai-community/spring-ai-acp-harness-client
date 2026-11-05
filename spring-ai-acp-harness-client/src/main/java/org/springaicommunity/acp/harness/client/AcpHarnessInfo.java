package org.springaicommunity.acp.harness.client;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * What the agent said about itself during {@code initialize}.
 *
 * <p>
 * Both fields are optional in ACP and at least one agent leaves them out, so this exists
 * rather than exposing {@code AcpSchema.Implementation} directly: a caller asking "which
 * agent am I talking to" gets a stable two-field answer instead of a schema record that
 * may grow fields, and an agent that gave no name is a {@code null} rather than a record
 * with a null name inside.
 */
public record AcpHarnessInfo(String name, @Nullable String version) {

	static @Nullable AcpHarnessInfo from(AcpSchema.@Nullable Implementation implementation) {
		if (implementation == null || implementation.name() == null) {
			return null;
		}
		return new AcpHarnessInfo(implementation.name(), implementation.version());
	}

	@Override
	public String toString() {
		return version == null || version.isBlank() ? name : name + " " + version;
	}
}
