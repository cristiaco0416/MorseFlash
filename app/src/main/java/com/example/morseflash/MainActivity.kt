package com.example.morseflash

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        const val UNIT = 300L // ms por unidad Morse (debe ser igual en ambos celulares)
        val MORSE = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
            'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
            'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
            'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
            'Y' to "-.--", 'Z' to "--..",
            '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
            '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
        )
        val REVERSE = MORSE.entries.associate { it.value to it.key }
    }

    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var rxSwitch: Switch
    private lateinit var output: TextView
    private lateinit var status: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val analyzerExec = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var sending = false

    // --- estado del receptor ---
    private var lo = 0f
    private var hi = 0f
    private var first = true
    private var isOn = false
    private var lastChange = 0L
    private val symbols = StringBuilder()
    private var spaceAdded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        input = EditText(this).apply { hint = "Mensaje a enviar (A-Z, 0-9)" }
        sendBtn = Button(this).apply { text = "ENVIAR POR FLASH" }
        rxSwitch = Switch(this).apply { text = "Recibir (usa la cámara trasera)  " }
        status = TextView(this).apply { text = "Listo"; gravity = Gravity.START }
        output = TextView(this).apply { textSize = 24f; setPadding(0, 32, 0, 0) }
        listOf(input, sendBtn, rxSwitch, status, output).forEach { root.addView(it) }
        setContentView(root)

        sendBtn.setOnClickListener { sendMessage(input.text.toString()) }
        rxSwitch.setOnCheckedChangeListener { _, on -> if (on) startReceiving() else stopReceiving() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)

        ui.post(object : Runnable {   // temporizador para cerrar letras y palabras
            override fun run() { checkTimeout(SystemClock.elapsedRealtime()); ui.postDelayed(this, 50) }
        })
    }

    // ======================= TRANSMISOR =======================
    private fun torchId(): String? {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun sendMessage(text: String) {
        if (sending) return
        if (rxSwitch.isChecked) { toast("Desactiva la recepción para enviar"); return }
        val id = torchId() ?: run { toast("Este celular no tiene flash"); return }
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        sending = true
        sendBtn.isEnabled = false
        status.text = "Enviando..."
        Thread {
            fun flash(on: Boolean, units: Int) {
                try { cm.setTorchMode(id, on) } catch (_: Exception) {}
                Thread.sleep(UNIT * units)
            }
            try {
                flash(true, 5); flash(false, 3)               // pulso de sincronización (calibra el receptor)
                val words = text.uppercase().trim().split(Regex("\\s+"))
                for ((wi, word) in words.withIndex()) {
                    for ((ci, ch) in word.withIndex()) {
                        val code = MORSE[ch] ?: continue
                        for ((si, s) in code.withIndex()) {
                            flash(true, if (s == '.') 1 else 3)
                            if (si < code.length - 1) flash(false, 1)
                        }
                        if (ci < word.length - 1) flash(false, 3)
                    }
                    if (wi < words.size - 1) flash(false, 7)
                }
            } finally {
                try { cm.setTorchMode(id, false) } catch (_: Exception) {}
                sending = false
                runOnUiThread { sendBtn.isEnabled = true; status.text = "Mensaje enviado" }
            }
        }.start()
    }

    // ======================== RECEPTOR ========================
    private fun startReceiving() {
        if (sending) { rxSwitch.isChecked = false; return }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get().also { provider = it }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExec) { img ->
                // brillo medio de la zona central del plano Y
                val plane = img.planes[0]
                val buf = plane.buffer
                val w = img.width; val h = img.height; val rs = plane.rowStride
                var sum = 0L; var n = 0
                for (y in h / 3 until 2 * h / 3 step 4) {
                    for (x in w / 3 until 2 * w / 3 step 4) {
                        sum += buf.get(y * rs + x).toInt() and 0xFF; n++
                    }
                }
                val b = if (n > 0) sum.toFloat() / n else 0f
                val t = SystemClock.elapsedRealtime()
                img.close()
                ui.post { onSample(b, t) }
            }
            p.unbindAll()
            p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            first = true; isOn = false; symbols.clear(); output.text = ""
            lastChange = SystemClock.elapsedRealtime()
            status.text = "Escuchando... apunta el flash del otro celular a tu cámara trasera"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopReceiving() {
        provider?.unbindAll()
        status.text = "Recepción detenida"
    }

    private fun onSample(b: Float, now: Long) {
        if (first) { lo = b; hi = b; first = false; lastChange = now; return }
        lo = if (b < lo) b else lo + (b - lo) * 0.02f
        hi = if (b > hi) b else hi - (hi - b) * 0.02f
        val on = (hi - lo) > 25f && b > (lo + hi) / 2f
        if (on == isOn) return
        val dur = now - lastChange
        if (isOn) {                       // terminó un destello: punto o raya
            if (dur >= UNIT / 3) symbols.append(if (dur < UNIT * 2) '.' else '-')
        } else {
            spaceAdded = false
        }
        isOn = on
        lastChange = now
    }

    private fun checkTimeout(now: Long) {
        if (isOn || first) return
        val gap = now - lastChange
        if (gap >= UNIT * 2 && symbols.isNotEmpty()) {
            val code = symbols.toString(); symbols.clear()
            // la secuencia de sincronización (raya larga) se ignora automáticamente
            REVERSE[code]?.let { output.append(it.toString()) }
        }
        if (gap >= UNIT * 5 && !spaceAdded && output.text.isNotEmpty()) {
            output.append(" "); spaceAdded = true
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
