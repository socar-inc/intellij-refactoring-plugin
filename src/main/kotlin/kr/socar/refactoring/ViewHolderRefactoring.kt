package kr.socar.refactoring

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.search.FilenameIndex
import kr.socar.ext.snakeToUpperCamelCase
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.getReturnTypeReference
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.astReplace
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.findFunctionByName
import org.jetbrains.plugins.groovy.lang.psi.util.backwardSiblings

fun runViewHolderRefactoring(project: Project) {
    val filesToReformat = mutableSetOf<PsiFile>()

    project.allModules().forEach { module ->
        FilenameIndex.getAllFilesByExt(project, "kt", module.moduleScope)
            .mapNotNull { it.toPsiFile(project) as? KtFile }
            .forEach { ktFile ->
                println(ktFile)
                val uniquePrefix = "someExtinguishableTemporaryName_"
                ktFile
                    .collectDescendantsOfType<KtClass>()
                    .forEach {
                        it.applyAdapterViewBinding(
                            project,
                            uniquePrefix,
                            when (module.name) {
                                "root.app" -> "socar.Socar"
                                "root.pairing-owner" -> "kr.socar.pairing.owner"
                                else -> "unexpected"
                            },
                        )?.also { filesToReformat.add(ktFile) }

                        it.applyViewHolderViewBinding(
                            project,
                            uniquePrefix,
                            when (module.name) {
                                "root.app" -> "socar.Socar"
                                "root.pairing-owner" -> "kr.socar.pairing.owner"
                                else -> "unexpected"
                            },
                        )?.also { filesToReformat.add(ktFile) }
                    }
            }
    }

    filesToReformat.forEach { reformatFile(it) }
}

fun KtClass.applyAdapterViewBinding(
    project: Project,
    uniquePrefix: String,
    appPrefix: String
): KtClass? {
    val helper = AdapterHelper.create(this) ?: return null

    whenIndexed(project) {
        helper.replaceConstructor()
        helper.replaceFunctionOnInstantiateViewHolder()
        listOf(containingFile)
    }
    renameBindViewProperties(project, uniquePrefix)

    whenIndexed(project) {
        PsiDocumentManager.getInstance(project)
            .getDocument(containingFile)
            ?.let { document ->
                val replaced = document.text
                    .addImport("kr.socar.common.recyclerview.widget.BaseBindingListAdapter")
                    .addImport("kr.socar.common.recyclerview.widget.BaseBindingViewHolder")
                    .addImport("kr.socar.common.recyclerview.widget.BlankBindingViewHolder")
                document.setText(replaced)
            }
        println("replaced ${this.name}")

        listOf(containingFile)
    }

    return this
}

class AdapterHelper private constructor(private val ktClass: KtClass) {
    val superConstructor = ktClass.getSuperTypeConstructor("BaseListAdapter")!!
    fun replaceConstructor() {
        superConstructor.findDescendantOfType<KtTypeArgumentList>()?.let {
            it.textReplace(ktClass.containingFile, "<${it.arguments[0].text}>")
        }
        superConstructor.findDescendantOfType<LeafPsiElement>()?.textReplace(ktClass.containingFile, "BaseBindingListAdapter")
    }

    fun replaceFunctionOnInstantiateViewHolder() {
        ktClass.findFunctionByName("onInstantiateViewHolder")
            ?.collectDescendantsOfType<KtNameReferenceExpression>()
            ?.filter { it.text == "BlankViewHolder" }
            ?.forEach { it.textReplace(ktClass.containingFile, "BlankBindingViewHolder") }
        ktClass.findFunctionByName("onInstantiateViewHolder")
            ?.getReturnTypeReference()
            ?.typeElement
            ?.firstChild
            ?.textReplace(ktClass.containingFile, "BaseBindingViewHolder")
    }

    companion object {
        fun create(ktClass: KtClass): AdapterHelper? =
            ktClass.getSuperTypeConstructor("BaseListAdapter")?.let { AdapterHelper(ktClass) }
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

    wrapper.replaceConstructor()
    renameBindViewProperties(project, uniquePrefix)

    whenIndexed(project) {
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

        listOf(containingFile)
    }

    return this
}

fun KtClass.getSuperTypeConstructor(name: String): KtSuperTypeListEntry? =
    superTypeListEntries.firstOrNull { it.findDescendantOfType<LeafPsiElement>()?.text == name }

class ViewHolder2KtClass(private val ktClass: KtClass) {
    val baseViewHolderConstructor by lazy {
        ktClass.getSuperTypeConstructor("BaseViewHolder2")
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
                it[1].textReplace(ktClass.containingFile, bindingName)
                it[0].textReplace(ktClass.containingFile, itemHolderType)
            }
        baseViewHolderConstructor!!.arguments[1].textReplace(ktClass.containingFile, "parent")
        baseViewHolderConstructor!!.arguments[0].textReplace(ktClass.containingFile, "$bindingName::inflate")

        ktClass.superTypeListEntries
            .firstOrNull { it.findDescendantOfType<LeafPsiElement>()?.text == "BaseViewHolder2" }
            ?.findDescendantOfType<LeafPsiElement>()
            ?.textReplace(ktClass.containingFile, "BaseBindingViewHolder")
    }
}

fun PsiElement.textReplace(file: PsiFile, text: String) {
    whenIndexed(file.project) {
        astReplace(PsiWhiteSpaceImpl(text))
        listOf(file)
    }
}