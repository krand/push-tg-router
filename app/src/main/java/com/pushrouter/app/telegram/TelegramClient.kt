package com.pushrouter.app.telegram

import com.pushrouter.core.Bot
import com.pushrouter.core.CapturedNotification
import com.pushrouter.core.Recipient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class TelegramException(val code: Int, val retryAfter: Long = 0) : Exception(when (code) {
    401 -> "The bot token is no longer valid. Update it in Settings."
    403 -> "The recipient blocked the bot or the chat is unavailable."
    409 -> "Another client is polling this bot. Use a dedicated bot for this phone."
    429 -> "Telegram rate limit reached. Delivery will retry later."
    else -> "Telegram rejected the request (code $code)."
})

data class PairingUpdate(val id: Long, val startToken: String?, val recipient: Recipient?, val date: Long)

/** No HTTP logging. Exceptions never contain the credential-bearing API URL or raw responses. */
class TelegramClient {
    fun verify(token: String): Bot {
        require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]{20,}"))) { "Enter the token provided by BotFather." }
        val me = request(token, "getMe", JSONObject()).getJSONObject("result")
        require(me.optBoolean("is_bot")) { "This credential does not identify a bot." }
        val info = request(token, "getWebhookInfo", JSONObject()).getJSONObject("result")
        require(info.optString("url").isBlank()) { "This bot has an active webhook. Use a dedicated bot without a webhook." }
        return Bot(me.getLong("id"), me.getString("username"), token)
    }

    fun updates(bot: Bot, offset: Long): List<PairingUpdate> {
        val data = request(bot.token, "getUpdates", JSONObject()
            .put("offset", offset).put("timeout", 0).put("allowed_updates", JSONArray().put("message")))
            .getJSONArray("result")
        return (0 until data.length()).map { i ->
            val item = data.getJSONObject(i)
            val message = item.optJSONObject("message")
            val sender = message?.optJSONObject("from")
            val chat = message?.optJSONObject("chat")
            val text = message?.optString("text", "").orEmpty()
            val private = chat?.optString("type") == "private" && sender?.optBoolean("is_bot") == false
                && !message.has("forward_origin")
            val token = Regex("^/start(?:@[A-Za-z0-9_]+)? ([A-Za-z0-9_-]{32})$").matchEntire(text)?.groupValues?.get(1)
            val recipient = if (private && sender != null && chat != null) Recipient(
                sender.getLong("id"), chat.getLong("id"),
                listOf(sender.optString("first_name"), sender.optString("last_name")).filter { it.isNotBlank() }.joinToString(" "),
                sender.optString("username").takeIf { it.isNotBlank() },
            ) else null
            PairingUpdate(item.getLong("update_id"), token, recipient, (message?.optLong("date") ?: 0) * 1000)
        }
    }

    fun send(bot: Bot, chatId: Long, notification: CapturedNotification): Long {
        val text = buildString {
            append(notification.source.appName)
            append("\n\n")
            if (notification.title.isNotBlank()) { append(notification.title); append("\n") }
            append(notification.text)
        }.take(4096)
        return request(bot.token, "sendMessage", JSONObject().put("chat_id", chatId).put("text", text)
            .put("link_preview_options", JSONObject().put("is_disabled", true)))
            .getJSONObject("result").getLong("message_id")
    }

    private fun request(token: String, method: String, payload: JSONObject): JSONObject {
        val connection = URI("https://api.telegram.org/bot$token/$method").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(512_000) } ?: throw TelegramException(status)
            val response = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { throw IOException("Invalid Telegram response") }
            if (!response.optBoolean("ok")) throw TelegramException(response.optInt("error_code", status), response.optJSONObject("parameters")?.optLong("retry_after") ?: 0)
            return response
        } catch (e: TelegramException) { throw e }
        catch (_: IOException) { throw IOException("Telegram connection failed") }
        finally { connection.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            if (output.size() + count > limit) throw IOException("Telegram response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
