package cz.stihnuto.panel.web

import cz.stihnuto.panel.App
import cz.stihnuto.panel.pages.Pages

/** Stránka s nastavením, kterou otevřeš v prohlížeči na počítači nebo telefonu. */
object SettingsPage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun render(app: App): String {
        val s = app.settings
        val ip = WebServer.localIp() ?: "<ip-hodin>"
        val allJson = Pages.all.entries.joinToString(",", "{", "}") {
            org.json.JSONObject.quote(it.key) + ":" + org.json.JSONObject.quote(it.value.title)
        }
        fun field(name: String, label: String, value: String, hint: String = "", type: String = "text") =
            """<label>$label<input name="$name" type="$type" value="${esc(value)}"></label>""" +
                (if (hint.isNotEmpty()) "<small>$hint</small>" else "")
        fun secret(name: String, label: String, set: Boolean, hint: String = "") =
            """<label>$label<input name="$name" type="password" placeholder="${if (set) "uloženo — nech prázdné, ať se nezmění" else ""}"></label>""" +
                (if (hint.isNotEmpty()) "<small>$hint</small>" else "")

        return """<!doctype html><html lang="cs"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Panel — nastavení</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b0f14;color:#f2f5f8;margin:0;padding:16px;max-width:760px;margin:auto}
h1{font-weight:500}h2{font-size:15px;text-transform:uppercase;letter-spacing:.08em;color:#ffc94a;margin-top:28px}
label{display:block;margin:12px 0 2px;color:#8a96a3;font-size:14px}
input,textarea{display:block;width:100%;box-sizing:border-box;padding:10px;margin-top:4px;border-radius:8px;border:1px solid #3a4652;background:#17202a;color:#f2f5f8;font-size:16px}
small{color:#8a96a3;display:block;margin-top:3px}code{color:#ffc94a}
button{background:#ffc94a;color:#0b0f14;border:0;border-radius:8px;padding:12px 20px;font-size:16px;font-weight:600;margin-top:16px;cursor:pointer}
.row{display:flex;gap:12px}.row>*{flex:1}.ok{background:#1d3a28;padding:10px;border-radius:8px}
#results button{display:block;background:#223040;color:#f2f5f8;margin:6px 0;font-weight:400}
.win{display:flex;align-items:center;gap:6px;background:#1e1f20;border-radius:10px;padding:6px 10px;margin:6px 0}
.win.off{opacity:.55}.chk{flex:1;display:flex;align-items:center;gap:8px;margin:0;color:#e3e3e3;font-size:16px}
.chk input{width:auto;display:inline;margin:0}.sm{margin:0;padding:6px 12px;background:#2d2f31;color:#e3e3e3;font-weight:400}
.sm:disabled{opacity:.3}.sm.home{background:#a8c7fa;color:#131314}
pre{background:#17202a;padding:10px;border-radius:8px;white-space:pre-wrap}
</style></head><body>
<h1>Panel na hodinách</h1>
<div id="saved"></div>
<form method="post" action="/save">

<h2>Místo pro počasí</h2>
<label>Najít obec<input id="q" placeholder="např. Brno"></label>
<div id="results"></div>
${field("place", "Název", s.place)}
<div class="row">${field("lat", "Šířka", s.lat.toString())}${field("lon", "Délka", s.lon.toString())}</div>

<h2>Kalendář</h2>
<label>Odkazy na kalendáře (každý na nový řádek)<textarea name="calendar_urls" rows="3">${esc(s.calendarUrls)}</textarea></label>
<small>Google Kalendář na počítači → ⚙ Nastavení → vlevo vyber kalendář → „Tajná adresa ve formátu iCal“ → zkopírovat.
Funguje i iCloud, Outlook nebo Seznam (cokoli s odkazem .ics).</small>

<h2>Okna</h2>
<small>Zaškrtnutá okna se zobrazují, šipkami změníš pořadí, ★ je domovské okno. Okno Aplikace je vždy poslední.</small>
<div id="wins"></div>
<input type="hidden" name="pages" id="pages" value="${esc(s.pages)}">
<input type="hidden" name="home_page" id="home_page" value="${esc(s.homePage)}">
${field("idle_minutes", "Návrat na domovské okno po (min, 0 = nikdy)", s.idleMinutes.toString(), type = "number")}
${field("radar_url", "Radar — vlastní webová adresa (nepovinné)", s.radarUrl, "Prázdné = vestavěný radar. Jinak třeba stránka ČHMÚ nebo Windy.")}

<h2>Topení (Tapo)</h2>
${field("hub_ip", "IP adresa hubu H100", s.hubIp, "Najdeš v aplikaci Tapo → hub → ⚙ → Informace o zařízení. V routeru jí nastav pevnou IP.")}
${field("tapo_user", "E-mail k účtu Tapo", s.tapoUser)}
${secret("tapo_pass", "Heslo k účtu Tapo", s.tapoPass.isNotEmpty(), "V aplikaci Tapo zapni: Já → Tapo Lab → Kompatibilita s třetími stranami.")}
${field("heat_step", "Krok tlačítek −/+ (°C)", s.heatStep.toString())}

<h2>Asistent</h2>
<label>Kdo odpovídá<select name="ai_provider" style="display:block;width:100%;padding:10px;margin-top:4px;border-radius:8px;border:1px solid #3a4652;background:#17202a;color:#f2f5f8;font-size:16px">
<option value="claude"${if (s.aiProvider == "claude") " selected" else ""}>Claude (placený, Anthropic)</option>
<option value="gemini"${if (s.aiProvider == "gemini") " selected" else ""}>Gemini (zdarma s limitem, Google)</option>
</select></label>

<h2>Gemini</h2>
${secret("gemini_key", "Klíč ke Gemini API", s.geminiKey.isNotEmpty(), "Zdarma na <a style=\"color:#4fa3ff\" href=\"https://aistudio.google.com/apikey\">aistudio.google.com/apikey</a> → Create API key. Na bezplatné verzi může Google dotazy použít ke zlepšování modelů.")}
${field("gemini_model", "Model", s.geminiModel, "Výchozí <code>gemini-flash-lite-latest</code> (nejvyšší denní limit zdarma), chytřejší <code>gemini-flash-latest</code>.")}

<h2>Claude</h2>
${secret("anthropic_key", "Klíč k Claude API", s.anthropicKey.isNotEmpty(), "Získáš na platform.claude.com → API Keys. Platí se podle použití.")}
${field("claude_model", "Model", s.claudeModel, "Výchozí <code>claude-opus-5-5</code>. Levnější a rychlejší: <code>claude-haiku-5-5</code>.")}

<h2>Noc</h2>
<div class="row">${field("night_from", "Noc od (hodina)", s.nightFrom.toString(), type = "number")}${field("night_to", "do (hodina)", s.nightTo.toString(), type = "number")}${field("night_brightness", "Jas v noci (%, 0 = neměnit)", s.nightBrightness.toString(), type = "number")}</div>
${field("day_brightness", "Jas přes den (%, 0 = automatika hodin)", s.dayBrightness.toString(), "Když hodiny přes den samy tmavnou, nastav třeba 80.", type = "number")}

<button>Uložit</button>
</form>

<h2>Aktualizace</h2>
<form method="post" action="/update"><button>Stáhnout a nainstalovat nejnovější verzi</button></form>
<small id="upd">Verze ${esc(versionName(app))}. Po stažení potvrď instalaci na displeji hodin.</small>

<h2>Zeptej se Clauda</h2>
<textarea id="ask" rows="2" placeholder="Zatop v obýváku na 22 stupňů"></textarea>
<button onclick="ask()">Poslat</button>
<pre id="reply" hidden></pre>

<h2>Ovládání odjinud</h2>
<small>Panel má API na <code>http://$ip:8080/api/…</code> — stav: <a style="color:#4fa3ff" href="/api/state">/api/state</a>.
Popis je v README v repozitáři.</small>

<script>
var ALL=$allJson;
var on=document.getElementById('pages').value.split(',').filter(function(x){return ALL[x]&&x!='apps'});
var home=document.getElementById('home_page').value;
function drawWins(){var off=Object.keys(ALL).filter(function(k){return k!='apps'&&on.indexOf(k)<0});
var box=document.getElementById('wins');box.innerHTML='';
on.concat(off).forEach(function(id){var en=on.indexOf(id)>=0,i=on.indexOf(id);
var d=document.createElement('div');d.className='win'+(en?'':' off');
d.innerHTML='<label class="chk"><input type="checkbox" '+(en?'checked':'')+'> '+ALL[id]+'</label>'+
(en?'<button type="button" class="sm" '+(i==0?'disabled':'')+'>▲</button><button type="button" class="sm" '+(i==on.length-1?'disabled':'')+'>▼</button><button type="button" class="sm'+(home==id?' home':'')+'">★</button>':'');
var b=d.querySelectorAll('button');
d.querySelector('input').onchange=function(e){if(e.target.checked)on.push(id);else if(on.length>1)on.splice(i,1);if(on.indexOf(home)<0)home=on[0];sync()};
if(en){b[0].onclick=function(){if(i>0){on.splice(i-1,0,on.splice(i,1)[0]);sync()}};
b[1].onclick=function(){if(i<on.length-1){on.splice(i+1,0,on.splice(i,1)[0]);sync()}};
b[2].onclick=function(){home=id;sync()};}
box.appendChild(d);});}
function sync(){document.getElementById('pages').value=on.join(',');document.getElementById('home_page').value=home;drawWins()}
drawWins();
if(location.search.indexOf('aktualizace')>=0)setInterval(function(){fetch('/api/update').then(r=>r.json()).then(function(j){document.getElementById('upd').textContent=j.status;});},1500);
if(location.search.indexOf('ulozeno')>=0)document.getElementById('saved').innerHTML='<p class="ok">Uloženo.</p>';
var t;document.getElementById('q').addEventListener('input',function(e){clearTimeout(t);var v=e.target.value;t=setTimeout(function(){
if(v.length<2)return;fetch('https://geocoding-api.open-meteo.com/v1/search?count=5&language=cs&name='+encodeURIComponent(v)).then(r=>r.json()).then(function(j){
var box=document.getElementById('results');box.innerHTML='';(j.results||[]).forEach(function(p){var b=document.createElement('button');b.type='button';
b.textContent=p.name+(p.admin1?', '+p.admin1:'')+(p.country?' ('+p.country+')':'');b.onclick=function(){
document.querySelector('[name=place]').value=p.name;document.querySelector('[name=lat]').value=p.latitude;document.querySelector('[name=lon]').value=p.longitude;box.innerHTML='';};box.appendChild(b);});});},300);});
function ask(){var r=document.getElementById('reply');r.hidden=false;r.textContent='Přemýšlím…';
fetch('/api/ask',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:document.getElementById('ask').value})})
.then(x=>x.json()).then(function(j){r.textContent=j.reply||j.error;}).catch(function(e){r.textContent='Chyba: '+e;});}
</script>
</body></html>"""
    }

    private fun versionName(app: App): String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"

    fun save(app: App, f: Map<String, String>) {
        val s = app.settings
        f["place"]?.let { s.place = it.trim() }
        f["lat"]?.replace(',', '.')?.toDoubleOrNull()?.let { s.lat = it }
        f["lon"]?.replace(',', '.')?.toDoubleOrNull()?.let { s.lon = it }
        f["pages"]?.let { v ->
            val list = v.split(',').map { it.trim() }.filter { it in Pages.all }
            if (list.isNotEmpty()) s.pages = list.joinToString(",")
        }
        f["home_page"]?.trim()?.takeIf { it in Pages.all }?.let { s.homePage = it }
        f["idle_minutes"]?.toIntOrNull()?.let { s.idleMinutes = it.coerceIn(0, 600) }
        f["radar_url"]?.let { s.radarUrl = it.trim() }
        f["calendar_urls"]?.let { s.calendarUrls = it.trim() }
        f["hub_ip"]?.let { s.hubIp = it.trim() }
        f["tapo_user"]?.let { s.tapoUser = it.trim() }
        f["tapo_pass"]?.takeIf { it.isNotEmpty() }?.let { s.tapoPass = it }
        f["heat_step"]?.replace(',', '.')?.toDoubleOrNull()?.let { s.heatStep = it.coerceIn(0.5, 5.0) }
        f["ai_provider"]?.takeIf { it == "claude" || it == "gemini" }?.let { s.aiProvider = it }
        f["gemini_key"]?.takeIf { it.isNotBlank() }?.let { s.geminiKey = it.trim() }
        f["gemini_model"]?.trim()?.takeIf { it.isNotEmpty() }?.let { s.geminiModel = it }
        f["anthropic_key"]?.takeIf { it.isNotBlank() }?.let { s.anthropicKey = it.trim() }
        f["claude_model"]?.trim()?.takeIf { it.isNotEmpty() }?.let { s.claudeModel = it }
        f["night_from"]?.toIntOrNull()?.let { s.nightFrom = it.coerceIn(0, 23) }
        f["night_to"]?.toIntOrNull()?.let { s.nightTo = it.coerceIn(0, 23) }
        f["day_brightness"]?.toIntOrNull()?.let { s.dayBrightness = it.coerceIn(0, 100) }
        f["night_brightness"]?.toIntOrNull()?.let { s.nightBrightness = it.coerceIn(0, 100) }
    }
}
