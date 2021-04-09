package kr.socar.refactoring

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.file.impl.FileManager
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

fun runActivityRefactoring(project: Project) {
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
                            when (module.name) {
                                "root.app" -> "socar.Socar"
                                "root.pairing-owner" -> "kr.socar.pairing.owner"
                                else -> "unexpected"
                            }
                        )
                    }

                if (refactoredClasses.isNotEmpty()) {
                    whenIndexed(project) {
                        // 파일을 새로고침해서 인덱싱을 다시 잡는다.
                        ktFile.virtualFile.refresh(false, false)
                        // 코드 정리작업.
                        OptimizeImportsProcessor(project, ktFile).run()
                        RearrangeCodeProcessor(ktFile).run()
                        ReformatCodeProcessor(ktFile, false).run()

                        listOf(ktFile)
                    }
                }
            }
    }
}

fun KtClass.applyActivityViewBinding(
    project: Project,
    uniquePrefix: String,
    appPrefix: String
): KtClass? {
    val isBaseActivity = superTypeListEntries
        .any { it.findDescendantOfType<KtNameReferenceExpression>()?.text == "BaseActivity" }
    if (!isBaseActivity) return null

    val bindingName = getBindingName() ?: return null
    println("apply view binding to $name: $bindingName")
    renameBindViewProperties(project, uniquePrefix)

    whenIndexed(project) {
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

        listOf(containingFile)
    }

    return this
}

fun String.addBindingProperty(bindingName: String): String {
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
    getBindViewProperties()
        .forEach { (property, xmlId) ->
            property.renameAllReferences(project, "$uniquePrefix${xmlId.snakeToLowerCamelCase()}")
            property.textReplace(containingFile, "")
        }
}

fun KtClass.getBindViewProperties() = getProperties()
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
            ?.let { xmlId -> BindViewProperty(property, xmlId) }
    }

data class BindViewProperty(val property: KtProperty, val xmlId: String)

fun KtClass.getBindingName() = getLayoutName()?.let { "${it.snakeToUpperCamelCase()}Binding" }
    ?: getProperties().filter { it.name == "binding" }.map {
        it.typeReference?.findDescendantOfType<LeafPsiElement>()?.text
    }.firstOrNull()

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
    whenIndexed(project) {
        val files = ReferencesSearch.search(this).map {
            it.handleElementRename(newName)
            it.resolve()?.containingFile
        }
        this.setName(newName)
        println("[Rename] ${this.elementType} ${this.name} -> $newName")
        files.plus(containingFile)
    }
}

fun whenIndexed(project: Project, action: () -> Iterable<PsiFile?>) {
    // 로딩되면 기본적으로 index 가 없는 기본 편집만 가능한 상태(dumb)일 것이다.
    DumbService.getInstance(project).runWhenSmart {
        // 따라서 인덱싱이 끝나면(smart) 작업을 수행한다.
        WriteCommandAction.runWriteCommandAction(project) {
            val filesToCommit = action()
            filesToCommit.filterNotNull().toSet().forEach { it.commitAndUnblockDocument() }
        }
    }
}

fun reformatFile(file: PsiFile) {
    // 파일을 새로고침해서 인덱싱을 다시 잡는다.
    //file.virtualFile.refresh(false, false)
    whenIndexed(file.project) {
        val docManager = PsiDocumentManager.getInstance(file.project)
        OptimizeImportsProcessor(file.project, file).run()
        docManager.getDocument(file)?.let {
            docManager.commitDocument(it)
            FileDocumentManager.getInstance().saveDocument(it)
        }
        ReformatCodeProcessor(file, false).run()
        docManager.getDocument(file)?.let {
            docManager.commitDocument(it)
            FileDocumentManager.getInstance().saveDocument(it)
        }
        RearrangeCodeProcessor(file).run()
        docManager.getDocument(file)?.let {
            docManager.commitDocument(it)
            FileDocumentManager.getInstance().saveDocument(it)
        }
        listOf(file)
    }
}
