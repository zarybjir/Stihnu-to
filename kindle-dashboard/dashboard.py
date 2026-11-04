#!/usr/bin/env python3
"""E-ink dashboard pro starý Kindle: počasí, kalendář a ovládání Tapo zařízení.

Server běží na čemkoli, co je stále zapnuté v domácí síti (Raspberry Pi,
NAS, starý notebook). Kindle jen otevře stránku v prohlížeči.

    python dashboard.py              # ostrý provoz, čte config.toml
    python dashboard.py --demo       # vymyšlená zařízení, bez Tapo účtu
"""

from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import html
import logging
import time
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

import aiohttp
from aiohttp import web

log = logging.getLogger("dashboard")

# --------------------------------------------------------------------------
# Konfigurace
# --------------------------------------------------------------------------


def load_config(path: Path) -> dict:
    cfg = {}
    if path.exists():
        with path.open("rb") as f:
            cfg = tomllib.load(f)
    elif not ARGS.demo:
        raise SystemExit(f"Chybí {path} — zkopíruj config.example.toml na config.toml.")
    server = cfg.setdefault("server", {})
    server.setdefault("port", 8080)
    server.setdefault("token", "")
    server.setdefault("refresh", 300)
    server.setdefault("tz", "Europe/Prague")
    w = cfg.setdefault("weather", {})
    w.setdefault("name", "Praha")
    w.setdefault("lat", 50.0755)
    w.setdefault("lon", 14.4378)
    c = cfg.setdefault("calendar", {})
    c.setdefault("ics", [])
    c.setdefault("days", 7)
    t = cfg.setdefault("tapo", {})
    t.setdefault("hosts", [])
    t.setdefault("step", 0.5)
    return cfg


# --------------------------------------------------------------------------
# Jednoduchá cache s časovým limitem
# --------------------------------------------------------------------------


class Cached:
    """Drží poslední úspěšný výsledek; při chybě vrací starý a chybu zaloguje."""

    def __init__(self, fetch, ttl: int):
        self.fetch, self.ttl = fetch, ttl
        self.value, self.at, self.error = None, 0.0, None
        self.lock = asyncio.Lock()

    async def get(self):
        async with self.lock:
            if self.value is None or time.monotonic() - self.at > self.ttl:
                try:
                    self.value = await self.fetch()
                    self.at, self.error = time.monotonic(), None
                except Exception as e:  # síť, špatné URL, výpadek služby…
                    log.warning("%s selhalo: %s", self.fetch.__name__, e)
                    self.error = str(e) or type(e).__name__
                    self.at = time.monotonic() - self.ttl + 60  # zkus znovu za minutu
            return self.value


# --------------------------------------------------------------------------
# Počasí — Open-Meteo (zdarma, bez klíče)
# --------------------------------------------------------------------------

WMO = {
    0: "Jasno", 1: "Skoro jasno", 2: "Polojasno", 3: "Zataženo",
    45: "Mlha", 48: "Mrznoucí mlha",
    51: "Slabé mrholení", 53: "Mrholení", 55: "Husté mrholení",
    56: "Mrznoucí mrholení", 57: "Mrznoucí mrholení",
    61: "Slabý déšť", 63: "Déšť", 65: "Silný déšť",
    66: "Mrznoucí déšť", 67: "Mrznoucí déšť",
    71: "Slabé sněžení", 73: "Sněžení", 75: "Silné sněžení", 77: "Sněhová zrna",
    80: "Přeháňky", 81: "Přeháňky", 82: "Prudké přeháňky",
    85: "Sněhové přeháňky", 86: "Sněhové přeháňky",
    95: "Bouřka", 96: "Bouřka s kroupami", 99: "Bouřka s kroupami",
}

DAYS = ["Po", "Út", "St", "Čt", "Pá", "So", "Ne"]
DAYS_LONG = ["pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle"]
MONTHS = ["ledna", "února", "března", "dubna", "května", "června", "července",
          "srpna", "září", "října", "listopadu", "prosince"]


async def fetch_weather(cfg: dict, http: aiohttp.ClientSession) -> dict:
    w = cfg["weather"]
    params = {
        "latitude": w["lat"], "longitude": w["lon"], "timezone": cfg["server"]["tz"],
        "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m",
        "hourly": "temperature_2m,precipitation_probability,weather_code",
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        "forecast_days": 5, "wind_speed_unit": "kmh",
    }
    async with http.get("https://api.open-meteo.com/v1/forecast", params=params,
                        timeout=aiohttp.ClientTimeout(total=15)) as r:
        r.raise_for_status()
        return await r.json()


