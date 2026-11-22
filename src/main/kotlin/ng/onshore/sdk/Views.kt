package ng.onshore.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

private val io = Executors.newCachedThreadPool()
private val main = Handler(Looper.getMainLooper())

private val NUMERIC = setOf("bvn", "nin", "ng_11_digit_id", "nuban")

private fun Context.activity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Marks the window secure while this view is showing: no screenshots, screen recording or recent-apps preview. */
private class SecureWindow(private val view: View) : View.OnAttachStateChangeListener {
    override fun onViewAttachedToWindow(v: View) {
        view.context.activity()?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    override fun onViewDetachedFromWindow(v: View) {
        view.context.activity()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private val noActions = object : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) {}
}

/**
 * Collects values, and hands your app only tokens. It has no method that returns what was
 * typed. A value still exists in your app's process while it is typed, so this keeps it out
 * of your code and logs, not out of a compromised app, which could walk the view tree.
 */
@SuppressLint("ViewConstructor")
class CollectView(
    context: Context,
    private val client: SecureClient,
    private val clientToken: String,
    private val labels: Map<String, String> = emptyMap(),
    submitLabel: String = "Continue",
    private val onTokens: (Map<String, String>) -> Unit,
    private val onError: (VaultException) -> Unit = {},
) : LinearLayout(context) {
    private val inputs = LinkedHashMap<String, EditText>()
    private val problems = HashMap<String, TextView>()
    private val submit = Button(context).apply { text = submitLabel; isEnabled = false }

    init {
        orientation = VERTICAL
        addOnAttachStateChangeListener(SecureWindow(this))
        filterTouchesWhenObscured = true // refuses taps through an overlay drawn by another app
        addView(submit)
        submit.setOnClickListener { send() }
        io.execute {
            try {
                val d = client.describe(clientToken)
                main.post { build(d.fields) }
            } catch (e: VaultException) {
                main.post { onError(e) }
            }
        }
    }

    private fun build(fields: List<FieldDescription>) {
        fields.forEachIndexed { i, f ->
            val title = labels[f.name] ?: f.name
            val label = TextView(context).apply { text = title }
            val input = EditText(context).apply {
                contentDescription = title
                inputType = if (f.dataType in NUMERIC) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isSaveEnabled = false // not in saved instance state, so not in a bundle on disk
                isLongClickable = false
                customSelectionActionModeCallback = noActions
                customInsertionActionModeCallback = noActions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                filterTouchesWhenObscured = true
            }
            val problem = TextView(context).apply { text = "Check this value"; setTextColor(Color.RED); visibility = GONE }
            val at = i * 3
            addView(label, at)
            addView(input, at + 1)
            addView(problem, at + 2)
            inputs[f.name] = input
            problems[f.name] = problem
        }
        submit.isEnabled = fields.isNotEmpty()
    }

    /** Sends what was typed. The same as tapping the button, for apps with their own. */
    fun submit() = send()

    private fun send() {
        submit.isEnabled = false
        problems.values.forEach { it.visibility = GONE }
        val values = inputs.mapValues { it.value.text.toString() }
        io.execute {
            try {
                val tokens = client.collect(clientToken, values)
                main.post {
                    inputs.values.forEach { it.text.clear() }
                    onTokens(tokens)
                }
            } catch (e: VaultException) {
                main.post {
                    if (e is VaultException.ValidationFailed) e.fields.forEach { problems[it]?.visibility = VISIBLE }
                    submit.isEnabled = true
                    onError(e)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        inputs.values.forEach { it.text.clear() }
        super.onDetachedFromWindow()
    }
}

/** Shows revealed values, and hides them after [hideAfterMs], or when it leaves the screen. */
@SuppressLint("ViewConstructor")
class RevealView(
    context: Context,
    client: SecureClient,
    clientToken: String,
    private val hideAfterMs: Long = 30_000,
    private val onError: (VaultException) -> Unit = {},
) : LinearLayout(context) {
    private val hide = Runnable { removeAllViews() }

    init {
        orientation = VERTICAL
        addOnAttachStateChangeListener(SecureWindow(this))
        io.execute {
            try {
                val results = client.reveal(clientToken)
                main.post { show(results) }
            } catch (e: VaultException) {
                main.post { onError(e) }
            }
        }
    }

    private fun show(results: List<RevealedValue>) {
        results.forEach { r ->
            addView(TextView(context).apply {
                text = r.value ?: if (r.errorCode == "policy_denied") "You do not have access to this" else "Not available"
                setTextIsSelectable(false)
                isSaveEnabled = false
            })
        }
        main.postDelayed(hide, hideAfterMs)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) removeAllViews()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(hide)
        removeAllViews()
        super.onDetachedFromWindow()
    }
}
