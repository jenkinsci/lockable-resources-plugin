package org.jenkins.plugins.lockableresources.jobParameter.LockableResourcesParameterDefinition

def f = namespace(lib.FormTagLib.class)

def lr_mini_nb = 0
def lr_noAddButton = false
def lr_deleteButton = true
def lr_select_title = _("Select resource capabilities")
if(my.onlyResourceNames) {
    lr_mini_nb = 1
    lr_noAddButton = true
    lr_deleteButton = false
    lr_select_title = _("Select resource")
}

instance = my
style """.lockableResource div.repeated-container div, .lockableResource div.repeated-container div.repeated-chunk {
        display:inline-block;
        width:auto;
        }"""
f.entry(title:lr_select_title + "<br/>" + my.name, description:my.formattedDescription) {
    div(name:"parameter", class:"lockableResource") {
        input(type:"hidden", name:"name", value:my.name)
        input(type:"hidden", name:"onlyResourceNames", value:my.onlyResourceNames)
        def selecDesc = my.descriptor.getPropertyType(my, "selectedCapabilities").itemTypeDescriptorOrDie
        f.repeatable(field:"selectedCapabilities", minimum:lr_mini_nb, noAddButton:lr_noAddButton) {
            input(type:"hidden", name:"neededLabels", value:my.neededLabels)
            input(type:"hidden", name:"prohibitedLabels", value:my.prohibitedLabels)
            input(type:"hidden", name:"onlyResourceNames", value:my.onlyResourceNames)
            descriptor = selecDesc
            f.select(field:"name")
            if(lr_deleteButton) {
                f.repeatableDeleteButton()
            }
        }
        br()
        
        if(my["neededCapabilities"].size() > 0) {
            div _("Forced capabilities:")
            f.repeatable(field:"neededCapabilities", minimum:"0", noAddButton:"true") {
                f.readOnlyTextbox(field:"name")
            }
        }
        if(my["prohibitedCapabilities"].size() > 0) {
            div _("Prohibited capabilities:")
            f.repeatable(field:"prohibitedCapabilities", minimum:"0", noAddButton:"true") {
                f.readOnlyTextbox(field:"name")
            }
        }
    }
}
