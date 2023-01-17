/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp

import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.utils.LazyResolvedConfiguration
import org.jetbrains.kotlin.gradle.utils.allResolvedDependencies
import org.jetbrains.kotlin.gradle.utils.dependencyArtifactsOrNull
import java.io.File

private typealias KotlinSourceSetName = String

internal data class SourceSetVisibilityResult(
    /**
     * Names of source sets that the consumer sees from the requested dependency.
     */
    val visibleSourceSetNames: Set<String>,

    /**
     * For some of the [visibleSourceSetNames], additional artifacts may be present that
     * the consumer should read the compiled source set metadata from.
     */
    val hostSpecificMetadataArtifactBySourceSet: Map<String, File>
)

private fun Project.collectAllPlatformCompilationData(): List<SourceSetVisibilityProvider.PlatformCompilationData> {
    val multiplatformExtension = multiplatformExtensionOrNull ?: return emptyList()
    return multiplatformExtension
        .targets
        .filter { it.platformType != KotlinPlatformType.common }
        .flatMap { target -> target.compilations.map { it.toPlatformCompilationData() } }
}

private fun KotlinCompilation<*>.toPlatformCompilationData() = SourceSetVisibilityProvider.PlatformCompilationData(
    allSourceSets = allKotlinSourceSets.map { it.name }.toSet(),
    resolvedDependenciesConfiguration = LazyResolvedConfiguration(project.configurations.getByName(compileDependencyConfigurationName)),
    hostSpecificMetadataConfiguration = project
        .configurations
        .getByName(compileDependencyConfigurationName)
        .copyRecursive().apply { attributes.attribute(Usage.USAGE_ATTRIBUTE, project.usageByName(KotlinUsages.KOTLIN_METADATA)) }
        .let(::LazyResolvedConfiguration)
)

internal class SourceSetVisibilityProvider(
    private val platformCompilations: List<PlatformCompilationData>,
) {
    constructor(project: Project) : this(
        platformCompilations = project.collectAllPlatformCompilationData()
    )

    class PlatformCompilationData(
        val allSourceSets: Set<KotlinSourceSetName>,
        val resolvedDependenciesConfiguration: LazyResolvedConfiguration,
        val hostSpecificMetadataConfiguration: LazyResolvedConfiguration?
    )

    /**
     * Determine which source sets of the [resolvedRootMppDependency] are visible in the [visibleFromSourceSet] source set.
     *
     * This requires resolving dependencies of the compilations which [visibleFromSourceSet] takes part in, in order to find which variants the
     * [resolvedRootMppDependency] got resolved to for those compilations.
     *
     * Once the variants are known, they are checked against the [dependencyProjectStructureMetadata], and the
     * source sets of the dependency are determined that are compiled for all those variants and thus should be visible here.
     *
     * If the [resolvedRootMppDependency] is a project dependency, its project should be passed as [resolvedToOtherProject], as
     * the Gradle API for dependency variants behaves differently for project dependencies and published ones.
     */
    fun getVisibleSourceSets(
        visibleFromSourceSet: KotlinSourceSetName,
        resolvedRootMppDependency: ResolvedDependencyResult,
        dependencyProjectStructureMetadata: KotlinProjectStructureMetadata,
        resolvedToOtherProject: Boolean
    ): SourceSetVisibilityResult {
        val component = resolvedRootMppDependency.selected
        val componentId = component.id

        val firstConfigurationByVariant = mutableMapOf<String, PlatformCompilationData>()

        val visiblePlatformVariantNames: Set<String?> =
            platformCompilations
                .filter { visibleFromSourceSet in it.allSourceSets }
                .mapTo(mutableSetOf()) { resolvedConfiguration ->
                    val resolvedVariant = resolvedConfiguration
                        .resolvedDependenciesConfiguration
                        .allResolvedDependencies
                        .find { it.selected.id == componentId }
                        ?.let { kotlinVariantNameFromPublishedVariantName(it.resolvedVariant.displayName) }
                        ?: return@mapTo null

                    firstConfigurationByVariant.putIfAbsent(resolvedVariant, resolvedConfiguration)
                    resolvedVariant
                }

        if (visiblePlatformVariantNames.isEmpty()) {
            return SourceSetVisibilityResult(emptySet(), emptyMap())
        }

        val visibleSourceSetNames = dependencyProjectStructureMetadata.sourceSetNamesByVariantName
            .filterKeys { it in visiblePlatformVariantNames }
            .values.let { if (it.isEmpty()) emptySet() else it.reduce { acc, item -> acc intersect item } }

        val hostSpecificArtifactBySourceSet: Map<String, File> =
            if (resolvedToOtherProject) {
                /**
                 * When a dependency resolves to a project, we don't need any artifacts from it, we can
                 * instead use the compilation outputs directly:
                 */
                emptyMap()
            } else {
                val hostSpecificSourceSets = visibleSourceSetNames.intersect(dependencyProjectStructureMetadata.hostSpecificSourceSets)

                /**
                 * As all of the variants normally contain the same metadata for each of the relevant host-specific source sets,
                 * any of the variants that we resolved can be used, so choose the first one that satisfies both:
                 *
                 *  - it contains the host-specific source set, and
                 *  - we have resolved it for some compilation
                 */
                val someVariantByHostSpecificSourceSet =
                    hostSpecificSourceSets.associate { sourceSetName ->
                        sourceSetName to dependencyProjectStructureMetadata.sourceSetNamesByVariantName
                            .filterKeys { it in firstConfigurationByVariant }
                            .filterValues { sourceSetName in it }
                            .keys.first()
                    }

                someVariantByHostSpecificSourceSet.entries.mapNotNull { (sourceSetName, variantName) ->
                    val resolvedHostSpecificMetadataConfiguration = firstConfigurationByVariant
                        .getValue(variantName)
                        .hostSpecificMetadataConfiguration
                        ?: return@mapNotNull null

                    val dependency = resolvedHostSpecificMetadataConfiguration
                        .allResolvedDependencies
                        .find { it.selected.id == componentId }
                        ?: return@mapNotNull null

                    val metadataArtifact = resolvedHostSpecificMetadataConfiguration
                        // it can happen that related host-specific metadata artifact doesn't exist
                        // for example on linux machines, then just gracefully return null
                        .dependencyArtifactsOrNull(dependency)
                        ?.singleOrNull()
                        ?: return@mapNotNull null

                    sourceSetName to metadataArtifact.file
                }.toMap()
            }

        return SourceSetVisibilityResult(
            visibleSourceSetNames,
            hostSpecificArtifactBySourceSet
        )
    }
}

internal fun kotlinVariantNameFromPublishedVariantName(resolvedToVariantName: String): String =
    originalVariantNameFromPublished(resolvedToVariantName) ?: resolvedToVariantName
