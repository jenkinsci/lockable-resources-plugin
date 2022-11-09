# Jenkins Lockable Resources Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/lockable-resources.svg)](https://plugins.jenkins.io/lockable-resources)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/lockable-resources-plugin.svg?label=release)](https://github.com/jenkinsci/lockable-resources-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/lockable-resources.svg?color=blue)](https://plugins.jenkins.io/lockable-resources)
[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Flockable-resources-plugin%2Fmaster)](https://ci.jenkins.io/job/Plugins/job/lockable-resources-plugin/job/master/)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/lockable-resources-plugin.svg)](https://github.com/jenkinsci/lockable-resources-plugin/blob/master/LICENSE.txt)
[![Maintenance](https://img.shields.io/maintenance/yes/2022.svg)](https://github.com/jenkinsci/lockable-resources-plugin)
[![Crowdin](https://badges.crowdin.net/e/656dcffac5a09ad0fbdedcb430af1904/localized.svg)](https://jenkins.crowdin.com/lockable-resources-plugin)

This plugin allows defining lockable resources (such as printers, phones,
computers, etc.) that can be used by builds. If a build requires a resource
which is already locked, it will wait for the resource to be free.

## Usage

### Adding lockable resources

1. In *Manage Jenkins* > *Configure System* go to **Lockable Resources
   Manager**
2. Select *Add Lockable Resource*

Each lockable resource has the following properties:

- **Name** - A name (not containing spaces!) for this particular resource, i.e.
  `DK_Printer_ColorA3_2342`
- **Description** - A verbose description of this particular resource,
  i.e. ` Printers in the Danish Office`
- **Labels** - Space-delimited list of Labels (Not containing spaces) used to
  identify a pool of resources. i.e. `DK_Printers_Office`,
  `DK_Printer_Production`, `DK_Printer_Engineering`
- **Reserved by** - If non-empty, the resource will be unavailable for jobs.
  i.e. `All printers are currently not available due to maintenance.`

### Using a resource in a freestyle job

When configuring the job, select **This build requires lockable resources**.
Please see the help item for each field for details.

### Using a resource in a pipeline job

When the `lock` step is used in a Pipeline, if the resource to be locked isn't
already defined in the Jenkins global configuration, an ephemeral resource is
used: These resources only exist as long as any running build is referencing
them.

#### Locking Examples

*Acquire lock*

```groovy
echo 'Starting'
lock('my-resource-name') {
  echo 'Do something here that requires unique access to the resource'
  // any other build will wait until the one locking the resource leaves this block
}
echo 'Finish'
```

*Take first position in queue*

```groovy
lock(resource: 'staging-server', inversePrecedence: true) {
    node {
        servers.deploy 'staging'
    }
    input message: "Does ${jettyUrl}staging/ look good?"
}
```

*Resolve a variable configured with the resource name*

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

*Skip executing the block if there is a queue*

```groovy
lock(resource: 'some_resource', skipIfLocked: true) {
  echo 'Do something now or never!'
}
```

#### Update Examples

*Set the note on a lock*

```groovy
updateLock(resource: 'printer', setNote: 'this might take a long time...')
```

*Changing labels of a lock*

```groovy
updateLock(resource: 'printer', addLabel: 'offline')
```
*Adding/Deleting locks dynamically*

```groovy
discoveredPrinters.each { p ->
  updateLock(resource: p.name, setLabels:'printer', createResource:true)
}
```

```groovy
brokenPrinters.each { p ->
  updateLock(resource: p.name, deleteResource:true)
}
```

Detailed documentation can be found as part of the
[Pipeline Steps](https://jenkins.io/doc/pipeline/steps/lockable-resources/)
documentation.

## Configuration as Code

This plugin can be configured via
[Configuration-as-Code](https://github.com/jenkinsci/configuration-as-code-plugin).

### Example configuration

```
unclassified:
  lockableResourcesManager:
    declaredResources:
      - name: "Resource_A"
        description: "Description_A"
        labels: "Label_A"
        reservedBy: "Reserved_A"
```

## Changelog

* See [GitHub Releases](https://github.com/jenkinsci/lockable-resources-plugin/releases)
  for recent versions.
* See the [old changelog](CHANGELOG.old.md) for versions 2.5 and older.

## Report an Issue

Please report issues and enhancements through the [Jenkins issue tracker in GitHub](https://github.com/jenkinsci/lockable-resources-plugin/issues/new/choose)

## Contributing

Contributions are welcome, please
refer to the separate [CONTRIBUTING](CONTRIBUTING.md) document
for details on how to proceed!

## License

All source code is licensed under the MIT license.
See [LICENSE](LICENSE.txt)
