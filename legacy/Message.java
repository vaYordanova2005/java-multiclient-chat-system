public class Message {
    String type;
    String user;
    String color;
    String text;
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