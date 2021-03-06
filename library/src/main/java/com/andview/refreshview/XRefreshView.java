package com.andview.refreshview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView.OnScrollListener;
import android.widget.LinearLayout;
import android.widget.Scroller;

import com.andview.refreshview.callback.IFooterCallBack;
import com.andview.refreshview.callback.IHeaderCallBack;
import com.andview.refreshview.listener.OnBottomLoadMoreTime;
import com.andview.refreshview.listener.OnTopRefreshTime;
import com.andview.refreshview.utils.LogUtils;
import com.andview.refreshview.utils.Utils;

import java.util.Calendar;

public class XRefreshView extends LinearLayout {
    // -- header view
    private View mHeaderView;

    private int mHeaderViewHeight; // header view's height
    /**
     * 最初的滚动位置.第一次布局时滚动header的高度的距离
     */
    protected int mInitScrollY = 0;
    private int mLastY = -1; // save event y
    private int mLastX = -1; // save event x
    private boolean mEnablePullRefresh = true;
    public boolean mPullRefreshing = false; // is refreashing.
    private float OFFSET_RADIO = 1.8f; // support iOS like pull
    private XRefreshViewListener mRefreshViewListener;
    // -- footer view
    private View mFooterView;
    private boolean mEnablePullLoad;
    public boolean mPullLoading;
    /**
     * 默认不自动刷新
     */
    private boolean autoRefresh = false;
    /**
     * 默认自动加载更多
     */
    private boolean autoLoadMore = true;
    private int mFootHeight;
    /**
     * 被刷新的view
     */
    private XRefreshContentView mContentView;
    private boolean isHeightMatchParent = true;
    private boolean isWidthMatchParent = true;
    private int mInitialMotionY;
    private int mTouchSlop;
    private XRefreshHolder mHolder;

    private MotionEvent mLastMoveEvent;
    private boolean mHasSendCancelEvent = false;
    private boolean mHasSendDownEvent = false;
    private Scroller mScroller;
    private boolean mMoveForHorizontal = false;
    private boolean isForHorizontalMove = false;
    private boolean mCanMoveHeaderWhenDisablePullRefresh = true;
    private boolean mCanMoveFooterWhenDisablePullLoadMore = true;

    private boolean mIsIntercept = false;
    private IHeaderCallBack mHeaderCallBack;
    private IFooterCallBack mFooterCallBack;
    /**
     * 当刷新完成以后，headerview和footerview被固定的时间，在这个时间以后headerview才会回弹
     */
    private int mPinnedTime;
    private static Handler mHandler = new Handler();
    private XRefreshViewState mState = null;
    /**
     * 当已无更多数据时候，需把这个变量设为true
     */
    private boolean mHasLoadComplete = false;
    /**
     * 在刷新的时候是否可以移动contentView
     */
    private boolean mIsPinnedContentWhenRefreshing = false;
    private boolean enableReleaseToLoadMore = true;
    /**
     * 在Recyclerview滑倒最底部的时候，是否允许Recyclerview继续往上滑动
     */
    private boolean enableRecyclerViewPullUp = true;
    /**
     * 当Recyclerview加载完成的时候，不允许界面被上拉
     */
    private boolean enablePullUp = true;

    public XRefreshView(Context context) {
        this(context, null);
    }

    public XRefreshView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setLongClickable(true);
        mContentView = new XRefreshContentView();
        mHolder = new XRefreshHolder();
        mScroller = new Scroller(getContext(), new LinearInterpolator());

