
# Contributing

If you want to contribute to this plugin, you probably will need a Jenkins plugin development
environment. This basically means a current version of Java (Java 11 should probably be okay for now)
and [Apache Maven]. See the [Jenkins Plugin Tutorial] for details.
You could also go the [GitPod](https://gitpod.io/#https://github.com/jenkinsci/lockable-resources-plugin) way.

If you have the proper environment, typing:

```sh
mvn verify
```

should create a plugin as `target/*.hpi`, which you can install in your Jenkins instance. Running

```sh
mvn hpi:run
```

allows you to spin up a test Jenkins instance on [localhost] to test your
local changes before committing.

[Apache Maven]: https://maven.apache.org/
[Jenkins Plugin Tutorial]: https://jenkins.io/doc/developer/tutorial/prepare/
[localhost]: http://localhost:8080/jenkins/

## Code Style

This plugin tries to migrate to [Google Java Code Style], please try to adhere to that style
whenever adding new files or making big changes to existing files. If your IDE doesn't support
this style, you can use the [fmt-maven-plugin], like this:

```sh
    mvn fmt:format -DfilesNamePattern=ChangedFile\.java
```

to reformat Java code in the proper style.

[Google Java Code Style]: https://google.github.io/styleguide/javaguide.html
[fmt-maven-plugin]: https://github.com/coveo/fmt-maven-plugin

## Code coverage

Test coverage is a percentage measure of the degree to which the source code of a program is executed when a test is run. A program with high test coverage has more of its source code executed during testing, which suggests it has a lower chance of containing undetected software bugs compared to a program with low test coverage. The best way to improve code coverage is writing of automated tests.

To get local line-by-line coverage report execute this command

```sh
mvn -P enable-jacoco clean verify jacoco:report
```

The report is then located in *target/site/jacoco/index.html*.

## License

The MIT License (MIT)

- Copyright 2013-2015 6WIND
- Copyright 2016-2018 Antonio Muñiz
- Copyright 2019 TobiX
- Copyright 2017-2022 Jim Klimov

See [LICENSE](LICENSE.txt)

## Localization

[![Crowdin](https://badges.crowdin.net/e/656dcffac5a09ad0fbdedcb430af1904/localized.svg)](https://jenkins.crowdin.com/lockable-resources-plugin)

Internationalization documentation for Jelly, Java and Groovy can be found [here](https://www.jenkins.io/doc/developer/internationalization/).

To translate this plugin we recommend to use [Crowdin](https://jenkins.crowdin.com/lockable-resources-plugin).

Read on [how to use the crowdin web interface](https://www.jenkins.io/doc/developer/crowdin/) to translate plugins.

When you want to help us, please create a new [feature request](https://github.com/jenkinsci/lockable-resources-plugin/issues/new?assignees=&labels=enhancement&template=2-feature-request.yml) with following content

Title:
l10n: \<language\>
Description
I would provide new (or update) translations for \<language\>

We will then add you to the Crowdin project.

For short translations / updates we can also send you invitation (time limited)

**Privacy policy notice**
When you start translating via Crowdin service, your browsers will send cookies to Crowdin so that Crowdin can identify translators contributing to the project. You might need to update the privacy policy to reflect this aspect of cookies usage.
