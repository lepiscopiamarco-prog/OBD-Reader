package it.studiolambda.obdmonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

private val ObdGreen = Color(0xFF20D18B)
private val Dark = Color(0xFF071A14)
private val Panel = Color(0xFF103126)

data class SensorValue(val name: String, val value: String, val unit: String)
data class ObdPid(val command: String, val name: String, val unit: String, val decode: (List<Int>) -> Double?)

private val pids = listOf(
    ObdPid("0104", "Carico motore", "%") { b -> b.getOrNull(0)?.times(100.0)?.div(255) },
    ObdPid("0105", "Temperatura refrigerante", "°C") { b -> b.getOrNull(0)?.minus(40.0) },
    ObdPid("0106", "Correzione carburante breve B1", "%") { b -> b.getOrNull(0)?.times(100.0)?.div(128)?.minus(100) },
    ObdPid("0107", "Correzione carburante lunga B1", "%") { b -> b.getOrNull(0)?.times(100.0)?.div(128)?.minus(100) },
    ObdPid("010B", "Pressione collettore", "kPa") { b -> b.getOrNull(0)?.toDouble() },
    ObdPid("010C", "Regime motore", "rpm") { b -> if (b.size >= 2) (b[0] * 256 + b[1]) / 4.0 else null },
    ObdPid("010D", "Velocità", "km/h") { b -> b.getOrNull(0)?.toDouble() },
    ObdPid("010E", "Anticipo accensione", "°") { b -> b.getOrNull(0)?.div(2.0)?.minus(64) },
    ObdPid("010F", "Temperatura aria aspirata", "°C") { b -> b.getOrNull(0)?.minus(40.0) },
    ObdPid("0110", "Portata aria MAF", "g/s") { b -> if (b.size >= 2) (b[0] * 256 + b[1]) / 100.0 else null },
    ObdPid("0111", "Posizione acceleratore", "%") { b -> b.getOrNull(0)?.times(100.0)?.div(255) },
    ObdPid("011F", "Tempo da avvio", "s") { b -> if (b.size >= 2) (b[0] * 256 + b[1]).toDouble() else null },
    ObdPid("012F", "Livello carburante", "%") { b -> b.getOrNull(0)?.times(100.0)?.div(255) },
    ObdPid("0142", "Tensione centralina", "V") { b -> if (b.size >= 2) (b[0] * 256 + b[1]) / 1000.0 else null },
    ObdPid("0146", "Temperatura ambiente", "°C") { b -> b.getOrNull(0)?.minus(40.0) },
    ObdPid("015C", "Temperatura olio", "°C") { b -> b.getOrNull(0)?.minus(40.0) }
)

class ObdViewModel : ViewModel() {
    var status by mutableStateOf("Non connesso")
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    var values by mutableStateOf<List<SensorValue>>(emptyList())
    var protocol by mutableStateOf("-")
    var adapterInfo by mutableStateOf("-")
    private var socket: BluetoothSocket? = null
    private var pollJob: Job? = null

    fun loadPaired(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            devices = adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
        }
    }

    fun connect(device: BluetoothDevice) {
        pollJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                status = "Connessione a ${device.name ?: device.address}…"
                val s = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                s.connect(); socket = s
                val io = ElmIo(s)
                adapterInfo = io.command("ATI")
                io.command("ATZ", 1800); io.command("ATE0"); io.command("ATL0"); io.command("ATS0"); io.command("ATH0"); io.command("ATSP0", 2500)
                protocol = io.command("ATDP")
                status = "Connesso"
                pollJob = launch {
                    while (isActive) {
                        val fresh = mutableListOf<SensorValue>()
                        for (pid in pids) {
                            val raw = io.command(pid.command)
                            parsePid(raw, pid)?.let { fresh += SensorValue(pid.name, format(it), pid.unit) }
                        }
                        values = fresh
                        delay(350)
                    }
                }
            } catch (e: Exception) {
                status = "Errore: ${e.message ?: e.javaClass.simpleName}"
                runCatching { socket?.close() }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel(); pollJob = null
        runCatching { socket?.close() }; socket = null
        status = "Non connesso"; values = emptyList(); protocol = "-"; adapterInfo = "-"
    }

    private fun parsePid(raw: String, pid: ObdPid): Double? {
        val clean = raw.uppercase().replace("SEARCHING...", "").replace(" ", "").replace("\r", "").replace("\n", "")
        if (clean.contains("NODATA") || clean.contains("ERROR") || clean.contains("UNABLETOCONNECT")) return null
        val marker = "41" + pid.command.takeLast(2)
        val index = clean.indexOf(marker)
        if (index < 0) return null
        val payload = clean.substring(index + marker.length).takeWhile { it in "0123456789ABCDEF" }
        val bytes = payload.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        return pid.decode(bytes)
    }

    private fun format(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    override fun onCleared() { disconnect(); super.onCleared() }
}

private class ElmIo(private val socket: BluetoothSocket) {
    private val input = BufferedReader(InputStreamReader(socket.inputStream))
    private val output = socket.outputStream
    suspend fun command(cmd: String, timeoutMs: Long = 1200): String = withContext(Dispatchers.IO) {
        output.write((cmd + "\r").toByteArray()); output.flush()
        val out = StringBuilder(); val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            while (input.ready()) {
                val c = input.read().toChar()
                if (c == '>') return@withContext out.toString().trim()
                out.append(c)
            }
            delay(15)
        }
        out.toString().trim()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = ObdGreen, background = Dark, surface = Panel)) { ObdApp() } }
    }
}

@Composable
fun ObdApp(vm: ObdViewModel = viewModel()) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.loadPaired(context) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else vm.loadPaired(context)
    }
    Scaffold(containerColor = Dark, topBar = { Surface(color = Panel) { Text("OBD Monitor", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White) } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp)) {
                    Text(vm.status, color = if (vm.status == "Connesso") ObdGreen else Color.White, fontWeight = FontWeight.Bold)
                    Text("Adattatore: ${vm.adapterInfo}", color = Color.LightGray)
                    Text("Protocollo: ${vm.protocol}", color = Color.LightGray)
                    if (vm.status != "Non connesso") Button(onClick = vm::disconnect, modifier = Modifier.padding(top = 8.dp)) { Text("Disconnetti") }
                } }
            }
            if (vm.status == "Non connesso") {
                item { Text("Dispositivi Bluetooth già associati", color = Color.White, fontWeight = FontWeight.Bold) }
                items(vm.devices) { d ->
                    Card(onClick = { vm.connect(d) }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(d.name ?: "Dispositivo OBD", color = Color.White); Text(d.address, color = Color.Gray) }
                            Text("Connetti", color = ObdGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item { Text("Prima associa il ricevitore ELM327 dalle impostazioni Bluetooth Android. PIN comuni: 1234 o 0000.", color = Color.LightGray) }
            } else {
                item { Text("Dati disponibili sulla tua centralina", color = Color.White, fontWeight = FontWeight.Bold) }
                items(vm.values) { s ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, modifier = Modifier.weight(1f), color = Color.White)
                            Text("${s.value} ${s.unit}", color = ObdGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (vm.values.isEmpty()) item { CircularProgressIndicator(color = ObdGreen) }
            }
        }
    }
}
