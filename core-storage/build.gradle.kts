dependencies {
    implementation(project(":core-kafka"))
    implementation(project(":core-filter"))
    implementation(project(":core-auth"))
    implementation(libs.duckdb.jdbc)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.java.keyring)
    implementation(libs.commons.csv)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.logback.classic)
}
