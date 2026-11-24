package com.arponzxc.novaai.config

import com.arponzxc.novaai.data.model.GeminiConstants
import com.arponzxc.novaai.security.DeveloperInfo
import com.arponzxc.novaai.security.SecurityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingAndPackageMigrationTest {

    @Test
    fun socialLinks_matchOfficialIdentity() {
        assertEquals("NOVA AI", SocialLinks.APP_NAME)
        assertEquals("Arpon Gang", SocialLinks.DEVELOPER_NAME)
        assertEquals("com.arponzxc.novaai", SocialLinks.PACKAGE_ID)

        assertEquals("@Appboy_offcialc", SocialLinks.TELEGRAM_OFFICIAL_USERNAME)
        assertEquals("https://t.me/Appboy_offcialc", SocialLinks.TELEGRAM_OFFICIAL_URL)

        assertEquals("@Appboy_offcialc", SocialLinks.TELEGRAM_DISCUSSION_USERNAME)
        assertEquals("https://t.me/Appboy_offcialc", SocialLinks.TELEGRAM_DISCUSSION_URL)

        assertEquals("Messi", SocialLinks.INSTAGRAM_USERNAME)
        assertEquals("https://www.instagram.com/Messi/", SocialLinks.INSTAGRAM_URL)

        assertEquals("@nexastudiox", SocialLinks.YOUTUBE_USERNAME)
        assertEquals("https://www.youtube.com/@nexastudiox", SocialLinks.YOUTUBE_URL)

        assertEquals("Arponzxc-prog", SocialLinks.GITHUB_USERNAME)
        assertEquals("https://github.com/Arponzxc-prog", SocialLinks.GITHUB_URL)
    }

    @Test
    fun securityAndDeveloperInfo_matchMigratedBrandAndPackage() {
        assertEquals("Arpon Gang", DeveloperInfo.DEVELOPER_NAME)
        assertEquals("com.arponzxc.novaai", DeveloperInfo.APP_OFFICIAL_ID)
        assertEquals("https://github.com/Arponzxc-prog", DeveloperInfo.GITHUB_URL)
        assertTrue(DeveloperInfo.COPYRIGHT_NOTICE.contains("Arpon Gang"))
        assertTrue(DeveloperInfo.VERIFIED_DEVELOPER.contains("Arpon Gang"))
        assertTrue(DeveloperInfo.VERIFIED_DEVELOPER.contains("NOVA AI"))

        assertEquals("Arpon Gang", SecurityManager.DEVELOPER_NAME)
        assertEquals("https://github.com/Arponzxc-prog", SecurityManager.GITHUB_REPO)
        assertTrue(SecurityManager.COPYRIGHT_NOTICE.contains("Arpon Gang"))
        assertTrue(SecurityManager.LICENSE_NAME.contains("Arpon Gang"))
    }

    @Test
    fun geminiPersonalities_doNotContainLegacyBrand() {
        assertTrue(GeminiConstants.PERSONALITY_ARPON_CORE in GeminiConstants.SUPPORTED_PERSONALITIES)
        GeminiConstants.SUPPORTED_PERSONALITIES.forEach { personality ->
            assertFalse("Legacy brand found in $personality", personality.contains("techgpt", ignoreCase = true))
        }
    }
}
