package org.springaicommunity.acp.harness.config;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one shape every adapter derives its own vendor spelling from.
 */
class ProviderSpecTests {

	private static ProviderSpec withBaseUrl(String baseUrl) {
		return new ProviderSpec("acme", "openai", URI.create(baseUrl), "k", Map.of());
	}

	@Test
	void aBaseUrlThatAlreadyNamesTheApiVersionIsLeftAlone() {
		assertThat(withBaseUrl("https://api.example.com/v1").findApiBase())
			.isEqualTo(URI.create("https://api.example.com/v1"));
	}

	@Test
	void aBaseUrlWithoutOneGainsIt() {
		// What a platform hands out: one endpoint per deployment, no version in the URL.
		assertThat(withBaseUrl("https://gateway.example.com/team-x/openai").findApiBase())
			.isEqualTo(URI.create("https://gateway.example.com/team-x/openai/v1"));
	}

	@Test
	void trailingSlashesDoNotMakeADifferentEndpoint() {
		assertThat(withBaseUrl("https://api.example.com/v1/").findApiBase())
			.isEqualTo(URI.create("https://api.example.com/v1"));
	}

	@Test
	void versionsOtherThanV1AreVersionsToo() {
		assertThat(withBaseUrl("https://api.example.com/v1beta").findApiBase())
			.isEqualTo(URI.create("https://api.example.com/v1beta"));
	}

	@Test
	void theModelListingHangsOffTheCanonicalBase() {
		assertThat(withBaseUrl("https://gateway.example.com/team-x/openai").findModelsUrl())
			.isEqualTo(URI.create("https://gateway.example.com/team-x/openai/v1/models"));
	}

	@Test
	void aProviderWithNoEndpointOfItsOwnIsNotByo() {
		ProviderSpec vendor = new ProviderSpec("openai", "openai", null, "k", Map.of());

		assertThat(vendor.isByo()).isFalse();
		assertThat(vendor.findModelsUrl()).isNull();
		assertThat(withBaseUrl("https://api.example.com/v1").isByo()).isTrue();
	}

}
