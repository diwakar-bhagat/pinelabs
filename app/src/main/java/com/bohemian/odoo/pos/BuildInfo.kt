package com.bohemian.odoo.pos

/** The installed build, as Setup, the top bar and crash reports show it. */
object BuildInfo {
    /** "0.4.0 (4)" */
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** "0.4.0 (4) PROD" */
    val label = "$version ${BuildConfig.ENV_LABEL}"
}
