package com.petpal.server

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    private fun url(id: String, name: String) = "/play?id=$id&name=$name&species=FOX&stage=KID&v=21"

    private suspend fun DefaultClientWebSocketSession.line() = (incoming.receive() as Frame.Text).readText()

    @Test fun pairsRelaysAndScores() = testApplication {
        val store = MemoryStore()
        application { petpal(store) }
        val client = createClient { install(WebSockets) }
        coroutineScope {
            val host = async {
                client.webSocket(url("aaaaaaaaaaaaaaaa", "Ace")) {
                    assertEquals("~M|host|Bo", line())
                    send("H|hello")
                    assertEquals("C|3", line())
                    send("V|hit\nS|0,1,0,0,0|1,1") // host's team won
                    runCatching { line() } // waits for the guest to hang up
                }
            }
            val guest = async {
                kotlinx.coroutines.delay(200)
                client.webSocket(url("bbbbbbbbbbbbbbbb", "Bo")) {
                    assertEquals("~M|guest|Ace", line())
                    assertEquals("H|hello", line())
                    send("C|3")
                    assertTrue(line().startsWith("V|hit"))
                }
            }
            guest.await()
            host.await()
        }
        val board = client.get("/leaderboard?id=bbbbbbbbbbbbbbbb").bodyAsText().lines().filter { it.isNotEmpty() }
        assertEquals("top\t1\tAce\tFOX\tKID\t1016\t1\t0\t0", board[0])
        assertEquals("me\t2\tBo\tFOX\tKID\t984\t0\t1\t0", board[2])
    }

    @Test fun elo() {
        assertEquals(1016 to 984, Elo.update(1000, 1000, 1.0))
        assertEquals(1000 to 1000, Elo.update(1000, 1000, 0.5))
    }
}
