import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

relocate("org.yaml.snakeyaml")
relocate("org.spongepowered.configurate")
relocate("com.google.gson")

val nativePlatforms = listOf(
    "windows-x86_64",
    "windows-aarch64",
    "linux-x86_64",
    "linux-aarch64",
    "macos-x86_64",
    "macos-aarch64"
)

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val version = project.version as String

val geyserVersion = "2.11.0"
val netherNetVersion = "1.8.0"
val webrtcVersion = "1.0.4"

val configurateVersion = "4.2.0-GeyserMC-20251111.004649-11"

repositories {
    // Repo for the Geyser API artifact
    maven("https://repo.opencollab.dev/main/")

    // Add other repositories here
    mavenCentral()
}

dependencies {
    // Geyser API - needed for all extensions
    compileOnly("org.geysermc.geyser:api:$geyserVersion-SNAPSHOT")

    // Geyser Core - we use things not exposed in the API
    compileOnly("org.geysermc.geyser:core:$geyserVersion-SNAPSHOT")

    // The NetherNet Netty transport
    implementation("dev.kastle.netty:netty-transport-nethernet:$netherNetVersion")

    // The WebRTC library and its natives
    implementation("dev.kastle.webrtc:webrtc-java:$webrtcVersion")
    nativePlatforms.forEach { platform ->
        runtimeOnly("dev.kastle.webrtc:webrtc-java:$webrtcVersion:$platform")
    }

    // Configurate
    annotationProcessor("org.spongepowered:configurate-extra-interface-ap:$configurateVersion")
    implementation("org.spongepowered:configurate-extra-interface:$configurateVersion")
    implementation("org.spongepowered:configurate-yaml:$configurateVersion")
}

// Java currently requires Java 21 or higher, so extensions should also target it
java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}

afterEvaluate {
    val idRegex = Regex("[a-z][a-z0-9-_]{0,63}")
    if (idRegex.matches(id).not()) {
        throw IllegalArgumentException("Invalid extension id $id! Must only contain lowercase letters, " +
                "and cannot start with a number.")
    }

    val nameRegex = Regex("^[A-Za-z_.-]+$")
    if (nameRegex.matches(extensionName).not()) {
        throw IllegalArgumentException("Invalid extension name $extensionName! Must fit regex: ${nameRegex.pattern})")
    }
}

tasks {
    // This automatically fills in the extension.yml file.
    processResources {
        filesMatching("extension.yml") {
            expand(
                "id" to id,
                "name" to extensionName,
                "api" to geyserVersion,
                "version" to version,
                "author" to author
            )
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        dependencies {
            // Exclude netty apart from the http codec
            exclude {
                it.moduleGroup == "io.netty" && (it.moduleName != "netty-codec-http" && it.moduleName != "netty-handler")
            }
        }

        archiveClassifier.set("")
        archiveVersion.set("")
    }

    build {
        dependsOn(shadowJar)
    }
}

fun Project.relocate(pattern: String) {
    tasks.named<ShadowJar>("shadowJar") {
        relocate(pattern, "org.geyser.extension.nethernet.shaded.$pattern")
    }
}
