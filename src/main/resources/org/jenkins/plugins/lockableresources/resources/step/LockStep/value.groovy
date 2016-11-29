package org.jenkins.plugins.lockableresources.resources.RequiredResources

def f = namespace(lib.FormTagLib.class)
def st = namespace("jelly:stapler")

instance = my
f.entry(title:_("Variable name")) {
    f.readOnlyTextbox(field:"variable")
}
f.entry(title:_("Lockable Resources")) {
    f.repeatable(field:"requiredResourcesList", header:_("Required resources"), noAddButton:"true") {
        table(width:"100%") {
            st.include(page:"value.groovy", class:"org.jenkins.plugins.lockableresources.resources.RequiredResources")
        }
    }
}
