package io.aatricks.easyreader.util

object ErrorMessages {

    data class Friendly(val title: String, val body: String, val isRetryable: Boolean = true)

    fun fromRaw(raw: String?): Friendly {
        val msg = raw.orEmpty()

        return when {
            msg.contains("UnknownHost", ignoreCase = true) ||
                msg.contains("No address associated", ignoreCase = true) ||
                msg.contains("network is unreachable", ignoreCase = true) ->
                Friendly(
                    title = "You're offline",
                    body = "Check your connection and try again."
                )

            msg.contains("SocketTimeout", ignoreCase = true) ||
                msg.contains("ConnectTimeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                Friendly(
                    title = "Connection timed out",
                    body = "The server took too long to respond. Try again in a moment."
                )

            msg.contains("403") || msg.contains("503") || msg.contains("Cloudflare", ignoreCase = true) ->
                Friendly(
                    title = "Site challenge required",
                    body = "The source is blocking automated access. Solve the challenge in the browser pop-up to continue."
                )

            msg.contains("404") ->
                Friendly(
                    title = "Chapter not found",
                    body = "The page no longer exists at that URL. The site may have moved or removed it.",
                    isRetryable = false
                )

            msg.contains("SSL", ignoreCase = true) ||
                msg.contains("certificate", ignoreCase = true) ->
                Friendly(
                    title = "Secure connection failed",
                    body = "Could not verify the site's certificate. Check your device date/time, then retry."
                )

            msg.contains("parse", ignoreCase = true) ||
                msg.contains("EPUB", ignoreCase = true) ->
                Friendly(
                    title = "Couldn't read the file",
                    body = "The content could not be parsed. The file may be corrupted or unsupported.",
                    isRetryable = false
                )

            msg.isBlank() ->
                Friendly(
                    title = "Reader couldn't load this chapter",
                    body = "Something went wrong. Try again."
                )

            else ->
                Friendly(
                    title = "Reader couldn't load this chapter",
                    body = msg.lines().firstOrNull()?.take(160) ?: "Try again."
                )
        }
    }
}
