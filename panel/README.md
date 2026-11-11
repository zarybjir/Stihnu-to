# Panel pro Lenovo Smart Clock 2

Android aplikace, která z hodin udělá domácí panel. Okna se přepínají přejetím
prstem do stran (nebo klepnutím na tečky dole):

| okno | co ukazuje |
|---|---|
| **Hodiny** | velký čas, datum, aktuální počasí |
| **Počasí** | čas, teď (teplota, pocitově, vítr, nárazy, vlhkost, tlak, srážky, východ/západ slunce) + 7 dní |
| **Po hodinách** | graf na 24 hodin: teplota, srážky v mm a pravděpodobnost, ikony |
| **Radar** | animace srážek za poslední hodinu (RainViewer), nebo libovolná webová stránka |
| **Hudba** | co hraje v YouTube Music / YouTube, ovládání, hlasitost, spuštění aplikací |
| **Topení** | hlavice Tapo KE100 (cíl −/+, zapnout/vypnout, „topí") a teploměry Tapo T310/T315 |
| **Claude** | hlasový asistent — „zatop v obýváku na 22", „bude zítra pršet?", „pusť další písničku" |

Počasí: [Open-Meteo](https://open-meteo.com) (zdarma). Topení: přímo přes hub
Tapo H100 v domácí síti, bez cloudu. V noci (nastavitelné) se displej ztlumí
a po dotyku se na půl minuty rozsvítí. Po pár minutách bez dotyku se panel
vrátí na domovské okno.

## Nastavení

Na počítači nebo telefonu ve stejné WiFi otevři **http://&lt;ip-hodin&gt;:8080**.
Tam se nastavuje místo pro počasí, pořadí oken, přihlášení k Tapo, klíč ke
Claude a noční režim. IP hodin ukazuje okno Topení, dokud není nic nastavené.

## Ovládání odjinud (API)

Panel ovládá i cokoli jiného v síti — skript, Home Assistant, nebo Claude
na počítači:

```bash
curl http://<ip-hodin>:8080/api/state                                   # stav jako JSON
curl -d '{"page":"radar"}' http://<ip-hodin>:8080/api/page               # přepnout okno
curl -d '{"room":"Obývák","target":22}' http://<ip-hodin>:8080/api/heating
curl -d '{"room":"Ložnice","on":false}' http://<ip-hodin>:8080/api/heating
curl -d '{"action":"next"}' http://<ip-hodin>:8080/api/media             # play|pause|toggle|next|previous|volume_up|volume_down
curl -d '{"volume":40}' http://<ip-hodin>:8080/api/media
curl -d '{"app":"youtube_music","search":"Lucie"}' http://<ip-hodin>:8080/api/open
curl -d '{"text":"Kde se teď topí?"}' http://<ip-hodin>:8080/api/ask      # zeptat se Clauda na hodinách
```

Všechno, co něco mění (`/api/page`, `/api/heating`, `/api/media`, `/api/play`,
`/api/open`, `/api/ask`), bere jen **POST** — tak, jak to dělá `curl -d` výše.

### Zabezpečení

API ani nastavení nemají heslo — patří jen do domácí sítě, nikdy je nevystavuj
do internetu (žádné přesměrování portu 8080 na routeru). Kdokoli v domácí síti
může panel ovládat a měnit nastavení; uložené klíče a heslo ale stránka nikdy
nezobrazí.

Proti útoku z webové stránky, kterou si někdo otevře na jiném zařízení v téže
síti, se server brání sám:

- odpovědi nemají `Access-Control-Allow-Origin`, takže je cizí stránka nepřečte,
- změny jdou jen metodou POST (nejde je vyvolat obrázkem ani odkazem),
- POST z prohlížeče musí přijít ze stránky panelu (kontrola `Origin`/`Referer`);
  skripty a `curl` tuhle hlavičku neposílají a fungují dál,
- adresa v hlavičce `Host` musí být IP nebo místní jméno (ochrana proti „DNS rebinding“).

Hub topení musí mít IP adresu z domácí sítě a hodiny s ním mluví zabezpečeným
protokolem KLAP, u kterého heslo neopouští hodiny. Jakmile ho hub jednou použil,
na starší protokol (který heslo posílá protistraně) se už nepřepne — leda po novém
přihlášení přímo na displeji hodin.

## Claude

Potřebuje klíč k Claude API (platform.claude.com → API Keys); platí se podle
použití. Výchozí model je `claude-opus-5-5`; levnější a rychlejší je
`claude-haiku-5-5` — přepíná se v nastavení. Asistent má nástroje na stav
panelu, přepínání oken, topení, hudbu a spouštění aplikací. Když bezpečnostní
filtr dotaz odmítne, server ho automaticky zkusí jiným modelem (`fallbacks`).

Mluvení vyžaduje rozpoznávání řeči od Google; pokud na hodinách není, fungují
tlačítka s hotovými dotazy a dotazy z webového nastavení.

## Přidání vlastního okna

1. Nový soubor v `src/main/java/cz/stihnuto/panel/pages/`, třída dědí z `Page`
   (nejjednodušší vzor je `ClockPage.kt`).
2. Jeden řádek do `Pages.all` v `Page.kt`.
3. V nastavení přidat jeho id do pořadí oken.

Pokud ho má ovládat i Claude, přidej nástroj do `claude/Assistant.kt`.

## Sestavení a aktualizace

GitHub Actions sestaví APK při každé změně v `panel/` a zveřejní ji na
stálé adrese, odkud si ji hodiny stáhnou:

    https://github.com/zarybjir/Stihnu-to/releases/download/panel-latest/panel.apk

Novou verzi pak nainstaluješ z nastavení (http://<ip-hodin>:8080 →
Aktualizace) — hodiny ji stáhnou samy a na displeji jen potvrdíš.

Aby šla nová verze nainstalovat přes starou, musí být všechny podepsané
stejným klíčem. Ten je v GitHub secrets (Settings → Secrets and variables →
Actions): `PANEL_KEYSTORE_B64` a `PANEL_KEYSTORE_PASSWORD`. Bez nich se APK
podepíše náhodným klíčem a aktualizace přes starou verzi neprojde.

Lokálně: `./gradlew :panel:assembleDebug`.

## Jednorázová oprávnění přes adb

```bash
adb install -r panel-debug.apk
adb shell pm grant cz.stihnuto.panel android.permission.RECORD_AUDIO
adb shell cmd notification allow_listener cz.stihnuto.panel/cz.stihnuto.panel.MediaListener
# volitelně: panel jako domovská obrazovka místo původních hodin
adb shell pm enable cz.stihnuto.panel/cz.stihnuto.panel.HomeAlias
```
