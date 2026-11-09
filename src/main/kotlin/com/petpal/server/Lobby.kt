package com.petpal.server

import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Random matchmaking + relay. Two queued players of the same app version are paired; the first in
 * line hosts (runs the battle) and every text frame is passed through to the other. The server only
 * reads the host's final state line ("S|time,ended,winner,...") to score the match.
 */
class Lobby(private val store: Store) {
    private val log = LoggerFactory.getLogger("Lobby")
    private val lock = Mutex()
    private val waiting = mutableListOf<Seat>()

    class Seat(val session: WebSocketSession, val player: Player, val version: String) {
        val match = CompletableDeferred<Match>()
    }

    inner class Match(val host: Seat, val guest: Seat) {
        private var started = false
        private var done = false

        fun other(s: Seat) = if (s === host) guest else host

        /** Host lines: 1v1 states put the host on team 0, the guest on team 1. */
        @Synchronized fun watch(text: String) {
            if (done) return
            for (line in text.lineSequence()) {
                if (!line.startsWith("S|")) continue
                started = true
                val h = line.substring(2).substringBefore('|').split(',')
                if (h.getOrNull(1) != "1") continue
                done = true
                when (h.getOrNull(2)) {
                    "0" -> score(host, guest, draw = false)
                    "1" -> score(guest, host, draw = false)
                    else -> score(host, guest, draw = true)
                }
                return
            }
        }

        /** Leaving a match that's under way is a loss. */
        @Synchronized fun left(s: Seat) {
            if (done) return
            done = true
            if (started) score(other(s), s, draw = false)
        }

        private fun score(winner: Seat, loser: Seat, draw: Boolean) {
            runCatching { store.record(winner.player.id, loser.player.id, draw) }
                .onFailure { log.error("record failed", it) }
        }
    }

    suspend fun play(session: WebSocketSession, player: Player, version: String) {
        val me = Seat(session, player, version)
        val paired = lock.withLock {
            val other = waiting.firstOrNull { it.version == version && it.player.id != player.id }
            if (other == null) { waiting += me; null } else { waiting -= other; Match(other, me) }
        }
        if (paired != null) {
            // Guest hears first: the host only starts talking after its own notice.
            paired.guest.match.complete(paired)
            paired.host.match.complete(paired)
            runCatching { paired.guest.session.send("~M|guest|${paired.host.player.name}") }
            runCatching { paired.host.session.send("~M|host|${paired.guest.player.name}") }
        }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text || !me.match.isCompleted) continue
                val m = me.match.getCompleted()
                val text = frame.readText()
                if (me === m.host) m.watch(text)
                runCatching { m.other(me).session.send(text) }
            }
        } finally {
            lock.withLock { waiting -= me }
            if (me.match.isCompleted) {
                val m = me.match.getCompleted()
                m.left(me)
                runCatching { m.other(me).session.close(CloseReason(CloseReason.Codes.NORMAL, "opponent left")) }
            }
        }
    }

    suspend fun queued() = lock.withLock { waiting.size }
}
