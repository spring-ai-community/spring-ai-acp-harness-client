# Skills

A skill is a directory holding a `SKILL.md`: a named, described procedure the agent loads when the task matches its description, optionally with scripts and reference files beside it. Skills are how you teach an agent *your* procedures (a release checklist, a review rubric, how to send an email) without putting them in every prompt.

ACP says nothing about skills, but Goose, Codex and OpenCode all read them from `<workspace>/.agents/skills/<name>/`. Spring AI ACP Harness Client installs the skills you configure into that directory **before the agent starts**, so they are portable across runtimes.

## Skills bundled with the application

The default way to ship a skill, because it travels with the build that was tested with it. Put it on the classpath:

```text
src/main/resources/
└── skills/
    └── release-checks/
        ├── SKILL.md
        └── scripts/
            └── check_versions.sh
```

```yaml title="application.yaml"
spring:
  ai:
    acp:
      harness:
        skills:
          - path: skills/release-checks          # classpath directory; installed as "release-checks"
```

This works from an IDE (`target/classes`) and from an executable jar. Scripts that start with a shebang are marked executable when copied out of a jar.

## Skills from a Git repository

```yaml
spring:
  ai:
    acp:
      harness:
        skills:
          - url: https://github.com/acme/agent-skills
            path: plugins/mailgun/skills/mailgun        # directory inside the repository
            ref: 8ae7aaf85bd1283dedb449d7fdb2192e05e27b5a  # full commit pins it; a branch or tag also works
            sha256: 3f9c…                               # optional: expected SHA-256 of SKILL.md

          - url: https://github.com/acme/private-skills
            path: review-rubric
            token: ${GITHUB_TOKEN}                      # for a private repository
```

- Fetched with the system `git`, so `git` must be on the `PATH`. HTTPS only.
- Without `ref`, the default branch is used, and the startup log names the commit to pin it to. A 40-character commit is checked against what Git actually resolved.
- A `token` reaches only that one git process, as an environment-borne header: never in a URL, an argument, a file or a log line.

## Skill properties

| Property | Bundled | Git | Meaning |
| --- | --- | --- | --- |
| `name` | optional | optional | Directory to install as. Defaults to the last segment of `path`, or the repository name. |
| `path` | required | optional | The skill's directory on the classpath, or inside the repository (default: its root). |
| `url` | — | required | The repository, over HTTPS, without credentials. Its presence makes the skill a Git skill. |
| `ref` | — | optional | Commit, branch or tag. |
| `token` | — | optional | Access token for a private repository. |
| `sha256` | optional | optional | Expected SHA-256 of `SKILL.md`. |

## How installation behaves

- **Before first use.** Skills are installed when the first agent connection opens, once per JVM for a given workspace and skill list.
- **Fails closed.** A skill that cannot be fetched, has no `SKILL.md`, contains a symbolic link, or fails its digest or pin check stops the agent from starting, rather than letting it start without a capability you rely on.
- **Reconciled.** Each installed skill carries a `.spring-ai-acp-skill` marker. A skill removed from configuration is removed from the workspace at the next start.
- **Your own skills are safe.** A directory under `.agents/skills/` without the marker is never overwritten or removed; a configured skill with the same name fails instead. You can still author skills by hand directly in `<workspace>/.agents/skills/` without configuring them.

## Writing a skill

```markdown title="skills/release-checks/SKILL.md"
---
name: release-checks
description: Verify a release candidate before it is tagged. Use when asked to check,
  validate or sign off a release, RC or version bump.
---

# Release checks

1. Confirm the version in `pom.xml` has no `-SNAPSHOT` suffix.
2. Confirm `CHANGELOG.md` has an entry for this version.
3. Run `scripts/check_versions.sh` and report any dependency whose version changed.

Report each step as PASS or FAIL with one line of evidence. Do not tag anything.
```

The `description` is what the agent matches a task against, so write it as *when to use this*. Name the skill in the prompt ("…using the release-checks skill") when you need deterministic behaviour.

!!! warning

    A skill that runs scripts needs the agent to have a shell tool, and runs that script with the agent's environment, which it inherits from your JVM. On goose that is the `developer` extension, which goose enables by default; name it in `spring.ai.acp.harness.runtimes.goose.builtins` to keep it on whatever goose's own config says ([Built-in extensions](../runtimes/goose.md#built-in-extensions)). A secret the script reads (say `MAILGUN_API_KEY`) must be in your application's environment. The `spring.ai.acp.harness.terminal` settings do not govern the agent's own shell.

!!! tip

    If the workspace lives in your source tree, add `workspace/.agents/` to `.gitignore`: it is rebuilt from configuration on every start.
