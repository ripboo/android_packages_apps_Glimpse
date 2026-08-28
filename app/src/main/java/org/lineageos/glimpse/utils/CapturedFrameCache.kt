/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils

import android.graphics.Bitmap
import android.os.Environment
import org.lineageos.glimpse.models.Media
import java.io.File
import java.io.FileOutputStream

/**
 * يخزّن صور مصغّرة ملتقطة يدويًا من الفيديو في مجلد مخفي على التخزين الخارجي
 * حتى تبقى محفوظة حتى بعد حذف التطبيق وإعادة تثبيته.
 */
object CapturedFrameCache {
    // اسم المجلد يبدأ بنقطة حتى يبقى مخفيًا عن معارض الصور ومدير الملفات
    private const val CACHE_DIR_NAME = ".pice"
    private const val JPEG_QUALITY = 90

    private val cacheDir: File
        get() = File(Environment.getExternalStorageDirectory(), CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
            // حماية إضافية حتى لا يتم فهرسة المجلد كوسائط
            File(this, ".nomedia").takeUnless { it.exists() }?.createNewFile()
        }

    private fun fileFor(media: Media): File {
        val safeName = media.uri.lastPathSegment
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: media.uri.toString().hashCode().toString()
        return File(cacheDir, "$safeName.jpg")
    }

    /**
     * يرجع ملف الصورة المصغّرة المخزّنة لهذا الميديا إن وُجد.
     */
    fun get(media: Media): File? = fileFor(media).takeIf { it.exists() && it.length() > 0 }

    /**
     * يحفظ [bitmap] كصورة مصغّرة لهذا الميديا (يستبدل القديمة إن وُجدت).
     */
    fun save(media: Media, bitmap: Bitmap): File {
        val file = fileFor(media)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        return file
    }
}
