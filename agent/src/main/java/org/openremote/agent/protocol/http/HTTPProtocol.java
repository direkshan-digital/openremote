/*
 * Copyright 2017, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.agent.protocol.http;

import org.apache.http.client.utils.URIBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.specimpl.BuiltResponse;
import org.jboss.resteasy.specimpl.ResponseBuilderImpl;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.container.web.QueryParameterInjectorFilter;
import org.openremote.container.web.WebTargetBuilder;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.agent.Protocol;
import org.openremote.model.attribute.*;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TextUtil;
import org.openremote.model.value.ValueType;
import org.openremote.model.value.Values;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.openremote.agent.protocol.http.HTTPAgent.*;
import static org.openremote.container.concurrent.GlobalLock.withLock;
import static org.openremote.container.web.QueryParameterInjectorFilter.QUERY_PARAMETERS_PROPERTY;
import static org.openremote.container.web.WebTargetBuilder.createClient;
import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

/**
 * This is a HTTP client protocol for communicating with HTTP servers; it uses the {@link WebTargetBuilder} factory to
 * generate JAX-RS {@link javax.ws.rs.client.WebTarget}s that can be used to make arbitrary calls to endpoints on a HTTP
 * server but it can also be extended and used as a JAX-RS client proxy.
 * <h1>Response filtering</h1>
 * <p>
 * Any {@link Attribute} whose value is to be set by the HTTP server response (i.e. it has an {@link
 * HTTPAgent#META_REQUEST_POLLING_MILLIS} {@link MetaItem}) can use the standard {@link Agent#META_VALUE_FILTERS} in
 * order to filter the received HTTP response.
 * <p>
 * <b>NOTE: if an exception is thrown during the request that means no response is returned then this is treated as if
 * a 500 response has been received</b>
 * <h1>Dynamic value injection</h1>
 * This allows the {@link HTTPAgent#META_REQUEST_PATH} and/or {@link Agent#META_WRITE_VALUE} to contain the linked
 * {@link Attribute} value when sending requests. To dynamically inject the attribute value use
 * {@value Protocol#DYNAMIC_VALUE_PLACEHOLDER} as a placeholder and this will be dynamically replaced at request time.
 * <h2>Path example</h2>
 * {@link HTTPAgent#META_REQUEST_PATH} = "volume/set/{$value}" and request received to set attribute value to 100. Actual path
 * used for the request = "volume/set/100"
 * <h2>Query parameter example</h2>
 * {@link HTTPAgent#META_REQUEST_QUERY_PARAMETERS} =
 * <blockquote><pre>
 * {@code
 * {
 *     param1: ["val1", "val2"],
 *     param2: 12232,
 *     param3: "{$value}"
 * }
 * }
 * </pre></blockquote>
 * Request received to set attribute value to true. Actual query parameters injected into the request =
 * "param1=val1&param1=val2&param2=12232&param3=true"
 * <h2>Body examples</h2>
 * {@link Agent#META_WRITE_VALUE} = '<?xml version="1.0" encoding="UTF-8"?>{$value}</xml>' and request received to set attribute value to 100. Actual body
 * used for the request = "{volume: 100}"
 * <p>
 * {@link Agent#META_WRITE_VALUE} = '{myObject: "{$value}"}' and request received to set attribute value to:
 * <blockquote><pre>
 * {@code
 * {
 *   prop1: true,
 *   prop2: "test",
 *   prop3: {
 *       prop4: 1234.4223
 *   }
 * }
 * }
 * </pre></blockquote>
 * Actual body used for the request = "{myObject: {prop1: true, prop2: "test", prop3: {prop4: 1234.4223}}}"
 */
public class HTTPProtocol extends AbstractProtocol<HTTPAgent, HTTPAgentLink> {

    public static class HttpClientRequest {

        public String method;
        public MultivaluedMap<String, Object> headers;
        public MultivaluedMap<String, String> queryParameters;
        public String path;
        protected String contentType;
        protected WebTarget client;
        protected WebTarget requestTarget;
        protected boolean dynamicQueryParameters;
        protected boolean pagingEnabled;

