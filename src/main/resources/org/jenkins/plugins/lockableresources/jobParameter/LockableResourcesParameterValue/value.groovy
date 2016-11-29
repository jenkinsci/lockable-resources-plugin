package org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterValue

def f = namespace(lib.FormTagLib.class)

instance = my
style """.lockableResource div.repeated-container, .lockableResource div.repeated-container div.repeated-chunk {
        display:inline-block;
        width:auto;
        }"""
f.entry(title:my.name, description:my.description) {
    f.readOnlyTextbox(field:"envString")
}
