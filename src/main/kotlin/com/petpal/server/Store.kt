package com.petpal.server

import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.pow
import kotlin.math.roundToInt

data class Player(
    val id: String,
    val name: String,
    val species: String,
    val stage: String,
    val rating: Int = Elo.START,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
)

object Elo {
    const val START = 1000
    private const val K = 32

    /** New ratings for a and b. [score] = 1 a won, 0 a lost, 0.5 draw. */
    fun update(a: Int, b: Int, score: Double): Pair<Int, Int> {
        val expected = 1 / (1 + 10.0.pow((b - a) / 400.0))
        val delta = (K * (score - expected)).roundToInt()
        return a + delta to b - delta
    }
}

/** Ratings and records. Postgres when DATABASE_URL is set, in memory otherwise (local runs, tests). */
interface Store {
    /** Creates the player or refreshes their name and pet; keeps the record. */
    fun upsert(id: String, name: String, species: String, stage: String): Player
    fun get(id: String): Player?
    fun top(n: Int): List<Player>
    /** 1-based place on the board. */
    fun rank(id: String): Int?

    /** Records one match and updates both ratings. */
    fun record(winner: String, loser: String, draw: Boolean) {
        val w = get(winner) ?: return
        val l = get(loser) ?: return
        val (rw, rl) = Elo.update(w.rating, l.rating, if (draw) 0.5 else 1.0)
        save(
            w.copy(rating = rw, wins = w.wins + if (draw) 0 else 1, draws = w.draws + if (draw) 1 else 0),
            l.copy(rating = rl, losses = l.losses + if (draw) 0 else 1, draws = l.draws + if (draw) 1 else 0),
        )
    }

    fun save(vararg players: Player)

    companion object {
        fun create(databaseUrl: String?): Store = if (databaseUrl.isNullOrBlank()) MemoryStore() else PgStore(databaseUrl)
    }
}

class MemoryStore : Store {
    private val players = mutableMapOf<String, Player>()

    @Synchronized override fun upsert(id: String, name: String, species: String, stage: String): Player {
        val p = players[id]?.copy(name = name, species = species, stage = stage) ?: Player(id, name, species, stage)
        players[id] = p
        return p
    }

    @Synchronized override fun get(id: String) = players[id]
    @Synchronized override fun top(n: Int) = ranked().take(n)
    @Synchronized override fun rank(id: String) = ranked().indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
    @Synchronized override fun save(vararg players: Player) = players.forEach { this.players[it.id] = it }

    /** Only players who have finished a match are ranked. */
    private fun ranked() = players.values.filter { it.wins + it.losses + it.draws > 0 }
        .sortedWith(compareByDescending<Player> { it.rating }.thenByDescending { it.wins })
}

class PgStore(databaseUrl: String) : Store {
    private val jdbc: String
    private val user: String?
    private val pass: String?

    init {
        // Render gives postgresql://user:pass@host:port/db
        val uri = URI(databaseUrl)
        val info = uri.userInfo?.split(':', limit = 2)
        user = info?.getOrNull(0)
        pass = info?.getOrNull(1)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        jdbc = "jdbc:postgresql://${uri.host}$port${uri.path}"
        use {
            it.createStatement().execute(
                """CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, species TEXT NOT NULL, stage TEXT NOT NULL,
                    rating INT NOT NULL, wins INT NOT NULL, losses INT NOT NULL, draws INT NOT NULL)""",
            )
        }
    }

    private fun <T> use(block: (Connection) -> T): T = synchronized(this) {
        DriverManager.getConnection(jdbc, user, pass).use(block)
    }

    private fun java.sql.ResultSet.player() = Player(
        getString("id"), getString("name"), getString("species"), getString("stage"),
        getInt("rating"), getInt("wins"), getInt("losses"), getInt("draws"),
    )

    override fun upsert(id: String, name: String, species: String, stage: String): Player = use { c ->
        c.prepareStatement(
            """INSERT INTO players VALUES (?, ?, ?, ?, ${Elo.START}, 0, 0, 0)
               ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, species = EXCLUDED.species, stage = EXCLUDED.stage""",
        ).apply { setString(1, id); setString(2, name); setString(3, species); setString(4, stage) }.executeUpdate()
        null
    }.let { get(id)!! }

    override fun get(id: String): Player? = use { c ->
        c.prepareStatement("SELECT * FROM players WHERE id = ?").apply { setString(1, id) }
            .executeQuery().let { if (it.next()) it.player() else null }
    }

    override fun top(n: Int): List<Player> = use { c ->
        c.prepareStatement(
            "SELECT * FROM players WHERE wins + losses + draws > 0 ORDER BY rating DESC, wins DESC LIMIT ?",
        ).apply { setInt(1, n) }.executeQuery().let { rs -> buildList { while (rs.next()) add(rs.player()) } }
    }

    override fun rank(id: String): Int? = use { c ->
        c.prepareStatement(
            """SELECT 1 + (SELECT COUNT(*) FROM players o WHERE o.wins + o.losses + o.draws > 0
                 AND (o.rating > p.rating OR (o.rating = p.rating AND o.wins > p.wins)))
               FROM players p WHERE p.id = ? AND p.wins + p.losses + p.draws > 0""",
        ).apply { setString(1, id) }.executeQuery().let { if (it.next()) it.getInt(1) else null }
    }

    override fun save(vararg players: Player) = use { c ->
        c.autoCommit = false
        val st = c.prepareStatement("UPDATE players SET rating = ?, wins = ?, losses = ?, draws = ? WHERE id = ?")
        players.forEach { p ->
            st.setInt(1, p.rating); st.setInt(2, p.wins); st.setInt(3, p.losses); st.setInt(4, p.draws); st.setString(5, p.id)
            st.executeUpdate()
        }
        c.commit()
    }
}
