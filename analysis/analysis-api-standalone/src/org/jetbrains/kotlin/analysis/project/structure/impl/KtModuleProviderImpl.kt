/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.project.structure.impl

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiJavaFile
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.analysis.api.standalone.base.project.structure.StandaloneProjectFactory.findJvmRootsForJavaFiles
import org.jetbrains.kotlin.analysis.low.level.api.fir.project.structure.LLFirBuiltinsSessionFactory
import org.jetbrains.kotlin.analysis.project.structure.*
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.analysisContext
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments

internal class KtModuleProviderImpl(
    private val platform: TargetPlatform,
    private val project: Project,
    internal val mainModules: List<KtModule>,
) : ProjectStructureProvider() {
    private val ktNotUnderContentRootModuleWithoutPsiFile by lazy {
        KtNotUnderContentRootModuleImpl(
            name = "unnamed-outside-content-root",
            moduleDescription = "Standalone-not-under-content-root-module-without-psi-file",
            project = project,
        )
    }

    private val notUnderContentRootModuleCache = ContainerUtil.createConcurrentWeakMap<PsiFile, KtNotUnderContentRootModule>()

    private val builtinsModule: KtBuiltinsModule by lazy {
        LLFirBuiltinsSessionFactory.getInstance(project).getBuiltinsSession(platform).ktModule as KtBuiltinsModule
    }

    @OptIn(KtModuleStructureInternals::class)
    override fun getModule(element: PsiElement, contextualModule: KtModule?): KtModule {
        val containingFileAsPsiFile = element.containingFile
            ?: return ktNotUnderContentRootModuleWithoutPsiFile
        // If an [element] is created on the fly, e.g., via [KtPsiFactory],
        // its containing [PsiFile] may not have [VirtualFile].
        // We can attempt to use an associated [analysisContext] (from [KtPsiFactory]) if any.
        // If both fail, the [element] is not bound to any [KtModule], and we bail out early
        // by returning a [KtNotUnderContentRootModule] (for that specific unbound file).
        val containingFileAsVirtualFile = containingFileAsPsiFile.virtualFile
            ?: (containingFileAsPsiFile as? KtFile)?.analysisContext?.containingFile?.virtualFile
            ?: return notUnderContentRootModuleCache.getOrPut(containingFileAsPsiFile) {
                KtNotUnderContentRootModuleImpl(
                    name = containingFileAsPsiFile.name,
                    moduleDescription = "Standalone-not-under-content-root-module-for-$containingFileAsPsiFile",
                    file = containingFileAsPsiFile,
                    project = project,
                )
            }

        if (containingFileAsVirtualFile.extension == BuiltInSerializerProtocol.BUILTINS_FILE_EXTENSION) {
            return builtinsModule
        }

        containingFileAsVirtualFile.analysisExtensionFileContextModule?.let { return it }

        return mainModules.firstOrNull { module ->
            containingFileAsVirtualFile in module.contentScope
        }
            ?: throw KotlinExceptionWithAttachments("Cannot find KtModule; see the attachment for more details.")
                .withAttachment(
                    containingFileAsVirtualFile.path,
                    mainModules.joinToString(separator = System.lineSeparator()) { it.asDebugString() }
                )
    }

    internal val binaryModules: List<KtBinaryModule> by lazy {
        mainModules
            .flatMap { it.allDirectDependencies() }
            .filterIsInstance<KtBinaryModule>()
    }

    internal fun allSourceFiles(): List<PsiFileSystemItem> = buildList {
        val files = mainModules.mapNotNull { (it as? KtSourceModuleImpl)?.sourceRoots }.flatten()
        addAll(files)
        addAll(findJvmRootsForJavaFiles(files.filterIsInstance<PsiJavaFile>()))
    }
}