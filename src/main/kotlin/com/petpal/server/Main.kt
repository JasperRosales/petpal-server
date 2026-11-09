package com.petpal.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds

private val ID = Regex("[A-Za-z0-9-]{16,64}")

/** Names and pet fields go into tab/pipe separated lines: strip separators and cap the length. */
private fun clean(s: String?, max: Int) = s.orEmpty().replace(Regex("[\\t\\n\\r|#~]"), " ").trim().take(max)

fun Player.row(rank: Int) = listOf(rank, name, species, stage, rating, wins, losses, draws).joinToString("\t")

fun Application.petpal(store: Store) {
    val lobby = Lobby(store)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }
    routing {
        get("/health") { call.respondText("ok") }
        get("/") { call.respondText("PetPal online: ${lobby.queued()} waiting") }

        /** "top" rows for the board, then a "me" row (rank 0 = not ranked yet) when ?id= is given. */
        get("/leaderboard") {
            val id = call.request.queryParameters["id"]
            val text = buildString {
                store.top(10).forEachIndexed { i, p -> append("top\t").append(p.row(i + 1)).append('\n') }
                if (id != null) store.get(id)?.let { append("me\t").append(it.row(store.rank(id) ?: 0)).append('\n') }
            }
            call.respondText(text, ContentType.Text.Plain)
        }

        webSocket("/play") {
            val q = call.request.queryParameters
            val id = q["id"].orEmpty()
            val name = clean(q["name"], 20)
            if (!ID.matches(id) || name.isEmpty()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad player"))
                return@webSocket
            }
            val player = store.upsert(id, name, clean(q["species"], 20), clean(q["stage"], 10))
            lobby.play(this, player, clean(q["v"], 10))
        }
    }
}

fun main() {
    val store = Store.create(System.getenv("DATABASE_URL"))
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { petpal(store) }.start(wait = true)
}
