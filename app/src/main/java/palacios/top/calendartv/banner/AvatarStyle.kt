package palacios.top.calendartv.banner

/** One stable look per account: the initial and the fallback color shown when there is no Google photo. */
object AvatarStyle {

    private val palette = listOf("#1A73E8", "#D93025", "#188038", "#E37400", "#9334E6", "#12B5CB", "#C2185B")

    fun initial(displayName: String, email: String): String =
        (displayName.ifBlank { email }).firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString().orEmpty()

    fun color(email: String): String = palette[Math.floorMod(email.lowercase().hashCode(), palette.size)]
}