        public HttpClientRequest(WebTarget client,
                                 String path,
                                 String method,
                                 MultivaluedMap<String, Object> headers,
                                 MultivaluedMap<String, String> queryParameters,
                                 boolean pagingEnabled,
                                 String contentType) {

            if (!TextUtil.isNullOrEmpty(path)) {
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
            }

            this.client = client;
            this.path = path;
            this.method = method != null ? method : HttpMethod.GET;
            this.headers = headers;
            this.queryParameters = queryParameters;
            this.pagingEnabled = pagingEnabled;
            this.contentType = contentType != null ? contentType : DEFAULT_CONTENT_TYPE;
            dynamicQueryParameters = queryParameters != null
                    && queryParameters
                    .entrySet()
                    .stream()
                    .anyMatch(paramNameAndValues ->
                            paramNameAndValues.getValue() != null
                                    && paramNameAndValues.getValue()
                                    .stream()
                                    .anyMatch(val -> val.contains(DYNAMIC_VALUE_PLACEHOLDER)));

            boolean dynamicPath = !TextUtil.isNullOrEmpty(path) && path.contains(DYNAMIC_VALUE_PLACEHOLDER);
            if (!dynamicPath) {
                requestTarget = createRequestTarget(path);
            }
        }

        protected WebTarget createRequestTarget(String path) {
            WebTarget requestTarget = client.path(path == null ? "" : path);

            if (queryParameters != null) {
                @SuppressWarnings("unchecked")
                MultivaluedMap<String, String> existingParams = (MultivaluedMap<String, String>) requestTarget.getConfiguration().getProperty(QUERY_PARAMETERS_PROPERTY);
                existingParams = existingParams != null ? new MultivaluedHashMap<String, String>(existingParams) : new MultivaluedHashMap<String, String>();
                queryParameters.forEach(existingParams::addAll);
                requestTarget.property(QUERY_PARAMETERS_PROPERTY, existingParams);
            }

            return requestTarget;
        }

        protected Invocation.Builder getRequestBuilder(String value) {
            Invocation.Builder requestBuilder;

            if (requestTarget != null) {
                requestBuilder = requestTarget.request();
            } else {
                // This means that the path is dynamic
                String path = this.path.replaceAll(DYNAMIC_VALUE_PLACEHOLDER_REGEXP, value);
                requestBuilder = createRequestTarget(path).request();
            }

            if (headers != null) {
                requestBuilder.headers(headers);
            }

            if (dynamicQueryParameters) {
                requestBuilder.property(QueryParameterInjectorFilter.DYNAMIC_VALUE_PROPERTY, value);
            }

            return requestBuilder;
        }

        protected Invocation buildInvocation(Invocation.Builder requestBuilder, String value) {
            Invocation invocation;

            if (dynamicQueryParameters) {
                requestBuilder.property(QueryParameterInjectorFilter.DYNAMIC_VALUE_PROPERTY, value);
            }

            if (method != null && !HttpMethod.GET.equals(method) && value != null) {
                invocation = requestBuilder.build(method, Entity.entity(value, contentType));
            } else {
                invocation = requestBuilder.build(method);
            }

            return invocation;
        }

        public Response invoke(String value) {
            Invocation.Builder requestBuilder = getRequestBuilder(value);
            Invocation invocation = buildInvocation(requestBuilder, value);
            return invocation.invoke();
        }

        @Override
        public String toString() {
            return client.getUri() + (path != null ? "/" + path : "");
        }
    }

    protected static class PagingResponse extends BuiltResponse {

        private PagingResponse(int status, Headers<Object> metadata, Object entity, Annotation[] entityAnnotations) {
            super(status, metadata, entity, entityAnnotations);
        }

