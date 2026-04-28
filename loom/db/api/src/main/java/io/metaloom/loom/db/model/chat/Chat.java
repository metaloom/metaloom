package io.metaloom.loom.db.model.chat;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.MetaElement;
import io.vertx.core.json.JsonArray;

public interface Chat extends CUDElement<Chat>, MetaElement<Chat> {

	String getTitle();

	Chat setTitle(String title);

	JsonArray getMessages();

	Chat setMessages(JsonArray messages);

}
