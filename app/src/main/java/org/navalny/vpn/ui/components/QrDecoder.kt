package org.navalny.vpn.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Decodes a QR code from an image (gallery / screenshot). Runs once, off the main thread. */
object QrDecoder {
    private const val MAX_SIDE = 1600

    suspend fun decode(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
        val bitmap = runCatching { load(context, uri) }.getOrNull() ?: return@withContext null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            tryDecode(source) ?: tryDecode(source.invert())
        } finally {
            bitmap.recycle()
        }
    }

    private fun tryDecode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            val text = runCatching { reader.decodeWithState(BinaryBitmap(binarizer)).text }.getOrNull()
            reader.reset()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun load(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a software bitmap
                val side = max(info.size.width, info.size.height)
                if (side > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / side
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > MAX_SIDE) sample *= 2
        val real = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, real)
        } ?: error("cannot decode image")
    }
}
