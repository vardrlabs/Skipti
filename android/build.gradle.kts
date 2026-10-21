// Root build: version pins for the whole Android tree. Every version here
// was fetched from the live registries when pinned (2026-08-26), not guessed:
//   AGP     9.3.2  latest stable line per Google's maven-metadata.xml
//                   (9.4/9.5 exist only as alphas)
// The Gradle distribution itself is pinned in gradle/wrapper/gradle-wrapper-
// properties, so `./gradlew` reproduces this build from a clean checkout.
//
// KOTLIN RIDES INSIDE AGP 9 (the plugin refused to apply: "no longer required
// since AGP 9.0": discovered live on first sync, not assumed). There is no
// org.jetbrains.kotlin.android line anywhere on purpose.
//
// One shell module only. ADR-0010's play/direct flavours are Phase 5 concerns;
// scaffolding nothing for them until there is something to flavour.
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // Kotlin compiler plugin for the ledger vault envelope (D-109). Same
    // mechanism as the compose plugin above: a Kotlin compiler plugin
    // pinned at the SAME version as AGP's built-in Kotlin (2.4.10), which
    // is what the compose plugin already proves works under AGP 9.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
