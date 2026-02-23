package dev.asdevs.signalr_flutter

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import microsoft.aspnet.signalr.client.ConnectionState
import microsoft.aspnet.signalr.client.Credentials
import microsoft.aspnet.signalr.client.SignalRFuture
import microsoft.aspnet.signalr.client.hubs.HubConnection
import microsoft.aspnet.signalr.client.hubs.HubProxy
import microsoft.aspnet.signalr.client.hubs.SubscriptionHandler2
import microsoft.aspnet.signalr.client.transport.LongPollingTransport
import microsoft.aspnet.signalr.client.transport.ServerSentEventsTransport
import java.lang.Exception

/** SignalrFlutterPlugin */
class SignalrFlutterPlugin : FlutterPlugin, SignalrApi.SignalRHostApi {
    private lateinit var connection: HubConnection
    private lateinit var hub: HubProxy

    private lateinit var signalrApi: SignalrApi.SignalRPlatformApi

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        SignalrApi.SignalRHostApi.setup(flutterPluginBinding.binaryMessenger, this)
        signalrApi = SignalrApi.SignalRPlatformApi(flutterPluginBinding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        SignalrApi.SignalRHostApi.setup(binding.binaryMessenger, null)
    }

    override fun connect(
        connectionOptions: SignalrApi.ConnectionOptions,
        result: SignalrApi.Result<String>?
    ) {
        try {
            // If a previous connection exists, try to stop it safely.
            if (this::connection.isInitialized) {
                try {
                    if (connection.state == ConnectionState.Connected ||
                        connection.state == ConnectionState.Reconnecting
                    ) {
                        connection.stop()
                    }
                } catch (_: Throwable) {
                    // Ignore, best-effort cleanup.
                }
            }

            // Create connection (with optional query string).
            connection =
                if (!connectionOptions.queryString.isNullOrEmpty()) {
                    HubConnection(
                        connectionOptions.baseUrl,
                        connectionOptions.queryString,
                        true
                    ) { _, _ -> }
                } else {
                    HubConnection(connectionOptions.baseUrl)
                }

            // Apply headers via Credentials.
            val headers = connectionOptions.headers
            if (headers != null && headers.isNotEmpty()) {
                val cred = Credentials { request ->
                    headers.forEach { (k, v) ->
                        if (!k.isNullOrEmpty() && v != null) {
                            request.headers[k] = v
                        }
                    }
                }
                connection.credentials = cred
            }

            hub = connection.createHubProxy(connectionOptions.hubName)

            // Hub method subscriptions: support two parameters (p1, p2).
            connectionOptions.hubMethods?.forEach { methodName ->
                if (!methodName.isNullOrEmpty()) {
                    hub.on(
                        methodName,
                        object : SubscriptionHandler2<String, String> {
                            override fun run(p1: String?, p2: String?) {
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

            // Status callbacks
            connection.connected {
                postStatus(SignalrApi.ConnectionStatus.CONNECTED)
            }
            connection.reconnected {
                postStatus(SignalrApi.ConnectionStatus.CONNECTED)
            }
            connection.reconnecting {
                postStatus(SignalrApi.ConnectionStatus.RECONNECTING)
            }
            connection.closed {
                postStatus(SignalrApi.ConnectionStatus.DISCONNECTED)
            }
            connection.connectionSlow {
                postStatus(SignalrApi.ConnectionStatus.CONNECTION_SLOW)
            }
            connection.error { err ->
                postStatus(
                    SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                    err.localizedMessage
                )
            }

            // Start connection with selected transport.
            val startFuture: SignalRFuture<Void> = when (connectionOptions.transport) {
                SignalrApi.Transport.SERVER_SENT_EVENTS ->
                    connection.start(ServerSentEventsTransport(connection.logger))
                SignalrApi.Transport.LONG_POLLING ->
                    connection.start(LongPollingTransport(connection.logger))
                else ->
                    connection.start()
            }

            // Only report success once start completes successfully.
            startFuture.done {
                Handler(Looper.getMainLooper()).post {
                    result?.success(connection.connectionId ?: "")
                }
            }

            // Propagate start errors back to Flutter.
            startFuture.onError { t ->
                Handler(Looper.getMainLooper()).post {
                    postStatus(
                        SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                        t.localizedMessage
                    )
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
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
            val r = SignalrApi.StatusChangeResult()
            if (this::connection.isInitialized) {
                r.connectionId = connection.connectionId
            }
            r.status = status
            r.errorMessage = errorMessage
            signalrApi.onStatusChange(r) {}
        }
    }

    override fun reconnect(result: SignalrApi.Result<String>?) {
        try {
            if (!this::connection.isInitialized) {
                result?.success("")
                return
            }

            if (connection.state == ConnectionState.Connected) {
                result?.success(connection.connectionId ?: "")
                return
            }

            val fut: SignalRFuture<Void> = connection.start()
            fut.done {
                Handler(Looper.getMainLooper()).post {
                    result?.success(connection.connectionId ?: "")
                }
            }
            fut.onError { t ->
                Handler(Looper.getMainLooper()).post {
                    postStatus(
                        SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                        t.localizedMessage
                    )
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
            postStatus(
                SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                ex.localizedMessage
            )
            result?.error(ex)
        }
    }

    override fun stop(result: SignalrApi.Result<Void>?) {
        try {
            if (!this::connection.isInitialized) {
                result?.success(null)
                return
            }

            val state = connection.state
            if (state != ConnectionState.Connected && state != ConnectionState.Reconnecting) {
                Log.d("SignalRFlutterPlugin", "stop() ignored. state=$state")
                result?.success(null)
                return
            }

            connection.stop()
            result?.success(null)
        } catch (npe: NullPointerException) {
            // Work around library NPE when stop() is called at bad times.
            Log.w("SignalRFlutterPlugin", "stop() swallowed NPE: ${npe.message}")
            result?.success(null)
        } catch (ex: Exception) {
            postStatus(
                SignalrApi.ConnectionStatus.CONNECTION_ERROR,
                ex.localizedMessage
            )
            result?.error(ex)
        }
    }

    override fun isConnected(result: SignalrApi.Result<Boolean>?) {
        try {
            if (this::connection.isInitialized) {
                result?.success(connection.state == ConnectionState.Connected)
            } else {
                result?.success(false)
            }
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun invokeMethod(
        methodName: String,
        arguments: MutableList<String>,
        result: SignalrApi.Result<String>?
    ) {
        try {
            val res: SignalRFuture<String> =
                hub.invoke(String::class.java, methodName, *arguments.toTypedArray())

            res.done { msg: String? ->
                Handler(Looper.getMainLooper()).post {
                    result?.success(msg ?: "")
                }
            }

            res.onError { t ->
                Handler(Looper.getMainLooper()).post {
                    result?.error(t)
                }
            }
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }
}