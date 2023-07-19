package dev.vanutp.tgbridge.fabric

import dev.vanutp.tgbridge.TelegramBridgeBase

class TelegramBridge : TelegramBridgeBase() {
    companion object {
        const val MOD_ID = "tgbridge"
    }
    override val logger = Logger()
    override val platform: String = "fabric"
}
