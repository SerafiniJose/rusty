package dev.rusty.app

/** One collapsed-section header summary: [text] plus whether it renders in the accent color
 *  (something actively configured/filtered) or muted (defaults / not set up). Pure — no Android. */
data class SectionSummary(val text: String, val active: Boolean)

/** Summary-line builders for the Slideshow settings sections. */
object SlideshowSummaries {

    /** Host as shown in a collapsed header: scheme stripped, trailing slash dropped. */
    private fun host(url: String): String = url.substringAfter("://").trimEnd('/')

    /** [verified] is whether the stored key actually authenticated on its last Save. A key that is
     *  present but unverified (fresh save mid-check, or one whose verification FAILED) stays muted as
     *  "Key not verified" — it must never read as the green "key saved", which would make a wrong key
     *  look connected. Only a verified key promotes to "{name} · {host}" / "{host} · key saved". */
    fun server(savedUrl: String?, keySaved: Boolean, verified: Boolean, accountName: String?): SectionSummary = when {
        savedUrl.isNullOrBlank() || !keySaved -> SectionSummary("Not configured", false)
        !verified -> SectionSummary("Key not verified", false)
        accountName != null -> SectionSummary("$accountName · ${host(savedUrl)}", true)
        else -> SectionSummary("${host(savedUrl)} · key saved", true)
    }

    fun filters(configured: Boolean, albums: Int, people: Int, tags: Int): SectionSummary = when {
        !configured -> SectionSummary("Set up the server first", false)
        albums == 0 && people == 0 && tags == 0 -> SectionSummary("Whole library", false)
        else -> SectionSummary(
            buildList {
                if (albums > 0) add(ImmichPickerModel.unitCount(albums, ImmichFilterKind.ALBUMS))
                if (people > 0) add(ImmichPickerModel.unitCount(people, ImmichFilterKind.PEOPLE))
                if (tags > 0) add(ImmichPickerModel.unitCount(tags, ImmichFilterKind.TAGS))
            }.joinToString(" · "),
            true,
        )
    }

    /** Always muted: the display section holds preferences, not connection/filter state. */
    fun display(intervalSeconds: Int, clock: Boolean, info: Boolean, zoom: Boolean, split: Boolean): SectionSummary {
        val overlays = buildList {
            if (clock) add("Clock")
            if (info) add("Photo info")
            if (zoom) add("Zoom")
            if (split) add("Split view")
        }
        val tail = if (overlays.isEmpty()) "no overlays" else overlays.joinToString(", ")
        return SectionSummary("Every ${SlideshowSettings.intervalLabel(intervalSeconds)} · $tail", false)
    }
}

/** Summary-line builders for the Home Assistant settings sections. */
object HaSummaries {

    fun server(signedIn: Boolean, accountName: String?, host: String?): SectionSummary = when {
        !signedIn -> SectionSummary("Not signed in", false)
        accountName != null -> SectionSummary("$accountName · ${host ?: "Home Assistant"}", true)
        else -> SectionSummary("Signed in · ${host ?: "Home Assistant"}", true)
    }

    /** Shared by the Dashboards and Apps sections. */
    fun items(signedIn: Boolean, selected: Int, total: Int): SectionSummary = when {
        !signedIn -> SectionSummary("Sign in first", false)
        total == 0 -> SectionSummary("None found — tap Refresh", false)
        else -> SectionSummary("$selected of $total shown in Rusty", selected > 0)
    }

    /** Theme selector summary for the Appearance section. [mode] is one of
     *  [HomeAssistantNav.MODE_AUTO]/`MODE_LIGHT`/`MODE_DARK`. Default theme + Auto is the neutral
     *  no-override state (inactive); anything else is an active choice. */
    fun theme(signedIn: Boolean, selectedName: String?, mode: String?): SectionSummary {
        if (!signedIn) return SectionSummary("Sign in first", false)
        val name = selectedName?.takeIf { it.isNotBlank() } ?: "Default"
        val modeLabel = when (mode) {
            HomeAssistantNav.MODE_LIGHT -> "Light"
            HomeAssistantNav.MODE_DARK -> "Dark"
            else -> null
        }
        val isDefaultAuto = selectedName.isNullOrBlank() && modeLabel == null
        val text = if (modeLabel != null) "$name · $modeLabel" else name
        return SectionSummary(text, active = !isDefaultAuto)
    }
}
