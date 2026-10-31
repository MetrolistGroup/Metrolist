/*
 * Ultra layer: :app seam (S2).
 * Applied from the last line of app/build.gradle.kts, after android {} and dependencies {},
 * so every flavor configuration (gmsImplementation, ...) already exists when this runs.
 *
 * Script plugins get no type-safe accessors, so use string configuration names:
 *   dependencies { "implementation"(project(":ultra-wiring")) }
 */

dependencies {
    // Contracts + registry. Needed directly because the glue package (com.metrolist.music.ultra)
    // and the one-line seams call UltraHooks.
    "implementation"(project(":ultra-hooks"))
    // Hilt multibindings + the androidx.startup initializer that fills UltraHooks. Nothing in :app
    // references its classes; being on the runtime classpath is what activates it (manifest merge
    // registers the initializer, Hilt's aggregating task picks up its modules).
    "implementation"(project(":ultra-wiring"))
}

/*
 * verifyUltraSeams: guards the seams against upstream merges.
 *
 * ultra/seams.txt lists every one-line edit this fork makes inside upstream files. After a merge or
 * rebase, a conflict resolved the wrong way (or a refactor that rewrote the surrounding code) can
 * silently drop a seam, and the app would still compile, just with that feature dead. This task
 * fails the build instead, naming the seam and the file. It is wired into preBuild, so every
 * build and CI run checks it.
 */
val verifyUltraSeams = tasks.register("verifyUltraSeams") {
    group = "verification"
    description = "Fails if an upstream file lost an ULTRA-SEAM tag registered in ultra/seams.txt."

    // Locals, not script-level vals: the action must capture plain Files only. Referencing a
    // script member would capture the whole script object, which the configuration cache rejects.
    val repoRoot: File = rootProject.projectDir
    val seamsFile: File = repoRoot.resolve("ultra/seams.txt")

    doLast {
        val problems = mutableListOf<String>()
        val registered = mutableSetOf<String>()

        seamsFile.readLines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) {
                problems += "seams.txt:${index + 1}: expected '<id> <path>', got '$line'"
                return@forEachIndexed
            }
            val (id, path) = parts
            if (!registered.add(id)) {
                problems += "seams.txt:${index + 1}: seam $id is registered twice"
                return@forEachIndexed
            }

            val file = repoRoot.resolve(path)
            when {
                !file.isFile ->
                    problems += "$id: $path no longer exists. If upstream moved or renamed it, re-apply the seam in the new file and update seams.txt."
                // The trailing delimiter keeps S1 from matching S10, S11, ...
                !Regex("ULTRA-SEAM:" + Regex.escape(id) + "(?![0-9A-Za-z])").containsMatchIn(file.readText()) ->
                    problems += "$id: tag 'ULTRA-SEAM:$id' is missing from $path. A merge probably dropped it; re-apply the line (find it with: git log -S\"ULTRA-SEAM:$id\" -p)."
            }
        }

        if (registered.isEmpty()) problems += "seams.txt registers no seams; refusing to pass vacuously."

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Ultra seam check failed (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(verifyUltraSeams) }
