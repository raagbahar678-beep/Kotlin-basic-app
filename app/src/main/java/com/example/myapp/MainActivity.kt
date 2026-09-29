package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

// A slice rectangle in DISPLAY-image coordinates.
// whiteOverlap = registered in SQUARE mode -> areas already covered by an EARLIER slice are blanked white.
class SliceRect(val left: Int, val top: Int, val right: Int, val bottom: Int, val whiteOverlap: Boolean)

// Everything needed to export slices, captured at the moment Save is pressed.
class Snapshot(val full: Bitmap, val dispW: Int, val dispH: Int, val slices: List<SliceRect>)

// Left/right vertical-line pair used by HORIZONTAL mode.
data class Bound(val l: Int, val r: Int)

// One page to work on: an image file, a PDF page, or an in-memory (recreated) image.
class WorkItem(
    val uri: Uri?,
    val isPdf: Boolean,
    val page: Int,
    val label: String,
    val isJpeg: Boolean,
    val mem: Bitmap?
)

// A file that was written to the output folder (needed for Reset).
class SavedRec(val uri: Uri, val number: Int)

// One source image inside the Recreate window.
class RecSrc(val bmp: Bitmap?, val uri: Uri?, val thumb: Bitmap?, val name: String)

enum class Mode { SQUARE, VERTICAL, HORIZONTAL, ERASER }

fun clampInt(v: Int, lo: Int, hi: Int): Int {
    return if (v < lo) lo else if (v > hi) hi else v
}

// Builds the full-resolution crop for slice number `pos`.
// Mirrors _register_slice: display -> image scale, int() truncation,
// and (square-mode slices) any area already covered by an EARLIER slice is filled white.
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

    if (!s.whiteOverlap) return out

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

// ─────────────────────────────────────────────────────────────────────────────
// The drawing view: SQUARE / VERTICAL / HORIZONTAL / ERASER modes
// ─────────────────────────────────────────────────────────────────────────────
class SliceView(context: Context) : View(context) {

    private var full: Bitmap? = null
    private var display: Bitmap? = null
    private var offX = 0f
    private var offY = 0f

    val slices = ArrayList<SliceRect>()
    val vLines = ArrayList<Int>()
    val hLines = LinkedHashMap<Bound, ArrayList<Int>>()
    private val grayLines = HashSet<String>()
    var voidActive = false

    // Number shown on the first pending slice badge (= next file number).
    var baseNumber = 1
    var hint = ""
    var onChanged: (() -> Unit)? = null

    private var dragging = false
    private var startX = 0
    private var startY = 0
    private var curX = 0
    private var curY = 0

    var mode: Mode = Mode.SQUARE
        set(v) {
            field = v
            voidActive = false
            grayLines.clear()
            hint = ""
            dragging = false
            invalidate()
            onChanged?.invoke()
        }

    private val density = context.resources.displayMetrics.density

    private val linePaint = Paint()
    private val dashPaint = Paint()
    private val redPaint = Paint()
    private val grayPaint = Paint()
    private val cyanPaint = Paint()
    private val erasePaint = Paint()
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

