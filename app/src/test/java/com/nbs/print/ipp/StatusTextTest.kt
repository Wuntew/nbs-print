package com.nbs.print.ipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {
    @Test
    fun busyIsReportedAsBusyNotAsRefusing() {
        // 0x0507 is server-error-busy (RFC 8011). It was once worded "not accepting jobs".
        val text = IppClient.statusText(0x0507, "Not allowed to print")
        assertTrue(text, text.contains("busy"))
        assertTrue(text, text.contains("0x0507: Not allowed to print"))
    }

    @Test
    fun codesMapToTheirRfcMeanings() {
        assertTrue(IppClient.statusText(0x0504, null).contains("device error"))
        assertTrue(IppClient.statusText(0x0506, null).contains("isn't accepting"))
        assertTrue(IppClient.statusText(0x0505, null).contains("temporary"))
        assertTrue(IppClient.statusText(0x040A, null).contains("format"))
        assertTrue(IppClient.statusText(0x0408, null).contains("too big"))
        assertEquals("The printer reported an error. (0x0999)", IppClient.statusText(0x0999, null))
    }

    @Test
    fun onlyBusyAndTemporaryErrorsAreWorthRetrying() {
        assertTrue(IppStatus.isRetryable(0x0507))
        assertTrue(IppStatus.isRetryable(0x0505))
        assertFalse(IppStatus.isRetryable(0x0506)) // not accepting jobs: waiting won't help
        assertFalse(IppStatus.isRetryable(0x040A))
        assertFalse(IppStatus.isRetryable(null))
    }
}
