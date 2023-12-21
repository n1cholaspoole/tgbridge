package dev.vanutp.tgbridge.common

import dev.vanutp.tgbridge.common.models.Config
import dev.vanutp.tgbridge.common.models.LastMessage
import dev.vanutp.tgbridge.common.models.LastMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds


abstract class TelegramBridge {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    protected abstract val logger: AbstractLogger
    protected abstract val platform: Platform
    private lateinit var config: Config
    private lateinit var bot: TelegramBot

    private var lastMessage: LastMessage? = null
    private val lastMessageLock = Mutex()

    private lateinit var lang: Map<String, String>

    private suspend fun resetLastMessage() {
        lastMessageLock.withLock {
            lastMessage = null
        }
    }

    private suspend fun sendMessage(text: String): TgMessage {
        return bot.sendMessage(config.chatId, text, replyToMessageId = config.threadId)
    }

    private suspend fun editMessageText(messageId: Int, text: String): TgMessage {
        return bot.editMessageText(config.chatId, messageId, text)
    }

    private suspend fun deleteMessage(messageId: Int) {
        bot.deleteMessage(config.chatId, messageId)
    }

    private fun loadLang() {
        val langPath = platform.configDir.resolve("lang.json")
        if (!langPath.exists()) {
            throw Exception("lang.json not found")
        }
        lang = Json.decodeFromString<Map<String, String>>(langPath.readText())
    }

