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

    // The SENDER's avatar (msg.user), traveling along with the message.
    public String avatarId;

    // Only for type == "username_changed" — the new session token (see
    // TokenService), issued for the new name. The old token carries the
    // now-nonexistent old name and UserDAO.userExists will reject it on the
    // next connect, so the FE must replace its in-memory token with this
    // one immediately, otherwise the next reconnect fails with "token rejected".
    public String token;

    public Message() {
    }

    public Message(String type, String user, String color, String text) {
        this.type = type;
        this.user = user;
        this.color = color;
        this.text = text;
    }
}
