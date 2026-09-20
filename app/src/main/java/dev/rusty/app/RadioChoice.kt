package dev.rusty.app

import android.widget.RadioButton

/**
 * Wires a set of standalone [RadioButton]s as one mutually-exclusive choice.
 *
 * They are NOT in a RadioGroup — every panel that needs this positions them with a Flow, so they
 * are not its direct children and get none of its exclusivity. So: check the option whose value
 * equals [selected] and, on any user check, uncheck the siblings and report the new value through
 * [onSelect]. `suppress` stops the programmatic sibling unchecks from re-entering [onSelect].
 *
 * Shared by the Spotify and Camera panels. It lived in both as a private copy, the camera one
 * labelled "copied from SpotifyFeature.bindRadioChoice so the two panels share one behaviour" —
 * which is exactly the thing a single definition guarantees and two copies only promise.
 */
internal fun <T> bindRadioChoice(
    options: List<Pair<RadioButton, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    options.forEach { (radio, value) -> radio.isChecked = value == selected }
    var suppress = false
    options.forEach { (radio, value) ->
        radio.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked || suppress) return@setOnCheckedChangeListener
            suppress = true
            options.forEach { (other, _) -> if (other !== radio) other.isChecked = false }
            suppress = false
            onSelect(value)
        }
    }
}
