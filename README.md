<p align="center">
  <img src="https://helltar.com/projects/varta/varta-avatar-rounded.png" width="128" alt="varta-avatar-rounded">
</p>

<h1 align="center">Varta</h1>

Did the stack come back up? Varta answers that in Telegram — once, after a reboot, without being
asked. Then it goes quiet, reporting later problems and recoveries without narrating ordinary
restarts.

A boot report in Telegram looks like this:

```text
🖥 atlas

✅ 3 healthy · ⚪ 1 unverified

my-stack

✅ aibot
✅ postgres
✅ varta
⚪ nginx — no healthcheck

1 service has no healthcheck — it is unverified, not confirmed.
```

See [how Varta works](docs/how-it-works.md) for the exact report behavior, status meanings and Docker
socket access.

## Run it

Varta is made for a Linux host running Docker; the image is built for amd64. It negotiates a
compatible Engine API version with the daemon (currently 1.24–1.55), and needs a Telegram bot and read
access to the Docker socket.

Create a bot for Varta with `@BotFather`, then send it `/start`.

On the server where Varta will run:

```bash
mkdir varta && cd varta && curl -fsSLO \
  "https://github.com/Helltar/varta/raw/master/{compose.yaml,.env.example}" && \
  mv .env.example .env
```

Get the Docker socket's group id:

```bash
stat -c '%g' /var/run/docker.sock
```

Put the result in `DOCKER_GID` in `.env`, together with `BOT_TOKEN` and `CHAT_ID`.

Then start Varta and check its log:

```bash
docker compose up -d
docker compose logs varta
```

The boot report should arrive in Telegram. If Telegram rejects it, the complete undelivered report is
kept in the log and retried automatically.

Already have a Compose stack? Copy the `varta` service from [compose.yaml](compose.yaml) into it. This
also keeps Varta grouped with the rest of that stack in its own reports.

All user-facing configuration is described in [.env.example](.env.example). Nothing is installed
inside the watched containers, and nothing about them changes.

## Build from source

```bash
git clone https://github.com/Helltar/varta.git && cd varta

# container image
docker build -t varta .

# fat jar -> build/libs/, needs JDK 21
./gradlew shadowJar
```
