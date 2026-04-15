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
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "datadatdat"
        url = uri("https://datadatdat-maven.s3.amazonaws.com")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation(kotlin("reflect"))
	implementation("com.datadatdat:remote-sdk:1.8.5")
	implementation("com.datadatdat:command-executor:1.8.6")
	implementation("com.google.code.gson:gson:2.13.2")
	testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
	testImplementation("io.kotest:kotest-assertions-core:6.1.11")
	testImplementation("io.mockk:mockk:1.14.9")
	
	// Force kotlin-reflect to match Kotlin version
	constraints {
		implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.20") {
			because("Match Kotlin compiler version to avoid reflection issues")
		}
		testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.3.20") {
			because("Match Kotlin compiler version to avoid reflection issues")
		}
	}
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
			artifactId = "ssh-remote-server"

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

// Test configuration

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio.file=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens", "java.base/java.net=ALL-UNNAMED"
    )
}

