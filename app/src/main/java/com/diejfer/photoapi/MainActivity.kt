package com.diejfer.photoapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.http.server.AsyncHttpServer
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {
    private lateinit var imageView: android.widget.ImageView
    private lateinit var server: AsyncHttpServer
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null
    private var latestImageBytes: ByteArray? = null
    private lateinit var logView: TextView

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startHttpServer()
            } else {
                Toast.makeText(this, "Permisos denegados", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Limpieza: removemos duplicado innecesario de ScrollView

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val widthInput = android.widget.EditText(this).apply {
            hint = "Ancho (ej: 1920)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val heightInput = android.widget.EditText(this).apply {
            hint = "Alto (ej: 1080)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val focusInput = android.widget.EditText(this).apply {
            hint = "Foco (ej: 0.0)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val afCheckbox = android.widget.CheckBox(this).apply {
            text = "Autofocus"
            isChecked = true
        }
        val exposureInput = android.widget.EditText(this).apply {
            hint = "Exposición (ns, ej: 50000000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val isoInput = android.widget.EditText(this).apply {
            hint = "ISO (ej: 400)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val captureButton = android.widget.Button(this).apply {
            text = "Capturar"
            setOnClickListener {
                val width = widthInput.text.toString().toIntOrNull() ?: 1920
                val height = heightInput.text.toString().toIntOrNull() ?: 1080
                val focus = focusInput.text.toString().toFloatOrNull() ?: 0.0f
                val af = afCheckbox.isChecked
                val exposure = exposureInput.text.toString().toLongOrNull()
                val iso = isoInput.text.toString().toIntOrNull()
                log("Captura manual: width=$width, height=$height, focus=$focus, af=$af, exposure=$exposure, iso=$iso")
                takePhoto(width, height, focus, af, exposure, iso)
            }
        }

        logView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
        }
        val scrollView = ScrollView(this).apply {
            addView(logView)
        }

        layout.addView(widthInput)
        layout.addView(heightInput)
        layout.addView(focusInput)
        layout.addView(afCheckbox)
        layout.addView(exposureInput)
        layout.addView(isoInput)
        layout.addView(captureButton)
        imageView = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = 800
        }
        layout.addView(imageView)
        layout.addView(scrollView)

        setContentView(layout)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else {
            startHttpServer()
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
            (logView.parent as? ScrollView)?.post {
                (logView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun startHttpServer() {
        server = AsyncHttpServer()

        server.get("/") { _, response ->
            response.headers.add("Location", "/index.html")
            response.code(302)
            response.end()
        }
        val ip = getLocalIpAddress(this) ?: "localhost"

        server.get("/index.html") { _, response ->
            val html = """
                <html>
                <head><title>Servidor de Fotos</title></head>
                <body>
                    <h2>Bienvenido al servidor de captura de fotos</h2>
                    <p><b>Endpoint disponible:</b> <code>/capture</code></p>
                    <p><b>Parámetros opcionales:</b></p>
                    <ul>
                        <li><code>width</code>: ancho (default 1920)</li>
                        <li><code>height</code>: alto (default 1080)</li>
                        <li><code>focus</code>: distancia de enfoque (0.0 = infinito)</li>
                        <li><code>af</code>: autofocus (true/false)</li>
                        <li><code>exposure</code>: tiempo en nanosegundos</li>
                        <li><code>iso</code>: sensibilidad ISO</li>
                    </ul>
                    <p><b>Ejemplo:</b></p>
                    <pre><a href="http://$ip:8080/capture?width=1920&height=1080&focus=0.0&af=false&exposure=50000000&iso=400">
http://$ip:8080/capture?width=1920&height=1080&focus=0.0&af=false&exposure=50000000&iso=400</a></pre>
                </body>
                </html>
            """.trimIndent()
            response.send("text/html", html)
        }

        server.get("/capture") { request, response ->
            val query = request.query
            val width = query.getString("width")?.toIntOrNull() ?: 1920
            val height = query.getString("height")?.toIntOrNull() ?: 1080
            val focus = query.getString("focus")?.toFloatOrNull() ?: 0.0f
            val af = query.getString("af")?.toBooleanStrictOrNull() ?: false
            val exposureTime = query.getString("exposure")?.toLongOrNull()
            val iso = query.getString("iso")?.toIntOrNull()

            log("Petición: ${request.path}")
            log("width=$width, height=$height, focus=$focus, af=$af, exposure=$exposureTime, iso=$iso")

            takePhoto(width, height, focus, af, exposureTime, iso)

            Handler(Looper.getMainLooper()).postDelayed({
                latestImageBytes?.let {
                    response.send("image/jpeg", it)
                    log("Foto enviada (${it.size} bytes)")
                } ?: run {
                    response.send("No image captured")
                    log("Error: no se capturó imagen")
                }
            }, 1500)
        }

        server.listen(AsyncServer.getDefault(), 8080)

        log("Servidor iniciado en http://$ip:8080")

    }

    fun getLocalIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: return null
        return java.net.InetAddress.getByAddress(
            byteArrayOf(
                (ipInt and 0xff).toByte(),
                (ipInt shr 8 and 0xff).toByte(),
                (ipInt shr 16 and 0xff).toByte(),
                (ipInt shr 24 and 0xff).toByte()
            )
        ).hostAddress
    }

    private fun takePhoto(width: Int, height: Int, focus: Float, af: Boolean, exposureTime: Long?, iso: Int?) {
        val cameraId = cameraManager.cameraIdList.first {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            latestImageBytes = bytes
            image.close()
            cameraDevice?.close()
            captureSession?.close()
            log("Foto capturada")
            latestImageBytes?.let {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                runOnUiThread { imageView.setImageBitmap(bitmap) }
            }
        }, Handler(Looper.getMainLooper()))

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val surface = imageReader!!.surface

                val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (af) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                    } else {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    }

                    if (exposureTime != null && iso != null) {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                }

                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                super.onCaptureCompleted(session, request, result)
                                log("Captura completada con AF")
                            }
                        }, null)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        log("Error: fallo la configuración de la sesión")
                    }
                }, null)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                log("Cámara desconectada")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                log("Error en la cámara: $error")
            }
        }, Handler(Looper.getMainLooper()))
    }
    override fun onDestroy() {
        server.stop()
        cameraDevice?.close()
        imageReader?.close()
        super.onDestroy()
    }
}

