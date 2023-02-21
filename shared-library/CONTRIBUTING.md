# Contributing

Fork this repository. Make your changes, tests it and provide a pull-request. That`s it.

## Setup environment

General you need same setup as to build the plugin.
Please refer to the plugin [CONTRIBUTING](../plugin/CONTRIBUTING.md) document for details on how to proceed!

## Build and test

If you have the proper environment, typing:

```sh
mvn clean verify
```

should provide tests for shared library

```sh
mvn hpi:run -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true
```

allows you to spin up a test Jenkins instance on [localhost] to test your
local changes before committing.

The plugin shall set everything you need on demand.

> Keep in mind, that shared library works only with committed changes.

The option `hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` allows you to load changes from locale repository without pushing to remote repository.
