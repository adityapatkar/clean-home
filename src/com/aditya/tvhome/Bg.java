package com.aditya.tvhome;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One low-priority worker thread for network and disk work, plus a main-thread hop. */
final class Bg {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clean-home-bg");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {
    }

    static void run(Runnable r) {
        WORKER.execute(r);
    }

    static void main(Runnable r) {
        MAIN.post(r);
    }
}
