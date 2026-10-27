package com.andrerinas.openheadunit.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaUpdatePolicyTest {

    @Test
    fun `isNewerVersion returns true when remote version is higher`() {
        assertTrue(OtaUpdateManager.isNewerVersion("1.3", "1.2"))
        assertTrue(OtaUpdateManager.isNewerVersion("1.2.1", "1.2"))
        assertTrue(OtaUpdateManager.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(OtaUpdateManager.isNewerVersion("1.3-beta", "1.2"))
    }

    @Test
    fun `isNewerVersion returns false when remote version is lower or equal`() {
        assertFalse(OtaUpdateManager.isNewerVersion("1.2", "1.2"))
        assertFalse(OtaUpdateManager.isNewerVersion("1.1", "1.2"))
        assertFalse(OtaUpdateManager.isNewerVersion("1.2.0", "1.2.1"))
    }

    @Test
    fun `OtaInstallReceiver action constant is consistent`() {
        org.junit.Assert.assertEquals("com.wsknnew.diautonew.ota.INSTALL_STATUS", OtaInstallReceiver.ACTION_INSTALL_STATUS)
    }
}
