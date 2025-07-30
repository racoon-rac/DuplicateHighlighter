plugins { id("java") }

repositories { mavenCentral() }

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.6")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.jar {
}
