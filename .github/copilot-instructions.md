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

<!-- graft:start -->
## Graft — repo context graph

This repo is indexed in `graft/`: small linked markdown nodes that explain each
Graft generates a local graph in `graft/`: small linked markdown nodes that
explain each system and carry exact file:line spans. The graph is ignored by
Git and can be rebuilt with `graft build`.

For ANY task here — understanding how something works, finding where code lives,
or scoping a change — get context from the graph before grepping or opening
source files. Re-ask freely (it's cheap) and reuse literal identifiers you
already have (symbol, error string, file name) as the query. New to this repo?
Run `graft map` first — a token-budgeted orientation (dir clusters, hubs,
hotspots), no LLM, no key.

- Run `graft ask "<your question>" --source` → ranked nodes with the relevant
  code spans inlined (each hit's ≤8-line crux by default; `--full` for whole
  definitions when the crux isn't enough). Match the tool to the task shape:
  for understanding or editing, the top node IS the answer — cite its
  `covers:` file:line spans and edit straight from `--source`. For
  exhaustive tasks ("every occurrence / every caller of this pattern"), ranked
  results are top-N, not complete — run `graft grep "<literal>"` instead
  (exhaustive over indexed files, grouped by enclosing symbol), falling back
  to raw `grep -rn` only for unindexed files.
- `graft skeleton <file>` → every definition's signature + span, ~10× cheaper
  than reading the file; use it to skim an API surface.
- `graft callers <symbol>` gives precomputed, exact edges — who calls this.
  Add `--direction out` for what it calls, or `--depth N` to walk
  transitively for the full blast radius. For structural questions, skip
  ranking and use this directly.
- Or browse: `graft/INDEX.md` lists every node; follow the links.
- Monorepos and folders of multiple repos rank fairly across sub-projects —
  hits carry `[scope/]` labels naming which one they're from. Narrow with
  `graft ask "<task>" --in <scope>/` once you know where you're working.

If a returned span is truncated ("+N more lines"), open the file at that exact
range before finalizing. Only open source files when a node genuinely lacks a
needed detail, and then at the exact file:line the node points to — never
re-read whole files.

After big code changes, refresh the graph with `graft build` (deterministic,
no API key, $0).
<!-- graft:end -->
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

PRs are auto-labeled by two workflows:

### File-based labels (`actions/labeler`)

| Label | Paths |
|-------|-------|
| `java` | `src/main/java/**` |
| `tests` | `src/test/**` |
| `ci` | `.github/workflows/**`, `Jenkinsfile` |
| `dependencies` | `pom.xml` |
| `documentation` | `*.md`, `src/doc/**` |
| `localization` | `src/main/resources/**/*.properties` |
| `frontend` | `src/main/webapp/**`, `src/main/resources/**/*.jelly` |
| `chore` | `.github/**`, `.gitignore`, `crowdin.yml` |

### Release-drafter labels (`auto-label-pr.yml`)

Added based on PR title/branch (for changelog generation):

| Label | Trigger (title/branch) |
|-------|------------------------|
| `breaking` | Title contains "breaking" or "!:" |
| `major-enhancement` | Title contains "major" + "feat/enhancement" |
| `major-bug` | Title contains "major" + "fix/bug" |
| `deprecated` | Title contains "deprecat" |
| `removed` | Title contains "remove" |
| `enhancement` | Title starts with "feat"/"add" or branch `feature/*` |
| `bug` | Title starts with "fix" or branch `fix/*` |
| `documentation` | Title starts with "docs" or branch `docs/*` |
| `chore` | Title starts with "chore"/"ci:"/"build:" |
| `refactor` | Title starts with "refactor" |
| `security` | Title contains "security"/"cve-" |

### Auto-approve countdown (owner/member PRs)

For PRs by OWNER, MEMBER, or COLLABORATOR (non-draft), the label `merge-in-3-days-without-review`
is automatically added to start the auto-approve countdown.

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
