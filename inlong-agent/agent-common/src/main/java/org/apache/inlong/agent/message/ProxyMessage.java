/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.message;

import static org.apache.inlong.agent.constant.CommonConstants.PROXY_KEY_GROUP_ID;
import static org.apache.inlong.agent.constant.CommonConstants.PROXY_KEY_STREAM_ID;

import java.util.Map;
import org.apache.inlong.agent.plugin.Message;

/**
 * Bus message with body, header, inlongGroupId and inlongStreamId.
 */
public class ProxyMessage implements Message {

    private static final String DEFAULT_INLONG_STREAM_ID = "__";

    private final byte[] body;
    private final Map<String, String> header;
    private final String inlongGroupId;
    private final String inlongStreamId;

    public ProxyMessage(byte[] body, Map<String, String> header) {
        this.body = body;
        this.header = header;
        this.inlongGroupId = header.get(PROXY_KEY_GROUP_ID);
        this.inlongStreamId = header.getOrDefault(PROXY_KEY_STREAM_ID, DEFAULT_INLONG_STREAM_ID);
    }

    /**
     * Get first line of body list
     *
     * @return first line of body list
     */
    @Override
    public byte[] getBody() {
        return body;
    }

    /**
     * Get header of message
     *
     * @return header
     */
    @Override
    public Map<String, String> getHeader() {
        return header;
    }

    public String getInlongGroupId() {
        return inlongGroupId;
    }

    public String getInlongStreamId() {
        return inlongStreamId;
    }

    public static ProxyMessage parse(Message message) {
        return new ProxyMessage(message.getBody(), message.getHeader());
    }
}
