package org.springaicommunity.acp.harness.registry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ACP agent catalogue, read from a snapshot.
 *
 * <p>
 * Three sources, in the order they are tried: a copy in the cache that is younger than
 * {@code refresh}, a fresh fetch of the published catalogue, and the copy bundled in this
 * jar. The last one is why this works with no network at all, and why an application that
 * has never reached the CDN can still launch an agent whose bytes happen to be in the
 * cache already.
 *
 * <p>
 * <strong>A fetch that fails is not a failure.</strong> A stale snapshot names an older
 * version of the same agents, which is a worse answer than a fresh one and a much better
 * answer than an application that will not start because a CDN was briefly unreachable.
 * So a failed refresh logs and falls through, and only a machine with no snapshot from
 * any of the three sources is an error — which cannot happen, because the third is on the
 * classpath.
 *
 * <p>
 * Loaded once and kept: the catalogue decides what to launch, and an agent whose
 * definition changed under a running application would be a worse surprise than one that
 * needs a restart to pick up a new version.
 */
public class AcpHarnessRegistry {

	private static final Logger logger = LoggerFactory.getLogger(AcpHarnessRegistry.class);

	/** The copy shipped in this jar, so the catalogue is never unavailable. */
	static final String BUNDLED_SNAPSHOT = "/org/springaicommunity/acp/harness/registry/registry-snapshot.json";

	private final RegistrySettings settings;

	private final JsonMapper mapper = JsonMapper.shared();

	private final AtomicReference<Map<String, RegistryEntry>> entries = new AtomicReference<>();

	public AcpHarnessRegistry(@Nullable RegistrySettings settings) {
		this.settings = settings == null ? RegistrySettings.defaults() : settings;
	}

	public RegistrySettings settings() {
		return settings;
	}

	/** The entry for {@code id}, or null if the catalogue has never heard of it. */
	public @Nullable RegistryEntry find(@Nullable String id) {
		return id == null ? null : entries().get(id.toLowerCase(Locale.ROOT));
	}

	/** Every agent id the catalogue knows, sorted. */
	public List<String> ids() {
		return entries().values().stream().map(RegistryEntry::id).sorted().toList();
	}

	private Map<String, RegistryEntry> entries() {
		Map<String, RegistryEntry> loaded = entries.get();
		if (loaded == null) {
			synchronized (this) {
				loaded = entries.get();
				if (loaded == null) {
					loaded = load();
					entries.set(loaded);
				}
			}
		}
		return loaded;
	}

	private Map<String, RegistryEntry> load() {
		String cached = readFreshCache();
		if (cached != null) {
			Map<String, RegistryEntry> parsed = parseQuietly(cached, "the cached snapshot");
			return parsed != null ? parsed : bundled();
		}
		if (!settings.offline()) {
			String fetched = fetch();
			if (fetched != null) {
				Map<String, RegistryEntry> parsed = parseQuietly(fetched, settings.url().toString());
				if (parsed != null) {
					writeCache(fetched);
					return parsed;
				}
			}
		}
		// A snapshot too old to refresh still names real agents; an unreachable CDN does
		// not.
		String stale = readAnyCache();
		Map<String, RegistryEntry> parsed = stale == null ? null : parseQuietly(stale, "the stale cached snapshot");
		return parsed != null ? parsed : bundled();
	}

