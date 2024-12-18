package sv.truestudio.app.superversion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class DependencyInfo(
    val group: String,
    val name: String,
    val currentVersion: String
)

class VersionCheckerAction : AnAction() {
    private val versionKeyCache = mutableMapOf<String, String>()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (!virtualFile.name.endsWith(".toml")) {
            Messages.showErrorDialog(project, "Please open a TOML file", "Error")
            return
        }

        editor.markupModel.removeAllHighlighters()
        val content = String(virtualFile.contentsToByteArray())

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking library versions") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    println("Starting version check")
                    indicator.text = "Parsing TOML file..."

                    val libraries = getLibraryDependencies(content)
                    println("Found ${libraries.size} libraries")

                    indicator.isIndeterminate = false
                    var processed = 0

                    libraries.forEach { (name, dependency) ->
                        indicator.fraction = processed.toDouble() / libraries.size
                        indicator.text = "Checking ${dependency.group}:${dependency.name}..."

                        try {
                            val latestVersion = checkLatestVersion(dependency)
                            if (latestVersion != null) {
                                println("Found latest version for ${dependency.name}: $latestVersion")
                                addVersionAnnotation(editor, name, dependency, latestVersion)
                            }
                        } catch (e: Exception) {
                            println("Error checking version for $name: ${e.message}")
                        }
                        processed++
                    }
                } catch (e: Exception) {
                    println("Error during version check: ${e.message}")
                    invokeLater {
                        Messages.showErrorDialog(project, "Error checking versions: ${e.message}", "Error")
                    }
                }
            }
        })
    }

    private fun getLibraryDependencies(content: String): Map<String, DependencyInfo> {
        val versions = mutableMapOf<String, String>()
        val libraries = mutableMapOf<String, DependencyInfo>()
        var currentSection = ""

        // Thanks to GPT
        val moduleRegex = "module\\s*=\\s*['\"]([^'\"]+)['\"]".toRegex()
        val versionRefRegex = "version\\.ref\\s*=\\s*['\"]([^'\"]+)['\"]".toRegex()

        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            when {
                trimmedLine == "[versions]" -> {
                    currentSection = "versions"
                    println("Entering [versions] section")
                }

                trimmedLine == "[libraries]" -> {
                    currentSection = "libraries"
                    println("Entering [libraries] section")
                }
                // TODO: Add support for `[plugins]` in future
                trimmedLine.startsWith("[") -> currentSection = ""

                currentSection == "libraries" && trimmedLine.contains("=") && !trimmedLine.startsWith("#") -> {
                    println("\nParsing library line: $trimmedLine")
                    try {
                        val (key, value) = trimmedLine.split("=", limit = 2).map { it.trim() }
                        val moduleMatch = moduleRegex.find(value)
                        val versionRef = versionRefRegex.find(value)?.groupValues?.get(1)

                        if (moduleMatch != null && versionRef != null && versions.containsKey(versionRef)) {
                            val fullModule = moduleMatch.groupValues[1]
                            val lastColonIndex = fullModule.lastIndexOf(':')
                            if (lastColonIndex != -1) {
                                val group = fullModule.substring(0, lastColonIndex)
                                val name = fullModule.substring(lastColonIndex + 1)
                                libraries[key] = DependencyInfo(group, name, versions[versionRef]!!)
                                // Cache the version key so we can go back and highlight it
                                versionKeyCache[key] = versionRef
                                println("✅ Cached version key: $key -> $versionRef")
                            }
                        } else {
                            val group = "group\\s*=\\s*\"([^\"]+)\"".toRegex().find(value)?.groupValues?.get(1)
                            val name = "name\\s*=\\s*\"([^\"]+)\"".toRegex().find(value)?.groupValues?.get(1)

                            if (group != null && name != null && versionRef != null && versions.containsKey(versionRef)) {
                                libraries[key] = DependencyInfo(group, name, versions[versionRef]!!)
                                // Cache the version key
                                versionKeyCache[key] = versionRef
                                println("✅ Cached version key: $key -> $versionRef")
                            }
                        }
                    } catch (e: Exception) {
                        println("❌ Failed to parse library line: ${e.message}")
                    }
                }

                currentSection == "versions" && trimmedLine.contains("=") && !trimmedLine.startsWith("#") -> {
                    val (key, value) = trimmedLine.split("=", limit = 2)
                    versions[key.trim()] = value.trim().removeSurrounding("\"").removeSurrounding("'")
                    println("Found version: ${key.trim()} = ${versions[key.trim()]}")
                }
            }
        }

        return libraries
    }

    private fun checkLatestVersion(dependency: DependencyInfo): String? {
        try {
            val repositories = listOf(
                MAVEN_CENTRAL_BASE,
                GOOGLE_MAVEN_BASE,
                "https://plugins.gradle.org/m2"
            )

            for (baseUrl in repositories) {
                try {
                    val groupPath = dependency.group.replace('.', '/')
                    val metadataUrl = "$baseUrl/$groupPath/${dependency.name}/maven-metadata.xml"
                    println("Checking repository: $metadataUrl")

                    val connection = URL(metadataUrl).openConnection() as HttpURLConnection
                    connection.apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "IntelliJ-Version-Checker")
                    }

                    if (connection.responseCode == 200) {
                        println("✅ Success - Repository responded for ${dependency.group}:${dependency.name}")

                        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        val doc = docBuilder.parse(connection.inputStream)

                        val versionsNodeList = doc.getElementsByTagName("version")

                        val versions = (0 until versionsNodeList.length)
                            .map { versionsNodeList.item(it).textContent }
                            .filter { version ->
                                // Filter stable version unless the key contains any of the pre-release tags
                                val userOptedForPreRelease = preReleaseTags.any { dependency.currentVersion.contains(it, ignoreCase = true) }

                                if (userOptedForPreRelease) {
                                    preReleaseTags.any { version.contains(it, ignoreCase = true) }
                                } else {
                                    preReleaseTags.none { version.contains(it, ignoreCase = true) }
                                }
                            }

                        println("Filtered out stable version - $versions")

                        if (versions.isNotEmpty()) {
                            println("Found ${versions.size} valid versions: ${versions.joinToString()}")
                            return versions.maxWithOrNull { v1, v2 ->
                                val parts1 = v1.split(".", "-", "alpha", "beta", "rc", "snapshot").map { it.toIntOrNull() ?: 0 }
                                val parts2 = v2.split(".", "-", "alpha", "beta", "rc", "snapshot").map { it.toIntOrNull() ?: 0 }
                                // TODO: Can we just get the last index instead of comparing? Anyway
                                compareVersionParts(parts1, parts2)
                            }
                        }
                    } else {
                        println("❌ Failed - Repository returned ${connection.responseCode} for ${dependency.group}:${dependency.name}")
                    }
                } catch (e: Exception) {
                    println("❌ Error - Repository $baseUrl failed for ${dependency.group}:${dependency.name}: ${e.message}")
                    continue
                }
            }
            return null
        } catch (e: Exception) {
            println("❌ Critical Error - Failed to check version for ${dependency.group}:${dependency.name}: ${e.message}")
            return null
        }
    }

    // TODO: Use Toml parser in future
    private fun addVersionAnnotation(editor: Editor, name: String, dependency: DependencyInfo, latestVersion: String) {
        invokeLater {
            try {
                val document = editor.document
                val text = document.text
                val lines = text.split("\n")

                val versionKey = versionKeyCache[name] ?: name
                println("Finding - version line with key: '$versionKey' for library: $name")

                val lineIndex = lines.indexOfFirst {
                    it.trim().startsWith("$versionKey = ") && it.contains(dependency.currentVersion)
                }

                println("Found line: $lineIndex index")
                if (lineIndex >= 0) {
                    println("Processing line: ${lines[lineIndex]}")
                    val startOffset = document.getLineEndOffset(lineIndex)
                    val isUpToDate = compareVersions(dependency.currentVersion, latestVersion)
                    val symbol = if (isUpToDate) "✅" else "❌"
                    val inlayText = " $symbol ${if (!isUpToDate) latestVersion else ""}"

                    editor.markupModel.addRangeHighlighter(
                        startOffset,
                        startOffset,
                        HighlighterLayer.LAST,
                        TextAttributes(),
                        HighlighterTargetArea.EXACT_RANGE
                    ).apply {
                        customRenderer =
                            CustomHighlighterRenderer { editor, highlighter, g ->
                                val point =
                                    editor.visualPositionToXY(editor.offsetToVisualPosition(highlighter.startOffset))
                                g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                                g.color = if (isUpToDate) JBColor(
                                    JBColor.GREEN.darker(),
                                    JBColor.GREEN.brighter()
                                ) else JBColor(
                                    JBColor.RED.darker(),
                                    JBColor.RED.brighter()
                                )
                                g.drawString(inlayText, point.x, point.y + editor.ascent)
                            }
                    }
                } else {
                    println("❌ No matching line found in document")
                }
            } catch (e: Exception) {
                println("❌ Error while adding annotation: ${e.message}")
            }
        }
    }

    private fun compareVersionParts(v1: List<Int>, v2: List<Int>): Int {
        for (i in 0 until maxOf(v1.size, v2.size)) {
            val part1 = v1.getOrNull(i) ?: 0
            val part2 = v2.getOrNull(i) ?: 0
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }

    private fun compareVersions(current: String, latest: String): Boolean {
        val currentParts = current.split(".", "-", "alpha", "beta", "rc", "snapshot").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".", "-", "alpha", "beta", "rc", "snapshot").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
            val currentPart = currentParts.getOrNull(i) ?: 0
            val latestPart = latestParts.getOrNull(i) ?: 0

            if (currentPart < latestPart) return false
            if (currentPart > latestPart) return true
        }

        return true
    }

    companion object {
        private const val MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2"
        private const val GOOGLE_MAVEN_BASE = "https://dl.google.com/dl/android/maven2"
        private val preReleaseTags = listOf("alpha", "beta", "rc", "snapshot")
    }
}