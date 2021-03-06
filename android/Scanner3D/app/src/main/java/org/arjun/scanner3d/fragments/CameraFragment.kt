package org.arjun.scanner3d.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation

import org.arjun.scanner3d.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import org.arjun.scanner3d.KEY_EVENT_ACTION
import org.arjun.scanner3d.KEY_EVENT_EXTRA
import org.arjun.scanner3d.MainActivity

import org.arjun.scanner3d.utils.simulateClick
import java.io.File
import java.text.SimpleDateFormat
import androidx.camera.core.ImageCapture.Metadata
import org.arjun.scanner3d.utils.ANIMATION_FAST_MILLIS
import org.arjun.scanner3d.utils.ANIMATION_SLOW_MILLIS
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import java.lang.Math.*
import java.util.*
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit


/**
 * Main fragment for this app. Implements all camera operations including:
 *
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 *
 */
val BACKEND_SERVER_URL = "http://192.168.43.77:5000"
class CameraFragment : Fragment() {

    private lateinit var mainExecutor : Executor


    private lateinit var uploadImageExecutor : Executor
    private lateinit var broadcastManager: LocalBroadcastManager

    private lateinit var container : ConstraintLayout

    private lateinit var displayManager : DisplayManager

    private var displayId: Int = -1

    private var imageCapture: ImageCapture? = null
    private var imageUploader: ImageAnalysis? = null

    private lateinit var viewFinder : PreviewView

    private lateinit var outputDirectory: File

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private var preview: Preview? = null

    private var camera: Camera? = null




    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container.findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainExecutor = ContextCompat.getMainExecutor(requireContext())
        uploadImageExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    // We need a display listener for orientation changes that do not trigger a configuration
    // change for example if we choose to override config change in manifest or for 180-defree
    // orientation changes.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let {
            view -> if (displayId == this@CameraFragment.displayId) {
            Log.d(TAG, "Rotation changed: ${view.display.rotation}")
            imageCapture?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit

    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera, container, false)

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)
        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive vents from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        // Every time the orientation of device changes, recompute layut
        displayManager = viewFinder.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Bind use cases
            bindCameraUseCases()

            // In the background load latest photo taken (if any) for gallery thumbnail
            /*lifecycleScope.launch(Dispatchers.IO) {
                outputDirectory.listFiles { file ->
                    EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
                }?.max()?.let {
                    setGalleryThumbnail(it)
                }
            }*/
        }

    }

    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and readding the view finder from the view hierarchyl this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE:  The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     *
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCameraUi()
    }

    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ration for dimensions provided in @params by counting
     *  absolute of preview ration to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ration
     */
    private fun aspectRatio(width: Int, height: Int) : Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATION_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    // Declare and bind preview, capture use cases */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also{ viewFinder.display.getRealMetrics(it)}
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")
        val rotation = viewFinder.display.rotation
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {
            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // Preview
            preview = Preview.Builder()
                //We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                //Set initial target rotation
                .setTargetRotation(rotation)
                .build()
            // Default PreviewSurfaceProvider
            preview?.previewSurfaceProvider = viewFinder.previewSurfaceProvider
            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                //.setTargetResolution(Size(metrics.widthPixels, metrics.heightPixels))
                // We request aspect ration but no resolution to match preview config, but letting
                // CameraX optimize for whatever resolution best fits requested capture mode
                .setTargetAspectRatio(screenAspectRatio)
                    // Set the initial target rotation, we will have to call this again if rotation changes
                    // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()


            // Must unbind the use-cases before rebinding them.
            cameraProvider.unbindAll()

            try {
                // A variable number of use cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview, imageCapture)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, mainExecutor)
    }

    // Define callback that will be triggered after a photo has been taken and saved to disk
    private val imageSavedListener = object : ImageCapture.OnImageSavedCallback {
        override fun onError(imageCaptureError : Int, message: String, cause: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message", cause)
        }

        override fun onImageSaved(photoFile: File) {
            // File has been saved to android disk. Now begin upload to server
            Log.d(TAG, "Photo capture suceeded: ${photoFile.absolutePath}")
            val imageData = photoFile.readBytes()
            //create file upload request to POST to server
            val request = object : VolleyFileUploadRequest(
                Request.Method.POST,
                BACKEND_SERVER_URL + "/upload",
                Response.Listener {
                    println("response is: $it")
                },
                Response.ErrorListener {
                    println("error is : $it")
                }
            ) {
                //this method is called to get the data of the file that needs to upload
                override fun getByteData(): MutableMap<String, FileDataPart> {
                    var params = HashMap<String, FileDataPart>()
                    params["file"] = FileDataPart("img-" + photoFile.name, imageData, "jpg")
                    return params
                }
            }
            // we queue the request to send to server
            Volley.newRequestQueue(context).add(request)

        }
    }

    // Method used to re-draw the camera UI controls, called every time configuration changes
    private fun updateCameraUi() {

        // Remove previous UI if any
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }
        // Inflate a new view containing all UI for controlling the camera
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        // Listener for button used to capture photo
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->
                // Create output file to hold the image
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
                // Setup image capture metadata
                val metadata = Metadata().apply {
                    // Mirror image when using front camera
                    isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
                }
                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(photoFile, metadata, mainExecutor, imageSavedListener)

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Display flash animation
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                            { container.foreground = null }, ANIMATION_FAST_MILLIS
                        )
                    }, ANIMATION_SLOW_MILLIS)

                }
            }
        }
        // Listener for button used to switch cameras
        controls.findViewById<ImageButton>(R.id.camera_switch_button).setOnClickListener {
            lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            // Bind use cases
            bindCameraUseCases()
        }
        // Listener for starting point cloud generation on server
        controls.findViewById<ImageButton>(R.id.photo_view_button).setOnClickListener{

            val url = BACKEND_SERVER_URL + "/scan"


            // Post parameters
            // Form fields and values
            val jsonObject = JSONObject()
            jsonObject.put("foo", "bar")

            // Volley post request with parameters
            val request = JsonObjectRequest(Request.Method.POST,url,jsonObject,
                Response.Listener { response ->
                    // Process the json
                    println("Response: $response")

                }, Response.ErrorListener{
                    // Error in request
                    println("Volley error: $it")
                })
            request.retryPolicy = DefaultRetryPolicy(1800000,0, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
            Volley.newRequestQueue(context).add(request)
        }
    }


    companion object {

        private const val TAG = "Scanner3D"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATION_16_9_VALUE = 16.0 / 9.0

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)
    }

}