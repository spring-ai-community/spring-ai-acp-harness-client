package org.springaicommunity.acp.harness.config;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Matches a requested option value against the values an agent actually offers.
 *
 * <p>
 * Needed because the same model is spelled differently by different agents. OpenCode
 * names its models {@code openai/gpt-5.4-mini}, Codex names the same family
 * {@code gpt-5.6-terra}, and goose offers both the bare id and an eighty-odd-entry
 * provider list beside it. A configuration that had to spell each one exactly would
 * defeat the point of a portable {@code model} property, so matching widens in four steps
 * and stops at the first that is unambiguous:
 *
 * <ol>
 * <li>the option value, case-insensitively;</li>
 * <li>the human-readable option name, case-insensitively — {@code GPT-5.4 mini} finds
 * {@code openai/gpt-5.4-mini};</li>
 * <li>{@code <provider>/<request>}, when a provider was configured;</li>
 * <li>any value whose last path segment is the request, if exactly one value
 * qualifies.</li>
 * </ol>
 *
 * <p>
 * The uniqueness condition on the last step matters: two providers offering a model of
 * the same name is exactly when a client must not guess.
 */
final class SelectMatcher {

	private SelectMatcher() {
	}

	/**
	 * Every option, flattened when the agent sent them in groups: a value is matched the
	 * same way wherever it is listed.
	 */
	private static @Nullable List<AcpSchema.SessionConfigSelectOption> optionsOf(AcpSchema.SessionConfigSelect select) {
		AcpSchema.SessionConfigSelectOptions options = select.options();
		return options == null ? null : options.allOptions();
	}

	/**
	 * @param request what the application asked for
	 * @param qualifier a provider id to try as a prefix, or null
	 * @return the value to send, or null when this option does not offer it
	 */
	static @Nullable String match(AcpSchema.SessionConfigSelect select, String request, @Nullable String qualifier) {
		List<AcpSchema.SessionConfigSelectOption> options = optionsOf(select);
		if (options == null || options.isEmpty()) {
			// Not an enumerated select: the agent takes free text, so the request passes
			// through and the
			// agent is the one to reject it.
			return request;
		}
		String wanted = lower(request);

		String exact = options.stream()
			.filter(o -> lower(o.value()).equals(wanted))
			.map(AcpSchema.SessionConfigSelectOption::value)
			.findFirst()
			.orElse(null);
		if (exact != null) {
			return exact;
		}

		String byName = options.stream()
			.filter(o -> lower(o.name()).equals(wanted))
			.map(AcpSchema.SessionConfigSelectOption::value)
			.findFirst()
			.orElse(null);
		if (byName != null) {
			return byName;
		}

		if (qualifier != null && !qualifier.isBlank()) {
			String qualified = lower(qualifier) + "/" + wanted;
			String composite = options.stream()
				.filter(o -> lower(o.value()).equals(qualified))
				.map(AcpSchema.SessionConfigSelectOption::value)
				.findFirst()
				.orElse(null);
			if (composite != null) {
				return composite;
			}
		}

		List<String> suffixed = options.stream()
			.map(AcpSchema.SessionConfigSelectOption::value)
			.filter(v -> lower(v).endsWith("/" + wanted))
			.toList();
		return suffixed.size() == 1 ? suffixed.get(0) : null;
	}

	/**
	 * A few legal values, for an error message that tells the operator what to write
	 * instead.
	 */
	static String examples(AcpSchema.SessionConfigSelect select) {
		List<AcpSchema.SessionConfigSelectOption> options = optionsOf(select);
		if (options == null || options.isEmpty()) {
			return "no enumerated values";
		}
		String sample = options.stream()
			.limit(5)
			.map(AcpSchema.SessionConfigSelectOption::value)
			.reduce((a, b) -> a + ", " + b)
			.orElse("");
		return options.size() > 5 ? sample + ", … (" + options.size() + " in all)" : sample;
	}

	private static String lower(@Nullable String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

}
