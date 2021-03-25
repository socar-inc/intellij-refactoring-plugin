package kr.socar.intellij.plugin.services

import com.intellij.openapi.project.Project
import kr.socar.intellij.plugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
