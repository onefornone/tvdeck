package com.tvdeck.helper

import android.app.Activity
import android.os.Bundle

/** Only exists so the system "wakes up" the helper after installing it. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
