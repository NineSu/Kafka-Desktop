dependencies {
    implementation(libs.kafka.clients)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.logback.classic)
}
