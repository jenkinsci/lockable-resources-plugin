# Copilot Instructions — Lockable Resources Plugin

This file tells GitHub Copilot (and other AI assistants) about the project's
coding standards, PR requirements, and review checklist so they are applied
automatically when generating code, commit messages, or PR descriptions.

## Project overview

Jenkins plugin that lets builds declare **lockable resources** (printers,
phones, lab machines, etc.). If a resource is already locked, the build waits
until it is free.

- Language: **Java 17+** (Maven build, Jenkins HPI packaging)
- Localization: managed via [Crowdin](https://crowdin.jenkins.io/lockable-resources-plugin); all strings live in `*.properties` files
- UI examples: documented under `src/doc/examples/`

## Issue linking

- Reference a Jira issue as `JENKINS-XXXXX` with a link to `https://issues.jenkins.io/browse/JENKINS-XXXXX`.
- Reference a GitHub issue as `#XXXXX`.
- Use closing keywords (`Fixes #XXXXX`) when the PR fully resolves an issue.
- Minor improvements do not require a tracking issue.
- Bug fixes **should** have a tracking issue to facilitate backporting.
- Major new features **must** have a tracking issue.

## Commit & PR title conventions

- The PR title is used as the **changelog entry** — write it in imperative mood
  (e.g. "Add timeout parameter to lock step").
- Follow the style of https://github.com/jenkins-infra/jenkins.io/blob/main/content/_data/changelogs/weekly.yml

## Coding rules

| Area | Rule |
|------|------|
| Internal-only public API | Annotate with `@NoExternalUse`; if called from Jelly, add `Used by {@code <panel>.jelly}` Javadoc |
| New public classes/fields/methods | Annotate with `@Restricted` or add `@since TODO` Javadoc |
| Deprecations | Use `@Deprecated(since = "TODO")` or `@Deprecated(forRemoval = true, since = "TODO")` |
| JavaScript | No inline scripts, no `eval()` — support future CSP directives (see [docs](https://www.jenkins.io/doc/developer/security/csp/)) |
| Localizations | Always use `*.properties` files; English strings are mandatory for every new key |

## Testing requirements

- Every change **must** have automated tests, or the PR must explain why tests
  are not feasible.
- A green CI build alone does not prove the changed lines were executed —
  describe the test scenario if coverage is missing.
- Frontend changes: include before/after screenshots.
- Refactoring: exercise the code before and after and confirm identical behavior.

## PR labels (automatic)

The repository uses `actions/labeler` (`.github/labeler.yml`) to auto-label PRs:

| Label | Paths |
|-------|-------|
| `java` | `src/main/java/**` |
| `tests` | `src/test/**` |
| `ci` | `.github/workflows/**`, `Jenkinsfile` |
| `dependencies` | `pom.xml` |
| `docs` | `*.md`, `src/doc/**` |
| `localization` | `src/main/resources/**/*.properties` |
| `frontend` | `src/main/webapp/**`, `src/main/resources/**/*.jelly` |

## Dependency updates

- Include links to external changelogs and, if possible, full diffs.
- For new APIs or extension points, link to at least one consumer.

## Upgrade guidelines

- Only needed for breaking changes or changes requiring manual user action.
- When applicable, set the `upgrade-guide-needed` label.

## Maintainer merge checklist

Before marking `ready-for-merge`:

1. At least **one approval** with no outstanding change requests.
2. All conversations resolved (or reviewer explicitly not blocking).
3. PR title is an accurate, imperative-mood changelog entry.
4. Correct release-drafter labels are set (see [label config](https://github.com/jenkinsci/.github/blob/ce466227c534c42820a597cb8e9cac2f2334920a/.github/release-drafter.yml#L9-L50)).
5. Java code changes are covered by automated tests.

## Interface changes

Document any UI or pipeline-DSL changes as examples under
[src/doc/examples/](src/doc/examples/readme.md).
