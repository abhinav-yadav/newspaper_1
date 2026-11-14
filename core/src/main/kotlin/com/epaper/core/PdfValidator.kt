package com.epaper.core

import java.io.File
import java.io.RandomAccessFile

object PdfValidator {
    private val MAGIC = "%PDF-".toByteArray()

    /** True if the `%PDF-` header appears within the first 1 KB (the spec tolerates leading junk). */
    fun hasPdfHeader(head: ByteArray): Boolean {
        val limit = minOf(head.size, 1024) - MAGIC.size
        for (i in 0..limit) {
            if ((MAGIC.indices).all { head[i + it] == MAGIC[it] }) return true
        }
        return false
    }

    /** Header present and an `%%EOF` marker in the tail, i.e. not an HTML error page and not truncated. */
    fun isCompletePdf(file: File): Boolean {
        if (!file.isFile || file.length() < 64) return false
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(1024L, raf.length()).toInt())
            raf.readFully(head)
            if (!hasPdfHeader(head)) return false
            val tailSize = minOf(4096L, raf.length()).toInt()
            raf.seek(raf.length() - tailSize)
            val tail = ByteArray(tailSize)
            raf.readFully(tail)
            return String(tail, Charsets.ISO_8859_1).contains("%%EOF")
        }
    }
}
