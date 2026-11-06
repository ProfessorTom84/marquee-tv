package app.marquee.tv.together

import app.marquee.tv.data.Title
import app.marquee.tv.net.Http
import app.marquee.tv.recs.Recommender
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Pick Together: deciding what to watch with someone, without the back-and-forth.
 *
 * Everyone joins from their phone (or takes turns on the remote), picks their mood and how long they've got,
 * then votes privately on the same short deck: Nah, Yes or Love it. Nobody sees anyone else's votes.
 * Marquee then reveals what you *all* said yes to, best first, and plays it with one click.
 * The deck only ever contains things on your Plex or included with your services.
 */
object Together {
    data class Mood(val key: String, val label: String, val genres: List<Int>)
    val moods = listOf(
        Mood("funny", "Funny", listOf(35)), Mood("thrilling", "Thrilling", listOf(53, 28)), Mood("feelgood", "Feel-good", listOf(10751, 35)),
        Mood("mind", "Mind-bending", listOf(878, 9648)), Mood("scary", "Scary", listOf(27)), Mood("romantic", "Romantic", listOf(10749)),
        Mood("animated", "Animated", listOf(16)), Mood("true", "True stories", listOf(99, 36)), Mood("epic", "Epic", listOf(12, 14, 10752)),
        Mood("crime", "Crime", listOf(80)),
    )

    data class Player(
        val id: String, val name: String, val viaPhone: Boolean,
        val moods: Set<String> = emptySet(), val maxMinutes: Int? = null, val type: String = "any", val ready: Boolean = false,
    )

    enum class Phase { LOBBY, BUILDING, VOTING, RESULTS }

    data class Session(
        val id: String,
        val phase: Phase = Phase.LOBBY,
        val players: List<Player> = emptyList(),
        val deck: List<Title> = emptyList(),
        val votes: Map<String, Map<String, Int>> = emptyMap(), // player -> title -> -1 nah, 1 yes, 2 love
        val remote: Boolean = false,
        val matchCount: Int = 0,
        val notice: String? = null,
    ) {
        fun votedCount(pid: String) = votes[pid]?.size ?: 0
        fun allDone() = players.isNotEmpty() && deck.isNotEmpty() && players.all { votedCount(it.id) >= deck.size }
    }

    data class Scored(val title: Title, val yes: Int, val love: Int, val nah: Int, val lovedBy: List<String>, val match: Boolean, val score: Double)

    val session = MutableStateFlow<Session?>(null)
    private val images = ConcurrentHashMap<String, ByteArray>()

    fun newSession(remote: Boolean = false, localNames: List<String> = emptyList()): Session {
        images.clear()
        val id = (1..5).map { "abcdefghjkmnpqrstuvwxyz23456789"[Random.nextInt(31)] }.joinToString("")
        val s = Session(id, remote = remote, players = localNames.mapIndexed { i, n -> Player("tv$i", n, viaPhone = false) })
        session.value = s
        return s
    }

    fun end() { session.value = null; images.clear() }

    fun join(name: String): Player? {
        var p: Player? = null
        session.update { s ->
            if (s == null || s.phase != Phase.LOBBY || s.players.size >= 4) return@update s
            val clean = name.trim().take(16).ifBlank { "Guest" }
            val unique = if (s.players.any { it.name.equals(clean, true) }) "$clean ${s.players.size + 1}" else clean
            p = Player("p" + Random.nextInt(100000, 999999), unique, viaPhone = true)
            s.copy(players = s.players + p!!)
        }
        return p
    }

    fun setPrefs(pid: String, moods: Set<String>, maxMinutes: Int?, type: String) = session.update { s ->
        s?.copy(players = s.players.map { if (it.id == pid) it.copy(moods = moods, maxMinutes = maxMinutes, type = type, ready = true) else it })
    }

    fun setAllPrefs(moods: Set<String>, maxMinutes: Int?, type: String) = session.update { s ->
        s?.copy(players = s.players.map { it.copy(moods = moods, maxMinutes = maxMinutes, type = type, ready = true) })
    }

