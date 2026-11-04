# Models and Providers

## Selecting a model

```yaml
spring:
  ai:
    acp:
      harness:
        model: gpt-5.6-terra
        on-unsupported: fail       # fail | warn (default) | ignore
```

`spring.ai.acp.harness.model` is a **request**. When a session opens, Spring AI ACP Harness Client reads the options the agent advertises and matches the request against them, in order: exact id, display name, `<provider>/<model>`, then a suffix match that must be unique. Only then does it send `session/set_config_option`. It never sends a value the agent did not advertise, because some agents (goose) accept an unknown model id and fail later, inside the turn.

When nothing matches, `on-unsupported` decides:

| Value | Behaviour |
| --- | --- |
| `fail` | Throws `UnsupportedAcpAgentOptionException` when the session opens. Use in production when a wrong model is worse than a failed request. |
| `warn` | Logs once per option per runtime, naming what the agent *does* offer, and continues on the agent's default. |
| `ignore` | Continues silently. |

```text title="A warn, naming the alternatives"
WARN  Runtime 'goose' cannot honor model='gpt-4o-from-2024' (the agent's 'model' option offers
      gpt-6-astra, gpt-5.6-luna, gpt-5.6-sol, gpt-5.6-terra, … (94 in all));
      continuing with the agent's own default.
```

!!! tip

    Leave `model` unset to use whatever the agent is already configured with, which is the one model its credentials are sure to reach. To see what an agent offers, set a deliberately wrong model with `warn` and read the warning, or inspect `acpHarnessClient.openSession("probe").configOptions()`.

