import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named
import java.util.Properties

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

relocate("org.yaml.snakeyaml")
relocate("org.spongepowered.configurate")
relocate("com.google.gson")
relocate("org.bouncycastle")

// Experimental native admission currently has a tested Linux x86_64 development classifier.
val nativePlatforms = listOf("x86_64")

val networkPin = Properties().apply {
    file("registration-network.properties").inputStream().use { load(it) }
}.getProperty("commit")

val id = project.property("id") as String
val extensionName = project.property("name") as String
val author = project.property("author") as String
val version = project.version as String

val geyserVersion = "2.11.0"
val netherNetVersion = "1.8.0"
val nativePins = Properties().apply { file("native-dependencies.properties").inputStream().use { load(it) } }
val libdatachannelVersion = "0.24.5.0-dev.${nativePins.getProperty("javaCommit")}"

val configurateVersion = "4.2.0-GeyserMC-20251111.004649-11"

repositories {
    maven {
        url = uri(providers.gradleProperty("nativeMavenRepository").getOrElse("../artifacts/maven"))
        content { includeGroup("io.github.teamziax") }
    }
    mavenLocal { content { includeGroup("io.github.teamziax") } }
    // Repo for the Geyser API artifact
    maven("https://repo.opencollab.dev/main/")

    // Add other repositories here
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.geysermc.geyser:core:$geyserVersion-SNAPSHOT")
    // Geyser API - needed for all extensions
    compileOnly("org.geysermc.geyser:api:$geyserVersion-SNAPSHOT")

    // Geyser Core - we use things not exposed in the API
    compileOnly("org.geysermc.geyser:core:$geyserVersion-SNAPSHOT")

    implementation("org.cloudburstmc.netty:netty-external-signalling:1.1.0.CR1-SNAPSHOT")

    // The NetherNet Netty transport
    implementation("org.cloudburstmc.netty:netty-transport-nethernet:$netherNetVersion")

    // The WebRTC library and its natives
    implementation("io.github.teamziax:libdatachannel-java:$libdatachannelVersion")
    nativePlatforms.forEach { platform ->
        runtimeOnly("io.github.teamziax:libdatachannel-java:$libdatachannelVersion:$platform")
    }

    // Build host-owned DTLS certificates using public JCA and X.509 APIs.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

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

tasks.test { useJUnitPlatform() }

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
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        mergeServiceFiles()
        val extensionRevision = providers.gradleProperty("registrationRevision").getOrElse("local-development")
        manifest.attributes["Registration-Revision"] = extensionRevision
        manifest.attributes["Extension-Revision"] = extensionRevision
        manifest.attributes["Registration-Network-Revision"] = networkPin
        manifest.attributes["Native-Network-Revision"] = networkPin
        manifest.attributes["Native-JNI-Revision"] = nativePins.getProperty("javaCommit")
        manifest.attributes["Native-Datachannel-Revision"] = nativePins.getProperty("datachannelCommit")
        manifest.attributes["Native-Juice-Revision"] = nativePins.getProperty("juiceCommit")
        dependencies {
            // Exclude netty apart from the http codec
            exclude {
                it.moduleGroup == "io.netty" && (it.moduleName != "netty-codec-http" && it.moduleName != "netty-handler")
            }

            exclude {
                it.moduleGroup == "org.slf4j"
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