    fun removePlayer(pid: String) = session.update { s -> s?.copy(players = s.players.filterNot { it.id == pid }) }

    fun vote(pid: String, titleId: String, v: Int) = session.update { s ->
        if (s == null || s.phase != Phase.VOTING || s.deck.none { it.id == titleId }) return@update s
        val mine = (s.votes[pid].orEmpty() + (titleId to v.coerceIn(-1, 2)))
        val next = s.copy(votes = s.votes + (pid to mine))
        val matches = rank(next).count { it.match }
        next.copy(matchCount = matches, phase = if (next.allDone()) Phase.RESULTS else Phase.VOTING)
    }

    /** Ends voting early (someone gave up): results from what's been voted so far. */
    fun finishNow() = session.update { s -> s?.copy(phase = Phase.RESULTS) }

    /**
     * Builds one shared deck from everyone's moods: moods you share come first, then each person's,
     * shortest "time available" wins, and the type is whatever you agree on (else either).
     */
    suspend fun buildDeck(pick: suspend (Recommender.Filters) -> List<Title>) {
        val s = session.value ?: return
        val players = s.players.filter { it.ready }.ifEmpty { s.players }
        if (players.isEmpty()) return
        session.update { it?.copy(phase = Phase.BUILDING, players = players) }
        val counts = players.flatMap { it.moods }.groupingBy { it }.eachCount()
        val ordered = counts.entries.sortedByDescending { it.value }.map { it.key }.take(4)
        val maxMinutes = players.mapNotNull { it.maxMinutes }.minOrNull()
        val types = players.map { it.type }.toSet()
        val type = if (types.size == 1) types.first() else "any"
        val lanes = if (ordered.isEmpty()) listOf(emptyList()) else ordered.map { k -> moods.first { it.key == k }.genres }
        val results = lanes.map { g -> safePick(pick, Recommender.Filters(g, maxMinutes, type)) }
        // Interleave the lanes, shared moods leading, so the deck has variety but leans toward common ground.
        val deck = mutableListOf<Title>()
        var i = 0
        while (deck.size < 18 && results.any { i < it.size }) {
            results.forEach { lane -> lane.getOrNull(i)?.let { t -> if (deck.none { d -> same(d, t) }) deck += t } }
            i++
        }
        if (deck.size < 8) safePick(pick, Recommender.Filters(emptyList(), maxMinutes, type))
            .forEach { t -> if (deck.size < 18 && deck.none { same(it, t) }) deck += t }
        // Never start a vote on an empty deck: go back to the lobby and say why.
        if (deck.isEmpty()) session.update { it?.copy(phase = Phase.LOBBY, notice = "Couldn't find anything for those moods on your Plex or services. Check your connection, or try other moods.") }
        else session.update { it?.copy(phase = Phase.VOTING, deck = deck.take(18), votes = emptyMap(), matchCount = 0, notice = null) }
    }

    private suspend fun safePick(pick: suspend (Recommender.Filters) -> List<Title>, f: Recommender.Filters): List<Title> =
        try { pick(f) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }

    private fun same(a: Title, b: Title) = a.id == b.id || (a.tmdbId != null && a.tmdbId == b.tmdbId && a.mediaType == b.mediaType)

    fun rank(s: Session): List<Scored> = s.deck.map { t ->
        val vs = s.players.map { s.votes[it.id]?.get(t.id) ?: 0 }
        val yes = vs.count { it > 0 }; val love = vs.count { it == 2 }; val nah = vs.count { it < 0 }
        val lovedBy = s.players.filter { s.votes[it.id]?.get(t.id) == 2 }.map { it.name }
        Scored(t, yes, love, nah, lovedBy, match = s.players.isNotEmpty() && yes == s.players.size, score = vs.sum() + (t.rating ?: 6.5) / 10.0)
    }.sortedByDescending { it.score }

    // =========================== Phone side (served by the TV on your network) ===========================

