package org.springaicommunity.acp.harness.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.acp.harness.config.AcpHarnessSettings;

/**
 * The environment an adapter starts its agent with.
 *
 * <p>
 * Ordering is the contract, and the same for every adapter: the agent's own defaults
 * first, then the provider's credentials, then the application's tier-3 {@code env}
 * block, which therefore wins over both. That last one is how an application reaches an
 * agent whose key lives under a name no adapter could have guessed.
 */
public final class AcpHarnessEnvironment {

	private AcpHarnessEnvironment() {
	}

	/**
	 * @param defaults what the adapter sets for its agent regardless of configuration
	 * @param credentials the provider's credentials, in the agent's spelling; usually
	 * {@code ProviderEnvironment.of(settings.provider())}
	 * @param settings whose tier-3 {@code env} block is applied last
	 */
	public static Map<String, String> layered(Map<String, String> defaults, Map<String, String> credentials,
			AcpHarnessSettings settings) {
		Map<String, String> env = new LinkedHashMap<>(defaults);
		env.putAll(credentials);
		env.putAll(settings.runtimeOptions().textSection("env"));
		return env;
	}

}
