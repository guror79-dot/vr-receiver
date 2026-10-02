package com.unitvr.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class HeadsetControlService : Service() {

    private var server: WebSocketServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val TAG = "UnitVRService"

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        acquireWakeLock()
        startWebSocketServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeadsetControlService активен и слушает порт 8888")
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "unitvr_receiver_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "UnitVR Background Daemon",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("UnitVR Headset Daemon")
            .setContentText("Порт 8888 открыт для команд с планшета")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UnitVR::HeadsetControlWakeLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus: Intent? = registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    private fun startWebSocketServer() {
        try {
            val address = InetSocketAddress(8888)
            server = object : WebSocketServer(address) {
                override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                    Log.d(TAG, "Планшет подключен: ${conn?.remoteSocketAddress}")
                    val (pct, charging) = getBatteryInfo()
                    val telemetry = JSONObject().apply {
                        put("type", "STATUS")
                        put("battery", pct)
                        put("isCharging", charging)
                    }
                    conn?.send(telemetry.toString())
                }

                override fun onMessage(conn: WebSocket?, message: String?) {
                    Log.d(TAG, "Входящая команда: $message")
                    try {
                        val json = JSONObject(message ?: "{}")
                        when (json.optString("action")) {
                            "START_GAME", "SWITCH_GAME" -> {
                                val pkgName = json.getString("packageName")
                                launchApplication(pkgName)
                                conn?.send(JSONObject().apply {
                                    put("status", "SUCCESS")
                                    put("message", "App launched: $pkgName")
                                }.toString())
                            }
                            "FORCE_STOP_GAME" -> {
                                returnToLauncher()
                                conn?.send(JSONObject().apply {
                                    put("status", "SUCCESS")
                                    put("message", "Returned to Home")
                                }.toString())
                            }
                            "PING" -> {
                                val (pct, charging) = getBatteryInfo()
                                conn?.send(JSONObject().apply {
                                    put("status", "PONG")
                                    put("battery", pct)
                                    put("isCharging", charging)
                                }.toString())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка обработки сообщения: ${e.message}")
                    }
                }

                override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "Соединение закрыто: $reason")
                }

                override fun onError(conn: WebSocket?, ex: Exception?) {
                    Log.e(TAG, "Ошибка сокета: ${ex?.message}")
                }

                override fun onStart() {
                    Log.d(TAG, "Сокет-сервер слушает порт 8888")
                }
            }
            server?.isReuseAddr = true
            server?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось инициализировать сервер: ${e.message}")
        }
    }

    private fun launchApplication(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launchIntent)
            } else {
                Log.w(TAG, "Пакет $packageName не найден на устройстве!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска пакета $packageName: ${e.message}")
        }
    }

    private fun returnToLauncher() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
