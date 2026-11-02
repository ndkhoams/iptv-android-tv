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

    fun setSelectedCategory(category: Category) {
        val index = categories.indexOfFirst { it.name == category.name }
        if (index != -1 && index != selectedIndex) {
            val old = selectedIndex
            selectedIndex = index
            notifyItemChanged(old)
            notifyItemChanged(selectedIndex)
        }
    }

    fun getSelectedPosition(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position == selectedIndex, position)
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

            // TV Focus chuẩn drawable selector, không chạy animation nặng để giữ 60fps mượt mà
            binding.root.setOnFocusChangeListener { _, _ -> }
        }

        fun bind(category: Category, isSelected: Boolean, position: Int) {
            if (position % 2 == 1) {
                binding.root.setBackgroundResource(com.iptv.tvapp.R.drawable.selector_category_item_odd)
            } else {
                binding.root.setBackgroundResource(com.iptv.tvapp.R.drawable.selector_category_item_even)
            }
            binding.tvCategoryName.text = category.name
            binding.root.isSelected = isSelected
        }
    }
}
