package dev.asdevs.signalr_flutter

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import microsoft.aspnet.signalr.client.ConnectionState
import microsoft.aspnet.signalr.client.Credentials
import microsoft.aspnet.signalr.client.LogLevel
import microsoft.aspnet.signalr.client.Logger
import microsoft.aspnet.signalr.client.Platform
import microsoft.aspnet.signalr.client.SignalRFuture
import microsoft.aspnet.signalr.client.http.android.AndroidPlatformComponent
import microsoft.aspnet.signalr.client.hubs.HubConnection
import microsoft.aspnet.signalr.client.hubs.HubProxy
import microsoft.aspnet.signalr.client.hubs.SubscriptionHandler2
import microsoft.aspnet.signalr.client.transport.LongPollingTransport
import microsoft.aspnet.signalr.client.transport.ServerSentEventsTransport
import java.lang.Exception

/** SignalrFlutterPlugin */
class SignalrFlutterPlugin : FlutterPlugin, SignalrApi.SignalRHostApi {

    companion object {
        private const val TAG = "SignalRFlutterPlugin"

        // Set to true while debugging, false when shipping.
        private const val DEBUG_LOGS = false

        @Volatile
        private var platformLoaded = false
    }

    private lateinit var connection: HubConnection
    private lateinit var hub: HubProxy

    private lateinit var signalrApi: SignalrApi.SignalRPlatformApi

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        if (DEBUG_LOGS) {
            Log.d(TAG, "onAttachedToEngine() called, plugin attached")
        }

