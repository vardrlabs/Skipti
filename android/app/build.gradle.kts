import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing credentials, loaded from OUTSIDE git (D-116): a
// gitignored keystore.properties if present. Environment variables are
// the other supported source. Nothing here generates, commits, or
// embeds a key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
val releaseSigningAvailable = keystorePropsFile.exists() ||
    System.getenv("SKIPTI_STORE_FILE") != null
if (keystorePropsFile.exists()) {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.vardrlabs.skipti"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vardrlabs.skipti"
        minSdk = 26
        targetSdk = 37

        // D-116's version scheme, stated once: versionCode =
        // major * 1_000_000 + minor * 1_000 + patch over the (MAJOR.MINOR.PATCH)
        // versionName: 0.1.0 -> 1000, 0.1.1 -> 1001, 0.2.0 -> 2000,
        // 1.0.0 -> 1_000_000. Monotonic for every grow-only bump of any
        // component, and 1.0.0 clears every 0.x code with room to spare.
        // Both flavours share the code: they are different DISTRIBUTION
        // channels of the same version, never both on one store track.
        versionCode = 0 * 1_000_000 + 5 * 1_000 + 0
        versionName = "0.5.0"

        // Matches scripts/build-android-core.sh's ABI set EXACTLY: a slice
        // built but not filtered is an untested slice; a filter the script
        // does not build is a silent packaging failure. The Makefile's
        // android-app target asserts both are inside the produced APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // One flavour, play (D-191 retired ADR-0010's direct flavour: Firebase is
    // Google, which direct existed to exclude). Its contract still holds: no
    // sensitive permission, asserted against the MERGED manifest by
    // scripts/check-play-manifest.sh. Kept as a flavour so task names, APK
    // paths and the play source set stay as they are.
    //
    // applicationId: the publication-frozen id (D-58).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
    }

    // Release signing is wired ONLY when credentials exist outside git:
    // a gitignored keystore.properties (storeFile/storePassword/
    // keyAlias/keyPassword) or the SKIPTI_* environment variables. The
    // founder creates the key; nothing here generates, commits, or
    // embeds one. Without credentials the release bundle is produced
    // UNSIGNED: the correct state for a build no one can sign yet.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(keystorePropsFile.inputStream())
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    } else if (System.getenv("SKIPTI_STORE_FILE") != null) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("SKIPTI_STORE_FILE"))
                storePassword = System.getenv("SKIPTI_STORE_PASSWORD")
                keyAlias = System.getenv("SKIPTI_KEY_ALIAS")
                keyPassword = System.getenv("SKIPTI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without a signing config Gradle produces the UNSIGNED
            // artifact (app-play-release-unsigned.aab), which is the
            // honest stop state for a build no one can sign yet.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 compiles Kotlin itself; jvmTarget follows compileOptions.
    // src/main/kotlin is on the default source set, which is how the
    // COMMITTED bindings are consumed in place: no copy exists to drift.

    // Compose for the shell screens (founder instruction: Compose, mirroring
    // iOS screen-for-screen). BOM was avoided deliberately: its artifact
    // vanished from the google maven index during this session, so each
    // androidx.compose artifact is pinned at its own current STABLE (fetched
    // live from dl.google.com metadata, not guessed): ui/foundation 1.12.0,
    // material3 1.4.0, activity-compose 1.13.0.
    buildFeatures {
        compose = true
    }

    // D-146: the licence inventory both apps show, generated by
    // scripts/licences.py into native/licences/: one file, shared with iOS.
    sourceSets {
        getByName("main") {
            assets.srcDir(rootDir.parentFile.resolve("native/licences"))
            // C2: the username blocklist, the cloud's own file (one list for all three).
            assets.srcDir(rootDir.parentFile.resolve("firebase/functions/blocklist"))
        }
    }

    // The balance screen's JVM goldens call the REAL core through the same
    // JNA bindings the app uses (no mocks): the host library built by
    // `cargo build -p skipti-core` is loaded from jna.library.path below.
    // The @aar artifact above carries only ANDROID dispatch natives, so the
    // DESKTOP packaging of the SAME dependency at the SAME version provides
    // the host loader: test scope only; the shipped APK is untouched.
    // Same-artifact/same-version justification per CONTRIBUTING rule 4;
    // recorded with the balance-screen decision in BUILD-LOG §2.
    testOptions {
        unitTests.all {
            it.systemProperty(
                "jna.library.path",
                rootDir.parentFile.resolve("target/debug").absolutePath,
            )
            // D-162: files the JVM tests read at run time are declared as
            // inputs, or Gradle reuses a cached pass after they change (a
            // sabotaged manifest passed that way). The host core library is
            // one of them: a core change must rerun the goldens.
            it.inputs.file(file("src/main/AndroidManifest.xml"))
            // C2: UsernameRulesTest reads the cloud's blocklist file.
            it.inputs.file(rootDir.parentFile.resolve("firebase/functions/blocklist/usernames.json"))
            // The Swift sources the JVM tests read (UsernameStep, DesignTokens, GroupsScreen, ThemeArt, ThemeMotion,
            // AddExpenseScreen, AccountHubScreen, People, and PaletteParityTest's scan of the whole folder): the whole
            // iOS app folder is one input, so no Swift file a test reads can change under a cached pass (D-162).
            it.inputs.dir(rootDir.parentFile.resolve("ios/Skipti/App"))
            it.inputs.dir(file("src/main/res/xml"))
            // D-235: the debug-only network config (PrivacyTest reads both halves), and core's picture bounds (SafeImage).
            it.inputs.dir(file("src/debug/res/xml"))
            it.inputs.file(rootDir.parentFile.resolve("core/src/straumr/groups.rs"))
            // D-232: AppearanceTest reads the window theme's light and dark halves.
            it.inputs.dir(file("src/main/res/values"))
            it.inputs.dir(file("src/main/res/values-night"))
            // D-215: AboutTest reads the bundled fonts (and iOS's copies of them) and hashes them.
            it.inputs.dir(file("src/main/res/font"))
            it.inputs.dir(rootDir.parentFile.resolve("native/fonts"))
            // D-215: AboutTest reads the licence inventory the Licences page shows.
            it.inputs.file(rootDir.parentFile.resolve("native/licences/licences.json"))
            // D-216: AppIconTest reads the notification glyph, the adaptive icons, every density's layers and the
            // previews (iOS's previews and icon sets are inside ios/Skipti/App, an input above), and the brand copy
            // of the glyph. The whole drawable folder, not the glyph alone: AppIconTest also asserts the bell's
            // drawing is ABSENT, and re-adding it must not pass on a cached result (the whole-branch review's M1).
            it.inputs.dir(file("src/main/res/drawable"))
            it.inputs.dir(file("src/main/res/mipmap-anydpi-v26"))
            for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
                it.inputs.dir(file("src/main/res/mipmap-$density"))
            }
            it.inputs.dir(file("src/main/res/drawable-nodpi"))
            it.inputs.file(rootDir.parentFile.resolve("brand/skipti-icon-assets/ic_stat_skipti.xml"))
            // D-165: AppearanceTest reads the Kotlin sources (chip colours).
            it.inputs.dir(file("src/main/java"))
            // D-251 review (M3): StoreShotsTest reads the debug seed and the release stub.
            it.inputs.dir(file("src/debug/java"))
            it.inputs.dir(file("src/release/java"))
            // D-193: AccountTest compares CloudProfile's fields with the rules.
            it.inputs.file(rootDir.parentFile.resolve("firebase/firestore.rules"))
            // D-196: the channel the push Function names (AccountTest).
            it.inputs.file(rootDir.parentFile.resolve("firebase/functions/push.js"))
            // D-197: the reserved usernames the phone must agree on (AccountTest).
            it.inputs.file(rootDir.parentFile.resolve("firebase/functions/people.js"))
            it.inputs.files(
                rootDir.parentFile.resolve("target/debug")
                    .listFiles { f -> f.name.startsWith("libskipti_core.") }
                    ?.toList() ?: emptyList<java.io.File>(),
            )
        }
    }
}

