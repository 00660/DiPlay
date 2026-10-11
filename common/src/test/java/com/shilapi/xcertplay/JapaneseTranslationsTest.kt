package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class JapaneseTranslationsTest {
    private val placeholder = Regex("%(?:\\d+\\$)?[dsf]")

    @Test fun everyEnglishStringHasAJapaneseTranslationWithMatchingArguments() {
        val english = load("values")
        val japanese = load("values-ja")
        assertEquals("Japanese resource keys", english.keys, japanese.keys)
        for ((name, source) in english) {
            assertEquals(name, placeholders(source), placeholders(japanese.getValue(name)))
        }
    }

    private fun placeholders(value: String) = placeholder.findAll(value).map { it.value }.toList().sorted()

    private fun load(locale: String): Map<String, String> {
        val file = File("src/main/res/$locale/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val key = node.attributes.getNamedItem("name").nodeValue
            check(result.put(key, node.textContent) == null) { "Duplicate translation: $locale/$key" }
        }
        return result
    }
}
