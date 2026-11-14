package com.epaper.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfValidatorTest {
    @Test fun header() {
        assertTrue(PdfValidator.hasPdfHeader("%PDF-1.4\n...".toByteArray()))
        assertTrue(PdfValidator.hasPdfHeader("﻿\n%PDF-1.4".toByteArray()))
        assertFalse(PdfValidator.hasPdfHeader("<!DOCTYPE html>".toByteArray()))
        assertFalse(PdfValidator.hasPdfHeader(ByteArray(0)))
    }

    @Test fun completeness() {
        val f = File.createTempFile("pdftest", ".pdf")
        f.writeText("%PDF-1.4\n" + "0".repeat(100) + "\n%%EOF\n")
        assertTrue(PdfValidator.isCompletePdf(f))
        f.writeText("%PDF-1.4\n" + "0".repeat(100)) // truncated download
        assertFalse(PdfValidator.isCompletePdf(f))
        f.writeText("<html>" + "0".repeat(100) + "%%EOF")
        assertFalse(PdfValidator.isCompletePdf(f))
        f.delete()
    }
}
