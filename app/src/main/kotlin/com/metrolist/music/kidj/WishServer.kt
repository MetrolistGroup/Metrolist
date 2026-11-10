/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny LAN web server for guests who scanned the QR code.
 * GET / -> wish page, GET /api/wishes -> JSON, POST /api/wish (q=...), POST /api/vote (id=...).
 */
class WishServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        /** Returns a short message for the guest. */
        fun wish(query: String, guest: String): String
        fun vote(id: String, guest: String): Boolean
        fun wishesJson(): String
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    val isRunning get() = socket?.isClosed == false

    fun start() {
        if (isRunning) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { client.use { serve(it) } }
            }
        }, "KiDjWishServer").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 5_000
        val guest = client.inetAddress.hostAddress ?: "?"
        val response = runCatching {
            val req = readRequest(BufferedInputStream(client.getInputStream())) ?: return
            route(req, guest)
        }.getOrElse { Response(500, "text/plain", "error") }
        val body = response.body.toByteArray()
        val head = "HTTP/1.1 ${response.status} OK\r\n" +
            "Content-Type: ${response.type}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    internal fun route(req: Request, guest: String): Response = when {
        req.method == "GET" && req.path == "/" -> Response(200, "text/html", WISH_PAGE)
        req.method == "GET" && req.path == "/api/wishes" -> Response(200, "application/json", handler.wishesJson())
        req.method == "POST" && req.path == "/api/wish" -> {
            val q = req.form["q"]?.trim().orEmpty().take(MAX_QUERY)
            if (q.length < 2) Response(400, "application/json", jsonMessage("Bitte Titel und Interpret eingeben."))
            else Response(200, "application/json", jsonMessage(handler.wish(q, guest)))
        }
        req.method == "POST" && req.path == "/api/vote" -> {
            val ok = handler.vote(req.form["id"].orEmpty().take(32), guest)
            Response(200, "application/json", jsonMessage(if (ok) "Stimme gezählt" else "Schon abgestimmt"))
        }
        else -> Response(404, "text/plain", "not found")
    }

    internal data class Request(val method: String, val path: String, val form: Map<String, String>)

    internal data class Response(val status: Int, val type: String, val body: String)

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_BODY = 2048
        private const val MAX_QUERY = 120

        internal fun readRequest(input: InputStream): Request? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (++total > 8192) return null
                if (c == '\n'.code) {
                    val l = line.toString().trimEnd('\r')
                    if (l.isEmpty()) break
                    lines += l
                    line.clear()
                } else {
                    line.append(c.toChar())
                }
            }
            val parts = lines.firstOrNull()?.split(" ") ?: return null
            if (parts.size < 2) return null
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return Request(parts[0], parts[1].substringBefore('?'), parseForm(String(body, 0, read)))
        }

        internal fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }

        fun jsonString(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' || c == '<' || c == '>' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun jsonMessage(msg: String) = "{\"message\":${jsonString(msg)}}"

        private val WISH_PAGE = """
<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Musikwunsch</title>
<style>
:root{color-scheme:dark}body{margin:0;font:16px system-ui,sans-serif;background:#111;color:#eee}
main{max-width:520px;margin:auto;padding:16px}h1{font-size:22px}
form{display:flex;gap:8px}input{flex:1;padding:12px;border-radius:10px;border:1px solid #444;background:#222;color:#eee;font-size:16px}
button{padding:12px 16px;border:0;border-radius:10px;background:#7c4dff;color:#fff;font-size:16px}
#msg{min-height:24px;margin:10px 0;color:#b9a6ff}
li{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #222}
li img{width:48px;height:48px;border-radius:6px;object-fit:cover}li div{flex:1;min-width:0}
li small{color:#999;display:block}ul{list-style:none;padding:0}
</style></head><body><main>
<h1>Wünsch dir einen Song</h1>
<form id="f"><input id="q" maxlength="120" placeholder="Titel und Interpret" autocomplete="off" required><button>Senden</button></form>
<div id="msg" role="status"></div>
<h2>Wünsche</h2><ul id="list"></ul>
</main><script>
const ${'$'}=s=>document.querySelector(s);
async function post(url,data){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});return (await r.json()).message}
async function load(){const w=await (await fetch('/api/wishes')).json();const ul=${'$'}('#list');ul.replaceChildren();
for(const x of w){const li=document.createElement('li');const img=document.createElement('img');img.src=x.thumbnail||'';img.alt='';
const d=document.createElement('div');d.textContent=x.title;const s=document.createElement('small');s.textContent=x.artist+' · '+x.votes+' Stimme(n)';d.append(s);
const b=document.createElement('button');b.textContent='+1';b.onclick=async()=>{${'$'}('#msg').textContent=await post('/api/vote',{id:x.id});load()};
li.append(img,d,b);ul.append(li)}}
${'$'}('#f').onsubmit=async e=>{e.preventDefault();${'$'}('#msg').textContent='Suche…';${'$'}('#msg').textContent=await post('/api/wish',{q:${'$'}('#q').value});${'$'}('#q').value='';load()};
load();setInterval(load,10000);
</script></body></html>
"""
    }
}