def demo_weather() -> dict:
    today = dt.date.today()
    now = dt.datetime.now().replace(minute=0, second=0, microsecond=0)
    hours = [now + dt.timedelta(hours=i) for i in range(24)]
    return {
        "current": {"temperature_2m": 7.4, "apparent_temperature": 4.9, "weather_code": 3,
                    "wind_speed_10m": 14, "relative_humidity_2m": 81},
        "hourly": {"time": [h.isoformat(timespec="minutes") for h in hours],
                   "temperature_2m": [7 + (i % 12) * 0.4 for i in range(24)],
                   "precipitation_probability": [10, 15, 30, 55, 70, 40] * 4,
                   "weather_code": [3, 3, 61, 63, 61, 2] * 4},
        "daily": {"time": [(today + dt.timedelta(days=i)).isoformat() for i in range(5)],
                  "weather_code": [3, 61, 2, 0, 71],
                  "temperature_2m_max": [9, 8, 11, 12, 3], "temperature_2m_min": [3, 4, 2, 0, -2],
                  "precipitation_probability_max": [20, 80, 10, 0, 60]},
    }


# --------------------------------------------------------------------------
# Kalendář — libovolné .ics odkazy
# --------------------------------------------------------------------------


@dataclass
class Event:
    start: dt.datetime
    end: dt.datetime | None
    title: str
    all_day: bool


async def fetch_calendar(cfg: dict, http: aiohttp.ClientSession) -> list[Event]:
    import icalendar
    import recurring_ical_events

    tz = ZoneInfo(cfg["server"]["tz"])
    today = dt.datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0)
    until = today + dt.timedelta(days=cfg["calendar"]["days"])
    events: list[Event] = []

    def to_local(v) -> tuple[dt.datetime, bool]:
        if isinstance(v, dt.datetime):
            return (v.replace(tzinfo=tz) if v.tzinfo is None else v.astimezone(tz)), False
        return dt.datetime(v.year, v.month, v.day, tzinfo=tz), True

    for url in cfg["calendar"]["ics"]:
        url = url.replace("webcal://", "https://", 1)
        async with http.get(url, timeout=aiohttp.ClientTimeout(total=20)) as r:
            r.raise_for_status()
            data = await r.read()
        cal = icalendar.Calendar.from_ical(data)
        for ev in recurring_ical_events.of(cal).between(today, until):
            if str(ev.get("STATUS", "")).upper() == "CANCELLED":
                continue
            start, all_day = to_local(ev.decoded("DTSTART"))
            end = to_local(ev.decoded("DTEND"))[0] if ev.get("DTEND") else None
            if end and end <= dt.datetime.now(tz) and not all_day:
                continue  # dnešní, už skončilo
            events.append(Event(start, end, str(ev.get("SUMMARY", "(bez názvu)")), all_day))
    events.sort(key=lambda e: (e.start, not e.all_day))
    return events


def demo_calendar(cfg: dict) -> list[Event]:
    tz = ZoneInfo(cfg["server"]["tz"])
    d = dt.datetime.now(tz).replace(minute=0, second=0, microsecond=0)
    mk = lambda days, h, title, ad=False: Event(  # noqa: E731
        (d + dt.timedelta(days=days)).replace(hour=h), None, title, ad)
    return [mk(0, 0, "Odvoz bioodpadu", True), mk(0, 18, "Trénink"),
            mk(1, 9, "Zubař — MUDr. Novák"), mk(1, 17, "Vyzvednout balík"),
            mk(2, 19, "Večeře u rodičů"), mk(4, 0, "Narozeniny Jana", True), mk(5, 10, "Výlet")]


# --------------------------------------------------------------------------
# Chytrá domácnost — TP-Link Tapo / Kasa přes python-kasa (lokálně, bez cloudu)
# --------------------------------------------------------------------------


@dataclass
class Entity:
    id: str
    name: str
    kind: str  # thermostat | contact | sensor | switch
    values: dict = field(default_factory=dict)


