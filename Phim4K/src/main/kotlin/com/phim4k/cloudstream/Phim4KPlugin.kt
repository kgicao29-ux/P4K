package com.phim4k.cloudstream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Phim4KPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Phim4KProvider())
    }
}
