@file:Suppress("ktlint:standard:filename", "ktlint:standard:no-wildcard-imports")

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.logging.LogLevel
import com.natpryce.konfig.ConfigurationProperties
import dataClasses.Data
import dataClasses.Strings
import dataClasses.TopArtist
import dataClasses.Track
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

private lateinit var config: ConfigurationProperties
private lateinit var logger: Logger
private lateinit var client: HttpClient
private lateinit var bot: Bot

fun startUpdate(
    properties: ConfigurationProperties,
    loggerFunc: Logger,
    httpClient: HttpClient,
) {
    config = properties
    logger = loggerFunc
    client = httpClient
    bot = createBot(properties[Data.tokenBot])

    fun getTime(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    CoroutineScope(Dispatchers.Default).launch {
        while (true) {
            updateMessage()
            loggerFunc.info("${getTime()} - message is updated")
            delay(properties[Data.updateInterval] * 60000)
        }
    }

    bot.startPolling()
    loggerFunc.info("Bot is started")
}

private fun deserialize(logger: Logger): Strings =
    try {
        Deserialized(logger).getDeserialized("strings_ru.json")!!
    } catch (e: Exception) {
        logger.error(e.toString())
        throw e
    }

private fun createBot(tokenBot: String): Bot =
    bot {
        token = tokenBot
        logLevel = LogLevel.Error
        dispatch {
            command("update") {
                if (checkNullMessageFrom(message.from)) {
                    updateMessage(message.from!!.id)
                }
            }
            command("info") {
                if (checkNullMessageFrom(message.from)) {
                    val messageSplit = message.text!!.split(" ")
                    if (messageSplit.size >= 2) {
                        bot.sendMessage(
                            ChatId.fromId(message.from!!.id),
                            infoText(messageSplit[1]),
                            parseMode = ParseMode.HTML,
                        )
                    } else {
                        bot.sendMessage(
                            ChatId.fromId(message.from!!.id),
                            infoText(),
                            parseMode = ParseMode.HTML,
                        )
                    }
                }
            }
        }
    }

private fun checkNullMessageFrom(from: User?) =
    if (from == null) {
        logger.warn("message.from is null")
        false
    } else {
        true
    }

private suspend fun infoText(lastFmUser: String? = null): String {
    val user =
        LastFmApi(client)
            .getInfo(
                lastFmUser ?: config[Data.user],
                config[Data.apiKey],
            ).user
    val deserialized = deserialize(logger)
    val text =
        """
        <b>${deserialized.infoForAccount}: <a href="${user.image[2].text}">${user.name}</a></b>
        
        ${deserialized.realName}: ${user.realname}
        ${deserialized.country}: ${user.country}
        ${deserialized.subscriber}: ${user.subscriber}
        
        ${deserialized.playcount}: ${user.playcount}
        ${deserialized.artistCount}: ${user.artist_count}
        ${deserialized.trackCount}: ${user.track_count}
        ${deserialized.albumCount}: ${user.album_count}
        ${deserialized.playlists}: ${user.playlists}
        
        ${deserialized.link}: <a href="${user.url}">${user.name}</a>
        ${deserialized.registered}: ${Date(user.registered.text * 1000L)}
        """.trimIndent()
    return text
}

private suspend fun updateMessage(userId: Long? = null) {
    bot.editMessageText(
        chatId = ChatId.fromId(config[Data.chatId]),
        messageId = config[Data.messageId],
        text = buildText(),
        parseMode = ParseMode.HTML,
        disableWebPagePreview = true,
    )
    if (userId != null) {
        bot.forwardMessage(
            ChatId.fromId(userId),
            ChatId.fromId(config[Data.chatId]),
            messageId = config[Data.messageId],
        )
    }
}

private suspend fun buildText(): String {
    val deserialized = deserialize(logger)
    val text = StringBuilder()
    val recentTracks = getRecentSongs()?.ifEmpty { null }
    val favoriteArtists = getFavoriteArtists()?.ifEmpty { null }

    if (recentTracks?.size != config[Data.limitForTracks]) {
        text.append("${deserialized.nowPlaying}\n")
        addNowPlaying(text, recentTracks?.get(0))
    }

    text.append("\n${deserialized.pastSongs}\n")
    recentTracks?.let {
        addRecentTracks(text, it)
    } ?: run {
        logger.warn("Result of getRecentSongs() is empty")
        text.append(deserialized.thereIsNothingHere)
    }

    text.append("\n${deserialized.favoriteArtists}\n")
    favoriteArtists?.let {
        addFavoriteArtists(text, favoriteArtists)
    } ?: run {
        logger.warn("Result of getFavoriteArtists() is empty")
        text.append(deserialized.thereIsNothingHere)
    }

    return text
        .toString()
        .replace("&", "&amp;")
}

private fun addNowPlaying(
    text: StringBuilder,
    track: Track?,
) {
    if (track != null) {
        text
            .append(
                """${track.artist.text} - <a href="${track.url}">${track.name}</a>""",
            ).append("\n\n")
    }
}

private fun addRecentTracks(
    text: StringBuilder,
    recentTracks: List<Track>,
) {
    recentTracks.onEach { track ->
        text
            .append(
                """${track.artist.text} - <a href="${track.url}">${track.name}</a>""",
            ).append("\n")
    }
}

private fun addFavoriteArtists(
    text: StringBuilder,
    listArtists: List<TopArtist>,
) {
    val deserialized = deserialize(logger)

    listArtists.forEachIndexed { index, artist ->
        text
            .append(
                """${index + 1}. <a href="${artist.url}">${artist.name}</a> - ${artist.playcount} ${deserialized.listens}""",
            ).append("\n")
    }
}

private suspend fun getFavoriteArtists(): List<TopArtist>? =
    safeApiCall {
        val user = config[Data.user]
        val apiKey = config[Data.apiKey]
        LastFmApi(client).getTopArtists(user, apiKey, limit = config[Data.limitForArtists])?.topartists?.artist
    }

private suspend fun getRecentSongs(): List<Track>? =
    safeApiCall {
        val user = config[Data.user]
        val apiKey = config[Data.apiKey]
        LastFmApi(client).getRecentTracks(user, apiKey, limit = config[Data.limitForTracks]).recenttracks.track
    }

private suspend fun <T> safeApiCall(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: HttpRequestTimeoutException) {
        logger.error("Request timeout has expired")
        null
    } catch (e: Exception) {
        logger.error("Error occurred", e)
        null
    }