def describe(dev) -> Entity | None:
    """Zařízení → jednoduchý popis pro stránku. Hub samotný se přeskočí."""
    f = dev.features

    def val(key):
        return f[key].value if key in f else None

    name = dev.alias or dev.model
    if "target_temperature" in f:  # hlavice KE100, termostat
        mode = val("thermostat_mode")
        return Entity(dev.device_id, name, "thermostat", {
            "current": val("temperature"),
            "target": val("target_temperature"),
            "on": val("state"),
            "heating": getattr(mode, "name", str(mode)).lower() == "heating",
            "battery_low": val("battery_low"),
        })
    if "is_open" in f:  # dveřní/okenní senzor T110
        return Entity(dev.device_id, name, "contact",
                      {"open": val("is_open"), "battery_low": val("battery_low")})
    if "temperature" in f:  # teploměr T310/T315
        return Entity(dev.device_id, name, "sensor",
                      {"current": val("temperature"), "humidity": val("humidity"),
                       "battery_low": val("battery_low")})
    if "state" in f and not dev.children:  # zásuvka P100/P110 (např. s přímotopem)
        return Entity(dev.device_id, name, "switch", {"on": val("state")})
    return None


class Home:
    """Spojení s Tapo zařízeními. Stav se načítá na pozadí, stránka je proto rychlá."""

    def __init__(self, cfg: dict):
        self.cfg = cfg["tapo"]
        self.roots: list = []  # zařízení, se kterými mluvíme (hub, zásuvky)
        self.by_id: dict = {}  # device_id → (zařízení, root)
        self.error: str | None = None
        self.lock = asyncio.Lock()

    async def connect(self):
        from kasa import Discover

        creds = {"username": self.cfg.get("username"), "password": self.cfg.get("password")}
        errors = []
        if self.cfg["hosts"]:
            roots = []
            for host in self.cfg["hosts"]:
                try:
                    d = await Discover.discover_single(host, **creds)
                    if d:
                        roots.append(d)
                except Exception as e:
                    log.warning("Tapo %s: %s", host, e)
                    errors.append(f"{host}: {e or type(e).__name__}")
        else:
            roots = list((await Discover.discover(**creds)).values())
        for d in self.roots:
            await d.disconnect()
        self.roots = roots
        await self.refresh()
        if errors:
            self.error = "; ".join(errors)

    async def refresh(self):
        async with self.lock:
            by_id, errors = {}, []
            for root in self.roots:
                try:
                    await root.update()
                except Exception as e:
                    errors.append(f"{root.host}: {e}")
                    continue
                stack = [root]
                while stack:  # hub → hlavice/senzory, lišta → zásuvky
                    d = stack.pop()
                    by_id[d.device_id] = (d, root)
                    stack.extend(d.children)
            self.by_id = by_id
            self.error = "; ".join(errors) or (None if self.roots else "nenalezena žádná zařízení")

    def entities(self) -> list[Entity]:
        out = []
        for dev, _ in self.by_id.values():
            try:
                e = describe(dev)
            except Exception as ex:
                log.warning("Nelze přečíst %s: %s", dev, ex)
                continue
            if e:
                out.append(e)
        order = {"contact": 0, "thermostat": 1, "switch": 2, "sensor": 3}
        return sorted(out, key=lambda e: (order[e.kind], e.name))

    async def act(self, dev_id: str, op: str):
        if dev_id not in self.by_id:
            return
        dev, root = self.by_id[dev_id]
        f = dev.features
        async with self.lock:
            if op in ("up", "down") and "target_temperature" in f:
                feat = f["target_temperature"]
                step = float(self.cfg["step"])
                new = (feat.value or 20) + (step if op == "up" else -step)
                new = max(feat.minimum_value, min(feat.maximum_value, round(new * 2) / 2))
                await feat.set_value(new)
            elif op == "toggle" and "state" in f:
                await f["state"].set_value(not f["state"].value)
            try:
                await root.update()
            except Exception as e:
                log.warning("Obnova po akci selhala: %s", e)

    async def run(self, interval: int = 60):
        while True:
            try:
                if not self.roots:
                    await self.connect()
                else:
                    await self.refresh()
            except Exception as e:
                log.warning("Tapo: %s", e)
                self.error = str(e)
            await asyncio.sleep(interval if self.roots else 120)


