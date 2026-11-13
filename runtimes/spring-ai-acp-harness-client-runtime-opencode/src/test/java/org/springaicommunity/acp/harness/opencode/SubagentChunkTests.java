package org.springaicommunity.acp.harness.opencode;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subagent's chunks are recognised by OpenCode's {@code _meta} marker. The wire shape
 * is pinned by updates captured from opencode 2.2.7, in a turn that started one subagent,
 * so an OpenCode that renames the marker is caught here rather than by an answer that
 * starts with the subagent's words.
 */
class SubagentChunkTests {

	/** Verbatim from opencode 2.2.7: the subagent's reply, on the parent session. */
	private static final String SUBAGENT_REPLY = """
			{"sessionUpdate":"agent_message_chunk","messageId":"msg_1d5a42815001xMnFGzTWCoVUqM",
			 "content":{"type":"text","text":"PONG"},
			 "_meta":{"opencode/child-session":{"id":"ses_e2a5bd7f9ffeR1xgaDpHiaZljp",
			          "parentID":"ses_e2a5be9a0ffeLtwoib72ADLz0K","depth":1,"title":"Reply PONG only"}}}
			""";

	/** The same turn's own answer, which carries no marker; text verbatim, no ids. */
	private static final String AGENT_REPLY = """
			{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"The subagent replied: **PONG**"}}
			""";

	/**
	 * Constructed, not captured: OpenCode marks every update it projects from a child
	 * session the same way, thoughts included.
	 */
	private static final String SUBAGENT_THOUGHT = """
			{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Replying."},
			 "_meta":{"opencode/child-session":{"id":"ses_child","parentID":"ses_parent","depth":1}}}
			""";

	/** Verbatim: the call that started the subagent, which is the agent's own. */
	private static final String SUBAGENT_CALL = """
			{"sessionUpdate":"tool_call","toolCallId":"call_JTHdRyx2gCSE39QlHAtPjNId","title":"subagent",
			 "kind":"think","status":"pending","locations":[],"rawInput":{}}
			""";

	private final OpenCodeRuntime runtime = new OpenCodeRuntime("opencode");

	@Test
	void recognisesTheCapturedSubagentReply() throws Exception {
		assertThat(runtime.writtenBySubagent(read(SUBAGENT_REPLY))).isTrue();
	}

	@Test
	void recognisesASubagentThought() throws Exception {
		assertThat(runtime.writtenBySubagent(read(SUBAGENT_THOUGHT))).isTrue();
	}

	@Test
	void leavesTheAgentsOwnAnswerAlone() throws Exception {
		assertThat(runtime.writtenBySubagent(read(AGENT_REPLY))).isFalse();
	}

	@Test
	void leavesTheCallThatStartedTheSubagentAlone() throws Exception {
		assertThat(runtime.writtenBySubagent(read(SUBAGENT_CALL))).isFalse();
	}

	private static AcpSchema.SessionUpdate read(String json) throws Exception {
		return AcpJsonMapper.createDefault().readValue(json, AcpSchema.SessionUpdate.class);
	}

}
