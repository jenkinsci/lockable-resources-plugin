/**
 * This JobDSL is used to generate jenkins jobs for the
 * example project within the lockable-resource-plugin folder.
 */

// job templates
pipelineJob('maintenance.template.new.pipelinejob') {
    displayName('Template: PipelineJob')
    description('Default Pipeline Job template.')

    logRotator {
        numToKeep(100)
        artifactNumToKeep(100)
    }
}

mavenJob('maintenance.template.new.mavenjob') {
    displayName('Template: MavenJob')
    description('Default Maven Job template.')

    jdk('java_openjdk')
    mavenInstallation('maven')
    mavenOpts('-Xms512m -Xmx1g')
    wrappers {
        timestamps()
        colorizeOutput()
    }
    logRotator {
        numToKeep(100)
        artifactNumToKeep(100)
    }
    providedGlobalSettings('4baada2f-7042-405a-bd7f-57b5a0f5751e')
    localRepository(javaposse.jobdsl.dsl.helpers.LocalRepositoryLocation.LOCAL_TO_EXECUTOR)
    publishers {
        archiveJunit('**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml') {
            allowEmptyResults(true)
            healthScaleFactor(1.0)
            retainLongStdout(true)
        }
    }
    quietPeriod(0)
    // removes default log output from maven
    runHeadless(true)
    archivingDisabled(true)
    siteArchivingDisabled(true)
    fingerprintingDisabled(true)
}

// build jobs
mavenJob('build.lrp.with.tests') {
    displayName('Lockable Resource Plugin - incl. Tests')
    description('Performs a maven build for the project, including unit tests.')
    using('maintenance.template.new.mavenjob')

    parameters {
        gitParameter {
            name('GIT_BRANCH')
            type('PT_BRANCH_TAG')
            defaultValue('master')
            description('Which branch should be build?')
            branch('master')
            branchFilter('*')
            tagFilter('*')
            sortMode('ASCENDING')
            selectedValue('DEFAULT')
            useRepository('')
            quickFilterEnabled(false)
        }
    }
    // can be transformed into a function
    scm {
        git {
            remote {
                url('https://github.com/R3d-Dragon/lockable-resources-plugin.git')
                credentials('f320cf26-b42b-4e49-b850-17a5e37e028a')
            }
            branches('$GIT_BRANCH')
            extensions {
                cleanBeforeCheckout()
                localBranch('$GIT_BRANCH')
                pruneBranches()
            }
            configure { git ->
                git / 'extensions' / 'hudson.plugins.git.extensions.impl.SparseCheckoutPaths' / 'sparseCheckoutPaths' {
                    'hudson.plugins.git.extensions.impl.SparseCheckoutPath' { path('/lockable-resource-plugin') }
                }
            }
        }
    }

    rootPOM('lockable-resource-plugin/pom.xml')
    goals('clean install')
}

mavenJob('build.lrp.without.tests') {
    displayName('Lockable Resource Plugin - No Tests')
    description('Performs a maven build for the project, without unit tests.')
    using('maintenance.template.new.mavenjob')

    parameters {
        gitParam('GIT_BRANCH') {
            branch('master')
            defaultValue('master')
            description('Which branch should be build?')
            sortMode('ASCENDING')
            tagFilter('*')
            type('BRANCH_TAG')
        }
    }

    // can be transformed into a function
    scm {
        git {
            remote {
                url('https://github.com/R3d-Dragon/lockable-resources-plugin.git')
                credentials('f320cf26-b42b-4e49-b850-17a5e37e028a')
            }
            branches('$GIT_BRANCH')
            extensions {
                cleanBeforeCheckout()
                localBranch('$GIT_BRANCH')
                pruneBranches()
            }
            configure { git ->
                git / 'extensions' / 'hudson.plugins.git.extensions.impl.SparseCheckoutPaths' / 'sparseCheckoutPaths' {
                    'hudson.plugins.git.extensions.impl.SparseCheckoutPath' { path('/lockable-resource-plugin') }
                }
            }
        }
    }

    rootPOM('lockable-resource-plugin/pom.xml')
    goals('clean install -DskipTests')
}
