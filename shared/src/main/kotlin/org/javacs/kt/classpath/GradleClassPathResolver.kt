package org.javacs.kt.classpath

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.javacs.kt.LOG
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.execAndReadStdoutAndStderr
import org.javacs.kt.util.findCommandOnPath
import org.javacs.kt.util.isOSWindows

internal class GradleClassPathResolver(
        private val path: Path,
        private val includeKotlinDSL: Boolean
) : ClassPathResolver {
    override val resolverType: String = "Gradle"
    private val projectDirectory: Path
        get() = path.parent

    override val classpath: Set<ClassPathEntry>
        get() {
            val scripts = listOf("projectClassPathFinder.gradle")
            val tasks = listOf("kotlinLSPProjectDeps")

            return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
                    .apply {
                        if (isNotEmpty())
                                LOG.info(
                                        "Successfully resolved dependencies for '${projectDirectory.fileName}' using Gradle"
                                )
                    }
                    .map { ClassPathEntry(it, null) }
                    .toSet()
        }
    override val buildScriptClasspath: Set<Path>
        get() {
            return if (includeKotlinDSL) {
                val scripts = listOf("kotlinDSLClassPathFinder.gradle")
                val tasks = listOf("kotlinLSPKotlinDSLDeps")

                return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks).apply {
                    if (isNotEmpty())
                            LOG.info(
                                    "Successfully resolved build script dependencies for '${projectDirectory.fileName}' using Gradle"
                            )
                }
            } else {
                emptySet()
            }
        }

    override val currentBuildFileVersion: Long
        get() = path.toFile().lastModified()

    companion object {
        /** Create a Gradle resolver if a file is a pom. */
        fun maybeCreate(file: Path): GradleClassPathResolver? =
                file
                        .takeIf {
                            file.endsWith("build.gradle") || file.endsWith("build.gradle.kts")
                        }
                        ?.let {
                            GradleClassPathResolver(
                                    it,
                                    includeKotlinDSL = file.toString().endsWith(".kts")
                            )
                        }
    }
}

private fun gradleScriptToTempFile(scriptName: String, deleteOnExit: Boolean = false): File {
    val config = File.createTempFile("classpath", ".gradle")
    if (deleteOnExit) {
        config.deleteOnExit()
    }

    LOG.debug("Creating temporary gradle file {}", config.absolutePath)

    config.bufferedWriter().use { configWriter ->
        GradleClassPathResolver::class
                .java
                .getResourceAsStream("/$scriptName")
                .bufferedReader()
                .use { configReader -> configReader.copyTo(configWriter) }
    }

    return config
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapperName = if (isOSWindows()) "gradlew.bat" else "gradlew"
    val wrapper = workspace.resolve(wrapperName).toAbsolutePath()
    if (Files.isExecutable(wrapper)) {
        return wrapper
    } else {
        return workspace.parent?.let(::getGradleCommand)
                ?: findCommandOnPath("gradle")
                        ?: throw KotlinLSException("Could not find 'gradle' on PATH")
    }
}

private fun readDependenciesViaGradleCLI(
        projectDirectory: Path,
        gradleScripts: List<String>,
        gradleTasks: List<String>
): Set<Path> {
    LOG.info(
            "Resolving dependencies for '{}' through Gradle's CLI using tasks {}...",
            projectDirectory.fileName,
            gradleTasks
    )

    val tmpScripts =
            gradleScripts.map {
                gradleScriptToTempFile(it, deleteOnExit = false).toPath().toAbsolutePath()
            }
    val gradle = getGradleCommand(projectDirectory)

    val command =
            listOf(gradle.toString()) +
                    tmpScripts.flatMap { listOf("-I", it.toString()) } +
                    gradleTasks +
                    listOf("--console=plain", "-Dorg.gradle.configuration-cache=false")
    val dependencies =
            findGradleCLIDependencies(command, projectDirectory)
                    ?.also { LOG.debug("Classpath for task {}", it) }
                    .orEmpty()
                    .filter {
                        it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it)
                    } // Some Gradle plugins seem to cause this to output POMs, therefore filter
                    // JARs
                    .toSet()

    tmpScripts.forEach(Files::delete)
    return dependencies
}

