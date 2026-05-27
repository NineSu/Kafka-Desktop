dependencies {
    api(project(":core-auth"))
    implementation(libs.kafka.clients)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.logback.classic)
}
