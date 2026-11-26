plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

if (System.getenv("CI") == null) {
    allprojects {
        layout.buildDirectory.set(file("/tmp/n1_build/${project.name}"))
    }
}
