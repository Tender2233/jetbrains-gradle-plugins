import org.jetbrains.gradle.plugins.docker.JvmBaseImages

plugins {
    kotlin("jvm")
    id("org.jetbrains.gradle.docker")
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.jetbrains.gradle.docker.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-server-cio:1.6.1")
    implementation("ch.qos.logback:logback-classic:1.2.5")
    testImplementation("io.ktor:ktor-server-test-host:1.6.1")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.2")
}

tasks {
    test {
        useJUnitPlatform()
    }

}

docker {

    if (System.getenv("USE_DOCKER_REST") == "true")
        useDockerRestApi()

    registries {

        val username = System.getenv("CONTAINER_REGISTRY_USERNAME")
            ?: extra.properties["CONTAINER_REGISTRY_USERNAME"] as? String
        val password = System.getenv("CONTAINER_REGISTRY_SECRET")
            ?: extra.properties["CONTAINER_REGISTRY_SECRET"] as? String

        if (username != null && password != null)
            create("testRegistry") {
                this.username = username
                this.password = password
                url = System.getenv("CONTAINER_REGISTRY_URL")
                    ?: extra.properties["CONTAINER_REGISTRY_URL"] as? String
                            ?: error("Container registry url not defined in env")
                imageNamePrefix = System.getenv("CONTAINER_REGISTRY_IMAGE_PREFIX")
                    ?: extra.properties["CONTAINER_REGISTRY_IMAGE_PREFIX"] as? String
                            ?: error("Container registry image prefix not defined in env")
            }
    }
    images {
        dockerJvmApp {
            setupJvmApp(JvmBaseImages.OpenJDK11Slim)
        }
    }
}

val containerName = "myContainer"

task<Exec>("stopImage") {
    group = "application"
    executable = "docker"
    args = listOf("stop", containerName)
}

task<Exec>("runImage") {
    dependsOn(tasks.dockerBuild)
    executable = "docker"
    group = "application"
    args = listOf(
        "run",
        "-d",
        "-p",
        "8080:8080",
        "--name",
        containerName,
        docker.images.dockerJvmApp.get().imageNameWithTag
    )
}
