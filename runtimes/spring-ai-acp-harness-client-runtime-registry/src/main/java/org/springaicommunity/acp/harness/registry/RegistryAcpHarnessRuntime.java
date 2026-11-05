package org.springaicommunity.acp.harness.registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.config.ProviderEnvironment;
import org.springaicommunity.acp.harness.runtime.AcpHarnessEnvironment;
import org.springaicommunity.acp.harness.runtime.AcpHarnessLaunchSpec;
import org.springaicommunity.acp.harness.runtime.AcpHarnessRuntime;
import org.springaicommunity.acp.harness.runtime.ToolNames;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * An {@link AcpHarnessRuntime} for an agent nobody wrote an adapter for.
 *
 * <p>
 * This is what makes "any ACP agent" a claim rather than a roadmap item. Set
 * {@code spring.ai.acp.harness.runtime: gemini} with no Gemini adapter on the classpath
 * and this resolves the entry from the registry snapshot, obtains the agent the way the
 * registry says to — an npm package through {@code npx}, a Python one through
 * {@code uvx}, or a verified archive for this platform — and launches it with the
 * arguments the registry says make it speak ACP.
 *
 * <p>
 * <strong>What it cannot do is the more useful half of the documentation.</strong> An
 * adapter exists to carry the things ACP does not standardize: what an agent calls its
 * options, where it reads its config file, which environment variable holds a key, how it
 * buries a tool name. None of that is in the registry, and none of it can be guessed. So
 * this runtime provisions nothing, writes no files, and reaches the negotiated tier only
 * through the portable half of the option vocabulary — the ACP {@code category} on an
 * advertised config option, plus the obvious ids {@code model}, {@code mode} and
 * {@code provider}. An agent that names its options anything else reports them
 * unsupported, honestly, through the same {@code on-unsupported} an adapter would.
 *
 * <p>
 * Tier 3 still works, because tier 3 is the escape hatch for exactly this:
 *
 * <pre>{@code
 * spring:
 *   ai:
 *     acp:
 *       harness:
 *         runtime: gemini
 *         runtimes:
 *           gemini:
 *             args: [--yolo]                    # appended to what the registry supplies
 *             env: { GEMINI_API_KEY: "${KEY}" }
 * }</pre>
 */
public class RegistryAcpHarnessRuntime implements AcpHarnessRuntime {

	private final RegistryEntry entry;

	private final AcpHarnessInstaller installer;

	private final Platform platform;

	public RegistryAcpHarnessRuntime(RegistryEntry entry, AcpHarnessInstaller installer) {
		this(entry, installer, Platform.current());
	}

	RegistryAcpHarnessRuntime(RegistryEntry entry, AcpHarnessInstaller installer, Platform platform) {
		this.entry = entry;
		this.installer = installer;
		this.platform = platform;
	}

	@Override
	public String id() {
		return entry.id();
	}

	/** What the registry says this agent is, for a log line that names a version. */
	public RegistryEntry entry() {
		return entry;
	}

	@Override
	public AcpHarnessLaunchSpec launch(AcpHarnessSettings settings) {
		RegistryEntry.Distribution distribution = entry.distribution();
		if (distribution == null) {
			throw new AcpHarnessInstaller.AcpHarnessInstallException(
					"The ACP registry says nothing about how to obtain '" + entry.id() + "'");
		}
		return switch (distribution) {
			case RegistryEntry.Distribution.Binary binary -> launchBinary(binary, settings);
			case RegistryEntry.Distribution.Npx npx ->
				stdio("npx", prepend(List.of("-y", npx.packageSpec()), npx.args(), settings), npx.env(), settings);
			case RegistryEntry.Distribution.Uvx uvx ->
				stdio("uvx", prepend(List.of(uvx.packageSpec()), uvx.args(), settings), uvx.env(), settings);
		};
	}

	private AcpHarnessLaunchSpec launchBinary(RegistryEntry.Distribution.Binary binary, AcpHarnessSettings settings) {
		RegistryEntry.Artifact artifact = binary.forPlatform(platform);
		if (artifact == null) {
			throw new AcpHarnessInstaller.AcpHarnessInstallException(
					"The ACP registry publishes no build of '" + entry.id() + "' for " + platform.id() + "; it has "
							+ binary.artifacts().keySet().stream().sorted().toList());
		}
		Path command = installer.install(entry, artifact);
		return stdio(command.toString(), prepend(List.of(), artifact.args(), settings), artifact.env(), settings);
	}

	/**
	 * The registry's own arguments first, then the application's.
	 *
	 * <p>
	 * The registry's are what make the agent speak ACP at all — {@code acp},
	 * {@code --acp} — so they are not the application's to reorder, and tier 3 appends
	 * rather than replaces.
	 */
	private static List<String> prepend(List<String> leading, List<String> registryArgs, AcpHarnessSettings settings) {
		List<String> args = new ArrayList<>(leading);
		args.addAll(registryArgs);
		args.addAll(settings.runtimeOptions().textList("args"));
		return args;
	}

	private AcpHarnessLaunchSpec stdio(String command, List<String> args, Map<String, String> registryEnv,
			AcpHarnessSettings settings) {
		return new AcpHarnessLaunchSpec.Stdio(command, args, environment(registryEnv, settings));
	}

	/**
	 * The environment the agent starts with: the registry's own defaults, then the
	 * provider's credentials under the derived variable names, then tier 3. See
	 * {@link AcpHarnessEnvironment}.
	 */
	private static Map<String, String> environment(Map<String, String> registryEnv, AcpHarnessSettings settings) {
		return AcpHarnessEnvironment.layered(registryEnv, ProviderEnvironment.of(settings.provider()), settings);
	}

	/**
	 * Only an identifier the agent actually published, never the title.
	 *
	 * <p>
	 * An adapter may fall back to the title because its author knows that agent's titles
	 * are stable tool names. Nothing here knows that about an agent it has never seen,
	 * and a wrong answer would turn an allowlist into an approval.
	 */
	@Override
	public @Nullable String toolNameOf(AcpSchema.@Nullable ToolCallUpdate toolCall) {
		return ToolNames.fromRawInput(toolCall);
	}

	/**
	 * Whether the provider was carried outside the protocol after all.
	 *
	 * <p>
	 * An agent with no {@code provider} config option and no {@code providers/set} still
	 * gets the key, because it went onto the process environment at launch. Saying so is
	 * what stops {@code on-unsupported: fail} firing on a configuration that is working.
	 */
	@Override
	public boolean appliedOutOfBand(PortableOption option, AcpHarnessSettings settings) {
		return option == PortableOption.PROVIDER && !ProviderEnvironment.of(settings.provider()).isEmpty();
	}

}
