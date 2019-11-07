# Jenkins Lockable Resources Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/lockable-resources.svg)](https://plugins.jenkins.io/lockable-resources)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/lockable-resources-plugin.svg?label=release)](https://github.com/jenkinsci/lockable-resources-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/lockable-resources.svg?color=blue)](https://plugins.jenkins.io/lockable-resources)
[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Flockable-resources-plugin%2Fmaster)](https://ci.jenkins.io/job/Plugins/job/lockable-resources-plugin/job/master/)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/lockable-resources-plugin.svg)](https://github.com/jenkinsci/lockable-resources-plugin/blob/master/LICENSE.txt)
[![Maintenance](https://img.shields.io/maintenance/yes/2019.svg)]()

This plugin allows defining lockable resources (such as printers, phones,
computers, etc.) that can be used by builds. If a build requires a resource
which is already locked, it will wait for the resource to be free.

## Usage

### Adding lockable resources

1. In *Manage Jenkins* > *Configure System* go to **Lockable Resources
   Manager**
2. Select *Add Lockable Resource*

Each lockable resource has the following properties:

- **Name** - A name (not containing spaces) for this particular resource, i.e.
  `DK_Printer_ColorA3_2342`
- **Description** - A verbose description of this particular resource,
  i.e. `DIN A3 color laser printer, office building, room 2.342`
- **Labels** - Space-delimited list of labels (not containing spaces) used to
  identify a pool of resources. i.e. `DK_Printers_Office`,
  `DK_Printers_Production`, `DK_Printers_Engineering`, each label specifies
  a single pool
- **Reserved by** - If non-empty, the resource will be unavailable for jobs.
  e.g. `Printer is currently not available due to maintenance.`

### Using a resource in a freestyle job

When configuring the job, select **This build requires lockable resources**.
Please see the help item for each field for details.

### Using a resource in a pipeline job

When the `lock` step is used in a Pipeline, if the resource to be locked isn't
already defined in the Jenkins global configuration, an ephermal resource is
used: These resources only exist as long as any running build is referencing
them.

- **resource** - The resource name to lock as defined in Global settings. If inversePrecedence isn't also specified, the step can be called as lock('some-resource') without the named argument. Set to `null` if locking on a label in a declarative pipeline is desired.
- **label** - Can be used to require locks on multiple resources concurrently. The build will wait until all resources tagged with the given label are available. Only this or resource can be used simultaneously.
- **quantity** - (optional) Specifies the number of resources required within the selected label. If not set, all resources from the label will be required.
- **inversePrecedence** - (optional) By default waiting builds are given the lock in the same order they requested to acquire it. If inversePrecedence is set to true, this will be done in reverse order instead, so that the newest build to request the lock will be granted it first.
- **variable** - (optional) When locking a resource via a label you can use variable to set an environment variable with the name of the locked resource.

#### Single resource examples

```groovy
echo 'Starting'
lock('my-resource-name') {
  echo 'Do something here that requires unique access to the resource'
  // any other build will wait until the one locking the resource leaves this block
}
echo 'Finish'
```

```groovy
lock(resource: 'staging-server', inversePrecedence: true) {
    node {
        servers.deploy 'staging'
    }
    input message: "Does ${jettyUrl}staging/ look good?"
}
```
#### Pooled resource example

```groovy
lock(label: 'DK_Printers_Office', quantity: 1, variable: 'LOCKED_RESOURCE') {
  echo "Printing on ${env.LOCKED_RESOURCE}"
  // might output "Printing on DK_Printer_ColorA3_2342" if the resource
  // "DK_Printer_ColorA3_2342" has the label "DK_Printers_Office" attached
}
```

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
  for recent versions
* See the [plugin's Wiki page](https://wiki.jenkins.io/display/JENKINS/Lockable+Resources+Plugin#LockableResourcesPlugin-Changelog)
  for versions 2.5 and older

## Contributing

If you want to contribute to this plugin, you probably will need a Jenkins plugin developement
environment. This basically means a current version of Java (Java 8 should probably be okay for now)
and [Apache Maven]. See the [Jenkins Plugin Tutorial] for details.

If you have the proper environment, typing:

    $ mvn verify

should create a plugin as `target/*.hpi`, which you can install in your Jenkins instance. Running

    $ mvn hpi:run -Djenkins.version=2.164.1

allows you to spin up a test Jenkins instance on [localhost] to test your
local changes before commiting.

[Apache Maven]: https://maven.apache.org/
[Jenkins Plugin Tutorial]: https://jenkins.io/doc/developer/tutorial/prepare/
[localhost]: http://localhost:8080/jenkins/

### Code Style

This plugin tries to migrate to [Google Java Code Style], please try to adhere to that style
whenever adding new files or making big changes to existing files. If your IDE doesn't support
this style, you can use the [fmt-maven-plugin], like this:

    $ mvn fmt:format -DfilesNamePattern=ChangedFile\.java

to reformat Java code in the proper style.

[Google Java Code Style]: https://google.github.io/styleguide/javaguide.html
[fmt-maven-plugin]: https://github.com/coveo/fmt-maven-plugin

## License

The MIT License (MIT)

- Copyright 2013-2015 6WIND
- Copyright 2016-2018 Antonio Muñiz
- Copyright 2019 TobiX

See [LICENSE](LICENSE.txt)
