package com.jamal2367.styx.search.engine

import com.jamal2367.styx.R

/**
 * The Ask search engine.
 */
class AskSearch : BaseSearchEngine(
    "file:///android_asset/ask.webp",
    "http://www.ask.com/web?qsrc=0&o=0&l=dir&qo=StyxBrowser&q=",
    R.string.search_engine_ask
)
