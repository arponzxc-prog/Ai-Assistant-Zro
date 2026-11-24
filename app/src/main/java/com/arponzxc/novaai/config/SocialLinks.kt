package com.arponzxc.novaai.config

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Centralized Social Media, Community & Developer Identity Configuration for NOVA AI.
 * All Activities and Fragments reference this single source of truth instead of hardcoding URLs.
 */
object SocialLinks {

    const val APP_NAME = "NOVA AI"
    const val DEVELOPER_NAME = "Arpon Gang"
    const val PACKAGE_ID = "com.arponzxc.novaai"

    const val TELEGRAM_OFFICIAL_USERNAME = "@Appboy_offcialc"
    const val TELEGRAM_OFFICIAL_URL = "https://t.me/Appboy_offcialc"

    const val TELEGRAM_DISCUSSION_USERNAME = "@Appboy_offcialc"
    const val TELEGRAM_DISCUSSION_URL = "https://t.me/Appboy_offcialc"

    const val INSTAGRAM_USERNAME = "Messi"
    const val INSTAGRAM_URL = "https://www.instagram.com/Messi/"

    const val YOUTUBE_USERNAME = "@nexastudiox"
    const val YOUTUBE_URL = "https://www.youtube.com/@nexastudiox"

    const val GITHUB_USERNAME = "Arponzxc-prog"
    const val GITHUB_URL = "https://github.com/Arponzxc-prog"

    fun openTelegramOfficial(context: Context) = openExternalUrl(context, TELEGRAM_OFFICIAL_URL)

    fun openTelegramDiscussion(context: Context) = openExternalUrl(context, TELEGRAM_DISCUSSION_URL)

    fun openInstagram(context: Context) = openExternalUrl(context, INSTAGRAM_URL)

    fun openYoutube(context: Context) = openExternalUrl(context, YOUTUBE_URL)

    fun openGithub(context: Context) = openExternalUrl(context, GITHUB_URL)

    /**
     * Safely launches an external HTTP/HTTPS URL using Intent.ACTION_VIEW with chooser and
     * graceful error handling if no compatible app or browser is installed.
     */
    fun openExternalUrl(context: Context, url: String) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            Toast.makeText(context, "Invalid external link", Toast.LENGTH_SHORT).show()
            return
        }

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        try {
            context.startActivity(viewIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                val chooser = Intent.createChooser(viewIntent, "Open with").apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(chooser)
            } catch (_: Exception) {
                Toast.makeText(context, "No browser or app available to open link", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }
}
