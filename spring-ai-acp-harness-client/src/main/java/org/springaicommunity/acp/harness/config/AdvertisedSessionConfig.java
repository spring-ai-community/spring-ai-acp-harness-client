package org.springaicommunity.acp.harness.config;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * What the agent said about itself when it created the session: the config options it
 * will accept, and the legacy {@code modes} state.
 *
 * <p>
 * Reading this before setting anything is what makes the negotiated tier honest. Measured
 * against three live runtimes, an agent asked to set a value it does not have does one of
 * two things: reject the call (OpenCode, Codex) or <em>accept it and fail later</em> —
 * goose 1.51 stores an unknown model id without complaint and the resulting 404 surfaces
 * mid-turn as agent prose. So "set it and see" cannot implement {@code on-unsupported}:
 * the answer has to come from the advertised options.
 *
 * <p>
 * {@code modes} is the pre-{@code configOptions} way of saying the same thing for the
 * mode. The spec's transition guidance has agents return both, and they do, so it is
 * consulted only when no matching config option exists — OpenCode, for instance, does not
 * return it. Its counterpart for the model, {@code models} with
 * {@code session/set_model}, never became stable ACP, and acp-core 0.80.0 no longer
 * models it.
 */
public record AdvertisedSessionConfig(List<AcpSchema.SessionConfigOption> configOptions,
		AcpSchema.@Nullable SessionModeState modes) {

	private static final AdvertisedSessionConfig EMPTY = new AdvertisedSessionConfig(List.of(), null);

	public AdvertisedSessionConfig {
		configOptions = configOptions == null ? List.of() : List.copyOf(configOptions);
	}

	/**
	 * What a session answer carried, either part of which an agent may leave out.
	 */
	public static AdvertisedSessionConfig of(@Nullable List<AcpSchema.SessionConfigOption> configOptions,
			AcpSchema.@Nullable SessionModeState modes) {
		return new AdvertisedSessionConfig(configOptions == null ? List.of() : configOptions, modes);
	}

	/**
	 * Nothing advertised: an agent that returned no options and no mode state.
	 */
	public static AdvertisedSessionConfig empty() {
		return EMPTY;
	}

	/**
	 * The select option matching {@code id}, or failing that {@code category}, or null.
	 *
	 * <p>
	 * Both are needed. Goose returns its {@code provider} option with a null category,
	 * and Codex returns two options in the {@code mode} family under different ids, so
	 * neither key alone finds everything.
	 */
	public AcpSchema.@Nullable SessionConfigSelect select(String id, @Nullable String category) {
		AcpSchema.SessionConfigSelect byId = selects().filter(o -> equalsIgnoreCase(o.id(), id))
			.findFirst()
			.orElse(null);
		if (byId != null) {
			return byId;
		}
		return selects().filter(o -> equalsIgnoreCase(o.category(), category)).findFirst().orElse(null);
	}

	/** Every select option the agent advertised, in the order it advertised them. */
	public Stream<AcpSchema.SessionConfigSelect> selects() {
		return configOptions.stream()
			.filter(AcpSchema.SessionConfigSelect.class::isInstance)
			.map(AcpSchema.SessionConfigSelect.class::cast);
	}

	/**
	 * The legacy {@code modes} state, or null when the agent sent none or an empty one.
	 */
	public AcpSchema.@Nullable SessionModeState findModes() {
		return modes != null && modes.availableModes() != null && !modes.availableModes().isEmpty() ? modes : null;
	}

	public boolean isEmpty() {
		return configOptions.isEmpty() && findModes() == null;
	}

	/**
	 * This config with the option set an agent returned from a
	 * {@code session/set_config_option}.
	 *
	 * <p>
	 * Not cosmetic: on an agent that scopes its model list by provider, setting the
	 * provider changes which models are legal, so a model resolved against the
	 * pre-provider list would be resolved against the wrong one.
	 */
	public AdvertisedSessionConfig withConfigOptions(@Nullable List<AcpSchema.SessionConfigOption> options) {
		return options == null || options.isEmpty() ? this : new AdvertisedSessionConfig(options, modes);
	}

	private static boolean equalsIgnoreCase(@Nullable String a, @Nullable String b) {
		return a != null && b != null && a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
	}
}
