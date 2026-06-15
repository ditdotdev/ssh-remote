/*
 * Copyright Dit.
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
        name = "dit"
        url = uri("https://dit-maven.s3.amazonaws.com")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation(kotlin("reflect"))
	implementation("dev.dit:remote-sdk:1.9.7")
	implementation("dev.dit:command-executor:1.9.8")
	implementation("com.google.code.gson:gson:2.14.0")
	testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
	testImplementation("io.kotest:kotest-assertions-core:6.1.11")
	testImplementation("io.mockk:mockk:1.14.9")
	
	// Force kotlin-reflect to match Kotlin version
	constraints {
		implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0") {
			because("Match Kotlin compiler version to avoid reflection issues")
		}
		testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0") {
			because("Match Kotlin compiler version to avoid reflection issues")
		}
	}
}

// Jar configuration
group = "dev.dit"
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
    false -> "dit-maven"
}

publishing {
	publications {
		create<MavenPublication>("maven") {
			groupId = "dev.dit"
			artifactId = "ssh-remote-server"

			from(components["java"])
		}
	}

    repositories {
        maven {
            name = "dit"
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
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