class DemoHome(Home):
    def __init__(self, cfg: dict):
        super().__init__(cfg)
        self.items = [
            Entity("door", "Vchodové dveře", "contact", {"open": False, "battery_low": False}),
            Entity("balk", "Balkon", "contact", {"open": True, "battery_low": True}),
            Entity("obyv", "Obývák", "thermostat",
                   {"current": 20.6, "target": 21.5, "on": True, "heating": True}),
            Entity("loz", "Ložnice", "thermostat",
                   {"current": 18.9, "target": 18.0, "on": True, "heating": False}),
            Entity("koup", "Koupelna", "thermostat",
                   {"current": 19.4, "target": 23.0, "on": False, "heating": False}),
            Entity("prim", "Přímotop pracovna", "switch", {"on": False}),
        ]

    def entities(self):
        return self.items

    async def act(self, dev_id, op):
        for e in self.items:
            if e.id == dev_id:
                if op == "up":
                    e.values["target"] = min(30, e.values["target"] + self.cfg["step"])
                elif op == "down":
                    e.values["target"] = max(5, e.values["target"] - self.cfg["step"])
                elif op == "toggle":
                    e.values["on"] = not e.values["on"]

    async def run(self, interval=60):
        pass


# --------------------------------------------------------------------------
# HTML — záměrně „starý" kód: tabulky, žádný flexbox, JS jen na hodiny.
# Prohlížeč na Kindlu je staré WebKit, ale tohle zvládne vždy.
# --------------------------------------------------------------------------

CSS = """
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:#fff;color:#000}
body{font-family:Georgia,"Times New Roman",serif;font-size:22px;padding:14px 18px}
table{border-collapse:collapse;width:100%}
td{vertical-align:middle;padding:0}
h2{font-size:17px;letter-spacing:2px;text-transform:uppercase;margin:16px 0 6px;
   border-bottom:3px solid #000;padding-bottom:3px;font-family:Helvetica,Arial,sans-serif}
.sans{font-family:Helvetica,Arial,sans-serif}
.clock{font-size:64px;font-weight:bold;line-height:1}
.date{font-size:24px}
.now{font-size:64px;font-weight:bold;line-height:1;text-align:right}
.nowd{font-size:20px;text-align:right}
.small{font-size:16px}
.muted{color:#555}
.fc td{text-align:center;padding:4px 2px;border-right:1px solid #999}
.fc td.last{border-right:0}
.fc .d{font-weight:bold;font-size:20px}
.fc .t{font-size:24px;font-weight:bold}
.row td{border-bottom:1px solid #999;padding:6px 0}
.btn{display:block;border:3px solid #000;border-radius:10px;text-align:center;
     text-decoration:none;color:#000;font-weight:bold;font-family:Helvetica,Arial,sans-serif;
     padding:10px 0;font-size:30px;line-height:1}
.btn.on{background:#000;color:#fff}
.btn.txt{font-size:20px;padding:15px 0}
.tgt{font-size:30px;font-weight:bold;text-align:center}
.alert{background:#000;color:#fff;font-weight:bold;padding:2px 8px;border-radius:6px}
.ev td{padding:4px 0;border-bottom:1px dotted #999}
.ev .tm{width:90px;font-weight:bold;font-family:Helvetica,Arial,sans-serif;font-size:19px}
.day{font-weight:bold;font-family:Helvetica,Arial,sans-serif;font-size:18px;padding-top:8px}
.err{font-size:15px;border:2px dashed #000;padding:4px 8px;margin:6px 0}
"""

JS = """
(function(){function p(n){return n<10?'0'+n:''+n}
function t(){var d=new Date(),e=document.getElementById('clock');
if(e)e.innerHTML=d.getHours()+':'+p(d.getMinutes())}
t();setInterval(t,30000)})();
"""


def fmt_t(v) -> str:
    if v is None:
        return "–"
    return f"{v:.1f}".replace(".", ",")