        initWithContext(context, attrs);
        setOrientation(VERTICAL);
    }

    /**
     * 设置顶部刷新时机
     *
     * @param topListener
     */
    public void setOnTopRefreshTime(OnTopRefreshTime topListener) {
        mContentView.setOnTopRefreshTime(topListener);
    }

    public void setOnBottomLoadMoreTime(OnBottomLoadMoreTime bottomListener) {
        mContentView.setOnBottomLoadMoreTime(bottomListener);
    }

    /**
     * 如果被刷新的view是RecyclerView，那么footerView将被添加到adapter中，不会添加到这里
     *
     * @return
     */
    public boolean needAddFooterView() {
        return !mContentView.isRecyclerView();
    }

    /**
     * pass true if need use for Horizontal move, or false
     *
     * @param isForHorizontalMove default false
     */
    public void setMoveForHorizontal(boolean isForHorizontalMove) {
        this.isForHorizontalMove = isForHorizontalMove;
    }

    /**
     * 设置静默加载更多，旨在提供被刷新的view滚动到底部的监听，自动静默加载更多
     */
    public void setSilenceLoadMore() {
        mContentView.setSilenceLoadMore(true);
        setPullLoadEnable(false);
    }

    /**
     * 当切换layoutManager时，需调用此方法
     */
    public void notifyLayoutManagerChanged() {
        mContentView.setScrollListener();
        mContentView.notifyDatasetChanged();
    }

    private void initWithContext(Context context, AttributeSet attrs) {

        // 根据属性设置参数
        if (attrs != null) {
            TypedArray a = context.getTheme().obtainStyledAttributes(attrs,

                    R.styleable.XRefreshView, 0, 0);
            try {
                isHeightMatchParent = a.getBoolean(
                        R.styleable.XRefreshView_isHeightMatchParent, true);
                isWidthMatchParent = a.getBoolean(
                        R.styleable.XRefreshView_isHeightMatchParent, true);
                autoRefresh = a.getBoolean(
                        R.styleable.XRefreshView_autoRefresh, false);
                autoLoadMore = a.getBoolean(
                        R.styleable.XRefreshView_autoLoadMore, true);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                a.recycle();
            }
        }
        mHeaderView = new XRefreshViewHeader(context);
        mFooterView = new XRefreshViewFooter(context);
        this.getViewTreeObserver().addOnGlobalLayoutListener(
                new OnGlobalLayoutListener() {

                    @Override
                    public void onGlobalLayout() {
                        addHeaderView();
                        addFooterView(this);
                    }
                });
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    private void addHeaderView() {
        Utils.removeViewFromParent(mHeaderView);
        addView(mHeaderView, 0);
        mHeaderView.measure(0, 0);
        mContentView.setContentView(XRefreshView.this.getChildAt(1));
        mContentView.setContainer(autoLoadMore ? this : null);
        mContentView.setContentViewLayoutParams(isHeightMatchParent, isWidthMatchParent);
        mHeaderCallBack = (IHeaderCallBack) mHeaderView;
        mFooterCallBack = (IFooterCallBack) mFooterView;
        setRefreshTime();
        checkPullRefreshEnable();
        checkPullLoadEnable();
    }

    private void addFooterView(OnGlobalLayoutListener listener) {
        mHeaderViewHeight = ((IHeaderCallBack) mHeaderView).getHeaderHeight();
        mContentView.setHolder(mHolder);
        mContentView.setParent(this);
        mContentView.setScrollListener();
        if (needAddFooterView()) {
            LogUtils.d("test add footView" + ";mHeaderViewHeight=" + mHeaderViewHeight);
            Utils.removeViewFromParent(mFooterView);
            addView(mFooterView);
        }
        // 移除视图树监听器
        removeViewTreeObserver(listener);
        if (autoRefresh) {
            startRefresh();
        }
        if (mHeadMoveDistence == 0) {
            int ScreenHeight = Utils.getScreenSize(getContext()).y;
            mHeadMoveDistence = ScreenHeight / 3;
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    public void removeViewTreeObserver(OnGlobalLayoutListener listener) {
        if (Build.VERSION.SDK_INT < VERSION_CODES.JELLY_BEAN) {
            getViewTreeObserver().removeGlobalOnLayoutListener(listener);
        } else {
            getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
//        int width2 = getDefaultSize(0, widthMeasureSpec);
//        int height2 = getDefaultSize(0, heightMeasureSpec);
        int childCount = getChildCount();
        int finalHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                final int paddingLeft = getPaddingLeft();
                final int paddingRight = getPaddingRight();
                final int paddingTop = getPaddingTop();
                final int paddingBottom = getPaddingBottom();
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
                int childWidthSpec = getChildMeasureSpec(widthMeasureSpec,
                        paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width);
                int childHeightSpec = getChildMeasureSpec(heightMeasureSpec,
                        paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height);
                child.measure(childWidthSpec, childHeightSpec);
                finalHeight += child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            }
        }
        setMeasuredDimension(width, finalHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t2, int r, int b) {
//        super.onLayout(changed, l, t2, r, b);
//        if(mHolder.mOffsetY!=0)return;
        LogUtils.d("onLayout mHolder.mOffsetY=" + mHolder.mOffsetY);
        mFootHeight = ((IFooterCallBack) mFooterView).getFooterHeight();
        int childCount = getChildCount();
        int top = getPaddingTop() + mHolder.mOffsetY;
        int adHeight = 0;
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            LayoutParams margins = (LayoutParams) child.getLayoutParams();
            int topMargin = margins.topMargin;
            int bottomMargin = margins.bottomMargin;
            int leftMargin = margins.leftMargin;
            int rightMargin = margins.rightMargin;
            l = leftMargin + getPaddingLeft();
            top += topMargin;
            r = child.getMeasuredWidth();
            if (child.getVisibility() != View.GONE) {
                if (i == 0) {
                    adHeight = child.getMeasuredHeight() - mHeaderViewHeight;
                    // 通过把headerview向上移动一个headerview高度的距离来达到隐藏headerview的效果
                    child.layout(l, top - mHeaderViewHeight, l + r, top + adHeight);
                    top += adHeight;
                } else if (i == 1) {
                    int childHeight = child.getMeasuredHeight() - adHeight;
                    int bottom = childHeight + top;
                    child.layout(l, top, l + r, bottom);
                    top += childHeight + bottomMargin;
                } else {
                    int bottom = child.getMeasuredHeight() + top;
                    child.layout(l, top, l + r, bottom);
                    top += child.getMeasuredHeight();
                }
            }
        }
    }

    private boolean isIntercepted = false;
    private int mHeadMoveDistence;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        int deltaY = 0;
        int deltaX = 0;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mHasSendCancelEvent = false;
                mHasSendDownEvent = false;
                mLastY = (int) ev.getRawY();
                mLastX = (int) ev.getRawX();
                mInitialMotionY = mLastY;
                break;
            case MotionEvent.ACTION_MOVE:
                mLastMoveEvent = ev;
                if (/*!enablePullUp ||*/ mStopingRefresh || !isEnabled() || mIsIntercept) {
                    return super.dispatchTouchEvent(ev);
                }
                if ((mPullLoading || mPullRefreshing) && mIsPinnedContentWhenRefreshing) {
                    sendCancelEvent();
                    return true;
                }
                int currentY = (int) ev.getRawY();
                int currentX = (int) ev.getRawX();
                deltaY = currentY - mLastY;
                deltaX = currentX - mLastX;
                mLastY = currentY;
                mLastX = currentX;
                // intercept the MotionEvent only when user is not scrolling
                if (!isIntercepted) {
                    if (Math.abs(currentY - mInitialMotionY) >= mTouchSlop) {
                        isIntercepted = true;
                    } else {
                        return super.dispatchTouchEvent(ev);
                    }
                }
                if (isForHorizontalMove && !mMoveForHorizontal && Math.abs(deltaX) > mTouchSlop
                        && Math.abs(deltaX) > Math.abs(deltaY)) {
                    if (mHolder.mOffsetY == 0) {
                        mMoveForHorizontal = true;
                    }
                }
                if (mMoveForHorizontal) {
                    return super.dispatchTouchEvent(ev);
                }
                LogUtils.d("isTop=" + mContentView.isTop() + ";isBottom=" + mContentView.isBottom());
                if (deltaY > 0 && mHolder.mOffsetY <= mHeadMoveDistence || deltaY < 0) {
                    deltaY = (int) (deltaY / OFFSET_RADIO);
                } else {
                    return super.dispatchTouchEvent(ev);
                }
                if (!mPullLoading && !mReleaseToLoadMore && mContentView.isTop() && (deltaY > 0 || (deltaY < 0 && mHolder.hasHeaderPullDown()))) {
                    sendCancelEvent();
                    updateHeaderHeight(currentY, deltaY);
                } else if (!mPullRefreshing && mContentView.isBottom()
                        && (deltaY < 0 || deltaY > 0 && mHolder.hasFooterPullUp())) {
                    sendCancelEvent();
                    updateFooterHeight(deltaY);
                } else if (deltaY != 0 && (mContentView.isTop() && !mHolder.hasHeaderPullDown()
                        || mContentView.isBottom() && !mHolder.hasFooterPullUp())) {
                    if (mReleaseToLoadMore) {
                        releaseToLoadMore(false);
                    }
                    if (Math.abs(deltaY) > 0)
                        sendDownEvent();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                // if (mHolder.mOffsetY != 0 && mRefreshViewListener != null
                // && !mPullRefreshing && !mPullLoading) {
                // mRefreshViewListener.onRelease(mHolder.mOffsetY);
                // }
                if (mHolder.hasHeaderPullDown()) {
                    if (mEnablePullRefresh && !mStopingRefresh && !mPullRefreshing && mHolder.mOffsetY > mHeaderViewHeight) {
                        mPullRefreshing = true;
                        mHeaderCallBack.onStateRefreshing();
                        mState = XRefreshViewState.STATE_REFRESHING;
                        if (mRefreshViewListener != null) {
                            mRefreshViewListener.onRefresh();
                        }
                    }
                    resetHeaderHeight();
                } else if (mHolder.hasFooterPullUp()) {
                    if (!mStopingRefresh) {
                        if (mEnablePullLoad && needAddFooterView() && !mHasLoadComplete) {
                            invokeLoadMore();
                        } else {
                            int offset = 0 - mHolder.mOffsetY;
                            startScroll(offset, Utils.computeScrollVerticalDuration(offset, getHeight()));
                        }
                    }
                }
                mLastY = -1; // reset
                mLastX = -1;
                mInitialMotionY = 0;
                isIntercepted = false;
                mMoveForHorizontal = false;
                mIsIntercept = false;
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    public boolean invokeLoadMore() {
        if (mEnablePullLoad && !mPullRefreshing && !mStopingRefresh && !mHasLoadComplete) {
            int offset = 0 - mHolder.mOffsetY - mFootHeight;
            if (offset != 0) {
                startScroll(offset, Utils.computeScrollVerticalDuration(offset, getHeight()));
            }
            startLoadMore();
            return true;
        }
        return false;
    }

    public void notifyLoadMore() {
        if (needAddFooterView()) {
            startLoadMore();
        } else {
            mContentView.notifyRecyclerViewLoadMore();
        }
    }

    /**
     * if child need the touch event,pass true
     */
    public void disallowInterceptTouchEvent(boolean isIntercept) {
        mIsIntercept = isIntercept;
    }

    private void sendCancelEvent() {
        if (!mHasSendCancelEvent) {
            LogUtils.d("sendCancelEvent");
            setRefreshTime();
            mHasSendCancelEvent = true;
            mHasSendDownEvent = false;
            MotionEvent last = mLastMoveEvent;
            MotionEvent e = MotionEvent.obtain(
                    last.getDownTime(),
                    last.getEventTime()
                            + ViewConfiguration.getLongPressTimeout(),
                    MotionEvent.ACTION_CANCEL, last.getX(), last.getY(),
                    last.getMetaState());
            dispatchTouchEventSupper(e);
        }
    }

    /**
     * header可下拉的最大距离
     *
     * @param headMoveDistence
     */
    public void setHeadMoveLargestDistence(int headMoveDistence) {
        mHeadMoveDistence = headMoveDistence;
    }

    private void sendDownEvent() {
        if (!mHasSendDownEvent) {
            LogUtils.d("sendDownEvent");
            mHasSendCancelEvent = false;
            mHasSendDownEvent = true;
            isIntercepted = false;
            final MotionEvent last = mLastMoveEvent;
            if (last == null)
                return;
            MotionEvent e = MotionEvent.obtain(last.getDownTime(),
                    last.getEventTime(), MotionEvent.ACTION_DOWN, last.getX(),
                    last.getY(), last.getMetaState());
            dispatchTouchEventSupper(e);
        }
    }

    public boolean dispatchTouchEventSupper(MotionEvent e) {
        return super.dispatchTouchEvent(e);
    }

    /**
     * enable or disable pull up load more feature.
     *
     * @param enable
     */
    public void setPullLoadEnable(boolean enable) {
        mEnablePullLoad = enable;
        if (needAddFooterView()) {
            checkPullLoadEnable();
        } else {
            mContentView.setEnablePullLoad(enable);
        }
    }

    public boolean getPullLoadEnable() {
        return mEnablePullLoad;
    }

    public boolean getPullRefreshEnable() {
        return mEnablePullRefresh;
    }

    /**
     * enable or disable pull down refresh feature.
     *
     * @param enable
     */
    public void setPullRefreshEnable(boolean enable) {
        mEnablePullRefresh = enable;
        checkPullRefreshEnable();
    }

    private void checkPullRefreshEnable() {
        if (mHeaderCallBack == null) {
            return;
        }
        if (!mEnablePullRefresh) {
            mHeaderCallBack.hide();
        } else {
            mHeaderCallBack.show();
        }
    }

    private void checkPullLoadEnable() {
        if (mFooterCallBack == null) {
            return;
        }
        if (!mEnablePullLoad) {
            mFooterCallBack.show(false);
        } else {
            mPullLoading = false;
            mFooterCallBack.show(true);
            mFooterCallBack.onStateRefreshing();
        }
    }

    private void startLoadMore() {
        if (!mPullLoading) {
            mFooterCallBack.onStateRefreshing();
            mPullLoading = true;
            if (mRefreshViewListener != null) {
                mRefreshViewListener.onLoadMore(false);
            }
        }
    }

    /**
     * 如果第可变参数不为空，则代表是自动刷新
     *
     * @param currentY
     * @param deltaY
     * @param during
     */
    private void updateHeaderHeight(int currentY, int deltaY, int... during) {
        boolean isAutoRefresh = during != null && during.length > 0;
        if (isAutoRefresh) {
            mHeaderCallBack.onStateRefreshing();
            startScroll(deltaY, during[0]);
        } else {
            if (mHolder.isOverHeader(deltaY)) {
                deltaY = -mHolder.mOffsetY;
            }
            if (mEnablePullRefresh || mCanMoveHeaderWhenDisablePullRefresh) {
                moveView(deltaY);
            }
            if (mEnablePullRefresh && !mPullRefreshing) {
                if (mHolder.mOffsetY > mHeaderViewHeight) {
                    if (mState != XRefreshViewState.STATE_READY) {
                        mHeaderCallBack.onStateReady();
                        mState = XRefreshViewState.STATE_READY;
                    }
                } else {
                    if (mState != XRefreshViewState.STATE_NORMAL) {
                        mHeaderCallBack.onStateNormal();
                        mState = XRefreshViewState.STATE_NORMAL;
                    }
                }
            }
        }
    }

    /**
     * 设置在下拉刷新被禁用的情况下，是否允许界面被下拉
     *
     * @param moveHeadWhenDisablePullRefresh 默认是true
     */
    public void setMoveHeadWhenDisablePullRefresh(boolean moveHeadWhenDisablePullRefresh) {
        mCanMoveHeaderWhenDisablePullRefresh = moveHeadWhenDisablePullRefresh;
    }

    /**
     * 设置在上拉加载被禁用的情况下，是否允许界面被上拉
     *
     * @param moveFootWhenDisablePullLoadMore 默认为true
     */
    public void setMoveFootWhenDisablePullLoadMore(boolean moveFootWhenDisablePullLoadMore) {
        mCanMoveFooterWhenDisablePullLoadMore = moveFootWhenDisablePullLoadMore;
    }

    private boolean mReleaseToLoadMore = false;
    private boolean mEnablePullUpWhenLoadCompleted = true;

    private boolean canReleaseToLoadMore() {
        return enableReleaseToLoadMore && mEnablePullLoad && mContentView != null && !mContentView.hasLoadCompleted() && !mContentView.isLoading();
    }

    private void releaseToLoadMore(boolean loadMore) {
        mReleaseToLoadMore = loadMore;
        mContentView.releaseToLoadMore(mReleaseToLoadMore);
    }

    private void updateFooterHeight(int deltaY) {
        if (mEnablePullLoad) {
            if (needAddFooterView()) {
                if (mState != XRefreshViewState.STATE_LOADING) {
                    mFooterCallBack.onStateRefreshing();
                    mState = XRefreshViewState.STATE_LOADING;
                }
            } else if (canReleaseToLoadMore()) {
                releaseToLoadMore(mHolder.mOffsetY != 0);
            }
        }
        if (needAddFooterView() || enableRecyclerViewPullUp) {
            if (mEnablePullUpWhenLoadCompleted || !mContentView.hasLoadCompleted()) {
                if (mContentView.hasLoadCompleted() && needAddFooterView() && mFooterCallBack != null && mFooterCallBack.isShowing()) {
                    mFooterCallBack.show(false);
                }
               /* if (!needAddFooterView() && mContentView.getState() != XRefreshViewState.STATE_COMPLETE && autoLoadMore) {
                    //当时是recyclerview，自动加载更多，并且没有加载完全的时候，不让Recyclerview上拉
                } else */
                if (mEnablePullLoad || mCanMoveFooterWhenDisablePullLoadMore) {
                    moveView(deltaY);
                }
            }
        }
    }

    /**
     * 设置是否自动刷新，默认不自动刷新
     *
     * @param autoRefresh true则自动刷新
     */
    public void setAutoRefresh(boolean autoRefresh) {
        this.autoRefresh = autoRefresh;
    }

    /**
     * 设置是否自动加载更多，默认是
     *
     * @param autoLoadMore true则自动刷新
     */
    public void setAutoLoadMore(boolean autoLoadMore) {
        this.autoLoadMore = autoLoadMore;
        if (mContentView != null) {
            mContentView.setContainer(autoLoadMore ? this : null);
        }
        if (autoLoadMore) {
            setPullLoadEnable(true);
        }
    }

    public void startRefresh() {
        if (!mEnablePullRefresh || mHolder.mOffsetY != 0 || mContentView.isLoading() || !isEnabled()) {
            return;
        }
        // 如果条件成立，代表布局还没有初始化完成，改变标记，等待该方法再次调用，完成开始刷新
        if (mHeaderCallBack == null) {
            this.autoRefresh = true;
        } else {
            if (!mPullRefreshing) {
                updateHeaderHeight(0, mHeaderViewHeight, 0);
                mPullRefreshing = true;
                if (mRefreshViewListener != null) {
                    mRefreshViewListener.onRefresh();
                }
                mContentView.scrollToTop();
            }
        }
    }

    /**
     * reset header view's height.
     */
    private void resetHeaderHeight() {
        float height = mHolder.mOffsetY;
        if (height == 0) // not visible.
            return;
        // refreshing and header isn't shown fully. do nothing.
        if (mPullRefreshing && height <= mHeaderViewHeight) {
            return;
        }
        int offsetY = 0;
        if (mPullRefreshing) {
            offsetY = mHeaderViewHeight - mHolder.mOffsetY;
            startScroll(offsetY, Utils.computeScrollVerticalDuration(offsetY, getHeight()));
        } else {
            offsetY = 0 - mHolder.mOffsetY;
            startScroll(offsetY, Utils.computeScrollVerticalDuration(offsetY, getHeight()));
        }
        LogUtils.d("resetHeaderHeight offsetY=" + offsetY);
    }

    public void moveView(int deltaY) {
        mHolder.move(deltaY);
        mHeaderView.offsetTopAndBottom(deltaY);
        mContentView.offsetTopAndBottom(deltaY);
        if (needAddFooterView()) {
            mFooterView.offsetTopAndBottom(deltaY);
        }
        ViewCompat.postInvalidateOnAnimation(this);

        if (mRefreshViewListener != null
                && (mContentView.isTop() || mPullRefreshing)) {
            double offset = 1.0 * mHolder.mOffsetY / mHeaderViewHeight;
            offset = offset > 1 ? 1 : offset;
            mRefreshViewListener.onHeaderMove(offset, mHolder.mOffsetY);
            mHeaderCallBack.onHeaderMove(offset, mHolder.mOffsetY, deltaY);
        }
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (mScroller.computeScrollOffset()) {
            int lastScrollY = mHolder.mOffsetY;
            int currentY = mScroller.getCurrY();
            int offsetY = currentY - lastScrollY;
            lastScrollY = currentY;
            moveView(offsetY);

            LogUtils.d("currentY=" + currentY + ";mHolder.mOffsetY=" + mHolder.mOffsetY);
            if (enableReleaseToLoadMore && mHolder.mOffsetY == 0 && mReleaseToLoadMore && mContentView != null && mContentView.isBottom()) {
                mReleaseToLoadMore = false;
                mContentView.startLoadMore(false, null, null);
            }
        } else {
            int currentY = mScroller.getCurrY();
            if (mHolder.mOffsetY == 0) {
                enablePullUp(true);
                mStopingRefresh = false;
            } else {
                //有时scroller已经停止了，但是却没有回到应该在的位置，执行下面的方法恢复
                if (mStopingRefresh && !mPullLoading && !mPullRefreshing) {
                    startScroll(-currentY, Utils.computeScrollVerticalDuration(currentY, getHeight()));
                }
            }
        }
    }

    private boolean mStopingRefresh = false;

    /**
     * stop refresh, reset header view.
     */
    public void stopRefresh() {
        LogUtils.d("stopRefresh mPullRefreshing=" + mPullRefreshing);
        if (mPullRefreshing == true) {
            mStopingRefresh = true;
            mHeaderCallBack.onStateFinish();
            mState = XRefreshViewState.STATE_COMPLETE;
            mHandler.postDelayed(new Runnable() {

                @Override
                public void run() {
                    mPullRefreshing = false;
                    if (mStopingRefresh) {
                        resetHeaderHeight();
                    }
                    lastRefreshTime = Calendar.getInstance().getTimeInMillis();
                }
            }, mPinnedTime);
        }
    }

    private long lastRefreshTime = -1;

    /**
     * 恢复上次刷新的时间
     *
     * @param lastRefreshTime
     */
    public void restoreLastRefreshTime(long lastRefreshTime) {
        this.lastRefreshTime = lastRefreshTime;
    }

    /**
     * 在停止刷新的时候调用，记录这次刷新的时间，用于下次刷新的时候显示
     *
     * @return
     */
    public long getLastRefreshTime() {
        return lastRefreshTime;
    }

    /**
     * 设置并显示上次刷新的时间
     */
    private void setRefreshTime() {
        if (lastRefreshTime <= 0) {
            return;
        }
        mHeaderCallBack.setRefreshTime(lastRefreshTime);
    }

    /**
     * stop load more, reset footer view.
     */
    public void stopLoadMore() {
        if (needAddFooterView()) {
            if (mPullLoading == true) {
                mStopingRefresh = true;
                mState = XRefreshViewState.STATE_COMPLETE;
                mFooterCallBack.onStateFinish(true);
                if (mPinnedTime >= 1000) {// 在加载更多完成以后，只有mPinnedTime大于1s才生效，不然效果不好
                    mHandler.postDelayed(new Runnable() {

                        @Override
                        public void run() {
                            endLoadMore(true);
                        }
                    }, mPinnedTime);
                } else {
                    endLoadMore(true);
                }
            }
        }
        mContentView.stopLoading(true);
    }

    /**
     * stop load more, reset footer view.
     *
     * @param hideFooter hide footerview if true
     */
    public void stopLoadMore(final boolean hideFooter) {
        if (needAddFooterView()) {
            if (mPullLoading == true) {
                mStopingRefresh = true;
                mState = XRefreshViewState.STATE_COMPLETE;
                mFooterCallBack.onStateFinish(hideFooter);
                if (mPinnedTime >= 1000) {// 在加载更多完成以后，只有mPinnedTime大于1s才生效，不然效果不好
                    mHandler.postDelayed(new Runnable() {

                        @Override
                        public void run() {
                            endLoadMore(hideFooter);
                        }
                    }, mPinnedTime);
                } else {
                    endLoadMore(hideFooter);
                }
            }
        }
        mContentView.stopLoading(hideFooter);
    }

    protected void resetLayout() {
        enablePullUp(false);
        if (mHolder.mOffsetY != 0) {
            startScroll(-mHolder.mOffsetY, Utils.computeScrollVerticalDuration(mHolder.mOffsetY, getHeight()));
        }
    }

    protected void enablePullUp(boolean enablePullUp) {
        this.enablePullUp = enablePullUp;
    }

    /**
     * 此方法当没有更多数据时调用，不要与stopLoadMore()同时调用
     *
     * @param hasComplete
     */
    public void setLoadComplete(boolean hasComplete) {
        mHasLoadComplete = hasComplete;
        if (needAddFooterView()) {
            stopLoadMore();
            if (!hasComplete && mEnablePullLoad && mFooterCallBack != null) {
                mFooterCallBack.onStateRefreshing();
//                mFooterCallBack.show(true);
            }
        }
        mContentView.setLoadComplete(hasComplete);
    }

    public boolean hasLoadCompleted() {
        return mHasLoadComplete;
    }

    private void endLoadMore(boolean hideFooter) {
        mPullLoading = false;
        startScroll(-mHolder.mOffsetY, 0);
//        mFooterCallBack.onStateRefreshing();
        if (mHasLoadComplete && hideFooter) {
            mFooterCallBack.show(false);
        }
    }

    /**
     * @param offsetY  滑动偏移量，负数向上滑，正数反之
     * @param duration 滑动持续时间
     */
    public void startScroll(int offsetY, int duration) {
        if (offsetY != 0) {
            mScroller.startScroll(0, mHolder.mOffsetY, 0, offsetY, duration);
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    /**
     * 设置Abslistview的滚动监听事件
     *
     * @param scrollListener
     */
    public void setOnAbsListViewScrollListener(OnScrollListener scrollListener) {
        mContentView.setOnAbsListViewScrollListener(scrollListener);
    }

    /**
     * 设置Recylerview的滚动监听事件
     */
    public void setOnRecyclerViewScrollListener(
            RecyclerView.OnScrollListener scrollListener) {
        mContentView.setOnRecyclerViewScrollListener(scrollListener);
    }

    /**
     * 设置静默加载时提前加载的item个数
     *
     * @param count
     */
    public void setPreLoadCount(int count) {
        mContentView.setPreLoadCount(count);
    }

    public void setXRefreshViewListener(XRefreshViewListener l) {
        mRefreshViewListener = l;
        mContentView.setXRefreshViewListener(l);
    }

    /**
     * 是否开启Recyclerview的松开加载更多功能，默认开启
     */
    public void enableReleaseToLoadMore(boolean enable) {
        this.enableReleaseToLoadMore = enable;
    }

    /**
     * 设置在数据加载完成以后,是否可以向上继续拉被刷新的view,默认为true
     *
     * @param enable
     */
    public void enablePullUpWhenLoadCompleted(boolean enable) {
        mEnablePullUpWhenLoadCompleted = enable;
    }

    /**
     * 设置在被刷新的view滑倒最底部的时候，是否允许被刷新的view继续往上滑动，默认是true
     */
    public void enableRecyclerViewPullUp(boolean enable) {
        enableRecyclerViewPullUp = enable;
    }

    public void setFooterCallBack(IFooterCallBack footerCallBack) {
        mFooterCallBack = footerCallBack;
    }

    /**
     * 设置阻尼系数，建议使用默认的
     *
     * @param ratio 默认 1.8
     */
    public void setDampingRatio(float ratio) {
        OFFSET_RADIO = ratio;
    }

    /**
     * 设置当下拉刷新完成以后，headerview和footerview被固定的时间
     * 注:考虑到ui效果，只有时间大于1s的时候，footerview被固定的效果才会生效
     *
     * @param pinnedTime
     */
    public void setPinnedTime(int pinnedTime) {
        mPinnedTime = pinnedTime;
        mContentView.setPinnedTime(pinnedTime);
    }

    /**
     * 设置是否在数据加载完成以后隐藏footerview
     *
     * @param hide true则隐藏footerview，false则反之，默认隐藏
     */
    public void setHideFooterWhenComplete(boolean hide) {
        mContentView.setHideFooterWhenComplete(hide);
    }

    /**
     * 设置在刷新的时候是否可以移动contentView
     *
     * @param isPinned true 固定不移动 反之，可以移动
     */
    public void setPinnedContent(boolean isPinned) {
        mIsPinnedContentWhenRefreshing = isPinned;
    }

    /**
     * 设置自定义headerView
     *
     * @param headerView headerView必须要实现 IHeaderCallBack接口
     */
    public void setCustomHeaderView(View headerView) {
        if (headerView instanceof IHeaderCallBack) {
            mHeaderView = headerView;
        } else {
            throw new RuntimeException(
                    "headerView must be implementes IHeaderCallBack!");
        }
    }


    /**
     * 设置自定义footerView
     *
     * @param footerView footerView必须要实现 IFooterCallBack接口
     */
    public void setCustomFooterView(View footerView) {
        if (footerView instanceof IFooterCallBack) {
            mFooterView = footerView;
        } else {
            throw new RuntimeException(
                    "footerView must be implementes IFooterCallBack!");
        }
    }

    /**
     * implements this interface to get refresh/load more event.
     */
    public interface XRefreshViewListener {
        public void onRefresh();

        /**
         * @param isSilence 是不是静默加载，静默加载即不显示footerview，自动监听滚动到底部并触发此回调
         */
        public void onLoadMore(boolean isSilence);

        /**
         * 用户手指释放的监听回调
         *
         * @param direction >0: 下拉释放，<0:上拉释放 注：暂时没有使用这个方法
         */
        public void onRelease(float direction);

        /**
         * 获取headerview显示的高度与headerview高度的比例
         *
         * @param offset  移动距离和headerview高度的比例，范围是0~1，0：headerview完全没显示
         *                1：headerview完全显示
         * @param offsetY headerview移动的距离
         */
        public void onHeaderMove(double offset, int offsetY);
    }

    public static class SimpleXRefreshListener implements XRefreshViewListener {

        @Override
        public void onRefresh() {
        }

        @Override
        public void onLoadMore(boolean isSilence) {
        }

        @Override
        public void onRelease(float direction) {
        }

        @Override
        public void onHeaderMove(double offset, int offsetY) {
        }

    }
}