// Minimal build script used ONLY to generate the Gradle Wrapper without
// triggering Android/Kotlin plugin resolution during configuration.
//
// Usage (Windows / PowerShell):
//   .\gradle-8.7\bin\gradle.bat -b wrapper.gradle.kts wrapper --gradle-version 8.7

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.7"
    distributionType = Wrapper.DistributionType.BIN
}

