package com.github.hgijeon.intellijrefactoringplugin.services

import com.github.hgijeon.intellijrefactoringplugin.MyBundle
import com.intellij.openapi.project.Project

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
