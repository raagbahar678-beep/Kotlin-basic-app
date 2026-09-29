package com.example.myapp

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

// A slice rectangle in DISPLAY-image coordinates (same idea as canvas coords in the Python app).
class SliceRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

// Everything needed to export slices, captured at the moment Save is pressed.
class Snapshot(val full: Bitmap, val dispW: Int, val dispH: Int, val slices: List<SliceRect>)

fun clampInt(v: Int, lo: Int, hi: Int): Int {
    return if (v < lo) lo else if (v > hi) hi else v
}

// Builds the full-resolution crop for slice number `pos`.
// Mirrors _register_slice: display -> image scale, int() truncation,
// and any area already covered by an EARLIER slice in this batch is filled white.
fun makeCrop(snap: Snapshot, pos: Int, whiteBackground: Boolean): Bitmap? {
    val wImg = snap.full.width
    val hImg = snap.full.height
    val scaleX = wImg.toDouble() / snap.dispW.toDouble()
    val scaleY = hImg.toDouble() / snap.dispH.toDouble()
    val s = snap.slices[pos]

    val cl = clampInt((s.left * scaleX).toInt(), 0, wImg)
    val ct = clampInt((s.top * scaleY).toInt(), 0, hImg)
    val cr = clampInt((s.right * scaleX).toInt(), 0, wImg)
    val cb = clampInt((s.bottom * scaleY).toInt(), 0, hImg)
    if (cr <= cl || cb <= ct) return null

    val cw = cr - cl
    val ch = cb - ct
    val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    if (whiteBackground) {
        canvas.drawColor(Color.WHITE)
    }
    canvas.drawBitmap(snap.full, Rect(cl, ct, cr, cb), Rect(0, 0, cw, ch), null)

    val white = Paint()
    white.color = Color.WHITE
    white.style = Paint.Style.FILL
    white.isAntiAlias = false

    for (j in 0 until pos) {
        val p = snap.slices[j]
        val ovLeft = maxOf(s.left, p.left)
        val ovTop = maxOf(s.top, p.top)
        val ovRight = minOf(s.right, p.right)
        val ovBottom = minOf(s.bottom, p.bottom)
        if (ovRight <= ovLeft || ovBottom <= ovTop) continue

        val bl = clampInt(((ovLeft - s.left) * scaleX).toInt(), 0, cw)
        val bt = clampInt(((ovTop - s.top) * scaleY).toInt(), 0, ch)
        val br = clampInt(((ovRight - s.left) * scaleX).toInt(), 0, cw)
        val bb = clampInt(((ovBottom - s.top) * scaleY).toInt(), 0, ch)
        if (br > bl && bb > bt) {
            canvas.drawRect(bl.toFloat(), bt.toFloat(), br.toFloat(), bb.toFloat(), white)
        }
    }
    return out
}

class SliceView(context: Context) : View(context) {

    private var full: Bitmap? = null
    private var display: Bitmap? = null
    private var offX = 0f
    private var offY = 0f

    val slices = ArrayList<SliceRect>()
    var baseIndex = 0
    var onChanged: (() -> Unit)? = null

    private var dragging = false
    private var startX = 0
    private var startY = 0
    private var curX = 0
    private var curY = 0

    private val density = context.resources.displayMetrics.density

    private val linePaint = Paint()
    private val dashPaint = Paint()
    private val badgeBg = Paint()
    private val badgeText = Paint()

    init {
        linePaint.color = Color.parseColor("#f7b731")
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 1f
        linePaint.isAntiAlias = false

        dashPaint.color = Color.parseColor("#f7b731")
        dashPaint.style = Paint.Style.STROKE
        dashPaint.strokeWidth = 1f
        dashPaint.isAntiAlias = false
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)

        badgeBg.color = Color.parseColor("#f7b731")
        badgeBg.style = Paint.Style.FILL
        badgeBg.isAntiAlias = true

