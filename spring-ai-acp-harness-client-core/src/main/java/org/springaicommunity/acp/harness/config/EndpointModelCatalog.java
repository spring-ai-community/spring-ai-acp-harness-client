package org.springaicommunity.acp.harness.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code GET {base-url}/models} — the listing every OpenAI-compatible endpoint
 * publishes — and remembers the answer.
 *
 * <p>
 * One request per endpoint per process, made the first time a model is resolved rather
 * than at startup, so an application that never names a model never makes it. It is the
 * same endpoint the agent is about to be pointed at with the same credentials, so it
 * reveals nothing new and costs one round trip.
 *
 * <p>
 * Every failure is null rather than an exception, at debug. An endpoint that answers 404,
 * serves a shape nobody standardised, or is simply slow today is not a reason to refuse
 * to start: the consequence of an unknown catalogue is that a model is taken on trust,
 * which is exactly what happened before this class existed.
 */
final class EndpointModelCatalog implements ModelCatalog {

	private static final Logger logger = LoggerFactory.getLogger(EndpointModelCatalog.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final JsonMapper json = JsonMapper.shared();

	/**
	 * Keyed by the listing's URL, so two providers onto one gateway ask once. An empty
	 * list records an endpoint that did not say, so it is not asked again either.
	 */
	private final Map<URI, List<String>> cache = new ConcurrentHashMap<>();

	private final HttpClient http;

	EndpointModelCatalog() {
		this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
	}

	EndpointModelCatalog(HttpClient http) {
		this.http = http;
	}

	@Override
	public @Nullable List<String> modelsOf(@Nullable ProviderSpec provider) {
		URI url = provider == null ? null : provider.findModelsUrl();
		if (url == null) {
			return null;
		}
		List<String> models = cache.computeIfAbsent(url, key -> fetch(key, provider));
		return models.isEmpty() ? null : models;
	}

	/** The listing, or an empty list when the endpoint did not say. */
	private List<String> fetch(URI url, ProviderSpec provider) {
		try {
			HttpResponse<String> response = http.send(request(url, provider), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				logger.debug("Endpoint {} answered {} for its model listing; models will be taken on trust", url,
						response.statusCode());
				return List.of();
			}
			List<String> models = parse(response.body());
			if (models.isEmpty()) {
				logger.debug("Endpoint {} listed no models in a shape this understands", url);
				return List.of();
			}
			logger.debug("Endpoint {} serves {}", url, models);
			return List.copyOf(models);
		}
		catch (IOException | RuntimeException ex) {
			logger.debug("Could not read the model listing at {}; models will be taken on trust", url, ex);
			return List.of();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}

	private static HttpRequest request(URI url, ProviderSpec provider) {
		HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET();
		String apiKey = provider.findApiKey();
		if (apiKey != null) {
			request.header("Authorization", "Bearer " + apiKey);
		}
		provider.headers().forEach(request::header);
		return request.build();
	}

	/**
	 * {@code {"data":[{"id":...}]}}, and the bare array some gateways return instead.
	 *
	 * <p>
	 * Anything else parses to nothing, which the caller reads as "the endpoint did not
	 * say".
	 */
	private static List<String> parse(String body) {
		try {
			JsonNode root = json.readTree(body);
			JsonNode entries = root.isArray() ? root : root.path("data");
			List<String> models = new ArrayList<>();
			entries.forEach(entry -> {
				JsonNode id = entry.isString() ? entry : entry.path("id");
				if (id.isString() && !id.asString().isBlank()) {
					models.add(id.asString());
				}
			});
			return models;
		}
		catch (JacksonException ex) {
			return List.of();
		}
	}

}
