/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.utils

import java.io.File

object XdgUtils {
    fun getXdgDataHome(): File {
        val env = System.getenv("XDG_DATA_HOME")
        return if (env != null && !env.isEmpty()) {
            File(env)
        } else {
            File(System.getProperty("user.home"), ".local/share")
        }
    }

    fun getXdgCacheHome(): File {
        val env = System.getenv("XDG_CACHE_HOME")
        return if (env != null && !env.isEmpty()) {
            File(env)
        } else {
            File(System.getProperty("user.home"), ".cache")
        }
    }

    fun getXdgStateHome(): File {
        val env = System.getenv("XDG_STATE_HOME")
        return if (env != null && !env.isEmpty()) {
            File(env)
        } else {
            File(System.getProperty("user.home"), ".local/state")
        }
    }

    fun getXdgConfigHome(): File {
        val env = System.getenv("XDG_CONFIG_HOME")
        return if (env != null && !env.isEmpty()) {
            File(env)
        } else {
            File(System.getProperty("user.home"), ".config")
        }
    }
}