        public static ResponseBuilder fromResponse(Response response) {
            ResponseBuilder b = new PagingResponseBuilder().status(response.getStatus());

            for (String headerName : response.getHeaders().keySet()) {
                List<Object> headerValues = response.getHeaders().get(headerName);
                for (Object headerValue : headerValues) {
                    b.header(headerName, headerValue);
                }
            }
            return b;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T readEntity(Class<T> type) {
            return (T) entity;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T readEntity(Class<T> type, Type genericType, Annotation[] anns) {
            return (T) entity;
        }
    }

    protected static class PagingResponseBuilder extends ResponseBuilderImpl {
        @Override
        public Response build() {
            if (status == -1 && entity == null) status = 204;
            else if (status == -1) status = 200;
            return new PagingResponse(status, metadata, entity, entityAnnotations);
        }
    }

    public static final String PROTOCOL_DISPLAY_NAME = "HTTP Client";
    public static final String DEFAULT_HTTP_METHOD = HttpMethod.GET;
    public static final String DEFAULT_CONTENT_TYPE = MediaType.TEXT_PLAIN;
    protected static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, HTTPProtocol.class);
    public static int MIN_POLLING_MILLIS = 5000;

    protected ResteasyWebTarget webTarget;
    protected final Map<AttributeRef, HttpClientRequest> requestMap = new HashMap<>();
    protected final Map<AttributeRef, ScheduledFuture<?>> pollingMap = new HashMap<>();
    protected final Map<AttributeRef, Set<AttributeRef>> pollingLinkedAttributeMap = new HashMap<>();
    protected static ResteasyClient client;

    static {
        client = createClient(org.openremote.container.Container.EXECUTOR_SERVICE);
    }

    public HTTPProtocol(HTTPAgent agent) {
        super(agent);
    }

    @Override
    protected void doStop(Container container) {
        pollingMap.forEach((attributeRef, scheduledFuture) -> scheduledFuture.cancel(true));
        pollingMap.clear();
        requestMap.clear();
    }

    @Override
    protected void doStart(Container container) throws Exception {

        String baseUri = agent.getBaseURI().orElseThrow(() ->
                        new IllegalArgumentException("Missing or invalid base URI attribute: " + this));

        if (baseUri.endsWith("/")) {
            baseUri = baseUri.substring(0, baseUri.length() - 1);
        }

        URI uri;

        try {
            uri = new URIBuilder(baseUri).build();
        } catch (URISyntaxException e) {
            LOG.log(Level.SEVERE, "Invalid URI", e);
            throw e;
        }

        /* We're going to fail hard and fast if optional meta items are incorrectly configured */

        Optional<OAuthGrant> oAuthGrant = agent.getOAuthGrant();
        Optional<UsernamePassword> usernameAndPassword = agent.getUsernamePassword();
        boolean followRedirects = agent.getFollowRedirects().orElse(false);
        Optional<ValueType.MultivaluedStringMap> headers = agent.getRequestHeaders();
        Optional<ValueType.MultivaluedStringMap> queryParams = agent.getRequestQueryParameters();

        Integer readTimeout = agent.getRequestTimeoutMillis().orElse(null);

        WebTargetBuilder webTargetBuilder;
        if (readTimeout != null) {
            webTargetBuilder = new WebTargetBuilder(WebTargetBuilder.createClient(executorService, WebTargetBuilder.CONNECTION_POOL_SIZE, readTimeout.longValue(), null), uri);
        } else {
            webTargetBuilder = new WebTargetBuilder(client, uri);
        }

        if (oAuthGrant.isPresent()) {
            LOG.info("Adding OAuth");
            webTargetBuilder.setOAuthAuthentication(oAuthGrant.get());
        } else {
            usernameAndPassword.ifPresent(userPass -> {
                LOG.info("Adding Basic Authentication");
                webTargetBuilder.setBasicAuthentication(userPass.getUsername(),
                        userPass.getPassword());
            });
        }

        headers.ifPresent(webTargetBuilder::setInjectHeaders);
        queryParams.ifPresent(webTargetBuilder::setInjectQueryParameters);
        webTargetBuilder.followRedirects(followRedirects);

        LOG.fine("Creating web target client '" + baseUri + "'");
        webTarget = webTargetBuilder.build();

        setConnectionStatus(ConnectionStatus.CONNECTED);
    }

    @Override
    protected void doLinkAttribute(String assetId, Attribute<?> attribute, HTTPAgentLink agentLink) {
        AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());

        String method = agentLink.getMethod().map(Enum::name).orElse(DEFAULT_HTTP_METHOD);
        String path = agentLink.getPath().orElse(null);
        String contentType = agentLink.getContentType().orElse(null);

