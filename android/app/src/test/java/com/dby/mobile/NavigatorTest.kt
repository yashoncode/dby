package com.dby.mobile

import com.dby.mobile.ui.nav.Navigator
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private class Probe : ScreenModel() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun tabs_keep_their_own_stacks() {
        val nav = Navigator()
        nav.push(Screen.Explorer("a"))
        nav.select(Tab.QUERY)
        assertEquals(Screen.Query, nav.current)
        nav.select(Tab.CONNECTIONS)
        assertEquals(Screen.Explorer("a"), nav.current)
    }

    @Test
    fun back_pops_then_returns_to_connections_then_stops() {
        val nav = Navigator()
        nav.select(Tab.SETTINGS)
        nav.push(Screen.Licences)
        assertTrue(nav.back())
        assertEquals(Screen.Settings, nav.current)
        assertTrue(nav.back())
        assertEquals(Tab.CONNECTIONS, nav.tab)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
    }

    @Test
    fun models_live_while_their_screen_is_stacked() {
        val nav = Navigator()
        nav.push(Screen.Explorer("a"))
        val probe = nav.model(Screen.Explorer("a")) { Probe() }
        assertSame(probe, nav.model(Screen.Explorer("a")) { Probe() })
        nav.select(Tab.QUERY)
        assertFalse(probe.closed)
        nav.select(Tab.CONNECTIONS)
        nav.select(Tab.CONNECTIONS) // re-tapping the tab pops to its root
        assertTrue(probe.closed)
        assertEquals(Screen.Connections, nav.current)
    }
}
