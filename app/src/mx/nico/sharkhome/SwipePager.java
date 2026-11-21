package mx.nico.sharkhome;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * Pager horizontal mínimo (sin androidx, framework puro) para deslizar entre 2+ páginas
 * del mismo tamaño que el contenedor. Lo usamos para "troca 3D" <-> "letras".
 * Sin lambdas (d8/javac con android.jar no las traga aquí).
 */
public class SwipePager extends ViewGroup {

    public interface OnPageChange { void onPage(int page); }
    public interface OnScroll { void onScroll(float fraction); } // 0 = página 0, 1 = página 1

    final Scroller scroller;
    final GestureDetector gestures;
    VelocityTracker vtracker;
    final int touchSlop, minFling, maxFling;
    int current = 0;
    boolean dragging;
    float downX, lastX;
    OnPageChange onPageChange;
    OnScroll onScroll;

    public SwipePager(Context c) {
        super(c);
        scroller = new Scroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener());
        setClipToPadding(false);
    }

    public void setOnPageChange(OnPageChange l) { onPageChange = l; }
    public void setOnScroll(OnScroll l) { onScroll = l; }
    public int getPage() { return current; }

    @Override
    protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (onScroll != null && getWidth() > 0) onScroll.onScroll((float) l / getWidth());
    }

    public void setPage(int page, boolean animate) {
        page = Math.max(0, Math.min(getChildCount() - 1, page));
        int target = page * getWidth();
        if (animate && getWidth() > 0) {
            scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 300);
            invalidate();
        } else {
            scrollTo(target, 0);
        }
        if (page != current) {
            current = page;
            if (onPageChange != null) onPageChange.onPage(page);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean ch, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, i * w + w, b - t);
        }
        scrollTo(current * w, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                dragging = !scroller.isFinished();
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > touchSlop) { dragging = true; return true; }
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vtracker == null) vtracker = VelocityTracker.obtain();
        vtracker.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.abortAnimation();
                downX = lastX = e.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = lastX - e.getX();
                lastX = e.getX();
                int max = (getChildCount() - 1) * getWidth();
                int nx = (int) (getScrollX() + dx);
                if (nx < 0) nx = 0; else if (nx > max) nx = max;
                scrollTo(nx, 0);
                dragging = true;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int w = getWidth();
                int target = current;
                vtracker.computeCurrentVelocity(1000, maxFling);
                float vx = vtracker.getXVelocity();
                if (Math.abs(vx) > minFling) {
                    target = vx < 0 ? current + 1 : current - 1;
                } else {
                    target = (getScrollX() + w / 2) / Math.max(1, w);
                }
                setPage(target, true);
                dragging = false;
                if (vtracker != null) { vtracker.recycle(); vtracker = null; }
                return true;
            }
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }
}
