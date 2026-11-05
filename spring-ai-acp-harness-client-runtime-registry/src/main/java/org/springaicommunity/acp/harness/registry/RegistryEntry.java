package org.springaicommunity.acp.harness.registry;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One agent as the ACP registry publishes it.
 *
 * <p>
 * Only the fields that decide how to start it are modelled. The description, icon,
 * authors and licence are catalogue copy for a human choosing an agent, and a server-side
 * library choosing one from a property has no use for them.
 *
 * @param name the agent's display name, or null when the registry gives none
 * @param version the published version, or null when the registry gives none
 * @param distribution how to obtain it, or null when the registry describes no way this
 * library understands
 */
public record RegistryEntry(String id, @Nullable String name, @Nullable String version,
		@Nullable Distribution distribution) {

	public RegistryEntry {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("registry entry has no id");
		}
	}

	/** The three ways the registry says an agent can be obtained. */
	public sealed interface Distribution {

		/**
		 * Extra environment the registry says this agent needs, which is usually none.
		 */
		Map<String, String> env();

		/**
		 * A platform-specific archive to download, verify and unpack.
		 *
		 * <p>
		 * Keyed by {@link Platform#id()}. An agent publishing no build for the running
		 * platform is unusable here, and says so at launch rather than at startup.
		 */
		record Binary(Map<String, Artifact> artifacts) implements Distribution {

			public Binary {
				artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
			}

			@Override
			public Map<String, String> env() {
				return Map.of();
			}

			/**
			 * The build for {@code platform}, trying its fallbacks in order, or null when
			 * there is none.
			 */
			public @Nullable Artifact forPlatform(Platform platform) {
				return platform.candidateKeys()
					.stream()
					.map(artifacts::get)
					.filter(Objects::nonNull)
					.findFirst()
					.orElse(null);
			}
		}

		/** {@code npx -y <package> <args>}: the agent is a published npm package. */
		record Npx(String packageSpec, List<String> args, Map<String, String> env) implements Distribution {

			public Npx {
				args = args == null ? List.of() : List.copyOf(args);
				env = env == null ? Map.of() : Map.copyOf(env);
			}
		}

		/** {@code uvx <package> <args>}: the same idea on PyPI. */
		record Uvx(String packageSpec, List<String> args, Map<String, String> env) implements Distribution {

			public Uvx {
				args = args == null ? List.of() : List.copyOf(args);
				env = env == null ? Map.of() : Map.copyOf(env);
			}
		}

	}

	/**
	 * One downloadable build.
	 *
	 * @param archive where to fetch it
	 * @param command the executable inside it, relative to where it unpacks
	 * @param args what to pass the executable to make it speak ACP, usually
	 * {@code ["acp"]}
	 * @param sha256 the expected digest, or {@code null} — which 9 of the registry's 19
	 * binary agents publish, and is the whole reason {@code require-checksum} exists as a
	 * property
	 */
	public record Artifact(URI archive, String command, List<String> args, Map<String, String> env,
			@Nullable String sha256) {

		public Artifact {
			if (archive == null) {
				throw new IllegalArgumentException("registry artifact has no archive url");
			}
			args = args == null ? List.of() : List.copyOf(args);
			env = env == null ? Map.of() : Map.copyOf(env);
		}

		/** The published digest, or null when the registry gives none or a blank one. */
		public @Nullable String findSha256() {
			return sha256 == null || sha256.isBlank() ? null : sha256;
		}
	}
}
