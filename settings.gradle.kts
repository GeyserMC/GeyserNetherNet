rootProject.name = "GeyserNetherNet"

// Composite build: pull the NetherNet transport straight from its source checkout instead of
// publishing it to Maven Local. Dependency substitution maps the coordinates declared in
// build.gradle.kts (org.cloudburstmc.netty:netty-transport-nethernet) onto the local :transport-nethernet
// project, so the version and published artifactId don't have to match anything real.
includeBuild(providers.gradleProperty("networkPath").getOrElse("../NetworkCompatible")) {
    dependencySubstitution {
        substitute(module("org.cloudburstmc.netty:netty-external-signalling")).using(project(":external-signalling"))
        substitute(module("org.cloudburstmc.netty:netty-transport-nethernet"))
            .using(project(":transport-nethernet"))
    }
}
