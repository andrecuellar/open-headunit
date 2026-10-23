package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.StatusPillPolicy.Withheld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusPillPolicyTest {

    private fun withheld(
        stage: ConnectionStage? = ConnectionStage.WAITING_FOR_PHONE,
        sessionLive: Boolean = false,
        overlay: Boolean = false,
        phoneLeft: Boolean = false,
        homeTellsIt: Boolean = false,
    ) = StatusPillPolicy.withheld(stage, sessionLive, overlay, phoneLeft, homeTellsIt)

    @Test
    fun `a bring-up step with nothing in the way is shown`() {
        val stage = ConnectionStage.WAITING_FOR_PHONE
        assertNull(withheld(stage))
        assertEquals(stage, StatusPillPolicy.shown(stage, withheld(stage)))
    }

    /** The reported case: Home during a session showed "Starting Android Auto" over the card. */
    @Test
    fun `a live session hides the step the tracker kept from its start`() {
        val stage = ConnectionStage.STARTING_PROJECTION
        val reason = withheld(stage, sessionLive = true)
        assertEquals(Withheld.SESSION_LIVE, reason)
        assertNull(StatusPillPolicy.shown(stage, reason))
    }

    @Test
    fun `a live session wins over every other reason in the log`() {
        assertEquals(
            Withheld.SESSION_LIVE,
            withheld(sessionLive = true, overlay = true, phoneLeft = true, homeTellsIt = true)
        )
    }

    @Test
    fun `the other reasons keep their old precedence`() {
        assertEquals(Withheld.PHONE_LEFT, withheld(phoneLeft = true, homeTellsIt = true, overlay = true))
        assertEquals(Withheld.HOME_TELLS_IT, withheld(homeTellsIt = true, overlay = true))
        assertEquals(Withheld.OVERLAY, withheld(overlay = true))
    }

    @Test
    fun `no step means no pill and nothing withheld`() {
        assertNull(withheld(stage = null, sessionLive = true))
        assertNull(StatusPillPolicy.shown(null, null))
    }
}
