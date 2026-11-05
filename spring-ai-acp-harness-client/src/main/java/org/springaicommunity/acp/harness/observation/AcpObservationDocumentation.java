package org.springaicommunity.acp.harness.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * The observations this library makes, and the tags each one carries.
 *
 * <p>
 * Two, and only two: a turn, and a tool call inside one. Both are things that take time
 * and can fail, which is what an observation is for; a session is neither, and a prompt
 * is an argument.
 *
 * <p>
 * Names follow Spring AI's rule: an OpenTelemetry GenAI key where one means the same
 * thing ({@code gen_ai.response.model}), and otherwise a key scoped under the
 * observation's own name, {@code spring.ai.acp.harness}. Each observation also carries
 * {@code spring.ai.kind}, the tag Spring AI puts on its own observations, so a breakdown
 * by kind includes the harness beside chat clients, advisors and tool calls.
 *
 * <p>
 * The low-cardinality split is the part worth checking against before adding a tag.
 * {@code spring.ai.acp.harness.runtime} has three values, {@code gen_ai.response.model}
 * has as many as an operator configured — one, usually — and
 * {@code spring.ai.acp.harness.outcome} has the stop reasons plus a handful of exception
 * names. A session name or a tool call id would have as many values as there are
 * conversations, so both are high-cardinality, where they become span attributes and not
 * meter dimensions.
 */
public enum AcpObservationDocumentation implements ObservationDocumentation {

	/** One prompt turn, from subscription to its single terminal event. */
	TURN {
		@Override
		public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
			return DefaultAcpHarnessTurnObservationConvention.class;
		}

		@Override
		public String getPrefix() {
			return TURN_NAME;
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return TurnLowCardinalityKeys.values();
		}

		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return TurnHighCardinalityKeys.values();
		}
	},

	/** One tool call the agent announced, from its start to a terminal status. */
	TOOL_CALL {
		@Override
		public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
			return DefaultToolCallObservationConvention.class;
		}

		@Override
		public String getPrefix() {
			return TOOL_CALL_NAME;
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return ToolCallLowCardinalityKeys.values();
		}

		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return ToolCallHighCardinalityKeys.values();
		}
	};

	/** The prefix every key of this library's own carries. */
	public static final String PREFIX = "spring.ai.acp.harness";

	/** The name of the turn observation. */
	public static final String TURN_NAME = PREFIX + ".turn";

	/** The name of the tool call observation. */
	public static final String TOOL_CALL_NAME = PREFIX + ".tool.call";

	/** Spring AI's own tag naming the kind of observation; shared with its components. */
	public static final String SPRING_AI_KIND = "spring.ai.kind";

	/** The {@code spring.ai.kind} of a turn. */
	public static final String TURN_KIND = "acp_harness_turn";

	/** The {@code spring.ai.kind} of a tool call. */
	public static final String TOOL_CALL_KIND = "acp_harness_tool_call";

	public enum TurnLowCardinalityKeys implements KeyName {

		/** {@code acp_harness_turn}. */
		KIND {
			@Override
			public String asString() {
				return SPRING_AI_KIND;
			}
		},

		/** The value of {@code spring.ai.acp.harness.runtime}. */
		RUNTIME {
			@Override
			public String asString() {
				return PREFIX + ".runtime";
			}
		},

		/**
		 * The model the session is really using — what the negotiated tier applied, not
		 * what was requested. {@code unknown} when the agent kept its own default and
		 * never said which. The OpenTelemetry key, as Spring AI's chat models tag it, so
		 * one breakdown by model covers both.
		 */
		MODEL {
			@Override
			public String asString() {
				return "gen_ai.response.model";
			}
		},

		/**
		 * {@code named} or {@code ephemeral}: whether the conversation outlives this
		 * turn.
		 */
		SESSION_KIND {
			@Override
			public String asString() {
				return PREFIX + ".session.kind";
			}
		},

		/**
		 * The agent's stop reason, or the exception's simple name when the turn did not
		 * reach one. Not {@code gen_ai.response.finish_reasons}: Spring AI records that
		 * as a high-cardinality list on the span, and this is one value on the meter.
		 */
		OUTCOME {
			@Override
			public String asString() {
				return PREFIX + ".outcome";
			}
		}

	}

	public enum TurnHighCardinalityKeys implements KeyName {

		/** The caller's name for the conversation. One value per conversation. */
		SESSION_NAME {
			@Override
			public String asString() {
				return PREFIX + ".session.name";
			}
		},

		/** Context window consumed, as of the last {@code usage_update} in this turn. */
		CONTEXT_USED {
			@Override
			public String asString() {
				return PREFIX + ".context.used";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		},

		/** The context window's size, from the same update. */
		CONTEXT_SIZE {
			@Override
			public String asString() {
				return PREFIX + ".context.size";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		},

		/** What the agent says the session has cost so far, when it says. */
		COST {
			@Override
			public String asString() {
				return PREFIX + ".cost";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		}

	}

	public enum ToolCallLowCardinalityKeys implements KeyName {

		/** {@code acp_harness_tool_call}. */
		KIND {
			@Override
			public String asString() {
				return SPRING_AI_KIND;
			}
		},

		RUNTIME {
			@Override
			public String asString() {
				return PREFIX + ".runtime";
			}
		},

		/** The ACP {@code ToolKind}: read, edit, execute, think, fetch, other. */
		TOOL_KIND {
			@Override
			public String asString() {
				return PREFIX + ".tool.kind";
			}
		},

		/**
		 * The terminal {@code ToolCallStatus}, or {@code unfinished} for a call the turn
		 * outlived.
		 */
		STATUS {
			@Override
			public String asString() {
				return PREFIX + ".tool.status";
			}
		}

	}

	public enum ToolCallHighCardinalityKeys implements KeyName {

		/**
		 * The agent's own title for the call — prose written for a human, which is all
		 * ACP guarantees. High-cardinality for exactly that reason.
		 */
		TITLE {
			@Override
			public String asString() {
				return PREFIX + ".tool.title";
			}
		},

		TOOL_CALL_ID {
			@Override
			public String asString() {
				return PREFIX + ".tool.id";
			}
		}

	}

}