    fun serve(http: IHTTPSession): Response? {
        val parts = http.uri.trim('/').split('/')
        if (parts.firstOrNull() != "t" || parts.size < 2) return null
        val s = session.value ?: return text("This round has ended. Start Pick Together on the TV again.", NanoHTTPD.Response.Status.NOT_FOUND)
        if (parts[1] != s.id) return text("This round has ended. Scan the new code on the TV.", NanoHTTPD.Response.Status.NOT_FOUND)
        val p = if (http.method == NanoHTTPD.Method.POST) {
            runCatching { http.parseBody(HashMap()) }
            http.parms
        } else http.parms
        return when (parts.getOrNull(2)) {
            null -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", page(s.id))
            "join" -> join(p["name"].orEmpty())?.let { json(buildJsonObject { put("pid", it.id); put("name", it.name) }.toString()) }
                ?: text("The room is full or voting has started.", NanoHTTPD.Response.Status.FORBIDDEN)
            "prefs" -> { setPrefs(p["p"].orEmpty(), p["moods"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(), p["minutes"]?.toIntOrNull(), p["type"] ?: "any"); json("{}") }
            "vote" -> { vote(p["p"].orEmpty(), p["t"].orEmpty(), p["v"]?.toIntOrNull() ?: 0); json("{}") }
            "state" -> json(stateJson(session.value!!, p["p"].orEmpty()))
            "img" -> image(s, parts.getOrNull(3)?.toIntOrNull() ?: -1)
            else -> null
        }
    }

    private fun stateJson(s: Session, pid: String): String = buildJsonObject {
        put("phase", s.phase.name)
        put("me", s.players.firstOrNull { it.id == pid }?.name ?: "")
        put("voted", s.votedCount(pid))
        putJsonArray("players") { s.players.forEach { pl -> add(buildJsonObject { put("name", pl.name); put("ready", pl.ready); put("voted", s.votedCount(pl.id)) }) } }
        putJsonArray("moods") { moods.forEach { m -> add(buildJsonObject { put("key", m.key); put("label", m.label) }) } }
        if (s.phase == Phase.VOTING || s.phase == Phase.RESULTS) putJsonArray("deck") {
            s.deck.forEachIndexed { i, t ->
                add(buildJsonObject {
                    put("i", i); put("id", t.id); put("title", t.title); put("year", t.year)
                    put("kind", if (t.mediaType == "tv") "Series" else "Movie")
                    put("runtime", t.runtimeMin?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" } ?: "")
                    put("where", t.bestAvailability?.let { Title.availabilityPhrase(it) } ?: "")
                    put("rating", t.ratingLabel ?: "")
                    put("badge", t.badge?.takeUnless { it.startsWith("RT ") || it.startsWith("S") && it.contains(" E") } ?: "")
                    put("genres", t.genres.take(3).joinToString(" · "))
                    put("overview", t.overview.take(240))
                    put("img", "/t/${s.id}/img/$i")
                })
            }
        }
        if (s.phase == Phase.RESULTS) {
            val top = rank(s).firstOrNull { it.match }
            put("winner", top?.title?.title ?: "")
            put("matches", rank(s).count { it.match })
        }
    }.toString()

    private fun image(s: Session, i: Int): Response {
        val t = s.deck.getOrNull(i) ?: return text("", NanoHTTPD.Response.Status.NOT_FOUND)
        val url = t.posterUrl ?: t.backdropUrl ?: return text("", NanoHTTPD.Response.Status.NOT_FOUND)
        // Proxied through the TV, so your Plex server's address and token never reach the phones.
        val bytes = images.getOrPut(url) {
            runCatching {
                Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
            }.getOrDefault(ByteArray(0))
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "max-age=3600") }
    }

    private fun json(body: String) = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body)
        .apply { addHeader("Cache-Control", "no-store") }
    private fun text(body: String, status: Response.Status) = NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    private fun page(id: String) = PHONE_PAGE.replace("__ID__", id)
}

