# Stihnu to? — Android aplikace s widgetem

Tvoje webová aplikace zabalená do Androidu + nativní widget na plochu.
Instaluje se jako APK, bez Play Storu.

## Co je uvnitř

- **Aplikace** = tvoje webovka beze změn vzhledu (`app/src/main/assets/web/`),
  běží ve WebView. Do `index.html` jsou přidané jen tři drobnosti: zrcadlení
  karet a nastavení do Androidu a předání stažených odjezdů widgetu.
  V normálním prohlížeči se nic nemění.
- **Widget** (4×3, dá se zmenšit i zvětšit) — jedna karta, až 5 nejbližších
  odjezdů: linka v barvě PID, směr, zpoždění, čas a **živý odpočet**
  (běží sám, bez stahování dat).

### Jak se widget obnovuje

| kdy | co se stane |
|---|---|
| máš otevřenou aplikaci | widget dostává data z aplikace, žádné dotazy navíc |
| klepneš na ↻ | okamžitě stáhne nové odjezdy z Golemia |
| odjede první spoj | widget se překreslí z uložených dat, seznam se posune |
| dojdou uložené odjezdy | stáhne nová data |
| každých 30 minut | stáhne nová data (nejčastější interval, který Android dovolí) |

Zpoždění se tedy mezi obnovami nemění. Když spěcháš, klepni na ↻.

## Sestavení v Android Studiu

1. Nainstaluj [Android Studio](https://developer.android.com/studio).
2. **File → Open** → vyber tuhle složku (`stihnu-to-android`).
3. Počkej na Gradle sync (poprvé stahuje několik minut).
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. APK najdeš v `app/build/outputs/apk/debug/app-debug.apk`.

Rychlejší cesta, když máš telefon připojený kabelem s povoleným laděním USB:
prostě klikni na zelené ▶ Run.

**Aktualizace:** debug APK je podepsaná klíčem z tvého počítače. Další verze
postavené na **stejném počítači** se nainstalují přes starou a data zůstanou.
Z jiného počítače by to nešlo — pak použij Build → Generate Signed App Bundle /
APK a vlastní klíč (a ten si zálohuj).

## Instalace do telefonu

1. Pošli si APK do telefonu (Disk, e-mail, kabel).
2. Otevři ho a povol „Instalovat neznámé aplikace" pro aplikaci, ze které ho otevíráš.
3. Play Protect se může zeptat, jestli chceš neznámou aplikaci nainstalovat → Instalovat.

## Přenos dat z prohlížeče

Aplikace má vlastní úložiště, karty z prohlížeče v ní nebudou.

1. V prohlížeči: ⚙ → **Zkopírovat zálohu** (zkopíruje se do schránky).
2. V aplikaci: ⚙ → **Obnovit ze zálohy** (otevře pole) → vlož text → znovu **Obnovit ze zálohy**.

Golemio klíč je součástí zálohy, nemusíš ho zadávat znovu.

## Přidání widgetu

Podrž prst na ploše → Widgety → **Stihnu to?** → přetáhni na plochu → vyber kartu.
Jinou kartu vybereš podržením widgetu → Změnit nastavení (na Androidu 12+).

## Volitelně: sestavení na GitHubu

`.github/workflows/build.yml` postaví APK při každém pushi. Návod na podpisový
klíč je v komentáři v souboru. Když pushneš tag `v1.0.1`, vznikne GitHub
Release — aplikace **Obtainium** pak hlídá nové verze a nabídne aktualizaci.

## Technické

- `minSdk 26` (Android 8), `targetSdk 34`, Kotlin, bez Compose.
- Web se načítá z `https://appassets.androidplatform.net/…` (WebViewAssetLoader),
  takže fetch na Golemio funguje stejně jako v prohlížeči.
- Odkazy ven (mapa PID, e-mail) se otevírají v prohlížeči.
- Při každé nové verzi zvyš `versionCode` v `app/build.gradle.kts`.
