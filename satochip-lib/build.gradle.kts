plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    api("org.bouncycastle:bcprov-jdk15on:1.70")
    api("org.bitcoinj:bitcoinj-core:0.16.2") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
}
