package de.augmentia.quad.core.message;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import java.io.IOException;
import java.util.List;

public class MessageSerializer extends StdSerializer<Message> {

    public MessageSerializer() {
        super(Message.class);
    }

    @Override
    public void serialize(Message msg, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartObject();
        gen.writeStringField("id", msg.id());
        gen.writeStringField("timestamp", msg.timestamp().toString());
        gen.writeFieldName("delegate");
        gen.writeString(ChatMessageSerializer.messagesToJson(List.of(msg.delegate())));
        gen.writeObjectField("metadata", msg.metadata());
        gen.writeEndObject();
    }
}
