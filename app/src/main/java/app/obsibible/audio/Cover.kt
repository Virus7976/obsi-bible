package app.obsibible.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.obsibible.bible.Ref
import java.io.ByteArrayOutputStream

/**
 * The picture on the media notification and lock screen: the chapter number in dim grey, like
 * the reader's drop cap, with the book name under it. Android lays the title over it, so it stays quiet.
 */
object Cover {
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6
    }

    @Synchronized
    fun png(ref: Ref): ByteArray = cache.getOrPut(ref.chapterRef.key) {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0x12, 0x12, 0x12))
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4A, 0x4A, 0x4A); typeface = serif; textSize = 150f; textAlign = Paint.Align.CENTER }
        c.drawText(ref.chapter.toString(), size / 2f, 262f, number)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x6A, 0x6A, 0x6A); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textSize = 40f; textAlign = Paint.Align.CENTER }
        val book = ref.bookInfo.name
        while (name.measureText(book) > size - 60 && name.textSize > 24f) name.textSize -= 2f
        c.drawText(book, size / 2f, 322f, name)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    }
}
