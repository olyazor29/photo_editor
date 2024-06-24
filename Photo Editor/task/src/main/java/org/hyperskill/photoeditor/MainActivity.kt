package org.hyperskill.photoeditor

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore.Images
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.graphics.blue
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.pow


class MainActivity : AppCompatActivity() {

    private val activityResultLauncher = registerForActivityResult(StartActivityForResult()) {result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val photoUri = result.data?.data ?: return@registerForActivityResult
            currentImage.setImageURI(photoUri)
            originalBitmap = currentImage.drawable.toBitmap()
        }
    }

    private lateinit var currentImage: ImageView
    private lateinit var sliderBrightness: Slider
    private lateinit var buttonGallery: Button
    private lateinit var buttonSave: Button
    private lateinit var sliderContrast: Slider
    private lateinit var sliderSaturation: Slider
    private lateinit var sliderGamma: Slider
    private var originalBitmap: Bitmap? = null
    private var brightness = 0
    private var contrast = 0
    private var avgBr = 0
    private var saturation = 0
    private var gamma = 0.0
    private var lastJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        //do not change this line
        currentImage.setImageBitmap(createBitmap())
        originalBitmap = currentImage.drawable.toBitmap()


        buttonGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, Images.Media.EXTERNAL_CONTENT_URI)
            activityResultLauncher.launch(intent)
        }

        buttonSave.setOnClickListener {
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                val bitmap = currentImage.drawable.toBitmap()
                val values = ContentValues()
                values.put(Images.Media.DATE_TAKEN, System.currentTimeMillis())
                values.put(Images.Media.MIME_TYPE, "image/jpeg")
                values.put(Images.ImageColumns.WIDTH, bitmap.width)
                values.put(Images.ImageColumns.HEIGHT, bitmap.height)

                val uri = this@MainActivity.contentResolver.insert(
                    Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return@setOnClickListener

                contentResolver.openOutputStream(uri).use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                }
            } else {
                when {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED -> {
                            }
                    else -> ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        0
                    )
                }
            }
        }


        sliderBrightness.addOnChangeListener { _, value, _ ->
            applyAllFilters()
        }


        sliderContrast.addOnChangeListener { _, value, _ ->
            applyAllFilters()
        }

        sliderSaturation.addOnChangeListener { _, value, _ ->
            applyAllFilters()
        }

        sliderGamma.addOnChangeListener { _, value, _ ->
            applyAllFilters()
        }
         
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            0 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    buttonSave.callOnClick()
                }
            }

            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    private fun applyAllFilters() {
        lastJob?.cancel()
        brightness = sliderBrightness.value.toInt()
        contrast = sliderContrast.value.toInt()
        saturation = sliderSaturation.value.toInt()
        gamma = sliderGamma.value.toDouble()

        lastJob = GlobalScope.launch(Dispatchers.Default) {

            val brightenImageDeferred: Deferred<Bitmap> = this.async {
               applyBrightnessFilter(brightness, originalBitmap!!)
            }

            val brightenImage: Bitmap = brightenImageDeferred.await()
            avgBr = calculateAvgBright(brightenImage)
            val contrastImage = applyContrastFilter(contrast, avgBr, brightenImage)
            ensureActive()
            val saturatedImage = applySaturationFilter(saturation, contrastImage)
            ensureActive()
            val gammaImage = applyGammaFilter(gamma, saturatedImage)
            ensureActive()

            runOnUiThread {
                currentImage.setImageBitmap(gammaImage)
            }
        }
    }

    private fun applyBrightnessFilter(value: Int, bitmap: Bitmap): Bitmap {
        val brightBitmap = bitmap.copy(Bitmap.Config.ARGB_8888,true)
        val width = brightBitmap.width
        val height = brightBitmap.height
        val pixels = IntArray(width * height)
        brightBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = checkBoundaries(Color.red(color) + value)
            val g = checkBoundaries(Color.green(color) + value)
            val b = checkBoundaries(Color.blue(color) + value)
            pixels[i] = Color.rgb(r,g,b)
        }
        brightBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return brightBitmap
    }

    private fun calculateAvgBright(bitmap: Bitmap) : Int {
        val width = bitmap.width
        val height = bitmap.height
        var total: Long = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                total += color.red + color.green + color.blue
            }
        }

        return checkBoundaries((total / (width * height * 3)).toInt())
    }

    private fun applyContrastFilter(value: Int, avg: Int, bitmap: Bitmap): Bitmap {
        val contrastBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = contrastBitmap.width
        val height = contrastBitmap.height
        val pixels = IntArray(width * height)
        contrastBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = ((255.0 + value) / (255 - value))

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val newR= checkBoundaries((alpha * (r - avg) + avg).toInt())
            val newG = checkBoundaries((alpha * (g - avg) + avg).toInt())
            val newB = checkBoundaries((alpha * (b - avg) + avg).toInt())
            pixels[i] = Color.rgb(newR, newG, newB)
        }
        contrastBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return contrastBitmap
    }

    private fun applySaturationFilter(value: Int, bitmap: Bitmap): Bitmap {
        val saturationBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = saturationBitmap.width
        val height = saturationBitmap.height
        val pixels = IntArray(width * height)
        saturationBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val alpha = ((255.0 + value) / (255 - value))

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val rgbAvg = (r + g + b) / 3
            val newR = checkBoundaries((alpha * (r - rgbAvg) + rgbAvg).toInt())
            val newG = checkBoundaries((alpha * (g - rgbAvg) + rgbAvg).toInt())
            val newB = checkBoundaries((alpha * (b - rgbAvg) + rgbAvg).toInt())
            pixels[i] = Color.rgb(newR, newG, newB)
        }

        saturationBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return saturationBitmap
    }

    private fun applyGammaFilter(value: Double, bitmap: Bitmap): Bitmap {
        val gammaBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = gammaBitmap.width
        val height = gammaBitmap.height
        val pixels = IntArray(width * height)
        gammaBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val newR = (255 * (r / 255.0).pow(value)).toInt()
            val newG = (255 * (g / 255.0).pow(value)).toInt()
            val newB = (255 * (b / 255.0).pow(value)).toInt()
            pixels[i] = Color.rgb(newR, newG, newB)
        }

        gammaBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return gammaBitmap
    }

    private fun hasPermission(manifestPermission: String) : Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.checkSelfPermission(manifestPermission) == PackageManager.PERMISSION_GRANTED
        } else {
            PermissionChecker.checkSelfPermission(this, manifestPermission) == PermissionChecker.PERMISSION_GRANTED
        }
    }

    private fun checkBoundaries(value: Int) : Int {
        return if (value > 255) {
            255
        } else if (value < 0) {
            0
        } else {
            value
        }
    }

    private fun bindViews() {
        currentImage = findViewById(R.id.ivPhoto)
        sliderBrightness = findViewById(R.id.slBrightness)
        buttonGallery = findViewById(R.id.btnGallery)
        buttonSave = findViewById(R.id.btnSave)
        sliderContrast = findViewById(R.id.slContrast)
        sliderSaturation = findViewById(R.id.slSaturation)
        sliderGamma = findViewById(R.id.slGamma)
    }

    // do not change this function
    fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x+y) % 100 + 120

                pixels[index] = Color.rgb(R,G,B)

            }
        }
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
}