plugins {
    alias(libs.plugins.javafx)
}

dependencies {
    testImplementation(libs.junit.jupiter)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.fxml")
}
