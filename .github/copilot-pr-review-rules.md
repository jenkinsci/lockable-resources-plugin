# Copilot Instructions — PR Review & Cleanup Rules

This file defines the mandatory checklist Copilot must follow when reviewing,
cleaning up, or triaging pull requests in this repository.

> **Context:** This plugin is large, old, and highly complex. Regressions and
> performance degradation are real risks. Every PR review must account for
> testing depth and runtime impact — not just correctness.

## PR review workflow

When asked to review or clean up a PR, always follow these steps **in order**:

### 1. Validate PR description

- Read the PR title and body.
- Verify the description accurately reflects the actual code changes.
- Flag mismatches: wrong description, copy-paste errors, or stale text from
  a template that was not filled in.

### 2. Validate linked items

- Check linked issues (Jira `JENKINS-XXXXX` or GitHub `#XXXXX`).
- Confirm the linked issue matches the intent of the PR.
- Flag PRs that reference an unrelated or wrong issue.
- Minor improvements may omit an issue; bug fixes and features must have one.

### 3. Validate the implementation

- Review the diff: does the code change actually solve the described problem?
- Look for incomplete changes, dead code, debug leftovers, or unrelated edits
  that got mixed in.
- Ensure new code follows the project coding rules (see `copilot-instructions.md`).

### 4. Review all comments — open AND closed

- **Open comments** must be resolved or explicitly accepted with a meaningful
  response before the PR can be merged. No open comment may be ignored.
- **Closed / resolved comments** should be read for context — they reveal
  prior reviewer concerns, rejected approaches, and design decisions.

### 5. Propose a continuation plan

After reviewing the PR state, always provide **at least one concrete proposal**
for how to move the PR forward. This should include:

- What is missing or broken and what needs to change.
- Specific next steps (code changes, rebases, comment resolutions).
- Whether the PR should be continued, split, or closed in favour of a fresh PR.
- If the PR has been idle for a long time, assess whether the approach is still
  viable given the current state of `master`.

### 6. Define testing — manual AND automated

Testing is **mandatory** for every non-trivial change. The plugin is complex
and regressions are costly. Always provide:

#### Manual testing steps

- Describe concrete steps to verify the change locally:
  ```
  mvn hpi:run
  # then in Jenkins UI → Lockable Resources → ...
  ```
- Include expected outcomes (what the user should see / not see).
- For lock/unlock logic changes, describe multi-job concurrency scenarios.
- For UI changes, list which pages to check and what to look for.

#### Automated testing requirements

- **Unit tests** (`src/test/java/...`) for any new or changed logic.
- **Integration tests** using `JenkinsRule` for pipeline-level behaviour.
- **Configuration-as-Code tests** (`ConfigurationAsCodeTest`) when config
  structures change.
- If the PR lacks tests, write them or explain precisely why they cannot be
  added — "no tests" without justification is not acceptable.
- Run the full test suite before approving:
  ```
  mvn clean verify
  ```
- If tests are flaky or slow, note it — do not silently skip them.

### 7. Evaluate performance impact

This plugin manages shared resources under contention. Performance matters.

- **Lock/unlock hot paths:** Changes to `LockableResourcesManager`,
  `LockStepExecution`, or queue handling must be evaluated for:
  - Lock contention and synchronization overhead
  - Iteration over all resources (O(n) scans)
  - Unnecessary save/persist calls (`save()` triggers disk I/O)
- **Memory:** Watch for resource leaks, unbounded collections, or retained
  references to `Run` / `FlowNode` objects.
- **Scalability:** Consider behaviour with 100+ resources and 50+ concurrent
  builds waiting for locks.
- If a change affects a hot path, request or write a benchmark scenario:
  ```java
  // Example: time lock acquisition with N resources and M waiters
  ```
- Flag any change that adds `synchronized` blocks, new `save()` calls, or
  full-collection scans without justification.

### 8. Update the source branch

- The source branch **must** be up-to-date with the target before merge.
- If the branch is behind, rebase or merge the target into the source branch
  first.
- Never merge a PR with an outdated source branch.

### 9. Target branch rules

- The default target branch is **`master`**.
- A different target branch is only acceptable for long-running feature
  development branches (explicitly agreed upon beforehand).
- If the target branch looks wrong, flag it immediately.

## Summary checklist

Use this quick checklist when processing any PR:

```
[ ] Description matches the actual changes
[ ] Linked issue (if any) is correct and relevant
[ ] Implementation solves the described problem
[ ] No unrelated changes mixed in
[ ] All open comments resolved or explicitly accepted
[ ] Closed comments reviewed for context
[ ] Continuation plan proposed (next steps / split / close)
[ ] Manual testing steps documented
[ ] Automated tests present or justified absence
[ ] Performance impact evaluated (hot paths, sync, save, memory)
[ ] Source branch is up-to-date with target
[ ] Target branch is master (or justified exception)
```