        redPaint.color = Color.RED
        redPaint.style = Paint.Style.STROKE
        redPaint.strokeWidth = 1.5f
        redPaint.pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)

        grayPaint.color = Color.parseColor("#888888")
        grayPaint.style = Paint.Style.STROKE
        grayPaint.strokeWidth = 1.5f
        grayPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)

        cyanPaint.color = Color.CYAN
        cyanPaint.style = Paint.Style.STROKE
        cyanPaint.strokeWidth = 1.5f

        erasePaint.color = Color.parseColor("#ff6b6b")
        erasePaint.style = Paint.Style.STROKE
        erasePaint.strokeWidth = 1.5f
        erasePaint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)

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

    private fun grayKey(b: Bound, y: Int): String {
        return b.l.toString() + "_" + b.r.toString() + "_" + y.toString()
    }

    private fun clearEverything() {
        slices.clear()
        vLines.clear()
        hLines.clear()
        grayLines.clear()
        voidActive = false
        dragging = false
        hint = ""
    }

    fun setImage(b: Bitmap) {
        full = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        display = null
        clearEverything()
        rebuildDisplay()
        invalidate()
        onChanged?.invoke()
    }

    fun snapshot(): Snapshot? {
        val f = full ?: return null
        val d = display ?: return null
        return Snapshot(f, d.width, d.height, ArrayList<SliceRect>(slices))
    }

    // Rotate / flip the working image. Lines and slices are cleared (like the Python app).
    fun transform(m: Matrix) {
        val f = full ?: return
        val nb = Bitmap.createBitmap(f, 0, 0, f.width, f.height, m, true)
        full = if (nb.isMutable) nb else nb.copy(Bitmap.Config.ARGB_8888, true)
        display = null
        clearEverything()
        rebuildDisplay()
        invalidate()
        onChanged?.invoke()
    }

    // Undo: last slice first; if none, last vertical line (VERTICAL mode) or last horizontal line (HORIZONTAL mode).
    fun undo() {
        if (slices.size > 0) {
            slices.removeAt(slices.size - 1)
        } else if (mode == Mode.VERTICAL && vLines.size > 0) {
            val rx = vLines.removeAt(vLines.size - 1)
            val toRemove = ArrayList<Bound>()
            for (b in hLines.keys) {
                if (b.l == rx || b.r == rx) toRemove.add(b)
            }
            for (b in toRemove) hLines.remove(b)
        } else if (mode == Mode.HORIZONTAL) {
            var lastB: Bound? = null
            for ((b, ys) in hLines) {
                if (ys.isNotEmpty()) lastB = b
            }
            val lb = lastB
            if (lb != null) {
                val ys = hLines[lb]
                if (ys != null && ys.isNotEmpty()) {
                    val ry = ys.removeAt(ys.size - 1)
                    grayLines.remove(grayKey(lb, ry))
                    if (ys.isEmpty()) hLines.remove(lb)
                }
            }
        }
        hint = ""
        invalidate()
        onChanged?.invoke()
    }

    // Clear ALL lines and pending slices.
    fun removeAll() {
        clearEverything()
        invalidate()
        onChanged?.invoke()
    }

    // Only pending slices are cleared (used after Save - lines stay, like the Python app).
    fun clearSlices() {
        slices.clear()
        invalidate()
        onChanged?.invoke()
    }

    fun toggleVoid() {
        if (mode != Mode.HORIZONTAL) {
            hint = "Void works in HORIZONTAL mode"
            onChanged?.invoke()
            return
        }
        voidActive = !voidActive
        if (voidActive) {
            var marked = false
            for ((b, ys) in hLines) {
                if (ys.isNotEmpty()) {
                    grayLines.add(grayKey(b, ys[ys.size - 1]))
                    marked = true
                }
            }
            hint = if (marked) "VOID ON: last line greyed - the next line only sets a new top"
            else "VOID ON: no lines yet"
        } else {
            grayLines.clear()
            hint = "Void OFF"
        }
        invalidate()
        onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildDisplay()
        invalidate()
        onChanged?.invoke()
    }

    private fun mapV(v: Int, n: Int, o: Double, max: Int): Int {
        return clampInt(Math.round(v * n / o).toInt(), 0, max)
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
            if (old != null && old.width > 0 && old.height > 0) {
                val ow = old.width.toDouble()
                val oh = old.height.toDouble()
                for (i in slices.indices) {
                    val r = slices[i]
                    slices[i] = SliceRect(
                        mapV(r.left, nw, ow, nw), mapV(r.top, nh, oh, nh),
                        mapV(r.right, nw, ow, nw), mapV(r.bottom, nh, oh, nh),
                        r.whiteOverlap
                    )
                }
                for (i in vLines.indices) {
                    vLines[i] = mapV(vLines[i], nw, ow, nw)
                }
                val oldMap = LinkedHashMap<Bound, ArrayList<Int>>(hLines)
                hLines.clear()
                for ((b, ys) in oldMap) {
                    val ny = ArrayList<Int>()
                    for (y in ys) ny.add(mapV(y, nh, oh, nh))
                    hLines[Bound(mapV(b.l, nw, ow, nw), mapV(b.r, nw, ow, nw))] = ny
                }
                grayLines.clear()
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
        val dh = d.height.toFloat()

        // vertical lines
        for (x in vLines) {
            canvas.drawLine(offX + x, offY, offX + x, offY + dh, redPaint)
        }
        // horizontal lines
        for ((b, ys) in hLines) {
            for (y in ys) {
                val p = if (grayLines.contains(grayKey(b, y))) grayPaint else redPaint
                canvas.drawLine(offX + b.l, offY + y, offX + b.r, offY + y, p)
            }
        }

        // slices + number badges
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
            canvas.drawText((baseNumber + i).toString(), cx, ty, badgeText)
        }

        // live preview while dragging
        if (dragging) {
            if (mode == Mode.SQUARE || mode == Mode.ERASER) {
                canvas.drawRect(
                    offX + minOf(startX, curX), offY + minOf(startY, curY),
                    offX + maxOf(startX, curX), offY + maxOf(startY, curY),
                    if (mode == Mode.ERASER) erasePaint else dashPaint
                )
            } else if (mode == Mode.VERTICAL) {
                canvas.drawLine(offX + curX, offY, offX + curX, offY + dh, cyanPaint)
            } else if (mode == Mode.HORIZONTAL) {
                val b = findBoundaries(curX)
                if (b != null) {
                    canvas.drawLine(offX + b.l, offY + curY, offX + b.r, offY + curY, cyanPaint)
                }
            }
        }
    }

    private fun findBoundaries(x: Int): Bound? {
        if (vLines.size < 2) return null
        val sv = vLines.sorted()
        val tol = 4
        for (i in 0 until sv.size - 1) {
            if ((sv[i] - tol) <= x && x <= (sv[i + 1] + tol)) {
                return Bound(sv[i], sv[i + 1])
            }
        }
        return null
    }

    private fun horizontalUp(x: Int, y: Int) {
        val b = findBoundaries(x)
        if (b == null) {
            hint = "Release between two vertical lines"
            return
        }
        var list = hLines[b]
        if (list == null) {
            list = ArrayList<Int>()
            hLines[b] = list
        }
        if (voidActive) {
            voidActive = false
            list.add(y)
            hint = "New top boundary set - draw the next line below it"
            return
        }
        if (list.isEmpty()) {
            list.add(y)
            hint = "Top boundary set - draw a second line below it to cut a slice"
        } else {
            val top = list[list.size - 1]
            list.add(y)
            if (y > top) {
                slices.add(SliceRect(b.l, top, b.r, y, false))
                hint = ""
            }
        }
    }

    private fun eraseRect(l: Int, t: Int, r: Int, b: Int) {
        val f = full ?: return
        val d = display ?: return
        val sx = f.width.toDouble() / d.width.toDouble()
        val sy = f.height.toDouble() / d.height.toDouble()
        val il = clampInt((l * sx).toInt(), 0, f.width)
        val it = clampInt((t * sy).toInt(), 0, f.height)
        val ir = clampInt((r * sx).toInt(), 0, f.width)
        val ib = clampInt((b * sy).toInt(), 0, f.height)
        if (ir > il && ib > it) {
            val c = Canvas(f)
            val p = Paint()
            p.color = Color.WHITE
            p.style = Paint.Style.FILL
            c.drawRect(il.toFloat(), it.toFloat(), ir.toFloat(), ib.toFloat(), p)
            display = Bitmap.createScaledBitmap(f, d.width, d.height, true)
            hint = "Region erased - draw another, or Save to store the erased page"
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
                    if (mode == Mode.SQUARE) {
                        // Same rule as the Python app: ignore tiny rectangles (<= 4 px)
                        if (right - left > 4 && bottom - top > 4) {
                            slices.add(SliceRect(left, top, right, bottom, true))
                        }
                    } else if (mode == Mode.ERASER) {
                        if (right - left > 2 && bottom - top > 2) {
                            eraseRect(left, top, right, bottom)
                        }
                    } else if (mode == Mode.VERTICAL) {
                        if (!vLines.contains(x)) {
                            vLines.add(x)
                            hint = vLines.size.toString() + " vertical line(s) - switch to HORIZONTAL to cut slices"
                        }
                    } else if (mode == Mode.HORIZONTAL) {
                        horizontalUp(x, y)
                    }
                    invalidate()
                    onChanged?.invoke()
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

// ─────────────────────────────────────────────────────────────────────────────
// Recreate window: arrange images / slices into columns and build one new image
// ─────────────────────────────────────────────────────────────────────────────
class RecreateUi(
    private val act: Activity,
    private val srcs: ArrayList<RecSrc>,
    private val onDone: (Bitmap) -> Unit
) {
    private val dlg = Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private var cols = 1
    private var rows = 1
    private var gap = 8
    private var black = false

    private val listBox = LinearLayout(act)
    private val colsTv = TextView(act)
    private val rowsTv = TextView(act)
    private val gapTv = TextView(act)
    private val bgBtn = Button(act)

    private val colBgs = arrayOf(
        "#0d2233", "#1a2a0d", "#2a1a0d", "#1a0d2a", "#2a0d1a",
        "#0d2a1a", "#2a2a0d", "#0d1a2a", "#2a0d0d", "#0d2a2a"
    )

    private fun dp(v: Int): Int {
        return (v * act.resources.displayMetrics.density).toInt()
    }

    init {
        rows = if (srcs.size > 0) srcs.size else 1
        buildUi()
        refresh()
    }

    fun show() {
        dlg.show()
    }

    private fun smallBtn(label: String, action: () -> Unit): Button {
        val b = Button(act)
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(6), dp(8), dp(6))
        b.setOnClickListener { action() }
        return b
    }

    private fun label(t: String): TextView {
        val tv = TextView(act)
        tv.text = t
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(dp(6), 0, dp(6), 0)
        return tv
    }

    private fun autoRows() {
        val n = if (srcs.size > 0) srcs.size else 1
        rows = (n + cols - 1) / cols
        if (rows < 1) rows = 1
    }

    private fun buildUi() {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0d0d1a"))
        val pad = dp(6)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(act)
        title.text = "Recreate Layout - " + srcs.size.toString() + " image(s)"
        title.setTextColor(Color.parseColor("#cba6f7"))
        title.textSize = 16f
        title.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.addView(title)

        val r1 = LinearLayout(act)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.addView(label("Columns"))
        r1.addView(smallBtn("-") {
            if (cols > 1) { cols--; autoRows(); refresh() }
        })
        r1.addView(colsTv)
        r1.addView(smallBtn("+") {
            if (cols < 20) { cols++; autoRows(); refresh() }
        })
        r1.addView(label("Rows/col"))
        r1.addView(smallBtn("-") {
            if (rows > 1) { rows--; refresh() }
        })
        r1.addView(rowsTv)
        r1.addView(smallBtn("+") {
            rows++; refresh()
        })
        root.addView(r1)

        val r2 = LinearLayout(act)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.addView(label("Gap"))
        r2.addView(smallBtn("-") {
            if (gap >= 4) { gap -= 4; refresh() }
        })
        r2.addView(gapTv)
        r2.addView(smallBtn("+") {
            gap += 4; refresh()
        })
        r2.addView(label("Background"))
        bgBtn.isAllCaps = false
        bgBtn.textSize = 12f
        bgBtn.minWidth = 0
        bgBtn.minimumWidth = 0
        bgBtn.minHeight = 0
        bgBtn.minimumHeight = 0
        bgBtn.setOnClickListener { black = !black; refresh() }
        r2.addView(bgBtn)
        root.addView(r2)

        val hintTv = TextView(act)
        hintTv.text = "Images fill column 1 first (Rows/col each), then column 2, ... Use Up/Dn to reorder."
        hintTv.setTextColor(Color.parseColor("#aaaaaa"))
        hintTv.textSize = 11f
        hintTv.setPadding(dp(4), dp(2), dp(4), dp(2))
        root.addView(hintTv)

        listBox.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(act)
        sv.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(act)
        bottom.orientation = LinearLayout.HORIZONTAL
        val bp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bottom.addView(smallBtn("Preview") { doPreview() }, bp)
        bottom.addView(smallBtn("Recreate") { doRecreate() }, bp)
        bottom.addView(smallBtn("Cancel") { dlg.dismiss() }, bp)
        root.addView(bottom)

        dlg.setContentView(root)
    }

    private fun refresh() {
        colsTv.text = cols.toString()
        colsTv.setTextColor(Color.WHITE)
        rowsTv.text = rows.toString()
        rowsTv.setTextColor(Color.WHITE)
        gapTv.text = gap.toString() + "px"
        gapTv.setTextColor(Color.WHITE)
        if (black) {
            bgBtn.text = "Black"
            bgBtn.setTextColor(Color.WHITE)
        } else {
            bgBtn.text = "White"
            bgBtn.setTextColor(Color.YELLOW)
        }

        listBox.removeAllViews()
        for (i in srcs.indices) {
            val s = srcs[i]
            val c = minOf(i / rows, cols - 1)
            val row = LinearLayout(act)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundColor(Color.parseColor(colBgs[c % colBgs.size]))
            row.setPadding(dp(4), dp(4), dp(4), dp(4))

            val iv = ImageView(act)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            if (s.thumb != null) iv.setImageBitmap(s.thumb)
            row.addView(iv, LinearLayout.LayoutParams(dp(60), dp(60)))

            val tv = TextView(act)
            tv.text = "#" + (i + 1).toString() + "  " + s.name + "\nColumn " + (c + 1).toString()
            tv.setTextColor(Color.WHITE)
            tv.textSize = 12f
            tv.setPadding(dp(8), 0, dp(8), 0)
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val idx = i
            row.addView(smallBtn("Up") {
                if (idx > 0) {
                    val t = srcs[idx]; srcs[idx] = srcs[idx - 1]; srcs[idx - 1] = t; refresh()
                }
            })
            row.addView(smallBtn("Dn") {
                if (idx < srcs.size - 1) {
                    val t = srcs[idx]; srcs[idx] = srcs[idx + 1]; srcs[idx + 1] = t; refresh()
                }
            })
            row.addView(smallBtn("X") {
                srcs.removeAt(idx)
                if (srcs.size == 0) { dlg.dismiss() } else { autoRows(); refresh() }
            })
            listBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ── image size / decode helpers ──
    private fun sizeOf(s: RecSrc): IntArray {
        val b = s.bmp
        if (b != null) return intArrayOf(b.width, b.height)
        val u = s.uri ?: return intArrayOf(1, 1)
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        val st = act.contentResolver.openInputStream(u)
        if (st != null) {
            try { BitmapFactory.decodeStream(st, null, o) } finally { st.close() }
        }
        return intArrayOf(maxOf(1, o.outWidth), maxOf(1, o.outHeight))
    }

    private fun loadFull(s: RecSrc): Bitmap? {
        val b = s.bmp
        if (b != null) return b
        val u = s.uri ?: return null
        val st = act.contentResolver.openInputStream(u) ?: return null
        try {
            return BitmapFactory.decodeStream(st)
        } finally {
            st.close()
        }
    }

    // Port of RecreateLayoutWindow._compose
    private fun compose(): Bitmap {
        val n = srcs.size
        if (n == 0) throw IllegalStateException("No images")

        val groups = ArrayList<List<RecSrc>>()
        for (c in 0 until cols) {
            val from = c * rows
            if (from >= n) break
            val to = if (c == cols - 1) n else minOf(n, from + rows)
            groups.add(ArrayList<RecSrc>(srcs.subList(from, to)))
        }

        val colW = IntArray(groups.size)
        val colH = IntArray(groups.size)
        val heights = ArrayList<IntArray>()
        val g = maxOf(0, gap)
        for (ci in groups.indices) {
            val grp = groups[ci]
            var w = 1
            val sizes = ArrayList<IntArray>()
            for (s in grp) {
                val sz = sizeOf(s)
                sizes.add(sz)
                if (sz[0] > w) w = sz[0]
            }
            colW[ci] = w
            val hs = IntArray(grp.size)
            var total = 0
            for (k in grp.indices) {
                val sz = sizes[k]
                hs[k] = maxOf(1, Math.round(sz[1].toDouble() * w.toDouble() / sz[0].toDouble()).toInt())
                total += hs[k]
            }
            total += g * maxOf(0, grp.size - 1)
            colH[ci] = total
            heights.add(hs)
        }
        var totalW = g * maxOf(0, groups.size - 1)
        var totalH = 1
        for (ci in groups.indices) {
            totalW += colW[ci]
            if (colH[ci] > totalH) totalH = colH[ci]
        }

        // Safety: shrink everything if the result would be enormous.
        val limit = 60000000.0
        val pixels = totalW.toDouble() * totalH.toDouble()
        val f = if (pixels > limit) Math.sqrt(limit / pixels) else 1.0
        fun sc(v: Int): Int {
            return maxOf(1, Math.round(v * f).toInt())
        }

        val outW = if (f < 1.0) sc(totalW) else totalW
        val outH = if (f < 1.0) sc(totalH) else totalH
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (black) Color.BLACK else Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var x = 0
        for (ci in groups.indices) {
            var y = 0
            val grp = groups[ci]
            val cw = if (f < 1.0) sc(colW[ci]) else colW[ci]
            for (k in grp.indices) {
                val s = grp[k]
                val hh = if (f < 1.0) sc(heights[ci][k]) else heights[ci][k]
                val bmp = loadFull(s)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, Rect(x, y, x + cw, y + hh), paint)
                    if (s.bmp == null) bmp.recycle()
                }
                y += hh + (if (f < 1.0) sc(g) else g)
            }
            x += cw + (if (f < 1.0) sc(g) else g)
        }
        return out
    }

    private fun doPreview() {
        Toast.makeText(act, "Building preview...", Toast.LENGTH_SHORT).show()
        Thread(Runnable {
            var result: Bitmap? = null
            var err: String? = null
            try {
                val full = compose()
                val m = maxOf(full.width, full.height)
                result = if (m > 1600) {
                    val k = 1600.0 / m.toDouble()
                    Bitmap.createScaledBitmap(full,
                        maxOf(1, (full.width * k).toInt()), maxOf(1, (full.height * k).toInt()), true)
                } else full
            } catch (t: Throwable) {
                err = t.message ?: "error"
            }
            val r = result
            val e = err
            act.runOnUiThread {
                if (r != null) {
                    val d = Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen)
                    val iv = ImageView(act)
                    iv.setBackgroundColor(Color.parseColor("#111122"))
                    iv.setImageBitmap(r)
                    iv.setOnClickListener { d.dismiss() }
                    d.setContentView(iv)
                    d.show()
                } else {
                    Toast.makeText(act, "Preview failed: " + (e ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }).start()
    }

    private fun doRecreate() {
        Toast.makeText(act, "Recreating...", Toast.LENGTH_SHORT).show()
        Thread(Runnable {
            var result: Bitmap? = null
            var err: String? = null
            try {
                result = compose()
            } catch (t: Throwable) {
                err = t.message ?: "error"
            }
            val r = result
            val e = err
            act.runOnUiThread {
                if (r != null) {
                    dlg.dismiss()
                    onDone(r)
                } else {
                    Toast.makeText(act, "Recreate failed: " + (e ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }).start()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main screen
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val REQ_OPEN = 1
    private val REQ_FOLDER = 2
    private val REQ_RECREATE = 3

    private lateinit var sliceView: SliceView
    private lateinit var status: TextView

    private lateinit var btnSquare: Button
    private lateinit var btnVert: Button
    private lateinit var btnHoriz: Button
    private lateinit var btnErase: Button
    private lateinit var btnVoid: Button

    private val items = ArrayList<WorkItem>()
    private var cur = -1
    private var loadToken = 0

    // Next file number (1, 2, 3 ...). Continues across ALL images / PDF pages.
    private var nextNumber = 1
    private val saved = HashMap<WorkItem, ArrayList<SavedRec>>()

    private var outTree: Uri? = null
    private var pendingSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#1a1a2e"))

        btnSquare = makeButton("Square") { setMode(Mode.SQUARE) }
        btnVert = makeButton("Vertical") { setMode(Mode.VERTICAL) }
        btnHoriz = makeButton("Horizontal") { setMode(Mode.HORIZONTAL) }
        btnErase = makeButton("Eraser") { setMode(Mode.ERASER) }
        btnVoid = makeButton("Void") { sliceView.toggleVoid(); refreshModeButtons() }

        addRow(root,
            makeButton("Open") { pickFiles() },
            makeButton("Folder") { pickFolder() },
            makeButton("< Prev") { gotoItem(cur - 1) },
            makeButton("Next >") { gotoItem(cur + 1) },
            makeButton("Go to") { askGoTo() })
        addRow(root, btnSquare, btnVert, btnHoriz, btnErase, btnVoid)
        addRow(root,
            makeButton("Undo") { sliceView.undo() },
            makeButton("Clear") { sliceView.removeAll() },
            makeButton("Save") { doSave() },
            makeButton("Reset") { doReset() },
            makeButton("Recreate") { openRecreate() })
        addRow(root,
            makeButton("Rot L") { rotate(-90f) },
            makeButton("Rot R") { rotate(90f) },
            makeButton("Flip H") { flip(true) },
            makeButton("Flip V") { flip(false) })

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        val pad = (6 * density).toInt()
        status.setPadding(pad, pad / 2, pad, pad / 2)

        sliceView = SliceView(this)
        sliceView.onChanged = { updateStatus() }

        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(sliceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // restore remembered output folder
        val s = getSharedPreferences("slicer", MODE_PRIVATE).getString("out_tree", null)
        if (s != null) {
            val u = Uri.parse(s)
            var ok = false
            for (p in contentResolver.persistedUriPermissions) {
                if (p.uri == u && p.isWritePermission) ok = true
            }
            if (ok) {
                outTree = u
                syncCounter()
            }
        }
        refreshModeButtons()
        updateStatus()
    }

    // ── small UI helpers ──
    private fun makeButton(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(2, 12, 2, 12)
        b.setOnClickListener { action() }
        return b
    }

    private fun addRow(root: LinearLayout, vararg btns: Button) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (b in btns) {
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setMode(m: Mode) {
        sliceView.mode = m
        refreshModeButtons()
    }

    private fun refreshModeButtons() {
        val on = Color.parseColor("#f7b731")
        val off = Color.WHITE
        btnSquare.setTextColor(if (sliceView.mode == Mode.SQUARE) on else off)
        btnVert.setTextColor(if (sliceView.mode == Mode.VERTICAL) on else off)
        btnHoriz.setTextColor(if (sliceView.mode == Mode.HORIZONTAL) on else off)
        btnErase.setTextColor(if (sliceView.mode == Mode.ERASER) on else off)
        btnVoid.setTextColor(if (sliceView.voidActive) on else off)
    }

    private fun modeHint(): String {
        return when (sliceView.mode) {
            Mode.SQUARE -> "SQUARE: drag a box for each slice, then Save"
            Mode.VERTICAL -> "VERTICAL: drag/tap to place vertical lines"
            Mode.HORIZONTAL -> "HORIZONTAL: draw lines between two verticals; each new line cuts a slice"
            Mode.ERASER -> "ERASER: drag a box to erase it (white); Save stores the erased page"
        }
    }

    private fun folderName(): String {
        val t = outTree ?: return "(not chosen - tap Folder)"
        try {
            val id = DocumentsContract.getTreeDocumentId(t)
            val afterColon = if (id.contains(":")) id.substring(id.indexOf(':') + 1) else id
            return if (afterColon.isEmpty()) "(root)" else afterColon
        } catch (e: Throwable) {
            return t.toString()
        }
    }

    private fun updateStatus() {
        val snap = sliceView.snapshot()
        val sb = StringBuilder()
        if (cur < 0 || snap == null) {
            sb.append("Tap Open to choose images / PDFs (you can select many)")
        } else {
            sb.append("[").append(cur + 1).append("/").append(items.size).append("] ")
            sb.append(items[cur].label)
            sb.append(" | ").append(snap.full.width).append("x").append(snap.full.height)
            sb.append("\n")
            sb.append("pending: ").append(snap.slices.size)
            sb.append(" | next file #").append(nextNumber)
            sb.append(" | ").append(if (sliceView.hint.isNotEmpty()) sliceView.hint else modeHint())
        }
        sb.append("\nOutput folder: ").append(folderName())
        status.text = sb.toString()
        btnVoid.setTextColor(if (sliceView.voidActive) Color.parseColor("#f7b731") else Color.WHITE)
    }

    private fun applyBase() {
        sliceView.baseNumber = nextNumber
        sliceView.invalidate()
        updateStatus()
    }

    // ── open files (multi-select images + PDFs) ──
    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, REQ_FOLDER)
    }

    private fun pickRecreateFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, REQ_RECREATE)
    }

    private fun urisFrom(data: Intent): ArrayList<Uri> {
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
            }
        } else {
            val u = data.data
            if (u != null) uris.add(u)
        }
        return uris
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_FOLDER) pendingSave = false
            return
        }
        if (requestCode == REQ_OPEN) {
            handleOpen(urisFrom(data))
        } else if (requestCode == REQ_FOLDER) {
            val u = data.data
            if (u != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (t: Throwable) {
                }
                outTree = u
                getSharedPreferences("slicer", MODE_PRIVATE).edit().putString("out_tree", u.toString()).apply()
                nextNumber = 1
                applyBase()
                syncCounter()
                if (pendingSave) {
                    pendingSave = false
                    doSave()
                }
            }
        } else if (requestCode == REQ_RECREATE) {
            handleRecreatePick(urisFrom(data))
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "image"
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (col >= 0) {
                            val n = cursor.getString(col)
                            if (n != null) name = n
                        }
                    }
                } finally {
                    cursor.close()
                }
            }
        } catch (t: Throwable) {
        }
        return name
    }

    private fun handleOpen(uris: ArrayList<Uri>) {
        if (uris.isEmpty()) return
        status.text = "Reading files..."
        Thread(Runnable {
            val list = ArrayList<WorkItem>()
            for (u in uris) {
                val name = queryName(u)
                val mime = contentResolver.getType(u) ?: ""
                val lower = name.lowercase()
                val dot = name.lastIndexOf('.')
                val base = (if (dot > 0) name.substring(0, dot) else name).replace('/', '_')
                if (mime == "application/pdf" || lower.endsWith(".pdf")) {
                    try {
                        val pfd = contentResolver.openFileDescriptor(u, "r")
                        if (pfd != null) {
                            try {
                                val r = PdfRenderer(pfd)
                                val n = r.pageCount
                                r.close()
                                for (p in 0 until n) {
                                    list.add(WorkItem(u, true, p,
                                        base + " p" + (p + 1).toString() + "/" + n.toString(), false, null))
                                }
                            } finally {
                                pfd.close()
                            }
                        }
                    } catch (t: Throwable) {
                    }
                } else {
                    val ext = if (dot > 0) lower.substring(dot + 1) else ""
                    val jpeg = ext == "jpg" || ext == "jpeg" || mime == "image/jpeg"
                    list.add(WorkItem(u, false, 0, base, jpeg, null))
                }
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    toast("No readable images / PDFs selected")
                    updateStatus()
                } else {
                    items.clear()
                    items.addAll(list)
                    showItem(0)
                    if (outTree == null) {
                        toast("Now choose the output folder for the slices")
                        pickFolder()
                    }
                }
            }
        }).start()
    }

    // ── loading pages ──
    private fun decodeImage(uri: Uri): Bitmap? {
        try {
            val stream = contentResolver.openInputStream(uri) ?: return null
            try {
                val o = BitmapFactory.Options()
                o.inMutable = true
                return BitmapFactory.decodeStream(stream, null, o)
            } finally {
                stream.close()
            }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun renderPdfPage(uri: Uri, page: Int): Bitmap? {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
            try {
                val r = PdfRenderer(pfd)
                try {
                    val p = r.openPage(page)
                    try {
                        val scale = 150f / 72f
                        var w = (p.width * scale).toInt()
                        var h = (p.height * scale).toInt()
                        val m = maxOf(w, h)
                        if (m > 4096) {
                            val k = 4096f / m.toFloat()
                            w = (w * k).toInt()
                            h = (h * k).toInt()
                        }
                        if (w < 1) w = 1
                        if (h < 1) h = 1
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    } finally {
                        p.close()
                    }
                } finally {
                    r.close()
                }
            } finally {
                pfd.close()
            }
        } catch (t: Throwable) {
            return null
        }
    }

    // Fresh copy of the ORIGINAL page (used for opening and for Reset).
    private fun loadItemBitmap(item: WorkItem): Bitmap? {
        val m = item.mem
        if (m != null) {
            return m.copy(Bitmap.Config.ARGB_8888, true)
        }
        val u = item.uri ?: return null
        return if (item.isPdf) renderPdfPage(u, item.page) else decodeImage(u)
    }

    private fun showItem(i: Int) {
        if (i < 0 || i >= items.size) return
        cur = i
        loadToken++
        val token = loadToken
        val item = items[i]
        status.text = "Loading " + item.label + "..."
        Thread(Runnable {
            val bmp = loadItemBitmap(item)
            runOnUiThread {
                if (token == loadToken) {
                    if (bmp == null) {
                        toast("Could not open " + item.label)
                        updateStatus()
                    } else {
                        sliceView.baseNumber = nextNumber
                        sliceView.setImage(bmp)
                        refreshModeButtons()
                    }
                }
            }
        }).start()
    }

    private fun gotoItem(i: Int) {
        if (items.isEmpty()) {
            toast("Open images / PDFs first")
            return
        }
        if (i < 0 || i >= items.size) {
            toast(if (i < 0) "This is the first page" else "This is the last page")
            return
        }
        val pending = sliceView.slices.size
        if (pending > 0) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved slices")
                .setMessage("You have " + pending.toString() + " unsaved slice(s). Discard them and continue?")
                .setPositiveButton("Discard") { _, _ -> showItem(i) }
                .setNegativeButton("Stay", null)
                .show()
        } else {
            showItem(i)
        }
    }

    private fun askGoTo() {
        if (items.isEmpty()) {
            toast("Open images / PDFs first")
            return
        }
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.hint = "1 - " + items.size.toString()
        AlertDialog.Builder(this)
            .setTitle("Go to page")
            .setView(et)
            .setPositiveButton("Go") { _, _ ->
                val n = et.text.toString().trim().toIntOrNull()
                if (n == null || n < 1 || n > items.size) {
                    toast("Enter a number from 1 to " + items.size.toString())
                } else {
                    gotoItem(n - 1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── rotate / flip ──
    private fun rotate(angle: Float) {
        if (!sliceView.hasImage()) { toast("Open an image first"); return }
        val m = Matrix()
        m.postRotate(angle)
        sliceView.transform(m)
    }

    private fun flip(horizontal: Boolean) {
        if (!sliceView.hasImage()) { toast("Open an image first"); return }
        val m = Matrix()
        if (horizontal) m.postScale(-1f, 1f) else m.postScale(1f, -1f)
        sliceView.transform(m)
    }

    // ── output folder (Storage Access Framework) ──
    private fun listChildren(): ArrayList<Array<String>> {
        val out = ArrayList<Array<String>>()
        val tree = outTree ?: return out
        try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val c = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        out.add(arrayOf(c.getString(0), c.getString(1)))
                    }
                } finally {
                    c.close()
                }
            }
        } catch (t: Throwable) {
        }
        return out
    }

    // Continue numbering after the highest existing "N.ext" file already in the folder (never overwrite old work).
    private fun syncCounter() {
        if (outTree == null) return
        Thread(Runnable {
            var mx = 0
            val re = Regex("^(\\d+)\\.[A-Za-z0-9]+\$")
            for (c in listChildren()) {
                val m = re.find(c[1])
                if (m != null) {
                    val n = m.groupValues[1].toIntOrNull()
                    if (n != null && n > mx) mx = n
                }
            }
            val best = mx
            runOnUiThread {
                if (best + 1 > nextNumber) nextNumber = best + 1
                applyBase()
            }
        }).start()
    }

    private fun saveToTree(bmp: Bitmap, name: String, jpeg: Boolean, existing: Map<String, String>): Uri? {
        val tree = outTree ?: return null
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val oldId = existing[name]
            if (oldId != null) {
                try {
                    DocumentsContract.deleteDocument(
                        contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, oldId))
                } catch (t: Throwable) {
                }
            }
            val mime = if (jpeg) "image/jpeg" else "image/png"
            val doc = DocumentsContract.createDocument(contentResolver, parent, mime, name) ?: return null
            try {
                val os = contentResolver.openOutputStream(doc) ?: throw IOException("No output stream")
                val format = if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                val ok: Boolean
                try {
                    ok = bmp.compress(format, 100, os)
                } finally {
                    os.close()
                }
                if (!ok) throw IOException("Compress failed")
                return doc
            } catch (t: Throwable) {
                try {
                    DocumentsContract.deleteDocument(contentResolver, doc)
                } catch (t2: Throwable) {
                }
                return null
            }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun existingMap(): Map<String, String> {
        val m = HashMap<String, String>()
        for (c in listChildren()) {
            m[c[1]] = c[0]
        }
        return m
    }

    // ── Save ──
    private fun doSave() {
        val snap = sliceView.snapshot()
        if (snap == null || cur < 0) {
            toast("Open an image first")
            return
        }
        if (outTree == null) {
            pendingSave = true
            toast("Choose the output folder first")
            pickFolder()
            return
        }
        val item = items[cur]
        val jpeg = item.isJpeg

        // ERASER mode: save the erased page
        if (sliceView.mode == Mode.ERASER) {
            toast("Saving erased image...")
            Thread(Runnable {
                val safe = item.label.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val name = safe + "_erased" + (if (jpeg) ".jpg" else ".png")
                val uri = saveToTree(snap.full, name, jpeg, existingMap())
                runOnUiThread {
                    if (uri != null) {
                        var l = saved[item]
                        if (l == null) {
                            l = ArrayList<SavedRec>()
                            saved[item] = l
                        }
                        l.add(SavedRec(uri, -1))
                        toast("Saved " + name)
                    } else {
                        toast("Save failed")
                    }
                }
            }).start()
            return
        }

        if (snap.slices.isEmpty()) {
            toast("Draw at least one slice first")
            return
        }

        // numbers are reserved now: 1,2,3 ... continuing over all pages
        val startNum = nextNumber
        val count = snap.slices.size
        nextNumber += count
        sliceView.baseNumber = nextNumber
        sliceView.clearSlices()
        toast("Saving " + count.toString() + " slice(s)...")

        Thread(Runnable {
            val existing = existingMap()
            val done = ArrayList<SavedRec>()
            for (i in 0 until count) {
                val crop = makeCrop(snap, i, jpeg)
                if (crop != null) {
                    val num = startNum + i
                    val fileName = num.toString() + (if (jpeg) ".jpg" else ".png")
                    val uri = saveToTree(crop, fileName, jpeg, existing)
                    if (uri != null) done.add(SavedRec(uri, num))
                    crop.recycle()
                }
            }
            runOnUiThread {
                var l = saved[item]
                if (l == null) {
                    l = ArrayList<SavedRec>()
                    saved[item] = l
                }
                l.addAll(done)
                toast("Saved " + done.size.toString() + "/" + count.toString() + " to " + folderName())
                updateStatus()
            }
        }).start()
    }

    // ── Reset: delete this page's saved slices + restore the original page ──
    private fun doReset() {
        if (cur < 0 || cur >= items.size) {
            toast("Open an image first")
            return
        }
        val item = items[cur]
        val recs = saved[item]
        val n = if (recs == null) 0 else recs.size
        AlertDialog.Builder(this)
            .setTitle("PAGE RESET - are you sure?")
            .setMessage(
                "This will:\n\n" +
                    "1. Restore this page to its ORIGINAL state\n" +
                    "2. Delete the " + n.toString() + " file(s) saved from this page in the output folder\n" +
                    "3. Clear every line, slice and erase\n\n" +
                    "This cannot be undone."
            )
            .setPositiveButton("Reset") { _, _ -> performReset(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReset(item: WorkItem) {
        val recs = saved.remove(item)
        if (recs != null && recs.size > 0) {
            var minN = Int.MAX_VALUE
            var maxN = -1
            for (r in recs) {
                if (r.number >= 0) {
                    if (r.number < minN) minN = r.number
                    if (r.number > maxN) maxN = r.number
                }
            }
            // if this page holds the latest numbers, free them again so numbering stays 1,2,3...
            if (maxN >= 0 && maxN == nextNumber - 1) {
                nextNumber = minN
            }
        }
        applyBase()
        status.text = "Resetting..."
        Thread(Runnable {
            var deleted = 0
            if (recs != null) {
                for (r in recs) {
                    try {
                        if (DocumentsContract.deleteDocument(contentResolver, r.uri)) deleted++
                    } catch (t: Throwable) {
                    }
                }
            }
            val bmp = loadItemBitmap(item)
            runOnUiThread {
                if (bmp != null && cur >= 0 && cur < items.size && items[cur] === item) {
                    sliceView.baseNumber = nextNumber
                    sliceView.setImage(bmp)
                }
                refreshModeButtons()
                updateStatus()
                toast("Page reset - " + deleted.toString() + " file(s) deleted")
            }
        }).start()
    }

    // ── Recreate ──
    private fun openRecreate() {
        val snap = sliceView.snapshot()
        if (snap != null && snap.slices.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Recreate - choose source")
                .setMessage("You have " + snap.slices.size.toString() + " pending slice(s).")
                .setPositiveButton("Use slices") { _, _ -> recreateFromSlices(snap) }
                .setNegativeButton("Pick files") { _, _ -> pickRecreateFiles() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            pickRecreateFiles()
        }
    }

    private fun makeThumb(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 200) return b
        val k = 200.0 / m.toDouble()
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    private fun recreateFromSlices(snap: Snapshot) {
        toast("Preparing slices...")
        Thread(Runnable {
            val srcs = ArrayList<RecSrc>()
            for (i in snap.slices.indices) {
                val crop = makeCrop(snap, i, true)
                if (crop != null) {
                    srcs.add(RecSrc(crop, null, makeThumb(crop), "slice " + (i + 1).toString()))
                }
            }
            runOnUiThread { showRecreate(srcs, true) }
        }).start()
    }

    private fun decodeThumb(uri: Uri, target: Int): Bitmap? {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            val s1 = contentResolver.openInputStream(uri) ?: return null
            try { BitmapFactory.decodeStream(s1, null, o) } finally { s1.close() }
            var sample = 1
            val mx = maxOf(o.outWidth, o.outHeight)
            while (mx / sample > target * 2) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            val s2 = contentResolver.openInputStream(uri) ?: return null
            try {
                return BitmapFactory.decodeStream(s2, null, o2)
            } finally {
                s2.close()
            }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun handleRecreatePick(uris: ArrayList<Uri>) {
        if (uris.isEmpty()) return
        toast("Loading images...")
        Thread(Runnable {
            val srcs = ArrayList<RecSrc>()
            for (u in uris) {
                val t = decodeThumb(u, 200)
                if (t != null) srcs.add(RecSrc(null, u, t, queryName(u)))
            }
            runOnUiThread { showRecreate(srcs, false) }
        }).start()
    }

    private fun showRecreate(srcs: ArrayList<RecSrc>, fromSlices: Boolean) {
        if (srcs.isEmpty()) {
            toast("No valid images could be loaded")
            return
        }
        val parentLabel = if (cur >= 0 && cur < items.size) items[cur].label else "recreated"
        val parentJpeg = if (cur >= 0 && cur < items.size) items[cur].isJpeg else false
        val ui = RecreateUi(this, srcs) { composed ->
            // insert the recreated image as a new page right after the current one and open it
            val item = WorkItem(null, false, 0, parentLabel + "_recreated", parentJpeg, composed)
            val pos = if (cur >= 0 && cur < items.size) cur + 1 else items.size
            items.add(pos, item)
            showItem(pos)
            toast("Recreated image opened as page " + (pos + 1).toString())
        }
        ui.show()
    }
}
