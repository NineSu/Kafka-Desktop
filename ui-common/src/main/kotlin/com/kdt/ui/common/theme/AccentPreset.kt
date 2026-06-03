package com.kdt.ui.common.theme

/** Built-in accent colors shown as quick swatches in Settings. Users may also pick a custom color. */
enum class AccentPreset(val displayName: String, val hex: String) {
    BLUE("Blue", "#3574F0"),
    PURPLE("Purple", "#8A5CF6"),
    TEAL("Teal", "#16A394"),
    GREEN("Green", "#1F9254"),
    AMBER("Amber", "#E0871E"),
    RED("Red", "#E0556B"),
    GRAPHITE("Graphite", "#5B6470");

    companion object {
        val DEFAULT = BLUE
    }
}
