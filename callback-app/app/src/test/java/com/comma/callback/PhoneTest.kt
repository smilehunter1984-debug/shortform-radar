package com.comma.callback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTest {
    @Test fun normalize() {
        assertEquals("01012345678", Phone.normalize("010-1234-5678"))
        assertEquals("01012345678", Phone.normalize("+82 10-1234-5678"))
        assertEquals("0212345678", Phone.normalize("02)1234-5678"))
        assertEquals("", Phone.normalize(null))
    }

    @Test fun mobile() {
        assertTrue(Phone.isMobile("01012345678"))
        assertTrue(Phone.isMobile("0111234567"))
        assertFalse(Phone.isMobile("0212345678"))
        assertFalse(Phone.isMobile("07012345678"))
        assertFalse(Phone.isMobile("15881234"))
    }

    @Test fun parseList() {
        assertEquals(setOf("01011112222", "01033334444"), Phone.parseList("010-1111-2222, 01033334444\n"))
        assertTrue(Phone.parseList("").isEmpty())
    }

    @Test fun bytes() {
        assertEquals(3, Phone.smsBytes("abc"))
        assertEquals(4, Phone.smsBytes("한글"))
        assertEquals(80, Phone.smsBytes("가".repeat(40)))
        assertTrue(Phone.smsBytes("가".repeat(41)) > Phone.SMS_MAX_BYTES)
    }

    @Test fun pretty() {
        assertEquals("010-1234-5678", Phone.pretty("01012345678"))
        assertEquals("02-1234-5678", Phone.pretty("0212345678"))
    }
}
