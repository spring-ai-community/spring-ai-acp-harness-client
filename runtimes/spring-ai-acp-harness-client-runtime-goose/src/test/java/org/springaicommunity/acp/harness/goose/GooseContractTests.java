package org.springaicommunity.acp.harness.goose;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.test.AcpHarnessProbe;
import org.springaicommunity.acp.harness.test.AcpHarnessRuntimeContract;

/**
 * The portable contract, against a real {@code goose acp} subprocess.
 *
 * <p>
 * Skipped when Goose is absent or cannot reach a provider from this machine, so the suite
 * stays green on a machine that has never installed it.
 */
@EnabledIf("usable")
class GooseContractTests extends AcpHarnessRuntimeContract {

	static boolean usable() {
		return AcpHarnessProbe.isUsable(new GooseRuntime());
	}

	@Override
	protected AcpHarnessRuntime runtime() {
		return new GooseRuntime();
	}

	/**
	 * Goose's model ids are bare, because the provider is chosen separately.
	 *
	 * <p>
	 * {@code gpt-6-luna} is the cheap end of the agentic coding line and still follows an
	 * instruction and calls a tool, which is all the contract asks of it. Goose
	 * advertises whatever its configured provider sells, so on a machine pointed at a
	 * different provider the name doesn't match and the base class falls back to the
	 * model Goose already has.
	 */
	@Override
	protected java.util.List<String> preferredModels() {
		return java.util.List.of("gpt-6-luna");
	}

	@Override
	protected String reviewingMode() {
		return "approve";
	}

}