        Map<String, List<String>> headers = agentLink.getHeaders().orElse(null);
        Map<String, List<String>> queryParams = agentLink.getQueryParameters().orElse(null);
        Integer pollingMillis = agentLink.getPollingMillis().map(millis -> Math.max(millis, MIN_POLLING_MILLIS)).orElse(null);
        boolean pagingEnabled = agentLink.getPagingMode().orElse(false);
        String pollingAttribute = agentLink.getPollingAttribute().orElse(null);

        if (!TextUtil.isNullOrEmpty(pollingAttribute)) {
            synchronized (pollingLinkedAttributeMap) {
                AttributeRef pollingSourceRef = new AttributeRef(attributeRef.getId(), pollingAttribute);
                pollingLinkedAttributeMap.compute(pollingSourceRef, (ref, links) -> {
                    if (links == null) {
                        links = new HashSet<>();
                    }
                    links.add(attributeRef);

                    return links;
                });
            }
        }

        String body = agentLink.getWriteValue().orElse(null);

        addHttpClientRequest(
            attributeRef,
            path,
            method,
            body,
            headers != null ? WebTargetBuilder.mapToMultivaluedMap(headers, new MultivaluedHashMap<String, Object>()) : null,
            queryParams != null ? WebTargetBuilder.mapToMultivaluedMap(queryParams, new MultivaluedHashMap<String, String>()) : null,
            pagingEnabled,
            contentType,
            pollingMillis);
    }

    @Override
    protected void doUnlinkAttribute(String assetId, Attribute<?> attribute, HTTPAgentLink agentLink) {
        AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
        requestMap.remove(attributeRef);
        cancelPolling(attributeRef);

        agentLink.getPollingMillis().ifPresent(pollingAttribute -> {
            synchronized (pollingLinkedAttributeMap) {
                pollingLinkedAttributeMap.remove(attributeRef);
                pollingLinkedAttributeMap.values().forEach(links -> links.remove(attributeRef));
            }
        });
    }

    @Override
    protected void doLinkedAttributeWrite(Attribute<?> attribute, HTTPAgentLink agentLink, AttributeEvent event, Object processedValue) {

        HttpClientRequest request = requestMap.get(event.getAttributeRef());

        if (request != null) {

            executeAttributeWriteRequest(request,
                    processedValue,
                    response -> onAttributeWriteResponse(request, response));
        } else {
            LOG.finest("Ignoring attribute write request as either attribute or agent is not linked: " + event);
        }
    }



    @Override
    public String getProtocolName() {
        return PROTOCOL_DISPLAY_NAME;
    }

    @Override
    public String getProtocolInstanceUri() {
        return webTarget != null ? webTarget.getUri().toString() : agent.getBaseURI().orElse("");
    }

    protected void addHttpClientRequest(AttributeRef attributeRef,
                                        String path,
                                        String method,
                                        String body,
                                        MultivaluedMap<String, Object> headers,
                                        MultivaluedMap<String, String> queryParams,
                                        boolean pagingEnabled,
                                        String contentType,
                                        Integer pollingMillis) {

        if (client == null) {
            LOG.warning("Client is undefined: " + this);
            return;
        }

        HttpClientRequest clientRequest = buildClientRequest(
            path,
            method,
            headers,
            queryParams,
            pagingEnabled,
            contentType);

        LOG.fine("Creating HTTP request for attributeRef '" + clientRequest + "': " + attributeRef);

        requestMap.put(attributeRef, clientRequest);

        Optional.ofNullable(pollingMillis).ifPresent(seconds -> {
            pollingMap.put(attributeRef, schedulePollingRequest(
                attributeRef,
                clientRequest,
                body,
                seconds));
        });
    }

    protected HttpClientRequest buildClientRequest(String path, String method, MultivaluedMap<String, Object> headers, MultivaluedMap<String, String> queryParams, boolean pagingEnabled, String contentType) {
        return new HttpClientRequest(
                webTarget,
                path,
                method,
                headers,
                queryParams,
                pagingEnabled,
                contentType);
    }

