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

import android.telephony.SmsMessage
import com.moez.QKSMS.blocking.BlockingClient
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.manager.NotificationManager
import com.moez.QKSMS.manager.ShortcutManager
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.util.Preferences
import com.moez.QKSMS.repository.ContactRepository
import io.reactivex.Flowable
import timber.log.Timber
import javax.inject.Inject

import java.util.NoSuchElementException

class ReceiveSms @Inject constructor(
        private val conversationRepo: ConversationRepository,
        private val blockingClient: BlockingClient,
        private val prefs: Preferences,
        private val messageRepo: MessageRepository,
        private val notificationManager: NotificationManager,
        private val updateBadge: UpdateBadge,
        private val shortcutManager: ShortcutManager,
        private val contactRepository: ContactRepository
) : Interactor<ReceiveSms.Params>() {

    class Params(val subId: Int, val messages: Array<SmsMessage>)

    override fun buildObservable(params: Params): Flowable<*> {
        return Flowable.just(params)
                .filter { it.messages.isNotEmpty() }
                .mapNotNull {
                    // Don't continue if the sender is blocked
                    val messages = it.messages
                    val address = messages[0].displayOriginatingAddress
                    val action = blockingClient.getAction(address).blockingGet()
                    val shouldDrop = prefs.drop.get()

                    /*
                    val queryResult = contactRepository.findContactUri(address)

                    var inContactsRepo: Boolean = false
                    try {
                        val uri = queryResult.blockingGet()
                        inContactsRepo = true;
                        Timber.v("uri=$uri")
                    } catch (ex: java.util.NoSuchElementException) {

                    }
                    Timber.v("address=$address queryResult=$inContactsRepo")
                    */

                    // If we should drop the message, don't even save it
                    if (action is BlockingClient.Action.Block && shouldDrop) {
                        return@mapNotNull null
                    }

                    val time = messages[0].timestampMillis
                    val body: String = messages
                            .mapNotNull { message -> message.displayMessageBody }
                            .reduce { body, new -> body + new }

                    // Add the message to the db
                    val message = messageRepo.insertReceivedSms(it.subId, address, body, time)

                    when (action) {
                        is BlockingClient.Action.Block -> {
                            messageRepo.markRead(message.threadId)
                            conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), action.reason)
                        }
                        is BlockingClient.Action.Unblock -> conversationRepo.markUnblocked(message.threadId)
                        else -> Unit
                    }

                    /*
                    if (!inContactsRepo) {
                        Timber.v("markedArchived")
                        conversationRepo.markArchived(message.threadId)
                    }
                    */

                    Timber.v("message1 = $message")

                    message
                }
                .doOnNext { message ->
                    Timber.v("message2 = $message")
                    val msg = conversationRepo.updateConversations(message.threadId) // Update the conversation
                    Timber.v("message2 = $msg")
                    msg
                }
                .mapNotNull { message ->
                    Timber.v("message3 = " + message.address.toString())

                    // This block will create new conversation from conversation repo.
                    // So we have to check whether the address is in contact list.
                    val queryResult = contactRepository.findContactUri(message.address)
                    var inContactsRepo: Boolean = false
                    try {
                        val uri = queryResult.blockingGet()
                        inContactsRepo = true;
                    } catch (ex: java.util.NoSuchElementException) {

                    }

                    // Check if the conversation exists in repo:
                    // If it does not exist in repo and the address is unknown, we will archived it.
                    // Otherwise, the address is in contact list or this is not a new conversation, we do not change its archived status.
                    // Therefore, once we unarchived a message whose address is not in contact list, we can receive new messages
                    // and get update notification.
                    val checkMsg = conversationRepo.getConversation(message.threadId)

                    Timber.v("message 3 " + checkMsg?.toString())

                    if(!inContactsRepo && checkMsg == null) {
                        // The address does not exist in conact list and this is a new conversation.
                        Timber.v("message3 markArchived")

                        // Because we cannot modify the message directly which will cause Exception.
                        // We create the conversation and then mark it as archived.
                        // Before return the message, we get the message again - the new message object will contain the latest archived state.
                        val tempMsg = conversationRepo.getOrCreateConversation(message.threadId)
                        conversationRepo.markArchived(message.threadId)
                    }

                    val msg = conversationRepo.getOrCreateConversation(message.threadId)

                    Timber.v("message3 = " + msg?.archived)
                    msg
                }
                .filter { conversation ->
                    Timber.v("conversation1 = $conversation")
                    if(conversation.archived) {
                        Timber.v("conversation1 archived")
                    }
                    else {
                        Timber.v("conversation1 NOT archived")
                    }
                    !conversation.blocked && !conversation.archived
                } // Don't notify for blocked conversations
                .doOnNext { conversation ->
                    Timber.v("test before check archived")
                    // Unarchive conversation if necessary
                    // if (conversation.archived) conversationRepo.markUnarchived(conversation.id)
                }
                .map { conversation ->
                    Timber.v("conversation3 = $conversation")
                    conversation.id
                } // Map to the id because [delay] will put us on the wrong thread
                .doOnNext { threadId -> notificationManager.update(threadId) } // Update the notification
                .doOnNext { shortcutManager.updateShortcuts() } // Update shortcuts
                .flatMap { updateBadge.buildObservable(Unit) } // Update the badge and widget
    }

}
