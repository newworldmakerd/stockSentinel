package com.stocksentinel.commands;

/** Ephemeral only; never log/toString callback objects or persist chat text. */
public final class GroupMessage {
    public final String msgId,botId,chatId,userId,text;
    public final Long createdAt;
    public GroupMessage(String msgId,String botId,String chatId,String userId,String text,Long createdAt) {
        this.msgId=msgId;this.botId=botId;this.chatId=chatId;this.userId=userId;this.text=text;this.createdAt=createdAt;
    }
}
