package com.example.playerdemo.media.extensions

import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale

/**
 * This file contains extension methods for the java.lang package.
 */

/**
 * Helper method to check if a [String] contains another in a case insensitive way.
 */
fun String?.containsCaseInsensitive(other: String?) =
    if (this != null && other != null) {
        lowercase(Locale.getDefault()).contains(other.lowercase(Locale.getDefault()))
    } else {
        this == other
    }

/**
 * Helper extension to URL encode a [String]. Returns an empty string when called on null.
 */
inline val String?.urlEncoded: String
    get() = if (Charset.isSupported("UTF-8")) {
        URLEncoder.encode(this ?: "", "UTF-8")
    } else {
        // If UTF-8 is not supported, use the default charset.
        @Suppress("deprecation")
        URLEncoder.encode(this ?: "")
    }
