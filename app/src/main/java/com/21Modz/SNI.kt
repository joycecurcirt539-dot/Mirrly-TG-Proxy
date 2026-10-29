package com.`21Modz`

import android.content.Context

class SNI {
    companion object {
        @JvmField
        var ok: Boolean = false

        init {
            try {
                System.loadLibrary("sni")
                ok = true
            } catch (t: Throwable) {
                ok = false
            }
        }

        @JvmStatic
        external fun available(): Boolean

        @JvmStatic
        external fun set(domain: String): Boolean

        @JvmStatic
        external fun ready(): Boolean

        @JvmStatic
        external fun current(context: Context): String?
    }
}
