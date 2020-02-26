# Old Changelog

This is the old changelog, see
[GitHub Releases](https://github.com/jenkinsci/lockable-resources-plugin/releases)
for recent versions.

## Release 2.5 (2019-03-25)

- [Fix security issue](https://jenkins.io/security/advisory/2019-03-25/)

## Release 2.4 (2019-01-18)

- [JENKINS-46555](https://issues.jenkins-ci.org/browse/JENKINS-46555) - Fix NPE
  on invalid entries.

## Release 2.3 (2018-06-26)

- [JENKINS-34433](https://issues.jenkins-ci.org/browse/JENKINS-34433) - Signal
  queued Pipeline tasks on unreserve

- Allow locking multiple resources in Pipeline

## Release 2.2 (2018-03-06)

- [JENKINS-40997](https://issues.jenkins-ci.org/browse/JENKINS-40997) - New
  configuration option to get the name of the locked resource inside the lock
  block (Pipeline).

- [JENKINS-49734](https://issues.jenkins-ci.org/browse/JENKINS-49734) -
  Add a PauseAction to the build when waiting for locking, so Pipeline
  representations in the UI are correctly shown.
- [JENKINS-43574](https://issues.jenkins-ci.org/browse/JENKINS-43574) - Fixed
  the "empty" resources lock (message: "acquired lock on \[\]")

## Release 2.1 (2017-11-13)

- [JENKINS-47235](https://issues.jenkins-ci.org/browse/JENKINS-47235) -
  Trim whitespace from resource names.
- [JENKINS-47754](https://issues.jenkins-ci.org/browse/JENKINS-47754) -
  Fix broken Freestyle behavior.

## Release 1.11.2 (2017-03-15)

- [JENKINS-40368](https://issues.jenkins-ci.org/browse/JENKINS-40368) - Locked
  resources are not always freed up on Pipeline hard kill when there
  are other pipelines waiting on the Resource

## Release 1.11.1 (2017-02-20)

- [JENKINS-40879](https://issues.jenkins-ci.org/browse/JENKINS-40879) - Locked
  areas are executed multiple times in parallel

## Release 1.11 (2016-12-19)

- [JENKINS-34268](https://issues.jenkins-ci.org/browse/JENKINS-34268) -
  lock multiple resources concurrently
- [JENKINS-34273](https://issues.jenkins-ci.org/browse/JENKINS-34273) -
  add the number of resources to lock from a given label

## Release 1.10 (2016-07-12)

- [JENKINS-36479](https://issues.jenkins-ci.org/browse/JENKINS-36479) -
  properly clean up resources locked by hard-killed or deleted while
  in progress Pipeline builds.

## Release 1.9 (2016-06-01)

- Reserved resources parameter visibility in environment (related to
  SECURITY-170)

## Release 1.8 (2016-04-14)

- Pipeline compatibility: lock step

## Release 1.2 (2014-02-05)

- Manual reservation/un-reservation of resources now require specific
  permissions

## Release 1.1 (2014-02-03)

- Allow jobs to require a subset of specified resources (the number of required
  resources is configurable)
- Allow manual reservation/un-reservation of resources

## Release 1.0 (2013-12-12)

- Initial release
