package org.springaicommunity.acp.harness.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime.PortableOption;

/**
 * The negotiated tier's outcome for one session: one {@link OptionResolution} per
 * portable option.
 */
public record SessionConfiguration(Map<PortableOption, OptionResolution> resolutions) {

	public SessionConfiguration {
		Map<PortableOption, OptionResolution> copy = new EnumMap<>(PortableOption.class);
		if (resolutions != null) {
			copy.putAll(resolutions);
		}
		for (PortableOption option : PortableOption.values()) {
			copy.putIfAbsent(option, OptionResolution.notRequested(option));
		}
		resolutions = Collections.unmodifiableMap(copy);
	}

	public static SessionConfiguration empty() {
		return new SessionConfiguration(Map.of());
	}

	public static SessionConfiguration from(List<OptionResolution> resolutions) {
		Map<PortableOption, OptionResolution> map = new EnumMap<>(PortableOption.class);
		resolutions.forEach(r -> map.put(r.option(), r));
		return new SessionConfiguration(map);
	}

	/**
	 * How {@code option} was resolved; {@link OptionResolution.Mechanism#NOT_REQUESTED}
	 * for one the configuration never mentioned.
	 */
	public OptionResolution of(PortableOption option) {
		OptionResolution resolution = resolutions.get(option);
		return resolution == null ? OptionResolution.notRequested(option) : resolution;
	}

	/**
	 * The value the agent actually took for {@code option}, or null when it took none.
	 */
	public @Nullable String applied(PortableOption option) {
		OptionResolution resolution = of(option);
		return resolution.isApplied() ? resolution.applied() : null;
	}

	/** Options that were requested and could not be honored. */
	public List<OptionResolution> unsupported() {
		return resolutions.values()
			.stream()
			.filter(r -> r.mechanism() == OptionResolution.Mechanism.UNSUPPORTED)
			.toList();
	}
}
