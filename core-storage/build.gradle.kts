dependencies {
    implementation(project(":core-kafka"))
    implementation(project(":core-filter"))
    implementation(libs.duckdb.jdbc)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.logback.classic)
}
