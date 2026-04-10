// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// ═══════════════════════════════════════════════════════════════
// NOTE: Add this dependency in your app-level build.gradle.kts
// (inside the app/ module, NOT this top-level file):
//
// dependencies {
//     implementation("androidx.work:work-runtime:2.9.1")
//     implementation("io.socket:socket.io-client:2.1.0")
// }
// ═══════════════════════════════════════════════════════════════