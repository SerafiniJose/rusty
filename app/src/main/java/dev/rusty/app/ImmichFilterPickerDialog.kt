package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.request.Disposable
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card picker for one filter category (albums / people / tags).
 *
 * Pure UI: never touches prefs or the repository — selection changes go out through
 * [onSelectionChanged] (the panel persists them), and the item list comes in fully
 * fetched. Row order is FROZEN at open (ImmichPickerModel.pickerOrder); search filters
 * that frozen order off-main with latest-wins; toggling only flips the check + count.
 *
 * Thumbnails: every request carries the x-api-key header (the shared loader has no
 * credentials of its own) and generation-namespaced cache keys, and its [Disposable] is
 * tracked so dismissal — including forced dismissal on a connection change — disposes
 * every in-flight request.
 */
class ImmichFilterPickerDialog(
    private val activity: Activity,
    private val kind: ImmichFilterKind,
    items: List<ImmichPickerItem>,
    selectedIds: Set<String>,
    private val cfg: ImmichConfig?,
    private val generation: Int,
    private val onSelectionChanged: (List<String>) -> Unit,
    private val onDismissed: () -> Unit,
) {
    private val selected = selectedIds.toMutableSet()
    private val baseOrder = ImmichPickerModel.pickerOrder(items, selectedIds, kind)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val disposables = mutableListOf<Disposable>()
    private var searchJob: Job? = null
    private var silenced = false
    private var dialog: Dialog? = null

    /**
     * Declared here, with the other fields, rather than beside [renderSelectedCount] further
     * down: the click listener installed in `onBindViewHolder` calls that function, and a
     * property whose initializer has not run yet would be a live ordering hazard. It is also
     * assigned before the adapter is attached in [show], so the `?.` below is belt-and-braces.
     */
    private var selectedCountView: TextView? = null

    private fun thumbUrl(item: ImmichPickerItem): String? {
        val c = cfg ?: return null
        return when (kind) {
            ImmichFilterKind.ALBUMS -> item.thumbAssetId?.let { ImmichRepository.shared.previewUrl(c, it) }
            ImmichFilterKind.PEOPLE -> ImmichRepository.shared.personThumbUrl(c, item.id)
            ImmichFilterKind.TAGS -> null
        }
    }

    private inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.imgPickerThumb)
        val name: TextView = view.findViewById(R.id.tvPickerName)
        val count: TextView = view.findViewById(R.id.tvPickerCount)
        val check: TextView = view.findViewById(R.id.tvPickerCheck)
    }

    private val adapter = object : ListAdapter<ImmichPickerItem, RowHolder>(
        object : DiffUtil.ItemCallback<ImmichPickerItem>() {
            override fun areItemsTheSame(a: ImmichPickerItem, b: ImmichPickerItem) = a.id == b.id
            override fun areContentsTheSame(a: ImmichPickerItem, b: ImmichPickerItem) = a == b
        },
    ) {
        init { setHasStableIds(true) }
        private val ids = HashMap<String, Long>()
        override fun getItemId(position: Int): Long =
            ids.getOrPut(getItem(position).id) { ids.size.toLong() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(LayoutInflater.from(parent.context)
                .inflate(R.layout.view_immich_picker_row, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = getItem(position)
            holder.name.text = item.label
            holder.count.visibility = if (item.count != null) View.VISIBLE else View.GONE
            item.count?.let { holder.count.text = if (it == 1) "1 photo" else "$it photos" }
            renderCheck(holder.check, item.id in selected)
            bindThumb(holder.thumb, item)
            holder.itemView.setOnClickListener {
                val nowSelected = item.id !in selected
                if (nowSelected) selected.add(item.id) else selected.remove(item.id)
                renderCheck(holder.check, nowSelected)
                renderSelectedCount()
                onSelectionChanged(selected.toList())
            }
        }
    }

    private fun renderCheck(check: TextView, isSelected: Boolean) {
        val accent = ContextCompat.getColor(activity, R.color.accent_fallback)
        val border = ContextCompat.getColor(activity, R.color.surface_border)
        check.text = if (isSelected) "✓" else ""
        check.setTextColor(ContextCompat.getColor(activity, R.color.surface))
        check.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (isSelected) setColor(accent) else setStroke(dp(1.5f), border)
        }
    }

    private fun bindThumb(view: ImageView, item: ImmichPickerItem) {
        val url = thumbUrl(item)
        val placeholder = GradientDrawable().apply {
            if (kind == ImmichFilterKind.PEOPLE) shape = GradientDrawable.OVAL
            else cornerRadius = dp(8f).toFloat()
            setColor(ContextCompat.getColor(activity, R.color.surface_border))
        }
        view.setImageDrawable(placeholder)
        val c = cfg
        if (url == null || c == null) {
            // A bind that enqueues nothing must still hand the ImageView back empty. Coil only
            // drops a view's previous request when a NEW one is enqueued against it; recycling a
            // row is a real detach→attach, and on re-attach Coil RESTARTS the retained request —
            // the previous album's URL, into this row. (Reachable: albumThumbnailAssetId is null
            // for empty albums, and the synthetic "Unknown" rows carry no thumb at all, so
            // thumb/no-thumb rows are interleaved.) dispose() clears the manager's held request,
            // so there is nothing left to restart.
            view.dispose()
            return
        }
        val key = ImmichPickerModel.thumbCacheKey(generation, url)
        // Coil disposes a view's previous request itself when a recycled row rebinds, so most
        // entries here are already dead; without a prune the list would still grow by one per
        // bind and keep every completed request reachable for the whole scroll session.
        if (disposables.size >= PRUNE_AFTER) disposables.removeAll { it.isDisposed }
        disposables += ImmichImages.loader(activity.applicationContext).enqueue(
            ImageRequest.Builder(activity)
                .data(url)
                .setHeader("x-api-key", c.apiKey)
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .transformations(
                    if (kind == ImmichFilterKind.PEOPLE) CircleCropTransformation()
                    else RoundedCornersTransformation(dp(8f).toFloat()),
                )
                .placeholder(placeholder)
                .error(placeholder)
                .target(view)
                .build(),
        )
    }

    fun show() {
        val root = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_immich_filter_picker, null)
        val d = Dialog(activity)
        dialog = d
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // adjustResize: the IME shrinks the card instead of covering the Done pill.
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
            )
        }
        // Re-sizes on rotation too — this card outlives a configuration change (see followDisplaySize).
        d.followDisplaySize(activity, heightFraction = 0.80f)

        root.findViewById<TextView>(R.id.tvPickerTitle).text = "Choose ${kind.title.lowercase()}"
        val search = root.findViewById<EditText>(R.id.etPickerSearch)
        // baseOrder.size, not items.size: it is exactly what the list shows and what search
        // filters, synthetic "Unknown" rows included. Count via unitCount so a one-row category
        // doesn't read "Search 1 albums".
        search.hint = "Search ${ImmichPickerModel.unitCount(baseOrder.size, kind)}"
        val list = root.findViewById<RecyclerView>(R.id.rvPickerItems)
        val empty = root.findViewById<TextView>(R.id.tvPickerEmpty)
        val done = root.findViewById<MaterialButton>(R.id.btnPickerDone)
        // Resolved BEFORE the adapter is attached: the row click listener calls
        // renderSelectedCount(), so the header must be wired first for the very first tap.
        selectedCountView = root.findViewById(R.id.tvPickerSelectedCount)
        renderSelectedCount()
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        adapter.submitList(baseOrder)

        search.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            searchJob?.cancel() // latest-wins: a new keystroke cancels the previous computation
            searchJob = scope.launch {
                val filtered = withContext(Dispatchers.Default) {
                    ImmichPickerModel.filterItems(baseOrder, query)
                }
                empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(filtered)
            }
        }
        // IME Done hides the keyboard and hands focus to the list (or the Done pill on
        // zero matches) so D-pad users land back on the focus chain.
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (adapter.itemCount > 0) list.requestFocus() else done.requestFocus()
            }
            false
        }

        root.findViewById<View>(R.id.btnPickerClose).setOnClickListener { d.dismiss() }
        done.setOnClickListener { d.dismiss() }
        d.setOnDismissListener {
            searchJob?.cancel()
            scope.cancel()
            disposables.forEach { it.dispose() }
            disposables.clear()
            if (!silenced) onDismissed()
        }
        d.show()
        // Initial focus: first row for D-pad entry (keyboard stays hidden); empty list
        // (selected-only mode with nothing selected can't happen — panel guards) → search.
        if (adapter.itemCount > 0) list.requestFocus() else search.requestFocus()
    }

    private fun renderSelectedCount() {
        selectedCountView?.text = if (selected.isEmpty()) "" else "${selected.size} selected"
    }

    /** Teardown/connection-change path: dismiss without the onDismissed re-render. */
    fun dismissSilently() {
        silenced = true
        dialog?.dismiss()
        dialog = null
    }

    private fun dp(v: Float): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private companion object {
        /** Prune threshold for [disposables]; roughly a few screens' worth of row binds. */
        const val PRUNE_AFTER = 64
    }
}
