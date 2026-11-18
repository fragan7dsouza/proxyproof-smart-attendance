package com.example.smartattendance.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-Java checks for the QR format, code generation and the 75% maths. */
public class LogicTest {

    @Test
    public void qrPayloadRoundTrips() {
        String token = Codes.token();
        QrPayload p = QrPayload.parse(QrPayload.encode("AbC123sessionXYZ7890", token));
        assertNotNull(p);
        assertEquals("AbC123sessionXYZ7890", p.sessionId);
        assertEquals(token, p.token);
    }

    @Test
    public void qrPayloadRejectsForeignAndTamperedCodes() {
        assertNull(QrPayload.parse(null));
        assertNull(QrPayload.parse("https://example.com"));
        assertNull(QrPayload.parse("PROXYPROOF1|abc"));
        assertNull(QrPayload.parse("PROXYPROOF1|abc|short"));
        assertNull(QrPayload.parse("PROXYPROOF1|../users/x|ABCDEFGHIJKLMNOP"));
        assertNull(QrPayload.parse("PROXYPROOF1|abc|ABCDEFGH|extra"));
        assertNull(QrPayload.parse("OTHERAPP|abc|ABCDEFGHIJKLMNOP"));
    }

    @Test
    public void codesHaveExpectedShape() {
        for (int i = 0; i < 200; i++) {
            assertTrue(Codes.joinCode().matches("[A-HJKMNP-Z2-9]{6}"));
            assertTrue(Codes.token().matches("[A-Za-z0-9]{16}"));
        }
        assertNotEquals(Codes.token(), Codes.token());
    }

    @Test
    public void percentRoundsDownAndHandlesNoClasses() {
        assertEquals(-1, Stats.percent(0, 0));
        assertEquals(66, Stats.percent(2, 3));
        assertEquals(100, Stats.percent(4, 4));
    }

    @Test
    public void seventyFivePercentRule() {
        assertTrue(Stats.isSafe(3, 4));   // exactly 75%
        assertFalse(Stats.isSafe(2, 3));  // 66%
        // 6/10: needs 6 more in a row -> 12/16 = 75%
        assertEquals(6, Stats.classesNeeded(6, 10));
        assertEquals(0, Stats.classesNeeded(9, 10));
        // 10/12: can miss 1 (10/13 = 76.9%) but not 2 (10/14 = 71.4%)
        assertEquals(1, Stats.classesSpare(10, 12));
        assertEquals(0, Stats.classesSpare(3, 4));
        assertEquals(0, Stats.classesSpare(1, 4));
    }

    @Test
    public void greetingSkipsTitles() {
        com.example.smartattendance.data.Profile p = new com.example.smartattendance.data.Profile();
        p.name = "Dr. Meera Kamath";
        assertEquals("Meera", p.firstName());
        p.name = "Drishti Rao";
        assertEquals("Drishti", p.firstName());
    }
}
