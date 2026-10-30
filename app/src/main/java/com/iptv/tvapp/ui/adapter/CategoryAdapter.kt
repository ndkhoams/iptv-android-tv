package com.iptv.tvapp.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iptv.tvapp.databinding.ItemCategoryBinding
import com.iptv.tvapp.model.Category

class CategoryAdapter(
    private val onCategorySelected: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private val categories = mutableListOf<Category>()
    private var selectedIndex = 0

    fun submitList(list: List<Category>) {
        categories.clear()
        categories.addAll(list)
        selectedIndex = 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val binding: ItemCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val prevIndex = selectedIndex
                    selectedIndex = pos
                    notifyItemChanged(prevIndex)
                    notifyItemChanged(selectedIndex)
                    onCategorySelected(categories[pos])
                }
            }

            // Hỗ trợ chọn nhanh khi dùng remote TV lướt qua
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }

        fun bind(category: Category, isSelected: Boolean) {
            binding.tvCategoryName.text = category.name
            binding.root.isSelected = isSelected
        }
    }
}
