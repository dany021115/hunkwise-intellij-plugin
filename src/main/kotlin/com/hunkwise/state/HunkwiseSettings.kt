package com.hunkwise.state

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

data class HunkwiseSettings(
    @SerializedName("ignorePatterns")
    val ignorePatterns: List<String> = defaultIgnorePatterns(),
    @SerializedName("respectGitignore")
    val respectGitignore: Boolean = true,
    @SerializedName("clearOnBranchSwitch")
    val clearOnBranchSwitch: Boolean = false,
    @SerializedName("showInlineDecorations")
    val showInlineDecorations: Boolean = true
) {
    companion object {
        private val gson = Gson()

        fun defaultIgnorePatterns(): List<String> {
            val os = System.getProperty("os.name", "").lowercase()
            return if (os.contains("mac")) listOf(".git", ".DS_Store") else listOf(".git")
        }

        fun load(hunkwiseDir: String): HunkwiseSettings {
            val file = File(hunkwiseDir, "settings.json")
            return if (file.exists()) {
                try {
                    gson.fromJson(file.readText(), HunkwiseSettings::class.java)
                } catch (_: Exception) {
                    HunkwiseSettings()
                }
            } else {
                HunkwiseSettings()
            }
        }

        fun save(hunkwiseDir: String, settings: HunkwiseSettings) {
            val file = File(hunkwiseDir, "settings.json")
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(settings))
        }
    }
}
