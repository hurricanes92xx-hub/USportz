package com.usportz.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndTvNavigation() = rule.collect(packageName = "com.usportz.app") {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.usportz.app")), 5_000)
        device.waitForIdle()
    }
}
