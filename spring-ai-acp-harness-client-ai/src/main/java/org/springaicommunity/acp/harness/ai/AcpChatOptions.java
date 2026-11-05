package org.springaicommunity.acp.harness.ai;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * {@link ChatOptions} plus the two things ACP has that a chat completion does not: a
 * conversation that lives on the agent, and a mode that decides whether the agent asks
 * before it acts.
 *
 * <p>
 * Most of the inherited options are not expressible over ACP and are reported rather than
 * silently dropped — see {@link AcpChatModel} for the list and for why reporting is the
 * honest choice. {@link #getModel()} is the exception: it maps onto the negotiated tier,
 * which is a request the agent may decline.
 */
public class AcpChatOptions implements ChatOptions {

	private @Nullable String model;

	private @Nullable String session;

	private @Nullable String mode;

	private @Nullable Duration timeout;

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The conversation this prompt belongs to, or null for a fresh one per call.
	 *
	 * <p>
	 * The single most important option here, because it changes what is sent. Without it
	 * every call is a fresh session and the whole prompt goes over the wire; with it the
	 * agent already holds the history and only what is new is sent. See
	 * {@link AcpChatModel}.
	 */
	public @Nullable String getSession() {
		return session;
	}

	public void setSession(@Nullable String session) {
		this.session = session;
	}

	/**
	 * The agent's own session mode: {@code plan}, {@code approve}, whatever it
	 * advertises; null to leave it as it is.
	 */
	public @Nullable String getMode() {
		return mode;
	}

	public void setMode(@Nullable String mode) {
		this.mode = mode;
	}

	/**
	 * How long this one turn may take, or null for the configured timeout. Agents are
	 * slower than chat completions.
	 */
	public @Nullable Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(@Nullable Duration timeout) {
		this.timeout = timeout;
	}

	@Override
	public @Nullable String getModel() {
		return model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	@Override
	public @Nullable Double getFrequencyPenalty() {
		return null;
	}

	@Override
	public @Nullable Integer getMaxTokens() {
		return null;
	}

	@Override
	public @Nullable Double getPresencePenalty() {
		return null;
	}

	@Override
	public @Nullable List<String> getStopSequences() {
		return null;
	}

	@Override
	public @Nullable Double getTemperature() {
		return null;
	}

	@Override
	public @Nullable Integer getTopK() {
		return null;
	}

	@Override
	public @Nullable Double getTopP() {
		return null;
	}

	@Override
	public Builder mutate() {
		return builder().model(model).session(session).mode(mode).timeout(timeout);
	}

	/** The values set here, on top of {@code defaults}, for one call. */
	AcpChatOptions merge(@Nullable AcpChatOptions defaults) {
		if (defaults == null) {
			return this;
		}
		return builder().model(model == null ? defaults.model : model)
			.session(session == null ? defaults.session : session)
			.mode(mode == null ? defaults.mode : mode)
			.timeout(timeout == null ? defaults.timeout : timeout)
			.build();
	}

	@Override
	public String toString() {
		return "AcpChatOptions{model=" + model + ", session=" + session + ", mode=" + mode + ", timeout=" + timeout
				+ "}";
	}

	public static final class Builder implements ChatOptions.Builder<Builder> {

		private final AcpChatOptions options = new AcpChatOptions();

		@Override
		public Builder clone() {
			return builder().model(options.model).session(options.session).mode(options.mode).timeout(options.timeout);
		}

		@Override
		public Builder model(@Nullable String model) {
			options.setModel(model);
			return this;
		}

		public Builder session(@Nullable String session) {
			options.setSession(session);
			return this;
		}

		public Builder mode(@Nullable String mode) {
			options.setMode(mode);
			return this;
		}

		public Builder timeout(@Nullable Duration timeout) {
			options.setTimeout(timeout);
			return this;
		}

		@Override
		public Builder frequencyPenalty(@Nullable Double frequencyPenalty) {
			return this;
		}

		@Override
		public Builder maxTokens(@Nullable Integer maxTokens) {
			return this;
		}

		@Override
		public Builder presencePenalty(@Nullable Double presencePenalty) {
			return this;
		}

		@Override
		public Builder stopSequences(@Nullable List<String> stopSequences) {
			return this;
		}

		@Override
		public Builder temperature(@Nullable Double temperature) {
			return this;
		}

		@Override
		public Builder topK(@Nullable Integer topK) {
			return this;
		}

		@Override
		public Builder topP(@Nullable Double topP) {
			return this;
		}

		@Override
		public Builder combineWith(ChatOptions.Builder<?> other) {
			ChatOptions built = other.build();
			if (built.getModel() != null) {
				model(built.getModel());
			}
			if (built instanceof AcpChatOptions acp) {
				if (acp.getSession() != null) {
					session(acp.getSession());
				}
				if (acp.getMode() != null) {
					mode(acp.getMode());
				}
				if (acp.getTimeout() != null) {
					timeout(acp.getTimeout());
				}
			}
			return this;
		}

		@Override
		public AcpChatOptions build() {
			return options;
		}

	}

}
