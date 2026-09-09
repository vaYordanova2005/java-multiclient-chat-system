package com.messenger.backend.model;

public class Message {
    // Public rather than package-private (as in the original default-package
    // version) because DAOs and the WebSocket handler now live in different
    // packages and both need direct field access — same fields, same shape,
    // just visible across the new package split.
    public String type;
    public String user;
    public String color;
    public String text;
    public String timestamp;
    public String room;

    public String receiver;

    // Avatar на ИЗПРАЩАЧА (msg.user), пътуващ заедно със съобщението.
    public String avatarId;

    public Message() {
    }

    public Message(String type, String user, String color, String text) {
        this.type = type;
        this.user = user;
        this.color = color;
        this.text = text;
    }
}
