package com.example.fold.ui.filebrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import com.example.fold.AppContainer
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ThumbnailLoader {
    private const val MAX_CACHE_SIZE = 50 * 1024 * 1024 // 50MB
    private const val THUMBNAIL_SIZE = 128 // 44dp * 3 density ≈ 132, use 128 for efficiency

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val loadingJobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun loadThumbnail(imageView: ImageView, file: File, isDark: Boolean) {
        val path = file.absolutePath
        val cacheKey = "${path}_${isDark}"

        // Check cache first
        cache.get(cacheKey)?.let { bitmap ->
            imageView.setImageBitmap(bitmap)
            return
        }

        // Cancel previous load for this ImageView
        val tag = imageView.tag as? String
        if (tag != null) {
            loadingJobs[tag]?.cancel()
        }

        imageView.tag = cacheKey
        imageView.setImageDrawable(null)

        val job = scope.launch {
            try {
                val bitmap = when {
                    isImageFile(file) -> loadBitmapThumbnail(file)
                    isVideoFile(file) -> loadVideoThumbnail(file)
                    isPdfFile(file) -> loadPdfThumbnail(file, isDark)
                    isApkFile(file) -> loadApkThumbnail(file)
                    else -> null
                }

                bitmap?.let {
                    cache.put(cacheKey, it)
                    withContext(Dispatchers.Main) {
                        if (imageView.tag == cacheKey) {
                            imageView.setImageBitmap(it)
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently fail, keep default icon
            }
        }

        loadingJobs[cacheKey] = job
    }

    fun cancelLoad(imageView: ImageView) {
        val tag = imageView.tag as? String ?: return
        loadingJobs[tag]?.cancel()
        loadingJobs.remove(tag)
        imageView.tag = null
    }

    private fun loadBitmapThumbnail(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(options, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

            // Crop to square
            val size = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2
            Bitmap.createBitmap(bitmap, x, y, size, size).also {
                if (it != bitmap) bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadVideoThumbnail(file: File): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, android.util.Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPdfThumbnail(file: File, isDark: Boolean): Bitmap? {
        return try {
            val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val page = renderer.openPage(0)

            val scale = THUMBNAIL_SIZE.toFloat() / maxOf(page.width, page.height)
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            if (isDark) {
                canvas.drawColor(0xFF1A1A1A.toInt())
            } else {
                canvas.drawColor(0xFFFFFFFF.toInt())
            }

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            fd.close()

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun loadApkThumbnail(file: File): Bitmap? {
        return try {
            val context = AppContainer.appContext
            val packageManager = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }

            packageInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = file.absolutePath
                appInfo.publicSourceDir = file.absolutePath
                val drawable = appInfo.loadIcon(packageManager)
                val bitmap = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                drawable.draw(canvas)
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun isImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }

    private fun isVideoFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "3gp", "ts")
    }

    private fun isPdfFile(file: File): Boolean {
        return file.extension.equals("pdf", ignoreCase = true)
    }

    private fun isApkFile(file: File): Boolean {
        return file.extension.equals("apk", ignoreCase = true)
    }

    fun clearCache() {
        cache.evictAll()
    }

    fun removeCacheForFile(path: String) {
        val keysToRemove = cache.snapshot().keys.filter { it.startsWith(path) }
        keysToRemove.forEach { cache.remove(it) }
    }
}
