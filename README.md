# Jenkins Lockable Resources Plugin

[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Flockable-resources-plugin%2Fmaster)](https://ci.jenkins.io/job/Plugins/job/lockable-resources-plugin/job/master/)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/lockable-resources-plugin.svg)](https://github.com/jenkinsci/lockable-resources-plugin/blob/master/LICENSE.txt)
[![Maintenance](https://img.shields.io/maintenance/yes/2019.svg)]()

This plugins allows to define "lockable resources" in the global configuration.
These resources can then be "required" by jobs. If a job requires a resource
which is already locked, it will be put in queue until the resource is released.

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
