import org.jetbrains.gradle.plugins.docker.JvmBaseImages

plugins {
    kotlin("jvm")
    id("org.jetbrains.gradle.docker")
    application
}

kotlin {
    target {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application {
    mainClass.set("org.jetbrains.gradle.docker.MainKt")
}

val ktorVersion: String by extra
val logbackVersion: String by extra
val junitVersion: String by extra

dependencies {


    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
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
