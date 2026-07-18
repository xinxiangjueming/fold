package com.example.fold.data.server

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/** InputStream wrapper that counts bytes read and reports progress via callback. */
class CountingInputStream(
    private val delegate: InputStream,
    private val totalBytes: Long = 0,
    private val onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
) : FilterInputStream(delegate) {

    private var bytesRead = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            bytesRead++
            onProgress?.invoke(bytesRead, totalBytes)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            bytesRead += n
            onProgress?.invoke(bytesRead, totalBytes)
        }
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = delegate.skip(n)
        bytesRead += skipped
        return skipped
    }

    fun getBytesRead(): Long = bytesRead
}

/** OutputStream wrapper that counts bytes written and reports progress via callback. */
class CountingOutputStream(
    private val delegate: OutputStream,
    private val totalBytes: Long = 0,
    private val onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null,
) : FilterOutputStream(delegate) {

    private var bytesWritten = 0L

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
        onProgress?.invoke(bytesWritten, totalBytes)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
        onProgress?.invoke(bytesWritten, totalBytes)
    }

    fun getBytesWritten(): Long = bytesWritten
}
