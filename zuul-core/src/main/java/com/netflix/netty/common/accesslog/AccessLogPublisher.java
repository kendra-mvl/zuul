/*
 * Copyright 2018 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */

package com.netflix.netty.common.accesslog;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicStringListProperty;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccessLogPublisher {
    private static final char DELIM = '\t';
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final List<String> LOG_REQ_HEADERS = new DynamicStringListProperty(
                    "zuul.access.log.requestheaders",
                    "host,x-forwarded-for,x-forwarded-proto,x-forwarded-host,x-forwarded-port,user-agent")
            .get();
    private static final List<String> LOG_RESP_HEADERS =
            new DynamicStringListProperty("zuul.access.log.responseheaders", "server,via,content-type").get();
    private static final DynamicIntProperty URI_LENGTH_LIMIT =
            new DynamicIntProperty("zuul.access.log.uri.length.limit", Integer.MAX_VALUE);

    private final Logger logger;
    private final BiFunction<Channel, HttpRequest, String> requestIdProvider;

    private static final Logger LOG = LoggerFactory.getLogger(AccessLogPublisher.class);

    public AccessLogPublisher(String loggerName, BiFunction<Channel, HttpRequest, String> requestIdProvider) {
        this.logger = LoggerFactory.getLogger(loggerName);
        this.requestIdProvider = requestIdProvider;
    }

    public void log(
            Channel channel,
            HttpRequest request,
            HttpResponse response,
            LocalDateTime dateTime,
            Integer localPort,
            String remoteIp,
            Long durationNs,
            Long requestBodySize,
            Long responseBodySize) {
        StringBuilder sb = new StringBuilder(512);

        String dateTimeStr = dateTime != null ? dateTime.format(DATE_TIME_FORMATTER) : "-----T-:-:-";
        String remoteIpStr = (remoteIp != null && !remoteIp.isEmpty()) ? remoteIp : "-";
        String port = localPort != null ? localPort.toString() : "-";
        String method = request != null ? request.method().toString().toUpperCase(Locale.ROOT) : "-";
        String uri = request != null ? request.uri() : "-";
        if (uri.length() > URI_LENGTH_LIMIT.get()) {
            uri = uri.substring(0, URI_LENGTH_LIMIT.get());
        }
        String status = response != null ? String.valueOf(response.status().code()) : "-";

        String requestId = null;
        try {
            requestId = requestIdProvider.apply(channel, request);
        } catch (Exception ex) {
            LOG.error(
                    "requestIdProvider failed in AccessLogPublisher method={}, uri={}, status={}", method, uri, status);
        }
        requestId = requestId != null ? requestId : "-";

        // Convert duration to microseconds.
        String durationStr = (durationNs != null && durationNs > 0) ? String.valueOf(durationNs / 1000) : "-";

        String requestBodySizeStr = (requestBodySize != null && requestBodySize > 0) ? requestBodySize.toString() : "-";
        String responseBodySizeStr =
                (responseBodySize != null && responseBodySize > 0) ? responseBodySize.toString() : "-";

        // Build the line.
        sb.append(dateTimeStr)
                .append(DELIM)
                .append(remoteIpStr)
                .append(DELIM)
                .append(port)
                .append(DELIM)
                .append(method)
                .append(DELIM)
                .append(uri)
                .append(DELIM)
                .append(status)
                .append(DELIM)
                .append(durationStr)
                .append(DELIM)
                .append(responseBodySizeStr)
                .append(DELIM)
                .append(requestId)
                .append(DELIM)
                .append(requestBodySizeStr);

        if (request != null && request.headers() != null) {
            includeMatchingHeaders(sb, LOG_REQ_HEADERS, request.headers());
        }

        if (response != null && response.headers() != null) {
            includeMatchingHeaders(sb, LOG_RESP_HEADERS, response.headers());
        }

        // Write to logger.
        String access = sb.toString();
        logger.info(access);
        LOG.debug(access);
    }

    void includeMatchingHeaders(StringBuilder builder, List<String> requiredHeaders, HttpHeaders headers) {
        for (String headerName : requiredHeaders) {
            String value = headerAsString(headers, headerName);
            builder.append(DELIM).append('\"').append(value).append('\"');
        }
    }

    String headerAsString(HttpHeaders headers, String headerName) {
        List<String> values = headers.getAll(headerName);
        return values.isEmpty() ? "-" : String.join(",", values);
    }
}
