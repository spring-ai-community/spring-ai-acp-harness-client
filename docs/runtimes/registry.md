# Registry agents

With `spring-ai-acp-harness-client-boot-starter-registry`, `spring.acp.runtime` can name any agent in the ACP registry that no compiled adapter claims. The agent is resolved from a cached catalogue snapshot, downloaded, verified against its published SHA-256, unpacked and launched:

```yaml
spring:
  acp:
    runtime: gemini
    registry:
      require-checksum: true        # default; refuse agents published without a sha256
      cache: ${user.home}/.spring-ai-acp/agents
      refresh: 24h
      offline: false                # true: bundled snapshot and already-installed agents only
    runtimes:
      gemini:
        args: [ --debug ]           # appended to the arguments the registry specifies
        env:
          GEMINI_API_KEY: ${GEMINI_API_KEY}
```

A registry agent has only two tier-3 keys, `args` and `env`, because nothing is known about its config files. It reaches the negotiated tier only through ACP's own option categories and the obvious ids `model`, `mode` and `provider`. Provider credentials reach it as `<API_TYPE>_API_KEY` and `<API_TYPE>_BASE_URL`; anything else goes in `env`.

!!! warning

    A registry agent is an executable downloaded from the internet and run with your application's credentials in its environment. Several binary agents publish no checksum; running one requires `spring.acp.registry.require-checksum: false`, which you should set only deliberately. Pin the catalogue with a `file:` URL in `spring.acp.registry.url` for reproducible builds.
