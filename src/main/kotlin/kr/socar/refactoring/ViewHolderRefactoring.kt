package kr.socar.refactoring

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.util.castSafelyTo
import kr.socar.ext.snakeToLowerCamelCase
import kr.socar.ext.snakeToUpperCamelCase
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.astReplace
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

fun runViewHolderRefactoring(project: Project) {
    project.allModules().forEach { module ->
        FilenameIndex.getAllFilesByExt(project, "kt", module.moduleScope)
            .mapNotNull { it.toPsiFile(project) as? KtFile }
            .map { ktFile ->
                println(ktFile)
                val uniquePrefix = "someExtinguishableTemporaryName_"
                val refactoredClasses = ktFile
                    .collectDescendantsOfType<KtClass>()
                    .mapNotNull {
                        it.applyViewHolderViewBinding(
                            project,
                            uniquePrefix,
                            when (module.name) {
                                "root.app" -> "socar.Socar"
                                "root.pairing-owner" -> "kr.socar.pairing.owner"
                                else -> "unexpected"
                            }
                        )
                    }

                if (refactoredClasses.isNotEmpty()) {
                    whenIndexed(project) {
                        ktFile.commitAndUnblockDocument()

                        // 파일을 새로고침해서 인덱싱을 다시 잡는다.
                        ktFile.virtualFile.refresh(false, false)
                        // 코드 정리작업.
                        OptimizeImportsProcessor(project, ktFile).run()
                        RearrangeCodeProcessor(ktFile).run()
                        ReformatCodeProcessor(ktFile, false).run()
                    }
                }
            }
    }
}

fun KtClass.applyViewHolderViewBinding(
    project: Project,
    uniquePrefix: String,
    appPrefix: String
): KtClass? {
    val isBaseActivity = superTypeListEntries
        .any { it.findDescendantOfType<KtNameReferenceExpression>()?.text == "BaseActivity" }
    if (!isBaseActivity) return null

    val bindingName = getBindingName() ?: return null
    println("apply view binding to $name: $bindingName")
    val containingFile = renameBindViewProperties(project, uniquePrefix)

    whenIndexed(project) {
        containingFile.commitAndUnblockDocument()
        PsiDocumentManager.getInstance(project)
            .getDocument(containingFile)
            ?.let { document ->
                val replaced = document.text
                    .addImport("android.view.LayoutInflater")
                    .addImport("$appPrefix.databinding.$bindingName")
                    .addBindingProperty(bindingName)
                    .replace(
                        "\n.*override fun getBaseLayoutId.*\n".toRegex(),
                        "\noverride fun inflateViewBinding(layoutInflater: LayoutInflater) =\n" +
                            "$bindingName.inflate(layoutInflater).also { binding = it }\n"
                    )
                    .replace("ButterKt.bind(this)\n", "")
                    .replace(uniquePrefix, "binding.")
                document.setText(replaced)
            }
        println("replaced ${this.name}")
    }

    return this
}
