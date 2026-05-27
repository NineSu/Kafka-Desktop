dependencies {
    implementation(project(":core-kafka"))
    implementation(libs.slf4j.api)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.logback.classic)
}
