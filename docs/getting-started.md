# Getting Started

## Prerequisites

- **Java 21** and **Spring Boot 4.1**.

- **An agent you can run.** Spring AI ACP Harness Client does not package agent binaries. For the compiled adapters:

    - **Goose**: the `goose` CLI on the `PATH`, or `GOOSE_CLI_PATH` pointing at it. Verified against goose 1.51.
    - **Codex**: Node.js with `npx`. The adapter runs `npx -y @agentclientprotocol/codex-acp@2.5.0`, so the first start downloads it. `CODEX_ACP_CLI_PATH` names a local codex-acp to run instead.
    - **OpenCode**: the `opencode` CLI on the `PATH`, or `OPENCODE_CLI_PATH`. Verified against opencode 1.18.
    - **Registry agents**: nothing pre-installed; the agent is downloaded and checksum-verified. Agents distributed through npm or PyPI need `npx` or `uvx`.

- **Model credentials**, either already configured in the agent itself (for example `goose configure` or `codex login`) or given to Spring AI ACP Harness Client through `spring.acp.provider` (see [Models and Providers](configuration/models.md)).

## Add the dependency

Releases are published to a public Artifact Registry repository; no credentials are needed to read it. Add the repository and **one runtime starter**:

=== "Maven"

    ```xml
    <repositories>
        <repository>
            <id>spring-ai-acp-harness-client</id>
            <url>https://us-central1-maven.pkg.dev/cf-mcp/maven-public</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>org.springaicommunity</groupId>
            <artifactId>spring-ai-acp-harness-client-boot-starter-goose</artifactId>
            <version>0.5.0</version>
        </dependency>
    </dependencies>
    ```

=== "Gradle"

    ```kotlin
    repositories {
        mavenCentral()
        maven { url = uri("https://us-central1-maven.pkg.dev/cf-mcp/maven-public") }
    }

    dependencies {
        implementation("org.springaicommunity:spring-ai-acp-harness-client-boot-starter-goose:0.5.0")
    }
    ```

Replace `-goose` with `-codex`, `-opencode` or `-registry` for another agent. No `@Enable…` annotation or bean definition is needed.

## Configure

```yaml title="src/main/resources/application.yaml"
spring:
  acp:
    runtime: goose                  # optional while goose is the only adapter on the classpath
    workspace: ${AGENT_WORKSPACE:./workspace}   # the agent's working directory; must exist
    timeout: 5m                     # longest a single turn may take
```

The **workspace** is the agent's working directory (its ACP session `cwd`). It is also where the agent looks for `AGENTS.md` and where skills are installed, so most of this guide comes back to it. A relative path is resolved against the JVM's working directory; left unset, the JVM's working directory itself is used. It must be an existing directory.

## Send a prompt

```java
@SpringBootApplication
public class ReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewApplication.class, args);
    }

    @Bean
    ApplicationRunner demo(AcpAgentClient agent) {
        return args -> System.out.println(
                agent.prompt("Summarize what is in this directory").call().content());
    }
}
```

`AcpAgentClient` is a thread-safe singleton. The agent process is started on the **first prompt**, not at context refresh: an application whose agent is misconfigured still starts, and reports the problem the first time it is used. The process is stopped when the context closes.

!!! tip

    The repository's `samples/smoke-app` has all three adapters and the registry on its classpath. After `mvn install` in the Spring AI ACP Harness Client checkout, run it against each agent in turn:

    ```bash
    mvn -pl samples/smoke-app spring-boot:run
    mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=codex
    mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=opencode
    mvn -pl samples/smoke-app spring-boot:run -Dspring-boot.run.arguments=--spring.acp.runtime=gemini
    ```
