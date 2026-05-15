package com.rokid.glass.utils;

public class FuncDebounce {

    public long mLastClickTime;
    public long mNeedDuration;

    public FuncDebounce(long needDuration) {
        this.mNeedDuration = needDuration;
    }

    public void mark() {
        mLastClickTime = System.currentTimeMillis();
    }

    public boolean checkLock() {
        return System.currentTimeMillis() - mLastClickTime <= mNeedDuration;
    }

    public boolean canDone() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastClickTime > mNeedDuration) {
            mLastClickTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    public void close() {
        mLastClickTime = 0L;
    }

}
