package com.simplemobiletools.gallery.adapters

import android.os.Build
import android.support.v7.view.ActionMode
import android.support.v7.widget.RecyclerView
import android.util.SparseArray
import android.view.*
import android.widget.FrameLayout
import com.bignerdranch.android.multiselector.ModalMultiSelectorCallback
import com.bignerdranch.android.multiselector.MultiSelector
import com.bignerdranch.android.multiselector.SwappingHolder
import com.bumptech.glide.Glide
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.dialogs.RenameItemDialog
import com.simplemobiletools.commons.extensions.isAStorageRootFolder
import com.simplemobiletools.commons.extensions.isImageVideoGif
import com.simplemobiletools.commons.extensions.needsStupidWritePermissions
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.activities.SimpleActivity
import com.simplemobiletools.gallery.dialogs.ExcludeFolderDialog
import com.simplemobiletools.gallery.extensions.*
import com.simplemobiletools.gallery.models.Directory
import kotlinx.android.synthetic.main.directory_item.view.*
import kotlinx.android.synthetic.main.directory_tmb.view.*
import java.io.File
import java.util.*

class DirectoryAdapter(val activity: SimpleActivity, var dirs: MutableList<Directory>, val listener: DirOperationsListener?, val itemClick: (Directory) -> Unit) :
        RecyclerView.Adapter<DirectoryAdapter.ViewHolder>() {

    val multiSelector = MultiSelector()
    val config = activity.config

    var actMode: ActionMode? = null
    var itemViews = SparseArray<View>()
    val selectedPositions = HashSet<Int>()
    var foregroundColor = 0
    var pinnedFolders = config.pinnedFolders

    fun toggleItemSelection(select: Boolean, pos: Int) {
        if (itemViews[pos] != null)
            getProperView(itemViews[pos]!!).isSelected = select

        if (select)
            selectedPositions.add(pos)
        else
            selectedPositions.remove(pos)

        if (selectedPositions.isEmpty()) {
            actMode?.finish()
            return
        }

        updateTitle(selectedPositions.size)
        actMode?.invalidate()
    }

    fun getProperView(itemView: View): View {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            itemView.dir_frame
        else
            itemView.dir_thumbnail
    }

    fun updateTitle(cnt: Int) {
        actMode?.title = "$cnt / ${dirs.size}"
    }

    fun updatePrimaryColor(color: Int) {
        foregroundColor = color
        (0..itemViews.size() - 1).mapNotNull { itemViews[it] }
                .forEach { setupItemViewForeground(it) }
    }

    private fun setupItemViewForeground(itemView: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            (getProperView(itemView) as FrameLayout).foreground = foregroundColor.createSelector()
        else
            getProperView(itemView).foreground = foregroundColor.createSelector()
    }

    val adapterListener = object : MyAdapterListener {
        override fun toggleItemSelectionAdapter(select: Boolean, position: Int) {
            toggleItemSelection(select, position)
        }

        override fun setupItemForeground(itemView: View) {
            setupItemViewForeground(itemView)
        }

        override fun getSelectedPositions(): HashSet<Int> = selectedPositions
    }

    init {
        foregroundColor = config.primaryColor
    }

    val multiSelectorMode = object : ModalMultiSelectorCallback(multiSelector) {
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                R.id.cab_properties -> showProperties()
                R.id.cab_rename -> renameDir()
                R.id.cab_pin -> pinFolders(true)
                R.id.cab_unpin -> pinFolders(false)
                R.id.cab_hide -> toggleFoldersVisibility(true)
                R.id.cab_unhide -> toggleFoldersVisibility(false)
                R.id.cab_exclude -> tryExcludeFolder()
                R.id.cab_copy_to -> copyMoveTo(true)
                R.id.cab_move_to -> copyMoveTo(false)
                R.id.cab_select_all -> selectAll()
                R.id.cab_delete -> askConfirmDelete()
                else -> return false
            }
            return true
        }

        override fun onCreateActionMode(actionMode: ActionMode?, menu: Menu?): Boolean {
            super.onCreateActionMode(actionMode, menu)
            actMode = actionMode
            activity.menuInflater.inflate(R.menu.cab_directories, menu)
            return true
        }

        override fun onPrepareActionMode(actionMode: ActionMode?, menu: Menu): Boolean {
            menu.findItem(R.id.cab_rename).isVisible = selectedPositions.size <= 1

            checkHideBtnVisibility(menu)
            checkPinBtnVisibility(menu)

            return true
        }

        override fun onDestroyActionMode(actionMode: ActionMode?) {
            super.onDestroyActionMode(actionMode)
            selectedPositions.forEach {
                if (itemViews[it] != null)
                    getProperView(itemViews[it]!!).isSelected = false
            }
            selectedPositions.clear()
            actMode = null
        }

        fun checkHideBtnVisibility(menu: Menu) {
            var hiddenCnt = 0
            var unhiddenCnt = 0
            selectedPositions.map { dirs.getOrNull(it)?.path }.filterNotNull().forEach {
                if (File(it).containsNoMedia())
                    hiddenCnt++
                else
                    unhiddenCnt++
            }

            menu.findItem(R.id.cab_hide).isVisible = unhiddenCnt > 0
            menu.findItem(R.id.cab_unhide).isVisible = hiddenCnt > 0
        }

        fun checkPinBtnVisibility(menu: Menu) {
            val pinnedFolders = config.pinnedFolders
            var pinnedCnt = 0
            var unpinnedCnt = 0
            selectedPositions.map { dirs.getOrNull(it)?.path }.filterNotNull().forEach {
                if (pinnedFolders.contains(it))
                    pinnedCnt++
                else
                    unpinnedCnt++
            }

            menu.findItem(R.id.cab_pin).isVisible = unpinnedCnt > 0
            menu.findItem(R.id.cab_unpin).isVisible = pinnedCnt > 0
        }
    }

    private fun showProperties() {
        if (selectedPositions.size <= 1) {
            PropertiesDialog(activity, dirs[selectedPositions.first()].path, config.shouldShowHidden)
        } else {
            val paths = ArrayList<String>()
            selectedPositions.forEach { paths.add(dirs[it].path) }
            PropertiesDialog(activity, paths, config.shouldShowHidden)
        }
    }

    private fun renameDir() {
        val path = dirs[selectedPositions.first()].path
        val dir = File(path)
        if (activity.isAStorageRootFolder(dir.absolutePath)) {
            activity.toast(R.string.rename_folder_root)
            return
        }

        RenameItemDialog(activity, dir.absolutePath) {
            activity.runOnUiThread {
                listener?.refreshItems()
                actMode?.finish()
            }
        }
    }

    private fun toggleFoldersVisibility(hide: Boolean) {
        getSelectedPaths().forEach {
            if (hide) {
                if (config.wasHideFolderTooltipShown) {
                    hideFolder(it)
                } else {
                    config.wasHideFolderTooltipShown = true
                    ConfirmationDialog(activity, activity.getString(R.string.hide_folder_description)) {
                        hideFolder(it)
                    }
                }
            } else {
                activity.removeNoMedia(it) {
                    noMediaHandled()
                }
            }
        }
    }

    private fun hideFolder(path: String) {
        activity.addNoMedia(path) {
            noMediaHandled()
        }
    }

    private fun tryExcludeFolder() {
        ExcludeFolderDialog(activity, getSelectedPaths().toList()) {
            listener?.refreshItems()
            actMode?.finish()
        }
    }

    private fun noMediaHandled() {
        activity.runOnUiThread {
            listener?.refreshItems()
            actMode?.finish()
        }
    }

    private fun pinFolders(pin: Boolean) {
        if (pin)
            config.addPinnedFolders(getSelectedPaths())
        else
            config.removePinnedFolders(getSelectedPaths())

        pinnedFolders = config.pinnedFolders
        listener?.refreshItems()
        notifyDataSetChanged()
        actMode?.finish()
    }

    private fun copyMoveTo(isCopyOperation: Boolean) {
        val files = ArrayList<File>()
        if (selectedPositions.isEmpty())
            return

        selectedPositions.forEach {
            val dir = File(dirs[it].path)
            files.addAll(dir.listFiles().filter { it.isFile && it.isImageVideoGif() })
        }

        activity.tryCopyMoveFilesTo(files, isCopyOperation) {
            listener?.refreshItems()
            actMode?.finish()
        }
    }

    fun selectAll() {
        val cnt = dirs.size
        for (i in 0..cnt - 1) {
            selectedPositions.add(i)
            notifyItemChanged(i)
        }
        updateTitle(cnt)
        actMode?.invalidate()
    }

    private fun askConfirmDelete() {
        ConfirmationDialog(activity) {
            deleteFiles()
            actMode?.finish()
        }
    }

    private fun deleteFiles() {
        val folders = ArrayList<File>(selectedPositions.size)
        val removeFolders = ArrayList<Directory>(selectedPositions.size)

        var needPermissionForPath = ""
        selectedPositions.forEach {
            val path = dirs[it].path
            if (activity.needsStupidWritePermissions(path) && config.treeUri.isEmpty()) {
                needPermissionForPath = path
            }
        }

        activity.handleSAFDialog(File(needPermissionForPath)) {
            selectedPositions.sortedDescending().forEach {
                val directory = dirs[it]
                folders.add(File(directory.path))
                removeFolders.add(directory)
                notifyItemRemoved(it)
                itemViews.put(it, null)
            }

            dirs.removeAll(removeFolders)
            selectedPositions.clear()
            listener?.tryDeleteFolders(folders)

            val newItems = SparseArray<View>()
            var curIndex = 0
            for (i in 0..itemViews.size() - 1) {
                if (itemViews[i] != null) {
                    newItems.put(curIndex, itemViews[i])
                    curIndex++
                }
            }

            itemViews = newItems
        }
    }

    private fun getSelectedPaths(): HashSet<String> {
        val paths = HashSet<String>(selectedPositions.size)
        selectedPositions.forEach { paths.add(dirs[it].path) }
        return paths
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent?.context).inflate(R.layout.directory_item, parent, false)
        return ViewHolder(view, adapterListener, itemClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val dir = dirs[position]
        itemViews.put(position, holder.bindView(activity, multiSelectorMode, multiSelector, dir, pinnedFolders.contains(dir.path), listener))
        toggleItemSelection(selectedPositions.contains(position), position)
        holder.itemView.tag = holder
    }

    override fun onViewRecycled(holder: ViewHolder?) {
        super.onViewRecycled(holder)
        holder?.stopLoad()
    }

    override fun getItemCount() = dirs.size

    fun updateDirs(newDirs: ArrayList<Directory>) {
        dirs = newDirs
        notifyDataSetChanged()
    }

    fun selectItem(pos: Int) {
        toggleItemSelection(true, pos)
    }

    fun selectRange(from: Int, to: Int, min: Int, max: Int) {
        if (from == to) {
            (min..max).filter { it != from }
                    .forEach { toggleItemSelection(false, it) }
            return
        }

        if (to < from) {
            for (i in to..from)
                toggleItemSelection(true, i)

            if (min > -1 && min < to) {
                (min..to - 1).filter { it != from }
                        .forEach { toggleItemSelection(false, it) }
            }
            if (max > -1) {
                for (i in from + 1..max)
                    toggleItemSelection(false, i)
            }
        } else {
            for (i in from..to)
                toggleItemSelection(true, i)

            if (max > -1 && max > to) {
                (to + 1..max).filter { it != from }
                        .forEach { toggleItemSelection(false, it) }
            }

            if (min > -1) {
                for (i in min..from - 1)
                    toggleItemSelection(false, i)
            }
        }
    }

    class ViewHolder(val view: View, val adapter: MyAdapterListener, val itemClick: (Directory) -> (Unit)) : SwappingHolder(view, MultiSelector()) {
        fun bindView(activity: SimpleActivity, multiSelectorCallback: ModalMultiSelectorCallback, multiSelector: MultiSelector, directory: Directory,
                     isPinned: Boolean, listener: DirOperationsListener?): View {
            itemView.apply {
                dir_name.text = directory.name
                photo_cnt.text = directory.mediaCnt.toString()
                dir_pin.visibility = if (isPinned) View.VISIBLE else View.GONE
                activity.loadImage(directory.tmb, dir_thumbnail)

                setOnClickListener { viewClicked(multiSelector, directory) }
                setOnLongClickListener {
                    if (listener != null) {
                        if (!multiSelector.isSelectable) {
                            activity.startSupportActionMode(multiSelectorCallback)
                            adapter.toggleItemSelectionAdapter(true, layoutPosition)
                        }

                        listener.itemLongClicked(layoutPosition)
                    }
                    true
                }

                adapter.setupItemForeground(this)
            }
            return itemView
        }

        fun viewClicked(multiSelector: MultiSelector, directory: Directory) {
            if (multiSelector.isSelectable) {
                val isSelected = adapter.getSelectedPositions().contains(layoutPosition)
                adapter.toggleItemSelectionAdapter(!isSelected, layoutPosition)
            } else {
                itemClick(directory)
            }
        }

        fun stopLoad() {
            Glide.clear(view.dir_thumbnail)
        }
    }

    interface MyAdapterListener {
        fun toggleItemSelectionAdapter(select: Boolean, position: Int)

        fun setupItemForeground(itemView: View)

        fun getSelectedPositions(): HashSet<Int>
    }

    interface DirOperationsListener {
        fun refreshItems()

        fun tryDeleteFolders(folders: ArrayList<File>)

        fun itemLongClicked(position: Int)
    }
}