private fun findGradleCLIDependencies(command: List<String>, projectDirectory: Path): Set<Path>? {
    val (result, errors) = execAndReadStdoutAndStderr(command, projectDirectory)
    java.io.File("/tmp/kls_gradle_raw.txt").writeText("Command: $command\nResult:\n$result")
    java.io.File("/tmp/kls_gradle_err.txt").writeText("Errors:\n$errors")
    if ("FAILURE: Build failed" in errors) {
        LOG.warn("Gradle task failed: {}", errors)
    } else {
        for (error in errors.lines()) {
            if ("ERROR: " in error) {
                LOG.warn("Gradle error: {}", error)
            }
        }
    }
    return parseGradleCLIDependencies(result)
}

private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(?:\r?\n)".toRegex() }
private val gradleErrorWherePattern by lazy { "\\*\\s+Where:[\r\n]+(\\S\\.*)".toRegex() }

private fun parseGradleCLIDependencies(output: String): Set<Path>? {
    LOG.debug(output)
    val artifacts =
            artifactPattern
                    .findAll(output)
                    .mapNotNull { Paths.get(it.groups[1]?.value) }
                    .filterNotNull()
                    .toList()

    // Deduplicate maven dependencies
    val deduplicated = mutableMapOf<String, Path>()
    val otherPaths = mutableListOf<Path>()

    // Pattern to match gradle cache: .../files-2.1/group.id/artifact.id/version/hash/file.jar
    val gradleCacheRegex =
            Regex(""".*[/\\]files-2\.1[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\]([^/\\]+)[/\\].*""")
    // Pattern to match transformed AARs:
    // .../transforms/hash/transformed/artifact-version/jars/classes.jar
    val transformedCacheRegex =
            Regex(
                    """.*[/\\]transformed[/\\]([a-zA-Z0-9_-]+)-([0-9]+\.[0-9]+.*?[a-zA-Z0-9-]*?)(?:-api)?(?:[/\\]|\.jar).*"""
            )

    for (path in artifacts) {
        val pathStr = path.toString()
        val match = gradleCacheRegex.matchEntire(pathStr)
        if (match != null) {
            val group = match.groupValues[1]
            val artifact = match.groupValues[2]
            val version = match.groupValues[3]
            val key = "$group:$artifact"

            val existing = deduplicated[key]
            if (existing != null) {
                val existingMatch = gradleCacheRegex.matchEntire(existing.toString())
                if (existingMatch != null) {
                    val existingVersion = existingMatch.groupValues[3]
                    if (compareVersions(version, existingVersion) > 0) {
                        deduplicated[key] = path
                    }
                } else {
                    deduplicated[key] = path
                }
            } else {
                deduplicated[key] = path
            }
        } else {
            val transformedMatch = transformedCacheRegex.matchEntire(pathStr)
            if (transformedMatch != null) {
                // Transformed caches lose the group name, so group globally by artifact alone
                val artifact = transformedMatch.groupValues[1]
                val version = transformedMatch.groupValues[2]
                val key = "transformed:$artifact"

                val existing = deduplicated[key]
                if (existing != null) {
                    val existingMatch = transformedCacheRegex.matchEntire(existing.toString())
                    if (existingMatch != null) {
                        val existingVersion = existingMatch.groupValues[2]
                        if (compareVersions(version, existingVersion) > 0) {
                            deduplicated[key] = path
                        }
                    } else {
                        deduplicated[key] = path
                    }
                } else {
                    deduplicated[key] = path
                }
            } else {
                // Local project files (e.g. module build output classes) or absolute jar paths
                otherPaths.add(path)
            }
        }
    }

    return (deduplicated.values + otherPaths).toSet()
}

/** Simple semver comparison. Returns > 0 if v1 > v2. */
private fun compareVersions(v1: String, v2: String): Int {
    val parts1 = v1.split(Regex("[.-]")).mapNotNull { it.toIntOrNull() }
    val parts2 = v2.split(Regex("[.-]")).mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(parts1.size, parts2.size)) {
        val p1 = parts1.getOrNull(i) ?: 0
        val p2 = parts2.getOrNull(i) ?: 0
        if (p1 != p2) return p1.compareTo(p2)
    }
    return v1.compareTo(v2)
}
