package com.usportz.app

import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStartupPolicySmokeTest {
    @Test fun channelsPublishWithoutGuide() {
        assertTrue(EpgStartupPolicy.canPublishChannels(24))
    }
}
