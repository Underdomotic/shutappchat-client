package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.adapter.SystemNotificationAdapter
import it.fabiodirauso.shutappchat.model.SystemNotification

class SwipeToActionCallback(
    private val context: Context,
    private val adapter: SystemNotificationAdapter,
    private val onSwipeRight: (Int) -> Unit, // Delete
    private val onSwipeLeft: (Int) -> Unit   // Open URL
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val deleteBackground = ColorDrawable(Color.parseColor("#F44336")) // Red
    private val linkBackground = ColorDrawable(Color.parseColor("#2196F3"))   // Blue
    private val paint = Paint()

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return false
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.adapterPosition
        when (direction) {
            ItemTouchHelper.RIGHT -> onSwipeRight(position)
            ItemTouchHelper.LEFT -> onSwipeLeft(position)
        }
    }

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val position = viewHolder.adapterPosition
        if (position < 0 || position >= adapter.currentList.size) {
            return 0
        }
        
        val notification = adapter.currentList[position]
        
        // Swipe destro sempre disponibile (elimina)
        var swipeDirs = ItemTouchHelper.RIGHT
        
        // Swipe sinistro solo se c'è un URL
        if (!notification.url.isNullOrEmpty()) {
            swipeDirs = swipeDirs or ItemTouchHelper.LEFT
        }
        
        return swipeDirs
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        
        when {
            dX > 0 -> { // Swipe destro - Elimina
                deleteBackground.setBounds(
                    itemView.left,
                    itemView.top,
                    itemView.left + dX.toInt(),
                    itemView.bottom
                )
                deleteBackground.draw(c)
                
                // Icona cestino
                paint.textSize = 50f
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.LEFT
                c.drawText("", itemView.left + 40f, itemView.top + (itemView.height / 2f) + 15f, paint)
                
                paint.textSize = 30f
                c.drawText("Elimina", itemView.left + 100f, itemView.top + (itemView.height / 2f) + 10f, paint)
            }
            dX < 0 -> { // Swipe sinistro - Apri link
                linkBackground.setBounds(
                    itemView.right + dX.toInt(),
                    itemView.top,
                    itemView.right,
                    itemView.bottom
                )
                linkBackground.draw(c)
                
                // Icona link
                paint.textSize = 50f
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.RIGHT
                c.drawText("", itemView.right - 40f, itemView.top + (itemView.height / 2f) + 15f, paint)
                
                paint.textSize = 30f
                c.drawText("Apri link", itemView.right - 100f, itemView.top + (itemView.height / 2f) + 10f, paint)
            }
        }
        
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
