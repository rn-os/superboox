package com.superboox.quickaccess

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.concurrent.Executors
import java.util.Date

/** Android Home entry point. The app list and settings work before Accessibility is enabled. */
class HomeActivity : Activity() {
    private val prefs by lazy { Prefs(this) }
    private lateinit var rows: LinearLayout
    private enum class Page { HOME, APPS, MORE, INBOX, PROCESSED, CAPTURE }

    private var captureId: String? = null
    private var captureBack = Page.INBOX
    private var exportingId: String? = null
    private enum class Template(val title: String) {
        LAST_USED("BOOX last used (fast)"), DOT_GRID("Dots Grid 1"), LINED("Lined 16"), BLANK("Blank")
    }

    private val penTemplate: Template
        get() = Template.entries.firstOrNull { it.name == prefs.sp.getString(PEN_TEMPLATE, null) }
            ?: Template.LAST_USED

    private data class InkStroke(val path: Path, val x: Float, val y: Float)
    private val draftInk = mutableListOf<InkStroke>()
    private var savingInk = false
    private var expandedInk = false

    private var page = Page.HOME
    private val digestWorker = Executors.newSingleThreadExecutor()
    private var recentDigest: Digests.Book? = null
    private var digestRequest = 0
    private lateinit var initialSize: MenuSize
    private lateinit var palette: AppPalette

    override fun attachBaseContext(newBase: Context) {
        initialSize = Prefs(newBase).menuSize
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(UiScale.apply(newBase, newBase.resources.configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        palette = Ink.palette
        setTheme(palette.theme)
        super.onCreate(savedInstanceState)
        page = Page.entries.firstOrNull { it.name == savedInstanceState?.getString("page") } ?: Page.HOME
        expandedInk = savedInstanceState?.getBoolean("expandedInk") ?: false
        captureId = savedInstanceState?.getString("captureId")
        captureBack = if (savedInstanceState?.getBoolean("processed") == true) Page.PROCESSED else Page.INBOX
        exportingId = savedInstanceState?.getString("exportingId")
    }

    override fun onResume() {
        super.onResume()
        if (prefs.menuSize != initialSize || palette != Ink.palette) {
            recreate()
            return
        }
        render()
        loadDigest()
    }

    override fun onDestroy() {
        digestWorker.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        page = Page.HOME
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
        outState.putBoolean("expandedInk", expandedInk)
        outState.putString("captureId", captureId)
        outState.putBoolean("processed", captureBack == Page.PROCESSED)
        outState.putString("exportingId", exportingId)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when (page) {
            Page.CAPTURE -> show(captureBack)
            Page.PROCESSED -> show(Page.INBOX)
            Page.HOME -> Unit
            else -> show(Page.HOME)
        }
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.surface)
            isFillViewport = true
        }
        rows = vbox().apply {
            setPadding(dp(28), dp(22), dp(28), dp(24))
        }
        scroll.addView(rows)
        setContentView(scroll)

        when (page) {
            Page.HOME -> renderHome()
            Page.APPS -> {
                action("Back to Home", R.drawable.ic_back) { show(Page.HOME) }
                heading("All apps")
                installedApps().forEach { (label, component) ->
                    action(label, R.drawable.ic_apps) { Launcher.launchApp(this, component) }
                }
            }
            Page.MORE -> renderMore()
            Page.INBOX, Page.PROCESSED -> renderInbox(page == Page.PROCESSED)
            Page.CAPTURE -> renderCapture()
        }
    }

