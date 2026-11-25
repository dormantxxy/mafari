package dev.dsh.miuixbrowser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 Android 12 模拟器冒烟测试配套，防止已修复的高版本 API 调用重新进入共用代码。 */
class Android12CompatibilityTest {
    private val source = File("src/main/java/dev/dsh/miuixbrowser")

    @Test fun portableFileReadsAndAutofillExtras() {
        source.walkTopDown().filter { it.extension == "kt" }.forEach {
            val code = it.readText()
            assertFalse(it.path, code.contains(".readNBytes("))
            assertFalse(it.path, code.contains("intent.getParcelableExtra("))
            assertFalse(it.path, code.contains("PackageManager.ApplicationInfoFlags"))
            assertFalse(it.path, code.contains("webView.settings.setAlgorithmicDarkeningAllowed("))
        }
        listOf("AutofillAuthActivity.kt", "AutofillPickActivity.kt").forEach {
            assertTrue(it, File(source, it).readText().contains("IntentCompat.getParcelableExtra(intent,"))
        }
    }

    @Test fun notificationRuntimePermissionOnlyOnAndroid13AndLater() {
        listOf("GmNotifications.kt", "engine/gecko/GeckoWebNotifications.kt", "player/NativePlayerActivity.kt").forEach {
            assertTrue(it, File(source, it).readText().contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&"))
        }
    }

    @Test fun pipReceiverRemainsPrivateOnAndroid12() {
        assertTrue(File(source, "script/MediaHost.kt").readText().contains(
            "ContextCompat.registerReceiver(a, r, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)",
        ))
    }
}
