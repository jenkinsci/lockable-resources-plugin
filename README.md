# Jenkins Lockable Resources Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/lockable-resources.svg)](https://plugins.jenkins.io/lockable-resources)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/lockable-resources-plugin.svg?label=release)](https://github.com/jenkinsci/lockable-resources-plugin/releases/latest)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/lockable-resources.svg?color=blue)](https://plugins.jenkins.io/lockable-resources)
[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Flockable-resources-plugin%2Fmaster)](https://ci.jenkins.io/job/Plugins/job/lockable-resources-plugin/job/master/)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/lockable-resources-plugin.svg)](https://github.com/jenkinsci/lockable-resources-plugin/blob/master/LICENSE.txt)
[![Maintenance](https://img.shields.io/maintenance/yes/2020.svg)](https://github.com/jenkinsci/lockable-resources-plugin)

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

Examples:

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

*Resolve a variable configured with the resource*

```groovy
lock(label: 'some_resource', variable: 'LOCKED_RESOURCE') {
  echo env.LOCKED_RESOURCE
}
```

*Skip executing the block if there is a queue*

```groovy
lock(resource: 'some_resource', skipIfLocked: true) {
  echo 'Do something now or never!'
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

## Contributing

If you want to contribute to this plugin, you probably will need a Jenkins plugin development
environment. This basically means a current version of Java (Java 8 should probably be okay for now)
and [Apache Maven]. See the [Jenkins Plugin Tutorial] for details.

If you have the proper environment, typing:

    $ mvn verify

should create a plugin as `target/*.hpi`, which you can install in your Jenkins instance. Running

    $ mvn hpi:run -Djenkins.version=2.164.1

allows you to spin up a test Jenkins instance on [localhost] to test your
local changes before committing.

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
