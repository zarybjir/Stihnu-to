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

API ani nastavení nemají heslo — patří jen do domácí sítě, nikdy je nevystavuj
do internetu.

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

## Sestavení

GitHub Actions sestaví APK při každé změně v `panel/` (Actions → Build panel
APK → Artifacts → `panel-apk`). Lokálně: `./gradlew :panel:assembleDebug`.

## Jednorázová oprávnění přes adb

```bash
adb install -r panel-debug.apk
adb shell pm grant cz.stihnuto.panel android.permission.RECORD_AUDIO
adb shell cmd notification allow_listener cz.stihnuto.panel/cz.stihnuto.panel.MediaListener
# volitelně: panel jako domovská obrazovka místo původních hodin
adb shell pm enable cz.stihnuto.panel/cz.stihnuto.panel.HomeAlias
```
