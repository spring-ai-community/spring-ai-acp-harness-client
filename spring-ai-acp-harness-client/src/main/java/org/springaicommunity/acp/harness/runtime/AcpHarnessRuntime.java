package org.springaicommunity.acp.harness.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.acp.harness.config.AcpHarnessSettings;
import org.springaicommunity.acp.harness.mcp.McpRequestFilter;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The seam between this library and one particular agent.
 *
 * <p>
 * Everything ACP standardizes is handled in the core and never reaches an implementation
 * of this interface. What remains is the provisioning ACP does not cover: how the agent
 * is started, what files it reads before it starts, the names it happens to use for the
 * options the protocol lets a client set, and the handful of places its wire output
 * carries vendor detail.
 *
 * <p>
 * Note what is <em>not</em> here. There is no hook for "apply the model", because model
 * selection is a protocol operation and the core performs it identically for every agent;
 * an adapter only says what the option is <em>called</em>. Every method that could tempt
 * an adapter into doing the core's job has been left out on purpose — an adapter that
 * grows one is a sign the core is missing something.
 *
 * <p>
 * An implementation that passes {@code AcpHarnessRuntimeContract} is swappable. That
 * suite, not this interface, is the real definition of the abstraction.
 */
public interface AcpHarnessRuntime {

	/** The value {@code spring.ai.acp.harness.runtime} selects this runtime by. */
	String id();

	/** Builds the command line, arguments and environment to start the agent with. */
	AcpHarnessLaunchSpec launch(AcpHarnessSettings settings);

	/**
	 * Writes whatever the agent reads from disk at startup — its own config file, an
	 * instructions file — before {@link #launch} is honored. Runs once per client, not
	 * once per session.
	 *
	 * <p>
	 * Anything written belongs under {@link AcpHarnessSettings#runtimeHome()}, never in
	 * the workspace.
	 */
	default void provision(AcpHarnessSettings settings) {
	}

	/**
	 * The {@code authMethods} id to send {@code authenticate} with before the first
	 * session, or null to send nothing.
	 *
	 * <p>
	 * ACP lets an agent refuse {@code session/new} until the client has picked one of the
	 * auth methods it offered on {@code initialize}, and a credential on the process
	 * environment does not count as picking one: codex-acp 1.12 and 1.13 answer
	 * "Authentication required" with {@code OPENAI_API_KEY} set, and accept the same key
	 * once {@code authenticate} names {@code api-key}. Which method a configuration means
	 * is vendor knowledge, so the adapter names it and the core sends it — the same
	 * division as {@link #configIdsFor}. Return one only when the settings carry what
	 * that method needs; an agent already signed in some other way needs nothing.
	 */
	default @Nullable String authMethod(AcpHarnessSettings settings) {
		return null;
	}

	/**
	 * Names the tool behind a permission request, when the agent makes that knowable.
	 *
	 * <p>
	 * ACP has no required field for this: {@code toolCall.title} is prose meant for
	 * humans, and agents that expose a stable identifier do so in their own
	 * {@code _meta}. A runtime that cannot answer returns null, and an allowlist policy
	 * then rejects the request rather than guessing.
	 */
	default @Nullable String toolNameOf(AcpSchema.@Nullable ToolCallUpdate toolCall) {
		return null;
	}

	/**
	 * The plan a tool call carries, when the agent keeps its plan in a tool of its own
	 * rather than sending ACP {@code plan} updates.
	 *
	 * <p>
	 * ACP has a portable plan, and a caller renders it as a task list. An agent whose
	 * task list is a tool instead — goose's {@code todo} extension — reports each change
	 * to it as an ordinary tool call, so without this the list is never seen as one.
	 * Answering turns that call into
	 * {@link org.springaicommunity.acp.harness.event.AcpHarnessEvent.PlanUpdated
	 * PlanUpdated}, and the call and its updates are not reported as tool activity.
	 *
	 * <p>
	 * Answer only for a call recognised by a stable identifier, never by its title, and
	 * with the <em>whole</em> plan: each answer replaces the last, as an ACP plan update
	 * does. Return null for a call whose content cannot be read as a plan, and it is
	 * reported as the tool call it is.
	 */
	default @Nullable List<AcpSchema.PlanEntry> planOf(AcpSchema.ToolCall toolCall) {
		return null;
	}

	/**
	 * Whether a message or thought chunk on a session was written by a subagent the agent
	 * started, rather than by the agent itself.
	 *
	 * <p>
	 * ACP has no subagents. An agent that runs one inside a turn may stream the
	 * subagent's own output on the parent session, as ordinary chunks marked only in its
	 * own {@code _meta}, so without this the subagent's words are appended to the answer.
	 * Answering true drops the chunk: the subagent's result reaches the answer through
	 * the agent itself, which reads it and replies. The subagent's tool calls are still
	 * reported, since they are work done in the workspace.
	 *
	 * <p>
	 * Consulted for {@code agent_message_chunk} and {@code agent_thought_chunk} only.
	 * Answer by a stable identifier, never by the text.
	 */
	default boolean writtenBySubagent(AcpSchema.SessionUpdate chunk) {
		return false;
	}