private val PHONE_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>Pick Together</title>
<style>
:root{--bg:#07090f;--card:#141826;--text:#f4efe8;--dim:#a9a4b6;--amber:#f5b54a;--line:rgba(255,255,255,.1);--good:#7fd6a2;--bad:#ef8a8a}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:radial-gradient(1200px 700px at 80% -10%,#25305f 0%,#0d1124 45%,#06070d 100%) fixed;color:var(--text);font:16px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;min-height:100%}
main{max-width:520px;margin:0 auto;padding:22px 18px 40px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--amber);font-weight:700;text-transform:uppercase}
h1{font:400 34px/1.1 Georgia,"Times New Roman",serif;margin:8px 0 6px}
p.sub{color:var(--dim);margin:0 0 18px}
input{width:100%;padding:15px 16px;border-radius:14px;border:1px solid var(--line);background:rgba(255,255,255,.06);color:var(--text);font-size:17px}
button{font:inherit}
.btn{display:block;width:100%;margin-top:14px;padding:15px;border:0;border-radius:999px;background:var(--amber);color:#1a1206;font-weight:800;font-size:16px}
.btn[disabled]{opacity:.4}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 16px}
.chip{padding:10px 14px;border-radius:999px;border:1px solid var(--line);background:rgba(255,255,255,.05);color:var(--text);font-weight:600}
.chip.on{background:rgba(245,181,74,.18);border-color:rgba(245,181,74,.7);color:#ffd99a}
label.h{display:block;font-size:12px;letter-spacing:.12em;color:var(--dim);text-transform:uppercase;margin-top:10px}
.who{display:flex;gap:10px;flex-wrap:wrap;margin:14px 0}
.who span{padding:8px 12px;border-radius:12px;background:rgba(255,255,255,.06);border:1px solid var(--line)}
.who span.ok{border-color:rgba(127,214,162,.6)}
.deck{position:relative;height:min(64vh,560px);margin-top:12px}
.card{position:absolute;inset:0;border-radius:22px;overflow:hidden;background:var(--card);box-shadow:0 20px 60px rgba(0,0,0,.5);transition:transform .28s ease,opacity .28s ease;touch-action:pan-y}
.card img{width:100%;height:100%;object-fit:cover;display:block}
.card .info{position:absolute;left:0;right:0;bottom:0;padding:70px 18px 18px;background:linear-gradient(transparent,rgba(5,6,12,.92) 45%)}
.card h2{margin:0;font:700 24px/1.15 -apple-system,Roboto,sans-serif}
.badge{display:inline-block;margin-bottom:8px;padding:4px 9px;border-radius:999px;background:rgba(245,181,74,.2);color:#ffd99a;font-size:11px;font-weight:800;letter-spacing:.08em}
.meta{color:#ffd99a;font-size:13px;font-weight:700;margin:6px 0}
.ov{color:#d9d4e2;font-size:14px;max-height:5.6em;overflow:hidden}
.stamp{position:absolute;top:22px;padding:6px 12px;border:3px solid;border-radius:10px;font-weight:900;font-size:22px;letter-spacing:.06em;opacity:0;transform:rotate(-12deg)}
.stamp.yes{left:18px;color:var(--good);border-color:var(--good)} .stamp.no{right:18px;color:var(--bad);border-color:var(--bad);transform:rotate(12deg)}
.row{display:flex;gap:12px;justify-content:center;margin-top:16px}
.vote{flex:1;padding:14px 0;border-radius:18px;border:1px solid var(--line);background:rgba(255,255,255,.06);color:var(--text);font-weight:800;font-size:15px}
.vote.no{color:var(--bad)} .vote.yes{color:var(--good)} .vote.love{color:var(--amber)}
.prog{height:4px;border-radius:9px;background:rgba(255,255,255,.12);margin-top:10px;overflow:hidden}.prog>i{display:block;height:100%;background:var(--amber);width:0}
.center{text-align:center;padding-top:40px}
.big{font:400 40px/1.1 Georgia,serif;margin:14px 0}
</style></head><body><main id="app"></main>
<script>
const ID="__ID__", base="/t/"+ID; let pid=localStorage.getItem("pt_"+ID), st=null, deck=[], i=0, myMoods=new Set(), mins=120, type="any", busy=false;
const ${'$'}=(h)=>{document.getElementById("app").innerHTML=h};
const post=(path,data)=>fetch(base+"/"+path,{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams(data)}).then(r=>r.ok?r.json():Promise.reject(r));
async function poll(){ try{ st=await (await fetch(base+"/state?p="+(pid||""))).json(); render(); }catch(e){} }
function esc(s){return (s||"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]))}
function render(){
  if(!pid){ return joinView(); }
  if(st.phase==="LOBBY"||st.phase==="BUILDING"){
    const me=st.players.find(p=>p.name===st.me);
    if(!me){ localStorage.removeItem("pt_"+ID); pid=null; return joinView(); }
    if(!me.ready) return prefsView();
    return waitView(st.phase==="BUILDING"?"Shuffling your deck…":"Waiting for everyone to be ready…");
  }
  if(st.phase==="VOTING"){
    deck=st.deck||[]; if(i<st.voted) i=st.voted;
    if(i>=deck.length) return doneView();
    if(!document.querySelector(".deck")) return cardView();
    return;
  }
  if(st.phase==="RESULTS") return resultView();
}
function joinView(){
  ${'$'}(`<div class="eyebrow">Pick together</div><h1>Let's find something you'll both love.</h1>
  <p class="sub">Vote privately on a short deck. The TV reveals what you all said yes to.</p>
  <input id="n" placeholder="Your name" maxlength="16" value="${'$'}{esc(localStorage.getItem('pt_name')||'')}">
  <button class="btn" id="j">Join</button>`);
  document.getElementById("j").onclick=async()=>{const n=document.getElementById("n").value.trim()||"Guest";localStorage.setItem("pt_name",n);
    try{const r=await post("join",{name:n});pid=r.pid;localStorage.setItem("pt_"+ID,pid);poll();}catch(e){alert("Couldn't join: the room may be full or voting has started.")}};
}
function prefsView(){
  const moods=st.moods.map(m=>`<button class="chip ${'$'}{myMoods.has(m.key)?'on':''}" data-m="${'$'}{m.key}">${'$'}{m.label}</button>`).join("");
  const seg=(v,l,cur,attr)=>`<button class="chip ${'$'}{cur===v?'on':''}" data-${'$'}{attr}="${'$'}{v}">${'$'}{l}</button>`;
  ${'$'}(`<div class="eyebrow">Hi ${'$'}{esc(st.me)}</div><h1>What are you in the mood for?</h1><p class="sub">Pick any that sound good. Your picks stay private.</p>
  <div class="chips">${'$'}{moods}</div>
  <label class="h">Time you've got</label><div class="chips">${'$'}{seg(90,"Under 90 min",mins,"t")}${'$'}{seg(120,"Under 2 hrs",mins,"t")}${'$'}{seg(0,"Any length",mins,"t")}</div>
  <label class="h">Movie or show</label><div class="chips">${'$'}{seg("movie","Movie",type,"k")}${'$'}{seg("tv","Show",type,"k")}${'$'}{seg("any","Either",type,"k")}</div>
  <button class="btn" id="go">I'm ready</button>`);
  document.querySelectorAll("[data-m]").forEach(b=>b.onclick=()=>{const k=b.dataset.m;myMoods.has(k)?myMoods.delete(k):myMoods.add(k);prefsView()});
  document.querySelectorAll("[data-t]").forEach(b=>b.onclick=()=>{mins=+b.dataset.t;prefsView()});
  document.querySelectorAll("[data-k]").forEach(b=>b.onclick=()=>{type=b.dataset.k;prefsView()});
  document.getElementById("go").onclick=async()=>{await post("prefs",{p:pid,moods:[...myMoods].join(","),minutes:mins||"",type});poll()};
}
function who(){return `<div class="who">${'$'}{st.players.map(p=>`<span class="${'$'}{p.ready?'ok':''}">${'$'}{esc(p.name)} ${'$'}{p.ready?'✓':'…'}</span>`).join("")}</div>`}
function waitView(msg){ ${'$'}(`<div class="center"><div class="eyebrow">Pick together</div><div class="big">${'$'}{msg}</div>${'$'}{who()}<p class="sub">Voting starts on the TV.</p></div>`) }
function cardView(){
  const c=deck[i]; if(!c) return doneView();
  ${'$'}(`<div class="eyebrow">${'$'}{i+1} of ${'$'}{deck.length}</div><div class="prog"><i style="width:${'$'}{100*i/deck.length}%"></i></div>
  <div class="deck"><div class="card" id="c"><img src="${'$'}{c.img}" alt=""><div class="stamp yes">YES</div><div class="stamp no">NAH</div>
  <div class="info">${'$'}{c.badge?`<div class="badge">${'$'}{esc(c.badge)}</div>`:""}<h2>${'$'}{esc(c.title)}</h2><div class="meta">${'$'}{[c.kind,c.year,c.runtime,c.rating].filter(Boolean).map(esc).join(" · ")}</div>
  <div class="meta" style="color:#cfe3ff">${'$'}{esc(c.where)}</div><div class="ov">${'$'}{esc(c.overview)}</div></div></div></div>
  <div class="row"><button class="vote no" data-v="-1">✕ Nah</button><button class="vote yes" data-v="1">✓ Yes</button><button class="vote love" data-v="2">★ Love it</button></div>`);
  document.querySelectorAll("[data-v]").forEach(b=>b.onclick=()=>cast(+b.dataset.v));
  const el=document.getElementById("c"); let x0=null,dx=0;
  el.addEventListener("touchstart",e=>{x0=e.touches[0].clientX;el.style.transition="none"},{passive:true});
  el.addEventListener("touchmove",e=>{if(x0===null)return;dx=e.touches[0].clientX-x0;el.style.transform=`translateX(${'$'}{dx}px) rotate(${'$'}{dx/18}deg)`;
    el.querySelector(".yes").style.opacity=Math.max(0,dx/120);el.querySelector(".no").style.opacity=Math.max(0,-dx/120)},{passive:true});
  el.addEventListener("touchend",()=>{el.style.transition="";if(dx>90)cast(1);else if(dx<-90)cast(-1);else{el.style.transform="";el.querySelectorAll(".stamp").forEach(s=>s.style.opacity=0)}x0=null;dx=0});
}
async function cast(v){ if(busy) return; busy=true; const c=deck[i]; const el=document.getElementById("c");
  if(el){el.style.transform=`translateX(${'$'}{v<0?-520:520}px) rotate(${'$'}{v<0?-18:18}deg)`;el.style.opacity=0}
  try{await post("vote",{p:pid,t:c.id,v})}catch(e){}
  setTimeout(()=>{i++;busy=false;cardView()},230) }
function doneView(){ ${'$'}(`<div class="center"><div class="eyebrow">All done</div><div class="big">Look at the TV.</div>${'$'}{who().replace(/✓|…/g,"")}<p class="sub">Waiting for everyone to finish voting…</p></div>`) }
function resultView(){ ${'$'}(`<div class="center"><div class="eyebrow">${'$'}{st.matches>0?"It's a match":"No perfect match"}</div><div class="big">${'$'}{esc(st.winner||"See the TV for your closest picks")}</div><p class="sub">Enjoy the show. The TV has the details.</p></div>`) }
poll(); setInterval(()=>{ if(!document.querySelector(".deck")||st.phase!=="VOTING") poll(); else fetch(base+"/state?p="+pid).then(r=>r.json()).then(s=>{st=s; if(s.phase!=="VOTING") render()}).catch(()=>{}) },1500);
</script></body></html>"""
