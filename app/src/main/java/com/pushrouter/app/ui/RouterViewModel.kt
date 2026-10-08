package com.pushrouter.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pushrouter.app.data.RouterRepository
import com.pushrouter.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RouterViewModel(val repository: RouterRepository) : ViewModel() {
    val state = repository.state
    private val messageValue = MutableStateFlow<String?>(null)
    val message = messageValue.asStateFlow()
    private val busyValue = MutableStateFlow(false)
    val busy = busyValue.asStateFlow()
    private val verifiedValue = MutableStateFlow<Bot?>(null)
    val verifiedBot = verifiedValue.asStateFlow()
    private var polling: Job? = null

    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            busyValue.value = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { messageValue.value = e.message ?: "Check your settings and try again." }
            catch (e: com.pushrouter.app.telegram.TelegramException) { messageValue.value = e.message }
            catch (_: Exception) { messageValue.value = "Could not complete the action. Check your connection and try again." }
            finally { busyValue.value = false }
        }
    }
    fun dismissMessage() { messageValue.value = null }
    fun verifyBot(token: String) = action { verifiedValue.value = repository.verifyBot(token) }
    fun discardVerifiedBot() { verifiedValue.value = null }
    fun useVerifiedBot() = action {
        val bot = verifiedValue.value ?: return@action
        repository.configureBot(bot)
        verifiedValue.value = null
    }
    fun startPolling() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                try {
                    repository.prune()
                    val invitation = state.value.invitation
                    if (invitation != null && invitation.recipient == null) repository.pollInvitation()
                } catch (e: CancellationException) { throw e }
                catch (e: com.pushrouter.app.telegram.TelegramException) {
                    messageValue.value = e.message
                    break
                } catch (_: Exception) { /* A transient connection failure leaves the invitation available for retry. */ }
                delay(2_000)
            }
        }
    }
    fun stopPolling() { polling?.cancel(); polling = null }
}
