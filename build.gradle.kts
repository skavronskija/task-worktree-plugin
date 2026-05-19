import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.toxa"
version = (findProperty("buildVersion") as String?)
    ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.tasks")
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
            Initial version: create git worktrees from IntelliJ tasks (works with any configured task server).
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
