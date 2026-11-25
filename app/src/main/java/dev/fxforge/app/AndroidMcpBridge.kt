package dev.fxforge.app

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import dev.fxforge.shizuku.ShizukuUserServiceController
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class AndroidMcpBridgeState(
    val running: Boolean = false,
    val port: Int? = null,
    val allowDeviceControl: Boolean = false,
    val error: String? = null
)

data class AndroidMcpBridgeInfo(
    val port: Int,
    val token: String
)

class AndroidMcpBridge(
    context: Context,
    private val shizukuController: ShizukuUserServiceController
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(AndroidMcpBridgeState())
    val state: StateFlow<AndroidMcpBridgeState> = mutableState.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var bearerToken: String? = null
    @Volatile private var allowDeviceControl = false
    @Volatile private var workers = Executors.newFixedThreadPool(MAX_WORKERS)

    @Synchronized
    fun start(allowControl: Boolean): AndroidMcpBridgeInfo {
        val current = serverSocket
        val currentToken = bearerToken
        if (current != null && !current.isClosed && currentToken != null) {
            allowDeviceControl = allowControl
            mutableState.value = AndroidMcpBridgeState(
                running = true,
                port = current.localPort,
                allowDeviceControl = allowControl
            )
            return AndroidMcpBridgeInfo(current.localPort, currentToken)
        }
        return try {
            if (workers.isShutdown) workers = Executors.newFixedThreadPool(MAX_WORKERS)
            val tokenBytes = ByteArray(32)
            SecureRandom().nextBytes(tokenBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = ACCEPT_TIMEOUT_MS
            bearerToken = token
            allowDeviceControl = allowControl
            serverSocket = socket
            acceptThread = Thread({ acceptLoop(socket) }, "fxforge-android-mcp").apply {
                isDaemon = true
                start()
            }
            mutableState.value = AndroidMcpBridgeState(
                running = true,
                port = socket.localPort,
                allowDeviceControl = allowControl
            )
            AndroidMcpBridgeInfo(socket.localPort, token)
        } catch (exception: IOException) {
            mutableState.value = AndroidMcpBridgeState(
                error = "Could not start the Android MCP bridge: " + (exception.message ?: "socket error")
            )
            throw exception
        }
    }

    @Synchronized
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        bearerToken = null
        allowDeviceControl = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        workers.shutdownNow()
        mutableState.value = AndroidMcpBridgeState()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                workers.execute {
                    client.use { handleClient(it) }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Re-check the closed flag.
            } catch (_: IOException) {
                if (!socket.isClosed) {
                    mutableState.value = mutableState.value.copy(
                        error = "Android MCP bridge socket stopped unexpectedly."
                    )
                }
                break
            } catch (_: RuntimeException) {
                if (socket.isClosed) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = CLIENT_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) {
            writeHttp(socket, 400, "Bad Request", "")
            return
        }
        val method = requestParts[0]
        val path = requestParts[1]
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        if (path != "/mcp") {
            writeHttp(socket, 404, "Not Found", "")
            return
        }
        if (method != "POST") {
            writeHttp(socket, 405, "Method Not Allowed", "")
            return
        }
        val expectedToken = bearerToken ?: run {
            writeHttp(socket, 503, "Service Unavailable", "")
            return
        }
        val authorization = headers["authorization"].orEmpty()
        val suppliedToken = authorization.removePrefix("Bearer ").trim()
        if (!authorization.startsWith("Bearer ") ||
            !MessageDigest.isEqual(
                expectedToken.toByteArray(Charsets.UTF_8),
                suppliedToken.toByteArray(Charsets.UTF_8)
            )
        ) {
            writeHttp(socket, 401, "Unauthorized", "")
            return
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
        if (contentLength == null || contentLength < 0 || contentLength > MAX_REQUEST_BYTES) {
            writeHttp(socket, 413, "Payload Too Large", "")
            return
        }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = input.read(body, offset, contentLength - offset)
            if (count < 0) {
                writeHttp(socket, 400, "Bad Request", "")
                return
            }
            offset += count
        }
        val request = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            writeHttp(socket, 400, "Bad Request", "")
            return
        }
        val id = request.opt("id")
        val methodName = request.optString("method")
        if (id == null || id == JSONObject.NULL) {
            if (methodName == "notifications/initialized" || methodName.startsWith("notifications/")) {
                writeHttp(socket, 202, "Accepted", "")
            } else {
                writeHttp(socket, 202, "Accepted", "")
            }
            return
        }
        val response = try {
            handleJsonRpc(request)
        } catch (exception: Exception) {
            jsonRpcError(id, -32603, exception.message ?: "Internal MCP bridge error")
        }
        writeJson(socket, response)
    }

    private fun handleJsonRpc(request: JSONObject): JSONObject {
        val id = request.opt("id")
        return when (request.optString("method")) {
            "initialize" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("result", JSONObject()
                        .put("protocolVersion", MCP_PROTOCOL_VERSION)
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject()
                            .put("name", "fxforge-android")
                            .put("version", "0.1.0"))
                        .put("instructions", "FxForge exposes explicitly enabled Android device tools through Shizuku. Shell and device-changing tools require the user's device-control toggle.")
                    )
            }
            "ping" -> JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject())
            "tools/list" -> JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("result", JSONObject().put("tools", toolDefinitions()))
            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val result = try {
                    callTool(name, arguments)
                } catch (exception: Exception) {
                    JSONObject()
                        .put("content", JSONArray().put(
                            JSONObject().put("type", "text").put("text", exception.message ?: "Android tool failed.")
                        ))
                        .put("isError", true)
                }
                JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            }
            else -> jsonRpcError(id, -32601, "Method not found: " + request.optString("method"))
        }
    }

    private fun toolDefinitions(): JSONArray {
        val tools = JSONArray()
        tools.put(tool(
            "android_device_info",
            "Read Android build facts and the actual Shizuku service UID.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false)
        ))
        tools.put(tool(
            "android_logcat",
            "Read a bounded recent Android logcat capture. Logs can contain private device data.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("lines", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 500)))
        ))
        if (allowDeviceControl) {
            tools.put(tool(
                "android_shell",
                "Run an explicitly user-enabled command through the Shizuku Android shell UID.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("command", JSONObject().put("type", "string"))
                        .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 60)))
                    .put("required", JSONArray().put("command"))
            ))
            tools.put(tool(
                "android_install_apk",
                "Install an APK produced in FxForge's private project workspace using Shizuku.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("path"))
            ))
            tools.put(tool(
                "android_launch_app",
                "Launch an installed Android app by package name through Shizuku.",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("package_name", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("package_name"))
            ))
        }
        return tools
    }

    private fun tool(name: String, description: String, schema: JSONObject) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val text = when (name) {
            "android_device_info" -> {
                runBlocking(Dispatchers.IO) { shizukuController.readDeviceInfo() }
                val facts = shizukuController.status.value.deviceFacts
                    ?: throw IOException(shizukuController.status.value.error ?: "Shizuku device information is unavailable.")
                JSONObject()
                    .put("uid", facts.uid)
                    .put("privilege", if (facts.uid == 0) "root" else if (facts.uid == 2000) "adb-shell" else "app")
                    .put("android_release", facts.androidRelease)
                    .put("api_level", facts.apiLevel)
                    .put("manufacturer", facts.manufacturer)
                    .put("model", facts.model)
                    .put("fingerprint", facts.fingerprint)
                    .put("supported_abis", JSONArray(facts.supportedAbis))
                    .toString(2)
            }
            "android_logcat" -> {
                val lines = args.optInt("lines", 200).coerceIn(1, 500)
                runBlocking(Dispatchers.IO) { shizukuController.readLogcat(lines) }
                val status = shizukuController.status.value
                status.logcatError?.let { throw IOException(it) }
                status.logcatOutput.orEmpty()
            }
            "android_shell" -> {
                requireDeviceControl()
                val command = args.optString("command")
                if (command.isBlank() || command.length > 8192) throw IOException("Command must contain 1 to 8192 characters.")
                val timeout = args.optInt("timeout_seconds", 30).coerceIn(1, 60)
                val result = runBlocking(Dispatchers.IO) {
                    shizukuController.executeCommand(command, timeout)
                }
                if (result.error != null) throw IOException(result.error)
                "exit_code=" + (result.exitCode?.toString() ?: "unknown") +
                    (if (result.truncated) "\n[output truncated at 64 KiB]\n" else "\n") + result.output
            }
            "android_install_apk" -> {
                requireDeviceControl()
                val apk = resolveWorkspaceApk(args.optString("path"))
                val descriptor = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
                val result = runBlocking(Dispatchers.IO) {
                    descriptor.use { shizukuController.installApk(it) }
                }
                if (!result.success) throw IOException(result.error ?: "Android package manager rejected the APK.")
                "Installed successfully.\n" + result.output
            }
            "android_launch_app" -> {
                requireDeviceControl()
                val packageName = args.optString("package_name")
                if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
                    throw IOException("Invalid Android package name.")
                }
                val result = runBlocking(Dispatchers.IO) {
                    shizukuController.executeCommand("monkey -p " + packageName + " 1", 15)
                }
                if (result.error != null || result.exitCode != 0) {
                    throw IOException(result.error ?: result.output)
                }
                "Launch request sent to " + packageName + ".\n" + result.output
            }
            else -> throw IOException("Unknown FxForge Android tool: " + name)
        }
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", false)
    }

    private fun requireDeviceControl() {
        if (!allowDeviceControl) {
            throw IOException("Device-changing tools are disabled. Enable full Android device control in FxForge.")
        }
    }

    private fun resolveWorkspaceApk(requestedPath: String): File {
        if (requestedPath.isBlank()) throw IOException("Provide the APK path.")
        val workspaceRoot = File(appContext.filesDir, "workspaces/active").canonicalFile
        val relative = when {
            requestedPath.startsWith("/workspace/") -> requestedPath.removePrefix("/workspace/")
            requestedPath.startsWith(workspaceRoot.path + File.separator) ->
                requestedPath.removePrefix(workspaceRoot.path + File.separator)
            else -> throw IOException("APK paths must be inside the active FxForge workspace.")
        }
        val apk = File(workspaceRoot, relative).canonicalFile
        if (!apk.path.startsWith(workspaceRoot.path + File.separator) ||
            !apk.isFile || !apk.name.endsWith(".apk", ignoreCase = true)
        ) {
            throw IOException("The requested APK is not a valid file inside the active workspace.")
        }
        return apk
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String) =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun writeJson(socket: Socket, response: JSONObject) {
        writeHttp(socket, 200, "OK", response.toString(), "application/json")
    }

    private fun writeHttp(
        socket: Socket,
        status: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
            "Content-Type: " + contentType + "; charset=utf-8\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (value == 10) break
            if (value != 13) {
                bytes.write(value)
                if (bytes.size() > MAX_HEADER_LINE_BYTES) throw IOException("HTTP header line is too long.")
            }
        }
        return bytes.toString("UTF-8")
    }

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_WORKERS = 4
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val ACCEPT_TIMEOUT_MS = 1000
        private const val CLIENT_TIMEOUT_MS = 10_000
        private val PACKAGE_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
