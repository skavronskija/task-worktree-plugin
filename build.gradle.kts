plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.toxa"

val pluginXmlVersion: String = file("src/main/resources/META-INF/plugin.xml")
    .readText()
    .let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1) }
    ?: error("<version> not found in src/main/resources/META-INF/plugin.xml")

version = (findProperty("buildVersion") as String?) ?: pluginXmlVersion

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
            <h3>0.0.1</h3>
            <ul>
              <li>Initial release.</li>
              <li>Create a worktree from any IntelliJ task, or define a custom worktree
                  on a new or existing local branch.</li>
              <li>Searchable picker with type-to-filter for both items and branches.</li>
              <li>Inline open / remove of existing worktrees; the main repo worktree is
                  protected from deletion.</li>
              <li>Optionally copies <code>.idea/</code> from the source repo into each
                  new worktree.</li>
              <li>Automatically removes the worktree's path from Recent Projects on
                  removal; bulk "Clean Up Obsolete Recent Projects" tool in settings.</li>
              <li>Global settings under <strong>Settings | Tools | Task Worktree</strong>:
                  base worktrees directory and the <code>.idea/</code> copy toggle.</li>
            </ul>
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
