#!groovy
def recentLTS = "2.249.1"
buildPlugin(configurations: [
  [ platform: "linux", jdk: "8", jenkins: null ],
  [ platform: "windows", jdk: "8", jenkins: recentLTS, javaLevel: "8" ],
  [ platform: "linux", jdk: "11", jenkins: recentLTS, javaLevel: "8" ],
])
