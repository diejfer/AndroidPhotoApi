package com.diejfer.photoapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
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
import java.io.File
import java.nio.ByteBuffer
import android.media.MediaScannerConnection
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Button
import android.widget.ImageView
import android.text.InputType
import android.graphics.BitmapFactory

class MainActivity : ComponentActivity() {
    private lateinit var imageView: ImageView
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
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val widthInput = EditText(this).apply {
            hint = "Width (e.g., 1920)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val heightInput = EditText(this).apply {
            hint = "Height (e.g., 1080)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val focusInput = EditText(this).apply {
            hint = "Focus (e.g., 0.0)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val afCheckbox = CheckBox(this).apply {
            text = "Autofocus"
            isChecked = true
        }
        val exposureInput = EditText(this).apply {
            hint = "Exposure (ns, e.g., 50000000)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val isoInput = EditText(this).apply {
            hint = "ISO (e.g., 400)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val captureButton = Button(this).apply {
            text = "Capture"
            setOnClickListener {
                val width = widthInput.text.toString().toIntOrNull() ?: 1920
                val height = heightInput.text.toString().toIntOrNull() ?: 1080
                val focus = focusInput.text.toString().toFloatOrNull() ?: 0.0f
                val af = afCheckbox.isChecked
                val exposure = exposureInput.text.toString().toLongOrNull()
                val iso = isoInput.text.toString().toIntOrNull()
                log("Manual capture: width=$width, height=$height, focus=$focus, af=$af, exposure=$exposure, iso=$iso")
                takePhoto(width, height, focus, af, exposure, iso, saveToDisk = true)
            }
        }

        logView = TextView(this).apply { setPadding(16, 16, 16, 16) }
        val scrollView = ScrollView(this).apply { addView(logView) }

        layout.apply {
            addView(widthInput)
            addView(heightInput)
            addView(focusInput)
            addView(afCheckbox)
            addView(exposureInput)
            addView(isoInput)
            addView(captureButton)
            imageView = ImageView(context).apply {
                adjustViewBounds = true
                maxHeight = 800
            }
            addView(imageView)
            addView(scrollView)
        }

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

    private fun listCameraInfo(): String {
        val sb = StringBuilder("<ul>")
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                else -> "Unknown"
            }

            val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val resolution = pixelArray?.let { "${it.width}x${it.height}" } ?: "Unknown"

            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focalStr = focalLengths?.joinToString(", ") { "${it}mm" } ?: "Unknown"

