/*
 * See the documentation for more options:
 * https://github.com/jenkins-infra/pipeline-library/
 */
buildPlugin(useContainerAgent: true, configurations: [
  // Test the common case (i.e., a recent LTS release) on both Linux and Windows.
  [ platform: 'linux', jdk: '11' ],
  [ platform: 'windows', jdk: '11'],

  // Test the bleeding edge of the compatibility spectrum (i.e., the latest supported Java runtime).
  // see also https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/
  [ platform: 'linux', jdk: '17', jenkins: '2.387.1' ],
])
