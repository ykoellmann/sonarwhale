import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.10.4"
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
}

val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
extra["isWindows"] = isWindows

val RiderPluginId: String by project
val ProductVersion: String by project
val PyCharmVersion: String by project
val PublishToken: String by project
val PythonPluginVersion: String by project

allprojects {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
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
            from("${buildDir}/distributions/${rootProject.name}-${version}.zip")
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
        rider(ProductVersion, useInstaller = false)
        jetbrainsRuntime()
    }
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("com.google.code.gson:gson:2.11.0")
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

        // IntelliJ IDEA Community — for Java / Spring Boot scanner testing.
        register("runIdeJava") {
            type = IntelliJPlatformType.IntellijIdeaCommunity
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

tasks.patchPluginXml {
    val changelogText = file("${rootDir}/CHANGELOG.md").readText()
    val changelogMatches = Regex("(?s)(-.+?)(?=##|\$)").findAll(changelogText)

    changeNotes.set(changelogMatches.map {
        it.groups[1]!!.value.replace("(?s)\r?\n".toRegex(), "<br />\n")
    }.take(1).joinToString())
}

tasks.publishPlugin {
    dependsOn(tasks.buildPlugin)
    token.set("${PublishToken}")
}