Override per turn with `.options(o -> o.model("gpt-5.6-sol"))`. Options are negotiated once per session, and again only when a turn asks for something different. To see what was applied, read `openSession(name).configuration()` ([Named sessions](../harness-client.md#named-sessions)); each option reports a `Mechanism`: `CONFIG_OPTION`, `ENDPOINT`, `SESSION_MODE`, `SESSION_MODEL`, `PROVIDERS_SET`, `OUT_OF_BAND`, `UNSUPPORTED` or `NOT_REQUESTED`.

## Provider credentials

An agent either already has credentials (its own login or config file), or you give it one through `spring.ai.acp.harness.provider`. The provider block is fixed at process start and never per request.

```yaml
spring:
  ai:
    acp:
      harness:
        model: gpt-5.6-terra
        provider:
          id: openai                  # negotiated: the provider as the agent names it
          api-type: openai            # the API dialect; also names the env vars (OPENAI_API_KEY, …)
          api-key: ${OPENAI_API_KEY}  # never logged
          # base-url: …               # only for an endpoint of your own; see below
          # headers: { X-Team: platform }
```

| Property | Meaning |
| --- | --- |
| `provider.id` | Which provider to use, as the agent names it. Negotiated like `model`; OpenCode takes it as the model prefix. |
| `provider.api-type` | The wire dialect. Its upper-cased form names the environment variables: `openai` → `OPENAI_API_KEY`, `OPENAI_BASE_URL`; `azure_openai` → `AZURE_OPENAI_API_KEY`. |
| `provider.api-key` | The key. Travels in the agent's environment or config file (as an `{env:…}` reference where the format allows), or through ACP `authenticate` for Codex. |
| `provider.base-url` | An endpoint of your own. HTTPS, or HTTP for loopback and `.apps.internal` only. |
| `provider.headers` | Extra headers for the provider endpoint. |

Any variable an adapter derives can be overridden from the runtime's tier-3 `env` block, which is applied last.

## Bring your own endpoint

Point the agent at a platform gateway, a proxy, or a model server you run, with four properties and no runtime-specific block:

```yaml
spring:
  ai:
    acp:
      harness:
        runtime: goose
        model: deepseek-ai/DeepSeek-V4-Flash-0731
        provider:
          id: openai
          api-type: openai
          base-url: https://gateway.example.com/team-x/openai   # "/v1" is added when there is no version segment
          api-key: ${GENAI_API_KEY}
```

With `base-url` set, the **endpoint's own `/models` listing** decides which models exist, not the agent's built-in catalogue, and the model is applied even though the agent never advertised it (`Mechanism.ENDPOINT`). An endpoint that publishes no listing refuses nothing. This applies to `model` only; `mode` and `provider.id` are still checked against the agent.

| Runtime | How the endpoint reaches the agent |
| --- | --- |
| goose | `OPENAI_HOST` (origin and path prefix, version stripped), plus `GOOSE_PROVIDER`/`GOOSE_MODEL` at launch. The wire API is not pinned: goose chooses Responses or Chat Completions per model. |
| codex | A `[model_providers.<id>]` table in `config.toml`, the key via `env_key`. |
| opencode | An `@ai-sdk/openai-compatible` provider in `opencode.json`, keyed `acp` when `id` equals `api-type`, with that vendor's built-in provider disabled so the key cannot leak to it. |
| registry | `OPENAI_BASE_URL` and `OPENAI_API_KEY` (named from `api-type`), nothing more. |

!!! danger "Important"

    The endpoint has to speak the agent's dialect. codex-acp 1.12+ supports only the OpenAI **Responses** API, so a gateway that serves only `/chat/completions` fails inside Codex's first turn. Goose and OpenCode work with either. When one runtime needs a different endpoint, give it its own profile ([Swapping the runtime](../runtimes/index.md#swapping-the-runtime)). To pin goose to one dialect, set `OPENAI_BASE_PATH` in `spring.ai.acp.harness.runtimes.goose.env`.

## Tanzu AI Models on Cloud Foundry

On Cloud Foundry, binding a Tanzu AI Models (GenAI) service is the whole provider configuration. At startup, Spring AI ACP Harness Client finds the binding in `VCAP_SERVICES` and uses its OpenAI-compatible endpoint as a [bring-your-own endpoint](#bring-your-own-endpoint), so every runtime reaches it with no runtime-specific settings.

```bash
cf create-service genai tanzu-all-models my-models
cf bind-service my-app my-models
```

- The binding sets `spring.ai.acp.harness.provider.base-url` to its `api_base` plus the wire format its catalog names (`<api_base>/openai`, as java-cfenv builds it), and `api-key` to its key. It sets `provider.id` and `api-type` to `openai` if they are unset. A service whose catalog names another wire format is not used.
- A binding is recognised by a label starting `genai` or `ai-models`, or a `genai` tag, and needs a `credentials.endpoint` with `api_base` and `api_key`.
- Without a configured `spring.ai.acp.harness.model`, the first model the service's catalog (`config_url`) marks `TOOLS`-capable is chosen. If the catalog cannot be read, no model is chosen, and a warning says to set one.
- **Your own configuration wins, as a whole.** If `spring.ai.acp.harness.provider` sets a `base-url` or `api-key`, the binding is not used, so a key is never paired with another service's endpoint. A blank value (`${OPENAI_API_KEY:}` with the variable unset) counts as unset, so one `application.yaml` can serve both a laptop and the platform.
- A provider `id` or `api-type` other than `openai` also leaves the binding unused.

| Property | Default | Meaning |
| --- | --- | --- |
| `spring.ai.acp.harness.tanzu-ai.enabled` | `true` | Use a bound Tanzu AI Models service as the provider. |
| `spring.ai.acp.harness.tanzu-ai.service-name` | — | Which bound service to use when there are several; the first by default. |

!!! note

    With goose-buildpack still supplying the goose binary, set `GOOSE_TANZU_GENAI_AUTO_CONFIG=false` so the buildpack's own binding logic stays out of the agent's environment.

## Mode

`spring.ai.acp.harness.mode` selects the agent's session mode, and is negotiated exactly like `model`. Its values are the agent's own:

| Runtime | Default | Asks before acting | Option id |
| --- | --- | --- | --- |
| goose | `auto` | `approve`, `smart_approve` | `mode` ([all values](../runtimes/goose.md#modes)) |
| codex | `agent` | `plan` | `collaboration_mode`; sandbox presets are `mode` ([details](../runtimes/codex.md#sandbox-and-approvals)) |
| opencode | `build` | `plan` | `mode`; custom agents add values ([details](../runtimes/opencode.md#modes-and-custom-agents)) |

Mode matters for safety: see [Permissions](permissions.md). Other agent-specific tuning, such as reasoning effort, belongs in tier 3: `spring.ai.acp.harness.runtimes.codex.config-toml.model_reasoning_effort: high`.
