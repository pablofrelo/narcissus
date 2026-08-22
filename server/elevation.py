"""
Doliczanie przewyższenia z Numerycznego Modelu Terenu GUGiK.

Telefon nie ma barometru, a wysokość z GNSS to wysokość elipsoidalna
z szumem rzędu kilkunastu metrów — sumowanie jej daje setki metrów
podjazdu na płaskim parkingu. Dlatego telefon zapisuje wyłącznie lat/lon,
a wysokość dokładamy tutaj, z modelu terenu o siatce metrowej.

Usługa GUGiK przyjmuje współrzędne w układzie PUWG1992 (EPSG:2180)
i zwraca wysokość w układzie PL-KRON86-NH:

    http://services.gugik.gov.pl/nmt/?request=GetHByXY&x=652222.45&y=252323.45

Wynik jest POWTARZALNY: ten sam ślad zawsze da tę samą liczbę, co przy
GNSS-ie nie zachodziło nigdy.
"""

import json
import math
import sqlite3
import urllib.parse
import urllib.request
from pathlib import Path

from pyproj import Transformer

NMT_URL = "http://services.gugik.gov.pl/nmt/"

# WGS84 -> PUWG1992. always_xy: podajemy (lon, lat), dostajemy (x, y).
_to_pl = Transformer.from_crs("EPSG:4326", "EPSG:2180", always_xy=True)

# Ślad ma tysiące punktów, a NMT pytamy po jednym. Bierzemy więc co
# najmniej co PRÓBKA metrów — teren nie zmienia się szybciej, a liczba
# zapytań spada z tysięcy do setek.
SAMPLE_EVERY_M = 40.0

# Poniżej tego progu różnicę wysokości traktujemy jako szum modelu,
# nie jako podjazd. Bez histerezy nawet czysty NMT nabija przewyższenie
# na płaskim odcinku.
ASCENT_THRESHOLD_M = 3.0

CACHE = Path("/data/nmt_cache.db")


def _cache():
    conn = sqlite3.connect(CACHE)
    conn.execute("CREATE TABLE IF NOT EXISTS h (k TEXT PRIMARY KEY, v REAL)")
    return conn


def height(lon: float, lat: float) -> float | None:
    """Wysokość terenu dla punktu WGS84. None, gdy usługa nie odpowiada."""
    x, y = _to_pl.transform(lon, lat)

    # Klucz cache zaokrąglony do metra — siatka NMT i tak ma metr,
    # a te same trasy przejeżdża się wielokrotnie.
    key = f"{x:.0f}:{y:.0f}"

    conn = _cache()
    row = conn.execute("SELECT v FROM h WHERE k = ?", (key,)).fetchone()
    if row:
        conn.close()
        return row[0]

    params = urllib.parse.urlencode({"request": "GetHByXY", "x": x, "y": y})
    try:
        with urllib.request.urlopen(f"{NMT_URL}?{params}", timeout=10) as r:
            text = r.read().decode("utf-8", "replace").strip()
        value = float(text.split()[-1])
    except Exception:
        conn.close()
        return None

    conn.execute("INSERT OR REPLACE INTO h VALUES (?, ?)", (key, value))
    conn.commit()
    conn.close()
    return value


def _haversine(a, b) -> float:
    r = 6371000.0
    p1, p2 = math.radians(a[1]), math.radians(b[1])
    dp = p2 - p1
    dl = math.radians(b[0] - a[0])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def ascent_for_track(path: Path) -> float | None:
    """Suma podjazdów dla śladu w formacie jsonl."""
    if not path.exists():
        return None

    points = []
    for line in path.read_text().splitlines():
        try:
            o = json.loads(line)
            points.append((o["lon"], o["lat"]))
        except Exception:
            continue

    if len(points) < 2:
        return None

    # Próbkowanie co SAMPLE_EVERY_M metrów wzdłuż trasy.
    sampled = [points[0]]
    run = 0.0
    for i in range(1, len(points)):
        run += _haversine(points[i - 1], points[i])
        if run >= SAMPLE_EVERY_M:
            sampled.append(points[i])
            run = 0.0

    heights = []
    for lon, lat in sampled:
        h = height(lon, lat)
        if h is not None:
            heights.append(h)

    if len(heights) < 2:
        return None

    # Histereza: sumujemy dopiero wtedy, gdy wzrost od ostatniego
    # zatwierdzonego poziomu przekroczy próg. Ta sama zasada, co przy
    # punkcie zaczepienia w liczeniu dystansu.
    total = 0.0
    anchor = heights[0]
    for h in heights[1:]:
        delta = h - anchor
        if delta > ASCENT_THRESHOLD_M:
            total += delta
            anchor = h
        elif delta < -ASCENT_THRESHOLD_M:
            anchor = h

    return round(total, 1)
