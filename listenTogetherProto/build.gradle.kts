import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

// Adoptium publishes no Windows ARM64 JDK 25; Microsoft's OpenJDK build is the one available there.
val jdkVendor =
    if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") {
        JvmVendorSpec.MICROSOFT
    } else {
        JvmVendorSpec.ADOPTIUM
    }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = jdkVendor
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

dependencies {
    api(libs.protobuf.javalite)
}

sourceSets {
    named("main") {
        proto {
            srcDir(rootProject.file("metroproto"))
        }
    }
}

protobuf {
    protoc {
        // protoc has no Windows ARM64 build; Windows runs the x64 one under emulation.
        val classifier = if (jdkVendor == JvmVendorSpec.MICROSOFT) ":windows-x86_64@exe" else ""
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufJava.get()}$classifier"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

tasks.named<Jar>("jar") {
    exclude("**/*.proto")
}
