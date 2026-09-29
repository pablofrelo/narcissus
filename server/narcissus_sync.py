#!/usr/bin/env python3
"""
Serwer synchronizacji dla narcissus-2.

Zmiana wobec pierwszej budowy: protokół zna NAGROBKI. Skasowana sesja nie
znika z bazy, tylko dostaje deleted_at. Bez tego serwer nie umie odróżnić
"skasowane" od "to urządzenie jeszcze tego nie ma" i przy każdej wymianie
grzecznie przywraca usunięte sesje — dokładnie to działo się z testami.

Uwierzytelniania brak celowo: serwer nasłuchuje w meshu WireGuard i to mesh
jest granicą zaufania. NIE wystawiaj tego na publiczny interfejs.

    pip install fastapi uvicorn
    uvicorn narcissus_sync:app --host 0.0.0.0 --port 8765
"""

import sqlite3
import time
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, Request, Response
from fastapi.responses import JSONResponse, PlainTextResponse

from elevation import ascent_for_track

DATA = Path("/data")
TRACKS = DATA / "tracks"
DB = DATA / "sessions.db"

TRACKS.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="narcissus-sync")

FIELDS = [
    "id", "mode", "startedAt", "endedAt", "distanceM", "elapsedMs", "movingMs",
    "avgMovingSpeedMps", "maxSpeedMps", "totalSteps", "acceptedFixes",
    "rejectedFixes", "ascentM", "pointCount", "test", "updatedAt", "deletedAt",
]


def db():
    conn = sqlite3.connect(DB)
    conn.row_factory = sqlite3.Row
    conn.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            mode TEXT,
            startedAt INTEGER,
            endedAt INTEGER,
            distanceM REAL,
            elapsedMs INTEGER,
            movingMs INTEGER,
            avgMovingSpeedMps REAL,
            maxSpeedMps REAL,
            totalSteps INTEGER,
            acceptedFixes INTEGER,
            rejectedFixes INTEGER,
            ascentM REAL,
            pointCount INTEGER,
            test INTEGER,
            updatedAt INTEGER,
            deletedAt INTEGER,
            device TEXT
        )
    """)
    conn.execute("CREATE INDEX IF NOT EXISTS idx_updated ON sessions(updatedAt)")
    return conn


def now_ms() -> int:
    return int(time.time() * 1000)


@app.post("/sync")
async def sync(request: Request):
    payload = await request.json()
    device = payload.get("device", "?")
    since = int(payload.get("since", 0))
    incoming = payload.get("sessions", [])

    conn = db()
    stamp = now_ms()
    accepted = set()

    for s in incoming:
        sid = s.get("id")
        if not sid:
            continue

        row = conn.execute(
            "SELECT updatedAt, ascentM FROM sessions WHERE id = ?", (sid,)
        ).fetchone()

        # Wygrywa nowszy updatedAt. Nagrobek nie jest wyjątkiem — jeśli jest
        # świeższy, wygrywa i kasuje po tej stronie.
        if row and int(row["updatedAt"] or 0) >= int(s.get("updatedAt", 0)):
            continue

        values = {f: s.get(f) for f in FIELDS}
        values["test"] = 1 if s.get("test") else 0
        values["device"] = device

        # Przewyższenie liczy tylko serwer. Urządzenie z nowszym updatedAt,
        # które jeszcze go nie ma, nie może skasować policzonej wartości.
        # Scalony rekord dostaje świeży znacznik i wraca do urządzenia.
        merged = False
        if values.get("ascentM") is None and row and row["ascentM"] is not None:
            values["ascentM"] = row["ascentM"]
            values["updatedAt"] = stamp
            merged = True

        cols = ", ".join(values.keys())
        marks = ", ".join("?" for _ in values)
        conn.execute(
            f"INSERT OR REPLACE INTO sessions ({cols}) VALUES ({marks})",
            tuple(values.values()),
        )
        if not merged:
            accepted.add(sid)

        if values.get("deletedAt"):
            track = TRACKS / f"{sid}.jsonl"
            track.unlink(missing_ok=True)

    conn.commit()

    # Odsyłamy wszystko, co zmieniło się po naszej stronie od "since",
    # z pominięciem tego, co przed chwilą PRZYJĘLIŚMY od tego urządzenia.
    # Nie wszystkiego, co przysłało: jeśli nasza wersja wygrała (np. ma już
    # policzone przewyższenie), urządzenie musi ją dostać z powrotem.
    rows = conn.execute(
        "SELECT * FROM sessions WHERE updatedAt > ?", (since,)
    ).fetchall()

    out = []
    for r in rows:
        if r["id"] in accepted:
            continue
        item = {f: r[f] for f in FIELDS}
        item["test"] = bool(r["test"])
        out.append(item)

    # Ślady, których nie mamy, a sesja twierdzi, że istnieją.
    want = []
    for s in incoming:
        sid = s.get("id")
        if not sid or s.get("deletedAt"):
            continue
        if int(s.get("pointCount") or 0) <= 0:
            continue
        if not (TRACKS / f"{sid}.jsonl").exists():
            want.append(sid)

    conn.close()

    return JSONResponse({"now": stamp, "sessions": out, "wantTracks": want})


def resolve_ascent(session_id: str):
    """
    Liczy przewyższenie w tle i zapisuje przy sesji.

    W tle, bo jedno zapytanie do NMT na każde czterdzieści metrów trasy
    znaczy setki wywołań — telefon nie ma na co czekać. Wynik dojedzie
    przy następnej synchronizacji, bo podbijamy updatedAt.
    """
    value = ascent_for_track(TRACKS / f"{session_id}.jsonl")
    if value is None:
        return

    conn = db()
    conn.execute(
        "UPDATE sessions SET ascentM = ?, updatedAt = ? WHERE id = ?",
        (value, now_ms(), session_id),
    )
    conn.commit()
    conn.close()


@app.put("/track/{session_id}")
async def put_track(session_id: str, request: Request, tasks: BackgroundTasks):
    body = await request.body()
    (TRACKS / f"{session_id}.jsonl").write_bytes(body)

    # Ślad dopiero co dotarł, więc to jedyny moment, w którym wiadomo,
    # że jest co liczyć.
    tasks.add_task(resolve_ascent, session_id)

    return {"ok": True, "bytes": len(body)}


@app.post("/reascent")
async def reascent(tasks: BackgroundTasks):
    """Przeliczenie przewyższenia dla sesji, które go jeszcze nie mają."""
    conn = db()
    rows = conn.execute(
        "SELECT id FROM sessions WHERE ascentM IS NULL AND deletedAt IS NULL"
    ).fetchall()
    conn.close()

    for r in rows:
        tasks.add_task(resolve_ascent, r["id"])

    return {"queued": len(rows)}


@app.get("/track/{session_id}")
async def get_track(session_id: str):
    f = TRACKS / f"{session_id}.jsonl"
    if not f.exists():
        return Response(status_code=404)
    return PlainTextResponse(f.read_text(), media_type="application/x-ndjson")


@app.get("/health")
async def health():
    conn = db()
    alive = conn.execute(
        "SELECT COUNT(*) c FROM sessions WHERE deletedAt IS NULL"
    ).fetchone()["c"]
    graves = conn.execute(
        "SELECT COUNT(*) c FROM sessions WHERE deletedAt IS NOT NULL"
    ).fetchone()["c"]
    conn.close()
    return {"sessions": alive, "tombstones": graves, "now": now_ms()}
