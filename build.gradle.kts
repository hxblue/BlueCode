plugins {
    java
    application
    id("com.gradleup.shadow") version "9.4.3"
}

group = "com.bluecode"
version = "0.2.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.bluecode.bluecode")
}

dependencies {
    implementation("org.jline:jline:3.30.13")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")
    implementation("com.anthropic:anthropic-java:2.44.0")
    implementation("com.openai:openai-java:4.41.0")
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.0")
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("bluecode")
    archiveVersion.set("")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