            sb.append(
                "<li><b>ID:</b> $id &ndash; " +
                        "<b>Lens:</b> $lensFacing &ndash; " +
                        "<b>Resolution:</b> $resolution &ndash; " +
                        "<b>Focal:</b> $focalStr</li>"
            )
        }
        sb.append("</ul>")
        return sb.toString()
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
            val cameraInfoHtml = listCameraInfo()
            val html = """
                <html>
                <head><title>Photo Server</title></head>
                <body>
                    <h2>Welcome to the photo capture server</h2>
                    <p><b>Available endpoint:</b> <code>/capture</code></p>
                    <p><b>Optional parameters:</b></p>
                    <ul>
                        <li><code>cameraId</code>: camera ID to use (see below)</li>
                        <li><code>width</code>: image width (default: 1920)</li>
                        <li><code>height</code>: image height (default: 1080)</li>
                        <li><code>focus</code>: manual focus distance (0.0 = infinity)</li>
                        <li><code>af</code>: autofocus (<code>true</code> or <code>false</code>)</li>
                        <li><code>exposure</code>: shutter time in nanoseconds</li>
                        <li><code>iso</code>: ISO sensitivity</li>
                        <li><code>savePhoto</code>: <code>return</code>, <code>local</code>, or <code>returnAndLocal</code></li>
                    </ul>
                    <p><b>Camera list:</b></p>
                    $cameraInfoHtml
                    <p><b>Example:</b></p>
                    <pre><a href="http://$ip:8080/capture?cameraId=0&width=1920&height=1080&focus=0.0&af=false&exposure=50000000&iso=400&savePhoto=return">
http://$ip:8080/capture?cameraId=0&width=1920&height=1080&focus=0.0&af=false&exposure=50000000&iso=400&savePhoto=return</a></pre>
                </body>
                </html>
            """.trimIndent()
            response.send("text/html", html)
        }

        server.get("/capture") { request, response ->
            val query = request.query
            val requestedCameraId = query.getString("cameraId")
            val width = query.getString("width")?.toIntOrNull() ?: 1920
            val height = query.getString("height")?.toIntOrNull() ?: 1080
            val focus = query.getString("focus")?.toFloatOrNull() ?: 0.0f
            val af = query.getString("af")?.toBooleanStrictOrNull() ?: false
            val exposureTime = query.getString("exposure")?.toLongOrNull()
            val iso = query.getString("iso")?.toIntOrNull()
            val savePhoto = query.getString("savePhoto") ?: "return"

            val cameraId = if (requestedCameraId != null && cameraManager.cameraIdList.contains(requestedCameraId)) {
                requestedCameraId
            } else {
                cameraManager.cameraIdList.first {
                    val characteristics = cameraManager.getCameraCharacteristics(it)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }
            }

            log("Request: ${request.path}")
            log("cameraId=$cameraId, width=$width, height=$height, focus=$focus, af=$af, exposure=$exposureTime, iso=$iso, savePhoto=$savePhoto")

            takePhoto(width, height, focus, af, exposureTime, iso, saveToDisk = (savePhoto != "return"), cameraId = cameraId)

            Handler(Looper.getMainLooper()).postDelayed({
                latestImageBytes?.let { bytes ->
                    val context = applicationContext
                    var savedPath: String? = null

                    if (savePhoto == "local" || savePhoto == "returnAndLocal") {
                        val filename = "slide_${System.currentTimeMillis()}.jpg"
                        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), filename)
                        file.writeBytes(bytes)
                        savedPath = file.absolutePath
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(file.absolutePath),
                            arrayOf("image/jpeg"),
                            null
                        )
                        log("Photo saved locally: $savedPath")
                    }

                    when (savePhoto) {
                        "return" -> response.send("image/jpeg", bytes)
                        "local" -> response.code(200).send("Saved at: $savedPath")
                        "returnAndLocal" -> response.send("image/jpeg", bytes)
                        else -> response.code(400).send("Invalid savePhoto value")
                    }
                } ?: run {
                    response.send("No image captured")
                    log("Error: no image captured")
                }
            }, 1500)
        }

        server.listen(AsyncServer.getDefault(), 8080)

        log("Server started at http://$ip:8080")
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

    private fun takePhoto(
        width: Int,
        height: Int,
        focus: Float,
        af: Boolean,
        exposureTime: Long?,
        iso: Int?,
        saveToDisk: Boolean = false,
        cameraId: String? = null
    ) {
        val id = cameraId ?: cameraManager.cameraIdList.first {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

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
            log("Photo captured")
            latestImageBytes?.let {
                if (saveToDisk) {
                    val filename = "slide_${System.currentTimeMillis()}.jpg"
                    val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), filename)
                    file.writeBytes(it)
                    MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                    log("Photo saved locally: ${file.absolutePath}")
                }
                val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                runOnUiThread { imageView.setImageBitmap(bitmap) }
            }
        }, Handler(Looper.getMainLooper()))

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val surface = imageReader!!.surface

                val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (af) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    } else {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
                    }
                }

                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        // Enviar preview para que empiece a enfocar
                        session.setRepeatingRequest(previewRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                                checkAutoFocusAndCapture(session, device, surface, af, focus, exposureTime, iso)
                            }

                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                checkAutoFocusAndCapture(session, device, surface, af, focus, exposureTime, iso)
                            }
                        }, Handler(Looper.getMainLooper()))
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        log("Error: session configuration failed")
                    }
                }, Handler(Looper.getMainLooper()))
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                log("Camera disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                log("Camera error: $error")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private var hasCaptured = false

    private fun checkAutoFocusAndCapture(
        session: CameraCaptureSession,
        device: CameraDevice,
        surface: android.view.Surface,
        af: Boolean,
        focus: Float,
        exposureTime: Long?,
        iso: Int?
    ) {
        if (hasCaptured) return

        session.stopRepeating()

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

        session.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, Handler(Looper.getMainLooper()))
        hasCaptured = true
    }


    override fun onDestroy() {
        server.stop()
        cameraDevice?.close()
        imageReader?.close()
        super.onDestroy()
    }
}