dependencies {
    // UniFFI-generated Kotlin rides JNA, not JNI (see uniffi/skipti_core).
    // @aar selects the Android artifact with its native dispatch bits.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Desktop packaging of the SAME JNA for JVM unit tests (see testOptions
    // above): embedded darwin/windows/linux dispatch natives, so the tests
    // can load the host libskipti_core.dylib through the app's bindings.
    testImplementation("net.java.dev.jna:jna:5.19.1")

    val compose = "1.12.0"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // kotlinx.serialization JSON for the ledger vault envelope (D-109).
    // Latest stable per Maven Central metadata, pinned not guessed; runtime
    // pairs with the 2.4.10 compiler plugin above.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // The passkey ceremony (D-118). Rule 4, taken. WHAT it does: the
    // Android platform's passkey API; CredentialManager.createCredential/
    // getCredential with the WebAuthn JSON passthrough the ceremony is
    // built on. WHO maintains it: Google's androidx, the documented
    // platform API for passkeys. RELEASE CADENCE: androidx stable
    // channel; 1.5.0 is a stable release. WHAT IT REPLACES: nothing in
    // this tree (no ceremony existed on Android); the alternatives were
    // com.google.android.gms.fido's Fido2ApiClient (GMS-coupled, the
    // deprecated path) or hand-rolling a WebAuthn ceremony client (D-30's
    // refused class). The platform API is the smallest supply chain that
    // does the job: D-114's HttpURLConnection reasoning. The runtime
    // PROVIDER artifact (credentials-play-services-auth) is deliberately
    // NOT taken: the ceremony cannot be verified without a real device,
    // a screen lock, and digital-asset-links hosting for the RP ID
    // anyway (recorded in BUILD-LOG §4); it joins with that work.
    //
    // D-133: moved to the 1.6.0 stable line because googleid 1.2.0 (below)
    // requires credentials >= 1.6.0-beta01 and would otherwise have lifted
    // this pin to a beta by dependency resolution.
    implementation("androidx.credentials:credentials:1.6.0")

    // Sign in with Google: PLAY FLAVOUR ONLY (ADR-0035 constraint 5; D-133).
    // Rule 4, taken:
    // WHAT they do: googleid provides GetSignInWithGoogleOption and
    // GoogleIdTokenCredential, the Credential Manager option for Google's
    // own "Sign in with Google" sheet. credentials-play-services-auth is the
    // Credential Manager provider that Google Play services answers through;
    // without it the option has no provider.
    // WHO maintains them: Google (androidx and Google Identity).
    // RELEASE CADENCE: androidx stable channel (1.6.0); googleid 1.2.0 is its
    // latest release.
    // WHAT THEY REPLACE: nothing. The alternative was the deprecated GMS
    // GoogleSignInClient, a larger surface on the same Play services.
    // STATED LIMIT: credentials-play-services-auth 1.6.0 pulls
    // play-services-identity-credentials 16.0.0-alpha08 at runtime. EVERY
    // release of the artifact pulls an alpha of it (1.5.0 pulled alpha02), so
    // no alpha-free set exists for this path. It also pulls play-services-auth
    // 21.1.1, play-services-fido 21.0.0 and play-services-auth-blockstore
    // 16.4.0. The merged play manifest is diffed against the pre-D-133
    // baseline so nothing that runs without a tap arrives unseen
    // (ADR-0035 constraint 1).
    // The string form: flavour configurations are created inside android {},
    // after the Kotlin DSL generates its typed accessors, so `playImplementation(...)`
    // does not resolve (first build of D-133: "Unresolved reference").
    add("playImplementation", "androidx.credentials:credentials-play-services-auth:1.6.0")
    add("playImplementation", "com.google.android.libraries.identity.googleid:googleid:1.2.0")

    // D-191: Firebase replaces the relay (the founder, 2026-10-01; design in
    // docs/superpowers/specs/2026-10-01-firebase-backend-design.md). Rule 4:
    // WHAT it does: firebase-auth signs in to the Firebase account with the
    // Google ID token Credential Manager returns; later units add Firestore,
    // Messaging and Storage from the same BoM.
    // WHO maintains it: Google (Firebase).
    // RELEASE CADENCE: the BoM ships every few weeks; 34.19.0 (2026-09-09)
    // is pinned, three weeks old when taken.
    // WHAT IT REPLACES: the relay's sign-in, sessions and refresh
    // (IdentityService), deleted with the relay in the next unit.
    // NEVER from this BoM: analytics, crashlytics, perf, config,
    // inappmessaging (GUARANTEES #1).
    // No google-services Gradle plugin: FirebaseConfig.kt sets the options in
    // code, so google-services.json (it carries the SHA-1s) stays out of git.
    // The direct flavour (ADR-0010) is retired: Firebase is Google, which
    // that flavour existed to exclude.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    // D-193: Cloud Firestore, the same BoM. WHAT it does: the account's
    // profile and phones now, its ledger and groups from units 4-5. WHO:
    // Google (Firebase). CADENCE: with the BoM. REPLACES: the relay's record
    // storage and sync. Memory cache only (Cloud in CloudProfile.kt).
    implementation("com.google.firebase:firebase-firestore")
    // D-196: Firebase Cloud Messaging, the same BoM. WHAT it does: delivers
    // the pushes the onGroupRecord Function sends ("Rahul added Dinner ·
    // ₹1,200.00" in Goa trip). WHO: Google (Firebase). CADENCE: with the BoM.
    // REPLACES: D-190's 15-minute background check (removed in D-192). Its
    // automatic start is off in the manifest, so nothing is sent without an
    // account; it brings Firebase Installations and Google's data transport.
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    // Kotlin reflection runtime for the saver's EXHAUSTIVE sweep (D-113):
    // KClass.sealedSubclasses throws KotlinReflectionNotSupportedError at
    // runtime without it (measured, not assumed: the sweep failed on a
    // bare ClassReference). JetBrains, version-locked to the compiler
    // (2.4.10), test scope only. It replaces a hand-listed subclass table
    // whose omission is the exact failure the sweep exists to prevent.
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
}

