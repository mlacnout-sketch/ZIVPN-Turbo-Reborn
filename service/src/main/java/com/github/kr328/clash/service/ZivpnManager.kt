package com.github.kr328.clash.service

import android.content.Context
import android.os.Build
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ZivpnStore
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ZivpnManager(
    private val context: Context,
    private val onCoreDied: () -> Unit
) {

    private val coreProcesses = mutableListOf<Process>()
    private var monitorJob: Job? = null
    private var netMonitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val store = ZivpnStore(context)

    fun start() {
        scope.launch {
            try {
                // 1. Generate Clash Profile for ZIVPN
                generateZivpnProfile()

                // 2. Aggressive Clean Up
                stop()
                
                // Kill any lingering native processes by name pattern
                val cmdKill = arrayOf("sh", "-c", "pkill -9 libuz_core && pkill -9 libload_core")
                try { Runtime.getRuntime().exec(cmdKill).waitFor() } catch (e: Exception) {}
                
                delay(1200) // Longer delay to ensure OS releases sockets

                val nativeDir = context.applicationInfo.nativeLibraryDir
                val libUz = File(nativeDir, "libuz_core.so").absolutePath
                val libLoad = File(nativeDir, "libload_core.so").absolutePath

                if (!File(libUz).exists()) {
                    Log.e("Native Binary libuz_core.so not found at $libUz", null)
                    return@launch
                }

                Log.i("Initializing ZIVPN Turbo Cores...")

                val tunnels = mutableListOf<String>()
                // Use FlClash ports
                val ports = listOf(20080, 20081, 20082, 20083)
                val ranges = store.portRanges.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                for ((index, port) in ports.withIndex()) {
                    val currentRange = if (ranges.isNotEmpty()) ranges[index % ranges.size] else "6000-19999"
                    val configContent = """{"server":"${store.serverHost}:$currentRange","obfs":"${store.serverObfs}","auth":"${store.serverPass}","socks5":{"listen":"127.0.0.1:$port"},"insecure":true,"recvwindowconn":131072,"recvwindow":327680}"""
                    
                    val pb = ProcessBuilder(libUz, "-s", store.serverObfs, "--config", configContent)
                    pb.directory(context.filesDir)
                    pb.environment()["LD_LIBRARY_PATH"] = nativeDir
                    
                    try {
                        val process = pb.start()
                        coreProcesses.add(process)
                        startProcessLogger(process, "Core-$port")
                        tunnels.add("127.0.0.1:$port")
                    } catch (e: Exception) {
                        Log.e("Failed to launch Core-$port: ${e.message}", e)
                    }
                    delay(200)
                }

                delay(1200)

                if (tunnels.isNotEmpty()) {
                    val lbArgs = mutableListOf(libLoad, "-lport", "7777", "-tunnel")
                    lbArgs.addAll(tunnels)
                    val lbPb = ProcessBuilder(lbArgs)
                    lbPb.environment()["LD_LIBRARY_PATH"] = nativeDir
                    
                    try {
                        val lbProcess = lbPb.start()
                        coreProcesses.add(lbProcess)
                        startProcessLogger(lbProcess, "LoadBalancer")
                        Log.i("ZIVPN Turbo Engine Ready on Port 7777")
                    } catch (e: Exception) {
                        Log.e("LoadBalancer failed: ${e.message}", e)
                    }
                }

                startMonitor()
                
                if (store.autoReset) {
                    startNetworkMonitor(store.resetTimeout)
                }

            } catch (e: Exception) {
                Log.e("Fatal engine startup error: ${e.message}", e)
                withContext(Dispatchers.Main) { onCoreDied() }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        netMonitorJob?.cancel()
        killCoreProcesses()
        Log.i("Zivpn Cores stopped")
    }

    fun destroy() {
        scope.cancel()
        stop()
    }

    private fun killCoreProcesses() {
        coreProcesses.forEach { 
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.destroyForcibly()
                } else {
                    it.destroy()
                }
            } catch(e: Exception) {}
        }
        coreProcesses.clear()
        try {
            Runtime.getRuntime().exec("killall -9 libuz_core.so libload_core.so")
            Runtime.getRuntime().exec("pkill -9 -f libuz_core.so")
            Runtime.getRuntime().exec("pkill -9 -f libload_core.so")
        } catch (e: Exception) {}
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(3000)
                if (coreProcesses.isNotEmpty()) {
                    var aliveCount = 0
                    for (proc in coreProcesses) {
                        val isAlive = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                proc.isAlive
                            } else {
                                proc.exitValue()
                                false
                            }
                        } catch (e: IllegalThreadStateException) {
                            true
                        }
                        if (isAlive) aliveCount++
                    }

                    if (aliveCount < (coreProcesses.size / 2)) {
                        val uptime = System.currentTimeMillis() - startTime
                        Log.e("CRITICAL: ZIVPN Engine crashed. Uptime: ${uptime}ms", null)
                        
                        if (uptime > 10000) {
                            withContext(Dispatchers.Main) {
                                onCoreDied()
                            }
                        }
                        stop()
                        break
                    }
                }
            }
        }
    }

    private fun runShellCommand(cmd: Array<String>): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.close()
            process.outputStream.close()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        } finally {
            process?.destroy()
        }
    }

    private fun startNetworkMonitor(timeoutSec: Int) {
        netMonitorJob?.cancel()
        netMonitorJob = scope.launch {
            var failCount = 0
            val maxFail = (timeoutSec / 5).coerceAtLeast(1)
            
            val hasRoot = isRootAvailable()
            if (hasRoot) {
                runShellCommand(arrayOf("su", "-c", "settings put global airplane_mode_radios cell,bluetooth,nfc,wifi,wimax"))
            }

            Log.i("[NetworkMonitor] STARTED (Timeout: ${timeoutSec}s, MaxFail: $maxFail, Mode: ${if (hasRoot) "ROOT" else "NON-ROOT"})")

            while (isActive) {
                delay(5000)
                
                val isConnected = try {
                    val url = URL("https://www.gstatic.com/generate_204")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.useCaches = false
                    conn.connect()
                    val responseCode = conn.responseCode
                    conn.disconnect()
                    responseCode == 204
                } catch (e: Exception) {
                    false
                }

                if (isConnected) {
                    if (failCount > 0) Log.i("[NetworkMonitor] CHECK: Internet Recovered")
                    failCount = 0
                } else {
                    failCount++
                    Log.w("[NetworkMonitor] WARNING: Connection Check Failed ($failCount/$maxFail)", null)
                    
                    if (failCount >= maxFail) {
                        failCount = 0 
                        
                        if (hasRoot) {
                            val callCheckOut = runShellCommand(arrayOf("su", "-c", "dumpsys telephony.registry | grep mCallState"))
                            if (callCheckOut.contains("mCallState=2")) {
                                Log.i("[NetworkMonitor] SKIP: User is in a call, reset aborted")
                                continue
                            }

                            Log.i("[NetworkMonitor] ACTION: Connection Dead. Toggling Airplane Mode...")
                            
                            try {
                                runShellCommand(arrayOf("su", "-c", "cmd connectivity airplane-mode enable"))
                                delay(2000)
                                runShellCommand(arrayOf("su", "-c", "cmd connectivity airplane-mode disable"))
                                
                                Log.i("[NetworkMonitor] WAITING: Waiting for data signal...")
                                var signalRecovered = false
                                for (i in 1..30) { 
                                    delay(1000)
                                    val out = runShellCommand(arrayOf("su", "-c", "dumpsys telephony.registry"))
                                    if (out.contains("mDataConnectionState=2")) {
                                        signalRecovered = true
                                        break
                                    }
                                }
                                
                                Log.i(if (signalRecovered) "[NetworkMonitor] SUCCESS: Signal Recovered" else "[NetworkMonitor] TIMEOUT: Signal recovery took too long")
                                delay(2000)
                                
                            } catch (e: Exception) {
                                Log.e("[NetworkMonitor] ERR: Root command failed. Switching to soft restart.", e)
                                scope.launch {
                                    stop()
                                    delay(2000)
                                    start()
                                }
                                break
                            }
                        } else {
                            Log.i("[NetworkMonitor] ACTION: Connection Dead. Executing Soft Restart (Non-Root)...")
                            scope.launch {
                                stop()
                                delay(2000)
                                start()
                                Log.i("[NetworkMonitor] INFO: Soft Restart Completed.")
                            }
                            break 
                        }
                    }
                }
            }
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun generateZivpnProfile() {
        try {
            val profileDir = File(context.filesDir, "profiles/00000000-0000-0000-0000-000000000001")
            if (!profileDir.exists()) profileDir.mkdirs()
            
            val yamlContent = """
                mixed-port: 7890
                allow-lan: true
                mode: rule
                log-level: info
                ipv6: false
                external-controller: 127.0.0.1:9090
                proxies:
                  - name: "ZIVPN-TURBO"
                    type: socks5
                    server: 127.0.0.1
                    port: 7777
                proxy-groups:
                  - name: PROXY
                    type: select
                    proxies:
                      - ZIVPN-TURBO
                rules:
                  - MATCH,PROXY
            """.trimIndent()
            
            File(profileDir, "config.yaml").writeText(yamlContent)
            Log.i("ZIVPN Clash Profile Generated at ${profileDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("Failed to generate ZIVPN profile", e)
        }
    }

    private fun startProcessLogger(process: Process, tag: String) {
        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { Log.i("[$tag] $it") }
                }
            } catch (e: Exception) {}
        }.start()
        
        Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { Log.e("[$tag] $it", null) }
                }
            } catch (e: Exception) {}
        }.start()
    }
}
