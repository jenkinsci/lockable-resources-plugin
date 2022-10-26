/*
 * See the documentation for more options:
 * https://github.com/jenkins-infra/pipeline-library/
 */
buildPlugin(useContainerAgent: true, configurations: [
  // Test the long-term support end of the compatibility spectrum (i.e., the minimum required
  // Jenkins version).
  [ platform: 'linux', jdk: '8' ],

  // Test the common case (i.e., a recent LTS release) on both Linux and Windows.
  [ platform: 'linux', jdk: '11', jenkins: '2.346.3' ],
  [ platform: 'windows', jdk: '11', jenkins: '2.346.3' ],

  // Test the bleeding edge of the compatibility spectrum (i.e., the latest supported Java runtime).
  // see also https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/
  [ platform: 'linux', jdk: '17', jenkins: '2.361.1' ],
  // check style
  checkstyle: [qualityGates: [[threshold: 1, type: 'NEW', unstable: true]]],
  // pmd
  pmd: [trendChartType: 'TOOLS_ONLY', qualityGates: [[threshold: 1, type: 'NEW', unstable: true]]
])
