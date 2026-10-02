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
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import android.os.ParcelFileDescriptor

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
class RecSrc(val bmp: Bitmap?, val uri: Uri?, val thumb: Bitmap?, val name: String, val w: Int, val h: Int)

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
// The drawing view: SQUARE / VERTICAL / HORIZONTAL / ERASER modes + zoom / pan
// ─────────────────────────────────────────────────────────────────────────────
class SliceView(context: Context) : View(context) {

    private var full: Bitmap? = null
    private var display: Bitmap? = null
    private var offX = 0f
    private var offY = 0f
    private var editVer = 0

    // zoom / pan (view = (content + off) * zoom + pan)
    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    var moveMode = false
    private var gesturing = false
    private var twoFinger = false
    private var midPan = false
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDist = 0f
    private var hoverX = -1f
    private var hoverY = -1f
    private var zoomCache: Bitmap? = null
    private var zoomKey = ""

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
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hoverPaint = Paint()
    private val barTrackPaint = Paint()
    private val barThumbPaint = Paint()
    private val barT = 14f * context.resources.displayMetrics.density
    private val barHit = barT
    private val barMinThumb = 40f * context.resources.displayMetrics.density
    private var sbMode = 0
    private var sbGrab = 0f

    init {
        isFocusable = false

        linePaint.color = Color.parseColor("#f7b731")
        linePaint.style = Paint.Style.STROKE
        linePaint.isAntiAlias = false

        dashPaint.color = Color.parseColor("#f7b731")
        dashPaint.style = Paint.Style.STROKE
        dashPaint.isAntiAlias = false

        redPaint.color = Color.RED
        redPaint.style = Paint.Style.STROKE

        grayPaint.color = Color.parseColor("#888888")
        grayPaint.style = Paint.Style.STROKE

        cyanPaint.color = Color.CYAN
        cyanPaint.style = Paint.Style.STROKE

        erasePaint.color = Color.parseColor("#ff6b6b")
        erasePaint.style = Paint.Style.STROKE

        badgeBg.color = Color.parseColor("#f7b731")
        badgeBg.style = Paint.Style.FILL
        badgeBg.isAntiAlias = true

        badgeText.color = Color.BLACK
        badgeText.textAlign = Paint.Align.CENTER
        badgeText.isFakeBoldText = true
        badgeText.isAntiAlias = true

        hoverPaint.color = Color.parseColor("#8800ffff")
        hoverPaint.style = Paint.Style.STROKE
        hoverPaint.strokeWidth = 1f

        barTrackPaint.color = Color.parseColor("#66000000")
        barTrackPaint.style = Paint.Style.FILL
        barThumbPaint.color = Color.parseColor("#ccdddddd")
        barThumbPaint.style = Paint.Style.FILL
        barThumbPaint.isAntiAlias = true
    }

    // ── scroll bars (visible only while zoomed in) ──
    private fun hRange(): Float {
        val d = display ?: return 0f
        if (!hVisible()) return 0f
        return d.width * zoom + (if (vVisible()) barT else 0f) - width
    }

    private fun vRange(): Float {
        val d = display ?: return 0f
        if (!vVisible()) return 0f
        return d.height * zoom + (if (hVisible()) barT else 0f) - height
    }

    // returns [thumbStart, thumbLength, trackLength] or null when there is nothing to scroll
    private fun hThumb(): FloatArray? {
        val d = display ?: return null
        val r = hRange()
        if (r <= 0f) return null
        val track = width - (if (vRange() > 0f) barT else 0f)
        var len = track * width / (d.width * zoom + (if (vVisible()) barT else 0f))
        if (len < barMinThumb) len = barMinThumb
        if (len > track) len = track
        val sc = -(offX * zoom + panX)
        val f = if (sc / r < 0f) 0f else if (sc / r > 1f) 1f else sc / r
        return floatArrayOf(f * (track - len), len, track)
    }

    private fun vThumb(): FloatArray? {
        val d = display ?: return null
        val r = vRange()
        if (r <= 0f) return null
        val track = height - (if (hRange() > 0f) barT else 0f)
        var len = track * height / (d.height * zoom + (if (hVisible()) barT else 0f))
        if (len < barMinThumb) len = barMinThumb
        if (len > track) len = track
        val sc = -(offY * zoom + panY)
        val f = if (sc / r < 0f) 0f else if (sc / r > 1f) 1f else sc / r
        return floatArrayOf(f * (track - len), len, track)
    }

    private fun setScrollH(x: Float) {
        val ht = hThumb() ?: return
        val span = ht[2] - ht[1]
        if (span <= 0f) return
        var f = (x - sbGrab) / span
        f = if (f < 0f) 0f else if (f > 1f) 1f else f
        panX = -(f * hRange()) - offX * zoom
        clampPan()
        invalidate()
    }

