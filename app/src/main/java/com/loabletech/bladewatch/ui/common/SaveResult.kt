package net.bladewatch.app.ui.common

/** Returned by client save methods so controllers can surface errors to the user via Toast. */
internal data class SaveResult(val ok: Boolean, val error: String?)
