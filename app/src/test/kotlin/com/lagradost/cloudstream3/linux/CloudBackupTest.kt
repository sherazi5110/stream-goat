package com.lagradost.cloudstream3.linux

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Google Drive backup against a stand-in for Google: code sign-in (with "pending" first), Drive's app folder
 * (create, then update), a skipped upload when nothing changed, an expired token refreshed, restore, sign-out.
 */
class CloudBackupTest {
    private class FakeGoogle {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var polls = 0
        var file: String? = null
        var uploads = 0
        var refreshes = 0
        var revoked: String? = null
        var validAccess = "access-1"

        val base get() = "http://127.0.0.1:${server.address.port}"

        private fun form(x: HttpExchange) = x.requestBody.readBytes().decodeToString().split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        private fun send(x: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray()
            x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.use { it.write(bytes) }
        }

        private fun authorized(x: HttpExchange) = x.requestHeaders.getFirst("Authorization") == "Bearer $validAccess"

        init {
            server.createContext("/device/code") { x ->
                val f = form(x)
                require(f["scope"] == "https://www.googleapis.com/auth/drive.appdata")
                send(x, 200, """{"device_code":"dev-1","user_code":"GQV-XHF-RNL","verification_url":"https://www.google.com/device","expires_in":60,"interval":1}""")
            }
            server.createContext("/token") { x ->
                val f = form(x)
                when (f["grant_type"]) {
                    "urn:ietf:params:oauth:grant-type:device_code" ->
                        if (++polls < 2) send(x, 428, """{"error":"authorization_pending"}""")
                        else send(x, 200, """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}""")
                    "refresh_token" -> {
                        require(f["refresh_token"] == "refresh-1")
                        refreshes++; validAccess = "access-${refreshes + 1}"
                        send(x, 200, """{"access_token":"$validAccess","expires_in":3600}""")
                    }
                    else -> send(x, 400, """{"error":"unsupported_grant_type"}""")
                }
            }
            server.createContext("/revoke") { x -> revoked = form(x)["token"]; send(x, 200, "") }
            server.createContext("/drive/v3/about") { x ->
                if (!authorized(x)) send(x, 401, "{}") else send(x, 200, """{"user":{"emailAddress":"goat@example.com"}}""")
            }
            server.createContext("/drive/v3/files") { x ->
                if (!authorized(x)) return@createContext send(x, 401, "{}")
                val path = x.requestURI.path
                if (path.endsWith("/files")) {
                    require(x.requestURI.rawQuery.contains("spaces=appDataFolder"))
                    val files = if (file == null) JSONArray() else JSONArray().put(JSONObject().put("id", "file-1")
                        .put("modifiedTime", "2026-11-14T10:00:00Z").put("appProperties", JSONObject().put("device", "test")))
                    send(x, 200, JSONObject().put("files", files).toString())
                } else if (x.requestURI.rawQuery == "alt=media") send(x, 200, file ?: "")
                else send(x, 404, "{}")
            }
            server.createContext("/upload/drive/v3/files") { x ->
                if (!authorized(x)) return@createContext send(x, 401, "{}")
                val boundary = x.requestHeaders.getFirst("Content-Type").substringAfter("boundary=").trim('"')
                val parts = x.requestBody.readBytes().decodeToString().split("--$boundary").filter { it.contains("{") }
                val meta = JSONObject(parts[0].substringAfter("\r\n\r\n").trim())
                if (x.requestMethod == "POST") require(meta.getJSONArray("parents").getString(0) == "appDataFolder")
                else require(x.requestMethod == "PATCH" && x.requestURI.path.endsWith("/file-1"))
                file = parts[1].substringAfter("\r\n\r\n").trim()
                uploads++
                send(x, 200, """{"id":"file-1"}""")
            }
            server.start()
        }
    }

    @Test
    fun `sign in with a code, back up to Drive's app folder, restore, sign out`(): Unit = runBlocking {
        Platform.init(); Network.init()
        val google = FakeGoogle()
        val before = CloudBackup.endpoints
        CloudBackup.endpoints = CloudBackup.Endpoints(
            deviceCode = "${google.base}/device/code", token = "${google.base}/token", revoke = "${google.base}/revoke",
            drive = "${google.base}/drive/v3", upload = "${google.base}/upload/drive/v3",
        )
        CloudBackup.clientId = "test-client"; CloudBackup.clientSecret = "test-secret"
        try {
            CloudBackup.signOut()
            val signIn = CloudBackup.startSignIn()
            assertEquals("GQV-XHF-RNL", signIn.userCode)
            assertEquals("https://www.google.com/device", signIn.url)
            assertTrue(CloudBackup.finishSignIn(signIn))
            assertEquals(2, google.polls)
            assertTrue(CloudBackup.state.value.signedIn)
            assertEquals("goat@example.com", CloudBackup.state.value.email)

            // First backup creates the file in the app folder; nothing changed: no second upload.
            assertNull(CloudBackup.remote())
            assertTrue(CloudBackup.backupNow())
            assertEquals(1, google.uploads)
            assertEquals(com.lagradost.cloudstream3.linux.Backup.FORMAT, JSONObject(google.file!!).getString("format"))
            assertFalse(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(1, google.uploads)
            // The sign-in itself is never in the backup.
            assertFalse(JSONObject(google.file!!).getJSONObject("stores").has("cloud_backup"))

            // A change uploads again (an update of the same file); an expired token is refreshed on the way.
            Platform.context.getSharedPreferences("cloud_test", 0).edit().putString("value", "one").commit()
            google.validAccess = "access-expired-on-server"
            assertTrue(CloudBackup.backupNow(onlyIfChanged = true))
            assertEquals(2, google.uploads)
            assertTrue(google.refreshes >= 1)
            assertNotNull(CloudBackup.remote())

            // Restore brings the backed-up value back.
            Platform.context.getSharedPreferences("cloud_test", 0).edit().putString("value", "changed").commit()
            CloudBackup.restore()
            assertEquals("one", Platform.context.getSharedPreferences("cloud_test", 0).getString("value", null))

            CloudBackup.signOut()
            assertEquals("refresh-1", google.revoked)
            assertFalse(CloudBackup.state.value.signedIn)
            assertFailsWith<CloudBackup.CloudException> { CloudBackup.backupNow() }
        } finally {
            Platform.context.getSharedPreferences("cloud_test", 0).edit().clear().commit()
            CloudBackup.endpoints = before
            google.server.stop(0)
        }
    }

    private fun assertNull(value: Any?) = kotlin.test.assertNull(value)
}
