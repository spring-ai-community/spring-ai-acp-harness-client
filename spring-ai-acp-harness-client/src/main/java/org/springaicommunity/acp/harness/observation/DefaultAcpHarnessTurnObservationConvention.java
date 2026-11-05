package org.springaicommunity.acp.harness.observation;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.observation.AcpObservationDocumentation.TurnHighCardinalityKeys;
import org.springaicommunity.acp.harness.observation.AcpObservationDocumentation.TurnLowCardinalityKeys;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationConvention;

/**
 * Turns a turn into tags.
 *
 * <p>
 * A separate, replaceable class because the split between low and high cardinality is a
 * deployment decision as much as a design one: an application running one conversation
 * per user may want the session name on the meter, and an application running a million
 * must not.
 */
public class DefaultAcpHarnessTurnObservationConvention
		implements ObservationConvention<AcpHarnessTurnObservationContext> {

	public static final DefaultAcpHarnessTurnObservationConvention INSTANCE = new DefaultAcpHarnessTurnObservationConvention();

	/** Absent is a fact worth tagging; a missing tag is a hole in a dashboard. */
	private static final String UNKNOWN = "unknown";

	@Override
	public String getName() {
		return "acp.turn";
	}

	@Override
	public String getContextualName(AcpHarnessTurnObservationContext context) {
		return "acp turn " + context.turn().runtimeId();
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(AcpHarnessTurnObservationContext context) {
		AcpHarnessObservations.TurnContext turn = context.turn();
		return KeyValues.of(TurnLowCardinalityKeys.RUNTIME.withValue(orUnknown(turn.runtimeId())),
				TurnLowCardinalityKeys.MODEL.withValue(orUnknown(turn.model())),
				TurnLowCardinalityKeys.SESSION_KIND.withValue(turn.ephemeral() ? "ephemeral" : "named"),
				TurnLowCardinalityKeys.OUTCOME.withValue(orUnknown(context.outcome())));
	}

	@Override
	public KeyValues getHighCardinalityKeyValues(AcpHarnessTurnObservationContext context) {
		KeyValues values = KeyValues
			.of(TurnHighCardinalityKeys.SESSION_NAME.withValue(orUnknown(context.turn().sessionName())));
		if (context.contextUsed() != null) {
			values = values.and(KeyValue.of(TurnHighCardinalityKeys.CONTEXT_USED.asString(),
					String.valueOf(context.contextUsed())));
		}
		if (context.contextSize() != null) {
			values = values.and(KeyValue.of(TurnHighCardinalityKeys.CONTEXT_SIZE.asString(),
					String.valueOf(context.contextSize())));
		}
		if (context.cost() != null) {
			values = values.and(KeyValue.of(TurnHighCardinalityKeys.COST.asString(), context.cost()));
		}
		return values;
	}

	@Override
	public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
		return context instanceof AcpHarnessTurnObservationContext;
	}

	private static String orUnknown(@Nullable String value) {
		return value == null || value.isBlank() ? UNKNOWN : value;
	}

}
