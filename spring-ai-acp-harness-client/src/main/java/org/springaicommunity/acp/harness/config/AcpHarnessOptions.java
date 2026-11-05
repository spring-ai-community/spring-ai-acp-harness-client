package org.springaicommunity.acp.harness.config;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * Per-request overrides. Every field is optional; a null field defers to
 * {@link AcpHarnessSettings}.
 *
 * <p>
 * Only options that ACP can genuinely vary per session belong here. Credentials, the
 * workspace root and the MCP server set are fixed when the agent process starts, so they
 * are deliberately absent — offering them per call would be a lie.
 */
public record AcpHarnessOptions(@Nullable Duration timeout, @Nullable String model, @Nullable String provider,
		@Nullable String mode) {

	private static final AcpHarnessOptions NONE = new AcpHarnessOptions(null, null, null, null);

	public AcpHarnessOptions {
		if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
			throw new IllegalArgumentException("timeout must be positive but was " + timeout);
		}
		if (timeout != null && timeout.toHours() > 24) {
			throw new IllegalArgumentException("timeout must not exceed 24h but was " + timeout);
		}
	}

	public static AcpHarnessOptions none() {
		return NONE;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable Duration timeout;

		private @Nullable String model;

		private @Nullable String provider;

		private @Nullable String mode;

		public Builder timeout(@Nullable Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		public Builder model(@Nullable String model) {
			this.model = model;
			return this;
		}

		public Builder provider(@Nullable String provider) {
			this.provider = provider;
			return this;
		}

		public Builder mode(@Nullable String mode) {
			this.mode = mode;
			return this;
		}

		public AcpHarnessOptions build() {
			return new AcpHarnessOptions(timeout, model, provider, mode);
		}

	}
}