    private fun renderHome() {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
        rows.addView(hbox().apply {
            setPadding(0, 0, 0, dp(12))
            addView(text("superboox", 20f, medium = true, color = palette.ink), lp(0, WRAP, 1f))
            addView(text(date, 15f, color = palette.ink))
        })
        rule()
        writingZone()

        val count = Digests.captures(this).count { it.status == Digests.CaptureStatus.INBOX }
        if (count > 0) {
            captureHeading("Review $count ${if (count == 1) "capture" else "captures"}")
            rows.addView(actionView("Open Capture Inbox", R.drawable.ic_book, featured = true) { show(Page.INBOX) },
                lp(MATCH, WRAP).apply { topMargin = dp(12); bottomMargin = dp(20) })
            rule()
        } else recentDigest?.let { book ->
            val passages = if (book.source == Digests.Source.SAVED) {
                if (book.count == 1) "saved passage" else "saved passages"
            } else {
                if (book.count == 1) "reading highlight" else "reading highlights"
            }
            rows.addView(text("Revisit ${book.count} $passages", 36f, color = palette.ink).apply {
                typeface = Typeface.SERIF
                isAccessibilityHeading = true
                setPadding(0, dp(22), 0, dp(8))
            })
            rows.addView(text(book.title, 17f, color = palette.secondary))
            val button = actionView("Review Digests", R.drawable.ic_book, featured = true) {
                openDigests(book.key)
            }
            rows.addView(button, lp(MATCH, WRAP).apply {
                topMargin = dp(18)
                bottomMargin = dp(20)
            })
            rule()
        }

        val quickCapture = actionView("Quick Capture", R.drawable.ic_edit) { openPenNote(Template.LAST_USED) }
        quickCapture.setOnLongClickListener {
            withQuickAccess { openNewNoteSetup(fromHome = true) }
            true
        }
        rows.addView(quickCapture, lp(MATCH, WRAP))
        rule()
        val notebook = notes().firstOrNull { it.id == prefs.sp.getString(DAILY_NOTEBOOK, null) }
        if (notebook == null) {
            action("Notes", R.drawable.ic_note) { withQuickAccess { openNotesFolder(null) } }
        } else {
            action("Daily Notebook", R.drawable.ic_note) { withQuickAccess { openBooxNote(notebook) } }
        }
        action("Highlights", R.drawable.ic_digest) { openDigests() }
        action("Apps", R.drawable.ic_apps) { show(Page.APPS) }
        action("More", R.drawable.ic_settings) { show(Page.MORE) }
    }

    private fun writingZone() {
        val template = penTemplate
        val viewport = resources.displayMetrics.heightPixels
        val compactHeight = (viewport * 0.30f).toInt().coerceAtLeast(dp(154))
        val expandedHeight = (viewport * 0.57f).toInt().coerceAtLeast(compactHeight + dp(88))
        val controls = hbox().apply { visibility = if (draftInk.isEmpty()) View.INVISIBLE else View.VISIBLE }
        val zone = object : View(this) {
            private val nib = getDrawable(R.drawable.ic_pen_nib)?.mutate()?.apply {
                setTint(palette.secondary)
                alpha = if (palette == AppPalette.DARK) 140 else 150
            }
            private val stroke = Path()
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.ink
                strokeWidth = dp(2.5f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private var startX = 0f
            private var startY = 0f
            private var drawing = false
            private val hardwareInk = HomeHardwareInk(this, palette.ink, ink.strokeWidth) { points ->
                if (points.size >= 2) {
                    val path = Path().apply {
                        moveTo(points[0], points[1])
                        for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                    }
                    addStroke(InkStroke(path, points[0], points[1]))
                }
            }

            private fun addStroke(stroke: InkStroke) {
                if (savingInk) return
                draftInk.add(stroke)
                controls.visibility = View.VISIBLE
                invalidate()
                val strokeBounds = RectF().also { stroke.path.computeBounds(it, true) }
                if (!expandedInk && maxOf(stroke.y, strokeBounds.bottom) >= height - dp(40)) {
                    expandedInk = true
                    resize(expandedHeight)
                }
            }

            fun resize(targetHeight: Int) {
                val params = layoutParams ?: return
                if (params.height == targetHeight) return
                params.height = targetHeight
                layoutParams = params
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (oldw != 0 && oldh != 0) {
                    hardwareInk.stop()
                    post { hardwareInk.start() }
                }
            }

            fun hardwareRedraw() {
                hardwareInk.refresh()
            }

            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) post { hardwareInk.start() } else hardwareInk.stop()
            }

            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                post { hardwareInk.start() }
            }

