package com.jamal2367.styx.search.engine

import com.jamal2367.styx.R

/**
 * The Google search engine.
 *
 * See https://www.google.com/images/srpr/logo11w.png for the icon.
 */
class GoogleSearch : BaseSearchEngine(
    "file:///android_asset/google.png",
    "https://www.google.com/search?client=styx&ie=UTF-8&oe=UTF-8&q=",
    R.string.search_engine_google
)