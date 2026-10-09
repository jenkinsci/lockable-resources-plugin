# Future Development Prompt: Lockable Resource Detail Pages

Use this prompt when continuing work on the per-resource detail-page feature in this repository.

## Project Context

This is the Jenkins Lockable Resources plugin, a Java 17+ Maven HPI plugin. UI pages use Jelly/Stapler, with plugin JavaScript and CSS under `src/main/webapp/`. Follow the repository's `.github/copilot-instructions.md`: preserve existing patterns, cover changes with tests, keep UI strings localized, and avoid inline scripts/styles in Jelly.

## Feature Goal

Implement resource detail pages at `/lockable-resources/<resource>/` with a standard Jenkins side panel. Users should be able to inspect a resource's status, holder, reason, timestamps, labels, properties, description, and note. Permissions and actions should reuse the plugin's existing `LockableResourcesRootAction` permission model. A per-resource configure page should edit description, labels, and properties.

## Current Implementation

- `LockableResourcesRootAction.getDynamic(String token)` routes `remote` to the remote API, other non-empty tokens to `LockableResourceDetailsAction`, and currently returns `this` for an empty token. Re-verify this root-route behavior; the 404 evidence below indicates the extension may not be registered at all, so this branch may not be the underlying cause.
- `LockableResourceDetailsAction.java` implements the resource view helpers and POST actions, including configure submission and repeated `propertyName`/`propertyValue` handling.
- `LockableResourceDetailsAction/index.jelly`, `configure.jelly`, `sidepanel.jelly` and their properties files provide the detail/configure views and localization.
- Configure properties use Jenkins repeatable Jelly controls. Compare against `LockableResource/config.jelly` for the existing `f:repeatableProperty` pattern. Verify the new configure form's `f:repeatable` binding renders existing values and submits every row correctly.
- Shared behavior is in `src/main/webapp/js/lockable-resources.js`; shared styles are in `src/main/webapp/css/style.css`.
- Recent Jelly cleanup moved inline style attributes into CSS classes and removed inline script bodies/event handlers. The overview donut currently receives its dynamic gradient from external JavaScript using element data attributes; check its visual behavior and CSP implications during UI verification.
- `LockableResourcesRootActionTest.java` contains detail-route/rendering and configure-submit test cases.

## Runtime Issue To Resolve

The detail feature was not visible in a local `mvn hpi:run` instance. Treat the symptoms as two separate failures:

1. At 2026-08-06 22:19, `mvn hpi:run` failed before starting Jenkins. SezPoz reported `IndexError` / `StreamCorruptedException: invalid stream header: 6F72672E`. This occurred while a manually-authored `META-INF/annotations/hudson.Extension` text index was present. That experimental file was removed; do not recreate a plain-text file at this SezPoz-serialized index path.
2. At 22:23, Jenkins started, but `/jenkins/lockable-resources/` and `/jenkins/manage/lockable-resources/` still returned 404. Runtime inspection showed the plugin itself active, but `LockableResourcesRootAction` and `LockableResourcesManagementLink` were absent from their respective Jenkins extension lists. A later page request logged `AssertionError: RequiredResourcesProperty is missing its descriptor`, and the focused test suite failed during setup with `LockableResourcesManager is not registered`.

The cause of missing extension instances is unresolved. Do not present Java 25 as the confirmed cause: the instance ran on Java 25, but that alone did not establish causality. Inspect how the plugin's build packages SezPoz metadata and compare its generated extension index inside the HPI/HPL against a known-good plugin. Keep any workaround build-generated or correctly serialized, and test it with both `hpi:run` and JenkinsRule.

## Suggested Next Steps

1. Inspect `pom.xml`, Maven compiler/annotation-processor configuration, and generated metadata from a known-good Jenkins plugin jar. Determine why build output logs report classes indexed under `hudson.Extension` while Jenkins runtime does not instantiate them.
2. Check the actual `target/classes`, `target/*.hpi`, `work/plugins/lockable-resources.hpl`, and runtime plugin classloader metadata. Avoid hand-writing a SezPoz index unless using its supported generator/format.
3. Add or retain a focused test that asserts the root action, manager, and management link are registered, then exercise `/lockable-resources/` and one resource detail URL.
4. Only after extension registration works, diagnose any Jelly rendering or route failures. Verify root route, detail route, configure repeatable add/remove, and property persistence in the running UI.
5. Run `mvn -DskipTests compile test-compile`, the focused JenkinsRule test, then `mvn hpi:run` and HTTP/UI smoke checks.

## Working-Tree Caution

The workspace was already dirty during the previous session. At handoff, modified or untracked files included changes in `RemoteApiV1Action.java`, `RemoteApiV1ActionTest.java`, the root action and root-action tests, shared CSS/JS/Jelly files, the new detail-action Java/Jelly resources, plus `pw.cmd` and `stopHPI.cmd`. These may be user work. Inspect `git status` before editing and preserve unrelated changes; do not reset, checkout, or delete them.

## Communication Requirements

State what is verified versus suspected. Do not claim the page is restored until the running Jenkins endpoint returns successfully and the RootAction extension is present. Report test/build failures with the actual cause and distinguish them from the separate SezPoz HPI-generation failure.