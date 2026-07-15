package ca.pkay.rcloneexplorer.widget;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** GridLayoutManager that derives its span count from the available width. */
public class AutoFitGridLayoutManager extends GridLayoutManager {

    private int columnWidth;

    public AutoFitGridLayoutManager(Context context, int columnWidth) {
        super(context, 1);
        this.columnWidth = Math.max(1, columnWidth);
    }

    public void setColumnWidth(int columnWidth) {
        this.columnWidth = Math.max(1, columnWidth);
        requestLayout();
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (availableWidth > 0) {
            int spanCount = Math.max(1, availableWidth / columnWidth);
            if (spanCount != getSpanCount()) {
                setSpanCount(spanCount);
            }
        }
        super.onLayoutChildren(recycler, state);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        // Grid items change height asynchronously when real thumbnail ratios arrive.
        // Predictive pre-layout can mix the previous directory/row geometry with the
        // new one and leave stale vertical offsets, even when ItemAnimator is disabled.
        return false;
    }
}
