/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.interactor

import android.net.Uri
import com.moez.QKSMS.blocking.BlockingClient
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.manager.ActiveConversationManager
import com.moez.QKSMS.manager.NotificationManager
import com.moez.QKSMS.repository.ContactRepository
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.repository.SyncRepository
import com.moez.QKSMS.util.Preferences
import io.reactivex.Flowable
import timber.log.Timber
import javax.inject.Inject

class ReceiveMms @Inject constructor(
    private val activeConversationManager: ActiveConversationManager,
    private val conversationRepo: ConversationRepository,
    private val blockingClient: BlockingClient,
    private val prefs: Preferences,
    private val syncManager: SyncRepository,
    private val messageRepo: MessageRepository,
    private val notificationManager: NotificationManager,
    private val updateBadge: UpdateBadge,
    private val contactRepository: ContactRepository
) : Interactor<Uri>() {

    override fun buildObservable(params: Uri): Flowable<*> {
        return Flowable.just(params)
                .mapNotNull(syncManager::syncMessage) // Sync the message
                .doOnNext { message ->
                    // TODO: Ideally this is done when we're saving the MMS to ContentResolver
                    // This change can be made once we move the MMS storing code to the Data module
                    if (activeConversationManager.getActiveConversation() == message.threadId) {
                        messageRepo.markRead(message.threadId)
                    }
                }
                .mapNotNull { message ->
                    // Because we use the smsmms library for receiving and storing MMS, we'll need
                    // to check if it should be blocked after we've pulled it into realm. If it
                    // turns out that it should be dropped, then delete it
                    // TODO Don't store blocked messages in the first place
                    val action = blockingClient.getAction(message.address).blockingGet()
                    val shouldDrop = prefs.drop.get()
                    Timber.v("block=$action, drop=$shouldDrop")

                    if (action is BlockingClient.Action.Block && shouldDrop) {
                        messageRepo.deleteMessages(message.id)
                        return@mapNotNull null
                    }

                    var checkMsg = conversationRepo.getConversation(message.threadId)

                    when (action) {
                        is BlockingClient.Action.Block -> {
                            messageRepo.markRead(message.threadId)
                            conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), action.reason)
                        }
                        is BlockingClient.Action.Unblock -> conversationRepo.markUnblocked(message.threadId)
                        else -> Unit
                    }

                    checkMsg = conversationRepo.getConversation(message.threadId)

                    message
                }
                .doOnNext { message ->
                    conversationRepo.updateConversations(message.threadId) // Update the conversation
                }
                .mapNotNull { message ->
                    // This block will create new conversation from conversation repo.
                    // So we have to check whether the address is in contact list.
                    val queryResult = contactRepository.findContactUri(message.address)
                    var inContactsRepo: Boolean = false
                    try {
                        val uri = queryResult.blockingGet()
                        inContactsRepo = true;
                    } catch (ex: java.util.NoSuchElementException) {

                    }

                    // This is different from ReceiveSms.
                    // The MMS conversation will be added into conversation repo in the very beginning.
                    // So, we can check the message length.
                    // If there is only one message in the repo, this is the first message of the conversation.
                    // Otherwise, if the size of message of this threadId is more than 1, this is NOT a new
                    // conversation.
                    val checkMsgs = messageRepo.getLastIncomingMessage(message.threadId)

                    if(!inContactsRepo && checkMsgs.size == 1) {
                        // The address does not exist in contact list and this is a new conversation.
                        Timber.d("this conversation will be archived")

                        // Because we cannot modify the message directly which will cause Exception.
                        // We create the conversation and then mark it as archived.
                        // Before return the message, we get the message again - the new message object will contain the latest archived state.
                        val tempMsg = conversationRepo.getOrCreateConversation(message.threadId)
                        conversationRepo.markArchived(message.threadId)
                    }

                    conversationRepo.getOrCreateConversation(message.threadId) // Map message to conversation
                }
                .filter { conversation -> !conversation.blocked && !conversation.archived } // Don't notify for blocked conversations
                .doOnNext { conversation ->
                    Timber.d("test before check archived")
                    // Unarchive conversation if necessary
                    // if (conversation.archived) conversationRepo.markUnarchived(conversation.id)
                }
                .map { conversation -> conversation.id } // Map to the id because [delay] will put us on the wrong thread
                .doOnNext(notificationManager::update) // Update the notification
                .flatMap { updateBadge.buildObservable(Unit) } // Update the badge
    }

}