            override fun onDetachedFromWindow() {
                hardwareInk.stop()
                super.onDetachedFromWindow()
            }

            private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.rule
                alpha = if (palette == AppPalette.DARK) 95 else 100
            }

            override fun onDraw(canvas: Canvas) {
                val step = dp(18)
                for (y in dp(48) until height step step) {
                    when (template) {
                        Template.DOT_GRID, Template.LAST_USED -> {
                            for (x in step / 2 until width step step) {
                                canvas.drawCircle(x.toFloat(), y.toFloat(), dp(1).toFloat(), guide)
                            }
                        }
                        Template.LINED -> canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), guide)
                        Template.BLANK -> Unit
                    }
                }
                nib?.let { drawable ->
                    val size = dp(14)
                    val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) dp(2) else width - size - dp(2)
                    drawable.setBounds(left, dp(9), left + size, dp(9) + size)
                    drawable.draw(canvas)
                }
                draftInk.forEach { drawInk(canvas, ink, it) }
                if (drawing && !hardwareInk.drawing) drawInk(canvas, ink, InkStroke(stroke, startX, startY))
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS || savingInk) return false
                if (hardwareInk.drawing) return true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        parent.requestDisallowInterceptTouchEvent(true)
                        drawing = true
                        startX = event.x
                        startY = event.y
                        stroke.reset()
                        stroke.moveTo(startX, startY)
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        if (!drawing) return false
                        for (sample in 0 until event.historySize) {
                            stroke.lineTo(event.getHistoricalX(sample), event.getHistoricalY(sample))
                        }
                        stroke.lineTo(event.x, event.y)
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            parent.requestDisallowInterceptTouchEvent(false)
                            drawing = false
                            addStroke(InkStroke(Path(stroke), startX, startY))
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        drawing = false
                        stroke.reset()
                    }
                }
                if (!hardwareInk.drawing) invalidate()
                return true
            }

        }.apply {
            contentDescription = "Handwriting area"
            minimumHeight = dp(154)
        }
        rows.addView(zone, lp(MATCH, if (expandedInk) expandedHeight else compactHeight).apply {
            topMargin = dp(12)
        })
        controls.addView(actionView("Undo", R.drawable.ic_back) {
            if (!savingInk && draftInk.isNotEmpty()) {
                draftInk.removeAt(draftInk.lastIndex)
                if (draftInk.isEmpty()) {
                    controls.visibility = View.INVISIBLE
                    expandedInk = false
                    zone.resize(compactHeight)
                }
                zone.hardwareRedraw()
            }
        }, lp(0, WRAP, 1f))
        controls.addView(actionView("Keep", R.drawable.ic_save, featured = true) { keepInk() }, lp(0, WRAP, 1f))
        rows.addView(controls, lp(MATCH, WRAP))
    }

    private fun drawInk(canvas: Canvas, paint: Paint, stroke: InkStroke) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(stroke.x, stroke.y, paint.strokeWidth / 2, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawPath(stroke.path, paint)
    }

    private fun keepInk() {
        if (savingInk || draftInk.isEmpty()) return
        val bounds = RectF()
        val segment = RectF()
        draftInk.forEach { stroke ->
            stroke.path.computeBounds(segment, true)
            bounds.union(stroke.x - 1, stroke.y - 1, stroke.x + 1, stroke.y + 1)
            if (segment.width() > 0 || segment.height() > 0) {
                segment.inset(-1f, -1f)
                bounds.union(segment)
            }
        }
        bounds.inset(-dp(16).toFloat(), -dp(16).toFloat())
        val scale = minOf(1f, 1600f / maxOf(bounds.width(), bounds.height()))
        val width = kotlin.math.ceil(bounds.width() * scale).toInt()
        val height = kotlin.math.ceil(bounds.height() * scale).toInt()
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(android.graphics.Color.WHITE)
            scale(scale, scale)
            translate(-bounds.left, -bounds.top)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                strokeWidth = dp(2.5f).toFloat()
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            draftInk.forEach { drawInk(this, paint, it) }
        }
        savingInk = true
        val app = applicationContext
        digestWorker.execute {
            val saved = runCatching { Digests.saveInk(app, image) }.isSuccess
            image.recycle()
            runOnUiThread {
                if (!isDestroyed) {
                    savingInk = false
                    if (saved) {
                        draftInk.clear()
                        expandedInk = false
                        if (page == Page.HOME) render()
                    }
                    Toast.makeText(this, if (saved) "Kept in Capture Inbox" else "Couldn't save handwriting",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openPenNote(template: Template) {
        if (template == Template.LAST_USED) {
            val direct = BooxNotes.newNoteIntent(this, prefs.notesPackage)
            if (direct != null && Launcher.tryStart(this, direct)) return
        }
        withQuickAccess {
            openNewNoteSetup(fromHome = true, template = template.title.takeUnless { template == Template.LAST_USED })
        }
    }

    private fun renderMore() {
        action("Back to Home", R.drawable.ic_back) { show(Page.HOME) }
        heading("More")
        action("Capture Inbox", R.drawable.ic_book) { show(Page.INBOX) }
        val appearance = if (palette == AppPalette.DARK) "Dark" else "Paper"
        action("Appearance · $appearance", R.drawable.ic_qc_light) { chooseAppearance() }
        action("Pen template · ${penTemplate.title}", R.drawable.ic_edit) { choosePenTemplate() }
        action("Choose Daily Notebook", R.drawable.ic_note) { chooseNotebook() }
        if (QuickAccessService.instance?.lastUsedApp != null) {
            action("Resume", R.drawable.ic_clock) { withQuickAccess { openPreviousApp(fromHome = true) } }
        }
        action("Quick Access", R.drawable.ic_panel) { withQuickAccess { showPanel() } }

        val installed = installedApps().associate { (label, component) -> component to label }
        val favorites = prefs.dock.mapNotNull { pin ->
            val component = ComponentName.unflattenFromString(pin.value)?.flattenToShortString()
                ?: packageManager.getLaunchIntentForPackage(pin.value)?.component?.flattenToShortString()
            component?.takeIf(installed::containsKey)?.let { pin.label.ifBlank { installed.getValue(it) } to it }
        }.distinctBy { it.second }
        if (favorites.isNotEmpty()) {
            section("Favorites")
            favorites.forEach { (label, component) ->
                action(label, R.drawable.ic_star) { Launcher.launchApp(this, component) }
            }
        }

        section("Settings")
        action("SuperBoox settings", R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) }
        action("Default Home app", R.drawable.ic_home) {
            Launcher.start(this, Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    private fun captureHeading(label: String) {
        rows.addView(text(label, 36f, color = palette.ink).apply {
            typeface = Typeface.SERIF
            isAccessibilityHeading = true
            setPadding(0, dp(22), 0, dp(12))
        })
    }

    private fun renderInbox(processed: Boolean) {
        action(if (processed) "Capture Inbox" else "Home", R.drawable.ic_back) {
            show(if (processed) Page.INBOX else Page.HOME)
        }
        captureHeading(if (processed) "Processed captures" else "Capture Inbox")
        val items = Digests.captures(this).filter { (it.status != Digests.CaptureStatus.INBOX) == processed }
        if (items.isEmpty()) rows.addView(text(if (processed) "No processed captures." else "Share text or a link with Save to Digest to add it here.",
            17f, color = palette.secondary))
        items.forEach { item ->
            val title = item.followUp.takeIf { item.status == Digests.CaptureStatus.FOLLOW_UP && it.isNotBlank() }
                ?: item.title
            val detail = if (processed) captureStatus(item) else
                "${item.source} · ${DateFormat.getDateInstance().format(Date(item.time))}"
            action("$title\n$detail", R.drawable.ic_digest) {
                captureId = item.id
                captureBack = page
                show(Page.CAPTURE)
            }
        }
        if (!processed) action("Processed captures", R.drawable.ic_recents) { show(Page.PROCESSED) }
    }

    private fun renderCapture() {
        action(if (captureBack == Page.PROCESSED) "Processed captures" else "Capture Inbox",
            R.drawable.ic_back) { show(captureBack) }
        val item = Digests.captures(this).firstOrNull { it.id == captureId } ?: return
        captureHeading(item.title)
        rows.addView(text(item.source, 17f, color = palette.secondary))
        if (item.inkId == null) {
            rows.addView(text(item.text, 21f, color = palette.ink).apply { setPadding(0, dp(18), 0, dp(18)) })
        } else {
            val image = Digests.inkFile(this, item.inkId)?.let { BitmapFactory.decodeFile(it.path) }
            if (image == null) rows.addView(text("Handwriting image unavailable", 17f, color = palette.secondary))
            else rows.addView(ImageView(this).apply {
                contentDescription = "Handwritten capture"
                adjustViewBounds = true
                setImageBitmap(image)
            }, lp(MATCH, WRAP).apply { topMargin = dp(18); bottomMargin = dp(18) })
        }
        item.link?.let { url ->
            action("Open link", R.drawable.ic_open) { Launcher.start(this, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        if (item.status == Digests.CaptureStatus.INBOX) {
            rows.addView(actionView("Act · Create a follow-up", R.drawable.ic_edit, featured = true) { actOn(item) },
                lp(MATCH, WRAP).apply { topMargin = dp(14) })
            action(if (item.inkId == null) "Save · Export as Markdown" else "Save · Export as PNG", R.drawable.ic_save) { exportCapture(item) }
            action("Archive · Clear from inbox", R.drawable.ic_recents) { process(item, Digests.CaptureStatus.ARCHIVED) }
        } else {
            rows.addView(text(captureStatus(item), 18f, color = palette.secondary))
            if (item.followUp.isNotBlank()) rows.addView(text(item.followUp, 21f, color = palette.ink))
            action("Return to inbox", R.drawable.ic_back) { process(item, Digests.CaptureStatus.INBOX) }
        }
        action(if (item.inkId == null) "Edit capture" else "Rename capture", R.drawable.ic_edit) { editCapture(item) }
        action("Delete capture", R.drawable.ic_delete) { deleteCapture(item) }
    }

    private fun editCapture(item: Digests.Capture) {
        val fields = vbox().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val title = EditText(this).apply {
            hint = "Title"
            setSingleLine(true)
            setText(item.title)
        }
        fields.addView(title)
        val passage = if (item.inkId == null) EditText(this).apply {
            hint = "Passage"
            minLines = 3
            maxLines = 8
            setText(item.text)
            fields.addView(this)
        } else null
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (passage == null) "Rename handwritten capture" else "Edit capture")
            .setView(fields)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newTitle = title.text.toString().trim()
            val newText = passage?.text?.toString()?.trim()
            when {
                newTitle.isEmpty() -> title.error = "Enter a title"
                newText != null && newText.isEmpty() -> passage.error = "Enter a passage"
                newText != null && newText.length > Digests.MAX_TEXT ->
                    passage.error = "Keep the passage under ${Digests.MAX_TEXT + 1} characters"
                else -> {
                    Digests.editCapture(this, item.id, newTitle, newText)
                    dialog.dismiss()
                    render()
                }
            }
        }
    }

    private fun deleteCapture(item: Digests.Capture) {
        AlertDialog.Builder(this)
            .setTitle("Delete capture?")
            .setMessage("This permanently removes the saved copy from SuperBoox.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                runCatching { Digests.deleteCapture(this, item.id) }
                    .onSuccess { show(captureBack) }
                    .onFailure { Toast.makeText(this, "Couldn't delete capture", Toast.LENGTH_SHORT).show() }
            }
            .show()
    }

    private fun captureStatus(item: Digests.Capture): String = when (item.status) {
        Digests.CaptureStatus.INBOX -> "In inbox"
        Digests.CaptureStatus.FOLLOW_UP -> "Follow-up"
        Digests.CaptureStatus.SAVED -> "Exported"
        Digests.CaptureStatus.ARCHIVED -> "Archived"
    }

    private fun process(item: Digests.Capture, status: Digests.CaptureStatus, followUp: String? = null) {
        Digests.processCapture(this, item.id, status, followUp)
        show(captureBack)
    }

    private fun actOn(item: Digests.Capture) {
        val input = EditText(this).apply {
            hint = "What will you do next?"
            setText(item.followUp)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Create a follow-up").setView(input)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) input.error = "Enter a follow-up"
            else {
                process(item, Digests.CaptureStatus.FOLLOW_UP, value)
                dialog.dismiss()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun exportCapture(item: Digests.Capture) {
        if (exportingId != null) return
        exportingId = item.id
        val filename = item.title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(70).ifBlank { "Capture" }
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (item.inkId == null) "text/markdown" else "image/png"
                val extension = if (item.inkId == null) "md" else "png"
                putExtra(Intent.EXTRA_TITLE, "$filename.$extension")
            }, EXPORT_CAPTURE)
        } catch (_: android.content.ActivityNotFoundException) {
            exportingId = null
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_CAPTURE) return
        val id = exportingId.also { exportingId = null } ?: return
        val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
        val item = Digests.captures(this).firstOrNull { it.id == id } ?: return
        val app = applicationContext
        digestWorker.execute {
            val success = runCatching {
                val body = buildString {
                    appendLine("# ${item.title}")
                    appendLine()
                    appendLine(item.text)
                }
                checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { out ->
                    if (item.inkId == null) out.write(body.toByteArray())
                    else checkNotNull(Digests.inkFile(app, item.inkId)).inputStream().use { it.copyTo(out) }
                }
                Digests.processCapture(app, id, Digests.CaptureStatus.SAVED)
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) {
                    Toast.makeText(this, if (success) "Capture exported" else "Couldn't export capture", Toast.LENGTH_SHORT).show()
                    if (success && page == Page.CAPTURE && captureId == id) show(Page.INBOX)
                }
            }
        }
    }

    private fun installedApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).map { result ->
            val activity = result.activityInfo
            result.loadLabel(packageManager).toString() to
                ComponentName(activity.packageName, activity.name).flattenToShortString()
        }.distinctBy { it.second }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
    }

    private fun show(destination: Page) {
        page = destination
        render()
    }

    private fun loadDigest() {
        val request = ++digestRequest
        val app = applicationContext
        digestWorker.execute {
            val book = runCatching {
                Digests.books(Digests.all(app).filter { it.source != Digests.Source.SAVED }).firstOrNull()
            }.getOrNull()
            runOnUiThread {
                if (!isDestroyed && request == digestRequest) {
                    val changed = recentDigest?.key != book?.key || recentDigest?.count != book?.count ||
                        recentDigest?.title != book?.title
                    recentDigest = book
                    if (page == Page.HOME && changed && draftInk.isEmpty()) render()
                }
            }
        }
    }

    private fun openDigests(bookKey: String? = null) {
        startActivity(Intent(this, DigestsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            bookKey?.let { putExtra(DigestsActivity.EXTRA_BOOK, it) }
        })
    }

    private fun notes(): List<Pin> = prefs.pins.filter { it.type == PinType.BOOX_NOTE }

    private fun chooseAppearance() {
        AlertDialog.Builder(this)
            .setTitle("Appearance")
            .setSingleChoiceItems(arrayOf("Paper", "Dark"), if (palette == AppPalette.DARK) 1 else 0) { dialog, index ->
                val dark = index == 1
                prefs.sp.edit().putBoolean(Ink.APPEARANCE_KEY, dark).apply()
                dialog.dismiss()
                if (dark != (palette == AppPalette.DARK)) {
                    page = Page.HOME
                    recreate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choosePenTemplate() {
        val choices = Template.entries
        AlertDialog.Builder(this)
            .setTitle("Pen template")
            .setSingleChoiceItems(choices.map { it.title }.toTypedArray(), penTemplate.ordinal) { dialog, index ->
                prefs.sp.edit().putString(PEN_TEMPLATE, choices[index].name).apply()
                dialog.dismiss()
                show(Page.HOME)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseNotebook() {
        val notes = notes()
        if (notes.isEmpty()) {
            Toast.makeText(this, "Pin a note in Quick Access first", Toast.LENGTH_LONG).show()
            return
        }
        val choices = listOf("None") + notes.map { it.label }
        val current = notes.indexOfFirst { it.id == prefs.sp.getString(DAILY_NOTEBOOK, null) } + 1
        AlertDialog.Builder(this)
            .setTitle("Choose a pinned BOOX note")
            .setSingleChoiceItems(choices.toTypedArray(), current) { dialog, index ->
                prefs.sp.edit().putString(DAILY_NOTEBOOK, notes.getOrNull(index - 1)?.id).apply()
                dialog.dismiss()
                show(Page.HOME)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun withQuickAccess(action: QuickAccessService.() -> Unit) {
        val service = QuickAccessService.instance
        if (service != null) {
            service.action()
        } else {
            Toast.makeText(this, "Turn on SuperBoox accessibility to use this shortcut", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun heading(label: String) {
        rows.addView(text(label, 40f, medium = true, color = palette.ink).apply {
            isAccessibilityHeading = true
            setPadding(0, dp(8), 0, dp(26))
        })
    }

    private fun section(label: String) {
        rows.addView(text(label, 14f, medium = true, color = palette.secondary).apply {
            isAccessibilityHeading = true
            setPadding(0, dp(24), 0, dp(6))
        })
    }

    private fun rule() {
        rows.addView(View(this).apply { setBackgroundColor(palette.rule) }, lp(MATCH, dp(1)))
    }

    private fun action(label: String, icon: Int, run: () -> Unit) {
        rows.addView(actionView(label, icon, run = run), lp(MATCH, WRAP))
        rule()
    }

    private fun actionView(label: String, icon: Int, featured: Boolean = false, run: () -> Unit): TextView =
        TextView(this).apply {
            val foreground = if (featured) palette.surface else palette.ink
            text = label
            setTextColor(foreground)
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(if (featured) 64 else 60)
            setPadding(dp(if (featured) 16 else 8), dp(8), dp(if (featured) 16 else 8), dp(8))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, if (featured) R.drawable.ic_chevron_right else 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(foreground)
            compoundDrawablePadding = dp(16)
            fun fill(color: Int) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = if (featured) dp(8).toFloat() else 0f
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                val highlight = if (featured) palette.featuredPressed else palette.pressed
                addState(intArrayOf(android.R.attr.state_pressed), fill(highlight))
                addState(intArrayOf(android.R.attr.state_focused), fill(highlight))
                if (featured) addState(intArrayOf(), fill(palette.ink))
            }
            isFocusable = true
            setOnClickListener { run() }
        }

    private companion object {
        const val EXPORT_CAPTURE = 42
        const val PEN_TEMPLATE = "home_pen_template"
        const val DAILY_NOTEBOOK = "home_daily_notebook"
    }
}
