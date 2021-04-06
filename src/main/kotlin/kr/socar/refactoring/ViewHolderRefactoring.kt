package kr.socar.refactoring

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.search.FilenameIndex
import kr.socar.ext.snakeToUpperCamelCase
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.astReplace
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType

fun runViewHolderRefactoring(project: Project) {
    project.allModules().forEach { module ->
        FilenameIndex.getAllFilesByExt(project, "kt", module.moduleScope)
            .mapNotNull { it.toPsiFile(project) as? KtFile }
            .map { ktFile ->
                println(ktFile)
                val uniquePrefix = "someExtinguishableTemporaryName_"
                val refactoredClasses = ktFile
                    .collectDescendantsOfType<KtClass>()
                    .filter { ktClass ->
                        ktClass.superTypeListEntries.any {
                            it.findDescendantOfType<LeafPsiElement>()?.text == "BaseViewHolder2"
                        }
                    }
                    .mapNotNull {
                        it.applyViewHolderViewBinding(
                            project,
                            uniquePrefix,
                            when (module.name) {
                                "root.app" -> "socar.Socar"
                                "root.pairing-owner" -> "kr.socar.pairing.owner"
                                else -> "unexpected"
                            },
                        )
                    }
            }
    }
}

fun KtClass.applyViewHolderViewBinding(
    project: Project,
    uniquePrefix: String,
    appPrefix: String
): KtClass? {
    val wrapper = ViewHolder2KtClass(this)
    wrapper.baseViewHolderConstructor ?: return null

    val bindingName = wrapper.bindingName
    println("apply view binding to $name: $bindingName")

    whenIndexed(project) {
        wrapper.replaceConstructor()
        containingFile.commitAndUnblockDocument()
    }
    val containingFile = renameBindViewProperties(project, uniquePrefix)

    whenIndexed(project) {
        containingFile.commitAndUnblockDocument()
        PsiDocumentManager.getInstance(project)
            .getDocument(containingFile)
            ?.let { document ->
                val replaced = document.text
                    .addImport("kr.socar.common.recyclerview.widget.BaseBindingViewHolder")
                    .addImport("$appPrefix.databinding.$bindingName")
                    .replace(uniquePrefix, "binding.")
                document.setText(replaced)
            }
        println("replaced ${this.name}")
    }

    return this
}

class ViewHolder2KtClass(val ktClass: KtClass) {
    val baseViewHolderConstructor by lazy {
        ktClass.superTypeListEntries
            .firstOrNull { it.findDescendantOfType<LeafPsiElement>()?.text == "BaseViewHolder2" }
            ?.findDescendantOfType<KtValueArgumentList>()
    }

    val bindingName by lazy {
        baseViewHolderConstructor!!.arguments[1].lastChild.lastChild.text.let { "${it.snakeToUpperCamelCase()}Binding" }
    }

    fun replaceConstructor() {
        ktClass.superTypeListEntries
            .mapNotNull { it.findDescendantOfType<KtTypeArgumentList>()?.arguments }
            .firstOrNull()
            ?.let {
                val itemHolderType = it[1].text
                it[1].astReplace(PsiWhiteSpaceImpl(bindingName))
                ktClass.containingFile.commitAndUnblockDocument()
                it[0].astReplace(PsiWhiteSpaceImpl(itemHolderType))
                ktClass.containingFile.commitAndUnblockDocument()
            }
        baseViewHolderConstructor!!.arguments[1].astReplace(PsiWhiteSpaceImpl("parent"))
        ktClass.containingFile.commitAndUnblockDocument()
        baseViewHolderConstructor!!.arguments[0].astReplace(PsiWhiteSpaceImpl("$bindingName::inflate"))
        ktClass.containingFile.commitAndUnblockDocument()

        ktClass.superTypeListEntries
            .firstOrNull { it.findDescendantOfType<LeafPsiElement>()?.text == "BaseViewHolder2" }
            ?.findDescendantOfType<LeafPsiElement>()
            ?.astReplace(PsiWhiteSpaceImpl("BaseBindingViewHolder"))
    }
}