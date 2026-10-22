package com.vardrlabs.skipti

import java.io.File

/**
 * D-260 (G-13, decision 269): the app's Kotlin as a debug build compiles it. The proof surfaces moved from `src/main` to
 * `src/debug` (a release build compiles `src/release`'s stubs instead), so a guard over every app source walks both
 * folders, or it silently stops covering the proofs. The release stubs are not walked (they hold no screen); a guard
 * about `src/main` alone (what ships) reads [MAIN] itself.
 */
internal object AppSources {
    val MAIN = File("src/main/java/com/vardrlabs/skipti")
    val DEBUG = File("src/debug/java/com/vardrlabs/skipti")
    private val DIRS = listOf(MAIN, DEBUG)

    /** Every file in both folders, as `File.walkTopDown` walks one (directories included). */
    fun walk(): Sequence<File> = DIRS.asSequence().flatMap { it.walkTopDown() }

    /** [name]'s text, from src/main or (a proof, since D-260) src/debug. */
    fun read(name: String): String = File(MAIN, name).takeIf { it.isFile }?.readText() ?: File(DEBUG, name).readText()

    /** The files in both folders that pass [filter], as `File.listFiles` lists one (never null: both folders exist). */
    fun listFiles(filter: (File) -> Boolean = { true }): Array<File> {
        val all = DIRS.flatMap { dir -> checkNotNull(dir.listFiles()) { "no folder $dir" }.toList() }
        check(all.size > 50) { "the app's sources were not found (${all.size})" }
        return all.filter(filter).toTypedArray()
    }
}
