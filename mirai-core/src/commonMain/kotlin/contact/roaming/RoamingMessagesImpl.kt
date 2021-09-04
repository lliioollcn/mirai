/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */

package net.mamoe.mirai.internal.contact.roaming

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.roaming.RoamingMessage
import net.mamoe.mirai.contact.roaming.RoamingMessageFilter
import net.mamoe.mirai.contact.roaming.RoamingMessages
import net.mamoe.mirai.internal.contact.FriendImpl
import net.mamoe.mirai.internal.contact.uin
import net.mamoe.mirai.internal.message.toMessageChainOnline
import net.mamoe.mirai.internal.network.protocol.packet.chat.receive.MessageSvcPbGetRoamMsgReq
import net.mamoe.mirai.internal.network.protocol.packet.sendAndExpect
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.utils.check
import net.mamoe.mirai.utils.stream
import net.mamoe.mirai.utils.toLongUnsigned
import java.util.stream.Stream

internal sealed class RoamingMessagesImpl : RoamingMessages {
    abstract val contact: Contact
}

internal class RoamingMessagesImplFriend(
    override val contact: FriendImpl
) : RoamingMessagesImpl() {
    override suspend fun getMessagesIn(
        timeStart: Long,
        timeEnd: Long,
        filter: RoamingMessageFilter?
    ): Flow<MessageChain> {
        return flow {
            var lastMessageTime = timeEnd.coerceAtLeast(timeStart).coerceAtLeast(1)
            var random = 0L
            while (currentCoroutineContext().isActive) {
                val resp = MessageSvcPbGetRoamMsgReq.createForFriend(
                    client = contact.bot.client,
                    uin = contact.uin,
                    timeStart = timeStart.coerceAtLeast(1), // zero is default value, which will not be encoded.
                    lastMsgTime = lastMessageTime, // can be Long.MAX
                    random = random,
                    sig = byteArrayOf(),
                    pwd = byteArrayOf()
                ).sendAndExpect(contact.bot).value.check()

                val messages = resp.messages ?: break
                if (filter == null || filter === RoamingMessageFilter.ANY) {
                    // fast path
                    messages.forEach { emit(it.toMessageChainOnline(contact.bot)) }
                } else {
                    for (message in messages) {
                        val roamingMessage = object : RoamingMessage {
                            override val contact: Contact get() = this@RoamingMessagesImplFriend.contact
                            override val sender: Long get() = message.msgHead.fromUin
                            override val target: Long get() = message.msgHead.toUin // should convert to code for group
                            override val time: Long get() = message.msgHead.msgTime.toLongUnsigned()
                        }

                        if (filter.invoke(roamingMessage)) {
                            emit(message.toMessageChainOnline(contact.bot))
                        }
                    }
                }

                lastMessageTime = resp.lastMessageTime
                random = resp.random
            }
        }
    }

    override suspend fun getMessagesStream(
        timeStart: Long,
        timeEnd: Long,
        filter: RoamingMessageFilter?,
    ): Stream<MessageChain> {
        return stream {
            var lastMessageTime = timeEnd
            var random = 0L
            while (true) {
                val resp = runBlocking {
                    MessageSvcPbGetRoamMsgReq.createForFriend(
                        client = contact.bot.client,
                        uin = contact.uin,
                        timeStart = timeStart,
                        lastMsgTime = lastMessageTime,
                        random = random,
                        maxCount = 1000,
                        sig = byteArrayOf(),
                        pwd = byteArrayOf()
                    ).sendAndExpect(contact.bot).value.check()
                }

                val messages = resp.messages ?: break
                if (filter == null || filter === RoamingMessageFilter.ANY) {
                    messages.forEach { yield(runBlocking { it.toMessageChainOnline(contact.bot) }) }
                } else {
                    for (message in messages) {
                        val roamingMessage = object : RoamingMessage {
                            override val contact: Contact get() = this@RoamingMessagesImplFriend.contact
                            override val sender: Long get() = message.msgHead.fromUin
                            override val target: Long get() = message.msgHead.toUin
                            override val time: Long get() = message.msgHead.msgTime.toLongUnsigned()
                        }

                        if (filter.invoke(roamingMessage)) {
                            yield(runBlocking { message.toMessageChainOnline(contact.bot) })
                        }
                    }
                }

                lastMessageTime = resp.lastMessageTime
                random = resp.random
            }
        }
    }

    override suspend fun getMessage(id: Int, internalId: Int, time: Long): MessageChain? {
        TODO()
//        return MessageSvcPbGetRoamMsgReq.createForFriend(
//            client = contact.bot.client,
//            uin = contact.uin,
//            timeStart = 0,
//            lastMsgTime = Int.MAX_VALUE.toLongUnsigned(),
//            random = internalId.toLongUnsigned(),
//            maxCount = 1000,
//            sig = byteArrayOf()
//        ).sendAndExpect(contact.bot).value.check().messages?.filter { it.msgHead.msgUid.contains(id) }?.firstOrNull()
    }
}