package re.abbot.librecr.app.data

enum class BmwIDriveDisplayMode {
    AUTO, SPLIT_SCREEN, FULL_SCREEN;

    companion object {
        fun fromName(name: String?): BmwIDriveDisplayMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
