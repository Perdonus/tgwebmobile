package com.tgweb.core.data

/**
 * Lightweight service locator for workers/services that are created by the Android framework.
 */
object AppRepositories {
    lateinit var chatRepository: ChatRepository
    lateinit var mediaRepository: MediaRepository
    lateinit var notificationService: NotificationService

    fun isInitialized(): Boolean {
        return ::chatRepository.isInitialized && ::mediaRepository.isInitialized && ::notificationService.isInitialized
    }
}
