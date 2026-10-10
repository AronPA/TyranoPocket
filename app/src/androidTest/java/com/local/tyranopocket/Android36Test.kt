package com.local.tyranopocket

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.local.tyranopocket.core.GameImporter
import com.local.tyranopocket.core.SafeFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** WRITTEN BUT NOT RUN. Synthetic VN fixture, not a real TyranoScript engine test.
 * Two separate instrumentation invocations permit an actual force-stop between phases. */
@RunWith(AndroidJUnit4::class)
class Android36Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun evaluate(scenario: ActivityScenario<MainActivity>, expression: String): String {
        val result = arrayOf<String?>(null)
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = web(activity.window.decorView)
            if (view == null) latch.countDown() else view.evaluateJavascript(expression) { result[0] = it; latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result[0] ?: "null"
    }
    private fun awaitTrue(scenario: ActivityScenario<MainActivity>, expression: String) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (evaluate(scenario, expression) == "true") return
            SystemClock.sleep(100)
        }
        fail("Web assertion timed out: $expression")
    }
    private fun launchFixture(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertNotNull(device.wait(Until.findObject(By.text("플레이")), 10_000))
        device.findObject(By.text("플레이")).click()
        awaitTrue(scenario, "Boolean(window.fixture&&fixture.ready&&fixture.plugin)")
        return scenario
    }
    @Test fun phaseOneImportRenderSaveAudioRotation() {
        assertEquals("Run only on the required API 36 test device", 36, Build.VERSION.SDK_INT)
        val game = instrumentation.context.assets.open("A.zip").use {
            GameImporter(context.filesDir).importZip(it, "fixture", SafeFiles.NONE)
        }
        context.getSharedPreferences("test-only", 0).edit().putString("fixtureId", game.name).commit()
        launchFixture().use { scenario ->
            awaitTrue(scenario, "document.getElementById('picture').complete&&document.getElementById('picture').naturalWidth>0")
            device.findObject(By.text("다음 대사")).click()
            device.findObject(By.text("저장")).click()
            assertEquals("\"1\"", evaluate(scenario, "localStorage.getItem('line')"))
            device.findObject(By.text("음악 재생")).click()
            awaitTrue(scenario, "!document.getElementById('bgm').paused&&document.getElementById('bgm').currentTime>0")
            device.setOrientationLeft()
            awaitTrue(scenario, "Boolean(window.fixture&&fixture.ready)")
            assertEquals("\"1\"", evaluate(scenario, "localStorage.getItem('line')"))
            device.setOrientationNatural()
            device.unfreezeRotation()
        }
    }
    @Test fun phaseTwoAfterForceStop() {
        assertEquals(36, Build.VERSION.SDK_INT)
        val id = context.getSharedPreferences("test-only", 0).getString("fixtureId", null)
        assertNotNull(id)
        assertTrue(GameLibrary(context).games().any { it.id == id })
        launchFixture().use { scenario -> assertEquals("\"1\"", evaluate(scenario, "localStorage.getItem('line')")) }
    }
}
