package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage

/**
 * Whether the status pill shows the bring-up step, and if not, why.
 *
 * The tracker keeps its last step (STARTING_PROJECTION) for the whole session, because only the end
 * of the session clears it. As the unit's launcher the home is on screen during a session (Home key,
 * move to background), and there the card already says the phone is connected: a pill still
 * claiming Android Auto is starting would contradict it, and its X would tear the wireless side down
 * under a running session.
 */
object StatusPillPolicy {

    /** Why a step the tracker holds is kept off screen. The order is the log's precedence. */
    enum class Withheld { SESSION_LIVE, PHONE_LEFT, HOME_TELLS_IT, OVERLAY }

    /**
     * @param sessionLive the handshake is done (HandshakeComplete or TransportStarted), the same
     *   line the home's card uses for "Back to Android Auto".
     * @param overlayOwnsScreen the full-screen auto-connect overlay took the screen from the pill.
     * @param phoneLeftQuiet the phone ended the session itself moments ago.
     * @param homeTellsIt the home's main button names the step already.
     */
    fun withheld(
        stage: ConnectionStage?,
        sessionLive: Boolean,
        overlayOwnsScreen: Boolean,
        phoneLeftQuiet: Boolean,
        homeTellsIt: Boolean,
    ): Withheld? = when {
        stage == null -> null
        sessionLive -> Withheld.SESSION_LIVE
        phoneLeftQuiet -> Withheld.PHONE_LEFT
        homeTellsIt -> Withheld.HOME_TELLS_IT
        overlayOwnsScreen -> Withheld.OVERLAY
        else -> null
    }

    /** The step the pill shows, or null for no pill. */
    fun shown(stage: ConnectionStage?, withheld: Withheld?): ConnectionStage? =
        if (withheld == null) stage else null
}
