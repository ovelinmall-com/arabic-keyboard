package com.workspace.arabickeyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

private data class ClipRecord(
    val id: String,
    val text: String,
    val createdAt: Long,
    val pinned: Boolean,
)

private data class EmojiGroupRecord(
    val id: String,
    val label: String,
    val icon: String,
    val emojis: List<String>,
)

private val keyboardBackground = Color.BLACK
private val keyboardKeyBackground = Color.rgb(43, 43, 43)
private val keyboardPressedBackground = Color.rgb(65, 65, 65)
private val keyboardTextColor = Color.WHITE

class ArabicKeyboardService : InputMethodService() {
    private enum class Panel { KEYS, CLIPBOARD, EMOJI }

    private var panel = Panel.KEYS
    private var arabic = true
    private var numbers = false
    private var numericFieldMode = false
    private var sensitiveFieldMode = false
    private var editorAction = EditorInfo.IME_ACTION_NONE
    private var symbolPage = 0
    private var shifted = false
    private var recentExpanded = false
    private var clipboardExpanded = false
    private var selectedEmojiGroupId: String? = null
    private var defaultImeHeightPx: Int? = null
    private var clipboardDragStartY = 0f
    private var clipboardDragStartTime = 0L
    private var clipboardDragActive = false
    private var internalSelectionUpdatePending = false
    private lateinit var root: LinearLayout
    private val selectedClipIds = mutableSetOf<String>()
    private val currentWordBuffer = StringBuilder()
    private var learnedWordCounts = mutableMapOf<String, Int>()
    private var suggestionButtons: List<TextView> = emptyList()
    private var currentSuggestions: List<String> = emptyList()
    private val arabicStarterWords = listOf(
        "أنا", "انت", "انتو", "من", "في", "على", "داير", "عايز", "شنو",
        "كيف", "ما", "ده", "دي", "كدا", "تمام", "طيب", "بعد", "قبل", "مع",
        "عندي", "ممكن", "والله", "الحمد", "لكن", "لمن", "كان", "كل", "نحن",
        "هسي", "اليوم", "الله", "بكون", "حاجة", "حصل", "تاني", "شوية", "ناس",
        "بالضبط", "ايوا", "اكتب", "دا", "دايرة", "دايرين",
    )
    private val englishStarterWords = listOf(
        "the", "you", "i", "to", "and", "is", "it", "a", "for", "in",
        "that", "this", "my", "we", "are", "on", "with", "have", "what", "can",
        "hello", "thanks", "please",
    )
    private var keyPopup: PopupWindow? = null
    private var keyPopupText: TextView? = null
    private var diacriticPopup: PopupWindow? = null
    private var activeDeleteRepeat: Runnable? = null
    private val popupHandler = Handler(Looper.getMainLooper())
    private val hideKeyPopup = Runnable { keyPopup?.dismiss() }
    private val clearInternalSelectionUpdate = Runnable { internalSelectionUpdatePending = false }
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val prefs by lazy { getSharedPreferences("arabic_keyboard", Context.MODE_PRIVATE) }
    private val keyboardManager by lazy { getSystemService(InputMethodManager::class.java) }
    private val emojiCatalog by lazy(LazyThreadSafetyMode.NONE) {
        val data = assets.open("emoji-data.json").bufferedReader().use { it.readText() }
        JSONObject(data)
    }
    private val emojiItems: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val values = emojiCatalog.getJSONArray("emojis")
        List(values.length()) { values.getString(it) }
    }
    private val emojiGroups: List<EmojiGroupRecord> by lazy(LazyThreadSafetyMode.NONE) {
        val values = emojiCatalog.getJSONArray("groups")
        List(values.length()) { index ->
            val group = values.getJSONObject(index)
            val groupEmojis = group.getJSONArray("emojis")
            EmojiGroupRecord(
                id = group.getString("id"),
                label = group.getString("label"),
                icon = group.getString("icon"),
                emojis = List(groupEmojis.length()) { groupEmojis.getString(it) },
            )
        }
    }
    private val keyRows: List<List<String>>
        get() = when {
            numbers -> if (symbolPage == 0) {
                listOf(
                    listOf("@", "#", "\$", "_", "&", "-", "+", "(", ")", "/"),
                    listOf("=\\<", "،", "\"", "'", ":", "؛", "!", "؟", "⌫"),
                )
            } else {
                listOf(
                    listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ"),
                    listOf("123", "£", "¢", "€", "¥", "^", "°", "=", "⌫"),
                )
            }
            arabic -> listOf(
                listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
                listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
                listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د", "⌫"),
            )
            else -> listOf(
                "qwertyuiop".map { it.toString() },
                "asdfghjkl".map { it.toString() },
                listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"),
            )
        }

    override fun onCreateInputView(): View {
        learnedWordCounts = loadLearnedWords()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
            setBackgroundColor(keyboardBackground)
        }
        prepareKeyPreview()
        render()
        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel = Panel.KEYS
        val inputClass = info?.inputType?.and(InputType.TYPE_MASK_CLASS)
        numericFieldMode =
            inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE
        val inputVariation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        sensitiveFieldMode = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> inputVariation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                inputVariation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                inputVariation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        editorAction = (info?.imeOptions ?: EditorInfo.IME_ACTION_NONE) and EditorInfo.IME_MASK_ACTION
        numbers = false
        symbolPage = 0
        shifted = false
        clipboardExpanded = false
        currentWordBuffer.setLength(0)
        internalSelectionUpdatePending = false
        defaultImeHeightPx = computeDefaultImeHeightPx()
        window.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, defaultImeHeightPx!!)
        registerClipboardListener()
        render()
        root.post {
            resizeClipboardWindow(false)
            root.postDelayed({ captureCurrentClipboard() }, 50)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        unregisterClipboardListener()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart != newSelEnd) {
            currentWordBuffer.setLength(0)
            internalSelectionUpdatePending = false
            popupHandler.removeCallbacks(clearInternalSelectionUpdate)
        } else if (internalSelectionUpdatePending) {
            internalSelectionUpdatePending = false
            popupHandler.removeCallbacks(clearInternalSelectionUpdate)
        } else if (newSelStart != oldSelStart || newSelEnd != oldSelEnd) {
            currentWordBuffer.setLength(0)
        }
        refreshSuggestionRow()
    }

    private fun expectInternalSelectionUpdate() {
        internalSelectionUpdatePending = true
        popupHandler.removeCallbacks(clearInternalSelectionUpdate)
        popupHandler.postDelayed(clearInternalSelectionUpdate, 250)
    }

    override fun onDestroy() {
        unregisterClipboardListener()
        popupHandler.removeCallbacks(hideKeyPopup)
        activeDeleteRepeat?.let(popupHandler::removeCallbacks)
        activeDeleteRepeat = null
        keyPopup?.dismiss()
        keyPopup = null
        keyPopupText = null
        diacriticPopup?.dismiss()
        diacriticPopup = null
        super.onDestroy()
    }

    private fun registerClipboardListener() {
        if (clipboardListener != null) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (captureCurrentClipboard()) {
                popupHandler.post {
                    if (::root.isInitialized && panel == Panel.CLIPBOARD) render()
                }
            }
        }
        clipboardManager = manager
        clipboardListener = listener
        manager.addPrimaryClipChangedListener(listener)
    }

    private fun unregisterClipboardListener() {
        val listener = clipboardListener ?: return
        clipboardManager?.removePrimaryClipChangedListener(listener)
        clipboardManager = null
        clipboardListener = null
    }

    private fun render() {
        if (!::root.isInitialized) return
        root.removeAllViews()
        suggestionButtons = emptyList()
        currentSuggestions = emptyList()
        if (panel == Panel.KEYS && numericFieldMode) {
            renderNumericPad()
            return
        }
        root.addView(toolbar(), LinearLayout.LayoutParams(-1, dp(42)))
        when (panel) {
            Panel.KEYS -> renderKeys()
            Panel.CLIPBOARD -> renderClipboard()
            Panel.EMOJI -> renderEmoji()
        }
    }

    private fun setClipboardExpanded(expanded: Boolean) {
        if (clipboardExpanded == expanded) return
        if (defaultImeHeightPx == null && root.height > 0) defaultImeHeightPx = root.height
        clipboardExpanded = expanded
        render()
        root.post { resizeClipboardWindow(expanded) }
    }

    private fun resizeClipboardWindow(expanded: Boolean) {
        val imeWindow = window.window ?: return
        if (defaultImeHeightPx == null && root.height > 0) defaultImeHeightPx = root.height
        val standardHeight = defaultImeHeightPx ?: return
        val expandedHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        val targetHeight = if (expanded) maxOf(standardHeight, expandedHeight) else standardHeight
        imeWindow.setLayout(WindowManager.LayoutParams.MATCH_PARENT, targetHeight)
    }

    private fun renderSuggestionRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        val buttons = mutableListOf<TextView>()
        repeat(3) { index ->
            val button = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(keyboardTextColor)
                setPadding(dp(4), 0, dp(4), 0)
                contentDescription = "اقتراح كلمة"
                setOnClickListener {
                    currentSuggestions.getOrNull(index)?.let(::renderSuggestionSelection)
                }
            }
            buttons.add(button)
            row.addView(button, LinearLayout.LayoutParams(0, dp(29), 1f))
            if (index < 2) {
                row.addView(
                    View(this).apply { setBackgroundColor(Color.rgb(56, 56, 56)) },
                    LinearLayout.LayoutParams(dp(1), dp(17)),
                )
            }
        }
        suggestionButtons = buttons
        refreshSuggestionRow()
        return row
    }

    private fun refreshSuggestionRow() {
        if (sensitiveFieldMode || numericFieldMode) {
            currentSuggestions = emptyList()
            suggestionButtons.forEach { it.text = "" }
            return
        }
        val prefix = currentWordBuffer.toString()
        val normalizedPrefix = prefix.lowercase(java.util.Locale.ROOT)
        val learned = learnedWordCounts.entries
            .asSequence()
            .filter { (word, _) ->
                word.lowercase(java.util.Locale.ROOT).startsWith(normalizedPrefix) &&
                    matchesKeyboardLanguage(word)
            }
            .sortedByDescending { it.value }
            .map { it.key }
            .toList()
        val starter = if (arabic) arabicStarterWords else englishStarterWords
        currentSuggestions = (learned + starter.filter {
            it.lowercase(java.util.Locale.ROOT).startsWith(normalizedPrefix)
        }).distinct().take(3)
        suggestionButtons.forEachIndexed { index, button ->
            button.text = currentSuggestions.getOrNull(index).orEmpty()
        }
    }

    private fun matchesKeyboardLanguage(word: String): Boolean =
        if (arabic) {
            word.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F || it.code in 0x08A0..0x08FF }
        } else {
            word.any { it in 'a'..'z' || it in 'A'..'Z' }
        }

    private fun renderSuggestionSelection(word: String) {
        val connection = currentInputConnection ?: return
        val prefixLength = currentWordBuffer.length
        expectInternalSelectionUpdate()
        if (prefixLength > 0) connection.deleteSurroundingText(prefixLength, 0)
        connection.commitText("$word ", 1)
        currentWordBuffer.setLength(0)
        learnWord(word)
        refreshSuggestionRow()
    }

    private fun loadLearnedWords(): MutableMap<String, Int> {
        return try {
            val saved = JSONObject(prefs.getString("learned_word_counts", "{}"))
            val words = mutableMapOf<String, Int>()
            val keys = saved.keys()
            while (keys.hasNext()) {
                val word = keys.next()
                val count = saved.optInt(word, 0)
                if (word.length > 1 && count > 0) words[word] = count
            }
            words.entries.sortedByDescending { it.value }.take(1000).associate { it.key to it.value }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun learnWord(rawWord: String) {
        val word = rawWord.trim().lowercase(java.util.Locale.ROOT)
        if (word.length < 2) return
        learnedWordCounts[word] = (learnedWordCounts[word] ?: 0) + 1
        if (learnedWordCounts.size > 1000) {
            val limited = learnedWordCounts.entries
                .sortedByDescending { it.value }
                .take(1000)
                .associate { it.key to it.value }
            learnedWordCounts.clear()
            learnedWordCounts.putAll(limited)
        }
        val saved = JSONObject()
        learnedWordCounts.forEach { (key, count) -> saved.put(key, count) }
        prefs.edit().putString("learned_word_counts", saved.toString()).apply()
    }

    private fun isWordCharacter(character: Char): Boolean =
        character.isLetter() ||
            Character.getType(character.code) == Character.NON_SPACING_MARK.toInt() ||
            Character.getType(character.code) == Character.COMBINING_SPACING_MARK.toInt() ||
            character == '\'' || character == '’' || character == '-'

    private fun updateWordBuffer(text: String, learn: Boolean) {
        if (!learn || sensitiveFieldMode || numericFieldMode) {
            currentWordBuffer.setLength(0)
            refreshSuggestionRow()
            return
        }
        if (text.isNotEmpty() && text.all(::isWordCharacter)) {
            currentWordBuffer.append(text)
        } else {
            if (currentWordBuffer.isNotEmpty()) learnWord(currentWordBuffer.toString())
            currentWordBuffer.setLength(0)
        }
        refreshSuggestionRow()
    }

    private fun removeLastWordCharacter() {
        if (currentWordBuffer.isEmpty()) return
        val iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        iterator.setText(currentWordBuffer.toString())
        val boundary = iterator.preceding(currentWordBuffer.length)
        currentWordBuffer.setLength(if (boundary == BreakIterator.DONE) 0 else boundary)
        refreshSuggestionRow()
    }

    private fun toolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        bar.addView(action("⋯") { toggleSymbols() }, toolbarKeyParams())
        val settingsButton = action("⚙") { keyboardManager?.showInputMethodPicker() }
        val clipboardButton = action("▤") {
            if (clipboardExpanded) {
                clipboardExpanded = false
                root.post { resizeClipboardWindow(false) }
            }
            recentExpanded = false
            panel = Panel.CLIPBOARD
            render()
            root.post { resizeClipboardWindow(false) }
            refreshClipboardAfterOpen()
        }.apply {
            setOnLongClickListener {
                val next = !prefs.getBoolean("toolbar_clipboard_settings_swapped", false)
                prefs.edit().putBoolean("toolbar_clipboard_settings_swapped", next).apply()
                render()
                true
            }
        }
        if (prefs.getBoolean("toolbar_clipboard_settings_swapped", false)) {
            bar.addView(clipboardButton, toolbarKeyParams())
            bar.addView(settingsButton, toolbarKeyParams())
        } else {
            bar.addView(settingsButton, toolbarKeyParams())
            bar.addView(clipboardButton, toolbarKeyParams())
        }
        bar.addView(action("文A") { toggleLanguage() }, toolbarKeyParams())
        bar.addView(action("☻") {
            clipboardExpanded = false
            panel = Panel.EMOJI
            render()
            root.post { resizeClipboardWindow(false) }
        }, toolbarKeyParams())
        bar.addView(action("✦") { pasteLatestClip() }, toolbarKeyParams())
        return bar
    }

    private fun pasteLatestClip() {
        val latest = loadClips().maxByOrNull { it.createdAt }
        if (latest != null) {
            commit(latest.text, learn = false)
        } else {
            recentExpanded = false
            panel = Panel.CLIPBOARD
            render()
            root.post { resizeClipboardWindow(false) }
            refreshClipboardAfterOpen()
        }
    }

    private fun refreshClipboardAfterOpen() {
        root.postDelayed({
            if (panel == Panel.CLIPBOARD && captureCurrentClipboard()) render()
        }, 50)
    }

    private fun pasteClip(text: String) {
        commit(text, learn = false)
        clipboardExpanded = false
        recentExpanded = false
        panel = Panel.KEYS
        render()
        root.post { resizeClipboardWindow(false) }
    }

    private fun toggleSymbols() {
        numbers = !numbers
        shifted = false
        symbolPage = 0
        diacriticPopup?.dismiss()
        panel = Panel.KEYS
        render()
    }

    private fun toggleSymbolPage() {
        symbolPage = if (symbolPage == 0) 1 else 0
        render()
    }

    private fun toggleLanguage() {
        arabic = !arabic
        shifted = false
        numbers = false
        symbolPage = 0
        diacriticPopup?.dismiss()
        currentWordBuffer.setLength(0)
        panel = Panel.KEYS
        render()
    }

    private fun renderKeys() {
        if (!sensitiveFieldMode) {
            root.addView(renderSuggestionRow(), LinearLayout.LayoutParams(-1, dp(29)))
        }
        val numberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        "1234567890".forEach { character ->
            numberRow.addView(key(character.toString()) { commit(character.toString()) }, keyParams())
        }
        root.addView(numberRow, rowParams())

        keyRows.forEach { rowKeys ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER
            }
            rowKeys.forEach { character ->
                val label = if (!numbers && !arabic && shifted && character.length == 1) {
                    character.uppercase(java.util.Locale.ROOT)
                } else {
                    character
                }
                row.addView(
                    key(label) {
                        if (numbers && (character == "=\\<" || character == "123")) {
                            toggleSymbolPage()
                        } else if (character == "⌫") {
                            deleteSelectionOrPreviousCharacter()
                        } else {
                            pressCharacter(character)
                        }
                    },
                    keyParams(),
                )
            }
            root.addView(row, rowParams())
        }

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        bottom.addView(
            action(if (numbers) { if (arabic) "أبج" else "ABC" } else "!#1") { toggleSymbols() },
            LinearLayout.LayoutParams(dp(42), dp(keyHeightDp() + 4)),
        )
        if (numbers) {
            bottom.addView(
                key(if (arabic) "،" else ",") { commit(if (arabic) "،" else ",") },
                LinearLayout.LayoutParams(dp(30), dp(keyHeightDp() + 4)),
            )
            bottom.addView(
                action("12\n34") { toggleSymbolPage() },
                LinearLayout.LayoutParams(dp(30), dp(keyHeightDp() + 4)),
            )
        } else {
            bottom.addView(
                key(if (arabic) "،" else ",") { commit(if (arabic) "،" else ",") },
                LinearLayout.LayoutParams(dp(32), dp(keyHeightDp() + 4)),
            )
            bottom.addView(
                action("🌐") { toggleLanguage() },
                LinearLayout.LayoutParams(dp(32), dp(keyHeightDp() + 4)),
            )
        }
        bottom.addView(
            key(if (arabic) "العربية" else "English") { commit(" ") }.apply { textSize = 12f },
            LinearLayout.LayoutParams(0, dp(keyHeightDp() + 4), 1f),
        )
        bottom.addView(periodKey(), LinearLayout.LayoutParams(dp(26), dp(keyHeightDp() + 4)))
        bottom.addView(
            action("→") { sendEnter() },
            LinearLayout.LayoutParams(dp(42), dp(keyHeightDp() + 4)),
        )
        root.addView(bottom, rowParams())
    }

    private fun renderNumericPad() {
        val rows = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        )
        rows.forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
            group.forEach { (digit, letters) ->
                row.addView(
                    numericDigitKey(digit, letters),
                    numericPadKeyParams(),
                )
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) },
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        controls.addView(
            key("⌫") { deleteSelectionOrPreviousCharacter() }.apply {
                textSize = 21f
                setTextColor(Color.rgb(35, 35, 48))
                background = rounded(Color.rgb(226, 216, 249), dp(26), Color.TRANSPARENT)
            },
            numericPadKeyParams(),
        )
        controls.addView(numericDigitKey("0", ""), numericPadKeyParams())
        controls.addView(
            action("✓") { performNumericEditorAction() }.apply {
                textSize = 22f
                setTextColor(Color.rgb(29, 39, 64))
                background = rounded(Color.rgb(154, 187, 255), dp(26), Color.TRANSPARENT)
            },
            numericPadKeyParams(),
        )
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(58)))
    }

    private fun numericDigitKey(digit: String, letters: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            background = rounded(Color.rgb(43, 44, 57), dp(27), Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { commit(digit) }

            addView(
                TextView(this@ArabicKeyboardService).apply {
                    text = digit
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(keyboardTextColor)
                },
                LinearLayout.LayoutParams(0, -1, 1f),
            )
            if (letters.isNotEmpty()) {
                addView(
                    TextView(this@ArabicKeyboardService).apply {
                        text = letters
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setTextColor(Color.rgb(190, 190, 202))
                    },
                    LinearLayout.LayoutParams(-2, -1),
                )
            }
        }

    private fun numericPadKeyParams() = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun performNumericEditorAction() {
        val action = if (
            editorAction == EditorInfo.IME_ACTION_NONE ||
            editorAction == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            EditorInfo.IME_ACTION_DONE
        } else {
            editorAction
        }
        currentInputConnection?.performEditorAction(action)
    }

    private fun periodKey(): TextView {
        var longPressTriggered = false
        val view = action(".") { commit(".") }
        val longPress = Runnable {
            longPressTriggered = true
            showDiacriticPopup(view)
        }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    popupHandler.removeCallbacks(longPress)
                    popupHandler.postDelayed(
                        longPress,
                        android.view.ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                    true
                }
                MotionEvent.ACTION_UP -> {
                    popupHandler.removeCallbacks(longPress)
                    if (!longPressTriggered) {
                        target.performClick()
                        showKeyPreview(".", target)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    popupHandler.removeCallbacks(longPress)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.x < 0 || event.x > target.width || event.y < 0 || event.y > target.height) {
                        popupHandler.removeCallbacks(longPress)
                    }
                    true
                }
                else -> false
            }
        }
        return view
    }

    private fun showDiacriticPopup(anchor: View) {
        diacriticPopup?.dismiss()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(Color.rgb(48, 49, 61), dp(24), Color.rgb(65, 66, 78))
        }
        val plainOptions = setOf("ـ", "؟", "!")
        val options = listOf("ً", "ٌ", "ٍ", "َ", "ُ", "ِ", "ْ", "ّ", "ٓ", "ٰ", "ٔ", "ٕ", "ۡ", "ـ", "؟", "!")
        options.chunked(8).forEachIndexed { rowIndex, rowOptions ->
            val optionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowOptions.forEach { option ->
                val key = TextView(this).apply {
                    text = if (option in plainOptions) option else "◌$option"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(keyboardTextColor)
                    background = rounded(keyboardKeyBackground, dp(20), Color.TRANSPARENT)
                    setOnClickListener {
                        commit(option)
                        diacriticPopup?.dismiss()
                        diacriticPopup = null
                    }
                }
                optionRow.addView(
                    key,
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    },
                )
            }
            content.addView(
                optionRow,
                LinearLayout.LayoutParams(-1, dp(42)).apply {
                    if (rowIndex == 0) bottomMargin = dp(4)
                },
            )
        }
        val popupWidth = (resources.displayMetrics.widthPixels - dp(18)).coerceAtMost(dp(420))
        val popupHeight = dp(102)
        diacriticPopup = PopupWindow(content, popupWidth, popupHeight, false).apply {
            isTouchable = true
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setBackgroundDrawable(rounded(Color.TRANSPARENT, dp(24), Color.TRANSPARENT))
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        diacriticPopup?.showAtLocation(
            anchor,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            0,
            (location[1] - popupHeight - dp(8)).coerceAtLeast(dp(8)),
        )
    }

    private fun renderClipboard() {
        val scroller = ScrollView(this).apply { setBackgroundColor(keyboardBackground) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(if (clipboardExpanded) 5 else 1), 0, dp(if (clipboardExpanded) 6 else 2))
            setBackgroundColor(keyboardBackground)
        }
        val clips = loadClips()
        val recent = clips.filterNot { it.pinned }.sortedByDescending { it.createdAt }
        val pinned = clips.filter { it.pinned }.sortedByDescending { it.createdAt }
        content.addView(clipboardTitle(), LinearLayout.LayoutParams(-1, dp(26)))
        content.addView(
            recentSectionHeading(recent.size),
            LinearLayout.LayoutParams(-1, dp(if (clipboardExpanded) 34 else 28)),
        )
        addClipGrid(
            content,
            if (recentExpanded) recent else recent.take(3),
            compact = !clipboardExpanded,
        )
        if (recent.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "العناصر المنسوخة الجديدة تظهر هنا"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(145, 145, 145))
                background = rounded(Color.TRANSPARENT, dp(10), Color.rgb(58, 58, 58))
            }, LinearLayout.LayoutParams(-1, dp(54)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        content.addView(sectionTitle("المثبتة"))
        addClipGrid(content, pinned)
        var selectionActionsView: View? = null
        if (selectedClipIds.isNotEmpty()) {
            val selectedItems = clips.filter { it.id in selectedClipIds }
            val allSelectedPinned = selectedItems.isNotEmpty() && selectedItems.all { it.pinned }
            val selectionActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pinButton = action(
                if (allSelectedPinned) "إلغاء التثبيت" else "تثبيت",
                { toggleSelectedPins() },
                keyboardBackground,
            ).apply {
                textSize = 12f
                background = rounded(keyboardBackground, dp(9), Color.rgb(70, 70, 70))
            }
            selectionActions.addView(
                pinButton,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) },
            )
            val deleteButton = action("حذف المحدد (${selectedClipIds.size})", {
                val selected = selectedClipIds.toSet()
                saveClips(loadClips().filterNot { it.id in selected })
                selectedClipIds.clear()
                render()
            }, keyboardBackground).apply {
                textSize = 12f
                setTextColor(Color.rgb(239, 119, 119))
                background = rounded(keyboardBackground, dp(9), Color.rgb(113, 55, 55))
            }
            selectionActions.addView(deleteButton, LinearLayout.LayoutParams(0, dp(42), 1f))
            selectionActions.setPadding(dp(8), 0, dp(8), 0)
            selectionActionsView = selectionActions
        }
        if (recent.isNotEmpty()) {
            content.addView(action("مسح العناصر غير المثبتة") {
                saveClips(clips.filter { it.pinned })
                selectedClipIds.retainAll(clips.filter { it.pinned }.map { it.id }.toSet())
                render()
            }, LinearLayout.LayoutParams(-1, dp(38)))
        }
        scroller.addView(content)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        selectionActionsView?.let {
            root.addView(it, LinearLayout.LayoutParams(-1, dp(42)))
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(action("▦", {
            clipboardExpanded = false
            panel = Panel.KEYS
            render()
            root.post { resizeClipboardWindow(false) }
        }, keyboardBackground), LinearLayout.LayoutParams(dp(42), dp(32)))
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(action("⌄", {
            clipboardExpanded = false
            panel = Panel.KEYS
            render()
            root.post { resizeClipboardWindow(false) }
        }, keyboardBackground), LinearLayout.LayoutParams(dp(42), dp(32)))
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(34)))
    }

    private fun clipboardTitle(): View = TextView(this).apply {
        text = "الحافظة"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(keyboardTextColor)
        setPadding(dp(6), 0, dp(6), 0)
        setOnTouchListener { _, event -> handleClipboardTitleTouch(event) }
    }

    private fun handleClipboardTitleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clipboardDragActive = true
                clipboardDragStartY = event.rawY
                clipboardDragStartTime = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                if (clipboardDragActive && event.eventTime - clipboardDragStartTime >= 80L) {
                    val dragThreshold = dp(12).toFloat()
                    val movedUp = event.rawY <= clipboardDragStartY - dragThreshold
                    val movedDown = event.rawY >= clipboardDragStartY + dragThreshold
                    if ((!clipboardExpanded && movedUp) || (clipboardExpanded && movedDown)) {
                        clipboardDragActive = false
                        setClipboardExpanded(!clipboardExpanded)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clipboardDragActive = false
        }
        return true
    }

    private fun toggleClipSelection(id: String) {
        if (!selectedClipIds.add(id)) selectedClipIds.remove(id)
        render()
    }

    private fun toggleSelectedPins() {
        val current = loadClips()
        val selected = current.filter { it.id in selectedClipIds }
        if (selected.isEmpty()) return
        val shouldPin = selected.any { !it.pinned }
        saveClips(current.map { item ->
            if (item.id in selectedClipIds) item.copy(pinned = shouldPin) else item
        })
        selectedClipIds.clear()
        render()
    }

    private fun addClipGrid(parent: LinearLayout, items: List<ClipRecord>, compact: Boolean = false) {
        val cardHeight = if (clipboardExpanded) 0 else if (compact) 78 else 136
        val actionsHeight = if (selectedClipIds.isEmpty()) 0 else 24
        val textHeight = if (clipboardExpanded) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            cardHeight - 11 - actionsHeight
        }
        items.chunked(3).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            group.forEach { item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(5), dp(7), dp(6))
                    background = rounded(
                        if (item.id in selectedClipIds) keyboardPressedBackground else keyboardKeyBackground,
                        dp(10),
                        Color.rgb(46, 46, 46),
                    )
                }
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                if (selectedClipIds.isNotEmpty()) {
                    actions.addView(action(if (item.id in selectedClipIds) "✓" else "○") {
                        toggleClipSelection(item.id)
                    }, LinearLayout.LayoutParams(dp(28), dp(24)))
                }
                card.addView(actions, LinearLayout.LayoutParams(-1, dp(actionsHeight)))
                val textView = TextView(this).apply {
                    text = clipCardPreview(item.text)
                    textSize = 16f
                    maxLines = if (clipboardExpanded) Int.MAX_VALUE else if (compact) 3 else 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.TOP or Gravity.END
                    setPadding(0, dp(4), 0, 0)
                    setTextColor(keyboardTextColor)
                    setOnClickListener {
                        if (selectedClipIds.isNotEmpty()) {
                            toggleClipSelection(item.id)
                        } else {
                            pasteClip(item.text)
                        }
                    }
                    setOnLongClickListener {
                        toggleClipSelection(item.id)
                        true
                    }
                }
                card.addView(
                    textView,
                    LinearLayout.LayoutParams(
                        -1,
                        if (clipboardExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else dp(textHeight),
                    ),
                )
                row.addView(
                    card,
                    LinearLayout.LayoutParams(
                        0,
                        if (clipboardExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else dp(cardHeight),
                        1f,
                    ).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                        bottomMargin = dp(6)
                    },
                )
            }
            repeat(3 - group.size) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(
                        0,
                        if (clipboardExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else dp(cardHeight),
                        1f,
                    ).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    },
                )
            }
            parent.addView(
                row,
                LinearLayout.LayoutParams(
                    -1,
                    if (clipboardExpanded) ViewGroup.LayoutParams.WRAP_CONTENT else dp(cardHeight + 6),
                ),
            )
        }
    }

    private fun clipCardPreview(text: String): String {
        val iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        iterator.setText(text)
        var boundary = iterator.first()
        repeat(30) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            boundary = next
        }
        return if (iterator.next() == BreakIterator.DONE) text else "${text.substring(0, boundary)}..."
    }

    private fun renderEmoji() {
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addEmojiTab(id: String?, icon: String, label: String) {
            val selected = selectedEmojiGroupId == id
            val tab = action(
                icon,
                {
                    if (selectedEmojiGroupId != id) {
                        selectedEmojiGroupId = id
                        render()
                    }
                },
                if (selected) keyboardPressedBackground else keyboardBackground,
            ).apply {
                textSize = 20f
                contentDescription = label
                background = rounded(
                    if (selected) keyboardPressedBackground else keyboardBackground,
                    dp(18),
                    if (selected) Color.rgb(88, 88, 88) else Color.TRANSPARENT,
                )
            }
            tabRow.addView(
                tab,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                },
            )
        }
        addEmojiTab(null, "🕘", "المستخدمة مؤخرًا")
        emojiGroups.forEach { group -> addEmojiTab(group.id, group.icon, group.label) }

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(tabRow, ViewGroup.LayoutParams(-2, -1))
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, dp(40)))

        val visibleEmojis = selectedEmojiGroupId
            ?.let { id -> emojiGroups.firstOrNull { it.id == id }?.emojis }
            ?: loadRecentEmojis()
        val grid = GridView(this).apply {
            val columns = (resources.configuration.screenWidthDp / 44).coerceIn(5, 9)
            numColumns = columns
            horizontalSpacing = dp(3)
            verticalSpacing = dp(3)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setSelector(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isVerticalScrollBarEnabled = false
            adapter = object : BaseAdapter() {
                override fun getCount() = visibleEmojis.size
                override fun getItem(position: Int) = visibleEmojis[position]
                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val cell = (convertView as? TextView) ?: TextView(this@ArabicKeyboardService).apply {
                        gravity = Gravity.CENTER
                        textSize = 26f
                        minimumHeight = dp(42)
                        includeFontPadding = false
                        setTextColor(keyboardTextColor)
                        background = rounded(keyboardKeyBackground, dp(8), Color.TRANSPARENT)
                        isClickable = false
                        isFocusable = false
                    }
                    cell.text = visibleEmojis[position]
                    return cell
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                val emoji = visibleEmojis[position]
                rememberEmoji(emoji)
                commit(emoji, learn = false)
                if (selectedEmojiGroupId == null) render()
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun loadRecentEmojis(): List<String> {
        return try {
            val saved = JSONArray(prefs.getString("recent_emojis", "[]"))
            List(saved.length()) { saved.optString(it) }
                .filter { it in emojiItems }
                .distinct()
                .take(32)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun rememberEmoji(emoji: String) {
        val next = (listOf(emoji) + loadRecentEmojis().filterNot { it == emoji }).take(32)
        val saved = JSONArray()
        next.forEach { saved.put(it) }
        prefs.edit().putString("recent_emojis", saved.toString()).apply()
    }

    private fun captureCurrentClipboard(): Boolean {
        return try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip ?: return false
            val description = clip.description
            val sensitive = if (android.os.Build.VERSION.SDK_INT >= 33) {
                description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            } else {
                description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            }
            if (sensitive || clip.itemCount == 0) return false
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return false
            val current = loadClips()
            val existing = current.find { it.text == text }
            val record = ClipRecord(
                id = existing?.id ?: "${System.currentTimeMillis()}",
                text = text,
                createdAt = System.currentTimeMillis(),
                pinned = existing?.pinned ?: false,
            )
            saveClips((listOf(record) + current.filterNot { it.text == text }).take(80))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun showKeyPreview(label: String, anchor: View) {
        val popup = keyPopup ?: return
        val previewText = keyPopupText ?: return
        previewText.text = label
        val popupWidth = dp(48)
        val popupHeight = dp(50)
        val xOffset = (anchor.width - popupWidth) / 2
        previewText.textSize = 26f
        previewText.background = rounded(Color.BLACK, dp(12), Color.rgb(55, 55, 55))
        popupHandler.removeCallbacks(hideKeyPopup)
        runCatching {
            val yOffset = -(anchor.height + popupHeight)
            if (popup.isShowing) {
                popup.update(anchor, xOffset, yOffset, popupWidth, popupHeight)
            } else {
                popup.showAsDropDown(anchor, xOffset, yOffset)
            }
            popupHandler.postDelayed(hideKeyPopup, 125)
        }.onFailure {
            popup.dismiss()
        }
    }

    private fun prepareKeyPreview() {
        val previewText = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(keyboardTextColor)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = rounded(Color.BLACK, dp(12), Color.rgb(55, 55, 55))
        }
        keyPopupText = previewText
        keyPopup = PopupWindow(previewText, dp(48), dp(50), false).apply {
            isTouchable = false
            isOutsideTouchable = false
            elevation = dp(8).toFloat()
            setBackgroundDrawable(rounded(Color.TRANSPARENT, dp(12), Color.TRANSPARENT))
        }
    }

    private fun loadClips(): List<ClipRecord> {
        return try {
            val array = JSONArray(prefs.getString("history", "[]"))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        ClipRecord(
                            id = item.optString("id"),
                            text = item.optString("text"),
                            createdAt = item.optLong("createdAt"),
                            pinned = item.optBoolean("pinned"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveClips(items: List<ClipRecord>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("createdAt", item.createdAt)
                    .put("pinned", item.pinned),
            )
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private fun sectionTitle(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(keyboardTextColor)
        setPadding(dp(6), dp(4), dp(6), dp(2))
    }

    private fun recentSectionHeading(count: Int, showToggle: Boolean = true): View {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        val recentLabel = sectionTitle("المنسوخة مؤخرًا").apply {
            setOnTouchListener { _, event -> handleClipboardTitleTouch(event) }
        }
        heading.addView(recentLabel, LinearLayout.LayoutParams(0, -1, 1f))
        if (count > 3 && showToggle) {
            val toggle = TextView(this).apply {
                text = if (recentExpanded) "عرض أقل" else "إظهار المزيد"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(118, 207, 194))
                setPadding(dp(5), 0, dp(5), 0)
                isClickable = true
                setOnClickListener {
                    recentExpanded = !recentExpanded
                    render()
                }
            }
            heading.addView(toggle, LinearLayout.LayoutParams(-2, -1))
        }
        return heading
    }

    private fun key(label: String, onClick: () -> Unit): TextView {
        val view = action(label, onClick, keyboardKeyBackground).apply {
            textSize = if (arabic) 23f else 21f
            typeface = Typeface.create(
                "sans-serif",
                if (arabic) Typeface.BOLD else Typeface.NORMAL,
            )
            textDirection = if (arabic) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
            isSingleLine = true
            maxLines = 1
            includeFontPadding = true
            textLocale = java.util.Locale.forLanguageTag(if (arabic) "ar" else "en")
            background = android.graphics.drawable.InsetDrawable(
                rounded(keyboardKeyBackground, dp(8), Color.rgb(104, 104, 104)),
                dp(2),
                0,
                dp(2),
                0,
            )
        }
        var deleteHeld = false
        val repeatDelete: Runnable? = if (label == "⌫") {
            object : Runnable {
                override fun run() {
                    if (!deleteHeld || !view.isAttachedToWindow) return
                    view.performClick()
                    popupHandler.postDelayed(this, 35)
                }
            }
        } else {
            null
        }
        val stopDeleteRepeat = {
            deleteHeld = false
            repeatDelete?.let {
                popupHandler.removeCallbacks(it)
                if (activeDeleteRepeat === it) activeDeleteRepeat = null
            }
        }
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (label == "⌫") {
                        deleteHeld = true
                        target.performClick()
                        showKeyPreview(label, target)
                        repeatDelete?.let {
                            activeDeleteRepeat?.let(popupHandler::removeCallbacks)
                            activeDeleteRepeat = it
                            popupHandler.postDelayed(it, 120)
                        }
                    } else {
                        if (label == "⇧") showKeyPreview(label, target)
                        target.performClick()
                        if (label != "⇧" && label != "=\\<" && label != "123" &&
                            label != "العربية" && label != "English"
                        ) {
                            target.post { showKeyPreview(label, target) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    stopDeleteRepeat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (label == "⌫" &&
                        (event.x < 0 || event.x > target.width || event.y < 0 || event.y > target.height)
                    ) {
                        stopDeleteRepeat()
                    }
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { onClick() }
        return view
    }

    private fun action(
        label: String,
        onClick: () -> Unit,
        backgroundColor: Int = keyboardKeyBackground,
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(keyboardTextColor)
            background = rounded(backgroundColor, dp(9), Color.TRANSPARENT)
            setOnClickListener { onClick() }
            isClickable = true
        }

    private fun action(
        label: String,
        backgroundColor: Int = keyboardKeyBackground,
        onClick: () -> Unit,
    ): TextView = action(label, onClick, backgroundColor)

    private fun pressCharacter(key: String) {
        if (key == "⇧") {
            shifted = !shifted
            render()
            return
        }
        val output = if (!arabic && shifted && key.length == 1) {
            key.uppercase(java.util.Locale.ROOT)
        } else {
            key
        }
        commit(output)
        if (!arabic && shifted) {
            shifted = false
            render()
        }
    }

    private fun commit(text: String) {
        commit(text, learn = true)
    }

    private fun commit(text: String, learn: Boolean) {
        val connection = currentInputConnection ?: return
        updateWordBuffer(text, learn)
        expectInternalSelectionUpdate()
        connection.commitText(text, 1)
    }

    private fun deleteSelectionOrPreviousCharacter() {
        val connection = currentInputConnection ?: return
        val selectedText = connection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            expectInternalSelectionUpdate()
            connection.commitText("", 1)
            currentWordBuffer.setLength(0)
        } else {
            expectInternalSelectionUpdate()
            val beforeCursor = connection.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            if (beforeCursor.isEmpty()) {
                connection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                val graphemes = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
                graphemes.setText(beforeCursor)
                val start = graphemes.preceding(beforeCursor.length)
                if (start == BreakIterator.DONE) {
                    connection.deleteSurroundingTextInCodePoints(1, 0)
                } else {
                    connection.deleteSurroundingText(beforeCursor.length - start, 0)
                }
            }
            removeLastWordCharacter()
        }
        refreshSuggestionRow()
    }

    private fun sendEnter() {
        commit("\n", learn = true)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun keyParams() = LinearLayout.LayoutParams(0, dp(keyHeightDp()), 1f).apply {
        marginStart = 0
        marginEnd = 0
        bottomMargin = dp(4)
    }

    private fun toolbarKeyParams() = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }

    private fun rowParams(bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, dp(keyHeightDp() + 5)).apply { bottomMargin = dp(bottom) }

    private fun keyHeightDp(): Int {
        val targetHeightDp =
            (defaultImeHeightPx ?: computeDefaultImeHeightPx()).toFloat() / resources.displayMetrics.density
        val suggestionHeightDp = if (sensitiveFieldMode || numericFieldMode) 0 else 29
        val rowCount = 2 + keyRows.size
        val fixedHeightDp = 42 + suggestionHeightDp + 4
        return ((targetHeightDp - fixedHeightDp) / rowCount - 5)
            .toInt()
            .coerceIn(32, 50)
    }

    private fun computeDefaultImeHeightPx(): Int =
        (resources.displayMetrics.heightPixels * 0.38f).toInt().coerceAtLeast(dp(230))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
