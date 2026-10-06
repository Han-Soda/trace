plugins {
    id("com.android.application") version "9.3.3" apply false
    // AGP compiles Kotlin itself; this plugin's version also picks the Kotlin version it uses.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
