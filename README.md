# Jenkins Lockable Resources Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/lockable-resources.svg)](https://plugins.jenkins.io/lockable-resources)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/lockable-resources-plugin.svg?label=release)](https://github.com/jenkinsci/lockable-resources-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/lockable-resources.svg?color=blue)](https://plugins.jenkins.io/lockable-resources)
[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Flockable-resources-plugin%2Fmaster)](https://ci.jenkins.io/job/Plugins/job/lockable-resources-plugin/job/master/)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/lockable-resources-plugin.svg)](https://github.com/jenkinsci/lockable-resources-plugin/blob/master/LICENSE.txt)
[![Maintenance](https://img.shields.io/maintenance/yes/2022.svg)](https://github.com/jenkinsci/lockable-resources-plugin)
[![Crowdin](https://badges.crowdin.net/e/656dcffac5a09ad0fbdedcb430af1904/localized.svg)](https://jenkins.crowdin.com/lockable-resources-plugin)
[![Join the chat at https://gitter.im/jenkinsci/lockable-resources](https://badges.gitter.im/jenkinsci/lockable-resources.svg)](https://gitter.im/jenkinsci/lockable-resources?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

This plugin allows defining lockable resources (such as printers, phones,
computers, etc.) that can be used by builds. If a build requires a resource
which is already locked, it will wait for the resource to be free.

## Usage

### Adding lockable resources

1. In *Manage Jenkins* > *Configure System* go to **Lockable Resources
   Manager**
2. Select *Add Lockable Resource*

Each lockable resource has the following properties:

- **Name** - A mandatory name (not containing spaces!) for this particular resource, i.e.
  `DK_Printer_ColorA3_2342`
- **Description** - Optional verbose description of this particular resource,
  i.e. `Printers in the Danish Office`
- **Labels** - Optional space-delimited list of Labels (A label can not containing spaces) used to
  identify a pool of resources. i.e. `DK_Printers_Office Country:DK device:printer`,
  `DK_Printer_Production`, `DK_Printer_Engineering`
- **Reserved by** - Optional reserved / locked cause. If non-empty,
  the resource will be unavailable for jobs. i.e. `All printers are currently not available due to maintenance.`
  This option is still possible, but we recommend to use the page `<jenkinsRootUrl>/lockable-resources/`

A resource is always the one thing that is locked (or free or reserved).
It exists once and has an unique name (if we take the hardware example, this may be `office_printer_14`).
Every resource can have multiple labels (the printer could be labeled `dot-matrix-printer`, `in-office-printer`, `a4-printer`, etc.).
All resources with the same label form a "pool", so if you try to lock an `a4-printer`, one of the resources with the label `a4-printer` will be locked when it is available.
If all resources with the label `a4-printer` are in use, your job waits until one is available.
This is similar to nodes and node labels.

### Using a resource in a freestyle job

When configuring the job, select **This build requires lockable resources**.
Please see the help item for each field for details.

### Using a resource in a pipeline job

When the `lock` step is used in a Pipeline, if the resource to be locked isn't
already defined in the Jenkins global configuration, an ephemeral resource is
used: These resources only exist as long as any running build is referencing
them.

Examples:

#### Acquire lock

```groovy
echo 'Starting'
lock('my-resource-name') {
  echo 'Do something here that requires unique access to the resource'
  // any other build will wait until the one locking the resource leaves this block
}
echo 'Finish'
```

#### Take first position in queue

```groovy
lock(resource: 'staging-server', inversePrecedence: true) {
    node {
        servers.deploy 'staging'
    }
    input message: "Does ${jettyUrl}staging/ look good?"
}
```

#### Resolve a variable configured with the resource name

```groovy
lock(label: 'some_resource', variable: 'LOCKED_RESOURCE') {
  echo env.LOCKED_RESOURCE
}
```

When multiple locks are acquired, each will be assigned to a numbered variable:

```groovy
lock(label: 'some_resource', variable: 'LOCKED_RESOURCE', quantity: 2) {
  // comma separated names of all acquired locks
  echo env.LOCKED_RESOURCE

  // first lock
  echo env.LOCKED_RESOURCE0

  // second lock
  echo env.LOCKED_RESOURCE1
}
```

#### Skip executing the block if there is a queue

```groovy
lock(resource: 'some_resource', skipIfLocked: true) {
  echo 'Do something now or never!'
}
```

Detailed documentation can be found as part of the
[Pipeline Steps](https://jenkins.io/doc/pipeline/steps/lockable-resources/)
documentation.

#### Multiple resource lock

```groovy
lock(label: 'label1', extra: [[resource: 'resource1']]) {
	echo 'Do something now or never!'
}
echo 'Finish'"
```

```groovy
lock(
  variable: 'var',
  extra: [
    [resource: 'resource4'],
    [resource: 'resource2'],
    [label: 'label1', quantity: 2]
  ]
) {
  def lockedResources = env.var.split(',').sort()
  echo "Resources locked: ${lockedResources}"
}
echo 'Finish'
```

More examples are [here](src/doc/examples/readme.md).

## Configuration as Code

This plugin can be configured via
[Configuration-as-Code](https://github.com/jenkinsci/configuration-as-code-plugin).

### Example configuration

```yml
unclassified:
  lockableResourcesManager:
    declaredResources:
      - name: "S7_1200_1"
        description: "S7 PLC model 1200"
        labels: "plc:S7 model:1200"
        reservedBy: "Reserved due maintenance window"
      - name: "S7_1200_2"
        labels: "plc:S7 model:1200"
```

Properties *description*, *labels* and *reservedBy* are optional.

## lockable-resources overview

The page `<jenkinsRootUrl>/lockable-resources/` provides an overview over all resources and actions to change resource status.

Name | Permission | Description
-----|------------|------------
Reserve | RESERVE | Reserves an available resource for currently logged user indefinitely (until that person, or some explicit scripted action, decides to release the resource).
Unreserve | RESERVE | Un-reserves a resource that may be reserved by some person already. The user can unreserve only own resource. Administrator can unreserve any resource.
Unlock | UNLOCK | Unlocks a resource that may be or not be locked by some job (or reserved by some user) already.
Steal lock | STEAL | Reserves a resource that may be or not be locked by some job (or reserved by some user) already. Giving it away to currently logged user indefinitely (until that person, or some explicit scripted action, later decides to release the resource).
Reassign | STEAL | Reserves a resource that may be or not be reserved by some person already. Giving it away to currently logged user indefinitely (until that person, or some explicit scripted action, decides to release the resource).
Reset | UNLOCK | Reset a resource that may be reserved, locked or queued.
Note | RESERVE | Add or edit resource note.

## Upgrading from 1102.vde5663d777cf

Due an [issue](https://github.com/jenkinsci/lockable-resources-plugin/issues/434) **is not possible anymore to read resource-labels** from the config file org.jenkins.plugins.lockableresources.LockableResourcesManager.xml, **which is generated in the release** [1102.vde5663d777cf](https://github.com/jenkinsci/lockable-resources-plugin/releases/tag/1102.vde5663d777cf)

This issue does not **effect** instances configured by [Configuration-as-Code](https://github.com/jenkinsci/configuration-as-code-plugin) plugin.

A possible solution is, to remove the `<string>` tags from your `org.jenkins.plugins.lockableresources.LockableResourcesManager.xml`config file manually, before you upgrade to new version (Keep in mind, that a backup is still good idea).


Example:

change this one
```
<labels>
  <string>tests-integration-installation</string>
</labels>
```
to
```
<labels>
  tests-integration-installation
</labels>
```

## Changelog

- See [GitHub Releases](https://github.com/jenkinsci/lockable-resources-plugin/releases)
  for recent versions.
- See the [old changelog](CHANGELOG.old.md) for versions 2.5 and older.

## Report an Issue

Please report issues and enhancements through the [Jenkins issue tracker in GitHub](https://github.com/jenkinsci/lockable-resources-plugin/issues/new/choose)

## Contributing

Contributions are welcome, please
refer to the separate [CONTRIBUTING](CONTRIBUTING.md) document
for details on how to proceed!
Join [Gitter channel](https://gitter.im/jenkinsci/lockable-resources) to discuss your ideas with the community.

## License
All source code is licensed under the MIT license.
See [LICENSE](LICENSE.txt)
