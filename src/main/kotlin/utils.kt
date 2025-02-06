@file:Suppress("ktlint:standard:filename", "ktlint:standard:no-wildcard-imports")

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.logging.LogLevel
import com.natpryce.konfig.*
import dataClasses.TopArtist
import dataClasses.Track
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Serializable
data class Strings(
    val pastSongs: String,
    val favoriteArtists: String,
    val listens: String,
    val thereIsNothingHere: String,
)

object Data : PropertyGroup() {
    val apiKey by stringType
    val user by stringType
    val tokenBot by stringType
    val chatId by longType
    val messageId by longType
    val userAgent by stringType
    val updateInterval by longType
    val limitForArtists by intType
    val limitForTracks by intType
}

@Suppress("ktlint:standard:property-naming")
const val notes = "\uD83C\uDFB6"

@Suppress("ktlint:standard:property-naming")
const val whiteHeart = "\uD83E\uDE76"

@Suppress("ktlint:standard:property-naming")
const val blueHeart = "\uD83E\uDE75"

@Suppress("ktlint:standard:property-naming")
const val think = "\uD83E\uDD14"

val config = ConfigurationProperties.fromResource("config.properties")
private val logger: Logger = LoggerFactory.getLogger("SpotifyBotLogger")
val bot = createBot()

private val client =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            gson()
        }
        install(UserAgent) {
            config[Data.userAgent]
        }
    }
private val lastFmApi = LastFmApi(client)

class Deserialized {
    fun getDeserialized(fileName: String): Strings? {
        val file = Deserialized::class.java.getResource(fileName)?.readText()

        return if (file != null) {
            Json.decodeFromString<Strings>(file)
        } else {
            logger.error("strings.json is null")
            null
        }
    }
}

private val deserialized: Strings =
    try {
        Deserialized().getDeserialized("strings.json")!!
    } catch (e: Exception) {
        logger.error(e.toString())
        throw e
    }

private fun createBot(): Bot =
    bot {
        token = config[Data.tokenBot]
        logLevel = LogLevel.Error
        dispatch {
            command("update") {
                if (message.from != null) {
                    updateMessage(message.from!!.id)
                } else {
                    logger.warn("message.from is null")
                    return@command
                }
            }
        }
    }

suspend fun updateMessage(userId: Long? = null) {
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
    val text =
        StringBuilder().append("$notes${deserialized.pastSongs}$notes\n")

    getRecentSongs().also { list ->
        if (list.isNotEmpty()) {
            list.forEach { track ->
                text
                    .append(
                        """${track.artist.text} - <a href="${track.url}">${track.name}</a>""",
                    ).append("\n")
            }
        } else {
            logger.warn("Result of getRecentSongs() is empty")
            text.append("${deserialized.thereIsNothingHere} $think")
        }
    }

    text.append("\n$whiteHeart${deserialized.favoriteArtists}$blueHeart\n")

    getFavoriteArtists().also { list ->
        if (list != null) {
            if (list.isNotEmpty()) {
                list.forEachIndexed { index, artist ->
                    text
                        .append(
                            """${index + 1}. <a href="${artist.url}">${artist.name}</a> - ${artist.playcount} ${deserialized.listens}""",
                        ).append("\n")
                }
            }
        } else {
            logger.warn("Result of getFavoriteArtists() is empty")
            text.append("${deserialized.thereIsNothingHere} $think")
        }
    }

    return text
        .toString()
        .replace("&", "&amp;")
}

private suspend fun getFavoriteArtists(): List<TopArtist>? =
    try {
        val user = config[Data.user]
        val apiKey = config[Data.apiKey]
        lastFmApi.getTopArtists(user, apiKey, limit = config[Data.limitForArtists])?.topartists?.artist
    } catch (e: Exception) {
        logger.error("Error fetching favorite artists: ${e.message}", e)
        emptyList()
    }

private suspend fun getRecentSongs(): List<Track> =
    try {
        val user = config[Data.user]
        val apiKey = config[Data.apiKey]
        lastFmApi.getRecentTracks(user, apiKey, limit = config[Data.limitForTracks]).recenttracks.track
    } catch (e: Exception) {
        logger.error("Error fetching recent songs: ${e.message}", e)
        emptyList()
    }
