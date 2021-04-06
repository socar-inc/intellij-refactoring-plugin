package kr.socar.intellij.plugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import kr.socar.refactoring.runActivityRefactoring

class RefactorViewBindingActivity : AnAction() {
    override fun update(event: AnActionEvent) {
        // Set the availability based on whether a project is open
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val currentElement = event.getData(CommonDataKeys.NAVIGATABLE)

        Messages.showOkCancelDialog(
            event.project,
            "${event.presentation.text} 실행.\ncurrent element: $currentElement",
            event.presentation.description,
            "실행",
            "취소",
            Messages.getInformationIcon()
        ).let {
            if (it == Messages.OK) runActivityRefactoring(event.project!!)
        }
    }
}
