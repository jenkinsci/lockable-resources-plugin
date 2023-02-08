/*
 * See the documentation for more options:
 * https://github.com/jenkins-infra/pipeline-library/
 */
// buildPlugin(useContainerAgent: true, configurations: [
//   // Test the common case (i.e., a recent LTS release) on both Linux and Windows.
//   [ platform: 'linux', jdk: '11', jenkins: '2.361.4' ],
//   [ platform: 'windows', jdk: '11', jenkins: '2.361.4' ],
// 
//   // Test the bleeding edge of the compatibility spectrum (i.e., the latest supported Java runtime).
//   // see also https://www.jenkins.io/doc/developer/plugin-development/choosing-jenkins-baseline/
//   [ platform: 'linux', jdk: '17', jenkins: '2.361.4' ],
// ])

def configs = [
  [platform: 'linux', jdk: '11'],
  [platform: 'windows', jdk: '11'],
  [platform: 'linux', jdk: '17']
]

def stages = [failFast: true]

configs.each { c ->
  final String stageIdentifier = "${c.platform}-${c.jdk}"
  stages[stageIdentifier] = {
    testSharedLib(c, stageIdentifier)
  }
}

parallel(stages)

void testSharedLib(Map config, String stageIdentifier) {
  String platform = config.platform
  String jdk = config.jdk == null ? '11' : config.jdk
  def timeoutValue = config.timeoutValue == null ? 120 : config.timeoutValue
  String label = 'maven-' + jdk

  if (platform == 'windows') {
    label += '-windows'
  }

  timestamps() {

    node(label) {
      stage("Checkout (${stageIdentifier})") {
        infra.checkoutSCM(null)
      }

      stage('Test shared lib') {
        dir('shared-library') {
          sh 'mvn --no-transfer-progress -B clean verify'
          junit(keepLongStdio: true, testResults: 'tests/target/surefire-reports/TEST-*.xml')
        }
      }
    }
  }
}