def render(cfg, weather, w_err, events, c_err, home: Home, tz) -> str:
    e = html.escape
    now = dt.datetime.now(tz)
    token = cfg["server"]["token"]
    q = lambda **kw: "?" + urlencode({**kw, **({"k": token} if token else {})})  # noqa: E731
    out = []
    a = out.append

    # --- hlavička: hodiny + aktuální počasí
    cur = (weather or {}).get("current", {})
    a('<table><tr><td>')
    a(f'<div class="clock" id="clock">{now.hour}:{now.minute:02d}</div>')
    a(f'<div class="date">{DAYS_LONG[now.weekday()]} {now.day}. {MONTHS[now.month - 1]}</div>')
    a('</td><td>')
    if cur:
        a(f'<div class="now">{fmt_t(cur.get("temperature_2m"))}°</div>')
        a(f'<div class="nowd">{e(WMO.get(cur.get("weather_code"), ""))} · '
          f'pocitově {fmt_t(cur.get("apparent_temperature"))}°<br>'
          f'<span class="small">vítr {round(cur.get("wind_speed_10m", 0))} km/h · '
          f'vlhkost {cur.get("relative_humidity_2m", "–")} % · {e(cfg["weather"]["name"])}</span></div>')
    a('</td></tr></table>')

    # --- příští hodiny + dny
    if weather:
        hourly = weather.get("hourly", {})
        times = hourly.get("time", [])
        start = next((i for i, t in enumerate(times) if t >= now.strftime("%Y-%m-%dT%H:00")), 0)
        idx = list(range(start + 1, min(start + 13, len(times)), 3))[:4]
        daily = weather.get("daily", {})
        a('<table class="fc sans" style="margin-top:12px"><tr>')
        cells = []
        for i in idx:
            cells.append((times[i][11:13] + " h", f'{round(hourly["temperature_2m"][i])}°',
                          WMO.get(hourly["weather_code"][i], ""),
                          f'{hourly["precipitation_probability"][i] or 0} %'))
        for i, day in enumerate(daily.get("time", [])[1:4], start=1):
            d = dt.date.fromisoformat(day)
            cells.append((DAYS[d.weekday()],
                          f'{round(daily["temperature_2m_max"][i])}°/{round(daily["temperature_2m_min"][i])}°',
                          WMO.get(daily["weather_code"][i], ""),
                          f'{daily["precipitation_probability_max"][i] or 0} %'))
        for n, (d, t, txt, rain) in enumerate(cells):
            last = ' class="last"' if n == len(cells) - 1 else ""
            sep = ' style="border-right:3px solid #000"' if n == len(idx) - 1 else last
            a(f'<td{sep} width="{100 // max(len(cells), 1)}%"><div class="d">{d}</div>'
              f'<div class="t">{t}</div><div class="small">{e(txt)}</div>'
              f'<div class="small muted">srážky {rain}</div></td>')
        a('</tr></table>')
    if w_err:
        a(f'<div class="err">Počasí: {e(w_err)}</div>')

    # --- domácnost
    ents = home.entities()
    contacts = [x for x in ents if x.kind == "contact"]
    a('<h2>Domov</h2>')
    if contacts:
        a('<table class="sans"><tr>')
        for c in contacts:
            st = '<span class="alert">OTEVŘENO</span>' if c.values["open"] else "zavřeno"
            bat = ' <span class="small">(baterie!)</span>' if c.values.get("battery_low") else ""
            a(f'<td style="padding:4px 0">{e(c.name)}: <b>{st}</b>{bat}</td>')
        a('</tr></table>')

    rows = [x for x in ents if x.kind in ("thermostat", "switch", "sensor")]
    if rows:
        a('<table>')
        for x in rows:
            v = x.values
            a('<tr class="row">')
            if x.kind == "thermostat":
                flame = ' <span class="alert small">topí</span>' if v.get("heating") else ""
                bat = ' <span class="small">(baterie!)</span>' if v.get("battery_low") else ""
                a(f'<td><b>{e(x.name)}</b>{flame}{bat}<br>'
                  f'<span class="small">teď {fmt_t(v.get("current"))} °C</span></td>')
                a(f'<td width="72"><a class="btn" href="/act{q(id=x.id, op="down")}">−</a></td>')
                a(f'<td width="110" class="tgt">{fmt_t(v.get("target"))}°</td>')
                a(f'<td width="72"><a class="btn" href="/act{q(id=x.id, op="up")}">+</a></td>')
                on = v.get("on")
                a(f'<td width="100" style="padding-left:12px"><a class="btn txt{" on" if on else ""}" '
                  f'href="/act{q(id=x.id, op="toggle")}">{"ZAP" if on else "VYP"}</a></td>')
            elif x.kind == "switch":
                on = v.get("on")
                a(f'<td colspan="4"><b>{e(x.name)}</b></td>')
                a(f'<td width="100" style="padding-left:12px"><a class="btn txt{" on" if on else ""}" '
                  f'href="/act{q(id=x.id, op="toggle")}">{"ZAP" if on else "VYP"}</a></td>')
            else:
                hum = f' · {v["humidity"]} %' if v.get("humidity") is not None else ""
                a(f'<td colspan="5"><b>{e(x.name)}</b> {fmt_t(v.get("current"))} °C{hum}</td>')
            a('</tr>')
        a('</table>')
    if not ents and not home.error:
        a('<div class="small muted">Načítám zařízení…</div>')
    if home.error:
        a(f'<div class="err">Tapo: {e(home.error)}</div>')

    # --- kalendář
    a('<h2>Kalendář</h2>')
    if events:
        a('<table class="ev">')
        last_day, shown = None, 0
        for ev in events:
            day = max(ev.start.date(), now.date())
            if shown >= 10:
                break
            if day != last_day:
                diff = (day - now.date()).days
                label = {0: "Dnes", 1: "Zítra"}.get(
                    diff, f"{DAYS_LONG[day.weekday()].capitalize()} {day.day}. {day.month}.")
                a(f'<tr><td colspan="2" class="day">{label}</td></tr>')
                last_day = day
            tm = "celý den" if ev.all_day else f"{ev.start.hour}:{ev.start.minute:02d}"
            if not ev.all_day and ev.start < now:
                tm = "probíhá"
            a(f'<tr><td class="tm">{tm}</td><td>{e(ev.title)}</td></tr>')
            shown += 1
        a('</table>')
    elif not c_err:
        a('<div class="muted">Žádné události.</div>' if cfg["calendar"]["ics"] or ARGS.demo
          else '<div class="small muted">Doplň odkaz na kalendář do config.toml.</div>')
    if c_err:
        a(f'<div class="err">Kalendář: {e(c_err)}</div>')

    a(f'<div class="small muted sans" style="margin-top:10px;text-align:right">'
      f'načteno {now.hour}:{now.minute:02d} · <a href="/{q()}" style="color:#000">obnovit</a></div>')

    refresh = int(cfg["server"]["refresh"])
    return (
        '<!DOCTYPE html><html lang="cs"><head><meta charset="utf-8">'
        f'<meta http-equiv="refresh" content="{refresh};url=/{q()}">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f'<title>Domov</title><style>{CSS}</style></head><body>'
        + "".join(out) + f"<script>{JS}</script></body></html>"
    )


