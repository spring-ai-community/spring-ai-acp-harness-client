package org.springaicommunity.acp.harness.runtime;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;

import static org.assertj.core.api.Assertions.assertThat;

class AcpHarnessEnvironmentTests {

	@TempDir
	Path workspace;

	@Test
	void credentialsOverrideDefaultsAndTierThreeOverridesBoth() {
		AcpHarnessSettings settings = AcpHarnessSettings.builder("test", workspace)
			.runtimeOptions(Map.of("env", Map.of("SHARED", "tier-3", "ONLY_TIER_3", "c")))
			.build();

		Map<String, String> env = AcpHarnessEnvironment.layered(Map.of("SHARED", "default", "ONLY_DEFAULT", "a"),
				Map.of("SHARED", "credential", "ONLY_CREDENTIAL", "b"), settings);

		assertThat(env).containsExactlyInAnyOrderEntriesOf(
				Map.of("SHARED", "tier-3", "ONLY_DEFAULT", "a", "ONLY_CREDENTIAL", "b", "ONLY_TIER_3", "c"));
	}

}
