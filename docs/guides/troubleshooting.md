# Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Startup fails: *spring.ai.acp.harness.runtime is not set and more than one runtime adapter is on the classpath* | Set `spring.ai.acp.harness.runtime` to one of the listed ids. |
| Startup fails: *No AcpHarnessRuntime registered for spring.ai.acp.harness.runtime='…'* | Add that runtime's starter, or `spring-ai-acp-harness-client-boot-starter-registry` for a registry id. |
| Startup fails: *spring.ai.acp.harness.runtimes has options for unregistered runtime(s)* | A tier-3 block names a runtime nothing supplies; usually a typo. |
| *workspace '…' is not an existing directory* | Create it first; Spring AI ACP Harness Client will not. A relative path is resolved against the JVM's working directory. |
| The first prompt fails with *Could not install skill …* | A skill failed to fetch or check. Pin a `ref` that exists, check `path`, make sure `git` is installed, and remove any symlinks from the skill. |
| The agent ignores `AGENTS.md` | It is not at the root of the workspace actually used; check the `workspace` in the startup `INFO` line. |
| A skill is never used | Check `<workspace>/.agents/skills/<name>/SKILL.md` exists, the description says *when* to use it, and the agent has the tools it needs (goose: `builtins: developer`). |
| The model says it has no tools from an MCP server | The server failed to load, which ACP never reports. On goose, check the `notices()` or WARN log; try `mcp.answer-discover` for gateways; confirm HTTPS, headers and OAuth sign-in. |
| Tool calls are refused | Default `policy: deny`. Use `allowlist` with names read off a DEBUG log, or `auto-approve`. |
| A warning that a model or mode cannot be honoured | The agent does not advertise the value; the warning lists what it does offer. With your own endpoint, check its `/models`. |
| Codex: *Authentication required* | No usable login: set `spring.ai.acp.harness.provider.api-key` (and `api-type: openai`). Against your own endpoint, check it serves the Responses API. |
| Goose cannot find the key `goose configure` saved | The adapter sets `GOOSE_DISABLE_KEYRING=1`, so a key kept in the OS keychain is not read. Set `spring.ai.acp.harness.provider.api-key`, or export the provider's key variable. |
| Turns end in `Failed` with a timeout | Raise `spring.ai.acp.harness.timeout`, or per turn with `.options(o -> o.timeout(…))`. |
