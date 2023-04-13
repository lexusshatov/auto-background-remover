package com.slowmac.autobackgroundremover

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


object BackgroundRemover {
    // [0;1]
    private const val MIN_CONFIDENCE = 0.4
    private const val TRANSPARENCY_BOUND = 0.99

    private val segment: Segmenter = let {
        val segmentOptions = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(segmentOptions)
    }
    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob()

    /**
     * Process the image to get buffer and image height and width
     * @param bitmap Bitmap which you want to remove background.
     * @param trimEmptyPart After removing the background if its true it will remove the empty part of bitmap. by default its false.
     **/
    @Throws(ForegroundNotFoundException::class)
    suspend fun bitmapForProcessing(
        bitmap: Bitmap,
        trimEmptyPart: Boolean = false,
        severity: Double = MIN_CONFIDENCE,
    ): Bitmap {
        val copyBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val mask = getSegmentationMask(copyBitmap)
        val bgRemovedBitmap =
            removeBackgroundFromImage(copyBitmap, mask, severity)
        return if (trimEmptyPart) {
            trim(bgRemovedBitmap)
        } else bgRemovedBitmap
    }

    private suspend fun getSegmentationMask(bitmap: Bitmap): SegmentationMask =
        suspendCoroutine {
            val input = InputImage.fromBitmap(bitmap, 0)
            segment.process(input)
                .addOnSuccessListener { segmentationMask ->
                    it.resume(segmentationMask)
                }
                .addOnFailureListener { e ->
                    it.resumeWithException(e)
                }
        }


    /**
     * Change the background pixels color to transparent.
     * */
    private suspend fun removeBackgroundFromImage(
        image: Bitmap,
        segmentationMask: SegmentationMask,
        severity: Double,
    ): Bitmap {
        var transparentCounter = 0
        (0 until segmentationMask.height)
            .asFlow()
            .collect { y ->
                (0 until segmentationMask.width).forEach { x ->
                    val index = (segmentationMask.width * y + x) * Float.SIZE_BYTES
                    val confidence = segmentationMask.buffer.getFloat(index)
                    if (confidence < severity) {
                        image.setPixel(x, y, Color.TRANSPARENT)
                        transparentCounter++
                    }
                }
            }
        segmentationMask.buffer.rewind()
        val transparency = transparentCounter / (segmentationMask.height * segmentationMask.width)
        if (transparency > TRANSPARENCY_BOUND) {
            throw ForegroundNotFoundException()
        }
        return image
    }


    /**
     * trim the empty part of a bitmap.
     **/
    private suspend fun trim(
        bitmap: Bitmap,
    ): Bitmap {
        val result = scope.async {
            var firstX = 0
            var firstY = 0
            var lastX = bitmap.width
            var lastY = bitmap.height
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            loop@ for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    if (pixels[x + y * bitmap.width] != Color.TRANSPARENT) {
                        firstX = x
                        break@loop
                    }
                }
            }
            loop@ for (y in 0 until bitmap.height) {
                for (x in firstX until bitmap.width) {
                    if (pixels[x + y * bitmap.width] != Color.TRANSPARENT) {
                        firstY = y
                        break@loop
                    }
                }
            }
            loop@ for (x in bitmap.width - 1 downTo firstX) {
                for (y in bitmap.height - 1 downTo firstY) {
                    if (pixels[x + y * bitmap.width] != Color.TRANSPARENT) {
                        lastX = x
                        break@loop
                    }
                }
            }
            loop@ for (y in bitmap.height - 1 downTo firstY) {
                for (x in bitmap.width - 1 downTo firstX) {
                    if (pixels[x + y * bitmap.width] != Color.TRANSPARENT) {
                        lastY = y
                        break@loop
                    }
                }
            }
            return@async Bitmap.createBitmap(bitmap, firstX, firstY, lastX - firstX, lastY - firstY)
        }
        return result.await()
    }
}