        badgeText.color = Color.BLACK
        badgeText.textAlign = Paint.Align.CENTER
        badgeText.isFakeBoldText = true
        badgeText.isAntiAlias = true
    }

    fun hasImage(): Boolean {
        return full != null
    }

    fun setImage(b: Bitmap) {
        full = b
        display = null
        slices.clear()
        rebuildDisplay()
        invalidate()
        onChanged?.invoke()
    }

    fun snapshot(): Snapshot? {
        val f = full ?: return null
        val d = display ?: return null
        return Snapshot(f, d.width, d.height, ArrayList<SliceRect>(slices))
    }

    fun undo() {
        if (slices.size > 0) {
            slices.removeAt(slices.size - 1)
            invalidate()
            onChanged?.invoke()
        }
    }

    fun clearSlices() {
        slices.clear()
        invalidate()
        onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildDisplay()
        invalidate()
    }

    // Builds the fitted display copy (like display_image in the Python app).
    private fun rebuildDisplay() {
        val f = full ?: return
        if (width <= 0 || height <= 0) return

        val s = minOf(width.toDouble() / f.width.toDouble(), height.toDouble() / f.height.toDouble())
        var nw = (f.width * s).toInt()
        var nh = (f.height * s).toInt()
        if (nw < 1) nw = 1
        if (nh < 1) nh = 1

        val old = display
        if (old == null || old.width != nw || old.height != nh) {
            val nd = Bitmap.createScaledBitmap(f, nw, nh, true)
            if (old != null && old.width > 0 && old.height > 0 && slices.size > 0) {
                val ow = old.width.toDouble()
                val oh = old.height.toDouble()
                for (i in slices.indices) {
                    val r = slices[i]
                    slices[i] = SliceRect(
                        clampInt(Math.round(r.left * nw / ow).toInt(), 0, nw),
                        clampInt(Math.round(r.top * nh / oh).toInt(), 0, nh),
                        clampInt(Math.round(r.right * nw / ow).toInt(), 0, nw),
                        clampInt(Math.round(r.bottom * nh / oh).toInt(), 0, nh)
                    )
                }
            }
            display = nd
        }
        offX = (width - nw) / 2f
        offY = (height - nh) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#111122"))
        val d = display ?: return
        canvas.drawBitmap(d, offX, offY, null)

        for (i in slices.indices) {
            val r = slices[i]
            canvas.drawRect(offX + r.left, offY + r.top, offX + r.right, offY + r.bottom, linePaint)

            val cx = offX + (r.left + r.right) / 2f
            val cy = offY + (r.top + r.bottom) / 2f
            val radius = maxOf(10f * density, minOf(22f * density, (r.right - r.left) / 5f))
            canvas.drawCircle(cx, cy, radius, badgeBg)
            badgeText.textSize = radius * 0.9f
            val fm = badgeText.fontMetrics
            val ty = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText((baseIndex + i + 1).toString(), cx, ty, badgeText)
        }

        if (dragging) {
            canvas.drawRect(
                offX + minOf(startX, curX), offY + minOf(startY, curY),
                offX + maxOf(startX, curX), offY + maxOf(startY, curY),
                dashPaint
            )
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = display ?: return false
        val x = clampInt((e.x - offX).toInt(), 0, d.width)
        val y = clampInt((e.y - offY).toInt(), 0, d.height)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                startX = x
                startY = y
                curX = x
                curY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    curX = x
                    curY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val left = minOf(startX, x)
                    val top = minOf(startY, y)
                    val right = maxOf(startX, x)
                    val bottom = maxOf(startY, y)
                    // Same rule as the Python app: ignore tiny rectangles (<= 4 px)
                    if (right - left > 4 && bottom - top > 4) {
                        slices.add(SliceRect(left, top, right, bottom))
                        onChanged?.invoke()
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var sliceView: SliceView
    private lateinit var status: TextView

    private var imageBaseName = "image"
    private var imageIsJpeg = false
    private var sliceCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1a1a2e"))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL

        val btnOpen = makeButton("Open") { pickImage() }
        val btnUndo = makeButton("Undo") { sliceView.undo() }
        val btnClear = makeButton("Clear") { sliceView.clearSlices() }
        val btnSave = makeButton("Save") { doSave() }

        val btnParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(btnOpen, btnParams)
        bar.addView(btnUndo, btnParams)
        bar.addView(btnClear, btnParams)
        bar.addView(btnSave, btnParams)

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 13f
        val pad = (8 * density).toInt()
        status.setPadding(pad, pad / 2, pad, pad / 2)

        sliceView = SliceView(this)
        sliceView.onChanged = { updateStatus() }

        root.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(sliceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        updateStatus()
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        return b
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val snap = sliceView.snapshot()
        if (snap == null) {
            status.text = "Tap Open to choose an image"
        } else {
            status.text = snap.full.width.toString() + "x" + snap.full.height.toString() +
                " | slices: " + snap.slices.size.toString() +
                " | drag to draw, Save exports to Pictures/SquareSlicer"
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val uri = data.data
            if (uri != null) {
                loadImage(uri)
            }
        }
    }

    private fun loadImage(uri: Uri) {
        status.text = "Loading..."
        val resolver = contentResolver
        Thread(Runnable {
            var bmp: Bitmap? = null
            var name = "image"
            try {
                val stream = resolver.openInputStream(uri)
                if (stream != null) {
                    try {
                        bmp = BitmapFactory.decodeStream(stream)
                    } finally {
                        stream.close()
                    }
                }
                val cursor = resolver.query(uri, null, null, null, null)
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst()) {
                            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (col >= 0) {
                                val n = cursor.getString(col)
                                if (n != null) {
                                    name = n
                                }
                            }
                        }
                    } finally {
                        cursor.close()
                    }
                }
            } catch (t: Throwable) {
                bmp = null
            }

            val result = bmp
            val dot = name.lastIndexOf('.')
            var base = name
            var ext = ""
            if (dot > 0) {
                base = name.substring(0, dot)
                ext = name.substring(dot + 1).lowercase()
            }
            base = base.replace('/', '_')
            val jpeg = ext == "jpg" || ext == "jpeg"

            runOnUiThread {
                if (result == null) {
                    toast("Could not open that image")
                    updateStatus()
                } else {
                    imageBaseName = base
                    imageIsJpeg = jpeg
                    sliceView.baseIndex = sliceCounter
                    sliceView.setImage(result)
                }
            }
        }).start()
    }

    private fun doSave() {
        val snap = sliceView.snapshot()
        if (snap == null) {
            toast("Open an image first")
            return
        }
        if (snap.slices.isEmpty()) {
            toast("Draw at least one slice first")
            return
        }

        val startIdx = sliceCounter
        sliceCounter += snap.slices.size
        sliceView.baseIndex = sliceCounter
        sliceView.clearSlices()

        val base = imageBaseName
        val jpeg = imageIsJpeg
        toast("Saving " + snap.slices.size.toString() + " slice(s)...")

        Thread(Runnable {
            var saved = 0
            for (i in snap.slices.indices) {
                val crop = makeCrop(snap, i, jpeg)
                if (crop != null) {
                    val fileName = base + "_slice_" + (startIdx + i).toString() +
                        (if (jpeg) ".jpg" else ".png")
                    if (saveToGallery(crop, fileName, jpeg)) {
                        saved++
                    }
                    crop.recycle()
                }
            }
            val total = snap.slices.size
            runOnUiThread {
                toast("Saved " + saved.toString() + "/" + total.toString() + " to Pictures/SquareSlicer")
            }
        }).start()
    }

    private fun saveToGallery(bmp: Bitmap, fileName: String, jpeg: Boolean): Boolean {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        values.put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SquareSlicer")
        values.put(MediaStore.Images.Media.IS_PENDING, 1)

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        try {
            val os = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
            val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val ok: Boolean
            try {
                ok = bmp.compress(format, 100, os)
            } finally {
                os.close()
            }
            if (!ok) throw IOException("Compress failed")

            val done = ContentValues()
            done.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
            return true
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return false
        }
    }
}
