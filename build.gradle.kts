plugins {
    java
}

group = "dev.sccl.pierdolauth"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
    maven(url = "https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    testImplementation("org.yaml:snakeyaml:2.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// direct adapter tests compile against the same pinned
sourceSets.test {
    compileClasspath += sourceSets.main.get().compileClasspath
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("pierdolAuthJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("pierdolAuthVersion", project.version.toString())
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("pierdol-auth-paper")
    manifest.attributes["Implementation-Version"] = project.version
    manifest.attributes["Multi-Release"] = "true"
    //pPaper supplies compileOnly deps
    from(configurations.runtimeClasspath.map { files -> files.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "**/module-info.class")
}

dependencyLocking {
    lockAllConfigurations()
}
