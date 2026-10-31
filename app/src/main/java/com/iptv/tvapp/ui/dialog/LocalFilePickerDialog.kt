package com.iptv.tvapp.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.R
import com.iptv.tvapp.databinding.DialogLocalFilePickerBinding
import com.iptv.tvapp.databinding.ItemLocalFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFilePickerDialog(
    context: Context,
    private val onFileSelected: (File) -> Unit,
    private val onOpenSysPicker: (() -> Unit)? = null
) : Dialog(context, R.style.TvDialogTheme) {

    private lateinit var binding: DialogLocalFilePickerBinding
    private var currentDir: File = getInitialDirectory()
    private val fileList = mutableListOf<FileItem>()
    private lateinit var adapter: FileAdapter

    data class FileItem(
        val file: File,
        val isDirectory: Boolean,
        val displayName: String,
        val detail: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogLocalFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = FileAdapter { item ->
            if (item.isDirectory) {
                navigateTo(item.file)
            } else {
                onFileSelected(item.file)
                dismiss()
            }
        }

        binding.rvLocalFiles.layoutManager = LinearLayoutManager(context)
        binding.rvLocalFiles.adapter = adapter

        binding.btnParentDir.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead()) {
                navigateTo(parent)
            }
        }

        binding.btnQuickDownload.setOnClickListener {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val publicFiles = try { downloadDir?.listFiles() } catch (e: Exception) { null }
            if (downloadDir != null && downloadDir.exists() && !publicFiles.isNullOrEmpty()) {
                navigateTo(downloadDir)
            } else if (appExtDir != null && appExtDir.exists() && appExtDir.listFiles()?.isNotEmpty() == true) {
                navigateTo(appExtDir)
            } else if (downloadDir != null && downloadDir.exists()) {
                navigateTo(downloadDir)
            } else if (appExtDir != null && appExtDir.exists()) {
                navigateTo(appExtDir)
            }
        }

        binding.btnQuickInternal.setOnClickListener {
            val internalDir = Environment.getExternalStorageDirectory()
            if (internalDir.exists()) navigateTo(internalDir)
        }

        binding.btnQuickUsb.setOnClickListener {
            val usbDir = findUsbDirectory()
            if (usbDir != null && usbDir.exists()) {
                navigateTo(usbDir)
            } else {
                val storage = File("/storage")
                if (storage.exists() && storage.canRead()) navigateTo(storage)
            }
        }

        if (onOpenSysPicker != null) {
            binding.btnSysFilePicker.visibility = View.VISIBLE
            binding.btnSysFilePicker.setOnClickListener {
                dismiss()
                onOpenSysPicker.invoke()
            }
        } else {
            binding.btnSysFilePicker.visibility = View.GONE
        }

        binding.btnCancelFilePicker.setOnClickListener {
            dismiss()
        }

        navigateTo(currentDir)
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawableResource(R.color.fpt_bg_dark)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && currentDir.absolutePath != "/storage" && currentDir.absolutePath != "/") {
                navigateTo(parent)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun navigateTo(dir: File) {
        currentDir = dir
        binding.tvCurrentPath.text = "📂 ${dir.absolutePath}"
        binding.btnParentDir.isEnabled = (dir.parentFile != null && dir.parentFile?.canRead() == true)

        fileList.clear()

        val files = dir.listFiles()
        if (files != null && files.isNotEmpty()) {
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            // Sắp xếp: Thư mục lên trước (A-Z), sau đó đến file (A-Z)
            val sortedList = files.filter { !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (f in sortedList) {
                if (f.isDirectory) {
                    val count = f.list()?.size ?: 0
                    fileList.add(
                        FileItem(
                            file = f,
                            isDirectory = true,
                            displayName = f.name,
                            detail = "Thư mục • $count mục"
                        )
                    )
                } else {
                    val ext = f.extension.lowercase()
                    if (ext == "m3u" || ext == "m3u8" || ext == "txt" || ext == "json") {
                        val sizeStr = formatFileSize(f.length())
                        val timeStr = dateFormat.format(Date(f.lastModified()))
                        val iconPrefix = if (ext == "json") "⚙ [Sao lưu] " else "📺 "
                        fileList.add(
                            FileItem(
                                file = f,
                                isDirectory = false,
                                displayName = f.name,
                                detail = "$iconPrefix$sizeStr • $timeStr"
                            )
                        )
                    }
                }
            }
        }

        adapter.submitList(fileList.toList())

        if (fileList.isEmpty()) {
            binding.rvLocalFiles.visibility = View.GONE
            binding.tvEmptyFolder.visibility = View.VISIBLE
        } else {
            binding.rvLocalFiles.visibility = View.VISIBLE
            binding.tvEmptyFolder.visibility = View.GONE
            binding.rvLocalFiles.scrollToPosition(0)
            binding.rvLocalFiles.post {
                binding.rvLocalFiles.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun getInitialDirectory(): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicFiles = try { downloadDir?.listFiles() } catch (e: Exception) { null }
        if (downloadDir != null && downloadDir.exists() && !publicFiles.isNullOrEmpty()) {
            return downloadDir
        }

        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (appExtDir != null && appExtDir.exists() && appExtDir.listFiles()?.isNotEmpty() == true) {
            return appExtDir
        }

        if (downloadDir != null && downloadDir.exists() && downloadDir.canRead()) return downloadDir

        val extDir = Environment.getExternalStorageDirectory()
        if (extDir != null && extDir.exists() && extDir.canRead()) return extDir

        return context.filesDir
    }

    private fun findUsbDirectory(): File? {
        try {
            val storage = File("/storage")
            if (storage.exists()) {
                val subDirs = storage.listFiles() ?: return null
                for (sub in subDirs) {
                    if (sub.isDirectory && sub.name != "emulated" && sub.name != "self" && sub.canRead()) {
                        return sub
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0))
            size >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
            else -> "$size B"
        }
    }

    private class FileAdapter(
        private val onItemClick: (FileItem) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

        private val items = mutableListOf<FileItem>()

        fun submitList(newItems: List<FileItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLocalFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemLocalFileBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(items[pos])
                    }
                }
                binding.root.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start()
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }
                }
            }

            fun bind(item: FileItem) {
                binding.tvFileName.text = item.displayName
                binding.tvFileDetail.text = item.detail

                if (item.isDirectory) {
                    binding.tvFileIcon.text = "📁"
                    binding.tvSelectHint.visibility = View.GONE
                } else {
                    binding.tvFileIcon.text = "📄"
                    binding.tvSelectHint.visibility = View.VISIBLE
                }
            }
        }
    }
}

