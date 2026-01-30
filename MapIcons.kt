package com.adityaapte.virtualcockpit

import android.graphics.Bitmap
import android.graphics.Paint

object MapIcons {
    fun makeAircraftBitmap(): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#000000")
            style = Paint.Style.FILL
        }

        val outer = android.graphics.Path().apply {
            moveTo(size / 2f, 8f)
            lineTo(size - 10f, size - 12f)
            lineTo(size / 2f, size - 28f)
            lineTo(10f, size - 12f)
            close()
        }
        c.drawPath(outer, white)

        val inner = android.graphics.Path().apply {
            moveTo(size / 2f, 18f)
            lineTo(size - 22f, size - 18f)
            lineTo(size / 2f, size - 34f)
            lineTo(22f, size - 18f)
            close()
        }
        c.drawPath(inner, green)

        return bmp
    }

    fun makeAirportBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)

        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#00E676")
            style = Paint.Style.FILL
        }

        // circle pin
        c.drawCircle(size / 2f, size / 2f, size / 2.2f, outer)
        c.drawCircle(size / 2f, size / 2f, size / 2.7f, inner)

        return bmp
    }

}
