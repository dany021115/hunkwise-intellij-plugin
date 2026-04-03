package com.hunkwise.util

import java.text.Normalizer

object PathNormalize {
    private val isMac = System.getProperty("os.name", "").lowercase().contains("mac")

    fun normalize(path: String): String {
        return if (isMac) Normalizer.normalize(path, Normalizer.Form.NFC) else path
    }
}
