package gi.hyeon.intellij.plugin.services

import com.intellij.openapi.project.Project
import gi.hyeon.intellij.plugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
