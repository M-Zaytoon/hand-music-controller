package com.example.handmusiccontroler

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.io.IOException
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera screen: tracks the hand with MediaPipe, turns the gesture into a text command
 * and sends it to the ESP32 over Bluetooth Classic (SPP).
 *
 * Command format sent to the ESP32:  <instrument><fingers>:<volume> followed by a newline, e.g. "P2:80"
 *   instrument: P = piano, D = flute, G = guitar
 *   fingers:    1-3 raised fingers (index, middle, ring) select the note
 *   volume:     10-100, set by the hand height in the camera frame
 *   "STOP:0" is sent when no hand (or no valid gesture) is detected.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "HandMusicSystem"
        // Bluetooth MAC address of MY ESP32. Replace it with the address of your own board.
        const val DEVICE_MAC = "04:83:08:73:5F:8E"
        val BT_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val REQ_CODE = 100
    }

    private lateinit var handLandmarker: HandLandmarker
    private lateinit var cameraExecutor: ExecutorService
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    private var currentInstrument = "P"
    private var lastGestureSent = "STOP"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentInstrument = intent.getStringExtra("INSTRUMENT") ?: "P"
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            initSystem()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                REQ_CODE
            )
        }
    }

    private fun initSystem() {
        setupHandTracker()
        setupCamera()
        setupBluetooth()
    }

    // ================= Hand Tracking =================

    private fun setupHandTracker() {
        val baseOptions =
            com.google.mediapipe.tasks.core.BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

        val options =
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    // Optional: uncomment to draw the hand skeleton on screen (uses OverlayView)
                    // runOnUiThread { findViewById<OverlayView>(R.id.overlay)?.setResults(result) }
                    processHandGestures(result)
                }
                .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    /** Counts raised fingers and hand height, then sends the matching command to the ESP32. */
    private fun processHandGestures(result: HandLandmarkerResult) {

        val landmarksResult = result.landmarks()

        if (landmarksResult.isNotEmpty()) {

            // Use the first detected hand only
            val handPoints: List<NormalizedLandmark> = landmarksResult[0]

            // MediaPipe landmark indices: 0 = wrist; tip/PIP pairs: index 8/6, middle 12/10, ring 16/14
            val wrist = handPoints[0]

            val iTip = handPoints[8]
            val iPip = handPoints[6]

            val mTip = handPoints[12]
            val mPip = handPoints[10]

            val rTip = handPoints[16]
            val rPip = handPoints[14]

            val indexUp = isRaised(iTip, iPip, wrist)
            val middleUp = isRaised(mTip, mPip, wrist)
            val ringUp = isRaised(rTip, rPip, wrist)

            val fingerCount = when {
                indexUp && !middleUp && !ringUp -> 1
                indexUp && middleUp && !ringUp -> 2
                indexUp && middleUp && ringUp -> 3
                else -> 0
            }

            val vol = (((0.8f - wrist.y()) / 0.6f) * 100)
                .toInt()
                .coerceIn(10, 100)

            if (fingerCount > 0) {
                val cmd = "$currentInstrument$fingerCount:$vol\n"

                if (cmd != lastGestureSent || currentInstrument == "P") {
                    sendData(cmd)
                    lastGestureSent = cmd
                }
            } else {
                handleStop()
            }

        } else {
            handleStop()
        }
    }

    /** A finger counts as raised if its tip is farther from the wrist than its middle (PIP) joint. */
    private fun isRaised(
        tip: NormalizedLandmark,
        pip: NormalizedLandmark,
        wrist: NormalizedLandmark
    ): Boolean {
        val dTip = Math.hypot(
            (tip.x() - wrist.x()).toDouble(),
            (tip.y() - wrist.y()).toDouble()
        )
        val dPip = Math.hypot(
            (pip.x() - wrist.x()).toDouble(),
            (pip.y() - wrist.y()).toDouble()
        )
        return dTip > dPip
    }

    private fun handleStop() {
        if (lastGestureSent != "STOP") {
            sendData("STOP:0\n")
            lastGestureSent = "STOP"
        }
    }

    // ================= Bluetooth =================

    private fun sendData(data: String) {
        try {
            outStream?.write(data.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth Send Error")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        Thread {
            try {
                val device: BluetoothDevice =
                    adapter.getRemoteDevice(DEVICE_MAC)

                btSocket =
                    device.createRfcommSocketToServiceRecord(BT_UUID)

                btSocket?.connect()
                outStream = btSocket?.outputStream

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "System Connected! 🚀",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: IOException) {
                Log.e(TAG, "BT Connection Fail")
            }
        }.start()
    }

    // ================= Camera =================

    private fun setupCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            val provider = providerFuture.get()
            val viewFinder = findViewById<PreviewView>(R.id.viewFinder)

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                )
                .build().apply {

                    setAnalyzer(cameraExecutor) { proxy ->

                        val bitmap = proxy.toBitmap()
                        val mpImage =
                            BitmapImageBuilder(bitmap).build()

                        val opts =
                            com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
                                .builder()
                                .setRotationDegrees(proxy.imageInfo.rotationDegrees)
                                .build()

                        handLandmarker.detectAsync(
                            mpImage,
                            opts,
                            System.currentTimeMillis()
                        )

                        proxy.close()
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    // ================= Permissions =================

    private fun allPermissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker.close()
        try {
            btSocket?.close()
        } catch (e: Exception) {}
    }
}