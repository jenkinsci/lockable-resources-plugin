# Global resource groups

This implements the global grouping part of
[#1044](https://github.com/jenkinsci/lockable-resources-plugin/issues/1044).
Configure a group named `release` with a description such as "Release test devices"
in Jenkins global configuration. Group names are case-sensitive and descriptions
are optional. Resource names remain globally unique across groups; groups do not
create separate resource-name namespaces.
Folder-scoped resources and delegated folder administration are not implemented.

Groups can be configured before they have resources; empty groups also appear in
the UI with a zero count. Assigning a resource to a new group automatically adds
that group. Removing a catalog entry does not move or delete its resources:
groups still referenced by resources remain available, without the removed description.

Configure `device-01` in `common` and `device-02` in `release`, both with labels
`armhf env=dev os=bullseye`. Group membership is separate from labels.

An existing job can keep its capability selector without listing excluded groups:

```groovy
lock(label: 'armhf && "os=bullseye"', quantity: 1, variable: 'DEVICE') {
    echo "Testing ${env.DEVICE}"
}
```

This selects only `device-01`. If it is busy, the job waits even when
`device-02` is free.

To use the release device, opt in explicitly:

```groovy
lock(group: 'release', label: '"os=bullseye"', quantity: 1,
     variable: 'DEVICE') {
    echo "Release testing ${env.DEVICE}"
}
```

For a named device, specify its group too:

```groovy
lock(group: 'release', resource: 'device-02') {
    echo 'Testing the dedicated release device'
}
```

Extra selectors inherit the main group unless they specify their own:

```groovy
lock(group: 'release', label: 'armhf', quantity: 1,
     extra: [[group: 'common', label: 'test-server', quantity: 1]]) {
    echo 'Both devices are held for this block'
}
```

To select by group alone, use `lock(group: 'release', quantity: 1)`.
A group-only request without a positive quantity locks all members.

An unassigned or blank resource group is `common`. Existing resources and jobs
without a group migrate to `common`. A group's membership and a capability label
expression intersect; a matching label never selects a resource from another group.
Omitted or non-positive quantity requests all matching resources in the selected
group, so specify `quantity: 1` when only one device is needed.

When **Fail immediately when the selected group has fewer matching resources than the requested quantity** is enabled, a positive quantity
cannot exceed the number of matching resources in the selector's group. This
also applies to group-only requests and each extra selector, using its inherited
or explicitly overridden group. Locked and reserved resources count as existing:
temporary unavailability still causes the request to wait. With fail-fast disabled,
an insufficient quantity waits for more matching resources to be added to that group.

On the resource management page, the **Resources** view offers **All**,
**common**, and a button for each custom group with its resource count. Group
filters combine with the existing status, name, label and search filters.
Group membership appears as a rounded badge below the resource name only in
**All**; it is hidden when a specific group is selected. The **#** column numbers
the current filtered and sorted view from 1, continuing across pages.
Column widths stay consistent when switching groups or filters. Long names,
labels, and properties wrap within their columns; narrower screens can scroll
the table horizontally. Columns can still be hidden with the column picker.
Compact checkbox, row-number, status, since, and action columns leave more space for properties.
Each property shows its key above its value so device mapping lines remain readable.
The **Add Resource** and **Edit Resource** dialogs include group membership.
Changing membership re-evaluates waiting requests, but does not revoke locks
already held by running builds.

For remote locks, upgrade both the requesting controller and the resolving
controller to a version supporting groups before using custom groups. Older
servers do not enforce the new group selector. On a group-aware server, remote
requests also remain restricted to resources exposed by its exposure labels.

Group selection is not a job authorization policy. No job allow-list or folder
permission policy is provided. A job that can request locks
can explicitly request a custom group. Do not rely on groups to prevent direct
network access to a physical device.
