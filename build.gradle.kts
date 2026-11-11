plugins {
    id("com.android.application") version "9.5.1"
}

android {
    namespace = "dev.lanlink"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.lanlink"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        release {
            // MINA SSHD leans on reflection; shrinking it isn't worth the breakage.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md")
    }
}

dependencies {
    // SFTP server so the desktop can mount the phone (same as KDE Connect uses).
    implementation("org.apache.sshd:sshd-core:2.20.1")
    implementation("org.apache.sshd:sshd-sftp:2.20.1")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
