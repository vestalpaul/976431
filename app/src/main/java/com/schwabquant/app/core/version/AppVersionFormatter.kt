package com.schwabquant.app.core.version

import java.util.Properties

enum class VersionBumpType {
    MINOR,
    PATCH,
    MAJOR
}

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val code: Int
) {
    init {
        require(major >= 0) { "VERSION_MAJOR must be >= 0" }
        require(minor >= 0) { "VERSION_MINOR must be >= 0" }
        require(patch >= 0) { "VERSION_PATCH must be >= 0" }
        require(code >= 1) { "VERSION_CODE must be >= 1" }
    }

    val versionName: String
        get() = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

    val versionLabel: String
        get() = "V$versionName"

    val displayLabel: String
        get() = "$versionLabel (Build $code)"

    fun bump(type: VersionBumpType): AppVersion = when (type) {
        VersionBumpType.MINOR -> copy(
            minor = minor + 1,
            patch = 0,
            code = code + 1
        )

        VersionBumpType.PATCH -> copy(
            patch = patch + 1,
            code = code + 1
        )

        VersionBumpType.MAJOR -> copy(
            major = major + 1,
            minor = 0,
            patch = 0,
            code = code + 1
        )
    }

    fun toPropertiesString(): String = buildString {
        appendLine("VERSION_MAJOR=$major")
        appendLine("VERSION_MINOR=$minor")
        appendLine("VERSION_PATCH=$patch")
        appendLine("VERSION_CODE=$code")
    }
}

object AppVersionFormatter {

    fun parse(propertiesText: String): AppVersion {
        val props = Properties().apply {
            load(propertiesText.reader())
        }
        val major = props.getProperty("VERSION_MAJOR")?.trim()?.toIntOrNull()
            ?: error("Missing or invalid VERSION_MAJOR in version.properties")
        val minor = props.getProperty("VERSION_MINOR")?.trim()?.toIntOrNull()
            ?: error("Missing or invalid VERSION_MINOR in version.properties")
        val patch = props.getProperty("VERSION_PATCH")?.trim()?.toIntOrNull()
            ?: error("Missing or invalid VERSION_PATCH in version.properties")
        val code = props.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
            ?: error("Missing or invalid VERSION_CODE in version.properties")
        return AppVersion(major = major, minor = minor, patch = patch, code = code)
    }

    fun parseBumpType(raw: String?): VersionBumpType = when (raw?.trim()?.lowercase()) {
        null, "", "minor" -> VersionBumpType.MINOR
        "patch" -> VersionBumpType.PATCH
        "major" -> VersionBumpType.MAJOR
        else -> error("Unsupported bump type '$raw'. Expected minor, patch, or major.")
    }
}
