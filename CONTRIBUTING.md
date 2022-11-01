
## Contributing

If you want to contribute to this plugin, you probably will need a Jenkins plugin development
environment. This basically means a current version of Java (Java 11 should probably be okay for now)
and [Apache Maven]. See the [Jenkins Plugin Tutorial] for details.

If you have the proper environment, typing:

    $ mvn verify

should create a plugin as `target/*.hpi`, which you can install in your Jenkins instance. Running

    $ mvn hpi:run -Djenkins.version=2.361.1

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
- Copyright 2017-2022 Jim Klimov

See [LICENSE](LICENSE.txt)
