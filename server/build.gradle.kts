// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm")
    jacoco
    "com.github.ben-manes.versions"
    id("com.vanniktech.maven.publish") version("0.37.0")

}
repositories {
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven {
        name = "dit"
        url = uri("https://maven.dit.dev")
    }
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation(kotlin("reflect"))
	implementation("dev.dit:remote-sdk:1.10.4")
	implementation("dev.dit:command-executor:1.10.4")
	implementation("com.google.code.gson:gson:2.15.0")
	testImplementation("io.kotest:kotest-runner-junit5:6.2.6")
	testImplementation("io.kotest:kotest-assertions-core:6.2.6")
	testImplementation("io.mockk:mockk:1.14.9")
	
	// Force kotlin-reflect to match Kotlin version
	constraints {
		implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.21") {
			because("Match Kotlin compiler version to avoid reflection issues")
		}
		testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.21") {
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
//
// Releases are published to Maven Central (namespace dev.dit) by the
// com.vanniktech.maven.publish plugin, which also supplies the sources jar,
// javadoc jar and signatures that Central requires. Credentials and the
// signing key come from Gradle properties (ORG_GRADLE_PROJECT_* in CI); see
// DEVELOPING.md.
mavenPublishing {
    // Upload as a deployment that waits for a manual "Publish" click in the
    // Central Portal: anything released to Central is permanent.
    publishToMavenCentral()

    // Sign only when a key is supplied, so `publishToMavenLocal` works on a
    // developer machine or pull request build with no key. A release without
    // signatures cannot slip through: Central rejects unsigned deployments.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    // Kotlin sources have no javadoc; Central accepts an empty javadoc jar.
    configureBasedOnAppliedPlugins(
        javadocJar = JavadocJar.Empty(),
        sourcesJar = SourcesJar.Sources(),
    )

    coordinates("dev.dit", "ssh-remote-server", version.toString())

    pom {
        name.set("Dit SSH Remote Provider")
        description.set("Dit remote provider that stores commits on a host reached over SSH.")
        inceptionYear.set("2026")
        url.set("https://github.com/ditdotdev/ssh-remote")
        licenses {
            license {
                name.set("BUSL-1.1")
                url.set("https://github.com/ditdotdev/ssh-remote/blob/master/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ditdotdev")
                name.set("Dit")
                url.set("https://github.com/ditdotdev")
            }
        }
        scm {
            url.set("https://github.com/ditdotdev/ssh-remote")
            connection.set("scm:git:https://github.com/ditdotdev/ssh-remote.git")
            developerConnection.set("scm:git:ssh://git@github.com/ditdotdev/ssh-remote.git")
        }
    }
}

// The dit-maven S3 bucket (served as https://maven.dit.dev) stays as a second
// publish target while the ecosystem migrates to Maven Central: the release
// tooling still verifies artifacts there. Remove it once every dev.dit
// artifact is released from Central.
val mavenBucket = when (project.hasProperty("mavenBucket")) {
    true -> project.property("mavenBucket")
    false -> "dit-maven"
}

publishing {
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

