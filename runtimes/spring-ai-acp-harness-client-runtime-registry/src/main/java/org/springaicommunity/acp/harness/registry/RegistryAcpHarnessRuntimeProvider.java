package org.springaicommunity.acp.harness.registry;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntimeProvider;

/**
 * Answers for any agent in the ACP registry that has no compiled adapter.
 *
 * <p>
 * Nothing is downloaded here. Resolving an id reads the snapshot and builds a runtime;
 * the agent itself is fetched the first time that runtime is launched, which is the first
 * time a prompt runs. That keeps a misconfigured agent out of context refresh — the rule
 * the whole library follows: an application healthy apart from its agent stays up.
 */
public class RegistryAcpHarnessRuntimeProvider implements AcpHarnessRuntimeProvider {

	private final AcpHarnessRegistry registry;

	private final AcpHarnessInstaller installer;

	public RegistryAcpHarnessRuntimeProvider(AcpHarnessRegistry registry) {
		this(registry, new AcpHarnessInstaller(registry.settings()));
	}

	public RegistryAcpHarnessRuntimeProvider(AcpHarnessRegistry registry, AcpHarnessInstaller installer) {
		this.registry = registry;
		this.installer = installer;
	}

	@Override
	public @Nullable AcpHarnessRuntime forId(String runtimeId) {
		RegistryEntry entry = registry.find(runtimeId);
		return entry == null ? null : new RegistryAcpHarnessRuntime(entry, installer);
	}

	@Override
	public List<String> knownIds() {
		return registry.ids();
	}

}
