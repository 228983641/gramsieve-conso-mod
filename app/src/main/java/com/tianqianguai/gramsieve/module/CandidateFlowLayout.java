package com.tianqianguai.gramsieve.module;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Wraps children left to right and onto the next row when the current
 * row is full, instead of one child per line. Used by the smart-block
 * confirmation dialog: short candidates (an @handle, a domain, a
 * keyword) share a row, so a long candidate list does not become a
 * long vertical checklist. No dependency on an external flexbox
 * library; spacing is fixed in dp and converted once at construction.
 */
public class CandidateFlowLayout extends ViewGroup {

    private final int horizontalSpacing;
    private final int verticalSpacing;

    public CandidateFlowLayout(Context context) {
        super(context);
        horizontalSpacing = DialogTheme.dp(context, 12f);
        verticalSpacing = DialogTheme.dp(context, 6f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = width - getPaddingLeft() - getPaddingRight();
        if (available < 0) {
            available = 0;
        }
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = getPaddingTop() + getPaddingBottom();
        boolean firstOnLine = true;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == GONE) {
                continue;
            }
            measureChild(child,
                    MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                    heightMeasureSpec);
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int nextWidth = firstOnLine ? childWidth : lineWidth + horizontalSpacing + childWidth;
            if (!firstOnLine && nextWidth > available) {
                totalHeight += lineHeight + verticalSpacing;
                lineWidth = childWidth;
                lineHeight = childHeight;
                firstOnLine = false;
            } else {
                lineWidth = nextWidth;
                lineHeight = Math.max(lineHeight, childHeight);
                firstOnLine = false;
            }
        }
        totalHeight += lineHeight;
        int height = resolveSize(totalHeight, heightMeasureSpec);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = right - left - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;
        boolean firstOnLine = true;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == null || child.getVisibility() == GONE) {
                continue;
            }
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (!firstOnLine && x + horizontalSpacing + childWidth > getPaddingLeft() + available) {
                x = getPaddingLeft();
                y += lineHeight + verticalSpacing;
                lineHeight = 0;
            } else if (!firstOnLine) {
                x += horizontalSpacing;
            }
            child.layout(x, y, x + childWidth, y + childHeight);
            x += childWidth;
            lineHeight = Math.max(lineHeight, childHeight);
            firstOnLine = false;
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
