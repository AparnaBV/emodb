package com.bazaarvoice.emodb.common.jersey2;

import com.bazaarvoice.emodb.client2.EmoClient;
import com.bazaarvoice.emodb.client2.EmoResource;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import javax.ws.rs.client.Client;
import java.net.URI;

import static java.util.Objects.requireNonNull;


/**
 * EmoClient implementation that uses a Jersey client.
 */
public class Jersey2EmoClient implements EmoClient {

    private final Client _client;

    public Jersey2EmoClient(final Client client) {
        _client = requireNonNull(client, "client");
        if (!_client.getConfiguration().isRegistered(JacksonJsonProvider.class)) {
            _client.register(JacksonJsonProvider.class);
        }
    }

    @Override
    public EmoResource resource(URI uri) {
        return new Jersey2EmoResource(_client.target(uri));
    }
}
