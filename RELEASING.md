# Releasing Spring AI ACP Harness Client

Releases go to Maven Central from GitHub Actions. The steps below are written out for 0.6.0; for a
later minor release, replace `0.6` with its own version.

## The one rule

**A release version never reaches `main`.** `Publish Snapshot` runs `./mvnw -Prelease deploy` on
every push to `main`, and the release profile sets `autoPublish=true`. It publishes whatever
version the POM holds, so a release version on `main` goes to Central at once, and Central
releases can't be deleted. This is by design, and the process keeps it safe: the release commit
lives only on the release branch and its tag, and neither triggers a workflow.

So:

- never merge a release branch into `main`, and never open a PR from one;
- move a fix between `main` and a release branch by cherry-picking the fix commit, never the
  `Release version …` or `Next development version` commits. The release commit's docs and
  CHANGELOG changes reach `main` as a path-limited diff that leaves out every POM (step 6).

| Workflow | Runs on | Does |
|---|---|---|
| `Publish Snapshot` | push to `main`, or by hand | `-Prelease deploy` of the POM's version |
| `Release to Maven Central` | by hand only | `clean verify`, then `-Prelease deploy` of the chosen ref's version |
| `Deploy Documentation` | docs, `mkdocs.yml` or `CHANGELOG.md` pushed to `main`; a published GitHub Release; by hand | mike: see [The docs site](#the-docs-site) |

## Before you start

- Java 21 (Temurin, as CI uses) and an up-to-date `main` whose CI is green.
- Commit signing configured (`commit.gpgsign`). `-s` in the commands below adds a
  `Signed-off-by` line; it does not sign the commit.
- Optionally, the live suites for every runtime you can test (spends real tokens):

  ```bash
  ./mvnw clean install
  ./mvnw test -Dspring-ai-acp-harness-client.test.live=true
  ```

  They skip rather than fail when an agent is missing, so check the surefire summary for the
  runtimes you expected. Test Codex with an empty `CODEX_HOME` (see AGENTS.md).

## Minor release (0.6.0)

### 1. Create the release branch

From `main`, at the commit to release:

```bash
git switch main && git pull --ff-only
git switch -c 0.6.x
git push -u origin 0.6.x
```

### 2. Make the release commit and tag it

On `0.6.x`:

```bash
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=0.6.0
```

Then update the docs to point at 0.6.0 (see [What the docs change](#what-the-docs-change)), and:

```bash
git commit -a -s -m "Release version 0.6.0"
git tag -a v0.6.0 -m "Release version 0.6.0"
git push origin v0.6.0
```

`git tag -a` makes an unsigned annotated tag; `git tag -s` would sign it.

### 3. Start the branch's next patch version

Still on `0.6.x`:

```bash
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=0.6.1-SNAPSHOT
git commit -a -s -m "Next development version"
git push origin 0.6.x
```

Nothing publishes `0.6.1-SNAPSHOT` by itself, since `Publish Snapshot` only fires on `main`. If
someone needs it, run `Publish Snapshot` by hand on `0.6.x`.

### 4. Release to Maven Central

In GitHub, **Actions → Release to Maven Central → Run workflow**, and under **Use workflow from**
choose the **tag** `v0.6.0`, not the `0.6.x` branch. By now the branch is at `0.6.1-SNAPSHOT`:
run on it, the workflow would publish a snapshot and still report success.

Then check that the release is there. It can take a while to appear after the workflow finishes:

- https://central.sonatype.com/artifact/org.springaicommunity/spring-ai-acp-harness-client/0.6.0
- the README snippet resolves from a throwaway project with an empty local repository
  (`-Dmaven.repo.local=$(mktemp -d)`), which proves the coordinates in the docs.

### 5. Publish the GitHub Release

Create a GitHub Release from the tag `v0.6.0` and paste in the version's `CHANGELOG.md` section.
Publishing it runs `Deploy Documentation`, which publishes the tag's docs as `0.6.0` with the
alias `latest`. From then on the site opens on `latest`.

### 6. Bring `main` up to date and move it to the next minor version

`main` still says 0.6.0 is unreleased, offers `0.6.0-SNAPSHOT`, and has `## [0.6.0] - Unreleased`
at the top of its CHANGELOG. One pull request fixes all three. Branch from `main`, never from
`0.6.x`:

```bash
git switch main && git pull --ff-only
git switch -c after-release-0.6.0
# The release commit's docs and CHANGELOG changes, and nothing else: no POM.
git diff v0.6.0^ v0.6.0 -- README.md CHANGELOG.md docs .github/ISSUE_TEMPLATE | git apply --3way
```

At the top of `CHANGELOG.md`, above the now dated 0.6.0 section, add:

```markdown
## [0.7.0] - Unreleased
```

Then move the version, check the docs as in [What the docs change](#what-the-docs-change), and
open the PR:

```bash
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=0.7.0-SNAPSHOT
grep -rnE '0\.6\.0-SNAPSHOT|0\.5\.0|not released yet|maven-snapshots|snapshot version' \
  README.md docs .github/ISSUE_TEMPLATE        # should print nothing
git commit -a -s -m "Next development version"
git push -u origin after-release-0.6.0
```

`git apply --3way` stops on a conflict if a file changed on `main` after `0.6.x` was cut;
resolve it like any merge. Merge the PR once CI passes. Until then, pushes to `main` keep
publishing `0.6.0-SNAPSHOT`, which does no harm. The merge redeploys `main`'s docs as
`0.7.0-SNAPSHOT`, which replaces `0.6.0-SNAPSHOT` in the site's version selector; the site still
opens on 0.6.0.

## Patch release (0.6.1)

A fix lands on `main` first, with its CHANGELOG entry under `## [0.7.0] - Unreleased`. When you
cherry-pick it onto `0.6.x`, put that entry under `## [0.6.1] - Unreleased` instead, adding the
heading above `## [0.6.0] - …` with the first fix; the cherry-pick will usually stop on
`CHANGELOG.md` for exactly this.

On the existing `0.6.x` branch, with the fixes already cherry-picked onto it:

```bash
git switch 0.6.x && git pull --ff-only
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=0.6.1
# update the docs to point at 0.6.1
git commit -a -s -m "Release version 0.6.1"
git tag -a v0.6.1 -m "Release version 0.6.1"
git push origin v0.6.1
./mvnw versions:set -DgenerateBackupPoms=false -DnewVersion=0.6.2-SNAPSHOT
git commit -a -s -m "Next development version"
git push origin 0.6.x
```

Then run `Release to Maven Central` on the tag `v0.6.1`, check
https://central.sonatype.com/artifact/org.springaicommunity/spring-ai-acp-harness-client/0.6.1,
and publish a GitHub Release from `v0.6.1`.

`main` keeps its version, but its docs and CHANGELOG catch up through a pull request branched
from `main`:

```bash
git switch main && git pull --ff-only
git switch -c after-release-0.6.1
git diff v0.6.1^ v0.6.1 -- README.md docs .github/ISSUE_TEMPLATE | git apply --3way
```

Copy the dated `## [0.6.1] - …` section from `git show v0.6.1:CHANGELOG.md` into `main`'s
`CHANGELOG.md`, between `## [0.7.0] - Unreleased` and `## [0.6.0] - …`, and take the same
entries out of the 0.7.0 section. Commit, push and open the PR.

## What the docs change

In the release commit, so that the docs published from the tag give coordinates that resolve:

| File | What |
|---|---|
| `README.md` | drop "0.6.0 is not released yet…" and the snapshot `<repositories>` block; `<version>0.6.0</version>` |
| `docs/getting-started.md` | the same paragraph and both repository blocks (Maven and Gradle); the Maven `<version>` and the Gradle coordinate; the warning that 0.5.0 is the latest release |
| `docs/guides/acp-meridian.md` | the `spring-ai-acp-harness-client.version` property; the "A snapshot version also needs the repository" sentence |
| `CHANGELOG.md` | `## [0.6.0] - Unreleased` becomes `## [0.6.0] - YYYY-MM-DD`; "releases will go to Maven Central" can now say they do |
| `.github/ISSUE_TEMPLATE/bug_report.yml` | the example version |

To check that nothing was missed, this should print nothing:

```bash
grep -rnE '0\.6\.0-SNAPSHOT|0\.5\.0|not released yet|maven-snapshots|snapshot version' \
  README.md docs .github/ISSUE_TEMPLATE
```

For a patch release, the same places move from the previous release to the new one. Step 6
brings the same changes to `main`.

## The docs site

`Deploy Documentation` publishes with mike to `gh-pages`, whose `versions.json` lists every
published version and its aliases:

- A push to `main` publishes `main`'s version, a `-SNAPSHOT`, with the alias `latest-snapshot`,
  and deletes any other `-SNAPSHOT` version, so the version selector shows only the snapshot
  that is still published.
- A published GitHub Release publishes the tag's version. It takes the alias `latest` only if it
  is the newest release in `versions.json`, so a patch to an older line (0.6.2 after 0.7.0) is
  listed without taking `latest`.
- After either, the site opens on `latest` if `versions.json` has it, and on `latest-snapshot`
  only until the first release.

## Earlier releases

0.1.0 to 0.5.0 were deployed by hand to the Google Artifact Registry repository
`https://us-central1-maven.pkg.dev/cf-mcp/maven-public`, which still serves them. None of them is
on Maven Central. 0.5.0's published POMs point at the archived `cpage-pivotal/acp-spring`, so keep
both.

| Version | Coordinates | Notes |
|---|---|---|
| 0.1.0 | `org.springaicommunity.acp:acp-spring-*` | first release, MIT |
| 0.2.0 | `org.springaicommunity.acp:acp-spring-*` | adds `acp-spring-console`, MIT |
| 0.3.0 | `org.springaicommunity:spring-ai-acp-*` | renamed to Spring AI ACP, Apache-2.0 |
| 0.3.1 | `org.springaicommunity:spring-ai-acp-*` | `auth: provided`, Tanzu AI Models binding, goose plans, OpenCode MCP-scope fix |
| 0.4.0 | `org.springaicommunity:spring-ai-acp-*` | ACP Java SDK 0.18.0, Jackson 3 throughout, Spring Boot 4.1.1, goose-serve WebSocket fixes |
| 0.5.0 | `org.springaicommunity:spring-ai-acp-harness-client-*` | renamed to Spring AI ACP Harness Client (artifacts and `AcpAgent*` types), `acp:` config-import prefix, mcp-oauth re-sign-in fixes |