    protected ScheduledFuture<?> schedulePollingRequest(AttributeRef attributeRef,
                                                     HttpClientRequest clientRequest,
                                                     String body,
                                                     int pollingMillis) {

        LOG.fine("Scheduling polling request '" + clientRequest + "' to execute every " + pollingMillis + " ms for attribute: " + attributeRef);

        return executorService.scheduleWithFixedDelay(() ->
                executePollingRequest(clientRequest, body, response -> {
                    try {
                        onPollingResponse(
                            clientRequest,
                            response,
                            attributeRef);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, prefixLogMessage("Exception thrown whilst processing polling response [" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) + "]: " + clientRequest.requestTarget.getUriBuilder().build().toString()));
                    }
                }), 0, pollingMillis, TimeUnit.MILLISECONDS);
    }

    protected void executePollingRequest(HttpClientRequest clientRequest, String body, Consumer<Response> responseConsumer) {
        Response originalResponse = null, lastResponse = null;
        List<String> entities = new ArrayList<>();

        try {
            originalResponse = clientRequest.invoke(body);
            if (clientRequest.pagingEnabled) {
                lastResponse = originalResponse;
                entities.add(lastResponse.readEntity(String.class));
                while ((lastResponse = executePagingRequest(clientRequest, lastResponse)) != null) {
                    entities.add(lastResponse.readEntity(String.class));
                    lastResponse.close();
                }
                originalResponse = PagingResponse.fromResponse(originalResponse).entity(entities).build();
            }

            responseConsumer.accept(originalResponse);
        } catch (Exception e) {
            LOG.log(Level.WARNING, prefixLogMessage("Exception thrown whilst doing polling request [" + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()) + "]: " + clientRequest.requestTarget.getUriBuilder().build().toString()));
        } finally {
            if (originalResponse != null) {
                originalResponse.close();
            }
            if (lastResponse != null) {
                lastResponse.close();
            }
        }
    }

    protected Response executePagingRequest(HttpClientRequest clientRequest, Response response) {
        if (response.hasLink("next")) {
            URI nextUrl = response.getLink("next").getUri();
            return clientRequest.client.register(new PaginationFilter(nextUrl)).request().build(clientRequest.method).invoke();
        }
        return null;
    }

    protected void executeAttributeWriteRequest(HttpClientRequest clientRequest,
                                                Object attributeValue,
                                                Consumer<Response> responseConsumer) {
        String valueStr = attributeValue == null ? null : Values.convert(attributeValue, String.class);
        Response response = null;

        try {
            response = clientRequest.invoke(valueStr);
            responseConsumer.accept(response);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Exception thrown whilst doing attribute write request", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    protected void onPollingResponse(HttpClientRequest request,
                                     Response response,
                                     AttributeRef attributeRef) {

        int responseCode = response != null ? response.getStatus() : 500;
        Object value = null;

        if (response != null && response.hasEntity() && response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            try {
                value = response.readEntity(String.class);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error occurred whilst trying to read response body", e);
                response.close();
            }
        } else {
            LOG.fine(prefixLogMessage("Request returned an un-successful response code (" + responseCode + "):" + request.requestTarget.getUriBuilder().build().toString()));
            return;
        }

        if (attributeRef != null) {
            updateLinkedAttribute(new AttributeState(attributeRef, value));

            // Look for any attributes that also want to use this polling response
            synchronized (pollingLinkedAttributeMap) {
                Set<AttributeRef> linkedRefs = pollingLinkedAttributeMap.get(attributeRef);
                if (linkedRefs != null) {
                    Object finalValue = value;
                    linkedRefs.forEach(ref -> updateLinkedAttribute(new AttributeState(ref, finalValue)));
                }
            }
        }
    }

    protected void onAttributeWriteResponse(HttpClientRequest request,
                                            Response response) {

        if (response != null && response.hasEntity() && response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            LOG.fine(prefixLogMessage("Attribute write request returned an unsuccessful response code (" + response.getStatus() + "): " + request.requestTarget.getUriBuilder().build().toString()));
        }
    }

    protected void cancelPolling(AttributeRef attributeRef) {
        withLock(getProtocolName() + "::cancelPolling", () -> {
            ScheduledFuture<?> pollTask = pollingMap.remove(attributeRef);
            if (pollTask != null) {
                pollTask.cancel(false);
            }
        });
    }

}
