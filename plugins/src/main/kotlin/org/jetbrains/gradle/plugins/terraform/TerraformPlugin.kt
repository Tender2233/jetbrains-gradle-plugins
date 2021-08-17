package org.jetbrains.gradle.plugins.terraform

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.distribution.plugins.DistributionPlugin
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RelativePath
import org.gradle.api.logging.LogLevel
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*
import org.jetbrains.gradle.plugins.*
import org.jetbrains.gradle.plugins.terraform.tasks.*
import java.io.File
import javax.inject.Inject

open class TerraformPlugin @Inject constructor(
    private val softwareComponentFactory: SoftwareComponentFactory
) : Plugin<Project> {

    object Attributes {

        const val USAGE = "terraform"
        const val LIBRARY_ELEMENTS = "ZIP_ARCHIVE"

        val SOURCE_SET_NAME_ATTRIBUTE: Attribute<String> = Attribute.of("terraform.sourceset.name", String::class.java)
    }

    companion object {

        const val TERRAFORM_EXTRACT_TASK_NAME = "terraformExtract"
        const val TERRAFORM_EXTENSION_NAME = "terraform"
        const val TASK_GROUP = "terraform"
    }

    override fun apply(target: Project): Unit = with(target) {

        setupTerraformRepository()

        val (lambda, terraformImplementation, terraformApi) = createConfigurations()

        val (terraformExtension, sourceSets) = createExtension()

        val (terraformInit, terraformShow, terraformDestroyShow,
            terraformPlan, terraformDestroyPlan, terraformApply,
            terraformDestroy) = registerLifecycleTasks()

        afterEvaluate {
            sourceSets.all {
                resourcesDirs.add(terraformExtension.lambdasDirectory)
            }

            val copyLambdas by tasks.registering(Sync::class) {
                from(lambda)
                into(terraformExtension.lambdasDirectory)
            }

            val terraformExtract = tasks.create<TerraformExtract>(TERRAFORM_EXTRACT_TASK_NAME) {
                configuration = generateTerraformDetachedConfiguration(terraformExtension.version)
                val executableName = evaluateTerraformName(terraformExtension.version)
                outputExecutable = File(buildDir, "terraform/$executableName")
            }

            sourceSets.forEach { sourceSet: TerraformSourceSet ->
                val taskName = sourceSet.name.capitalize()
                val terraformModuleMetadata =
                    tasks.register<GenerateTerraformMetadata>("terraform${taskName}Metadata") {
                        outputFile = file("${sourceSet.baseBuildDir}/tmp/metadata.json")
                        metadata = sourceSet.metadata
                    }
                val terraformModuleZip = tasks.create<Zip>("terraform${taskName}Module") {
                    from(terraformModuleMetadata)
                    from(sourceSet.getSourceDependencies().flatMap { it.srcDirs } + sourceSet.srcDirs) {
                        into("src/${sourceSet.metadata.group}/${sourceSet.metadata.moduleName}")
                        include { it.file.extension == "tf" || it.isDirectory }
                    }
                    from(sourceSet.getSourceDependencies().flatMap { it.resourcesDirs } + sourceSet.resourcesDirs) {
                        into("resources")
                    }
                    includeEmptyDirs = false
                    exclude { it.file.endsWith(".terraform.lock.hcl") }
                    duplicatesStrategy = DuplicatesStrategy.WARN
                    archiveFileName.set("terraform${project.name.toCamelCase().capitalize()}${taskName}.tfmodule")
                    destinationDirectory.set(file("$buildDir/terraform/archives"))
                }

                val terraformOutgoingElements =
                    configurations.create("terraform${taskName}OutgoingElements") {
                        isCanBeConsumed = true
                        extendsFrom(terraformApi)
                        outgoing.artifact(terraformModuleZip.archiveFile) {
                            builtBy(terraformModuleZip)
                        }
                        attributes {
                            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Attributes.USAGE))
                            attribute(
                                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                                objects.named(Attributes.LIBRARY_ELEMENTS)
                            )
                            attribute(Attributes.SOURCE_SET_NAME_ATTRIBUTE, sourceSet.name)
                        }
                    }

                val component = softwareComponentFactory.adhoc(buildString {
                    append("terraform")
                    if (sourceSet.name != "main") append(taskName)
                })

                components.add(component)

                component.addVariantsFromConfiguration(terraformOutgoingElements) {
                    mapToMavenScope("terraform")
                }

                if (project.plugins.has<MavenPublishPlugin>()) {
                    extensions.configure<PublishingExtension> {
                        publications {
                            create<MavenPublication>("terraform$taskName") {
                                groupId = project.group.toString()
                                artifactId = buildString {
                                    append(project.name)
                                    if (sourceSet.name != "main") append("-${sourceSet.name}")
                                }
                                version = project.version.toString()

                                from(component)
                            }
                        }
                    }
                }

                val terraformRuntimeElements =
                    configurations.create("terraform${taskName}RuntimeElements") {
                        isCanBeResolved = true
                        isCanBeConsumed = false
                        extendsFrom(terraformImplementation)
                        attributes {
                            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Attributes.USAGE))
                            attribute(
                                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                                objects.named(Attributes.LIBRARY_ELEMENTS)
                            )
                            attribute(Attributes.SOURCE_SET_NAME_ATTRIBUTE, sourceSet.name)
                        }
                    }

                val tmpRestFile = file("${sourceSet.baseBuildDir}/tmp/res.tf")

                val copyResFiles =
                    tasks.register<CopyTerraformResourceFileInModules>("copy${taskName}ResFileInExecutionContext") {
                        inputResFile = tmpRestFile
                        runtimeContextDir = sourceSet.runtimeExecutionDirectory
                    }

                val createResFile =
                    tasks.register<GenerateResourcesTerraformFile>("generate${taskName}ResFile") {
                        resourcesDirectory = sourceSet.runtimeExecutionDirectory.resolve("resources")
                        outputResourceModuleFile = tmpRestFile
                        finalizedBy(copyResFiles)
                    }

                val copyExecutionContext = tasks.register<Sync>("generate${taskName}ExecutionContext") {
                    dependsOn(terraformRuntimeElements, terraformModuleZip, copyLambdas)
                    from(zipTree(terraformModuleZip.archiveFile)) {
                        eachFile {
                            if (relativePath.segments.first() == "src") {
                                relativePath = RelativePath(
                                    true,
                                    *relativePath.segments.drop(3).toTypedArray()
                                )
                            }
                        }
                    }
                    from(terraformRuntimeElements.resolve().map { zipTree(it) }) {
                        eachFile {
                            if (relativePath.segments.first() == "src") {
                                relativePath = RelativePath(
                                    true,
                                    *relativePath.segments.drop(1).toTypedArray()
                                )
                            }
                        }
                    }
                    exclude { it.name == "metadata.json" }
                    includeEmptyDirs = false
                    from(sourceSet.lockFile)
                    into(sourceSet.runtimeExecutionDirectory)
                    finalizedBy(createResFile)
                }

                val syncLockFile = tasks.register<Copy>("sync${taskName}LockFile") {
                    from(sourceSet.runtimeExecutionDirectory.resolve(".terraform.lock.hcl"))
                    into(sourceSet.lockFile.parentFile)
                    duplicatesStrategy = DuplicatesStrategy.INCLUDE
                }

                if (plugins.has<DistributionPlugin>()) {
                    extensions.configure<DistributionContainer> {
                        maybeCreate(sourceSet.name).apply {
                            contents {
                                from(copyExecutionContext)
                                from(terraformExtract)
                            }
                        }
                    }
                }

                val tfInit: TaskProvider<TerraformInit> =
                    tasks.register<TerraformInit>("terraform${taskName}Init") {
                        dependsOn(copyExecutionContext)
                        sourcesDirectory = sourceSet.runtimeExecutionDirectory
                        dataDir = sourceSet.dataDir
                        finalizedBy(syncLockFile)
                        sourceSet.tasksProvider.initActions.executeAllOn(this)
                    }

                terraformInit.dependsOn(tfInit)
                val tfShow = tasks.create<TerraformShow>("terraform${taskName}Show") {
                    sourcesDirectory = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    inputPlanFile = sourceSet.outputBinaryPlan
                    outputJsonPlanFile = sourceSet.outputJsonPlan
                    sourceSet.tasksProvider.showActions.executeAllOn(this)
                }
                terraformShow.dependsOn(terraformShow)
                val tfPlan = tasks.register<TerraformPlan>("terraform${taskName}Plan") {
                    dependsOn(tfInit)
                    sourcesDirectory = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    outputPlanFile = sourceSet.outputBinaryPlan
                    variables = sourceSet.planVariables
                    finalizedBy(tfShow)
                    sourceSet.tasksProvider.planActions.executeAllOn(this)
                    if (terraformExtension.showPlanOutputInConsole)
                        logging.captureStandardOutput(LogLevel.LIFECYCLE)
                }
                terraformPlan.dependsOn(tfPlan)
                tfShow.dependsOn(tfPlan)

                val tfApply = tasks.register<TerraformApply>("terraform${taskName}Apply") {
                    dependsOn(tfPlan)
                    sourcesDirectory = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    planFile = sourceSet.outputBinaryPlan
                    onlyIf {
                        val canExecuteApply = terraformExtension.applySpec.isSatisfiedBy(this)
                        if (!canExecuteApply) logger.warn(
                            "Cannot execute $name. Please check " +
                                    "your terraform extension in the script."
                        )
                        canExecuteApply
                    }
                    sourceSet.tasksProvider.applyActions.executeAllOn(this)
                }
                terraformApply.dependsOn(tfApply)

                val tfDestroyShow = tasks.create<TerraformShow>("terraform${taskName}DestroyShow") {
                    inputPlanFile = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    sourcesDirectory = sourceSet.srcDirs.first()
                    outputJsonPlanFile = sourceSet.outputDestroyJsonPlan
                    sourceSet.tasksProvider.destroyShowActions.executeAllOn(this)
                }
                terraformDestroyShow.dependsOn(tfDestroyShow)
                val tfDestroyPlan = tasks.register<TerraformPlan>("terraform${taskName}DestroyPlan") {
                    dependsOn(tfInit, copyLambdas)
                    sourcesDirectory = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    outputPlanFile = sourceSet.outputDestroyBinaryPlan
                    isDestroy = true
                    variables = sourceSet.planVariables
                    finalizedBy(tfDestroyShow)
                    sourceSet.tasksProvider.destroyPlanActions.executeAllOn(this)
                    if (terraformExtension.showPlanOutputInConsole)
                        logging.captureStandardOutput(LogLevel.LIFECYCLE)
                }
                terraformDestroyPlan.dependsOn(tfDestroyPlan)
                tfDestroyShow.dependsOn(tfDestroyPlan)

                val tfDestroy = tasks.register<TerraformApply>("terraform${taskName}Destroy") {
                    dependsOn(tfDestroyPlan)
                    sourcesDirectory = sourceSet.runtimeExecutionDirectory
                    dataDir = sourceSet.dataDir
                    planFile = sourceSet.outputDestroyBinaryPlan
                    onlyIf {
                        val canExecuteApply = terraformExtension.destroySpec.isSatisfiedBy(this)
                        if (!canExecuteApply) logger.warn(
                            "Cannot execute $name. Please check " +
                                    "your terraform extension in the script."
                        )
                        canExecuteApply
                    }
                    sourceSet.tasksProvider.destroyActions.executeAllOn(this)
                }
                terraformDestroy.dependsOn(tfDestroy)
            }

        }
    }
}
