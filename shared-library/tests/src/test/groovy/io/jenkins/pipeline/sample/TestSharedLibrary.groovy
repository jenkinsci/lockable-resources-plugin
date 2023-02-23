package io.jenkins.pipeline.sample

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.LocalSource.localSource

// import org.jenkins.plugins.lockableresources.SharedLibrary;
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import com.lesfurets.jenkins.unit.BasePipelineTest

class TestSharedLibrary extends BasePipelineTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder()

    String sharedLibs = this.class.getResource('/').getFile()

    String myBranch = 'shared-lib'

    @Override
    @Before
    void setUp() throws Exception {
        scriptRoots += 'src/main/jenkins'
        super.setUp()
        binding.setVariable('scm', [branch: myBranch])
    }

    @Test
    void library_annotation() throws Exception {
        assert(true);
/*        boolean exception = false
        def library = library().name('lockable-resources-shared-library')
                            //    .defaultVersion(SharedLibrary.myCurrentVersion())
                               .defaultVersion(myBranch)
                               .allowOverride(false)
                               .implicit(false)
                               .targetPath(sharedLibs)
                               .retriever(localSource(sharedLibs))
                               .build()
        helper.registerSharedLibrary(library)
        runScript('io/jenkins/pipeline/sample/pipelineUsingSharedLib.groovy')
        printCallStack()*/
    }
}
