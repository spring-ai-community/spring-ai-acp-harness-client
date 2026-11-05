package org.springaicommunity.acp.harness.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.acp.harness.client.AcpHarnessClient;
import org.springaicommunity.acp.harness.client.AcpHarnessClientFactory;
import org.springaicommunity.acp.harness.config.AcpAgentSettings;
import org.springaicommunity.acp.harness.runtime.AcpAgentRuntime;
import org.springaicommunity.acp.harness.session.AcpAgentSession;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Asks a runtime, once, whether it can actually be used here — and what it has.
 *
 * <p>
 * Gating a live suite on {@code agent --version} is not enough. All three agents are
 * installed on a developer machine long before they are usable from one: an agent with no
 * credentials answers the handshake and then fails inside a turn, which reads as a bug in
 * this library rather than a machine that needs a {@code login}. So the gate is a real
 * session, and a suite skips rather than fails.
 *
 * <p>
 * It also answers the question that makes a portable conformance suite possible at all:
 * which model to ask for. Hardcoding one per agent would put three model catalogs into
 * the test source and break whenever a vendor retires a name. Instead this reads the
 * models the agent advertises and picks one that is <em>not</em> the one it already has,
 * so setting it proves something.
 *
 * <p>
 * Probes are cached per runtime id for the life of the JVM: three handshakes per build,
 * not one per test. On a build that has not opted in to {@link LiveAcpAgents}, there are
 * none at all.
 */
public final class AcpAgentProbe {

	private static final Logger logger = LoggerFactory.getLogger(AcpAgentProbe.class);

	private static final Map<String, AcpAgentProbe> CACHE = new ConcurrentHashMap<>();

	private static final Duration HANDSHAKE_TIMEOUT = Duration.ofMinutes(2);

	private final boolean usable;

	private final @Nullable String failure;

	private final List<String> models;

	private final @Nullable String currentModel;

	private final List<String> modes;

	private AcpAgentProbe(boolean usable, @Nullable String failure, List<String> models, @Nullable String currentModel,
			List<String> modes) {
		this.usable = usable;
		this.failure = failure;
		this.models = List.copyOf(models);
		this.currentModel = currentModel;
		this.modes = List.copyOf(modes);
	}

	/** Probes {@code runtime}, or returns the answer from the first time it was asked. */
	public static AcpAgentProbe of(AcpAgentRuntime runtime) {
		return CACHE.computeIfAbsent(runtime.id(), id -> probe(runtime));
	}

	/**
	 * Whether a live suite for {@code runtime} should run on this build.
	 *
	 * <p>
	 * Two questions, in the cheap order. A build that has not opted in with
	 * {@link LiveAcpAgents#PROPERTY} is not probed at all — no agent is started and no
	 * turn is spent — because the answer cannot change what it does. Only then is the
	 * machine asked whether it has a usable agent, which costs a handshake.
	 */
	public static boolean isUsable(AcpAgentRuntime runtime) {
		return LiveAcpAgents.enabled() && of(runtime).usable();
	}

	private static AcpAgentProbe probe(AcpAgentRuntime runtime) {
		Path workspace = null;
		try {
			workspace = Files.createTempDirectory("acp-probe-" + runtime.id());
			AcpAgentSettings settings = AcpAgentSettings.builder(runtime.id(), workspace)
				.timeout(HANDSHAKE_TIMEOUT)
				.build();
			try (AcpHarnessClient client = AcpHarnessClientFactory.create(runtime, settings)) {
				AcpAgentSession session = client.openSession("probe");
				AcpSchema.SessionConfigSelect model = session.advertised().select("model", "model");
				List<String> models = model == null ? List.of() : values(model);
				if (models.isEmpty()) {
					models = legacyModelIds(session);
				}
				AcpSchema.SessionConfigSelect mode = session.advertised().select("mode", "mode");
				List<String> modes = mode == null ? legacyModeIds(session) : values(mode);

				return new AcpAgentProbe(true, null, models, model == null ? null : model.currentValue(), modes);
			}
		}
		catch (RuntimeException | IOException ex) {
			logger.info("Runtime '{}' is not usable here, so its live suite will be skipped: {}", runtime.id(),
					ex.getMessage());
			return new AcpAgentProbe(false, ex.getMessage(), List.of(), null, List.of());
		}
		finally {
			deleteQuietly(workspace);
		}
	}

	/**
	 * The models of the legacy {@code models} state, or none when the agent sent none.
	 */
	private static List<String> legacyModelIds(AcpAgentSession session) {
		AcpSchema.SessionModelState state = session.advertised().findModels();
		return state == null ? List.of() : state.availableModels().stream().map(AcpSchema.ModelInfo::modelId).toList();
	}

	/** The modes of the legacy {@code modes} state, or none when the agent sent none. */
	private static List<String> legacyModeIds(AcpAgentSession session) {
		AcpSchema.SessionModeState state = session.advertised().findModes();
		return state == null ? List.of() : state.availableModes().stream().map(AcpSchema.SessionMode::id).toList();
	}

	private static List<String> values(AcpSchema.SessionConfigSelect select) {
		return select.options() == null ? List.of()
				: select.options().stream().map(AcpSchema.SessionConfigSelectOption::value).toList();
	}

	private static void deleteQuietly(@Nullable Path directory) {
		if (directory == null) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
					// A temp directory left behind is not worth failing a probe over.
				}
			});
		}
		catch (IOException ignored) {
			// Likewise.
		}
	}

	public boolean usable() {
		return usable;
	}

	/** Every model the agent advertised, however it advertised them. */
	public List<String> models() {
		return models;
	}

	public List<String> modes() {
		return modes;
	}

	/**
	 * The model the agent is already configured with — the one its credentials
	 * demonstrably reach, and so the only safe choice for a test that runs a turn. Null
	 * when the agent advertised none.
	 */
	public @Nullable String currentModel() {
		if (currentModel != null && !currentModel.isBlank()) {
			return currentModel;
		}
		return models.isEmpty() ? null : models.get(0);
	}

	/**
	 * Some other model the agent advertises, for asserting that a change can be applied,
	 * or null when there is none. Whether this machine could actually prompt with it is
	 * unknown and beside the point.
	 */
	public @Nullable String alternativeModel() {
		return models.stream().filter(m -> !m.equals(currentModel)).findFirst().orElse(null);
	}

}
