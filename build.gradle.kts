plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; the Compose compiler plugin must match its Kotlin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
