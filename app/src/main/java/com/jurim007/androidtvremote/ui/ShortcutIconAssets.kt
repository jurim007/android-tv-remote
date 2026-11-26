package com.jurim007.androidtvremote.ui

import com.jurim007.androidtvremote.domain.TvAppCatalog

/** The bundled logo for a shortcut, matched by its app ID or, failing that, its name. */
internal fun shortcutIconAsset(packageName: String, name: String?): String? =
    TvAppCatalog.match(packageName, name)?.iconAsset
