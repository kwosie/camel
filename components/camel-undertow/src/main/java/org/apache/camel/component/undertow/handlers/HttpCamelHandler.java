/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.undertow.handlers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.MimeMappings;
import io.undertow.util.StatusCodes;
import org.apache.camel.Exchange;
import org.apache.camel.TypeConverter;
import org.apache.camel.component.undertow.ExchangeHeaders;
import org.apache.camel.component.undertow.UndertowConsumer;
import org.apache.camel.component.undertow.UndertowConsumerResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom handler to process incoming HTTP request and prepare them
 * to be used in the Camel route.
 *
 * This class can be considered part of UndertowConsumer implementation.
 */
public class HttpCamelHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(HttpCamelHandler.class);
    private UndertowConsumerResolver resolver = new UndertowConsumerResolver();
    private ConcurrentMap<String, UndertowConsumer> consumers = new ConcurrentHashMap<String, UndertowConsumer>();

    @Override
    public void handleRequest(HttpServerExchange httpExchange) throws Exception {
        UndertowConsumer consumer = resolver.resolve(httpExchange, consumers);

        if (consumer == null) {
            LOG.debug("Unable to resolve consumer matching path {}", httpExchange.getRequestPath());
            new NotFoundHandler().handleRequest(httpExchange);
            return;
        }

        HttpString requestMethod = httpExchange.getRequestMethod();

        if (Methods.OPTIONS.equals(requestMethod) && !consumer.getEndpoint().isOptionsEnabled()) {
            String allowedMethods;
            if (consumer.getEndpoint().getHttpMethodRestrict() != null) {
                allowedMethods = "OPTIONS," + consumer.getEndpoint().getHttpMethodRestrict();
            } else {
                allowedMethods = "GET,HEAD,POST,PUT,DELETE,TRACE,OPTIONS,CONNECT,PATCH";
            }
            //return list of allowed methods in response headers
            httpExchange.setStatusCode(StatusCodes.OK);
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_TYPE, MimeMappings.DEFAULT_MIME_MAPPINGS.get("txt"));
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_LENGTH, 0);
            httpExchange.getResponseHeaders().put(Headers.ALLOW, allowedMethods);
            httpExchange.getResponseSender().close();
            return;
        }

        //reject if the method is not allowed
        if (consumer.getEndpoint().getHttpMethodRestrict() != null
            && !consumer.getEndpoint().getHttpMethodRestrict().contains(requestMethod.toString())) {
            httpExchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_TYPE, MimeMappings.DEFAULT_MIME_MAPPINGS.get("txt"));
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_LENGTH, 0);
            httpExchange.getResponseSender().close();
            return;
        }

        //perform blocking operation on exchange
        if (httpExchange.isInIoThread()) {
            httpExchange.dispatch(this);
            return;
        }

        //create new Exchange
        //binding is used to extract header and payload(if available)
        Exchange camelExchange = consumer.getEndpoint().createExchange(httpExchange);

        //Unit of Work to process the Exchange
        consumer.createUoW(camelExchange);
        try {
            consumer.getProcessor().process(camelExchange);
        } catch (Exception e) {
            consumer.getExceptionHandler().handleException(e);
        } finally {
            consumer.doneUoW(camelExchange);
        }

        Object body = getResponseBody(httpExchange, camelExchange, consumer);
        TypeConverter tc = consumer.getEndpoint().getCamelContext().getTypeConverter();

        if (body == null) {
            LOG.trace("No payload to send as reply for exchange: " + camelExchange);
            httpExchange.getResponseHeaders().put(ExchangeHeaders.CONTENT_TYPE, MimeMappings.DEFAULT_MIME_MAPPINGS.get("txt"));
            httpExchange.getResponseSender().send("No response available");
        } else {
            ByteBuffer bodyAsByteBuffer = tc.convertTo(ByteBuffer.class, body);
            httpExchange.getResponseSender().send(bodyAsByteBuffer);
        }
        httpExchange.getResponseSender().close();
    }

    private Object getResponseBody(HttpServerExchange httpExchange, Exchange camelExchange, UndertowConsumer consumer) throws IOException {
        Object result;
        if (camelExchange.hasOut()) {
            result = consumer.getEndpoint().getUndertowHttpBinding().toHttpResponse(httpExchange, camelExchange.getOut());
        } else {
            result = consumer.getEndpoint().getUndertowHttpBinding().toHttpResponse(httpExchange, camelExchange.getIn());
        }
        return result;
    }

    public void connectConsumer(UndertowConsumer consumer) {
        consumers.put(consumer.getEndpoint().getEndpointUri(), consumer);
    }
}
