plugins {
    alias(libs.plugins.javafx)
}

dependencies {
    implementation(project(":core-filter"))
    testImplementation(libs.junit.jupiter)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}
