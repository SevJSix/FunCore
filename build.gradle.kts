plugins {
    id("java-library")
    // Gives compile-time access to Minecraft server internals (used only by the npc.nms package).
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    // NoteBlockAPI (reads .nbs songs), published through JitPack.
    maven("https://jitpack.io")
}

dependencies {
    // Includes the Paper API plus the server internals.
    paperweight.paperDevBundle("26.3.build.244-stable")
    // Optional: the WorldEdit API, which FastAsyncWorldEdit provides at runtime. Only used by the arena.worldedit package.
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    // Optional: NoteBlockAPI, installed on the server as its own plugin. Only used by the music.nbs package.
    compileOnly("com.github.koca2000:NoteBlockAPI:1.7.0") { isTransitive = false }

    // Unit tests for the Bukkit-free logic (e.g. the tycoon core). SnakeYAML reads template files in tests, as the
    // server's copy does at runtime.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.yaml:snakeyaml:2.3")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    test {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
