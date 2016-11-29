package org.jenkins.plugins.lockableresources.resources.RequiredResources

def f = namespace(lib.FormTagLib.class)
def st = namespace("jelly:stapler")

f.entry(title:_("Variable name")) {
    f.textbox(field:"variable")
}
f.entry(title:_("Lockable Resources")) {
    f.repeatable(field:"requiredResourcesList", header:_("Required resources"), minimum:"1", add:_("Add required resources")) {
        table(width:"100%") {
            st.include(page:"config.groovy", class:"org.jenkins.plugins.lockableresources.resources.RequiredResources")
            f.repeatableDeleteButton()
        }
    }
}
