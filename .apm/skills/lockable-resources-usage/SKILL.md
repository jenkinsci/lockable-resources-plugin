---
name: lockable-resources-usage
description: 'Use when configuring Jenkins lockable resources, choosing resource labels and quantities, writing Pipeline lock() blocks, or creating Groovy shared-library helpers for exclusive resource access.'
compatibility: Requires Jenkins with Lockable Resources installed; verify step parameters against the installed plugin version.
---

# Use Jenkins Lockable Resources

## Review Status and Scope

Local draft awaiting mPokornyETM's review. Do not publish this skill or its
references without explicit approval. This is end-user guidance, not permission
to administer Jenkins, modify a controller, or maintain plugin implementation.

## Workflow

1. Establish the installed Jenkins and plugin versions, Pipeline style
   (Scripted or Declarative), intended resources, and concurrency requirements.
   Use that version's Pipeline Snippet Generator and documentation to verify
   parameters; a feature in a checkout or open PR may not be released.
2. Choose a uniquely named resource for one specific device or a shared label
   for interchangeable resources. Configure resources through the installed
   version's Manage Jenkins / Lockable Resources interface or its documented
   Configuration as Code schema. Obtain approval before changing a controller.
3. Use simple names without spaces. A resource can have multiple space-separated
   labels; each label identifies a pool, not an access-control boundary.
   Check for administrative reservations when a resource cannot be acquired.
4. Choose `resource` for a named resource, or `label` with an explicit positive
   `quantity` for a pool. Do not silently substitute another resource when the
   intended selector is unavailable. A missing named resource may be ephemeral;
   do not rely on that behavior to create durable controller configuration.
5. Keep all work that requires exclusivity inside `lock { ... }`. Jenkins owns
   lock release when the block finishes or fails. Do not generate manual manager
   calls, force-unlock scripts, or controller-internal mutations as a workaround.
6. If the body needs the selected name, set `variable` and read it only within
   the block. Prefer indexed variables for multiple selected resources: resource
   names can contain commas, so splitting the combined value is unsafe.
7. Place a Pipeline `timeout` outside the lock if the limit includes queue wait,
   or inside it if the limit applies only to the protected work. Consider
   executor availability before acquiring a node or holding a lock across waits.
8. Use the [patterns and helper](./references/pipeline-patterns.md) as starting
   points, adapting timeouts and resource labels to the user's environment.
   Do not add `@NonCPS` to code that invokes Pipeline steps or request broad
   script approvals to bypass sandbox checks.
9. Validate generated Pipeline syntax using the installed Jenkins tooling and
   test with disposable resources/jobs. Check both the free and busy cases:
   contenders for the same resource must wait, and an aborted/failed holder must
   release its lock. Ask before triggering jobs or changing real resources.

## Guardrails

- `skipIfLocked: true` skips the body instead of waiting; only use it when the
  user accepts skipped work. Do not describe it as a guaranteed acquisition.
- Do not combine `priority` and `inversePrecedence`.
- Avoid nested lock ordering that can deadlock. For multi-resource acquisition,
  verify the installed version's `extra` syntax using the Snippet Generator.
- Never print credentials or assume resource properties are safe to log.
- Do not recommend unreleased group or remote-locking features from an open PR
  as though they are supported by the installed version.

## Provenance

Initial guidance was checked against the repository README and existing
examples at source revision `c62c844c`. The bundled reference links those sources.
These are documentation patterns, not proof of compatibility with every plugin
version. Confirm generated helpers against the actual installed environment.