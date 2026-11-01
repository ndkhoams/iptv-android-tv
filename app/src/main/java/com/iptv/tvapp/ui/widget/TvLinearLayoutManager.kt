package com.iptv.tvapp.ui.widget

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LayoutManager chuyên biệt cho Android TV:
 * - Triệt tiêu hoàn toàn lỗi văng/mất focus (D-pad lost focus) khi cuộn nhanh qua hàng trăm kênh.
 * - Khóa focus ở biên (đầu/cuối danh sách) không cho focus nhảy lung tung ra khỏi danh sách.
 * - Tự động pre-scroll và phục hồi focus mượt mà khi người dùng giữ lì phím D-pad Lên / Xuống.
 */
class TvLinearLayoutManager(
    context: Context,
    private val recyclerViewProvider: () -> RecyclerView?,
    var onHorizontalFocusSearch: ((focused: View, direction: Int) -> View?)? = null
) : LinearLayoutManager(context, VERTICAL, false) {

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val count = itemCount
        val position = getPosition(focused)
        if (position == RecyclerView.NO_POSITION) {
            return super.onInterceptFocusSearch(focused, direction)
        }

        if (direction == View.FOCUS_DOWN) {
            if (position + 1 < count) {
                val nextPos = position + 1
                val nextView = findViewByPosition(nextPos)
                if (nextView != null && nextView.isShown) {
                    return nextView
                }

                scrollToPositionWithOffset(nextPos, 0)
                val rv = recyclerViewProvider()
                rv?.post {
                    val vh = rv.findViewHolderForAdapterPosition(nextPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        findViewByPosition(nextPos)?.requestFocus() ?: run {
                            rv.postDelayed({
                                rv.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                                    ?: findViewByPosition(nextPos)?.requestFocus()
                            }, 40)
                        }
                    }
                }

                return focused
            } else {
                return focused
            }
        } else if (direction == View.FOCUS_UP) {
            if (position - 1 >= 0) {
                val prevPos = position - 1
                val prevView = findViewByPosition(prevPos)
                if (prevView != null && prevView.isShown) {
                    return prevView
                }

                scrollToPositionWithOffset(prevPos, 0)
                val rv = recyclerViewProvider()
                rv?.post {
                    val vh = rv.findViewHolderForAdapterPosition(prevPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        findViewByPosition(prevPos)?.requestFocus() ?: run {
                            rv.postDelayed({
                                rv.findViewHolderForAdapterPosition(prevPos)?.itemView?.requestFocus()
                                    ?: findViewByPosition(prevPos)?.requestFocus()
                            }, 40)
                        }
                    }
                }

                return focused
            } else {
                return focused
            }
        }

        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            val customTarget = onHorizontalFocusSearch?.invoke(focused, direction)
            if (customTarget != null) {
                return customTarget
            }
        }

        // Với hướng TRÁI hoặc PHẢI, chuyển tiếp cho hệ thống để điều hướng sang Cột Danh mục hoặc EPG
        return super.onInterceptFocusSearch(focused, direction)
    }
}