    fun init() {
        logger.info("tgbridge starting on ${platform.name}")
        config = Config.load(platform.configDir)
        bot = TelegramBot(config, logger)
        if (config.botToken == Config().botToken || config.chatId == Config().chatId) {
            logger.error("Can't start with default config values: please fill in botToken and chatId")
            return
        }
        loadLang()

        runBlocking {
            bot.init()
            sendMessage("✅ <b>Сервер запущен!</b>")
        }
        bot.registerCommandHandler("list") {
            val onlinePlayerNames = platform.getOnlinePlayerNames()
            sendMessage("${onlinePlayerNames.size} игроков онлайн: ${onlinePlayerNames.joinToString()}")
        }
        bot.registerMessageHandler { msg ->
            if (msg.chat.id != config.chatId || config.threadId != null && msg.messageThreadId != config.threadId) {
                return@registerMessageHandler
            }
            resetLastMessage()
            val components = mutableListOf<Component>()

            components.add(Component.text("<${msg.senderName}>", NamedTextColor.AQUA))

            msg.replyToMessage?.let { reply ->
                if (reply.messageId == config.threadId) {
                    return@let
                }
                components.add(
                    Component.text(
                        "[R ${reply.senderName}: ${reply.effectiveText.take(40)}]",
                        NamedTextColor.BLUE
                    )
                )
            }

            val forwardFromName = msg.forwardFrom?.let { _ ->
                (msg.forwardFrom.firstName + " " + (msg.forwardFrom.lastName ?: "")).trim()
            } ?: msg.forwardFromChat?.let {
                msg.forwardFromChat.title
            }

            forwardFromName?.let {
                components.add(Component.text("[F $it]", NamedTextColor.GRAY))
            }

            msg.animation?.let {
                components.add(Component.text("[GIF]", NamedTextColor.GREEN))
            } ?: msg.document?.let {
                components.add(Component.text("[Document]", NamedTextColor.GREEN))
            }
            msg.photo?.let {
                components.add(Component.text("[Photo]", NamedTextColor.GREEN))
            }
            msg.audio?.let {
                components.add(Component.text("[Audio]", NamedTextColor.GREEN))
            }
            msg.sticker?.let {
                components.add(Component.text("[Sticker]", NamedTextColor.GREEN))
            }
            msg.video?.let {
                components.add(Component.text("[Video]", NamedTextColor.GREEN))
            }
            msg.videoNote?.let {
                components.add(Component.text("[Video message]", NamedTextColor.GREEN))
            }
            msg.voice?.let {
                components.add(Component.text("[Voice message]", NamedTextColor.GREEN))
            }
            msg.poll?.let {
                components.add(Component.text("[Poll]", NamedTextColor.GREEN))
            }

            components.add(Component.text(msg.effectiveText))


            platform.broadcastMessage(
                components
                    .flatMap { listOf(it, Component.text(" ")) }
                    .fold(Component.text()) { acc, component -> acc.append(component) }
                    .build()
            )
        }
        coroutineScope.launch {
            bot.startPolling()
        }
        platform.registerCommand(arrayOf("tgbridge", "reload")) { ctx ->
            try {
                config = Config.load(platform.configDir)
                loadLang()
            } catch (e: Exception) {
                ctx.reply("Error reloading config: " + (e.message ?: e.javaClass.name))
                return@registerCommand false
            }
            ctx.reply("Config reloaded. Note that bot token can't be changed without a restart")
            return@registerCommand true
        }
        platform.registerChatMessageListener { e ->
            coroutineScope.launch {
                val rawMinecraftText = (e.text as TextComponent).content()
                if (!rawMinecraftText.startsWith(config.requirePrefixInMinecraft ?: "")) {
                    return@launch
                }
                val currText = "<b>[${e.username}]</b> ${rawMinecraftText.escapeHTML()}"
                val currDate = Clock.System.now()
                lastMessageLock.withLock {
                    lastMessage?.let {
                        if (
                            it.type == LastMessageType.TEXT
                            && (it.text + "\n" + currText).length <= 4000
                            && currDate - it.date < (config.messageMergeWindowSeconds ?: 0).seconds
                        ) {
                            it.text += "\n" + currText
                            it.date = currDate
                            editMessageText(lastMessage!!.id, it.text!!)
                            true
                        } else {
                            null
                        }
                    } ?: let {
                        val newMsg = sendMessage(currText)
                        lastMessage = LastMessage(LastMessageType.TEXT, newMsg.messageId, currDate, text = currText)
                    }
                }
            }
        }
        platform.registerPlayerDeathListener { e ->
            coroutineScope.launch {
                resetLastMessage()
                val component = e.text as TranslatableComponent
                sendMessage("\u2620\uFE0F <b>${component.translate(lang).escapeHTML()}</b>")
            }
        }
        platform.registerPlayerJoinListener { e ->
            coroutineScope.launch {
                lastMessageLock.withLock {
                    lastMessage?.let {
                        if (
                            it.type == LastMessageType.LEAVE
                            && it.leftPlayer!! == e.username
                            && Clock.System.now() - it.date < (config.messageMergeWindowSeconds ?: 0).seconds
                        ) {
                            deleteMessage(it.id)
                            true
                        } else {
                            null
                        }
                    }
                        ?: sendMessage("<b>\uD83E\uDD73 ${e.username} зашёл на сервер</b>")
                    lastMessage = null
                }
            }
        }
        platform.registerPlayerLeaveListener { e ->
            coroutineScope.launch {
                resetLastMessage()
                val newMsg = sendMessage("<b>\uD83D\uDE15 ${e.username} покинул сервер</b>")
                lastMessageLock.withLock {
                    lastMessage = LastMessage(
                        LastMessageType.LEAVE,
                        newMsg.messageId,
                        Clock.System.now(),
                        leftPlayer = e.username
                    )
                }
            }
        }
        platform.registerPlayerAdvancementListener { e ->
            coroutineScope.launch {
                val component = e.text as TranslatableComponent
                val advancementTypeKey = component.key()
                val squareBracketsComponent = component.args()[1] as TranslatableComponent
                resetLastMessage()
                val advancementName = (squareBracketsComponent.args()[0] as TranslatableComponent).translate(lang)
                sendMessage("<b>\uD83D\uDE3C ${e.username} получил достижение ${advancementName.escapeHTML()}</b>")
            }
        }
    }

    fun shutdown() {
        coroutineScope.launch {
            sendMessage("❌ <b>Сервер остановлен!</b>")
            bot.stopPolling()
        }
    }
}
