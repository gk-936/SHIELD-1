// Top-level build.gradle.kts for SHIELD RanSim
plugins {
    id("com.android.application") version "8.13.2" apply false
    kotlin("android") version "2.0.21" apply false
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
