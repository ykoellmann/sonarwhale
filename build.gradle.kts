import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

buildscript {
    repositories {
        maven { url = uri("https://cache-redirector.jetbrains.com/maven-central") }
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.5.0")
    }
}

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.15.0"
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
}

val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
extra["isWindows"] = isWindows

val PluginId: String by project
val ProductVersion: String by project
val PyCharmVersion: String by project
val PublishToken: String by project
val PythonPluginVersion: String by project

allprojects {
    repositories {
        maven { url = uri("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

tasks.wrapper {
    gradleVersion = "8.8"
    distributionType = Wrapper.DistributionType.ALL
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

version = extra["PluginVersion"] as String

tasks.processResources {
    from("dependencies.json") { into("META-INF") }
}

sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
    test {
        kotlin.srcDir("src/rider/test/kotlin")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.buildPlugin {
    doLast {
        copy {
            from(layout.buildDirectory.file("distributions/${rootProject.name}-${version}.zip"))
            into("${rootDir}/output")
        }

        val changelogText = file("${rootDir}/CHANGELOG.md").readText()
        val changelogMatches = Regex("(?s)(-.+?)(?=##|$)").findAll(changelogText)
        val changeNotes = changelogMatches.map {
            it.groups[1]!!.value.replace("(?s)- ".toRegex(), "\u2022 ").replace("`", "").replace(",", "%2C").replace(";", "%3B")
        }.take(1).joinToString()
    }
}

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("ProductVersion"))
        jetbrainsRuntime()
        // JavaScript plugin — bundled in Rider und IntelliJ IDEA Ultimate.
        // Stellt NodeDebugRunConfiguration, NodeDebuggableRunProfileState,
        // NodeCommandLineUtil und CommandLineDebugConfigurator bereit.
        // NodeDebugProgramRunner.canRun() prüft: executor == "Debug" && profile instanceof NodeDebugRunConfiguration
        // — daher reicht dieses eine Plugin, kein JavaScriptDebugger-Modul nötig.
        // Optional in plugin.xml deklariert; Debug-Button wird via isNodeAvailable() ausgeblendet.
        bundledPlugin("JavaScript")
    }
    implementation("org.mozilla:rhino:1.7.15")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Language-specific run configurations — each starts a different IDE so the
// corresponding scanner can be tested end-to-end inside the right editor.
//
//   ./gradlew runIdeCSharp   → Rider          (C# / ASP.NET Core)
//   ./gradlew runIdeJava     → IntelliJ IDEA  (Java / Spring Boot)
//   ./gradlew runIdePython   → PyCharm        (Python / FastAPI / Flask)
// ---------------------------------------------------------------------------
intellijPlatformTesting {
    runIde {
        // Rider — uses the platform already declared in the dependencies block.
        register("runIdeCSharp") {
            task {
                maxHeapSize = "1500m"
            }
        }

        // IntelliJ IDEA — for Java / Spring Boot scanner testing.
        // IntelliJ IDEA Community (IC) was discontinued after 2025.2; the unified IntelliJ IDEA (IU) is used from 2025.3+.
        register("runIdeJava") {
            type = IntelliJPlatformType.IntellijIdea
            version = ProductVersion
            task {
                maxHeapSize = "1500m"
            }
        }

        // PyCharm — Python support is built-in, no extra plugin needed.
        // PyCharm Community was discontinued after 2025.2; the unified PyCharm (PY) is used from 2025.3+.
        register("runIdePython") {
            type = IntelliJPlatformType.PyCharm
            version = PyCharmVersion
            task {
                maxHeapSize = "1500m"
            }
        }
    }
}

intellijPlatform {
    buildSearchableOptions = true
}


tasks.patchPluginXml {
    val changelogText = file("${rootDir}/CHANGELOG.md").readText()
    val changelogMatches = Regex("(?s)(-.+?)(?=##|\$)").findAll(changelogText)

    changeNotes.set(changelogMatches.map {
        it.groups[1]!!.value.replace("(?s)\r?\n".toRegex(), "<br />\n")
    }.take(1).joinToString())

    // Compute release-version from PluginVersion and inject placeholders into plugin.xml.
    // release-version format: major * 10 + minor  →  "1.0.x" → 10,  "1.1.x" → 11,  "2.0.x" → 20
    // release-date comes from PluginReleaseDate in gradle.properties (set once per release).
    val pluginVer = providers.gradleProperty("PluginVersion").get()
    val parts = pluginVer.split(".")
    val computedReleaseVersion = (parts[0].toInt() * 10 + parts.getOrElse(1) { "0" }.toInt()).toString()
    val releaseDate = providers.gradleProperty("PluginReleaseDate").get()

    doLast {
        val outputXml = outputFile.get().asFile
        outputXml.writeText(
            outputXml.readText()
                .replace("RELEASE_VERSION_PLACEHOLDER", computedReleaseVersion)
                .replace("RELEASE_DATE_PLACEHOLDER", releaseDate)
        )
    }
}

tasks.signPlugin {
    certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
    privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
    password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
}

tasks.publishPlugin {
    dependsOn(tasks.signPlugin)
    token.set(providers.environmentVariable("PUBLISH_TOKEN").orElse(PublishToken))
}

// ---------------------------------------------------------------------------
// Obfuscation — produces a ProGuard-processed jar ready for release.
//   ./gradlew obfuscate
// Outputs:  build/libs/Sonarwhale-VERSION-obfuscated.jar
//           build/libs/mapping.txt  (keep for crash decoding!)
// ---------------------------------------------------------------------------
tasks.register<proguard.gradle.ProGuardTask>("obfuscate") {
    // InstrumentedJarTask is not a subtype of Jar in IPG 2.x — access via outputs.files.
    val instrTask = tasks.named("instrumentedJar")
    dependsOn(instrTask)

    // Exclude kotlin_module metadata — ProGuard 7.5.0 can't parse Kotlin 2.3.0 metadata.
    // Not needed at plugin runtime (only used by the Kotlin compiler for cross-module resolution).
    injars(mapOf("filter" to "!META-INF/*.kotlin_module"), instrTask.map { it.outputs.files })

    // IntelliJ platform + bundled libs (Rhino, Gson) — library-only, not obfuscated.
    libraryjars(configurations.compileClasspath)

    // JDK 9+ jmods: ProGuard needs java.lang.Object to resolve class hierarchies.
    // The "incorrectly named files" warning from jmod's classes/ prefix is suppressed via -ignorewarnings.
    val jmodsDir = file("${System.getProperty("java.home")}/jmods")
    if (jmodsDir.isDirectory) {
        libraryjars(
            mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
            fileTree(jmodsDir) { include("*.jmod") }
        )
    }

    configuration(file("proguard.pro"))
    outjars(layout.buildDirectory.file("libs/${rootProject.name}-${version}-obfuscated.jar"))
    printmapping(layout.buildDirectory.file("libs/mapping.txt"))

    // Replace the original instrumented JAR with the obfuscated version so that
    // prepareSandbox (and therefore buildPlugin) picks up the obfuscated output.
    doLast {
        val obfJar  = layout.buildDirectory.file("libs/${rootProject.name}-${version}-obfuscated.jar").get().asFile
        val origJar = instrTask.get().outputs.files.singleFile
        obfJar.copyTo(origJar, overwrite = true)
    }
}

// prepareSandbox copies the instrumentedJar into the plugin sandbox;
// run obfuscate first so the sandbox (and the final ZIP) contains the obfuscated JAR.
tasks.named("prepareSandbox") {
    dependsOn("obfuscate")
}