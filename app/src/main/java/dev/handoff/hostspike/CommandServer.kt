package dev.handoff.hostspike

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A tiny web server on the main phone. Opening it in a browser on another device on the same Wi-Fi
 * stands in for a linked device sending commands, so remote answer and decline can be tested
 * before any cloud backend exists. Every request needs the token shown in the app.
 */
class CommandServer(private val ctx: Context, private val port: Int, private val token: String) {
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        Thread({ acceptLoop() }, "command-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun acceptLoop() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            socket = s
            EventLog.log(ctx, "command_server_started", "port" to port, "address" to Status.lanAddress(ctx))
            while (!s.isClosed) {
                val client = s.accept()
                Thread { handle(client) }.start()
            }
        } catch (e: Exception) {
            if (socket?.isClosed != true) {
                EventLog.log(ctx, "command_server_error", "error" to e.toString())
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                // Browsers open spare connections that never send a request; ignore those quietly.
                val requestLine = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    null
                } ?: return
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                SelfStats.count("lan_request")
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val uri = Uri.parse("http://phone" + parts[1])
                val path = uri.path ?: "/"
                val out = sock.getOutputStream()

                if (path == "/favicon.ico") {
                    respond(out, 404, "text/plain", "")
                    return
                }
                if (uri.getQueryParameter("token") != token) {
                    respond(out, 401, "text/plain", "Missing or wrong token. Use the address shown in the Host Spike app.")
                    return
                }

                when (path) {
                    "/" -> respond(out, 200, "text/html", page())
                    "/status" -> respond(out, 200, "application/json", Status.build(ctx).toString(2))
                    "/answer" -> {
                        val ok = CallControl.answer(ctx, "lan")
                        respond(out, 200, "application/json", JSONObject().put("answer_ok", ok).toString())
                    }
                    "/decline" -> {
                        val returned = CallControl.decline(ctx, "lan")
                        respond(out, 200, "application/json", JSONObject().put("decline_returned", returned).toString())
                    }
                    "/sms" -> {
                        val to = uri.getQueryParameter("to") ?: ""
                        val body = uri.getQueryParameter("body") ?: ""
                        val result = SmsSender.send(ctx, to, body, "lan")
                        respond(out, 200, "application/json", JSONObject().put("result", result).toString())
                    }
                    else -> respond(out, 404, "text/plain", "Not found")
                }
            } catch (e: Exception) {
                EventLog.log(ctx, "command_request_error", "error" to e.toString())
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            else -> "Not Found"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun page(): String = PAGE.replace("TOKEN_VALUE", token)

    companion object {
        private val PAGE = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Host spike remote</title>
<style>
body{font-family:system-ui,sans-serif;margin:16px;max-width:640px}
button{font-size:17px;padding:12px 14px;margin:4px 4px 4px 0;border-radius:8px;border:1px solid #888;background:#f4f4f4}
pre{background:#f2f2f2;padding:8px;white-space:pre-wrap;font-size:12px;border-radius:6px}
input,textarea{width:100%;font-size:16px;margin:4px 0;box-sizing:border-box}
</style>
</head>
<body>
<h2>Host spike remote</h2>
<p>This page stands in for a linked device. Use it while the main phone is ringing.</p>
<button onclick="cmd('answer')">Answer on main phone</button>
<button onclick="cmd('decline')">Take it here (decline to forward)</button>
<h3>Send an SMS from the main phone</h3>
<input id="to" placeholder="Recipient, e.g. 0412 345 678">
<textarea id="body" rows="3">Test from host spike</textarea>
<button onclick="sms()">Send SMS</button>
<h3>Last result</h3>
<pre id="res">none</pre>
<h3>Main phone status</h3>
<pre id="st">loading</pre>
<script>
var T = 'TOKEN_VALUE';
function q(p) {
  var sep = p.indexOf('?') < 0 ? '?' : '&';
  return fetch(p + sep + 'token=' + T).then(function (r) { return r.text(); });
}
function show(t) {
  document.getElementById('res').textContent = new Date().toLocaleTimeString() + '  ' + t;
}
function cmd(c) { q('/' + c).then(show).catch(function (e) { show('error: ' + e); }); }
function sms() {
  var to = encodeURIComponent(document.getElementById('to').value);
  var body = encodeURIComponent(document.getElementById('body').value);
  q('/sms?to=' + to + '&body=' + body).then(show).catch(function (e) { show('error: ' + e); });
}
function st() {
  q('/status').then(function (t) {
    try { t = JSON.stringify(JSON.parse(t), null, 2); } catch (e) {}
    document.getElementById('st').textContent = t;
  }).catch(function () { document.getElementById('st').textContent = 'main phone not reachable'; });
}
setInterval(st, 2000);
st();
</script>
</body>
</html>
"""
    }
}