	/** The cached snapshot, or null when there is none young enough. */
	private @Nullable String readFreshCache() {
		Path file = settings.snapshotFile();
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			Instant modified = Files.getLastModifiedTime(file).toInstant();
			if (modified.plus(settings.refresh()).isBefore(Instant.now())) {
				return null;
			}
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			logger.debug("Could not read the cached ACP registry snapshot at {}", file, ex);
			return null;
		}
	}

	/** The cached snapshot whatever its age, or null when there is none. */
	private @Nullable String readAnyCache() {
		Path file = settings.snapshotFile();
		try {
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	/** The published catalogue, or null when it could not be fetched. */
	private @Nullable String fetch() {
		URI url = settings.url();
		if ("file".equalsIgnoreCase(url.getScheme())) {
			try {
				return Files.readString(Path.of(url), StandardCharsets.UTF_8);
			}
			catch (IOException | RuntimeException ex) {
				logger.warn("Could not read the ACP registry from {}: {}", url, ex.toString());
				return null;
			}
		}
		try (HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build()) {
			HttpResponse<String> response = client.send(
					HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				logger.warn("The ACP registry at {} answered HTTP {}; using the snapshot on disk", url,
						response.statusCode());
				return null;
			}
			return response.body();
		}
		catch (IOException ex) {
			logger.warn("Could not reach the ACP registry at {}: {}", url, ex.toString());
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private void writeCache(String json) {
		Path file = settings.snapshotFile();
		try {
			Files.createDirectories(file.getParent());
			Path temporary = Files.createTempFile(file.getParent(), "registry", ".json");
			Files.writeString(temporary, json, StandardCharsets.UTF_8);
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ex) {
			// A cache that cannot be written costs a fetch per start, which is not worth
			// failing over.
			logger.debug("Could not cache the ACP registry snapshot at {}", file, ex);
		}
	}

	private Map<String, RegistryEntry> bundled() {
		try (InputStream in = AcpHarnessRegistry.class.getResourceAsStream(BUNDLED_SNAPSHOT)) {
			if (in == null) {
				throw new IllegalStateException("The bundled ACP registry snapshot is missing from this jar");
			}
			logger.info("Using the ACP registry snapshot bundled with spring-ai-acp-harness-client");
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read the bundled ACP registry snapshot", ex);
		}
	}

	/** The agents in {@code json}, or null when it lists none or cannot be read. */
	private @Nullable Map<String, RegistryEntry> parseQuietly(String json, String source) {
		try {
			Map<String, RegistryEntry> parsed = parse(json);
			if (parsed.isEmpty()) {
				logger.warn("The ACP registry from {} listed no agents", source);
				return null;
			}
			return parsed;
		}
		catch (RuntimeException ex) {
			logger.warn("Could not read the ACP registry from {}: {}", source, ex.toString());
			return null;
		}
	}

	/**
	 * Reads the catalogue, one agent at a time.
	 *
	 * <p>
	 * An entry that cannot be read costs that agent rather than the catalogue. The
	 * registry is a document 41 third parties contribute to, so one of them publishing a
	 * shape this version does not understand is an ordinary event, and it should not be
	 * able to take away the other 40 — the same reasoning as
	 * {@code SessionUpdateDecoder}'s, for the same class of problem.
	 */
	Map<String, RegistryEntry> parse(String json) {
		JsonNode root;
		try {
			root = mapper.readTree(json);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("The ACP registry is not valid JSON", ex);
		}
		Map<String, RegistryEntry> parsed = new LinkedHashMap<>();
		for (JsonNode agent : root.path("agents")) {
			try {
				RegistryEntry entry = entry(agent);
				if (entry.distribution() != null) {
					parsed.put(entry.id().toLowerCase(Locale.ROOT), entry);
				}
			}
			catch (RuntimeException ex) {
				logger.debug("Skipping an unreadable ACP registry entry: {}", ex.toString());
			}
		}
		return parsed;
	}

	private RegistryEntry entry(JsonNode agent) {
		return new RegistryEntry(required(agent, "id"), text(agent, "name"), text(agent, "version"),
				distribution(agent.path("distribution")));
	}

	private RegistryEntry.@Nullable Distribution distribution(JsonNode node) {
		if (node.has("binary")) {
			Map<String, RegistryEntry.Artifact> artifacts = new LinkedHashMap<>();
			node.path("binary").properties().forEach(entry -> {
				try {
					artifacts.put(entry.getKey(), artifact(entry.getValue()));
				}
				catch (RuntimeException ex) {
					logger.debug("Skipping registry platform {}: {}", entry.getKey(), ex.toString());
				}
			});
			return artifacts.isEmpty() ? null : new RegistryEntry.Distribution.Binary(artifacts);
		}
		if (node.has("npx")) {
			JsonNode npx = node.path("npx");
			return new RegistryEntry.Distribution.Npx(required(npx, "package"), strings(npx.path("args")),
					stringMap(npx.path("env")));
		}
		if (node.has("uvx")) {
			JsonNode uvx = node.path("uvx");
			return new RegistryEntry.Distribution.Uvx(required(uvx, "package"), strings(uvx.path("args")),
					stringMap(uvx.path("env")));
		}
		return null;
	}

	private RegistryEntry.Artifact artifact(JsonNode node) {
		return new RegistryEntry.Artifact(URI.create(required(node, "archive")), required(node, "cmd"),
				strings(node.path("args")), stringMap(node.path("env")), text(node, "sha256"));
	}

	/**
	 * A field the entry cannot be used without; the caller skips the entry when it is
	 * missing.
	 */
	private static String required(JsonNode node, String field) {
		String value = text(node, field);
		if (value == null) {
			throw new IllegalArgumentException("registry entry has no " + field);
		}
		return value;
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.asString() : null;
	}

	private static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		node.forEach(element -> {
			if (element.isString()) {
				values.add(element.asString());
			}
		});
		return values;
	}

	private static Map<String, String> stringMap(JsonNode node) {
		Map<String, String> values = new LinkedHashMap<>();
		node.properties().forEach(entry -> {
			if (entry.getValue().isValueNode()) {
				values.put(entry.getKey(), entry.getValue().asString());
			}
		});
		return values;
	}

}
