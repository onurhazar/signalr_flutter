package dev.asdevs.signalr_flutter

import android.os.Handler
import android.os.Looper

import io.flutter.embedding.engine.plugins.FlutterPlugin
import microsoft.aspnet.signalr.client.ConnectionState
import microsoft.aspnet.signalr.client.Credentials
import microsoft.aspnet.signalr.client.LogLevel
import microsoft.aspnet.signalr.client.SignalRFuture
import microsoft.aspnet.signalr.client.hubs.HubConnection
import microsoft.aspnet.signalr.client.hubs.HubProxy
import microsoft.aspnet.signalr.client.transport.LongPollingTransport
import microsoft.aspnet.signalr.client.transport.ServerSentEventsTransport
import java.lang.Exception
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

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
            connection =
                if (connectionOptions.queryString?.isNotEmpty() == true) {
                    HubConnection(
                        connectionOptions.baseUrl,
                        connectionOptions.queryString,
                        true
                    ) { _: String, _: LogLevel ->
                    }
                } else {
                    HubConnection(connectionOptions.baseUrl)
                }

            if (connectionOptions.headers?.isNotEmpty() == true) {
                val cred = Credentials { request ->
                    request.headers = connectionOptions.headers
                }
                connection.credentials = cred
            }

            hub = connection.createHubProxy(connectionOptions.hubName)

            connectionOptions.hubMethods?.forEach { methodName ->
                hub.on(methodName, { res: JsonElement? ->
                    val arguments: List<String> = when {
                        res == null || res.isJsonNull -> emptyList()
                        res.isJsonArray -> {
                            val arr: JsonArray = res.asJsonArray
                            arr.map { it.toStringSafe() }
                        }
                        res.isJsonPrimitive -> listOf(res.toStringSafe())
                        else -> listOf(res.toString())
                    }

                    Handler(Looper.getMainLooper()).post {
                        android.util.Log.d("SignalR", "onNewMessage method=$methodName args=$arguments size=${arguments.size}")
                        signalrApi.onNewMessage(methodName, arguments) { }
                    }
                }, JsonElement::class.java)
            }

            connection.connected {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.reconnected {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.reconnecting {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.RECONNECTING
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.closed {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.DISCONNECTED
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.connectionSlow {
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.connectionId = connection.connectionId
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTION_SLOW
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            connection.error { handler ->
                Handler(Looper.getMainLooper()).post {
                    val statusChangeResult = SignalrApi.StatusChangeResult()
                    statusChangeResult.status = SignalrApi.ConnectionStatus.CONNECTION_ERROR
                    statusChangeResult.errorMessage = handler.localizedMessage
                    signalrApi.onStatusChange(statusChangeResult) { }
                }
            }

            when (connectionOptions.transport) {
                SignalrApi.Transport.SERVER_SENT_EVENTS -> connection.start(
                    ServerSentEventsTransport(
                        connection.logger
                    )
                )
                SignalrApi.Transport.LONG_POLLING -> connection.start(
                    LongPollingTransport(
                        connection.logger
                    )
                )
                else -> {
                    connection.start()
                }
            }

            result?.success(connection.connectionId ?: "")
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun reconnect(result: SignalrApi.Result<String>?) {
        try {
            connection.start()
            result?.success(connection.connectionId ?: "")
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun stop(result: SignalrApi.Result<Void>?) {
        try {
            connection.stop()
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    override fun isConnected(result: SignalrApi.Result<Boolean>?) {
        try {
            if (this::connection.isInitialized) {
                when (connection.state) {
                    ConnectionState.Connected -> result?.success(true)
                    else -> result?.success(false)
                }
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

            res.onError { throwable ->
                throw throwable
            }
        } catch (ex: Exception) {
            result?.error(ex)
        }
    }

    private fun JsonElement.toStringSafe(): String {
        return try {
            if (this.isJsonNull) return ""
            if (this.isJsonPrimitive) {
                val p: JsonPrimitive = this.asJsonPrimitive
                return if (p.isString) p.asString else p.toString()
            }
            this.toString()
        } catch (_: Exception) {
            this.toString()
        }
    }
}
