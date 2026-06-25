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
        bundledPlugin("org.jetbrains.plugins.github")
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.4.1")
        }
    }
    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = listOf("default")
    }

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
            <h3>0.0.7</h3>
            <ul>
              <li>Refreshed the plugin description to cover base-ref selection and the
                  additional files-to-copy setting.</li>
            </ul>

            <h3>0.0.6</h3>
            <ul>
              <li>New <strong>Additional files to copy</strong> setting: copy un-tracked files
                  (e.g. <code>.env</code>, <code>config/**</code>,
                  <code>*.local.properties</code>) from the main repo working tree into every
                  new worktree. Paths are relative to the repo root, glob syntax is supported,
                  directories are copied recursively, and patterns matching nothing are skipped.</li>
            </ul>

            <h3>0.0.5</h3>
            <ul>
              <li>Branch and worktree folder names are now driven by configurable patterns
                  under <strong>Settings | Tools | Task Worktree</strong>, with variables
                  <code>${'$'}{id}</code>, <code>${'$'}{number}</code>, <code>${'$'}{type}</code>,
                  <code>${'$'}{summary}</code> and <code>${'$'}{project}</code> (both default to
                  <code>${'$'}{id}</code>).</li>
              <li>Task summaries are sanitized before use — spaces become <code>_</code> and
                  special characters are dropped.</li>
              <li>New task type remapping table lets you override resolved
                  <code>${'$'}{type}</code> values (e.g. map <code>other</code> to
                  <code>feature</code>).</li>
            </ul>

            <h3>0.0.4</h3>
            <ul>
              <li>New branches can be created from a chosen <strong>base</strong> ref — branch a new
                  worktree off e.g. <code>main</code> without switching to the main worktree first.</li>
            </ul>
            
            <h3>0.0.3</h3>
            <ul>
              <li>Existing-branch picker now lists remote branches too. Picking a remote
                  (e.g. <code>origin/feature-x</code>) creates a local tracking branch
                  from it.</li>
              <li>Custom worktree dialog auto-fills the folder name from the chosen
                  branch name (path separators replaced with <code>-</code>); stops
                  syncing once you edit the folder manually.</li>
              <li>Custom worktree dialog now opens with focus on the branch input.</li>
              <li>Internal: replaced deprecated <code>Messages.showChooseDialog</code>
                  with an inline list popup; plugin verifier now reports no deprecated
                  API usages.</li>
            </ul>

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
