package app.revanced.patches.gradle

import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.android.tools.r8.utils.ArchiveResourceProvider
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.MavenPublishPlugin
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownProjectException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.AbiValidationExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val REVANCED_CLI_URL =
    "https://github.com/ReVanced/revanced-cli/releases/download/v6.0.0/revanced-cli-6.0.0-all.jar"
private const val REVANCED_CLI_SHA256 =
    "c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958"

abstract class PatchesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("patches", PatchesExtension::class.java)

        project.applyPlugins()
        project.configureJava()
        project.configureKotlin()
        project.configureDependencies()
        project.configureConsumeExtensions(extension)
        project.configureJarTask(extension)
        project.configurePublishing(extension)

    }

    private fun Project.applyPlugins() {
        pluginManager.apply {
            apply(KotlinPluginWrapper::class.java)
            apply(MavenPublishPlugin::class.java)
        }
    }

    /**
     * Adds the dependencies ReVanced Patcher and SMALI to the project.
     * The versions are fetched from the version catalog by the respective project.
     */
    private fun Project.configureDependencies() {
        afterEvaluate {
            val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            // GitHub requires a PAT even to read ReVanced's public Maven packages.
            // The public ReVanced CLI fat JAR contains the exact Patcher 22.0.0
            // classes needed by this source tree, so use it as the compile classpath.
            val cliJar = rootProject.layout.projectDirectory
                .file(".gradle/revanced-cli-6.0.0-all.jar")
            val downloadCli = rootProject.tasks.register("downloadReVancedCli") { task ->
                task.description = "Downloads the public ReVanced CLI compile classpath."
                task.outputs.file(cliJar)
                task.outputs.upToDateWhen {
                    cliJar.asFile.exists() && sha256(cliJar.asFile) == REVANCED_CLI_SHA256
                }

                task.doLast {
                    val target = cliJar.asFile
                    target.parentFile.mkdirs()

                    if (target.exists() && sha256(target) == REVANCED_CLI_SHA256) {
                        return@doLast
                    }

                    val temporary = target.resolveSibling(target.name + ".part")
                    temporary.delete()
                    URI(REVANCED_CLI_URL).toURL().openStream().use { input ->
                        temporary.outputStream().use(input::copyTo)
                    }

                    check(sha256(temporary) == REVANCED_CLI_SHA256) {
                        "Downloaded ReVanced CLI checksum does not match"
                    }
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }

            val cliClasspath = files(cliJar).builtBy(downloadCli)
            dependencies.add("implementation", cliClasspath)

            operator fun String.invoke(versionAlias: String) = dependencies.add(
                "implementation",
                "$this:" + catalog.findVersion(versionAlias).orElseThrow {
                    IllegalArgumentException("Version with alias $versionAlias not found in version catalog")
                },
            )

            "com.android.tools.smali:smali"("smali")
        }
    }

    /**
     * Configures the Java plugin with Java 17 as the target because Java 17 is the target on Android.
     * Additionally, adds sources and javadoc JARs, as patches have a public API.
     */
    private fun Project.configureJava() {
        extensions.configure<JavaPluginExtension>("java") {
            it.targetCompatibility = JavaVersion.VERSION_17
        }
    }


    /**
     * Configures the Kotlin plugin with JVM 17 as the target because JVM 17 is the target on Android.
     */
    @OptIn(ExperimentalAbiValidation::class)
    private fun Project.configureKotlin() {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            it.extensions.configure<AbiValidationExtension>("abiValidation") { extension ->
                extension.enabled.set(true)
            }

            it.compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexplicit-backing-fields",
                    "-Xcontext-parameters"
                )

                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    /**
     * Adds a task to build the DEX file of the patches and adds it to the patches file to use on Android,
     * adds the publishing plugin to the project to publish the patches API and
     * configures the publication with the "about" information from the extension.
     */
    private fun Project.configurePublishing(patchesExtension: PatchesExtension) {
        val buildAndroid = tasks.register("buildAndroid") { task ->
            task.description =
                "Builds the project for Android by compiling to DEX and adding it to the patches file."
            task.group = "build"

            task.dependsOn(tasks["jar"])

            task.doLast {
                val workingDirectory =
                    layout.buildDirectory.dir("revanced").get().asFile.also(File::mkdirs)

                val patchesFile = tasks["jar"].outputs.files.first()
                val classesZipFile = workingDirectory.resolve("classes.zip")

                D8Command.builder()
                    .addProgramResourceProvider(
                        ArchiveResourceProvider.fromArchive(
                            patchesFile.toPath(),
                            true
                        )
                    )
                    .setMode(CompilationMode.RELEASE)
                    .setOutput(classesZipFile.toPath(), OutputMode.DexIndexed)
                    .setMinApiLevel(27)
                    .build()
                    .let(D8::run)

                ZFile.openReadWrite(patchesFile).use { zFile ->
                    zFile.mergeFrom(ZFile.openReadOnly(classesZipFile)) { false }
                }
            }
        }

        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") { extension ->
            extensions.configure<PublishingExtension>("publishing") { publishingExtension ->
                // Necessary for the signing plugin for a publication to be created
                // and the signing plugin to sign the publication, when no repositories are defined.
                publishingExtension.repositories.mavenLocal {
                    it.name = "DummyMavenLocal"
                }
            }

            extension.signAllPublications()
            extensions.getByType<SigningExtension>().useGpgCmd()

            extension.pom { pom ->
                val about = patchesExtension.about

                pom.name.set(about.name)
                pom.description.set(about.description)
                pom.url.set(about.website)

                pom.licenses { licenses ->
                    licenses.license { license ->
                        license.name.set(about.license)
                    }
                }
                pom.developers { developers ->
                    developers.developer { developer ->
                        developer.name.set(about.author)
                        developer.email.set(about.contact)
                    }
                }
                pom.scm { scm ->
                    scm.url.set(about.source)
                }
            }

            afterEvaluate {
                extension.coordinates(group.toString(), name, version.toString())
            }
        }

        // Used by gradle-semantic-release-plugin.
        // Tracking: https://github.com/KengoTODA/gradle-semantic-release-plugin/issues/435
        tasks["publish"].apply {
            dependsOn(buildAndroid)
        }
    }

    /**
     * Configures the project to consume the extension artifacts and add them to the resources of the patches project.
     */
    private fun Project.configureConsumeExtensions(patchesExtension: PatchesExtension) {
        val extensionsProject = try {
            project(patchesExtension.extensionsProjectPath ?: return)
        } catch (_: UnknownProjectException) {
            return
        }

        val extensionProjects = extensionsProject.subprojects.filter { extensionProject ->
            extensionProject.plugins.hasPlugin(ExtensionPlugin::class.java)
        }

        val extensionsDependencyScopeConfiguration =
            configurations.dependencyScope("extensionsDependencyScope").get()
        val extensionsConfiguration = configurations.resolvable("extensionConfiguration").apply {
            configure { it.extendsFrom(extensionsDependencyScopeConfiguration) }
        }

        project.dependencies.apply {
            extensionProjects.forEach { extensionProject ->
                add(
                    extensionsDependencyScopeConfiguration.name,
                    project(
                        mapOf(
                            "path" to extensionProject.path,
                            "configuration" to "extensionConfiguration",
                        ),
                    ),
                )
            }
        }

        extensions.configure<SourceSetContainer>("sourceSets") { sources ->
            sources.named("main") { main ->
                main.resources.srcDir(extensionsConfiguration)
            }
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Configure the manifest file with the "about" information from the extension.
 */
private fun Project.configureJarTask(patchesExtension: PatchesExtension) {
    tasks.withType(Jar::class.java).configureEach {
        it.archiveExtension.set("rvp")
        it.manifest.apply {
            attributes["Name"] = patchesExtension.about.name
            attributes["Description"] = patchesExtension.about.description
            attributes["Version"] = project.version.toString()
            attributes["Timestamp"] = System.currentTimeMillis().toString()
            attributes["Source"] = patchesExtension.about.source
            attributes["Author"] = patchesExtension.about.author
            attributes["Contact"] = patchesExtension.about.contact
            attributes["Website"] = patchesExtension.about.website
            attributes["License"] = patchesExtension.about.license
        }
    }
}
