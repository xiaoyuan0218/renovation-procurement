package com.xiaoyuan.renovation.mobile.data.prefs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打赏开关的版本规则：关掉只对当时那个版本有效，更新（或重新部署）后自动回到
 * 打开 —— 每次发新版，打赏提示都会再出现一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DonateSwitchTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `同一个版本里关掉，就一直关着`() = runBlocking {
        val prefs = AppPrefs(context, "1.2.5")

        prefs.setDonateEnabled(false)

        assertFalse("同一版本内不再提示", prefs.donateEnabled.first())
        assertFalse("重开 App（新实例、同一份存储）也还是关的",
            AppPrefs(context, "1.2.5").donateEnabled.first())
    }

    @Test
    fun `换到新版本，开关自动回到打开`() = runBlocking {
        AppPrefs(context, "1.2.5").setDonateEnabled(false)

        assertTrue("更新到新版本后要重新提示",
            AppPrefs(context, "1.2.6").donateEnabled.first())
    }

    @Test
    fun `在新版本里再关，也算数`() = runBlocking {
        AppPrefs(context, "1.2.5").setDonateEnabled(false)
        AppPrefs(context, "1.2.6").setDonateEnabled(false)

        assertFalse("在新版本里关掉后，这个版本内不再提示",
            AppPrefs(context, "1.2.6").donateEnabled.first())
        assertTrue("再换到更新的版本又回到打开",
            AppPrefs(context, "1.2.7").donateEnabled.first())
    }

    @Test
    fun `手动打开后，任何判断都不再算关`() = runBlocking {
        val prefs = AppPrefs(context, "1.2.5")
        prefs.setDonateEnabled(false)
        prefs.setDonateEnabled(true)

        assertTrue(prefs.donateEnabled.first())
        assertTrue(AppPrefs(context, "1.2.5").donateEnabled.first())
    }

    @Test
    fun `老记录没有版本号时当作没关过`() {
        assertFalse("1.2.4 及以前关的没有版本记录，按规则重新打开",
            donateOffStillValid(off = true, offVersion = null, currentVersion = "1.2.5"))
        assertTrue(donateOffStillValid(off = true, offVersion = "1.2.5",
            currentVersion = "1.2.5"))
        assertFalse("在更早的版本里关的，换了版本不作数",
            donateOffStillValid(off = true, offVersion = "1.2.4", currentVersion = "1.2.5"))
        assertFalse(donateOffStillValid(off = false, offVersion = "1.2.5",
            currentVersion = "1.2.5"))
    }
}
