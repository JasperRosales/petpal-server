# PetPal server

Online matchmaking, battle relay and ranked leaderboard for the [PetPal](https://github.com/JasperRosales/petpal) app.

- `GET /health` – liveness
- `GET /leaderboard?id=<player>` – top 10 (`top` rows) plus your own row (`me`), tab separated
- `WS /play?id=&name=&species=&stage=&v=` – queue for a random 1v1. Players on the same app version are paired;
  the first in line hosts the battle and every frame is relayed to the other. The server reads the host's final
  state line to score the match (Elo, K=32, start 1000). Leaving a match under way counts as a loss.

Ratings live in Postgres when `DATABASE_URL` is set, in memory otherwise.

```sh
./gradlew test   # tests
./gradlew run    # local server on :8080 (PORT to change)
```

Deployed on Render as a Docker web service (see `Dockerfile`).
