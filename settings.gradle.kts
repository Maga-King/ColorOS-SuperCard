pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ColorOS-SuperCardHost"
include(":app")
if (providers.gradleProperty("payProbe").orNull == "true") {
    include(":payProbe")
    project(":payProbe").projectDir = file("tools/pay-probe-app")
}