	/**
	 * Where this agent writes its own log, if it writes one this client can find.
	 *
	 * <p>
	 * Exists because of a failure the protocol does not report at all. An agent that
	 * cannot connect to an MCP server answers {@code session/new} normally, sends no
	 * {@code session/update} and — measured against goose 1.51.0 on both of its
	 * transports — writes nothing to stdout or stderr either. It does write
	 * {@code Failed to load extension <name>} to a log file. Watching that file is the
	 * only way a client learns what the agent already knows.
	 *
	 * <p>
	 * A declaration, not an action: the core does the watching, exactly as it does the
	 * option setting that {@link #configIdsFor} only names. Return null unless
	 * <em>this</em> client can know the path — an adapter that attaches to an agent
	 * somebody else started does not know what environment that process has, and guessing
	 * would watch the wrong file.
	 *
	 * @see #noticeOf(String)
	 */
	default @Nullable Path logDirectory(AcpHarnessSettings settings) {
		return null;
	}

	/**
	 * What one line of that log means to a client, if it means anything.
	 *
	 * <p>
	 * The file-based twin of {@link #toolNameOf}: a pure function over the agent's own
	 * output, reading vendor detail the protocol never standardized. Most lines mean
	 * nothing to a client and return null; a client is not a log aggregator, and an
	 * adapter that reported everything would make the warnings that matter unfindable.
	 *
	 * <p>
	 * The line arrives already redacted, and what is returned is logged, so an
	 * implementation must not put anything back that a secret could hide in.
	 */
	default @Nullable AcpHarnessNotice noticeOf(String logLine) {
		return null;
	}

	/**
	 * Workarounds this agent needs on its MCP traffic, applied by the loopback proxy.
	 *
	 * <p>
	 * For the interoperability bugs between one agent and some MCP servers that make the
	 * agent drop a server without a word. The same division of labour as
	 * {@link #noticeOf}: the adapter knows its agent's quirks, the core does the
	 * plumbing. While this returns anything, every HTTP MCP server is routed through the
	 * proxy — credentialed or not, since a filter cannot touch traffic the agent sends
	 * straight to the server — so an adapter should return filters only when the
	 * application has asked for them.
	 */
	default List<McpRequestFilter> mcpRequestFilters(AcpHarnessSettings settings) {
		return List.of();
	}

	/**
	 * How far the MCP servers declared on one session reach inside the agent process.
	 *
	 * <p>
	 * The client hands every session its own loopback route, carrying that session's
	 * principal's credentials. That is only sound if the agent keeps each session's
	 * servers to that session. An agent that keeps one client per server name for the
	 * whole process instead has every session call through whichever route was declared
	 * last: another user's, or a closed session's. For such a runtime the client keeps a
	 * process to one principal at a time and gives that principal one route, shared by
	 * all of their sessions and held until the last of them closes.
	 */
	default McpScope mcpScope() {
		return McpScope.SESSION;
	}

	/**
	 * The {@code session/set_config_option} ids this runtime uses for a portable option,
	 * most specific first, or empty if it has none.
	 *
	 * <p>
	 * Order is meaningful. Codex exposes two options in the mode family — {@code mode}
	 * for its approval policy and {@code collaboration_mode} for plan-versus-build — and
	 * the resolver walks the list looking for the one whose advertised values contain
	 * what was asked for.
	 */
	default List<String> configIdsFor(PortableOption option) {
		return List.of(option.defaultConfigId());
	}

	/**
	 * The {@code category} values to fall back on when no id matches, most specific
	 * first.
	 *
	 * <p>
	 * Categories are the portable half of the option vocabulary and ids are the vendor
	 * half, so an agent nobody wrote an adapter for is still reachable through this. It
	 * is a fallback rather than the primary key because agents do not always set it:
	 * goose returns its {@code provider} option with no category at all.
	 */
	default List<String> configCategoriesFor(PortableOption option) {
		String category = option.defaultCategory();
		return category == null ? List.of() : List.of(category);
	}

	/**
	 * Whether this runtime already carried {@code option} to the agent outside the
	 * protocol — an environment variable set in {@link #launch}, a line written by
	 * {@link #provision}.
	 *
	 * <p>
	 * Consulted only after every protocol path has failed, and only to decide whether the
	 * request counts as unsupported. It is a declaration, not an action: the work
	 * happened at launch, because that is the last moment an environment variable can
	 * still be set.
	 */
	default boolean appliedOutOfBand(PortableOption option, AcpHarnessSettings settings) {
		return false;
	}

	/**
	 * Where an agent keeps the MCP servers a session declares. See {@link #mcpScope()}.
	 */
	enum McpScope {

		/** Each session's servers are its own. */
		SESSION,

		/** One client per server name, shared by every session in the process. */
		PROCESS

	}

	/**
	 * The options that {@code spring.ai.acp.harness.*} can express and ACP may or may not
	 * be able to honor.
	 */
	enum PortableOption {

		MODEL("model", "model"), PROVIDER("provider", null), MODE("mode", "mode");

		private final String defaultConfigId;

		private final @Nullable String defaultCategory;

		PortableOption(String defaultConfigId, @Nullable String defaultCategory) {
			this.defaultConfigId = defaultConfigId;
			this.defaultCategory = defaultCategory;
		}

		public String defaultConfigId() {
			return defaultConfigId;
		}

		/**
		 * The ACP {@code category} this option corresponds to, where the protocol names
		 * one. There is no standard category for a provider, which is why that one is
		 * null.
		 */
		public @Nullable String defaultCategory() {
			return defaultCategory;
		}

		/**
		 * The lower-case name used in property names, log lines and exception messages.
		 */
		public String propertyName() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

}
