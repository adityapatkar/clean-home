package com.aditya.cleanhome;

import android.service.dreams.DreamService;

/** System screensaver entry point; the same scene the launcher shows when idle. */
public class ClockDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(new Screensaver(this));
    }
}