    private fun setScrollV(y: Float) {
        val vt = vThumb() ?: return
        val span = vt[2] - vt[1]
        if (span <= 0f) return
        var f = (y - sbGrab) / span
        f = if (f < 0f) 0f else if (f > 1f) 1f else f
        panY = -(f * vRange()) - offY * zoom
        clampPan()
        invalidate()
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

    private fun resetView() {
        zoom = 1f
        panX = 0f
        panY = 0f
        zoomCache = null
        zoomKey = ""
        editVer++
    }

    fun setImage(b: Bitmap) {
        full = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        display = null
        clearEverything()
        resetView()
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
        resetView()
        rebuildDisplay()
        invalidate()
        onChanged?.invoke()
    }

    // ── zoom / pan ──
    private fun hVisible(): Boolean {
        val d = display ?: return false
        return d.width * zoom - width > 1f
    }

    private fun vVisible(): Boolean {
        val d = display ?: return false
        return d.height * zoom - height > 1f
    }

    // The scroll bars sit on top of the picture, so the scroll range gets extra room (barT) and
    // the last strip of the image can always be brought out from under the bars.
    private fun clampPan() {
        val d = display ?: return
        val dwz = d.width * zoom
        val dhz = d.height * zoom
        if (hVisible()) {
            val extra = if (vVisible()) barT else 0f
            val minPan = width - (offX + d.width) * zoom - extra
            val maxPan = -offX * zoom
            panX = if (panX < minPan) minPan else if (panX > maxPan) maxPan else panX
        } else {
            panX = (width - dwz) / 2f - offX * zoom
        }
        if (vVisible()) {
            val extra = if (hVisible()) barT else 0f
            val minPan = height - (offY + d.height) * zoom - extra
            val maxPan = -offY * zoom
            panY = if (panY < minPan) minPan else if (panY > maxPan) maxPan else panY
        } else {
            panY = (height - dhz) / 2f - offY * zoom
        }
    }

    fun zoomAt(nz: Float, fx: Float, fy: Float) {
        if (display == null) return
        val z = if (nz < 0.1f) 0.1f else if (nz > 8f) 8f else nz
        val u = (fx - panX) / zoom
        val v = (fy - panY) / zoom
        zoom = z
        panX = fx - u * zoom
        panY = fy - v * zoom
        clampPan()
        invalidate()
        onChanged?.invoke()
    }

    fun zoomBy(f: Float) {
        zoomAt(zoom * f, width / 2f, height / 2f)
    }

    fun zoomFit() {
        zoom = 1f
        panX = 0f
        panY = 0f
        clampPan()
        invalidate()
        onChanged?.invoke()
    }

    fun panBy(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        clampPan()
        invalidate()
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
        clampPan()
    }

    // Draws the picture itself. When zoomed in, the visible part is taken from the FULL-resolution image (sharp).
    private fun drawImageLayer(canvas: Canvas, d: Bitmap) {
        val z = zoom
        val f = full
        if (z <= 1f || f == null) {
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(z, z)
            canvas.drawBitmap(d, offX, offY, null)
            canvas.restore()
            return
        }
        val cx0 = maxOf(0f, (0f - panX) / z - offX)
        val cy0 = maxOf(0f, (0f - panY) / z - offY)
        val cx1 = minOf(d.width.toFloat(), (width - panX) / z - offX)
        val cy1 = minOf(d.height.toFloat(), (height - panY) / z - offY)
        if (cx1 <= cx0 || cy1 <= cy0) return
        val sx = f.width.toFloat() / d.width.toFloat()
        val sy = f.height.toFloat() / d.height.toFloat()
        val sl = clampInt((cx0 * sx).toInt(), 0, f.width - 1)
        val st = clampInt((cy0 * sy).toInt(), 0, f.height - 1)
        val sr = clampInt(Math.ceil((cx1 * sx).toDouble()).toInt(), sl + 1, f.width)
        val sb = clampInt(Math.ceil((cy1 * sy).toDouble()).toInt(), st + 1, f.height)
        val src = Rect(sl, st, sr, sb)
        val dst = RectF(
            (sl / sx + offX) * z + panX, (st / sy + offY) * z + panY,
            (sr / sx + offX) * z + panX, (sb / sy + offY) * z + panY
        )
        if (maxOf(f.width, f.height) <= 4096) {
            canvas.drawBitmap(f, src, dst, imgPaint)
        } else if (gesturing) {
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(z, z)
            canvas.drawBitmap(d, offX, offY, null)
            canvas.restore()
        } else {
            val w = maxOf(1, Math.round(dst.width()))
            val h = maxOf(1, Math.round(dst.height()))
            val key = "" + sl + "_" + st + "_" + sr + "_" + sb + "_" + w + "_" + h + "_" + editVer
            var c = zoomCache
            if (c == null || zoomKey != key) {
                val nc = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(nc).drawBitmap(f, src, Rect(0, 0, w, h), imgPaint)
                zoomCache = nc
                zoomKey = key
                c = nc
            }
            canvas.drawBitmap(c, dst.left, dst.top, null)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#111122"))
        val d = display ?: return
        drawImageLayer(canvas, d)

        val z = zoom
        val inv = 1f / z
        linePaint.strokeWidth = inv
        dashPaint.strokeWidth = inv
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(4f * inv, 4f * inv), 0f)
        redPaint.strokeWidth = 1.5f * inv
        redPaint.pathEffect = DashPathEffect(floatArrayOf(6f * inv, 6f * inv), 0f)
        grayPaint.strokeWidth = 1.5f * inv
        grayPaint.pathEffect = DashPathEffect(floatArrayOf(10f * inv, 10f * inv), 0f)
        cyanPaint.strokeWidth = 1.5f * inv
        erasePaint.strokeWidth = 1.5f * inv
        erasePaint.pathEffect = DashPathEffect(floatArrayOf(4f * inv, 4f * inv), 0f)

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(z, z)

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
            val radius = maxOf(10f * density * inv, minOf(22f * density * inv, (r.right - r.left) / 5f))
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
        canvas.restore()

        // mouse crosshair (like the Python app)
        if (hoverX >= 0f && !gesturing) {
            canvas.drawLine(hoverX, 0f, hoverX, height.toFloat(), hoverPaint)
            canvas.drawLine(0f, hoverY, width.toFloat(), hoverY, hoverPaint)
        }

        // scroll bars
        val ht = hThumb()
        if (ht != null) {
            val top = height - barT
            canvas.drawRect(0f, top, ht[2], height.toFloat(), barTrackPaint)
            canvas.drawRoundRect(RectF(ht[0], top + 1f, ht[0] + ht[1], height - 1f), barT / 2f, barT / 2f, barThumbPaint)
        }
        val vt = vThumb()
        if (vt != null) {
            val left = width - barT
            canvas.drawRect(left, 0f, width.toFloat(), vt[2], barTrackPaint)
            canvas.drawRoundRect(RectF(left + 1f, vt[0], width - 1f, vt[0] + vt[1]), barT / 2f, barT / 2f, barThumbPaint)
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
            editVer++
            hint = "Region erased - draw another, or Save to store the erased page"
        }
    }

    private fun toCx(vx: Float, d: Bitmap): Int {
        return clampInt(((vx - panX) / zoom - offX).toInt(), 0, d.width)
    }

    private fun toCy(vy: Float, d: Bitmap): Int {
        return clampInt(((vy - panY) / zoom - offY).toInt(), 0, d.height)
    }

    private fun dist2(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    // Mouse: hover shows a crosshair
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = event.x
                hoverY = event.y
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverX = -1f
                hoverY = -1f
                invalidate()
            }
        }
        return true
    }

    // Mouse wheel: scroll, Shift+wheel: sideways, Ctrl+wheel: zoom
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val ctrl = (event.metaState and KeyEvent.META_CTRL_ON) != 0
            val shift = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
            val step = 60f * density
            if (ctrl) {
                if (v > 0f) zoomAt(zoom * 1.15f, event.x, event.y)
                else if (v < 0f) zoomAt(zoom / 1.15f, event.x, event.y)
            } else if (shift) {
                panBy(v * step, 0f)
            } else {
                panBy(h * step, v * step)
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = display ?: return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hoverX = -1f
                twoFinger = false
                sbMode = 0
                val htb = hThumb()
                val vtb = vThumb()
                if (htb != null && e.y >= height - barHit && e.x <= htb[2]) {
                    sbMode = 1
                    sbGrab = if (e.x >= htb[0] && e.x <= htb[0] + htb[1]) e.x - htb[0] else htb[1] / 2f
                    setScrollH(e.x)
                    return true
                }
                if (vtb != null && e.x >= width - barHit && e.y <= vtb[2]) {
                    sbMode = 2
                    sbGrab = if (e.y >= vtb[0] && e.y <= vtb[0] + vtb[1]) e.y - vtb[0] else vtb[1] / 2f
                    setScrollV(e.y)
                    return true
                }
                if (moveMode || (e.buttonState and MotionEvent.BUTTON_TERTIARY) != 0) {
                    // middle mouse button drags the picture
                    midPan = true
                    gesturing = true
                    lastMidX = e.x
                    lastMidY = e.y
                    return true
                }
                midPan = false
                dragging = true
                startX = toCx(e.x, d)
                startY = toCy(e.y, d)
                curX = startX
                curY = startY
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2 && sbMode == 0) {
                    dragging = false
                    twoFinger = true
                    gesturing = true
                    lastDist = dist2(e)
                    lastMidX = (e.getX(0) + e.getX(1)) / 2f
                    lastMidY = (e.getY(0) + e.getY(1)) / 2f
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (sbMode == 1) {
                    setScrollH(e.x)
                    return true
                }
                if (sbMode == 2) {
                    setScrollV(e.y)
                    return true
                }
                if (midPan) {
                    panBy(e.x - lastMidX, e.y - lastMidY)
                    lastMidX = e.x
                    lastMidY = e.y
                } else if (twoFinger) {
                    if (e.pointerCount >= 2) {
                        val nd = dist2(e)
                        val mx = (e.getX(0) + e.getX(1)) / 2f
                        val my = (e.getY(0) + e.getY(1)) / 2f
                        if (lastDist > 10f && nd > 10f) {
                            val u = (lastMidX - panX) / zoom
                            val v = (lastMidY - panY) / zoom
                            var nz = zoom * nd / lastDist
                            nz = if (nz < 0.1f) 0.1f else if (nz > 8f) 8f else nz
                            zoom = nz
                            panX = mx - u * zoom
                            panY = my - v * zoom
                            clampPan()
                            invalidate()
                            onChanged?.invoke()
                        }
                        lastDist = nd
                        lastMidX = mx
                        lastMidY = my
                    }
                } else if (dragging) {
                    curX = toCx(e.x, d)
                    curY = toCy(e.y, d)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastDist = 0f
            }
            MotionEvent.ACTION_UP -> {
                if (sbMode != 0) {
                    sbMode = 0
                    return true
                }
                if (midPan || twoFinger) {
                    midPan = false
                    twoFinger = false
                    gesturing = false
                    invalidate()
                    return true
                }
                if (dragging) {
                    dragging = false
                    val x = toCx(e.x, d)
                    val y = toCy(e.y, d)
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
                sbMode = 0
                dragging = false
                midPan = false
                twoFinger = false
                gesturing = false
                invalidate()
            }
        }
        return true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Recreate window - same layout logic as RecreateLayoutWindow in the Python app:
// up to 20 columns, choose how many images go in each column, move images between
// columns, reorder, remove, gap, background, quick layouts, preview, recreate.
// ─────────────────────────────────────────────────────────────────────────────
class RecreateUi(
    private val act: Activity,
    private val srcs: ArrayList<RecSrc>,
    private val onDone: (Bitmap) -> Unit
) {
    private class Quick(val label: String, val color: String, val cols: Int, val sizes: IntArray?)

    private val maxCols = 20
    private val dlg = Dialog(act, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    private val n = srcs.size
    private val columns = ArrayList<ArrayList<Int>>()
    private var numCols = 1
    private var gap = 8
    private var rowsPerGroup = 7
    private var black = false

    private val colColors = arrayOf(
        "#4ecdc4", "#f7b731", "#ff6b6b", "#a29bfe", "#fd79a8", "#55efc4", "#fdcb6e",
        "#74b9ff", "#e17055", "#00cec9", "#6c5ce7", "#00b894", "#d63031", "#0984e3",
        "#e84393", "#b2bec3", "#fab1a0", "#81ecec", "#dfe6e9", "#636e72"
    )
    private val colBgs = arrayOf(
        "#0d2233", "#1a2a0d", "#2a1a0d", "#1a0d2a", "#2a0d1a", "#0d2a1a", "#2a2a0d",
        "#0d1a2a", "#2a0d0d", "#0d2a2a", "#1a0d2a", "#0d2a0d", "#2a0d0d", "#0d1a2a",
        "#2a0d1a", "#1a1a1a", "#2a1a0d", "#0d2a2a", "#1a2a1a", "#2a2a2a"
    )

    private val colsTv = TextView(act)
    private val gapTv = TextView(act)
    private val rpgTv = TextView(act)
    private val bgBtn = Button(act)
    private val numBox = LinearLayout(act)
    private val colsBox = LinearLayout(act)
    private val statusTv = TextView(act)

    init {
        for (i in 0 until maxCols) columns.add(ArrayList<Int>())
        for (i in 0 until n) columns[0].add(i)
        numCols = minOf(2, maxOf(1, n))
        buildUi()
        renderColumns()
        buildNumBar()
    }

    fun show() {
        dlg.show()
    }

    private fun dp(v: Int): Int {
        return (v * act.resources.displayMetrics.density).toInt()
    }

    private fun col(s: String): Int {
        return Color.parseColor(s)
    }

    private fun lp(w: Int, h: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.setMargins(dp(1), dp(1), dp(1), dp(1))
        return p
    }

    private fun btn(label: String, bg: String, fg: String, action: () -> Unit): Button {
        val b = Button(act)
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(5), dp(8), dp(5))
        b.setBackgroundColor(col(bg))
        b.setTextColor(col(fg))
        b.setOnClickListener { action() }
        return b
    }

    private fun label(t: String, color: String): TextView {
        val tv = TextView(act)
        tv.text = t
        tv.setTextColor(col(color))
        tv.textSize = 12f
        tv.setPadding(dp(6), 0, dp(6), 0)
        tv.gravity = Gravity.CENTER_VERTICAL
        return tv
    }

    private fun hRow(): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    private fun hScroll(inner: View): HorizontalScrollView {
        val h = HorizontalScrollView(act)
        h.isHorizontalScrollBarEnabled = false
        h.addView(inner)
        return h
    }

    private fun getNumCols(): Int {
        return if (numCols < 1) 1 else if (numCols > maxCols) maxCols else numCols
    }

    private fun buildUi() {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(col("#0d0d1a"))
        root.setPadding(dp(4), dp(4), dp(4), dp(4))

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        // action row
        val top = hRow()
        top.addView(label("Recreate Layout", "#cba6f7"))
        top.addView(btn("RECREATE IMAGE", "#5f27cd", "#ffffff") { doRecreate() }, lp(wrap, wrap))
        top.addView(btn("Preview", "#1a6b8a", "#ffffff") { doPreview() }, lp(wrap, wrap))
        top.addView(btn("Reset", "#444444", "#cba6f7") { resetLayout() }, lp(wrap, wrap))
        top.addView(btn("Close", "#444444", "#ffffff") { dlg.dismiss() }, lp(wrap, wrap))
        root.addView(hScroll(top))

        // columns count
        val r1 = hRow()
        r1.addView(label("Columns:", "#89b4fa"))
        r1.addView(btn("-", "#313244", "#89b4fa") { setNumCols(numCols - 1) }, lp(wrap, wrap))
        colsTv.setTextColor(col("#89b4fa"))
        colsTv.textSize = 14f
        colsTv.setPadding(dp(6), 0, dp(6), 0)
        r1.addView(colsTv)
        r1.addView(btn("+", "#313244", "#89b4fa") { setNumCols(numCols + 1) }, lp(wrap, wrap))
        for (k in 1..8) {
            r1.addView(btn(k.toString(), "#1e1e2e", "#89b4fa") { setNumCols(k) }, lp(wrap, wrap))
        }
        root.addView(hScroll(r1))

        // gap + background
        val r2 = hRow()
        r2.addView(label("Gap px:", "#f7b731"))
        r2.addView(btn("-", "#313244", "#f7b731") {
            if (gap >= 4) gap -= 4 else gap = 0
            refreshLabels()
        }, lp(wrap, wrap))
        gapTv.setTextColor(Color.WHITE)
        gapTv.textSize = 14f
        gapTv.setPadding(dp(6), 0, dp(6), 0)
        r2.addView(gapTv)
        r2.addView(btn("+", "#313244", "#f7b731") {
            if (gap < 80) gap += 4
            refreshLabels()
        }, lp(wrap, wrap))
        r2.addView(label("BG:", "#a6e3a1"))
        bgBtn.isAllCaps = false
        bgBtn.textSize = 12f
        bgBtn.minWidth = 0
        bgBtn.minimumWidth = 0
        bgBtn.minHeight = 0
        bgBtn.minimumHeight = 0
        bgBtn.setOnClickListener { black = !black; refreshLabels() }
        r2.addView(bgBtn, lp(wrap, wrap))
        root.addView(hScroll(r2))

        // quick layouts
        val qs = ArrayList<Quick>()
        qs.add(Quick("All → 1 col", "#444444", 1, null))
        qs.add(Quick("2 (1|1)", "#1a6b5a", 2, intArrayOf(1, 1)))
        qs.add(Quick("2 (2|1)", "#6b3a1a", 2, intArrayOf(2, 1)))
        qs.add(Quick("2 (1|2)", "#6b1a3a", 2, intArrayOf(1, 2)))
        qs.add(Quick("2 (2|2)", "#2d6a4f", 2, intArrayOf(2, 2)))
        qs.add(Quick("2 (3|3)", "#1a4f6a", 2, intArrayOf(3, 3)))
        qs.add(Quick("2 (4|4)", "#4f1a6a", 2, intArrayOf(4, 4)))
        qs.add(Quick("3 (1|1|1)", "#1a3a6b", 3, intArrayOf(1, 1, 1)))
        qs.add(Quick("3 (2|1|1)", "#4f2d6a", 3, intArrayOf(2, 1, 1)))
        qs.add(Quick("3 (2|2|2)", "#6a4f2d", 3, intArrayOf(2, 2, 2)))
        qs.add(Quick("3x3 (3|3|3)", "#2d4f6a", 3, intArrayOf(3, 3, 3)))
        qs.add(Quick("3 (4|4|4)", "#6a2d4f", 3, intArrayOf(4, 4, 4)))
        qs.add(Quick("4 (2|2|2|2)", "#2d6a4f", 4, intArrayOf(2, 2, 2, 2)))
        qs.add(Quick("5 (2|2|2|2|2)", "#4f2d6a", 5, intArrayOf(2, 2, 2, 2, 2)))
        qs.add(Quick("6 (1|1|1|1|1|1)", "#1a5a3a", 6, intArrayOf(1, 1, 1, 1, 1, 1)))
        qs.add(Quick("7 cols", "#3a1a5a", 7, null))
        qs.add(Quick("8 cols", "#5a3a1a", 8, null))
        qs.add(Quick("9 cols", "#1a3a5a", 9, null))
        qs.add(Quick("10 cols", "#5a1a3a", 10, null))
        val r3 = hRow()
        r3.addView(label("Quick Layout:", "#f7b731"))
        for (q in qs) {
            r3.addView(btn(q.label, q.color, "#ffffff") {
                val sz = q.sizes
                applyQuick(q.cols, if (sz == null) intArrayOf(n) else sz)
            }, lp(wrap, wrap))
        }
        root.addView(hScroll(r3))

        // rows per group
        val r4 = hRow()
        r4.addView(label("Set column sizes - Rows/group:", "#a6e3a1"))
        r4.addView(btn("-", "#313244", "#a6e3a1") {
            if (rowsPerGroup > 1) rowsPerGroup--
            buildNumBar()
        }, lp(wrap, wrap))
        rpgTv.setTextColor(col("#a6e3a1"))
        rpgTv.textSize = 14f
        rpgTv.setPadding(dp(6), 0, dp(6), 0)
        r4.addView(rpgTv)
        r4.addView(btn("+", "#313244", "#a6e3a1") {
            rowsPerGroup++
            buildNumBar()
        }, lp(wrap, wrap))
        for (qv in intArrayOf(3, 5, 7, 10, 15, 20)) {
            r4.addView(btn(qv.toString(), "#1e1e2e", "#a6e3a1") {
                rowsPerGroup = qv
                buildNumBar()
            }, lp(wrap, wrap))
        }
        root.addView(hScroll(r4))

        // C1..Cn size buttons (0-9)
        numBox.orientation = LinearLayout.HORIZONTAL
        val numH = HorizontalScrollView(act)
        numH.addView(numBox)
        val numV = ScrollView(act)
        numV.addView(numH)
        root.addView(numV, LinearLayout.LayoutParams(match, dp(120)))

        // columns with thumbnails
        colsBox.orientation = LinearLayout.HORIZONTAL
        val colsH = HorizontalScrollView(act)
        colsH.isFillViewport = true
        colsH.addView(colsBox, ViewGroup.LayoutParams(wrap, match))
        root.addView(colsH, LinearLayout.LayoutParams(match, 0, 1f))

        statusTv.setTextColor(col("#a6e3a1"))
        statusTv.textSize = 11f
        statusTv.setPadding(dp(4), dp(3), dp(4), dp(3))
        root.addView(statusTv)

        dlg.setContentView(root)
        refreshLabels()
    }

    private fun refreshLabels() {
        colsTv.text = getNumCols().toString()
        gapTv.text = gap.toString()
        rpgTv.text = rowsPerGroup.toString()
        if (black) {
            bgBtn.text = "Black"
            bgBtn.setBackgroundColor(Color.BLACK)
            bgBtn.setTextColor(Color.WHITE)
        } else {
            bgBtn.text = "White"
            bgBtn.setBackgroundColor(Color.WHITE)
            bgBtn.setTextColor(Color.BLACK)
        }
    }

    // ── layout operations (ported from the Python RecreateLayoutWindow) ──
    private fun allInActive(): ArrayList<Int> {
        val all = ArrayList<Int>()
        for (ci in 0 until getNumCols()) all.addAll(columns[ci])
        return all
    }

    private fun setNumCols(v: Int) {
        numCols = if (v < 1) 1 else if (v > maxCols) maxCols else v
        val nc = getNumCols()
        for (ci in nc until maxCols) {
            columns[0].addAll(columns[ci])
            columns[ci].clear()
        }
        refreshLabels()
        renderColumns()
        buildNumBar()
    }

    private fun setColSize(target: Int, count: Int) {
        val nc = getNumCols()
        val all = allInActive()
        val cnt = if (count > all.size) all.size else count
        val sizes = IntArray(nc)
        for (ci in 0 until nc) sizes[ci] = columns[ci].size
        sizes[target] = cnt
        for (ci in 0 until maxCols) columns[ci].clear()
        var pos = 0
        for (ci in 0 until nc) {
            val end = minOf(all.size, pos + sizes[ci])
            columns[ci].addAll(all.subList(pos, end))
            pos = end
        }
        if (pos < all.size) columns[nc - 1].addAll(all.subList(pos, all.size))
        renderColumns()
        buildNumBar()
        val sb = StringBuilder()
        for (ci in 0 until nc) {
            if (ci > 0) sb.append(" | ")
            sb.append("C").append(ci + 1).append(": ").append(columns[ci].size)
        }
        statusTv.text = "Column " + (target + 1).toString() + " -> " + cnt.toString() + "  |  " + sb.toString()
    }

    private fun applyQuick(newCols: Int, sizes: IntArray) {
        val all = allInActive()
        if (all.isEmpty()) {
            for (i in 0 until n) all.add(i)
        }
        for (ci in 0 until maxCols) columns[ci].clear()
        numCols = newCols
        var pos = 0
        for (ci in 0 until newCols) {
            val count = if (ci < sizes.size) sizes[ci] else 0
            val end = minOf(all.size, pos + count)
            columns[ci].addAll(all.subList(pos, end))
            pos = end
        }
        if (pos < all.size) columns[newCols - 1].addAll(all.subList(pos, all.size))
        refreshLabels()
        renderColumns()
        buildNumBar()
        val sb = StringBuilder()
        for (ci in 0 until newCols) {
            if (ci > 0) sb.append(" | ")
            sb.append("C").append(ci + 1).append(": ").append(columns[ci].size)
        }
        statusTv.text = "Quick layout applied: " + newCols.toString() + " col(s)  -  " + sb.toString()
    }

    private fun moveWithin(ci: Int, rowPos: Int, delta: Int) {
        val c = columns[ci]
        val np = rowPos + delta
        if (np < 0 || np >= c.size) return
        val t = c[rowPos]
        c[rowPos] = c[np]
        c[np] = t
        renderColumns()
        buildNumBar()
    }

    private fun moveToCol(from: Int, rowPos: Int, to: Int) {
        val idx = columns[from].removeAt(rowPos)
        columns[to].add(idx)
        renderColumns()
        buildNumBar()
    }

    private fun removeFromLayout(ci: Int, rowPos: Int) {
        columns[ci].removeAt(rowPos)
        renderColumns()
        buildNumBar()
    }

    private fun resetLayout() {
        for (ci in 0 until maxCols) columns[ci].clear()
        for (i in 0 until n) columns[0].add(i)
        numCols = minOf(2, maxOf(1, n))
        refreshLabels()
        renderColumns()
        buildNumBar()
    }

    // ── UI drawing ──
    private fun buildNumBar() {
        rpgTv.text = rowsPerGroup.toString()
        numBox.removeAllViews()
        val nc = getNumCols()
        val rpg = if (rowsPerGroup < 1) 1 else rowsPerGroup
        val groups = (nc + rpg - 1) / rpg
        for (g in 0 until groups) {
            val gl = LinearLayout(act)
            gl.orientation = LinearLayout.VERTICAL
            for (row in 0 until rpg) {
                val ci = g * rpg + row
                if (ci >= nc) break
                val clr = colColors[ci % colColors.size]
                val curN = columns[ci].size
                val rl = hRow()
                val tv = TextView(act)
                tv.text = "C" + (ci + 1).toString() + ":"
                tv.setTextColor(col(clr))
                tv.textSize = 12f
                tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
                rl.addView(tv, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                for (num in 0..9) {
                    val active = num == curN
                    val b = btn(
                        num.toString(),
                        if (active) clr else "#1e1e2e",
                        if (active) "#000000" else clr
                    ) { setColSize(ci, num) }
                    rl.addView(b, lp(dp(30), dp(32)))
                }
                gl.addView(rl)
            }
            val gp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gp.setMargins(0, 0, dp(18), 0)
            numBox.addView(gl, gp)
        }
    }

    private fun renderColumns() {
        colsBox.removeAllViews()
        val nc = getNumCols()
        for (ci in 0 until nc) {
            val clr = colColors[ci % colColors.size]
            val bg = colBgs[ci % colBgs.size]
            val outer = LinearLayout(act)
            outer.orientation = LinearLayout.VERTICAL
            outer.setBackgroundColor(col(bg))

            val hdr = TextView(act)
            hdr.text = "Column " + (ci + 1).toString() + "  (" + columns[ci].size.toString() + " images)"
            hdr.setTextColor(col(clr))
            hdr.textSize = 13f
            hdr.typeface = android.graphics.Typeface.DEFAULT_BOLD
            hdr.setPadding(dp(8), dp(6), dp(8), dp(6))
            outer.addView(hdr)

            val inner = LinearLayout(act)
            inner.orientation = LinearLayout.VERTICAL
            for ((rowPos, imgIdx) in columns[ci].withIndex()) {
                inner.addView(makeCard(ci, rowPos, imgIdx, nc, clr), lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val sv = ScrollView(act)
            sv.addView(inner)
            outer.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val op = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT)
            op.setMargins(dp(3), dp(3), dp(3), dp(3))
            colsBox.addView(outer, op)
        }
        updateStatus()
    }

    private fun makeCard(ci: Int, rowPos: Int, imgIdx: Int, nc: Int, clr: String): View {
        val s = srcs[imgIdx]
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(col("#1e1e2e"))
        card.setPadding(dp(4), dp(4), dp(4), dp(4))

        val nm = TextView(act)
        var shown = s.name
        if (shown.length > 24) shown = shown.substring(0, 24)
        nm.text = "#" + (imgIdx + 1).toString() + "  " + shown
        nm.setTextColor(col(clr))
        nm.textSize = 11f
        nm.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(nm)

        val iv = ImageView(act)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        if (s.thumb != null) iv.setImageBitmap(s.thumb)
        card.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))

        val sz = TextView(act)
        sz.text = s.w.toString() + "x" + s.h.toString() + " px"
        sz.setTextColor(col("#6c7086"))
        sz.textSize = 10f
        sz.gravity = Gravity.CENTER
        card.addView(sz)

        val row = hRow()
        row.addView(btn("▲", "#313244", "#cba6f7") { moveWithin(ci, rowPos, -1) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(btn("▼", "#313244", "#cba6f7") { moveWithin(ci, rowPos, 1) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (t in 0 until nc) {
            if (t == ci) continue
            val tc = colColors[t % colColors.size]
            row.addView(btn("→C" + (t + 1).toString(), "#1e1e2e", tc) { moveToCol(ci, rowPos, t) },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        row.addView(btn("✖", "#1e1e2e", "#ff6b6b") { removeFromLayout(ci, rowPos) }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(hScroll(row))
        return card
    }

    private fun updateStatus() {
        val nc = getNumCols()
        var total = 0
        val sb = StringBuilder()
        for (ci in 0 until nc) {
            total += columns[ci].size
            sb.append("C").append(ci + 1).append(": ").append(columns[ci].size).append("  ")
        }
        statusTv.text = "Layout: " + nc.toString() + " column(s)  |  " + total.toString() +
            " image(s) total  |  " + sb.toString() + " |  use ▲▼ and →C buttons to reorder"
    }

    // ── compose (port of _compose) ──
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

    private fun compose(): Bitmap {
        val groups = ArrayList<List<Int>>()
        for (ci in 0 until getNumCols()) {
            if (columns[ci].isNotEmpty()) groups.add(ArrayList<Int>(columns[ci]))
        }
        if (groups.isEmpty()) throw IllegalStateException("No images in any column")

        val g = maxOf(0, gap)
        val colW = IntArray(groups.size)
        val colH = IntArray(groups.size)
        val heights = ArrayList<IntArray>()
        for (ci in groups.indices) {
            val grp = groups[ci]
            var w = 1
            for (idx in grp) {
                if (srcs[idx].w > w) w = srcs[idx].w
            }
            colW[ci] = w
            val hs = IntArray(grp.size)
            var total = 0
            for (k in grp.indices) {
                val s = srcs[grp[k]]
                hs[k] = maxOf(1, Math.round(s.h.toDouble() * w.toDouble() / maxOf(1, s.w).toDouble()).toInt())
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
            return if (f < 1.0) maxOf(1, Math.round(v * f).toInt()) else v
        }

        val out = Bitmap.createBitmap(sc(totalW), sc(totalH), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (black) Color.BLACK else Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var x = 0
        for (ci in groups.indices) {
            var y = 0
            val grp = groups[ci]
            val cw = sc(colW[ci])
            for (k in grp.indices) {
                val s = srcs[grp[k]]
                val hh = sc(heights[ci][k])
                val bmp = loadFull(s)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, Rect(x, y, x + cw, y + hh), paint)
                    if (s.bmp == null) bmp.recycle()
                }
                y += hh + sc(g)
            }
            x += cw + sc(g)
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
    private lateinit var btnMove: Button
    private var recItem: WorkItem? = null
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
            makeButton("< Prev") { navStep(-1) },
            makeButton("Next >") { navStep(1) },
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
        btnMove = makeButton("Move") { toggleMove() }
        addRow(root,
            makeButton("Zoom +") { sliceView.zoomBy(1.25f) },
            makeButton("Zoom -") { sliceView.zoomBy(0.8f) },
            makeButton("Fit") { sliceView.zoomFit() },
            btnMove)

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
        restoreSession()
        refreshModeButtons()
        updateStatus()
    }

    // ── Resume: remember opened files, current page, numbering and saved slices ──
    private fun recsFor(item: WorkItem): ArrayList<SavedRec> {
        val existing = saved[item]
        if (existing != null) return existing
        val fresh = ArrayList<SavedRec>()
        saved[item] = fresh
        return fresh
    }

    private fun saveSession() {
        try {
            val arr = JSONArray()
            var curOut = -1
            var before = 0
            for (i in items.indices) {
                val w = items[i]
                val u = w.uri
                if (w.mem != null || u == null) {
                    if (i < cur) before++
                    continue
                }
                if (i == cur) curOut = arr.length()
                val o = JSONObject()
                o.put("u", u.toString())
                o.put("pdf", w.isPdf)
                o.put("page", w.page)
                o.put("label", w.label)
                o.put("jpeg", w.isJpeg)
                val ra = JSONArray()
                val rl = saved[w]
                if (rl != null) {
                    for (r in rl) {
                        val ro = JSONObject()
                        ro.put("u", r.uri.toString())
                        ro.put("n", r.number)
                        ra.put(ro)
                    }
                }
                o.put("recs", ra)
                arr.put(o)
            }
            if (curOut < 0) {
                curOut = maxOf(0, minOf(cur - before, arr.length() - 1))
            }
            val root = JSONObject()
            root.put("items", arr)
            root.put("cur", curOut)
            root.put("next", nextNumber)
            getSharedPreferences("slicer", MODE_PRIVATE).edit().putString("session", root.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    private fun restoreSession() {
        try {
            val txt = getSharedPreferences("slicer", MODE_PRIVATE).getString("session", null) ?: return
            val root = JSONObject(txt)
            val arr = root.getJSONArray("items")
            if (arr.length() == 0) return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val w = WorkItem(
                    Uri.parse(o.getString("u")), o.getBoolean("pdf"), o.getInt("page"),
                    o.getString("label"), o.getBoolean("jpeg"), null
                )
                items.add(w)
                val ra = o.optJSONArray("recs")
                if (ra != null && ra.length() > 0) {
                    val l = ArrayList<SavedRec>()
                    for (k in 0 until ra.length()) {
                        val ro = ra.getJSONObject(k)
                        l.add(SavedRec(Uri.parse(ro.getString("u")), ro.getInt("n")))
                    }
                    saved[w] = l
                }
            }
            nextNumber = maxOf(1, root.optInt("next", 1))
            val c = clampInt(root.optInt("cur", 0), 0, items.size - 1)
            showItem(c)
            toast("Resumed at page " + (c + 1).toString() + " of " + items.size.toString())
        } catch (t: Throwable) {
            items.clear()
            saved.clear()
        }
    }

    override fun onPause() {
        super.onPause()
        saveSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(pdfLock) { closePdf() }
    }

    // ── External keyboard shortcuts (same keys as the Python app) ──
    private var preBMode = Mode.VERTICAL
    private var lastRTime = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(ev: KeyEvent): Boolean {
        val k = ev.keyCode
        val ctrl = ev.isCtrlPressed
        val shift = ev.isShiftPressed
        val first = ev.repeatCount == 0
        val plain = !ctrl && !ev.isAltPressed && !ev.isMetaPressed
        val step = 80f * resources.displayMetrics.density

        when (k) {
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_EQUALS -> {
                sliceView.zoomBy(1.15f)
                return true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                sliceView.zoomBy(1f / 1.15f)
                return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_MOVE_HOME -> {
                sliceView.zoomFit()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sliceView.panBy(0f, step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sliceView.panBy(0f, -step)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (shift) sliceView.panBy(-step, 0f) else if (first) navStep(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (shift) sliceView.panBy(step, 0f) else if (first) navStep(-1)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (first) navStep(1)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (first) navStep(-1)
                return true
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                if (first) {
                    if (ctrl) flip(true) else rotate(-90f)
                }
                return true
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                if (first) {
                    if (ctrl) flip(false) else rotate(90f)
                }
                return true
            }
        }

        if (!plain || !first) return false

        when (k) {
            KeyEvent.KEYCODE_R -> {
                val now = System.currentTimeMillis()
                if (now - lastRTime <= 500L) sliceView.removeAll() else sliceView.undo()
                lastRTime = now
                return true
            }
            KeyEvent.KEYCODE_S -> {
                doSave()
                return true
            }
            KeyEvent.KEYCODE_V -> {
                sliceView.toggleVoid()
                refreshModeButtons()
                return true
            }
            KeyEvent.KEYCODE_X -> {
                val m = sliceView.mode
                if (m == Mode.VERTICAL) setMode(Mode.HORIZONTAL)
                else if (m == Mode.HORIZONTAL) setMode(Mode.VERTICAL)
                else setMode(preBMode)
                return true
            }
            KeyEvent.KEYCODE_B -> {
                val m = sliceView.mode
                if (m == Mode.VERTICAL || m == Mode.HORIZONTAL) preBMode = m
                setMode(Mode.SQUARE)
                return true
            }
            KeyEvent.KEYCODE_Z -> {
                val m = sliceView.mode
                if (m == Mode.VERTICAL || m == Mode.HORIZONTAL) preBMode = m
                setMode(Mode.ERASER)
                return true
            }
            KeyEvent.KEYCODE_M -> {
                toggleMove()
                return true
            }
            KeyEvent.KEYCODE_G -> {
                askGoTo()
                return true
            }
            KeyEvent.KEYCODE_O -> {
                pickFiles()
                return true
            }
            KeyEvent.KEYCODE_F -> {
                pickFolder()
                return true
            }
            KeyEvent.KEYCODE_E -> {
                openRecreate()
                return true
            }
        }
        return false
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
        b.isFocusable = false
        b.isFocusableInTouchMode = false
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

    private fun toggleMove() {
        sliceView.moveMode = !sliceView.moveMode
        refreshModeButtons()
        updateStatus()
    }

    private fun activeItem(): WorkItem? {
        val r = recItem
        if (r != null) return r
        return if (cur >= 0 && cur < items.size) items[cur] else null
    }

    // Prev / Next: from a (temporary) recreated image they return to the normal page it came from.
    private fun navStep(delta: Int) {
        if (recItem != null && cur >= 0 && cur < items.size) gotoItem(cur) else gotoItem(cur + delta)
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
        btnMove.setTextColor(if (sliceView.moveMode) on else off)
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
        val act0 = activeItem()
        if (act0 == null || snap == null) {
            sb.append("Tap Open to choose images / PDFs (you can select many)")
        } else {
            if (recItem != null) {
                sb.append("[RECREATED - temporary, not in the page list] ")
            } else {
                sb.append("[").append(cur + 1).append("/").append(items.size).append("] ")
            }
            sb.append(act0.label)
            sb.append(" | ").append(snap.full.width).append("x").append(snap.full.height)
            sb.append("\n")
            sb.append("pending: ").append(snap.slices.size)
            sb.append(" | next file #").append(nextNumber)
            sb.append(" | zoom ").append((sliceView.zoom * 100).toInt()).append("%")
            sb.append(" | ").append(if (sliceView.hint.isNotEmpty()) sliceView.hint else if (sliceView.moveMode) "MOVE: drag to pan (tap Move again to draw)" else modeHint())
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
        val t = outTree
        if (t != null) {
            try {
                intent.putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)))
            } catch (x: Throwable) {
            }
        }
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
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (t: Throwable) {
                }
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

    // The PDF stays open while you move between its pages (fast page turns on big files).
    private val pdfLock = Any()
    private var pdfUri: Uri? = null
    private var pdfPfd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    private fun closePdf() {
        try { pdfRenderer?.close() } catch (t: Throwable) { }
        try { pdfPfd?.close() } catch (t: Throwable) { }
        pdfRenderer = null
        pdfPfd = null
        pdfUri = null
    }

    private fun renderPdfPage(uri: Uri, page: Int): Bitmap? {
        synchronized(pdfLock) {
            try {
                if (pdfUri != uri || pdfRenderer == null) {
                    closePdf()
                    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
                    pdfPfd = pfd
                    pdfRenderer = PdfRenderer(pfd)
                    pdfUri = uri
                }
                val r = pdfRenderer ?: return null
                if (page < 0 || page >= r.pageCount) return null
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
            } catch (t: Throwable) {
                closePdf()
                return null
            }
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
        val oldRec = recItem
        if (oldRec != null) saved.remove(oldRec)
        recItem = null
        cur = i
        saveSession()
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
        if (snap == null || activeItem() == null) {
            toast("Open an image first")
            return
        }
        if (outTree == null) {
            pendingSave = true
            toast("Choose the output folder first")
            pickFolder()
            return
        }
        val item = activeItem() ?: return
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
                        recsFor(item).add(SavedRec(uri, -1))
                        saveSession()
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
        saveSession()
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
                recsFor(item).addAll(done)
                saveSession()
                toast("Saved " + done.size.toString() + "/" + count.toString() + " to " + folderName())
                updateStatus()
            }
        }).start()
    }

    // ── Reset: delete this page's saved slices + restore the original page ──
    private fun doReset() {
        if (activeItem() == null) {
            toast("Open an image first")
            return
        }
        val item = activeItem() ?: return
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
        saveSession()
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
                if (bmp != null && activeItem() === item) {
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
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (snap != null && snap.slices.isNotEmpty()) {
            names.add("Pending slices (" + snap.slices.size.toString() + ")")
            acts.add { recreateFromSlices(snap) }
        }
        if (outTree != null) {
            names.add("All images in the output folder")
            acts.add { recreateFromFolder() }
        }
        names.add("Pick image files...")
        acts.add { pickRecreateFiles() }
        AlertDialog.Builder(this)
            .setTitle("Recreate - choose source")
            .setItems(names.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun natKey(nm: String): Long {
        val m = Regex("^(\\d+)").find(nm)
        if (m == null) return Long.MAX_VALUE
        return m.groupValues[1].toLongOrNull() ?: Long.MAX_VALUE
    }

    private fun boundsOf(uri: Uri): IntArray {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        try {
            val st = contentResolver.openInputStream(uri)
            if (st != null) {
                try { BitmapFactory.decodeStream(st, null, o) } finally { st.close() }
            }
        } catch (t: Throwable) {
        }
        return intArrayOf(maxOf(1, o.outWidth), maxOf(1, o.outHeight))
    }

    // Every image saved in the chosen output folder (1, 2, 3 ... in number order).
    private fun recreateFromFolder() {
        val tree = outTree
        if (tree == null) {
            toast("Choose the output folder first")
            return
        }
        toast("Reading output folder...")
        Thread(Runnable {
            val files = ArrayList<Array<String>>()
            for (c in listChildren()) {
                val nm = c[1].lowercase()
                if (nm.endsWith(".png") || nm.endsWith(".jpg") || nm.endsWith(".jpeg") ||
                    nm.endsWith(".bmp") || nm.endsWith(".gif") || nm.endsWith(".webp") ||
                    nm.endsWith(".tif") || nm.endsWith(".tiff")) {
                    files.add(c)
                }
            }
            files.sortWith(Comparator { x, y ->
                val kx = natKey(x[1])
                val ky = natKey(y[1])
                if (kx != ky) kx.compareTo(ky) else x[1].compareTo(y[1])
            })
            val srcs = ArrayList<RecSrc>()
            for (c in files) {
                try {
                    val u = DocumentsContract.buildDocumentUriUsingTree(tree, c[0])
                    val t = decodeThumb(u, 200)
                    if (t != null) {
                        val bd = boundsOf(u)
                        srcs.add(RecSrc(null, u, t, c[1], bd[0], bd[1]))
                    }
                } catch (t: Throwable) {
                }
            }
            runOnUiThread {
                if (srcs.isEmpty()) toast("No images found in the output folder")
                else showRecreate(srcs, false)
            }
        }).start()
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
                    srcs.add(RecSrc(crop, null, makeThumb(crop), "slice " + (i + 1).toString(), crop.width, crop.height))
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
                if (t != null) {
                    val bd = boundsOf(u)
                    srcs.add(RecSrc(null, u, t, queryName(u), bd[0], bd[1]))
                }
            }
            runOnUiThread { showRecreate(srcs, false) }
        }).start()
    }

    private fun showRecreate(srcs: ArrayList<RecSrc>, fromSlices: Boolean) {
        if (srcs.isEmpty()) {
            toast("No valid images could be loaded")
            return
        }
        val cItem = if (cur >= 0 && cur < items.size) items[cur] else null
        val parentLabel = if (cItem != null) cItem.label else "recreated"
        val parentJpeg = if (cItem != null) cItem.isJpeg else false
        val ui = RecreateUi(this, srcs) { composed ->
            // The recreated image is TEMPORARY: it is not added to the page list.
            val item = WorkItem(null, false, 0, parentLabel + "_recreated", parentJpeg, composed)
            val old = recItem
            if (old != null) saved.remove(old)
            recItem = item
            loadToken++
            sliceView.baseNumber = nextNumber
            sliceView.setImage(composed.copy(Bitmap.Config.ARGB_8888, true))
            refreshModeButtons()
            updateStatus()
            toast("Recreated image opened - Prev/Next returns to your normal pages")
        }
        ui.show()
    }
}
