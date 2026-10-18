package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfReportUtil {
    fun generateAndSharePdf(context: Context, records: List<InspectionRecord>) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas
        val paint = Paint().apply {
            textSize = 14f
            setARGB(255, 0, 0, 0)
        }

        var y = 40f
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText("소방용품 KFI 인증 점검 보고서", 40f, y, paint)
        y += 30f

        paint.textSize = 12f
        paint.isFakeBoldText = false
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        canvas.drawText("생성 일시: $dateStr", 40f, y, paint)
        y += 30f

        canvas.drawLine(40f, y, 555f, y, paint)
        y += 20f

        paint.isFakeBoldText = true
        canvas.drawText("번호", 40f, y, paint)
        canvas.drawText("형식번호", 90f, y, paint)
        canvas.drawText("종별", 180f, y, paint)
        canvas.drawText("제조업체", 280f, y, paint)
        canvas.drawText("점검시각", 430f, y, paint)
        y += 10f
        paint.isFakeBoldText = false
        canvas.drawLine(40f, y, 555f, y, paint)
        y += 20f

        for ((index, rec) in records.withIndex()) {
            if (y > 780f) break
            canvas.drawText("${index + 1}", 40f, y, paint)
            canvas.drawText(rec.formatCode, 90f, y, paint)
            canvas.drawText(rec.type.take(12), 180f, y, paint)
            canvas.drawText(rec.manufacturer.take(15), 280f, y, paint)
            val tStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(rec.timestamp))
            canvas.drawText(tStr, 430f, y, paint)
            y += 25f
        }

        pdfDocument.finishPage(page)

        val file = File(context.cacheDir, "KFI_Inspection_Report.pdf")
        try {
            pdfDocument.writeTo(FileOutputStream(file))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pdfDocument.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "점검 보고서 PDF 공유"))
    }
}
