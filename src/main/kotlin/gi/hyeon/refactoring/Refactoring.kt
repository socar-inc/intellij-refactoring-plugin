package gi.hyeon.refactoring

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.elementType
import com.intellij.util.castSafelyTo
import gi.hyeon.ext.snakeToLowerCamelCase
import gi.hyeon.ext.snakeToUpperCamelCase
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.astReplace
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.referenceExpression

fun runRefactoring(project: Project) {
    project.allModules().forEach { module ->
        FilenameIndex.getAllFilesByExt(project, "kt", module.moduleScope)
            .mapNotNull { it.toPsiFile(project) as? KtFile }
            .map { ktFile ->
                println(ktFile)
                val uniquePrefix = "someExtinguishableTemporaryName_"
                val refactoredClasses = ktFile
                    .collectDescendantsOfType<KtClass>()
                    .mapNotNull {
                        it.applyActivityViewBinding(
                            project,
                            uniquePrefix,
                            "gi.hyeon"
                        )
                    }

                if (refactoredClasses.isNotEmpty()) {
                    DumbService.getInstance(project).let { service ->
                        service.runWhenSmart {
                            WriteCommandAction.runWriteCommandAction(project) {
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
    }
}

fun KtClass.applyActivityViewBinding(project: Project, uniquePrefix: String, appPrefix: String): KtClass? {
    val isBaseActivity = superTypeListEntries
        .any { it.findDescendantOfType<KtNameReferenceExpression>()?.text == "BaseActivity" }
    if (!isBaseActivity) return null

    val bindingName = getBindingName()
    println("$name: $bindingName")
    renameBindViewProperties(project, uniquePrefix)

    DumbService.getInstance(project).let { service ->
        service.runWhenSmart {
            WriteCommandAction.runWriteCommandAction(project) {
                containingFile.commitAndUnblockDocument()
                PsiDocumentManager.getInstance(project)
                    .getDocument(containingFile)
                    ?.let { document ->
                        document.setText(
                            document.text
                                .addImport("android.view.LayoutInflater")
                                .let {
                                    if (bindingName == null) it
                                    else it.addImport("$appPrefix.databinding.$bindingName")
                                        .addBinding(bindingName)
                                        .replace(
                                            "\n.*override fun getBaseLayoutId.*\n".toRegex(),
                                            "\noverride fun inflateViewBinding(layoutInflater: LayoutInflater) =\n" +
                                                    "$bindingName.inflate(layoutInflater).also { binding = it }\n"
                                        )
                                }
                                .replace(uniquePrefix, "binding.")
                        )
                    }
                println("replaced ${this.name}")
            }
        }
    }

    return this
}

fun String.addBinding(bindingName: String): String {
    if ("private lateinit var binding: $bindingName".toRegex().find(this) != null) return this
    return replace(
        "BaseActivity() {",
        "BaseActivity() {\n\nprivate lateinit var binding: $bindingName"
    )
}

fun String.addImport(classPath: String): String {
    return if ("import.*$classPath".toRegex().find(this) != null) this
    else "(package .*\n)".toRegex().replace(this) { "${it.value}import $classPath\n" }
}

fun KtClass.renameBindViewProperties(project: Project, uniquePrefix: String) {

    getBindViewTargets()
        .forEach { (property, xmlId) ->
            property.renameAllReferences(project, "$uniquePrefix${xmlId.snakeToLowerCamelCase()}")

            DumbService.getInstance(project).let { service ->
                service.runWhenSmart {
                    WriteCommandAction.runWriteCommandAction(project) {
                        property.astReplace(PsiWhiteSpaceImpl(""))
                    }
                }
            }
        }
}

fun KtClass.getBindViewTargets() = getProperties()
    .mapNotNull { property ->
        property.delegate
            ?.expression
            ?.castSafelyTo<KtCallExpression>()
            ?.takeIf { it.referenceExpression()?.text == "bindView" }
            ?.valueArgumentList
            ?.arguments
            ?.first()
            ?.getArgumentExpression()
            ?.lastChild
            ?.text
            ?.let { xmlId -> property to xmlId }
    }

fun KtClass.getBindingName() = getLayoutName()?.let { "${it.snakeToUpperCamelCase()}Binding" }

fun KtClass.getLayoutName() = body
    ?.collectChildrenByName("getBaseLayoutId")
    .orEmpty()
    .mapNotNull { it as? KtNamedFunction }
    .mapNotNull { it.bodyExpression }
    .flatMap { it.children.toList() }
    .mapNotNull { it as? KtNameReferenceExpression }
    .map { it.firstChild.text }
    .firstOrNull()

fun PsiElement.collectChildrenByName(name: String) = children
    .mapNotNull { it.namedUnwrappedElement }
    .filter { it.name == name }

fun PsiNamedElement.renameAllReferences(project: Project, newName: String) {

    // 로딩되면 기본적으로 index 가 없는 기본 편집만 가능한 상태(dumb)일 것이다.
    DumbService.getInstance(project).let { service ->
        if (service.isDumb) println(
            "[Rename] Pending until indexing finished: ${
                this.elementType
            } ${this.name} -> $newName"
        )

        // 따라서 인덱싱이 끝나면 본격적인 이름변경 작업을 요청한다.
        service.runWhenSmart {
            WriteCommandAction.runWriteCommandAction(project) {
                ReferencesSearch.search(this).forEach { it.handleElementRename(newName) }
                this.setName(newName)
                println("[Rename] ${this.elementType} ${this.name} -> $newName")
            }
        }
    }
}
