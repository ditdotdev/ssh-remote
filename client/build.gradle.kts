/*
 * Copyright Datadatdat.
 */

plugins {
    kotlin("jvm")
    jacoco
    "com.github.ben-manes.versions"
    `maven-publish`

}
repositories {
    mavenLocal()
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "datadatdat"
        url = uri("https://datadatdat-maven.s3.amazonaws.com")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.datadatdat:remote-sdk:1.7.0")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.5")
    testImplementation("io.kotest:kotest-assertions-core:6.1.5")
}

// Jar configuration
group = "com.datadatdat"
version = when(project.hasProperty("version")) {
    true -> project.property("version")!!
    false -> "latest"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val jar by tasks.getting(Jar::class) {
    archiveBaseName.set("ssh-remote")
}

// Maven publishing configuration
val mavenBucket = when(project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "datadatdat-maven"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.datadatdat"
            artifactId = "ssh-remote-client"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "datadatdat"
            url = uri("s3://$mavenBucket")
            authentication {
                create<AwsImAuthentication>("awsIm")
            }
        }
    }
}

// ktlint configuration
tasks.named("check").get().dependsOn(tasks.named("ktlint"))

// Test configuration

tasks.test {
    useJUnitPlatform()
}