        // Required for the Java SignalR client on Android
        if (!platformLoaded) {
            try {
                Platform.loadPlatformComponent(AndroidPlatformComponent())
                platformLoaded = true
                if (DEBUG_LOGS) {
                    Log.d(TAG, "Platform.loadPlatformComponent(AndroidPlatformComponent()) called")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to load AndroidPlatformComponent: ${ex.message}", ex)
            }
        }

        SignalrApi.SignalRHostApi.setup(flutterPluginBinding.binaryMessenger, this)
        signalrApi = SignalrApi.SignalRPlatformApi(flutterPluginBinding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (DEBUG_LOGS) {
            Log.d(TAG, "onDetachedFromEngine() called, plugin detached")
        }
        SignalrApi.SignalRHostApi.setup(binding.binaryMessenger, null)
    }

    override fun connect(
        connectionOptions: SignalrApi.ConnectionOptions,
        result: SignalrApi.Result<String>?
    ) {
        connectionOptions.transport = SignalrApi.Transport.LONG_POLLING
        if (DEBUG_LOGS) {
            Log.d(
                TAG,
                "connect() called. baseUrl=${connectionOptions.baseUrl}, " +
                        "hubName=${connectionOptions.hubName}, " +
                        "transport=${connectionOptions.transport}, " +
                        "hasQueryString=${!connectionOptions.queryString.isNullOrEmpty()}, " +
                        "headersCount=${connectionOptions.headers?.size ?: 0}"
            )
        }

        try {
            // 1. Stop previous connection if needed
            if (this::connection.isInitialized) {
                val prevState = connection.state
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connect() - previous connection exists. state=$prevState")
                }
                if (prevState == ConnectionState.Connected ||
                    prevState == ConnectionState.Reconnecting
                ) {
                    try {
                        if (DEBUG_LOGS) {
                            Log.d(TAG, "connect() - stopping previous connection")
                        }
                        connection.stop()
                    } catch (t: Throwable) {
                        Log.w(TAG, "connect() - error stopping previous connection: ${t.message}", t)
                    }
                }
            } else {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connect() - no previous connection")
                }
            }

            // 2. Logger for SignalR internals
            val logger = object : Logger {
                override fun log(message: String?, level: LogLevel?) {
                    when (level) {
                        LogLevel.Critical -> {
                            Log.e(TAG, "[SignalR-Critical] $message")
                        }
                        LogLevel.Information -> {
                            if (DEBUG_LOGS) {
                                Log.i(TAG, "[SignalR-Information] $message")
                            }
                        }
                        LogLevel.Verbose -> {
                            if (DEBUG_LOGS) {
                                Log.d(TAG, "[SignalR-Verbose] $message")
                            }
                        }
                        else -> {
                            if (DEBUG_LOGS) {
                                Log.d(TAG, "[SignalR-${level?.name}] $message")
                            }
                        }
                    }
                }
            }

            // 3. Create HubConnection
            connection =
                if (!connectionOptions.queryString.isNullOrEmpty()) {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - creating HubConnection with queryString")
                    }
                    HubConnection(
                        connectionOptions.baseUrl,
                        connectionOptions.queryString,
                        true,
                        logger
                    )
                } else {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - creating HubConnection without queryString")
                    }
                    HubConnection(
                        connectionOptions.baseUrl,
                        null,
                        true,
                        logger
                    )
                }

            // 4. Apply headers as Credentials
            val headers = connectionOptions.headers
            if (headers != null && headers.isNotEmpty()) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connect() - applying ${headers.size} header(s)")
                }
                val cred = Credentials { request ->
                    headers.forEach { (k, v) ->
                        if (!k.isNullOrEmpty() && v != null) {
                            request.headers[k] = v
                        }
                    }
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - headers set on request (Authorization redacted)")
                    }
                }
                connection.credentials = cred
            }

            // 5. Create HubProxy
            hub = connection.createHubProxy(connectionOptions.hubName)
            if (DEBUG_LOGS) {
                Log.d(TAG, "connect() - hub proxy created for ${connectionOptions.hubName}")
            }

            // 6. Subscribe to hub methods
            connectionOptions.hubMethods?.forEach { methodName ->
                if (!methodName.isNullOrEmpty()) {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - subscribing to hub method: $methodName")
                    }
                    hub.on(
                        methodName,
                        object : SubscriptionHandler2<String, String> {
                            override fun run(p1: String?, p2: String?) {
                                if (DEBUG_LOGS) {
                                    Log.d(TAG, "onNewMessage from $methodName p1=$p1 p2=$p2")
                                }
                                val arguments = arrayListOf(p1 ?: "", p2 ?: "")
                                Handler(Looper.getMainLooper()).post {
                                    signalrApi.onNewMessage(methodName, arguments) {}
                                }
                            }
                        },
                        String::class.java,
                        String::class.java
                    )
                }
            }

            // 7. Status callbacks
            connection.connected {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connection.connected() - id=${connection.connectionId}")
                }
                postStatus(SignalrApi.ConnectionStatus.CONNECTED)
            }
            connection.reconnected {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connection.reconnected() - id=${connection.connectionId}")
                }
                postStatus(SignalrApi.ConnectionStatus.CONNECTED)
            }
            connection.reconnecting {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connection.reconnecting()")
                }
                postStatus(SignalrApi.ConnectionStatus.RECONNECTING)
            }
            connection.closed {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connection.closed()")
                }
                postStatus(SignalrApi.ConnectionStatus.DISCONNECTED)
            }
            connection.connectionSlow {
                Log.w(TAG, "connection.connectionSlow()")
                postStatus(SignalrApi.ConnectionStatus.CONNECTION_SLOW)
            }
            connection.error { err ->
                Log.e(TAG, "connection.error(): ${err.message}", err)
                postStatus(
                    SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                    err.localizedMessage
                )
            }

            // 8. Start connection with selected transport
            val startFuture: SignalRFuture<Void> = when (connectionOptions.transport) {
                SignalrApi.Transport.SERVER_SENT_EVENTS -> {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - starting with SERVER_SENT_EVENTS transport")
                    }
                    ServerSentEventsTransport(connection.logger).let { t ->
                        connection.start(t)
                    }
                }

                SignalrApi.Transport.LONG_POLLING -> {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - starting with LONG_POLLING transport")
                    }
                    LongPollingTransport(connection.logger).let { t ->
                        connection.start(t)
                    }
                }

                else -> {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "connect() - starting with AUTO transport")
                    }
                    connection.start()
                }
            }

            if (DEBUG_LOGS) {
                Log.d(TAG, "connect() - startFuture created, attaching callbacks")
            }
            postStatus(SignalrApi.ConnectionStatus.CONNECTING)

            // 9. Only COMPLETE result when start() actually finishes
            startFuture.done {
                val id = connection.connectionId
                val state = connection.state
                if (DEBUG_LOGS) {
                    Log.d(TAG, "connect() - startFuture.done(), state=$state, connectionId=$id")
                }

                Handler(Looper.getMainLooper()).post {
                    // In case connection.connected() didn't fire for some reason,
                    // ensure Flutter sees CONNECTED at least once.
                    if (state == ConnectionState.Connected) {
                        postStatus(SignalrApi.ConnectionStatus.CONNECTED)
                    }
                    result?.success(id ?: "")
                }
            }

            startFuture.onError { t ->
                Log.e(TAG, "connect() - startFuture.onError: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    postStatus(
                        SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                        t.localizedMessage
                    )
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "connect() - exception: ${ex.message}", ex)
            postStatus(
                SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                ex.localizedMessage
            )
            result?.error(ex)
        }
    }

    private fun postStatus(
        status: SignalrApi.ConnectionStatus,
        errorMessage: String? = null
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val r = SignalrApi.StatusChangeResult()
                if (this::connection.isInitialized) {
                    r.connectionId = connection.connectionId
                }
                r.status = status
                r.errorMessage = errorMessage
                if (DEBUG_LOGS) {
                    Log.d(TAG, "postStatus() -> status=$status, connectionId=${r.connectionId}, error=$errorMessage")
                }
                signalrApi.onStatusChange(r) {}
            } catch (t: Throwable) {
                Log.e(TAG, "postStatus() failed: ${t.message}", t)
            }
        }
    }

    override fun reconnect(result: SignalrApi.Result<String>?) {
        if (DEBUG_LOGS) {
            Log.d(TAG, "reconnect() called")
        }
        try {
            if (!this::connection.isInitialized) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "reconnect() - no existing connection, returning empty id")
                }
                result?.success("")
                return
            }

            if (connection.state == ConnectionState.Connected) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "reconnect() - already connected, id=${connection.connectionId}")
                }
                result?.success(connection.connectionId ?: "")
                return
            }

            if (DEBUG_LOGS) {
                Log.d(TAG, "reconnect() - starting connection.restart()")
            }
            val fut: SignalRFuture<Void> = connection.start()
            fut.done {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "reconnect() - start done, id=${connection.connectionId}")
                }
                Handler(Looper.getMainLooper()).post {
                    result?.success(connection.connectionId ?: "")
                }
            }
            fut.onError { t ->
                Log.e(TAG, "reconnect() - error: ${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    postStatus(
                        SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                        t.localizedMessage
                    )
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "reconnect() - exception: ${ex.message}", ex)
            postStatus(
                SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                ex.localizedMessage
            )
            result?.error(ex)
        }
    }

    override fun stop(result: SignalrApi.Result<Void>?) {
        if (DEBUG_LOGS) {
            Log.d(TAG, "stop() called")
        }
        try {
            if (!this::connection.isInitialized) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "stop() - connection not initialized")
                }
                result?.success(null)
                return
            }

            val state = connection.state
            if (state != ConnectionState.Connected &&
                state != ConnectionState.Reconnecting
            ) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "stop() ignored. state=$state")
                }
                result?.success(null)
                return
            }

            if (DEBUG_LOGS) {
                Log.d(TAG, "stop() - calling connection.stop()")
            }
            try {
                connection.stop()
            } catch (npe: NullPointerException) {
                // Work around library NPE when stop() is called at bad times.
                Log.w(TAG, "stop() swallowed NPE: ${npe.message}")
            }

            result?.success(null)
        } catch (ex: Exception) {
            Log.e(TAG, "stop() - exception: ${ex.message}", ex)
            postStatus(
                SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                ex.localizedMessage
            )
            result?.error(ex)
        }
    }

    override fun isConnected(result: SignalrApi.Result<Boolean>?) {
        try {
            val connected =
                this::connection.isInitialized && connection.state == ConnectionState.Connected
            if (DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "isConnected() -> $connected, state=${
                        if (this::connection.isInitialized) connection.state else "NOT_INITIALIZED"
                    }"
                )
            }
            result?.success(connected)
        } catch (ex: Exception) {
            Log.e(TAG, "isConnected() - exception: ${ex.message}", ex)
            result?.error(ex)
        }
    }

    override fun invokeMethod(
        methodName: String,
        arguments: MutableList<String>,
        result: SignalrApi.Result<String>?
    ) {
        if (DEBUG_LOGS) {
            Log.d(TAG, "invokeMethod() called. method=$methodName args=$arguments")
        }
        try {
            val res: SignalRFuture<String> =
                hub.invoke(String::class.java, methodName, *arguments.toTypedArray())

            res.done { msg: String? ->
                if (DEBUG_LOGS) {
                    Log.d(TAG, "invokeMethod() done. method=$methodName result=$msg")
                }
                Handler(Looper.getMainLooper()).post {
                    result?.success(msg ?: "")
                }
            }

            res.onError { t ->
                Log.e(TAG, "invokeMethod() error. method=$methodName msg=${t.message}", t)
                Handler(Looper.getMainLooper()).post {
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "invokeMethod() - exception: ${ex.message}", ex)
            result?.error(ex)
        }
    }
}