# --------------------------------------------------------------------------
# Web server
# --------------------------------------------------------------------------


def make_app(cfg: dict) -> web.Application:
    tz = ZoneInfo(cfg["server"]["tz"])
    app = web.Application()
    home = DemoHome(cfg) if ARGS.demo else Home(cfg)
    state: dict = {}

    def check(request):
        token = cfg["server"]["token"]
        if token and request.query.get("k") != token:
            raise web.HTTPForbidden(text="Špatný nebo chybějící ?k= v adrese.")

    async def on_start(app):
        state["http"] = aiohttp.ClientSession(trust_env=True)
        if ARGS.demo:
            state["weather"] = Cached(lambda: asyncio.sleep(0, demo_weather()), 900)
            state["cal"] = Cached(lambda: asyncio.sleep(0, demo_calendar(cfg)), 900)
        else:
            async def weather():
                return await fetch_weather(cfg, state["http"])

            async def calendar():
                return await fetch_calendar(cfg, state["http"])

            state["weather"] = Cached(weather, 900)
            state["cal"] = Cached(calendar, 900)
        state["home_task"] = asyncio.create_task(home.run())

    async def on_stop(app):
        state["home_task"].cancel()
        await state["http"].close()
        for d in home.roots:
            await d.disconnect()

    async def index(request):
        check(request)
        weather, events = await asyncio.gather(state["weather"].get(), state["cal"].get())
        body = render(cfg, weather, state["weather"].error, events or [],
                      state["cal"].error, home, tz)
        return web.Response(text=body, content_type="text/html",
                            headers={"Cache-Control": "no-store"})

    async def act(request):
        check(request)
        try:
            await asyncio.wait_for(home.act(request.query.get("id", ""),
                                            request.query.get("op", "")), 15)
        except Exception as e:
            log.warning("Akce selhala: %s", e)
            home.error = f"akce selhala: {e}"
        token = cfg["server"]["token"]
        raise web.HTTPSeeOther("/" + (f"?k={token}" if token else ""))

    async def api(request):
        check(request)
        return web.json_response([e.__dict__ for e in home.entities()])

    app.on_startup.append(on_start)
    app.on_cleanup.append(on_stop)
    app.add_routes([web.get("/", index), web.get("/act", act), web.get("/api/devices", api)])
    return app


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config(Path(ARGS.config))
    web.run_app(make_app(cfg), port=ARGS.port or cfg["server"]["port"])


parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
parser.add_argument("--config", default=str(Path(__file__).with_name("config.toml")))
parser.add_argument("--port", type=int)
parser.add_argument("--demo", action="store_true", help="vymyšlená data, bez Tapo účtu")
ARGS = parser.parse_args() if __name__ == "__main__" else parser.parse_args([])

if __name__ == "__main__":
    main()
