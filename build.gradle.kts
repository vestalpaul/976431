import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}

val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        localPropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}
val localBuildRootPath = localProps.getProperty("localBuildDir")?.trim()?.takeIf { it.isNotEmpty() }
    ?: System.getenv("SCHWABQUANT_LOCAL_BUILD_DIR")?.trim()?.takeIf { it.isNotEmpty() }

if (localBuildRootPath != null) {
    val localBuildRoot = File(localBuildRootPath)
    layout.buildDirectory.set(File(localBuildRoot, "root"))
    subprojects {
        layout.buildDirectory.set(File(localBuildRoot, name))
    }
}

abstract class BumpVersionTask : DefaultTask() {
    @get:Input
    abstract val bumpType: Property<String>

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val target = versionFile.get().asFile
        require(target.exists()) { "Missing version file at ${target.absolutePath}" }

        val props = Properties().apply {
            target.reader(Charsets.UTF_8).use { load(it) }
        }
        val major = props.getProperty("VERSION_MAJOR")?.trim()?.toIntOrNull()
            ?: error("Invalid VERSION_MAJOR in ${target.name}")
        val minor = props.getProperty("VERSION_MINOR")?.trim()?.toIntOrNull()
            ?: error("Invalid VERSION_MINOR in ${target.name}")
        val patch = props.getProperty("VERSION_PATCH")?.trim()?.toIntOrNull()
            ?: error("Invalid VERSION_PATCH in ${target.name}")
        val code = props.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
            ?: error("Invalid VERSION_CODE in ${target.name}")

        val oldName = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
        val mode = bumpType.get().trim().lowercase()
        val (newMajor, newMinor, newPatch) = when (mode) {
            "minor" -> Triple(major, minor + 1, 0)
            "patch" -> Triple(major, minor, patch + 1)
            "major" -> Triple(major + 1, 0, 0)
            else -> error("Unsupported -Pbump='$mode'. Use minor, patch, or major.")
        }
        val newCode = code + 1
        val newName = if (newPatch == 0) "$newMajor.$newMinor" else "$newMajor.$newMinor.$newPatch"

        val updated = buildString {
            appendLine("VERSION_MAJOR=$newMajor")
            appendLine("VERSION_MINOR=$newMinor")
            appendLine("VERSION_PATCH=$newPatch")
            appendLine("VERSION_CODE=$newCode")
        }

        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        tmpFile.writeText(updated, Charsets.UTF_8)
        try {
            Files.move(
                tmpFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmpFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        println("Bumped SchwabQuant version: V$oldName (code $code) -> V$newName (code $newCode)")
    }
}

abstract class PrintVersionTask : DefaultTask() {
    @get:InputFile
    abstract val versionFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val target = versionFile.get().asFile
        val props = Properties().apply {
            target.reader(Charsets.UTF_8).use { load(it) }
        }
        val major = props.getProperty("VERSION_MAJOR")?.trim()?.toInt() ?: 0
        val minor = props.getProperty("VERSION_MINOR")?.trim()?.toInt() ?: 0
        val patch = props.getProperty("VERSION_PATCH")?.trim()?.toInt() ?: 0
        val code = props.getProperty("VERSION_CODE")?.trim()?.toInt() ?: 1
        val name = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"
        println("SchwabQuant V$name (code $code)")
    }
}

tasks.register<BumpVersionTask>("bumpVersion") {
    group = "versioning"
    description = "Increments version.properties (-Pbump=minor|patch|major)."
    bumpType.set(providers.gradleProperty("bump").orElse("minor"))
    versionFile.set(layout.projectDirectory.file("version.properties"))
}

tasks.register<PrintVersionTask>("printVersion") {
    group = "versioning"
    description = "Prints current SchwabQuant version from version.properties."
    versionFile.set(layout.projectDirectory.file("version.properties"))
}
