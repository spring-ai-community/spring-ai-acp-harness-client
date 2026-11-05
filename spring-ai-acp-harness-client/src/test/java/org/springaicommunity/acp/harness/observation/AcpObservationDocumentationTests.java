package org.springaicommunity.acp.harness.observation;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.common.docs.KeyName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documentation enum is the contract an application reads; the default conventions
 * are what reaches the registry. They are maintained by hand in two places, so this pins
 * them to each other: every documented key is emitted, and nothing undocumented is.
 */
class AcpObservationDocumentationTests {

	@Test
	void theTurnConventionEmitsExactlyTheDocumentedKeysUnderTheDocumentedName() {
		AcpHarnessTurnObservationContext context = new AcpHarnessTurnObservationContext(
				new AcpHarnessObservations.TurnContext("goose", "review-123", "gpt-5.4-mini", false));
		context.outcome("END_TURN");
		context.usage(4441, 200000, 0.008932, "USD");
		DefaultAcpHarnessTurnObservationConvention convention = DefaultAcpHarnessTurnObservationConvention.INSTANCE;

		assertThat(convention.getName()).isEqualTo(AcpObservationDocumentation.TURN_NAME);
		assertThat(keysOf(convention.getLowCardinalityKeyValues(context)))
			.containsExactlyInAnyOrderElementsOf(namesOf(AcpObservationDocumentation.TURN.getLowCardinalityKeyNames()));
		assertThat(keysOf(convention.getHighCardinalityKeyValues(context))).containsExactlyInAnyOrderElementsOf(
				namesOf(AcpObservationDocumentation.TURN.getHighCardinalityKeyNames()));
	}

	@Test
	void theToolCallConventionEmitsExactlyTheDocumentedKeysUnderTheDocumentedName() {
		ToolCallObservationContext context = new ToolCallObservationContext("goose", "t1", "Read the build file",
				"READ");
		context.status("COMPLETED");
		DefaultToolCallObservationConvention convention = DefaultToolCallObservationConvention.INSTANCE;

		assertThat(convention.getName()).isEqualTo(AcpObservationDocumentation.TOOL_CALL_NAME);
		assertThat(keysOf(convention.getLowCardinalityKeyValues(context))).containsExactlyInAnyOrderElementsOf(
				namesOf(AcpObservationDocumentation.TOOL_CALL.getLowCardinalityKeyNames()));
		assertThat(keysOf(convention.getHighCardinalityKeyValues(context))).containsExactlyInAnyOrderElementsOf(
				namesOf(AcpObservationDocumentation.TOOL_CALL.getHighCardinalityKeyNames()));
	}

	@Test
	void everyKeyOfOurOwnSitsUnderThePrefixAndTheSharedOnesAreSpringAis() {
		List<String> all = namesOf(AcpObservationDocumentation.TURN.getLowCardinalityKeyNames());
		all.addAll(namesOf(AcpObservationDocumentation.TURN.getHighCardinalityKeyNames()));
		all.addAll(namesOf(AcpObservationDocumentation.TOOL_CALL.getLowCardinalityKeyNames()));
		all.addAll(namesOf(AcpObservationDocumentation.TOOL_CALL.getHighCardinalityKeyNames()));

		assertThat(all)
			.allSatisfy(
					key -> assertThat(key).matches(
							k -> k.startsWith(AcpObservationDocumentation.PREFIX + ".") || k.equals("spring.ai.kind")
									|| k.equals("gen_ai.response.model"),
							"our prefix, or a key Spring AI already uses"));
		assertThat(all).noneMatch(key -> key.startsWith("acp."));
	}

	private static List<String> keysOf(KeyValues values) {
		return values.stream().map(KeyValue::getKey).toList();
	}

	private static List<String> namesOf(KeyName[] names) {
		return new java.util.ArrayList<>(Arrays.stream(names).map(KeyName::asString).toList());
	}

}
