package rs.teslaris.core.util.coarnotify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public class CoarNotifyPayloadBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();


    public static String buildIngestOffer(IngestOffer offer) {
        var root = MAPPER.createObjectNode();
        root.set("@context", MAPPER.createArrayNode()
            .add("https://www.w3.org/ns/activitystreams")
            .add("https://coar-notify.net"));
        root.put("id", offer.notificationId());
        root.set("type", MAPPER.createArrayNode().add("Offer").add("coar-notify:IngestAction"));
        root.set("actor", service(offer.originId(), offer.originName(), null));
        root.set("origin", service(offer.originId(), null, offer.originInbox()));
        root.set("target", service(offer.targetId(), null, offer.targetInbox()));

        var item = MAPPER.createObjectNode();
        item.put("id", offer.itemUrl());
        item.put("mediaType", offer.mediaType());
        item.set("type", MAPPER.createArrayNode().add("Article").add("sorg:ScholarlyArticle"));

        var object = MAPPER.createObjectNode();
        object.put("id", offer.objectId());
        if (Objects.nonNull(offer.citeAs())) {
            object.put("ietf:cite-as", offer.citeAs());
        }
        if (Objects.nonNull(offer.title())) {
            object.put("sorg:name", offer.title());
        }
        object.set("type", MAPPER.createArrayNode().add("Page").add("sorg:AboutPage"));
        object.set("ietf:item", item);
        root.set("object", object);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode service(String id, String name, String inbox) {
        var service = MAPPER.createObjectNode();
        service.put("id", id);
        if (Objects.nonNull(name)) {
            service.put("name", name);
        }
        if (Objects.nonNull(inbox)) {
            service.put("inbox", inbox);
        }
        service.put("type", "Service");
        return service;
    }

    public record IngestOffer(
        String notificationId,
        String originId,
        String originName,
        String originInbox,
        String targetId,
        String targetInbox,
        String objectId,
        String citeAs,
        String title,
        String itemUrl,
        String mediaType
    ) {
    }
}
