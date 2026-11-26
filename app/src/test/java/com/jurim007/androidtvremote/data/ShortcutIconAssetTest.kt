package com.jurim007.androidtvremote.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutIconAssetTest {
    private val icons = File("src/main/assets/shortcut-icons").listFiles { file -> file.extension == "svg" }.orEmpty()

    @Test
    fun bundledLogosScaleToFillTheirShortcut() {
        assertTrue("No shortcut icons found", icons.isNotEmpty())
        // A fixed width or height draws the logo at that size instead of filling the button.
        val fixedSize = icons.filter { icon ->
            val root = Regex("<svg[^>]*>").find(icon.readText())?.value.orEmpty()
            Regex("""\s(width|height)=""").containsMatchIn(root) || "viewBox=" !in root
        }.map { it.name }
        assertEquals(emptyList<String>(), fixedSize)
    }
}
