package io.github.yuriybak.contactlabelshortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ExecutorService

/** A colored circle with the first letter of a name: shown while (or instead of) the photo. */
class LetterAvatar(name: String) : Drawable() {
    private val letter = initialOf(name)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorFor(name, COLORS)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle)
        text.textSize = r
        val y = b.exactCenterY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(letter, b.exactCenterX(), y, text)
    }

    override fun setAlpha(alpha: Int) {
        circle.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        circle.colorFilter = colorFilter
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val COLORS = intArrayOf(
            0xFF1A73E8.toInt(), 0xFFD93025.toInt(), 0xFF188038.toInt(), 0xFFE37400.toInt(),
            0xFF9334E6.toInt(), 0xFF007B83.toInt(), 0xFFC5221F.toInt(), 0xFF5F6368.toInt(),
        )
    }
}

/**
 * Loads contact thumbnails in the background, only for the rows that are bound,
 * and keeps the decoded bitmaps and the letter avatars in memory.
 */
class AvatarLoader(private val context: Context, private val executor: ExecutorService) {

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** All avatars have the same size, so one letter drawable can serve every row with the name. */
    private val letters = LruCache<String, LetterAvatar>(256)

    /** Shows the letter avatar at once, then the photo when (if) it is loaded. */
    fun bind(view: ImageView, name: String, photoUri: String?) {
        view.tag = photoUri
        val cached = photoUri?.let { cache.get(it) }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(letters.get(name) ?: LetterAvatar(name).also { letters.put(name, it) })
        if (photoUri == null) return

        executor.execute {
            // The row was reused for another contact while this waited: skip the work
            if (view.tag != photoUri) return@execute
            val bitmap = decode(photoUri) ?: return@execute
            main.post {
                cache.put(photoUri, bitmap)
                if (view.tag == photoUri) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(uri: String): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
