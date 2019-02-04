# Jenkins Lockable Resources Plugin

This plugins allows to define "lockable resources" in the global configuration.
These resources can then be "required" by jobs. If a job requires a resource
which is already locked, it will be put in queue until the resource is released.

## Configuration as Code
This plugin can be configured via [Configuration-as-Code](https://github.com/jenkinsci/configuration-as-code-plugin).
For details, please see the [demo configuration](https://github.com/jenkinsci/configuration-as-code-plugin).