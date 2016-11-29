package org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterDefinition

def f = namespace(lib.FormTagLib.class)

style """.lockableResource div.repeated-container div, .lockableResource div.repeated-container div.repeated-chunk {
        display:inline-block;
        width:auto;
        }"""

f.entry(title:_("Variable name")) {
    f.textbox(field:"name")
}
f.entry(title:_("Default selection"), field:"selectedCapabilities") {
    div(class:"lockableResource") {
        f.repeatable(field:"selectedCapabilities", minimum:"0") {
            f.select(field:"name")
            f.repeatableDeleteButton()
        }
    }
    f.checkbox(title:_("Select only resources names"), field:"onlyResourceNames")
}
f.entry(title:_("Forced capabilities"), field:"neededCapabilities") {
    div(class:"lockableResource") {
        f.repeatable(field:"neededCapabilities", minimum:"0") {
            f.select(field:"name")
            f.repeatableDeleteButton()
        }
    }
}
f.entry(title:_("Prohibited capabilities"), field:"prohibitedCapabilities") {
    div(class:"lockableResource") {
        f.repeatable(field:"prohibitedCapabilities", minimum:"0") {
            f.select(field:"name")
            f.repeatableDeleteButton()
        }
    }
}
f.entry(title:_("Description")) {
    f.textarea(field:"description", previewEndpoint:"/markupFormatter/previewDescription")
}
