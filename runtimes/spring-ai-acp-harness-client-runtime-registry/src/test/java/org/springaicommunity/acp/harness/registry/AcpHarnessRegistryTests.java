package org.springaicommunity.acp.harness.registry;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the catalogue, including the shapes real publishers actually put in it.
 *
 * <p>
 * All offline: the bundled snapshot is on the classpath, which is why it is bundled.
 */
class AcpHarnessRegistryTests {

	@TempDir
	Path cache;

	private AcpHarnessRegistry offlineRegistry() {
		return new AcpHarnessRegistry(new RegistrySettings(null, cache, null, true, true, null));
	}

	@Test
	void readsTheBundledSnapshotWithNoNetwork() {
		AcpHarnessRegistry registry = offlineRegistry();

		assertThat(registry.ids()).contains("goose", "opencode", "codex-acp", "gemini").hasSizeGreaterThan(30);
	}

	@Test
	void readsAnNpxAgent() {
		RegistryEntry gemini = java.util.Objects.requireNonNull(offlineRegistry().find("gemini"));

		assertThat(gemini.distribution()).isInstanceOfSatisfying(RegistryEntry.Distribution.Npx.class, npx -> {
			assertThat(npx.packageSpec()).startsWith("@google/gemini-cli@");
			// The argument that makes it an ACP agent rather than a CLI, and the reason
			// the registry
			// is worth reading at all.
			assertThat(npx.args()).contains("--acp");
		});
	}

	@Test
	void readsABinaryAgentWithItsDigestPerPlatform() {
		RegistryEntry goose = java.util.Objects.requireNonNull(offlineRegistry().find("goose"));

		assertThat(goose.distribution()).isInstanceOfSatisfying(RegistryEntry.Distribution.Binary.class, binary -> {
			RegistryEntry.Artifact artifact = binary.artifacts().get("linux-x86_64");
			assertThat(artifact.command()).isEqualTo("./goose");
			assertThat(artifact.args()).containsExactly("acp");
			assertThat(artifact.findSha256()).isNotNull();
		});
	}

	@Test
	@DisplayName("an entry the catalogue publishes no digest for is readable, and says so")
	void readsAnEntryWithNoDigest() {
		// 9 of the registry's 19 binary agents are like this, which is why
		// require-checksum exists
		// as a property rather than as a constant.
		RegistryEntry cursor = java.util.Objects.requireNonNull(offlineRegistry().find("cursor"));

		assertThat(cursor.distribution()).isInstanceOfSatisfying(RegistryEntry.Distribution.Binary.class,
				binary -> assertThat(binary.artifacts().values())
					.allSatisfy(artifact -> assertThat(artifact.findSha256()).isNull()));
	}

	@Test
	void findsAnAgentWhateverCaseItIsAskedIn() {
		assertThat(offlineRegistry().find("GOOSE")).isNotNull();
	}

	@Test
	void hasNeverHeardOfAnAgentThatIsNotThere() {
		assertThat(offlineRegistry().find("not-an-agent")).isNull();
		assertThat(offlineRegistry().find(null)).isNull();
	}

	@Test
	@DisplayName("one unreadable entry costs that agent, not the catalogue")
	void skipsAnEntryItCannotUnderstand() {
		// The registry is a document 41 third parties contribute to. One of them shipping
		// a shape
		// this version does not model must not be able to take away the other 40.
		AcpHarnessRegistry registry = offlineRegistry();

		var parsed = registry.parse("""
				{"agents": [
				  {"id": "broken"},
				  {"name": "no id at all", "distribution": {"npx": {"package": "x"}}},
				  {"id": "fine", "version": "1.0.0", "distribution": {"npx": {"package": "fine@1.0.0"}}}
				]}
				""");

		assertThat(parsed).containsOnlyKeys("fine");
	}

	@Test
	void readsASnapshotFromAFileUrlWhenOneIsPinned() throws Exception {
		Path pinned = cache.resolve("pinned.json");
		Files.writeString(pinned, """
				{"agents": [{"id": "house-agent", "version": "9.9.9",
				  "distribution": {"npx": {"package": "house-agent@9.9.9", "args": ["--acp"]}}}]}
				""");
		AcpHarnessRegistry registry = new AcpHarnessRegistry(
				new RegistrySettings(pinned.toUri(), cache.resolve("c"), null, false, true, null));

		assertThat(registry.ids()).containsExactly("house-agent");
	}

	@Test
	@DisplayName("a catalogue that cannot be reached falls back rather than failing")
	void fallsBackToTheBundledSnapshotWhenTheFetchFails() {
		// A CDN being briefly unreachable is not a reason for an application not to
		// start. A stale
		// catalogue names older versions of the same agents, which is a much better
		// answer.
		AcpHarnessRegistry registry = new AcpHarnessRegistry(
				new RegistrySettings(java.net.URI.create("https://cdn.agentclientprotocol.invalid/registry.json"),
						cache, java.time.Duration.ZERO, false, true, null));

		assertThat(registry.ids()).contains("goose");
	}

	@Test
	@DisplayName("plain http is refused: this list decides which executables get run")
	void rejectsACatalogueUrlThatIsNotHttpsOrAFile() {
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new RegistrySettings(java.net.URI.create("http://example.com/registry.json"),
					cache, null, false, true, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("https");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(
					() -> new RegistrySettings(java.net.URI.create("registry.json"), cache, null, false, true, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("https");
	}

}
