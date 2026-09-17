# How Varta works

When Varta starts, it reads the watched containers from Docker and waits for them to stop starting or
restarting. It sends one boot report, then polls quietly and reports only settled state changes and
crash loops.

## Boot report

```text
🖥 atlas

✅ 6 healthy · ⚪ 2 unverified

my-stack

✅ aibot
✅ dlpbot
✅ postgres
✅ twitchbot
✅ varta
✅ vusan
⚪ grafana — no healthcheck
⚪ nginx — no healthcheck

2 services have no healthcheck — they are unverified, not confirmed.
```

Services are grouped by Compose project and listed with healthy first, unverified next, problems after
that and transient states last. Standalone containers are grouped under `standalone`; scaled Compose
replicas include their container names so they remain distinguishable.

Varta's own start triggers this report. With `restart: unless-stopped`, it comes up with the stack
after a host reboot. Restarting Varta after a deploy gives the same fresh report without a systemd
unit, cron job or host agent.

If the watched services do not settle within `SETTLE_TIMEOUT_SECONDS`, Varta reports them anyway. The
header says `Still starting` instead of claiming that the stack is healthy.

If nothing matches `PROJECTS` and `IGNORE`, the report says `No containers to watch` once the settle
timeout has passed.

If the roll call is too long for Telegram, Varta drops the healthy and unverified rows, keeps as many
problem rows as fit and says how many were omitted.

## What the marks mean

| Mark | Meaning |
|---|---|
| ✅ | running, healthcheck passes |
| ⚪ | running, no healthcheck |
| ❌ | running, healthcheck fails |
| 💥 | keeps restarting — a crash loop |
| ⛔ | not running |
| 🕓 | healthcheck is still starting |
| 🔄 | container is restarting |

Docker can only measure service health when the container has a configured healthcheck. Without one,
Docker knows that a process is running, but not whether it is still doing its job.

That is why ⚪ stays separate from ✅ and is counted in the footer as unverified rather than confirmed.
The 🕓 and 🔄 marks appear only when the boot report reaches its settle timeout; they are not announced
as later state changes.

## Later changes

After the boot report, Varta sends a message when a watched service moves between settled states:

```text
🖥 atlas

❌ dlpbot — unhealthy
Up 3 hours (unhealthy)
```

Starting and restarting are treated as states a container is only passing through. An ordinary
restart therefore produces no message if the service ends in the same state where it began.

A crash loop is the exception, because a container that keeps dying under a restart policy never
leaves those states and would otherwise stay silent forever. Varta follows Docker's restart count
instead:

```text
🖥 atlas

💥 vusan — restarting repeatedly
restarted 9 times in 6m
```

A service is reported as looping once it has been restarted at least three times and the restarts
have gone on for `SETTLE_TIMEOUT_SECONDS` — the same allowance a booting stack gets, so a service that
crashes a few times while its database comes up is not called broken. The loop is reported once. When
the service has then stayed up for the same length of time, Varta reports its recovery. A container
that runs for longer than that between crashes is not recognised as looping.

A service already looping when Varta starts is in the boot report as 🕓 or 🔄 — or as ⚪, without a
healthcheck — and the 💥 message follows it.

A newly discovered healthy service is also quiet; a newly discovered broken service is reported.
Removing a container does not produce a change message. Restart Varta whenever a deploy should produce
a fresh roll call of the whole stack.

## What Varta watches

By default, Varta reports every container visible through the socket, including stopped containers.
Set `PROJECTS` to watch only named Compose projects and `IGNORE` to leave out individual service or
container names.

Containers created by `docker compose run` are always ignored. They are meant to end and would
otherwise remain in the report as broken containers.

The full list of settings and defaults is in [.env.example](../.env.example).

## Which host a report came from

Every message carries the name of the machine it describes. Watching a second host is otherwise a
trap: two hosts running the same Compose project send reports that are identical down to the
project heading, arriving hours apart with no context.

`HOST_LABEL` sets the name. Left unset, Varta asks the daemon for it (`GET /info`, field `Name`),
so a second host usually needs no configuration at all. Deliberately *not* the container's own
hostname — inside a container that is the container id, which names nothing useful.

If the daemon cannot be asked, reports go out unlabelled rather than not at all: an unknown host
name is cosmetic, unlike an unreadable socket, which stops Varta at startup.

## Docker socket access

Varta supports Docker Engine API 1.24–1.55 and negotiates the highest version supported by both sides.
It lists the stack once, then inspects each watched container that is running or restarting: that
is where its authoritative health state and its restart count come from. Every request is a `GET`; Varta never changes anything through the daemon. The `:ro`
mount prevents changes to the socket file itself, but it does not make the Docker API read-only.
Treat access to the socket as root-level access to the host.

The container runs as a non-root user. `DOCKER_GID` gives that user the host socket's group id, which
the image cannot know in advance.

`DOCKER_SOCKET` may point to another Unix socket path. Varta does not connect to HTTP or TCP socket
proxies.

## Varta's own healthcheck

After every successful Docker read, Varta refreshes `/tmp/health`. Its own `HEALTHCHECK` fails when
that file gets too old, so `docker ps` shows Varta as unhealthy if it stops polling the daemon. The
file is removed at startup, so one left over from before a restart never vouches for a run that has
not read the daemon yet.

The default window is deliberately wide. Raise `HEALTH_STALE_SECONDS` when
`POLL_INTERVAL_SECONDS` is longer than a couple of minutes.

## If Telegram delivery fails

An undelivered report is written in full to the Varta log at `WARN` — once, not on every retry — kept
in memory and retried with exponential backoff, up to five minutes between attempts. Reports made in
the meantime wait behind it, in order, and are logged the same way; at most 100 wait at a time, after
which the oldest is dropped. Read the log with `docker logs varta` or the equivalent Compose command.
