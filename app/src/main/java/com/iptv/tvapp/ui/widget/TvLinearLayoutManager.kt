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
    private val recyclerViewProvider: () -> RecyclerView?
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

                // Item tiếp theo chưa hiển thị trên màn hình:
                // Pre-scroll ngay lập tức đến item mục tiêu và lên lịch chuyển focus
                scrollToPosition(nextPos)
                val rv = recyclerViewProvider()
                rv?.post {
                    val vh = rv.findViewHolderForAdapterPosition(nextPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        rv.postDelayed({
                            rv.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                        }, 50)
                    }
                }

                // BẪY FOCUS: Giữ focus tại item hiện tại trong frame này,
                // TUYỆT ĐỐI không trả về null khiến Android FocusFinder hủy focus hoặc nhảy ra ngoài
                return focused
            } else {
                // Đã chạm đáy danh sách: Khóa focus ở item cuối cùng
                return focused
            }
        } else if (direction == View.FOCUS_UP) {
            if (position - 1 >= 0) {
                val prevPos = position - 1
                val prevView = findViewByPosition(prevPos)
                if (prevView != null && prevView.isShown) {
                    return prevView
                }

                scrollToPosition(prevPos)
                val rv = recyclerViewProvider()
                rv?.post {
                    val vh = rv.findViewHolderForAdapterPosition(prevPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        rv.postDelayed({
                            rv.findViewHolderForAdapterPosition(prevPos)?.itemView?.requestFocus()
                        }, 50)
                    }
                }

                return focused
            } else {
                // Đã ở đầu danh sách: Khóa focus ở item đầu tiên
                return focused
            }
        }

        // Với hướng TRÁI hoặc PHẢI, chuyển tiếp cho hệ thống để điều hướng sang Cột Danh mục hoặc EPG
        return super.onInterceptFocusSearch(focused, direction)
    }
}

