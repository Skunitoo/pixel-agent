package pl.pixelagent

import org.junit.Assert.*
import org.junit.Test

class AgentProtocolTest {
    @Test fun parsesStrictUiAction() {
        val action = AgentProtocol.parse("""{"action":"tap","target":3,"reason":"Test"}""")
        assertEquals("TAP", action.action)
        assertEquals(3, action.target)
        assertEquals("Test", action.reason)
    }
    @Test fun parsesJsonInsideMarkdown() {
        val action = AgentProtocol.parse("Plan:\n" +
            """{"action":"TYPE","target":1,"text":"hello","reason":"write"}""" + "\n")
        assertEquals("TYPE", action.action)
        assertEquals("hello", action.text)
    }
    @Test fun rejectsUnknownAction() {
        try {
            AgentProtocol.parse("""{"action":"ROOT_SHELL","reason":"bad"}""")
            fail("Must reject unlisted action")
        } catch (_: IllegalArgumentException) {}
    }
    @Test fun refusesSensitiveApps() {
        assertTrue(AgentProtocol.forbidden("com.revolut.revolut", "Revolut"))
        assertTrue(AgentProtocol.forbidden("com.microsoft.authenticator", "Authenticator"))
        assertFalse(AgentProtocol.forbidden("com.android.chrome", "Chrome"))
        assertFalse(AgentProtocol.forbidden("org.telegram.messenger", "Telegram"))
    }
    @Test fun refusesTypingWithoutAVisibleInput() {
        val cmd = AgentCommand(action="TYPE", target=1, text="test")
        assertEquals("Brak widocznego ekranu.",
            AgentProtocol.validate(cmd, null, emptyList()))
    }
    @Test fun rejectsUnknownApp() {
        val cmd = AgentCommand(action="OPEN_APP", app="Something dangerous")
        assertNotNull(AgentProtocol.validate(cmd, null, listOf(AgentApp("Chrome","com.android.chrome"))))
    }
    @Test fun detectsElevatedTyping() {
        assertTrue(AgentProtocol.elevated(AgentCommand(action="TYPE", text="x"), null))
    }
    @Test fun rejectsMalformedModelOutput() {
        try {
            AgentProtocol.parse("SCROLL_DOWN")
            fail("Output must be JSON")
        } catch (_: IllegalArgumentException) {}
    }
}
