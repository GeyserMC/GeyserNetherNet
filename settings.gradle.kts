rootProject.name = "GeyserNetherNet"

// Composite build: pull the NetherNet transport straight from its source checkout instead of
// publishing it to Maven Local. Dependency substitution maps the coordinates declared in
// build.gradle.kts (dev.kastle.netty:netty-transport-nethernet) onto the local :transport-nethernet
// project, so the version and published artifactId don't have to match anything real.
includeBuild("../NetworkCompatible") {
    dependencySubstitution {
        substitute(module("dev.kastle.netty:netty-transport-nethernet"))
            .using(project(":transport-nethernet"))
    }
}
