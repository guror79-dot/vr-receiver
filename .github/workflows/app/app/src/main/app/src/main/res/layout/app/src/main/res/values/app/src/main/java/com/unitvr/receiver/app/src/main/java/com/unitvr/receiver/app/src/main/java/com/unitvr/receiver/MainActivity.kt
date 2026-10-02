package com.unitvr.receiver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvIp = findViewById<TextView>(R.id.tvIpAddress)
        val tvStatus = findViewById<TextView>(R.id.tvServiceStatus)
        val btnOverlay = findViewById<Button>(R.id.btnPermissionOverlay)
        val btnStart = findViewById<Button>(R.id.btnStartServiceManual)

        tvIp.text = "IP шлема в сети: ${getDeviceIpAddress()}:8888"
        tvStatus.text = "Статус службы: Запущена и ожидает команды"

        startServiceExplicitly()

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        btnStart.setOnClickListener {
            startServiceExplicitly()
            tvStatus.text = "Служба перезапущена на порту 8888"
        }
    }

    private fun startServiceExplicitly() {
        val serviceIntent = Intent(this, HeadsetControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
