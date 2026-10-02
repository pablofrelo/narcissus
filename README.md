# NARCISSUS

A bike, run and walk tracker for Android that looks like a terminal on the
*Nostromo*: green phosphor on black, scanlines, the Spleen console font.
English by default; tap `[EN]` in the header to switch to Polish.

*[Polski](README.pl.md)*

## What it does

- **Three modes**: BIKE, RUN, WALK. Each mode
  declares its own set of readouts and its own GNSS thresholds.
- **Raw GNSS**, not Fused. In side-by-side tests on two phones the raw
  `GPS_PROVIDER` held up better in the countryside, where Fused could lose
  minutes of track.
- **Distance without phantom kilometres**: fixes are filtered by accuracy
  and by implausible jumps, and distance only grows once the position moves
  away from an anchor by more than the noise floor. Standing at a traffic
  light adds nothing.
- **Pace**: smoothed over the last 20 s, plus the pace of the current
  kilometre. **Cadence** from the step counter.
- **Session log** stored on the phone, with a map of each track.
- **Story card**: a 1080×1920 image of a session (your photo fading into
  black, the numbers, a small map), shared through the system share sheet.
- **Hold to start and stop** (2 s), so a brush of the finger can't end a
  workout halfway.
- **Burn-in protection**: the layout drifts slowly across the AMOLED panel.

## Sync server (optional)

`server/` holds a small FastAPI service that keeps sessions in SQLite and
adds **elevation gain** from the Polish national terrain model (GUGiK NMT,
1 m grid). The phone stores only latitude and longitude: GNSS altitude is
too noisy to sum, and the terrain model gives the same number for the same
track every time. The elevation lookup works for Poland only.

The server has **no authentication**. It is meant to listen inside a
WireGuard network, and that network is the trust boundary. Don't expose it
publicly.

    cd server
    docker compose up -d

Then in the app open **LOG → [SRV]**, enter the server address (e.g.
`http://10.0.0.1:8765`) and save. With the field empty, sync is off and the
app works fully offline. The app talks to the server over plain HTTP, which
is fine inside an encrypted tunnel; change the bind address in
`server/compose.yml` to your own network.

## Building

Android 11 (API 30) or newer.

    ./gradlew installDebug

## About the code

NARCISSUS was written by an AI (Claude, by Anthropic) under my direction:
I set the goal and the look, tested it on my phone on walks and rides,
and reported what was wrong, round after round. I did not write the code
myself, and I want that to be clear up front.

## License

[MIT](LICENSE). The Spleen font by Frederic Cambus is under the
[BSD 2-Clause license](licenses/SPLEEN.txt).
