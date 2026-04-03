plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.hunkwise"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
    }
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.hunkwise"
        name = "Hunkwise"
        version = "0.1.0"
        description = "Per-hunk accept/discard review for external file changes"
        vendor {
            name = "hunkwise"
        }
        ideaVersion {
            sinceBuild = "243"
        }
    }
}

tasks {
    test {
        useJUnit()
    }
}
