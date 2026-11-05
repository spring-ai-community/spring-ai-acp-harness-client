package org.springaicommunity.acp.harness.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Which model provider to talk to, and the credentials for it.
 *
 * <p>
 * Split across all three configuration tiers, which is why it is one record rather than
 * four properties. The {@code id} is a <em>negotiated</em> request — the agent decides
 * whether it has a provider by that name. The {@code apiType}, {@code baseUrl} and
 * {@code apiKey} are portable in intent but not in mechanism: ACP's {@code providers/set}
 * carries them when an agent advertises that capability, and otherwise they reach the
 * agent as environment variables the adapter names.
 *
 * <p>
 * A {@code baseUrl} makes this a <em>bring-your-own endpoint</em> provider, which changes
 * who is authoritative about models: an agent's built-in catalogue cannot know what a
 * private endpoint serves, so {@link #findModelsUrl()} — the endpoint's own listing — is
 * consulted instead. See {@code ConfigResolver} for what that buys.
 *
 * <p>
 * Fixed when the agent process starts, never per request. An HTTP endpoint and a
 * credential that could change per call would mean a pool of processes with different
 * credentials and no way to tell them apart; see {@link AcpHarnessOptions} for what a
 * caller genuinely may vary.
 */
public record ProviderSpec(@Nullable String id, @Nullable String apiType, @Nullable URI baseUrl,
		@Nullable String apiKey, Map<String, String> headers) {

	private static final ProviderSpec NONE = new ProviderSpec(null, null, null, null, Map.of());

	public ProviderSpec {
		if (id != null && !id.isBlank()) {
			Validation.requireName(id, "provider id");
		}
		if (apiType != null && !apiType.isBlank()) {
			Validation.requireName(apiType, "provider api-type");
		}
		if (baseUrl != null) {
			Validation.requireSecureUrl(baseUrl, "provider base-url");
		}
		if (apiKey != null && !apiKey.isEmpty()) {
			Validation.requireSecret(apiKey, "provider api-key");
		}
		headers = headers == null ? Map.of() : sanitized(headers);
	}

	private static Map<String, String> sanitized(Map<String, String> headers) {
		Map<String, String> copy = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			Validation.requireHeaderName(name);
			Validation.requireHeaderValue(name, value);
			copy.put(name, value);
		});
		return Map.copyOf(copy);
	}

	/**
	 * The trailing segment of an OpenAI-style base URL that names the API version: v1,
	 * v2, v1beta.
	 */
	private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+[a-z]*");

	/** No provider configured: the agent keeps whatever it was configured with itself. */
	public static ProviderSpec none() {
		return NONE;
	}

	public static ProviderSpec of(@Nullable String id) {
		return new ProviderSpec(id, null, null, null, Map.of());
	}

	/** The provider id, or null when none or a blank one was configured. */
	public @Nullable String findId() {
		return id == null || id.isBlank() ? null : id;
	}

	/** The API type, or null when none or a blank one was configured. */
	public @Nullable String findApiType() {
		return apiType == null || apiType.isBlank() ? null : apiType;
	}

	/** The API key, or null when none or an empty one was configured. */
	public @Nullable String findApiKey() {
		return apiKey == null || apiKey.isEmpty() ? null : apiKey;
	}

	/**
	 * Whether anything beyond the name was configured — the part that has to reach the
	 * process.
	 */
	public boolean hasCredentials() {
		return findApiKey() != null || baseUrl != null || !headers.isEmpty();
	}

	/**
	 * Whether this provider names an endpoint of its own — a gateway, a proxy, a
	 * self-hosted model server — rather than the one its agent was built knowing about.
	 */
	public boolean isByo() {
		return baseUrl != null;
	}

	/**
	 * The base URL in the one form every OpenAI-compatible client agrees on: everything
	 * up to and including the API version segment, as {@code OPENAI_BASE_URL} and the
	 * vendor SDKs spell it.
	 *
	 * <p>
	 * Configuration arrives both ways in practice — a platform that hands out
	 * {@code https://gateway.example.com/my-endpoint/openai} and a developer who copies
	 * {@code https://api.example.com/v1} out of a README both mean the same endpoint — so
	 * a base URL that does not already end in a version segment gains {@code /v1}.
	 * Normalising here rather than in each adapter is what lets an adapter derive its own
	 * vendor spelling from one known shape.
	 * @return the normalised base URL, or null when no endpoint was configured
	 */
	public @Nullable URI findApiBase() {
		return baseUrl == null ? null : normalize(baseUrl);
	}

	/**
	 * Where to ask the endpoint what it actually serves: {@code {apiBase}/models}.
	 *
	 * <p>
	 * OpenAI-compatible endpoints are the only ones this can be derived for, which is
	 * also the only case it is needed in — an agent talking to its own vendor already has
	 * a catalogue.
	 * @return the listing's URL, or null when no endpoint was configured
	 */
	public @Nullable URI findModelsUrl() {
		URI base = findApiBase();
		return base == null ? null : URI.create(base + "/models");
	}

	private static URI normalize(URI baseUrl) {
		String text = baseUrl.toString();
		while (text.endsWith("/")) {
			text = text.substring(0, text.length() - 1);
		}
		int lastSegment = text.lastIndexOf('/');
		String segment = lastSegment < 0 ? "" : text.substring(lastSegment + 1);
		return URI.create(VERSION_SEGMENT.matcher(segment).matches() ? text : text + "/v1");
	}

	public ProviderSpec withId(@Nullable String newId) {
		return newId == null || newId.equals(id) ? this : new ProviderSpec(newId, apiType, baseUrl, apiKey, headers);
	}

	/**
	 * Never renders the key or the headers: this record ends up in log lines and
	 * exception messages.
	 */
	@Override
	public String toString() {
		return "ProviderSpec[id=" + id + ", apiType=" + apiType + ", baseUrl=" + baseUrl + ", apiKey="
				+ (findApiKey() != null ? "***" : "none") + ", headers=" + headers.keySet() + "]";
	}
}
