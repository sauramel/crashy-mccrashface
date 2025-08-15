plugins {
    `java-library`
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.crashymccrashface"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://m2.dv8tion.net/releases") }
}

dependencies {
    // Discord Bot
    implementation("net.dv8tion:JDA:5.0.0-beta.22")

    // Gemini AI
    implementation("com.google.cloud:google-cloud-vertexai:1.31.0")

    // SQLite Database
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Redis Client
    implementation("redis.clients:jedis:5.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.crashymccrashface.crashybought.App")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.crashymccrashface.crashybought.App"
    }
}

tasks.named("shadowJar", com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    archiveBaseName.set("crashy-mccrashface")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.crashymccrashface.crashybought.App"
    }
}
