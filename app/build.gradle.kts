plugins {
    alias(libs.plugins.javafx)
    application
}

dependencies {
    implementation(project(":core-kafka"))
    implementation(project(":core-filter"))
    implementation(project(":core-storage"))
    implementation(project(":ui-common"))
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation("io.insert-koin:koin-test:3.5.6")
    testImplementation("io.insert-koin:koin-test-junit5:3.5.6")
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}

application {
    mainClass.set("com.kdt.app.MainKt")
}
