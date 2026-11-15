package com.iptv.tvapp.ui.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * LayoutManager chuyên biệt cho Android TV:
 * - Triệt tiêu hoàn toàn lỗi khựng / lag / văng mất focus (D-pad lost focus) khi cuộn danh sách.
 * - Khóa focus ở biên (đầu/cuối danh sách) không cho focus nhảy lung tung ra khỏi danh sách.
 * - calculateExtraLayoutSpace: Tự động nạp sẵn (pre-render) các view ở trên và dưới màn hình giúp phím D-pad phản hồi tức thì 60 FPS.
 * - Cho phép RecyclerView cuộn mượt mà tự nhiên, không giật nhảy offset.
 */
class TvLinearLayoutManager(
    context: Context,
    private val recyclerViewProvider: () -> RecyclerView?,
    var onHorizontalFocusSearch: ((focused: View, direction: Int) -> View?)? = null
) : LinearLayoutManager(context, VERTICAL, false) {

    override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
        super.calculateExtraLayoutSpace(state, extraLayoutSpace)
        val extra = (height * 2).coerceAtLeast(1000)
        extraLayoutSpace[0] = extra
        extraLayoutSpace[1] = extra
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val count = itemCount
        val position = getPosition(focused)
        if (position == RecyclerView.NO_POSITION) {
            return super.onInterceptFocusSearch(focused, direction)
        }

        if (direction == View.FOCUS_DOWN) {
            if (position + 1 >= count) {
                return focused // Khóa focus ở cuối danh sách, không cho focus văng ra ngoài
            }
            val nextView = findViewByPosition(position + 1)
            if (nextView != null && nextView.isShown && nextView.isFocusable) {
                return nextView
            }
            // Để onFocusSearchFailed tự động cuộn và gắn view mới tức thì
            return null
        } else if (direction == View.FOCUS_UP) {
            if (position - 1 < 0) {
                return focused // Khóa focus ở đầu danh sách, không cho focus văng ra ngoài
            }
            val prevView = findViewByPosition(position - 1)
            if (prevView != null && prevView.isShown && prevView.isFocusable) {
                return prevView
            }
            // Để onFocusSearchFailed tự động cuộn và gắn view mới tức thì
            return null
        }

        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            val customTarget = onHorizontalFocusSearch?.invoke(focused, direction)
            if (customTarget != null) {
                return customTarget
            }
        }

        return super.onInterceptFocusSearch(focused, direction)
    }

    override fun onFocusSearchFailed(
        focused: View,
        direction: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        val position = getPosition(focused)
        if (position == RecyclerView.NO_POSITION) {
            return super.onFocusSearchFailed(focused, direction, recycler, state)
        }

        if (direction == View.FOCUS_DOWN) {
            if (position + 1 >= itemCount) {
                return focused
            }
        } else if (direction == View.FOCUS_UP) {
            if (position - 1 < 0) {
                return focused
            }
        }

        val result = super.onFocusSearchFailed(focused, direction, recycler, state)
        if (result != null) {
            return result
        }

        val targetPos = if (direction == View.FOCUS_DOWN) position + 1 else position - 1
        if (targetPos in 0 until itemCount) {
            val view = findViewByPosition(targetPos)
            if (view != null) return view
            scrollToPosition(targetPos)
            return findViewByPosition(targetPos) ?: focused
        }

        return focused
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }
}
